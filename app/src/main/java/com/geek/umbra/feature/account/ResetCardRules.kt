// 「就地用重置卡」的纯判据 —— 照 UmbraPC `src/features/shop/resetCard.ts`（sam 2026-10-21：「应该在会员里直接显示重置按钮，
// 点击后直接扣除重置卡，如果重置卡不足，则提示去购买，重置按钮仅在超过80%才显示」；另外「在额度不足的地方还不能用」；设计侧批次 278）。
//
// 为什么照 PC 不照 iOS：iOS 32de292 起不卖数字商品，额度用完只递手里已有的卡、没有「去购买」；Android 照卖重置卡（sam 10-21），
// 和 PC 是同一套：会员额度那两行 80% 起出［重置］、额度用完的卡上出［用重置卡］+［去购买］。
// 挑哪张卡与服务端 `duty.reset_card_for` / `use_item` 同口径：可用（owned）、是重置卡、窗口对得上、没过期；最终以服务端为准。
//
// ⚠️ 只许纯 Kotlin：`ResetCardRulesTest` 照抄 PC `tests/resetCard.test.ts` / `resetCard278.test.ts` 的纯函数那几条。
package com.geek.umbra.feature.account

import java.time.Instant

/** 两条窗口张数的快照（null = 不知道），以及当时的额度状态（错误里没说窗口时按它猜是哪条）。 */
data class CardCounts(val fiveH: Int?, val week: Int?, val quota: Map<String, QuotaWindowDTO>?) {
    operator fun get(kind: String): Int? = if (kind == "5h") fiveH else week
    fun with(kind: String, n: Int) = if (kind == "5h") copy(fiveH = n) else copy(week = n)
}

object ResetCardRules {
    /** 额度用到这个百分比才出「重置」（sam 原话「仅在超过80%才显示」，80 本身算）。 */
    const val RESET_SHOW_PCT = 80
    val WINDOWS = listOf("5h", "week")

    /** 这一条额度要不要出「重置」：窗口开着（`resets_at` 有值）且用到 80% 以上。 */
    fun canShowReset(q: QuotaWindowDTO?): Boolean = q != null && !q.resets_at.isNullOrEmpty() && q.pct >= RESET_SHOW_PCT

    private fun expiresAt(i: ShopItemDTO): Instant? = i.expires_at?.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() ?: PayRules.parseIso(it) }

    private fun usable(i: ShopItemDTO, kind: String, now: Instant): Boolean =
        i.state == "owned" && i.kind == "reset_card" && i.window_kind == kind && (expiresAt(i)?.let { it > now } ?: true)

    /** 背包里一张能用在 `kind` 窗口上的重置卡（快过期的先用；不过期的排最后）。没有 = null。 */
    fun pickResetCard(items: List<ShopItemDTO>, kind: String, now: Instant): ShopItemDTO? =
        items.filter { usable(it, kind, now) }.minByOrNull { expiresAt(it) ?: Instant.MAX }

    /** 服务端在 `quota_exhausted` 的错误体 / 失败 meta 里带的 `window`（服务端 1980e92）→ 窗口种类；不认得 / 没带 = null。 */
    fun quotaWindowOf(v: String?): String? = v?.takeIf { it == "5h" || it == "week" }

    /** 能用在 `kind` 窗口上的重置卡有几张。服务端在 `quota[kind].reset_cards` 给了就用它（4c22ca9），否则数背包；都没有 = null。 */
    fun resetCardsLeft(q: QuotaWindowDTO?, items: List<ShopItemDTO>?, kind: String, now: Instant): Int? {
        q?.reset_cards?.takeIf { it >= 0 }?.let { return it }
        return items?.count { usable(it, kind, now) }
    }

    /** 撞到「额度用完」时该重置哪一条：用完的那条（5 小时先问，同服务端 `check` 的顺序）；都没标用完就取用得多的那条。没会员 = null。 */
    fun exhaustedKind(quota: Map<String, QuotaWindowDTO>?): String? {
        quota ?: return null
        for (k in WINDOWS) if (quota[k]?.exhausted == true) return k
        return if ((quota["5h"]?.pct ?: 0) >= (quota["week"]?.pct ?: 0)) "5h" else "week"
    }

    /** 用完一张之后这条窗口还剩几张：用卡接口回的会员状态里有 `reset_cards` 就用它，没有就「用之前 - 1」，不小于 0。 */
    fun cardsLeftAfter(status: MembershipDTO?, kind: String, before: Int): Int =
        status?.quota?.get(kind)?.reset_cards?.takeIf { it >= 0 } ?: maxOf(0, before - 1)

    /** 「用重置卡」出不出（批次 278 §二 ②）：不知道张数就出（点了再说）；知道是 0 才不出。`window` 是错误里服务端说的那条，没说就按额度状态猜。 */
    fun showUseCard(window: String?, c: CardCounts?): Boolean {
        c ?: return true
        val k = window ?: exhaustedKind(c.quota) ?: return true
        val n = c[k]
        return n == null || n > 0
    }

    /** 窗口的显示名（同账号页那两行、「我的道具」那一套）。 */
    fun windowName(kind: String): String = ItemRules.windowLabel(kind)
}
