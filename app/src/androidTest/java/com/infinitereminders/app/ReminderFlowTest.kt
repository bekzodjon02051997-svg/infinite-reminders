package com.infinitereminders.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.infinitereminders.app.data.AppDatabase
import com.infinitereminders.app.data.Draft
import com.infinitereminders.app.data.ReminderRepository
import com.infinitereminders.app.data.SettingsStore
import com.infinitereminders.app.data.State
import com.infinitereminders.app.notify.AlarmScheduler
import com.infinitereminders.app.notify.Notif
import com.infinitereminders.app.notify.NotificationFactory
import com.infinitereminders.app.notify.NotificationSynchronizer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Instrumented tests (need an emulator/device). They use a fake poster, so no real permission is needed. */
@RunWith(AndroidJUnit4::class)
class ReminderFlowTest {
    private lateinit var ctx: Context
    private lateinit var db: AppDatabase
    private lateinit var poster: FakePoster
    private lateinit var settings: SettingsStore
    private lateinit var sync: NotificationSynchronizer
    private lateinit var repo: ReminderRepository

    private fun wire(database: AppDatabase) {
        val factory = NotificationFactory(ctx)
        sync = NotificationSynchronizer(ctx, database.dao(), settings, poster, factory)
        repo = ReminderRepository(database, sync, AlarmScheduler(ctx, database.dao(), poster, factory, settings))
    }

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = AppDatabase.inMemory(ctx)
        poster = FakePoster()
        settings = SettingsStore(ctx, "test_" + UUID.randomUUID())
        wire(db)
    }

    @After fun tearDown() { db.close() }

    private fun state(id: Long) = runBlocking { db.dao().get(id)!!.state }

    @Test fun createOne_persistsAndShowsNotificationImmediately() = runBlocking {
        val id = repo.create(Draft("Read 10 pages", dueAt = System.currentTimeMillis() + 7 * 86_400_000L))
        assertEquals(State.ACTIVE, state(id))
        assertTrue(poster.reminderIds().contains(id.toInt()))   // due next week, notification is still immediate
        assertEquals(listOf("DONE", "OPEN"), poster.actions(id.toInt()))
    }

    @Test fun create100_allTrackedAndUnique() = runBlocking {
        val ids = (1..100).map { repo.create(Draft("r$it")) }
        assertEquals(100, ids.toSet().size)
        assertEquals(100, db.dao().countAll())
        assertEquals(45, poster.reminderIds().size)             // default cap; rest are safe in DB + overflow row
        assertTrue(poster.posts.containsKey(Notif.TAG_O to 0))
    }

    @Test fun create500_noLossNoDuplicateIds() = runBlocking {
        val ids = repo.createBulk((1..500).map { "bulk $it" })
        assertEquals(500, ids.toSet().size)
        assertEquals(500, db.dao().countAll())
        assertEquals(500, db.dao().countNotifiable())
        assertTrue(poster.reminderIds().size <= Notif.MAX_CAP)
        assertEquals(poster.reminderIds().size, poster.reminderIds().toSet().size)
    }

    @Test fun doneThenConfirm_isTwoStep() = runBlocking {
        val id = repo.create(Draft("two-step"))
        assertFalse(repo.confirm(id))                            // CONFIRM without DONE is rejected
        assertEquals(State.ACTIVE, state(id))

        assertTrue(repo.markDone(id))
        assertEquals(State.PENDING, state(id))                   // DONE does NOT complete
        assertTrue(poster.reminderIds().contains(id.toInt()))    // notification still visible
        assertEquals(listOf("CONFIRM DONE", "UNDO", "OPEN"), poster.actions(id.toInt()))
        assertTrue(db.dao().get(id)!!.firstDoneAt != null)
        assertTrue(db.dao().get(id)!!.completedAt == null)

        assertTrue(repo.confirm(id))
        assertEquals(State.COMPLETED, state(id))
        assertFalse(poster.reminderIds().contains(id.toInt()))   // removed only after saved
        assertTrue(db.dao().get(id)!!.completedAt != null)
        assertFalse(repo.confirm(id))                            // cannot complete twice
    }

    @Test fun undoReturnsToActive() = runBlocking {
        val id = repo.create(Draft("oops"))
        repo.markDone(id)
        assertTrue(repo.undo(id))
        assertEquals(State.ACTIVE, state(id))
        assertEquals(listOf("DONE", "OPEN"), poster.actions(id.toInt()))
    }

    @Test fun undoActionHiddenWhenSettingOff() = runBlocking {
        settings.update { it.copy(allowUndo = false) }
        val id = repo.create(Draft("no undo"))
        repo.markDone(id)
        assertEquals(listOf("CONFIRM DONE", "OPEN"), poster.actions(id.toInt()))
    }

    @Test fun rapidRepeatedTaps_completeExactlyOnce() = runBlocking {
        val id = repo.create(Draft("rapid"))
        val done = (1..20).map { async { repo.markDone(id) } }.awaitAll()
        assertEquals(1, done.count { it })
        val confirms = (1..20).map { async { repo.confirm(id) } }.awaitAll()
        assertEquals(1, confirms.count { it })
        assertEquals(State.COMPLETED, state(id))
        assertEquals(1, db.dao().countAll())
    }

    @Test fun pendingSurvivesRestartAndRebootRestoresConfirmAction() = runBlocking {
        val id = repo.create(Draft("survive"))
        repo.markDone(id)
        // "reboot": notifications vanish, process state is gone; new objects over the same database
        poster.posts.clear()
        wire(db)
        sync.syncAll()
        assertEquals(State.PENDING, state(id))
        assertEquals(listOf("CONFIRM DONE", "UNDO", "OPEN"), poster.actions(id.toInt()))
    }

    @Test fun activeSurvivesRestart() = runBlocking {
        val id = repo.create(Draft("active"))
        poster.posts.clear()
        wire(db)
        sync.syncAll()
        assertTrue(poster.reminderIds().contains(id.toInt()))
    }

    @Test fun deniedPermission_keepsReminderAndReportsIt() = runBlocking {
        poster.enabled = false
        val id = repo.create(Draft("no perm"))
        assertEquals(State.ACTIVE, state(id))
        assertTrue(poster.posts.isEmpty())
        assertFalse(sync.result.value.permissionGranted)
        poster.enabled = true
        sync.syncAll()                                           // retry after granting
        assertTrue(poster.reminderIds().contains(id.toInt()))
    }

    @Test fun registrationFailure_keepsReminderAndRetryWorks() = runBlocking {
        poster.failNotify = true
        val id = repo.create(Draft("fails"))
        assertEquals(State.ACTIVE, state(id))
        assertEquals(1, sync.result.value.failed)
        poster.failNotify = false
        sync.syncAll()
        assertEquals(0, sync.result.value.failed)
        assertTrue(poster.reminderIds().contains(id.toInt()))
    }

    @Test fun noDuplicateNotificationsOrReposts() = runBlocking {
        repeat(10) { repo.create(Draft("d$it")) }
        assertEquals(10, poster.reminderIds().size)
        val before = poster.notifyCalls
        repeat(5) { sync.syncAll() }
        assertEquals(before, poster.notifyCalls)                 // unchanged items are not re-posted
        assertEquals(10, poster.reminderIds().toSet().size)
    }

    @Test fun dismissedNotificationIsRestoredFromDatabase() = runBlocking {
        val id = repo.create(Draft("swipe me"))
        poster.swipeAway(id.toInt())
        assertFalse(poster.reminderIds().contains(id.toInt()))
        sync.syncAll()                                           // what the DISMISSED receiver / worker does
        assertTrue(poster.reminderIds().contains(id.toInt()))
        assertEquals(State.ACTIVE, state(id))
    }

    @Test fun finishingATaskPromotesAnOverflowedReminder() = runBlocking {
        settings.update { it.copy(maxIndividual = 5) }
        val ids = (1..8).map { repo.create(Draft("p$it")) }
        assertEquals(5, poster.reminderIds().size)
        repo.markDone(ids[0]); repo.confirm(ids[0])
        assertEquals(5, poster.reminderIds().size)               // slot refilled by a previously hidden one
        assertEquals(2, sync.result.value.overflow)
    }

    @Test fun searchAndFilterOverLargeCollection() = runBlocking {
        repo.createBulk((1..1000).map { if (it % 100 == 0) "needle $it" else "hay $it" })
        val hits = db.dao().searchList(listOf(State.ACTIVE), "needle", "", 50)
        assertEquals(10, hits.size)
        repo.markDone(hits[0].id); repo.confirm(hits[0].id)
        assertEquals(1, db.dao().searchList(listOf(State.COMPLETED), "", "", 50).size)
    }

    @Test fun repeatingTaskCreatesNextOccurrenceOnConfirm() = runBlocking {
        val due = System.currentTimeMillis() - 3_600_000
        val id = repo.create(Draft("daily", dueAt = due, repeat = "DAILY"))
        repo.markDone(id); repo.confirm(id)
        assertEquals(2, db.dao().countAll())
        assertEquals(1, db.dao().countNotifiable())
    }

    @Test fun databasePersistsAcrossReopen() = runBlocking {
        val f = File(ctx.cacheDir, "persist-${UUID.randomUUID()}.db")
        var d = Room.databaseBuilder(ctx, AppDatabase::class.java, f.absolutePath).build()
        wire(d)
        val id = repo.create(Draft("persist"))
        repo.markDone(id)
        d.close()
        d = Room.databaseBuilder(ctx, AppDatabase::class.java, f.absolutePath).build()
        val r = d.dao().get(id)!!
        assertEquals("persist", r.title); assertEquals(State.PENDING, r.state)
        d.close(); f.delete()
        wire(db)
    }

    @Test fun backupRoundTripNeverDuplicatesOrOverwrites() = runBlocking {
        repo.createBulk(listOf("a", "b", "c"))
        val out = ByteArrayOutputStream()
        assertEquals(3, repo.export(out))
        assertEquals(0, repo.import(ByteArrayInputStream(out.toByteArray())))   // same data -> nothing added
        db.dao().delete(1)
        assertEquals(1, repo.import(ByteArrayInputStream(out.toByteArray())))   // only the missing one
        assertEquals(3, db.dao().countAll())
    }
}
