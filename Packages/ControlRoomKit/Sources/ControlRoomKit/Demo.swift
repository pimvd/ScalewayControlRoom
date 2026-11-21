import Foundation

/// The demo lets App Review, screenshots and curious users try the app
/// without a Scaleway account. Everything here is made up. State is shared,
/// so a server stopped on one screen is stopped on all of them.
public struct DemoClient: ScalewayClient {
    let latency: Duration
    let settle: Duration

    public init(latency: Duration = .milliseconds(300), settle: Duration = .seconds(3)) {
        self.latency = latency
        self.settle = settle
    }

    public func listServers(zone: Zone) async throws -> [Server] {
        try await Task.sleep(for: latency)
        return await DemoState.shared.servers.filter { $0.zone == zone }
    }

    public func act(zone: Zone, serverID: String, action: ServerAction) async throws {
        try await Task.sleep(for: latency)
        let busy: ServerState = action == .poweroff ? .stopping : .starting
        let done: ServerState = action == .poweroff ? .stopped : .running
        await DemoState.shared.set(serverID, busy)
        let settle = self.settle
        Task {
            try? await Task.sleep(for: settle)
            await DemoState.shared.set(serverID, done)
        }
    }

    public func costs(period: String) async throws -> MonthlyCosts {
        try await Task.sleep(for: latency)
        // Older months a little different, so the history does not look copied.
        let month = Int(period.suffix(2)) ?? 1
        let factor = 1 - Double(month % 3) * 0.08
        return MonthlyCosts(
            items: Demo.costs.map {
                Consumption(amount: ($0.amount * factor * 100).rounded() / 100, category: $0.category,
                            product: $0.product, project: $0.project)
            },
            discount: 0, currency: "EUR")
    }

    public func hourlyPrices(zone: Zone) async throws -> [String: Double] {
        Demo.prices
    }

    public func listServerless(region: Region) async throws -> [ServerlessApp] {
        try await Task.sleep(for: latency)
        return Demo.apps.filter { $0.region == region }
    }

    /// No latency: the Other tab asks this some 170 times.
    public func listResources(_ product: Product, place: String?) async throws -> [Resource] {
        Demo.resources.filter { $0.type == product.type && ($0.location == place || place == nil) }
    }

    public func projectNames() async throws -> [String: String] {
        ["webshop": "Webshop", "tooling": "Tooling", "ml": "Machine learning"]
    }

    public func logs(target: CockpitTarget, minutes: Int) async throws -> LogResult {
        try await Task.sleep(for: latency)
        return LogResult(sources: 1, lines: Demo.logs(for: target.resourceID, now: Date()))
    }

    public func metricNames(target: CockpitTarget, minutes: Int) async throws -> MetricNames {
        try await Task.sleep(for: latency)
        let hasMetrics = Demo.apps.contains { $0.id == target.resourceID }
        return MetricNames(sources: 1, names: hasMetrics ? Demo.metricNames : [])
    }

    public func metric(_ name: String, target: CockpitTarget, minutes: Int) async throws -> [MetricSeries] {
        try await Task.sleep(for: latency)
        let points = Demo.metric(name, minutes: minutes, now: Date())
        // The instance count is for the app as a whole; the rest per instance.
        if name == "instances" { return [MetricSeries(instance: "", points: points)] }
        return [("deployment-7f9c-k2x8p", 1.0), ("deployment-7f9c-q4m1z", 0.7)].map { instance, share in
            MetricSeries(instance: instance, points: points.map { MetricPoint(time: $0.time, value: $0.value * share) })
        }
    }
}

actor DemoState {
    static let shared = DemoState()
    var servers = Demo.servers

    func set(_ id: String, _ state: ServerState) {
        if let index = servers.firstIndex(where: { $0.id == id }) { servers[index].state = state }
    }

    /// For tests: start from the initial demo state again.
    func reset() { servers = Demo.servers }
}

