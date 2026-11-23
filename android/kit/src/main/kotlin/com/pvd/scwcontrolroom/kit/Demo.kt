package com.pvd.scwcontrolroom.kit

import java.time.Instant
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The demo lets reviewers, screenshots and curious users try the app
 * without a Scaleway account. Everything here is made up. State is shared,
 * so a server stopped on one screen is stopped on all of them.
 */
class DemoClient(private val latency: Long = 300, private val settle: Long = 3000) : ScalewayClient {
    override suspend fun listServers(zone: Zone): List<Server> {
        delay(latency)
        return DemoState.servers().filter { it.zone == zone }
    }

    override suspend fun act(zone: Zone, serverID: String, action: ServerAction) {
        delay(latency)
        val busy = if (action == ServerAction.POWEROFF) ServerState.STOPPING else ServerState.STARTING
        val done = if (action == ServerAction.POWEROFF) ServerState.STOPPED else ServerState.RUNNING
        DemoState.set(serverID, busy)
        DemoState.scope.launch {
            delay(settle)
            DemoState.set(serverID, done)
        }
    }

    override suspend fun costs(period: String): MonthlyCosts {
        delay(latency)
        // Older months a little different, so the history does not look copied.
        val month = period.takeLast(2).toIntOrNull() ?: 1
        val factor = 1 - (month % 3) * 0.08
        return MonthlyCosts(
            items = Demo.costs.map { it.copy(amount = Math.round(it.amount * factor * 100) / 100.0) },
            discount = 0.0, currency = "EUR",
        )
    }

    override suspend fun hourlyPrices(zone: Zone): Map<String, Double> = Demo.prices

    override suspend fun listServerless(region: Region): List<ServerlessApp> {
        delay(latency)
        return Demo.apps.filter { it.region == region }
    }

    /** No latency: the Other tab asks this some 170 times. */
    override suspend fun listResources(product: Product, place: String?): List<Resource> =
        Demo.resources.filter { it.type == product.type && (it.location == place || place == null) }

    override suspend fun projectNames(): Map<String, String> =
        mapOf("webshop" to "Webshop", "tooling" to "Tooling", "ml" to "Machine learning")

    override suspend fun logs(target: CockpitTarget, minutes: Int): LogResult {
        delay(latency)
        return LogResult(1, Demo.logs(target.resourceID, Instant.now()))
    }

    override suspend fun metricNames(target: CockpitTarget, minutes: Int): MetricNames {
        delay(latency)
        val hasMetrics = Demo.apps.any { it.id == target.resourceID }
        return MetricNames(1, if (hasMetrics) Demo.metricNames else emptyList())
    }

    override suspend fun metric(name: String, target: CockpitTarget, minutes: Int): List<MetricSeries> {
        delay(latency)
        val points = Demo.metric(name, minutes, Instant.now())
        // The instance count is for the app as a whole; the rest per instance.
        if (name == "instances") return listOf(MetricSeries("", points))
        return listOf("deployment-7f9c-k2x8p" to 1.0, "deployment-7f9c-q4m1z" to 0.7).map { (instance, share) ->
            MetricSeries(instance, points.map { it.copy(value = it.value * share) })
        }
    }
}

object DemoState {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var servers = Demo.servers

    @Synchronized
    fun servers(): List<Server> = servers

    @Synchronized
    fun set(id: String, state: ServerState) {
        servers = servers.map { if (it.id == id) it.copy(state = state) else it }
    }

    /** For tests: start from the initial demo state again. */
    @Synchronized
    fun reset() {
        servers = Demo.servers
    }
}

object Demo {
    val servers = listOf(
        Server("demo-1", "web-prod-1", ServerState.RUNNING, "PRO2-S", Zone.FR_PAR_1, "webshop", listOf("prod")),
        Server("demo-2", "web-prod-2", ServerState.RUNNING, "PRO2-S", Zone.FR_PAR_2, "webshop", listOf("prod")),
        Server("demo-3", "staging", ServerState.RUNNING, "DEV1-M", Zone.FR_PAR_1, "webshop", listOf("dev")),
        Server("demo-4", "ci-runner", ServerState.RUNNING, "GP1-XS", Zone.NL_AMS_1, "tooling", listOf("dev")),
        Server("demo-5", "gpu-training", ServerState.STOPPED, "L4-1-24G", Zone.FR_PAR_2, "ml", listOf("dev")),
        Server("demo-6", "old-wordpress", ServerState.STOPPED, "DEV1-S", Zone.NL_AMS_1, "tooling"),
    )

