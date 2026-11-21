import ControlRoomKit
import SwiftUI

/// Every resource besides instances and serverless (or, on request, every
/// resource at all), searchable and grouped by type, location or project.
struct OtherView: View {
    @Environment(AppModel.self) private var model
    @Environment(ProStore.self) private var pro
    @State private var query = ""
    @State private var grouping = ResourceGrouping.type
    @State private var everything = false

    private var rows: [ResourceRow] {
        let others = model.resources.map(ResourceRow.init)
        guard everything else { return others }
        return model.servers.map(ResourceRow.init) + model.serverless.map(ResourceRow.init) + others
    }

    var body: some View {
        Group {
            if !pro.isPro(keys: model.keys) {
                ProGate(text: "Your databases, volumes, IPs, domains and every other resource, with their logs and metrics, are part of Pro.")
            } else {
                list
            }
        }
        .navigationTitle(everything ? "All resources" : "Other")
        .toolbar {
            Menu {
                Picker("Show", selection: $everything) {
                    Text("Other resources").tag(false)
                    Text("All resources").tag(true)
                }
                Picker("Group by", selection: $grouping) {
                    ForEach(ResourceGrouping.allCases, id: \.self) { Text($0.rawValue.capitalized).tag($0) }
                }
            } label: {
                Label("Show and group", systemImage: "line.3.horizontal.decrease.circle")
            }
        }
        .task(id: model.keys.map(\.id)) {
            if pro.isPro(keys: model.keys) { await model.refreshResources() }
        }
    }

    private var list: some View {
        let sections = groupResources(rows, by: grouping, query: query, projectNames: model.projectNames)
        return List {
            ForEach(model.problems.filter { $0.area == .other }) { problem in
                ProblemBanner(text: "\(model.label(of: problem.keyID)): \(problem.message)")
            }
            ForEach(sections, id: \.title) { section in
                Section(section.title) {
                    ForEach(section.rows) { row in
                        NavigationLink(value: row.destination) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(row.name).font(.headline)
                                Text(details(row)).font(.footnote).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            if sections.isEmpty {
                if model.loadingResources {
                    ProgressView().frame(maxWidth: .infinity)
                } else {
                    Text(model.keys.isEmpty ? "Add an API key first." : rows.isEmpty ? "No other resources found for your keys." : "No resources match.")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .searchable(text: $query, prompt: "Search name, type, location or project")
        .refreshable {
            if everything { await model.refresh() }
            await model.refreshResources()
        }
    }

    /// What the grouping does not already say.
    private func details(_ row: ResourceRow) -> String {
        var parts: [String] = []
        if grouping != .type { parts.append(row.type) }
        if !row.status.isEmpty { parts.append(row.status) }
        if grouping != .location { parts.append(row.location) }
        if grouping != .project, let project = model.projectNames[row.projectID] { parts.append(project) }
        if model.keys.count > 1 { parts.append(model.label(of: row.keyIDs[0])) }
        return parts.joined(separator: " · ")
    }
}

struct ResourceDetailView: View {
    @Environment(AppModel.self) private var model
    var id: String

    var body: some View {
        if let listed = model.resources.first(where: { $0.id == id }) {
            let resource = listed.item
            List {
                Section {
                    LabeledContent("Type", value: resource.type)
                    if !resource.status.isEmpty { LabeledContent("Status", value: resource.status) }
                    LabeledContent("Location", value: resource.location)
                    if !resource.projectID.isEmpty {
                        LabeledContent("Project", value: model.projectNames[resource.projectID] ?? resource.projectID)
                    }
                    LabeledContent("ID") { Text(resource.id).textSelection(.enabled) }
                    LabeledContent("Seen by", value: listed.keyIDs.map(model.label(of:)).joined(separator: ", "))
                }
                // Global resources have no regional Cockpit to look in.
                if resource.cockpitTarget != nil {
                    Section {
                        NavigationLink("Metrics", value: Destination.metrics(.resource, resource.id))
                        NavigationLink("Logs", value: Destination.logs(.resource, resource.id))
                    }
                }
            }
            .navigationTitle(resource.name)
            .navigationBarTitleDisplayMode(.inline)
        } else if model.loadingResources {
            ProgressView()
        } else {
            ContentUnavailableView("This resource is no longer listed.", systemImage: "square.stack.3d.up")
        }
    }
}
