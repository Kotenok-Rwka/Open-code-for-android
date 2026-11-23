package com.pocketcode.accounts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Токены аккаунтов хранятся зашифрованными ключом из Android Keystore (AES-GCM). */
object AccountStore {
    private const val PREFS = "pocketcode_accounts"
    private const val ALIAS = "pocketcode_accounts_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val data = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val (iv, data) = stored.split(":", limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(ctx: Context, kind: AccountKind): String? =
        prefs(ctx).getString("token_" + kind.name, null)?.let { decrypt(it) }?.takeIf { it.isNotBlank() }

    fun email(ctx: Context, kind: AccountKind): String? = prefs(ctx).getString("email_" + kind.name, null)

    fun save(ctx: Context, kind: AccountKind, token: String, email: String? = null) {
        val e = prefs(ctx).edit().putString("token_" + kind.name, encrypt(token))
        if (email != null) e.putString("email_" + kind.name, email)
        e.apply()
    }

    fun remove(ctx: Context, kind: AccountKind) {
        prefs(ctx).edit().remove("token_" + kind.name).remove("email_" + kind.name).apply()
    }
}
