package com.pvd.scwcontrolroom.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pvd.scwcontrolroom.kit.ApiException
import com.pvd.scwcontrolroom.kit.ApiKey
import com.pvd.scwcontrolroom.kit.CockpitTarget
import com.pvd.scwcontrolroom.kit.Listed
import com.pvd.scwcontrolroom.kit.LogKind
import com.pvd.scwcontrolroom.kit.Region
import com.pvd.scwcontrolroom.kit.Resource
import com.pvd.scwcontrolroom.kit.ScalewayClient
import com.pvd.scwcontrolroom.kit.Server
import com.pvd.scwcontrolroom.kit.ServerAction
import com.pvd.scwcontrolroom.kit.ServerState
import com.pvd.scwcontrolroom.kit.ServerlessApp
import com.pvd.scwcontrolroom.kit.Zone
import com.pvd.scwcontrolroom.kit.attempt
import com.pvd.scwcontrolroom.kit.clientFor
import com.pvd.scwcontrolroom.kit.listResources
import com.pvd.scwcontrolroom.kit.mergeByID
import com.pvd.scwcontrolroom.kit.naturalOrder
import com.pvd.scwcontrolroom.kit.sortServers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class KeyProblem(val keyID: String, val area: Area, val message: String) {
    enum class Area { INSTANCES, SERVERLESS, OTHER }
}

/**
 * Where a resource's logs and metrics are, which keys see it, and the
 * server when it is an instance (for the setup guide).
 */
data class CockpitResource(val target: CockpitTarget, val keyIDs: List<String>, val server: Server?)

/** The app's state: the keys, what they can see, and the actions on it. Lives on the main thread. */
class AppModel(private val store: KeyStorage) {
    private val scope = MainScope()

    var keys by mutableStateOf(emptyList<ApiKey>())
        private set
    var loaded by mutableStateOf(false)
        private set
    var servers by mutableStateOf(emptyList<Listed<Server>>())
        private set
    var serverless by mutableStateOf(emptyList<Listed<ServerlessApp>>())
        private set
    /** Everything else, loaded when the Other tab asks for it. */
    var resources by mutableStateOf(emptyList<Listed<Resource>>())
        private set
    var projectNames by mutableStateOf(emptyMap<String, String>())
        private set
    var loadingResources by mutableStateOf(false)
        private set
    var problems by mutableStateOf(emptyList<KeyProblem>())
        private set
    var refreshing by mutableStateOf(false)
        private set

    /** "zone/commercialType" → price per hour. */
    private val prices = mutableStateMapOf<String, Double>()
    private val pricedZones = mutableSetOf<Zone>()
    private var poll: Job? = null

    fun label(keyID: String): String = keys.firstOrNull { it.id == keyID }?.label.orEmpty()

    fun price(server: Server): Double? = prices["${server.zone.id}/${server.commercialType}"]

    fun cockpitResource(kind: LogKind, id: String): CockpitResource? = when (kind) {
        LogKind.INSTANCE -> servers.firstOrNull { it.id == id }?.let { listed ->
            val server = listed.item
            CockpitResource(CockpitTarget(server.project, server.zone.region, id), listed.keyIDs, server)
        }
        LogKind.SERVERLESS -> serverless.firstOrNull { it.id == id }?.let {
            CockpitResource(it.item.cockpitTarget, it.keyIDs, null)
        }
        LogKind.RESOURCE -> resources.firstOrNull { it.id == id }?.let { listed ->
            listed.item.cockpitTarget?.let { CockpitResource(it, listed.keyIDs, null) }
        }
    }

    // Other resources

    /** The keys the resources were last listed for. */
    private var resourcesListedFor: List<String>? = null

    /**
     * Lists every other resource the first time the Other tab is shown, and
     * again when the keys change; pulling the list refreshes it.
     */
    suspend fun loadResourcesIfNeeded() {
        if (resourcesListedFor != keys.map { it.id }) refreshResources()
    }

    /**
     * Lists every other resource of every key: some 170 requests per key,
     * so not with every refresh. In a job of its own: leaving the screen
     * cancels the caller, and a cancelled listing would replace the list
     * with the part that had loaded.
     */
    suspend fun refreshResources() {
        scope.launch { listAllResources() }.join()
    }

    private suspend fun listAllResources() {
        if (loadingResources) return
        loadingResources = true
        try {
            val current = keys
            val results = coroutineScope {
                current.map { key ->
                    async {
                        val api = clientFor(key)
                        val names = async { attempt { api.projectNames() }.getOrNull().orEmpty() }
                        val (found, problem) = listResources(api)
                        Triple(key.id, found to problem, names.await())
                    }
                }.awaitAll()
            }
            resources = mergeByID(results.map { (keyID, listed, _) -> keyID to listed.first })
            resourcesListedFor = current.map { it.id }
            projectNames = results.fold(emptyMap()) { names, result -> result.third + names }
            problems = problems.filter { it.area != KeyProblem.Area.OTHER } + results.mapNotNull { (keyID, listed, _) ->
                listed.second?.let { KeyProblem(keyID, KeyProblem.Area.OTHER, it) }
            }
        } finally {
            loadingResources = false
        }
    }

    // Keys

    fun load() {
        keys = runCatching { store.load() }.getOrDefault(emptyList())
        loaded = true
    }

    suspend fun add(key: ApiKey) {
        val next = keys.filter { it.id != key.id } + key
        store.save(next)
        keys = next
        refresh()
    }

