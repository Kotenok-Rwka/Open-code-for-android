package com.pocketcode.ui

import android.content.Context
import android.content.Intent
import java.security.SecureRandom
import java.util.Base64

/** OAuth 2.0 Authorization Code + PKCE для провайдеров, которые поддерживают вход через браузер. */
object OauthHelper {
    data class Flow(val authorizeUrl: String, val codeVerifier: String, val state: String)

    private fun random(n: Int): String {
        val b = ByteArray(n)
        SecureRandom().nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    fun begin(authorizeEndpoint: String, clientId: String, redirectUri: String, scopes: String): Flow {
        val verifier = random(32)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        )
        val state = random(16)
        val url = authorizeEndpoint + (if (authorizeEndpoint.contains("?")) "&" else "?") +
            "response_type=code&client_id=${java.net.URLEncoder.encode(clientId, "UTF-8")}" +
            "&redirect_uri=${java.net.URLEncoder.encode(redirectUri, "UTF-8")}" +
            "&scope=${java.net.URLEncoder.encode(scopes, "UTF-8")}" +
            "&state=$state&code_challenge=$challenge&code_challenge_method=S256"
        return Flow(url, verifier, state)
    }

    /** Открывает системный браузер: приложение не перехватывает пароли пользователя. */
    fun open(context: Context, flow: Flow) {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(flow.authorizeUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
