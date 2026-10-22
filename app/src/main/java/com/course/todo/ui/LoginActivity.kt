package com.course.todo.ui

import android.content.res.ColorStateList
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
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
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
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
import kotlinx.coroutines.launch

/**
 * 登录页：只保留网页登录。
 *
 * 账号密码 / 手机验证码 / 扫码都在平台的网页内完成，App 不读取、不保存密码；
 * 登录成功后把 WebView 的 Cookie 同步到本地存储，供同步模块使用。
 */
class LoginActivity : AppCompatActivity() {
    private lateinit var preferences: AppPreferences
    private lateinit var palette: Palette
    private val loginScope = MainScope()
    private var verification: Job? = null
    private var webView: WebView? = null
    private var webStatus: TextView? = null
    private var webProgress: ProgressBar? = null
    private var desktopMode = false
    private var mobileUserAgent: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(this)
        palette = ThemePalette.resolveApp(this, preferences.theme)
        desktopMode = savedInstanceState?.getBoolean("desktop_mode") ?: false

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
            text = "登录学习通"
            textSize = 18f * preferences.appFontScalePercent / 100f
            setTextColor(palette.text)
            maxLines = 2
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        renderWeb(content)
        if (savedInstanceState == null || webView?.restoreState(savedInstanceState) == null) {
            reloadWeb()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.saveState(outState)
        outState.putBoolean("desktop_mode", desktopMode)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        verification?.cancel()
        loginScope.cancel()
        webView?.let { view ->
            view.stopLoading()
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun renderWeb(container: FrameLayout) {
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
                        showWebStatus("登录网页运行异常，可点「重新加载」再试")
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
                    val landing = Uri.parse(url)
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

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        footer.addView(TextView(this).apply {
            text = "切换电脑版"
            id = R.id.login_desktop_toggle
            textSize = 13f * preferences.appFontScalePercent / 100f
            setTextColor(palette.accent)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnClickListener {
                desktopMode = !desktopMode
                text = if (desktopMode) "切换手机版" else "切换电脑版"
                applyWebUserAgent()
                reloadWeb()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(TextView(this).apply {
            text = "登录状态保存在本机，密码不会经过 App"
            textSize = 11f * preferences.appFontScalePercent / 100f
            setTextColor(palette.secondary)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(footer)

        container.addView(root)
        applyWebUserAgent()
    }

    private fun handleNavigation(url: String, isMainFrame: Boolean = true, method: String = "GET"): Boolean =
        when (val decision = LoginNavigationPolicy.decide(url, isMainFrame, method)) {
            LoginNavigationDecision.Allow -> false
            is LoginNavigationDecision.UpgradeToHttps -> {
                webView?.loadUrl(decision.url)
                true
            }
            LoginNavigationDecision.Block -> {
                if (isMainFrame) showWebStatus("此跳转暂不支持，请点「重新加载」后重试")
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
            showWebStatus("正在验证学习通登录状态")
            try {
                val service = PlatformLoginService(this@LoginActivity)
                service.adoptWebViewCookies()
                if (service.verifyCurrentSession()) {
                    showWebStatus("登录成功")
                    if (closeOnSuccess) {
                        preferences.demoMode = false
                        preferences.hasLoggedIn = true
                        preferences.realtimeSyncEnabled = true
                        if (preferences.lastAccount.isBlank()) {
                            preferences.lastAccount = "网页登录账号"
                        }
                        preferences.lastLoginAt = System.currentTimeMillis()
                        preferences.invalidateSession()
                        SyncScheduler.schedule(this@LoginActivity)
                        SyncScheduler.requestNow(this@LoginActivity)
                        setResult(RESULT_OK)
                        finish()
                    }
                } else {
                    showWebStatus("尚未检测到登录状态：请先在网页里完成账号登录 / 验证，再点「完成登录」")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                showWebStatus("登录状态验证失败，请检查网络后重试")
            }
        }
    }

    private fun showWebStatus(message: String) {
        webStatus?.text = message
        webStatus?.visibility = View.VISIBLE
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()

    private companion object {
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
