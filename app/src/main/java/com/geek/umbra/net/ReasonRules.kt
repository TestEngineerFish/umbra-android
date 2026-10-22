// 服务端 `reason_kind` 的端上判定（iOS: Networking/ReasonKind.swift）：出不出「去充值」、能不能重试、读图卡出不出重读、
// 任务失败处给什么动作。
//
// 和 iOS 一样住在网络层：聊天错误卡 / 读图卡、任务详情失败卡、任务通知三处共用这一份。从前聊天（ChatReasonRules）和
// 任务（TaskReason）各抄了一份，判定一致但改一处容易漏另一处，所以合到这里（开发文档第五节技术债）。
// 枚举本身在 NetRules.kt（ReasonKind）。纯判据，用例照抄 iOS Tools/swifttests/ReasonKindTests.swift。
package com.geek.umbra.net

import java.net.URI

/** 任务失败处的动作分档（iOS: ReasonKind.swift:177 `TaskFailAction`）。 */
sealed interface TaskFailAction {
    /** 欠费且服务端给了能打开的充值地址：出「去充值」外链。 */
    data class Topup(val url: String) : TaskFailAction
    /** 限流 / 模型服务抖动 / unknown / 缺字段：值得再跑一次的那一档。 */
    data object AsBefore : TaskFailAction
    /** 其余（key 错、参数被拒、没启用、欠费却没给地址）：不给重试类动作。 */
    data object NoRetry : TaskFailAction
}

object ReasonRules {
    /**
     * 线上字面 → 类别。wire == null = 字段根本没有（老服务端）→ 走兼容分支；"" = 字段在但没有原因 → null。
     * 不认识的值一律 Unknown，**不能**回退去读原话。iOS: ReasonKind.swift:47
     */
    fun resolve(wire: String?, legacyReason: String?): ReasonKind? {
        if (wire == null) {
            if (legacyReason.isNullOrEmpty()) return null
            return if (legacyInsufficient(legacyReason)) ReasonKind.InsufficientBalance else null
        }
        if (wire.isEmpty()) return null
        return ReasonKind.entries.firstOrNull { it.wire == wire } ?: ReasonKind.Unknown
    }

    /** 出不出「去哪儿买」那颗钮。 */
    fun showsTopup(k: ReasonKind) = k == ReasonKind.InsufficientBalance || k == ReasonKind.NeedMembership || k == ReasonKind.QuotaExhausted

    /** 「去哪儿买」那颗钮的文案 key（iOS 点分 key；Android 资源名把 . 换成 _）。 */
    fun topupButtonKey(k: ReasonKind?): String = when (k) {
        ReasonKind.NeedMembership -> "vision.openMembership"
        ReasonKind.QuotaExhausted -> "vision.quotaShop"
        else -> "vision.topup"
    }

    /**
     * 「去充值」该打开哪儿；null = 不出这颗钮。类别得是欠费三类，地址得是服务端给的 http(s) 且有 host。
     * 只收 http(s)：这串最后交给系统浏览器，`javascript:` / 自定义 scheme 一律当没有。iOS: ReasonKind.swift:96
     */
    fun topupLink(kind: ReasonKind?, url: String?): String? {
        if (kind == null || !showsTopup(kind)) return null
        val raw = url?.trim() ?: return null
        val lower = raw.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        val u = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (u.host.isNullOrEmpty()) return null
        return raw
    }

    /** 原样立刻再来一次有没有用。 */
    fun retryable(k: ReasonKind) = k == ReasonKind.RateLimited || k == ReasonKind.UpstreamError || k == ReasonKind.Unknown

    /** 读图错误卡上那颗「重读这 N 张」出不出：可重试类 + 欠费三类（充完值回来还得靠它）；null 照旧出。 */
    fun visionShowsReread(k: ReasonKind?): Boolean = k == null || retryable(k) || showsTopup(k)

    /** 读图错误卡第二段的文案 key；Unknown 回 null（显示原话）。 */
    fun visionCopyKey(k: ReasonKind): String? = when (k) {
        ReasonKind.InsufficientBalance -> "reason.insufficientBalance"
        ReasonKind.NeedMembership -> "reason.needMembership"
        ReasonKind.QuotaExhausted -> "reason.quotaExhausted"
        ReasonKind.RateLimited -> "reason.rateLimited"
        ReasonKind.UpstreamError -> "reason.upstreamError"
        ReasonKind.AuthFailed -> "reason.authFailed"
        ReasonKind.BadRequest -> "reason.badRequest"
        ReasonKind.Unavailable -> "reason.unavailable"
        ReasonKind.Unknown -> null
    }

    /** 任务失败处该给什么动作。缺字段按 unknown（任务这条线从没按原话猜过欠费）。iOS: ReasonKind.swift:153 */
    fun taskFailAction(wire: String?, topupUrl: String?): TaskFailAction {
        val kind = resolve(wire ?: ReasonKind.Unknown.wire, null) ?: ReasonKind.Unknown
        topupLink(kind, topupUrl)?.let { return TaskFailAction.Topup(it) }
        return if (retryable(kind)) TaskFailAction.AsBefore else TaskFailAction.NoRetry
    }

    /** 老办法：在厂商原话里认「欠费」。只在 resolve 的兼容分支里调（缺 reason_kind 的旧服务端），全量升级后删。 */
    fun legacyInsufficient(reason: String): Boolean {
        val lower = reason.lowercase()
        if (listOf("额度", "余额", "资源包", "欠费", "quota", "insufficient", "balance").any { lower.contains(it) }) return true
        return standaloneCode(reason, "429")
    }

    /** 在文本里找一个两边都不挨着数字的码（`4290` / `1429x800` 不算 429）。 */
    fun standaloneCode(text: String, code: String): Boolean {
        if (text.length < code.length) return false
        var i = text.indexOf(code)
        while (i >= 0) {
            val beforeOk = i == 0 || !text[i - 1].isDigit()
            val j = i + code.length
            val afterOk = j >= text.length || !text[j].isDigit()
            if (beforeOk && afterOk) return true
            i = text.indexOf(code, i + 1)
        }
        return false
    }
}
