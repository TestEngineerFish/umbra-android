package com.geek.umbra.feature.money

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** iOS Tools/swifttests/InvoiceNoKitTests.swift 逐条照抄。 */
class InvoiceNoKitTest {
    @Test fun shouldCheck() {
        assertEquals("空号也去查了", false, InvoiceNoKit.shouldCheck("", true))
        assertEquals("全是空格的号也去查了", false, InvoiceNoKit.shouldCheck("   ", true))
        assertEquals("生活账本也去查了", false, InvoiceNoKit.shouldCheck("2544", false))
        assertEquals("经营账本有号却不查", true, InvoiceNoKit.shouldCheck("2544", true))
        assertEquals("两头有空格就不查了", true, InvoiceNoKit.shouldCheck("  2544  ", true))
    }

    /** ⚠️⚠️ 重号不拦保存（sam 2026-09-16 推翻）。 */
    @Test fun dupDoesNotBlockSave() {
        assertEquals("重号又开始拦保存了", false, InvoiceNoKit.DUP_BLOCKS_SAVE)
    }

    /** 那句话里的日期：按那一笔自己的时区算。 */
    @Test fun dupDate() {
        val at = LocalDateTime.of(2026, 8, 3, 2, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val east = InvoiceDup("a", at, 480, 1, "")
        val west = InvoiceDup("b", at, -300, 1, "")
        assertEquals("东八区那一笔的日子", 3, InvoiceNoKit.dupDate(east)?.second)
        assertEquals("同一时刻在西五区还是前一天 —— 日期该按那一笔自己的时区算", 2, InvoiceNoKit.dupDate(west)?.second)
        assertEquals("没撞号却说了话", true, InvoiceNoKit.dupDate(null) == null)
        val line = InvoiceNoKit.dupText(8, 3)
        assertEquals("那句话里没有日期", true, line.contains("8 月 3 日"))
        assertEquals("那句话没说清是什么事", true, line.contains("已经用过"))
    }
}
