package com.course.todo.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.course.todo.R

/** 开发者用：预览「长时间未同步」红色小组件样式（不导出，不影响用户）。 */
class WidgetStalePreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF1B1B1F.toInt())
            val pad = (24 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val params = FrameLayout.LayoutParams(
            (340 * density).toInt(),
            (244 * density).toInt(),
            Gravity.CENTER,
        )
        root.addView(layoutInflater.inflate(R.layout.widget_stale, root, false), params)
        setContentView(root)
    }
}
