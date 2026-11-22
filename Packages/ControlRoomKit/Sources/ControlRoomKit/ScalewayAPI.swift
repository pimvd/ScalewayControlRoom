import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Sends a request and returns the body and status code. The app uses
/// URLSession; tests use a fake.
public protocol HTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (Data, Int)
}

public struct URLSessionTransport: HTTPTransport {
    public init() {}

    public func send(_ request: URLRequest) async throws -> (Data, Int) {
        let (data, response) = try await URLSession.shared.data(for: request)
        return (data, (response as? HTTPURLResponse)?.statusCode ?? 0)
    }
}

/// The real client. Every Scaleway endpoint, Cockpit's Loki included, takes
/// the IAM secret key in `X-Auth-Token`.
public struct ScalewayAPI: ScalewayClient {
    static let base = URL(string: "https://api.scaleway.com")!
    static let pageSize = 100
    static let logLimit = 200

    let key: APIKey
    let transport: HTTPTransport

    public init(key: APIKey, transport: HTTPTransport = URLSessionTransport()) {
        self.key = key
        self.transport = transport
    }

    // MARK: Requests

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return decoder
    }()

    private func send<T: Decodable>(_ url: URL, method: String = "GET", body: Data? = nil,
                                    decoder: JSONDecoder = ScalewayAPI.decoder) async throws -> T {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        request.setValue(key.secretKey, forHTTPHeaderField: "X-Auth-Token")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        var (data, status) = try await transport.send(request)
        // Too many requests at once: wait a little, then try again.
        for attempt in 1...3 where status == 429 {
            try await Task.sleep(for: .seconds(attempt))
            (data, status) = try await transport.send(request)
        }
        guard (200..<300).contains(status) else {
            let message = (try? JSONDecoder().decode(ErrorBody.self, from: data))?.message ?? "HTTP \(status)"
            throw APIError(status: status, message: message)
        }
        return try decoder.decode(T.self, from: data)
    }

    private func url(_ path: String, _ query: [String: String] = [:], base: URL = ScalewayAPI.base) -> URL {
        var components = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty {
            components.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        return components.url!
    }

    /// Fetches every page of a list endpoint: a short page is the last one.
    private func allPages<T>(pageSize: Int = ScalewayAPI.pageSize, _ fetch: (Int) async throws -> [T]) async throws -> [T] {
        var all: [T] = []
        var page = 1
        while true {
            let items = try await fetch(page)
            all += items
            if items.count < pageSize { return all }
            page += 1
        }
    }

    /// A list endpoint that pages with `page_size`; the Instance API uses `per_page`.
    private func pages<T: Decodable>(_ path: String, _ query: [String: String] = [:],
                                     _ field: KeyPath<ListResponse<T>, [T]?>) async throws -> [T] {
        try await allPages { page in
            var query = query
            query["page_size"] = String(Self.pageSize)
            query["page"] = String(page)
            let response: ListResponse<T> = try await send(url(path, query))
            return response[keyPath: field] ?? []
        }
    }

    // MARK: ScalewayClient

    public func listServers(zone: Zone) async throws -> [Server] {
        let servers: [APIServer] = try await allPages { page in
            let response: ListResponse<APIServer> = try await send(
                url("instance/v1/zones/\(zone.rawValue)/servers", ["per_page": String(Self.pageSize), "page": String(page)]))
            return response.servers ?? []
        }
        return servers.map(\.server)
    }

    public func act(zone: Zone, serverID: String, action: ServerAction) async throws {
        let body = try JSONEncoder().encode(["action": action.rawValue])
        let _: Ignored = try await send(url("instance/v1/zones/\(zone.rawValue)/servers/\(serverID)/action"),
                                        method: "POST", body: body)
    }

    public func costs(period: String) async throws -> MonthlyCosts {
        var discount = 0.0
        let items: [APIConsumption] = try await allPages { page in
            let response: ListResponse<APIConsumption> = try await send(url("billing/v2beta1/consumptions", [
                "organization_id": key.organizationID, "billing_period": period,
                "page_size": String(Self.pageSize), "page": String(page),
            ]))
            discount = abs(response.totalDiscountUntaxedValue ?? discount)
            return response.consumptions ?? []
        }
        return MonthlyCosts(
            items: items.map {
                Consumption(amount: $0.value.amount, category: $0.categoryName, product: $0.productName,
                            project: ($0.projectName ?? "").isEmpty ? $0.projectId : $0.projectName!)
            },
            discount: discount,
            currency: items.first?.value.currencyCode ?? "EUR")
    }

    public func hourlyPrices(zone: Zone) async throws -> [String: Double] {
        // The Instance API's own catalogue (`products/servers`) reports 0 for
        // every price; the public Product Catalog has them, keyed by the same
        // commercial type. Retired types carry 0 and are left out.
        let products: [CatalogProduct] = try await pages(
            "product-catalog/v2alpha1/public-catalog/products",
            ["product_types": "instance", "zone": zone.rawValue], \.products)
        let entries = products.filter { $0.unitOfMeasure?.unit == "hour" && $0.price.retailPrice.value > 0 }
            .map { ($0.product, $0.price.retailPrice.value) }
        return Dictionary(entries, uniquingKeysWith: { first, _ in first })
    }

    public func listServerless(region: Region) async throws -> [ServerlessApp] {
        // Containers, functions and jobs are separate products with separate
        // permission sets, not all offered in every region: a key that may see
        // only some of them still lists those.
        async let containers = attempt { try await serverlessApps(region, .container) }
        async let functions = attempt { try await serverlessApps(region, .function) }
        async let jobs = attempt { try await serverlessJobs(region) }
        let results = await [containers, functions, jobs]
        let failures = results.compactMap { result -> APIError? in
            if case .failure(let error) = result { return error as? APIError ?? APIError(status: 0, message: error.localizedDescription) }
            return nil
        }
        let unknown = failures.filter(\.isUnknownService)
        let forbidden = failures.filter(\.isForbidden)
        // Anything else is a real problem; "unknown service" when the region
        // offers none of them, and "may not" when none it offers is allowed.
        if let other = failures.first(where: { !$0.isUnknownService && !$0.isForbidden }) { throw other }
        if unknown.count == results.count { throw unknown[0] }
        if let first = forbidden.first, forbidden.count + unknown.count == results.count { throw first }
        return results.flatMap { (try? $0.get()) ?? [] }
    }

    /// One product's apps in a region, across the key's organization. They
    /// carry no project; their namespace does. Functions can only be listed
    /// per namespace.
    private func serverlessApps(_ region: Region, _ kind: ServerlessApp.Kind) async throws -> [ServerlessApp] {
        let base = kind == .container ? "containers/v1" : "functions/v1beta1"
        let path = "\(base)/regions/\(region.rawValue)"
        let organization = ["organization_id": key.organizationID]
        let namespaces: [APINamespace] = try await pages("\(path)/namespaces", organization, \.namespaces)
        var apps: [APIServerless] = []
        if kind == .container {
            apps = try await pages("\(path)/containers", organization, \.containers)
        } else {
            for namespace in namespaces {
                apps += try await pages("\(path)/functions", ["namespace_id": namespace.id], \.functions)
            }
        }
        let projectOf = Dictionary(namespaces.map { ($0.id, $0.projectId) }, uniquingKeysWith: { first, _ in first })
        return apps.map { a in
            ServerlessApp(id: a.id, name: a.name, kind: kind, status: a.status, region: region,
                          projectID: projectOf[a.namespaceId] ?? "", endpoint: a.publicEndpoint ?? a.domainName ?? "",
                          minScale: a.minScale ?? 0, maxScale: a.maxScale ?? 0)
        }
    }

    /// The organization's job definitions in a region. A job has no state of
    /// its own: its status is that of its last run.
    private func serverlessJobs(_ region: Region) async throws -> [ServerlessApp] {
        let path = "serverless-jobs/v1alpha1/regions/\(region.rawValue)"
        let jobs: [APIJobDefinition] = try await pages("\(path)/job-definitions",
                                                       ["organization_id": key.organizationID], \.jobDefinitions)
        var apps: [ServerlessApp] = []
        for job in jobs {
            let runs: ListResponse<APIJobRun> = try await send(url("\(path)/job-runs", [
                "job_definition_id": job.id, "order_by": "created_at_desc", "page_size": "1",
            ]))
            let schedule = job.cronSchedule.map { "\($0.schedule) (\($0.timezone))" } ?? ""
            apps.append(ServerlessApp(id: job.id, name: job.name, kind: .job, status: runs.jobRuns?.first?.state ?? "not run yet",
                                      region: region, projectID: job.projectId, endpoint: "", minScale: 0, maxScale: 0,
                                      schedule: schedule))
        }
        return apps
    }

    public func listResources(_ product: Product, place: String?) async throws -> [Resource] {
        let path = product.path(at: place)
        // The Instance API filters by `organization` and pages by at most 50
        // with `per_page`; the others say `organization_id` and `page_size`.
        let instanceAPI = path.hasPrefix("instance/")
        let pageSize = 50
        let items: [JSONValue] = try await allPages(pageSize: pageSize) { page in
            let query = instanceAPI
                ? ["organization": key.organizationID, "per_page": String(pageSize), "page": String(page)]
                : ["organization_id": key.organizationID, "page_size": String(pageSize), "page": String(page)]
            let response: [String: JSONValue] = try await send(url(path, query), decoder: JSONDecoder())
            return response[product.field]?.array ?? []
        }
        return items.compactMap { $0.object.flatMap { Resource($0, product: product, place: place) } }
    }

    public func projectNames() async throws -> [String: String] {
        let projects: [APIProject] = try await pages("account/v3/projects", ["organization_id": key.organizationID], \.projects)
        return Dictionary(projects.map { ($0.id, $0.name) }, uniquingKeysWith: { first, _ in first })
    }

    private func attempt<T: Sendable>(_ work: () async throws -> T) async -> Result<T, Error> {
        do { return .success(try await work()) } catch { return .failure(error) }
    }

    /// The project's Cockpit data sources of one type in the region: Scaleway's
    /// own and any custom one the user pushes to.
    private func dataSources(_ target: CockpitTarget, type: String) async throws -> [URL] {
        let sources: [APIDataSource] = try await pages(
            "cockpit/v1/regions/\(target.region.rawValue)/data-sources",
            ["project_id": target.projectID, "types": type], \.dataSources)
        return sources.compactMap { URL(string: $0.url) }
    }

    public func logs(target: CockpitTarget, minutes: Int) async throws -> LogResult {
        let sources = try await dataSources(target, type: "logs")
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var responses: [LokiResponse] = []
        for base in sources {
            responses.append(try await send(url("loki/api/v1/query_range", [
                "query": target.selector,
                "start": "\(now - Int64(minutes) * 60_000)000000",
                "end": "\(now)000000",
                "limit": String(Self.logLimit),
                "direction": "backward",
            ], base: base)))
        }
        return LogResult(sources: sources.count, lines: Self.logLines(responses))
    }

    // Metrics data sources speak the Prometheus HTTP API under /prometheus.

    public func metricNames(target: CockpitTarget, minutes: Int) async throws -> MetricNames {
        let sources = try await dataSources(target, type: "metrics")
        let now = Int(Date().timeIntervalSince1970)
        var names = Set<String>()
        for base in sources {
            let response: PrometheusNames = try await send(url("prometheus/api/v1/label/__name__/values", [
                "match[]": target.selector,
                "start": String(now - minutes * 60), "end": String(now),
            ], base: base))
            names.formUnion(response.data)
        }
        return MetricNames(sources: sources.count, names: names.sorted())
    }

    public func metric(_ name: String, target: CockpitTarget, minutes: Int) async throws -> [MetricSeries] {
        let now = Int(Date().timeIntervalSince1970)
        for base in try await dataSources(target, type: "metrics") {
            let response: PrometheusMatrix = try await send(url("prometheus/api/v1/query_range", [
                "query": metricQuery(name, selector: target.selector),
                "start": String(now - minutes * 60), "end": String(now),
                "step": String(metricStep(minutes: minutes)),
            ], base: base))
            // The first source with data is the one that has the metric.
            let series = response.data.result.filter { !$0.values.isEmpty }.map { s in
                MetricSeries(instance: s.metric?["resource_instance"] ?? "",
                             points: s.values.map { MetricPoint(time: Date(timeIntervalSince1970: $0.time), value: $0.value) })
            }
            if !series.isEmpty { return series.sorted { $0.instance < $1.instance } }
        }
        return []
    }

    /// Loki's streams merged into one list, newest first.
    static func logLines(_ responses: [LokiResponse], limit: Int = logLimit) -> [LogLine] {
        responses
            .flatMap { $0.data.result.flatMap(\.values) }
            .compactMap { pair -> LogLine? in
                guard pair.count == 2, let ns = Double(pair[0]) else { return nil }
                return LogLine(time: Date(timeIntervalSince1970: ns / 1e9), line: pair[1])
            }
            .sorted { $0.time > $1.time }
            .prefix(limit)
            .map { $0 }
    }
}

