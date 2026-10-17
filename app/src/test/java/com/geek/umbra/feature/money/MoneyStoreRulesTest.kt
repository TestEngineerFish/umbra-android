package com.geek.umbra.feature.money

import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS Tools/swifttests/MoneyStoreRulesTests.swift 逐条照抄（台账 month-switch-stale-response）。 */
class MoneyStoreRulesTest {
    /** 一轮请求带走的东西。 */
    private data class Req(val gen: Int, val ledger: String, val ym: String)

    /** 和 MoneyStore.reload 同形的最小模型：ym 发请求时就翻、entriesYm 回来才落。 */
    private class Store {
        val gate = MoneyReloadGate()
        var ledger = "personal"
        var ym = "2026-10"
        var entriesYm = "2026-10"

        /** 发一轮：记下号、账本、月份。 */
        fun send(m: String): Req {
            ym = m
            return Req(gate.begin(), ledger, m)
        }

        /** 那一轮回来：两道判据都过了才落地。 */
        fun land(r: Req) {
            if (!gate.isCurrent(r.gen) || ledger != r.ledger) return
            entriesYm = r.ym
        }
    }

    @Test fun gate() {
        val g = MoneyReloadGate()
        assertEquals("一轮都没发时号是 0", 0, g.latest)
        val a = g.begin()
        val b = g.begin()
        assertEquals("旧的那一轮被当成了最新", false, g.isCurrent(a))
        assertEquals("最新那一轮没被认出来", true, g.isCurrent(b))
    }

    @Test fun lateMonthDropped() {
        // 本条主体：从 10 月连点两下「←」—— 9 月那一轮后到。
        val s = Store()
        val r9 = s.send("2026-09")
        val r8 = s.send("2026-08")
        s.land(r8)
        s.land(r9)   // 迟到
        assertEquals("抬头", "2026-08", s.ym)
        assertEquals("迟到的 9 月那一轮盖到了 8 月的屏上（抬头 8 月、数据 9 月）", "2026-08", s.entriesYm)

        // 按顺序回来时照常落地。
        val t = Store()
        val x = t.send("2026-09")
        t.land(x)
        assertEquals("正常回来的那一轮没落地", "2026-09", t.entriesYm)

        // 写操作后的静默重拉在飞、人又翻了月：静默那一轮是旧的，也不许落。
        val u = Store()
        val silent = u.send("2026-10")
        val nav = u.send("2026-09")
        u.land(nav)
        u.land(silent)
        assertEquals("迟到的静默重拉把翻过去的月份盖回来了", "2026-09", u.entriesYm)

        // 账本那道判据仍然有用：号是最新的、但账本在别处被翻过 —— 不落。
        val v = Store()
        val y = v.send("2026-10")
        v.ledger = "business"
        v.entriesYm = "-"
        v.land(y)
        assertEquals("账本已经换了，旧账本那一轮还是落了地", "-", v.entriesYm)
    }
}
