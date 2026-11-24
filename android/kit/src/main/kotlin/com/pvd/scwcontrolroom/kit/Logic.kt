package com.pvd.scwcontrolroom.kit

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

/**
 * A resource as the app lists it, with the keys that can see it. Two keys
 * for the same organization see the same resources; each is shown once.
 */
data class Listed<T : Identified>(val item: T, val keyIDs: List<String>) : Identified {
    override val id: String get() = item.id
}

fun <T : Identified> mergeByID(results: List<Pair<String, List<T>>>): List<Listed<T>> {
    val byID = linkedMapOf<String, Listed<T>>()
    for ((keyID, items) in results) {
        for (item in items) {
            val seen = byID[item.id]
            byID[item.id] = if (seen != null) seen.copy(keyIDs = seen.keyIDs + keyID) else Listed(item, listOf(keyID))
        }
    }
    return byID.values.toList()
}

/** Names as people sort them: case does not matter, and "web-10" comes after "web-9". */
val naturalOrder: Comparator<String> = Comparator { a, b ->
    val chunk = Regex("\\d+|\\D+")
    val x = chunk.findAll(a.lowercase()).map { it.value }.toList()
    val y = chunk.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(x.size, y.size)) {
        val (p, q) = x[i] to y[i]
        val result = if (p[0].isDigit() && q[0].isDigit()) p.toBigInteger().compareTo(q.toBigInteger()) else p.compareTo(q)
        if (result != 0) return@Comparator result
    }
    x.size.compareTo(y.size)
}

/** What is costing money comes first, then by name. */
fun sortServers(list: List<Listed<Server>>): List<Listed<Server>> {
    val rank = listOf(ServerState.RUNNING, ServerState.STARTING, ServerState.STOPPING, ServerState.LOCKED,
                      ServerState.UNKNOWN, ServerState.STOPPED_IN_PLACE, ServerState.STOPPED)
    return list.sortedWith(compareBy<Listed<Server>> { rank.indexOf(it.item.state) }.thenBy(naturalOrder) { it.item.name })
}

enum class StateFilter(val label: String) { ALL("All"), RUNNING("Running"), STOPPED("Stopped") }

fun filterServers(list: List<Listed<Server>>, query: String, state: StateFilter): List<Listed<Server>> {
    val q = query.trim().lowercase()
    return list.filter { listed ->
        val server = listed.item
        when {
            state == StateFilter.RUNNING && server.state.isStopped -> false
            state == StateFilter.STOPPED && !server.state.isStopped -> false
            q.isEmpty() -> true
            else -> server.name.lowercase().contains(q) || server.tags.any { it.lowercase().contains(q) }
        }
    }
}

/**
 * Billing is per organization, so several keys for one organization must
 * not count its costs more than once.
 */
fun uniqueOrganizations(keys: List<ApiKey>): List<ApiKey> = keys.distinctBy { it.organizationID }

/** Totals per category or project, largest first. */
fun sum(items: List<Consumption>, by: (Consumption) -> String): List<Pair<String, Double>> =
    items.groupBy(by).map { (name, group) -> name to group.sumOf { it.amount } }.sortedByDescending { it.second }

fun total(costs: MonthlyCosts): Double = costs.items.sumOf { it.amount } - costs.discount

/** YYYY-MM of this month and the months before it, newest first. */
fun recentPeriods(now: Instant, count: Int): List<String> {
    val month = YearMonth.from(now.atOffset(ZoneOffset.UTC))
    return (0 until count).map { month.minusMonths(it.toLong()).toString() }
}

/** "November 2026" for "2026-11". */
fun formatPeriod(period: String): String =
    runCatching { YearMonth.parse(period).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)) }.getOrDefault(period)

fun formatMoney(amount: Double, currency: String = "EUR"): String {
    val format = NumberFormat.getCurrencyInstance(Locale.Builder().setLanguage("en").setRegion("IE").build())
    runCatching { format.currency = Currency.getInstance(currency) }
    format.minimumFractionDigits = 2
    format.maximumFractionDigits = if (amount != 0.0 && kotlin.math.abs(amount) < 0.1) 4 else 2
    return format.format(amount)
}

/**
 * [value] with [digits] decimals, rounded as C's printf (and Swift's
 * String(format:)) do: on the exact binary value, halves to even.
 */
fun fixed(value: Double, digits: Int): String =
    if (value.isNaN() || value.isInfinite()) value.toString()
    else BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()

// Instance logs and metrics

/**
 * Scaleway's dates, such as "2026-11-20T09:12:34.567890+00:00". The
 * fraction is dropped, as the iPhone app does.
 */
fun parseScalewayDate(text: String): Instant? =
    runCatching { OffsetDateTime.parse(text.replace(Regex("\\.\\d+"), "")).toInstant() }.getOrNull()

/** Why an instance may have nothing in Cockpit for a period. */
enum class InstanceQuiet {
    /** Stopped or stopping: it sends nothing now, but may have before. */
    STOPPED,
    /** Booting: its agent starts with it. */
    STARTING,
    /** Created within the hour: no agent has been set up yet, or only just. */
    NEW,
    /** Running: no agent, or one that sends nothing yet. */
    RUNNING,
}

