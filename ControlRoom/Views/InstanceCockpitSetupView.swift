import ControlRoomKit
import SwiftUI

/// Shown when an instance has no logs or metrics in Cockpit: how to start
/// sending both with one agent.
struct InstanceCockpitSetupView: View {
    var server: Server
    /// "logs" or "metrics", for the heading.
    var what: String

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("This instance sends no \(what)").font(.title3.bold())
            Text("Unlike serverless apps, instances do not send their logs and metrics to Cockpit by themselves: an agent on the instance has to push them. Set it up with the commands below and both show up here. Data you push yourself is billed by Scaleway as Cockpit custom data.")
                .font(.subheadline).foregroundStyle(.secondary)
            CommandBlock(title: "1. On your computer, with the scw CLI", command: Commands.cockpitSources(for: server))
            Text("Keep the two URLs and the secret key it prints. The secret key is shown only once.")
                .font(.footnote).foregroundStyle(.secondary)
            CommandBlock(title: "2. On the instance: install Grafana Alloy", command: Commands.alloyInstall)
            CommandBlock(title: "3. On the instance: fill in the three values, then run", command: Commands.alloyConfig(for: server))
            Link("Open the setup guide", destination: Links.support)
        }
        .padding(.vertical, 8)
    }
}
