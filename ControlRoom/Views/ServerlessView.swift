import ControlRoomKit
import SwiftUI

struct ServerlessView: View {
    @Environment(AppModel.self) private var model
    @Environment(ProStore.self) private var pro

    var body: some View {
        Group {
            if !pro.isPro(keys: model.keys) {
                ProGate(text: "Your serverless containers, functions and jobs, with their logs and metrics, are part of Pro.")
            } else {
                List {
                    ForEach(model.problems.filter { $0.area == .serverless }) { problem in
                        ProblemBanner(text: "\(model.label(of: problem.keyID)): \(problem.message)")
                    }
                    ForEach(model.serverless) { listed in
                        NavigationLink(value: Destination.serverless(listed.id)) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(listed.item.name).font(.headline)
                                Text(details(listed)).font(.footnote).foregroundStyle(.secondary)
                            }
                        }
                    }
                    if model.serverless.isEmpty && !model.refreshing {
                        Text(model.keys.isEmpty ? "Add an API key first."
                             : "No serverless containers, functions or jobs found for your keys.")
                            .foregroundStyle(.secondary)
                    }
                }
                .refreshable { await model.refresh() }
            }
        }
        .navigationTitle("Serverless")
    }

    private func details(_ listed: Listed<ServerlessApp>) -> String {
        var parts = [listed.item.kind.rawValue, listed.item.region.rawValue, listed.item.status]
        if model.keys.count > 1 { parts.append(model.label(of: listed.keyIDs[0])) }
        return parts.joined(separator: " · ")
    }
}

struct ServerlessDetailView: View {
    @Environment(AppModel.self) private var model
    var id: String

    var body: some View {
        if let listed = model.serverless.first(where: { $0.id == id }) {
            let app = listed.item
            List {
                Section {
                    LabeledContent("Kind", value: "Serverless \(app.kind.rawValue)")
                    LabeledContent(app.kind == .job ? "Last run" : "Status", value: app.status)
                    LabeledContent("Region", value: app.region.rawValue)
                    if app.kind == .job {
                        if !app.schedule.isEmpty { LabeledContent("Schedule", value: app.schedule) }
                    } else {
                        LabeledContent("Scale", value: "\(app.minScale) to \(app.maxScale)")
                    }
                    if !app.endpoint.isEmpty { LabeledContent("Endpoint", value: app.endpoint) }
                    LabeledContent("Project", value: app.projectID)
                    LabeledContent("Seen by", value: listed.keyIDs.map(model.label(of:)).joined(separator: ", "))
                }
                Section {
                    // Cockpit keeps logs for jobs, but no metrics.
                    if app.kind != .job {
                        NavigationLink("Metrics", value: Destination.metrics(.serverless, app.id))
                    }
                    NavigationLink("Logs", value: Destination.logs(.serverless, app.id))
                }
            }
            .navigationTitle(app.name)
            .navigationBarTitleDisplayMode(.inline)
        } else {
            ContentUnavailableView("This app is no longer listed.", systemImage: "bolt")
        }
    }
}
