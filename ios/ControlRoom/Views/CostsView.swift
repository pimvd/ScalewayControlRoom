import ControlRoomKit
import QuickLook
import SwiftUI

struct CostsView: View {
    @Environment(AppModel.self) private var model
    @Environment(ProStore.self) private var pro
    @State private var period = recentPeriods(now: Date(), count: 1)[0]
    @State private var results: [CostResult] = []
    @State private var loading = false
    @State private var showPro = false
    @State private var openingInvoice: String?
    @State private var invoicePreview: URL?
    @State private var invoiceProblem: String?

    struct CostResult: Identifiable {
        var key: APIKey
        var costs: MonthlyCosts?
        var error: String?
        /// Loaded with Pro only.
        var invoices: [Invoice] = []
        var invoicesError: String?
        var id: String { key.organizationID }
    }

    private var isPro: Bool { pro.isPro(keys: model.keys) }

    var body: some View {
        List {
            if model.keys.isEmpty {
                Text("Add an API key to see your costs.").foregroundStyle(.secondary)
            }
            if let invoiceProblem { ProblemBanner(text: invoiceProblem) }
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
                if isPro { invoices(result, title: results.count > 1 ? result.key.label : nil) }
            }
            if !loaded.isEmpty && !isPro {
                Section {
                    Button("See the details with Pro") { showPro = true }
                } footer: {
                    Text("Costs by category and by project, the months before, and your invoices are part of Pro.")
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
        .quickLookPreview($invoicePreview)
    }

    /// The organization's latest invoices; tapping one opens its PDF.
    @ViewBuilder
    private func invoices(_ result: CostResult, title: String?) -> some View {
        if !result.invoices.isEmpty || result.invoicesError != nil {
            Section(title.map { "\($0) · Invoices" } ?? "Invoices") {
                if let error = result.invoicesError { ProblemBanner(text: error) }
                ForEach(result.invoices) { invoice in
                    Button { open(invoice, from: result.key) } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(formatPeriod(invoice.period)).font(.headline)
                                Text(details(invoice)).font(.footnote).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if openingInvoice == invoice.id {
                                ProgressView()
                            } else {
                                Text(formatMoney(invoice.total, currency: invoice.currency)).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .foregroundStyle(.primary)
                    .disabled(openingInvoice != nil)
                }
            }
        }
    }

    private func details(_ invoice: Invoice) -> String {
        var parts: [String] = []
        if !invoice.number.isEmpty { parts.append("No. \(invoice.number)") }
        if !invoice.state.isEmpty { parts.append(invoice.state) }
        if let issued = invoice.issued { parts.append("issued \(issued.formatted(date: .abbreviated, time: .omitted))") }
        return parts.joined(separator: " · ")
    }

    /// Downloads the PDF into the temporary folder and shows it, with its
    /// share button to save or send it.
    private func open(_ invoice: Invoice, from key: APIKey) {
        openingInvoice = invoice.id
        invoiceProblem = nil
        Task {
            defer { openingInvoice = nil }
            do {
                let file = try await client(for: key).invoicePDF(id: invoice.id)
                let url = FileManager.default.temporaryDirectory
                    .appendingPathComponent(file.name.replacingOccurrences(of: "/", with: "-"))
                try file.data.write(to: url)
                invoicePreview = url
            } catch {
                invoiceProblem = "Could not open the invoice: \(error.localizedDescription)"
            }
        }
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
        let withInvoices = isPro
        results = await withTaskGroup(of: CostResult.self) { group in
            for key in uniqueOrganizations(model.keys) {
                group.addTask {
                    let api = client(for: key)
                    var result: CostResult
                    do { result = CostResult(key: key, costs: try await api.costs(period: period)) }
                    catch { result = CostResult(key: key, error: error.localizedDescription) }
                    if withInvoices {
                        do { result.invoices = try await api.invoices() }
                        catch let error as APIError where error.isForbidden {
                            result.invoicesError = "This key may not read invoices. Add BillingReadOnly to its policy."
                        } catch {
                            result.invoicesError = error.localizedDescription
                        }
                    }
                    return result
                }
            }
            var all: [CostResult] = []
            for await result in group { all.append(result) }
            return all.sorted { $0.key.label < $1.key.label }
        }
    }
}
