package com.pvd.scwcontrolroom.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pvd.scwcontrolroom.kit.Destination
import com.pvd.scwcontrolroom.kit.Listed
import com.pvd.scwcontrolroom.kit.LogKind
import com.pvd.scwcontrolroom.kit.ServerlessApp
import com.pvd.scwcontrolroom.model.KeyProblem

@Composable
fun ServerlessScreen() {
    val model = LocalModel.current
    val pro = LocalPro.current
    val navigator = LocalNavigator.current
    var kind by rememberSaveable { mutableStateOf(ServerlessApp.Kind.CONTAINER) }
    Screen("Serverless") {
        if (!pro.isPro(model.keys)) {
            ProGate("Your serverless containers, functions and jobs, with their logs and metrics, are part of Pro.")
            return@Screen
        }
        val apps = model.serverless.filter { it.item.kind == kind }
        RefreshList(onRefresh = { model.refresh() }) {
            item { Segmented(ServerlessApp.Kind.entries, kind, { it.plural }) { kind = it } }
            items(model.problems.filter { it.area == KeyProblem.Area.SERVERLESS }) {
                ProblemBanner("${model.label(it.keyID)}: ${it.message}")
            }
            items(apps, key = { it.id }) { listed ->
                LinkRow(listed.item.name, details(listed)) { navigator.push(Destination.Serverless(listed.id)) }
                Divider()
            }
            if (apps.isEmpty()) {
                item {
                    when {
                        model.refreshing -> Loading()
                        model.keys.isEmpty() -> Footnote("Add an API key first.")
                        else -> Footnote("No serverless ${kind.plural.lowercase()} found for your keys.")
                    }
                }
            }
        }
    }
}

@Composable
private fun details(listed: Listed<ServerlessApp>): String {
    val model = LocalModel.current
    val parts = mutableListOf(listed.item.region.id, listed.item.status)
    if (listed.item.schedule.isNotEmpty()) parts += listed.item.schedule
    if (model.keys.size > 1) parts += model.label(listed.keyIDs[0])
    return parts.joinToString(" · ")
}

@Composable
fun ServerlessDetailScreen(id: String) {
    val model = LocalModel.current
    val navigator = LocalNavigator.current
    val listed = model.serverless.firstOrNull { it.id == id }
    if (listed == null) {
        Screen("Serverless") { Unavailable("This app is no longer listed.") }
        return
    }
    val app = listed.item
    val job = app.kind == ServerlessApp.Kind.JOB
    Screen(app.name) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LabeledRow("Kind", "Serverless ${app.kind.id}")
            LabeledRow(if (job) "Last run" else "Status", app.status)
            LabeledRow("Region", app.region.id)
            if (job) {
                if (app.schedule.isNotEmpty()) LabeledRow("Schedule", app.schedule)
            } else {
                LabeledRow("Scale", "${app.minScale} to ${app.maxScale}")
            }
            if (app.endpoint.isNotEmpty()) LabeledRow("Endpoint", app.endpoint, selectable = true)
            LabeledRow("Project", app.projectID, selectable = true)
            LabeledRow("Seen by", listed.keyIDs.joinToString(", ") { model.label(it) })
            Divider()
            // Cockpit keeps logs for jobs, but no metrics.
            if (!job) LinkRow("Metrics") { navigator.push(Destination.Metrics(LogKind.SERVERLESS, app.id)) }
            LinkRow("Logs") { navigator.push(Destination.Logs(LogKind.SERVERLESS, app.id)) }
        }
    }
}
