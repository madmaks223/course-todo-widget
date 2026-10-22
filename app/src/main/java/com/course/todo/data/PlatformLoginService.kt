package com.course.todo.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 登录辅助：网页登录成功后校验会话、同步 Cookie、退出登录。
 *
 * App 不再提供账号密码 / 验证码的原生登录表单，账号信息只在平台的登录网页内输入。
 */
class PlatformLoginService(context: Context) {
    private val session = PlatformSession(context)
    private val http = PlatformHttpClient(session)

    /**
     * 校验当前 Cookie 是否仍能访问学习通个人空间。
     *
     * 注意：未登录时 i.chaoxing.com/base 会 302 到 passport 登录页，HTTP 仍是 200，
     * 只看 HTTP 状态会误判为已登录，所以必须检查最终地址。
     */
    suspend fun verifyCurrentSession(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val response = http.get(HOME_URL)
            response.code == 200 && response.finalUrl.startsWith(HOME_URL, ignoreCase = true)
        }.getOrDefault(false)
    }

    /** 网页登录成功后，把 WebView Cookie 同步到统一存储。 */
    fun adoptWebViewCookies() {
        session.copyFromWebViewCookieManager()
    }

    fun logout() {
        session.clear()
    }

    private companion object {
        const val HOME_URL = "https://i.chaoxing.com/base"
    }
}
