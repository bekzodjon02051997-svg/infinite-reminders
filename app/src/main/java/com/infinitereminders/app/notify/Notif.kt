package com.infinitereminders.app.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context

object Notif {
    // Notification identity is (package, tag, id). The tag keeps reminder ids (tag "r") from
    // ever colliding with the overflow / group-summary / alert notifications.
    const val TAG_R = "r"
    const val TAG_O = "o"
    const val TAG_G = "g"
    const val TAG_A = "a"

    const val CH_PERSIST = "persistent_v1"
    const val GROUP = "ir_reminders_group"
    const val EXTRA_SIG = "ir_sig"
    const val EXTRA_ID = "reminder_id"

    const val ACTION_DONE = "com.infinitereminders.app.DONE"
    const val ACTION_CONFIRM = "com.infinitereminders.app.CONFIRM"
    const val ACTION_UNDO = "com.infinitereminders.app.UNDO"
    const val ACTION_DISMISSED = "com.infinitereminders.app.DISMISSED"
    const val ACTION_ALERT = "com.infinitereminders.app.ALERT"
    const val ACTION_OPEN = "com.infinitereminders.app.OPEN"

    /** Android caps one app at 50 notifications. 46 reminders + overflow + group summary + alert = 49. */
    const val MAX_CAP = 46
}

data class ActiveNotif(val tag: String?, val id: Int, val sig: String?)

/** Abstraction over NotificationManager so registration failures / denied permission can be tested. */
interface NotificationPoster {
    fun areEnabled(): Boolean
    fun notify(tag: String, id: Int, n: Notification)
    fun cancel(tag: String, id: Int)
    fun active(): List<ActiveNotif>
}

class SystemPoster(ctx: Context) : NotificationPoster {
    private val nm = ctx.applicationContext.getSystemService(NotificationManager::class.java)

    override fun areEnabled(): Boolean = nm.areNotificationsEnabled()
    override fun notify(tag: String, id: Int, n: Notification) = nm.notify(tag, id, n)
    override fun cancel(tag: String, id: Int) = nm.cancel(tag, id)
    override fun active(): List<ActiveNotif> = try {
        nm.activeNotifications.map {
            ActiveNotif(it.tag, it.id, it.notification.extras?.getString(Notif.EXTRA_SIG))
        }
    } catch (e: Exception) {
        emptyList()
    }
}

data class SyncResult(
    val at: Long = 0,
    val permissionGranted: Boolean = true,
    val total: Int = 0,      // reminders that should be in the shade (ACTIVE + PENDING)
    val shown: Int = 0,      // individually shown
    val overflow: Int = 0,   // saved but not shown individually (Android cap)
    val failed: Int = 0,
    val error: String? = null
)
