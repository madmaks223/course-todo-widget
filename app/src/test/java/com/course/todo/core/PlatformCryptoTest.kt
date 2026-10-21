package com.course.todo.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformCryptoTest {
    @Test
    fun aesCbcMatchesLearningPassLoginJavaScript() {
        // 用课程平台登录页 login.js 的 encryptByAES（AES-128-CBC，key/IV 相同，PKCS7）
        // 对固定输入计算出的期望值，保证 Kotlin 实现与网页一致。
        assertEquals("0QgUbMUJj2usHikiqtb8HQ==", PlatformCrypto.encryptForLogin("123456"))
        assertEquals("xZ9dYlVASzFVINL+G69JXA==", PlatformCrypto.encryptForLogin("test-password"))
        assertEquals("FoUyDorD0DK2FtQXYqRmyw==", PlatformCrypto.encryptForLogin("16606673874"))
    }
}
