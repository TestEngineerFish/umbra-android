package com.geek.umbra.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 「后台路径」标记 —— 对应 iOS 的 `@TaskLocal UmbraInitiator.background`。
 * 同步、轮询、重连补拉、续期、启动问门都包在 `withContext(BackgroundWork)` 里，统一出口就不会给它们打
 * `X-Umbra-Initiator: user`（作息统计「宁可少标」）。
 */
object BackgroundWork : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<BackgroundWork>
}

/** 最后一次真实输入（触摸 / 按键）的时刻。MainActivity 的 dispatchTouchEvent / dispatchKeyEvent 写它。 */
object Initiator {
    val lastInputMs = AtomicLong(0)
    fun markInput() = lastInputMs.set(System.currentTimeMillis())
}

/** 一次请求的结果。status == 0 表示没到服务端（离线 / 被本地请求闸拦下）。 */
sealed interface HttpResult {
    data class Ok(val status: Int, val body: String, val headers: okhttp3.Headers) : HttpResult
    data class Fail(val status: Int, val code: String?, val detail: String?, val body: String = "", val headers: okhttp3.Headers? = null) : HttpResult
    data class Offline(val message: String) : HttpResult
    /** 未登录时本地请求闸拦下：不记离线、不当 401（iOS SignedOutRequestBlocked）。 */
    data class Blocked(val path: String) : HttpResult
}

/** 写操作的结果：成功带值；失败带服务端原话（detail 只用来显示，分档只看 code）。 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Err(val status: Int, val code: String?, val detail: String?, val offline: Boolean = false) : ApiResult<Nothing>
}

val ApiResult<*>.isOk get() = this is ApiResult.Ok

/**
 * 统一出口 —— 对应 iOS NetworkConfig 里那个「所有响应都经过一处」的出口。所有 HTTP 请求只走这里：
 *
 * 1. 请求闸：未登录只放行 `/auth/…` 与 `/health`；
 * 2. `Authorization: Bearer`（每次真读令牌）；不再带已退役的 `X-Umbra-Token`；
 * 3. 3 秒内有真实输入且不在后台路径 → `X-Umbra-Initiator: user`；
 * 4. 记账路径自动追加 `ledger=`；
 * 5. 响应：会话失效 → 交给 [onSessionInvalid]（带上**发请求时那把**令牌）；401 普通码只记「凭证被拒」不登出；
 * 6. 连不上 → 记离线；2xx → 记在线。
 *
 * 重定向不交给 OkHttp：只跟同主机（含 http→https 升级），跨源 3xx 当失败。
 */
