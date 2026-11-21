import Foundation

/// Any Scaleway resource the Other tab lists: databases, volumes, IPs,
/// domains, secrets and the rest. Each product is one entry in `Products.all`;
/// its list endpoint is read as plain JSON, so a product needs no code of its own.
public struct Resource: Identifiable, Hashable, Sendable {
    public var id: String
    public var name: String
    /// The product, as `Product.type` names it.
    public var type: String
    public var status: String
    /// A zone, a region, or "global".
    public var location: String
    public var projectID: String

    public init(id: String, name: String, type: String, status: String, location: String, projectID: String) {
        self.id = id
        self.name = name
        self.type = type
        self.status = status
        self.location = location
        self.projectID = projectID
    }

    /// The region of a regional or zonal resource; global ones have none.
    public var region: Region? { Region(rawValue: location) ?? Zone(rawValue: location)?.region }

    /// Scaleway labels the logs and metrics of its managed products with the
    /// resource's ID, in the project's Cockpit in the resource's region.
    public var cockpitTarget: CockpitTarget? {
        region.map { CockpitTarget(projectID: projectID, region: $0, resourceID: id) }
    }
}

/// One product's list endpoint.
public struct Product: Hashable, Sendable {
    public enum Scope: Sendable { case regional, zonal, global }

    public var type: String
    /// With `{place}` for the region or zone.
    public var path: String
    /// The list field of the response.
    public var field: String
    public var scope: Scope

    public init(_ type: String, _ path: String, _ field: String, _ scope: Scope) {
        self.type = type
        self.path = path
        self.field = field
        self.scope = scope
    }

    /// Where to ask: every region or zone, or once for a global product.
    public var places: [String?] {
        switch scope {
        case .regional: Region.allCases.map(\.rawValue)
        case .zonal: Zone.allCases.map(\.rawValue)
        case .global: [nil]
        }
    }

    public func path(at place: String?) -> String {
        path.replacingOccurrences(of: "{place}", with: place ?? "")
    }
}

public enum Products {
    /// Every product with resources, besides instances and serverless (their
    /// own tabs). Object Storage is missing: buckets are listed through the
    /// S3 API, which needs an access key the app does not have.
    public static let all: [Product] = [
        Product("Managed Database", "rdb/v1/regions/{place}/instances", "instances", .regional),
        Product("Redis", "redis/v1/zones/{place}/clusters", "clusters", .zonal),
        Product("MongoDB", "mongodb/v1/regions/{place}/instances", "instances", .regional),
        Product("Serverless SQL Database", "serverless-sqldb/v1alpha1/regions/{place}/databases", "databases", .regional),
        Product("Kafka", "kafka/v1alpha1/regions/{place}/clusters", "clusters", .regional),
        Product("OpenSearch", "searchdb/v1alpha1/regions/{place}/deployments", "deployments", .regional),
        Product("Data Warehouse", "datawarehouse/v1beta1/regions/{place}/deployments", "deployments", .regional),
        Product("Kubernetes", "k8s/v1/regions/{place}/clusters", "clusters", .regional),
        Product("Elastic Metal", "baremetal/v1/zones/{place}/servers", "servers", .zonal),
        Product("Apple Silicon", "apple-silicon/v1alpha1/zones/{place}/servers", "servers", .zonal),
        Product("Inference", "inference/v1/regions/{place}/deployments", "deployments", .regional),
        Product("Block volume", "block/v1/zones/{place}/volumes", "volumes", .zonal),
        Product("Block snapshot", "block/v1/zones/{place}/snapshots", "snapshots", .zonal),
        Product("Local volume", "instance/v1/zones/{place}/volumes", "volumes", .zonal),
        Product("File Storage", "file/v1alpha1/regions/{place}/filesystems", "filesystems", .regional),
        Product("Container Registry", "registry/v1/regions/{place}/namespaces", "namespaces", .regional),
        Product("Load Balancer", "lb/v1/zones/{place}/lbs", "lbs", .zonal),
        Product("Flexible IP", "instance/v1/zones/{place}/ips", "ips", .zonal),
        Product("VPC", "vpc/v2/regions/{place}/vpcs", "vpcs", .regional),
        Product("Private Network", "vpc/v2/regions/{place}/private-networks", "private_networks", .regional),
        Product("Public Gateway", "vpc-gw/v2/zones/{place}/gateways", "gateways", .zonal),
        Product("Edge Services", "edge-services/v1beta1/pipelines", "pipelines", .global),
        Product("DNS zone", "domain/v2beta1/dns-zones", "dns_zones", .global),
        Product("Domain", "domain/v2beta1/domains", "domains", .global),
        Product("Transactional Email", "transactional-email/v1alpha1/regions/{place}/domains", "domains", .regional),
        Product("NATS", "mnq/v1beta1/regions/{place}/nats-accounts", "nats_accounts", .regional),
        Product("Secret", "secret-manager/v1beta1/regions/{place}/secrets", "secrets", .regional),
        Product("Key", "key-manager/v1alpha1/regions/{place}/keys", "keys", .regional),
        Product("IoT Hub", "iot/v1/regions/{place}/hubs", "hubs", .regional),
        Product("Web Hosting", "webhosting/v1/regions/{place}/hostings", "hostings", .regional),
    ]
}

