import Foundation

public enum AppTab: String, Hashable, Sendable {
    case instances, serverless, costs, settings
}

public enum LogKind: String, Hashable, Sendable {
    case instance, serverless
}

/// Screens pushed onto a tab's navigation stack.
public enum Destination: Hashable, Sendable {
    case instance(String)
    case serverless(String)
    case logs(LogKind, String)
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
    case "logs":
        let id = query["id"] ?? ""
        return query["kind"] == "serverless"
            ? (.serverless, [.serverless(id), .logs(.serverless, id)])
            : (.instances, [.instance(id), .logs(.instance, id)])
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
