// 积分 / 会员 / 商城 / 我的道具 / 付钱下单的**纯判据**与线上形状 —— 照抄 iOS Networking/CreditsRules.swift
// （与 PC `src/features/settings/{credits,membership,shop}.ts` 同一份口径）。
// 不 import android.*：用例在 app/src/test/.../account/CreditsRulesTest.kt（输入输出照抄 Tools/swifttests/CreditsRulesTests.swift）。
package com.geek.umbra.feature.account

import com.geek.umbra.model.LooseStringSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// MARK: - 线上形状（字段名照服务端，不改名；解码宽松，缺字段给默认值）

/** `GET /credits` 的一条流水。 */
@Serializable
data class CreditEntry(
    val id: Int = 0, val delta: Int = 0, val balance_after: Int = 0,
    val kind: String = "", val note: String = "", val created_at: String = "",
)

/** `GET /credits`。`topup_url` 空 / 缺 = 没有充值页，不出「去充值」。 */
@Serializable
data class CreditsDTO(val user_id: Int = 0, val balance: Int = 0, val ledger: List<CreditEntry> = emptyList(), val topup_url: String? = null)

@Serializable
data class MembershipMember(
    val tier: String = "", val tier_name: String = "", val start_at: String = "", val end_at: String = "",
    val src: String = "", val auto_renew: Boolean = false,
)

/** 一个额度窗口：**只有百分比与重置时间，没有点数**（草案 §3.7）。`resets_at` 为 null = 还没开（没用过）。 */
@Serializable
data class QuotaWindowDTO(
    val pct: Int = 0, val resets_at: String? = null, val exhausted: Boolean = false,
    /** 背包里能用在这条窗口上的重置卡张数（服务端 4c22ca9 起带；旧服务端没有 = null，端上数背包）。 */
    val reset_cards: Int? = null,
)

/** `GET /membership`。`member` / `quota` 为 null = 没会员；`storage` 是批次 234 起服务端顺带回的那份用量。 */
@Serializable
data class MembershipDTO(
    val member: MembershipMember? = null,
    val quota: Map<String, QuotaWindowDTO>? = null,
    val trial_days: Int = 0,
    val shop_url: String? = null,
    val discount_pct: Int? = null,
    val storage: StorageDTO? = null,
)

/** 一件商品（`GET /shop/products`）。`available` 为假时 `reason` 说为什么。 */
@Serializable
data class ShopProductDTO(
    val key: String = "", val kind: String = "", val name: String = "", val description: String = "",
    val price_cents: Int = 0, val price_points: Int = 0, val tier: String = "", val window_kind: String = "",
    val points: Int = 0, val days: Int = 0, val available: Boolean = false, val reason: String = "",
    val member_price: Int? = null, val discount_pct: Int? = null,
)

/** `POST /shop/buy` 的回包。`item`：放进背包的那张（服务端 2026-10-14 起；旧服务端没有 = 买完直接生效）。 */
@Serializable
data class ShopBuyReplyDTO(
    val status: MembershipDTO = MembershipDTO(), val item: ShopItemDTO? = null,
    val price: Int? = null, val discount: Int? = null, val charged: Int? = null,
)

/** `GET /shop/products`。`payment_connected` 为假 = 付钱的那几件是灰的（收单 M1 没接）。 */
@Serializable
data class ShopCatalogDTO(val items: List<ShopProductDTO> = emptyList(), val payment_connected: Boolean = false)

/** 背包里的一张（`GET /shop/items`）。`state`：owned 能用 / used / expired / revoked；`expires_at` null = 不过期。 */
@Serializable
data class ShopItemDTO(
    val id: Int = 0, val kind: String = "", val name: String = "", val window_kind: String = "", val points: Int = 0,
    val state: String = "", val created_at: String = "", val expires_at: String? = null, val used_at: String? = null,
)

