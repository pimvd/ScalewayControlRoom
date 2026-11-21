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
