package com.pvd.scwcontrolroom.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pvd.scwcontrolroom.kit.ApiException
import com.pvd.scwcontrolroom.kit.Destination
import com.pvd.scwcontrolroom.kit.ServerState
import com.pvd.scwcontrolroom.model.AppModel
import com.pvd.scwcontrolroom.model.ProStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val LocalModel = compositionLocalOf<AppModel> { error("No AppModel") }
val LocalPro = compositionLocalOf<ProStore> { error("No ProStore") }

/** Pushes and pops the current tab's screens. */
class Navigator(val push: (Destination) -> Unit, val pop: () -> Unit, val canPop: Boolean)

val LocalNavigator = compositionLocalOf { Navigator({}, {}, false) }

/** A screen: its top bar, with a back arrow when it was pushed, and its content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val navigator = LocalNavigator.current
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (navigator.canPop) {
                    IconButton(onClick = navigator.pop) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            },
            actions = actions,
        )
        content()
    }
}

/** A list that refreshes when pulled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshList(onRefresh: suspend () -> Unit, content: LazyListScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                try {
                    onRefresh()
                } finally {
                    refreshing = false
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), content = content)
    }
}

@Composable
fun StateDot(state: ServerState) {
    val color = when (state) {
        ServerState.RUNNING -> Color(0xFF2EB872)
        ServerState.STARTING, ServerState.STOPPING -> Warning
        ServerState.LOCKED -> MaterialTheme.colorScheme.error
        else -> Color.Gray
    }
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

/** A warning about one key, shown above a list. */
@Composable
fun ProblemBanner(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Warning, null, tint = Warning, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Warning)
    }
}

/** A section's title above its rows. */
@Composable
fun SectionHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

/** Explanatory text under or between rows. */
@Composable
fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** A label with its value on the right. */
@Composable
fun LabeledRow(label: String, value: String, selectable: Boolean = false, leading: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 16.dp))
        Spacer(Modifier.weight(1f))
        leading?.let { it(); Spacer(Modifier.width(8.dp)) }
        val text = @Composable {
            Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                 textAlign = TextAlign.End)
        }
        if (selectable) SelectionContainer { text() } else text()
    }
}

/** A row that opens another screen. */
@Composable
fun LinkRow(title: String, subtitle: String? = null, trailing: String? = null, leading: @Composable (() -> Unit)? = null,
            onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let { it(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
fun Divider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

/** A choice of a few options, like the iPhone's segmented picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
            ) { Text(label(option), maxLines = 1) }
        }
    }
}

/**
 * From 15 minutes to 30 days: the period of a logs or metrics screen. What a
 * long period shows depends on how long Cockpit keeps the data.
 */
@Composable
fun PeriodPicker(minutes: Int, onSelect: (Int) -> Unit) {
    val ranges = listOf(15 to "15m", 60 to "1h", 1440 to "24h", 10080 to "7d", 43200 to "30d")
    Segmented(ranges.map { it.first }, minutes, { m -> ranges.first { it.first == m }.second }, onSelect)
}

@Composable
fun SearchField(query: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query, onValueChange = onChange, singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** Centred text in place of a screen's content. */
@Composable
fun Unavailable(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A section that opens and closes, like the iPhone's DisclosureGroup. */
@Composable
fun Expandable(title: String, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary,
                 modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.primary)
        }
        AnimatedVisibility(open) { Column(content = content) }
    }
}

/** A block of shell commands with a Copy button. */
@Composable
fun CommandBlock(title: String, command: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(title, command))
                copied = true
            }) { Text(if (copied) "Copied" else "Copy", fontWeight = FontWeight.SemiBold) }
        }
        SelectionContainer {
            Text(
                command, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            )
        }
    }
}

/** What to tell the user when Cockpit refuses or fails. */
fun cockpitFailure(error: Throwable): String =
    if ((error as? ApiException)?.isForbidden == true) {
        "This key may not read Cockpit. Give its application the ObservabilityReadOnly permission set."
    } else {
        error.message ?: error.toString()
    }

fun openUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

/** Shown in place of a Pro feature: what it is, and the way to get it. */
@Composable
fun ProGate(text: String) {
    var showPro by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
        Spacer(Modifier.size(16.dp))
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(16.dp))
        Button(onClick = { showPro = true }) { Text("Get Pro") }
    }
    if (showPro) ProSheet { showPro = false }
}

/** An icon and its label for the bottom bar. */
data class TabItem(val title: String, val icon: ImageVector)
