package com.geek.umbra.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** 照抄 iOS Tools/swifttests/ChatTableRulesTests.swift。 */
class ChatTableRulesTest {
    private val inf = Float.POSITIVE_INFINITY

    @Test fun columns() {
        assertEquals(listOf(72f, 72f, 90f), ChatTableRules.columnWidths(listOf(60f, 50f, 90f), inf))
        assertEquals(listOf(138f, 72f, 90f), ChatTableRules.columnWidths(listOf(60f, 50f, 90f), 300f))
        assertEquals(listOf(72f, 220f), ChatTableRules.columnWidths(listOf(40f, 500f), inf))
        assertEquals(listOf(80f, 100f, 100f, 100f, 100f), ChatTableRules.columnWidths(listOf(80f, 100f, 100f, 100f, 100f), 300f))
        assertEquals(listOf(72f, 72f), ChatTableRules.columnWidths(listOf(72f, 72f), 144f))
        assertEquals(emptyList<Float>(), ChatTableRules.columnWidths(emptyList(), 300f))
        assertEquals(295f, ChatTableRules.naturalWidth(listOf(72f, 220f), 1f))
        assertEquals(0f, ChatTableRules.naturalWidth(emptyList(), 1f))
    }

    @Test fun bodyWidth() {
        assertEquals(261f, ChatTableRules.bodyWidth(317f, 56f, 240f))
        assertEquals(295f, ChatTableRules.bodyWidth(317f, 56f, 295f))
        assertEquals(317f, ChatTableRules.bodyWidth(317f, 56f, 600f))
        assertEquals(261f, ChatTableRules.bodyWidth(261f, 0f, 600f))
    }

    @Test fun hasTable() {
        assertEquals(true, ChatTableRules.hasTable("| a | b |\n|---|---|\n| 1 | 2 |"))
        assertEquals(true, ChatTableRules.hasTable("前面一句。\n\n| a | b |\n|:-|-:|\n| 1 | 2 |\n\n后面一句。"))
        assertEquals(false, ChatTableRules.hasTable("a | b 这种句子里的竖线"))
        assertEquals(false, ChatTableRules.hasTable("| a | b |"))
        assertEquals(false, ChatTableRules.hasTable("普通一句话"))
    }

    private fun table(md: String) = ChatMarkdown.parse(md).filterIsInstance<MdBlock.Table>().firstOrNull()?.table

    @Test fun numeric() {
        val t = table("| 类别 | 笔数 | 金额 |\n| :-- | :-: | --: |\n| 餐饮 | 42 | 2,186.40 |\n| 交通 | 17 | 463.00 |\n| 日用 | 9 | 1,032.75 |")!!
        assertEquals(false, ChatTableRules.isNumericColumn(t, 0))
        assertEquals(true, ChatTableRules.isNumericColumn(t, 1))
        assertEquals(true, ChatTableRules.isNumericColumn(t, 2))
        assertEquals(false, ChatTableRules.isNumericColumn(t, 9))
        val t2 = table("| 名 | 状态 |\n| --- | ---: |\n| a | 进行中 |\n| b | 完成 |")!!
        assertEquals(false, ChatTableRules.isNumericColumn(t2, 1))
        val t3 = table("| a | b | c |\n| --- | --- | --- |\n| 1 |  | x |\n|  |  | 2 |\n| 3 |  | 4 |")!!
        assertEquals(true, ChatTableRules.isNumericColumn(t3, 0))
        assertEquals(false, ChatTableRules.isNumericColumn(t3, 1))
        assertEquals(false, ChatTableRules.isNumericColumn(t3, 2))
        for (c in listOf("42", "2,186.40", "-3", "−3", "+12.5%", "¥1,032.75", "-¥20", "$0.99", "**1,032.75**", "`42`", " 7 ")) {
            assertEquals("数字格：「$c」是数", true, ChatTableRules.isNumericCell(c))
        }
        for (c in listOf("3 天", "12 kg", "v2.1", "1,23", "2026-10-07", "09:00", "约 40", "N/A", "—", "", "1.2.3", "12,3456")) {
            assertEquals("数字格：「$c」不是一个数", false, ChatTableRules.isNumericCell(c))
        }
    }
}
