// 知识库的接口（iOS HTTPService.swift:1249-1350「Knowledge」那一段）+ 原文预览（iOS KbPreview.swift）。
// 每一条都回 KbWriteResponse（分档原料），分档只在 KnowledgeRules.outcome 一处判。
package com.geek.umbra.feature.knowledge

import android.content.ActivityNotFoundException
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.feature.account.StorageRules
import com.geek.umbra.net.BackgroundWork
import com.geek.umbra.net.HttpResult
import com.geek.umbra.net.UmbraHttp
import com.geek.umbra.net.UmbraJson
import com.geek.umbra.net.Uploads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import com.geek.umbra.net.SessionRevokeRules
import com.geek.umbra.net.await
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.File
import java.util.UUID

object KbNet {
    private val http get() = AppGraph.http

    /** HttpResult → 分档原料。码先看 X-Umbra-Error 头、再看体（统一出口已经这么取了）；storage_full 的三个数从体顶层读。 */
    fun response(r: HttpResult): KbWriteResponse = when (r) {
        is HttpResult.Ok -> KbWriteResponse(r.status, null, null, "")
        is HttpResult.Fail -> {
            val o = runCatching { UmbraJson.parseToJsonElement(r.body) as? JsonObject }.getOrNull()
            KbWriteResponse(
                r.status, r.code, (o?.get("state") as? JsonPrimitive)?.contentOrNull, r.detail ?: "",
                used = o?.let { StorageRules.long(it, "used") } ?: 0,
                limit = o?.let { StorageRules.long(it, "limit") } ?: 0,
                incoming = o?.let { StorageRules.long(it, "incoming") } ?: 0,
            )
        }
        is HttpResult.Offline -> KbWriteResponse(0, null, null, r.message)
        is HttpResult.Blocked -> KbWriteResponse(0, KnowledgeRules.LocalCode.SIGNED_OUT, null, "")
    }

    /** 发一次；协程被取消（离开页面、点了取消）→ 本地码 cancelled，不当成断网。 */
    private suspend fun send(method: String, path: String, body: RequestBody? = null, headers: Map<String, String> = emptyMap(), timeoutMs: Long? = null): Pair<KbWriteResponse, HttpResult?> =
        try {
            val r = http.raw(method, path, body = body, headers = headers, timeoutMs = timeoutMs)
            response(r) to r
        } catch (e: CancellationException) {
            KnowledgeRules.transport(e) to null
        }

    /** 库列表。档非 ok 时列表 null（= 没拉到，不当成「没有库」）。 */
    suspend fun fetchKbs(): Pair<KbWriteResponse, List<KbDTO>?> {
        val (r, raw) = send("GET", "/kb")
        val list = if (KnowledgeRules.outcome(r) == KbOutcome.Ok && raw is HttpResult.Ok)
            runCatching { UmbraJson.decodeFromString(ListSerializer(KbDTO.serializer()), raw.body) }.getOrNull()?.filter { it.kb_id.isNotEmpty() }
        else null
        return r to list
    }

    /** 一个库的文档。缺 doc_id / path 的丢掉（点不开、删不了，画出来只是一行坏的）。 */
    suspend fun fetchKbDocs(kbId: String): Pair<KbWriteResponse, KbDocsDTO?> {
        val (r, raw) = send("GET", "/kb/${KnowledgeRules.segment(kbId)}/docs")
        val dto = if (KnowledgeRules.outcome(r) == KbOutcome.Ok && raw is HttpResult.Ok)
            runCatching { UmbraJson.decodeFromString(KbDocsDTO.serializer(), raw.body) }.getOrNull()
                ?.let { d -> d.copy(docs = d.docs.filter { it.doc_id.isNotEmpty() && it.path.isNotEmpty() }) }
        else null
        return r to dto
    }

    suspend fun createKb(name: String, description: String): KbWriteResponse =
        send("POST", "/kb", buildJsonObject { put("name", name); put("description", description) }.toString().toBody()).first

    /**
     * 删一份（进回收站）。**没有 sha 就不发**（kb-delete-fail-open-without-sha）：服务端只在带了 If-Match 时比版本，
     * 不带就直接删 —— 删掉的可能是人没见过的新版本。412 = 别处刚改过。
     */
    suspend fun deleteKbDoc(kbId: String, docId: String, blobSha: String?): KbWriteResponse {
        val sha = KnowledgeRules.ifMatch(blobSha)
            ?: return KbWriteResponse(0, KnowledgeRules.LocalCode.NO_VERSION, null, "")
        return send("DELETE", "/kb/${KnowledgeRules.segment(kbId)}/docs/${KnowledgeRules.segment(docId)}", headers = mapOf("If-Match" to sha)).first
    }

    suspend fun reindexKbDoc(kbId: String, docId: String): KbWriteResponse =
        send("POST", "/kb/${KnowledgeRules.segment(kbId)}/docs/${KnowledgeRules.segment(docId)}/reindex").first

