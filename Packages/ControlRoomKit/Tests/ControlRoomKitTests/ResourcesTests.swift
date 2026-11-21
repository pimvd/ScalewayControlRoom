import XCTest
@testable import ControlRoomKit

final class ResourcesTests: XCTestCase {
    let key = APIKey(id: "k", label: "Work", secretKey: "secret", organizationID: "org-1")

    func testProductsAreReadFromTheirListField() async throws {
        let transport = FakeTransport()
        transport.responses["/rdb/v1/regions/nl-ams/instances"] = (200, """
            {"instances": [{"id": "db1", "name": "psql-prd", "status": "ready", "region": "nl-ams", "project_id": "p1"}]}
            """)
        let product = Products.all.first { $0.type == "Managed Database" }!
        let found = try await ScalewayAPI(key: key, transport: transport).listResources(product, place: "nl-ams")
        XCTAssertEqual(found, [Resource(id: "db1", name: "psql-prd", type: "Managed Database", status: "ready",
                                        location: "nl-ams", projectID: "p1")])
        let query = transport.queries(for: "/rdb/v1/regions/nl-ams/instances").first
        XCTAssertEqual(query?["organization_id"], "org-1")
        XCTAssertEqual(query?["page_size"], "50")
    }

    func testTheInstanceAPISaysOrganizationProjectAndState() async throws {
        let transport = FakeTransport()
        transport.responses["/instance/v1/zones/fr-par-1/ips"] = (200, """
            {"ips": [{"id": "ip1", "address": "51.15.0.1", "state": "attached", "zone": "fr-par-1", "project": "p1"}]}
            """)
        let product = Products.all.first { $0.type == "Flexible IP" }!
        let found = try await ScalewayAPI(key: key, transport: transport).listResources(product, place: "fr-par-1")
        XCTAssertEqual(found.first?.name, "51.15.0.1")
        XCTAssertEqual(found.first?.status, "attached")
        XCTAssertEqual(found.first?.projectID, "p1")
        XCTAssertEqual(found.first?.region, .frPar)
        let query = transport.queries(for: "/instance/v1/zones/fr-par-1/ips").first
        XCTAssertEqual(query?["organization"], "org-1")
        XCTAssertEqual(query?["per_page"], "50")
    }

    func testDNSZonesAreNamedByTheirDomainAndAreGlobal() async throws {
        let transport = FakeTransport()
        transport.responses["/domain/v2beta1/dns-zones"] = (200, """
            {"dns_zones": [{"domain": "example.com", "subdomain": "", "status": "active", "project_id": "p1"},
                           {"domain": "example.com", "subdomain": "api", "status": "active", "project_id": "p1"},
                           {"domain": "privatedns", "subdomain": "pn.vpc", "status": "active", "project_id": "p1"}]}
            """)
        let product = Products.all.first { $0.type == "DNS zone" }!
        let found = try await ScalewayAPI(key: key, transport: transport).listResources(product, place: nil)
        XCTAssertEqual(found.map(\.name), ["example.com", "api.example.com"])
        XCTAssertEqual(found.map(\.location), ["global", "global"])
        XCTAssertNil(found[0].cockpitTarget)
    }

