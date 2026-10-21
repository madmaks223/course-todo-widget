package com.course.todo.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.children
import com.course.todo.R
import com.course.todo.core.LoginNavigationDecision
import com.course.todo.core.LoginNavigationPolicy
import com.course.todo.data.PlatformLoginService
import com.course.todo.storage.AppPreferences
import com.course.todo.sync.SyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 课程平台登录页。
 *
 * 默认是手机原生登录：
 * - 账号密码：本机 AES 加密后提交；
 * - 手机验证码：先获取短信验证码，再加密提交。
 * 登录成功后 Cookie 写入统一存储，同步模块直接复用。
 *
 * 原生登录遇到机构账号、二次验证等情况时，可切换到「网页登录」兜底。
 */
class LoginActivity : AppCompatActivity() {
    private enum class Mode { NATIVE, WEB }

    private enum class NativeTab { PASSWORD, SMS }

    private lateinit var preferences: AppPreferences
    private lateinit var palette: Palette
    private val loginScope = MainScope()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var verification: Job? = null
    private var countdownJob: Job? = null

    private var mode = Mode.NATIVE
    private var nativeTab = NativeTab.PASSWORD

    private lateinit var contentHost: FrameLayout
    private lateinit var nativeTabsRow: LinearLayout
    private lateinit var accountInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var smsCodeInput: EditText
    private lateinit var passwordGroup: LinearLayout
    private lateinit var smsGroup: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var submitButton: AppCompatButton
    private lateinit var sendCodeButton: TextView