    val prices = mapOf("DEV1-S" to 0.0109, "DEV1-M" to 0.0219, "GP1-XS" to 0.091, "PRO2-S" to 0.219, "L4-1-24G" to 0.75)

    val costs = listOf(
        Consumption(112.4, "Compute", "Instances PRO2-S", "webshop"),
        Consumption(11.8, "Compute", "Instances DEV1-M", "webshop"),
        Consumption(49.1, "Compute", "Instances GP1-XS", "tooling"),
        Consumption(36.0, "Compute", "Instances L4-1-24G", "ml"),
        Consumption(8.6, "Storage", "Block Storage", "webshop"),
        Consumption(2.3, "Storage", "Object Storage", "tooling"),
        Consumption(2.9, "Network", "Flexible IP", "webshop"),
        Consumption(1.2, "Observability", "Cockpit", "webshop"),
    )

    /**
     * Read from list items like Scaleway's, as real resources are, so the
     * detail screen has its fields.
     */
    val resources: List<Resource> = listOf(
        resource("Managed Database", "fr-par", """
            {"id": "demo-r1", "name": "orders-db", "status": "ready", "region": "fr-par", "project_id": "webshop",
             "engine": "PostgreSQL-16", "node_type": "db-gp-s", "is_ha_cluster": true, "tags": ["prd"],
             "volume": {"type": "sbs_5k", "size": 50000000000}, "endpoint": {"ip": "51.159.24.110", "port": 5432},
             "backup_schedule": {"frequency": 24, "retention": 7, "disabled": false},
             "settings": [{"name": "max_connections", "value": "200"}], "created_at": "2026-03-02T09:12:00Z"}
            """),
        resource("Redis", "fr-par-1", """
            {"id": "demo-r2", "name": "sessions", "status": "ready", "zone": "fr-par-1", "project_id": "webshop",
             "version": "7.2.5", "node_type": "RED1-S", "cluster_size": 1, "tls_enabled": true,
             "endpoints": [{"ips": ["10.12.0.4"], "port": 6379}], "created_at": "2026-03-02T09:20:00Z"}
            """),
        resource("Block volume", "fr-par-1", """
            {"id": "demo-r3", "name": "uploads", "status": "in_use", "zone": "fr-par-1", "project_id": "webshop",
             "type": "sbs_15k", "size": 200000000000, "specs": {"perf_iops": 15000, "class": "sbs"},
             "created_at": "2026-04-11T14:03:00Z"}
            """),
        resource("Flexible IP", "fr-par-1", """
            {"id": "demo-r4", "address": "51.15.10.20", "state": "attached", "zone": "fr-par-1", "project": "webshop",
             "reverse": "shop.example.com", "type": "routed_ipv4", "server": {"id": "demo-s1", "name": "web-1"}}
            """),
        resource("Load Balancer", "fr-par-1", """
            {"id": "demo-r5", "name": "web-lb", "status": "ready", "zone": "fr-par-1", "project_id": "webshop",
             "type": "lb-s", "ip": [{"ip_address": "51.159.30.40", "reverse": "lb.example.com"}],
             "frontend_count": 2, "backend_count": 1, "ssl_compatibility_level": "ssl_compatibility_level_intermediate"}
            """),
        resource("Transactional Email", "fr-par", """
            {"id": "demo-r6", "name": "mail.example.com", "status": "checked", "region": "fr-par", "project_id": "webshop",
             "reputation": {"status": "excellent", "score": 98}, "statistics": {"sent_count": 1284, "failed_count": 3},
             "spf_config": "include:_spf.tem.scaleway.com", "last_valid_at": "2026-11-21T03:40:00Z"}
            """),
        resource("Secret", "fr-par", """
            {"id": "demo-r7", "name": "stripe-api-key", "status": "ready", "region": "fr-par", "project_id": "webshop",
             "description": "Live key for the checkout API", "type": "opaque", "version_count": 3, "path": "/checkout",
             "protected": false, "tags": ["prd"], "updated_at": "2026-10-30T16:45:00Z"}
            """),
        resource("DNS zone", null, """
            {"domain": "example.com", "subdomain": "", "status": "active", "project_id": "webshop",
             "ns": ["ns0.dom.scw.cloud", "ns1.dom.scw.cloud"], "linked_products": [], "updated_at": "2026-09-14T08:00:00Z"}
            """),
        resource("Managed Database", "fr-par", """
            {"id": "demo-r9", "name": "metabase-db", "status": "ready", "region": "fr-par", "project_id": "tooling",
             "engine": "PostgreSQL-16", "node_type": "db-dev-s", "is_ha_cluster": false,
             "volume": {"type": "sbs_5k", "size": 10000000000}, "endpoint": {"ip": "51.159.24.120", "port": 18342},
             "backup_schedule": {"frequency": 24, "retention": 7, "disabled": false}, "created_at": "2026-05-20T10:00:00Z"}
            """),
    )