@Serializable
data class ShopItemsDTO(val items: List<ShopItemDTO> = emptyList())

/** `POST /shop/items/{id}/use`：用掉的那张 + 用完的会员状态。 */
@Serializable
data class ShopUseReplyDTO(val item: ShopItemDTO = ShopItemDTO(), val status: MembershipDTO = MembershipDTO())

/** `POST /shop/checkout`：一笔待支付的订单。`pay_url`（method=page）整页跳到支付宝；`qr_code` 是给扫的 URL。 */
@Serializable
data class ShopCheckoutDTO(
    val order_id: Int = 0, val status: String = "", val amount_cents: Int = 0,
    val pay_url: String? = null, val qr_code: String? = null, val expires_at: String? = null,
)

/** `GET /shop/orders/{id}`：轮询用。`status`：pending / paid / closed / refunded。 */
data class ShopOrderDTO(val id: Int, val status: String, val product_key: String?)

/** 存储用量里「每一类」的字节（全部可选：少一个键整份不该解不开）。 */
@Serializable
data class StorageByKindDTO(
    val chat: Long? = null, val money: Long? = null, val reminder: Long? = null, val insp: Long? = null,
    val task: Long? = null, val other: Long? = null, val kb: Long? = null, val vault: Long? = null,
)

/** `GET /storage`（单位字节）。`warn` 四档 ok / warn / critical / full —— **端上只认这个字段**，不拿 pct 自己比阈值。 */
@Serializable
data class StorageDTO(
    val used: Long = 0, val limit: Long = 0, val pct: Double = 0.0, val warn: String = "ok",
    val by_kind: StorageByKindDTO = StorageByKindDTO(), val trash: Long = 0, val tier: String? = null,
    val warn_pct: Int? = null, val critical_pct: Int? = null,
)

/** `GET /storage/items` 的一条。`kb_id` 知识库文档才有；`left_days` 回收站里的才有。 */
@Serializable
data class StorageItemDTO(
    @Serializable(with = LooseStringSerializer::class) val id: String = "",
    val name: String = "", val source: String = "", val bytes: Long = 0, val at_ms: Long = 0,
    val kb_id: String? = null, val left_days: Int? = null,
)

/** 「其余 N 项」：直接用服务端的，端上不自己相减（235 §二）。 */
@Serializable
data class StorageRestDTO(val count: Int = 0, val bytes: Long = 0)

/** `GET /storage/items?kind=files|kb|trash|vault`。`listable` 为假（保险箱）时 `items` 是空的、只看 `bytes`。 */
@Serializable
data class StorageItemsDTO(
    val kind: String = "", val listable: Boolean = true, val count: Int = 0, val bytes: Long = 0,
    val offset: Int? = null, val limit: Int? = null, val items: List<StorageItemDTO> = emptyList(),
    val rest: StorageRestDTO? = null, val keep_days: Int? = null,
)

// MARK: - 积分

object CreditsRules {
    /** 流水种类 → 显示词。认不得的退回「其它」—— 屏上是一个泛泛的词，不是空白。 */
    fun kindLabel(kind: String): String = when (kind) {
        "recharge" -> "充值"
        "ai" -> "AI"
        "plugin" -> "插件"
        "refund" -> "退积分"
        "gift" -> "赠送"
        "adjust" -> "调账"
        else -> "其它"
    }

    /** 正负带符号：+100 / -3 / 0。 */
    fun deltaText(n: Int): String = if (n > 0) "+$n" else n.toString()

    /** 充值页能不能开：只认 http(s)，且要有 host。能开回原串（去首尾空白），不能回 null。 */
    fun topupUrl(s: String?): String? {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val u = runCatching { URI(t) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (u.host.isNullOrEmpty()) return null
        return t
    }
}

// MARK: - 会员与额度

object MembershipRules {
    /** 会员来源 → 显示词。认不得的退回「已付费」。 */
    fun srcLabel(src: String): String = when (src) {
        "paid" -> "已付费"
        "trial" -> "试用中"
        "admin" -> "后台开通"
        "gift" -> "赠送"
        else -> "已付费"
    }

