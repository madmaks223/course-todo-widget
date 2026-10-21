package com.course.todo.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.tabs.TabLayout
import com.course.todo.R
import com.course.todo.core.DeadlineRules
import com.course.todo.core.MarkdownDocument
import com.course.todo.core.MarkdownRow
import com.course.todo.core.TaskSortOrder
import com.course.todo.core.TodoOrdering
import com.course.todo.storage.AppPreferences
import com.course.todo.storage.MarkdownStore
import com.course.todo.data.PlatformLoginService
import com.course.todo.data.PlatformSession
import com.course.todo.storage.SyncStatus
import com.course.todo.sync.SyncScheduler
import com.course.todo.sync.RealtimeSyncService
import com.course.todo.widget.TodoWidgetProvider
import com.course.todo.widget.WidgetUpdater
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var preferences: AppPreferences
    private lateinit var markdown: MarkdownStore
    private lateinit var palette: Palette
    private var selectedTab = 0
    private var rendering = false
    private var realtimeSwitch: SwitchCompat? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val visibleRefresh = object : Runnable {
        override fun run() {
            if (!isStarted) return
            if (selectedTab == 0) render()
            WidgetUpdater.refreshAll(this@MainActivity)
            if (!preferences.demoMode && !preferences.realtimeSyncEnabled) {
                SyncScheduler.requestLight(this@MainActivity)
            }
            mainHandler.postDelayed(this, LIVE_REFRESH_INTERVAL_MS)
        }
    }
    private var isStarted = false
    private val fileExecutor = Executors.newSingleThreadExecutor()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { if (!isFinishing && !rendering) render() }
    }

    private val exportDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val flags = result.data!!.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        fileExecutor.execute {
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
                markdown.exportTo(uri)
                preferences.exportUri = uri.toString()
                runOnUiThread { toast("Markdown 已导出") }
            } catch (error: Exception) {
                runOnUiThread { toast("导出失败：${error.message ?: "无法写入文档"}") }
            }
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            enableRealtimeSync()
        } else {
            preferences.realtimeSyncEnabled = false
            realtimeSwitch?.let { setRealtimeSwitch(it, false) }
            RealtimeSyncService.stop(this)
            SyncScheduler.requestNow(this)
            toast("允许通知后才能开启后台实时更新")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(this)
        markdown = MarkdownStore(this)
        selectedTab = savedInstanceState?.getInt("selectedTab")
            ?: if (intent.getBooleanExtra("show_settings", false)) 1 else 0
        SyncScheduler.schedule(this)
        if (preferences.demoMode && markdown.read().isNullOrBlank()) SyncScheduler.requestNow(this)
    }

    override fun onStart() {
        super.onStart()
        isStarted = true
        preferences.registerListener(preferenceListener)
        render()
        mainHandler.removeCallbacks(visibleRefresh)
        mainHandler.postDelayed(visibleRefresh, LIVE_REFRESH_INTERVAL_MS)
        if (!preferences.demoMode) {
            if (preferences.realtimeSyncEnabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    RealtimeSyncService.start(this)
                }
            }
            else SyncScheduler.requestLight(this)
            if (preferences.realtimeSyncEnabled && !preferences.batteryOptimizationPrompted) {
                mainHandler.postDelayed({ requestBatteryOptimizationExemption() }, 800)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("show_settings", false)) {
            if (selectedTab != 1) findViewById<ScrollView>(R.id.main_content)?.scrollTo(0, 0)
            selectedTab = 1
            render()
        }
    }

    override fun onStop() {
        isStarted = false
        mainHandler.removeCallbacks(visibleRefresh)
        preferences.unregisterListener(preferenceListener)
        super.onStop()
    }

    override fun onDestroy() {
        fileExecutor.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selectedTab", selectedTab)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        rendering = true
        val previousScroll = findViewById<ScrollView>(R.id.main_content)?.scrollY ?: 0
        palette = ThemePalette.resolveApp(this, preferences.theme, preferences.widgetStyle)
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (preferences.theme != ThemeStyle.DARK &&
            (palette.background and 0xFFFFFF) > 0x777777
        ) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0

        val root = column().apply {
            fitsSystemWindows = true
            setBackgroundColor(palette.background)
        }
        val header = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
            background = surfaceDrawable(palette.surface, palette.border, 18)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), dp(14), dp(16), dp(10))
            }
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            contentDescription = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
        val headerCopy = column()
        headerCopy.addView(label(getString(R.string.app_name), 20f, true))
        headerCopy.addView(label(if (preferences.demoMode) "演示数据 · 课程与截止时间" else "课程平台 · 课程与截止时间", 12f).apply {
            setTextColor(palette.secondary)
            setPadding(0, dp(4), 0, 0)
        })
        header.addView(headerCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton(R.drawable.ic_refresh, "刷新").apply {
            id = R.id.refresh_button
            setOnClickListener { SyncScheduler.requestNow(this@MainActivity) }
        })
        root.addView(header)
        root.addView(statusSection())

        val tabs = TabLayout(this).apply {
            id = R.id.main_tabs
            setBackgroundColor(palette.background)
            setTabTextColors(palette.secondary, palette.accent)
            setSelectedTabIndicatorColor(palette.accent)
            addTab(newTab().setText("待办"), selectedTab == 0)
            addTab(newTab().setText("设置"), selectedTab == 1)
        }
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        root.addView(divider())
        val scroll = ScrollView(this).apply {
            id = R.id.main_content
            isFillViewport = true
            clipToPadding = false
        }
        fun showSelectedTab() {
            scroll.removeAllViews()
            scroll.addView(if (selectedTab == 0) taskSection() else settingsSection())
        }
        showSelectedTab()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedTab = tab.position
                showSelectedTab()
                scroll.scrollTo(0, 0)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        scroll.post { scroll.scrollTo(0, previousScroll) }
        rendering = false
    }

    private fun statusSection(): View {
        val state = preferences.readSyncState()
        val status = when (state.status) {
            SyncStatus.IDLE -> "等待同步"
            SyncStatus.RUNNING -> "正在同步"
            SyncStatus.SUCCESS -> "同步完成"
            SyncStatus.ERROR -> "同步失败"
            SyncStatus.AUTH_REQUIRED -> "需要登录课程平台"
        }
        return column().apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = surfaceDrawable(palette.surface, palette.border, 14)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), 0, dp(16), dp(8))
            }
            addView(label("${if (preferences.demoMode) "演示数据" else "课程平台"} · $status", 14f).apply {
                id = R.id.sync_status
                setTextColor(if (state.status == SyncStatus.ERROR || state.status == SyncStatus.AUTH_REQUIRED)
                    0xFFB44F40.toInt() else palette.accent)
            })
            if (state.status == SyncStatus.ERROR || state.status == SyncStatus.AUTH_REQUIRED ||
                state.status == SyncStatus.SUCCESS && state.message.contains("写入失败")) {
                if (state.message.isNotBlank()) addView(label(state.message, 12f).apply { setTextColor(palette.secondary) })
            }
            addView(label(if (state.lastSuccessEpochMillis > 0)
                "上次更新 ${SimpleDateFormat("MM月dd日 HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date(state.lastSuccessEpochMillis))}"
            else "尚未更新", 12f).apply {
                id = R.id.sync_time
                setTextColor(palette.secondary)
            })
        }
    }

    private fun taskSection(): View {
        val document = markdown.read().orEmpty()
        val now = Instant.now()
        val rows = DeadlineRules.filterVisible(
            MarkdownDocument.parse(document), now, hideCompleted = preferences.hideCompleted,
        )
        val alertStyle = preferences.deadlineAlertStyle
        val alertColors = DeadlineAlertPalette.resolve(palette.background, alertStyle)
        val displayRows = TodoOrdering.build(rows, preferences.taskSortOrder)
        val urgentCount = displayRows.filterIsInstance<TodoOrdering.DisplayRow.Task>()
            .count { DeadlineRules.isUrgent(it.row, now) }
        val showTimeline = preferences.widgetStyle == WidgetStyle.PAPER_TIMELINE ||
            alertStyle == DeadlineAlertStyle.TIMELINE
        val taskPointRows = rows.filter { it.isTaskPoint }
        return column().apply {
            id = R.id.task_list
            setPadding(dp(20), dp(8), dp(20), dp(24))
            addView(label("未完成任务", 13f, true).apply {
                setTextColor(palette.accent)
                setPadding(0, dp(8), 0, dp(2))
            })
            if (urgentCount > 0) addView(label("$urgentCount 项临期（24 小时内或剩余不足 30%）", 13f, true).apply {
                setTextColor(alertColors.color)
                setPadding(0, dp(8), 0, dp(4))
            })
            val isDemoDocument = document.lineSequence().firstOrNull()?.contains("演示") == true
            if (document.isNotBlank() && isDemoDocument != preferences.demoMode) {
                addView(label("当前文档：${if (isDemoDocument) "演示数据" else "课程平台数据"}", 12f).apply {
                    setTextColor(palette.secondary)
                    setPadding(0, dp(8), 0, 0)
                })
            }
            var visibleTasks = 0
            displayRows.forEach { item ->
                when (item) {
                    is TodoOrdering.DisplayRow.Heading -> addView(label(item.text, 17f, true).apply {
                        setPadding(0, dp(20), 0, dp(10))
                    })
                    is TodoOrdering.DisplayRow.Task -> {
                        addView(taskRowView(item.row, now, alertStyle, alertColors, showTimeline))
                        addView(divider())
                        visibleTasks++
                    }
                }
            }
            if (visibleTasks == 0 && taskPointRows.isEmpty()) addView(label("暂无待办", 16f).apply {
                gravity = Gravity.CENTER
                setTextColor(palette.secondary)
                setPadding(0, dp(56), 0, dp(56))
            })
            // 任务点长期存在，固定放在作业、考试、分组任务之后。
            if (taskPointRows.isNotEmpty()) {
                addView(label(DeadlineRules.TASK_POINT_HEADING, 15f, true).apply {
                    setTextColor(palette.text)
                    setPadding(0, dp(24), 0, dp(2))
                })
                addView(label("长期学习进度，不计入作业/考试", 12f).apply {
                    setTextColor(palette.secondary)
                    setPadding(0, 0, 0, dp(6))
                })
                taskPointRows.forEach { row ->
                    addView(row().apply {
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(38)
                        addView(
                            label(row.text, 14f).apply { setTextColor(palette.text) },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                    })
                    addView(divider())
                }
            }
        }
    }

    private fun taskRowView(
        item: MarkdownRow,
        now: Instant,
        style: DeadlineAlertStyle,
        colors: DeadlineAlertColors,
        showTimeline: Boolean,
    ): View {
        val urgent = DeadlineRules.isUrgent(item, now)
        val card = urgent && style == DeadlineAlertStyle.CARD_CAPSULE
        val tinted = urgent && style == DeadlineAlertStyle.PROGRESS
        val task = row().apply {
            gravity = Gravity.TOP
            minimumHeight = dp(62)
            val side = if (card || tinted) dp(10) else 0
            setPadding(side, dp(10), side, dp(10))
            if (card) background = surfaceDrawable(colors.background, colors.border, 10)
            if (tinted) background = surfaceDrawable(colors.background, colors.background, 10)
            if (card || tinted) layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        }
        if (showTimeline) {
            task.addView(FrameLayout(this).apply {
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.widget_timeline_line)
                    setColorFilter(if (urgent) colors.color else palette.border)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    leftMargin = dp(10)
                })
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.widget_timeline_dot)
                    setColorFilter(
                        when {
                            urgent -> colors.color
                            item.checked == true -> palette.secondary
                            else -> palette.accent
                        },
                    )
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(dp(11), dp(11)).apply {
                    leftMargin = dp(5)
                    topMargin = dp(17)
                })
            }, LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            task.addView(CheckBox(this).apply {
                isChecked = item.checked == true
                isClickable = false
                isFocusable = false
                buttonTintList = ColorStateList.valueOf(
                    when {
                        urgent -> colors.color
                        isChecked -> palette.secondary
                        else -> palette.accent
                    },
                )
                contentDescription = if (isChecked) "已完成" else "未完成"
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
            }, LinearLayout.LayoutParams(dp(36), dp(34)))
        }
        val emphasize = urgent && style != DeadlineAlertStyle.SOFT
        val texts = column()
        texts.addView(label(item.text, 15f, emphasize).apply {
            setTextColor(
                when {
                    emphasize -> colors.color
                    item.checked == true -> palette.secondary
                    else -> palette.text
                },
            )
        })
        if (!item.detail.isNullOrBlank()) texts.addView(label(item.detail!!, 12f).apply {
            setTextColor(if (urgent) colors.color else palette.secondary)
            setPadding(0, dp(5), 0, 0)
        })
        DeadlineRules.urgencyLabel(item, now)?.let { tag ->
            texts.addView(label(tag, 12f, true).apply {
                setTextColor(colors.color)
                setPadding(0, dp(5), 0, 0)
            })
        }
        task.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        when {
            card -> DeadlineRules.remainingPercent(item, now)?.let { percent ->
                task.addView(capsuleView("剩 $percent%", colors))
            }
            tinted -> task.addView(
                progressView(DeadlineRules.elapsedPercent(item, now) ?: 70, colors),
            )
        }
        return task
    }

    private fun capsuleView(text: String, colors: DeadlineAlertColors): TextView = TextView(this).apply {
        this.text = text
        textSize = scaled(12f)
        setTextColor(colors.capsuleText)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = surfaceDrawable(colors.capsuleBackground, colors.capsuleBackground, 999)
        contentDescription = "剩余时间 $text"
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(6)
            marginStart = dp(8)
        }
    }

    private fun progressView(percent: Int, colors: DeadlineAlertColors): View = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(colors.progressTrack)
        }
        addView(View(this@MainActivity).apply {
            setBackgroundColor(colors.progressFill)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (dp(46) * percent.coerceIn(0, 100) / 100).coerceAtLeast(dp(4)),
            Gravity.BOTTOM,
        ))
        contentDescription = "时间已过 $percent%"
        layoutParams = LinearLayout.LayoutParams(dp(8), dp(46)).apply {
            topMargin = dp(7)
            marginStart = dp(10)
        }
    }

    private fun settingsSection(): View = column().apply {
        setPadding(dp(20), dp(8), dp(20), dp(24))
        addView(sectionTitle("数据来源"))
        addView(RadioGroup(this@MainActivity).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(this@MainActivity).apply {
                id = R.id.source_mode_demo
                text = "演示"
                textSize = scaled(15f)
                setTextColor(palette.text)
                buttonTintList = controlTint()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(RadioButton(this@MainActivity).apply {
                id = R.id.source_mode_live
                text = "课程平台"
                textSize = scaled(15f)
                setTextColor(palette.text)
                buttonTintList = controlTint()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            check(if (preferences.demoMode) R.id.source_mode_demo else R.id.source_mode_live)
            setOnCheckedChangeListener { _, checkedId ->
                val demo = checkedId == R.id.source_mode_demo
                if (preferences.demoMode != demo) {
                    preferences.demoMode = demo
                    if (demo) disableRealtimeSync()
                    SyncScheduler.schedule(this@MainActivity)
                    SyncScheduler.requestNow(this@MainActivity)
                }
            }
        })
        if (!preferences.demoMode) {
            val state = preferences.readSyncState()
            val isLiveDocument = markdown.read()?.lineSequence()?.firstOrNull()?.let { !it.contains("演示") } == true
            val sessionSaved = PlatformSession(this@MainActivity).hasSession()
            val account = preferences.lastAccount
            val loginState = when {
                !sessionSaved -> "登录状态：未登录"
                account.isNotBlank() -> "登录状态：已登录 · $account"
                else -> "登录状态：已登录"
            }
            addView(label(loginState, 12f).apply {
                setTextColor(if (sessionSaved) palette.accent else palette.secondary)
                setPadding(0, dp(6), 0, 0)
            })
            addView(label(if (sessionSaved) "登录会话保存在本机，重启 App 无需重新登录" else "登录会话仅保存在本机，不保存密码", 12f).apply {
                setTextColor(palette.secondary)
                setPadding(0, 0, 0, dp(8))
            })
            if (sessionSaved && !(state.status == SyncStatus.SUCCESS && isLiveDocument)) {
                addView(label("最近一次同步待验证，可尝试重新同步", 12f).apply {
                    setTextColor(palette.secondary)
                    setPadding(0, 0, 0, dp(8))
                })
            }
            addView(commandButton("登录课程平台（账号密码 / 验证码）", R.id.login_button) {
                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
            })
            addView(commandButton("网页登录 / 访问验证", R.id.login_web_button) {
                startActivity(
                    Intent(this@MainActivity, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_WEB_MODE, true),
                )
            })
            addView(commandButton("退出登录", R.id.logout_button) {
                disableRealtimeSync()
                PlatformLoginService(this@MainActivity).logout()
                preferences.invalidateSession()
                CookieManager.getInstance().removeAllCookies {
                    preferences.invalidateSession()
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                    SyncScheduler.requestNow(this@MainActivity)
                    toast("已退出登录")
                }
            })
        }
        addView(SwitchCompat(this@MainActivity).apply {
            id = R.id.auto_sync_switch
            text = "后台自动同步（约每 15 分钟）"
            textSize = scaled(15f)
            setTextColor(palette.text)
            isChecked = preferences.autoSyncEnabled
            minimumHeight = dp(56)
            thumbTintList = controlTint()
            setOnCheckedChangeListener { _, checked ->
                preferences.autoSyncEnabled = checked
                SyncScheduler.schedule(this@MainActivity)
                WidgetUpdater.refreshAll(this@MainActivity)
            }
        })
        if (!preferences.demoMode) {
            addView(SwitchCompat(this@MainActivity).apply {
                id = R.id.realtime_sync_switch
                text = "常驻后台每 2 分钟检查"
                textSize = scaled(15f)
                setTextColor(palette.text)
                isChecked = preferences.realtimeSyncEnabled
                minimumHeight = dp(56)
                thumbTintList = controlTint()
                realtimeSwitch = this
                setOnCheckedChangeListener { _, enabled -> onRealtimeSwitchChanged(this, enabled) }
            })
            addView(label("登录后默认开启；通知中可停止。需要通知权限，会增加耗电与流量。", 12f).apply {
                setTextColor(palette.secondary)
                setPadding(0, 0, 0, dp(8))
            })
            addView(commandButton("加入电池优化白名单（提高后台存活）", R.id.battery_optimization_button) {
                requestBatteryOptimizationExemption()
            })
            addView(label("App 支持开机自启与常驻前台服务；加入白名单后系统更不容易杀后台。", 12f).apply {
                setTextColor(palette.secondary)
                setPadding(0, 0, 0, dp(8))
            })
        } else {
            realtimeSwitch = null
        }
        addView(divider())
        addView(sectionTitle("外观"))
        addView(label("主题", 14f).apply { setTextColor(palette.secondary) })
        val themeNames = listOf("跟随系统", "浅色", "深色", "薄荷", "纸张")
        addView(Spinner(this@MainActivity).apply {
            id = R.id.theme_spinner
            minimumHeight = dp(52)
            backgroundTintList = ColorStateList.valueOf(palette.accent)
            adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, themeNames) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(palette.text)
                        textSize = scaled(15f)
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                    }
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (super.getDropDownView(position, convertView, parent) as TextView).apply {
                        setBackgroundColor(palette.surface)
                        setTextColor(palette.text)
                        textSize = scaled(15f)
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                    }
            }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(preferences.theme.ordinal)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val theme = ThemeStyle.entries[position]
                    if (preferences.theme != theme) {
                        preferences.theme = theme
                        WidgetUpdater.refreshAll(this@MainActivity)
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        })
        addView(label("截止警示方案", 14f).apply {
            setTextColor(palette.secondary)
            setPadding(0, dp(16), 0, 0)
        })
        val alertStyleNames = listOf("柔和警示", "红边卡片 · 倒计时胶囊", "时间线警戒", "整行浅红 · 时间进度条")
        addView(Spinner(this@MainActivity).apply {
            id = R.id.deadline_alert_spinner
            minimumHeight = dp(52)
            backgroundTintList = ColorStateList.valueOf(palette.accent)
            adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, alertStyleNames) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(palette.text)
                        textSize = scaled(15f)
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                    }
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (super.getDropDownView(position, convertView, parent) as TextView).apply {
                        setBackgroundColor(palette.surface)
                        setTextColor(palette.text)
                        textSize = scaled(15f)
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                    }
            }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(preferences.deadlineAlertStyle.ordinal)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val style = DeadlineAlertStyle.entries.getOrNull(position) ?: DeadlineAlertStyle.SOFT
                    if (preferences.deadlineAlertStyle != style) {
                        preferences.deadlineAlertStyle = style
                        WidgetUpdater.refreshAll(this@MainActivity)
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        })
        addView(label("任务排序", 14f).apply {
            setTextColor(palette.secondary)
            setPadding(0, dp(16), 0, 0)
        })
        val sortOrderNames = listOf("按课程排序", "按截止日期排序（近的在上）")
        addView(Spinner(this@MainActivity).apply {
            id = R.id.task_sort_spinner
            minimumHeight = dp(52)
            backgroundTintList = ColorStateList.valueOf(palette.accent)
            adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, sortOrderNames) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(palette.text)
                        textSize = scaled(15f)
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                    }
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (super.getDropDownView(position, convertView, parent) as TextView).apply {
                        setBackgroundColor(palette.surface)
                        setTextColor(palette.text)
                        textSize = scaled(15f)
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                    }
            }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(preferences.taskSortOrder.ordinal)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val order = TaskSortOrder.entries.getOrNull(position) ?: TaskSortOrder.COURSE
                    if (preferences.taskSortOrder != order) {
                        preferences.taskSortOrder = order
                        WidgetUpdater.refreshAll(this@MainActivity)
                        render()
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        })
        addView(label("小组件美化方案", 14f).apply {
            setTextColor(palette.secondary)
            setPadding(0, dp(16), 0, 0)
        })
        val widgetStyleNames = listOf("Material 清透", "Notion 专注", "纸张时间线")
        addView(Spinner(this@MainActivity).apply {
            id = R.id.widget_style_spinner
            minimumHeight = dp(52)
            backgroundTintList = ColorStateList.valueOf(palette.accent)
            adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, widgetStyleNames) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(palette.text)
                        textSize = scaled(15f)
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                    }
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (super.getDropDownView(position, convertView, parent) as TextView).apply {
                        setBackgroundColor(palette.surface)
                        setTextColor(palette.text)
                        textSize = scaled(15f)
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                    }
            }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(preferences.widgetStyle.ordinal)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val style = WidgetStyle.entries[position]
                    if (preferences.widgetStyle != style) {
                        preferences.widgetStyle = style
                        WidgetUpdater.refreshAll(this@MainActivity)
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        })
        val fontLabel = label("字体大小 ${preferences.fontScalePercent}%", 14f).apply {
            id = R.id.font_scale_value
            setPadding(0, dp(14), 0, 0)
        }
        addView(fontLabel)
        val fontRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        fontRow.addView(SeekBar(this@MainActivity).apply {
            id = R.id.font_seekbar
            max = 16
            progress = (preferences.fontScalePercent - 80) / 5
            progressTintList = ColorStateList.valueOf(palette.accent)
            thumbTintList = ColorStateList.valueOf(palette.accent)
            contentDescription = "字体大小"
            minimumHeight = dp(48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    fontLabel.text = "字体大小 ${80 + progress * 5}%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    preferences.fontScalePercent = 80 + (seekBar?.progress ?: 4) * 5
                    WidgetUpdater.refreshAll(this@MainActivity)
                }
            })
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        fontRow.addView(iconButton(android.R.drawable.ic_menu_revert, "重置字体大小").apply {
            id = R.id.font_reset_button
            setOnClickListener {
                preferences.fontScalePercent = 100
                WidgetUpdater.refreshAll(this@MainActivity)
            }
        })
        addView(fontRow)
        addView(CheckBox(this@MainActivity).apply {
            id = R.id.hide_completed_checkbox
            text = "App 中隐藏已完成"
            textSize = scaled(15f)
            setTextColor(palette.text)
            buttonTintList = controlTint()
            isChecked = preferences.hideCompleted
            minimumHeight = dp(52)
            setOnCheckedChangeListener { _, checked ->
                preferences.hideCompleted = checked
                WidgetUpdater.refreshAll(this@MainActivity)
            }
        })
        addView(divider())
        addView(sectionTitle("文档与小组件"))
        addView(commandButton("导出 Markdown", R.id.export_markdown_button) {
            exportDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/markdown"
                putExtra(Intent.EXTRA_TITLE, "课程待办.md")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            })
        })
        if (preferences.exportUri != null) addView(label("已连接导出文档", 12f).apply {
            setTextColor(palette.secondary)
            setPadding(dp(4), dp(4), 0, dp(8))
        })
        addView(commandButton("分享 Markdown", R.id.share_markdown_button) {
            val text = markdown.read()
            if (text.isNullOrBlank()) toast("暂无文档") else startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_SUBJECT, "课程待办")
                putExtra(Intent.EXTRA_TEXT, text)
            }, "分享 Markdown"))
        })
        val widgetManager = AppWidgetManager.getInstance(this@MainActivity)
        addView(commandButton(if (widgetManager.isRequestPinAppWidgetSupported) "添加桌面小组件" else "当前桌面不支持快捷添加",
            R.id.pin_widget_button) {
            widgetManager.requestPinAppWidget(ComponentName(this@MainActivity, TodoWidgetProvider::class.java), null, null)
        }.apply { isEnabled = widgetManager.isRequestPinAppWidgetSupported })
        addView(divider())
        addView(sectionTitle("关于"))
        addView(commandButton("开源许可", R.id.licenses_button) { showLicensesDialog() })
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        addView(label("课程待办 $versionName · 个人非官方项目", 12f).apply {
            setTextColor(palette.secondary)
            setPadding(0, dp(2), 0, dp(8))
        })
    }

    private fun showLicensesDialog() {
        val licenseText = runCatching {
            resources.openRawResource(R.raw.third_party_licenses).bufferedReader().use { it.readText() }
        }.getOrDefault("开源许可信息暂缺，见仓库 THIRD_PARTY_LICENSES.md")
        val content = TextView(this).apply {
            text = licenseText
            textSize = scaled(12f)
            setTextColor(palette.text)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this)
            .setTitle("开源许可")
            .setView(scroll)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun sectionTitle(text: String): TextView = label(text, 13f, true).apply {
        setTextColor(palette.accent)
        setPadding(0, dp(22), 0, dp(10))
    }

    private fun onRealtimeSwitchChanged(toggle: SwitchCompat, enabled: Boolean) {
        if (!enabled) {
            disableRealtimeSync()
            return
        }
        val isLiveDocument = markdown.read()?.lineSequence()?.firstOrNull()?.let { !it.contains("演示") } == true
        if (preferences.demoMode || preferences.readSyncState().status != SyncStatus.SUCCESS || !isLiveDocument) {
            setRealtimeSwitch(toggle, false)
            toast("请先登录并成功同步课程平台数据")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        enableRealtimeSync()
    }

    private fun setRealtimeSwitch(toggle: SwitchCompat, checked: Boolean) {
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = checked
        toggle.setOnCheckedChangeListener { _, enabled -> onRealtimeSwitchChanged(toggle, enabled) }
    }

    private fun enableRealtimeSync() {
        preferences.realtimeSyncEnabled = true
        RealtimeSyncService.start(this)
        WidgetUpdater.refreshAll(this)
    }

    private fun disableRealtimeSync() {
        preferences.realtimeSyncEnabled = false
        RealtimeSyncService.stop(this)
        WidgetUpdater.refreshAll(this)
    }

    private fun commandButton(text: String, stableId: Int, action: () -> Unit): View =
        AppCompatButton(this).apply {
            id = stableId
            this.text = text
            textSize = scaled(14f)
            isAllCaps = false
            gravity = Gravity.CENTER
            setTextColor(palette.accent)
            minimumHeight = dp(48)
            background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), palette.border)
            }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            setOnClickListener { action() }
        }

    private fun iconButton(icon: Int, description: String) = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(palette.accent)
        contentDescription = description
        tooltipText = description
        val value = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
        setBackgroundResource(value.resourceId)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }

    private fun surfaceDrawable(fill: Int, border: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), border)
        }

    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = scaled(size)
        setTextColor(palette.text)
        setLineSpacing(dp(2).toFloat(), 1.05f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun controlTint() = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(palette.accent, palette.secondary))

    private fun divider() = View(this).apply {
        setBackgroundColor(palette.border)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun scaled(size: Float) = size * preferences.fontScalePercent / 100f
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun toast(message: String) { if (!isFinishing) Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    companion object { private const val LIVE_REFRESH_INTERVAL_MS = 60_000L }

    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager?.isIgnoringBatteryOptimizations(packageName) == true) {
            toast("已在电池优化白名单中")
            return
        }
        preferences.batteryOptimizationPrompted = true
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(request) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { toast("请在系统设置中允许 App 后台运行") }
        }
    }

}
