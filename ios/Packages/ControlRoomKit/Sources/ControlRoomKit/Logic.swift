import Foundation

/// A resource as the app lists it, with the keys that can see it. Two keys
/// for the same organization see the same resources; each is shown once.
public struct Listed<Item: Identifiable & Hashable & Sendable>: Identifiable, Hashable, Sendable where Item.ID == String {
    public var item: Item
    public var keyIDs: [String]
    public var id: String { item.id }

    public init(item: Item, keyIDs: [String]) {
        self.item = item
        self.keyIDs = keyIDs
    }
}

public func mergeByID<Item>(_ results: [(keyID: String, items: [Item])]) -> [Listed<Item>] {
    var order: [String] = []
    var byID: [String: Listed<Item>] = [:]
    for (keyID, items) in results {
        for item in items {
            if byID[item.id] != nil {
                byID[item.id]!.keyIDs.append(keyID)
            } else {
                byID[item.id] = Listed(item: item, keyIDs: [keyID])
                order.append(item.id)
            }
        }
    }
    return order.map { byID[$0]! }
}

/// What is costing money comes first, then by name.
public func sortServers(_ list: [Listed<Server>]) -> [Listed<Server>] {
    let rank: [ServerState: Int] = [.running: 0, .starting: 1, .stopping: 2, .locked: 3, .unknown: 4,
                                    .stoppedInPlace: 5, .stopped: 6]
    return list.sorted {
        let (a, b) = (rank[$0.item.state]!, rank[$1.item.state]!)
        return a != b ? a < b : $0.item.name.localizedStandardCompare($1.item.name) == .orderedAscending
    }
}

public enum StateFilter: String, CaseIterable, Sendable {
    case all, running, stopped
}

public func filterServers(_ list: [Listed<Server>], query: String, state: StateFilter) -> [Listed<Server>] {
    let q = query.trimmingCharacters(in: .whitespaces).lowercased()
    return list.filter { listed in
        let server = listed.item
        if state == .running && server.state.isStopped { return false }
        if state == .stopped && !server.state.isStopped { return false }
        if q.isEmpty { return true }
        return server.name.lowercased().contains(q) || server.tags.contains { $0.lowercased().contains(q) }
    }
}

/// Billing is per organization, so several keys for one organization must
/// not count its costs more than once.
public func uniqueOrganizations(_ keys: [APIKey]) -> [APIKey] {
    var seen = Set<String>()
    return keys.filter { seen.insert($0.organizationID).inserted }
}

/// Totals per category or project, largest first.
public func sum(_ items: [Consumption], by field: KeyPath<Consumption, String>) -> [(name: String, amount: Double)] {
    var totals: [String: Double] = [:]
    for item in items { totals[item[keyPath: field], default: 0] += item.amount }
    return totals.map { (name: $0.key, amount: $0.value) }.sorted { $0.amount > $1.amount }
}

public func total(_ costs: MonthlyCosts) -> Double {
    costs.items.reduce(0) { $0 + $1.amount } - costs.discount
}

private let utc: Calendar = {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(identifier: "UTC")!
    return calendar
}()

/// YYYY-MM of this month and the months before it, newest first.
public func recentPeriods(now: Date, count: Int) -> [String] {
    let start = utc.date(from: utc.dateComponents([.year, .month], from: now))!
    return (0..<count).map { i in
        let date = utc.date(byAdding: .month, value: -i, to: start)!
        let parts = utc.dateComponents([.year, .month], from: date)
        return String(format: "%04d-%02d", parts.year!, parts.month!)
    }
}

/// "November 2026" for "2026-11".
public func formatPeriod(_ period: String) -> String {
    let parts = period.split(separator: "-").compactMap { Int($0) }
    guard parts.count == 2, let date = utc.date(from: DateComponents(year: parts[0], month: parts[1])) else { return period }
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_GB")
    formatter.timeZone = utc.timeZone
    formatter.dateFormat = "MMMM yyyy"
    return formatter.string(from: date)
}

public func formatMoney(_ amount: Double, currency: String = "EUR") -> String {
    let formatter = NumberFormatter()
    formatter.numberStyle = .currency
    formatter.locale = Locale(identifier: "en_IE")
    formatter.currencyCode = currency
    formatter.maximumFractionDigits = amount != 0 && abs(amount) < 0.1 ? 4 : 2
    return formatter.string(from: NSNumber(value: amount)) ?? "\(amount)"
}

// MARK: Instance logs and metrics

