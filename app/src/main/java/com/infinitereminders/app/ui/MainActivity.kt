package com.infinitereminders.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.infinitereminders.app.graph
import com.infinitereminders.app.notify.Notif
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val openId = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            val prefs by vm.prefs.collectAsStateWithLifecycle()
            IRTheme(prefs.theme) {
                Surface(Modifier.fillMaxSize()) {
                    var screen by rememberSaveable { mutableStateOf("home") }
                    var editId by rememberSaveable { mutableStateOf<Long?>(null) }
                    val requested = openId.value
                    LaunchedEffect(requested) {
                        if (requested != null) {
                            editId = requested
                            screen = "edit"
                            openId.value = null
                        }
                    }
                    BackHandler(enabled = screen != "home") { screen = "home" }

                    if (!prefs.onboardingDone) {
                        OnboardingScreen(vm)
                    } else when (screen) {
                        "edit" -> key(editId) { EditorScreen(vm, editId) { screen = "home" } }
                        "settings" -> SettingsScreen(vm) { screen = "home" }
                        else -> HomeScreen(
                            vm,
                            onAdd = { editId = null; screen = "edit" },
                            onEdit = { editId = it; screen = "edit" },
                            onSettings = { screen = "settings" }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.action == Notif.ACTION_OPEN) {
            val id = i.getLongExtra(Notif.EXTRA_ID, -1L)
            if (id > 0) openId.value = id
        }
    }

    override fun onResume() {
        super.onResume()
        // Reconcile shade <-> database whenever the app is opened (cheap: unchanged items are skipped).
        lifecycleScope.launch(Dispatchers.IO) { applicationContext.graph.recover() }
    }
}
