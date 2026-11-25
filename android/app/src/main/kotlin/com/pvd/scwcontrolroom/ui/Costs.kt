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
import kotlinx.coroutines.launch
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.pvd.scwcontrolroom.kit.ApiException
import com.pvd.scwcontrolroom.kit.Invoice
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class CostResult(
    val key: ApiKey,
    val costs: MonthlyCosts?,
    val error: String?,
    /** Loaded with Pro only. */
    val invoices: List<Invoice> = emptyList(),
    val invoicesError: String? = null,
)

private val issuedFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK).withZone(ZoneId.systemDefault())

@Composable
fun CostsScreen() {
    val model = LocalModel.current
    val pro = LocalPro.current
    var period by rememberSaveable { mutableStateOf(recentPeriods(Instant.now(), 1)[0]) }
    var results by remember { mutableStateOf(emptyList<CostResult>()) }
    var picking by remember { mutableStateOf(false) }
    var showPro by remember { mutableStateOf(false) }
    var openingInvoice by remember { mutableStateOf<String?>(null) }
    var invoiceProblem by remember { mutableStateOf<String?>(null) }
    val isPro = pro.isPro(model.keys)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /** Billing is per organization: one request per organization, not per key. */
    suspend fun load() {
        results = coroutineScope {
            uniqueOrganizations(model.keys).map { key ->
                async {
                    val api = clientFor(key)
                    val costs = attempt { api.costs(period) }
                    val invoices = if (isPro) attempt { api.invoices() } else Result.success(emptyList())
                    CostResult(
                        key, costs.getOrNull(), costs.exceptionOrNull()?.let { it.message ?: it.toString() },
                        invoices.getOrNull().orEmpty(),
                        invoices.exceptionOrNull()?.let {
                            if ((it as? ApiException)?.isForbidden == true) "This key may not read invoices. Add BillingReadOnly to its policy."
                            else it.message ?: it.toString()
                        },
                    )
                }
            }.awaitAll()
        }.sortedBy { it.key.label }
    }

    /** Downloads the PDF into the app's cache and opens it in a PDF app. */
    fun open(invoice: Invoice, key: ApiKey) {
        openingInvoice = invoice.id
        invoiceProblem = null
        scope.launch {
            try {
                val file = clientFor(key).invoicePdf(invoice.id)
                val folder = File(context.cacheDir, "invoices").apply { mkdirs() }
                val pdf = File(folder, file.name.replace("/", "-")).apply { writeBytes(file.bytes) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", pdf)
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                                          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (error: ActivityNotFoundException) {
                invoiceProblem = "No app on this phone opens PDF files."
            } catch (error: Exception) {
                invoiceProblem = "Could not open the invoice: ${error.message}"
            } finally {
                openingInvoice = null
            }
        }
    }

    LaunchedEffect(period, model.keys.map { it.id }) { load() }

    Screen("Costs") {
        RefreshList(onRefresh = { load() }) {
            if (model.keys.isEmpty()) item { Footnote("Add an API key to see your costs.") }
            invoiceProblem?.let { item { ProblemBanner(it) } }
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
                if (isPro && (result.invoices.isNotEmpty() || result.invoicesError != null)) {
                    item { SectionHeader(if (results.size > 1) "${result.key.label} · Invoices" else "Invoices") }
                    result.invoicesError?.let { item { ProblemBanner(it) } }
                    result.invoices.forEach { invoice ->
                        item(key = "invoice-${invoice.id}") {
                            InvoiceRow(invoice, opening = openingInvoice == invoice.id, enabled = openingInvoice == null) {
                                open(invoice, result.key)
                            }
                        }
                    }
                }
            }
            if (loaded.isNotEmpty() && !isPro) {
                item {
                    TextButton(onClick = { showPro = true }, Modifier.padding(horizontal = 8.dp)) { Text("See the details with Pro") }
                    Footnote("Costs by category and by project, the months before, and your invoices are part of Pro.")
                }
            }
            if (loaded.isNotEmpty()) {
                item { Footnote("Scaleway updates these figures a few times a day. Your invoice is the final word.") }
            }
        }
    }
    if (showPro) ProSheet { showPro = false }
}

/** An invoice: its month, number, state and date, and its total; tapping opens the PDF. */
@Composable
private fun InvoiceRow(invoice: Invoice, opening: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val details = listOfNotNull(
        invoice.number.takeIf { it.isNotEmpty() }?.let { "No. $it" },
        invoice.state.takeIf { it.isNotEmpty() },
        invoice.issued?.let { "issued ${issuedFormat.format(it)}" },
    ).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(formatPeriod(invoice.period), style = MaterialTheme.typography.titleMedium)
            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (opening) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Text(formatMoney(invoice.total, invoice.currency), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