/// Any JSON value, for reading list responses without a type per product.
public enum JSONValue: Decodable, Hashable, Sendable {
    case string(String), number(Double), bool(Bool), object([String: JSONValue]), array([JSONValue]), null

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Double.self) { self = .number(v) }
        else if let v = try? c.decode([JSONValue].self) { self = .array(v) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }

    public var string: String? { if case .string(let s) = self, !s.isEmpty { s } else { nil } }
    public var array: [JSONValue]? { if case .array(let a) = self { a } else { nil } }
    public var object: [String: JSONValue]? { if case .object(let o) = self { o } else { nil } }
}

extension Resource {
    /// Reads one item of a product's list. Products name their fields a
    /// little differently: the Instance API says `project` and `state`, DNS
    /// zones have no ID, IPs no name.
    init?(_ item: [String: JSONValue], product: Product, place: String?) {
        // Scaleway's own private DNS zones for VPCs, named by IDs: not managed by anyone.
        if item["domain"]?.string == "privatedns" { return nil }
        let dnsName = item["domain"]?.string.map { domain in
            item["subdomain"]?.string.map { "\($0).\(domain)" } ?? domain
        }
        guard let id = item["id"]?.string ?? dnsName else { return nil }
        self.init(id: id,
                  name: item["name"]?.string ?? dnsName ?? item["address"]?.string ?? id,
                  type: product.type,
                  status: item["status"]?.string ?? item["state"]?.string ?? "",
                  location: item["zone"]?.string ?? item["region"]?.string ?? place ?? "global",
                  projectID: item["project_id"]?.string ?? item["project"]?.string ?? "")
    }
}

