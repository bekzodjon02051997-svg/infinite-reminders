package com.infinitereminders.app

import android.app.Application
import android.content.Context
import com.infinitereminders.app.data.AppDatabase
import com.infinitereminders.app.data.ReminderRepository
import com.infinitereminders.app.data.SettingsStore
import com.infinitereminders.app.notify.AlarmScheduler
import com.infinitereminders.app.notify.NotificationFactory
import com.infinitereminders.app.notify.NotificationPoster
import com.infinitereminders.app.notify.NotificationSynchronizer
import com.infinitereminders.app.notify.SystemPoster
import com.infinitereminders.app.work.ReconcileWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class Graph(ctx: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings = SettingsStore(ctx)
    val db = AppDatabase.create(ctx)
    val poster: NotificationPoster = SystemPoster(ctx)
    val factory = NotificationFactory(ctx)
    val sync = NotificationSynchronizer(ctx, db.dao(), settings, poster, factory)
    val alarms = AlarmScheduler(ctx, db.dao(), poster, factory, settings)
    val repo = ReminderRepository(db, sync, alarms)

    /** Idempotent recovery used by boot, app start, WorkManager and the manual "restore" button. */
    suspend fun recover() {
        sync.syncAll()
        alarms.rescheduleNext()
    }
}

class App : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        ReconcileWorker.schedule(this, graph.settings.value.periodicHeal)
    }
}

val Context.graph: Graph get() = (applicationContext as App).graph
