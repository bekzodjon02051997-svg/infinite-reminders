package com.infinitereminders.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.infinitereminders.app.graph
import com.infinitereminders.app.notify.Notif
import com.infinitereminders.app.work.ReconcileWorker
import kotlinx.coroutines.launch

/** Handles DONE, CONFIRM DONE, UNDO and "notification was swiped away -> restore it". */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(Notif.EXTRA_ID, -1L)
        val action = intent.action ?: return
        val g = context.graph
        val result = goAsync()
        g.scope.launch {
            try {
                when (action) {
                    Notif.ACTION_DONE -> g.repo.markDone(id)
                    Notif.ACTION_CONFIRM -> g.repo.confirm(id)
                    Notif.ACTION_UNDO ->
                        if (g.settings.value.allowUndo) g.repo.undo(id) else g.sync.syncAll()
                    else -> g.sync.syncAll() // DISMISSED: DB is truth, so this simply re-posts it
                }
            } finally {
                result.finish()
            }
        }
    }
}

class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = context.graph
        val result = goAsync()
        g.scope.launch {
            try {
                g.alarms.fireDue()
            } finally {
                result.finish()
            }
        }
    }
}

/** Boot, app update, and exact-alarm permission changes all run the same idempotent recovery. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = context.graph
        val result = goAsync()
        g.scope.launch {
            try {
                g.recover()
                ReconcileWorker.schedule(context, g.settings.value.periodicHeal)
            } finally {
                result.finish()
            }
        }
    }
}
