package com.geek.umbra.feature.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** 积分 / 会员 / 商城 / 道具 / 付钱下单用例。输入输出照抄 iOS Tools/swifttests/CreditsRulesTests.swift。 */
class CreditsRulesTest {
    @Test fun credits() {
        listOf("recharge" to "充值", "ai" to "AI", "plugin" to "插件", "refund" to "退积分", "gift" to "赠送", "adjust" to "调账", "bonus" to "其它")
            .forEach { (k, v) -> assertEquals(v, CreditsRules.kindLabel(k)) }
        assertEquals("+100", CreditsRules.deltaText(100))
        assertEquals("-3", CreditsRules.deltaText(-3))
        assertEquals("0", CreditsRules.deltaText(0))
        assertEquals("https://shop.example.com/credits", CreditsRules.topupUrl("https://shop.example.com/credits"))
        assertNull(CreditsRules.topupUrl("javascript:alert(1)"))
        assertNull(CreditsRules.topupUrl(""))
        assertNull(CreditsRules.topupUrl(null))
        val dto = CreditsDTO(1, 7, listOf(CreditEntry(1, -3, 7, "ai", "x", "t")), null)
        assertEquals(-3, dto.ledger.first().delta)
        assertNull(dto.topup_url)
    }

    @Test fun membership() {
        listOf("paid" to "已付费", "trial" to "试用中", "admin" to "后台开通", "gift" to "赠送", "bonus" to "已付费")
            .forEach { (k, v) -> assertEquals(v, MembershipRules.srcLabel(k)) }
        assertEquals(listOf(0, 37, 100), listOf(-5, 37, 140).map(MembershipRules::clampPct))
        assertEquals(
            listOf(MembershipRules.Tone.Ok, MembershipRules.Tone.Ok, MembershipRules.Tone.Warning, MembershipRules.Tone.Danger, MembershipRules.Tone.Danger),
            listOf(0, 79, 80, 100, 130).map(MembershipRules::tone),
        )
        val now = Instant.parse("2026-10-11T00:00:00Z")
        assertEquals(7, MembershipRules.daysLeft("2026-10-18T00:00:00Z", now))
        assertEquals(1, MembershipRules.daysLeft("2026-10-11T00:00:01Z", now))
        assertEquals(0, MembershipRules.daysLeft("2026-10-10T00:00:00Z", now))
        assertEquals(0, MembershipRules.daysLeft("nope", now))
        assertEquals("已用 75% · 17:00 重置", MembershipRules.windowText(75, "17:00"))
        assertEquals("还没用过", MembershipRules.windowText(0, null))
        assertEquals("https://shop.example.com/vip", MembershipRules.shopUrl("https://shop.example.com/vip"))
        assertNull(MembershipRules.shopUrl("ftp://x"))
    }

    private val card = ShopProductDTO(key = "reset-5h", kind = "reset_card", name = "5 小时重置卡", price_points = 10, tier = "basic", window_kind = "5h", available = true)
    private val vip = card.copy(kind = "membership", price_cents = 1500, price_points = 0, available = false, reason = "payment_pending")

