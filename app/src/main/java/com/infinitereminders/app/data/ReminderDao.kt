package com.infinitereminders.app.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Insert
    suspend fun insert(r: Reminder): Long

    @Insert
    suspend fun insertAll(rs: List<Reminder>): List<Long>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: Long): Reminder?

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("DELETE FROM reminders WHERE state = 'COMPLETED'")
    suspend fun clearCompleted(): Int

    // ---- Guarded, atomic state transitions. The WHERE clause IS the state machine. ----
    @Query("UPDATE reminders SET state = 'PENDING', firstDoneAt = :now WHERE id = :id AND state = 'ACTIVE'")
    suspend fun markDone(id: Long, now: Long): Int

    @Query("UPDATE reminders SET state = 'COMPLETED', completedAt = :now WHERE id = :id AND state = 'PENDING'")
    suspend fun confirm(id: Long, now: Long): Int

    @Query("UPDATE reminders SET state = 'ACTIVE', firstDoneAt = NULL WHERE id = :id AND state = 'PENDING'")
    suspend fun undo(id: Long): Int

    @Query("UPDATE reminders SET state = 'ACTIVE', firstDoneAt = NULL, completedAt = NULL WHERE id = :id AND state = 'COMPLETED'")
    suspend fun reopen(id: Long): Int

    // Edits never touch `state`, so a stale editor can't undo a completion.
    @Query(
        """UPDATE reminders SET title = :title, description = :description, dueHasTime = :dueHasTime,
           alertedForDueAt = CASE WHEN dueAt IS :dueAt THEN alertedForDueAt ELSE NULL END,
           dueAt = :dueAt, priority = :priority, category = :category, repeat = :repeat,
           colorArgb = :colorArgb, alertEnabled = :alertEnabled WHERE id = :id"""
    )
    suspend fun updateDetails(
        id: Long, title: String, description: String, dueAt: Long?, dueHasTime: Boolean,
        priority: Int, category: String, repeat: String, colorArgb: Int, alertEnabled: Boolean
    ): Int

    // ---- Notification source of truth ----
    @Query(
        """SELECT * FROM reminders WHERE state IN ('ACTIVE','PENDING')
           ORDER BY CASE state WHEN 'PENDING' THEN 0 ELSE 1 END, priority DESC,
           COALESCE(dueAt, 9223372036854775807) ASC, createdAt ASC, id ASC LIMIT :limit"""
    )
    suspend fun notifiable(limit: Int): List<Reminder>

    @Query("SELECT COUNT(*) FROM reminders WHERE state IN ('ACTIVE','PENDING')")
    suspend fun countNotifiable(): Int

    @Query("SELECT COUNT(*) FROM reminders")
    suspend fun countAll(): Int

    // ---- UI queries (paged) ----
    @Query(
        """SELECT * FROM reminders WHERE state IN (:states)
           AND (:q = '' OR title LIKE '%' || :q || '%' OR description LIKE '%' || :q || '%' OR category LIKE '%' || :q || '%')
           AND (:cat = '' OR category = :cat)
           ORDER BY
             CASE WHEN :sort = 0 THEN createdAt END DESC,
             CASE WHEN :sort = 1 THEN COALESCE(dueAt, 9223372036854775807) END ASC,
             CASE WHEN :sort = 2 THEN priority END DESC,
             CASE WHEN :sort = 3 THEN completedAt END DESC,
             createdAt DESC, id DESC"""
    )
    fun search(states: List<String>, q: String, cat: String, sort: Int): PagingSource<Int, Reminder>

    /** Non-paged variant used by tests and diagnostics. */
    @Query(
        """SELECT * FROM reminders WHERE state IN (:states)
           AND (:q = '' OR title LIKE '%' || :q || '%' OR description LIKE '%' || :q || '%' OR category LIKE '%' || :q || '%')
           AND (:cat = '' OR category = :cat) ORDER BY createdAt DESC, id DESC LIMIT :limit"""
    )
    suspend fun searchList(states: List<String>, q: String, cat: String, limit: Int): List<Reminder>

    @Query("SELECT DISTINCT category FROM reminders WHERE category != '' ORDER BY category COLLATE NOCASE")
    fun categories(): Flow<List<String>>

    @Query("SELECT state, COUNT(*) AS n FROM reminders GROUP BY state")
    fun counts(): Flow<List<StateCount>>

    @Query("SELECT COUNT(*) FROM reminders WHERE state = 'COMPLETED' AND completedAt >= :since")
    fun completedSince(since: Long): Flow<Int>

    // ---- Scheduled alerts ----
    @Query(
        """SELECT * FROM reminders WHERE alertEnabled = 1 AND state = 'ACTIVE' AND dueAt IS NOT NULL
           AND dueAt <= :now AND (alertedForDueAt IS NULL OR alertedForDueAt != dueAt)
           ORDER BY dueAt LIMIT :limit"""
    )
    suspend fun dueAlerts(now: Long, limit: Int): List<Reminder>

    @Query(
        """SELECT MIN(dueAt) FROM reminders WHERE alertEnabled = 1 AND state = 'ACTIVE' AND dueAt IS NOT NULL
           AND (alertedForDueAt IS NULL OR alertedForDueAt != dueAt)"""
    )
    suspend fun nextAlertCandidate(): Long?

    @Query("UPDATE reminders SET alertedForDueAt = dueAt WHERE id IN (:ids)")
    suspend fun markAlerted(ids: List<Long>): Int

    // ---- Backup ----
    @Query("SELECT * FROM reminders WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun chunkAfter(afterId: Long, limit: Int): List<Reminder>

    @Query("SELECT COUNT(*) FROM reminders WHERE title = :title AND createdAt = :createdAt")
    suspend fun countSame(title: String, createdAt: Long): Int
}
