// 知识库（M3）的纯判据 —— 照抄 iOS Features/Knowledge/KnowledgeRules.swift + KnowledgeModels.swift：
// 引用怎么解析、文档状态怎么画、写失败怎么分档、路径怎么拼、选完文件收不收 / 撞没撞名、上传结束说哪句。
// 不 import android.*；只回字符串目录的 key（"kb.fail.xxx"），由视图层取词 —— 用例钉的是档位而不是某一句中文。
// 用例：app/src/test/.../knowledge/KnowledgeRulesTest.kt（照抄 Tools/swifttests/KnowledgeRulesTests.swift）。
package com.geek.umbra.feature.knowledge

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToLong

// MARK: - 线上形状（服务端 routes/knowledge.py；除了库 / 文档的 id、文档的 path，一律可缺）

/** 一个库（`GET /kb` 的一项）。 */
@Serializable
data class KbDTO(
    val kb_id: String = "",
    val name: String? = null,
    val description: String? = null,
    val doc_count: Int? = null,
    val ready_count: Int? = null,
    val failed_count: Int? = null,
    val processing_count: Int? = null,
    val stale_count: Int? = null,
    val head: String? = null,
) {
    val displayName: String get() = name ?: ""
    val docs: Int get() = doc_count ?: 0
    val failed: Int get() = failed_count ?: 0
    val processing: Int get() = processing_count ?: 0
}

/** 一份文档（`GET /kb/{id}/docs` 的一项）。`blob_sha` 删除时原样放进 `If-Match`。 */
@Serializable
data class KbDocDTO(
    val doc_id: String = "",
    val kb_id: String? = null,
    val path: String = "",
    val blob_sha: String? = null,
    val title: String? = null,
    val state: String? = null,
    val error: String? = null,
    val bytes: Long? = null,
    val chunk_count: Int? = null,
    val stale: Boolean? = null,
    val updated_at_ms: Double? = null,
) {
    val docState: KbDocState get() = KbDocState.of(state ?: "")
    val displayTitle: String get() = title?.takeIf { it.isNotEmpty() } ?: KbCitations.fileName(path)
}

/** `GET /kb/{id}/docs` 的响应体。 */
@Serializable
data class KbDocsDTO(val docs: List<KbDocDTO> = emptyList(), val head: String? = null)

/** `POST /kb/{id}/files` 的响应体。`indexed: false` = 入了仓但不参与检索；`changed: false` = 和 HEAD 上那份一模一样。 */
@Serializable
data class KbUploadDTO(val indexed: Boolean? = null, val changed: Boolean? = null, val path: String? = null, val doc: KbDocDTO? = null)

// MARK: - 聊天引用（服务端助手消息的 meta.citations）

/** 一处引用：哪个库的哪个文件，哪一节、第几页（PDF 1 起，其它 0）。 */
data class KbCitation(val kbId: String, val path: String, val title: String, val headingPath: String, val page: Int)

object KbCitations {
    /** 标题路径各级的分隔（服务端 knowledge_split.HEADING_SEP）。 */
    const val SEP = " › "

    /**
     * `meta.citations` → 引用列表。**认不出的项丢掉，不猜**：缺 kb_id 或 path 的点开也打不开。
     * 同一处（库 + 路径 + 小节 + 页）只留一颗。不是数组 → 空。
     */
    fun parse(raw: JsonElement?): List<KbCitation> {
        val arr = raw as? JsonArray ?: return emptyList()
        val out = mutableListOf<KbCitation>()
        val seen = HashSet<String>()
        for (item in arr) {
            val o = item as? JsonObject ?: continue
            val kb = o.string("kb_id")
            val path = o.string("path")
            if (kb.isEmpty() || path.isEmpty()) continue
            val title = o.string("title").ifEmpty { fileName(path) }
            val heading = o.string("heading_path")
            val page = maxOf(0, intValue(o["page"]))
            val key = "$kb\u0000$path\u0000$heading\u0000$page"
            if (seen.add(key)) out += KbCitation(kb, path, title, heading, page)
        }
        return out
    }

    /** chip 上那行字：「标题 › 小节」。小节路径以标题打头时不重复写。页码不在这里拼（带本地化，由视图层加）。 */
    fun label(c: KbCitation): String {
        var head = c.headingPath
        if (head == c.title) head = ""
        else if (head.startsWith(c.title + SEP)) head = head.removePrefix(c.title + SEP)
        return if (head.isEmpty()) c.title else c.title + SEP + head
    }

    /** 路径最后一段（标题缺省时用）。 */
    fun fileName(path: String): String = path.split('/').lastOrNull { it.isNotEmpty() } ?: path

