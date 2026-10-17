// 月度趋势柱下面那排短标签 —— 照抄 iOS Features/Money/MoneyTrendKit.swift。纯 Kotlin。
//
// ⚠️ 和 PC moneyKit.trendBarLabels 同一口径（设计侧 169 §二）。挡的是：近 12 月的区间必然跨年，
// 「9月」既可能是 '25-09 也可能是 '26-09 —— 两根不同年份的柱子共用一个名字，用户读到的是两个数打架。
package com.geek.umbra.feature.money

/** iOS: MoneyTrendKit.swift:21 */
object MoneyTrend {

    /** "2026-08" → "'26" */
    private fun shortYear(ym: String): String = "'" + ym.drop(2).take(2)

    /**
     * 一排 "YYYY-MM"（老→新）→ 一排柱标签，一一对应。
     * 区间不跨年 → 只写月；跨年 → 第一根定基准年，之后每到 1 月把年重说一遍，其余不带年
     * （12 根全带年会挤成一团，那是另一种读不出来）。iOS: MoneyTrendKit.swift:29
     */
    fun barLabels(yms: List<String>): List<String> {
        val crossesYear = yms.map { it.take(4) }.toSet().size > 1
        return yms.mapIndexed { i, ym ->
            val m = "${ym.takeLast(2).toIntOrNull() ?: 0}月"
            if (!crossesYear) m
            else if (i == 0 || ym.takeLast(2) == "01") "${shortYear(ym)} · $m"
            else m
        }
    }
}
