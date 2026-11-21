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
    /// Everything else its list item says, the product's key fields first.
    public var details: [Detail]

    public init(id: String, name: String, type: String, status: String, location: String, projectID: String,
                details: [Detail] = []) {
        self.id = id
        self.name = name
        self.type = type
        self.status = status
        self.location = location
        self.projectID = projectID
        self.details = details
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
    /// The fields the detail screen shows first, as paths into the list item
    /// ("endpoint.ip"); a path also takes the fields below it. A field the
    /// item lacks is simply not shown.
    public var keyFields: [String]

    public init(_ type: String, _ path: String, _ field: String, _ scope: Scope, keyFields: [String] = []) {
        self.type = type
        self.path = path
        self.field = field
        self.scope = scope
        self.keyFields = keyFields
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
        Product("Managed Database", "rdb/v1/regions/{place}/instances", "instances", .regional,
                keyFields: ["engine", "node_type", "endpoint.ip", "endpoint.port", "volume.size", "is_ha_cluster"]),
        Product("Redis", "redis/v1/zones/{place}/clusters", "clusters", .zonal,
                keyFields: ["version", "node_type", "cluster_size", "endpoints.ips", "endpoints.port", "tls_enabled"]),
        Product("MongoDB", "mongodb/v1/regions/{place}/instances", "instances", .regional,
                keyFields: ["version", "node_type", "node_amount", "volume.size", "endpoints"]),
        Product("Serverless SQL Database", "serverless-sqldb/v1alpha1/regions/{place}/databases", "databases", .regional,
                keyFields: ["endpoint", "cpu_min", "cpu_max"]),
        Product("Kafka", "kafka/v1alpha1/regions/{place}/clusters", "clusters", .regional,
                keyFields: ["version", "node_type", "node_amount", "volume.size"]),
        Product("OpenSearch", "searchdb/v1alpha1/regions/{place}/deployments", "deployments", .regional,
                keyFields: ["version", "node_type", "node_amount", "volume.size"]),
        Product("Data Warehouse", "datawarehouse/v1beta1/regions/{place}/deployments", "deployments", .regional,
                keyFields: ["version", "node_type", "replica_count", "shard_count"]),
        Product("Kubernetes", "k8s/v1/regions/{place}/clusters", "clusters", .regional,
                keyFields: ["version", "type", "cni", "cluster_url"]),
        Product("Elastic Metal", "baremetal/v1/zones/{place}/servers", "servers", .zonal,
                keyFields: ["offer_name", "ips.address", "domain"]),
        Product("Apple Silicon", "apple-silicon/v1alpha1/zones/{place}/servers", "servers", .zonal,
                keyFields: ["type", "ip", "os.name"]),
        Product("Inference", "inference/v1/regions/{place}/deployments", "deployments", .regional,
                keyFields: ["model_name", "node_type_name", "endpoints.url", "size"]),
        Product("Block volume", "block/v1/zones/{place}/volumes", "volumes", .zonal,
                keyFields: ["size", "type", "specs.perf_iops", "references"]),
        Product("Block snapshot", "block/v1/zones/{place}/snapshots", "snapshots", .zonal,
                keyFields: ["size", "parent_volume.name"]),
        Product("Local volume", "instance/v1/zones/{place}/volumes", "volumes", .zonal,
                keyFields: ["size", "volume_type", "server.name"]),
        Product("File Storage", "file/v1alpha1/regions/{place}/filesystems", "filesystems", .regional,
                keyFields: ["size", "number_of_attachments"]),
        Product("Container Registry", "registry/v1/regions/{place}/namespaces", "namespaces", .regional,
                keyFields: ["endpoint", "image_count", "size", "is_public"]),
        Product("Load Balancer", "lb/v1/zones/{place}/lbs", "lbs", .zonal,
                keyFields: ["type", "ip.ip_address", "frontend_count", "backend_count"]),
        Product("Flexible IP", "instance/v1/zones/{place}/ips", "ips", .zonal,
                keyFields: ["address", "reverse", "server.name", "type"]),
        Product("VPC", "vpc/v2/regions/{place}/vpcs", "vpcs", .regional,
                keyFields: ["private_network_count", "is_default", "routing_enabled"]),
        Product("Private Network", "vpc/v2/regions/{place}/private-networks", "private_networks", .regional,
                keyFields: ["subnets.subnet", "dhcp_enabled"]),
        Product("Public Gateway", "vpc-gw/v2/zones/{place}/gateways", "gateways", .zonal,
                keyFields: ["type", "ipv4.address", "bastion_enabled", "bastion_port"]),
        Product("Edge Services", "edge-services/v1beta1/pipelines", "pipelines", .global,
                keyFields: ["description"]),
        Product("DNS zone", "domain/v2beta1/dns-zones", "dns_zones", .global,
                keyFields: ["ns", "ns_master", "linked_products"]),
        Product("Domain", "domain/v2beta1/domains", "domains", .global,
                keyFields: ["expired_at", "auto_renew_status", "dnssec.status"]),
        Product("Transactional Email", "transactional-email/v1alpha1/regions/{place}/domains", "domains", .regional,
                keyFields: ["last_error", "reputation.status", "statistics.sent_count", "statistics.failed_count"]),
        Product("NATS", "mnq/v1beta1/regions/{place}/nats-accounts", "nats_accounts", .regional,
                keyFields: ["endpoint"]),
        Product("Secret", "secret-manager/v1beta1/regions/{place}/secrets", "secrets", .regional,
                keyFields: ["description", "type", "version_count", "path"]),
        Product("Key", "key-manager/v1alpha1/regions/{place}/keys", "keys", .regional,
                keyFields: ["usage", "rotation_policy.next_rotation_at", "protected"]),
        Product("IoT Hub", "iot/v1/regions/{place}/hubs", "hubs", .regional,
                keyFields: ["product_plan", "endpoint", "device_count"]),
        Product("Web Hosting", "webhosting/v1/regions/{place}/hostings", "hostings", .regional,
                keyFields: ["domain", "offer.name", "platform_hostname"]),
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
                  projectID: item["project_id"]?.string ?? item["project"]?.string ?? "",
                  details: Detail.all(in: item, keyFields: product.keyFields))
    }
}