    suspend fun remove(key: ApiKey) {
        val next = keys.filter { it.id != key.id }
        runCatching { store.save(next) }
        keys = next
        refresh()
    }

    // Listing

    private data class Listing(
        val keyID: String,
        val servers: List<Server>,
        val serversProblem: String?,
        val apps: List<ServerlessApp>,
        val appsProblem: String?,
    )

    suspend fun refresh() {
        if (refreshing) return
        refreshing = true
        try {
            val current = keys
            val listings = coroutineScope { current.map { key -> async { list(key) } }.awaitAll() }
            servers = sortServers(mergeByID(listings.map { it.keyID to it.servers }))
            serverless = mergeByID(listings.map { it.keyID to it.apps }).sortedWith(compareBy(naturalOrder) { it.item.name })
            // The Other tab's problems come from its own loading; keep them.
            problems = listings.flatMap { listing ->
                listOfNotNull(
                    listing.serversProblem?.let { KeyProblem(listing.keyID, KeyProblem.Area.INSTANCES, it) },
                    listing.appsProblem?.let { KeyProblem(listing.keyID, KeyProblem.Area.SERVERLESS, it) },
                )
            } + problems.filter { it.area == KeyProblem.Area.OTHER && it.keyID in current.map { key -> key.id } }
        } finally {
            refreshing = false
        }
        loadPrices()
        pollWhileBusy()
    }

    private suspend fun list(key: ApiKey): Listing = coroutineScope {
        val api = clientFor(key)
        val instances = async { listAll(Zone.entries, { it.id }, quietForbidden = false) { api.listServers(it) } }
        // A key that may not see serverless has none; say why only when it may
        // not anywhere, rather than leave an unexplained empty list.
        val apps = async {
            listAll(Region.entries, { it.id }, quietForbidden = true,
                    forbiddenEverywhere = "This key may not list serverless apps. Add AllProductsReadOnly to its policy.") {
                api.listServerless(it)
            }
        }
        val (i, a) = instances.await() to apps.await()
        Listing(key.id, i.first, i.second, a.first, a.second)
    }

    /** Lists every place in parallel. Places that fail are named in the problem. */
    private suspend fun <P, T> listAll(
        places: List<P>,
        name: (P) -> String,
        quietForbidden: Boolean,
        forbiddenEverywhere: String? = null,
        list: suspend (P) -> List<T>,
    ): Pair<List<T>, String?> {
        val results = coroutineScope { places.map { place -> async { attempt { list(place) } } }.awaitAll() }
        val items = mutableListOf<T>()
        val failed = mutableListOf<Pair<P, Throwable>>()
        var forbidden = 0
        var offered = places.size
        for ((place, result) in places.zip(results)) {
            val error = result.exceptionOrNull()
            if (error == null) {
                items += result.getOrThrow()
                continue
            }
            val apiError = error as? ApiException
            // A region without the product, or a key that may not see it
            // (where asked), is simply empty.
            when {
                apiError?.isUnknownService == true -> offered -= 1
                quietForbidden && apiError?.isForbidden == true -> forbidden += 1
                else -> failed += place to error
            }
        }
        // Every place that offers the product said "may not".
        if (forbiddenEverywhere != null && forbidden > 0 && forbidden == offered) return items to forbiddenEverywhere
        val first = failed.firstOrNull() ?: return items to null
        val message = first.second.message ?: first.second.toString()
        if (failed.size == places.size) return items to message
        return items to "Could not load ${failed.joinToString(", ") { name(it.first) }}: $message"
    }

    /** Prices never change while the app runs: fetch each zone once. */
    private fun loadPrices() {
        for (listed in servers) {
            val zone = listed.item.zone
            if (!pricedZones.add(zone)) continue
            scope.launch {
                val zonePrices = attempt { withKeys(listed.keyIDs) { it.hourlyPrices(zone) } }.getOrNull()
                if (zonePrices == null) pricedZones.remove(zone)
                zonePrices?.forEach { (type, price) -> prices["${zone.id}/$type"] = price }
            }
        }
    }

    /** While a server is starting or stopping, keep checking until it settles. */
    private fun pollWhileBusy() {
        poll?.cancel()
        if (servers.none { it.item.state.isBusy }) return
        poll = scope.launch {
            delay(4000)
            refresh()
        }
    }

    // Actions

    /**
     * Runs [call] with the first of [keyIDs] that is allowed to: a read-only
     * key next to a full one for the same resource is not a reason to fail.
     */
    suspend fun <T> withKeys(keyIDs: List<String>, call: suspend (ScalewayClient) -> T): T {
        var lastError: Exception = ApiException(0, "No key can reach this resource.")
        for (id in keyIDs) {
            val key = keys.firstOrNull { it.id == id } ?: continue
            try {
                return call(clientFor(key))
            } catch (error: ApiException) {
                lastError = error
                if (!error.isForbidden) break
            }
        }
        throw lastError
    }

    suspend fun act(listed: Listed<Server>, action: ServerAction) {
        val server = listed.item
        withKeys(listed.keyIDs) { it.act(server.zone, server.id, action) }
        if (action != ServerAction.REBOOT) {
            val busy = if (action == ServerAction.POWEROFF) ServerState.STOPPING else ServerState.STARTING
            servers = servers.map { if (it.id == server.id) it.copy(item = it.item.copy(state = busy)) else it }
        }
        refresh()
    }
}
