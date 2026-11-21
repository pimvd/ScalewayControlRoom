import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import XCTest
@testable import ControlRoomKit

/// Answers requests from canned JSON by path, and records what was asked.
final class FakeTransport: HTTPTransport, @unchecked Sendable {
    var responses: [String: (Int, String)] = [:]
    var requests: [URLRequest] = []
    private let lock = NSLock()

    func send(_ request: URLRequest) async throws -> (Data, Int) {
        let (status, body) = lock.withLock {
            requests.append(request)
            return responses[request.url!.path] ?? (404, #"{"message":"not found"}"#)
        }
        return (Data(body.utf8), status)
    }

    func queries(for path: String) -> [[String: String]] {
        lock.withLock { requests }.filter { $0.url!.path == path }.map { request in
            Dictionary((URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems ?? [])
                .map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { a, _ in a })
        }
    }
}

final class ScalewayAPITests: XCTestCase {
    let key = APIKey(id: "k", label: "Work", secretKey: "secret", organizationID: "org-1")

    func testListsServersAndSendsTheSecretKey() async throws {
        let transport = FakeTransport()
        transport.responses["/instance/v1/zones/nl-ams-1/servers"] = (200, """
            {"servers": [{"id": "1", "name": "web", "state": "stopped in place", "commercial_type": "DEV1-S",
              "zone": "nl-ams-1", "project": "p", "tags": ["prod"], "public_ips": [{"address": "51.15.0.1"}]}]}
            """)
        let servers = try await ScalewayAPI(key: key, transport: transport).listServers(zone: .nlAms1)
        XCTAssertEqual(servers, [Server(id: "1", name: "web", state: .stoppedInPlace, commercialType: "DEV1-S",
                                        zone: .nlAms1, project: "p", tags: ["prod"], publicIPs: ["51.15.0.1"])])
        XCTAssertEqual(transport.requests.first?.value(forHTTPHeaderField: "X-Auth-Token"), "secret")
        XCTAssertEqual(transport.queries(for: "/instance/v1/zones/nl-ams-1/servers").first?["per_page"], "100")
    }

