package com.course.todo.data

import android.content.Context
import android.util.AtomicFile
import android.webkit.CookieManager
import java.io.File
import org.json.JSONObject

/**
 * 课程平台登录凭证（Cookie）存储。
 *
 * 关键点：平台网页按域名发送 Cookie，部分 Cookie 只应在登录域名下发送，
 * 发到其它域名会触发访问验证。因此这里保存每个 Cookie 的 Domain/host-only 归属，
 * 并按目标域名组装请求头。
 *
 * - 原生登录、网页登录兜底、同步请求共用同一份 Cookie；
 * - 旧版本（只存 name → value）会自动迁移。
 */
class PlatformSession(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("platform_session", Context.MODE_PRIVATE)

    /** 登录状态同时存一份本地文件，进程重启/SharedPreferences 异常时可恢复。 */
    private val stateFile = File(appContext.filesDir, "login_state.json")

    /** 同步并发抓取时多个响应可能同时写 Cookie，避免读改写丢失。 */
    private val writeLock = Any()

    private data class Entry(
        val name: String,
        val value: String,
        val host: String? = null,
        val domain: String? = null,
    )

    fun hasSession(): Boolean = readEntries().isNotEmpty()

    /** 读取单个 Cookie 值（只读，用于取学校 fid 等非敏感配置）。 */
    fun cookieValue(name: String): String =
        readEntries().values.firstOrNull { it.name == name }?.value.orEmpty()



    /** 按目标域名组装 Cookie 头：host-only 只发给来源域名，domain Cookie 发给子域。 */
    fun cookieHeaderFor(host: String): String? {
        val target = host.lowercase()
        val matched = readEntries().values.filter { entry ->
            when {
                entry.domain != null -> target == entry.domain || target.endsWith(".${entry.domain}")
                entry.host != null -> target == entry.host.lowercase()
                else -> false
            }
        }
            // 这类 Cookie 只允许回到登录域名，避免其它接口请求被平台拒绝。
            .filterNot { entry -> entry.name == RISK_FINGERPRINT_COOKIE && !target.startsWith("passport") }
        // 同名 Cookie 只保留一个：host-only 比 domain 更精确，优先使用。
        val deduped = LinkedHashMap<String, Entry>()
        matched.forEach { entry ->
            val existing = deduped[entry.name]
            if (existing == null || (existing.host == null && entry.host != null)) {
                deduped[entry.name] = entry
            }
        }
        val header = deduped.values.joinToString("; ") { entry -> "${entry.name}=${entry.value}" }
        return header.ifBlank { null }
    }

    /** 兼容旧调用：不区分域名时的保守默认值（只用于非同步场景的调试）。 */
    fun cookieHeader(): String? = cookieHeaderFor("mooc1.chaoxing.com")

    /** 采集响应里的 Set-Cookie，保留 Domain / host-only 归属。 */
    fun capture(sourceHost: String, setCookieHeaders: List<String>) = synchronized(writeLock) {
        if (setCookieHeaders.isEmpty()) return
        val host = sourceHost.lowercase()
        val entries = readEntries().toMutableMap()
        var changed = false
        setCookieHeaders.forEach { header ->
            val parts = header.split(';')
            val first = parts.firstOrNull().orEmpty()
            val name = first.substringBefore('=', "").trim()
            val value = first.substringAfter('=', "").trim()
            if (name.isBlank() || value.isBlank()) return@forEach
            val domainAttribute = parts.drop(1)
                .map { it.trim() }
                .firstOrNull { it.startsWith("Domain=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim()
                ?.trimStart('.')
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }
            val key = if (domainAttribute != null) "$name|.$domainAttribute" else "$name|@$host"
            entries[key] = if (domainAttribute != null) {
                Entry(name, value, domain = domainAttribute)
            } else {
                Entry(name, value, host = host)
            }
            changed = true
        }
        if (changed) writeEntries(entries)
    }

    /**
     * 网页登录兜底 / 完成访问验证后，把 WebView 里的 Cookie 复制进统一存储。
     * CookieManager 不返回 Domain 属性，这里按已知的 passport 会话 Cookie 名单区分域名。
     */
    fun copyFromWebViewCookieManager() = synchronized(writeLock) {
        val manager = CookieManager.getInstance()
        val entries = readEntries().toMutableMap()
        var changed = false
        WEB_VIEW_URLS.forEach { url ->
            val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return@forEach
            val cookieString = manager.getCookie(url) ?: return@forEach
            cookieString.split(';').forEach { pair ->
                val name = pair.substringBefore('=', "").trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isBlank() || value.isBlank()) return@forEach
                val passportOnly = name in PASSPORT_HOST_ONLY_COOKIES
                val key = if (passportOnly) "$name|@$host" else "$name|.chaoxing.com"
                val entry = if (passportOnly) Entry(name, value, host = host) else Entry(name, value, domain = "chaoxing.com")
                if (entries[key] != entry) {
                    entries[key] = entry
                    changed = true
                }
            }
        }
        if (changed) writeEntries(entries)
    }

    fun clear() = synchronized(writeLock) {
        prefs.edit().remove(KEY_JAR).apply()
        runCatching { stateFile.delete() }
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
        runCatching { CookieManager.getInstance().flush() }
    }

    private fun readEntries(): Map<String, Entry> {
        var raw = prefs.getString(KEY_JAR, null)
        if (raw.isNullOrBlank()) {
            raw = readBackup()
            if (!raw.isNullOrBlank()) prefs.edit().putString(KEY_JAR, raw).apply()
        }
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            val entries = mutableMapOf<String, Entry>()
            json.keys().forEach { key ->
                when (val value = json.opt(key)) {
                    is JSONObject -> {
                        val name = value.optString("name").ifBlank { key.substringBefore('|') }
                        entries[key] = Entry(
                            name = name,
                            value = value.optString("value"),
                            host = value.optString("host").takeIf { it.isNotBlank() },
                            domain = value.optString("domain").takeIf { it.isNotBlank() },
                        )
                    }
                    // 旧格式：{"cookieName": "cookieValue"}
                    is String -> {
                        val name = key.substringBefore('|')
                        entries[key] = if (name in PASSPORT_HOST_ONLY_COOKIES) {
                            Entry(name, value, host = "passport2.chaoxing.com")
                        } else {
                            Entry(name, value, domain = "chaoxing.com")
                        }
                    }
                }
            }
            entries.filterValues { it.value.isNotBlank() }
        }.getOrDefault(emptyMap())
    }

    private fun writeEntries(entries: Map<String, Entry>) {
        val json = JSONObject()
        entries.forEach { (key, entry) ->
            json.put(
                key,
                JSONObject().apply {
                    put("name", entry.name)
                    put("value", entry.value)
                    entry.host?.let { put("host", it) }
                    entry.domain?.let { put("domain", it) }
                },
            )
        }
        prefs.edit().putString(KEY_JAR, json.toString()).apply()
        runCatching { writeBackup(json.toString()) }
    }

    private fun readBackup(): String? = runCatching {
        if (stateFile.exists()) stateFile.readText().takeIf { it.isNotBlank() } else null
    }.getOrNull()

    private fun writeBackup(content: String) {
        val file = AtomicFile(stateFile)
        val stream = file.startWrite()
        try {
            stream.write(content.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private companion object {
        const val KEY_JAR = "cookie_jar"

        /** 只允许返回登录域名的 Cookie。 */
        const val RISK_FINGERPRINT_COOKIE = "k8s"

        /** 只属于 passport 登录页面的会话 Cookie，不能发给课程接口。 */
        val PASSPORT_HOST_ONLY_COOKIES = setOf(
            "JSESSIONID", "route", "k8s", "k8sexam", "retainlogin", "source",
        )

        val WEB_VIEW_URLS = listOf(
            "https://chaoxing.com",
            "https://i.chaoxing.com",
            "https://i.mooc.chaoxing.com",
            "https://mooc1.chaoxing.com",
            "https://mooc1-api.chaoxing.com",
            "https://passport2.chaoxing.com",
        )
    }
}
