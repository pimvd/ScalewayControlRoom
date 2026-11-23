package com.pvd.scwcontrolroom.kit

import java.net.URI
import java.net.URLDecoder

enum class AppTab { INSTANCES, SERVERLESS, OTHER, COSTS, SETTINGS }

/** Which kind of resource a logs or metrics screen is for. */
enum class LogKind(val id: String) {
    INSTANCE("instance"), SERVERLESS("serverless"), RESOURCE("resource");

    companion object {
        fun of(id: String): LogKind? = entries.firstOrNull { it.id == id }
    }
}

/** Screens pushed onto a tab's back stack. */
sealed interface Destination {
    data class Instance(val id: String) : Destination
    data class Serverless(val id: String) : Destination
    data class Resource(val id: String) : Destination
    data class Logs(val kind: LogKind, val id: String) : Destination
    data class Metrics(val kind: LogKind, val id: String) : Destination
    data class Metric(val kind: LogKind, val id: String, val name: String) : Destination
}

/**
 * Where a screenshot route ("/costs", "/instance/demo-1",
 * "/logs?kind=serverless&id=demo-c1") leads: a tab and the screens on it.
 * The screenshot test passes one per picture, as the iPhone's Screenshots
 * workflow does.
 */
fun screenshotDestination(route: String): Pair<AppTab, List<Destination>> {
    val uri = runCatching { URI(route) }.getOrNull()
    val parts = (uri?.path ?: route).split("/").filter { it.isNotEmpty() }
    val query = uri?.rawQuery.orEmpty().split("&").filter { it.isNotEmpty() }.associate { pair ->
        val (name, value) = (pair.split("=", limit = 2) + "").take(2)
        URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
    }
    val second = parts.getOrNull(1)
    return when (parts.firstOrNull()) {
        "instance" if second != null -> AppTab.INSTANCES to listOf(Destination.Instance(second))
        "serverless" if second != null -> AppTab.SERVERLESS to listOf(Destination.Serverless(second))
        "serverless" -> AppTab.SERVERLESS to emptyList()
        "resource" if second != null -> AppTab.OTHER to listOf(Destination.Resource(second))
        "other" -> AppTab.OTHER to emptyList()
        "costs" -> AppTab.COSTS to emptyList()
        "settings" -> AppTab.SETTINGS to emptyList()
        "logs", "metrics", "metric" -> {
            val id = query["id"].orEmpty()
            val kind = LogKind.of(query["kind"].orEmpty()) ?: LogKind.INSTANCE
            val screen = when (parts[0]) {
                "logs" -> listOf(Destination.Logs(kind, id))
                "metrics" -> listOf(Destination.Metrics(kind, id))
                else -> listOf(Destination.Metrics(kind, id), Destination.Metric(kind, id, query["name"].orEmpty()))
            }
            when (kind) {
                LogKind.INSTANCE -> AppTab.INSTANCES to listOf(Destination.Instance(id)) + screen
                LogKind.SERVERLESS -> AppTab.SERVERLESS to listOf(Destination.Serverless(id)) + screen
                LogKind.RESOURCE -> AppTab.OTHER to listOf(Destination.Resource(id)) + screen
            }
        }
        else -> AppTab.INSTANCES to emptyList()
    }
}

/**
 * The subscription the app sells on Google Play: one product with a monthly
 * and a yearly base plan. PlayStore/subscriptions.json configures the same
 * IDs; scripts/validate_listing.py checks both match.
 */
object ProProducts {
    const val PRODUCT = "pro"
    const val MONTHLY = "monthly"
    const val YEARLY = "yearly"
    val basePlans = listOf(YEARLY, MONTHLY)

    /** Without Pro, one real key (the demo key does not count). */
    const val FREE_KEYS = 1
}