    fun clampPct(v: Int): Int = max(0, min(100, v))

    enum class Tone { Ok, Warning, Danger }

    /** 进度的颜色档：≥100 红、≥80 橙、其余绿。 */
    fun tone(pct: Int): Tone {
        val p = clampPct(pct)
        return if (p >= 100) Tone.Danger else if (p >= 80) Tone.Warning else Tone.Ok
    }

    /** 会员到期还剩几天（向上取整；已过期 0；坏日期 0）。 */
    fun daysLeft(endAtIso: String, now: Instant): Int {
        val end = PayRules.parseIso(endAtIso) ?: return 0
        val secs = (end.toEpochMilli() - now.toEpochMilli()) / 1000.0
        return if (secs <= 0) 0 else ceil(secs / 86_400).toInt()
    }

    /** 窗口那一行右侧的字：没开过「还没用过」；开了「已用 x% · <时间> 重置」。 */
    fun windowText(pct: Int, resetsAtText: String?): String {
        val r = resetsAtText ?: return "还没用过"
        return "已用 ${clampPct(pct)}% · $r 重置"
    }

    /** 商城能不能开：与充值页同一条尺子。 */
    fun shopUrl(s: String?): String? = CreditsRules.topupUrl(s)
}

// MARK: - 商城

object ShopRules {
    /** 分三组画：会员 / 积分充值（付钱）· 重置卡与加量包（付积分）。 */
    fun group(kind: String): String = when (kind) {
        "membership" -> "会员"
        "credits" -> "积分充值"
        else -> RESET_GROUP
    }

    /** 「去购买」重置卡落到的那一组（PC `gotoShop({ cat: "reset_card" })`）。 */
    const val RESET_GROUP = "重置卡与加量包"

    /** 价格那一格：付钱「¥15.00」、付积分「50 积分」、都没有「—」。 */
    fun priceText(cents: Int, points: Int): String {
        if (cents > 0) return String.format(Locale.ROOT, "¥%.2f", cents / 100.0)
        if (points > 0) return "$points 积分"
        return "—"
    }

    fun reasonLabel(reason: String): String = when (reason) {
        "payment_pending" -> "收单接入后开放"
        "no_membership" -> "要先有会员"
        "wrong_tier" -> "不是你这一档的"
        else -> "暂不可买"
    }

    /**
     * 买之前确认框里那句后果。**中继加量包不进背包**（服务端交货时直接加到本月中继额度）——
     * 对它也说「先放进我的道具」，人会去道具里找一张永远不会出现的卡。
     */
    fun buyTail(kind: String): String =
        if (kind == "relay_pack") "买了直接加到这个月的远程控制中继额度，月底作废；积分不退现金。"
        else "买了先放进「我的道具」，需要时再用；积分不退现金。"

    fun canBuy(p: ShopProductDTO): Boolean = p.available && p.price_points > 0

    /** 实扣积分：有会员价且真比原价低才用它，否则原价；付钱的回 0。 */
    fun chargedPoints(p: ShopProductDTO): Int {
        if (p.price_points <= 0) return 0
        val mp = p.member_price
        if (mp != null && mp >= 0 && mp < p.price_points) return mp
        return p.price_points
    }

    /** 带会员价的价格：「会员价 95 积分（原价 100）」，没折扣退回老价文。 */
    fun priceText(p: ShopProductDTO): String {
        val charged = chargedPoints(p)
        if (p.price_points > 0 && charged < p.price_points) return "会员价 $charged 积分（原价 ${p.price_points}）"
        return priceText(p.price_cents, p.price_points)
    }

