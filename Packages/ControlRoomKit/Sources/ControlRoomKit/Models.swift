import Foundation

/// Every zone the Instance API serves. Listing runs against all of them,
/// because a key cannot tell us where its servers are.
public enum Zone: String, CaseIterable, Sendable, Codable, Hashable {
    case frPar1 = "fr-par-1", frPar2 = "fr-par-2", frPar3 = "fr-par-3"
    case nlAms1 = "nl-ams-1", nlAms2 = "nl-ams-2", nlAms3 = "nl-ams-3"
    case plWaw1 = "pl-waw-1", plWaw2 = "pl-waw-2", plWaw3 = "pl-waw-3"
    case itMil1 = "it-mil-1"

    public var region: Region {
        Region(rawValue: String(rawValue[..<rawValue.lastIndex(of: "-")!]))!
    }
}

/// Regions of Serverless and Cockpit.
public enum Region: String, CaseIterable, Sendable, Codable, Hashable {
    case frPar = "fr-par", nlAms = "nl-ams", plWaw = "pl-waw", itMil = "it-mil"
}

public enum ServerState: String, Sendable, Hashable {
    case running, starting, stopping, locked
    case stoppedInPlace = "stopped in place"
    case stopped
    case unknown

    public init(apiValue: String) {
        self = ServerState(rawValue: apiValue) ?? .unknown
    }

    /// Starting or stopping: the app keeps checking until it settles.
    public var isBusy: Bool { self == .starting || self == .stopping }
    public var isStopped: Bool { self == .stopped || self == .stoppedInPlace }
}

/// `poweroff` stops the server and releases its hypervisor, which is what
/// stops the compute billing. `stop_in_place` keeps it reserved (and billed),
/// so the app does not offer it.
public enum ServerAction: String, Sendable {
    case poweron, poweroff, reboot
}

public struct Server: Identifiable, Hashable, Sendable {
    public var id: String
    public var name: String
    public var state: ServerState
    public var commercialType: String
    public var zone: Zone
    public var project: String
    public var tags: [String]
    public var publicIPs: [String]

    public init(id: String, name: String, state: ServerState, commercialType: String, zone: Zone,
                project: String, tags: [String] = [], publicIPs: [String] = []) {
        self.id = id
        self.name = name
        self.state = state
        self.commercialType = commercialType
        self.zone = zone
        self.project = project
        self.tags = tags
        self.publicIPs = publicIPs
    }
}

public struct ServerlessApp: Identifiable, Hashable, Sendable {
    public enum Kind: String, Sendable { case container, function }

    public var id: String
    public var name: String
    public var kind: Kind
    public var status: String
    public var region: Region
    public var projectID: String
    public var endpoint: String
    public var minScale: Int
    public var maxScale: Int

    public init(id: String, name: String, kind: Kind, status: String, region: Region, projectID: String,
                endpoint: String, minScale: Int, maxScale: Int) {
        self.id = id
        self.name = name
        self.kind = kind
        self.status = status
        self.region = region
        self.projectID = projectID
        self.endpoint = endpoint
        self.minScale = minScale
        self.maxScale = maxScale
    }
}

/// Where Cockpit keeps a resource's logs. Scaleway labels every log line with
/// the resource's ID, and the instance setup guide asks for the same label.
public struct LogTarget: Hashable, Sendable {
    public var projectID: String
    public var region: Region
    public var resourceID: String

    public init(projectID: String, region: Region, resourceID: String) {
        self.projectID = projectID
        self.region = region
        self.resourceID = resourceID
    }
}

public struct LogLine: Hashable, Sendable {
    public var time: Date
    public var line: String

    public init(time: Date, line: String) {
        self.time = time
        self.line = line
    }
}

public struct LogResult: Sendable {
    /// How many log data sources the project has in that region.
    public var sources: Int
    /// Newest first.
    public var lines: [LogLine]

    public init(sources: Int, lines: [LogLine]) {
        self.sources = sources
        self.lines = lines
    }
}

public struct Consumption: Hashable, Sendable {
    public var amount: Double
    public var category: String
    public var product: String
    public var project: String

    public init(amount: Double, category: String, product: String, project: String) {
        self.amount = amount
        self.category = category
        self.product = product
        self.project = project
    }
}

public struct MonthlyCosts: Sendable {
    public var items: [Consumption]
    public var discount: Double
    public var currency: String

    public init(items: [Consumption], discount: Double, currency: String) {
        self.items = items
        self.discount = discount
        self.currency = currency
    }
}

/// A Scaleway API key as the user entered it. Only the secret key is sent to
/// the API, so the access key is not asked for. The demo key is a key like
/// any other, served by a fake client instead of the API.
public struct APIKey: Identifiable, Hashable, Codable, Sendable {
    public var id: String
    public var label: String
    public var secretKey: String
    public var organizationID: String
    public var isDemo: Bool

    public init(id: String = UUID().uuidString, label: String, secretKey: String, organizationID: String,
                isDemo: Bool = false) {
        self.id = id
        self.label = label
        self.secretKey = secretKey
        self.organizationID = organizationID
        self.isDemo = isDemo
    }

    public static let demo = APIKey(id: "demo", label: "Demo", secretKey: "", organizationID: "demo-organization",
                                    isDemo: true)
}

public struct APIError: Error, LocalizedError, Sendable {
    public var status: Int
    public var message: String

    public init(status: Int, message: String) {
        self.status = status
        self.message = message
    }

    /// The key may not do this: try another key, or leave the feature out.
    public var isForbidden: Bool { status == 401 || status == 403 }
    public var errorDescription: String? { message }
}

public protocol ScalewayClient: Sendable {
    func listServers(zone: Zone) async throws -> [Server]
    func act(zone: Zone, serverID: String, action: ServerAction) async throws
    /// `period` is YYYY-MM.
    func costs(period: String) async throws -> MonthlyCosts
    /// Commercial type → price per hour.
    func hourlyPrices(zone: Zone) async throws -> [String: Double]
    func listServerless(region: Region) async throws -> [ServerlessApp]
    /// The newest lines of the last `minutes`, newest first.
    func logs(target: LogTarget, minutes: Int) async throws -> LogResult
}

public func client(for key: APIKey) -> ScalewayClient {
    key.isDemo ? DemoClient() : ScalewayAPI(key: key)
}
