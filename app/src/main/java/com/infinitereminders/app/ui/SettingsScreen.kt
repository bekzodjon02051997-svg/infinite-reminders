package com.infinitereminders.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.infinitereminders.app.data.Fmt
import com.infinitereminders.app.notify.Notif

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val sync by vm.syncResult.collectAsStateWithLifecycle()
    val enabled by rememberNotificationsEnabled()
    var diag by remember { mutableStateOf<com.infinitereminders.app.ui.Diag?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var slider by remember(prefs.maxIndividual) { mutableStateOf(prefs.maxIndividual.toFloat()) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(refresh, sync.at) { diag = vm.diag() }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.resync() }
    val exportL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u -> if (u != null) vm.exportTo(u) }
    val importL = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.importFrom(u) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Section("Notification permission")
            Text(if (enabled) "Enabled" else "DISABLED – reminders cannot appear", style = MaterialTheme.typography.bodyLarge)
            if (!enabled) {
                OutlinedButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(POST_NOTIFICATIONS) else openNotificationSettings(ctx)
                }) { Text("Allow notifications") }
            }
            OutlinedButton(onClick = { openNotificationSettings(ctx) }) { Text("Open system notification settings") }

            HorizontalDivider()
            Section("Notification behavior")
            SwitchRow("Show UNDO on “awaiting confirmation”", "Lets you cancel an accidental DONE right from the notification.", prefs.allowUndo) { v -> vm.updatePrefs { it.copy(allowUndo = v) } }
            SwitchRow("Protect from dismissal", "If you swipe one away (Android 14 allows this), it is restored immediately.", prefs.protectDismiss) { v -> vm.updatePrefs { it.copy(protectDismiss = v) } }
            SwitchRow("Group notifications", "Off by default so every reminder stays an individual row. When on, they collapse into an expandable group.", prefs.groupNotifications) { v -> vm.updatePrefs { it.copy(groupNotifications = v) } }
            Text("Individual notification slots: ${slider.toInt()} (max 46; Android allows 50 per app)", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = slider, onValueChange = { slider = it }, valueRange = 5f..46f,
                onValueChangeFinished = { vm.updatePrefs { it.copy(maxIndividual = slider.toInt()) } }
            )

            HorizontalDivider()
            Section("Sound & vibration (scheduled alerts only)")
            SwitchRow("Sound", "Persistent notifications are always silent.", prefs.sound) { v -> vm.updatePrefs { it.copy(sound = v) } }
            SwitchRow("Vibration", null, prefs.vibrate) { v -> vm.updatePrefs { it.copy(vibrate = v) } }
            val exact = vm.canExactAlarms()
            Text(if (exact) "Exact alert timing: allowed" else "Exact alert timing: not allowed (alerts may be a few minutes late)", style = MaterialTheme.typography.bodySmall)
            if (!exact && Build.VERSION.SDK_INT >= 31) {
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Allow exact alarms") }
            }

            HorizontalDivider()
            Section("Theme")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("System", "Light", "Dark").forEachIndexed { i, l ->
                    FilterChip(selected = prefs.theme == i, onClick = { vm.updatePrefs { it.copy(theme = i) } }, label = { Text(l) })
                }
            }

            HorizontalDivider()
            Section("Battery & background")
            Text(
                "Some phones (Xiaomi, Huawei, Oppo/Realme, Vivo, Samsung “sleeping apps”) kill apps aggressively. " +
                    "Set this app to “Unrestricted” battery usage and allow auto-start so reboot recovery and the 15-minute self-heal work.",
                style = MaterialTheme.typography.bodySmall
            )
            SwitchRow("Periodic self-heal (every ~15 min)", "WorkManager re-posts anything cleared by the system or a cleaner app.", prefs.periodicHeal) { v -> vm.updatePrefs { it.copy(periodicHeal = v) } }
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Open battery optimization settings") }
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Phone-specific guide (dontkillmyapp.com)") }

            HorizontalDivider()
            Section("Recovery diagnostics")
            val d = diag
            Text(
                buildString {
                    appendLine("Reminders in database: ${d?.dbAll ?: "…"}")
                    appendLine("Open (should be in shade): ${d?.dbOpen ?: "…"}")
                    appendLine("Individual notifications posted: ${d?.posted ?: "…"}")
                    appendLine("Saved but over the cap: ${sync.overflow}")
                    appendLine("Failed registrations: ${sync.failed}")
                    if (sync.error != null) appendLine("Last error: ${sync.error}")
                    append(if (sync.at > 0) "Last sync: ${Fmt.stamp(sync.at)}" else "Last sync: not yet")
                },
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.retry(); refresh++ }) { Text("Restore / retry") }
                OutlinedButton(onClick = { refresh++ }) { Text("Refresh") }
            }

            HorizontalDivider()
            Section("Backup & restore")
            Text("JSON file you choose. Restore only adds missing reminders; it never overwrites or deletes.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportL.launch("infinite-reminders-backup.json") }) { Text("Export") }
                OutlinedButton(onClick = { importL.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Restore") }
            }
            TextButton(onClick = { confirmClear = true }) { Text("Clear completed history…") }

            HorizontalDivider()
            Section("About")
            Text(
                "Fully offline. No internet permission, no accounts, no analytics. If you Force Stop the app in Android settings, " +
                    "Android blocks all its notifications/receivers until you open it once again.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear completed history?") },
            text = { Text("Permanently deletes all COMPLETED reminders. Active and awaiting ones are untouched.") },
            confirmButton = { TextButton(onClick = { vm.clearCompleted(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Section(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
