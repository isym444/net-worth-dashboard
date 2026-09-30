package com.isym444.networth.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Network-free, unconstrained backstop: re-render from the cache so "updated Xh ago", hints and ages stay right offline. */
class RerenderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WidgetUpdater.refreshAll(applicationContext)
        return Result.success()
    }
}
