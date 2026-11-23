package com.pocketcode

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** E2E-шифрование синхронизации: пароль → AES-256-GCM. Ключ не передаётся на сервер. */
object SyncCrypto {
    fun isEncrypted(payload: String): Boolean = payload.startsWith("PCENC1:")

    fun key(password: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        repeat(2000) { digest.update(password.toByteArray(Charsets.UTF_8)) }
        return SecretKeySpec(digest.digest(), "AES")
    }

    fun encrypt(plain: String, password: String): String {
        if (password.isBlank()) return plain
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password))
        val iv = cipher.iv
        val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "PCENC1:" + b64(iv) + ":" + b64(enc)
    }

    /** Неверный пароль или повреждённые данные не роняют вызывающий код: возвращаем исходный текст. */
    fun decrypt(payload: String, password: String): String {
        if (!isEncrypted(payload) || password.isBlank()) return payload
        return runCatching {
            val parts = payload.removePrefix("PCENC1:").split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password), GCMParameterSpec(128, unb64(parts[0])))
            String(cipher.doFinal(unb64(parts[1])), Charsets.UTF_8)
        }.getOrDefault(payload)
    }

    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = java.util.Base64.getDecoder().decode(s)
}