    /**
     * 上传一份：multipart `path`（原名，UTF-8 表单字段 —— 中文名以它为准）+ `file`（filename 洗过的那份），带真实进度。
     *
     * ⚠️ 没用 net/Uploads.upload：它把回包固定解成 UploadResponse、没有 file_id 就当失败，也不把状态码 / 码交回来 ——
     * `/kb/{id}/files` 回的是 {indexed, changed, path, doc}，分档要的 412 / 413 / storage_full 也拿不到。这里照它的做法
     * （同一个洗名函数、同一个 octet-stream 体）自己拼，回原样的分档原料。
     */
    suspend fun uploadKbFile(kbId: String, bytes: ByteArray, name: String, onProgress: (Long, Long) -> Unit): Pair<KbWriteResponse, KbUploadDTO?> {
        val total = bytes.size.toLong()
        val file = ProgressBody(bytes, "application/octet-stream".toMediaType()) { sent -> onProgress(sent, total) }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("path", name)
            .addFormDataPart("file", Uploads.safeName(name), file)
            .build()
        val (r, raw) = send("POST", "/kb/${KnowledgeRules.segment(kbId)}/files", body = body, timeoutMs = 180_000)
        val dto = if (KnowledgeRules.outcome(r) == KbOutcome.Ok && raw is HttpResult.Ok)
            runCatching { UmbraJson.decodeFromString(KbUploadDTO.serializer(), raw.body) }.getOrNull() else null
        return r to dto
    }

    /** 带进度的请求体（按整数百分比节流，免得每个 8 KB 块都去刷界面）。 */
    private class ProgressBody(val data: ByteArray, val type: MediaType, val cb: (Long) -> Unit) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = data.size.toLong()
        override fun writeTo(sink: BufferedSink) {
            var sent = 0L
            var lastPct = -1
            val total = data.size.toLong().coerceAtLeast(1)
            val counting = object : ForwardingSink(sink) {
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    sent += byteCount
                    val pct = (sent * 100 / total).toInt()
                    if (pct != lastPct) { lastPct = pct; cb(sent) }
                }
            }.buffer()
            counting.write(data)
            counting.flush()
        }
    }

    /**
     * 下原文到缓存（`cacheDir/kb-preview/<uuid>/<文件名>`）。先扫掉早先没人收拾的老目录；写盘失败是本地问题（Local），
     * 不说「连不上服务器」。成功回本地文件。
     */
    suspend fun downloadKbRaw(kbId: String, path: String): Pair<KbWriteResponse, File?> {
        val (r, bytes) = try { rawBytes(KnowledgeRules.rawPath(kbId, path)) } catch (e: CancellationException) { KnowledgeRules.transport(e) to null }
        if (bytes == null) return r to null
        return withContext(Dispatchers.IO) {
            try {
                val root = KbPreviewTemp.root(AppGraph.context.cacheDir)
                KbPreviewTemp.sweep(root)
                val dir = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
                val f = File(dir, KbCitations.fileName(path).ifEmpty { "file" })
                f.writeBytes(bytes)
                r to f
            } catch (e: Exception) {
                // 写盘失败（磁盘满）—— 本地问题，带系统原话
                KbWriteResponse(0, KnowledgeRules.LocalCode.IO, null, e.message ?: "I/O") to null
            }
        }
    }

    /**
     * 按**字节**拿原文。统一出口 `http.raw` 把回包读成字符串，二进制（PDF / DOCX）会被 UTF-8 解坏，所以这一条直接用
     * 同一个 OkHttp 客户端（同样不跟跨源重定向）自己发；请求闸、Bearer、失效信号照统一出口的规矩补上：
     * 未登录不发；`session_invalid` 交给 AuthService.report（带发请求时那把令牌）。
     */
    private suspend fun rawBytes(path: String): Pair<KbWriteResponse, ByteArray?> {
        if (AppGraph.prefs.signedOut) return KbWriteResponse(0, KnowledgeRules.LocalCode.SIGNED_OUT, null, "") to null
        val token = AppGraph.session.token
        val b = okhttp3.Request.Builder().url(http.url(path))
        if (token.isNotEmpty()) b.header("Authorization", "Bearer $token")
        return withContext(Dispatchers.IO + BackgroundWork) {
            val resp = try {
                http.client.newBuilder().readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build().newCall(b.build()).await()
            } catch (e: java.io.IOException) {
                return@withContext KbWriteResponse(0, null, null, e.message ?: "") to null
            }
            resp.use {
                val err = it.header(SessionRevokeRules.ERROR_HEADER)
                if (SessionRevokeRules.http(it.code, err, it.header(SessionRevokeRules.SESSION_HEADER))) {
                    AppGraph.scope.launch { AppGraph.auth.report(token) }
                }
                if (it.isSuccessful) {
                    val bytes = try { it.body.bytes() } catch (e: java.io.IOException) { return@withContext KbWriteResponse(0, null, null, e.message ?: "") to null }
                    KbWriteResponse(it.code, null, null, "") to bytes
                } else {
                    val text = runCatching { it.body.string() }.getOrDefault("")
                    val o = runCatching { UmbraJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
                    val bodyCode = (o?.get("code") as? JsonPrimitive)?.contentOrNull
                    val detail = (o?.get("detail") as? JsonPrimitive)?.contentOrNull ?: ""
                    KbWriteResponse(it.code, err ?: bodyCode, (o?.get("state") as? JsonPrimitive)?.contentOrNull, detail) to null
                }
            }
        }
    }

    private fun String.toBody(): RequestBody = toRequestBody(UmbraHttp.JSON)
}

