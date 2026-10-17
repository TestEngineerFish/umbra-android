package com.geek.umbra.feature.inspiration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 输入输出照抄 iOS Tools/swifttests/InspLocalRulesTests.swift（逐条和 PC tests/inspLocal.test.ts 对得上）。 */
class InspLocalRulesTest {
    private val R = InspLocalRules
    private fun I(uid: String, id: Long = 1, raw: String = "r", updated: Long = 10, dirty: Boolean? = null, organize: String = "done", title: String = "") =
        Inspiration(id = id, raw = raw, title = title, summary = "", tags = emptyList(), status = "open", source_channel = "ios", created_at = "", updated_at = "",
            organize_status = organize, research = "", research_status = "idle", research_at = "", uid = uid, updated_at_ms = updated, atts = null, dirty = dirty)

    @Test fun tempId() {
        assertTrue("临时 id 稳定且为负", R.tempId("abc") == R.tempId("abc") && R.tempId("abc") < 0 && R.tempId("abc") != R.tempId("abd"))
    }

    @Test fun merge() {
        val out = R.merge(
            local = listOf(I("a", id = R.tempId("a"), raw = "本机改", updated = 20, dirty = true, organize = "pending"), I("b", raw = "旧", updated = 5)),
            items = listOf(I("a", id = 7, raw = "远端", updated = 10, organize = "done", title = "整理出的"), I("b", raw = "新", updated = 9), I("z", updated = 1)),
            tombs = listOf(InspTomb("c", 40)),
            pendingTombs = listOf(InspTomb("z", 2)),
        )
        val by = out.associateBy { it.key }
        assertEquals("本机更新留正文，整数 id / 整理结果照收", listOf("本机改", "7", "done", "true"),
            by["a"]!!.let { listOf(it.raw, it.id.toString(), it.organize_status ?: "", (it.dirty == true).toString()) })
        assertEquals("服务端更新取服务端", listOf("新", "false"), by["b"]!!.let { listOf(it.raw, (it.dirty == true).toString()) })
        assertNull("本机删得更新：不加回", by["z"])
        assertEquals("老数据的 key", "i5", Inspiration(id = 5).key)
    }

    @Test fun sortAndFilter() {
        val s = R.sorted(listOf(I("x", id = 3), I("y", id = R.tempId("y"), updated = 50), I("w", id = 9)))
        assertEquals("新→旧，离线新建最前", listOf("y", "w", "x"), s.map { it.key })
        assertEquals(0, R.filtered(s, "done").size)
        assertEquals("不认识的状态不筛", 3, R.filtered(s, "bogus").size)
    }

    @Test fun freshAndApply() {
        val f = R.fresh("n", " 想法 ", "", "", listOf(" t ", ""), true, 100, "now")
        assertEquals(listOf("想法", "pending", "queued", "t", "true", "true"),
            listOf(f.raw, f.organize_status ?: "", f.research_status ?: "", f.tags.joinToString(""), (f.dirty == true).toString(), (f.id < 0).toString()))
        val g = R.applying(f, InspPatch(status = "done", title = "有标题", raw = " "), 200)
        assertEquals("本机改：只改给了的、空 raw 不覆盖、盖戳", listOf("done", "有标题", "想法", "200"), listOf(g.status, g.title, g.raw, (g.updated_at_ms ?: 0).toString()))
    }

    @Test fun accountSwitch() {
        val mine = listOf(I("s1", id = 11), I("d1", id = 12, raw = "改过", dirty = true), I("d2", id = R.tempId("d2"), dirty = true))
        assertEquals("待推 = 2 条 dirty + 1 条墓碑", 3, R.pendingCount(mine, listOf(InspTomb("x", 1))))
        assertEquals(0, R.rebaseForAccount(false, mine).size)
        val carried = R.rebaseForAccount(true, mine)
        assertEquals(listOf("d1", "d2"), carried.map { it.key })
        assertEquals("整数 id 换成临时 id", listOf(R.tempId("d1"), R.tempId("d2")), carried.map { it.id })
        assertEquals("改过", carried.first().raw)
    }

    @Test fun displayTitle() {
        assertEquals("有标题", Inspiration(title = " 有标题 ", raw = "x").displayTitle)
        assertEquals("短原文", Inspiration(raw = "短原文").displayTitle)
        assertEquals("一二三四五六七八九十一二三四五六七八九十…", Inspiration(raw = "一二三四五六七八九十一二三四五六七八九十多出来").displayTitle)
        assertEquals("换行变空格", "a b", Inspiration(raw = "a\nb").displayTitle)
    }
}
