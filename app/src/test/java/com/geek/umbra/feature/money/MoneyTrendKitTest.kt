package com.geek.umbra.feature.money

import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS Tools/swifttests/MoneyTrendKitTests.swift 逐条照抄（与 PC tests/money.test.ts 同源）。 */
class MoneyTrendKitTest {
    /** 从某月起连续 n 个月（老→新），跨年靠取模，不靠字符串拼。 */
    private fun span(from: String, n: Int): List<String> {
        var y = from.take(4).toIntOrNull() ?: 2026
        var m = from.takeLast(2).toIntOrNull() ?: 1
        val out = mutableListOf<String>()
        repeat(n) {
            out.add(String.format(java.util.Locale.ROOT, "%04d-%02d", y, m))
            m += 1
            if (m > 12) { m = 1; y += 1 }
        }
        return out
    }

    /** 区间不跨年：一个年份都不该出现。 */
    @Test fun sameYearCases() {
        assertEquals("近 6 月不跨年：只写月",
            listOf("3月", "4月", "5月", "6月", "7月", "8月"), MoneyTrend.barLabels(span("2026-03", 6)))
        assertEquals("整一年（1 月起 12 个月）仍然不跨年：1 月也不带年",
            listOf("1月", "2月", "3月", "4月", "5月", "6月", "7月", "8月", "9月", "10月", "11月", "12月"),
            MoneyTrend.barLabels(span("2026-01", 12)))
    }

    /** 跨年：第一根定基准年，之后每到 1 月重说一遍年，其余不带。 */
    @Test fun crossYearCases() {
        assertEquals("跨年（'25-10 → '26-09）：第一根与 1 月带年，其余不带",
            listOf("'25 · 10月", "11月", "12月", "'26 · 1月", "2月", "3月", "4月", "5月", "6月", "7月", "8月", "9月"),
            MoneyTrend.barLabels(span("2025-10", 12)))
        val tail = MoneyTrend.barLabels(span("2025-02", 12))
        assertEquals("跨年且以 1 月收尾：第一根带年", "'25 · 2月", tail.firstOrNull() ?: "")
        assertEquals("跨年且以 1 月收尾：最后那根是 1 月，要带年", "'26 · 1月", tail.lastOrNull() ?: "")
        assertEquals("跨年 12 根里带年的**只有** 2 根（第一根 + 那个 1 月）", 2, tail.count { it.contains("·") })
    }

    /** ⚠️ 那条 bug 的样子：区间里同时有 '25-09 和 '26-09 时两根都叫「9月」。 */
    @Test fun noDuplicateNames() {
        val labels = MoneyTrend.barLabels(span("2025-09", 13))
        assertEquals("跨年时两根同月份数字的柱子不许同名：有重名 —— ${labels.joinToString(" / ")}",
            labels.size, labels.toSet().size)
    }

    /** 只有一个月的数据、以及一根都没有时，不能崩也不能凭空造年份。 */
    @Test fun edgeCases() {
        assertEquals("一根也能用", listOf("8月"), MoneyTrend.barLabels(listOf("2026-08")))
        assertEquals("零根返回空表", emptyList<String>(), MoneyTrend.barLabels(emptyList()))
        assertEquals("只有两根且跨年：两根都带年", listOf("'25 · 12月", "'26 · 1月"),
            MoneyTrend.barLabels(listOf("2025-12", "2026-01")))
    }
}
