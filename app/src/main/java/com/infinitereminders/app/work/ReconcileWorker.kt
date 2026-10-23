package com.infinitereminders.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.infinitereminders.app.graph
import java.util.concurrent.TimeUnit

/** Periodic self-heal: re-posts anything an OEM/user cleared, using WorkManager (no always-on service). */
class ReconcileWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        applicationContext.graph.recover()
        return Result.success()
    }

    companion object {
        private const val NAME = "reconcile"
        fun schedule(ctx: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (enabled) {
                wm.enqueueUniquePeriodicWork(
                    NAME, ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<ReconcileWorker>(15, TimeUnit.MINUTES).build()
                )
            } else wm.cancelUniqueWork(NAME)
        }
    }
}
