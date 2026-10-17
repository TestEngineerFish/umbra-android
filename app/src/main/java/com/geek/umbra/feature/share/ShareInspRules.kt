// 分享面板「记到 Umbra 灵感」的纯判据 —— 照抄 iOS ShareExtension/ShareInspirationRules.swift（idea umbra-ios#24 第二步）。
// 纯 Kotlin，配 JUnit 用例 ShareInspRulesTest.kt（照抄 Tools/swifttests/ShareInspirationRulesTests.swift）。
// 文案只给 key（"share.insp.x" ↔ R.string.share_insp_x，见 res/values/strings_share.xml）。
//
// 为什么单拎出来（iOS 文件头）：
// 1. 分享一个链接时同时给了链接和纯文本（常常就是链接本身 / 页面标题）：怎么拼是一条规则，不是一段 UI 代码；
// 2. 图最多 4 张（服务端 MAX_ATTS，超了整条 409）：端上不裁就整条记不上，裁了不说人以为都在；
// 3. 失败要如实说、成功才关面板：面板一关人就回到原来的 App，没有草稿可找。
package com.geek.umbra.feature.share

import com.geek.umbra.feature.inspiration.InspCreateOutcome
import com.geek.umbra.feature.inspiration.InspIntentGate
import com.geek.umbra.feature.inspiration.InspIntentReply
import com.geek.umbra.feature.inspiration.InspIntentRules
import com.geek.umbra.feature.inspiration.InspTransportError
import com.geek.umbra.net.AttLimit
import com.geek.umbra.net.UploadError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 分享面板能给出的结果。比意图多一档 ImageTooLarge：意图不带图，这里带。 */
sealed interface ShareSaveOutcome {
    /** 灵感已存上。**只有这一档关面板。** */
    data object Saved : ShareSaveOutcome
    /** 某张图在 /files/upload 那一步被 413 拒了。下一步是「去掉那张」，和「连不上 → 稍后再试」相反。 */
    data object ImageTooLarge : ShareSaveOutcome
    /** 其余沿用意图那张分档表 —— 两个页外入口对同一个失败说同一种话。 */
    data class Create(val outcome: InspCreateOutcome) : ShareSaveOutcome
}

object ShareInspRules {
    /** 图上限。不另写一个 4：与记一笔、提醒、PC、服务端同一个数。 */
    val maxImages: Int get() = AttLimit.MAX

    /**
     * 只有图、没有一个字时落进 raw 的那一句。服务端 raw 是 min_length=1（空正文 422），
     * 而「分享一张截图过来记一下」最常见。它是**存进数据里的字**，不随界面语言变。
     */
    const val IMAGE_ONLY_RAW = "（分享的图片）"

    /**
     * 文字 + 链接 → 正文（iOS: ShareInspirationRules.swift:49）。
     * - 文字按出现顺序、去首尾空白、去空串和重复；
     * - **一段文字如果整个就是某条链接**，丢掉 —— 链接下面统一补；
     * - 链接拼在正文后、各占一行；正文里已经出现过的链接不再补。
     */
    fun composeText(texts: List<String>, urls: List<String>): String {
        val links = urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val linkSet = links.toSet()
        val body = texts.map { it.trim() }.filter { it.isNotEmpty() && it !in linkSet }.distinct()
        val joined = body.joinToString("\n")
        val tail = links.filter { !joined.contains(it) }
        return (body + tail).joinToString("\n")
    }