    private fun resource(type: String, place: String?, json: String): Resource {
        val product = Products.all.first { it.type == type }
        return Resource.parse(Json.parseToJsonElement(json).jsonObject, product, place)!!
    }

    val apps = listOf(
        app("demo-c1", "checkout-api", ServerlessApp.Kind.CONTAINER, "webshop", 5),
        app("demo-c2", "image-resizer", ServerlessApp.Kind.CONTAINER, "webshop", 10),
        app("demo-f1", "stripe-webhook", ServerlessApp.Kind.FUNCTION, "webshop", 3),
        app("demo-f2", "nightly-report", ServerlessApp.Kind.FUNCTION, "tooling", 1),
        ServerlessApp("demo-j1", "db-backup", ServerlessApp.Kind.JOB, "succeeded", Region.FR_PAR, "tooling", "", 0, 0,
                      "0 3 * * * (Europe/Paris)"),
    )

    private fun app(id: String, name: String, kind: ServerlessApp.Kind, project: String, maxScale: Int) =
        ServerlessApp(id, name, kind, "ready", Region.FR_PAR, project, "$name-demo.functions.fnc.fr-par.scw.cloud", 0, maxScale)

    private val logLines = listOf(
        "GET /api/cart 200 18ms",
        "POST /api/checkout 201 142ms",
        "GET /api/cart 200 12ms",
        "payment confirmed for order 10482",
        "GET /healthz 200 1ms",
        "POST /api/checkout 402 96ms: card declined",
        "GET /api/products?page=2 200 31ms",
        "cache miss for product 8812, loading from database",
    )

    val metricNames = listOf("cpu_usage_ratio", "http_requests_total", "instances", "memory_usage_bytes")

    /** A plausible day of traffic: a slow wave with some wobble. */
    fun metric(name: String, minutes: Int, now: Instant): List<MetricPoint> {
        val (base, swing) = when (name) {
            "cpu_usage_ratio" -> 0.35 to 0.2
            "http_requests_total" -> 40.0 to 25.0
            "instances" -> 2.0 to 1.0
            else -> 180_000_000.0 to 60_000_000.0
        }
        val step = metricStep(minutes).toLong()
        val count = minutes * 60 / step
        return (0..count).map { i ->
            val time = now.minusSeconds((count - i) * step)
            val phase = time.epochSecond / 3600.0
            var value = base + swing * sin(phase / 2) + swing * 0.3 * sin(phase * 7)
            if (name == "instances") value = Math.rint(value)
            MetricPoint(time, max(0.0, value))
        }
    }

    /**
     * Serverless apps log; the demo instances do not, so their logs screen
     * shows how to set that up.
     */
    fun logs(resourceID: String, now: Instant): List<LogLine> {
        if (apps.none { it.id == resourceID }) return emptyList()
        return (0 until 40).map { LogLine(now.minusSeconds(47L * it), logLines[it % logLines.size]) }
    }
}
