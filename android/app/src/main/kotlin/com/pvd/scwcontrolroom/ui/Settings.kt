package com.pvd.scwcontrolroom.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pvd.scwcontrolroom.BuildConfig
import com.pvd.scwcontrolroom.kit.ApiException
import com.pvd.scwcontrolroom.kit.ApiKey
import com.pvd.scwcontrolroom.kit.Commands
import com.pvd.scwcontrolroom.kit.ProProducts
import com.pvd.scwcontrolroom.kit.ScalewayApi
import com.pvd.scwcontrolroom.kit.Zone
import com.pvd.scwcontrolroom.kit.validateKey
import com.pvd.scwcontrolroom.model.AUTH_BEFORE_ACTIONS
import com.pvd.scwcontrolroom.model.FreeProStore
import com.pvd.scwcontrolroom.model.appPrefs
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import com.pvd.scwcontrolroom.model.Links
import com.pvd.scwcontrolroom.model.ProPlan
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val model = LocalModel.current
    val pro = LocalPro.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAddKey by remember { mutableStateOf(false) }
    var showPro by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<ApiKey?>(null) }

    Screen("Settings") {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionHeader("API keys")
            model.keys.forEach { key ->
                LinkRow(key.label, trailing = if (key.isDemo) "Made-up data" else key.organizationID.take(8)) { removing = key }
            }
            LinkRow("Add API key") { showAddKey = true }
            if (model.keys.none { it.isDemo }) LinkRow("Try the demo") { scope.launch { model.add(ApiKey.demo) } }
            Footnote("Keys are stored encrypted on this phone and sent only to Scaleway. Tap a key to remove it.")

            SectionHeader("Security")
            var authBeforeActions by remember { mutableStateOf(appPrefs(context).getBoolean(AUTH_BEFORE_ACTIONS, false)) }
            Row(
                Modifier.fillMaxWidth().toggleable(authBeforeActions, role = Role.Switch) {
                    authBeforeActions = it
                    appPrefs(context).edit().putBoolean(AUTH_BEFORE_ACTIONS, it).apply()
                }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Confirm stop and reboot with your fingerprint, face or screen lock",
                     style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 16.dp))
                Switch(authBeforeActions, null)
            }

            SectionHeader("Pro")
            if (pro == FreeProStore) {
                Footnote("Every feature is free in this version of the app.")
            } else if (pro.subscribed) {
                LabeledRow("Status", if (pro.isTestStore) "Active (test store)" else "Active")
                LinkRow(if (pro.isTestStore) "Cancel the test subscription" else "Manage subscription") { pro.manage(context) }
            } else {
                LinkRow("Get Pro") { showPro = true }
                LinkRow("Restore purchases") { scope.launch { pro.restore() } }
            }
            if (!pro.subscribed && pro.isPro(model.keys)) Footnote("Every Pro feature is open while the demo is your only key.")

            SectionHeader("Help")
            LinkRow("Setup guide") { openUrl(context, Links.SUPPORT) }
            LinkRow("Privacy policy") { openUrl(context, Links.PRIVACY) }

            SectionHeader("About")
            LabeledRow("Version", BuildConfig.VERSION_NAME)
            Footnote("ControlRoom is an independent app. It is not made, endorsed or supported by Scaleway.")
        }
    }

    if (showAddKey) AddKeySheet { showAddKey = false }
    if (showPro) ProSheet { showPro = false }
    removing?.let { key ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${key.label}?") },
            text = { Text("The key is deleted from this phone. It stays valid at Scaleway.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch { model.remove(key) }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddKeySheet(onDismiss: () -> Unit) {
    val model = LocalModel.current
    val pro = LocalPro.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var organizationID by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val locked = !pro.isPro(model.keys) && model.keys.count { !it.isDemo } >= ProProducts.FREE_KEYS

    suspend fun save() {
        val key = ApiKey(label = label.trim(), secretKey = secretKey.trim(), organizationID = organizationID.trim())
        validateKey(key.label, key.secretKey, key.organizationID)?.let {
            problem = it
            return
        }
        saving = true
        try {
            // One cheap call tells a mistyped secret from a working one. A key
            // that may not list instances (403) is still useful for costs.
            try {
                ScalewayApi(key).listServers(Zone.FR_PAR_1)
            } catch (error: ApiException) {
                if (error.status == 401) {
                    problem = "Scaleway does not recognise this secret key."
                    return
                }
                if (error.status != 403) {
                    problem = "Could not reach Scaleway: ${error.message}"
                    return
                }
            } catch (error: java.io.IOException) {
                problem = "Could not reach Scaleway: ${error.message}"
                return
            }
            try {
                model.add(key)
                onDismiss()
            } catch (error: Exception) {
                problem = "Could not save the key: ${error.message}"
            }
        } finally {
            saving = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Text("Add API key", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                 textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (locked) Spacer(Modifier.width(72.dp))
            else if (saving) CircularProgressIndicator(Modifier.padding(horizontal = 24.dp).size(24.dp), strokeWidth = 2.dp)
            else TextButton(onClick = { scope.launch { save() } }) { Text("Save") }
        }
        if (locked) {
            Column(Modifier.height(360.dp)) { ProGate("More than one API key is part of Pro.") }
            return@ModalBottomSheet
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Footnote("Give the app its own IAM application and API key. With the scw CLI, run these commands on your computer: they print the secret key and the organization ID to paste below. Remove any permission set you do not want the app to have; the app hides what its key may not do.")
            CommandBlock("On your computer, with the scw CLI", Commands.keySetup)
            TextButton(onClick = { openUrl(context, Links.SUPPORT) }, Modifier.padding(horizontal = 4.dp)) {
                Text("Setup guide (also without the CLI)")
            }
            SectionHeader("Key")
            val noCaps = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
            val field = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            OutlinedTextField(label, { label = it }, field, label = { Text("Name, like Work") }, singleLine = true)
            OutlinedTextField(secretKey, { secretKey = it }, field, label = { Text("Secret key") }, singleLine = true,
                              visualTransformation = PasswordVisualTransformation(), keyboardOptions = noCaps)
            OutlinedTextField(organizationID, { organizationID = it }, field, label = { Text("Organization ID") },
                              singleLine = true, keyboardOptions = noCaps)
            problem?.let { ProblemBanner(it) }
        }
    }
}

private val features = listOf(
    "Serverless" to "Your containers, functions and jobs in every region.",
    "Everything else" to "Databases, volumes, IPs, domains and more, grouped and searchable.",
    "Logs and metrics" to "From Cockpit, for serverless apps and instances: from the last 15 minutes up to 30 days.",
    "Cost details" to "Costs by category and by project, and the months before.",
    "More keys" to "Every organization and project you work on, side by side.",
)

/** The purchase screen: what Pro adds, the plans, and the terms. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProSheet(onDismiss: () -> Unit) {
    val pro = LocalPro.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf<ProPlan?>(null) }
    LaunchedEffect(Unit) { pro.loadPlans() }
    val plan = chosen ?: pro.plans.firstOrNull()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        if (pro.subscribed) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                   verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Filled.Verified, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                Text("You have Pro. Thank you!", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text("Close") }
            }
            return@ModalBottomSheet
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
               verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ControlRoom Pro", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Free: your instances, starting and stopping them, and this month's total cost. Pro adds:",
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            features.forEach { (title, text) ->
                Row {
                    Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            pro.plans.forEach { option ->
                Row(
                    Modifier.fillMaxWidth().selectable(option == plan) { chosen = option },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(option == plan, null)
                    Spacer(Modifier.width(8.dp))
                    Text(option.title, Modifier.weight(1f))
                    Text(option.price, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(
                onClick = { if (plan != null && activity != null) scope.launch { pro.buy(activity, plan) } },
                enabled = plan != null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Subscribe") }
            if (pro.isTestStore) {
                Text("Test store: nothing is charged. Google Play billing comes with the Play Store listing.",
                     style = MaterialTheme.typography.bodySmall, color = Warning)
            }
            Text("The subscription renews automatically until you cancel it in Google Play.",
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton(onClick = { scope.launch { pro.restore() } }) { Text("Restore purchases") }
                TextButton(onClick = { openUrl(context, Links.PRIVACY) }) { Text("Privacy policy") }
            }
        }
    }
}
