package com.pvd.scwcontrolroom.kit

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Any Scaleway resource the Other tab lists: databases, volumes, IPs,
 * domains, secrets and the rest. Each product is one entry in [Products.all];
 * its list endpoint is read as plain JSON, so a product needs no code of its own.
 */
data class Resource(
    override val id: String,
    val name: String,
    /** The product, as [Product.type] names it. */
    val type: String,
    val status: String,
    /** A zone, a region, or "global". */
    val location: String,
    val projectID: String,
    /** Everything else its list item says, the product's key fields first. */
    val details: List<Detail> = emptyList(),
) : Identified {
    /** The region of a regional or zonal resource; global ones have none. */
    val region: Region? get() = Region.of(location) ?: Zone.of(location)?.region

    /**
     * Scaleway labels the logs and metrics of its managed products with the
     * resource's ID, in the project's Cockpit in the resource's region.
     */
    val cockpitTarget: CockpitTarget? get() = region?.let { CockpitTarget(projectID, it, id) }

    companion object {
        /**
         * Reads one item of a product's list. Products name their fields a
         * little differently: the Instance API says `project` and `state`, DNS
         * zones have no ID, IPs no name.
         */
        fun parse(item: JsonObject, product: Product, place: String?): Resource? {
            // Scaleway's own private DNS zones for VPCs, named by IDs: not managed by anyone.
            if (item.str("domain") == "privatedns") return null
            val dnsName = item.str("domain")?.let { domain -> item.str("subdomain")?.let { "$it.$domain" } ?: domain }
            val id = item.str("id") ?: dnsName ?: return null
            return Resource(
                id = id,
                name = item.str("name") ?: dnsName ?: item.str("address") ?: id,
                type = product.type,
                status = item.str("status") ?: item.str("state") ?: "",
                location = item.str("zone") ?: item.str("region") ?: place ?: "global",
                projectID = item.str("project_id") ?: item.str("project") ?: "",
                details = Detail.all(item, product.keyFields),
            )
        }
    }
}

/** One product's list endpoint. */
data class Product(
    val type: String,
    /** With `{place}` for the region or zone. */
    val path: String,
    /** The list field of the response. */
    val field: String,
    val scope: Scope,
    /**
     * The fields the detail screen shows first, as paths into the list item
     * ("endpoint.ip"); a path also takes the fields below it. A field the
     * item lacks is simply not shown.
     */
    val keyFields: List<String> = emptyList(),
) {
    enum class Scope { REGIONAL, ZONAL, GLOBAL }

    /** Where to ask: every region or zone, or once for a global product. */
    val places: List<String?>
        get() = when (scope) {
            Scope.REGIONAL -> Region.entries.map { it.id }
            Scope.ZONAL -> Zone.entries.map { it.id }
            Scope.GLOBAL -> listOf(null)
        }

    fun path(place: String?): String = path.replace("{place}", place ?: "")
}

object Products {
    private val R = Product.Scope.REGIONAL
    private val Z = Product.Scope.ZONAL
    private val G = Product.Scope.GLOBAL

