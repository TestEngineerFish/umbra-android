package com.geek.umbra.net

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * OIDC（Casdoor）授权码 + PKCE(S256) —— 对应 iOS Networking/OidcRules.swift + Features/Auth/OidcWebAuth.swift。
 * 公开客户端，不带 client_secret。浏览器用 Custom Tabs，回调 `umbra://auth/callback` 由 MainActivity 的 intent-filter 收。
 */
object OidcRules {
    const val REDIRECT = "umbra://auth/callback"
    const val DEFAULT_SCOPE = "openid profile email"

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun rand(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

    data class Pkce(val verifier: String, val challenge: String, val state: String)

    fun newPkce(): Pkce {
        val v = b64url(rand(32))
        val ch = b64url(MessageDigest.getInstance("SHA-256").digest(v.toByteArray(Charsets.US_ASCII)))
        return Pkce(v, ch, b64url(rand(16)))
    }

    private fun isLoopback(host: String) = host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"

    private fun origin(u: URI): String {
        val s = u.scheme.lowercase(); val h = u.host.lowercase()
        val p = if (u.port > 0) u.port else if (s == "https") 443 else 80
        return "$s://$h:$p"
    }

    /**
     * 端点校验：issuer 与各端点必须 https（回环地址可 http），端点 origin 必须等于 issuer origin，不得带 userinfo。
     * 防被劫持的服务端把授权码和刷新令牌导到别处（iOS OidcRules.swift:308-348）。
     */
    fun validate(c: AuthConfig): Boolean {
        val iss = c.issuer?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        fun ok(u: URI?): Boolean {
            u ?: return false
            val s = u.scheme?.lowercase() ?: return false
            val h = u.host ?: return false
            if (u.userInfo != null) return false
            return s == "https" || (s == "http" && isLoopback(h.lowercase()))
        }
        if (!ok(iss)) return false
        val eps = listOfNotNull(c.authorization_endpoint, c.token_endpoint)
        if (eps.size < 2 || c.client_id.isNullOrBlank()) return false
        return eps.all { e ->
            val u = runCatching { URI(e) }.getOrNull()
            ok(u) && origin(u!!) == origin(iss)
        }
    }

    fun authorizeUrl(c: AuthConfig, p: Pkce): String {
        val q = linkedMapOf(
            "client_id" to c.client_id!!, "response_type" to "code", "redirect_uri" to REDIRECT,
            "scope" to (c.scope?.takeIf { it.isNotBlank() } ?: DEFAULT_SCOPE), "state" to p.state,
            "code_challenge" to p.challenge, "code_challenge_method" to "S256",
        )
        val sep = if (c.authorization_endpoint!!.contains('?')) "&" else "?"
        return c.authorization_endpoint + sep + q.entries.joinToString("&") { "${form(it.key)}=${form(it.value)}" }
    }

    /** 回调校验：scheme umbra / host auth / path /callback，state 一致且非空。返回 code 或 null。 */
    fun codeFrom(callback: String, expectedState: String): String? {
        val u = runCatching { URI(callback) }.getOrNull() ?: return null
        if (u.scheme != "umbra" || u.host != "auth" || u.path != "/callback") return null
        val q = (u.rawQuery ?: "").split('&').mapNotNull {
            val i = it.indexOf('='); if (i < 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
        if (expectedState.isEmpty() || q["state"] != expectedState) return null
        return q["code"]?.takeIf { it.isNotEmpty() }
    }

    /** 表单编码只保留 `A-Za-z0-9-._~`。 */
    fun form(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-._~")) sb.append(ch)
            else sb.append('%').append("%02X".format(c))
        }
        return sb.toString()
    }
}

/** 登录时存下的那份 IdP 配置。续期只用它。 */
@Serializable
data class OidcClient(val issuer: String, val client_id: String, val token_endpoint: String, val scope: String = OidcRules.DEFAULT_SCOPE)

/** 令牌端点调用。另起一个不走统一出口的客户端，超时 20 秒。 */
class OidcTokens {
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()

    sealed interface Result {
        data class Ok(val access: String, val refresh: String?) : Result
        data object Rejected : Result
        /** IdP 5xx / 网络不通 = 连不上，**不是拒了**，不能因此登出。 */
        data object Unreachable : Result
    }

    suspend fun exchange(c: OidcClient, code: String, verifier: String): Result = post(
        c.token_endpoint,
        mapOf("grant_type" to "authorization_code", "client_id" to c.client_id, "code" to code,
            "redirect_uri" to OidcRules.REDIRECT, "code_verifier" to verifier),
    )

    suspend fun refresh(c: OidcClient, refreshToken: String): Result = post(
        c.token_endpoint,
        mapOf("grant_type" to "refresh_token", "client_id" to c.client_id, "refresh_token" to refreshToken, "scope" to c.scope),
    )

    private suspend fun post(url: String, form: Map<String, String>): Result = withContext(Dispatchers.IO) {
        val body = form.entries.joinToString("&") { "${OidcRules.form(it.key)}=${OidcRules.form(it.value)}" }
        val req = Request.Builder().url(url).header("Accept", "application/json")
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        try {
            client.newCall(req).execute().use { r ->
                if (r.code >= 500) return@withContext Result.Unreachable
                if (r.code !in 200..299) return@withContext Result.Rejected
                val o = runCatching { UmbraJson.parseToJsonElement(r.body.string()) as JsonObject }.getOrNull()
                    ?: return@withContext Result.Rejected
                val at = o.str("access_token") ?: return@withContext Result.Rejected
                Result.Ok(at, o.str("refresh_token"))
            }
        } catch (_: IOException) {
            Result.Unreachable
        }
    }

}
