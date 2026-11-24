package com.pvd.scwcontrolroom.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Euro
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pvd.scwcontrolroom.kit.ApiKey
import com.pvd.scwcontrolroom.kit.AppTab
import com.pvd.scwcontrolroom.kit.Destination
import com.pvd.scwcontrolroom.kit.screenshotDestination
import com.pvd.scwcontrolroom.model.AppModel
import com.pvd.scwcontrolroom.model.ProStore

private val tabs = mapOf(
    AppTab.INSTANCES to TabItem("Instances", Icons.Filled.Dns),
    AppTab.SERVERLESS to TabItem("Serverless", Icons.Filled.Bolt),
    AppTab.OTHER to TabItem("Other", Icons.Filled.Layers),
    AppTab.COSTS to TabItem("Costs", Icons.Filled.Euro),
    AppTab.SETTINGS to TabItem("Settings", Icons.Filled.Settings),
)

/** Five tabs, each with its own stack of screens. */
@Composable
fun RootScreen(model: AppModel, pro: ProStore, screenshotRoute: String?) {
    var tab by rememberSaveable { mutableStateOf(AppTab.INSTANCES) }
    val stacks = remember { AppTab.entries.associateWith { mutableStateListOf<Destination>() } }
    val stack = stacks.getValue(tab)

    LaunchedEffect(Unit) {
        model.load()
        // The screenshot test starts the app with a screen to open: turn the
        // demo on and open that screen.
        if (screenshotRoute == null) {
            model.refresh()
            return@LaunchedEffect
        }
        if (model.keys.any { it.isDemo }) model.refresh() else model.add(ApiKey.demo)
        val (destinationTab, path) = screenshotDestination(screenshotRoute)
        tab = destinationTab
        stacks.getValue(destinationTab).apply { clear(); addAll(path) }
    }

    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }

    CompositionLocalProvider(
        LocalModel provides model,
        LocalPro provides pro,
        LocalNavigator provides Navigator(push = { stack.add(it) }, pop = { stack.removeAt(stack.lastIndex) },
                                          canPop = stack.isNotEmpty()),
    ) {
        // Each screen's top bar keeps clear of the status bar itself.
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                NavigationBar {
                    tabs.forEach { (key, item) ->
                        NavigationBarItem(
                            selected = key == tab,
                            onClick = {
                                // Tapping the open tab again goes back to its first screen.
                                if (key == tab) stacks.getValue(key).clear()
                                tab = key
                            },
                            icon = { Icon(item.icon, null) },
                            label = { Text(item.title) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (val top = stack.lastOrNull()) {
                    null -> when (tab) {
                        AppTab.INSTANCES -> InstancesScreen()
                        AppTab.SERVERLESS -> ServerlessScreen()
                        AppTab.OTHER -> OtherScreen()
                        AppTab.COSTS -> CostsScreen()
                        AppTab.SETTINGS -> SettingsScreen()
                    }
                    is Destination.Instance -> InstanceDetailScreen(top.id)
                    is Destination.Serverless -> ServerlessDetailScreen(top.id)
                    is Destination.Resource -> ResourceDetailScreen(top.id)
                    is Destination.Logs -> LogsScreen(top.kind, top.id)
                    is Destination.Metrics -> MetricsScreen(top.kind, top.id)
                    is Destination.Metric -> MetricChartScreen(top.kind, top.id, top.name)
                }
            }
        }
    }
}
