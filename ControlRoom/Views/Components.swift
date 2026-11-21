import ControlRoomKit
import SwiftUI
import UIKit

struct StateDot: View {
    var state: ServerState

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 10, height: 10)
            .accessibilityHidden(true)
    }

    private var color: Color {
        switch state {
        case .running: .green
        case .starting, .stopping: .orange
        case .locked: .red
        default: .gray
        }
    }
}

/// A warning about one key, shown above a list.
struct ProblemBanner: View {
    var text: String

    var body: some View {
        Label(text, systemImage: "exclamationmark.triangle")
            .font(.footnote)
            .foregroundStyle(.orange)
    }
}

/// A block of shell commands with a Copy button. On a Mac with the same Apple
/// account, the copied text can be pasted straight into Terminal.
struct CommandBlock: View {
    var title: String
    var command: String
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(title).font(.footnote).foregroundStyle(.secondary)
                Spacer()
                Button(copied ? "Copied" : "Copy") {
                    UIPasteboard.general.string = command
                    copied = true
                    Task {
                        try? await Task.sleep(for: .seconds(2))
                        copied = false
                    }
                }
                .font(.footnote.weight(.semibold))
            }
            Text(command)
                .font(.system(size: 11, design: .monospaced))
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 4)
    }
}

/// 15 minutes, an hour or a day: the period of a logs or metrics screen.
struct PeriodPicker: View {
    @Binding var minutes: Int
    private static let ranges = [(label: "15 min", minutes: 15), (label: "1 hour", minutes: 60), (label: "24 hours", minutes: 1440)]

    var body: some View {
        Picker("Period", selection: $minutes) {
            ForEach(Self.ranges, id: \.minutes) { Text($0.label).tag($0.minutes) }
        }
        .pickerStyle(.segmented)
        .listRowBackground(Color.clear)
        .listRowInsets(EdgeInsets())
    }
}

/// What to tell the user when Cockpit refuses or fails.
func cockpitFailure(_ error: Error) -> String {
    if let error = error as? APIError, error.isForbidden {
        return "This key may not read Cockpit. Give its application the ObservabilityReadOnly permission set."
    }
    return error.localizedDescription
}

/// Shown in place of a Pro feature: what it is, and the way to get it.
struct ProGate: View {
    var text: String
    @State private var showPro = false

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "star.circle").font(.system(size: 44)).foregroundStyle(.tint)
            Text(text).multilineTextAlignment(.center).foregroundStyle(.secondary)
            Button("Get Pro") { showPro = true }
                .buttonStyle(.borderedProminent)
        }
        .padding()
        .sheet(isPresented: $showPro) { ProView() }
    }
}
