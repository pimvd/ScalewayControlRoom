package com.pvd.scwcontrolroom.ui

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.pvd.scwcontrolroom.kit.ApiKey
import com.pvd.scwcontrolroom.kit.Destination
import com.pvd.scwcontrolroom.kit.Listed
import com.pvd.scwcontrolroom.kit.LogKind
import com.pvd.scwcontrolroom.kit.Server
import com.pvd.scwcontrolroom.kit.ServerAction
import com.pvd.scwcontrolroom.kit.StateFilter
import com.pvd.scwcontrolroom.kit.filterServers
import com.pvd.scwcontrolroom.kit.formatMoney
import com.pvd.scwcontrolroom.kit.shouldAskForReview
import kotlinx.coroutines.launch

@Composable
fun InstancesScreen() {
    val model = LocalModel.current
    when {
        !model.loaded -> Loading()
        model.keys.isEmpty() -> WelcomeScreen()
        else -> Screen("Instances") { InstanceList() }
    }
}

@Composable
private fun InstanceList() {
    val model = LocalModel.current
    val navigator = LocalNavigator.current
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(StateFilter.ALL) }
    val visible = filterServers(model.servers, query, filter)
    RefreshList(onRefresh = { model.refresh() }) {
        item { SearchField(query, "Search name or tag") { query = it } }
        items(model.problems.filter { it.area == com.pvd.scwcontrolroom.model.KeyProblem.Area.INSTANCES }) {
            ProblemBanner("${model.label(it.keyID)}: ${it.message}")
        }
        item { Segmented(StateFilter.entries, filter, { it.label }) { filter = it } }
        items(visible, key = { it.id }) { listed ->
            InstanceRow(listed) { navigator.push(Destination.Instance(listed.id)) }
            Divider()
        }
        if (visible.isEmpty()) {
            item {
                if (model.refreshing) Loading()
                else Footnote(if (model.servers.isEmpty()) "No instances found for your keys." else "No instances match.")
            }
        }
    }
}

@Composable
private fun InstanceRow(listed: Listed<Server>, onClick: () -> Unit) {
    val model = LocalModel.current
    val server = listed.item
    val details = mutableListOf(server.state.apiValue, server.commercialType, server.zone.id)
    if (model.keys.size > 1) details += model.label(listed.keyIDs[0])
    val price = model.price(server)?.takeIf { server.state == com.pvd.scwcontrolroom.kit.ServerState.RUNNING }
    LinkRow(server.name, details.joinToString(" · "), price?.let { "${formatMoney(it)}/h" }, { StateDot(server.state) },
            onClick)
}

@Composable
fun WelcomeScreen() {
    val model = LocalModel.current
    val scope = rememberCoroutineScope()
    var showAddKey by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("ControlRoom", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            "Start and stop your Scaleway instances, read your serverless logs, and see what your organization costs this month. Your keys stay on this phone.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = { showAddKey = true }, Modifier.fillMaxWidth().height(52.dp)) { Text("Add API key") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { scope.launch { model.add(ApiKey.demo) } }, Modifier.fillMaxWidth().height(52.dp)) {
            Text("Try the demo")
        }
    }
    if (showAddKey) AddKeySheet { showAddKey = false }
}

@Composable
fun InstanceDetailScreen(id: String) {
    val model = LocalModel.current
    val listed = model.servers.firstOrNull { it.id == id }
    if (listed == null) {
        Screen("Instance") { Unavailable("This instance is no longer listed.") }
        return
    }
    val server = listed.item
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ServerAction?>(null) }
    var confirming by remember { mutableStateOf<ServerAction?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    fun run(action: ServerAction) {
        pending = action
        scope.launch {
            try {
                model.act(listed, action)
                // Asks for a rating after a few successful starts and stops
                // with a real key; never in the demo, where reviewers and
                // first-time users are.
                if (ApiKey.demo.id !in listed.keyIDs && activity != null && countSuccess(context)) {
                    runCatching {
                        val reviews = ReviewManagerFactory.create(context)
                        reviews.launchReview(activity, reviews.requestReview())
                    }
                }
            } catch (error: Exception) {
                failure = error.message ?: error.toString()
            } finally {
                pending = null
            }
        }
    }

    Screen(server.name) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LabeledRow("State", server.state.apiValue, leading = { StateDot(server.state) })
            LabeledRow("Type", server.commercialType)
            model.price(server)?.let { LabeledRow("Price", "${formatMoney(it)} per hour") }
            LabeledRow("Zone", server.zone.id)
            if (server.publicIPs.isNotEmpty()) LabeledRow("Public IP", server.publicIPs.joinToString(", "), selectable = true)
            if (server.tags.isNotEmpty()) LabeledRow("Tags", server.tags.joinToString(", "))
            LabeledRow("Project", server.project, selectable = true)
            LabeledRow("Seen by", listed.keyIDs.joinToString(", ") { model.label(it) })
            Divider()
            LinkRow("Metrics") { navigator.push(Destination.Metrics(LogKind.INSTANCE, server.id)) }
            LinkRow("Logs") { navigator.push(Destination.Logs(LogKind.INSTANCE, server.id)) }
            Divider()
            Spacer(Modifier.height(8.dp))
            if (server.state.isStopped) ActionButton("Start", pending, ServerAction.POWERON) { run(it) }
            if (server.state == com.pvd.scwcontrolroom.kit.ServerState.RUNNING) {
                ActionButton("Stop", pending, ServerAction.POWEROFF, destructive = true) { confirming = it }
                ActionButton("Reboot", pending, ServerAction.REBOOT) { confirming = it }
            }
            if (server.state.isBusy) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Waiting for Scaleway…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    confirming?.let { action ->
        val stop = action == ServerAction.POWEROFF
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(if (stop) "Stop this instance?" else "Reboot this instance?") },
            text = {
                Text(if (stop) "Compute billing stops. Volumes and flexible IPs stay, and are still billed."
                     else "It will be unreachable for a moment.")
            },
            confirmButton = {
                TextButton(onClick = { confirming = null; run(action) }) {
                    Text(if (stop) "Stop" else "Reboot", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
    failure?.let { message ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text("Scaleway refused") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun ActionButton(
    title: String,
    pending: ServerAction?,
    action: ServerAction,
    destructive: Boolean = false,
    onClick: (ServerAction) -> Unit,
) {
    OutlinedButton(
        onClick = { onClick(action) },
        enabled = pending == null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(title, color = if (destructive && pending == null) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
        if (pending == action) {
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

/** Counts a successful start or stop; true when it is time to ask for a rating. */
private fun countSuccess(context: Context): Boolean {
    val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    val count = prefs.getInt("successfulActions", 0) + 1
    prefs.edit().putInt("successfulActions", count).apply()
    return shouldAskForReview(count)
}
