package com.pvd.scwcontrolroom.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pvd.scwcontrolroom.kit.LogKind
import com.pvd.scwcontrolroom.kit.LogResult
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
/** Beyond a day, the time alone does not say which day. */
private val dayTimeFormat = DateTimeFormatter.ofPattern("d MMM HH:mm:ss", java.util.Locale.UK).withZone(ZoneId.systemDefault())

@Composable
fun LogsScreen(kind: LogKind, id: String) {
    val model = LocalModel.current
    val pro = LocalPro.current
    val resource = model.cockpitResource(kind, id)
    var minutes by rememberSaveable { mutableStateOf(60) }
    var result by remember { mutableStateOf<LogResult?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    suspend fun load() {
        val target = resource?.target ?: return
        loading = true
        failure = null
        try {
            result = model.withKeys(resource.keyIDs) { it.logs(target, minutes) }
        } catch (error: Exception) {
            failure = cockpitFailure(error)
        } finally {
            loading = false
        }
    }

    LaunchedEffect(minutes, resource != null) { load() }

    Screen("Logs") {
        when {
            !pro.isPro(model.keys) -> ProGate("Logs from Cockpit are part of Pro.")
            resource == null -> Unavailable("This resource is no longer listed.")
            else -> RefreshList(onRefresh = { load() }) {
                item { PeriodPicker(minutes) { minutes = it } }
                failure?.let { item { ProblemBanner(it) } }
                if (loading && result == null) item { Loading() }
                itemsIndexed(result?.lines.orEmpty()) { _, line ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text((if (minutes > 1440) dayTimeFormat else timeFormat).format(line.time), style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer {
                            Text(line.line, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 17.sp)
                        }
                    }
                    Divider()
                }
                if (result?.lines?.isEmpty() == true) {
                    item {
                        val server = resource.server
                        if (server != null) {
                            InstanceCockpitSetup(server, "logs")
                        } else {
                            Footnote(
                                if (kind == LogKind.RESOURCE) "No logs in this period. Scaleway sends the logs of its managed products to Cockpit by itself, where the product has any."
                                else "No logs in this period. Everything the app writes to standard output and standard error shows up here, as Scaleway sends serverless logs to Cockpit by itself.",
                            )
                        }
                    }
                }
            }
        }
    }
}
