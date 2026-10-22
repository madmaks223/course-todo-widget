package com.course.todo.sync

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.course.todo.storage.AppPreferences
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val PERIODIC = "xxt_sync_30min"
    private const val SINGLE_SYNC = "xxt_manual_sync"
    private const val LIGHT_SYNC = "xxt_light_sync"

    fun schedule(context: Context) {
        val prefs = AppPreferences(context)
        val manager = WorkManager.getInstance(context)
        if (!prefs.autoSyncEnabled) {
            manager.cancelUniqueWork(PERIODIC)
            return
        }
        val constraints = Constraints.Builder().apply {
            if (!prefs.demoMode) setRequiredNetworkType(NetworkType.CONNECTED)
        }.build()
        manager.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setInputData(workDataOf("periodic" to true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    fun requestNow(context: Context) {
        enqueueSingle(context, expedited = true, full = true)
    }

    /** 轻量刷新：只请求 3 个接口，用于 App 可见时的自动刷新，避免触发访问验证。 */
    fun requestLight(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            LIGHT_SYNC,
            ExistingWorkPolicy.KEEP,
            oneTimeWork(context, full = false),
        )
    }

    fun requestLive(context: Context) {
        enqueueSingle(context, expedited = false, full = true)
    }

    private fun enqueueSingle(context: Context, expedited: Boolean, full: Boolean) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            SINGLE_SYNC,
            ExistingWorkPolicy.KEEP,
            oneTimeWork(context, full, expedited),
        )
    }

    private fun oneTimeWork(
        context: Context,
        full: Boolean,
        expedited: Boolean = false,
    ): OneTimeWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
        .apply {
            setInputData(workDataOf("full" to full))
            setConstraints(singleConstraints(context))
            if (expedited && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
        }
        .build()

    private fun singleConstraints(context: Context): Constraints = Constraints.Builder().apply {
        if (!AppPreferences(context).demoMode) setRequiredNetworkType(NetworkType.CONNECTED)
    }.build()

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }
}
