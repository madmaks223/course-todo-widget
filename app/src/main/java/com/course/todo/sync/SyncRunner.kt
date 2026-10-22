package com.course.todo.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import com.course.todo.core.MarkdownDocument
import com.course.todo.core.RiskControlRequired
import com.course.todo.core.SampleData
import com.course.todo.data.AuthenticationRequired
import com.course.todo.data.PlatformRepository
import com.course.todo.storage.AppPreferences
import com.course.todo.storage.MarkdownStore
import com.course.todo.storage.SnapshotStore
import com.course.todo.storage.SyncStatus
import com.course.todo.widget.WidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Single-flight sync implementation shared by WorkManager and the realtime service.
 *
 * 两级同步：
 * - FULL：逐门课抓作业/考试/任务点，请求较多（约 40 个），刷新绝对截止时间；
 * - LIGHT：只请求课程、全部作业、全部考试 3 个接口，用上次快照补齐时间，
 *   用于常驻服务的近实时轮询，降低触发访问验证与耗电。
 *
 * AUTO 模式在距离上次完整同步超过 30 分钟时自动升级为 FULL。
 */
object SyncRunner {
    enum class SyncMode { AUTO, FULL, LIGHT }

    sealed class Outcome {
        data object Success : Outcome()
        data object Stale : Outcome()
        data object AuthenticationRequired : Outcome()
        data object RiskControl : Outcome()
        data class Failure(val retryable: Boolean) : Outcome()
    }

    private const val FULL_REFRESH_INTERVAL_MILLIS = 60 * 60 * 1000L
    private const val RISK_CONTROL_COOLDOWN_MILLIS = 30 * 60 * 1000L

    private val mutex = Mutex()

    suspend fun execute(context: Context, mode: SyncMode = SyncMode.AUTO): Outcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val prefs = AppPreferences(appContext)
            val generation = prefs.sourceGeneration
            val isDemo = prefs.demoMode
            if (!isDemo && System.currentTimeMillis() < prefs.riskControlUntil) {
                Log.d("course-sync", "skip sync during risk-control cooldown")
                return@withContext Outcome.Stale
            }
            prefs.syncStartedAt = System.currentTimeMillis()
            prefs.commitIfCurrent(generation) {
                prefs.writeSyncState(SyncStatus.RUNNING, if (isDemo) "正在准备演示数据" else "正在读取学习通")
            }
            try {
                runCatching { WidgetUpdater.refreshAll(appContext) }
                val snapshotStore = SnapshotStore(appContext)
                val previous = if (isDemo) null else snapshotStore.read()
                val fullSync = isDemo || when (mode) {
                    SyncMode.FULL -> true
                    SyncMode.LIGHT -> previous == null
                    SyncMode.AUTO ->
                        previous == null ||
                            System.currentTimeMillis() - prefs.lastFullSyncAt > FULL_REFRESH_INTERVAL_MILLIS
                }
                val snapshot = if (isDemo) {
                    SampleData.snapshot()
                } else {
                    val repository = PlatformRepository(appContext)
                    if (fullSync) repository.fetch() else repository.fetchLight(previous!!)
                }
                coroutineContext.ensureActive()
                if (!isDemo) snapshotStore.write(snapshot)
                val store = MarkdownStore(appContext)
                val committed = prefs.commitIfCurrent(generation) {
                    store.write(MarkdownDocument.render(snapshot))
                }
                if (!committed) {
                    return@withContext Outcome.Stale
                }
                if (!isDemo && fullSync) prefs.lastFullSyncAt = System.currentTimeMillis()
                val warning = prefs.exportUri?.let { address ->
                    runCatching { store.exportTo(Uri.parse(address)) }.exceptionOrNull()
                        ?.let { "，外部 Markdown 写入失败，请重新选择文档" }
                } ?: ""
                prefs.commitIfCurrent(generation) {
                    prefs.writeSyncState(
                        SyncStatus.SUCCESS,
                        (if (isDemo) "演示数据 · 登录学习通后同步真实课程" else "学习通数据已更新") + warning,
                        snapshot.fetchedAt.toEpochMilli(),
                    )
                }
                Log.d("course-sync", "sync ok (${if (fullSync) "full" else "light"}): ${snapshot.courses.size} courses, ${snapshot.tasks.size} tasks")
                Outcome.Success
            } catch (error: CancellationException) {
                prefs.commitIfCurrent(generation) {
                    prefs.writeSyncState(SyncStatus.IDLE, "同步已取消，保留上次文档")
                }
                throw error
            } catch (_: AuthenticationRequired) {
                val committed = prefs.commitIfCurrent(generation) {
                    prefs.writeSyncState(SyncStatus.AUTH_REQUIRED, "请登录课程平台，保留上次文档")
                }
                if (committed) Outcome.AuthenticationRequired else Outcome.Stale
            } catch (_: RiskControlRequired) {
                Log.w("course-sync", "risk control triggered, pausing auto sync for a while")
                prefs.riskControlUntil = System.currentTimeMillis() + RISK_CONTROL_COOLDOWN_MILLIS
                val committed = prefs.commitIfCurrent(generation) {
                    prefs.writeSyncState(
                        SyncStatus.AUTH_REQUIRED,
                        "课程平台要求访问验证：请打开 设置 → 网页登录 / 访问验证 完成验证；自动同步已暂停 30 分钟",
                    )
                }
                runCatching { RealtimeSyncService.pause(appContext) }
                if (committed) Outcome.RiskControl else Outcome.Stale
            } catch (error: Exception) {
                Log.w("course-sync", "sync failed: ${error.message}")
                val message = when (error) {
                    is IOException -> "网络或文件读取失败，保留上次文档"
                    else -> "同步失败：${error.message?.take(100) ?: "未知错误"}，保留上次文档"
                }
                val committed = prefs.commitIfCurrent(generation) {
                    prefs.writeSyncState(SyncStatus.ERROR, message)
                }
                if (committed) Outcome.Failure(error is IOException) else Outcome.Stale
            } finally {
                runCatching { WidgetUpdater.refreshAll(appContext) }
            }
        }
    }
}
