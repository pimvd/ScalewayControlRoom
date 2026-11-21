import ControlRoomKit
import SwiftUI

struct InstancesView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var filter: StateFilter = .all

    var body: some View {
        Group {
            if !model.loaded {
                ProgressView()
            } else if model.keys.isEmpty {
                WelcomeView()
            } else {
                list
            }
        }
        .navigationTitle(model.keys.isEmpty ? "" : "Instances")
    }

    private var list: some View {
        let visible = filterServers(model.servers, query: query, state: filter)
        return List {
            ForEach(model.problems.filter { $0.area == .instances }) { problem in
                ProblemBanner(text: "\(model.label(of: problem.keyID)): \(problem.message)")
            }
            Picker("Show", selection: $filter) {
                ForEach(StateFilter.allCases, id: \.self) { Text($0.rawValue.capitalized).tag($0) }
            }
            .pickerStyle(.segmented)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())

            ForEach(visible) { listed in
                NavigationLink(value: Destination.instance(listed.id)) {
                    InstanceRow(listed: listed)
                }
            }
            if visible.isEmpty && !model.refreshing {
                Text(model.servers.isEmpty ? "No instances found for your keys." : "No instances match.")
                    .foregroundStyle(.secondary)
            }
        }
        .searchable(text: $query, prompt: "Search name or tag")
        .refreshable { await model.refresh() }
    }
}

private struct InstanceRow: View {
    @Environment(AppModel.self) private var model
    var listed: Listed<Server>

    var body: some View {
        let server = listed.item
        var details = [server.state.rawValue, server.commercialType, server.zone.rawValue]
        if model.keys.count > 1 { details.append(model.label(of: listed.keyIDs[0])) }
        return HStack(spacing: 10) {
            StateDot(state: server.state)
            VStack(alignment: .leading, spacing: 2) {
                Text(server.name).font(.headline)
                Text(details.joined(separator: " · ")).font(.footnote).foregroundStyle(.secondary)
            }
            Spacer()
            if server.state == .running, let price = model.price(of: server) {
                Text("\(formatMoney(price))/h").font(.footnote).foregroundStyle(.secondary)
            }
        }
    }
}

struct WelcomeView: View {
    @Environment(AppModel.self) private var model
    @State private var showAddKey = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Spacer()
            Text("ControlRoom").font(.largeTitle.bold())
            Text("Start and stop your Scaleway instances, read your serverless logs, and see what your organization costs this month. Your keys stay on this iPhone.")
                .foregroundStyle(.secondary)
            Button { showAddKey = true } label: {
                Text("Add API key").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            Button { Task { try? await model.add(.demo) } } label: {
                Text("Try the demo").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            Spacer()
        }
        .padding()
        .sheet(isPresented: $showAddKey) { AddKeyView() }
    }
}
