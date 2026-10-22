package com.course.todo.core

/**
 * 桌面小组件「长时间未同步」判定：超过 4 小时没有成功同步时，小组件切换到红色警告样式。
 */
object WidgetStaleRules {
    const val STALE_THRESHOLD_MILLIS = 4 * 60 * 60 * 1000L

    fun isStale(
        lastSuccessEpochMillis: Long,
        nowEpochMillis: Long,
        demoMode: Boolean,
        hasLoggedIn: Boolean,
        syncing: Boolean = false,
    ): Boolean {
        if (demoMode || !hasLoggedIn || syncing) return false
        // 登录后从未成功同步过，也算需要提醒（显示“尚未同步”）。
        if (lastSuccessEpochMillis <= 0L) return true
        return nowEpochMillis - lastSuccessEpochMillis > STALE_THRESHOLD_MILLIS
    }
}
