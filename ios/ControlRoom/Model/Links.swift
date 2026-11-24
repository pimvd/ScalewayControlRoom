import Foundation

/// The store listing's links. scripts/validate_metadata.py checks they match
/// AppStore/app.json, so the app and App Store Connect never disagree.
enum Links {
    static let privacy = URL(string: "https://vandijkcloud.nl/Scaleway-ControlRoom/privacy.html")!
    static let support = URL(string: "https://vandijkcloud.nl/Scaleway-ControlRoom/support.html")!
    static let terms = URL(string: "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/")!
}

enum AppInfo {
    static var version: String {
        let info = Bundle.main.infoDictionary ?? [:]
        let version = info["CFBundleShortVersionString"] as? String ?? "?"
        let build = info["CFBundleVersion"] as? String ?? "?"
        return "\(version) (\(build))"
    }
}