    /** 折扣百分比 → 「9.5 折」；null / ≥100 / ≤0 回 null（不画那一行）。 */
    fun discountText(pct: Int?): String? {
        if (pct == null || pct <= 0 || pct >= 100) return null
        if (pct % 10 == 0) return "${pct / 10} 折"
        return "${pct / 10}.${pct % 10} 折"
    }

    fun boughtToast(discount: Int?, charged: Int?): String {
        if (discount != null && discount > 0 && charged != null) return "买好了，已按会员价扣 $charged 积分，额度已更新"
        return "买好了，额度已更新"
    }

    /**
     * 一次购买的幂等键（服务端只拿它去重）。iOS 是 `ios-<秒>-<随机>`，Android 换成 `android-` 前缀 ——
     * 只要每次购买唯一即可，前缀让服务端日志看得出是哪一端下的单。
     */
    fun buyRef(nowEpochSec: Long, rnd: Long): String = "android-$nowEpochSec-$rnd"
}

// MARK: - 我的道具

object ItemRules {
    /** 分两段：能用的（owned）一段、其余收进「历史」。服务端已排好序，这里只分不排。 */
    fun split(items: List<ShopItemDTO>): Pair<List<ShopItemDTO>, List<ShopItemDTO>> =
        items.filter { it.state == "owned" } to items.filter { it.state != "owned" }

    fun stateLabel(state: String): String = when (state) {
        "owned" -> "可用"
        "expired" -> "已过期"
        "revoked" -> "已退款"
        else -> "已使用"
    }

    fun windowLabel(kind: String): String = if (kind == "week") "本周额度" else "5 小时额度"

    /** 还剩几天过期（向上取整、最少 0）；不过期 / 解不开 = null。 */
    fun daysUntil(expiresAt: String?, now: Instant): Int? {
        val d = PayRules.parseIso(expiresAt) ?: return null
        return max(0, ceil((d.toEpochMilli() - now.toEpochMilli()) / 1000.0 / 86_400).toInt())
    }

    fun subtitle(i: ShopItemDTO, now: Instant): String {
        val win = windowLabel(i.window_kind)
        if (i.state != "owned") return win
        val d = daysUntil(i.expires_at, now) ?: return "$win · 不过期"
        return "$win · $d 天后过期"
    }

    /** 快过期：3 天内。不过期 = false。 */
    fun soon(i: ShopItemDTO, now: Instant): Boolean {
        val d = daysUntil(i.expires_at, now) ?: return false
        return d <= 3
    }

    /** 能用那几行的小字：快过期那半句（提醒色 + 三角）+ 其余；白用的原因并进其余。回 (warn, rest)。 */
    fun rowSub(i: ShopItemDTO, now: Instant, blocker: String?): Pair<String?, String?> {
        val exp = daysUntil(i.expires_at, now)?.let { "$it 天后过期" } ?: "不过期"
        if (soon(i, now)) return exp to blocker
        return null to (blocker?.let { "$exp · $it" } ?: exp)
    }

    /** 历史那一行的小字：用掉 / 过期 / 买下的时间（「10-09 15:20」，本地时区）。 */
    fun historySub(i: ShopItemDTO, zone: ZoneId = ZoneId.systemDefault()): String {
        val iso = i.used_at ?: i.expires_at ?: i.created_at
        val d = PayRules.parseIso(iso) ?: return windowLabel(i.window_kind)
        val z = d.atZone(zone)
        return String.format(Locale.ROOT, "%02d-%02d %02d:%02d", z.monthValue, z.dayOfMonth, z.hour, z.minute)
    }

    /** 现在用这张白不白用（只用来灰掉、写原因；最终以服务端为准）。0% 不拦：pct 四舍五入，0% 不等于一点没用。 */
    fun blocker(i: ShopItemDTO, quota: Map<String, QuotaWindowDTO>?): String? {
        val w = quota?.get(i.window_kind) ?: return "要先有会员"
        return if (w.resets_at.isNullOrEmpty()) "额度还没开始用" else null
    }

