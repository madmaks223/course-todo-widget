package com.course.todo.core

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 课程平台网页登录页使用的 AES-CBC/PKCS7 加密。
 *
 * 加密方式与平台网页登录页一致：AES-128-CBC，
 * IV 与密钥相同，输出 Base64。密码、手机号、短信验证码都用同一个算法。
 */
object PlatformCrypto {
    const val TRANSFER_KEY: String = "u2oh6Vu^HWe4_AES"

    fun encryptForLogin(plain: String, key: String = TRANSFER_KEY): String {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        require(keyBytes.size == 16) { "课程平台登录密钥必须为 16 字节" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(keyBytes),
        )
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }
}