    @Test fun shop() {
        assertEquals(listOf("会员", "积分充值", "重置卡与加量包", "重置卡与加量包", "重置卡与加量包"),
            listOf("membership", "credits", "reset_card", "booster", "weird").map(ShopRules::group))
        assertEquals("¥15.00", ShopRules.priceText(1500, 0))
        assertEquals("50 积分", ShopRules.priceText(0, 50))
        assertEquals("—", ShopRules.priceText(0, 0))
        assertEquals("不是你这一档的", ShopRules.reasonLabel("wrong_tier"))
        assertEquals("暂不可买", ShopRules.reasonLabel("???"))
        assertEquals("重置卡与加量包", ShopRules.group("relay_pack"))
        assertFalse(ShopRules.buyTail("relay_pack").contains("我的道具"))
        assertTrue(ShopRules.buyTail("relay_pack").contains("中继额度"))
        assertTrue(ShopRules.buyTail("reset_card").contains("我的道具"))
        assertTrue(ShopRules.canBuy(card))
        assertFalse(ShopRules.canBuy(vip))
        // iOS 是 ios- 前缀；Android 换成 android-（幂等键只要唯一，前缀让服务端日志看得出哪一端）
        assertEquals("android-1760000000-7", ShopRules.buyRef(1_760_000_000, 7))
        val m = MembershipDTO(member = null, quota = null, trial_days = 7)
        assertTrue(m.member == null && m.trial_days == 7)
        assertEquals(10, ShopRules.chargedPoints(card))
        val vipCard = card.copy(member_price = 9, discount_pct = 95)
        assertEquals(9, ShopRules.chargedPoints(vipCard))
        assertEquals(10, ShopRules.chargedPoints(card.copy(member_price = 10)))
        assertEquals(10, ShopRules.chargedPoints(card.copy(member_price = 99)))
        assertEquals(0, ShopRules.chargedPoints(vip))
        assertEquals("会员价 9 积分（原价 10）", ShopRules.priceText(vipCard))
        assertEquals("10 积分", ShopRules.priceText(card))
        assertEquals("¥15.00", ShopRules.priceText(vip))
        assertEquals("9.5 折", ShopRules.discountText(95))
        assertEquals("9 折", ShopRules.discountText(90))
        assertEquals("8.8 折", ShopRules.discountText(88))
        assertNull(ShopRules.discountText(100))
        assertNull(ShopRules.discountText(null))
        assertNull(ShopRules.discountText(0))
        assertNull(ShopRules.discountText(130))
        assertEquals("买好了，已按会员价扣 9 积分，额度已更新", ShopRules.boughtToast(1, 9))
        assertEquals("买好了，额度已更新", ShopRules.boughtToast(0, 10))
        assertEquals("买好了，额度已更新", ShopRules.boughtToast(null, null))
    }

    @Test fun pay() {
        val vipOn = vip.copy(available = true, reason = "")
        assertTrue(PayRules.canCheckout(vipOn, true))
        assertFalse(PayRules.canCheckout(vipOn, false))
        assertFalse(PayRules.canCheckout(vip, true))
        assertFalse(PayRules.canCheckout(card, true))
        assertEquals("¥15.00", PayRules.amountText(1500))
        assertEquals("¥148.00", PayRules.amountText(14800))
        val co = ShopCheckoutDTO(42, "pending", 1500, "https://openapi.alipay.com/gateway.do?x=1", null, "2026-10-11T12:00:00Z")
        assertEquals("openapi.alipay.com", java.net.URI(PayRules.payUrl(co)!!).host)
        assertEquals("qr.alipay.com", java.net.URI(PayRules.payUrl(ShopCheckoutDTO(1, "pending", 1, null, "https://qr.alipay.com/abc"))!!).host)
        assertNull(PayRules.payUrl(ShopCheckoutDTO(1, "pending", 1, "alipays://x", null)))
        assertNull(PayRules.payUrl(ShopCheckoutDTO(1, "pending", 1, null, null)))
        val exp = PayRules.parseIso("2026-10-11T12:00:00Z")
        assertEquals(1_791_720_000L, exp!!.epochSecond)
        assertEquals(1_791_720_000L, PayRules.parseIso("2026-10-11T12:00:00.250Z")!!.epochSecond)
        assertNull(PayRules.parseIso("昨天"))
        assertNull(PayRules.parseIso(null))
        val before = Instant.ofEpochSecond(1_791_719_000)
        val after = Instant.ofEpochSecond(1_791_720_001)
        assertEquals(PayWait.Paid, PayRules.outcome("paid", after, exp))
        assertEquals(PayWait.Closed, PayRules.outcome("closed", before, exp))
        assertEquals(PayWait.Closed, PayRules.outcome("refunded", before, exp))
        // umbra-ios#88：信封、平铺两种都要解得出 status；两样都没有才算解不出。
        assertEquals("paid", PayRules.parseOrder("""{"order":{"id":7,"status":"paid","product_key":"vip-month"}}""")?.status)
        assertEquals(7, PayRules.parseOrder("""{"order":{"id":7,"status":"paid","product_key":"vip-month"}}""")?.id)
        assertEquals("closed", PayRules.parseOrder("""{"id":8,"status":"closed"}""")?.status)
        assertNull(PayRules.parseOrder("""{"order":{"status":"paid"}}"""))
        assertNull(PayRules.parseOrder("""{"detail":"x"}"""))
        assertEquals(PayWait.Paid, PayRules.outcome(PayRules.parseOrder("""{"order":{"id":7,"status":"paid"}}""")?.status, after, exp))
        assertEquals(PayWait.Pending, PayRules.outcome("pending", before, exp))
        assertEquals(PayWait.Expired, PayRules.outcome("pending", after, exp))
        assertEquals(PayWait.Pending, PayRules.outcome(null, before, exp))
        assertEquals(PayWait.Pending, PayRules.outcome("weird", before, null))
        assertEquals("已到账，会员和积分已更新", PayRules.doneText(PayWait.Paid))
        assertEquals("支付超时，订单已过期，请重新下单", PayRules.doneText(PayWait.Expired))
        assertEquals("这笔订单已关闭", PayRules.doneText(PayWait.Closed))
        assertNull(PayRules.doneText(PayWait.Pending))
        assertEquals("这台服务器还没接收单，暂时付不了钱", PayRules.checkoutFailText(true, "x"))
        assertEquals("这件现在不卖", PayRules.checkoutFailText(false, "这件现在不卖"))
        assertEquals("没下成单，稍后再试", PayRules.checkoutFailText(false, ""))
    }