    /** 只认字符串类型的字段（kb_id 是数字的那项当缺：iOS `as? String`）。 */
    private fun JsonObject.string(k: String): String = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: ""

    /**
     * JSON 里的页码：**只收「正好是整数、而且装得下」的**（iOS kb-citation-page-double-trap）——
     * 1e20、NaN、无穷、2.5 一律 0（页码没有半页，宁可不标也不猜成第 2 页）；字符串 "3" 也是 0（不猜）。
     */
    private fun intValue(v: JsonElement?): Int {
        val p = v as? JsonPrimitive ?: return 0
        if (p.isString) return 0
        val s = p.contentOrNull ?: return 0
        s.toIntOrNull()?.let { return it }
        val d = s.toDoubleOrNull() ?: return 0
        if (!d.isFinite() || d != Math.floor(d) || d > Int.MAX_VALUE || d < Int.MIN_VALUE) return 0
        return d.toInt()
    }
}

// MARK: - 文档状态

/** 文档的五个状态。**不认识的按「排队」画**：画成「排队中」比画成「就绪」诚实（它确实还不能被检索到）。 */
enum class KbDocState {
    Queued, Parsing, Embedding, Ready, Failed;

    val labelKey: String
        get() = when (this) {
            Queued -> "kb.state.queued"
            Parsing -> "kb.state.parsing"
            Embedding -> "kb.state.embedding"
            Ready -> "kb.state.ready"
            Failed -> "kb.state.failed"
        }

    /** 还在处理中（列表要兜底轮询；左滑不给「重试」）。 */
    val busy: Boolean get() = this == Queued || this == Parsing || this == Embedding

    val tone: KbTone
        get() = when (this) {
            Ready -> KbTone.Success
            Failed -> KbTone.Danger
            else -> KbTone.Working
        }

    companion object {
        fun of(raw: String): KbDocState = when (raw) {
            "ready" -> Ready
            "failed" -> Failed
            "parsing" -> Parsing
            "embedding" -> Embedding
            else -> Queued
        }
    }
}

/** 状态点的三档色：就绪绿、失败红、处理中琥珀。状态不只靠颜色 —— 点旁边总有那一句字。 */
enum class KbTone { Success, Danger, Working }

// MARK: - 写失败分档

/** 一次写请求拿回来的分档原料。没拿到响应时 status 0；code 优先取 X-Umbra-Error 头。 */
data class KbWriteResponse(
    val status: Int,
    val code: String?,
    val state: String?,
    val detail: String,
    val used: Long = 0,
    val limit: Long = 0,
    val incoming: Long = 0,
)

/** 写失败的档。按档说，不解析服务端那句中文；failed 档把服务端原话带上。 */
sealed interface KbOutcome {
    data object Ok : KbOutcome
    /** 412：带的 If-Match 已经不是 HEAD 上那一版。 */
    data object ChangedSince : KbOutcome
    data object Disabled : KbOutcome
    data object Missing : KbOutcome
    data object Duplicate : KbOutcome
    /** 409 state_conflict：正在处理中，不能重试。 */
    data object Busy : KbOutcome
    /** 超过单个文件 32 MiB（服务端 413，或本地先挡下的那一发）。 */
    data object TooLarge : KbOutcome
    /** 413 storage_full：账号的存储用满了，不是这一份太大。 */
    data class StorageFull(val used: Long, val limit: Long, val incoming: Long) : KbOutcome
    data object Offline : KbOutcome
    /** 我们自己取消的（离开页面、点了「取消上传」）。**不提示**。 */
    data object Cancelled : KbOutcome
    /** 本地出的问题（写缓存文件失败、磁盘满）。带系统那句原话 —— 跟服务器无关。 */
    data class Local(val say: String) : KbOutcome
    data object Unreadable : KbOutcome
    /** 删除时手上没有这份的 blob_sha：**不发**（kb-delete-fail-open-without-sha）。 */
    data object NoVersion : KbOutcome
    data object Auth : KbOutcome
    data class Failed(val say: String) : KbOutcome
}

/** 读选中的那个文件时出的事：「读不到」vs「太大了」。 */
class KbFileError(val kind: Kind) : Exception(kind.name) {
    enum class Kind { Unreadable, TooLarge }
}

object KnowledgeRules {
    /** 单个文件上限（MiB）。只用来拼提示、挡掉明知会 413 的那一发，说了算的是服务端（files.MAX_UPLOAD_BYTES）。 */
    const val UPLOAD_LIMIT_MB = 32

