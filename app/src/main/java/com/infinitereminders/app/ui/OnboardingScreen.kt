package com.infinitereminders.app.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val enabled by rememberNotificationsEnabled()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.resync() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(32.dp))
        Text("Infinite Reminders", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Every open reminder lives in your notification panel until you finish it with two taps: " +
                "DONE, then CONFIRM DONE. Nothing is ever limited by the app.",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            "Honest limit: Android itself allows an app only 50 notifications at once. This app shows up to 45 " +
                "individually, keeps every other reminder safe in its database, and fills a slot the moment you finish a task.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            if (enabled) "Notifications are enabled. You're all set." else "Notifications are currently OFF. Reminders would be saved but invisible.",
            style = MaterialTheme.typography.titleMedium
        )
        if (!enabled) {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33) launcher.launch(POST_NOTIFICATIONS) else openNotificationSettings(ctx)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Enable notifications") }
            OutlinedButton(onClick = { openNotificationSettings(ctx) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open notification settings")
            }
        }
        Button(
            onClick = { vm.updatePrefs { it.copy(onboardingDone = true) } },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (enabled) "Get started" else "Continue anyway") }
    }
}
