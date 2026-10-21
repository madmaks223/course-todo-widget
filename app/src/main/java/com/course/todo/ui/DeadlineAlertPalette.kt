package com.course.todo.ui

import android.graphics.Color

data class DeadlineAlertColors(
    val color: Int,
    val background: Int,
    val border: Int,
    val capsuleBackground: Int,
    val capsuleText: Int,
    val progressTrack: Int,
    val progressFill: Int,
)

object DeadlineAlertPalette {
    fun resolve(background: Int, style: DeadlineAlertStyle): DeadlineAlertColors {
        val dark = Color.luminance(background) < 0.5f
        val color = Color.parseColor(
            if (dark) {
                "#FFB4AB"
            } else {
                when (style) {
                    DeadlineAlertStyle.SOFT -> "#B23C36"
                    DeadlineAlertStyle.CARD_CAPSULE -> "#BA1A1A"
                    DeadlineAlertStyle.TIMELINE -> "#C62828"
                    DeadlineAlertStyle.PROGRESS -> "#B3261E"
                }
            },
        )
        return DeadlineAlertColors(
            color = color,
            background = Color.parseColor(if (dark) "#4B2425" else "#FFF0ED"),
            border = color,
            capsuleBackground = Color.parseColor(if (dark) "#6B2F30" else "#FFDAD6"),
            capsuleText = Color.parseColor(if (dark) "#FFDAD6" else "#93000A"),
            progressTrack = Color.parseColor(if (dark) "#5C2B2C" else "#FFDDD8"),
            progressFill = color,
        )
    }
}
