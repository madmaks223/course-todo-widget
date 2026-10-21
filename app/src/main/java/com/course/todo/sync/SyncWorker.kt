package com.course.todo.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val periodic = inputData.getBoolean("periodic", false)
        val mode = when {
            periodic -> SyncRunner.SyncMode.AUTO
            inputData.getBoolean("full", true) -> SyncRunner.SyncMode.FULL
            else -> SyncRunner.SyncMode.AUTO
        }
        return when (val outcome = SyncRunner.execute(applicationContext, mode)) {
            SyncRunner.Outcome.Success -> Result.success()
            SyncRunner.Outcome.Stale -> Result.success()
            SyncRunner.Outcome.AuthenticationRequired -> {
                RealtimeSyncService.stop(applicationContext)
                Result.success()
            }
            SyncRunner.Outcome.RiskControl -> {
                RealtimeSyncService.stop(applicationContext)
                Result.success()
            }
            is SyncRunner.Outcome.Failure -> {
                if (outcome.retryable && inputData.getBoolean("periodic", false) && runAttemptCount < 2) {
                    Result.retry()
                } else {
                    Result.success()
                }
            }
        }
    }
}
