// 「用电脑转换」的网络（服务端 `/convert/*`，`Umbra/private_ai_assistant/routes/convert.py` + `remote_convert.py`）。
// 照 iOS `Features/Convert/ConvertClient.swift`。
//
// Android 和 iOS 的不同：分享透明 Activity 与主 App 同进程，所以**直接走 AppGraph.http**（请求闸、Bearer、分档码都在那一处），
// 不用像 iOS 扩展那样自己写一段最小网络。只有一处例外：
//   成品下载 `/files/<id>` 可能 302 到 OSS 签名地址。统一出口只跟同主机的重定向、而且只回字符串 ——
//   这里用 `http.client` 自己发（同保险箱 / 远程文件面板取字节的做法），**不跟重定向**，拿到 3xx 后照 Location 另发一次（只认 https），
//   两次都**不带登录头**（`/files` 不鉴权；带着 Authorization 去 OSS 会被当成签错的请求拒掉）。
package com.geek.umbra.feature.convert

import com.geek.umbra.app.AppGraph
import com.geek.umbra.net.HttpResult
import com.geek.umbra.net.UmbraJson
import com.geek.umbra.net.Uploads
import com.geek.umbra.net.arr
import com.geek.umbra.net.bool
import com.geek.umbra.net.dbl
import com.geek.umbra.net.int
import com.geek.umbra.net.long
import com.geek.umbra.net.obj
import com.geek.umbra.net.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.geek.umbra.net.UmbraHttp
import java.time.Instant
import java.util.concurrent.TimeUnit

/** `/convert/devices` 的一格 + `/devices/all` 里离线的那几台（面板「选一台电脑」要把离线的也画出来、置灰）。 */
data class ConvertPC(val id: String, val name: String, val online: Boolean, val lastSeen: Instant? = null)

/** `/convert/targets` 的一格（PC `convert.targets_for`）。`install`（安装命令）不取：手机上复制命令没用，只说缺什么。 */
data class ConvertTarget(val to: String, val label: String, val available: Boolean, val reason: String?, val note: String?)

/** `/convert/jobs/{id}` 的形状（`remote_convert.Job.view()`，只取用到的）。 */
data class ConvertJob(
    val id: String,
    val status: String,
    val progress: Double?,
    val deviceId: String?,
    val deviceName: String?,
    val name: String?,
    val to: String?,
    val result: Result?,
    val error: String?,
    val createdMs: Long?,
    val updatedMs: Long?,
) {
    data class Result(val fileId: String, val name: String?, val size: Int?, val note: String?)

    companion object {
        fun parse(o: JsonObject): ConvertJob? {
            val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return null
            val res = o.obj("result")?.let { r -> r.str("file_id")?.takeIf { it.isNotEmpty() }?.let { ConvertJob.Result(it, r.str("name"), r.int("size"), r.str("note")) } }
            return ConvertJob(
                id, o.str("status").orEmpty(), o.dbl("progress"), o.str("device_id"), o.str("device_name"), o.str("name"), o.str("to"),
                res, o.str("error"), o.long("created_ms"), o.long("updated_ms"),
            )
        }
    }
}

/** 一次请求的三种结局（被拒时带服务端的分档码与那句话）。 */
sealed interface ConvertReply<out T> {
    data class Ok<T>(val value: T) : ConvertReply<T>
    /** 服务端回了非 2xx：`code` = `X-Umbra-Error` 头（按它分档，不按 detail 那句中文）。 */
    data class Rejected(val status: Int, val code: String?, val detail: String?) : ConvertReply<Nothing>
    /** 没回话：网络断了 / 超时。`timedOut` 单独一档（问电脑能转什么时它就是「电脑没回话」）。 */
    data class Unreachable(val timedOut: Boolean) : ConvertReply<Nothing>
}

object ConvertClient {
    /** 一般请求的超时：「人在等着」的那种，默认 60 秒太久（同 iOS `ConvertClient.timeout`）。 */
    private const val TIMEOUT_MS = 15_000L
    /** 传文件 / 取成品的超时：几 MB 的文件在蜂窝网络上 15 秒传不完是常事。 */
    private const val TRANSFER_TIMEOUT_MS = 120_000L

