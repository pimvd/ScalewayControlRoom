package com.pvd.scwcontrolroom.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pvd.scwcontrolroom.kit.Commands
import com.pvd.scwcontrolroom.kit.InstanceQuiet
import com.pvd.scwcontrolroom.kit.Server
import com.pvd.scwcontrolroom.kit.instanceQuiet
import com.pvd.scwcontrolroom.model.Links

/**
 * Shown when an instance has no logs or metrics in Cockpit in the period:
 * why, from its state, and how to start sending both with one agent.
 * [what] is "logs" or "metrics", for the text.
 */
@Composable
fun InstanceCockpitSetup(server: Server, what: String) {
    Column(Modifier.padding(vertical = 8.dp)) {
        when (instanceQuiet(server)) {
            InstanceQuiet.STOPPED -> {
                Explanation("This instance is stopped",
                            "A stopped instance sends no $what. If its agent is set up, what it sent while it ran shows up in a longer period, such as 24 hours.")
                Expandable("Set up the agent") { Steps(server) }
            }
            InstanceQuiet.STARTING -> {
                Explanation("This instance is starting",
                            "Its agent starts with it: $what show up a minute or two after it runs. Pull to refresh.")
                Expandable("Set up the agent") { Steps(server) }
            }
            InstanceQuiet.NEW -> {
                Explanation("This instance is new",
                            "A new instance sends no $what until an agent on it pushes them. Set it up with the commands below; the first data arrives a minute or two later. Data you push yourself is billed by Scaleway as Cockpit custom data.")
                Steps(server)
            }
            InstanceQuiet.RUNNING -> {
                Explanation("This instance sends no $what",
                            "Unlike serverless apps, instances do not send their logs and metrics to Cockpit by themselves: an agent on the instance has to push them. Set it up with the commands below and both show up here. Data you push yourself is billed by Scaleway as Cockpit custom data.")
                Footnote("Just set up or restarted? The first data takes a minute or two. Pull to refresh.")
                Steps(server)
            }
        }
    }
}

@Composable
private fun Explanation(heading: String, text: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(heading, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Steps(server: Server) {
    val context = LocalContext.current
    CommandBlock("1. On your computer, with the scw CLI", Commands.cockpitSources(server))
    Footnote("Keep the two URLs and the secret key it prints. The secret key is shown only once.")
    CommandBlock("2. On the instance: install Grafana Alloy", Commands.alloyInstall)
    CommandBlock("3. On the instance: fill in the three values, then run", Commands.alloyConfig(server))
    TextButton(onClick = { openUrl(context, Links.SUPPORT) }, Modifier.padding(horizontal = 4.dp)) {
        Text("Open the setup guide")
    }
}
