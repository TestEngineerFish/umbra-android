package com.geek.umbra.feature.inspiration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 输入输出照抄 iOS Tools/swifttests/InspirationWriteRulesTests.swift（umbra-ios#60 / #69）。 */
class InspirationWriteRulesTest {
    private val W = InspWriteRules
    private fun idea(id: Long, research: String?) = Inspiration(
        id = id, raw = "原文$id", title = "", summary = "", tags = listOf("a"), status = "open", source_channel = "ios",
        organize_status = "done", research = if (research == "done") "纪要" else null, research_status = research,
    )

    @Test fun save() {
        for (research in listOf(false, true)) for (titleEmpty in listOf(false, true)) {
            val o = W.afterSave(false, research, titleEmpty)
            assertFalse(o.leave)
            assertEquals("没存上，内容还在，稍后再点保存", o.toast)
            assertFalse(o.toast.contains("已"))
            assertTrue(W.afterSave(true, research, titleEmpty).leave)
        }
        assertEquals("已记下，秘书这就去查", W.afterSave(true, true, true).toast)
        assertEquals("已记下，秘书稍后补标题", W.afterSave(true, false, true).toast)
        assertEquals("已保存", W.afterSave(true, false, false).toast)
    }

    @Test fun otherWrites() {
        assertEquals(InspWriteRules.DeleteOutcome(true, "已移入回收站 · 保留 30 天"), W.afterDelete(1))
        assertEquals(InspWriteRules.DeleteOutcome(false, "没删掉，稍后再试"), W.afterDelete(null))
        val gone = W.afterDelete(0)
        assertTrue(gone.leave)
        assertFalse(gone.toast.contains("回收站"))
        assertEquals("这条已经不在了，可能在别的设备上删掉了", gone.toast)
        assertEquals(0, W.deletedCount(200, """{"deleted": 0}"""))
        assertEquals(1, W.deletedCount(200, """{"deleted": 1}"""))
        assertEquals("2xx 但回包解不开 → 按删成算", 1, W.deletedCount(200, "ok"))
        assertNull(W.deletedCount(302, """{"deleted": 1}"""))
        assertNull(W.deletedCount(503, ""))
        assertNull(W.afterStatus(true))
        assertEquals("没改成，稍后再试", W.afterStatus(false))
        assertEquals("已交给秘书，查完会更新到这里", W.afterResearch(true))
        assertEquals("没交给秘书，稍后再试", W.afterResearch(false))
    }

    @Test fun research() {
        for (before in listOf("done", "failed", "idle", null)) assertEquals(before, W.researchRollback(before))
        val list = listOf(idea(1, "done"), idea(2, null))
        val before = list.first { it.id == 1L }.research_status
        val queued = W.settingResearch(list, 1, "queued")
        assertEquals("queued", queued[0].research_status)
        assertEquals("纪要", queued[0].researchText)
        assertNull(queued[1].research_status)
        val back = W.settingResearch(queued, 1, W.researchRollback(before))
        assertEquals("done", back[0].research_status)
        assertEquals("done", back[0].researchState)
        assertEquals(listOf(1L, 2L), back.map { it.id })
        assertEquals("原文1", back[0].raw)
        assertEquals(listOf("a"), back[0].tags)
        assertEquals(list.map { it.research_status }, W.settingResearch(list, 99, "queued").map { it.research_status })
    }

