package com.infinitereminders.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object State {
    const val ACTIVE = "ACTIVE"
    const val PENDING = "PENDING"
    const val COMPLETED = "COMPLETED"
}

object Repeat {
    const val NONE = "NONE"
    const val DAILY = "DAILY"
    const val WEEKLY = "WEEKLY"
    const val MONTHLY = "MONTHLY"
    val all = listOf(NONE, DAILY, WEEKLY, MONTHLY)
}

val PRIORITY_LABELS = listOf("None", "Low", "Medium", "High")

/** AUTOINCREMENT primary key: ids are never reused, so notification ids never collide. */
@Entity(
    tableName = "reminders",
    indices = [Index("state"), Index("createdAt"), Index("dueAt"), Index("category")]
)
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    val createdAt: Long,
    val dueAt: Long? = null,
    val dueHasTime: Boolean = true,
    val priority: Int = 0,
    val category: String = "",
    val state: String = State.ACTIVE,
    val firstDoneAt: Long? = null,
    val completedAt: Long? = null,
    val repeat: String = Repeat.NONE,
    val colorArgb: Int = 0,
    val alertEnabled: Boolean = false,
    val alertedForDueAt: Long? = null
) {
    val nid: Int get() = id.toInt()
}

data class Draft(
    val title: String,
    val description: String = "",
    val dueAt: Long? = null,
    val dueHasTime: Boolean = true,
    val priority: Int = 0,
    val category: String = "",
    val repeat: String = Repeat.NONE,
    val colorArgb: Int = 0,
    val alertEnabled: Boolean = false
) {
    /** Repeat and alert make no sense without a due date, so they are normalised away. */
    fun normalized(): Draft = copy(
        title = title.trim(),
        description = description.trim(),
        category = category.trim(),
        priority = priority.coerceIn(0, 3),
        repeat = if (dueAt == null) Repeat.NONE else repeat,
        alertEnabled = dueAt != null && alertEnabled
    )

    fun toReminder(now: Long): Reminder {
        val d = normalized()
        return Reminder(
            title = d.title, description = d.description, createdAt = now, dueAt = d.dueAt,
            dueHasTime = d.dueHasTime, priority = d.priority, category = d.category,
            repeat = d.repeat, colorArgb = d.colorArgb, alertEnabled = d.alertEnabled
        )
    }
}

data class StateCount(val state: String, val n: Int)

object Fmt {
    private val dt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    private val d = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

    fun due(ms: Long, hasTime: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        val z = Instant.ofEpochMilli(ms).atZone(zone)
        return if (hasTime) z.format(dt) else z.format(d)
    }

    fun stamp(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone).format(dt)
}

object Recurrence {
    /** First occurrence strictly after [now], stepping from the original due date (month-end safe). */
    fun nextDue(dueAt: Long?, repeat: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val base = Instant.ofEpochMilli(dueAt ?: now).atZone(zone)
        fun at(k: Long): ZonedDateTime = when (repeat) {
            Repeat.DAILY -> base.plusDays(k)
            Repeat.WEEKLY -> base.plusWeeks(k)
            Repeat.MONTHLY -> base.plusMonths(k)
            else -> throw IllegalArgumentException("not repeating: $repeat")
        }
        var k = 1L
        var next = at(k)
        while (next.toInstant().toEpochMilli() <= now && k < 200_000) {
            k++
            next = at(k)
        }
        return next.toInstant().toEpochMilli()
    }

    fun next(r: Reminder, now: Long, zone: ZoneId = ZoneId.systemDefault()): Reminder = r.copy(
        id = 0, createdAt = now, state = State.ACTIVE, firstDoneAt = null, completedAt = null,
        dueAt = nextDue(r.dueAt, r.repeat, now, zone), alertedForDueAt = null
    )
}
