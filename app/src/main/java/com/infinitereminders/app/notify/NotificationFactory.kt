package com.infinitereminders.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.infinitereminders.app.R
import com.infinitereminders.app.data.Fmt
import com.infinitereminders.app.data.PRIORITY_LABELS
import com.infinitereminders.app.data.Prefs
import com.infinitereminders.app.data.Reminder
import com.infinitereminders.app.data.State
import com.infinitereminders.app.receiver.NotificationActionReceiver
import com.infinitereminders.app.ui.MainActivity

class NotificationFactory(context: Context) {
    private val ctx = context.applicationContext

    fun alertChannelId(p: Prefs) = "alert_s${if (p.sound) 1 else 0}_v${if (p.vibrate) 1 else 0}"

    fun ensureChannels(p: Prefs) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(Notif.CH_PERSIST, "Persistent reminders", NotificationManager.IMPORTANCE_LOW).apply {
                description = "One silent notification per open reminder"
                setShowBadge(true)
            }
        )
        val aid = alertChannelId(p)
        nm.createNotificationChannel(
            NotificationChannel(aid, "Scheduled alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alert when a reminder's due time arrives"
                if (!p.sound) setSound(null, null)
                enableVibration(p.vibrate)
            }
        )
        // Channel settings are immutable after creation, so sound/vibration live in the channel id.
        nm.notificationChannels.filter { it.id.startsWith("alert_") && it.id != aid }
            .forEach { nm.deleteNotificationChannel(it.id) }
    }

    fun build(r: Reminder, p: Prefs, sig: String): Notification {
        val pending = r.state == State.PENDING
        val status = if (pending) "AWAITING CONFIRMATION" else "ACTIVE"
        val meta = buildString {
            append(status)
            r.dueAt?.let { append(" · Due ").append(Fmt.due(it, r.dueHasTime)) }
            if (r.priority > 0) append(" · ").append(PRIORITY_LABELS[r.priority]).append(" priority")
            if (r.category.isNotBlank()) append(" · ").append(r.category)
        }
        val big = if (r.description.isBlank()) meta else r.description + "\n" + meta
        val b = NotificationCompat.Builder(ctx, Notif.CH_PERSIST)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(r.title)
            .setContentText(if (pending) "Awaiting confirmation" else r.description.ifBlank { meta })
            .setSubText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(r.createdAt)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(if (r.colorArgb != 0) r.colorArgb else if (pending) 0xFFF59E0B.toInt() else 0xFF2563EB.toInt())
            .setContentIntent(openIntent(r.nid))

        if (pending) {
            b.addAction(0, "CONFIRM DONE", broadcast(Notif.ACTION_CONFIRM, r.nid))
            if (p.allowUndo) b.addAction(0, "UNDO", broadcast(Notif.ACTION_UNDO, r.nid))
            b.addAction(0, "OPEN", openIntent(r.nid))
        } else {
            b.addAction(0, "DONE", broadcast(Notif.ACTION_DONE, r.nid))
            b.addAction(0, "OPEN", openIntent(r.nid))
        }
        if (p.protectDismiss) b.setDeleteIntent(broadcast(Notif.ACTION_DISMISSED, r.nid))
        if (p.groupNotifications) b.setGroup(Notif.GROUP)
        b.addExtras(Bundle().apply { putString(Notif.EXTRA_SIG, sig) })
        return b.build()
    }

    fun buildOverflow(count: Int, p: Prefs): Notification {
        val b = NotificationCompat.Builder(ctx, Notif.CH_PERSIST)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle("$count more reminders saved")
            .setContentText("Android limits an app to 50 notifications. Open the app to see them all.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$count more open reminders are safely stored but can't be shown individually because " +
                        "Android limits an app to 50 notifications. Finish a task and the next one takes its slot."
                )
            )
            .setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent(0))
        if (p.groupNotifications) b.setGroup(Notif.GROUP)
        return b.build()
    }

    fun buildSummary(total: Int, titles: List<String>): Notification {
        val inbox = NotificationCompat.InboxStyle()
        titles.take(5).forEach { inbox.addLine(it) }
        inbox.setSummaryText("$total open reminders")
        return NotificationCompat.Builder(ctx, Notif.CH_PERSIST)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle("$total open reminders")
            .setStyle(inbox)
            .setGroup(Notif.GROUP).setGroupSummary(true)
            .setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent(0))
            .build()
    }

    fun buildAlert(due: List<Reminder>, p: Prefs): Notification {
        val inbox = NotificationCompat.InboxStyle()
        due.take(6).forEach { inbox.addLine(it.title) }
        if (due.size > 6) inbox.setSummaryText("+${due.size - 6} more")
        return NotificationCompat.Builder(ctx, alertChannelId(p))
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(if (due.size == 1) due[0].title else "${due.size} reminders are due")
            .setContentText(if (due.size == 1) "Due now" else due.take(3).joinToString(", ") { it.title })
            .setStyle(inbox)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent(0))
            .build()
    }

    private fun broadcast(action: String, id: Int): PendingIntent {
        val i = Intent(ctx, NotificationActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("ir://reminder/$id/$action"))
            .putExtra(Notif.EXTRA_ID, id.toLong())
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openIntent(id: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .setAction(Notif.ACTION_OPEN)
            .setData(Uri.parse("ir://open/$id"))
            .putExtra(Notif.EXTRA_ID, id.toLong())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
