package com.pvd.scwcontrolroom.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pvd.scwcontrolroom.kit.ApiKey
import com.pvd.scwcontrolroom.kit.MonthlyCosts
import com.pvd.scwcontrolroom.kit.attempt
import com.pvd.scwcontrolroom.kit.clientFor
import com.pvd.scwcontrolroom.kit.formatMoney
import com.pvd.scwcontrolroom.kit.formatPeriod
import com.pvd.scwcontrolroom.kit.recentPeriods
import com.pvd.scwcontrolroom.kit.sum
import com.pvd.scwcontrolroom.kit.total
import com.pvd.scwcontrolroom.kit.uniqueOrganizations
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private data class CostResult(val key: ApiKey, val costs: MonthlyCosts?, val error: String?)

@Composable
fun CostsScreen() {
    val model = LocalModel.current
    val pro = LocalPro.current
    var period by rememberSaveable { mutableStateOf(recentPeriods(Instant.now(), 1)[0]) }
    var results by remember { mutableStateOf(emptyList<CostResult>()) }
    var picking by remember { mutableStateOf(false) }
    var showPro by remember { mutableStateOf(false) }
    val isPro = pro.isPro(model.keys)

    /** Billing is per organization: one request per organization, not per key. */
    suspend fun load() {
        results = coroutineScope {
            uniqueOrganizations(model.keys).map { key ->
                async {
                    val result = attempt { clientFor(key).costs(period) }
                    CostResult(key, result.getOrNull(), result.exceptionOrNull()?.let { it.message ?: it.toString() })
                }
            }.awaitAll()
        }.sortedBy { it.key.label }
    }

    LaunchedEffect(period, model.keys.map { it.id }) { load() }

    Screen("Costs") {
        RefreshList(onRefresh = { load() }) {
            if (model.keys.isEmpty()) item { Footnote("Add an API key to see your costs.") }
            if (isPro) {
                item {
                    TextButton(onClick = { picking = true }, Modifier.padding(horizontal = 8.dp)) {
                        Text(formatPeriod(period))
                        Icon(Icons.Filled.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                        recentPeriods(Instant.now(), 6).forEach {
                            DropdownMenuItem(text = { Text(formatPeriod(it)) }, onClick = { period = it; picking = false })
                        }
                    }
                }
            }
            val loaded = results.mapNotNull { it.costs }
            if (loaded.isNotEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("${formatPeriod(period)}, excluding VAT", style = MaterialTheme.typography.bodyMedium,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatMoney(loaded.sumOf(::total), loaded[0].currency), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            for (result in results) {
                result.error?.let { item { ProblemBanner("${result.key.label}: $it") } }
                if (result.costs != null && isPro) breakdown(result.costs, if (results.size > 1) result.key.label else null)
            }
            if (loaded.isNotEmpty() && !isPro) {
                item {
                    TextButton(onClick = { showPro = true }, Modifier.padding(horizontal = 8.dp)) { Text("See the details with Pro") }
                    Footnote("Costs by category and by project, and the months before, are part of Pro.")
                }
            }
            if (loaded.isNotEmpty()) {
                item { Footnote("Scaleway updates these figures a few times a day. Your invoice is the final word.") }
            }
        }
    }
    if (showPro) ProSheet { showPro = false }
}

private fun LazyListScope.breakdown(costs: MonthlyCosts, title: String?) {
    val categories = sum(costs.items) { it.category } +
        if (costs.discount != 0.0) listOf("Discounts" to -costs.discount) else emptyList()
    val projects = sum(costs.items) { it.project }
    item { SectionHeader(title?.let { "$it · By category" } ?: "By category") }
    categories.forEach { (name, amount) -> item { LabeledRow(name, formatMoney(amount, costs.currency)) } }
    item { SectionHeader(title?.let { "$it · By project" } ?: "By project") }
    projects.forEach { (name, amount) -> item { LabeledRow(name, formatMoney(amount, costs.currency)) } }
}
