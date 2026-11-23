package com.pvd.scwcontrolroom.kit

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable

/** Something listed by its ID, so several keys' lists can be merged. */
interface Identified {
    val id: String
}

/**
 * Every zone the Instance API serves. Listing runs against all of them,
 * because a key cannot tell us where its servers are.
 */
enum class Zone(val id: String) {
    FR_PAR_1("fr-par-1"), FR_PAR_2("fr-par-2"), FR_PAR_3("fr-par-3"),
    NL_AMS_1("nl-ams-1"), NL_AMS_2("nl-ams-2"), NL_AMS_3("nl-ams-3"),
    PL_WAW_1("pl-waw-1"), PL_WAW_2("pl-waw-2"), PL_WAW_3("pl-waw-3"),
    IT_MIL_1("it-mil-1");

    val region: Region get() = Region.of(id.substringBeforeLast("-"))!!

    companion object {
        fun of(id: String): Zone? = entries.firstOrNull { it.id == id }
    }
}

/** Regions of Serverless and Cockpit. */
enum class Region(val id: String) {
    FR_PAR("fr-par"), NL_AMS("nl-ams"), PL_WAW("pl-waw"), IT_MIL("it-mil");

    companion object {
        fun of(id: String): Region? = entries.firstOrNull { it.id == id }
    }
}

enum class ServerState(val apiValue: String) {
    RUNNING("running"), STARTING("starting"), STOPPING("stopping"), LOCKED("locked"),
    STOPPED_IN_PLACE("stopped in place"), STOPPED("stopped"), UNKNOWN("unknown");

    /** Starting or stopping: the app keeps checking until it settles. */
    val isBusy: Boolean get() = this == STARTING || this == STOPPING
    val isStopped: Boolean get() = this == STOPPED || this == STOPPED_IN_PLACE

    companion object {
        fun of(apiValue: String): ServerState = entries.firstOrNull { it.apiValue == apiValue } ?: UNKNOWN
    }
}

/**
 * `poweroff` stops the server and releases its hypervisor, which is what
 * stops the compute billing. `stop_in_place` keeps it reserved (and billed),
 * so the app does not offer it.
 */
enum class ServerAction(val apiValue: String) {
    POWERON("poweron"), POWEROFF("poweroff"), REBOOT("reboot")
}

data class Server(
    override val id: String,
    val name: String,
    val state: ServerState,
    val commercialType: String,
    val zone: Zone,
    val project: String,
    val tags: List<String> = emptyList(),
    val publicIPs: List<String> = emptyList(),
    val created: Instant? = null,
) : Identified

data class ServerlessApp(
    override val id: String,
    val name: String,
    val kind: Kind,
    val status: String,
    val region: Region,
    val projectID: String,
    val endpoint: String,
    val minScale: Int,
    val maxScale: Int,
    /** A job's cron schedule with its time zone; empty for the others. */
    val schedule: String = "",
) : Identified {
    enum class Kind(val id: String) {
        CONTAINER("container"), FUNCTION("function"), JOB("job");

        /** "Containers", "Functions", "Jobs". */
        val plural: String get() = id.replaceFirstChar { it.uppercase() } + "s"
    }

    /** Cockpit labels a job's logs with its definition; their resource_id is the run. */
    val cockpitTarget: CockpitTarget
        get() = CockpitTarget(projectID, region, id, if (kind == Kind.JOB) "job_definition_id" else "resource_id")
}

/**
 * Where Cockpit keeps a resource's logs and metrics. Scaleway labels its data
 * with the resource's ID (jobs: the job definition's), and the instance setup
 * guide adds the same label.
 */
data class CockpitTarget(
    val projectID: String,
    val region: Region,
    val resourceID: String,
    val label: String = "resource_id",
) {
    /** The Loki and Prometheus label selector for the resource. */
    val selector: String get() = "{$label=\"$resourceID\"}"
}

data class LogLine(val time: Instant, val line: String)

data class LogResult(
    /** How many log data sources the project has in that region. */
    val sources: Int,
    /** Newest first. */
    val lines: List<LogLine>,
)

data class MetricPoint(val time: Instant, val value: Double)

/** One line of a chart: a metric of one instance of the resource. */
data class MetricSeries(
    /** Cockpit's `resource_instance`; empty when the metric is for the resource as a whole. */
    val instance: String,
    val points: List<MetricPoint>,
)

data class MetricNames(
    /** How many metrics data sources the project has in that region. */
    val sources: Int,
    /** The metrics that have data for the resource, sorted. */
    val names: List<String>,
)

data class Consumption(val amount: Double, val category: String, val product: String, val project: String)

data class MonthlyCosts(val items: List<Consumption>, val discount: Double, val currency: String)

/**
 * A Scaleway API key as the user entered it. Only the secret key is sent to
 * the API, so the access key is not asked for. The demo key is a key like
 * any other, served by a fake client instead of the API.
 */
@Serializable
data class ApiKey(
    override val id: String = UUID.randomUUID().toString(),
    val label: String,
    val secretKey: String,
    val organizationID: String,
    val isDemo: Boolean = false,
) : Identified {
    companion object {
        val demo = ApiKey(id = "demo", label = "Demo", secretKey = "", organizationID = "demo-organization", isDemo = true)
    }
}

class ApiException(val status: Int, override val message: String) : Exception(message) {
    /** The key may not do this: try another key, or leave the feature out. */
    val isForbidden: Boolean get() = status == 401 || status == 403

    /**
     * Scaleway does not offer the product in this region or zone (it answers
     * "unknown service"): nothing to list there, and not worth a warning.
     */
    val isUnknownService: Boolean get() = message.lowercase().contains("unknown service")
}

interface ScalewayClient {
    suspend fun listServers(zone: Zone): List<Server>
    suspend fun act(zone: Zone, serverID: String, action: ServerAction)
    /** `period` is YYYY-MM. */
    suspend fun costs(period: String): MonthlyCosts
    /** Commercial type → price per hour. */
    suspend fun hourlyPrices(zone: Zone): Map<String, Double>
    suspend fun listServerless(region: Region): List<ServerlessApp>
    /** One product's resources in a region or zone (null: a global product). */
    suspend fun listResources(product: Product, place: String?): List<Resource>
    /** Project ID → name. */
    suspend fun projectNames(): Map<String, String>
    /** The newest lines of the last [minutes], newest first. */
    suspend fun logs(target: CockpitTarget, minutes: Int): LogResult
    /** Which metrics Cockpit has for the resource in the last [minutes]. */
    suspend fun metricNames(target: CockpitTarget, minutes: Int): MetricNames
    /** One metric over the last [minutes], oldest first (see [metricQuery]). One series per instance. */
    suspend fun metric(name: String, target: CockpitTarget, minutes: Int): List<MetricSeries>
}

fun clientFor(key: ApiKey): ScalewayClient = if (key.isDemo) DemoClient() else ScalewayApi(key)