fun instanceQuiet(server: Server, now: Instant = Instant.now()): InstanceQuiet = when (server.state) {
    ServerState.STOPPED, ServerState.STOPPED_IN_PLACE, ServerState.STOPPING -> InstanceQuiet.STOPPED
    ServerState.STARTING -> InstanceQuiet.STARTING
    else -> {
        val created = server.created
        if (created != null && now.epochSecond - created.epochSecond < 3600) InstanceQuiet.NEW else InstanceQuiet.RUNNING
    }
}

// Metrics

/**
 * Scaleway's `serverless_container_instances_total` counts running
 * instances. It has no samples while the app is scaled to zero.
 */
fun isInstanceCount(name: String): Boolean = name.endsWith("instances_total")

/**
 * Counters only ever go up; what matters is how fast. Cockpit does not say
 * which metrics are counters, so the name tells, except for the instance count.
 */
fun isCounter(name: String): Boolean =
    !isInstanceCount(name) && (name.endsWith("_total") || name.endsWith("_count") || name.endsWith("_sum"))

/**
 * PromQL for one metric of one resource, one series per instance: the rate
 * per second for a counter, the average for anything else. The instance
 * count is one series, 0 where there are no samples (scaled to zero) so the
 * chart does not bridge those gaps.
 */
fun metricQuery(name: String, selector: String): String {
    val s = name + selector
    if (isInstanceCount(name)) return "sum($s) or vector(0)"
    return if (isCounter(name)) "sum by (resource_instance) (rate($s[5m]))" else "avg by (resource_instance) ($s)"
}

/**
 * Metrics worth a chart: `_info` metrics, and Scaleway's serverless
 * `_status`, carry their meaning in labels and are always 1.
 */
fun isChartable(name: String): Boolean =
    !name.endsWith("_info") && !(name.startsWith("serverless_") && name.endsWith("_status"))

/**
 * A readable title: "serverless_container_cpu_usage_ratio" → "CPU usage".
 * The value already shows the unit, so "bytes" goes where enough is left.
 */
fun metricTitle(name: String): String {
    val words = name.replace(Regex("^serverless_(container|function|job)_"), "").split("_").toMutableList()
    if (words.size > 1 && words.last() in listOf("total", "ratio")) words.removeAt(words.lastIndex)
    if (words.size > 2 && words.last() == "bytes") words.removeAt(words.lastIndex)
    val title = words.joinToString(" ") { if (it in listOf("cpu", "http")) it.uppercase() else it }
    return title.replaceFirstChar { it.uppercase() }
}

/** Instances are named after their deployment; the last part tells them apart. */
fun instanceLabel(instance: String): String = instance.split("-").lastOrNull { it.isNotEmpty() } ?: instance

/** About a hundred points over the period, never closer than 15 seconds. */
fun metricStep(minutes: Int): Int = maxOf(15, minutes * 60 / 100)

/** A value in the unit its name suggests, per second for counters. */
fun formatMetric(value: Double, name: String): String {
    val perSecond = if (isCounter(name)) "/s" else ""
    if (value == 0.0) return "0$perSecond"
    if (isInstanceCount(name)) return fixed(value, 0)
    if (name.contains("bytes")) {
        var amount = value
        var unit = 0
        val units = listOf("B", "KB", "MB", "GB", "TB")
        while (kotlin.math.abs(amount) >= 1000 && unit < units.size - 1) {
            amount /= 1000
            unit += 1
        }
        return fixed(amount, if (unit == 0) 0 else 1) + " " + units[unit] + perSecond
    }
    // Scaleway's serverless CPU "ratio" is a percentage already.
    if (name.startsWith("serverless_") && name.endsWith("cpu_usage_ratio")) return fixed(value, 1) + "%"
    if (name.endsWith("_ratio")) return fixed(value * 100, 1) + "%"
    if (name.contains("percent")) return fixed(value, 1) + "%"
    val digits = if (kotlin.math.abs(value) >= 100) 0 else if (kotlin.math.abs(value) >= 1) 1 else 3
    return fixed(value, digits) + perSecond
}

private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** Returns what is wrong with an entered key, or null when it looks right. */
fun validateKey(label: String, secretKey: String, organizationID: String): String? = when {
    label.isBlank() -> "Give the key a name."
    !uuid.matches(secretKey.trim()) -> "The secret key is a UUID, like 1a2b3c4d-…."
    !uuid.matches(organizationID.trim()) -> "The organization ID is a UUID, like 1a2b3c4d-…."
    else -> null
}

/**
 * The app asks for a rating after a server was started or stopped
 * successfully: the moment it has just been useful. Google Play limits how
 * often the prompt shows whatever we ask, so a few milestones are enough.
 */
fun shouldAskForReview(successfulActions: Int): Boolean = successfulActions in listOf(3, 20, 60)

/**
 * Runs [block] and keeps its failure as a value, so one failing region or
 * product does not end a listing. Cancellation still cancels.
 */
suspend fun <T> attempt(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
