package com.course.todo.core

import java.net.URI
import java.util.Locale

sealed class LoginNavigationDecision {
    data object Allow : LoginNavigationDecision()
    data class UpgradeToHttps(val url: String) : LoginNavigationDecision()
    data object Block : LoginNavigationDecision()
}

object LoginNavigationPolicy {
    const val LOGIN_URL = "https://passport2.chaoxing.com/login?refer=https%3A%2F%2Fi.chaoxing.com%2F&newversion=true"

    fun decide(url: String, isMainFrame: Boolean = true, method: String = "GET"): LoginNavigationDecision {
        val uri = try {
            URI(url)
        } catch (_: IllegalArgumentException) {
            return LoginNavigationDecision.Block
        } catch (_: java.net.URISyntaxException) {
            return LoginNavigationDecision.Block
        }
        if (uri.isOpaque || uri.rawUserInfo != null) return LoginNavigationDecision.Block
        val host = uri.host?.lowercase(Locale.ROOT) ?: return LoginNavigationDecision.Block
        if (!isOfficialHost(host)) return LoginNavigationDecision.Block
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "https" -> LoginNavigationDecision.Allow
            "http" -> {
                if (!isMainFrame || !method.equals("GET", ignoreCase = true)) return LoginNavigationDecision.Block
                // Replace only the parsed scheme so escaped query and fragment values retain their meaning.
                LoginNavigationDecision.UpgradeToHttps("https" + uri.toASCIIString().substring(uri.scheme.length))
            }
            else -> LoginNavigationDecision.Block
        }
    }

    private fun isOfficialHost(host: String): Boolean =
        host == "chaoxing.com" || host.endsWith(".chaoxing.com")
}
