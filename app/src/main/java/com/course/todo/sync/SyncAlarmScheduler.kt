package com.course.todo.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 30 分钟兜底闹钟：WorkManager 在 Doze / 厂商省电策略下可能被延迟，
 * 这里用 [AlarmManager.setAndAllowWhileIdle]（非精确闹钟，不需要精确闹钟权限）
 * 每 30 分钟叫醒一次进程，触发一次轻量同步并刷新小组件。
 */
object SyncAlarmScheduler {
    const val ACTION = "com.course.todo.action.SYNC_ALARM"

    private const val REQUEST_CODE = 4201
    private const val INTERVAL_MILLIS = 30 * 60 * 1000L

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + INTERVAL_MILLIS,
                pendingIntent(appContext),
            )
        }
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(AlarmManager::class.java) ?: return
        runCatching { manager.cancel(pendingIntent(appContext)) }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, SyncAlarmReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