    private var webView: WebView? = null
    private var webStatus: TextView? = null
    private var webProgress: ProgressBar? = null
    private var desktopMode = false
    private var mobileUserAgent: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(this)
        palette = ThemePalette.resolveApp(this, preferences.theme)
        mode = when {
            intent.getBooleanExtra(EXTRA_WEB_MODE, false) -> Mode.WEB
            savedInstanceState?.getString("mode") == Mode.WEB.name -> Mode.WEB
            else -> Mode.NATIVE
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(palette.background)
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(8))
        }
        header.addView(ImageButton(this).apply {
            id = R.id.login_close_button
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            imageTintList = ColorStateList.valueOf(palette.text)
            setBackgroundColor(palette.background)
            contentDescription = "关闭登录"
            tooltipText = "关闭登录"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply {
            text = "课程平台登录"
            textSize = 18f * preferences.appFontScalePercent / 100f
            setTextColor(palette.text)
            maxLines = 2
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)
        contentHost = FrameLayout(this)
        root.addView(contentHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        renderMode()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("mode", mode.name)
        webView?.let { it.saveState(outState) }
        outState.putBoolean("desktop_mode", desktopMode)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        countdownJob?.cancel()
        loginScope.cancel()
        webView?.let { view ->
            view.stopLoading()
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        webView = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 模式切换

    private fun renderMode() {
        contentHost.removeAllViews()
        if (mode == Mode.NATIVE) renderNative() else renderWeb()
    }

    private fun switchMode(target: Mode) {
        if (mode == target) return
        mode = target
        renderMode()
    }

    // ------------------------------------------------------------------ 原生登录

    private fun renderNative() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(20))
        }
        nativeTabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                nativeTabView("账号密码", NativeTab.PASSWORD),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                nativeTabView("手机验证码", NativeTab.SMS),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        root.addView(nativeTabsRow)
        root.addView(divider())

        accountInput = editText("手机号 / 账号").apply {
            id = R.id.login_account
            inputType = InputType.TYPE_CLASS_TEXT
        }
        passwordInput = editText("密码").apply {
            id = R.id.login_password
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        smsCodeInput = editText("短信验证码").apply {
            id = R.id.login_sms_code
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        // 手机号/账号输入框两个 Tab 共用，放在 Tab 内容之外，避免同一个 View 有两个父容器。
        root.addView(fieldLabel("手机号 / 账号"))
        root.addView(accountInput)
        passwordGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(fieldLabel("密码"))
            addView(passwordInput)
        }
        smsGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(fieldLabel("短信验证码"))
            val codeRow = LinearLayout(this@LoginActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            codeRow.addView(smsCodeInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            sendCodeButton = TextView(this@LoginActivity).apply {
                id = R.id.login_send_code
                text = "获取验证码"
                textSize = 13f * preferences.appFontScalePercent / 100f
                setTextColor(palette.accent)
                setPadding(dp(12), dp(12), dp(4), dp(12))
                setOnClickListener { sendSmsCode() }
            }
            codeRow.addView(sendCodeButton)
            addView(codeRow)
        }
        root.addView(passwordGroup)
        root.addView(smsGroup)
        statusText = TextView(this).apply {
            id = R.id.login_status
            textSize = 13f * preferences.appFontScalePercent / 100f
            setTextColor(palette.secondary)
            setPadding(0, dp(12), 0, dp(4))
            visibility = View.GONE
        }
        root.addView(statusText)
        submitButton = AppCompatButton(this).apply {
            id = R.id.login_submit
            text = "登 录"
            textSize = 16f * preferences.appFontScalePercent / 100f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            backgroundTintList = ColorStateList.valueOf(palette.accent)
            minimumHeight = dp(52)
            setOnClickListener { submit() }
        }
        root.addView(
            submitButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(18)
            },
        )
        root.addView(TextView(this).apply {
            text = "遇到问题？使用网页登录"
            textSize = 13f * preferences.appFontScalePercent / 100f
            setTextColor(palette.accent)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(8))
            setOnClickListener { switchMode(Mode.WEB) }
        })
        root.addView(TextView(this).apply {
            text = "两种登录方式都走课程平台官方接口；密码与验证码只在本机 AES 加密后提交，不保存、不写日志。" +
                "登录状态会保存在本机，重启 App 无需重新登录，除非退出登录或平台会话过期。" +
                "登录成功后 Cookie 直接用于同步课程、作业、考试与任务点。"
            textSize = 11.5f * preferences.appFontScalePercent / 100f
            setTextColor(palette.secondary)
            setPadding(0, dp(10), 0, 0)
            setLineSpacing(0f, 1.35f)
        })
        contentHost.addView(root)
        applyNativeTab()
    }

    private fun nativeTabView(label: String, tab: NativeTab): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(TextView(this@LoginActivity).apply {
            text = label
            textSize = 14f * preferences.appFontScalePercent / 100f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            setTextColor(if (nativeTab == tab) palette.accent else palette.secondary)
            setTypeface(typeface, if (nativeTab == tab) Typeface.BOLD else Typeface.NORMAL)
        })
        addView(
            View(this@LoginActivity).apply {
                setBackgroundColor(if (nativeTab == tab) palette.accent else 0x00000000)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)),
        )
        setOnClickListener {
            if (nativeTab != tab) {
                nativeTab = tab
                applyNativeTab()
            }
        }
    }

    private fun applyNativeTab() {
        nativeTabsRow.children.forEachIndexed { index, child ->
            val selected = if (index == 0) nativeTab == NativeTab.PASSWORD else nativeTab == NativeTab.SMS
            val column = child as LinearLayout
            val text = column.getChildAt(0) as TextView
            text.setTextColor(if (selected) palette.accent else palette.secondary)
            text.setTypeface(text.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            column.getChildAt(1).setBackgroundColor(if (selected) palette.accent else 0x00000000)
        }
        passwordGroup.visibility = if (nativeTab == NativeTab.PASSWORD) View.VISIBLE else View.GONE
        smsGroup.visibility = if (nativeTab == NativeTab.SMS) View.VISIBLE else View.GONE
        statusText.visibility = View.GONE
    }

    private fun submit() {
        val service = PlatformLoginService(this)
        val account = accountInput.text.toString().trim()
        val password = passwordInput.text.toString()
        val code = smsCodeInput.text.toString().trim()
        if (account.isBlank()) {
            showNativeStatus("请输入手机号 / 账号", error = true)
            return
        }
        if (nativeTab == NativeTab.PASSWORD && password.isBlank()) {
            showNativeStatus("请输入密码", error = true)
            return
        }
        if (nativeTab == NativeTab.SMS && code.isBlank()) {
            showNativeStatus("请输入短信验证码", error = true)
            return
        }
        setNativeBusy(true)
        showNativeStatus(if (nativeTab == NativeTab.PASSWORD) "正在登录课程平台…" else "正在校验验证码…", error = false)
        loginScope.launch {
            val result = try {
                if (nativeTab == NativeTab.PASSWORD) {
                    service.loginByPassword(account, password)
                } else {
                    service.loginBySms(account, code)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                PlatformLoginService.LoginResult(false, "登录失败：${error.message ?: "请稍后重试"}")
            }
            setNativeBusy(false)
            if (result.ok) onLoginSucceeded(result.message) else showNativeStatus(result.message, error = true)
        }
    }

    private fun sendSmsCode() {
        val phone = accountInput.text.toString().trim()
        if (!phone.matches(Regex("\\d{11}"))) {
            showNativeStatus("请输入 11 位手机号", error = true)
            return
        }
        sendCodeButton.isEnabled = false
        showNativeStatus("正在发送验证码…", error = false)
        loginScope.launch {
            val result = PlatformLoginService(this@LoginActivity).sendSmsCode(phone)
            if (!result.ok) {
                sendCodeButton.isEnabled = true
                showNativeStatus(result.message, error = true)
                return@launch
            }
            showNativeStatus(result.message, error = false)
            startCountdown()
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = loginScope.launch {
            for (seconds in 60 downTo 1) {
                sendCodeButton.text = "重新获取 ${seconds}s"
                delay(1_000)
            }
            sendCodeButton.text = "获取验证码"
            sendCodeButton.isEnabled = true
        }
    }

    private fun setNativeBusy(busy: Boolean) {
        submitButton.isEnabled = !busy
        submitButton.text = if (busy) "正在登录…" else "登 录"
        accountInput.isEnabled = !busy
        passwordInput.isEnabled = !busy
        smsCodeInput.isEnabled = !busy
    }

    private fun showNativeStatus(message: String, error: Boolean) {
        statusText.text = message
        statusText.setTextColor(if (error) 0xFFB3261E.toInt() else palette.accent)
        statusText.visibility = View.VISIBLE
    }

    private fun onLoginSucceeded(message: String) {
        showNativeStatus(message, error = false)
        rememberLoginAccount(accountInput.text.toString().trim())
        preferences.demoMode = false
        preferences.hasLoggedIn = true
        preferences.realtimeSyncEnabled = true
        preferences.invalidateSession()
        SyncScheduler.schedule(this)
        SyncScheduler.requestNow(this)
        mainHandler.postDelayed({ finish() }, 350)
    }

    /** 记录脱敏后的账号，用于设置页显示“已登录”；不保存密码。 */
    private fun rememberLoginAccount(raw: String) {
        if (raw.isBlank()) return
        val masked = if (raw.length >= 8) raw.take(3) + "****" + raw.takeLast(4) else "已登录账号"
        preferences.lastAccount = masked
        preferences.lastLoginAt = System.currentTimeMillis()
    }

    // ------------------------------------------------------------------ 网页登录兜底

    private fun renderWeb() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        webStatus = TextView(this).apply {
            id = R.id.login_page_status
            textSize = 13f * preferences.appFontScalePercent / 100f
            setTextColor(palette.accent)
            setPadding(dp(8), dp(4), dp(8), dp(6))
            visibility = View.GONE
        }
        webProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
        }
        webView = WebView(this).apply {
            id = R.id.login_webview
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            mobileUserAgent = settings.userAgentString
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    webProgress?.progress = newProgress
                    webProgress?.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                }

                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        showWebStatus("登录网页运行异常，可重新加载或返回账号密码登录")
                    }
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    handleNavigation(request.url.toString(), request.isForMainFrame, request.method)

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    handleNavigation(url)

                override fun onPageFinished(view: WebView, url: String) {
                    CookieManager.getInstance().flush()
                    val landing = android.net.Uri.parse(url)
                    if (landing.host == "i.chaoxing.com" && !landing.path.orEmpty().contains("error")) {
                        verifyWebLogin(closeOnSuccess = false)
                    }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame || request.url.path.orEmpty().endsWith(".js")) {
                        showWebStatus("登录页面加载失败（${error.errorCode}），请重新加载")
                    }
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame && response.statusCode >= 500) {
                        showWebStatus("登录页面服务异常（HTTP ${response.statusCode}）")
                    }
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    showWebStatus("登录页面证书校验失败，已阻止不安全的连接")
                }
            }
        }
        root.addView(webStatus)
        root.addView(webProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        actions.addView(AppCompatButton(this).apply {
            text = "完成登录"
            id = R.id.login_done_button
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            backgroundTintList = ColorStateList.valueOf(palette.accent)
            setOnClickListener { verifyWebLogin(closeOnSuccess = true) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        actions.addView(AppCompatButton(this).apply {
            text = "重新加载"
            id = R.id.login_reload_button
            isAllCaps = false
            setOnClickListener { reloadWeb() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        root.addView(actions)
        root.addView(TextView(this).apply {
            text = "返回账号密码登录"
            textSize = 13f * preferences.appFontScalePercent / 100f
            setTextColor(palette.accent)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { switchMode(Mode.NATIVE) }
        })
        contentHost.addView(root)
        applyWebUserAgent()
        webView?.loadUrl(LoginNavigationPolicy.LOGIN_URL)
    }

    private fun handleNavigation(url: String, isMainFrame: Boolean = true, method: String = "GET"): Boolean =
        when (val decision = LoginNavigationPolicy.decide(url, isMainFrame, method)) {
            LoginNavigationDecision.Allow -> false
            is LoginNavigationDecision.UpgradeToHttps -> {
                webView?.loadUrl(decision.url)
                true
            }
            LoginNavigationDecision.Block -> {
                if (isMainFrame) showWebStatus("此登录跳转暂不支持，请返回账号密码登录")
                true
            }
        }

    private fun applyWebUserAgent() {
        webView?.settings?.userAgentString = if (desktopMode) DESKTOP_USER_AGENT else mobileUserAgent
    }

    private fun reloadWeb() {
        verification?.cancel()
        webView?.stopLoading()
        applyWebUserAgent()
        webView?.loadUrl(LoginNavigationPolicy.LOGIN_URL)
    }

    private fun verifyWebLogin(closeOnSuccess: Boolean) {
        if (verification?.isActive == true) return
        CookieManager.getInstance().flush()
        verification = loginScope.launch {
            showWebStatus("正在验证课程平台会话")
            try {
                val service = PlatformLoginService(this@LoginActivity)
                service.adoptWebViewCookies()
                if (service.verifyCurrentSession()) {
                    showWebStatus("课程平台登录已验证")
                    if (closeOnSuccess) {
                        preferences.demoMode = false
                        preferences.hasLoggedIn = true
                        preferences.realtimeSyncEnabled = true
                        rememberLoginAccount(accountInput.text.toString().trim())
                        preferences.invalidateSession()
                        SyncScheduler.schedule(this@LoginActivity)
                        SyncScheduler.requestNow(this@LoginActivity)
                        setResult(RESULT_OK, Intent())
                        finish()
                    }
                } else {
                    showWebStatus("尚未检测到登录会话：请先在网页里完成登录/滑块验证，再点「完成登录」")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                showWebStatus("登录会话验证失败，请检查网络后重试")
            }
        }
    }

    private fun showWebStatus(message: String) {
        webStatus?.text = message
        webStatus?.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ 通用视图

    private fun editText(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        textSize = 15f * preferences.appFontScalePercent / 100f
        setTextColor(palette.text)
        setHintTextColor(palette.secondary)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(palette.surface)
            setStroke(dp(1), palette.border)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        maxLines = 1
    }

    private fun fieldLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f * preferences.appFontScalePercent / 100f
        setTextColor(palette.secondary)
        setPadding(dp(2), dp(14), dp(2), dp(6))
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(palette.border)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()

    companion object {
        const val EXTRA_WEB_MODE = "com.course.todo.extra.WEB_LOGIN"
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