// MARK: Wire formats

struct ErrorBody: Decodable { var message: String? }

/// Any JSON object, ignored.
struct Ignored: Decodable {}

/// Every list response the client reads, by its list field.
struct ListResponse<T: Decodable>: Decodable {
    var servers: [T]?
    var consumptions: [T]?
    var namespaces: [T]?
    var containers: [T]?
    var functions: [T]?
    var jobDefinitions: [T]?
    var projects: [T]?
    var jobRuns: [T]?
    var dataSources: [T]?
    var products: [T]?
    var totalDiscountUntaxedValue: Double?
}

struct APIServer: Decodable {
    struct IP: Decodable { var address: String }
    var id: String
    var name: String
    var state: String
    var commercialType: String
    var zone: String
    var project: String
    var tags: [String]?
    var publicIps: [IP]?

    var server: Server {
        Server(id: id, name: name, state: ServerState(apiValue: state), commercialType: commercialType,
               zone: Zone(rawValue: zone) ?? .frPar1, project: project, tags: tags ?? [],
               publicIPs: (publicIps ?? []).map(\.address))
    }
}

/// google.type.Money: whole units (a number or a string) plus nanos.
struct Money: Decodable {
    var currencyCode: String?
    var amount: Double

    enum CodingKeys: String, CodingKey { case currencyCode, units, nanos }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        currencyCode = try c.decodeIfPresent(String.self, forKey: .currencyCode)
        let units: Double
        if let number = try? c.decodeIfPresent(Double.self, forKey: .units) {
            units = number
        } else {
            units = Double(try c.decodeIfPresent(String.self, forKey: .units) ?? "0") ?? 0
        }
        let nanos = try c.decodeIfPresent(Double.self, forKey: .nanos) ?? 0
        amount = units + nanos / 1e9
    }
}