class UmbraHttp(
    private val prefs: Prefs,
    private val session: SessionStore,
    private val ledger: () -> Ledger,
) {
    val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _offline = MutableStateFlow(false)
    /** 最近一次请求是不是没连上服务端。各页的离线条读它。 */
    val offline: StateFlow<Boolean> = _offline

    /** 凭证状态：ok / badToken（多半没登录）/ sessionInvalid。「我 › 连接」读它。 */
    enum class AuthVerdict { Ok, BadToken, SessionInvalid }
    private val _authVerdict = MutableStateFlow(AuthVerdict.Ok)
    val authVerdict: StateFlow<AuthVerdict> = _authVerdict

    /** 会话失效回调（由 SessionRevoke 接上）。参数是发请求时带的令牌。 */
    var onSessionInvalid: (sentToken: String) -> Unit = {}

    val server: String get() = prefs.serverUrl

    fun url(path: String, query: Map<String, Any?> = emptyMap()): String {
        val sb = StringBuilder(server).append(path)
        val q = query.filterValues { it != null }.toMutableMap()
        if (Ledger.matches(path.substringBefore('?')) && !q.containsKey("ledger") && !path.contains("ledger=")) {
            q["ledger"] = ledger().wire
        }
        if (q.isNotEmpty()) {
            sb.append(if (path.contains('?')) '&' else '?')
            sb.append(q.entries.joinToString("&") { "${UmbraUrl.value(it.key)}=${UmbraUrl.value(it.value.toString())}" })
        }
        return sb.toString()
    }

    /** 底层发送。[authed]=false 用于 /health、OIDC 之外的匿名探活。 */
    suspend fun raw(
        method: String,
        path: String,
        query: Map<String, Any?> = emptyMap(),
        body: RequestBody? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long? = null,
        authed: Boolean = true,
    ): HttpResult {
        val full = url(path, query)
        val basePath = server.toHttpUrlOrNull()?.encodedPath ?: ""
        val reqPath = full.toHttpUrlOrNull()?.encodedPath ?: path
        if (SessionRequestGate.blocks(prefs.signedOut, reqPath, basePath)) return HttpResult.Blocked(path)

        val token = if (authed) session.token else ""
        val b = Request.Builder().url(full)
        if (token.isNotEmpty()) b.header("Authorization", "Bearer $token")
        headers.forEach { (k, v) -> b.header(k, v) }
        val background = coroutineContext[BackgroundWork.Key] != null
        if (!headers.containsKey(InitiatorRules.HEADER) &&
            InitiatorRules.tags(System.currentTimeMillis(), Initiator.lastInputMs.get(), background)
        ) b.header(InitiatorRules.HEADER, InitiatorRules.USER)
        b.method(method, body ?: if (method == "POST" || method == "PUT" || method == "PATCH") ByteArray(0).toRequestBody(null) else null)

        val c = if (timeoutMs != null) client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).readTimeout(timeoutMs, TimeUnit.MILLISECONDS).build() else client
        return withContext(Dispatchers.IO) {
            var req = b.build()
            var hops = 0
            while (true) {
                val resp = try {
                    c.newCall(req).await()
                } catch (e: IOException) {
                    _offline.value = true
                    return@withContext HttpResult.Offline(e.message ?: e.javaClass.simpleName)
                }
                if (resp.code in 300..399 && hops < 3) {
                    val loc = resp.header("Location")
                    resp.close()
                    val target = loc?.let { req.url.resolve(it) }
                    if (target != null && UmbraUrl.followsRedirect(req.url.toString(), target.toString())) {
                        req = req.newBuilder().url(target).build(); hops++; continue
                    }
                    return@withContext HttpResult.Fail(resp.code, "cross_origin_redirect", "服务端把请求转去了别的地址，已拦下")
                }
                return@withContext handle(resp, token)
            }
            @Suppress("UNREACHABLE_CODE") HttpResult.Offline("")
        }
    }

    private fun handle(resp: Response, sentToken: String): HttpResult {
        val text = resp.body.string()
        val code = resp.header(SessionRevokeRules.ERROR_HEADER)
        _offline.value = false
        if (SessionRevokeRules.http(resp.code, code, resp.header(SessionRevokeRules.SESSION_HEADER))) {
            onSessionInvalid(sentToken)
            if (resp.code == 401) _authVerdict.value = AuthVerdict.SessionInvalid
        } else if (resp.code == 401) {
            if (code == null || code == "bad_token" || code == "unauthorized") _authVerdict.value = AuthVerdict.BadToken
        } else if (resp.code in 200..299) {
            _authVerdict.value = AuthVerdict.Ok
        }
        if (resp.code in 200..299) return HttpResult.Ok(resp.code, text, resp.headers)
        // 分档看头 X-Umbra-Error，没有再看 body 的 code；detail 只用来显示
        val parsed = runCatching { UmbraJson.parseToJsonElement(text).jsonObject }.getOrNull()
        val bodyCode = parsed?.get("code")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        val detail = parsed?.get("detail")?.let { d -> runCatching { d.jsonPrimitive.content }.getOrElse { d.toString() } }
        return HttpResult.Fail(resp.code, code ?: bodyCode, detail, text, resp.headers)
    }

    // MARK: - 便捷封装

    /** GET 并解码。**null = 没拉到**（离线 / 失败 / 解不开），与「空」严格区分。 */
    suspend fun <T> get(path: String, ser: KSerializer<T>, query: Map<String, Any?> = emptyMap(), timeoutMs: Long? = null): T? =
        when (val r = raw("GET", path, query, timeoutMs = timeoutMs)) {
            is HttpResult.Ok -> runCatching { UmbraJson.decodeFromString(ser, r.body) }.getOrNull()
            else -> null
        }

    suspend inline fun <reified T> get(path: String, query: Map<String, Any?> = emptyMap(), timeoutMs: Long? = null): T? =
        get(path, serializer<T>(), query, timeoutMs)

    /** 带 JSON 体的写操作，回包解成 T。 */
    suspend fun <T> send(
        method: String, path: String, body: JsonElement?, ser: KSerializer<T>,
        query: Map<String, Any?> = emptyMap(), headers: Map<String, String> = emptyMap(), timeoutMs: Long? = null,
    ): ApiResult<T> {
        val rb = body?.toString()?.toRequestBody(JSON)
        return when (val r = raw(method, path, query, rb, headers, timeoutMs)) {
            is HttpResult.Ok -> {
                val v = if (r.body.isBlank()) runCatching { UmbraJson.decodeFromString(ser, "{}") }.getOrNull()
                else runCatching { UmbraJson.decodeFromString(ser, r.body) }.getOrNull()
                if (v != null) ApiResult.Ok(v) else ApiResult.Err(r.status, "decode", "回包格式认不出")
            }
            is HttpResult.Fail -> ApiResult.Err(r.status, r.code, r.detail)
            is HttpResult.Offline -> ApiResult.Err(0, "offline", r.message, offline = true)
            is HttpResult.Blocked -> ApiResult.Err(0, "signed_out", "未登录")
        }
    }

    suspend inline fun <reified T> send(
        method: String, path: String, body: JsonElement? = null,
        query: Map<String, Any?> = emptyMap(), headers: Map<String, String> = emptyMap(), timeoutMs: Long? = null,
    ): ApiResult<T> = send(method, path, body, serializer<T>(), query, headers, timeoutMs)

    /** 只关心成败、不关心回包的写操作。 */
    suspend fun call(method: String, path: String, body: JsonElement? = null, query: Map<String, Any?> = emptyMap(), headers: Map<String, String> = emptyMap()): ApiResult<JsonObject> =
        send(method, path, body, JsonObject.serializer(), query, headers)

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** OkHttp Call 的挂起版本；协程取消时取消请求。 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
