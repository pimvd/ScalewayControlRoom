package com.pvd.scwcontrolroom.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pvd.scwcontrolroom.kit.Detail
import com.pvd.scwcontrolroom.kit.Destination
import com.pvd.scwcontrolroom.kit.LogKind
import com.pvd.scwcontrolroom.kit.ResourceGrouping
import com.pvd.scwcontrolroom.kit.ResourceRow
import com.pvd.scwcontrolroom.kit.groupResources
import com.pvd.scwcontrolroom.model.KeyProblem

/**
 * Every resource besides instances and serverless (or, on request, every
 * resource at all), searchable and grouped by type, location or project.
 */
@Composable
fun OtherScreen() {
    val model = LocalModel.current
    val pro = LocalPro.current
    var query by rememberSaveable { mutableStateOf("") }
    var grouping by rememberSaveable { mutableStateOf(ResourceGrouping.TYPE) }
    var everything by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val isPro = pro.isPro(model.keys)

    LaunchedEffect(model.keys.map { it.id }, isPro) {
        if (isPro) model.loadResourcesIfNeeded()
    }

    Screen(if (everything) "All resources" else "Other", actions = {
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.FilterList, "Show and group") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            listOf(false to "Other resources", true to "All resources").forEach { (value, title) ->
                DropdownMenuItem(text = { Text(title) }, leadingIcon = { RadioButton(everything == value, null) },
                                 onClick = { everything = value; menu = false })
            }
            HorizontalDivider()
            ResourceGrouping.entries.forEach { value ->
                DropdownMenuItem(text = { Text("Group by ${value.label.lowercase()}") },
                                 leadingIcon = { RadioButton(grouping == value, null) },
                                 onClick = { grouping = value; menu = false })
            }
        }
    }) {
        if (!isPro) {
            ProGate("Your databases, volumes, IPs, domains and every other resource, with their logs and metrics, are part of Pro.")
            return@Screen
        }
        val others = model.resources.map(ResourceRow::of)
        val rows = if (everything) model.servers.map(ResourceRow::ofServer) + model.serverless.map(ResourceRow::ofApp) + others else others
        val sections = groupResources(rows, grouping, query, model.projectNames)
        val navigator = LocalNavigator.current
        RefreshList(onRefresh = {
            if (everything) model.refresh()
            model.refreshResources()
        }) {
            item { SearchField(query, "Search name, type, location or project") { query = it } }
            items(model.problems.filter { it.area == KeyProblem.Area.OTHER }) {
                ProblemBanner("${model.label(it.keyID)}: ${it.message}")
            }
            sections.forEach { (title, sectionRows) ->
                item(key = "section-$title") { SectionHeader(title) }
                items(sectionRows, key = { "$title-${it.id}" }) { row ->
                    LinkRow(row.name, details(row, grouping)) { navigator.push(row.destination) }
                    Divider()
                }
            }
            if (sections.isEmpty()) {
                item {
                    when {
                        model.loadingResources -> Loading()
                        model.keys.isEmpty() -> Footnote("Add an API key first.")
                        rows.isEmpty() -> Footnote("No other resources found for your keys.")
                        else -> Footnote("No resources match.")
                    }
                }
            }
        }
    }
}

/** What the grouping does not already say. */
@Composable
private fun details(row: ResourceRow, grouping: ResourceGrouping): String {
    val model = LocalModel.current
    val parts = mutableListOf<String>()
    if (grouping != ResourceGrouping.TYPE) parts += row.type
    if (row.status.isNotEmpty()) parts += row.status
    if (grouping != ResourceGrouping.LOCATION) parts += row.location
    if (grouping != ResourceGrouping.PROJECT) model.projectNames[row.projectID]?.let { parts += it }
    if (model.keys.size > 1) parts += model.label(row.keyIDs[0])
    return parts.joinToString(" · ")
}

@Composable
fun ResourceDetailScreen(id: String) {
    val model = LocalModel.current
    val navigator = LocalNavigator.current
    val listed = model.resources.firstOrNull { it.id == id }
    if (listed == null) {
        Screen("Resource") {
            if (model.loadingResources) Loading() else Unavailable("This resource is no longer listed.")
        }
        return
    }
    val resource = listed.item
    Screen(resource.name) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LabeledRow("Type", resource.type)
            if (resource.status.isNotEmpty()) LabeledRow("Status", resource.status)
            LabeledRow("Location", resource.location)
            if (resource.projectID.isNotEmpty()) LabeledRow("Project", model.projectNames[resource.projectID] ?: resource.projectID)
            LabeledRow("ID", resource.id, selectable = true)
            LabeledRow("Seen by", listed.keyIDs.joinToString(", ") { model.label(it) })
            val keyFields = resource.details.filter { it.key }
            if (keyFields.isNotEmpty()) {
                SectionHeader("Details")
                keyFields.forEach { DetailRow(it) }
            }
            // Global resources have no regional Cockpit to look in.
            if (resource.cockpitTarget != null) {
                Divider()
                LinkRow("Metrics") { navigator.push(Destination.Metrics(LogKind.RESOURCE, resource.id)) }
                LinkRow("Logs") { navigator.push(Destination.Logs(LogKind.RESOURCE, resource.id)) }
            }
            // Everything Scaleway's list says about it, key fields included.
            if (resource.details.isNotEmpty()) {
                Divider()
                Expandable("All fields (${resource.details.size})") { resource.details.forEach { DetailRow(it) } }
            }
        }
    }
}

@Composable
private fun DetailRow(detail: Detail) = LabeledRow(detail.label, detail.value, selectable = true)
