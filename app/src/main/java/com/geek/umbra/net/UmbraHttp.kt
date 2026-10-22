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

/** [UmbraHttp.bytes] 的结果。Ok 的 [data] 是原样字节；TooLarge = 超过调用方给的上限，读到一半就停了。 */
sealed interface BytesResult {
    class Ok(val status: Int, val data: ByteArray, val headers: okhttp3.Headers) : BytesResult
    /** [body]：失败时服务端回的那段正文（JSON 原文；不是自己的服务端时为空），要读码以外字段（如 `state`）的调用方自己解。 */
    data class Fail(val status: Int, val code: String?, val detail: String?, val body: String = "") : BytesResult
    data object TooLarge : BytesResult
    data class Offline(val message: String) : BytesResult
    data class Blocked(val path: String) : BytesResult
}

/** 拿到了就是字节，其余一律 null（只关心「有没有」的调用方用）。 */
val BytesResult.dataOrNull: ByteArray? get() = (this as? BytesResult.Ok)?.data

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

    /** 进入未登录时由守卫调：上一个会话留下的离线状态不该带进单机模式。 */
    fun clearOffline() { _offline.value = false }

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
    ): HttpResult = when (val x = exchange(method, url(path, query), path, body, headers, timeoutMs, authed, followHttpsAway = false, ours = true)) {
        is Exchange.Got -> x.resp.use { handle(it, x.token) }
        is Exchange.Gave -> x.result
    }

    /**
     * 按字节读（附件、快照、成品、主题包）。[raw] 把正文读成字符串，二进制会被按 UTF-8 解坏，所以另开这一个出口；
     * 请求闸、Bearer、发起方头、重定向规矩、会话失效与离线记账都和 [raw] 是同一份。
     *
     * - [target]：以 `/` 开头的路径（拼服务端地址），或一条完整的 http(s) 地址（服务端回的绝对地址、CDN）。
     *   **登录令牌只带给自己的服务端**：完整地址不同源时不带、也不过请求闸、不记离线（那是别人家的服务器）。
     * - [maxBytes]：读到超过就停、回 [BytesResult.TooLarge]（先看 Content-Length，再边读边数）。
     * - [onProgress]：0..1；不知道总长时不回调。在 IO 线程上调。
     * - [followHttpsAway]：允许 3xx 跳到别的主机（只认 https），跳过去时**去掉登录头**（`/files/<id>` 302 到 OSS 签名地址那种）。
     */
    suspend fun bytes(
        target: String,
        query: Map<String, Any?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long? = null,
        authed: Boolean = true,
        maxBytes: Long = Long.MAX_VALUE,
        followHttpsAway: Boolean = false,
        onProgress: ((Float) -> Unit)? = null,
    ): BytesResult {
        val full = if (target.startsWith("/")) url(target, query) else target
        return when (val x = exchange("GET", full, target, null, headers, timeoutMs, authed, followHttpsAway)) {
            is Exchange.Gave -> when (val r = x.result) {
                is HttpResult.Fail -> BytesResult.Fail(r.status, r.code, r.detail, r.body)
                is HttpResult.Offline -> BytesResult.Offline(r.message)
                is HttpResult.Blocked -> BytesResult.Blocked(r.path)
                is HttpResult.Ok -> BytesResult.Fail(r.status, null, null)
            }
            is Exchange.Got -> withContext(Dispatchers.IO) {
                x.resp.use { resp ->
                    if (!resp.isSuccessful) {
                        // 失败的正文是 JSON（码 / 原话），按 raw 那一套分档；不是我们的服务端就只留状态码。
                        if (!x.ours) return@use BytesResult.Fail(resp.code, null, null)
                        val r = handle(resp, x.token) as? HttpResult.Fail
                        return@use BytesResult.Fail(resp.code, r?.code, r?.detail, r?.body ?: "")
                    }
                    if (x.ours) note(resp, x.token)
                    try {
                        readCapped(resp, maxBytes, onProgress)?.let { BytesResult.Ok(resp.code, it, resp.headers) } ?: BytesResult.TooLarge
                    } catch (e: IOException) {
                        if (x.ours && !prefs.signedOut) _offline.value = true
                        BytesResult.Offline(e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        }
    }

    /** 读正文字节，超过 [max] 回 null。 */
    private fun readCapped(resp: Response, max: Long, onProgress: ((Float) -> Unit)?): ByteArray? {
        val total = resp.body.contentLength()
        if (total > max) return null
        val src = resp.body.byteStream()
        val out = java.io.ByteArrayOutputStream(if (total in 1..Int.MAX_VALUE) total.toInt() else 8192)
        val buf = ByteArray(16 * 1024)
        var got = 0L
        while (true) {
            val n = src.read(buf)
            if (n < 0) break
            got += n
            if (got > max) return null
            out.write(buf, 0, n)
            if (total > 0) onProgress?.invoke((got.toFloat() / total).coerceAtMost(1f))
        }
        return out.toByteArray()
    }

    /** 一次往返的去向：拿到了响应（调用方负责关），或者在发出去之前 / 路上已经有了结论。 */
    private sealed interface Exchange {
        /** [ours]：最后答话的是不是自己的服务端（决定记不记会话 / 离线、失败正文按不按我们的 JSON 读）。 */
        class Got(val resp: Response, val token: String, val ours: Boolean) : Exchange
        class Gave(val result: HttpResult) : Exchange
    }

    /** [raw] 与 [bytes] 共用的发送：请求闸、令牌、发起方头、手动跟重定向。 */
    private suspend fun exchange(
        method: String, full: String, path: String, body: RequestBody?, headers: Map<String, String>,
        timeoutMs: Long?, authed: Boolean, followHttpsAway: Boolean, ours: Boolean? = null,
    ): Exchange {
        // 地址不是合法的 http(s)（空串、手抖填错）时 OkHttp 会抛 IllegalArgumentException —— 那不是 IOException，
        // 没人接就整个崩。这里当作一次失败返回，文案同 iOS NetworkError.invalidURL。
        val fullUrl = full.toHttpUrlOrNull() ?: return Exchange.Gave(HttpResult.Fail(0, "invalid_url", "服务端地址填得不对（在「我 › 连接」里看）。"))
        // raw 一律当自己的服务端（地址就是拿 server 拼的），行为与拆出这一段之前完全一样；bytes 才按地址判。
        val ours = ours ?: UmbraUrl.isServer(server, fullUrl.toString())
        if (ours) {
            val basePath = server.toHttpUrlOrNull()?.encodedPath ?: ""
            if (SessionRequestGate.blocks(prefs.signedOut, fullUrl.encodedPath, basePath)) return Exchange.Gave(HttpResult.Blocked(path))
        }

        val token = if (authed && ours) session.token else ""
        val b = Request.Builder().url(fullUrl)
        if (token.isNotEmpty()) b.header("Authorization", "Bearer $token")
        headers.forEach { (k, v) -> b.header(k, v) }
        val background = coroutineContext[BackgroundWork.Key] != null
        if (ours && !headers.containsKey(InitiatorRules.HEADER) &&
            InitiatorRules.tags(System.currentTimeMillis(), Initiator.lastInputMs.get(), background)
        ) b.header(InitiatorRules.HEADER, InitiatorRules.USER)
        b.method(method, body ?: if (method == "POST" || method == "PUT" || method == "PATCH") ByteArray(0).toRequestBody(null) else null)

        val c = if (timeoutMs != null) client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).readTimeout(timeoutMs, TimeUnit.MILLISECONDS).build() else client
        return withContext(Dispatchers.IO) {
            var req = b.build()
            var hops = 0
            var here = ours
            while (true) {
                val resp = try {
                    c.newCall(req).await()
                } catch (e: IOException) {
                    // 未登录（单机）时不记离线：那时只有探活 / 问门这类匿名请求，它们连不上不代表本机数据旧了 ——
                    // 记上的话本机账本（提醒、生活账本、灵感）页顶会挂一条误导人的「离线」条。
                    if (here && !prefs.signedOut) _offline.value = true
                    return@withContext Exchange.Gave(HttpResult.Offline(e.message ?: e.javaClass.simpleName))
                }
                if (resp.code in 300..399 && hops < 3) {
                    val loc = resp.header("Location")
                    resp.close()
                    val next = loc?.let { req.url.resolve(it) }
                    if (next != null && UmbraUrl.followsRedirect(req.url.toString(), next.toString())) {
                        req = req.newBuilder().url(next).build(); hops++; continue
                    }
                    if (next != null && followHttpsAway && next.isHttps) {
                        // 跳去别家（OSS 签名地址）：登录头和发起方头都不跟过去。
                        req = req.newBuilder().url(next).removeHeader("Authorization").removeHeader(InitiatorRules.HEADER).build()
                        hops++; here = false; continue
                    }
                    return@withContext Exchange.Gave(HttpResult.Fail(resp.code, "cross_origin_redirect", "服务端把请求转去了别的地址，已拦下"))
                }
                return@withContext Exchange.Got(resp, token, here)
            }
            @Suppress("UNREACHABLE_CODE") Exchange.Gave(HttpResult.Offline(""))
        }
    }

    /** 收到自己服务端的响应后记账：在线了、会话失效、凭证状态。 */
    private fun note(resp: Response, sentToken: String) {
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
    }

    private fun handle(resp: Response, sentToken: String): HttpResult {
        val text = resp.body.string()
        val code = resp.header(SessionRevokeRules.ERROR_HEADER)
        note(resp, sentToken)
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
