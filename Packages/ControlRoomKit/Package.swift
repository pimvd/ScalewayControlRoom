// swift-tools-version: 6.0
import PackageDescription

// Everything that is not UI: the Scaleway client, the demo, and the logic the
// screens use. Runs and tests on macOS and Linux (`swift test`).
let package = Package(
    name: "ControlRoomKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "ControlRoomKit", targets: ["ControlRoomKit"]),
    ],
    targets: [
        .target(name: "ControlRoomKit"),
        .testTarget(name: "ControlRoomKitTests", dependencies: ["ControlRoomKit"]),
    ]
)
