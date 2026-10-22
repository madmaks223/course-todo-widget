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

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
    }

    private fun editSync(block: SharedPreferences.Editor.() -> Unit) = synchronized(sourceLock) {
        prefs.edit().apply(block).apply()
    }

    var demoMode: Boolean
        get() = prefs.getBoolean("demo_mode", true)
        set(value) = editSync {
            if (demoMode != value) {
                putBoolean("demo_mode", value)
                putLong("source_generation", sourceGeneration + 1)
            }
        }
    val sourceGeneration: Long get() = prefs.getLong("source_generation", 0)

    /** 是否成功登录过。用于「演示数据只在首次安装时显示」的判断。 */
    var hasLoggedIn: Boolean
        get() = prefs.getBoolean("has_logged_in", false)
        set(value) = edit { putBoolean("has_logged_in", value) }

    /** 最近一次登录的账号（脱敏），只用于设置页展示登录状态。 */
    var lastAccount: String
        get() = prefs.getString("last_account", "").orEmpty()
        set(value) = edit { putString("last_account", value) }

    /** 最近一次登录时间（毫秒）。 */
    var lastLoginAt: Long
        get() = prefs.getLong("last_login_at", 0L)
        set(value) = edit { putLong("last_login_at", value) }

    fun invalidateSession() = editSync { putLong("source_generation", sourceGeneration + 1) }

    fun commitIfCurrent(generation: Long, commit: () -> Unit): Boolean = synchronized(sourceLock) {
        if (sourceGeneration != generation) false else {
            commit()
            true
        }
    }

    var autoSyncEnabled: Boolean
        get() = prefs.getBoolean("auto_sync", true)
        set(value) = edit { putBoolean("auto_sync", value) }

    var realtimeSyncEnabled: Boolean
        get() = prefs.getBoolean("realtime_sync", hasLiveSnapshot())
        set(value) = edit { putBoolean("realtime_sync", value) }

    var theme: ThemeStyle
        get() = runCatching { ThemeStyle.valueOf(prefs.getString("theme", "SYSTEM")!!) }
            .getOrDefault(ThemeStyle.SYSTEM)
        set(value) = edit { putString("theme", value.name) }

    /** App 字体缩放（80%–200%）。旧版本共用的 font_scale 会自动作为初始值。 */
    var appFontScalePercent: Int
        get() = prefs.getInt("app_font_scale", legacyFontScale()).coerceIn(80, 200)
        set(value) = edit { putInt("app_font_scale", value.coerceIn(80, 200)) }

    /** 桌面小组件字体缩放（80%–200%），与 App 字体大小独立。 */
    var widgetFontScalePercent: Int
        get() = prefs.getInt("widget_font_scale", legacyFontScale()).coerceIn(80, 200)
        set(value) = edit { putInt("widget_font_scale", value.coerceIn(80, 200)) }

    private fun legacyFontScale(): Int = prefs.getInt("font_scale", 100).coerceIn(80, 200)

    var hideCompleted: Boolean
        get() = prefs.getBoolean("hide_completed", false)
        set(value) = edit { putBoolean("hide_completed", value) }

    var widgetStyle: WidgetStyle
        get() = when (val stored = prefs.getString("widget_style", null)) {
            null, "PAPER_NOTE" -> WidgetStyle.PAPER_TIMELINE
            "CLEAN", "MINT_CARD", "NIGHT", "MATERIAL_CLEAR" -> WidgetStyle.MATERIAL_CLEAR
            "NOTION_FOCUS" -> WidgetStyle.NOTION_FOCUS
            "PAPER_TIMELINE" -> WidgetStyle.PAPER_TIMELINE
            else -> WidgetStyle.PAPER_TIMELINE
        }
        set(value) = edit { putString("widget_style", value.name) }

    var deadlineAlertStyle: DeadlineAlertStyle
        get() = when (val stored = prefs.getString("deadline_alert_style", null)) {
            null -> DeadlineAlertStyle.CARD_CAPSULE
            // 旧版本 0.2.x 的「红边卡片」升级为带胶囊的新方案。
            "RED_CARD" -> DeadlineAlertStyle.CARD_CAPSULE
            else -> runCatching { DeadlineAlertStyle.valueOf(stored) }
                .getOrDefault(DeadlineAlertStyle.CARD_CAPSULE)
        }
        set(value) = edit { putString("deadline_alert_style", value.name) }

    /** 任务排序：按课程分组，或按截止时间由近到远。 */
    var taskSortOrder: TaskSortOrder
        get() = runCatching {
            TaskSortOrder.valueOf(prefs.getString("task_sort_order", TaskSortOrder.COURSE.name)!!)
        }.getOrDefault(TaskSortOrder.COURSE)
        set(value) = edit { putString("task_sort_order", value.name) }

    /** 最近一次完整同步成功时间（毫秒），用于决定轻量/完整同步。 */
    var lastFullSyncAt: Long
        get() = prefs.getLong("last_full_sync", 0)
        set(value) = edit { putLong("last_full_sync", value) }

    /** 课程平台访问验证冷却结束时间（毫秒）；冷却期内跳过自动同步，避免频繁重试。 */
    var riskControlUntil: Long
        get() = prefs.getLong("risk_control_until", 0)
        set(value) = edit { putLong("risk_control_until", value) }

    /** 是否已经提示过「忽略电池优化」，避免每次启动都弹系统框。 */
    var batteryOptimizationPrompted: Boolean
        get() = prefs.getBoolean("battery_optimization_prompted", false)
        set(value) = edit { putBoolean("battery_optimization_prompted", value) }

    var exportUri: String?
        get() = prefs.getString("export_uri", null)
        set(value) = edit { putString("export_uri", value) }

    /** 最近一次同步开始时间（毫秒），用于识别进程被杀后残留的 RUNNING 状态。 */
    var syncStartedAt: Long
        get() = prefs.getLong("sync_started_at", 0L)
        set(value) = edit { putLong("sync_started_at", value) }

    fun readSyncState(): SyncState = SyncState(
        runCatching { SyncStatus.valueOf(prefs.getString("sync_status", "IDLE")!!) }
            .getOrDefault(SyncStatus.IDLE),
        prefs.getString("sync_message", "尚未同步") ?: "尚未同步",
        prefs.getLong("last_success", 0),
    )

    fun writeSyncState(status: SyncStatus, message: String, successAt: Long? = null) = edit {
        putString("sync_status", status.name)
        putString("sync_message", message)
        if (successAt != null) putLong("last_success", successAt)
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