/// Lists every product in every place with at most `limit` requests at a
/// time: Scaleway answers a burst of them with 429. A place without the
/// product ("unknown service") and a product the key may not see are left
/// out; other failures are named in the problem.
public func listResources(_ client: ScalewayClient, products: [Product] = Products.all, limit: Int = 6) async
    -> (resources: [Resource], problem: String?) {
    let calls = products.flatMap { product in product.places.map { (product, $0) } }
    @Sendable func call(_ index: Int) async -> (Int, Result<[Resource], Error>) {
        let (product, place) = calls[index]
        do { return (index, .success(try await client.listResources(product, place: place))) }
        catch { return (index, .failure(error)) }
    }
    let results = await withTaskGroup(of: (Int, Result<[Resource], Error>).self) { group in
        // A window of `limit` calls: each one that ends starts the next.
        var pending = calls.indices.makeIterator()
        for _ in 0..<max(1, limit) {
            if let index = pending.next() { group.addTask { await call(index) } }
        }
        var all = [Result<[Resource], Error>?](repeating: nil, count: calls.count)
        for await (index, result) in group {
            all[index] = result
            if let index = pending.next() { group.addTask { await call(index) } }
        }
        return all.compactMap { $0 }
    }
    var resources: [Resource] = []
    var failed: [String] = []
    var forbidden = 0
    var offered = calls.count
    var firstError: String?
    for ((product, place), result) in zip(calls, results) {
        switch result {
        case .success(let found): resources += found
        case .failure(let error):
            let apiError = error as? APIError
            if apiError?.isUnknownService == true { offered -= 1; continue }
            if apiError?.isForbidden == true { forbidden += 1; continue }
            firstError = firstError ?? error.localizedDescription
            failed.append(place.map { "\(product.type) (\($0))" } ?? product.type)
        }
    }
    if forbidden > 0 && forbidden == offered {
        return (resources, "This key may not list other resources. Add AllProductsReadOnly to its policy.")
    }
    guard let firstError else { return (resources, nil) }
    let names = failed.prefix(3).joined(separator: ", ") + (failed.count > 3 ? " and \(failed.count - 3) more" : "")
    return (resources, "Could not load \(names): \(firstError)")
}

/// One line of the resource list, whatever the resource is.
public struct ResourceRow: Identifiable, Hashable, Sendable {
    public var id: String
    public var name: String
    public var type: String
    public var status: String
    public var location: String
    public var projectID: String
    public var keyIDs: [String]
    public var destination: Destination

    public init(_ listed: Listed<Resource>) {
        let r = listed.item
        self.init(id: r.id, name: r.name, type: r.type, status: r.status, location: r.location, projectID: r.projectID,
                  keyIDs: listed.keyIDs, destination: .resource(r.id))
    }

    public init(_ listed: Listed<Server>) {
        let s = listed.item
        self.init(id: s.id, name: s.name, type: "Instance", status: s.state.rawValue, location: s.zone.rawValue,
                  projectID: s.project, keyIDs: listed.keyIDs, destination: .instance(s.id))
    }

    public init(_ listed: Listed<ServerlessApp>) {
        let a = listed.item
        self.init(id: a.id, name: a.name, type: "Serverless \(a.kind.rawValue)", status: a.status,
                  location: a.region.rawValue, projectID: a.projectID, keyIDs: listed.keyIDs,
                  destination: .serverless(a.id))
    }

    init(id: String, name: String, type: String, status: String, location: String, projectID: String,
         keyIDs: [String], destination: Destination) {
        self.id = id
        self.name = name
        self.type = type
        self.status = status
        self.location = location
        self.projectID = projectID
        self.keyIDs = keyIDs
        self.destination = destination
    }
}

public enum ResourceGrouping: String, CaseIterable, Sendable {
    case type, location, project
}

/// The rows matching the search, in sections by type, location or project,
/// sorted by section title and then by name.
public func groupResources(_ rows: [ResourceRow], by grouping: ResourceGrouping, query: String,
                           projectNames: [String: String]) -> [(title: String, rows: [ResourceRow])] {
    func project(_ row: ResourceRow) -> String { projectNames[row.projectID] ?? row.projectID }
    let words = query.lowercased().split(separator: " ")
    let matching = rows.filter { row in
        let text = [row.name, row.type, row.status, row.location, project(row), row.id].joined(separator: " ").lowercased()
        return words.allSatisfy { text.contains($0) }
    }
    let sections = Dictionary(grouping: matching) { row -> String in
        switch grouping {
        case .type: row.type
        case .location: row.location
        case .project: project(row).isEmpty ? "No project" : project(row)
        }
    }
    return sections.keys.sorted { $0.localizedStandardCompare($1) == .orderedAscending }.map { title in
        (title, sections[title]!.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending })
    }
}