    private val http get() = AppGraph.http

    /** 统一出口的结果 → 三种结局。Offline 的原因串里带 timeout 字样的算「超时」（OkHttp 的 SocketTimeoutException / 「timeout」）。 */
    private fun <T> map(r: HttpResult, parse: (JsonElement) -> T?): ConvertReply<T> = when (r) {
        is HttpResult.Ok -> runCatching { parse(UmbraJson.parseToJsonElement(r.body)) }.getOrNull()?.let { ConvertReply.Ok(it) }
            ?: ConvertReply.Rejected(r.status, null, null)
        is HttpResult.Fail -> ConvertReply.Rejected(r.status, r.code, r.detail)
        is HttpResult.Offline -> ConvertReply.Unreachable(r.message.contains("time", ignoreCase = true))
        is HttpResult.Blocked -> ConvertReply.Rejected(401, "signed_out", null)
    }

    // MARK: 查

    /** 在线、装着转换技能的电脑（空 = 一台都没有）。 */
    suspend fun onlineDevices(): ConvertReply<List<ConvertPC>> =
        map(http.raw("GET", "/convert/devices", timeoutMs = TIMEOUT_MS)) { e ->
            (e as JsonObject).arr("devices").mapNotNull { d ->
                (d as? JsonObject)?.let { o -> o.str("device_id")?.let { ConvertPC(it, o.str("device_name") ?: it, true) } }
            }
        }

    /**
     * 离线的电脑（`/devices/all`，面板里画成置灰行）。拿不到就当没有 —— 它只是让人知道「那台在、但离线」。
     * 手机 / 平板不算（它们不是执行设备）。
     */
    suspend fun offlineComputers(): List<ConvertPC> {
        val r = map(http.raw("GET", "/devices/all", timeoutMs = TIMEOUT_MS)) { e -> (e as JsonArray).mapNotNull { it as? JsonObject } }
        val rows = (r as? ConvertReply.Ok)?.value ?: return emptyList()
        return rows.filter { it.bool("online") != true && (it.str("platform") ?: "").lowercase() !in setOf("ios", "android", "ipados") }
            .mapNotNull { o -> o.str("device_id")?.let { ConvertPC(it, o.str("device_name") ?: it, false, ConvertRules.parseDate(o.str("last_seen"))) } }
    }

    /** 问某台电脑这个文件能转成什么（服务端同步派活，等电脑最多 60 秒；请求本身 70 秒超时）。 */
    suspend fun targets(device: String, name: String): ConvertReply<List<ConvertTarget>> {
        val body = buildJsonObject { put("device_id", device); put("name", name) }
        val r = http.raw("POST", "/convert/targets", body = body.asBody(), timeoutMs = ConvertRules.ASK_REQUEST_TIMEOUT_MS)
        return map(r) { e ->
            (e as JsonObject).arr("targets").mapNotNull { t ->
                (t as? JsonObject)?.let { o ->
                    o.str("to")?.let { ConvertTarget(it, o.str("label") ?: it.uppercase(), o.bool("available") ?: false, o.str("reason"), o.str("note")) }
                }
            }
        }
    }

    /** 一件作业的状态。404 = 服务端查不到这件了（续看时多半是结束超过 2 小时被清掉，转换途中则是服务端重启丢了）。 */
    suspend fun job(id: String): ConvertReply<ConvertJob> =
        map(http.raw("GET", "/convert/jobs/$id", timeoutMs = TIMEOUT_MS)) { ConvertJob.parse(it as JsonObject) }

    /** 这个人的全部作业（最近的在前）。横条拿它和本机记着的 id 比。拿不到回 null。 */
    suspend fun jobs(): List<ConvertJob>? =
        (map(http.raw("GET", "/convert/jobs", timeoutMs = TIMEOUT_MS)) { e -> (e as JsonObject).arr("jobs").mapNotNull { (it as? JsonObject)?.let(ConvertJob::parse) } }
            as? ConvertReply.Ok)?.value

