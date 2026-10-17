package com.geek.umbra.feature.account

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** StorageRules 用例。输入输出照抄 iOS Tools/swifttests/StorageRulesTests.swift（设计侧批次 234 / 235）。 */
class StorageRulesTest {
    private val MB = 1024L * 1024
    private val GB = 1024 * MB

    @Test fun format() {
        assertEquals("0", StorageRules.fmt(0))
        assertEquals("0", StorageRules.fmt(-5))
        assertEquals("1 KB", StorageRules.fmt(512))
        assertEquals("300 KB", StorageRules.fmt(300 * 1024))
        assertEquals("1 MB", StorageRules.fmt(MB))
        assertEquals("24.6 MB", StorageRules.fmt((24.6 * MB).toLong()))
        assertEquals("100 MB", StorageRules.fmt((99.96 * MB).toLong()))
        assertEquals("412 MB", StorageRules.fmt(412 * MB))
        assertEquals("412 MB", StorageRules.fmt((412.4 * MB).toLong()))
        assertEquals("1 GB", StorageRules.fmt(GB))
        assertEquals("1.5 GB", StorageRules.fmt(GB + 512 * MB))
        assertEquals("1.26 GB", StorageRules.fmt((1.256 * GB).toLong()))
        assertEquals("3", StorageRules.trimZeros("3.00"))
        assertEquals("30", StorageRules.trimZeros("30"))
        assertEquals("83%", StorageRules.pctText(83.4))
        assertEquals("118%", StorageRules.pctText(118.0))
        assertEquals(1.0, StorageRules.barFraction(118.0, 10), 0.0)
        assertEquals(0.01, StorageRules.barFraction(0.1, 1024), 0.0)
        assertEquals(0.0, StorageRules.barFraction(0.0, 0), 0.0)
    }

    @Test fun tiers() {
        assertEquals(
            listOf(StorageWarn.Ok, StorageWarn.Warn, StorageWarn.Critical, StorageWarn.Full, StorageWarn.Ok),
            listOf("ok", "warn", "critical", "full", "boom").map(StorageRules::warn),
        )
        assertTrue(StorageRules.isFree(null))
        assertTrue(StorageRules.isFree(""))
        assertFalse(StorageRules.isFree("plus"))
        assertTrue(StorageRules.isOverLimit(null, 120 * MB, 100 * MB))
        assertFalse(StorageRules.isOverLimit("plus", 2 * GB, GB))
        assertFalse(StorageRules.isOverLimit(null, 50 * MB, 100 * MB))
        assertEquals(
            listOf(StorageRules.Tone.Normal, StorageRules.Tone.Normal, StorageRules.Tone.Warning, StorageRules.Tone.Danger),
            listOf(StorageWarn.Ok, StorageWarn.Warn, StorageWarn.Critical, StorageWarn.Full).map { StorageRules.tone(it) },
        )
        assertEquals(StorageRules.Tone.Danger, StorageRules.tone(StorageWarn.Ok, overLimit = true))
        assertEquals("会员", StorageRules.tierLabel("plus"))
        assertEquals("免费档", StorageRules.tierLabel(null))
        assertEquals("会员已到期", StorageRules.tierLabel(null, overLimit = true))
        assertTrue(StorageRules.showsUpgrade(null))
        assertFalse(StorageRules.showsUpgrade("plus"))
    }

    @Test fun kinds() {
        val k = StorageByKindDTO(10, 20, 30, 40, 50, 60, 700, 800)
        assertEquals(210L, StorageRules.filesBytes(k))
        assertEquals(5L, StorageRules.filesBytes(StorageByKindDTO(chat = 5)))
        assertEquals("64 MB", StorageRules.vaultText("plus", 64 * MB))
        assertEquals("18 MB · 仍在云端，开会员后继续同步", StorageRules.vaultText(null, 18 * MB))
        assertEquals("0 · 附件只在本机", StorageRules.vaultText(null, 0))
        assertEquals("64 MB" to null, StorageRules.vaultCell("plus", 64 * MB))
        assertEquals("18 MB · 仍在云端" to "保险箱 18 MB · 仍在云端，开会员后继续同步", StorageRules.vaultCell(null, 18 * MB))
        assertEquals("0 · 只在本机" to null, StorageRules.vaultCell(null, 0))
        assertEquals("64 MB · 到保险箱里管理", StorageRules.vaultRow("plus", 64 * MB))
        assertEquals("0 · 附件只在本机", StorageRules.vaultRow(null, 0))
        assertEquals("18 MB · 仍在云端，开会员后继续同步", StorageRules.vaultRow(null, 18 * MB))
    }

