package com.infinitereminders.app.data

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter

/** Streaming JSON backup: never holds the whole table in memory. Import never overwrites. */
object Backup {
    suspend fun export(dao: ReminderDao, out: OutputStream): Int = withContext(Dispatchers.IO) {
        val w = JsonWriter(OutputStreamWriter(out, Charsets.UTF_8))
        w.beginObject()
        w.name("app").value("InfiniteReminders")
        w.name("version").value(1L)
        w.name("reminders").beginArray()
        var after = 0L
        var n = 0
        while (true) {
            val chunk = dao.chunkAfter(after, 500)
            if (chunk.isEmpty()) break
            for (r in chunk) { write(w, r); n++ }
            after = chunk.last().id
        }
        w.endArray()
        w.endObject()
        w.flush()
        n
    }

    private fun write(w: JsonWriter, r: Reminder) {
        w.beginObject()
        w.name("title").value(r.title)
        w.name("description").value(r.description)
        w.name("createdAt").value(r.createdAt)
        if (r.dueAt != null) w.name("dueAt").value(r.dueAt)
        w.name("dueHasTime").value(r.dueHasTime)
        w.name("priority").value(r.priority.toLong())
        w.name("category").value(r.category)
        w.name("state").value(r.state)
        if (r.firstDoneAt != null) w.name("firstDoneAt").value(r.firstDoneAt)
        if (r.completedAt != null) w.name("completedAt").value(r.completedAt)
        w.name("repeat").value(r.repeat)
        w.name("colorArgb").value(r.colorArgb.toLong())
        w.name("alertEnabled").value(r.alertEnabled)
        w.endObject()
    }

    /** Returns the number of reminders actually added (identical title+createdAt are skipped). */
    suspend fun import(db: AppDatabase, input: InputStream): Int = withContext(Dispatchers.IO) {
        val dao = db.dao()
        val rd = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        val batch = ArrayList<Reminder>()
        var added = 0

        suspend fun flush() {
            if (batch.isEmpty()) return
            db.withTransaction {
                for (r in batch) {
                    if (dao.countSame(r.title, r.createdAt) == 0) {
                        dao.insert(r)
                        added++
                    }
                }
            }
            batch.clear()
        }

        rd.beginObject()
        while (rd.hasNext()) {
            if (rd.nextName() == "reminders") {
                rd.beginArray()
                while (rd.hasNext()) {
                    batch.add(readOne(rd))
                    if (batch.size >= 200) flush()
                }
                rd.endArray()
            } else rd.skipValue()
        }
        rd.endObject()
        flush()
        added
    }

    private fun readOne(rd: JsonReader): Reminder {
        var r = Reminder(title = "(untitled)", createdAt = System.currentTimeMillis())
        rd.beginObject()
        while (rd.hasNext()) {
            val name = rd.nextName()
            if (rd.peek() == JsonToken.NULL) { rd.skipValue(); continue }
            r = when (name) {
                "title" -> r.copy(title = rd.nextString().ifBlank { "(untitled)" })
                "description" -> r.copy(description = rd.nextString())
                "createdAt" -> r.copy(createdAt = rd.nextLong())
                "dueAt" -> r.copy(dueAt = rd.nextLong())
                "dueHasTime" -> r.copy(dueHasTime = rd.nextBoolean())
                "priority" -> r.copy(priority = rd.nextInt().coerceIn(0, 3))
                "category" -> r.copy(category = rd.nextString())
                "state" -> rd.nextString().let { s ->
                    r.copy(state = if (s == State.ACTIVE || s == State.PENDING || s == State.COMPLETED) s else State.ACTIVE)
                }
                "firstDoneAt" -> r.copy(firstDoneAt = rd.nextLong())
                "completedAt" -> r.copy(completedAt = rd.nextLong())
                "repeat" -> rd.nextString().let { s -> r.copy(repeat = if (s in Repeat.all) s else Repeat.NONE) }
                "colorArgb" -> r.copy(colorArgb = rd.nextInt())
                "alertEnabled" -> r.copy(alertEnabled = rd.nextBoolean())
                else -> { rd.skipValue(); r }
            }
        }
        rd.endObject()
        return r
    }
}