struct APIConsumption: Decodable {
    var value: Money
    var categoryName: String
    var productName: String
    var projectName: String?
    var projectId: String
}

struct CatalogProduct: Decodable {
    struct Price: Decodable {
        struct Amount: Decodable {
            var units: Double
            var nanos: Double
            var value: Double { units + nanos / 1e9 }
        }
        var retailPrice: Amount
    }
    struct Unit: Decodable { var unit: String }
    var product: String
    var price: Price
    var unitOfMeasure: Unit?
}

struct APINamespace: Decodable {
    var id: String
    var projectId: String
}

struct APIJobDefinition: Decodable {
    struct Cron: Decodable {
        var schedule: String
        var timezone: String
    }
    var id: String
    var name: String
    var projectId: String
    var cronSchedule: Cron?
}

struct APIJobRun: Decodable { var state: String }

struct APIProject: Decodable {
    var id: String
    var name: String
}

struct APIServerless: Decodable {
    var id: String
    var name: String
    var status: String
    var namespaceId: String
    var minScale: Int?
    var maxScale: Int?
    var publicEndpoint: String?  // containers
    var domainName: String?  // functions
}

struct APIDataSource: Decodable {
    var url: String
}

struct PrometheusNames: Decodable {
    var data: [String]
}

struct PrometheusMatrix: Decodable {
    /// A sample is `[unix time, "value"]`.
    struct Sample: Decodable {
        var time: Double
        var value: Double

        init(from decoder: Decoder) throws {
            var c = try decoder.unkeyedContainer()
            time = try c.decode(Double.self)
            value = Double(try c.decode(String.self)) ?? .nan
        }
    }
    struct Series: Decodable {
        var metric: [String: String]?
        var values: [Sample]
    }
    struct Payload: Decodable { var result: [Series] }
    var data: Payload
}

struct LokiResponse: Decodable {
    struct Payload: Decodable { var result: [Stream] }
    struct Stream: Decodable { var values: [[String]] }
    var data: Payload
}
