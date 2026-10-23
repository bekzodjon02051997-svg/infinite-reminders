package com.infinitereminders.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Prefs(
    val allowUndo: Boolean = true,          // show UNDO in the "awaiting confirmation" notification
    val protectDismiss: Boolean = true,     // re-post a notification the user swiped away
    val groupNotifications: Boolean = false,
    val maxIndividual: Int = 45,            // individual notification slots (Android caps an app at 50)
    val sound: Boolean = false,
    val vibrate: Boolean = false,
    val theme: Int = 0,                     // 0 system, 1 light, 2 dark
    val periodicHeal: Boolean = true,
    val onboardingDone: Boolean = false
)

class SettingsStore(ctx: Context, name: String = "settings") {
    private val sp = ctx.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<Prefs> get() = _flow
    val value: Prefs get() = _flow.value

    private fun load() = Prefs(
        allowUndo = sp.getBoolean("allowUndo", true),
        protectDismiss = sp.getBoolean("protectDismiss", true),
        groupNotifications = sp.getBoolean("group", false),
        maxIndividual = sp.getInt("maxIndividual", 45),
        sound = sp.getBoolean("sound", false),
        vibrate = sp.getBoolean("vibrate", false),
        theme = sp.getInt("theme", 0),
        periodicHeal = sp.getBoolean("periodicHeal", true),
        onboardingDone = sp.getBoolean("onboardingDone", false)
    )

    @Synchronized
    fun update(f: (Prefs) -> Prefs) {
        val n = f(_flow.value)
        sp.edit()
            .putBoolean("allowUndo", n.allowUndo).putBoolean("protectDismiss", n.protectDismiss)
            .putBoolean("group", n.groupNotifications).putInt("maxIndividual", n.maxIndividual)
            .putBoolean("sound", n.sound).putBoolean("vibrate", n.vibrate).putInt("theme", n.theme)
            .putBoolean("periodicHeal", n.periodicHeal).putBoolean("onboardingDone", n.onboardingDone)
            .apply()
        _flow.value = n
    }
}
