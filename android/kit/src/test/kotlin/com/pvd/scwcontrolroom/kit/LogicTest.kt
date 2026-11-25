package com.pvd.scwcontrolroom.kit

import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private fun server(id: String, name: String, state: ServerState, tags: List<String> = emptyList()) =
        Server(id, name, state, "DEV1-S", Zone.FR_PAR_1, "p", tags)

    @Test fun zonesKnowTheirRegion() {
        assertEquals(Region.FR_PAR, Zone.FR_PAR_2.region)
        assertEquals(Region.IT_MIL, Zone.IT_MIL_1.region)
        assertEquals(Region.entries.toSet(), Zone.entries.map { it.region }.toSet())
    }

    @Test fun aServerTwoKeysSeeIsListedOnceWithBothKeys() {
        val shared = server("1", "web", ServerState.RUNNING)
        val merged = mergeByID(listOf("a" to listOf(shared), "b" to listOf(shared, server("2", "db", ServerState.RUNNING))))
        assertEquals(2, merged.size)
        assertEquals(listOf("a", "b"), merged.first { it.id == "1" }.keyIDs)
    }

    @Test fun runningServersComeFirstThenByName() {
        val sorted = sortServers(mergeByID(listOf("a" to listOf(
            server("1", "b", ServerState.STOPPED), server("2", "z", ServerState.RUNNING), server("3", "a", ServerState.RUNNING),
        ))))
        assertEquals(listOf("a", "z", "b"), sorted.map { it.item.name })
    }

    @Test fun namesSortAsPeopleExpect() {
        assertEquals(listOf("web-2", "Web-9", "web-10"), listOf("web-10", "web-2", "Web-9").sortedWith(naturalOrder))
    }

    @Test fun filters() {
        val list = mergeByID(listOf("a" to listOf(
            server("1", "web-prod", ServerState.RUNNING, listOf("prod")), server("2", "staging", ServerState.STOPPED, listOf("dev")),
        )))
        assertEquals(listOf("1"), filterServers(list, "", StateFilter.RUNNING).map { it.id })
        assertEquals(listOf("2"), filterServers(list, "", StateFilter.STOPPED).map { it.id })
        assertEquals(listOf("1"), filterServers(list, "WEB", StateFilter.ALL).map { it.id })
        assertEquals(listOf("2"), filterServers(list, "dev", StateFilter.ALL).map { it.id })
    }

    @Test fun eachOrganizationIsCountedOnce() {
        val keys = listOf(ApiKey("a", "a", "s", "o1"), ApiKey("b", "b", "s", "o1"), ApiKey("c", "c", "s", "o2"))
        assertEquals(listOf("a", "c"), uniqueOrganizations(keys).map { it.id })
    }

    @Test fun costsSumLargestFirstAndSubtractDiscounts() {
        val costs = MonthlyCosts(listOf(
            Consumption(1.0, "Storage", "x", "p"),
            Consumption(5.0, "Compute", "y", "p"),
            Consumption(2.0, "Storage", "z", "q"),
        ), 1.5, "EUR")
        val byCategory = sum(costs.items) { it.category }
        assertEquals(listOf("Compute", "Storage"), byCategory.map { it.first })
        assertEquals(listOf(5.0, 3.0), byCategory.map { it.second })
        assertEquals(6.5, total(costs), 0.0)
    }

    @Test fun periodsAreThisMonthAndTheMonthsBefore() {
        assertEquals(listOf("2026-02", "2026-01", "2025-12"), recentPeriods(Instant.parse("2026-02-15T12:00:00Z"), 3))
        assertEquals("November 2026", formatPeriod("2026-11"))
    }

    @Test fun moneyIsInEurosWithMoreDigitsForSmallAmounts() {
        assertEquals("€1,234.50", formatMoney(1234.5))
        assertEquals("€0.0219", formatMoney(0.0219))
        assertEquals("€0.00", formatMoney(0.0))
    }

    @Test fun keyValidation() {
        val uuid = "1a2b3c4d-1a2b-1a2b-1a2b-1a2b3c4d5e6f"
        assertNull(validateKey("Work", uuid, uuid))
        assertNotNull(validateKey("", uuid, uuid))
        assertNotNull(validateKey("Work", "SCW123", uuid))
    }

    @Test fun reviewIsAskedAtAFewMilestonesOnly() {
        assertEquals(listOf(false, false, true, false, true, false, true),
                     listOf(1, 2, 3, 4, 20, 21, 60).map { shouldAskForReview(it) })
    }

    @Test fun setupCommands() {
        // One scope type per rule: project-level sets in rule 0, billing in rule 1.
        Commands.projectPermissionSets.forEachIndexed { index, set ->
            assertTrue(Commands.keySetup.contains("rules.0.permission-set-names.$index=$set"))
        }
        assertTrue(Commands.keySetup.contains("rules.1.permission-set-names.0=BillingReadOnly"))
        assertTrue(Commands.keySetup.contains("rules.0.organization-id=\$ORG"))
        assertTrue(Commands.keySetup.contains("rules.1.organization-id=\$ORG"))
        // The policy command continues over its lines, and the key is only
        // created when the policy was.
        val policy = Commands.keySetup.lines().filter { it.contains("rules.") || it.contains("policy create") }
        assertTrue(policy.dropLast(1).all { it.endsWith("\\") })
        assertTrue(policy.last().endsWith("&&"))
        assertTrue(Commands.cockpitSources(server("i", "web", ServerState.RUNNING)).contains("rules.0.project-ids.0=p"))
        val s = Server("11111111-2222", "web", ServerState.RUNNING, "DEV1-S", Zone.NL_AMS_2, "p")
        assertTrue(Commands.cockpitSources(s).contains("type=logs project-id=p region=nl-ams"))
        assertTrue(Commands.cockpitSources(s).contains("type=metrics project-id=p region=nl-ams"))
        val config = Commands.alloyConfig(s)
        assertTrue(config.contains("""resource_id   = "11111111-2222""""))
        assertTrue(config.contains("\"\${LOGS_URL}/loki/api/v1/push\""))
        assertTrue(config.contains("\"\${METRICS_URL}/api/v1/push\""))
        // Both writers carry the label the app queries by.
        assertEquals(3, config.split("resource_id   = ").size)
        // Grafana's package leaves Alloy disabled: without this it is gone after a reboot.
        assertTrue(config.contains("sudo systemctl enable alloy"))
        assertTrue(config.contains("sudo chmod 640 /etc/alloy/config.alloy"))
        // Same commands as the iPhone app shows, but for the IAM application's description.
        assertTrue(Commands.keySetup.lines()[2].startsWith("scw iam policy create name=controlroom application-id=\$APP \\"))
        assertTrue(config.lines().contains("  external_labels = {"))
    }

    @Test fun screenshotRoutes() {
        assertEquals(AppTab.INSTANCES, screenshotDestination("/").first)
        assertEquals(AppTab.COSTS, screenshotDestination("/costs").first)
        assertEquals(listOf(Destination.Instance("demo-1")), screenshotDestination("/instance/demo-1").second)
        val logs = screenshotDestination("/logs?kind=serverless&id=demo-c1")
        assertEquals(AppTab.SERVERLESS, logs.first)
        assertEquals(listOf(Destination.Serverless("demo-c1"), Destination.Logs(LogKind.SERVERLESS, "demo-c1")), logs.second)
        val metric = screenshotDestination("/metric?kind=serverless&id=demo-c1&name=cpu_usage_ratio")
        assertEquals(listOf(Destination.Serverless("demo-c1"), Destination.Metrics(LogKind.SERVERLESS, "demo-c1"),
                            Destination.Metric(LogKind.SERVERLESS, "demo-c1", "cpu_usage_ratio")), metric.second)
        assertEquals(listOf(Destination.Instance("demo-3"), Destination.Metrics(LogKind.INSTANCE, "demo-3")),
                     screenshotDestination("/metrics?kind=instance&id=demo-3").second)
    }

    @Test fun metricQueriesRateCountersAndAverageTheRest() {
        val selector = """{resource_id="r"}"""
        assertEquals("""sum by (resource_instance) (rate(http_requests_total{resource_id="r"}[5m]))""",
                     metricQuery("http_requests_total", selector))
        assertEquals("""avg by (resource_instance) (memory_usage_bytes{resource_id="r"})""", metricQuery("memory_usage_bytes", selector))
        // Scaleway's instance count ends in _total but is no counter.
        assertFalse(isCounter("serverless_container_instances_total"))
        // Scaled to zero there are no samples: count those as 0.
        assertEquals("""sum(serverless_container_instances_total{resource_id="r"}) or vector(0)""",
                     metricQuery("serverless_container_instances_total", selector))
        assertEquals("2", formatMetric(2.0, "serverless_container_instances_total"))
        assertTrue(isCounter("serverless_container_receive_bytes_total"))
        assertEquals(15, metricStep(15))
        assertEquals(864, metricStep(1440))
        // Over 30 days the points are hours apart: the rate covers each gap.
        assertEquals("""sum by (resource_instance) (rate(http_requests_total{resource_id="r"}[25920s]))""",
                     metricQuery("http_requests_total", selector, metricStep(43200)))
    }

    @Test fun serverlessKindsHaveTabLabels() {
        assertEquals(listOf("Containers", "Functions", "Jobs"), ServerlessApp.Kind.entries.map { it.plural })
    }

    @Test fun metricTitlesAndInstanceLabels() {
        assertEquals("CPU usage", metricTitle("serverless_container_cpu_usage_ratio"))
        assertEquals("Receive bytes", metricTitle("serverless_container_receive_bytes_total"))
        assertEquals("Instances", metricTitle("serverless_container_instances_total"))
        assertEquals("Node load1", metricTitle("node_load1"))
        assertEquals("Memory usage", metricTitle("serverless_container_memory_usage_bytes"))
        assertEquals("HTTP requests", metricTitle("http_requests_total"))
        assertFalse(isChartable("serverless_container_info"))
        assertFalse(isChartable("serverless_container_status"))
        assertTrue(isChartable("serverless_container_memory_usage_bytes"))
        assertEquals("8q8c6", instanceLabel("fqprdcb358d0e-app-00020-deployment-fbc974dc5-8q8c6"))
    }

    @Test fun metricValuesUseTheUnitTheirNameSuggests() {
        assertEquals("183.4 MB", formatMetric(183_400_000.0, "memory_usage_bytes"))
        assertEquals("512 B/s", formatMetric(512.0, "network_receive_bytes_total"))
        assertEquals("35.4%", formatMetric(0.354, "cpu_usage_ratio"))
        assertEquals("8.2%", formatMetric(8.15, "serverless_container_cpu_usage_ratio"))
        assertEquals("42.2/s", formatMetric(42.25, "http_requests_total"))
        assertEquals("2.0", formatMetric(2.0, "instances"))
        assertEquals("0/s", formatMetric(0.0, "http_requests_total"))
    }
}

class DemoTest {
    @Test fun stoppingADemoServerSettlesOnStopped() = runBlocking {
        DemoState.reset()
        val demo = DemoClient(latency = 0, settle = 50)
        demo.act(Zone.FR_PAR_1, "demo-1", ServerAction.POWEROFF)
        assertEquals(ServerState.STOPPING, demo.listServers(Zone.FR_PAR_1).first { it.id == "demo-1" }.state)
        delay(300)
        assertEquals(ServerState.STOPPED, demo.listServers(Zone.FR_PAR_1).first { it.id == "demo-1" }.state)
        DemoState.reset()
    }

    @Test fun serverlessAppsHaveMetricsAndInstancesDoNot() = runTest {
        val demo = DemoClient(latency = 0)
        assertFalse(demo.metricNames(CockpitTarget("webshop", Region.FR_PAR, "demo-c1"), 60).names.isEmpty())
        assertTrue(demo.metricNames(CockpitTarget("webshop", Region.FR_PAR, "demo-3"), 60).names.isEmpty())
        val series = demo.metric("cpu_usage_ratio", CockpitTarget("webshop", Region.FR_PAR, "demo-c1"), 60)
        assertTrue(series.first().points.size > 50)
    }

    @Test fun serverlessAppsHaveLogsAndInstancesDoNot() = runTest {
        val demo = DemoClient(latency = 0)
        assertFalse(demo.logs(CockpitTarget("webshop", Region.FR_PAR, "demo-c1"), 60).lines.isEmpty())
        assertTrue(demo.logs(CockpitTarget("webshop", Region.FR_PAR, "demo-3"), 60).lines.isEmpty())
    }

    @Test fun aQuietInstanceIsExplainedByItsState() {
        val now = Instant.ofEpochSecond(1_000_000)
        var s = Server("1", "web", ServerState.STOPPED, "DEV1-S", Zone.FR_PAR_1, "p")
        assertEquals(InstanceQuiet.STOPPED, instanceQuiet(s, now))
        s = s.copy(state = ServerState.STOPPING)
        assertEquals(InstanceQuiet.STOPPED, instanceQuiet(s, now))
        s = s.copy(state = ServerState.STARTING)
        assertEquals(InstanceQuiet.STARTING, instanceQuiet(s, now))
        s = s.copy(state = ServerState.RUNNING)
        assertEquals(InstanceQuiet.RUNNING, instanceQuiet(s, now))
        s = s.copy(created = now.minusSeconds(600))
        assertEquals(InstanceQuiet.NEW, instanceQuiet(s, now))
        s = s.copy(created = now.minusSeconds(7200))
        assertEquals(InstanceQuiet.RUNNING, instanceQuiet(s, now))
    }

    @Test fun scalewayDatesParseWithTheirFraction() {
        assertEquals(Instant.ofEpochSecond(100), parseScalewayDate("1970-01-01T00:01:40.123456+00:00"))
        assertEquals(Instant.ofEpochSecond(100), parseScalewayDate("1970-01-01T00:01:40Z"))
        assertNull(parseScalewayDate("yesterday"))
    }
}
