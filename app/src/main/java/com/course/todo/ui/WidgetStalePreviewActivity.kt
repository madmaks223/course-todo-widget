package com.course.todo.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.course.todo.R

/** 开发者用：预览「长时间未同步」红色小组件与图标效果（不导出，不影响用户）。 */
class WidgetStalePreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF1B1B1F.toInt())
            val pad = (24 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                (340 * density).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        column.addView(headerPreview(density))
        column.addView(focusWidgetPreview(density), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (200 * density).toInt(),
        ).apply { topMargin = (12 * density).toInt() })
        column.addView(
            layoutInflater.inflate(R.layout.widget_stale, column, false),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (244 * density).toInt()).apply {
                topMargin = (14 * density).toInt()
            },
        )
        root.addView(column)
        setContentView(root)
        // 与真实小组件一致：警告页刷新图标用浅色 tint。
        findViewById<ImageView>(R.id.widget_stale_refresh_icon)?.setColorFilter(0xFFFFD9D5.toInt())
    }

    /** 正常小组件 + 黄色特别关注卡片的预览。 */
    private fun focusWidgetPreview(density: Float): View {
        val widget = layoutInflater.inflate(R.layout.widget_todo, null, false)
        val focus = widget.findViewById<LinearLayout>(R.id.widget_focus)
        focus.visibility = View.VISIBLE
        focus.findViewById<TextView>(R.id.widget_focus_title).text = "特别关注 · 测试课程"
        focus.findViewById<TextView>(R.id.widget_focus_detail).text = "2 项待办 · 最近截止 10-25 23:59 · 已置顶"
        focus.findViewById<ImageView>(R.id.widget_focus_icon)?.setColorFilter(0xFFFFF6D8.toInt())
        widget.findViewById<TextView>(R.id.widget_title)?.text = "课程待办"
        widget.findViewById<ImageView>(R.id.widget_refresh)?.setColorFilter(0xFFB36332.toInt())
        widget.findViewById<ImageView>(R.id.widget_settings)?.setColorFilter(0xFF887668.toInt())
        return widget
    }

    /** 正常小组件头部的图标预览。 */
    private fun headerPreview(density: Float): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0xFFFFF8EA.toInt())
        setPadding((16 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
        addView(TextView(this@WidgetStalePreviewActivity).apply {
            text = "课程待办"
            textSize = 18f
            setTextColor(0xFF40352D.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageView(this@WidgetStalePreviewActivity).apply {
            setImageResource(R.drawable.ic_refresh)
            setColorFilter(0xFFB36332.toInt())
        }, LinearLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt()).apply { marginEnd = (10 * density).toInt() })
        addView(ImageView(this@WidgetStalePreviewActivity).apply {
            setImageResource(R.drawable.ic_settings)
            setColorFilter(0xFF887668.toInt())
        }, LinearLayout.LayoutParams((20 * density).toInt(), (20 * density).toInt()))
    }
}
