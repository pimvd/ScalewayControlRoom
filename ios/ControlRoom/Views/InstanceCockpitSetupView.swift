import ControlRoomKit
import SwiftUI

/// Shown when an instance has no logs or metrics in Cockpit in the period:
/// why, from its state, and how to start sending both with one agent.
struct InstanceCockpitSetupView: View {
    var server: Server
    /// "logs" or "metrics", for the text.
    var what: String

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            switch instanceQuiet(server) {
            case .stopped:
                explanation("This instance is stopped",
                            "A stopped instance sends no \(what). If its agent is set up, what it sent while it ran shows up in a longer period, such as 24 hours.")
                DisclosureGroup("Set up the agent") { steps }
            case .starting:
                explanation("This instance is starting",
                            "Its agent starts with it: \(what) show up a minute or two after it runs. Pull to refresh.")
                DisclosureGroup("Set up the agent") { steps }
            case .new:
                explanation("This instance is new",
                            "A new instance sends no \(what) until an agent on it pushes them. Set it up with the commands below; the first data arrives a minute or two later. Data you push yourself is billed by Scaleway as Cockpit custom data.")
                steps
            case .running:
                explanation("This instance sends no \(what)",
                            "Unlike serverless apps, instances do not send their logs and metrics to Cockpit by themselves: an agent on the instance has to push them. Set it up with the commands below and both show up here. Data you push yourself is billed by Scaleway as Cockpit custom data.")
                Text("Just set up or restarted? The first data takes a minute or two. Pull to refresh.")
                    .font(.footnote).foregroundStyle(.secondary)
                steps
            }
        }
        .padding(.vertical, 8)
    }

    private func explanation(_ heading: String, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(heading).font(.title3.bold())
            Text(text).font(.subheadline).foregroundStyle(.secondary)
        }
    }

    private var steps: some View {
        VStack(alignment: .leading, spacing: 12) {
            CommandBlock(title: "1. On your computer, with the scw CLI", command: Commands.cockpitSources(for: server))
            Text("Keep the two URLs and the secret key it prints. The secret key is shown only once.")
                .font(.footnote).foregroundStyle(.secondary)
            CommandBlock(title: "2. On the instance: install Grafana Alloy", command: Commands.alloyInstall)
            CommandBlock(title: "3. On the instance: fill in the three values, then run", command: Commands.alloyConfig(for: server))
            Link("Open the setup guide", destination: Links.support)
        }
        .padding(.top, 4)
    }
}