    /** 进检索的后缀（服务端 knowledge_split.EXT_KIND 逐字相同；比较不分大小写）。 */
    val pickableExts = listOf("pdf", "docx", "md", "markdown", "txt")

    /** status 0 时放在 code 里的本地码。带 `local.` 前缀，撞不上服务端的 snake_case 码。 */
    object LocalCode {
        const val CANCELLED = "local.cancelled"
        const val IO = "local.io"
        const val UNREADABLE = "local.unreadable"
        const val TOO_LARGE = "local.too_large"
        const val SIGNED_OUT = "local.signed_out"
        const val NO_VERSION = "local.no_version"
    }

    /** 响应 → 档。412 先判；其余先看码、再看状态码（KnowledgeRules.swift:308）。 */
    fun outcome(r: KbWriteResponse): KbOutcome {
        if (r.status == 0) {
            return when (r.code) {
                LocalCode.CANCELLED -> KbOutcome.Cancelled
                LocalCode.IO -> KbOutcome.Local(r.detail.trim().ifEmpty { "I/O" })
                LocalCode.UNREADABLE -> KbOutcome.Unreadable
                LocalCode.TOO_LARGE -> KbOutcome.TooLarge
                // 请求闸只在「未登录」时拦：对人说就是登录失效。
                LocalCode.SIGNED_OUT -> KbOutcome.Auth
                LocalCode.NO_VERSION -> KbOutcome.NoVersion
                else -> KbOutcome.Offline
            }
        }
        if (r.status in 200..299) return KbOutcome.Ok
        if (r.status == 412) return KbOutcome.ChangedSince
        when (r.code) {
            "feature_disabled" -> return KbOutcome.Disabled
            "not_found" -> return KbOutcome.Missing
            "duplicate" -> return KbOutcome.Duplicate
            "state_conflict" -> return KbOutcome.Busy
            // 先于 413 兜底：没有这一行，存储满了会被说成「超过单个文件上限」。
            "storage_full" -> return KbOutcome.StorageFull(r.used, r.limit, r.incoming)
            "file_too_large", "body_too_large" -> return KbOutcome.TooLarge
            "session_invalid", "bad_token", "unauthorized" -> return KbOutcome.Auth
            // 403 not_owner：凭证认得出是谁，只是主人才能做 —— 说「登录失效」他去重新登录回来还是一样。
            "not_owner" -> return KbOutcome.Failed(r.detail.trim().ifEmpty { "HTTP ${r.status}" })
        }
        return when (r.status) {
            401, 403 -> KbOutcome.Auth
            404 -> KbOutcome.Missing
            413 -> KbOutcome.TooLarge
            else -> KbOutcome.Failed(r.detail.trim().ifEmpty { "HTTP ${r.status}" })
        }
    }

    /** 档 → 字符串目录 key。Ok / Cancelled 回空 key（不说话）。 */
    fun messageKey(o: KbOutcome): String = when (o) {
        KbOutcome.Ok -> ""
        KbOutcome.ChangedSince -> "kb.fail.changedSince"
        KbOutcome.Disabled -> "kb.fail.disabled"
        KbOutcome.Missing -> "kb.fail.missing"
        KbOutcome.Duplicate -> "kb.fail.duplicate"
        KbOutcome.Busy -> "kb.fail.busy"
        KbOutcome.TooLarge -> "kb.fail.tooLarge"
        is KbOutcome.StorageFull -> "kb.fail.storageFull"
        KbOutcome.Offline -> "kb.fail.offline"
        KbOutcome.Cancelled -> ""
        is KbOutcome.Local -> "kb.fail.local"
        KbOutcome.Unreadable -> "kb.readFail"
        KbOutcome.NoVersion -> "kb.fail.noVersion"
        KbOutcome.Auth -> "kb.fail.auth"
        is KbOutcome.Failed -> "kb.fail.server"
    }

    /** 删除要带的 If-Match：去首尾空白后非空才算有；没有回 null —— **调用方此时不许发删除**。 */
    fun ifMatch(blobSha: String?): String? = blobSha?.trim()?.takeIf { it.isNotEmpty() }

    /** 兜底轮询这一次要不要拉：库没了 / 没启用 / 登录失效这三档重拉多少次都不会好，停下来；断网、5xx 照拉。 */
    fun shouldPoll(anyBusy: Boolean, last: KbOutcome): Boolean {
        if (!anyBusy) return false
        return when (last) {
            KbOutcome.Missing, KbOutcome.Disabled, KbOutcome.Auth -> false
            else -> true
        }
    }

