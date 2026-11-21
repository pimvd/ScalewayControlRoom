import ControlRoomKit
import SwiftUI

struct CostsView: View {
    @Environment(AppModel.self) private var model
    @Environment(ProStore.self) private var pro
    @State private var period = recentPeriods(now: Date(), count: 1)[0]
    @State private var results: [CostResult] = []
    @State private var loading = false
    @State private var showPro = false

    struct CostResult: Identifiable {
        var key: APIKey
        var costs: MonthlyCosts?
        var error: String?
        var id: String { key.organizationID }
    }

    private var isPro: Bool { pro.isPro(keys: model.keys) }

    var body: some View {
        List {
            if model.keys.isEmpty {
                Text("Add an API key to see your costs.").foregroundStyle(.secondary)
            }
            if isPro {
                Picker("Month", selection: $period) {
                    ForEach(recentPeriods(now: Date(), count: 6), id: \.self) { Text(formatPeriod($0)).tag($0) }
                }
            }
            let loaded = results.compactMap(\.costs)
            if !loaded.isEmpty {
                Section {
                    VStack(alignment: .leading) {
                        Text("\(formatPeriod(period)), excluding VAT").font(.subheadline).foregroundStyle(.secondary)
                        Text(formatMoney(loaded.map(total).reduce(0, +), currency: loaded[0].currency))
                            .font(.system(size: 40, weight: .bold))
                    }
                }
            }
            ForEach(results) { result in
                if let error = result.error { ProblemBanner(text: "\(result.key.label): \(error)") }
                if let costs = result.costs, isPro {
                    breakdown(costs, title: results.count > 1 ? result.key.label : nil)
                }
            }
            if !loaded.isEmpty && !isPro {
                Section {
                    Button("See the details with Pro") { showPro = true }
                } footer: {
                    Text("Costs by category and by project, and the months before, are part of Pro.")
                }
            }
            if !loaded.isEmpty {
                Text("Scaleway updates these figures a few times a day. Your invoice is the final word.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Costs")
        .refreshable { await load() }
        .task(id: "\(period)-\(model.keys.map(\.id))") { await load() }
        .sheet(isPresented: $showPro) { ProView() }
    }

    private func breakdown(_ costs: MonthlyCosts, title: String?) -> some View {
        let money = { (amount: Double) in formatMoney(amount, currency: costs.currency) }
        var categories = sum(costs.items, by: \.category)
        if costs.discount != 0 { categories.append((name: "Discounts", amount: -costs.discount)) }
        let projects = sum(costs.items, by: \.project)
        return Group {
            Section(title.map { "\($0) · By category" } ?? "By category") {
                ForEach(categories, id: \.name) { LabeledContent($0.name, value: money($0.amount)) }
            }
            Section(title.map { "\($0) · By project" } ?? "By project") {
                ForEach(projects, id: \.name) { LabeledContent($0.name, value: money($0.amount)) }
            }
        }
    }

    /// Billing is per organization: one request per organization, not per key.
    private func load() async {
        loading = true
        defer { loading = false }
        let period = self.period
        results = await withTaskGroup(of: CostResult.self) { group in
            for key in uniqueOrganizations(model.keys) {
                group.addTask {
                    do { return CostResult(key: key, costs: try await client(for: key).costs(period: period)) }
                    catch { return CostResult(key: key, error: error.localizedDescription) }
                }
            }
            var all: [CostResult] = []
            for await result in group { all.append(result) }
            return all.sorted { $0.key.label < $1.key.label }
        }
    }
}
