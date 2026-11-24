package com.pvd.scwcontrolroom.kit

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ScalewayApiTest {
    private val key = ApiKey("k", "Work", "secret", "org-1")

    @Test fun listsServersAndSendsTheSecretKey() = runTest {
        val transport = FakeTransport()
        transport.responses["/instance/v1/zones/nl-ams-1/servers"] = 200 to """
            {"servers": [{"id": "1", "name": "web", "state": "stopped in place", "commercial_type": "DEV1-S",
              "zone": "nl-ams-1", "project": "p", "tags": ["prod"], "public_ips": [{"address": "51.15.0.1"}],
              "creation_date": "2026-11-20T09:12:34.567890+00:00"}]}
            """
        val servers = ScalewayApi(key, transport).listServers(Zone.NL_AMS_1)
        assertEquals(listOf(Server("1", "web", ServerState.STOPPED_IN_PLACE, "DEV1-S", Zone.NL_AMS_1, "p", listOf("prod"),
                                   listOf("51.15.0.1"), Instant.ofEpochSecond(1_795_165_954))), servers)
        assertEquals("secret", transport.requests.first().headers["X-Auth-Token"])
        assertEquals("100", transport.queries("/instance/v1/zones/nl-ams-1/servers").first()["per_page"])
    }

    @Test fun anErrorCarriesScalewaysMessage() = runTest {
        val transport = FakeTransport()
        transport.responses["/instance/v1/zones/fr-par-1/servers"] = 403 to """{"message": "insufficient permissions"}"""
        try {
            ScalewayApi(key, transport).listServers(Zone.FR_PAR_1)
            fail("expected an error")
        } catch (error: ApiException) {
            assertTrue(error.isForbidden)
            assertEquals("insufficient permissions", error.message)
        }
    }

    @Test fun aRegionWithoutTheProductIsRecognised() = runTest {
        val transport = FakeTransport()
        for (path in listOf("/containers/v1/regions/it-mil/namespaces", "/functions/v1beta1/regions/it-mil/namespaces",
                            "/serverless-jobs/v1alpha1/regions/it-mil/job-definitions")) {
            transport.responses[path] = 501 to """{"message": "unknown service"}"""
        }
        try {
            ScalewayApi(key, transport).listServerless(Region.IT_MIL)
            fail("expected an error")
        } catch (error: ApiException) {
            assertTrue(error.isUnknownService)
            assertFalse(error.isForbidden)
        }
    }

    @Test fun actionsPostTheAction() = runTest {
        val transport = FakeTransport()
        transport.responses["/instance/v1/zones/fr-par-1/servers/1/action"] = 202 to """{"task": {}}"""
        ScalewayApi(key, transport).act(Zone.FR_PAR_1, "1", ServerAction.POWEROFF)
        val request = transport.requests.first()
        assertEquals("POST", request.method)
        assertEquals("""{"action":"poweroff"}""", request.body)
    }

    @Test fun costsReadMoneyWhoseUnitsMayBeAString() = runTest {
        val transport = FakeTransport()
        transport.responses["/billing/v2beta1/consumptions"] = 200 to """
            {"consumptions": [
              {"value": {"currency_code": "EUR", "units": "12", "nanos": 500000000}, "category_name": "Compute",
               "product_name": "Instances", "project_name": "webshop", "project_id": "p1"},
              {"value": {"currency_code": "EUR", "units": 3, "nanos": 0}, "category_name": "Storage",
               "product_name": "Block", "project_name": "", "project_id": "p2"}],
             "total_discount_untaxed_value": -1.5}
            """
        val costs = ScalewayApi(key, transport).costs("2026-11")
        assertEquals(listOf(12.5, 3.0), costs.items.map { it.amount })
        assertEquals(listOf("webshop", "p2"), costs.items.map { it.project })
        assertEquals(1.5, costs.discount, 0.0)
        assertEquals(14.0, total(costs), 0.0)
        val query = transport.queries("/billing/v2beta1/consumptions").first()
        assertEquals("org-1", query["organization_id"])
        assertEquals("2026-11", query["billing_period"])
    }

    @Test fun pricesComeFromTheProductCatalog() = runTest {
        val transport = FakeTransport()
        transport.responses["/product-catalog/v2alpha1/public-catalog/products"] = 200 to """
            {"products": [
              {"product": "DEV1-S", "price": {"retail_price": {"currency_code": "EUR", "units": 0, "nanos": 8976000}},
               "unit_of_measure": {"unit": "hour", "size": 1}},
              {"product": "POP2-32C-128G", "price": {"retail_price": {"currency_code": "EUR", "units": 1, "nanos": 180000000}},
               "unit_of_measure": {"unit": "hour", "size": 1}},
              {"product": "ARM64-128GB", "price": {"retail_price": {"currency_code": "EUR", "units": 0, "nanos": 0}},
               "unit_of_measure": {"unit": "hour", "size": 1}}
            ], "total_count": 3}
            """
        val prices = ScalewayApi(key, transport).hourlyPrices(Zone.FR_PAR_1)
        assertEquals(listOf("DEV1-S", "POP2-32C-128G"), prices.keys.sorted())
        assertEquals(0.008976, prices.getValue("DEV1-S"), 1e-9)
        assertEquals(1.18, prices.getValue("POP2-32C-128G"), 1e-9)
        val query = transport.queries("/product-catalog/v2alpha1/public-catalog/products").first()
        assertEquals("instance", query["product_types"])
        assertEquals("fr-par-1", query["zone"])
    }

    @Test fun serverlessTakesTheProjectFromTheNamespace() = runTest {
        val transport = FakeTransport()
        transport.responses["/containers/v1/regions/fr-par/namespaces"] = 200 to
            """{"namespaces": [{"id": "ns-c", "project_id": "p-c"}]}"""
        transport.responses["/containers/v1/regions/fr-par/containers"] = 200 to """
            {"containers": [{"id": "c1", "name": "api", "status": "ready", "namespace_id": "ns-c",
              "min_scale": 0, "max_scale": 5, "public_endpoint": "api.example"}]}
            """
        transport.responses["/functions/v1beta1/regions/fr-par/namespaces"] = 200 to
            """{"namespaces": [{"id": "ns-f", "project_id": "p-f"}]}"""
        transport.responses["/functions/v1beta1/regions/fr-par/functions"] = 200 to """
            {"functions": [{"id": "f1", "name": "hook", "status": "ready", "namespace_id": "ns-f",
              "min_scale": 0, "max_scale": 1, "domain_name": "hook.example"}]}
            """
        transport.responses["/serverless-jobs/v1alpha1/regions/fr-par/job-definitions"] = 200 to """
            {"job_definitions": [{"id": "j1", "name": "worker", "project_id": "p-j",
              "cron_schedule": {"schedule": "*/15 * * * *", "timezone": "Europe/Amsterdam"}}]}
            """
        transport.responses["/serverless-jobs/v1alpha1/regions/fr-par/job-runs"] = 200 to
            """{"job_runs": [{"id": "r1", "job_definition_id": "j1", "state": "succeeded"}]}"""
        val apps = ScalewayApi(key, transport).listServerless(Region.FR_PAR)
        assertEquals(listOf("p-c", "p-f", "p-j"), apps.map { it.projectID })
        assertEquals(listOf(ServerlessApp.Kind.CONTAINER, ServerlessApp.Kind.FUNCTION, ServerlessApp.Kind.JOB), apps.map { it.kind })
        assertEquals(listOf("api.example", "hook.example", ""), apps.map { it.endpoint })
        // A job's status is its last run's.
        assertEquals("succeeded", apps[2].status)
        assertEquals("*/15 * * * * (Europe/Amsterdam)", apps[2].schedule)
        val runs = transport.queries("/serverless-jobs/v1alpha1/regions/fr-par/job-runs").first()
        assertEquals("j1", runs["job_definition_id"])
        assertEquals("created_at_desc", runs["order_by"])
        assertEquals("1", runs["page_size"])
        assertEquals("ns-f", transport.queries("/functions/v1beta1/regions/fr-par/functions").first()["namespace_id"])
    }

    @Test fun serverlessIsListedForTheKeysOrganization() = runTest {
        val transport = FakeTransport()
        for (path in listOf("/containers/v1/regions/fr-par/namespaces", "/functions/v1beta1/regions/fr-par/namespaces")) {
            transport.responses[path] = 200 to """{"namespaces": []}"""
        }
        transport.responses["/containers/v1/regions/fr-par/containers"] = 200 to """{"containers": []}"""
        transport.responses["/serverless-jobs/v1alpha1/regions/fr-par/job-definitions"] = 200 to """{"job_definitions": []}"""
        ScalewayApi(key, transport).listServerless(Region.FR_PAR)
        for (path in listOf("/containers/v1/regions/fr-par/namespaces", "/containers/v1/regions/fr-par/containers",
                            "/functions/v1beta1/regions/fr-par/namespaces", "/serverless-jobs/v1alpha1/regions/fr-par/job-definitions")) {
            assertEquals(path, "org-1", transport.queries(path).first()["organization_id"])
        }
    }

    @Test fun aKeyThatMaySeeOnlyContainersStillListsThem() = runTest {
        val transport = FakeTransport()
        transport.responses["/containers/v1/regions/fr-par/namespaces"] = 200 to
            """{"namespaces": [{"id": "ns-c", "project_id": "p-c"}]}"""
        transport.responses["/containers/v1/regions/fr-par/containers"] = 200 to
            """{"containers": [{"id": "c1", "name": "api", "status": "ready", "namespace_id": "ns-c"}]}"""
        transport.responses["/functions/v1beta1/regions/fr-par/namespaces"] = 403 to """{"message": "insufficient permissions"}"""
        transport.responses["/serverless-jobs/v1alpha1/regions/fr-par/job-definitions"] = 403 to """{"message": "insufficient permissions"}"""
        assertEquals(listOf("c1"), ScalewayApi(key, transport).listServerless(Region.FR_PAR).map { it.id })
    }

    @Test fun aRegionWithoutJobsStillListsContainers() = runTest {
        val transport = FakeTransport()
        transport.responses["/containers/v1/regions/fr-par/namespaces"] = 200 to
            """{"namespaces": [{"id": "ns-c", "project_id": "p-c"}]}"""
        transport.responses["/containers/v1/regions/fr-par/containers"] = 200 to
            """{"containers": [{"id": "c1", "name": "api", "status": "ready", "namespace_id": "ns-c"}]}"""
        transport.responses["/functions/v1beta1/regions/fr-par/namespaces"] = 200 to """{"namespaces": []}"""
        transport.responses["/serverless-jobs/v1alpha1/regions/fr-par/job-definitions"] = 501 to """{"message": "unknown service"}"""
        assertEquals(listOf("c1"), ScalewayApi(key, transport).listServerless(Region.FR_PAR).map { it.id })
    }

    @Test fun aKeyThatMaySeeNoneIsForbidden() = runTest {
        val transport = FakeTransport()
        for (path in listOf("/containers/v1/regions/fr-par/namespaces", "/containers/v1/regions/fr-par/containers",
                            "/functions/v1beta1/regions/fr-par/namespaces", "/serverless-jobs/v1alpha1/regions/fr-par/job-definitions")) {
            transport.responses[path] = 403 to """{"message": "insufficient permissions"}"""
        }
        try {
            ScalewayApi(key, transport).listServerless(Region.FR_PAR)
            fail("expected an error")
        } catch (error: ApiException) {
            assertTrue(error.isForbidden)
        }
    }

    @Test fun logsQueryEveryDataSourceByResourceIDNewestFirst() = runTest {
        val transport = FakeTransport()
        transport.responses["/cockpit/v1/regions/fr-par/data-sources"] = 200 to """
            {"data_sources": [{"url": "https://a.logs.cockpit.fr-par.scw.cloud"},
                              {"url": "https://b.logs.cockpit.fr-par.scw.cloud"}]}
            """
        // Both data sources answer on the same path; the fake does not tell hosts apart.
        transport.responses["/loki/api/v1/query_range"] = 200 to """
            {"data": {"result": [{"values": [["1700000000000000000", "a"], ["1700000002000000000", "c"]]},
                                 {"values": [["1700000001000000000", "b"]]}]}}
            """
        val result = ScalewayApi(key, transport).logs(CockpitTarget("p", Region.FR_PAR, "r1"), 60)
        assertEquals(2, result.sources)
        assertEquals(listOf("c", "c", "b"), result.lines.take(3).map { it.line })
        assertEquals(Instant.ofEpochSecond(1_700_000_002), result.lines.first().time)
        assertEquals("""{resource_id="r1"}""", transport.queries("/loki/api/v1/query_range").first()["query"])
        assertEquals("p", transport.queries("/cockpit/v1/regions/fr-par/data-sources").first()["project_id"])
    }

    @Test fun jobLogsAreFoundByJobDefinition() = runTest {
        val transport = FakeTransport()
        transport.responses["/cockpit/v1/regions/fr-par/data-sources"] = 200 to
            """{"data_sources": [{"url": "https://a.logs.cockpit.fr-par.scw.cloud"}]}"""
        transport.responses["/loki/api/v1/query_range"] = 200 to """{"data": {"result": []}}"""
        val job = ServerlessApp("j1", "worker", ServerlessApp.Kind.JOB, "succeeded", Region.FR_PAR, "p", "", 0, 0)
        ScalewayApi(key, transport).logs(job.cockpitTarget, 60)
        assertEquals("""{job_definition_id="j1"}""", transport.queries("/loki/api/v1/query_range").first()["query"])
    }

    @Test fun metricsAreFoundByResourceIDAndQueriedThroughPrometheus() = runTest {
        val transport = FakeTransport()
        transport.responses["/cockpit/v1/regions/fr-par/data-sources"] = 200 to
            """{"data_sources": [{"url": "https://m.metrics.cockpit.fr-par.scw.cloud"}]}"""
        transport.responses["/prometheus/api/v1/label/__name__/values"] = 200 to
            """{"status": "success", "data": ["requests_total", "cpu_usage_ratio"]}"""
        transport.responses["/prometheus/api/v1/query_range"] = 200 to """
            {"status": "success", "data": {"resultType": "matrix",
              "result": [{"metric": {"resource_instance": "app-b"}, "values": [[1700000000, "0.25"], [1700000060.5, "0.5"]]},
                         {"metric": {"resource_instance": "app-a"}, "values": [[1700000000, "1"]]}]}}
            """
        val api = ScalewayApi(key, transport)
        val target = CockpitTarget("p", Region.FR_PAR, "r1")

        val names = api.metricNames(target, 60)
        assertEquals(listOf("cpu_usage_ratio", "requests_total"), names.names)
        assertEquals("""{resource_id="r1"}""", transport.queries("/prometheus/api/v1/label/__name__/values").first()["match[]"])
        assertEquals("metrics", transport.queries("/cockpit/v1/regions/fr-par/data-sources").first()["types"])

        val series = api.metric("requests_total", target, 60)
        assertEquals(listOf("app-a", "app-b"), series.map { it.instance })
        assertEquals(listOf(0.25, 0.5), series[1].points.map { it.value })
        assertEquals(Instant.ofEpochMilli(1_700_000_060_500), series[1].points.last().time)
        val query = transport.queries("/prometheus/api/v1/query_range").first()
        assertEquals("""sum by (resource_instance) (rate(requests_total{resource_id="r1"}[5m]))""", query["query"])
        assertEquals("36", query["step"])
    }

    @Test fun listsFollowPagesUntilAShortOne() = runTest {
        val pages = mutableListOf<String>()
        val transport = HttpTransport { request ->
            val page = request.query.getValue("page")
            pages += page
            val count = if (page == "1") 100 else 3
            val servers = (0 until count).joinToString(",") {
                """{"id": "$page-$it", "name": "s", "state": "running", "commercial_type": "DEV1-S", "zone": "fr-par-1", "project": "p"}"""
            }
            HttpResponse(200, """{"servers": [$servers]}""")
        }
        assertEquals(103, ScalewayApi(key, transport).listServers(Zone.FR_PAR_1).size)
        assertEquals(listOf("1", "2"), pages)
    }
}
