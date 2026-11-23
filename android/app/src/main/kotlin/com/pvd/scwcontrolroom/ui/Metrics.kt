package com.pvd.scwcontrolroom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pvd.scwcontrolroom.kit.Destination
import com.pvd.scwcontrolroom.kit.LogKind
import com.pvd.scwcontrolroom.kit.MetricNames
import com.pvd.scwcontrolroom.kit.MetricPoint
import com.pvd.scwcontrolroom.kit.MetricSeries
import com.pvd.scwcontrolroom.kit.formatMetric
import com.pvd.scwcontrolroom.kit.isChartable
import com.pvd.scwcontrolroom.kit.isCounter
import com.pvd.scwcontrolroom.kit.metricTitle
import com.pvd.scwcontrolroom.model.AppModel

/**
 * The metrics Cockpit has for a resource, as a dashboard: a chart per
 * metric, a line per instance. Names are found, not assumed: whatever
 * carries the resource's label is shown.
 */
@Composable
fun MetricsScreen(kind: LogKind, id: String) {
    val model = LocalModel.current
    val pro = LocalPro.current
    val navigator = LocalNavigator.current
    val resource = model.cockpitResource(kind, id)
    var minutes by rememberSaveable { mutableStateOf(60) }
    var result by remember { mutableStateOf<MetricNames?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    // Bumped by pull to refresh, so every chart reloads.
    var reloads by remember { mutableIntStateOf(0) }

    suspend fun load() {
        val target = resource?.target ?: return
        failure = null
        try {
            result = model.withKeys(resource.keyIDs) { it.metricNames(target, minutes) }
        } catch (error: Exception) {
            failure = cockpitFailure(error)
        }
    }

    LaunchedEffect(minutes, resource != null) { load() }

    Screen("Metrics") {
        when {
            !pro.isPro(model.keys) -> ProGate("Metrics from Cockpit are part of Pro.")
            resource == null -> Unavailable("This resource is no longer listed.")
            else -> RefreshList(onRefresh = {
                reloads += 1
                load()
            }) {
                item { PeriodPicker(minutes) { minutes = it } }
                failure?.let { item { ProblemBanner(it) } }
                if (result == null && failure == null) item { Loading() }
                items(result?.names.orEmpty().filter(::isChartable), key = { it }) { name ->
                    MetricCard(kind, id, name, minutes, reloads) { navigator.push(Destination.Metric(kind, id, name)) }
                    Divider()
                }
                if (result?.names?.isEmpty() == true) {
                    item {
                        val server = resource.server
                        if (server != null) {
                            InstanceCockpitSetup(server, "metrics")
                        } else {
                            Footnote(
                                if (kind == LogKind.RESOURCE) "No metrics in this period. Scaleway sends the metrics of its managed products to Cockpit by itself, where the product has any."
                                else "No metrics in this period. Scaleway sends serverless metrics to Cockpit by itself; they appear once the app has run.",
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Loads one metric's series per instance. */
private suspend fun loadSeries(model: AppModel, kind: LogKind, id: String, name: String, minutes: Int): List<MetricSeries> {
    val resource = model.cockpitResource(kind, id) ?: return emptyList()
    return model.withKeys(resource.keyIDs) { it.metric(name, resource.target, minutes) }
}

/** The newest value of any instance. */
private fun latest(series: List<MetricSeries>): MetricPoint? = series.mapNotNull { it.points.lastOrNull() }.maxByOrNull { it.time }

/** One metric on the dashboard: title, latest value and a small chart. */
@Composable
private fun MetricCard(kind: LogKind, id: String, name: String, minutes: Int, reloads: Int, onClick: () -> Unit) {
    val model = LocalModel.current
    var series by remember { mutableStateOf<List<MetricSeries>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(minutes, reloads) {
        failure = null
        try {
            series = loadSeries(model, kind, id, name, minutes)
        } catch (error: Exception) {
            failure = cockpitFailure(error)
        }
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(metricTitle(name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            series?.let(::latest)?.let {
                Text(formatMetric(it.value, name), style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val loaded = series
        when {
            failure != null -> Footnote(failure!!, Modifier.padding(0.dp))
            loaded == null -> Loading(Modifier.height(120.dp))
            loaded.isEmpty() -> Footnote("No data in this period.")
            else -> MetricChart(loaded, name, Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp))
        }
    }
}

/** One metric as a large chart, with its latest value. */
@Composable
fun MetricChartScreen(kind: LogKind, id: String, name: String) {
    val model = LocalModel.current
    var minutes by rememberSaveable { mutableStateOf(60) }
    var series by remember { mutableStateOf<List<MetricSeries>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        failure = null
        try {
            series = loadSeries(model, kind, id, name, minutes)
        } catch (error: Exception) {
            failure = cockpitFailure(error)
        }
    }

    LaunchedEffect(minutes) { load() }

    Screen(metricTitle(name)) {
        RefreshList(onRefresh = { load() }) {
            item { PeriodPicker(minutes) { minutes = it } }
            failure?.let { item { ProblemBanner(it) } }
            item {
                val loaded = series
                when {
                    loaded == null -> if (failure == null) Loading()
                    latest(loaded) == null -> Footnote("No data in this period.")
                    else -> Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("Latest", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatMetric(latest(loaded)!!.value, name), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        MetricChart(loaded, name, Modifier.fillMaxWidth().height(240.dp).padding(vertical = 8.dp))
                    }
                }
            }
            item {
                Text(name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (isCounter(name)) item { Footnote("A counter: shown as its rate per second.") }
        }
    }
}
