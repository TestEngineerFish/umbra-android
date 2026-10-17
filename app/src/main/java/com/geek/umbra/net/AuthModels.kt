package com.geek.umbra.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import java.time.OffsetDateTime

/** JsonObject 的宽松取值。服务端字段有时是数、有时是数字串、有时是 null —— 一个都不许让整条解码失败。 */
fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() ?: it.contentOrNull?.toLongOrNull() }
fun JsonObject.int(k: String): Int? = long(k)?.toInt()
fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.longOrNull?.let { n -> n != 0L } }
fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
fun JsonObject.arr(k: String): List<JsonElement> = runCatching { this[k]?.jsonArray?.toList() }.getOrNull() ?: emptyList()

/**
 * 账号。**必须宽松解码**（iOS AuthUser.swift:115 那次事故：OIDC 下 created_at_ms / email 缺失，
 * 一次解码失败让整台手机处在「登着又没登」的状态）。
 */
data class AuthUser(
    val id: Long,
    val email: String,
    val displayName: String,
    val createdAtMs: Long?,
    val emailVerified: Boolean,
    val deactivating: Boolean,
    val closed: Boolean,
    val deactivatedAtMs: Long?,
) {
    companion object {
        fun parse(o: JsonObject?): AuthUser? {
            o ?: return null
            val id = o.long("id") ?: return null
            val email = o.str("email").orEmpty()
            val name = o.str("display_name")?.takeIf { it.isNotBlank() }
                ?: o.str("name")?.takeIf { it.isNotBlank() }
                ?: email.takeIf { it.isNotBlank() } ?: "#$id"
            val created = o.long("created_at_ms")
                ?: o.str("created_at")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
            return AuthUser(
                id, email, name, created,
                emailVerified = o.bool("email_verified") ?: true,
                deactivating = o.bool("deactivating") ?: false,
                closed = o.bool("closed") ?: false,
                deactivatedAtMs = o.long("deactivated_at_ms"),
            )
        }
    }
}

data class AuthDevice(val device: String, val createdAtMs: Long?, val lastSeenMs: Long?)

sealed interface LoginState {
    /** 还没问过 / 断网问不到。**断网不算被登出**，保持上一个状态。 */
    data object Unknown : LoginState
    data object Out : LoginState
    data class SignedIn(val user: AuthUser, val devices: List<AuthDevice>) : LoginState
    data object Invalid : LoginState
}

/** `GET /auth/config`。404（老服务端）当 local。 */
@Serializable
data class AuthConfig(
    val provider: String = "local",
    val issuer: String? = null,
    val client_id: String? = null,
    val authorization_endpoint: String? = null,
    val token_endpoint: String? = null,
    val end_session_endpoint: String? = null,
    val scope: String? = null,
) {
    val isOidc get() = provider == "oidc"
}

/** 登录 / 注册 / 改密等 /auth/… 写操作的失败分档（iOS AuthService.swift:184-226）。按码分，不按中文 detail。 */
sealed interface AuthFailure {
    data object Offline : AuthFailure
    data object BadCredentials : AuthFailure
    data class Locked(val seconds: Int) : AuthFailure
    data object BadInvite : AuthFailure
    data object NoMailer : AuthFailure
    data object NoAccountApi : AuthFailure
    data class Rejected(val detail: String?) : AuthFailure
    data class Other(val status: Int, val detail: String?) : AuthFailure

    companion object {
        fun of(r: HttpResult): AuthFailure = when (r) {
            is HttpResult.Offline, is HttpResult.Blocked -> Offline
            is HttpResult.Ok -> Other(r.status, null)
            is HttpResult.Fail -> {
                val retry = r.headers?.get("Retry-After")?.toIntOrNull()
                val field = runCatching { UmbraJson.parseToJsonElement(r.body) as? JsonObject }.getOrNull()?.str("field")
                when {
                    r.status == 401 && r.code == "bad_credentials" -> BadCredentials
                    (r.status == 401 || r.status == 429) && r.code == "too_many_attempts" -> Locked(retry ?: 60)
                    r.status == 429 -> Locked(retry ?: 60)
                    r.status == 400 && r.code == "invalid_field" && field == "invite" -> BadInvite
                    r.status == 503 && (r.detail ?: "").contains("发信") -> NoMailer
                    r.status == 503 -> NoAccountApi
                    r.status in setOf(400, 404, 409) -> Rejected(r.detail)
                    else -> Other(r.status, r.detail)
                }
            }
        }
    }
}
