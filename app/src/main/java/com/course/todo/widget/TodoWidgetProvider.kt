package com.course.todo.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.course.todo.sync.SyncScheduler

class TodoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.refreshAll(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        WidgetUpdater.refresh(context, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            // 小组件点按/刷新只做轻量同步，避免频繁点按触发访问验证；完整同步在 App 里手动刷新。
            SyncScheduler.requestLight(context)
            WidgetUpdater.refreshAll(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.course.todo.widget.REFRESH"
    }
}