    func testTooManyRequestsAreRetried() async throws {
        final class Flaky: HTTPTransport, @unchecked Sendable {
            var calls = 0
            func send(_ request: URLRequest) async throws -> (Data, Int) {
                calls += 1
                return calls == 1 ? (Data(), 429) : (Data(#"{"secrets": []}"#.utf8), 200)
            }
        }
        let transport = Flaky()
        let product = Products.all.first { $0.type == "Secret" }!
        let found = try await ScalewayAPI(key: key, transport: transport).listResources(product, place: "fr-par")
        XCTAssertEqual(found, [])
        XCTAssertEqual(transport.calls, 2)
    }

    func testListingSkipsMissingProductsAndNamesFailures() async {
        let db = Product("Database", "db/{place}", "items", .regional)
        let mail = Product("Mail", "mail/{place}", "items", .regional)
        let fake = FakeTransport()
        fake.responses["/db/fr-par"] = (200, #"{"items": [{"id": "d1", "name": "orders"}]}"#)
        for region in ["nl-ams", "pl-waw", "it-mil"] { fake.responses["/db/\(region)"] = (501, #"{"message": "unknown service"}"#) }
        fake.responses["/mail/fr-par"] = (500, #"{"message": "boom"}"#)
        for region in ["nl-ams", "pl-waw", "it-mil"] { fake.responses["/mail/\(region)"] = (403, #"{"message": "denied"}"#) }
        let result = await listResources(ScalewayAPI(key: key, transport: fake), products: [db, mail])
        XCTAssertEqual(result.resources.map(\.name), ["orders"])
        XCTAssertEqual(result.resources.first?.location, "fr-par")
        XCTAssertEqual(result.problem, "Could not load Mail (fr-par): boom")
    }

    func testAKeyThatMaySeeNothingIsTold() async {
        let fake = FakeTransport()
        for region in Region.allCases { fake.responses["/db/\(region.rawValue)"] = (403, #"{"message": "denied"}"#) }
        let result = await listResources(ScalewayAPI(key: key, transport: fake),
                                         products: [Product("Database", "db/{place}", "items", .regional)])
        XCTAssertEqual(result.problem, "This key may not list other resources. Add AllProductsReadOnly to its policy.")
    }

    func testRowsAreGroupedAndSearched() {
        func row(_ name: String, _ type: String, _ location: String, _ project: String) -> ResourceRow {
            ResourceRow(Listed(item: Resource(id: name, name: name, type: type, status: "ready", location: location,
                                              projectID: project), keyIDs: ["k"]))
        }
        let rows = [row("orders-db", "Managed Database", "fr-par", "p1"), row("uploads", "Block volume", "fr-par-1", "p1"),
                    row("archive", "Block volume", "nl-ams-1", "p2")]
        let names = ["p1": "Webshop"]
        XCTAssertEqual(groupResources(rows, by: .type, query: "", projectNames: names).map(\.title),
                       ["Block volume", "Managed Database"])
        XCTAssertEqual(groupResources(rows, by: .type, query: "", projectNames: names)[0].rows.map(\.name), ["archive", "uploads"])
        XCTAssertEqual(groupResources(rows, by: .project, query: "", projectNames: names).map(\.title), ["p2", "Webshop"])
        XCTAssertEqual(groupResources(rows, by: .location, query: "volume webshop", projectNames: names).map(\.title), ["fr-par-1"])
    }

    func testEveryKindOfResourceBecomesARow() {
        let server = Server(id: "s1", name: "web", state: .running, commercialType: "DEV1-S", zone: .frPar1, project: "p")
        XCTAssertEqual(ResourceRow(Listed(item: server, keyIDs: ["k"])).destination, .instance("s1"))
        XCTAssertEqual(ResourceRow(Listed(item: Demo.apps[0], keyIDs: ["k"])).type, "Serverless container")
        XCTAssertEqual(ResourceRow(Listed(item: Demo.resources[0], keyIDs: ["k"])).destination, .resource("demo-r1"))
    }

    func testDemoHasOtherResources() async throws {
        let result = await listResources(DemoClient(latency: .zero))
        XCTAssertEqual(result.resources.count, Demo.resources.count)
        XCTAssertNil(result.problem)
    }

    func testResourceRoutes() {
        XCTAssertEqual(screenshotDestination("/other").tab, .other)
        XCTAssertEqual(screenshotDestination("/resource/demo-r1").path, [.resource("demo-r1")])
        XCTAssertEqual(screenshotDestination("/logs?kind=resource&id=demo-r1").path, [.resource("demo-r1"), .logs(.resource, "demo-r1")])
    }
}