    /**
     * Android 独有：系统分享不像 iOS 分开给 URL / 纯文本两个 provider，只有 EXTRA_TEXT（和可能的 EXTRA_SUBJECT）。
     * 浏览器分享网页时 EXTRA_TEXT 就是那条链接 —— 整段是一个 http(s) 链接的认成「链接」，其余认成「文字」，
     * 再交给 [composeText]，和 iOS 「URL 与纯文本两样都取」得到同一个结果。
     */
    fun isLink(s: String): Boolean {
        val t = s.trim()
        return (t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)) &&
            t.length > 8 && t.none { it.isWhitespace() }
    }

    /** 选进来的图按上限裁：前 maxImages 张留下（按分享顺序），后面的数出来给那一句提示。和 AttLimit.fit 同一口径。 */
    fun <T> pickImages(items: List<T>): Pair<List<T>, Int> {
        val kept = AttLimit.fit(items, 0)
        return kept to (items.size - kept.size)
    }

    /** 裁掉几张时那一句的 key（带一个整数参数）；没裁回 null。 */
    fun droppedKey(dropped: Int): String? = if (dropped > 0) "share.insp.dropped" else null

    /** 点「保存」那一刻放不放行。**守卫在前**；之后比意图多一条：没字但有图 → 放行，正文落 IMAGE_ONLY_RAW。 */
    fun gate(locked: Boolean, text: String, imageCount: Int): InspIntentGate {
        val g = InspIntentRules.gate(locked, text)
        if (g == InspIntentGate.Empty && imageCount > 0) return InspIntentGate.Send(IMAGE_ONLY_RAW)
        return g
    }

    /** POST /inspirations 请求体：意图那一份（带 source_channel）+ 配图。没有图时**不带 atts 这个键**（服务端三态里「不传 = 没有」）。 */
    fun createBody(raw: String, research: Boolean, fileIds: List<String>): JsonObject {
        val base = InspIntentRules.createBody(raw, "", "", emptyList(), research, InspIntentRules.sourceChannel)
        if (fileIds.isEmpty()) return base
        return JsonObject(base + ("atts" to buildJsonArray {
            fileIds.forEach { id -> add(buildJsonObject { put("file_id", id); put("label", "") }) }
        }))
    }

    /** /files/upload 回了 HTTP 响应：null = 这张传上了；413 单拎（去掉那张），其余照意图那张表。 */
    fun uploadOutcome(status: Int): ShareSaveOutcome? {
        if (status == 413) return ShareSaveOutcome.ImageTooLarge
        val o = InspIntentRules.outcome(status)
        return if (o == InspCreateOutcome.Saved) null else ShareSaveOutcome.Create(o)
    }

    /**
     * Android 独有：上传走 [com.geek.umbra.net.Uploads.upload]，它把状态码收成了 [UploadError]（与记一笔、提醒同一份分档）。
     * 映射回分享这张表：太大 → 去掉那张；未登录 / 会话失效 → 去登录；连不上 → 连不上；
     * 存储满 → 被拒（原样重试也没用）；其余（5xx / 别的 4xx / 解不出回包）→ 「没记上，可以再试一次」。
     */
    fun uploadOutcome(err: UploadError): ShareSaveOutcome = when (err) {
        UploadError.FileTooLarge, UploadError.BodyTooLarge -> ShareSaveOutcome.ImageTooLarge
        UploadError.Unauthorized, UploadError.SessionInvalid -> ShareSaveOutcome.Create(InspCreateOutcome.SignedOut)
        UploadError.Offline -> ShareSaveOutcome.Create(InspCreateOutcome.Offline)
        is UploadError.StorageFull -> ShareSaveOutcome.Create(InspCreateOutcome.Rejected(413))
        UploadError.Server -> ShareSaveOutcome.Create(InspCreateOutcome.Failed)
    }

    /**
     * 没拿到 HTTP 响应：照意图那张表；「说不准」折回「连不上」—— 传图那一步超时时建灵感根本没发，说「可能已经记下了」是假话。
     */
    fun outcome(error: InspTransportError): ShareSaveOutcome {
        val o = InspIntentRules.outcome(error)
        return ShareSaveOutcome.Create(if (o == InspCreateOutcome.Uncertain) InspCreateOutcome.Offline else o)
    }

    /** 创建那一步回了 HTTP 响应。 */
    fun createOutcome(status: Int): ShareSaveOutcome {
        val o = InspIntentRules.outcome(status)
        return if (o == InspCreateOutcome.Saved) ShareSaveOutcome.Saved else ShareSaveOutcome.Create(o)
    }

    /** 执行前就被挡下时说哪句。放行那一档不该走到这里，给失败兜底而不是成功。 */
    fun reply(gate: InspIntentGate): InspIntentReply = when (gate) {
        InspIntentGate.SignedOut -> InspIntentReply(false, "share.insp.signedOut")
        InspIntentGate.Empty -> InspIntentReply(false, "share.insp.empty")
        is InspIntentGate.Send -> InspIntentReply(false, "share.insp.failed")
    }

    /**
     * 结果 → 成没成 + 那一句。**只有 Saved 是 ok**，面板据此决定关不关。
     * [hostCanRenew]：iOS 扩展是独立进程，401 时令牌可能留给主 App 去续（umbra-ios#92）。Android 的分享 Activity
     * 和主 App 同进程、同一个 AuthService，没有这一档 —— 参数留着只为用例与 iOS 对齐，调用处恒传 false。
     */
    fun reply(outcome: ShareSaveOutcome, hostCanRenew: Boolean = false): InspIntentReply {
        if (hostCanRenew && outcome == ShareSaveOutcome.Create(InspCreateOutcome.SignedOut)) return InspIntentReply(false, "share.insp.renewInApp")
        return when (outcome) {
            ShareSaveOutcome.Saved -> InspIntentReply(true, "share.insp.saved")
            ShareSaveOutcome.ImageTooLarge -> InspIntentReply(false, "share.insp.tooLarge")
            is ShareSaveOutcome.Create -> when (outcome.outcome) {
                InspCreateOutcome.Saved -> InspIntentReply(true, "share.insp.saved")
                InspCreateOutcome.SignedOut -> InspIntentReply(false, "share.insp.signedOut")
                InspCreateOutcome.NotConfigured -> InspIntentReply(false, "share.insp.notConfigured")
                InspCreateOutcome.Offline, InspCreateOutcome.Uncertain -> InspIntentReply(false, "share.insp.offline")
                is InspCreateOutcome.Rejected -> InspIntentReply(false, "share.insp.rejected")
                is InspCreateOutcome.ServerError -> InspIntentReply(false, "share.insp.serverError")
                InspCreateOutcome.Failed -> InspIntentReply(false, "share.insp.failed")
            }
        }
    }

    /** 上面几个函数会给出的全部 key —— 用例核对「给出的 = 登记的」，并核对每一句在 strings_share.xml 里都有。 */
    val allKeys = listOf(
        "share.insp.saved", "share.insp.signedOut", "share.insp.empty", "share.insp.notConfigured",
        "share.insp.offline", "share.insp.rejected", "share.insp.serverError", "share.insp.failed",
        "share.insp.tooLarge", "share.insp.dropped", "share.insp.renewInApp",
    )
}