    private fun item(state: String, win: String = "5h", exp: String? = null, kind: String = "reset_card") =
        ShopItemDTO(1, kind, "卡", win, 120, state, "t", exp, null)

    @Test fun items() {
        val (usable, history) = ItemRules.split(listOf(item("used"), item("owned"), item("expired"), item("owned", "week")))
        assertEquals(listOf("5h", "week"), usable.map { it.window_kind })
        assertEquals(listOf("used", "expired"), history.map { it.state })
        assertEquals(listOf("可用", "已使用", "已过期", "已退款", "已使用"), listOf("owned", "used", "expired", "revoked", "weird").map(ItemRules::stateLabel))
        val t0 = PayRules.parseIso("2026-10-14T00:00:00+00:00")!!
        assertEquals(2, ItemRules.daysUntil("2026-10-15T01:00:00+00:00", t0))
        assertEquals(0, ItemRules.daysUntil("2026-10-13T00:00:00+00:00", t0))
        assertNull(ItemRules.daysUntil(null, t0))
        assertEquals("本周额度 · 2 天后过期", ItemRules.subtitle(item("owned", "week", "2026-10-15T01:00:00+00:00"), t0))
        assertEquals("5 小时额度 · 不过期", ItemRules.subtitle(item("owned"), t0))
        val q = mapOf("5h" to QuotaWindowDTO(0, "2026-10-14T05:00:00+00:00", false), "week" to QuotaWindowDTO(10, null, false))
        assertEquals("要先有会员", ItemRules.blocker(item("owned"), null))
        assertNull(ItemRules.blocker(item("owned"), q))
        assertEquals("额度还没开始用", ItemRules.blocker(item("owned", "week"), q))
        assertTrue(ItemRules.soon(item("owned", exp = "2026-10-16T00:00:00+00:00"), t0))
        assertFalse(ItemRules.soon(item("owned", exp = "2026-10-20T00:00:00+00:00"), t0))
        assertEquals("2 天后过期" to "额度还没开始用", ItemRules.rowSub(item("owned", exp = "2026-10-16T00:00:00+00:00"), t0, "额度还没开始用"))
        assertEquals(null to "12 天后过期 · 额度还没开始用", ItemRules.rowSub(item("owned", exp = "2026-10-26T00:00:00+00:00"), t0, "额度还没开始用"))
        assertEquals("不过期", ItemRules.rowSub(item("owned"), t0, null).second)
        assertEquals("买好了，已放进「我的道具」，需要时点「使用」", ItemRules.boughtToast(true, null, null))
        assertEquals("买好了，额度已更新", ItemRules.boughtToast(false, null, null))
        // 历史小字：按本地时区写「MM-dd HH:mm」（这里钉 UTC，免得用例随跑的机器时区变）
        assertEquals("10-09 15:20", ItemRules.historySub(item("used").copy(used_at = "2026-10-09T15:20:00Z"), ZoneOffset.UTC))
    }
}
