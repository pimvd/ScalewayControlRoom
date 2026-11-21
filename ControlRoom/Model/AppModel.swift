import ControlRoomKit
import Foundation

struct KeyProblem: Identifiable, Hashable {
    enum Area { case instances, serverless, other }
    var keyID: String
    var area: Area
    var message: String
    var id: String { "\(keyID)-\(area)" }
}

/// The app's state: the keys, what they can see, and the actions on it.
@MainActor
@Observable
final class AppModel {
    private(set) var keys: [APIKey] = []
    private(set) var loaded = false
    private(set) var servers: [Listed<Server>] = []
    private(set) var serverless: [Listed<ServerlessApp>] = []
    /// Everything else, loaded when the Other tab asks for it.
    private(set) var resources: [Listed<Resource>] = []
    private(set) var projectNames: [String: String] = [:]
    private(set) var loadingResources = false
    private(set) var problems: [KeyProblem] = []
    /// "zone/commercialType" → price per hour.
    private(set) var prices: [String: Double] = [:]
    private(set) var refreshing = false

    private let store = KeyStore()
    private var pricedZones = Set<Zone>()
    private var poll: Task<Void, Never>?

    func label(of keyID: String) -> String {
        keys.first { $0.id == keyID }?.label ?? ""
    }

    func price(of server: Server) -> Double? {
        prices["\(server.zone.rawValue)/\(server.commercialType)"]
    }

    /// Where a resource's logs and metrics are, which keys see it, and the
    /// server when it is an instance (for the setup guide).
    struct CockpitResource {
        var target: CockpitTarget
        var keyIDs: [String]
        var server: Server?
    }

    func cockpitResource(_ kind: LogKind, _ id: String) -> CockpitResource? {
        switch kind {
        case .instance:
            guard let listed = servers.first(where: { $0.id == id }) else { return nil }
            let server = listed.item
            return CockpitResource(target: CockpitTarget(projectID: server.project, region: server.zone.region, resourceID: id),
                                   keyIDs: listed.keyIDs, server: server)
        case .serverless:
            guard let listed = serverless.first(where: { $0.id == id }) else { return nil }
            return CockpitResource(target: listed.item.cockpitTarget, keyIDs: listed.keyIDs, server: nil)
        case .resource:
            guard let listed = resources.first(where: { $0.id == id }), let target = listed.item.cockpitTarget else { return nil }
            return CockpitResource(target: target, keyIDs: listed.keyIDs, server: nil)
        }
    }

    /// Lists every other resource of every key: some 170 requests per key,
    /// so only when the Other tab is shown or pulled, not with every refresh.
    func refreshResources() async {
        guard !loadingResources else { return }
        loadingResources = true
        defer { loadingResources = false }
        let current = keys
        let results = await withTaskGroup(of: (keyID: String, resources: [Resource], problem: String?, names: [String: String]).self) { group in
            for key in current {
                group.addTask {
                    let api = client(for: key)
                    async let names = (try? await api.projectNames()) ?? [:]
                    let listed = await listResources(api)
                    return (key.id, listed.resources, listed.problem, await names)
                }
            }
            var all: [String: (keyID: String, resources: [Resource], problem: String?, names: [String: String])] = [:]
            for await result in group { all[result.keyID] = result }
            return current.compactMap { all[$0.id] }
        }
        resources = mergeByID(results.map { (keyID: $0.keyID, items: $0.resources) })
        projectNames = results.reduce(into: [:]) { names, result in names.merge(result.names) { first, _ in first } }
        problems = problems.filter { $0.area != .other }
            + results.compactMap { result in result.problem.map { KeyProblem(keyID: result.keyID, area: .other, message: $0) } }
    }

    // MARK: Keys

    func load() {
        keys = (try? store.load()) ?? []
        loaded = true
    }

    func add(_ key: APIKey) async throws {
        let next = keys.filter { $0.id != key.id } + [key]
        try store.save(next)
        keys = next
        await refresh()
    }

    func remove(_ key: APIKey) async {
        let next = keys.filter { $0.id != key.id }
        try? store.save(next, removed: [key])
        keys = next
        await refresh()
    }

    // MARK: Listing

    private struct Listing: Sendable {
        var keyID: String
        var servers: [Server]
        var serversProblem: String?
        var apps: [ServerlessApp]
        var appsProblem: String?
    }

    func refresh() async {
        guard !refreshing else { return }
        refreshing = true
        defer { refreshing = false }
        let current = keys
        let listings = await withTaskGroup(of: Listing.self) { group in
            for key in current { group.addTask { await Self.list(key) } }
            var all: [String: Listing] = [:]
            for await listing in group { all[listing.keyID] = listing }
            return current.compactMap { all[$0.id] }
        }
        servers = sortServers(mergeByID(listings.map { (keyID: $0.keyID, items: $0.servers) }))
        serverless = mergeByID(listings.map { (keyID: $0.keyID, items: $0.apps) })
            .sorted { $0.item.name.localizedStandardCompare($1.item.name) == .orderedAscending }
        // The Other tab's problems come from its own loading; keep them.
        problems = listings.flatMap { listing in
            [listing.serversProblem.map { KeyProblem(keyID: listing.keyID, area: .instances, message: $0) },
             listing.appsProblem.map { KeyProblem(keyID: listing.keyID, area: .serverless, message: $0) }]
                .compactMap { $0 }
        } + problems.filter { $0.area == .other && current.map(\.id).contains($0.keyID) }
        loadPrices()
        pollWhileBusy()
    }

