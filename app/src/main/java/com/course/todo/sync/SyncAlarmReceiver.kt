package com.course.todo.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.course.todo.storage.AppPreferences
import com.course.todo.widget.WidgetUpdater

/** 30 分钟兜底闹钟：恢复后台能力并触发一次轻量同步。 */
class SyncAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        // 先排下一次，避免这次处理完进程被回收后兜底断掉。
        SyncAlarmScheduler.schedule(appContext)
        Log.d("xxt-sync", "sync alarm fired")
        val prefs = AppPreferences(appContext)
        runCatching { WidgetUpdater.refreshAll(appContext) }
        if (prefs.demoMode) return
        if (prefs.realtimeSyncEnabled) {
            runCatching { RealtimeSyncService.start(appContext) }
        }
        if (prefs.autoSyncEnabled) {
            SyncScheduler.requestLight(appContext)
        }
    }
}
