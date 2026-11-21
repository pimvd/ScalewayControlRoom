import ControlRoomKit
import StoreKit
import SwiftUI

struct InstanceDetailView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.requestReview) private var requestReview
    var id: String
    @State private var pending: ServerAction?
    @State private var confirming: ServerAction?
    @State private var failure: String?

    var body: some View {
        if let listed = model.servers.first(where: { $0.id == id }) {
            content(listed)
        } else {
            ContentUnavailableView("This instance is no longer listed.", systemImage: "server.rack")
        }
    }

    private func content(_ listed: Listed<Server>) -> some View {
        let server = listed.item
        return List {
            Section {
                LabeledContent("State") {
                    HStack { StateDot(state: server.state); Text(server.state.rawValue) }
                }
                LabeledContent("Type", value: server.commercialType)
                if let price = model.price(of: server) {
                    LabeledContent("Price", value: "\(formatMoney(price)) per hour")
                }
                LabeledContent("Zone", value: server.zone.rawValue)
                if !server.publicIPs.isEmpty {
                    LabeledContent("Public IP", value: server.publicIPs.joined(separator: ", "))
                }
                if !server.tags.isEmpty {
                    LabeledContent("Tags", value: server.tags.joined(separator: ", "))
                }
                LabeledContent("Project", value: server.project)
                LabeledContent("Seen by", value: listed.keyIDs.map(model.label(of:)).joined(separator: ", "))
            }
            Section {
                NavigationLink("Metrics", value: Destination.metrics(.instance, server.id))
                NavigationLink("Logs", value: Destination.logs(.instance, server.id))
            }
            Section {
                if server.state.isStopped {
                    actionButton("Start", .poweron, listed)
                }
                if server.state == .running {
                    actionButton("Stop", .poweroff, listed, role: .destructive)
                    actionButton("Reboot", .reboot, listed)
                }
                if server.state.isBusy {
                    HStack { ProgressView(); Text("Waiting for Scaleway…").foregroundStyle(.secondary) }
                }
            }
        }
        .navigationTitle(server.name)
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog(confirming == .poweroff ? "Stop this instance?" : "Reboot this instance?",
                            isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }),
                            titleVisibility: .visible, presenting: confirming) { action in
            Button(action == .poweroff ? "Stop" : "Reboot", role: .destructive) { run(action, listed) }
        } message: { action in
            Text(action == .poweroff
                 ? "Compute billing stops. Volumes and flexible IPs stay, and are still billed."
                 : "It will be unreachable for a moment.")
        }
        .alert("Scaleway refused", isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(failure ?? "")
        }
    }

    private func actionButton(_ title: String, _ action: ServerAction, _ listed: Listed<Server>,
                              role: ButtonRole? = nil) -> some View {
        Button(role: role) {
            if action == .poweron { run(action, listed) } else { confirming = action }
        } label: {
            HStack {
                Text(title)
                if pending == action { Spacer(); ProgressView() }
            }
        }
        .disabled(pending != nil)
    }

    private func run(_ action: ServerAction, _ listed: Listed<Server>) {
        pending = action
        Task {
            defer { pending = nil }
            do {
                try await model.act(listed, action)
                noteSuccess(listed)
            } catch {
                failure = error.localizedDescription
            }
        }
    }

    /// Asks for a rating after a few successful starts and stops with a real
    /// key; never in the demo, where App Review and first-time users are.
    private func noteSuccess(_ listed: Listed<Server>) {
        guard !listed.keyIDs.contains(APIKey.demo.id) else { return }
        let count = UserDefaults.standard.integer(forKey: "successfulActions") + 1
        UserDefaults.standard.set(count, forKey: "successfulActions")
        if shouldAskForReview(successfulActions: count) { requestReview() }
    }
}
