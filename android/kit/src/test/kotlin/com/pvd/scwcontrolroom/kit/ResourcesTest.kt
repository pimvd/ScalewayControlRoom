package com.pvd.scwcontrolroom.kit

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourcesTest {
    private val key = ApiKey("k", "Work", "secret", "org-1")
    private fun product(type: String) = Products.all.first { it.type == type }
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun productsAreReadFromTheirListField() = runTest {
        val transport = FakeTransport()
        transport.responses["/rdb/v1/regions/nl-ams/instances"] = 200 to """
            {"instances": [{"id": "db1", "name": "psql-prd", "status": "ready", "region": "nl-ams", "project_id": "p1"}]}
            """
        val found = ScalewayApi(key, transport).listResources(product("Managed Database"), "nl-ams")
        assertEquals(listOf(Resource("db1", "psql-prd", "Managed Database", "ready", "nl-ams", "p1")), found)
        val query = transport.queries("/rdb/v1/regions/nl-ams/instances").first()
        assertEquals("org-1", query["organization_id"])
        assertEquals("50", query["page_size"])
    }

    @Test fun theInstanceApiSaysOrganizationProjectAndState() = runTest {
        val transport = FakeTransport()
        transport.responses["/instance/v1/zones/fr-par-1/ips"] = 200 to """
            {"ips": [{"id": "ip1", "address": "51.15.0.1", "state": "attached", "zone": "fr-par-1", "project": "p1"}]}
            """
        val found = ScalewayApi(key, transport).listResources(product("Flexible IP"), "fr-par-1").first()
        assertEquals("51.15.0.1", found.name)
        assertEquals("attached", found.status)
        assertEquals("p1", found.projectID)
        assertEquals(Region.FR_PAR, found.region)
        val query = transport.queries("/instance/v1/zones/fr-par-1/ips").first()
        assertEquals("org-1", query["organization"])
        assertEquals("50", query["per_page"])
    }

    @Test fun dnsZonesAreNamedByTheirDomainAndAreGlobal() = runTest {
        val transport = FakeTransport()
        transport.responses["/domain/v2beta1/dns-zones"] = 200 to """
            {"dns_zones": [{"domain": "example.com", "subdomain": "", "status": "active", "project_id": "p1"},
                           {"domain": "example.com", "subdomain": "api", "status": "active", "project_id": "p1"},
                           {"domain": "privatedns", "subdomain": "pn.vpc", "status": "active", "project_id": "p1"}]}
            """
        val found = ScalewayApi(key, transport).listResources(product("DNS zone"), null)
        assertEquals(listOf("example.com", "api.example.com"), found.map { it.name })
        assertEquals(listOf("global", "global"), found.map { it.location })
        assertNull(found[0].cockpitTarget)
    }

    @Test fun everyFieldBecomesADetailWithTheKeyFieldsFirst() {
        val item = json("""
            {"id": "db1", "name": "psql-prd", "status": "ready", "region": "nl-ams", "project_id": "p1",
             "organization_id": "o1", "srn": "srn://x", "engine": "PostgreSQL-17", "node_type": "db-dev-s",
             "is_ha_cluster": false, "tags": ["prd", "terraform"], "upgradable_version": [], "encryption": {"kms_key_id": null},
             "volume": {"type": "sbs_5k", "size": 10000000000},
             "endpoint": {"id": "e1", "ip": "51.158.240.184", "port": 8758, "name": null},
             "endpoints": [{"id": "e2", "ip": "172.16.12.2", "port": 5432,
                            "private_network": {"private_network_id": "pn1", "service_ip": "172.16.12.2/22", "zone": "nl-ams-1"}},
                           {"id": "e1", "ip": "51.158.240.184", "port": 8758}],
             "settings": [{"name": "max_connections", "value": "100"}],
             "logs_policy": {"max_age_retention": 30}}
            """)
        val details = Resource.parse(item, product("Managed Database"), "nl-ams")!!.details
        val shown = details.associate { it.label to it.value }

        assertEquals(listOf("Engine", "Node type", "Endpoint IP", "Endpoint port", "Volume size", "HA cluster"),
                     details.filter { it.key }.map { it.label })
        assertEquals("10 GB", shown["Volume size"])
        assertEquals("No", shown["HA cluster"])
        assertEquals("8758", shown["Endpoint port"])
        assertEquals("prd, terraform", shown["Tags"])
        assertEquals("172.16.12.2/22", shown["Endpoints 1 private network service IP"])
        assertEquals("51.158.240.184", shown["Endpoints 2 IP"])
        assertEquals("100", shown["Settings max connections"])
        assertEquals("30", shown["Logs policy max age retention"])
        // The header already shows these, IDs are noise, empty values say nothing.
        for (hidden in listOf("ID", "Name", "Status", "Region", "Srn", "Endpoint ID", "Endpoint name", "Upgradable version",
                              "Encryption kms key ID", "Endpoints 1 private network zone")) {
            assertNull(hidden, shown[hidden])
        }
        val rest = details.filter { !it.key }.map { it.label }
        assertEquals(rest.sortedWith(naturalOrder), rest)
    }

    @Test fun detailValuesAreReadable() {
        val item = json("""
            {"id": "k1", "name": "data-protection", "state": "enabled", "region": "nl-ams", "project_id": "p1",
             "usage": {"symmetric_encryption": "aes_256_gcm"}, "protected": true, "rotation_count": 1,
             "rotation_policy": {"rotation_period": "31536000s"}, "description": ""}
            """)
        val details = Resource.parse(item, product("Key"), "nl-ams")!!.details
        val shown = details.associate { it.label to it.value }
        assertEquals("aes_256_gcm", shown["Usage symmetric encryption"])
        assertEquals("365 days", shown["Rotation policy rotation period"])
        assertEquals("Yes", shown["Protected"])
        assertEquals("1", shown["Rotation count"])
        assertNull(shown["Description"])
        assertEquals("Usage symmetric encryption", details.first().label)
    }

    @Test fun sizesAreDecimal() {
        assertEquals("10 GB", Detail.bytes(10_000_000_000))
        assertEquals("1.5 MB", Detail.bytes(1_500_000))
        assertEquals("1.25 GB", Detail.bytes(1_250_000_000))
        assertEquals("512 bytes", Detail.bytes(512))
    }

    @Test fun demoResourcesHaveDetails() {
        assertTrue(Demo.resources.all { it.details.isNotEmpty() })
    }

    @Test fun tooManyRequestsAreRetried() = runTest {
        var calls = 0
        val transport = HttpTransport {
            calls += 1
            if (calls == 1) HttpResponse(429, "") else HttpResponse(200, """{"secrets": []}""")
        }
        assertEquals(emptyList<Resource>(), ScalewayApi(key, transport).listResources(product("Secret"), "fr-par"))
        assertEquals(2, calls)
    }

    @Test fun listingSkipsMissingProductsAndNamesFailures() = runTest {
        val db = Product("Database", "db/{place}", "items", Product.Scope.REGIONAL)
        val mail = Product("Mail", "mail/{place}", "items", Product.Scope.REGIONAL)
        val fake = FakeTransport()
        fake.responses["/db/fr-par"] = 200 to """{"items": [{"id": "d1", "name": "orders"}]}"""
        for (region in listOf("nl-ams", "pl-waw", "it-mil")) fake.responses["/db/$region"] = 501 to """{"message": "unknown service"}"""
        fake.responses["/mail/fr-par"] = 500 to """{"message": "boom"}"""
        for (region in listOf("nl-ams", "pl-waw", "it-mil")) fake.responses["/mail/$region"] = 403 to """{"message": "denied"}"""
        val (resources, problem) = listResources(ScalewayApi(key, fake), listOf(db, mail))
        assertEquals(listOf("orders"), resources.map { it.name })
        assertEquals("fr-par", resources.first().location)
        assertEquals("Could not load Mail (fr-par): boom", problem)
    }

    @Test fun aKeyThatMaySeeNothingIsTold() = runTest {
        val fake = FakeTransport()
        for (region in Region.entries) fake.responses["/db/${region.id}"] = 403 to """{"message": "denied"}"""
        val (_, problem) = listResources(ScalewayApi(key, fake), listOf(Product("Database", "db/{place}", "items", Product.Scope.REGIONAL)))
        assertEquals("This key may not list other resources. Add AllProductsReadOnly to its policy.", problem)
    }

    @Test fun rowsAreGroupedAndSearched() {
        fun row(name: String, type: String, location: String, project: String) =
            ResourceRow.of(Listed(Resource(name, name, type, "ready", location, project), listOf("k")))
        val rows = listOf(row("orders-db", "Managed Database", "fr-par", "p1"), row("uploads", "Block volume", "fr-par-1", "p1"),
                          row("archive", "Block volume", "nl-ams-1", "p2"))
        val names = mapOf("p1" to "Webshop")
        assertEquals(listOf("Block volume", "Managed Database"), groupResources(rows, ResourceGrouping.TYPE, "", names).map { it.first })
        assertEquals(listOf("archive", "uploads"), groupResources(rows, ResourceGrouping.TYPE, "", names)[0].second.map { it.name })
        assertEquals(listOf("p2", "Webshop"), groupResources(rows, ResourceGrouping.PROJECT, "", names).map { it.first })
        assertEquals(listOf("fr-par-1"), groupResources(rows, ResourceGrouping.LOCATION, "volume webshop", names).map { it.first })
    }

    @Test fun everyKindOfResourceBecomesARow() {
        val server = Server("s1", "web", ServerState.RUNNING, "DEV1-S", Zone.FR_PAR_1, "p")
        assertEquals(Destination.Instance("s1"), ResourceRow.ofServer(Listed(server, listOf("k"))).destination)
        assertEquals("Serverless container", ResourceRow.ofApp(Listed(Demo.apps[0], listOf("k"))).type)
        assertEquals(Destination.Resource("demo-r1"), ResourceRow.of(Listed(Demo.resources[0], listOf("k"))).destination)
    }

    @Test fun demoHasOtherResources() = runTest {
        val (resources, problem) = listResources(DemoClient(latency = 0))
        assertEquals(Demo.resources.size, resources.size)
        assertNull(problem)
    }

    @Test fun resourceRoutes() {
        assertEquals(AppTab.OTHER, screenshotDestination("/other").first)
        assertEquals(listOf(Destination.Resource("demo-r1")), screenshotDestination("/resource/demo-r1").second)
        assertEquals(listOf(Destination.Resource("demo-r1"), Destination.Logs(LogKind.RESOURCE, "demo-r1")),
                     screenshotDestination("/logs?kind=resource&id=demo-r1").second)
    }
}