/**
 * 取原文 → 交系统打开（iOS KbPreviewer + QuickLook）。Android 没有 QuickLook：下到缓存后 `ACTION_VIEW` + FileProvider
 * （authority `${applicationId}.files`，路径 res/xml/file_paths.xml 的 cache-path），由系统里能打开这类文件的 App 接手。
 * 没有能打开它的 App 时如实说一句。
 *
 * 进程级单例而不是每页一份：聊天 chip 与文档页同一时刻只会在等一份；[opening] 给行 / chip 画转圈、挡住连点。
 */
object KbPreviewer {
    private val _opening = MutableStateFlow<String?>(null)
    val opening: StateFlow<String?> = _opening

    fun key(kbId: String, path: String) = kbId + "\u0000" + path

    fun open(kbId: String, path: String, onFail: (String) -> Unit = { AppGraph.router.showToast(it) }) {
        if (_opening.value != null) return
        _opening.value = key(kbId, path)
        AppGraph.scope.launch {
            val (r, f) = KbNet.downloadKbRaw(kbId, path)
            _opening.value = null
            if (f != null) {
                if (!view(f)) onFail("这台手机上没有能打开「${f.name}」的应用")
            } else {
                // 自己取消的不说话；别的档各说各的。
                val o = KnowledgeRules.outcome(r)
                if (o != KbOutcome.Cancelled) onFail(kbFailText(o))
            }
        }
    }

    private fun view(f: File): Boolean {
        val ctx = AppGraph.context
        val uri = runCatching { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f) }.getOrNull() ?: return false
        val ext = f.extension.lowercase()
        val mime = when (ext) {
            "md", "markdown" -> "text/markdown"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i); true
        } catch (_: ActivityNotFoundException) {
            // Markdown 没有专门的查看器时退回纯文本，多数机器上的文本 / 文件管理器能接
            if (mime == "text/markdown") runCatching { ctx.startActivity(Intent(i).setDataAndType(uri, "text/plain")) }.isSuccess else false
        }
    }
}

/** 写 / 读失败 → 一句人话（iOS KbPreview.swift:96 kbFailText）。带参数的几档单独填。 */
fun kbFailText(o: KbOutcome): String {
    val ctx = AppGraph.context
    return when (o) {
        KbOutcome.TooLarge -> ctx.getString(R.string.kb_fail_tooLarge, KnowledgeRules.UPLOAD_LIMIT_MB)
        is KbOutcome.StorageFull -> ctx.getString(R.string.kb_fail_storageFull, StorageRules.fmt(o.used), StorageRules.fmt(o.limit), StorageRules.fmt(o.incoming))
        is KbOutcome.Failed -> ctx.getString(R.string.kb_fail_server, o.say)
        is KbOutcome.Local -> ctx.getString(R.string.kb_fail_local, o.say)
        else -> kbString(KnowledgeRules.messageKey(o))
    }
}

/** 字符串目录 key（"kb.fail.offline"）→ 本地化文字。key 是 KnowledgeRules 回的那几个，空 key 回空串。 */
fun kbString(key: String): String {
    val ctx = AppGraph.context
    val id = when (key) {
        "kb.fail.changedSince" -> R.string.kb_fail_changedSince
        "kb.fail.disabled" -> R.string.kb_fail_disabled
        "kb.fail.missing" -> R.string.kb_fail_missing
        "kb.fail.duplicate" -> R.string.kb_fail_duplicate
        "kb.fail.busy" -> R.string.kb_fail_busy
        "kb.fail.offline" -> R.string.kb_fail_offline
        "kb.readFail" -> R.string.kb_readFail
        "kb.fail.noVersion" -> R.string.kb_fail_noVersion
        "kb.fail.auth" -> R.string.kb_fail_auth
        "kb.state.queued" -> R.string.kb_state_queued
        "kb.state.parsing" -> R.string.kb_state_parsing
        "kb.state.embedding" -> R.string.kb_state_embedding
        "kb.state.ready" -> R.string.kb_state_ready
        "kb.state.failed" -> R.string.kb_state_failed
        else -> return ""
    }
    return ctx.getString(id)
}
