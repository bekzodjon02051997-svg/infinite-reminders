package com.infinitereminders.app.data

import androidx.room.withTransaction
import com.infinitereminders.app.notify.AlarmScheduler
import com.infinitereminders.app.notify.NotificationSynchronizer
import java.io.InputStream
import java.io.OutputStream

/**
 * All mutations go through here: write DB (guarded/transactional) -> then sync notifications.
 * A notification is only removed AFTER the new state is committed.
 */
class ReminderRepository(
    private val db: AppDatabase,
    private val sync: NotificationSynchronizer,
    private val alarms: AlarmScheduler,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private val dao = db.dao()

    private suspend fun afterChange() {
        sync.syncAll()          // notification first: it must appear immediately
        alarms.rescheduleNext()
    }

    suspend fun create(d: Draft): Long {
        require(d.title.isNotBlank()) { "Title is required" }
        val id = dao.insert(d.toReminder(now()))
        afterChange()
        return id
    }

    suspend fun createBulk(titles: List<String>, template: Draft? = null): List<Long> {
        val t0 = now()
        val items = titles.map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { i, title ->
            (template ?: Draft(title)).copy(title = title).toReminder(t0 + i)
        }
        val ids = ArrayList<Long>(items.size)
        for (chunk in items.chunked(500)) ids += db.withTransaction { dao.insertAll(chunk) }
        afterChange()
        return ids
    }

    /** STEP 1. Only ACTIVE -> PENDING. Never completes, never removes the notification. */
    suspend fun markDone(id: Long): Boolean {
        val ok = dao.markDone(id, now()) == 1
        if (ok) afterChange() else sync.syncAll()
        return ok
    }

    /** STEP 2. Only PENDING -> COMPLETED, atomically with the next occurrence of a repeating task. */
    suspend fun confirm(id: Long): Boolean {
        val ok = db.withTransaction {
            val r = dao.get(id) ?: return@withTransaction false
            if (dao.confirm(id, now()) != 1) return@withTransaction false
            if (r.repeat != Repeat.NONE) dao.insert(Recurrence.next(r, now()))
            true
        }
        if (ok) afterChange() else sync.syncAll()
        return ok
    }

    suspend fun undo(id: Long): Boolean {
        val ok = dao.undo(id) == 1
        if (ok) afterChange() else sync.syncAll()
        return ok
    }

    suspend fun reopen(id: Long): Boolean {
        val ok = dao.reopen(id) == 1
        if (ok) afterChange()
        return ok
    }

    suspend fun update(id: Long, d: Draft): Boolean {
        require(d.title.isNotBlank()) { "Title is required" }
        val n = d.normalized()
        val ok = dao.updateDetails(
            id, n.title, n.description, n.dueAt, n.dueHasTime, n.priority, n.category,
            n.repeat, n.colorArgb, n.alertEnabled
        ) == 1
        if (ok) afterChange()
        return ok
    }

    suspend fun duplicate(id: Long): Long? {
        val r = dao.get(id) ?: return null
        val copy = r.copy(
            id = 0, title = r.title + " (copy)", createdAt = now(), state = State.ACTIVE,
            firstDoneAt = null, completedAt = null, alertedForDueAt = null
        )
        val newId = dao.insert(copy)
        afterChange()
        return newId
    }

    suspend fun delete(id: Long): Boolean {
        val ok = dao.delete(id) == 1
        afterChange()
        return ok
    }

    suspend fun clearCompleted(): Int = dao.clearCompleted()

    suspend fun load(id: Long): Reminder? = dao.get(id)

    suspend fun export(out: OutputStream): Int = Backup.export(dao, out)

    suspend fun import(input: InputStream): Int {
        val n = Backup.import(db, input)
        afterChange()
        return n
    }
}
