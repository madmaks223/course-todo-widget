package com.course.todo.storage

import android.content.Context
import android.content.SharedPreferences
import com.course.todo.core.TaskSortOrder
import com.course.todo.ui.DeadlineAlertStyle
import com.course.todo.ui.ThemeStyle
import com.course.todo.ui.WidgetStyle
import java.io.File

enum class SyncStatus { IDLE, RUNNING, SUCCESS, ERROR, AUTH_REQUIRED }

data class SyncState(
    val status: SyncStatus,
    val message: String,
    val lastSuccessEpochMillis: Long,
)

class AppPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("todo_settings", Context.MODE_PRIVATE)

    var demoMode: Boolean
        get() = prefs.getBoolean("demo_mode", true)
        set(value) = synchronized(sourceLock) {
            if (demoMode != value) {
                prefs.edit().putBoolean("demo_mode", value)
                    .putLong("source_generation", sourceGeneration + 1).apply()
            }
        }
    val sourceGeneration: Long get() = prefs.getLong("source_generation", 0)
    /** 是否成功登录过。用于「演示数据只在首次安装时显示」的判断。 */
    var hasLoggedIn: Boolean
        get() = prefs.getBoolean("has_logged_in", false)
        set(value) = prefs.edit().putBoolean("has_logged_in", value).apply()

    /** 最近一次登录的账号（脱敏），只用于设置页展示登录状态。 */
    var lastAccount: String
        get() = prefs.getString("last_account", "").orEmpty()
        set(value) = prefs.edit().putString("last_account", value).apply()

    /** 最近一次登录时间（毫秒）。 */
    var lastLoginAt: Long
        get() = prefs.getLong("last_login_at", 0L)
        set(value) = prefs.edit().putLong("last_login_at", value).apply()


    fun invalidateSession() = synchronized(sourceLock) {
        prefs.edit().putLong("source_generation", sourceGeneration + 1).apply()
    }

    fun commitIfCurrent(generation: Long, commit: () -> Unit): Boolean = synchronized(sourceLock) {
        if (sourceGeneration != generation) false else {
            commit()
            true
        }
    }
    var autoSyncEnabled: Boolean
        get() = prefs.getBoolean("auto_sync", true)
        set(value) { prefs.edit().putBoolean("auto_sync", value).apply() }
    var realtimeSyncEnabled: Boolean
        get() = prefs.getBoolean("realtime_sync", hasLiveSnapshot())
        set(value) { prefs.edit().putBoolean("realtime_sync", value).apply() }
    var theme: ThemeStyle
        get() = runCatching { ThemeStyle.valueOf(prefs.getString("theme", "SYSTEM")!!) }
            .getOrDefault(ThemeStyle.SYSTEM)
        set(value) { prefs.edit().putString("theme", value.name).apply() }
    var fontScalePercent: Int
        get() = prefs.getInt("font_scale", 100).coerceIn(80, 160)
        set(value) { prefs.edit().putInt("font_scale", value.coerceIn(80, 160)).apply() }
    var hideCompleted: Boolean
        get() = prefs.getBoolean("hide_completed", false)
        set(value) { prefs.edit().putBoolean("hide_completed", value).apply() }
    var widgetStyle: WidgetStyle
        get() = when (val stored = prefs.getString("widget_style", null)) {
            null, "PAPER_NOTE" -> WidgetStyle.PAPER_TIMELINE
            "CLEAN", "MINT_CARD", "NIGHT" -> WidgetStyle.MATERIAL_CLEAR
            "MATERIAL_CLEAR" -> WidgetStyle.MATERIAL_CLEAR
            "NOTION_FOCUS" -> WidgetStyle.NOTION_FOCUS
            "PAPER_TIMELINE" -> WidgetStyle.PAPER_TIMELINE
            else -> WidgetStyle.PAPER_TIMELINE
        }
        set(value) { prefs.edit().putString("widget_style", value.name).apply() }
    var deadlineAlertStyle: DeadlineAlertStyle
        get() = when (val stored = prefs.getString("deadline_alert_style", null)) {
            null -> DeadlineAlertStyle.CARD_CAPSULE
            // 旧版本 0.2.x 的「红边卡片」升级为带胶囊的新方案。
            "RED_CARD" -> DeadlineAlertStyle.CARD_CAPSULE
            else -> runCatching { DeadlineAlertStyle.valueOf(stored) }
                .getOrDefault(DeadlineAlertStyle.CARD_CAPSULE)
        }
        set(value) { prefs.edit().putString("deadline_alert_style", value.name).apply() }

    /** 任务排序：按课程分组，或按截止时间由近到远。 */
    var taskSortOrder: TaskSortOrder
        get() = runCatching {
            TaskSortOrder.valueOf(prefs.getString("task_sort_order", TaskSortOrder.COURSE.name)!!)
        }.getOrDefault(TaskSortOrder.COURSE)
        set(value) { prefs.edit().putString("task_sort_order", value.name).apply() }

    /** 最近一次完整同步成功时间（毫秒），用于决定轻量/完整同步。 */
    var lastFullSyncAt: Long
        get() = prefs.getLong("last_full_sync", 0)
        set(value) { prefs.edit().putLong("last_full_sync", value).apply() }

    /** 课程平台访问验证冷却结束时间（毫秒）；冷却期内跳过自动同步，避免频繁重试。 */
    var riskControlUntil: Long
        get() = prefs.getLong("risk_control_until", 0)
        set(value) { prefs.edit().putLong("risk_control_until", value).apply() }

    /** 是否已经提示过「忽略电池优化」，避免每次启动都弹系统框。 */
    var batteryOptimizationPrompted: Boolean
        get() = prefs.getBoolean("battery_optimization_prompted", false)
        set(value) { prefs.edit().putBoolean("battery_optimization_prompted", value).apply() }
    var exportUri: String?
        get() = prefs.getString("export_uri", null)
        set(value) { prefs.edit().putString("export_uri", value).apply() }

    fun readSyncState(): SyncState = SyncState(
        runCatching { SyncStatus.valueOf(prefs.getString("sync_status", "IDLE")!!) }
            .getOrDefault(SyncStatus.IDLE),
        prefs.getString("sync_message", "尚未同步") ?: "尚未同步",
        prefs.getLong("last_success", 0),
    )

    fun writeSyncState(status: SyncStatus, message: String, successAt: Long? = null) {
        prefs.edit().apply {
            putString("sync_status", status.name)
            putString("sync_message", message)
            if (successAt != null) putLong("last_success", successAt)
        }.apply()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)
    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    private fun hasLiveSnapshot(): Boolean {
        if (demoMode || readSyncState().status != SyncStatus.SUCCESS) return false
        val firstLine = runCatching {
            File(appContext.filesDir, "课程待办.md").useLines { it.firstOrNull() }
        }.getOrNull()
        return firstLine != null && firstLine.startsWith("# 课程待办") && !firstLine.contains("演示")
    }

    companion object { private val sourceLock = Any() }
}
