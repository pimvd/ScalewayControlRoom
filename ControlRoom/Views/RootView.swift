import ControlRoomKit
import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model
    @State private var tab: AppTab = .instances
    @State private var instancesPath: [Destination] = []
    @State private var serverlessPath: [Destination] = []

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack(path: $instancesPath) {
                InstancesView().withDestinations()
            }
            .tabItem { Label("Instances", systemImage: "server.rack") }
            .tag(AppTab.instances)

            NavigationStack(path: $serverlessPath) {
                ServerlessView().withDestinations()
            }
            .tabItem { Label("Serverless", systemImage: "bolt") }
            .tag(AppTab.serverless)

            NavigationStack {
                CostsView()
            }
            .tabItem { Label("Costs", systemImage: "eurosign.circle") }
            .tag(AppTab.costs)

            NavigationStack {
                SettingsView()
            }
            .tabItem { Label("Settings", systemImage: "gearshape") }
            .tag(AppTab.settings)
        }
        .task { await start() }
    }

    private func start() async {
        model.load()
        // The Screenshots workflow launches the app with `-screenshotRoute <path>`,
        // which iOS hands to the app as a setting: turn the demo on and open
        // that screen, without a deep link and its "Open in" prompt.
        guard let route = UserDefaults.standard.string(forKey: "screenshotRoute") else {
            await model.refresh()
            return
        }
        if model.keys.contains(where: \.isDemo) {
            await model.refresh()
        } else {
            try? await model.add(.demo)
        }
        let destination = screenshotDestination(route)
        tab = destination.tab
        switch destination.tab {
        case .instances: instancesPath = destination.path
        case .serverless: serverlessPath = destination.path
        default: break
        }
    }
}

private extension View {
    func withDestinations() -> some View {
        navigationDestination(for: Destination.self) { destination in
            switch destination {
            case .instance(let id): InstanceDetailView(id: id)
            case .serverless(let id): ServerlessDetailView(id: id)
            case .logs(let kind, let id): LogsView(kind: kind, id: id)
            }
        }
    }
}
