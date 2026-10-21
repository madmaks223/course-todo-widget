package com.course.todo.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

enum class ThemeStyle { SYSTEM, LIGHT, DARK, MINT, PAPER }

enum class WidgetStyle { MATERIAL_CLEAR, NOTION_FOCUS, PAPER_TIMELINE }

/**
 * 截止警示方案，全部内置，可自由选择：
 * - SOFT：柔和警示（只标红时间和标签）
 * - CARD_CAPSULE：红边卡片 + 倒计时胶囊
 * - TIMELINE：时间线警戒
 * - PROGRESS：整行浅红 + 时间进度条
 */
enum class DeadlineAlertStyle { SOFT, CARD_CAPSULE, TIMELINE, PROGRESS }

data class Palette(
    val background: Int,
    val surface: Int,
    val text: Int,
    val secondary: Int,
    val accent: Int,
    val border: Int,
)

data class WidgetPalette(
    val background: Int,
    val surface: Int,
    val text: Int,
    val secondary: Int,
    val accent: Int,
    val border: Int,
    val showTimeline: Boolean,
)

object ThemePalette {
    fun resolve(context: Context, style: ThemeStyle): Palette {
        val resolved = if (style == ThemeStyle.SYSTEM) {
            if (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            ) ThemeStyle.DARK else ThemeStyle.LIGHT
        } else style
        return when (resolved) {
            ThemeStyle.DARK -> palette("#171C1A", "#222925", "#F4F6F3", "#A8B4AD", "#8ED0AD", "#3C4841")
            ThemeStyle.MINT -> palette("#EDF6F1", "#FBFFFC", "#1E332A", "#5F7168", "#27785C", "#D2E4D9")
            ThemeStyle.PAPER -> palette("#FBF8F2", "#FFFCF6", "#3D382F", "#7A7166", "#B3653B", "#E7D9C7")
            else -> palette("#F4F7F5", "#FFFFFF", "#24302B", "#68756E", "#27765B", "#DCE7E0")
        }
    }

    private fun palette(background: String, surface: String, text: String, secondary: String, accent: String, border: String) =
        Palette(Color.parseColor(background), Color.parseColor(surface), Color.parseColor(text),
            Color.parseColor(secondary), Color.parseColor(accent), Color.parseColor(border))

    fun resolveWidget(context: Context, theme: ThemeStyle, style: WidgetStyle): WidgetPalette {
        val base = resolve(context, theme)
        return when (style) {
            WidgetStyle.MATERIAL_CLEAR -> WidgetPalette(
                base.background, base.surface, base.text, base.secondary, base.accent, base.border,
                false,
            )
            WidgetStyle.NOTION_FOCUS -> if (isDark(base)) WidgetPalette(
                Color.parseColor("#20211F"), Color.parseColor("#292A27"), Color.parseColor("#ECEBE6"),
                Color.parseColor("#AAA89F"), Color.parseColor("#D87967"), Color.parseColor("#3D3E39"),
                false,
            ) else WidgetPalette(
                Color.parseColor("#F2EFE9"), Color.parseColor("#FFFEFA"), Color.parseColor("#302F2B"),
                Color.parseColor("#7B7970"), Color.parseColor("#C15F49"), Color.parseColor("#E5E0D6"),
                false,
            )
            WidgetStyle.PAPER_TIMELINE -> {
                val dark = isDark(base)
                if (dark) WidgetPalette(
                    Color.parseColor("#28231F"), Color.parseColor("#312B25"), Color.parseColor("#F5EBDD"),
                    Color.parseColor("#C2AA94"), Color.parseColor("#E29A68"), Color.parseColor("#5A493B"),
                    true,
                ) else WidgetPalette(
                    Color.parseColor("#FFF8EA"), Color.parseColor("#FFFDF6"), Color.parseColor("#40352D"),
                    Color.parseColor("#887668"), Color.parseColor("#B36332"), Color.parseColor("#E7D3B4"),
                    true,
                )
            }
        }
    }

    fun resolveApp(context: Context, theme: ThemeStyle, widgetStyle: WidgetStyle): Palette {
        val widget = resolveWidget(context, theme, widgetStyle)
        return Palette(widget.background, widget.surface, widget.text, widget.secondary, widget.accent, widget.border)
    }

    private fun isDark(palette: Palette) = Color.luminance(palette.background) < 0.5f
}
