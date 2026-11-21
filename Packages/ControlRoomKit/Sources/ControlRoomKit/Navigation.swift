import Foundation

public enum AppTab: String, Hashable, Sendable {
    case instances, serverless, costs, settings
}

/// Which kind of resource a logs or metrics screen is for.
public enum LogKind: String, Hashable, Sendable {
    case instance, serverless
}

/// Screens pushed onto a tab's navigation stack.
public enum Destination: Hashable, Sendable {
    case instance(String)
    case serverless(String)
    case logs(LogKind, String)
    case metrics(LogKind, String)
    case metric(LogKind, String, name: String)
}

/// Where a screenshot route ("/costs", "/instance/demo-1",
/// "/logs?kind=serverless&id=demo-c1") leads: a tab and the screens on it.
/// The Screenshots workflow passes one per launch.
public func screenshotDestination(_ route: String) -> (tab: AppTab, path: [Destination]) {
    let components = URLComponents(string: route)
    let parts = (components?.path ?? route).split(separator: "/").map(String.init)
    let query = Dictionary((components?.queryItems ?? []).map { ($0.name, $0.value ?? "") },
                           uniquingKeysWith: { first, _ in first })
    switch parts.first {
    case "instance" where parts.count > 1:
        return (.instances, [.instance(parts[1])])
    case "serverless" where parts.count > 1:
        return (.serverless, [.serverless(parts[1])])
    case "serverless":
        return (.serverless, [])
    case "costs":
        return (.costs, [])
    case "settings":
        return (.settings, [])
    case "logs", "metrics", "metric":
        let id = query["id"] ?? ""
        let kind: LogKind = query["kind"] == "serverless" ? .serverless : .instance
        let screen: [Destination] = switch parts[0] {
        case "logs": [.logs(kind, id)]
        case "metrics": [.metrics(kind, id)]
        default: [.metrics(kind, id), .metric(kind, id, name: query["name"] ?? "")]
        }
        return kind == .serverless
            ? (.serverless, [.serverless(id)] + screen)
            : (.instances, [.instance(id)] + screen)
    default:
        return (.instances, [])
    }
}

/// The subscriptions the app sells. AppStore/subscriptions.json configures the
/// same IDs in App Store Connect; scripts/validate_subscriptions.py checks both
/// lists match.
public enum ProProducts {
    public static let yearly = "com.pvd.scwcontrolroom.pro.yearly"
    public static let monthly = "com.pvd.scwcontrolroom.pro.monthly"
    public static let all = [yearly, monthly]

    /// Without Pro, one real key (the demo key does not count).
    public static let freeKeys = 1
}
