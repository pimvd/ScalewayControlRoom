import Charts
import ControlRoomKit
import SwiftUI

/// The metrics Cockpit has for a resource, as a dashboard: a chart per
/// metric, a line per instance. Names are found, not assumed: whatever
/// carries the resource's label is shown.
struct MetricsView: View {
    @Environment(AppModel.self) private var model
    @Environment(ProStore.self) private var pro
    var kind: LogKind
    var id: String
    @State private var minutes = 60
    @State private var result: MetricNames?
    @State private var failure: String?
    /// Bumped by pull to refresh, so every chart reloads.
    @State private var reloads = 0

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
                    ForEach((result?.names ?? []).filter(isChartable), id: \.self) { name in
                        NavigationLink(value: Destination.metric(kind, id, name: name)) {
                            MetricCard(kind: kind, id: id, name: name, minutes: minutes, reloads: reloads)
                        }
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
                .refreshable {
                    reloads += 1
                    await load()
                }
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

/// Loads one metric's series per instance.
@MainActor
private func loadSeries(_ model: AppModel, kind: LogKind, id: String, name: String, minutes: Int) async throws -> [MetricSeries] {
    guard let resource = model.cockpitResource(kind, id) else { return [] }
    let target = resource.target
    return try await model.withKeys(resource.keyIDs) { try await $0.metric(name, target: target, minutes: minutes) }
}

/// The newest value of any instance.
private func latest(_ series: [MetricSeries]) -> MetricPoint? {
    series.compactMap(\.points.last).max { $0.time < $1.time }
}

/// One metric on the dashboard: title, latest value and a small chart.
private struct MetricCard: View {
    @Environment(AppModel.self) private var model
    var kind: LogKind
    var id: String
    var name: String
    var minutes: Int
    var reloads: Int
    @State private var series: [MetricSeries]?
    @State private var failure: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(metricTitle(name)).font(.headline)
                Spacer()
                if let point = series.flatMap(latest) {
                    Text(formatMetric(point.value, name: name)).font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                }
            }
            if let failure {
                Text(failure).font(.footnote).foregroundStyle(.secondary)
            } else if let series {
                if series.isEmpty {
                    Text("No data in this period.").font(.footnote).foregroundStyle(.secondary)
                } else {
                    MetricChart(series: series, name: name).frame(height: 120)
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 120)
            }
        }
        .padding(.vertical, 4)
        .task(id: "\(minutes)-\(reloads)") {
            failure = nil
            do {
                series = try await loadSeries(model, kind: kind, id: id, name: name, minutes: minutes)
            } catch {
                failure = cockpitFailure(error)
            }
        }
    }
}

/// A line per instance; a single line also gets a shaded area.
private struct MetricChart: View {
    var series: [MetricSeries]
    var name: String

    var body: some View {
        Chart {
            ForEach(series, id: \.instance) { line in
                ForEach(line.points, id: \.time) { point in
                    if series.count == 1 {
                        LineMark(x: .value("Time", point.time), y: .value("Value", point.value))
                            .interpolationMethod(.monotone)
                        AreaMark(x: .value("Time", point.time), y: .value("Value", point.value))
                            .foregroundStyle(.tint.opacity(0.15))
                            .interpolationMethod(.monotone)
                    } else {
                        LineMark(x: .value("Time", point.time), y: .value("Value", point.value),
                                 series: .value("Instance", line.instance))
                            .foregroundStyle(by: .value("Instance", instanceLabel(line.instance)))
                            .interpolationMethod(.monotone)
                    }
                }
            }
        }
        .chartLegend(series.count > 1 ? .visible : .hidden)
        .chartYAxis {
            AxisMarks { value in
                AxisGridLine()
                AxisValueLabel { Text(value.as(Double.self).map { formatMetric($0, name: name) } ?? "") }
            }
        }
    }
}

/// One metric as a large chart, with its latest value.
struct MetricChartView: View {
    @Environment(AppModel.self) private var model
    var kind: LogKind
    var id: String
    var name: String
    @State private var minutes = 60
    @State private var series: [MetricSeries]?
    @State private var failure: String?

    var body: some View {
        List {
            PeriodPicker(minutes: $minutes)
            if let failure { ProblemBanner(text: failure) }
            if let series {
                if let last = latest(series) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Latest").font(.subheadline).foregroundStyle(.secondary)
                        Text(formatMetric(last.value, name: name)).font(.system(size: 34, weight: .bold))
                    }
                    MetricChart(series: series, name: name)
                        .frame(height: 240)
                        .padding(.vertical, 8)
                } else {
                    Text("No data in this period.").foregroundStyle(.secondary)
                }
            } else if failure == nil {
                ProgressView()
            }
            Text(name).font(.system(.footnote, design: .monospaced)).foregroundStyle(.secondary)
            if isCounter(name) {
                Text("A counter: shown as its rate per second.").font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle(metricTitle(name))
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await load() }
        .task(id: minutes) { await load() }
    }

    private func load() async {
        failure = nil
        do {
            series = try await loadSeries(model, kind: kind, id: id, name: name, minutes: minutes)
        } catch {
            failure = cockpitFailure(error)
        }
    }
}
