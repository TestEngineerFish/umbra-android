package com.geek.umbra.feature.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** 就地用重置卡的纯判据 —— 照抄 UmbraPC `tests/resetCard.test.ts` / `resetCard278.test.ts` 的纯函数那几条（批次 278）。 */
class ResetCardRulesTest {
    private val now = Instant.parse("2026-10-21T08:00:00Z")
    private fun item(id: Int, kind: String = "reset_card", window: String = "week", state: String = "owned", expires: String? = null) =
        ShopItemDTO(id = id, kind = kind, name = "周重置卡", window_kind = window, state = state, created_at = "2026-10-01T00:00:00Z", expires_at = expires)
    private val R = ResetCardRules

    @Test fun canShowReset() {
        assertEquals(80, R.RESET_SHOW_PCT)
        assertEquals("80 本身算", true, R.canShowReset(QuotaWindowDTO(80, "2026-10-25T00:00:00Z")))
        assertEquals(false, R.canShowReset(QuotaWindowDTO(79, "2026-10-25T00:00:00Z")))
        assertEquals("窗口没开不出", false, R.canShowReset(QuotaWindowDTO(100, null)))
        assertEquals(false, R.canShowReset(null))
    }

    @Test fun pickResetCard() {
        val list = listOf(item(1, state = "used"), item(2, expires = "2026-10-20T00:00:00Z"), item(3, kind = "booster"), item(4, window = "5h"), item(5))
        assertEquals(5, R.pickResetCard(list, "week", now)?.id)
        assertEquals(4, R.pickResetCard(list, "5h", now)?.id)
        val soon = listOf(item(1), item(2, expires = "2026-10-30T00:00:00Z"), item(3, expires = "2026-10-23T00:00:00Z"))
        assertEquals("快过期的先用，不过期的最后", 3, R.pickResetCard(soon, "week", now)?.id)
        assertNull(R.pickResetCard(listOf(item(1, state = "expired")), "week", now))
    }

    @Test fun resetCardsLeft() {
        assertEquals("用服务端的数", 3, R.resetCardsLeft(QuotaWindowDTO(reset_cards = 3), emptyList(), "week", now))
        assertEquals("没给就数背包", 1, R.resetCardsLeft(QuotaWindowDTO(), listOf(item(1), item(2, window = "5h")), "week", now))
        assertNull("背包也没问到 → 不知道", R.resetCardsLeft(QuotaWindowDTO(), null, "week", now))
    }

    @Test fun exhaustedKind() {
        fun w(pct: Int, ex: Boolean = false) = QuotaWindowDTO(pct, "x", ex)
        assertEquals("5 小时先问", "5h", R.exhaustedKind(mapOf("5h" to w(100, true), "week" to w(100, true))))
        assertEquals("week", R.exhaustedKind(mapOf("5h" to w(30), "week" to w(100, true))))
        assertEquals("都没标取用得多的", "week", R.exhaustedKind(mapOf("5h" to w(30), "week" to w(90))))
        assertNull(R.exhaustedKind(null))
    }

    @Test fun quotaWindowOf() {
        assertEquals("5h", R.quotaWindowOf("5h"))
        assertEquals("week", R.quotaWindowOf("week"))
        for (v in listOf(null, "", "day")) assertNull(R.quotaWindowOf(v))
    }

    private fun quota(c5: Int?, cw: Int?, wkOut: Boolean = true) = mapOf(
        "5h" to QuotaWindowDTO(10, "2026-10-21T10:00:00Z", false, c5),
        "week" to QuotaWindowDTO(100, "2026-10-25T00:00:00Z", wkOut, cw),
    )

    @Test fun showUseCard() {
        assertEquals("没问过 → 出", true, R.showUseCard("week", null))
        assertEquals("那条不知道 → 出", true, R.showUseCard("week", CardCounts(0, null, null)))
        assertEquals("说的那条是 0 → 不出", false, R.showUseCard("week", CardCounts(3, 0, null)))
        assertEquals("别的那条是 0 不影响", true, R.showUseCard("5h", CardCounts(3, 0, null)))
        assertEquals("没说窗口按额度状态猜", false, R.showUseCard(null, CardCounts(2, 0, quota(2, 0))))
    }

    @Test fun cardsLeftAfter() {
        assertEquals("用卡接口回的张数优先", 4, R.cardsLeftAfter(MembershipDTO(quota = quota(1, 4)), "week", 9))
        assertEquals("没有就减一", 2, R.cardsLeftAfter(null, "week", 3))
        assertEquals("不小于 0", 0, R.cardsLeftAfter(MembershipDTO(quota = quota(null, null)), "5h", 0))
    }
}