/// One field of a resource as the detail screen shows it: "Volume size", "10 GB".
public struct Detail: Hashable, Sendable {
    public var label: String
    public var value: String
    /// One of the product's key fields, shown before the rest.
    public var key: Bool

    public init(label: String, value: String, key: Bool = false) {
        self.label = label
        self.value = value
        self.key = key
    }

    /// What the header already shows, wherever it appears.
    private static let hidden: Set = ["id", "srn", "region", "zone", "organization", "organization_id", "project", "project_id"]
    /// What the header already shows, at the top of the item.
    private static let header: Set = ["name", "status", "state"]

    /// Every field of a list item with a value, one line per leaf: nested
    /// objects and lists of objects are walked ("Endpoints 2 IP"), lists of
    /// values joined. IDs and empty values are left out. The key fields come
    /// first in their order, the rest by label.
    static func all(in item: [String: JSONValue], keyFields: [String]) -> [Detail] {
        var found: [(path: [String], value: String)] = []
        func walk(_ value: JSONValue, _ path: [String]) {
            let last = path.last ?? ""
            if hidden.contains(last) || last.hasSuffix("_id") { return }
            switch value {
            case .object(let object):
                // A setting: {"name": "max_connections", "value": "100"}.
                if Set(object.keys) == ["name", "value"], let name = object["name"]?.string {
                    walk(object["value"]!, path.filter { Int($0) == nil } + [name])
                    return
                }
                for (name, inner) in object { walk(inner, path + [name]) }
            case .array(let list) where list.contains { $0.object != nil }:
                if list.count == 1 { walk(list[0], path) }
                else { for (index, inner) in list.enumerated() { walk(inner, path + [String(index + 1)]) } }
            case .array(let list):
                let values = list.compactMap { format($0, name: last) }
                if !values.isEmpty { found.append((path, values.joined(separator: ", "))) }
            default:
                if let text = format(value, name: last) { found.append((path, text)) }
            }
        }
        for (name, value) in item where !header.contains(name) { walk(value, [name]) }

        func rank(_ path: [String]) -> Int? {
            let field = path.filter { Int($0) == nil }.joined(separator: ".")
            return keyFields.firstIndex { field == $0 || field.hasPrefix($0 + ".") }
        }
        return found
            .map { (rank: rank($0.path), detail: Detail(label: label($0.path), value: $0.value, key: rank($0.path) != nil)) }
            .sorted { a, b in
                switch (a.rank, b.rank) {
                case let (x?, y?) where x != y: x < y
                case (_?, nil): true
                case (nil, _?): false
                default: a.detail.label.localizedStandardCompare(b.detail.label) == .orderedAscending
                }
            }
            .map(\.detail)
    }

    private static let words = [
        "ip": "IP", "ips": "IPs", "ipv4": "IPv4", "ipv6": "IPv6", "ha": "HA", "dns": "DNS", "dnssec": "DNSSEC",
        "spf": "SPF", "dkim": "DKIM", "dmarc": "DMARC", "mx": "MX", "kms": "KMS", "url": "URL", "ns": "NS",
        "dhcp": "DHCP", "tls": "TLS", "ssl": "SSL", "ssh": "SSH", "cpu": "CPU", "gpu": "GPU", "api": "API",
        "cni": "CNI", "vpc": "VPC", "vnc": "VNC", "id": "ID", "os": "OS",
    ]

    /// "endpoint", "ip" → "Endpoint IP"; "is_ha_cluster" → "HA cluster".
    static func label(_ path: [String]) -> String {
        let words = path.flatMap { part -> [String] in
            var part = part
            for prefix in ["is_", "has_"] where part.hasPrefix(prefix) { part.removeFirst(prefix.count) }
            return part.split(separator: "_").map { Self.words[$0.lowercased()] ?? String($0) }
        }
        let text = words.joined(separator: " ")
        return text.prefix(1).uppercased() + text.dropFirst()
    }

    /// A value as people read it, or nil when there is nothing to show.
    static func format(_ value: JSONValue, name: String) -> String? {
        switch value {
        case .bool(let flag):
            return flag ? "Yes" : "No"
        case .number(let number):
            if name == "size" || name.hasSuffix("_bytes") {
                return ByteCountFormatter.string(fromByteCount: Int64(number), countStyle: .decimal)
            }
            return number == number.rounded() && abs(number) < 1e15 ? String(Int64(number)) : String(number)
        case .string(let text):
            if text.isEmpty { return nil }
            if let date = parseDate(text) { return date.formatted(date: .abbreviated, time: .shortened) }
            if text.hasSuffix("s"), let seconds = Int(text.dropLast()) { return duration(seconds) }
            return text
        default:
            return nil
        }
    }

    private static func parseDate(_ text: String) -> Date? {
        guard text.count >= 20, text.contains("T") else { return nil }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: text) { return date }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: text)
    }

    /// Scaleway writes durations as seconds: "31536000s" → "365 days".
    private static func duration(_ seconds: Int) -> String {
        for (size, unit) in [(86400, "day"), (3600, "hour"), (60, "minute")] where seconds >= size && seconds % size == 0 {
            let count = seconds / size
            return "\(count) \(unit)\(count == 1 ? "" : "s")"
        }
        return "\(seconds) seconds"
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
