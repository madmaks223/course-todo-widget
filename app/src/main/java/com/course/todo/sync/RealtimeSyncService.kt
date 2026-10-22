package com.course.todo.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.course.todo.R
import com.course.todo.storage.AppPreferences
import com.course.todo.widget.WidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RealtimeSyncService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private var timeTickReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerTimeTickReceiver()
    }

    /** 每分钟跟随系统时间广播刷新小组件，让剩余时间/临期标记/未同步警告尽量接近实时。 */
    private fun registerTimeTickReceiver() {
        if (timeTickReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_TIME_TICK) {
                    Log.d("xxt-widget", "time tick -> refresh widget")
                    runCatching { WidgetUpdater.refreshAll(this@RealtimeSyncService) }
                }
            }
        }
        timeTickReceiver = receiver
        runCatching {
            ContextCompat.registerReceiver(
                this, receiver, IntentFilter(Intent.ACTION_TIME_TICK), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val prefs = AppPreferences(this)
        if (intent?.action == ACTION_STOP) {
            prefs.realtimeSyncEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!prefs.realtimeSyncEnabled || prefs.demoMode) {
            prefs.realtimeSyncEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (pollingJob?.isActive != true) startPolling()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        timeTickReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
            timeTickReceiver = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startPolling() {
        pollingJob = serviceScope.launch {
            var failureCount = 0
            while (isActive) {
                val prefs = AppPreferences(this@RealtimeSyncService)
                if (!prefs.realtimeSyncEnabled || prefs.demoMode) {
                    prefs.realtimeSyncEnabled = false
                    stopSelf()
                    break
                }

                val wakeLock = acquireWakeLock()
                val outcome = try {
                    SyncRunner.execute(this@RealtimeSyncService, SyncRunner.SyncMode.AUTO)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    SyncRunner.Outcome.Failure(retryable = false)
                } finally {
                    runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
                }
                val nextDelay = when (outcome) {
                    SyncRunner.Outcome.Success -> {
                        failureCount = 0
                        POLL_INTERVAL_MILLIS
                    }
                    SyncRunner.Outcome.Stale -> POLL_INTERVAL_MILLIS
                    SyncRunner.Outcome.AuthenticationRequired -> {
                        prefs.realtimeSyncEnabled = false
                        stopSelf()
                        break
                    }
                    SyncRunner.Outcome.RiskControl -> {
                        // 冷却期内由 SyncRunner 跳过同步；服务先停下，等用户完成验证或冷却结束。
                        stopSelf()
                        break
                    }
                    is SyncRunner.Outcome.Failure -> {
                        failureCount = (failureCount + 1).coerceAtMost(MAX_FAILURE_EXPONENT)
                        val exponent = (failureCount - 1).coerceAtLeast(0)
                        (POLL_INTERVAL_MILLIS * (1L shl exponent)).coerceAtMost(MAX_BACKOFF_MILLIS)
                    }
                }
                delay(nextDelay)
            }
        }
    }


    /** 同步期间持有短时唤醒锁，降低进入 Doze 后网络请求被中断的概率。 */
    private fun acquireWakeLock(): PowerManager.WakeLock? = runCatching {
        val powerManager = getSystemService(PowerManager::class.java) ?: return null
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xxt:realtime-sync").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        }
    }.getOrNull()

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "课程平台实时同步",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "后台检查课程平台课程与待办更新"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, RealtimeSyncService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("课程平台实时同步")
            .setContentText("每 2 分钟检查课程与待办")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_notification, "关闭实时同步", stopPendingIntent)
            .build()
    }

    companion object {
        private const val ACTION_STOP = "com.course.todo.sync.STOP_REALTIME_SYNC"
        private const val CHANNEL_ID = "realtime_sync"
        private const val NOTIFICATION_ID = 3001
        private const val STOP_REQUEST_CODE = 3002
        private const val POLL_INTERVAL_MILLIS = 120_000L
        private const val MAX_BACKOFF_MILLIS = 15 * 60_000L
        private const val MAX_FAILURE_EXPONENT = 8
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 120_000L

        fun start(context: Context) {
            val appContext = context.applicationContext
            val prefs = AppPreferences(appContext)
            if (!prefs.demoMode && System.currentTimeMillis() < prefs.riskControlUntil) return
            if (prefs.demoMode) {
                prefs.realtimeSyncEnabled = false
                appContext.stopService(Intent(appContext, RealtimeSyncService::class.java))
                return
            }
            prefs.realtimeSyncEnabled = true
            ContextCompat.startForegroundService(appContext, Intent(appContext, RealtimeSyncService::class.java))
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            AppPreferences(appContext).realtimeSyncEnabled = false
            appContext.stopService(Intent(appContext, RealtimeSyncService::class.java))
        }

        /** 访问验证冷却：只停服务，保留「常驻」开关，冷却结束后 App 启动或开机广播会重新拉起。 */
        fun pause(context: Context) {
            context.applicationContext.stopService(Intent(context.applicationContext, RealtimeSyncService::class.java))
        }
    }
}
