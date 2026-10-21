// 「用电脑转换」的纯判据（设计侧批次 269 §四 / 271 / 272，iOS `Features/Convert/ConvertRules.swift` 逐条照抄）。
//
// 主 App（设备详情页「用这台电脑转换文件」）和分享透明 Activity（第一屏）共用同一张面板，判据都在这里。
// ⚠️ 只许纯 Kotlin，不许 import android.*：`ConvertRulesTest` 直接跑它，用例照抄 iOS `Tools/swifttests/ConvertRulesTests.swift`。
// 钉的几样都是「错了也不报错」的那种：成品名是落盘路径；横条挑错一件的样子是挂着别的手机的事、或者一件已经看过的事。
package com.geek.umbra.feature.convert

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.roundToInt

/** 要转换的那一个文件（分享进来的文件 / 一张图 / App 里从「文件」挑的那一个）。`name` 带扩展名：电脑按它判格式。 */
class ConvertSource(val name: String, val data: ByteArray)

/** 面板拿到的东西：读进来了，或者太大根本没读（只记名字和大小，面板第一屏直接说「太大」）。 */
sealed interface ConvertInput {
    val name: String
    val bytes: Int

    data class File(val src: ConvertSource) : ConvertInput {
        override val name get() = src.name
        override val bytes get() = src.data.size
    }

    data class TooLarge(override val name: String, override val bytes: Int) : ConvertInput
}

/** 成品是哪一类：决定结果页给哪几颗钮（图片 / 视频多一颗「存到相册」排第一）。 */
enum class ConvertKind { Image, Video, Document }

/**
 * 这台手机发起过的一件转换（存在 `filesDir/convert/jobs.json`：分享 Activity 开的作业，回到 App 时也要认得出是「我发起的」）。
 * 为什么要在本机记：服务端 `GET /convert/jobs` 列的是**这个人**的全部作业，不分是哪台手机开的 ——
 * 而横条「X 转好了」只该挂在发起的那台手机上（批次 269 §四）。记下自己开过的 id，拿服务端那张表一比就够了。
 */
@Serializable
data class ConvertJobRecord(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val name: String,
    val to: String,
    /** 开作业那一刻（毫秒）。 */
    val startedMs: Long,
    /** 结果人已经看过了（面板当场看到 / 点过横条）：横条不再挂它。 */
    val seen: Boolean = false,
)

/** 服务端 `GET /convert/jobs` 里一件作业，横条要用的那几格。 */
data class ConvertJobBrief(val id: String, val status: String, val deviceId: String, val name: String, val resultName: String?, val updatedMs: Long)

/** 设备详情页顶上那一条要说的事：「合同.pdf 转好了」/「合同.docx 没转成」。 */
data class ConvertBanner(val jobId: String, val name: String, val ok: Boolean)

object ConvertRules {
    /** 服务端 `/files/upload` 的上限（`files.MAX_UPLOAD_BYTES`，32 MiB）。超了在端上先说，不白传一趟。 */
    const val MAX_BYTES = 32 * 1024 * 1024
    /** 上限的 MB 数（文案里那个「32」，由上一个数算出来）。 */
    const val MAX_MB = MAX_BYTES / 1024 / 1024
    /** 轮询作业的间隔（毫秒）。 */
    const val POLL_MS = 1_500L
    /**
     * 「X 没回话」那句里的秒数：问电脑能转什么是服务端**同步派活**，等电脑最多 `device_registry.TASK_TIMEOUT_SECONDS` = 60 秒，
     * 过了回 502 `upstream_failed`。稿上的 15 秒是设计侧占位（批次 269 §四「请按通道的实际超时改」）。
     */
    const val ASK_TIMEOUT_SECONDS = 60
    /** 那一条请求本身的超时：比服务端那 60 秒多 10 秒，让服务端的 502 先到（两边都算「没回话」）。 */
    const val ASK_REQUEST_TIMEOUT_MS = 70_000L
    /** 转换中连着几次看不到那台电脑在线，就算它断开了。一次不算：长连接重连那一下会短暂掉线。 */
    const val LOST_AFTER_MISSES = 2
    /** 本机记着的作业留多久：成品文件 24 小时后被服务端清（`files.ORPHAN_GRACE_MS`），过了再挂横条只会点进「找不到了」那一屏。 */
    const val RECORD_KEEP_MS = 24L * 3600 * 1000
    /** 格子里排在前面的那几种（同 PC 窗口 `formatConvert.window.formats`），其余按字母。 */
    val COMMON_FIRST = listOf("pdf", "jpg", "png", "docx", "mp4", "mp3")