    // MARK: 做

    /** 传文件（`/files/upload`，multipart）。成功回 file_id；`onProgress` 收 0–1。413 单独回（文件太大那句）。 */
    suspend fun upload(src: ConvertSource, onProgress: (Float) -> Unit): ConvertReply<String> {
        val (u, err) = Uploads.upload(src.data, src.name, onProgress, timeoutMs = TRANSFER_TIMEOUT_MS)
        if (u != null) return ConvertReply.Ok(u.file_id)
        return when (err) {
            com.geek.umbra.net.UploadError.Offline -> ConvertReply.Unreachable(false)
            com.geek.umbra.net.UploadError.FileTooLarge, com.geek.umbra.net.UploadError.BodyTooLarge -> ConvertReply.Rejected(413, "file_too_large", null)
            else -> ConvertReply.Rejected(0, null, null)
        }
    }

    /**
     * 开一件转换。iOS 这里带本机 APNs 令牌（转完只推给它）；Android 今天没有服务端推送（FCM 待服务端），不带 ——
     * 作业照样跑完，人回到 App 时设备详情页顶上那一条横条会说。
     */
    suspend fun start(device: String, fileId: String, name: String, to: String): ConvertReply<ConvertJob> {
        val body = buildJsonObject { put("device_id", device); put("file_id", fileId); put("name", name); put("to", to) }
        return map(http.raw("POST", "/convert/jobs", body = body.asBody(), timeoutMs = TIMEOUT_MS)) { ConvertJob.parse(it as JsonObject) }
    }

    /**
     * 停掉一件转换：`DELETE /convert/jobs/{id}`（服务端 f227ac4 起有：作废作业、删已传原件、给电脑发 task_cancel）。
     * 老服务端回 404 / 405 也不当回事：面板照样收起，作业跑完后由孤儿清理器收走文件。回「服务端认没认」。
     */
    suspend fun cancel(id: String): Boolean = http.raw("DELETE", "/convert/jobs/$id", timeoutMs = TIMEOUT_MS) is HttpResult.Ok

    /** 取成品：先问 `/files/<id>`（不带登录头、不跟重定向）；回 3xx 就照 Location 再要一次（只认 https）。`onProgress` 收 0–1。 */
    suspend fun fetch(fileId: String, onProgress: (Float) -> Unit): ByteArray? = withContext(Dispatchers.IO) {
        val first = http.url("/files/$fileId").toHttpUrlOrNull() ?: return@withContext null
        val c = http.client.newBuilder().followRedirects(false).followSslRedirects(false)
            .callTimeout(TRANSFER_TIMEOUT_MS, TimeUnit.MILLISECONDS).readTimeout(TRANSFER_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
        fun get(url: okhttp3.HttpUrl): Pair<Int, Any?>? = runCatching {
            c.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        val total = resp.body.contentLength()
                        val src = resp.body.byteStream()
                        val out = java.io.ByteArrayOutputStream(if (total in 1..Int.MAX_VALUE) total.toInt() else 8192)
                        val buf = ByteArray(16 * 1024)
                        var got = 0L
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            if (total > 0) onProgress((got.toFloat() / total).coerceAtMost(1f))
                        }
                        resp.code to out.toByteArray()
                    }
                    resp.code in 300..399 -> resp.code to resp.header("Location")
                    else -> resp.code to null
                }
            }
        }.getOrNull()
        val (code, v) = get(first) ?: return@withContext null
        if (code in 200..299) return@withContext v as? ByteArray
        val loc = (v as? String)?.let { first.resolve(it) }?.takeIf { it.isHttps } ?: return@withContext null
        val (c2, v2) = get(loc) ?: return@withContext null
        if (c2 in 200..299) v2 as? ByteArray else null
    }

    private fun JsonObject.asBody() = toString().toRequestBody(UmbraHttp.JSON)
}
