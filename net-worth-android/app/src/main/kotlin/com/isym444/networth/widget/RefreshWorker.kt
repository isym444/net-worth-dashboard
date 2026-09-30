package com.isym444.networth.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.isym444.networth.data.AppGraph
import com.isym444.networth.data.SyncResult
import com.isym444.networth.data.rejectsKey

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        if (!graph.settings.load().isConfigured) return Result.success()
        val result = graph.sync().refresh()
        WidgetUpdater.refreshAll(applicationContext)        // also refreshes the hint line after a failure
        // Retrying a rejected key is pointless; the user has to fix it in the app.
        val retry = result is SyncResult.Failed && !result.error.rejectsKey && runAttemptCount < 3
        return if (retry) Result.retry() else Result.success()
    }
}
