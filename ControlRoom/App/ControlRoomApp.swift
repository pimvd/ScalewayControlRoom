import SwiftUI

@main
struct ControlRoomApp: App {
    @State private var model = AppModel()
    @State private var pro = ProStore()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .environment(pro)
                .tint(.accentColor)
        }
    }
}
