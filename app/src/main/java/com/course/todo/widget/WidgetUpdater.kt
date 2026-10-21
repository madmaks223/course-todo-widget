package com.course.todo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.course.todo.R
import com.course.todo.core.DeadlineRules
import com.course.todo.core.MarkdownDocument
import com.course.todo.core.MarkdownRow
import com.course.todo.core.TaskSortOrder
import com.course.todo.core.TodoOrdering
import com.course.todo.storage.AppPreferences
import com.course.todo.storage.MarkdownStore
import com.course.todo.storage.SyncStatus
import com.course.todo.ui.DeadlineAlertPalette
import com.course.todo.ui.MainActivity
import com.course.todo.ui.ThemePalette
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

object WidgetUpdater {
    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, TodoWidgetProvider::class.java))
        ids.forEach { refresh(context, it) }
    }

    @Suppress("DEPRECATION")
    fun refresh(context: Context, appWidgetId: Int) {
        val manager = AppWidgetManager.getInstance(context)
        val preferences = AppPreferences(context)
        val palette = ThemePalette.resolveWidget(context, preferences.theme, preferences.widgetStyle)
        val alertColors = DeadlineAlertPalette.resolve(palette.background, preferences.deadlineAlertStyle)
        val markdown = MarkdownStore(context).read()
        val documentRows = markdown?.let(MarkdownDocument::parse).orEmpty()
        val now = Instant.now()
        val isDemo = documentRows.firstOrNull { it.isHeading }?.text?.let {
            it.startsWith("课程待办") && it.contains("演示")
        } == true
        val tasks = documentRows.filter { it.checked != null }
        val pendingTasks = visibleRows(documentRows, now, preferences.taskSortOrder).filter { it.checked != null }
        val urgentCount = pendingTasks.count { DeadlineRules.isUrgent(it, now) }
        val fontScale = preferences.fontScalePercent.coerceIn(80, 160) / 100f
        val narrow = manager.getAppWidgetOptions(appWidgetId)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) < 220
        val views = RemoteViews(context.packageName, R.layout.widget_todo)

        views.setInt(R.id.widget_background_image, "setColorFilter", palette.background)
        views.setTextColor(R.id.widget_title, palette.text)
        views.setTextColor(R.id.widget_count, palette.secondary)
        views.setTextColor(R.id.widget_urgent_count, alertColors.color)
        views.setTextColor(R.id.widget_status, palette.secondary)
        views.setTextColor(R.id.widget_empty, palette.secondary)
        views.setInt(R.id.widget_refresh, "setColorFilter", palette.accent)
        views.setInt(R.id.widget_settings, "setColorFilter", palette.secondary)
        views.setTextViewTextSize(
            R.id.widget_title, TypedValue.COMPLEX_UNIT_SP,
            if (narrow) 13f else (if (preferences.widgetStyle == com.course.todo.ui.WidgetStyle.PAPER_TIMELINE) 15f else 14f) * fontScale,
        )
        views.setTextViewTextSize(
            R.id.widget_count, TypedValue.COMPLEX_UNIT_SP, (11f * fontScale).coerceAtMost(14f),
        )
        views.setTextViewTextSize(
            R.id.widget_urgent_count, TypedValue.COMPLEX_UNIT_SP, (11f * fontScale).coerceAtMost(14f),
        )
        views.setTextViewTextSize(
            R.id.widget_status, TypedValue.COMPLEX_UNIT_SP, (11f * fontScale).coerceAtMost(14f),
        )
        views.setTextViewTextSize(
            R.id.widget_empty, TypedValue.COMPLEX_UNIT_SP, (13f * fontScale).coerceAtMost(18f),
        )
        views.setTextViewText(
            R.id.widget_count,
            if (tasks.isEmpty()) "暂无任务" else "待完成 ${pendingTasks.size}",
        )
        val urgentText = "临期 · $urgentCount 项（24 小时或剩余不足 30%）"
        views.setTextViewText(R.id.widget_urgent_count, urgentText)
        views.setContentDescription(R.id.widget_urgent_count, urgentText)
        views.setViewVisibility(R.id.widget_urgent_count, if (urgentCount > 0) View.VISIBLE else View.GONE)

        val state = preferences.readSyncState()
        val hasExportWarning = state.status == SyncStatus.SUCCESS && state.message.contains("外部 Markdown 写入失败")
        val statusPrefix = if (isDemo) "演示 · " else ""
        val statusText = when (state.status) {
            SyncStatus.RUNNING -> state.message.ifBlank { "正在同步课程平台..." }
            SyncStatus.AUTH_REQUIRED -> "需要登录课程平台"
            SyncStatus.ERROR -> state.message.ifBlank { "同步失败，请打开 App 查看" }
            SyncStatus.IDLE, SyncStatus.SUCCESS -> {
                if (state.lastSuccessEpochMillis > 0) {
                    val time = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                        .format(Date(state.lastSuccessEpochMillis))
                    (if (isDemo) time else "更新于 $time") +
                        if (hasExportWarning) "\n外部 MD 写入失败" else ""
                } else {
                    "尚未同步"
                }
            }
        }
        val status = statusPrefix + statusText
        views.setTextViewText(R.id.widget_status, status)
        views.setContentDescription(R.id.widget_status, status)
        if (state.status == SyncStatus.ERROR || state.status == SyncStatus.AUTH_REQUIRED || hasExportWarning) {
            views.setTextColor(R.id.widget_status, palette.accent)
        }
        val empty = when {
            markdown.isNullOrBlank() -> "登录并同步后显示课程待办"
            tasks.isNotEmpty() && tasks.all { it.checked == true } -> "全部任务已完成"
            tasks.isNotEmpty() && pendingTasks.isEmpty() -> "当前没有未截止的待办"
            else -> "当前没有待办"
        }
        views.setTextViewText(R.id.widget_empty, empty)
        if (preferences.widgetStyle == com.course.todo.ui.WidgetStyle.PAPER_TIMELINE) {
            views.setViewPadding(R.id.widget_content, dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            views.setTextColor(R.id.widget_count, palette.accent)
        }

        val service = Intent(context, TodoWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse("xxttodo://widget/$appWidgetId")
        }
        views.setRemoteAdapter(R.id.widget_list, service)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.parse("xxttodo://widget/$appWidgetId/open")
        }
        val open = PendingIntent.getActivity(
            context, appWidgetId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val settingsIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.parse("xxttodo://widget/$appWidgetId/settings")
            putExtra("show_settings", true)
        }
        val settings = PendingIntent.getActivity(
            context, appWidgetId, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_title, open)
        views.setOnClickPendingIntent(R.id.widget_urgent_count, open)
        views.setOnClickPendingIntent(R.id.widget_settings, settings)
        views.setOnClickPendingIntent(R.id.widget_status, open)
        views.setOnClickPendingIntent(R.id.widget_empty, open)
        val rowTemplate = PendingIntent.getActivity(
            context, appWidgetId + 10_000, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        views.setPendingIntentTemplate(R.id.widget_list, rowTemplate)

        val refreshIntent = Intent(context, TodoWidgetProvider::class.java).apply {
            action = TodoWidgetProvider.ACTION_REFRESH
            data = Uri.parse("xxttodo://widget/$appWidgetId/refresh")
        }
        val refresh = PendingIntent.getBroadcast(
            context, appWidgetId, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_refresh, refresh)
        manager.updateAppWidget(appWidgetId, views)
        manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list)
    }
}

private fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()

internal fun visibleRows(
    markdown: String?,
    now: Instant = Instant.now(),
    sortOrder: TaskSortOrder = TaskSortOrder.COURSE,
): List<MarkdownRow> =
    visibleRows(markdown?.let(MarkdownDocument::parse).orEmpty(), now, sortOrder)

private fun visibleRows(
    rows: List<MarkdownRow>,
    now: Instant,
    sortOrder: TaskSortOrder,
): List<MarkdownRow> {
    val documentTitle = rows.firstOrNull { it.isHeading }?.takeIf { it.text.startsWith("课程待办") }
    val visible = DeadlineRules.filterVisible(rows, now).filter { it.isHeading || it.checked != null }
        .filterNot { it === documentTitle }
    // 排序 + 把“分组任务 / 大作业”统一放到最后；空课程标题不会出现。
    return TodoOrdering.build(visible, sortOrder).map { item ->
        when (item) {
            is TodoOrdering.DisplayRow.Heading -> MarkdownRow(item.text, isHeading = true)
            is TodoOrdering.DisplayRow.Task -> item.row
        }
    }
}