    /**
     * 没拿到响应时的那个异常 → 分档原料（status 0 + 本地码）。协程取消 → cancelled（不提示）；
     * 网络层 IOException → 不带码（落到 offline）；读选中的文件出事 → unreadable / tooLarge；其余 → io，带原话。
     */
    fun transport(e: Throwable): KbWriteResponse {
        fun r(code: String?) = KbWriteResponse(0, code, null, e.message ?: e.javaClass.simpleName)
        return when (e) {
            is CancellationException -> r(LocalCode.CANCELLED)
            is KbFileError -> r(if (e.kind == KbFileError.Kind.TooLarge) LocalCode.TOO_LARGE else LocalCode.UNREADABLE)
            is IOException -> r(null)
            else -> r(LocalCode.IO)
        }
    }

    /** 上传前的那道挡：超过上限的不发（发了也是 413，白占流量）。 */
    fun overLimit(bytes: Long): Boolean = bytes > UPLOAD_LIMIT_MB.toLong() * 1024 * 1024

    /** 字节 → 人话（文档行副标题里的大小；同 PC humanBytes）。负数回空串。 */
    fun humanBytes(n: Long): String {
        if (n < 0) return ""
        if (n < 1024) return "$n B"
        if (n < 1024 * 1024) return "${(n / 1024.0).roundToLong()} KB"
        return String.format(Locale.ROOT, "%.1f MB", n / 1024.0 / 1024.0)
    }

    /** `/kb/{kb}/raw/<path>`：**逐段**编码，`/` 保留（服务端路由是 {path:path}）。 */
    fun rawPath(kbId: String, path: String): String =
        "/kb/${segment(kbId)}/raw/" + path.split("/").joinToString("/") { segment(it) }

    /**
     * 单段路径编码：只放行 RFC 3986 unreserved（ASCII 字母数字 `-._~`），其余按 UTF-8 字节百分号编码。
     * ⚠️ 整段是 `.` / `..` 的编成 `%2E` / `%2E%2E`（kb-raw-dot-segment）：字面点段会被下游的路径规范化折叠掉，
     * 请求打到另一个文件上。只认**整段**：`a.b`、`...` 不动。
     */
    fun segment(s: String): String {
        if (s == ".") return "%2E"
        if (s == "..") return "%2E%2E"
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 0x80 && (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-._~")) sb.append(ch)
            else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
        }
        return sb.toString()
    }

