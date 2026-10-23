package com.infinitereminders.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.infinitereminders.app.data.ReminderDao
import com.infinitereminders.app.data.SettingsStore
import com.infinitereminders.app.receiver.AlertReceiver

/**
 * Scheduled alerts. Only ONE alarm is ever registered (the earliest pending alert), because Android
 * limits an app to ~500 concurrent alarms. When it fires, all due alerts are posted and the next
 * alarm is armed. The persistent notification is independent of this and appears immediately.
 */
class AlarmScheduler(
    context: Context,
    private val dao: ReminderDao,
    private val poster: NotificationPoster,
    private val factory: NotificationFactory,
    private val settings: SettingsStore
) {
    private val ctx = context.applicationContext
    private val am = ctx.getSystemService(AlarmManager::class.java)

    fun canExact(): Boolean = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    private fun pending(): PendingIntent = PendingIntent.getBroadcast(
        ctx, 7,
        Intent(ctx, AlertReceiver::class.java).setAction(Notif.ACTION_ALERT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    suspend fun rescheduleNext() {
        val pi = pending()
        val next = dao.nextAlertCandidate()
        if (next == null) {
            am.cancel(pi)
            return
        }
        val at = maxOf(next, System.currentTimeMillis() + 1000)
        try {
            if (canExact()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Called by AlertReceiver. Returns number of alerts fired. */
    suspend fun fireDue(): Int {
        val due = dao.dueAlerts(System.currentTimeMillis(), 200)
        if (due.isNotEmpty()) {
            val p = settings.value
            if (poster.areEnabled()) {
                factory.ensureChannels(p)
                try {
                    poster.notify(Notif.TAG_A, 0, factory.buildAlert(due, p))
                } catch (e: Exception) { /* alert is best-effort; the persistent notification remains */ }
            }
            dao.markAlerted(due.map { it.id })
        }
        rescheduleNext()
        return due.size
    }
}