    @Test fun lines() {
        assertNull(StorageRules.statusLine(StorageWarn.Ok, "plus", 40.0, GB))
        assertNull(StorageRules.statusLine(StorageWarn.Warn, "plus", 83.0, GB))
        assertEquals(StorageRules.LineKind.Warning to "已用 96%，快满了。满了之后新文件传不上去，已有的照常能看。",
            StorageRules.statusLine(StorageWarn.Critical, "plus", 96.0, GB))
        assertEquals(StorageRules.LineKind.Full to "已用满 1 GB。清理或升级后才能继续上传，已有的照常能看。",
            StorageRules.statusLine(StorageWarn.Full, "plus", 100.0, GB))
        assertEquals(StorageRules.LineKind.Info to "开会员可到 1 GB", StorageRules.statusLine(StorageWarn.Ok, null, 38.0, 100 * MB))
        assertNull(StorageRules.statusLine(StorageWarn.Full, null, 118.0, 100 * MB, overLimit = true))
        assertEquals("存储已用 83%（850 MB / 1 GB）。满了之后新文件传不上去，已有的照常能看。",
            StorageRules.warnBanner(StorageWarn.Warn, 83.0, 850 * MB, GB))
        assertNull(StorageRules.warnBanner(StorageWarn.Critical, 96.0, 980 * MB, GB))
        assertEquals("会员已到期，上限回落到 100 MB，超出的部分不会丢，清到 100 MB 以下或续费后可以继续上传", StorageRules.expiredBanner(100 * MB))
    }

    @Test fun page() {
        assertEquals(listOf("files", "kb", "vault", "trash"), StorageRules.groups.map { it.first })
        assertEquals(listOf("聊天与附件", "知识库", "保险箱", "回收站"), StorageRules.groups.map { it.second })
        assertEquals("186 MB · 214 项", StorageRules.groupMeta(186 * MB, 214))
        assertNull(StorageRules.groupMeta(0, 0))
        assertEquals("其余 208 项，到对应模块里管理" to "144 MB", StorageRules.restLine(StorageRestDTO(208, 144 * MB)))
        assertNull(StorageRules.restLine(StorageRestDTO(0, 0)))
        assertNull(StorageRules.restLine(null))
        assertEquals("回收站是空的。", StorageRules.emptyText("trash"))
        assertEquals("这一类还没有东西。", StorageRules.emptyText("kb"))
        listOf("chat" to "聊天", "money" to "记账", "reminder" to "提醒", "insp" to "灵感", "task" to "任务", "other" to "其他", "weird" to "其他")
            .forEach { (k, v) -> assertEquals(v, StorageRules.sourceLabel(k)) }
        val file = StorageItemDTO("f1", "a.xlsx", "money", MB, 0)
        assertEquals("记账 · 10月8日", StorageRules.subline("files", file, "10月8日"))
        val doc = StorageItemDTO("k1", "保单.pdf", "保单", MB, 0, kb_id = "kb1")
        assertEquals("知识库 · 保单 · 8月20日", StorageRules.subline("kb", doc, "8月20日"))
        val tr = StorageItemDTO("t1", "旧.pdf", "家里", MB, 0, kb_id = "kb2", left_days = 21)
        assertEquals("原在知识库 · 家里 · 还剩 21 天", StorageRules.subline("trash", tr, "9月21日"))
        assertEquals("原在知识库 · 家里", StorageRules.subline("trash", tr.copy(left_days = null), ""))
        assertEquals(listOf("big", "f1"), StorageRules.sorted(listOf(file, StorageItemDTO("big", "b", "chat", 9 * MB, 0))).map { it.id })
        assertEquals(123L, StorageRules.long(buildJsonObject { put("used", 123) }, "used"))
        assertEquals(1_500_000_000L, StorageRules.long(buildJsonObject { put("used", JsonPrimitive(1.5e9)) }, "used"))
        assertEquals(0L, StorageRules.long(buildJsonObject { }, "used"))
    }

    @Test fun alerts() {
        val f = StorageRules.deleteFileAlert("季度报表.xlsx", (24.6 * MB).toLong())
        assertEquals("删除「季度报表.xlsx」？", f.title)
        assertEquals("删除后马上腾出 24.6 MB，不能找回。引用它的聊天或账目会显示「附件已删除」。", f.body)
        assertEquals("删除", f.button)
        assertEquals("删除后会移入回收站，保留 30 天。回收站也算在用量里，要腾出这 18.4 MB 还得清空回收站。",
            StorageRules.deleteKbDocAlert("保单.pdf", (18.4 * MB).toLong(), 30).body)
        assertTrue(StorageRules.deleteKbDocAlert("x", MB, null).body.contains("保留 30 天"))
        assertTrue(StorageRules.deleteKbDocAlert("x", MB, 14).body.contains("保留 14 天"))
        val p = StorageRules.purgeAlert("旧版合同.pdf", 12 * MB)
        assertEquals("彻底删除「旧版合同.pdf」？", p.title)
        assertEquals("删除后不能再找回，马上腾出 12 MB。", p.body)
        assertEquals("彻底删除", p.button)
        val c = StorageRules.clearTrashAlert(6, 42 * MB)
        assertEquals("清空回收站？", c.title)
        assertEquals("清空后这 6 项立刻删掉，不能再找回，马上腾出 42 MB。", c.body)
        assertEquals("清空", c.button)
        assertEquals("已找回「旧版合同.pdf」，放回知识库「家里」", StorageRules.restoredToast("旧版合同.pdf", "家里"))
        assertEquals("存储已满：已用 1 GB / 1 GB，这个文件 3 MB 放不下。清理或升级会员后再试。", StorageRules.storageFullMessage(GB, GB, 3 * MB))
    }
}
