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

    private static let ranges = [(label: "15 min", minutes: 15), (label: "1 hour", minutes: 60), (label: "24 hours", minutes: 1440)]

    private var server: Listed<Server>? { kind == .instance ? model.servers.first { $0.id == id } : nil }
    private var app: Listed<ServerlessApp>? { kind == .serverless ? model.serverless.first { $0.id == id } : nil }

    private var target: LogTarget? {
        if let server { return LogTarget(projectID: server.item.project, region: server.item.zone.region, resourceID: id) }
        if let app { return LogTarget(projectID: app.item.projectID, region: app.item.region, resourceID: id) }
        return nil
    }

    var body: some View {
        Group {
            if !pro.isPro(keys: model.keys) {
                ProGate(text: "Logs from Cockpit are part of Pro.")
            } else if target == nil {
                ContentUnavailableView("This resource is no longer listed.", systemImage: "doc.text")
            } else {
                list
            }
        }
        .navigationTitle("Logs")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: "\(minutes)-\(target != nil)") { await load() }
    }

    private var list: some View {
        List {
            Picker("Period", selection: $minutes) {
                ForEach(Self.ranges, id: \.minutes) { Text($0.label).tag($0.minutes) }
            }
            .pickerStyle(.segmented)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())

            if let failure { ProblemBanner(text: failure) }
            if loading && result == nil { ProgressView() }

            ForEach(Array((result?.lines ?? []).enumerated()), id: \.offset) { _, line in
                VStack(alignment: .leading, spacing: 2) {
                    Text(line.time, format: .dateTime.hour().minute().second()).font(.caption).foregroundStyle(.secondary)
                    Text(line.line).font(.system(size: 13, design: .monospaced)).textSelection(.enabled)
                }
            }

            if let result, result.lines.isEmpty {
                if let server {
                    InstanceLogsSetupView(server: server.item)
                } else {
                    Text("No logs in this period. Everything the app writes to standard output and standard error shows up here, as Scaleway sends serverless logs to Cockpit by itself.")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .refreshable { await load() }
    }

    private func load() async {
        guard let target, let keyIDs = (server?.keyIDs ?? app?.keyIDs) else { return }
        loading = true
        defer { loading = false }
        failure = nil
        let minutes = self.minutes
        do {
            result = try await model.withKeys(keyIDs) { try await $0.logs(target: target, minutes: minutes) }
        } catch let error as APIError where error.isForbidden {
            failure = "This key may not read Cockpit. Give its application the ObservabilityReadOnly permission set."
        } catch {
            failure = error.localizedDescription
        }
    }
}

/// Shown when an instance has no logs in Cockpit: how to start sending them.
struct InstanceLogsSetupView: View {
    var server: Server

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("This instance sends no logs").font(.title3.bold())
            Text("Unlike serverless apps, instances do not send their logs to Cockpit by themselves: an agent on the instance has to push them. Set it up with the commands below and the logs show up here. Logs you push yourself are billed by Scaleway as Cockpit custom data.")
                .font(.subheadline).foregroundStyle(.secondary)
            CommandBlock(title: "1. On your computer, with the scw CLI", command: Commands.logsSource(for: server))
            Text("Keep the data source URL and the secret key it prints. The secret key is shown only once.")
                .font(.footnote).foregroundStyle(.secondary)
            CommandBlock(title: "2. On the instance: install Grafana Alloy", command: Commands.alloyInstall)
            CommandBlock(title: "3. On the instance: fill in the two values, then run", command: Commands.alloyConfig(for: server))
            Link("Open the setup guide", destination: Links.support)
        }
        .padding(.vertical, 8)
    }
}
