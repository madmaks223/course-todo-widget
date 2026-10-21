package com.course.todo.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.course.todo.storage.AppPreferences
import com.course.todo.widget.WidgetUpdater

/**
 * 开机、应用更新、部分厂商的快速开机广播后恢复后台能力：
 * - 重新登记 WorkManager 兜底任务；
 * - 重新绘制全部桌面小组件；
 * - 若常驻检查仍开启，则恢复前台服务，避免重启后长期不同步。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val preferences = AppPreferences(appContext)
        SyncScheduler.schedule(appContext)
        runCatching { WidgetUpdater.refreshAll(appContext) }
        if (preferences.demoMode) return
        if (preferences.realtimeSyncEnabled) {
            runCatching { RealtimeSyncService.start(appContext) }
        } else {
            SyncScheduler.requestNow(appContext)
        }
    }
}
