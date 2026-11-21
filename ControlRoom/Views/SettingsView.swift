import ControlRoomKit
import StoreKit
import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(ProStore.self) private var pro
    @State private var showAddKey = false
    @State private var showPro = false
    @State private var showManage = false
    @State private var removing: APIKey?

    var body: some View {
        List {
            Section {
                ForEach(model.keys) { key in
                    Button { removing = key } label: {
                        LabeledContent(key.label, value: key.isDemo ? "Made-up data" : String(key.organizationID.prefix(8)))
                    }
                    .foregroundStyle(.primary)
                }
                Button("Add API key") { showAddKey = true }
                if !model.keys.contains(where: \.isDemo) {
                    Button("Try the demo") { Task { try? await model.add(.demo) } }
                }
            } header: {
                Text("API keys")
            } footer: {
                Text("Keys are stored in the iPhone keychain and sent only to Scaleway. Tap a key to remove it.")
            }

            Section {
                if pro.subscribed {
                    LabeledContent("Status", value: "Active")
                    Button("Manage subscription") { showManage = true }
                } else {
                    Button("Get Pro") { showPro = true }
                    Button("Restore purchases") { Task { await pro.restore() } }
                }
            } header: {
                Text("Pro")
            } footer: {
                if !pro.subscribed && pro.isPro(keys: model.keys) {
                    Text("Every Pro feature is open while the demo is your only key.")
                }
            }

            Section("Help") {
                Link("Setup guide", destination: Links.support)
                Link("Privacy policy", destination: Links.privacy)
                Link("Terms of use", destination: Links.terms)
            }

            Section {
                LabeledContent("Version", value: AppInfo.version)
            } header: {
                Text("About")
            } footer: {
                Text("ControlRoom is an independent app. It is not made, endorsed or supported by Scaleway.")
            }
        }
        .navigationTitle("Settings")
        .sheet(isPresented: $showAddKey) { AddKeyView() }
        .sheet(isPresented: $showPro) { ProView() }
        .manageSubscriptionsSheet(isPresented: $showManage)
        .confirmationDialog("Remove \(removing?.label ?? "")?",
                            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
                            titleVisibility: .visible, presenting: removing) { key in
            Button("Remove", role: .destructive) { Task { await model.remove(key) } }
        } message: { _ in
            Text("The key is deleted from this iPhone. It stays valid at Scaleway.")
        }
    }
}