    fun confirmBody(i: ShopItemDTO, quota: QuotaWindowDTO?, resetsText: String): String {
        val win = windowLabel(i.window_kind)
        if (i.kind == "reset_card") return "${win}已用 ${quota?.pct ?: 0}%，使用后清零（$resetsText 照常重置）。用掉就不能退回。"
        return "给${win}加 ${i.points} 点，到 $resetsText 窗口重置时一起作废。用掉就不能退回。"
    }

    fun boughtToast(stored: Boolean, discount: Int?, charged: Int?): String {
        if (!stored) return ShopRules.boughtToast(discount, charged)
        if (discount != null && discount > 0 && charged != null) return "买好了，已按会员价扣 $charged 积分，放进了「我的道具」"
        return "买好了，已放进「我的道具」，需要时点「使用」"
    }
}

// MARK: - 付钱下单（M1 收单：支付宝）

enum class PayWait { Pending, Paid, Closed, Expired }

object PayRules {
    const val POLL_MS = 3_000L
    /** 服务端没给 `expires_at` 时端上自己兜底的等待上限 —— 不能无限轮询。 */
    const val FALLBACK_WAIT_MS = 15 * 60 * 1000L

    fun canCheckout(p: ShopProductDTO, paymentConnected: Boolean): Boolean =
        paymentConnected && p.available && p.price_cents > 0 && p.price_points == 0

    fun amountText(cents: Int): String = String.format(Locale.ROOT, "¥%.2f", cents / 100.0)

    /** 要开的支付地址：只认 http(s)（服务端被改过的话这里是最后一道）。 */
    fun payUrl(dto: ShopCheckoutDTO): String? = CreditsRules.topupUrl(dto.pay_url) ?: CreditsRules.topupUrl(dto.qr_code)

    /** 解析服务端的 ISO 时间（带不带小数秒、Z 或 ±hh:mm 都认）。认不得回 null。 */
    fun parseIso(s: String?): Instant? {
        if (s.isNullOrEmpty()) return null
        return runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(s) }.getOrNull()
    }

    /** 这一轮轮询之后处于哪一态。过期在端上也判一遍：服务端关单可能晚几秒，端上不该一直转圈。 */
    fun outcome(status: String?, now: Instant, expiresAt: Instant?): PayWait = when (status) {
        "paid" -> PayWait.Paid
        "closed", "refunded" -> PayWait.Closed
        else -> if (expiresAt != null && !now.isBefore(expiresAt)) PayWait.Expired else PayWait.Pending
    }

    fun doneText(o: PayWait): String? = when (o) {
        PayWait.Paid -> "已到账，会员和积分已更新"
        PayWait.Closed -> "这笔订单已关闭"
        PayWait.Expired -> "支付超时，订单已过期，请重新下单"
        PayWait.Pending -> null
    }

    /** 下单失败那句：503（收单没接 / 功能没开）要说清是服务器的事，别让人去重试。 */
    fun checkoutFailText(serverLacks: Boolean, detail: String): String {
        if (serverLacks) return "这台服务器还没接收单，暂时付不了钱"
        return detail.ifEmpty { "没下成单，稍后再试" }
    }

    /**
     * `GET /shop/orders/{id}` 的回包（umbra-ios#88）：服务端回 `{"order": {...}}` 信封，也可能平铺 —— **两种都认**。
     * 有 `order` 键就拆它；`id` / `status` 缺一样算解不出（不猜）。
     */
    fun parseOrder(json: String): ShopOrderDTO? {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        val o = if (root.containsKey("order")) root["order"] as? JsonObject ?: return null else root
        val id = (o["id"] as? JsonPrimitive)?.longOrNull?.toInt() ?: return null
        val status = (o["status"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        val key = (o["product_key"] as? JsonPrimitive)?.contentOrNull
        return ShopOrderDTO(id, status, key)
    }
}
