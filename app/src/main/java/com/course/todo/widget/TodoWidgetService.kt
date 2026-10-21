package com.course.todo.widget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.course.todo.R
import com.course.todo.core.DeadlineRules
import com.course.todo.core.MarkdownRow
import com.course.todo.storage.AppPreferences
import com.course.todo.storage.MarkdownStore
import com.course.todo.ui.DeadlineAlertPalette
import com.course.todo.ui.DeadlineAlertStyle
import com.course.todo.ui.ThemePalette
import java.time.Instant

class TodoWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = TodoFactory(applicationContext)

    private class TodoFactory(private val context: Context) : RemoteViewsFactory {
        private var rows = emptyList<MarkdownRow>()
        private var preferences = AppPreferences(context)
        private var renderNow = Instant.now()

        override fun onCreate() = load()
        override fun onDataSetChanged() = load()

        private fun load() {
            preferences = AppPreferences(context)
            renderNow = Instant.now()
            val baseRows = visibleRows(MarkdownStore(context).read(), renderNow, preferences.taskSortOrder)
            rows = if (preferences.demoMode) {
                listOf(MarkdownRow(text = "演示数据 · 登录学习通后显示真实待办", isHeading = true)) + baseRows
            } else {
                baseRows
            }
        }

        override fun onDestroy() {
            rows = emptyList()
        }

        override fun getCount(): Int = rows.size
        override fun getViewTypeCount(): Int = 2
        override fun hasStableIds(): Boolean = false
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getLoadingView(): RemoteViews? = null

        override fun getViewAt(position: Int): RemoteViews? {
            val row = rows.getOrNull(position) ?: return null
            val palette = ThemePalette.resolveWidget(context, preferences.theme, preferences.widgetStyle)
            val style = preferences.deadlineAlertStyle
            val alertColors = DeadlineAlertPalette.resolve(palette.background, style)
            val dark = Color.luminance(palette.background) < 0.5f
            val showTimeline = palette.showTimeline || style == DeadlineAlertStyle.TIMELINE
            val fontScale = preferences.fontScalePercent.coerceIn(80, 160) / 100f
            val layout = if (row.isHeading) R.layout.widget_heading else R.layout.widget_row
            val views = RemoteViews(context.packageName, layout)
            if (row.isHeading) {
                views.setTextViewText(R.id.widget_heading_text, row.text)
                views.setTextColor(R.id.widget_heading_text, palette.accent)
                views.setViewVisibility(R.id.widget_heading_marker, if (showTimeline) View.VISIBLE else View.GONE)
                views.setInt(R.id.widget_heading_marker, "setColorFilter", palette.accent)
                views.setTextViewTextSize(
                    R.id.widget_heading_text, TypedValue.COMPLEX_UNIT_SP,
                    ((if (showTimeline) 13f else 12f) * fontScale).coerceAtMost(18f),
                )
                views.setOnClickFillInIntent(R.id.widget_heading_root, Intent())
                return views
            }

            val checked = row.checked == true
            val urgent = DeadlineRules.isUrgent(row, renderNow)
            val card = urgent && style == DeadlineAlertStyle.CARD_CAPSULE
            val tinted = urgent && style == DeadlineAlertStyle.PROGRESS
            val emphasized = urgent && style != DeadlineAlertStyle.SOFT
            val hasCardBackground = card || tinted
            views.setViewVisibility(R.id.widget_row_timeline_line, if (showTimeline) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_row_timeline_dot, if (showTimeline) View.VISIBLE else View.GONE)
            views.setInt(
                R.id.widget_row_timeline_line, "setColorFilter",
                if (urgent) alertColors.color else palette.border,
            )
            views.setInt(R.id.widget_row_timeline_dot, "setColorFilter", if (urgent) alertColors.color else palette.accent)
            views.setViewVisibility(R.id.widget_row_alert_background, if (hasCardBackground) View.VISIBLE else View.GONE)
            if (hasCardBackground) {
                views.setImageViewResource(
                    R.id.widget_row_alert_background,
                    if (dark) R.drawable.widget_deadline_card_dark else R.drawable.widget_deadline_card_light,
                )
            }
            views.setViewPadding(
                R.id.widget_row_content,
                dp(if (showTimeline) 24 else if (hasCardBackground) 10 else 6),
                dp(7),
                dp(if (hasCardBackground) 10 else 6),
                dp(7),
            )
            views.setTextViewText(R.id.widget_row_title, row.text)
            views.setTextColor(
                R.id.widget_row_title,
                when {
                    checked -> palette.secondary
                    emphasized -> alertColors.color
                    else -> palette.text
                },
            )
            views.setTextViewTextSize(R.id.widget_row_title, TypedValue.COMPLEX_UNIT_SP, 14f * fontScale)
            views.setInt(
                R.id.widget_row_title, "setPaintFlags",
                Paint.ANTI_ALIAS_FLAG or
                    (if (checked) Paint.STRIKE_THRU_TEXT_FLAG else 0) or
                    (if (emphasized) Paint.FAKE_BOLD_TEXT_FLAG else 0),
            )
            views.setViewVisibility(R.id.widget_row_check, if (row.checked == null || showTimeline) View.GONE else View.VISIBLE)
            views.setImageViewResource(R.id.widget_row_check, if (checked) R.drawable.ic_check else R.drawable.ic_circle)
            views.setInt(
                R.id.widget_row_check, "setColorFilter",
                when {
                    checked -> palette.secondary
                    urgent -> alertColors.color
                    else -> palette.accent
                },
            )
            views.setContentDescription(R.id.widget_row_check, if (checked) "已完成" else "待完成")
            views.setTextViewText(R.id.widget_row_detail, row.detail.orEmpty())
            views.setTextColor(R.id.widget_row_detail, if (urgent) alertColors.color else palette.secondary)
            views.setTextViewTextSize(
                R.id.widget_row_detail, TypedValue.COMPLEX_UNIT_SP,
                (11f * fontScale).coerceAtMost(16f),
            )
            views.setViewVisibility(R.id.widget_row_detail, if (row.detail.isNullOrBlank()) View.GONE else View.VISIBLE)

            val urgencyLabel = DeadlineRules.urgencyLabel(row, renderNow).orEmpty()
            views.setTextViewText(R.id.widget_row_alert_label, urgencyLabel)
            views.setTextColor(R.id.widget_row_alert_label, alertColors.color)
            views.setTextViewTextSize(
                R.id.widget_row_alert_label, TypedValue.COMPLEX_UNIT_SP,
                (11f * fontScale).coerceAtMost(16f),
            )
            views.setViewVisibility(
                R.id.widget_row_alert_label,
                if (urgent && urgencyLabel.isNotBlank()) View.VISIBLE else View.GONE,
            )

            // 方案 A：右侧倒计时胶囊（剩余百分比）
            val remaining = if (card) DeadlineRules.remainingPercent(row, renderNow) else null
            if (remaining != null) {
                views.setTextViewText(R.id.widget_row_capsule, "剩 $remaining%")
                views.setTextColor(R.id.widget_row_capsule, alertColors.capsuleText)
                views.setInt(
                    R.id.widget_row_capsule, "setBackgroundResource",
                    if (dark) R.drawable.widget_capsule_dark else R.drawable.widget_capsule_light,
                )
                views.setTextViewTextSize(
                    R.id.widget_row_capsule, TypedValue.COMPLEX_UNIT_SP,
                    (10.5f * fontScale).coerceAtMost(15f),
                )
                views.setViewVisibility(R.id.widget_row_capsule, View.VISIBLE)
                views.setContentDescription(R.id.widget_row_capsule, "剩余时间 $remaining%")
            } else {
                views.setViewVisibility(R.id.widget_row_capsule, View.GONE)
            }

            // 方案 C：时间进度条（时间已用百分比）
            if (tinted) {
                val elapsed = DeadlineRules.elapsedPercent(row, renderNow) ?: 75
                views.setProgressBar(R.id.widget_row_progress, 100, elapsed, false)
                views.setViewVisibility(R.id.widget_row_progress, View.VISIBLE)
                views.setContentDescription(R.id.widget_row_progress, "时间已过 $elapsed%")
            } else {
                views.setViewVisibility(R.id.widget_row_progress, View.GONE)
            }

            views.setContentDescription(
                R.id.widget_row_root,
                listOfNotNull(row.text, row.detail, urgencyLabel.takeIf { urgent }).joinToString("，"),
            )
            views.setOnClickFillInIntent(R.id.widget_row_root, Intent())
            return views
        }

        private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
    }
}