    func testAnErrorCarriesScalewaysMessage() async {
        let transport = FakeTransport()
        transport.responses["/instance/v1/zones/fr-par-1/servers"] = (403, #"{"message": "insufficient permissions"}"#)
        do {
            _ = try await ScalewayAPI(key: key, transport: transport).listServers(zone: .frPar1)
            XCTFail("expected an error")
        } catch let error as APIError {
            XCTAssertTrue(error.isForbidden)
            XCTAssertEqual(error.message, "insufficient permissions")
        } catch {
            XCTFail("unexpected \(error)")
        }
    }

    func testARegionWithoutTheProductIsRecognised() async {
        let transport = FakeTransport()
        transport.responses["/containers/v1/regions/it-mil/namespaces"] = (404, #"{"message": "unknown service"}"#)
        do {
            _ = try await ScalewayAPI(key: key, transport: transport).listServerless(region: .itMil)
            XCTFail("expected an error")
        } catch let error as APIError {
            XCTAssertTrue(error.isUnknownService)
            XCTAssertFalse(error.isForbidden)
        } catch {
            XCTFail("unexpected \(error)")
        }
    }

    func testActionsPostTheAction() async throws {
        let transport = FakeTransport()
        transport.responses["/instance/v1/zones/fr-par-1/servers/1/action"] = (202, #"{"task": {}}"#)
        try await ScalewayAPI(key: key, transport: transport).act(zone: .frPar1, serverID: "1", action: .poweroff)
        let request = transport.requests.first!
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(String(data: request.httpBody!, encoding: .utf8), #"{"action":"poweroff"}"#)
    }

    func testCostsReadMoneyWhoseUnitsMayBeAString() async throws {
        let transport = FakeTransport()
        transport.responses["/billing/v2beta1/consumptions"] = (200, """
            {"consumptions": [
              {"value": {"currency_code": "EUR", "units": "12", "nanos": 500000000}, "category_name": "Compute",
               "product_name": "Instances", "project_name": "webshop", "project_id": "p1"},
              {"value": {"currency_code": "EUR", "units": 3, "nanos": 0}, "category_name": "Storage",
               "product_name": "Block", "project_name": "", "project_id": "p2"}],
             "total_discount_untaxed_value": -1.5}
            """)
        let costs = try await ScalewayAPI(key: key, transport: transport).costs(period: "2026-11")
        XCTAssertEqual(costs.items.map(\.amount), [12.5, 3])
        XCTAssertEqual(costs.items.map(\.project), ["webshop", "p2"])
        XCTAssertEqual(costs.discount, 1.5)
        XCTAssertEqual(total(costs), 14)
        let query = transport.queries(for: "/billing/v2beta1/consumptions").first!
        XCTAssertEqual(query["organization_id"], "org-1")
        XCTAssertEqual(query["billing_period"], "2026-11")
    }

    func testPricesKeepTheCommercialTypesAsTheyAre() async throws {
        let transport = FakeTransport()
        transport.responses["/instance/v1/zones/fr-par-1/products/servers"] = (200,
            #"{"servers": {"DEV1-S": {"hourly_price": 0.0109, "ncpus": 2}, "POP2_HC-2C": {"hourly_price": 0.07}}}"#)
        let prices = try await ScalewayAPI(key: key, transport: transport).hourlyPrices(zone: .frPar1)
        XCTAssertEqual(prices, ["DEV1-S": 0.0109, "POP2_HC-2C": 0.07])
    }

    func testServerlessTakesTheProjectFromTheNamespace() async throws {
        let transport = FakeTransport()
        transport.responses["/containers/v1/regions/fr-par/namespaces"] = (200,
            #"{"namespaces": [{"id": "ns-c", "project_id": "p-c"}]}"#)
        transport.responses["/containers/v1/regions/fr-par/containers"] = (200, """
            {"containers": [{"id": "c1", "name": "api", "status": "ready", "namespace_id": "ns-c",
              "min_scale": 0, "max_scale": 5, "public_endpoint": "api.example"}]}
            """)
        transport.responses["/functions/v1beta1/regions/fr-par/namespaces"] = (200,
            #"{"namespaces": [{"id": "ns-f", "project_id": "p-f"}]}"#)
        transport.responses["/functions/v1beta1/regions/fr-par/functions"] = (200, """
            {"functions": [{"id": "f1", "name": "hook", "status": "ready", "namespace_id": "ns-f",
              "min_scale": 0, "max_scale": 1, "domain_name": "hook.example"}]}
            """)
        let apps = try await ScalewayAPI(key: key, transport: transport).listServerless(region: .frPar)
        XCTAssertEqual(apps.map(\.projectID), ["p-c", "p-f"])
        XCTAssertEqual(apps.map(\.kind), [.container, .function])
        XCTAssertEqual(apps.map(\.endpoint), ["api.example", "hook.example"])
        XCTAssertEqual(transport.queries(for: "/functions/v1beta1/regions/fr-par/functions").first?["namespace_id"], "ns-f")
    }

    func testLogsQueryEveryDataSourceByResourceIDNewestFirst() async throws {
        let transport = FakeTransport()
        transport.responses["/cockpit/v1/regions/fr-par/data-sources"] = (200, """
            {"data_sources": [{"url": "https://a.logs.cockpit.fr-par.scw.cloud"},
                              {"url": "https://b.logs.cockpit.fr-par.scw.cloud"}]}
            """)
        // Both data sources answer on the same path; the fake does not tell hosts apart.
        transport.responses["/loki/api/v1/query_range"] = (200, """
            {"data": {"result": [{"values": [["1700000000000000000", "a"], ["1700000002000000000", "c"]]},
                                 {"values": [["1700000001000000000", "b"]]}]}}
            """)
        let result = try await ScalewayAPI(key: key, transport: transport)
            .logs(target: CockpitTarget(projectID: "p", region: .frPar, resourceID: "r1"), minutes: 60)
        XCTAssertEqual(result.sources, 2)
        XCTAssertEqual(result.lines.prefix(3).map(\.line), ["c", "c", "b"])
        XCTAssertEqual(result.lines.first?.time, Date(timeIntervalSince1970: 1_700_000_002))
        let query = transport.queries(for: "/loki/api/v1/query_range").first!
        XCTAssertEqual(query["query"], #"{resource_id="r1"}"#)
        XCTAssertEqual(transport.queries(for: "/cockpit/v1/regions/fr-par/data-sources").first?["project_id"], "p")
    }

    func testMetricsAreFoundByResourceIDAndQueriedThroughPrometheus() async throws {
        let transport = FakeTransport()
        transport.responses["/cockpit/v1/regions/fr-par/data-sources"] = (200,
            #"{"data_sources": [{"url": "https://m.metrics.cockpit.fr-par.scw.cloud"}]}"#)
        transport.responses["/prometheus/api/v1/label/__name__/values"] = (200,
            #"{"status": "success", "data": ["requests_total", "cpu_usage_ratio"]}"#)
        transport.responses["/prometheus/api/v1/query_range"] = (200, """
            {"status": "success", "data": {"resultType": "matrix",
              "result": [{"metric": {}, "values": [[1700000000, "0.25"], [1700000060.5, "0.5"]]}]}}
            """)
        let api = ScalewayAPI(key: key, transport: transport)
        let target = CockpitTarget(projectID: "p", region: .frPar, resourceID: "r1")

        let names = try await api.metricNames(target: target, minutes: 60)
        XCTAssertEqual(names.names, ["cpu_usage_ratio", "requests_total"])
        XCTAssertEqual(transport.queries(for: "/prometheus/api/v1/label/__name__/values").first?["match[]"], #"{resource_id="r1"}"#)
        XCTAssertEqual(transport.queries(for: "/cockpit/v1/regions/fr-par/data-sources").first?["types"], "metrics")

        let points = try await api.metric("requests_total", target: target, minutes: 60)
        XCTAssertEqual(points.map(\.value), [0.25, 0.5])
        XCTAssertEqual(points.last?.time, Date(timeIntervalSince1970: 1_700_000_060.5))
        let query = transport.queries(for: "/prometheus/api/v1/query_range").first!
        XCTAssertEqual(query["query"], #"sum(rate(requests_total{resource_id="r1"}[5m]))"#)
        XCTAssertEqual(query["step"], "36")
    }

    func testListsFollowPagesUntilAShortOne() async throws {
        final class Paged: HTTPTransport, @unchecked Sendable {
            var pages: [String] = []
            func send(_ request: URLRequest) async throws -> (Data, Int) {
                let page = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!
                    .queryItems!.first { $0.name == "page" }!.value!
                pages.append(page)
                let count = page == "1" ? 100 : 3
                let servers = (0..<count).map { #"{"id": "\#(page)-\#($0)", "name": "s", "state": "running", "commercial_type": "DEV1-S", "zone": "fr-par-1", "project": "p"}"# }
                return (Data(#"{"servers": [\#(servers.joined(separator: ","))]}"#.utf8), 200)
            }
        }
        let transport = Paged()
        let servers = try await ScalewayAPI(key: key, transport: transport).listServers(zone: .frPar1)
        XCTAssertEqual(servers.count, 103)
        XCTAssertEqual(transport.pages, ["1", "2"])
    }
}