    /** 文件名的扩展名（小写，不带点）。同 NSString.pathExtension：只看最后一段路径、点开头的隐藏名不算扩展名。 */
    fun ext(name: String): String {
        val last = name.substringAfterLast('/')
        val i = last.lastIndexOf('.')
        return if (i <= 0 || i == last.length - 1) "" else last.substring(i + 1).lowercase()
    }

    /** 文件卡左边那块方章里的字：扩展名大写，最多 4 个字符；没有扩展名写 FILE。 */
    fun badge(name: String): String = ext(name).let { if (it.isEmpty()) "FILE" else it.uppercase().take(4) }

    /** 成品落在本机缓存目录里的名字：服务端给的名字只取最后一段，防 `../`。 */
    fun localName(serverName: String, fallbackExt: String): String {
        val base = serverName.split('/', '\\').lastOrNull { it.isNotEmpty() }.orEmpty()
        val clean = base.trim()
        return if (clean.isEmpty() || clean.startsWith(".")) "converted.$fallbackExt" else clean
    }

    /** 成品是哪一类（按扩展名）。认不出的都算文档：多给一颗「存到相册」点了存不进去，比少给一颗更糟。 */
    fun kind(name: String): ConvertKind = when (ext(name)) {
        "jpg", "jpeg", "png", "heic", "heif", "gif", "webp", "avif", "tif", "tiff", "bmp" -> ConvertKind.Image
        "mp4", "mov", "m4v" -> ConvertKind.Video
        else -> ConvertKind.Document
    }

    /** 字节数 → 「2.4 MB」/「820 KB」（同 PC 窗口那几行；不到 1 KB 也写 1 KB）。 */
    fun sizeText(bytes: Int): String {
        val mb = bytes / 1_048_576.0
        if (mb >= 100) return "${mb.roundToInt()} MB"
        if (mb >= 1) return String.format(java.util.Locale.ROOT, "%.1f MB", mb)
        return "${maxOf(1, (bytes / 1024.0).roundToInt())} KB"
    }

    /** 格子排序：常用的几种排前（按 [COMMON_FIRST] 的顺序），其余按目标格式字母序。 */
    fun <T> ordered(items: List<T>, to: (T) -> String): List<T> = items.sortedWith { a, b ->
        val x = to(a).lowercase()
        val y = to(b).lowercase()
        val i = COMMON_FIRST.indexOf(x).let { if (it < 0) Int.MAX_VALUE else it }
        val j = COMMON_FIRST.indexOf(y).let { if (it < 0) Int.MAX_VALUE else it }
        if (i != j) i.compareTo(j) else x.compareTo(y)
    }

    /**
     * 问到格子之后选中哪一格：原来选着的（或「作业没了」重转时带来的上一次目标格式）这台还能转就留着，否则落到第一格；
     * 一格都没有就是 null（「开始转换」灰着）。批次 272：预选的格式这台这次可能转不了了，留着它主钮亮着却点不出东西。
     */
    fun preselect(current: String?, available: List<String>): String? =
        if (current != null && current in available) current else available.firstOrNull()

    /**
     * 第二段（电脑转换）的比例。PC 报的 `progress` 是整件事的：取文件 0 → 转换 0.1 + 0.8 × 进度 → 传回 0.95。
     * 只有转换那一截有真百分比，其余时候回 null 画不定进度 —— 把「正在取文件」的 0 画成「0%」会让人以为卡住了。
     */
    fun convertFraction(p: Double?): Double? {
        if (p == null || p < 0.1 || p > 0.9) return null
        return ((p - 0.1) / 0.8).coerceIn(0.0, 1.0)
    }

    /** 百分比整数（0–100），向下取整：没到不写 100。 */
    fun percent(f: Double): Int = (f.coerceIn(0.0, 1.0) * 100).toInt()

