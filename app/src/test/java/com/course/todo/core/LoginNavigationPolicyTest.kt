package com.course.todo.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class LoginNavigationPolicyTest {
    @Test fun officialHttpsPagesAreAllowed() {
        listOf(
            "https://chaoxing.com/",
            "https://passport2.chaoxing.com/mlogin?loginType=2",
            "https://mooc1.chaoxing.com/work/task-list",
            "HTTPS://PASSPORT2.CHAOXING.COM/login"
        ).forEach { assertEquals(it, LoginNavigationDecision.Allow, LoginNavigationPolicy.decide(it)) }
        assertEquals(LoginNavigationDecision.Allow, LoginNavigationPolicy.decide("https://captcha.chaoxing.com/", false))
    }

    @Test fun oldOfficialHttpGetRedirectsAreUpgraded() {
        assertEquals(
            LoginNavigationDecision.UpgradeToHttps("https://i.mooc.chaoxing.com"),
            LoginNavigationPolicy.decide("http://i.mooc.chaoxing.com")
        )
        assertEquals(
            LoginNavigationDecision.UpgradeToHttps("https://i.chaoxing.com/"),
            LoginNavigationPolicy.decide("HTTP://i.chaoxing.com/", method = "get")
        )
    }

    @Test fun upgradingRetainsEscapedPathQueryAndFragment() {
        val http = "http://passport2.chaoxing.com/a%2Fb?refer=https%3A%2F%2Fi.chaoxing.com%2F%3Fx%3D1%26y%3D2&value=a%2Bb#step%202"
        assertEquals(
            LoginNavigationDecision.UpgradeToHttps(http.replaceFirst("http:", "https:")),
            LoginNavigationPolicy.decide(http)
        )
    }

    @Test fun loginEntryProvidesAnExplicitHttpsReturnAddress() {
        val login = URI(LoginNavigationPolicy.LOGIN_URL)
        assertEquals("https", login.scheme)
        assertEquals("passport2.chaoxing.com", login.host)
        val query = login.rawQuery.split('&').associate { part ->
            val pair = part.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
        }
        assertEquals("https://i.chaoxing.com/", query["refer"])
        assertEquals("true", query["newversion"])
        assertEquals(LoginNavigationDecision.Allow, LoginNavigationPolicy.decide(LoginNavigationPolicy.LOGIN_URL))
    }

    @Test fun nonOfficialAndLookalikeHostsAreBlocked() {
        listOf(
            "https://example.com/",
            "https://chaoxing.com.example.com/",
            "https://notchaoxing.com/",
            "https://chaoxing.example/",
            "https://chaoxing.com./",
            "http://example.com/"
        ).forEach { assertBlocked(it) }
    }

    @Test fun userInfoAndUnsafeSchemesAreBlocked() {
        listOf(
            "https://name:password@passport2.chaoxing.com/",
            "https://@passport2.chaoxing.com/",
            "http://name@chaoxing.com/",
            "https://chaoxing.com@example.com/",
            "javascript:alert(1)",
            "data:text/html,hello",
            "intent://passport2.chaoxing.com/login#Intent;end",
            "file:///chaoxing.com/login",
            "ftp://chaoxing.com/login"
        ).forEach { assertBlocked(it) }
    }

    @Test fun insecureSubframesAndNonGetRequestsAreBlocked() {
        assertEquals(LoginNavigationDecision.Block, LoginNavigationPolicy.decide("http://chaoxing.com/", false))
        listOf("POST", "HEAD", "PUT", "DELETE").forEach { method ->
            assertEquals(LoginNavigationDecision.Block, LoginNavigationPolicy.decide("http://chaoxing.com/", method = method))
        }
    }

    @Test fun malformedAndRelativeUrlsAreBlocked() {
        listOf(
            "",
            "/mlogin",
            "//passport2.chaoxing.com/login",
            "https://",
            "https:///passport2.chaoxing.com/login",
            "https://passport2.chaoxing.com/a b",
            "https://passport2.chaoxing.com/%zz",
            "https://passport2.chaoxing.com\\@example.com/",
            "https://passport2.chaoxing.com\n.example.com/"
        ).forEach { assertBlocked(it) }
    }

    private fun assertBlocked(url: String) {
        assertEquals(LoginNavigationDecision.Block, LoginNavigationPolicy.decide(url))
    }
}