    private nonisolated static func list(_ key: APIKey) async -> Listing {
        let api = client(for: key)
        async let instances = listAll(Zone.allCases, quietForbidden: false) { try await api.listServers(zone: $0) }
        // A key that may not see serverless has none; say why only when it may
        // not anywhere, rather than leave an unexplained empty list.
        async let apps = listAll(Region.allCases, quietForbidden: true,
                                 forbiddenEverywhere: "This key may not list serverless apps. Add AllProductsReadOnly to its policy.") {
            try await api.listServerless(region: $0)
        }
        let (i, a) = await (instances, apps)
        return Listing(keyID: key.id, servers: i.items, serversProblem: i.problem, apps: a.items, appsProblem: a.problem)
    }

    /// Lists every place in parallel. Places that fail are named in the problem.
    private nonisolated static func listAll<Place: RawRepresentable & Sendable, Item: Sendable>(
        _ places: [Place], quietForbidden: Bool, forbiddenEverywhere: String? = nil,
        _ list: @escaping @Sendable (Place) async throws -> [Item]
    ) async -> (items: [Item], problem: String?) where Place.RawValue == String {
        let results = await withTaskGroup(of: (Int, Result<[Item], Error>).self) { group in
            for (index, place) in places.enumerated() {
                group.addTask {
                    do { return (index, .success(try await list(place))) } catch { return (index, .failure(error)) }
                }
            }
            var all = [Result<[Item], Error>?](repeating: nil, count: places.count)
            for await (index, result) in group { all[index] = result }
            return all.compactMap { $0 }
        }
        var items: [Item] = []
        var failed: [(place: Place, error: Error)] = []
        var forbidden = 0
        var offered = places.count
        for (place, result) in zip(places, results) {
            switch result {
            case .success(let found): items += found
            case .failure(let error):
                let apiError = error as? APIError
                // A region without the product, or a key that may not see it
                // (where asked), is simply empty.
                if apiError?.isUnknownService == true {
                    offered -= 1
                    continue
                }
                if quietForbidden && apiError?.isForbidden == true {
                    forbidden += 1
                    continue
                }
                failed.append((place, error))
            }
        }
        // Every place that offers the product said "may not".
        if let forbiddenEverywhere, forbidden > 0, forbidden == offered { return (items, forbiddenEverywhere) }
        guard let first = failed.first else { return (items, nil) }
        let message = first.error.localizedDescription
        if failed.count == places.count { return (items, message) }
        return (items, "Could not load \(failed.map(\.place.rawValue).joined(separator: ", ")): \(message)")
    }

    /// Prices never change while the app runs: fetch each zone once.
    private func loadPrices() {
        for listed in servers where !pricedZones.contains(listed.item.zone) {
            let zone = listed.item.zone
            pricedZones.insert(zone)
            Task {
                if let zonePrices = try? await withKeys(listed.keyIDs, { try await $0.hourlyPrices(zone: zone) }) {
                    for (type, price) in zonePrices { prices["\(zone.rawValue)/\(type)"] = price }
                } else {
                    pricedZones.remove(zone)
                }
            }
        }
    }

    /// While a server is starting or stopping, keep checking until it settles.
    private func pollWhileBusy() {
        poll?.cancel()
        guard servers.contains(where: { $0.item.state.isBusy }) else { return }
        poll = Task {
            try? await Task.sleep(for: .seconds(4))
            if !Task.isCancelled { await refresh() }
        }
    }

    // MARK: Actions

    /// Runs `call` with the first of `keyIDs` that is allowed to: a read-only
    /// key next to a full one for the same resource is not a reason to fail.
    func withKeys<T: Sendable>(_ keyIDs: [String], _ call: @Sendable (ScalewayClient) async throws -> T) async throws -> T {
        var lastError: Error = APIError(status: 0, message: "No key can reach this resource.")
        for id in keyIDs {
            guard let key = keys.first(where: { $0.id == id }) else { continue }
            do {
                return try await call(client(for: key))
            } catch {
                lastError = error
                if (error as? APIError)?.isForbidden != true { break }
            }
        }
        throw lastError
    }

    func act(_ listed: Listed<Server>, _ action: ServerAction) async throws {
        let server = listed.item
        try await withKeys(listed.keyIDs) { try await $0.act(zone: server.zone, serverID: server.id, action: action) }
        if action != .reboot, let index = servers.firstIndex(where: { $0.id == server.id }) {
            servers[index].item.state = action == .poweroff ? .stopping : .starting
        }
        await refresh()
    }
}
