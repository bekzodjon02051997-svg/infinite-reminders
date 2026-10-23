package com.infinitereminders.app.notify

import android.content.Context
import com.infinitereminders.app.data.Reminder
import com.infinitereminders.app.data.ReminderDao
import com.infinitereminders.app.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Makes the notification shade match the database. The database is the source of truth; this is
 * idempotent, so it is safe to call after every change, on boot, on app start and from WorkManager.
 * A per-notification signature avoids re-posting unchanged notifications (no duplicate work, no flicker).
 */
class NotificationSynchronizer(
    @Suppress("unused") private val ctx: Context,
    private val dao: ReminderDao,
    private val settings: SettingsStore,
    private val poster: NotificationPoster,
    private val factory: NotificationFactory
) {
    private val mutex = Mutex()
    private val _result = MutableStateFlow(SyncResult())
    val result: StateFlow<SyncResult> get() = _result

    suspend fun syncAll(): SyncResult = mutex.withLock {
        val res = try {
            doSync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult(at = System.currentTimeMillis(), error = e.message ?: e.javaClass.simpleName)
        }
        _result.value = res
        res
    }

    private suspend fun doSync(): SyncResult {
        val p = settings.value
        val now = System.currentTimeMillis()
        val total = dao.countNotifiable()

        if (!poster.areEnabled()) {
            // Reminders stay safely in the database; we just can't show them.
            return SyncResult(at = now, permissionGranted = false, total = total, shown = 0, overflow = total)
        }
        factory.ensureChannels(p)

        val cap = p.maxIndividual.coerceIn(1, Notif.MAX_CAP)
        val desired = dao.notifiable(cap)
        val active = poster.active().filter { it.tag == Notif.TAG_R }.associateBy { it.id }

        var posted = 0
        var failed = 0
        var lastError: String? = null
        for (r in desired) {
            val sig = signature(r, p)
            if (active[r.nid]?.sig == sig) continue
            try {
                poster.notify(Notif.TAG_R, r.nid, factory.build(r, p, sig))
                posted++
                if (posted > 5) delay(40) // stay under Android's notification enqueue rate limit
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                lastError = e.message ?: e.javaClass.simpleName
            }
        }

        val keep = desired.map { it.nid }.toSet()
        active.keys.filter { it !in keep }.forEach { poster.cancel(Notif.TAG_R, it) }

        val overflow = (total - desired.size).coerceAtLeast(0)
        if (overflow > 0) poster.notify(Notif.TAG_O, 0, factory.buildOverflow(overflow, p))
        else poster.cancel(Notif.TAG_O, 0)

        if (p.groupNotifications && desired.isNotEmpty()) {
            poster.notify(Notif.TAG_G, 0, factory.buildSummary(total, desired.map { it.title }))
        } else poster.cancel(Notif.TAG_G, 0)

        return SyncResult(
            at = now, permissionGranted = true, total = total,
            shown = desired.size - failed, overflow = overflow, failed = failed, error = lastError
        )
    }

    private fun signature(r: Reminder, p: com.infinitereminders.app.data.Prefs): String =
        listOf(
            r.state, r.title, r.description, r.dueAt, r.dueHasTime, r.priority, r.category,
            r.colorArgb, p.allowUndo, p.protectDismiss, p.groupNotifications
        ).joinToString("|").hashCode().toString()
}