    /**
     * 读选中的文件（Android：ContentResolver 的流），边读边数，超过 [limitBytes] 立刻停（大小元数据可能是 0 / 说谎）。
     * 对应 iOS KbMultipart.assemble 的「读的过程中数着」那道。读不到 → Unreadable；超了 → TooLarge。
     */
    fun readCapped(input: InputStream, limitBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        var read = 0L
        try {
            input.use { s ->
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    read += n
                    if (read > limitBytes) throw KbFileError(KbFileError.Kind.TooLarge)
                    out.write(buf, 0, n)
                }
            }
        } catch (e: KbFileError) {
            throw e
        } catch (e: IOException) {
            throw KbFileError(KbFileError.Kind.Unreadable)
        }
        return out.toByteArray()
    }

    // MARK: 选完文件之后：收不收、撞没撞名、两个都保留时叫什么

    /** 后缀（不带点、小写）。拆法照服务端 posixpath.splitext。 */
    fun ext(name: String): String = splitExt(name).second.drop(1).lowercase()

    /** 进不进得了知识库。不分大小写；没后缀的一律不收。 */
    fun isPickable(name: String): Boolean = ext(name) in pickableExts

    /**
     * 上传前查同名。existing = 库里现有文档的路径；null = 列表没拉到。
     * 先认**逐字**相同的（Kotlin 的 == 按 UTF-16 码元比，不做规范等价，等同于按字节比 —— 回的永远是库里那一串），
     * 没有再认折叠后（NFC + 小写）相同的。只和同一层比：路径就是文件名，子目录里的同名不冲突。
     */
    fun nameCheck(name: String, existing: List<String>?): KbNameCheck {
        existing ?: return KbNameCheck.Unknown
        existing.firstOrNull { it == name }?.let { return KbNameCheck.Clash(it) }
        val key = foldKey(name)
        existing.firstOrNull { foldKey(it) == key }?.let { return KbNameCheck.Clash(it) }
        return KbNameCheck.Free
    }

    /** 「两个都保留」：`名 (2).扩展名`，有 (2) 就 (3)……名字自带 (2019) 不剥（认不出是不是副本号）。 */
    fun keepBothName(name: String, existing: List<String>): String {
        val taken = existing.map { foldKey(it) }.toHashSet()
        val (stem, ext) = splitExt(name)
        var n = 2
        while (true) {
            val cand = "$stem ($n)$ext"
            if (foldKey(cand) !in taken) return cand
            n++
        }
    }

    /** 查同名 + 人的选择 → 这一份传到哪个名字上；null = 不传。 */
    fun uploadName(name: String, check: KbNameCheck, choice: KbClashChoice, existing: List<String>): String? = when {
        check == KbNameCheck.Free -> name
        choice == KbClashChoice.Cancel -> null
        check is KbNameCheck.Clash && choice == KbClashChoice.Overwrite -> check.existing
        check is KbNameCheck.Clash && choice == KbClashChoice.KeepBoth -> keepBothName(name, existing)
        check == KbNameCheck.Unknown && choice == KbClashChoice.Overwrite -> name
        else -> null
    }

    private fun foldKey(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    /** posixpath.splitext：最后一个点；点前面全是点（或空）的不算后缀。 */
    private fun splitExt(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || name.substring(0, dot).all { it == '.' }) return name to ""
        return name.substring(0, dot) to name.substring(dot)
    }

    /** 上传结束后：说哪句、刷不刷列表（KnowledgeRules.swift:614）。只有「请求肯定没发出去」才不刷。 */
    fun uploadEnd(r: KbWriteResponse, indexed: Boolean?, changed: Boolean?, taskCancelled: Boolean): KbUploadEnd {
        val o = outcome(r)
        if (o == KbOutcome.Ok) {
            if (indexed == false) return KbUploadEnd(KbUploadEnd.Say.Unindexed, true)
            if (changed == false) return KbUploadEnd(KbUploadEnd.Say.Unchanged, true)
            return KbUploadEnd(KbUploadEnd.Say.Uploaded, true)
        }
        val neverSent = r.status == 0 && (r.code == LocalCode.UNREADABLE || r.code == LocalCode.TOO_LARGE)
        if (taskCancelled) return KbUploadEnd(KbUploadEnd.Say.Cancelled, !neverSent)
        return when (o) {
            KbOutcome.Cancelled -> KbUploadEnd(KbUploadEnd.Say.Silent, true)
            KbOutcome.TooLarge -> KbUploadEnd(KbUploadEnd.Say.TooLarge, !neverSent)
            else -> KbUploadEnd(KbUploadEnd.Say.Fail(o), !neverSent)
        }
    }
}

sealed interface KbNameCheck {
    data object Free : KbNameCheck
    /** 带的是**库里那份的真实路径** —— 选「覆盖」时要传到这个路径上，服务端才是真的覆盖。 */
    data class Clash(val existing: String) : KbNameCheck
    data object Unknown : KbNameCheck
}

enum class KbClashChoice { Overwrite, KeepBoth, Cancel }

/** 一次上传结束之后的那一步。 */
data class KbUploadEnd(val say: Say, val reload: Boolean) {
    sealed interface Say {
        data object Uploaded : Say
        data object Unchanged : Say
        data object Unindexed : Say
        data object Cancelled : Say
        data object Silent : Say
        data object TooLarge : Say
        data class Fail(val o: KbOutcome) : Say
    }
}

/**
 * 文档列表的「第几次加载」（kb-docs-load-out-of-order）：轮询与删后刷新可能乱序回来，被删那份会被画回去。
 * 发出前领号，回来时只认最后领的那个号。
 */
class KbLoadSeq {
    var issued = 0; private set
    fun next(): Int { issued += 1; return issued }
    fun isLatest(ticket: Int): Boolean = ticket == issued
}

/**
 * 预览下到本地的原文放在 `cacheDir/kb-preview/<uuid>/<文件名>`。每次新下载前顺手扫一遍：比 [STALE_AFTER_MS] 老的子目录整个删掉
 * （kb-preview-temp-leak：合同、病历这类原文不该一直躺在缓存里）。按年龄删，不会把正在看的那份从底下抽走。
 */
object KbPreviewTemp {
    const val STALE_AFTER_MS = 10 * 60 * 1000L

    fun root(cache: File): File = File(cache, "kb-preview")

    fun sweep(root: File, nowMs: Long = System.currentTimeMillis(), olderThanMs: Long = STALE_AFTER_MS): Int {
        val subs = root.listFiles() ?: return 0
        var n = 0
        for (d in subs) {
            if (d.name.startsWith(".")) continue
            if (nowMs - d.lastModified() > olderThanMs && d.deleteRecursively()) n++
        }
        return n
    }
}
