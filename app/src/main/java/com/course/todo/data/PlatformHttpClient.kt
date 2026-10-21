package com.course.todo.data

import android.util.Log
import com.course.todo.core.AuthenticationRequired
import com.course.todo.core.RiskControlRequired
import com.course.todo.core.UpstreamFailure
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 课程平台 HTTP 客户端：只访问 HTTPS 的课程平台域名，自动跟随跳转、按域名维护 Cookie。
 *
 * 与网页版保持一致：POST 表单每个字段都做一次 URL 编码（课程平台的 `refer`
 * 等字段在页面里已经编码过一次，所以这是故意的双重编码）。
 *
 * 访问验证处理：平台要求人工验证时会返回特定状态并跳转到验证页面，
 * 这里统一抛 [RiskControlRequired]，由同步层提示用户完成验证，而不是当成解析失败。
 */
class PlatformHttpClient(private val session: PlatformSession) {
    data class Response(val code: Int, val body: String, val finalUrl: String)

    fun get(
        address: String,
        userAgent: String = DESKTOP_USER_AGENT,
        referer: String = DEFAULT_REFERER,
    ): Response = request(address, userAgent, referer, fields = null)

    fun postForm(
        address: String,
        fields: Map<String, String>,
        userAgent: String = DESKTOP_USER_AGENT,
        referer: String = DEFAULT_REFERER,
    ): Response = request(address, userAgent, referer, fields = fields)

    private fun request(
        address: String,
        userAgent: String,
        referer: String,
        fields: Map<String, String>?,
    ): Response {
        var target = URL(address)
        var formFields = fields
        repeat(MAX_REDIRECTS) {
            validate(target)
            val connection = target.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                connection.setRequestProperty("Referer", referer)
                connection.setRequestProperty("Accept", "text/html,application/json,application/xhtml+xml,*/*;q=0.8")
                session.cookieHeaderFor(target.host)?.let { connection.setRequestProperty("Cookie", it) }
                if (formFields != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty(
                        "Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8",
                    )
                    connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
                    val payload = formFields.entries.joinToString("&") { (name, value) ->
                        "${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
                    }
                    connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                session.capture(target.host, setCookieHeaders(connection))
                Log.d(TAG, "${if (formFields != null) "POST" else "GET"} ${target.host}${target.path} -> $code")
                when {
                    code in REDIRECT_CODES -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw UpstreamFailure("课程平台接口跳转响应缺少地址")
                        val next = resolve(target, location)
                        if (isRiskControl(next.toString())) throw RiskControlRequired()
                        if (isLoginEntry(next)) throw AuthenticationRequired()
                        target = next
                        formFields = null // 浏览器对 302/303 会改用 GET
                    }
                    code == HttpURLConnection.HTTP_UNAUTHORIZED ||
                        code == HttpURLConnection.HTTP_FORBIDDEN -> throw AuthenticationRequired()
                    code == 202 -> throw RiskControlRequired()
                    code !in 200..299 -> throw UpstreamFailure("课程平台接口响应异常（HTTP $code）")
                    else -> {
                        val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                            .removePrefix("\uFEFF")
                        if (isRiskControl(target.toString()) || looksLikeRiskControlPage(text)) {
                            throw RiskControlRequired()
                        }
                        if (looksLikeLoginPage(text)) throw AuthenticationRequired()
                        return Response(code, text, target.toString())
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw UpstreamFailure("课程平台接口跳转次数过多")
    }

    private fun validate(url: URL) {
        val host = url.host.lowercase()
        if (url.protocol != "https" || (host != "chaoxing.com" && !host.endsWith(".chaoxing.com"))) {
            throw UpstreamFailure("课程平台接口跳转至未支持的地址")
        }
    }

    private fun resolve(base: URL, location: String): URL {
        val normalized = if (location.startsWith("http://")) {
            val host = runCatching { URL(location).host }.getOrNull().orEmpty()
            if (host == "chaoxing.com" || host.endsWith(".chaoxing.com")) {
                "https://" + location.removePrefix("http://")
            } else {
                location
            }
        } else {
            location
        }
        return URL(base, normalized)
    }

    private fun isLoginEntry(url: URL): Boolean =
        url.host.startsWith("passport") || url.path.orEmpty().contains("/login")

    private fun isRiskControl(url: String): Boolean = url.contains("antispider", ignoreCase = true)

    private fun looksLikeRiskControlPage(text: String): Boolean =
        text.contains("antispiderShowVerify", ignoreCase = true) ||
            (text.contains("<title>提示页面</title>") && text.length < 20_000)

    private fun looksLikeLoginPage(text: String): Boolean =
        text.contains("<title>用户登录</title>") ||
            text.contains("请重新登录") || text.contains("请先登录") ||
            text.contains("登录已失效") || text.contains("登录超时")

    private fun setCookieHeaders(connection: HttpURLConnection): List<String> =
        connection.headerFields
            .filterKeys { key -> key?.equals("Set-Cookie", ignoreCase = true) == true }
            .values.flatten()

    companion object {
        private const val TAG = "course-http"
        const val DESKTOP_USER_AGENT: String =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val MOBILE_USER_AGENT: String =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/108.0.0.0 Mobile Safari/537.36 com.chaoxing.mobile/ChaoXingStudy_3_6.7.2_android_phone"
        const val DEFAULT_REFERER: String = "https://mooc1.chaoxing.com/"
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 25_000
        private const val MAX_REDIRECTS = 8
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