    /**
     * `/devices/all` 的 `last_seen`：Python `isoformat()`（6 位小数秒 +「+00:00」）或 SQLite 的 `2026-08-08 14:15:09`（UTC、没有时区后缀）。
     * 认不出 → null（副行只写「离线」）。
     */
    fun parseDate(s: String?): Instant? {
        var t = s?.trim().orEmpty()
        if (t.isEmpty()) return null
        t = t.replace(Regex("\\.\\d+"), "").replace(" ", "T")
        return runCatching {
            if (Regex("(Z|[+-]\\d{2}:?\\d{2})$").containsMatchIn(t)) OffsetDateTime.parse(t.replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")).toInstant()
            else LocalDateTime.parse(t).toInstant(ZoneOffset.UTC)
        }.getOrNull()
    }

    /**
     * 进度屏那句保留时长里「合同.pdf 转好了」的名字（批次 271）：原文件名去掉扩展名 + 目标格式的小写扩展名。
     * 拼不出 → null，界面换不带名字的那句（写成「.pdf 转好了」比不写名字更糟）。
     */
    fun expectedResultName(source: String, to: String): String? {
        val dot = source.lastIndexOf('.')
        val base = (if (dot > 0) source.substring(0, dot) else source).trim()
        val e = to.trim().lowercase()
        if (base.isEmpty() || e.isEmpty()) return null
        return "$base.$e"
    }

    private val intelRe = Regex("^(.+) 是 Intel 版$")

    /**
     * 「X 上转不了」那几行的原因（批次 271）：PC 回的「Pandoc 是 Intel 版」改写成「书房 Mac 上的 Pandoc 是 Intel 版，用不了」
     * （`intelFormat` 是文案表里那句，`%1$s` 电脑名、`%2$s` 工具名）。其它原样；空的给 `fallback`；电脑名空的也原样。
     */
    fun blockedReason(reason: String?, device: String, intelFormat: String, fallback: String): String {
        val r = reason.orEmpty().trim()
        if (r.isEmpty()) return fallback
        val dev = device.trim()
        val m = intelRe.matchEntire(r) ?: return r
        if (dev.isEmpty()) return r
        val tool = m.groupValues[1].trim()
        if (tool.isEmpty()) return r
        return String.format(intelFormat, dev, tool)
    }

    // MARK: 本机记着的作业

    /** 记一件新开的作业：同 id 的替换，顺手把过期的扔掉。 */
    fun remember(r: ConvertJobRecord, list: List<ConvertJobRecord>, nowMs: Long): List<ConvertJobRecord> =
        prune(list.filter { it.id != r.id } + r, nowMs)

    /** 扔掉超过 [RECORD_KEEP_MS] 的。 */
    fun prune(list: List<ConvertJobRecord>, nowMs: Long) = list.filter { nowMs - it.startedMs <= RECORD_KEEP_MS }

    /** 标成看过。 */
    fun markSeen(id: String, list: List<ConvertJobRecord>) = list.map { if (it.id == id) it.copy(seen = true) else it }

    /**
     * 设备详情页顶上那一条：**这台手机发起的**、在这台电脑上、已经结束、还没看过的作业里最新的一件。
     * 正在转的不挂；不是本机开的不挂（另一台手机的事）。
     */
    fun banner(records: List<ConvertJobRecord>, jobs: List<ConvertJobBrief>, deviceId: String): ConvertBanner? {
        val mine = records.associateBy { it.id }
        val hit = jobs.filter { j ->
            val r = mine[j.id] ?: return@filter false
            !r.seen && j.deviceId == deviceId && (j.status == "done" || j.status == "failed")
        }.maxByOrNull { it.updatedMs } ?: return null
        val ok = hit.status == "done"
        val name = if (ok) hit.resultName?.takeIf { it.isNotEmpty() }?.let { localName(it, "") } ?: hit.name else hit.name
        return ConvertBanner(hit.id, name, ok)
    }

    // MARK: 推送

    private val idRe = Regex("^[A-Za-z0-9-]{1,128}$")

    /**
     * 从推送数据里认出「格式转换结束了」那一条，回作业 id；不是 → null。服务端把自定义键放根上（`convert_job`）。
     * id 只收 ASCII 字母数字和连字符、1–128 位 —— 它下一步要拼进 `/convert/jobs/{id}`。
     * Android 今天还收不到这条推送（FCM 待服务端），判据先备着，接上推送时直接用。
     */
    fun pushJob(info: Map<String, Any?>): String? = (info["convert_job"] as? String)?.takeIf { idRe.matches(it) }
}