public enum Demo {
    static let servers: [Server] = [
        Server(id: "demo-1", name: "web-prod-1", state: .running, commercialType: "PRO2-S", zone: .frPar1,
               project: "webshop", tags: ["prod"]),
        Server(id: "demo-2", name: "web-prod-2", state: .running, commercialType: "PRO2-S", zone: .frPar2,
               project: "webshop", tags: ["prod"]),
        Server(id: "demo-3", name: "staging", state: .running, commercialType: "DEV1-M", zone: .frPar1,
               project: "webshop", tags: ["dev"]),
        Server(id: "demo-4", name: "ci-runner", state: .running, commercialType: "GP1-XS", zone: .nlAms1,
               project: "tooling", tags: ["dev"]),
        Server(id: "demo-5", name: "gpu-training", state: .stopped, commercialType: "L4-1-24G", zone: .frPar2,
               project: "ml", tags: ["dev"]),
        Server(id: "demo-6", name: "old-wordpress", state: .stopped, commercialType: "DEV1-S", zone: .nlAms1,
               project: "tooling"),
    ]

    static let prices: [String: Double] = [
        "DEV1-S": 0.0109, "DEV1-M": 0.0219, "GP1-XS": 0.091, "PRO2-S": 0.219, "L4-1-24G": 0.75,
    ]

    static let costs: [Consumption] = [
        Consumption(amount: 112.4, category: "Compute", product: "Instances PRO2-S", project: "webshop"),
        Consumption(amount: 11.8, category: "Compute", product: "Instances DEV1-M", project: "webshop"),
        Consumption(amount: 49.1, category: "Compute", product: "Instances GP1-XS", project: "tooling"),
        Consumption(amount: 36.0, category: "Compute", product: "Instances L4-1-24G", project: "ml"),
        Consumption(amount: 8.6, category: "Storage", product: "Block Storage", project: "webshop"),
        Consumption(amount: 2.3, category: "Storage", product: "Object Storage", project: "tooling"),
        Consumption(amount: 2.9, category: "Network", product: "Flexible IP", project: "webshop"),
        Consumption(amount: 1.2, category: "Observability", product: "Cockpit", project: "webshop"),
    ]

    /// Read from list items like Scaleway's, as real resources are, so the
    /// detail screen has its fields.
    static let resources: [Resource] = [
        resource("Managed Database", "fr-par", """
            {"id": "demo-r1", "name": "orders-db", "status": "ready", "region": "fr-par", "project_id": "webshop",
             "engine": "PostgreSQL-16", "node_type": "db-gp-s", "is_ha_cluster": true, "tags": ["prd"],
             "volume": {"type": "sbs_5k", "size": 50000000000}, "endpoint": {"ip": "51.159.24.110", "port": 5432},
             "backup_schedule": {"frequency": 24, "retention": 7, "disabled": false},
             "settings": [{"name": "max_connections", "value": "200"}], "created_at": "2026-03-02T09:12:00Z"}
            """),
        resource("Redis", "fr-par-1", """
            {"id": "demo-r2", "name": "sessions", "status": "ready", "zone": "fr-par-1", "project_id": "webshop",
             "version": "7.2.5", "node_type": "RED1-S", "cluster_size": 1, "tls_enabled": true,
             "endpoints": [{"ips": ["10.12.0.4"], "port": 6379}], "created_at": "2026-03-02T09:20:00Z"}
            """),
        resource("Block volume", "fr-par-1", """
            {"id": "demo-r3", "name": "uploads", "status": "in_use", "zone": "fr-par-1", "project_id": "webshop",
             "type": "sbs_15k", "size": 200000000000, "specs": {"perf_iops": 15000, "class": "sbs"},
             "created_at": "2026-04-11T14:03:00Z"}
            """),
        resource("Flexible IP", "fr-par-1", """
            {"id": "demo-r4", "address": "51.15.10.20", "state": "attached", "zone": "fr-par-1", "project": "webshop",
             "reverse": "shop.example.com", "type": "routed_ipv4", "server": {"id": "demo-s1", "name": "web-1"}}
            """),
        resource("Load Balancer", "fr-par-1", """
            {"id": "demo-r5", "name": "web-lb", "status": "ready", "zone": "fr-par-1", "project_id": "webshop",
             "type": "lb-s", "ip": [{"ip_address": "51.159.30.40", "reverse": "lb.example.com"}],
             "frontend_count": 2, "backend_count": 1, "ssl_compatibility_level": "ssl_compatibility_level_intermediate"}
            """),
        resource("Transactional Email", "fr-par", """
            {"id": "demo-r6", "name": "mail.example.com", "status": "checked", "region": "fr-par", "project_id": "webshop",
             "reputation": {"status": "excellent", "score": 98}, "statistics": {"sent_count": 1284, "failed_count": 3},
             "spf_config": "include:_spf.tem.scaleway.com", "last_valid_at": "2026-11-21T03:40:00Z"}
            """),
        resource("Secret", "fr-par", """
            {"id": "demo-r7", "name": "stripe-api-key", "status": "ready", "region": "fr-par", "project_id": "webshop",
             "description": "Live key for the checkout API", "type": "opaque", "version_count": 3, "path": "/checkout",
             "protected": false, "tags": ["prd"], "updated_at": "2026-10-30T16:45:00Z"}
            """),
        resource("DNS zone", nil, """
            {"domain": "example.com", "subdomain": "", "status": "active", "project_id": "webshop",
             "ns": ["ns0.dom.scw.cloud", "ns1.dom.scw.cloud"], "linked_products": [], "updated_at": "2026-09-14T08:00:00Z"}
            """),
        resource("Managed Database", "fr-par", """
            {"id": "demo-r9", "name": "metabase-db", "status": "ready", "region": "fr-par", "project_id": "tooling",
             "engine": "PostgreSQL-16", "node_type": "db-dev-s", "is_ha_cluster": false,
             "volume": {"type": "sbs_5k", "size": 10000000000}, "endpoint": {"ip": "51.159.24.120", "port": 18342},
             "backup_schedule": {"frequency": 24, "retention": 7, "disabled": false}, "created_at": "2026-05-20T10:00:00Z"}
            """),
    ]

