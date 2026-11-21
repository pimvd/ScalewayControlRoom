import XCTest
@testable import ControlRoomKit

final class LogicTests: XCTestCase {
    func server(_ id: String, _ name: String, _ state: ServerState, tags: [String] = []) -> Server {
        Server(id: id, name: name, state: state, commercialType: "DEV1-S", zone: .frPar1, project: "p", tags: tags)
    }

    func testZonesKnowTheirRegion() {
        XCTAssertEqual(Zone.frPar2.region, .frPar)
        XCTAssertEqual(Zone.itMil1.region, .itMil)
        XCTAssertEqual(Set(Zone.allCases.map(\.region)), Set(Region.allCases))
    }

    func testAServerTwoKeysSeeIsListedOnceWithBothKeys() {
        let shared = server("1", "web", .running)
        let merged = mergeByID([(keyID: "a", items: [shared]), (keyID: "b", items: [shared, server("2", "db", .running)])])
        XCTAssertEqual(merged.count, 2)
        XCTAssertEqual(merged.first { $0.id == "1" }?.keyIDs, ["a", "b"])
    }

    func testRunningServersComeFirstThenByName() {
        let sorted = sortServers(mergeByID([(keyID: "a", items: [
            server("1", "b", .stopped), server("2", "z", .running), server("3", "a", .running),
        ])]))
        XCTAssertEqual(sorted.map(\.item.name), ["a", "z", "b"])
    }

    func testFilters() {
        let list = mergeByID([(keyID: "a", items: [
            server("1", "web-prod", .running, tags: ["prod"]), server("2", "staging", .stopped, tags: ["dev"]),
        ])])
        XCTAssertEqual(filterServers(list, query: "", state: .running).map(\.id), ["1"])
        XCTAssertEqual(filterServers(list, query: "", state: .stopped).map(\.id), ["2"])
        XCTAssertEqual(filterServers(list, query: "WEB", state: .all).map(\.id), ["1"])
        XCTAssertEqual(filterServers(list, query: "dev", state: .all).map(\.id), ["2"])
    }

    func testEachOrganizationIsCountedOnce() {
        let keys = [APIKey(id: "a", label: "a", secretKey: "s", organizationID: "o1"),
                    APIKey(id: "b", label: "b", secretKey: "s", organizationID: "o1"),
                    APIKey(id: "c", label: "c", secretKey: "s", organizationID: "o2")]
        XCTAssertEqual(uniqueOrganizations(keys).map(\.id), ["a", "c"])
    }

    func testCostsSumLargestFirstAndSubtractDiscounts() {
        let costs = MonthlyCosts(items: [
            Consumption(amount: 1, category: "Storage", product: "x", project: "p"),
            Consumption(amount: 5, category: "Compute", product: "y", project: "p"),
            Consumption(amount: 2, category: "Storage", product: "z", project: "q"),
        ], discount: 1.5, currency: "EUR")
        let byCategory = sum(costs.items, by: \.category)
        XCTAssertEqual(byCategory.map(\.name), ["Compute", "Storage"])
        XCTAssertEqual(byCategory.map(\.amount), [5, 3])
        XCTAssertEqual(total(costs), 6.5)
    }

    func testPeriodsAreThisMonthAndTheMonthsBefore() {
        let date = ISO8601DateFormatter().date(from: "2026-02-15T12:00:00Z")!
        XCTAssertEqual(recentPeriods(now: date, count: 3), ["2026-02", "2026-01", "2025-12"])
        XCTAssertEqual(formatPeriod("2026-11"), "November 2026")
    }

    func testKeyValidation() {
        let uuid = "1a2b3c4d-1a2b-1a2b-1a2b-1a2b3c4d5e6f"
        XCTAssertNil(validateKey(label: "Work", secretKey: uuid, organizationID: uuid))
        XCTAssertNotNil(validateKey(label: "", secretKey: uuid, organizationID: uuid))
        XCTAssertNotNil(validateKey(label: "Work", secretKey: "SCW123", organizationID: uuid))
    }

    func testReviewIsAskedAtAFewMilestonesOnly() {
        XCTAssertEqual([1, 2, 3, 4, 20, 21, 60].map { shouldAskForReview(successfulActions: $0) },
                       [false, false, true, false, true, false, true])
    }

