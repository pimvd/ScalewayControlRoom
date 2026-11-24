package com.pvd.scwcontrolroom.kit

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
) {
    val path: String get() = URI(url).rawPath

    /** The query, decoded. */
    val query: Map<String, String>
        get() = URI(url).rawQuery.orEmpty().split("&").filter { it.isNotEmpty() }.associate { pair ->
            val (name, value) = (pair.split("=", limit = 2) + "").take(2)
            URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }
}

data class HttpResponse(val status: Int, val body: String)

/** Sends a request. The app uses [UrlConnectionTransport]; tests use a fake. */
fun interface HttpTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}

class UrlConnectionTransport : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URI(request.url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            request.body?.let { body ->
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            HttpResponse(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * The real client. Every Scaleway endpoint, Cockpit's Loki included, takes
 * the IAM secret key in `X-Auth-Token`.
 */
class ScalewayApi(
    private val key: ApiKey,
    private val transport: HttpTransport = UrlConnectionTransport(),
) : ScalewayClient {
    companion object {
        const val BASE = "https://api.scaleway.com"
        const val PAGE_SIZE = 100
        const val LOG_LIMIT = 200

        /** Loki's streams merged into one list, newest first. */
        fun logLines(responses: List<JsonElement>, limit: Int = LOG_LIMIT): List<LogLine> =
            responses
                .flatMap { response -> response.obj?.get("data").obj?.arr("result").orEmpty() }
                .flatMap { stream -> stream.obj?.arr("values").orEmpty() }
                .mapNotNull { pair ->
                    val values = (pair as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: return@mapNotNull null
                    val ns = values.getOrNull(0)?.toBigIntegerOrNull() ?: return@mapNotNull null
                    if (values.size != 2) return@mapNotNull null
                    val seconds = ns.divide(1_000_000_000.toBigInteger())
                    val nanos = ns.mod(1_000_000_000.toBigInteger())
                    LogLine(Instant.ofEpochSecond(seconds.toLong(), nanos.toLong()), values[1])
                }
                .sortedByDescending { it.time }
                .take(limit)
    }

    // Requests

    private suspend fun send(url: String, method: String = "GET", body: String? = null): JsonElement {
        val request = HttpRequest(url, method, mapOf("X-Auth-Token" to key.secretKey, "Content-Type" to "application/json"), body)
        var response = transport.send(request)
        // Too many requests at once: wait a little, then try again.
        for (attempt in 1..3) {
            if (response.status != 429) break
            delay(attempt * 1000L)
            response = transport.send(request)
        }
        val json = runCatching { Json.parseToJsonElement(response.body) }.getOrNull()
        if (response.status !in 200..299) {
            throw ApiException(response.status, json.obj?.str("message") ?: "HTTP ${response.status}")
        }
        return json ?: throw ApiException(response.status, "Scaleway sent something that is not JSON.")
    }

    private fun url(path: String, query: Map<String, String> = emptyMap(), base: String = BASE): String {
        val encoded = query.toSortedMap().entries.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
        return base.trimEnd('/') + "/" + path + if (encoded.isEmpty()) "" else "?$encoded"
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    /** Fetches every page of a list endpoint: a short page is the last one. */
    private suspend fun <T> allPages(pageSize: Int = PAGE_SIZE, fetch: suspend (Int) -> List<T>): List<T> {
        val all = mutableListOf<T>()
        var page = 1
        while (true) {
            val items = fetch(page)
            all += items
            if (items.size < pageSize) return all
            page += 1
        }
    }

    /** A list endpoint that pages with `page_size`; the Instance API uses `per_page`. */
    private suspend fun pages(path: String, query: Map<String, String>, field: String): List<JsonObject> =
        allPages { page ->
            val response = send(url(path, query + mapOf("page_size" to PAGE_SIZE.toString(), "page" to page.toString())))
            response.obj?.objects(field).orEmpty()
        }

    // ScalewayClient

    override suspend fun listServers(zone: Zone): List<Server> =
        allPages { page ->
            send(url("instance/v1/zones/${zone.id}/servers", mapOf("per_page" to PAGE_SIZE.toString(), "page" to page.toString())))
                .obj?.objects("servers").orEmpty()
        }.map { s ->
            Server(
                id = s.str("id").orEmpty(),
                name = s.str("name").orEmpty(),
                state = ServerState.of(s.str("state").orEmpty()),
                commercialType = s.str("commercial_type").orEmpty(),
                zone = Zone.of(s.str("zone").orEmpty()) ?: Zone.FR_PAR_1,
                project = s.str("project").orEmpty(),
                tags = s.arr("tags").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
                publicIPs = s.objects("public_ips").mapNotNull { it.str("address") },
                created = s.str("creation_date")?.let(::parseScalewayDate),
            )
        }

    override suspend fun act(zone: Zone, serverID: String, action: ServerAction) {
        send(url("instance/v1/zones/${zone.id}/servers/$serverID/action"), "POST", """{"action":"${action.apiValue}"}""")
    }

    override suspend fun costs(period: String): MonthlyCosts {
        var discount = 0.0
        val items = allPages { page ->
            val response = send(url("billing/v2beta1/consumptions", mapOf(
                "organization_id" to key.organizationID, "billing_period" to period,
                "page_size" to PAGE_SIZE.toString(), "page" to page.toString(),
            ))).obj
            response?.num("total_discount_untaxed_value")?.let { discount = kotlin.math.abs(it) }
            response?.objects("consumptions").orEmpty()
        }
        return MonthlyCosts(
            items = items.map { c ->
                val project = c.str("project_name")
                Consumption(money(c["value"].obj), c.str("category_name").orEmpty(), c.str("product_name").orEmpty(),
                            if (project.isNullOrEmpty()) c.str("project_id").orEmpty() else project)
            },
            discount = discount,
            currency = items.firstOrNull()?.get("value").obj?.str("currency_code") ?: "EUR",
        )
    }

    /** google.type.Money: whole units (a number or a string) plus nanos. */
    private fun money(value: JsonObject?): Double = (value?.num("units") ?: 0.0) + (value?.num("nanos") ?: 0.0) / 1e9

    override suspend fun hourlyPrices(zone: Zone): Map<String, Double> {
        // The Instance API's own catalogue (`products/servers`) reports 0 for
        // every price; the public Product Catalog has them, keyed by the same
        // commercial type. Retired types carry 0 and are left out.
        val products = pages("product-catalog/v2alpha1/public-catalog/products",
                             mapOf("product_types" to "instance", "zone" to zone.id), "products")
        val prices = linkedMapOf<String, Double>()
        for (p in products) {
            val price = money(p["price"].obj?.get("retail_price").obj)
            val product = p.str("product") ?: continue
            if (p["unit_of_measure"].obj?.str("unit") == "hour" && price > 0) prices.putIfAbsent(product, price)
        }
        return prices
    }

    override suspend fun listServerless(region: Region): List<ServerlessApp> = coroutineScope {
        // Containers, functions and jobs are separate products with separate
        // permission sets, not all offered in every region: a key that may see
        // only some of them still lists those.
        val containers = async { attempt { serverlessApps(region, ServerlessApp.Kind.CONTAINER) } }
        val functions = async { attempt { serverlessApps(region, ServerlessApp.Kind.FUNCTION) } }
        val jobs = async { attempt { serverlessJobs(region) } }
        val results = listOf(containers.await(), functions.await(), jobs.await())
        val failures = results.mapNotNull { result ->
            result.exceptionOrNull()?.let { it as? ApiException ?: ApiException(0, it.message ?: it.toString()) }
        }
        val unknown = failures.filter { it.isUnknownService }
        val forbidden = failures.filter { it.isForbidden }
        // Anything else is a real problem; "unknown service" when the region
        // offers none of them, and "may not" when none it offers is allowed.
        failures.firstOrNull { !it.isUnknownService && !it.isForbidden }?.let { throw it }
        if (unknown.size == results.size) throw unknown[0]
        if (forbidden.isNotEmpty() && forbidden.size + unknown.size == results.size) throw forbidden[0]
        results.flatMap { it.getOrNull().orEmpty() }
    }

    /**
     * One product's apps in a region, across the key's organization. They
     * carry no project; their namespace does. Functions can only be listed
     * per namespace.
     */
    private suspend fun serverlessApps(region: Region, kind: ServerlessApp.Kind): List<ServerlessApp> {
        val base = if (kind == ServerlessApp.Kind.CONTAINER) "containers/v1" else "functions/v1beta1"
        val path = "$base/regions/${region.id}"
        val organization = mapOf("organization_id" to key.organizationID)
        val namespaces = pages("$path/namespaces", organization, "namespaces")
        val apps = if (kind == ServerlessApp.Kind.CONTAINER) {
            pages("$path/containers", organization, "containers")
        } else {
            namespaces.flatMap { pages("$path/functions", mapOf("namespace_id" to it.str("id").orEmpty()), "functions") }
        }
        val projectOf = namespaces.reversed().associate { it.str("id").orEmpty() to it.str("project_id").orEmpty() }
        return apps.map { a ->
            ServerlessApp(
                id = a.str("id").orEmpty(), name = a.str("name").orEmpty(), kind = kind, status = a.str("status").orEmpty(),
                region = region, projectID = projectOf[a.str("namespace_id")].orEmpty(),
                endpoint = a.str("public_endpoint") ?: a.str("domain_name") ?: "",
                minScale = a.int("min_scale") ?: 0, maxScale = a.int("max_scale") ?: 0,
            )
        }
    }

    /**
     * The organization's job definitions in a region. A job has no state of
     * its own: its status is that of its last run.
     */
    private suspend fun serverlessJobs(region: Region): List<ServerlessApp> {
        val path = "serverless-jobs/v1alpha1/regions/${region.id}"
        val jobs = pages("$path/job-definitions", mapOf("organization_id" to key.organizationID), "job_definitions")
        return jobs.map { job ->
            val runs = send(url("$path/job-runs", mapOf(
                "job_definition_id" to job.str("id").orEmpty(), "order_by" to "created_at_desc", "page_size" to "1",
            ))).obj?.objects("job_runs").orEmpty()
            val cron = job["cron_schedule"].obj
            val schedule = cron?.let { "${it.str("schedule").orEmpty()} (${it.str("timezone").orEmpty()})" } ?: ""
            ServerlessApp(
                id = job.str("id").orEmpty(), name = job.str("name").orEmpty(), kind = ServerlessApp.Kind.JOB,
                status = runs.firstOrNull()?.str("state") ?: "not run yet", region = region,
                projectID = job.str("project_id").orEmpty(), endpoint = "", minScale = 0, maxScale = 0, schedule = schedule,
            )
        }
    }

    override suspend fun listResources(product: Product, place: String?): List<Resource> {
        val path = product.path(place)
        // The Instance API filters by `organization` and pages by at most 50
        // with `per_page`; the others say `organization_id` and `page_size`.
        val instanceApi = path.startsWith("instance/")
        val pageSize = 50
        val items = allPages(pageSize) { page ->
            val query = if (instanceApi) {
                mapOf("organization" to key.organizationID, "per_page" to "$pageSize", "page" to "$page")
            } else {
                mapOf("organization_id" to key.organizationID, "page_size" to "$pageSize", "page" to "$page")
            }
            send(url(path, query)).obj?.arr(product.field).orEmpty()
        }
        return items.mapNotNull { item -> item.obj?.let { Resource.parse(it, product, place) } }
    }

    override suspend fun projectNames(): Map<String, String> =
        pages("account/v3/projects", mapOf("organization_id" to key.organizationID), "projects")
            .reversed().associate { it.str("id").orEmpty() to it.str("name").orEmpty() }

    /**
     * The project's Cockpit data sources of one type in the region: Scaleway's
     * own and any custom one the user pushes to.
     */
    private suspend fun dataSources(target: CockpitTarget, type: String): List<String> =
        pages("cockpit/v1/regions/${target.region.id}/data-sources",
              mapOf("project_id" to target.projectID, "types" to type), "data_sources")
            .mapNotNull { it.str("url") }

    override suspend fun logs(target: CockpitTarget, minutes: Int): LogResult {
        val sources = dataSources(target, "logs")
        val now = System.currentTimeMillis()
        val responses = sources.map { base ->
            send(url("loki/api/v1/query_range", mapOf(
                "query" to target.selector,
                "start" to "${now - minutes * 60_000L}000000",
                "end" to "${now}000000",
                "limit" to LOG_LIMIT.toString(),
                "direction" to "backward",
            ), base))
        }
        return LogResult(sources.size, logLines(responses))
    }

    // Metrics data sources speak the Prometheus HTTP API under /prometheus.

    override suspend fun metricNames(target: CockpitTarget, minutes: Int): MetricNames {
        val sources = dataSources(target, "metrics")
        val now = System.currentTimeMillis() / 1000
        val names = sortedSetOf<String>()
        for (base in sources) {
            val response = send(url("prometheus/api/v1/label/__name__/values", mapOf(
                "match[]" to target.selector, "start" to "${now - minutes * 60}", "end" to "$now",
            ), base))
            names += response.obj?.arr("data").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        }
        return MetricNames(sources.size, names.toList())
    }

    override suspend fun metric(name: String, target: CockpitTarget, minutes: Int): List<MetricSeries> {
        val now = System.currentTimeMillis() / 1000
        for (base in dataSources(target, "metrics")) {
            val response = send(url("prometheus/api/v1/query_range", mapOf(
                "query" to metricQuery(name, target.selector),
                "start" to "${now - minutes * 60}", "end" to "$now",
                "step" to metricStep(minutes).toString(),
            ), base))
            // The first source with data is the one that has the metric.
            val series = response.obj?.get("data").obj?.objects("result").orEmpty().mapNotNull { s ->
                val points = s.arr("values").orEmpty().mapNotNull { sample ->
                    val pair = sample as? JsonArray ?: return@mapNotNull null
                    val time = (pair.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val value = (pair.getOrNull(1) as? JsonPrimitive)?.content?.toDoubleOrNull() ?: Double.NaN
                    MetricPoint(Instant.ofEpochMilli(Math.round(time * 1000)), value)
                }
                if (points.isEmpty()) null else MetricSeries(s["metric"].obj?.str("resource_instance").orEmpty(), points)
            }
            if (series.isNotEmpty()) return series.sortedBy { it.instance }
        }
        return emptyList()
    }
}

// JSON helpers: Scaleway's responses are read as plain JSON.

internal val JsonElement?.obj: JsonObject? get() = this as? JsonObject

/** A non-empty string field. */
internal fun JsonObject.str(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content

/** A number field, also when Scaleway writes it as a string. */
internal fun JsonObject.num(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.arr(name: String): JsonArray? = this[name] as? JsonArray

internal fun JsonObject.objects(name: String): List<JsonObject> = arr(name).orEmpty().mapNotNull { it.obj }
