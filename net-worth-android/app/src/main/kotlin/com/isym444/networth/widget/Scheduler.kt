package com.isym444.networth.widget

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** No midnight alarm: the data changes about once a day, so an hourly refresh plus a 6-hourly re-render is enough. */
object Scheduler {
    private const val PERIODIC = "networth-refresh"
    private const val ONCE = "networth-refresh-now"
    private const val RERENDER = "networth-rerender"
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Idempotent: safe to call from every render and from the settings screen. */
    fun ensureScheduled(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RefreshWorker>(60, TimeUnit.MINUTES)
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build(),
        )
        // No constraints, no network: keeps "updated Xh ago", hints and ages current while offline.
        wm.enqueueUniquePeriodicWork(
            RERENDER, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RerenderWorker>(6, TimeUnit.HOURS).build(),
        )
    }

    fun refreshNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONCE, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(online)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(RERENDER)
    }
}