    /**
     * Every product with resources, besides instances and serverless (their
     * own tabs). Object Storage is missing: buckets are listed through the
     * S3 API, which needs an access key the app does not have.
     */
    val all: List<Product> = listOf(
        Product("Managed Database", "rdb/v1/regions/{place}/instances", "instances", R,
                listOf("engine", "node_type", "endpoint.ip", "endpoint.port", "volume.size", "is_ha_cluster")),
        Product("Redis", "redis/v1/zones/{place}/clusters", "clusters", Z,
                listOf("version", "node_type", "cluster_size", "endpoints.ips", "endpoints.port", "tls_enabled")),
        Product("MongoDB", "mongodb/v1/regions/{place}/instances", "instances", R,
                listOf("version", "node_type", "node_amount", "volume.size", "endpoints")),
        Product("Serverless SQL Database", "serverless-sqldb/v1alpha1/regions/{place}/databases", "databases", R,
                listOf("endpoint", "cpu_min", "cpu_max")),
        Product("Kafka", "kafka/v1alpha1/regions/{place}/clusters", "clusters", R,
                listOf("version", "node_type", "node_amount", "volume.size")),
        Product("OpenSearch", "searchdb/v1alpha1/regions/{place}/deployments", "deployments", R,
                listOf("version", "node_type", "node_amount", "volume.size")),
        Product("Data Warehouse", "datawarehouse/v1beta1/regions/{place}/deployments", "deployments", R,
                listOf("version", "node_type", "replica_count", "shard_count")),
        Product("Kubernetes", "k8s/v1/regions/{place}/clusters", "clusters", R,
                listOf("version", "type", "cni", "cluster_url")),
        Product("Elastic Metal", "baremetal/v1/zones/{place}/servers", "servers", Z,
                listOf("offer_name", "ips.address", "domain")),
        Product("Apple Silicon", "apple-silicon/v1alpha1/zones/{place}/servers", "servers", Z,
                listOf("type", "ip", "os.name")),
        Product("Inference", "inference/v1/regions/{place}/deployments", "deployments", R,
                listOf("model_name", "node_type_name", "endpoints.url", "size")),
        Product("Block volume", "block/v1/zones/{place}/volumes", "volumes", Z,
                listOf("size", "type", "specs.perf_iops", "references")),
        Product("Block snapshot", "block/v1/zones/{place}/snapshots", "snapshots", Z,
                listOf("size", "parent_volume.name")),
        Product("Local volume", "instance/v1/zones/{place}/volumes", "volumes", Z,
                listOf("size", "volume_type", "server.name")),
        Product("File Storage", "file/v1alpha1/regions/{place}/filesystems", "filesystems", R,
                listOf("size", "number_of_attachments")),
        Product("Container Registry", "registry/v1/regions/{place}/namespaces", "namespaces", R,
                listOf("endpoint", "image_count", "size", "is_public")),
        Product("Load Balancer", "lb/v1/zones/{place}/lbs", "lbs", Z,
                listOf("type", "ip.ip_address", "frontend_count", "backend_count")),
        Product("Flexible IP", "instance/v1/zones/{place}/ips", "ips", Z,
                listOf("address", "reverse", "server.name", "type")),
        Product("VPC", "vpc/v2/regions/{place}/vpcs", "vpcs", R,
                listOf("private_network_count", "is_default", "routing_enabled")),
        Product("Private Network", "vpc/v2/regions/{place}/private-networks", "private_networks", R,
                listOf("subnets.subnet", "dhcp_enabled")),
        Product("Public Gateway", "vpc-gw/v2/zones/{place}/gateways", "gateways", Z,
                listOf("type", "ipv4.address", "bastion_enabled", "bastion_port")),
        Product("Edge Services", "edge-services/v1beta1/pipelines", "pipelines", G,
                listOf("description")),
        Product("DNS zone", "domain/v2beta1/dns-zones", "dns_zones", G,
                listOf("ns", "ns_master", "linked_products")),
        Product("Domain", "domain/v2beta1/domains", "domains", G,
                listOf("expired_at", "auto_renew_status", "dnssec.status")),
        Product("Transactional Email", "transactional-email/v1alpha1/regions/{place}/domains", "domains", R,
                listOf("last_error", "reputation.status", "statistics.sent_count", "statistics.failed_count")),
        Product("NATS", "mnq/v1beta1/regions/{place}/nats-accounts", "nats_accounts", R,
                listOf("endpoint")),
        Product("Secret", "secret-manager/v1beta1/regions/{place}/secrets", "secrets", R,
                listOf("description", "type", "version_count", "path")),
        Product("Key", "key-manager/v1alpha1/regions/{place}/keys", "keys", R,
                listOf("usage", "rotation_policy.next_rotation_at", "protected")),
        Product("IoT Hub", "iot/v1/regions/{place}/hubs", "hubs", R,
                listOf("product_plan", "endpoint", "device_count")),
        Product("Web Hosting", "webhosting/v1/regions/{place}/hostings", "hostings", R,
                listOf("domain", "offer.name", "platform_hostname")),
    )
}

