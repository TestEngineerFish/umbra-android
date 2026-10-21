// 就地用重置卡的运行时（照 UmbraPC `resetCard.ts` 的 spend / 张数缓存 + `QuotaRows.tsx` 的确认 / 没卡两张框，设计侧批次 278）。
//
// 三处共用这一份：账号页会员那两行额度（80% 起出［重置］，先确认）、聊天错误卡 / 读图卡、任务详情失败卡（额度用完出［用重置卡］，不另确认）。
// 判据在 [ResetCardRules]；这里管网络、张数缓存、吐司与落地到商城。
package com.geek.umbra.feature.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import com.geek.umbra.net.ApiResult
import com.geek.umbra.net.BackgroundWork
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

object ResetCardOffer {
    /** 「就地用一张」的结果：用掉了 / 没卡（调用方提示去买）/ 没成（`message` 是服务端那句话）。 */
    sealed interface Outcome {
        /** `window`：用在了哪条窗口；`left`：用完之后这条还剩几张（吐司里写：不确认，所以要让人知道花掉了什么）。 */
        data class Used(val window: String, val left: Int, val status: MembershipDTO?) : Outcome
        data class None(val window: String?) : Outcome
        data class Failed(val message: String) : Outcome
    }

    private val router get() = AppGraph.router
    private fun str(id: Int, vararg a: Any) = AppGraph.context.getString(id, *a)

    /** 「5 小时额度」/「本周额度」（英文界面跟着换）。 */
    fun winName(kind: String) = str(if (kind == "5h") R.string.android_quota_win5h else R.string.android_quota_winWeek)

    // MARK: 张数缓存（批次 278 §二 ②：「用重置卡」那颗钮画的那一刻不能等网络，所以留一份最近问到的张数）

    /** 最近一次问到的两条窗口的张数；null = 还没问过（钮照出，点了再说）。Compose 读它会订阅。 */
    var counts by mutableStateOf<CardCounts?>(null); private set
    private var askedAtMs = 0L
    private var inflight: Deferred<CardCounts?>? = null
    /** 正在用卡（钮上转圈、防连点）。 */
    var spending by mutableStateOf(false); private set

    /** 画「用重置卡」的地方调：离上次去问超过 60 秒才再问一次。 */
    fun maybeRefresh(maxAgeMs: Long = 60_000) {
        if (inflight != null || System.currentTimeMillis() - askedAtMs < maxAgeMs) return
        AppGraph.scope.launch { refresh() }
    }

    /** 张数作废（商城里刚买了卡）：下一次画之前会重新问。 */
    fun invalidate() { counts = null; askedAtMs = 0 }

    /** 问一次两条窗口各有几张（`/membership` 的 `reset_cards`，旧服务端没有就数背包）。同时只问一次；问不到回 null、缓存不动。后台路径。 */
    suspend fun refresh(): CardCounts? {
        inflight?.let { return it.await() }
        askedAtMs = System.currentTimeMillis()
        val d = AppGraph.scope.async {
            withContext(BackgroundWork) {
                val m = AccountApi.membership() ?: return@withContext null
                val q = m.quota
                val items = if (q?.get("5h")?.reset_cards != null && q["week"]?.reset_cards != null) null else AccountApi.shopItems()?.items
                val now = Instant.now()
                CardCounts(ResetCardRules.resetCardsLeft(q?.get("5h"), items, "5h", now), ResetCardRules.resetCardsLeft(q?.get("week"), items, "week", now), q)
                    .also { counts = it }
            }
        }
        inflight = d
        return try { d.await() } finally { inflight = null }
    }

    // MARK: 额度用完的卡上：［用重置卡］（不另确认）

