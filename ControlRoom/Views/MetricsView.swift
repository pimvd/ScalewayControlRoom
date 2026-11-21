import Charts
import ControlRoomKit
import SwiftUI

/// The metrics Cockpit has for a resource. Their names are found, not
/// assumed: whatever carries the resource's ID is listed.
struct MetricsView: View {
    @Environment(AppModel.self) private var model
    @Environment(ProStore.self) private var pro
    var kind: LogKind
    var id: String
    @State private var minutes = 60
    @State private var result: MetricNames?
    @State private var failure: String?

    private var resource: AppModel.CockpitResource? { model.cockpitResource(kind, id) }

    var body: some View {
        Group {
            if !pro.isPro(keys: model.keys) {
                ProGate(text: "Metrics from Cockpit are part of Pro.")
            } else if resource == nil {
                ContentUnavailableView("This resource is no longer listed.", systemImage: "chart.xyaxis.line")
            } else {
                List {
                    PeriodPicker(minutes: $minutes)
                    if let failure { ProblemBanner(text: failure) }
                    if result == nil && failure == nil { ProgressView() }
                    ForEach(result?.names ?? [], id: \.self) { name in
                        NavigationLink(name, value: Destination.metric(kind, id, name: name))
                            .font(.system(.body, design: .monospaced))
                    }
                    if let result, result.names.isEmpty {
                        if let server = resource?.server {
                            InstanceCockpitSetupView(server: server, what: "metrics")
                        } else {
                            Text("No metrics in this period. Scaleway sends serverless metrics to Cockpit by itself; they appear once the app has run.")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .refreshable { await load() }
            }
        }
        .navigationTitle("Metrics")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: "\(minutes)-\(resource != nil)") { await load() }
    }

    private func load() async {
        guard let resource else { return }
        let target = resource.target
        let minutes = self.minutes
        failure = nil
        do {
            result = try await model.withKeys(resource.keyIDs) { try await $0.metricNames(target: target, minutes: minutes) }
        } catch {
            failure = cockpitFailure(error)
        }
    }
}

/// One metric as a chart, with its latest value.
struct MetricChartView: View {
    @Environment(AppModel.self) private var model
    var kind: LogKind
    var id: String
    var name: String
    @State private var minutes = 60
    @State private var points: [MetricPoint]?
    @State private var failure: String?

    var body: some View {
        List {
            PeriodPicker(minutes: $minutes)
            if let failure { ProblemBanner(text: failure) }
            if let points {
                if let last = points.last {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Latest").font(.subheadline).foregroundStyle(.secondary)
                        Text(formatMetric(last.value, name: name)).font(.system(size: 34, weight: .bold))
                    }
                    Chart(points, id: \.time) { point in
                        LineMark(x: .value("Time", point.time), y: .value("Value", point.value))
                            .interpolationMethod(.monotone)
                        AreaMark(x: .value("Time", point.time), y: .value("Value", point.value))
                            .foregroundStyle(.tint.opacity(0.15))
                            .interpolationMethod(.monotone)
                    }
                    .chartYAxis {
                        AxisMarks { value in
                            AxisGridLine()
                            AxisValueLabel { Text(value.as(Double.self).map { formatMetric($0, name: name) } ?? "") }
                        }
                    }
                    .frame(height: 240)
                    .padding(.vertical, 8)
                } else {
                    Text("No data in this period.").foregroundStyle(.secondary)
                }
            } else if failure == nil {
                ProgressView()
            }
            if isCounter(name) {
                Text("A counter: shown as its rate per second.").font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle(name)
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await load() }
        .task(id: minutes) { await load() }
    }

    private func load() async {
        guard let resource = model.cockpitResource(kind, id) else { return }
        let target = resource.target
        let (name, minutes) = (self.name, self.minutes)
        failure = nil
        do {
            points = try await model.withKeys(resource.keyIDs) { try await $0.metric(name, target: target, minutes: minutes) }
        } catch {
            failure = cockpitFailure(error)
        }
    }
}