/** One field of a resource as the detail screen shows it: "Volume size", "10 GB". */
data class Detail(
    val label: String,
    val value: String,
    /** One of the product's key fields, shown before the rest. */
    val key: Boolean = false,
) {
    companion object {
        /** What the header already shows, wherever it appears. */
        private val hidden = setOf("id", "srn", "region", "zone", "organization", "organization_id", "project", "project_id")
        /** What the header already shows, at the top of the item. */
        private val header = setOf("name", "status", "state")

        /**
         * Every field of a list item with a value, one line per leaf: nested
         * objects and lists of objects are walked ("Endpoints 2 IP"), lists of
         * values joined. IDs and empty values are left out. The key fields come
         * first in their order, the rest by label.
         */
        fun all(item: JsonObject, keyFields: List<String>): List<Detail> {
            val found = mutableListOf<Pair<List<String>, String>>()
            fun walk(value: JsonElement, path: List<String>) {
                val last = path.lastOrNull().orEmpty()
                if (last in hidden || last.endsWith("_id")) return
                when {
                    value is JsonObject -> {
                        // A setting: {"name": "max_connections", "value": "100"}.
                        val name = value.str("name")
                        if (value.keys == setOf("name", "value") && name != null) {
                            walk(value["value"]!!, path.filter { it.toIntOrNull() == null } + name)
                            return
                        }
                        for ((field, inner) in value) walk(inner, path + field)
                    }
                    value is JsonArray && value.any { it is JsonObject } -> {
                        if (value.size == 1) walk(value[0], path)
                        else value.forEachIndexed { index, inner -> walk(inner, path + (index + 1).toString()) }
                    }
                    value is JsonArray -> {
                        val values = value.mapNotNull { format(it, last) }
                        if (values.isNotEmpty()) found += path to values.joinToString(", ")
                    }
                    else -> format(value, last)?.let { found += path to it }
                }
            }
            for ((name, value) in item) if (name !in header) walk(value, listOf(name))

            fun rank(path: List<String>): Int? {
                val field = path.filter { it.toIntOrNull() == null }.joinToString(".")
                return keyFields.indexOfFirst { field == it || field.startsWith("$it.") }.takeIf { it >= 0 }
            }
            return found
                .map { (path, value) -> rank(path) to Detail(label(path), value, rank(path) != null) }
                .sortedWith { a, b ->
                    val (x, y) = a.first to b.first
                    when {
                        x != null && y != null && x != y -> x.compareTo(y)
                        x != null && y == null -> -1
                        x == null && y != null -> 1
                        else -> naturalOrder.compare(a.second.label, b.second.label)
                    }
                }
                .map { it.second }
        }

        private val words = mapOf(
            "ip" to "IP", "ips" to "IPs", "ipv4" to "IPv4", "ipv6" to "IPv6", "ha" to "HA", "dns" to "DNS",
            "dnssec" to "DNSSEC", "spf" to "SPF", "dkim" to "DKIM", "dmarc" to "DMARC", "mx" to "MX", "kms" to "KMS",
            "url" to "URL", "ns" to "NS", "dhcp" to "DHCP", "tls" to "TLS", "ssl" to "SSL", "ssh" to "SSH",
            "cpu" to "CPU", "gpu" to "GPU", "api" to "API", "cni" to "CNI", "vpc" to "VPC", "vnc" to "VNC",
            "id" to "ID", "os" to "OS",
        )

        /** "endpoint", "ip" → "Endpoint IP"; "is_ha_cluster" → "HA cluster". */
        fun label(path: List<String>): String {
            val text = path.flatMap { part ->
                val bare = listOf("is_", "has_").fold(part) { p, prefix -> p.removePrefix(prefix) }
                bare.split("_").filter { it.isNotEmpty() }.map { words[it.lowercase()] ?: it }
            }.joinToString(" ")
            return text.replaceFirstChar { it.uppercase() }
        }

        private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK)

        /** A value as people read it, or null when there is nothing to show. */
        fun format(value: JsonElement, name: String): String? {
            if (value !is JsonPrimitive || value is JsonNull) return null
            if (value.isString) {
                val text = value.content
                if (text.isEmpty()) return null
                if (text.length >= 20 && text.contains("T")) {
                    runCatching { OffsetDateTime.parse(text) }.getOrNull()?.let {
                        return it.atZoneSameInstant(ZoneId.systemDefault()).format(dateFormat)
                    }
                }
                if (text.endsWith("s")) text.dropLast(1).toIntOrNull()?.let { return duration(it) }
                return text
            }
            value.booleanOrNull?.let { return if (it) "Yes" else "No" }
            val number = value.doubleOrNull ?: return null
            if (name == "size" || name.endsWith("_bytes")) return bytes(number.toLong())
            return if (number == Math.rint(number) && kotlin.math.abs(number) < 1e15) number.toLong().toString() else number.toString()
        }

        /** Decimal sizes, as the iPhone's ByteCountFormatter writes them: "10 GB", "1.5 MB". */
        fun bytes(count: Long): String {
            if (count < 1000) return "$count bytes"
            val units = listOf("KB", "MB", "GB", "TB", "PB")
            var amount = count / 1000.0
            var unit = 0
            while (amount >= 1000 && unit < units.size - 1) {
                amount /= 1000
                unit += 1
            }
            val text = fixed(amount, minOf(unit, 2))
            return (if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text) + " " + units[unit]
        }

        /** Scaleway writes durations as seconds: "31536000s" → "365 days". */
        private fun duration(seconds: Int): String {
            for ((size, unit) in listOf(86400 to "day", 3600 to "hour", 60 to "minute")) {
                if (seconds >= size && seconds % size == 0) {
                    val count = seconds / size
                    return "$count $unit${if (count == 1) "" else "s"}"
                }
            }
            return "$seconds seconds"
        }
    }
}

