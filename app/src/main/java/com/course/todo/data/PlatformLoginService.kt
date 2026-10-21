package com.course.todo.data

import android.content.Context
import com.course.todo.core.AuthenticationRequired
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 手机原生登录：账号密码登录、手机验证码登录。
 *
 * - 密码：手机号与密码在本机 AES-CBC 加密后再提交；
 * - 验证码：先请求短信验证码，再用加密后的验证码登录；
 * - 成功后 Cookie 写入 [PlatformSession]，同步模块直接复用，登录态与网页登录等价。
 */
class PlatformLoginService(context: Context) {
    data class LoginResult(val ok: Boolean, val message: String)

    private val session = PlatformSession(context)
    private val http = PlatformHttpClient(session)

    suspend fun loginByPassword(account: String, password: String): LoginResult =
        withContext(Dispatchers.IO) {
            val name = account.trim()
            if (name.isBlank() || password.isBlank()) return@withContext LoginResult(false, "请输入手机号/账号和密码")
            runCatching {
                http.get(LOGIN_URL, referer = LOGIN_URL)
                val response = http.postForm(
                    "https://passport2.chaoxing.com/fanyalogin",
                    mapOf(
                        "fid" to "-1",
                        "uname" to com.course.todo.core.PlatformCrypto.encryptForLogin(name),
                        "password" to com.course.todo.core.PlatformCrypto.encryptForLogin(password),
                        "refer" to "https%3A%2F%2Fi.chaoxing.com",
                        "t" to "true",
                        "forbidotherlogin" to "0",
                        "validate" to "",
                        "doubleFactorLogin" to "0",
                        "independentId" to "0",
                        "independentNameId" to "0",
                    ),
                    referer = LOGIN_URL,
                )
                parseLoginResponse(response.body)
            }.getOrElse { error -> failure(error) }
        }

    suspend fun sendSmsCode(phone: String): LoginResult = withContext(Dispatchers.IO) {
        val name = phone.trim()
        if (!name.matches(Regex("\\d{11}"))) return@withContext LoginResult(false, "请输入 11 位手机号")
        runCatching {
            http.get(SMS_LOGIN_URL, referer = SMS_LOGIN_URL)
            val response = http.get(
                "https://passport2.chaoxing.com/num/phonecode?phone=$name&code=&type=1&needcode=false" +
                    "&countrycode=86&validate=&fid=-1",
                referer = SMS_LOGIN_URL,
            )
            val json = JSONObject(response.body)
            if (json.optBoolean("result")) {
                LoginResult(true, "验证码已发送，请查看手机短信")
            } else {
                LoginResult(false, json.optString("msg").ifBlank { "验证码发送失败，请稍后重试" })
            }
        }.getOrElse { error -> failure(error) }
    }

    suspend fun loginBySms(phone: String, code: String): LoginResult = withContext(Dispatchers.IO) {
        val name = phone.trim()
        val smsCode = code.trim()
        if (!name.matches(Regex("\\d{11}"))) return@withContext LoginResult(false, "请输入 11 位手机号")
        if (smsCode.isBlank()) return@withContext LoginResult(false, "请输入短信验证码")
        runCatching {
            val encryptedCode = URLEncoder.encode(
                com.course.todo.core.PlatformCrypto.encryptForLogin(smsCode),
                "UTF-8",
            )
            val response = http.postForm(
                "https://passport2.chaoxing.com/fanyaloginbycode",
                mapOf(
                    "fid" to "-1",
                    "uname" to name,
                    "verCode" to encryptedCode,
                    "refer" to "https%3A%2F%2Fi.chaoxing.com",
                    "doubleFactorLogin" to "0",
                    "independentNameId" to "0",
                    "countrycode" to "86",
                ),
                referer = SMS_LOGIN_URL,
            )
            parseLoginResponse(response.body)
        }.getOrElse { error -> failure(error) }
    }

    /** 校验当前 Cookie 是否仍能访问课程平台个人空间。 */
    suspend fun verifyCurrentSession(): Boolean = withContext(Dispatchers.IO) {
        runCatching { http.get("https://i.chaoxing.com/base") }.isSuccess
    }

    /** 网页登录兜底成功后，把 WebView Cookie 同步到统一存储。 */
    fun adoptWebViewCookies() {
        session.copyFromWebViewCookieManager()
    }

    fun logout() {
        session.clear()
    }

    private suspend fun parseLoginResponse(body: String): LoginResult {
        val json = runCatching { JSONObject(body) }.getOrElse {
            return LoginResult(false, "课程平台返回了无法识别的登录结果，请使用网页登录")
        }
        if (!json.optBoolean("status")) {
            val message = listOf("msg2", "mes", "msg")
                .map { json.optString(it) }
                .firstOrNull { it.isNotBlank() }
                ?: "登录失败，请检查账号和密码"
            return LoginResult(false, message)
        }
        if (json.optBoolean("containTwoFactorLogin")) {
            return LoginResult(false, "该账号需要二次验证，请改用网页登录")
        }
        return if (verifyCurrentSession()) {
            LoginResult(true, "登录成功")
        } else {
            LoginResult(false, "课程平台未返回有效登录态，请重试或使用网页登录")
        }
    }

    private fun failure(error: Throwable): LoginResult = when (error) {
        is AuthenticationRequired -> LoginResult(false, "账号、密码或验证码不正确")
        else -> LoginResult(false, "网络错误：${error.message ?: "请稍后重试"}")
    }

    private companion object {
        const val LOGIN_URL: String =
            "https://passport2.chaoxing.com/login?fid=&newversion=true&refer=https%3A%2F%2Fi.chaoxing.com"
        const val SMS_LOGIN_URL: String =
            "https://passport2.chaoxing.com/login?loginType=2&newversion=true&fid=-1" +
                "&refer=https%3A%2F%2Fi.chaoxing.com"
    }
}