    func testSetupCommands() {
        for set in Commands.appPermissionSets { XCTAssertTrue(Commands.keySetup.contains(set)) }
        XCTAssertTrue(Commands.keySetup.contains("rules.0.organization-id=$ORG"))
        let s = Server(id: "11111111-2222", name: "web", state: .running, commercialType: "DEV1-S", zone: .nlAms2, project: "p")
        XCTAssertTrue(Commands.cockpitSources(for: s).contains("type=logs project-id=p region=nl-ams"))
        XCTAssertTrue(Commands.cockpitSources(for: s).contains("type=metrics project-id=p region=nl-ams"))
        XCTAssertTrue(Commands.alloyConfig(for: s).contains(#"resource_id   = "11111111-2222""#))
        XCTAssertTrue(Commands.alloyConfig(for: s).contains(#""${LOGS_URL}/loki/api/v1/push""#))
        XCTAssertTrue(Commands.alloyConfig(for: s).contains(#""${METRICS_URL}/api/v1/push""#))
        // Both writers carry the label the app queries by.
        XCTAssertEqual(Commands.alloyConfig(for: s).components(separatedBy: "resource_id   = ").count, 3)
    }

    func testScreenshotRoutes() {
        XCTAssertEqual(screenshotDestination("/").tab, .instances)
        XCTAssertEqual(screenshotDestination("/costs").tab, .costs)
        XCTAssertEqual(screenshotDestination("/instance/demo-1").path, [.instance("demo-1")])
        let logs = screenshotDestination("/logs?kind=serverless&id=demo-c1")
        XCTAssertEqual(logs.tab, .serverless)
        XCTAssertEqual(logs.path, [.serverless("demo-c1"), .logs(.serverless, "demo-c1")])
        let metric = screenshotDestination("/metric?kind=serverless&id=demo-c1&name=cpu_usage_ratio")
        XCTAssertEqual(metric.path, [.serverless("demo-c1"), .metrics(.serverless, "demo-c1"),
                                     .metric(.serverless, "demo-c1", name: "cpu_usage_ratio")])
        XCTAssertEqual(screenshotDestination("/metrics?kind=instance&id=demo-3").path,
                       [.instance("demo-3"), .metrics(.instance, "demo-3")])
    }

    func testMetricQueriesRateCountersAndAverageTheRest() {
        XCTAssertEqual(metricQuery("http_requests_total", resourceID: "r"), #"sum(rate(http_requests_total{resource_id="r"}[5m]))"#)
        XCTAssertEqual(metricQuery("memory_usage_bytes", resourceID: "r"), #"avg(memory_usage_bytes{resource_id="r"})"#)
        XCTAssertEqual(metricStep(minutes: 15), 15)
        XCTAssertEqual(metricStep(minutes: 1440), 864)
    }

    func testMetricValuesUseTheUnitTheirNameSuggests() {
        XCTAssertEqual(formatMetric(183_400_000, name: "memory_usage_bytes"), "183.4 MB")
        XCTAssertEqual(formatMetric(512, name: "network_receive_bytes_total"), "512 B/s")
        XCTAssertEqual(formatMetric(0.354, name: "cpu_usage_ratio"), "35.4%")
        XCTAssertEqual(formatMetric(42.25, name: "http_requests_total"), "42.2/s")
        XCTAssertEqual(formatMetric(2, name: "instances"), "2.0")
        XCTAssertEqual(formatMetric(0, name: "http_requests_total"), "0/s")
    }
}

final class DemoTests: XCTestCase {
    func testStoppingADemoServerSettlesOnStopped() async throws {
        await DemoState.shared.reset()
        let demo = DemoClient(latency: .zero, settle: .milliseconds(50))
        try await demo.act(zone: .frPar1, serverID: "demo-1", action: .poweroff)
        var state = try await demo.listServers(zone: .frPar1).first { $0.id == "demo-1" }?.state
        XCTAssertEqual(state, .stopping)
        try await Task.sleep(for: .milliseconds(300))
        state = try await demo.listServers(zone: .frPar1).first { $0.id == "demo-1" }?.state
        XCTAssertEqual(state, .stopped)
        await DemoState.shared.reset()
    }

    func testServerlessAppsHaveMetricsAndInstancesDoNot() async throws {
        let demo = DemoClient(latency: .zero)
        let app = try await demo.metricNames(target: CockpitTarget(projectID: "webshop", region: .frPar, resourceID: "demo-c1"), minutes: 60)
        let instance = try await demo.metricNames(target: CockpitTarget(projectID: "webshop", region: .frPar, resourceID: "demo-3"), minutes: 60)
        XCTAssertFalse(app.names.isEmpty)
        XCTAssertTrue(instance.names.isEmpty)
        let points = try await demo.metric("cpu_usage_ratio", target: CockpitTarget(projectID: "webshop", region: .frPar, resourceID: "demo-c1"), minutes: 60)
        XCTAssertGreaterThan(points.count, 50)
    }

    func testServerlessAppsHaveLogsAndInstancesDoNot() async throws {
        let demo = DemoClient(latency: .zero)
        let app = try await demo.logs(target: CockpitTarget(projectID: "webshop", region: .frPar, resourceID: "demo-c1"), minutes: 60)
        let instance = try await demo.logs(target: CockpitTarget(projectID: "webshop", region: .frPar, resourceID: "demo-3"), minutes: 60)
        XCTAssertFalse(app.lines.isEmpty)
        XCTAssertTrue(instance.lines.isEmpty)
    }
}
