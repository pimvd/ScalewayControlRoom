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

// MARK: Metrics

/// Counters only ever go up; what matters is how fast.
public func isCounter(_ name: String) -> Bool {
    name.hasSuffix("_total") || name.hasSuffix("_count") || name.hasSuffix("_sum")
}

/// PromQL for one metric of one resource, summed to a single series: the rate
/// per second for a counter, the average for anything else.
public func metricQuery(_ name: String, resourceID: String) -> String {
    let selector = "\(name){resource_id=\"\(resourceID)\"}"
    return isCounter(name) ? "sum(rate(\(selector)[5m]))" : "avg(\(selector))"
}

/// About a hundred points over the period, never closer than 15 seconds.
public func metricStep(minutes: Int) -> Int {
    max(15, minutes * 60 / 100)
}

/// A value in the unit its name suggests, per second for counters.
public func formatMetric(_ value: Double, name: String) -> String {
    let perSecond = isCounter(name) ? "/s" : ""
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