    /**
     * 读会员状态与背包，挑一张对得上的卡用掉。`window` 优先用错误里服务端说的那条（1980e92），不给才按 `/membership` 的 exhausted 猜。
     * 不弹确认 —— 要确认的调用方自己先问（账号页那颗）。
     */
    suspend fun spend(window: String?): Outcome {
        val m = AccountApi.membership() ?: return Outcome.Failed(str(R.string.share_conv_unreachable))
        val items = AccountApi.shopItems()?.items ?: return Outcome.Failed(str(R.string.share_conv_unreachable))
        val k = window ?: ResetCardRules.exhaustedKind(m.quota)
        val now = Instant.now()
        val card = k?.let { ResetCardRules.pickResetCard(items, it, now) } ?: return Outcome.None(k)
        val before = ResetCardRules.resetCardsLeft(m.quota?.get(k), items, k, now) ?: 1
        return when (val r = AccountApi.shopUseItem(card.id)) {
            is ApiResult.Ok -> {
                val left = ResetCardRules.cardsLeftAfter(r.value.status, k, before)
                counts = counts?.with(k, left)
                ShopStore.membership.value = r.value.status
                Outcome.Used(k, left, r.value.status)
            }
            is ApiResult.Err -> Outcome.Failed(r.detail?.takeIf { it.isNotEmpty() } ?: "没用成，稍后再试")
        }
    }

    /** 失败卡上那颗［用重置卡］：转圈防连点 → 用 → 吐司（成功写哪条从 0% 算、还剩几张、下一步点「重试任务」；没卡带［去购买］）。 */
    fun useFromFailCard(window: String?) {
        if (spending) return
        spending = true
        AppGraph.scope.launch {
            try {
                when (val o = spend(window)) {
                    is Outcome.Used -> router.showToast(str(R.string.android_quota_useCardDone, winName(o.window), o.left))
                    is Outcome.None -> router.showToast(str(R.string.android_quota_noCardShort), str(R.string.android_quota_buyCard)) { gotoShopResetCards() }
                    is Outcome.Failed -> router.showToast(o.message)
                }
            } finally { spending = false }
        }
    }

    // MARK: 账号页额度行上：［重置］（先确认）

    /**
     * 点「重置」：现问一次背包（张数可能是旧的），挑一张；有就确认（同「我的道具」那张确认框：已用 N% → 使用后清零、用掉不能退回），
     * 没有就说「没有重置卡了」+［去购买］（落到商城「重置卡与加量包」那一组）。
     */
    fun askReset(kind: String) {
        if (spending) return
        spending = true
        AppGraph.scope.launch {
            try {
                val items = AccountApi.shopItems()?.items
                val m = AccountApi.membership() ?: ShopStore.membership.value
                if (items == null) { router.showToast(str(R.string.share_conv_unreachable)); return@launch }
                val q = m?.quota?.get(kind)
                val card = ResetCardRules.pickResetCard(items, kind, Instant.now())
                if (card == null) {
                    router.confirm(UmbraAlert(
                        title = str(R.string.android_quota_noCardTitle),
                        message = str(R.string.android_quota_noCardBody, winName(kind), MembershipRules.clampPct(q?.pct ?: 0)),
                        confirmLabel = str(R.string.android_quota_buyCard),
                    ) { gotoShopResetCards() })
                    return@launch
                }
                val at = PayRules.parseIso(q?.resets_at)?.let {
                    val z = it.atZone(ZoneId.systemDefault()); "${z.monthValue}月${z.dayOfMonth}日 %02d:%02d".format(z.hour, z.minute)
                } ?: "—"
                router.confirm(UmbraAlert(title = "使用「${card.name}」？", message = ItemRules.confirmBody(card, q, at), confirmLabel = "使用") {
                    AppGraph.scope.launch {
                        when (val r = AccountApi.shopUseItem(card.id)) {
                            is ApiResult.Ok -> {
                                ShopStore.membership.value = r.value.status
                                counts = counts?.with(kind, ResetCardRules.cardsLeftAfter(r.value.status, kind, (counts?.get(kind) ?: 1)))
                                router.showToast(str(R.string.android_quota_resetDone, winName(kind)))
                            }
                            is ApiResult.Err -> router.showToast(r.detail?.takeIf { it.isNotEmpty() } ?: "没用成，稍后再试")
                        }
                    }
                })
            } finally { spending = false }
        }
    }

    /** 落到商城「商品」栏的「重置卡与加量包」那一组（PC `gotoShop({ cat: "reset_card" })`）。 */
    fun gotoShopResetCards() {
        ShopStore.tab.value = 0
        ShopStore.landing.value = ShopRules.RESET_GROUP
        invalidate()
        router.jump(Route.SetShop)
    }
}