/**
 * Lists every product in every place with at most [limit] requests at a
 * time: Scaleway answers a burst of them with 429. A place without the
 * product ("unknown service") and a product the key may not see are left
 * out; other failures are named in the problem.
 */
suspend fun listResources(
    client: ScalewayClient,
    products: List<Product> = Products.all,
    limit: Int = 6,
): Pair<List<Resource>, String?> {
    val calls = products.flatMap { product -> product.places.map { product to it } }
    val window = Semaphore(maxOf(1, limit))
    val results = coroutineScope {
        calls.map { (product, place) ->
            async { window.withPermit { attempt { client.listResources(product, place) } } }
        }.awaitAll()
    }
    val resources = mutableListOf<Resource>()
    val failed = mutableListOf<String>()
    var forbidden = 0
    var offered = calls.size
    var firstError: String? = null
    for ((call, result) in calls.zip(results)) {
        val (product, place) = call
        val error = result.exceptionOrNull()
        if (error == null) {
            resources += result.getOrThrow()
            continue
        }
        val apiError = error as? ApiException
        when {
            apiError?.isUnknownService == true -> offered -= 1
            apiError?.isForbidden == true -> forbidden += 1
            else -> {
                firstError = firstError ?: (error.message ?: error.toString())
                failed += place?.let { "${product.type} ($it)" } ?: product.type
            }
        }
    }
    if (forbidden > 0 && forbidden == offered) {
        return resources to "This key may not list other resources. Add AllProductsReadOnly to its policy."
    }
    if (firstError == null) return resources to null
    val names = failed.take(3).joinToString(", ") + if (failed.size > 3) " and ${failed.size - 3} more" else ""
    return resources to "Could not load $names: $firstError"
}

/** One line of the resource list, whatever the resource is. */
data class ResourceRow(
    override val id: String,
    val name: String,
    val type: String,
    val status: String,
    val location: String,
    val projectID: String,
    val keyIDs: List<String>,
    val destination: Destination,
) : Identified {
    companion object {
        fun of(listed: Listed<Resource>) = listed.item.let { r ->
            ResourceRow(r.id, r.name, r.type, r.status, r.location, r.projectID, listed.keyIDs, Destination.Resource(r.id))
        }

        fun ofServer(listed: Listed<Server>) = listed.item.let { s ->
            ResourceRow(s.id, s.name, "Instance", s.state.apiValue, s.zone.id, s.project, listed.keyIDs,
                        Destination.Instance(s.id))
        }

        fun ofApp(listed: Listed<ServerlessApp>) = listed.item.let { a ->
            ResourceRow(a.id, a.name, "Serverless ${a.kind.id}", a.status, a.region.id, a.projectID, listed.keyIDs,
                        Destination.Serverless(a.id))
        }
    }
}

enum class ResourceGrouping(val label: String) { TYPE("Type"), LOCATION("Location"), PROJECT("Project") }

/**
 * The rows matching the search, in sections by type, location or project,
 * sorted by section title and then by name.
 */
fun groupResources(
    rows: List<ResourceRow>,
    by: ResourceGrouping,
    query: String,
    projectNames: Map<String, String>,
): List<Pair<String, List<ResourceRow>>> {
    fun project(row: ResourceRow) = projectNames[row.projectID] ?: row.projectID
    val words = query.lowercase().split(" ").filter { it.isNotEmpty() }
    val matching = rows.filter { row ->
        val text = listOf(row.name, row.type, row.status, row.location, project(row), row.id).joinToString(" ").lowercase()
        words.all { text.contains(it) }
    }
    val sections = matching.groupBy { row ->
        when (by) {
            ResourceGrouping.TYPE -> row.type
            ResourceGrouping.LOCATION -> row.location
            ResourceGrouping.PROJECT -> project(row).ifEmpty { "No project" }
        }
    }
    return sections.keys.sortedWith(naturalOrder).map { title ->
        title to sections.getValue(title).sortedWith(compareBy(naturalOrder) { it.name })
    }
}
