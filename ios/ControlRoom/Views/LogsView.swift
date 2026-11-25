import ControlRoomKit
import SwiftUI

struct LogsView: View {
    @Environment(AppModel.self) private var model
    @Environment(ProStore.self) private var pro
    var kind: LogKind
    var id: String
    @State private var minutes = 60
    @State private var result: LogResult?
    @State private var failure: String?
    @State private var loading = false

    private var resource: AppModel.CockpitResource? { model.cockpitResource(kind, id) }

    var body: some View {
        Group {
            if !pro.isPro(keys: model.keys) {
                ProGate(text: "Logs from Cockpit are part of Pro.")
            } else if resource == nil {
                ContentUnavailableView("This resource is no longer listed.", systemImage: "doc.text")
            } else {
                list
            }
        }
        .navigationTitle("Logs")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: "\(minutes)-\(resource != nil)") { await load() }
    }

    private var list: some View {
        List {
            PeriodPicker(minutes: $minutes)

            if let failure { ProblemBanner(text: failure) }
            if loading && result == nil { ProgressView() }

            ForEach(Array((result?.lines ?? []).enumerated()), id: \.offset) { _, line in
                VStack(alignment: .leading, spacing: 2) {
                    // Beyond a day, the time alone does not say which day.
                    Text(line.time, format: minutes > 1440 ? .dateTime.day().month().hour().minute().second()
                                                           : .dateTime.hour().minute().second())
                        .font(.caption).foregroundStyle(.secondary)
                    Text(line.line).font(.system(size: 13, design: .monospaced)).textSelection(.enabled)
                }
            }

            if let result, result.lines.isEmpty {
                if let server = resource?.server {
                    InstanceCockpitSetupView(server: server, what: "logs")
                } else {
                    Text(kind == .resource
                         ? "No logs in this period. Scaleway sends the logs of its managed products to Cockpit by itself, where the product has any."
                         : "No logs in this period. Everything the app writes to standard output and standard error shows up here, as Scaleway sends serverless logs to Cockpit by itself.")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .refreshable { await load() }
    }

    private func load() async {
        guard let resource else { return }
        let target = resource.target
        loading = true
        defer { loading = false }
        failure = nil
        let minutes = self.minutes
        do {
            result = try await model.withKeys(resource.keyIDs) { try await $0.logs(target: target, minutes: minutes) }
        } catch {
            failure = cockpitFailure(error)
        }
    }
}
