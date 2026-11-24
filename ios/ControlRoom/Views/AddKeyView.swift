import ControlRoomKit
import SwiftUI

struct AddKeyView: View {
    @Environment(AppModel.self) private var model
    @Environment(ProStore.self) private var pro
    @Environment(\.dismiss) private var dismiss
    @State private var label = ""
    @State private var secretKey = ""
    @State private var organizationID = ""
    @State private var problem: String?
    @State private var saving = false

    private var locked: Bool {
        !pro.isPro(keys: model.keys) && model.keys.filter { !$0.isDemo }.count >= ProProducts.freeKeys
    }

    var body: some View {
        NavigationStack {
            Group {
                if locked {
                    ProGate(text: "More than one API key is part of Pro.")
                } else {
                    form
                }
            }
            .navigationTitle("Add API key")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                if !locked {
                    ToolbarItem(placement: .confirmationAction) {
                        if saving { ProgressView() } else { Button("Save") { Task { await save() } } }
                    }
                }
            }
        }
    }

    private var form: some View {
        Form {
            Section {
                Text("Give the app its own IAM application and API key. With the scw CLI, run these commands on your computer: they print the secret key and the organization ID to paste below. Remove any permission set you do not want the app to have; the app hides what its key may not do.")
                    .font(.subheadline).foregroundStyle(.secondary)
                CommandBlock(title: "On your computer, with the scw CLI", command: Commands.keySetup)
                Link("Setup guide (also without the CLI)", destination: Links.support)
            }
            Section("Key") {
                TextField("Name, like Work", text: $label)
                SecureField("Secret key", text: $secretKey)
                TextField("Organization ID", text: $organizationID)
            }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            if let problem {
                Section { ProblemBanner(text: problem) }
            }
        }
    }

    private func save() async {
        let key = APIKey(label: label.trimmingCharacters(in: .whitespaces),
                         secretKey: secretKey.trimmingCharacters(in: .whitespaces),
                         organizationID: organizationID.trimmingCharacters(in: .whitespaces))
        if let invalid = validateKey(label: key.label, secretKey: key.secretKey, organizationID: key.organizationID) {
            problem = invalid
            return
        }
        saving = true
        defer { saving = false }
        do {
            // One cheap call tells a mistyped secret from a working one. A key
            // that may not list instances (403) is still useful for costs.
            _ = try await ScalewayAPI(key: key).listServers(zone: .frPar1)
        } catch let error as APIError where error.status == 403 {
            // Keep it.
        } catch let error as APIError where error.status == 401 {
            problem = "Scaleway does not recognise this secret key."
            return
        } catch {
            problem = "Could not reach Scaleway: \(error.localizedDescription)"
            return
        }
        do {
            try await model.add(key)
            dismiss()
        } catch {
            problem = "Could not save the key: \(error.localizedDescription)"
        }
    }
}