    private static func resource(_ type: String, _ place: String?, _ json: String) -> Resource {
        let product = Products.all.first { $0.type == type }!
        let item = try! JSONDecoder().decode([String: JSONValue].self, from: Data(json.utf8))
        return Resource(item, product: product, place: place)!
    }

    static let apps: [ServerlessApp] = [
        app("demo-c1", "checkout-api", .container, "webshop", 5),
        app("demo-c2", "image-resizer", .container, "webshop", 10),
        app("demo-f1", "stripe-webhook", .function, "webshop", 3),
        app("demo-f2", "nightly-report", .function, "tooling", 1),
        ServerlessApp(id: "demo-j1", name: "db-backup", kind: .job, status: "succeeded", region: .frPar,
                      projectID: "tooling", endpoint: "", minScale: 0, maxScale: 0, schedule: "0 3 * * * (Europe/Paris)"),
    ]

    private static func app(_ id: String, _ name: String, _ kind: ServerlessApp.Kind, _ project: String,
                            _ maxScale: Int) -> ServerlessApp {
        ServerlessApp(id: id, name: name, kind: kind, status: "ready", region: .frPar, projectID: project,
                      endpoint: "\(name)-demo.functions.fnc.fr-par.scw.cloud", minScale: 0, maxScale: maxScale)
    }

    private static let logLines = [
        "GET /api/cart 200 18ms",
        "POST /api/checkout 201 142ms",
        "GET /api/cart 200 12ms",
        "payment confirmed for order 10482",
        "GET /healthz 200 1ms",
        "POST /api/checkout 402 96ms: card declined",
        "GET /api/products?page=2 200 31ms",
        "cache miss for product 8812, loading from database",
    ]

    static let metricNames = ["cpu_usage_ratio", "http_requests_total", "instances", "memory_usage_bytes"]

    /// A plausible day of traffic: a slow wave with some wobble.
    static func metric(_ name: String, minutes: Int, now: Date) -> [MetricPoint] {
        let (base, swing): (Double, Double) = switch name {
        case "cpu_usage_ratio": (0.35, 0.2)
        case "http_requests_total": (40, 25)
        case "instances": (2, 1)
        default: (180_000_000, 60_000_000)
        }
        let step = Double(metricStep(minutes: minutes))
        let count = minutes * 60 / Int(step)
        return (0...count).map { i in
            let time = now.addingTimeInterval(-Double(count - i) * step)
            let phase = time.timeIntervalSince1970 / 3600
            var value = base + swing * sin(phase / 2) + swing * 0.3 * sin(phase * 7)
            if name == "instances" { value = value.rounded() }
            return MetricPoint(time: time, value: max(0, value))
        }
    }

    /// Serverless apps log; the demo instances do not, so their logs screen
    /// shows how to set that up.
    static func logs(for resourceID: String, now: Date) -> [LogLine] {
        guard apps.contains(where: { $0.id == resourceID }) else { return [] }
        return (0..<40).map { LogLine(time: now.addingTimeInterval(-47 * Double($0)), line: logLines[$0 % logLines.count]) }
    }
}