/// Scaleway's dates, such as "2026-11-20T09:12:34.567890+00:00". The
/// fraction is dropped: the system parser does not take six digits everywhere.
public func parseScalewayDate(_ text: String) -> Date? {
    let whole = text.replacingOccurrences(of: #"\.\d+"#, with: "", options: .regularExpression)
    return ISO8601DateFormatter().date(from: whole)
}

/// Why an instance may have nothing in Cockpit for a period.
public enum InstanceQuiet: Equatable, Sendable {
    /// Stopped or stopping: it sends nothing now, but may have before.
    case stopped
    /// Booting: its agent starts with it.
    case starting
    /// Created within the hour: no agent has been set up yet, or only just.
    case new
    /// Running: no agent, or one that sends nothing yet.
    case running
}

public func instanceQuiet(_ server: Server, now: Date = Date()) -> InstanceQuiet {
    switch server.state {
    case .stopped, .stoppedInPlace, .stopping: return .stopped
    case .starting: return .starting
    default:
        if let created = server.created, now.timeIntervalSince(created) < 3600 { return .new }
        return .running
    }
}

// MARK: Metrics

/// Scaleway's `serverless_container_instances_total` counts running
/// instances. It has no samples while the app is scaled to zero.
public func isInstanceCount(_ name: String) -> Bool { name.hasSuffix("instances_total") }

/// Counters only ever go up; what matters is how fast. Cockpit does not say
/// which metrics are counters, so the name tells, except for the instance count.
public func isCounter(_ name: String) -> Bool {
    guard !isInstanceCount(name) else { return false }
    return name.hasSuffix("_total") || name.hasSuffix("_count") || name.hasSuffix("_sum")
}

/// PromQL for one metric of one resource, one series per instance: the rate
/// per second for a counter, the average for anything else. The instance
/// count is one series, 0 where there are no samples (scaled to zero) so the
/// chart does not bridge those gaps.
public func metricQuery(_ name: String, selector: String) -> String {
    let selector = name + selector
    if isInstanceCount(name) { return "sum(\(selector)) or vector(0)" }
    return isCounter(name) ? "sum by (resource_instance) (rate(\(selector)[5m]))" : "avg by (resource_instance) (\(selector))"
}

/// Metrics worth a chart: `_info` metrics, and Scaleway's serverless
/// `_status`, carry their meaning in labels and are always 1.
public func isChartable(_ name: String) -> Bool {
    !name.hasSuffix("_info") && !(name.hasPrefix("serverless_") && name.hasSuffix("_status"))
}

/// A readable title: "serverless_container_cpu_usage_ratio" → "CPU usage".
/// The value already shows the unit, so "bytes" goes where enough is left.
public func metricTitle(_ name: String) -> String {
    var words = name.replacing(#/^serverless_(container|function|job)_/#, with: "").split(separator: "_").map(String.init)
    if words.count > 1, ["total", "ratio"].contains(words.last) { words.removeLast() }
    if words.count > 2, words.last == "bytes" { words.removeLast() }
    let title = words.map { ["cpu", "http"].contains($0) ? $0.uppercased() : $0 }.joined(separator: " ")
    return title.prefix(1).uppercased() + title.dropFirst()
}

/// Instances are named after their deployment; the last part tells them apart.
public func instanceLabel(_ instance: String) -> String {
    instance.split(separator: "-").last.map(String.init) ?? instance
}

/// About a hundred points over the period, never closer than 15 seconds.
public func metricStep(minutes: Int) -> Int {
    max(15, minutes * 60 / 100)
}

/// A value in the unit its name suggests, per second for counters.
public func formatMetric(_ value: Double, name: String) -> String {
    let perSecond = isCounter(name) ? "/s" : ""
    if value == 0 { return "0" + perSecond }
    if isInstanceCount(name) { return String(format: "%.0f", value) }
    if name.contains("bytes") {
        var amount = value
        var unit = 0
        let units = ["B", "KB", "MB", "GB", "TB"]
        while abs(amount) >= 1000 && unit < units.count - 1 {
            amount /= 1000
            unit += 1
        }
        return String(format: unit == 0 ? "%.0f" : "%.1f", amount) + " " + units[unit] + perSecond
    }
    // Scaleway's serverless CPU "ratio" is a percentage already.
    if name.hasPrefix("serverless_") && name.hasSuffix("cpu_usage_ratio") { return String(format: "%.1f%%", value) }
    if name.hasSuffix("_ratio") { return String(format: "%.1f%%", value * 100) }
    if name.contains("percent") { return String(format: "%.1f%%", value) }
    let digits = abs(value) >= 100 ? 0 : abs(value) >= 1 ? 1 : 3
    return String(format: "%.\(digits)f", value) + perSecond
}

/// Returns what is wrong with an entered key, or nil when it looks right.
public func validateKey(label: String, secretKey: String, organizationID: String) -> String? {
    let uuid = #/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/#
    if label.trimmingCharacters(in: .whitespaces).isEmpty { return "Give the key a name." }
    if (try? uuid.wholeMatch(in: secretKey.trimmingCharacters(in: .whitespaces))) == nil {
        return "The secret key is a UUID, like 1a2b3c4d-…."
    }
    if (try? uuid.wholeMatch(in: organizationID.trimmingCharacters(in: .whitespaces))) == nil {
        return "The organization ID is a UUID, like 1a2b3c4d-…."
    }
    return nil
}

/// The app asks for a rating after a server was started or stopped
/// successfully: the moment it has just been useful. Apple shows the prompt
/// at most three times a year whatever we ask, so a few milestones are enough.
public func shouldAskForReview(successfulActions: Int) -> Bool {
    [3, 20, 60].contains(successfulActions)
}