    @Test fun editPatch() {
        val pre = InspWriteRules.EditSnapshot("做个读书 App", "", emptyList())
        val p1 = W.editPatch(pre, "做个读书 app", "", "")
        assertEquals(setOf("raw"), p1.keys)
        assertEquals("做个读书 app", p1.raw)
        assertNull(p1.summary)
        val p2 = W.editPatch(pre, "做个读书 App", " 读书 App ", "")
        assertEquals(setOf("title"), p2.keys)
        assertEquals("读书 App", p2.title)
        val p3 = W.editPatch(pre, "做个读书 App", "", "产品、阅读")
        assertEquals(setOf("tags"), p3.keys)
        assertEquals(listOf("产品", "阅读"), p3.tags)
        assertTrue("只差首尾空白 → 空 patch", W.editPatch(pre, " 做个读书 App\n", "  ", " ").isEmpty)
        val post = InspWriteRules.EditSnapshot("r", "读书 App", listOf("产品", "阅读"))
        val shown = W.tagsText(post.tags)
        assertTrue(W.editPatch(post, "r", "读书 App", shown).isEmpty)
        assertTrue("换了分隔符但标签相同 → 不算改", W.editPatch(post, "r", "读书 App", "产品,阅读").isEmpty)
        val p4 = W.editPatch(post, "r", "", shown)
        assertEquals(setOf("title"), p4.keys)
        assertEquals("", p4.title)
        assertEquals(emptyList<String>(), W.editPatch(post, "r", "读书 App", "").tags)
        val odd = InspWriteRules.EditSnapshot("r", "t", listOf("a,b"))
        assertTrue("标签里带逗号、没动 → 不算改", W.editPatch(odd, "r", "t", W.tagsText(odd.tags)).isEmpty)
        assertEquals(InspWriteRules.EditSnapshot("原文", "", emptyList()), W.snapshot(Inspiration(id = 7, raw = "原文", organize_status = "pending")))
    }

    @Test fun editMode() {
        val snap = InspWriteRules.EditSnapshot("r", "", emptyList())
        assertEquals(InspWriteRules.EditMode.Create, W.editMode(null, null))
        assertEquals("带 id、找不到 → gone，不新建", InspWriteRules.EditMode.Gone, W.editMode("3", null))
        assertEquals(InspWriteRules.EditMode.Update(snap), W.editMode("3", snap))
        assertEquals(InspWriteRules.UpdateResult.Saved, W.updateResult(200))
        assertEquals(InspWriteRules.UpdateResult.Gone, W.updateResult(404))
        assertEquals(InspWriteRules.UpdateResult.Failed, W.updateResult(500))
        assertEquals(InspWriteRules.UpdateResult.Failed, W.updateResult(302))
        assertEquals(InspWriteRules.UpdateResult.Failed, W.updateResult(null))
        val gone = W.afterUpdate(InspWriteRules.UpdateResult.Gone)
        assertFalse(gone.leave)
        assertFalse(gone.toast.contains("稍后"))
        assertTrue(gone.toast.contains("已在别处删除"))
        assertEquals(InspWriteRules.SaveOutcome(false, "没存上，内容还在，稍后再点保存"), W.afterUpdate(InspWriteRules.UpdateResult.Failed))
        assertEquals(InspWriteRules.SaveOutcome(true, "已保存"), W.afterUpdate(InspWriteRules.UpdateResult.Saved))
    }

    @Test fun loadOrder() {
        assertTrue(W.shouldApplyLoad(2, 1))
        assertFalse(W.shouldApplyLoad(1, 2))
        assertFalse(W.shouldApplyLoad(2, 2))
        var applied = 0
        for (issued in listOf(2, 1)) if (W.shouldApplyLoad(issued, applied)) applied = issued
        assertEquals("B 先回 A 后回 → 留的是 B", 2, applied)
        assertEquals("running", W.rollingBackResearch(listOf(idea(1, "running")), 1, "idle", true)[0].research_status)
        assertEquals("done", W.rollingBackResearch(listOf(idea(1, "queued")), 1, "done", false)[0].research_status)
    }

    @Test fun apply() {
        val list = listOf(idea(1, null), idea(2, null))
        val s = W.applying(list, 1, InspPatch(status = "done"))
        assertEquals("done", s[0].status)
        assertEquals("open", s[1].status)
        assertEquals("原文1", s[0].raw)
        val e = W.applying(list, 2, InspPatch(title = " 新标题 ", tags = listOf("x", " ")))
        assertEquals("新标题", e[1].title)
        assertEquals(listOf("x"), e[1].tags)
        assertEquals("原文2", e[1].raw)
        assertEquals("raw 空 → 不动", "原文2", W.applying(list, 2, InspPatch(raw = "  "))[1].raw)
        assertEquals(listOf("open", "open"), W.applying(list, 9, InspPatch(status = "done")).map { it.status })
    }

    @Test fun parseTags() {
        assertEquals(listOf("产品", "效率", "阅读"), W.parseTags("产品，效率、 阅读,"))
    }
}
