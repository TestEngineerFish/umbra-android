package com.geek.umbra.feature.memory

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** MemoryRules 用例。输入输出照抄 iOS Tools/swifttests/MemoryRulesTests.swift。 */
class MemoryRulesTest {
    private fun m(id: String, kind: String, body: String = "- x") = MemoryItemDTO(id, kind, id, "", body, "2026-10-14T00:00:00+00:00")
    private val sh = ZoneId.of("Asia/Shanghai")
    private val raw: JsonObject = buildJsonObject {
        put("id", "m_1"); put("kind", "topic"); put("title", "车"); put("summary", "s"); put("body", "- b"); put("updated_at", "t")
    }

    @Test fun groups() {
        assertEquals(listOf("you", "you", "topic", "area", "person", "topic"),
            listOf("preferences", "profile", "topic", "area", "person", "weird").map(MemoryRules::group))
        val g = MemoryRules.grouped(listOf(m("preferences", "preferences"), m("profile", "profile"), m("b", "person"), m("a", "person")))
        assertEquals(listOf("你", "人物"), g.map { it.first })
        assertEquals(listOf("b", "a"), g[1].second.map { it.id })
        assertEquals(listOf("你"), MemoryRules.grouped(emptyList()).map { it.first })
        assertEquals(listOf(true, true, false), listOf("preferences", "profile", "topic").map(MemoryRules::isFixed))
        assertEquals(listOf("a", "b", "c"), MemoryRules.bodyLines("- a\n\n* b\nc  \n- \n-"))
        assertTrue(MemoryRules.isEmpty(m("x", "topic", " \n- \n")))
        assertEquals(listOf("你", "人物", "话题"), listOf("profile", "person", "x").map(MemoryRules::groupLabel))
    }

    @Test fun time() {
        val now = MemoryRules.parseIso("2026-10-14T04:00:00+00:00")!! // 上海 12:00
        assertEquals("今天更新", MemoryRules.updatedText("2026-10-13T20:00:00+00:00", now, sh))
        assertEquals("昨天更新", MemoryRules.updatedText("2026-10-13T01:00:00+00:00", now, sh))
        assertEquals("4 天前更新", MemoryRules.updatedText("2026-10-10T01:00:00+00:00", now, sh))
        assertEquals("09-07 更新", MemoryRules.updatedText("2026-09-07T01:00:00+00:00", now, sh))
        assertEquals("2025-12-31 更新", MemoryRules.updatedText("2025-12-31T01:00:00+00:00", now, sh))
        assertEquals("", MemoryRules.updatedText("坏的", now, sh))
        assertNotNull(MemoryRules.parseIso("2026-10-14T04:00:00.123+00:00"))
        assertEquals("今天 04:30", MemoryRules.agoText("2026-10-13T20:30:00+00:00", now, sh))
        assertEquals("4 天前", MemoryRules.agoText("2026-10-10T01:00:00+00:00", now, sh))
        assertEquals("秘书 14:07 从一段对话里改了这条。现在保存会盖掉那一版。", MemoryRules.changedText("chat", "2026-10-14T06:07:00+00:00", sh))
        assertEquals("14:07 在别处改了这条。现在保存会盖掉那一版。", MemoryRules.changedText("user", "2026-10-14T06:07:00+00:00", sh))
    }

    @Test fun parse() {
        assertEquals("车", MemoryRules.item(raw)?.title)
        assertNull(MemoryRules.item(buildJsonObject { put("kind", "topic") }))
        val list = buildJsonObject { putJsonArray("items") { add(raw); add(3); addJsonObject { put("id", "") } } }
        assertEquals(1, MemoryRules.items(list)?.size)
        assertNull(MemoryRules.items(buildJsonObject { put("x", 1) }))
        assertEquals("import", MemoryRules.item(buildJsonObject { put("id", "a"); put("source", "import") })?.source)
    }

    @Test fun write() {
        assertEquals(MemoryWrite.Failed("连不上服务器"), MemoryRules.write(null, null))
        assertEquals(MemoryWrite.Ok(MemoryRules.item(raw), null, null), MemoryRules.write(200, buildJsonObject { put("item", raw) }))
        assertEquals(MemoryWrite.Ok(null, listOf(MemoryRules.item(raw)!!), 2),
            MemoryRules.write(200, buildJsonObject { put("changed", buildJsonArray { add(1); add(2) }); put("items", buildJsonArray { add(raw) }) }))
        assertEquals(MemoryWrite.Ok(null, null, 0, full = true, skipped = 3),
            MemoryRules.write(200, buildJsonObject { put("changed", buildJsonArray { }); put("full", true); put("skipped", 3) }))
        assertEquals(MemoryWrite.Ok(null, null, 2, firstChanged = "b"),
            MemoryRules.write(200, buildJsonObject {
                put("changed", buildJsonArray { addJsonObject { put("id", "a"); put("deleted", true) }; addJsonObject { put("id", "b") } })
            }))
        assertEquals("导入了一部分：记忆满了，有 3 条没建上。改已有的那几条都记上了；删掉几条不用的，再导入一次能补上。", MemoryRules.importFullText(3))
        assertEquals(MemoryWrite.Conflict(MemoryRules.item(raw)!!), MemoryRules.write(409, buildJsonObject { put("state", "memory_changed"); put("current", raw) }))
        assertEquals(MemoryWrite.Failed("记忆最多 200 条"), MemoryRules.write(409, buildJsonObject { put("state", "memory_full"); put("detail", "记忆最多 200 条") }))
        assertEquals(MemoryWrite.Failed("没保存成，稍后再试"), MemoryRules.write(502, null))
        assertEquals("已更新 3 条记忆", MemoryRules.toldText(3))
        assertEquals("秘书觉得没什么需要改的", MemoryRules.toldText(0))
        assertEquals("已导入，更新了 8 条记忆", MemoryRules.importedText(8))
        assertEquals("秘书觉得没什么需要改的", MemoryRules.importedText(null))
    }

    @Test fun loadAndSession() {
        // umbra-ios#86：读失败不抹掉手里那份，但失败标记一律亮；读到就换上。
        val old = listOf(m("preferences", "preferences"))
        val fresh = listOf(m("profile", "profile"))
        assertEquals(old, MemoryRules.afterLoad(null, old).first)
        assertTrue(MemoryRules.afterLoad(null, old).second)
        assertTrue(MemoryRules.afterLoad(null, null).second)
        assertEquals(fresh, MemoryRules.afterLoad(fresh, old).first)
        assertEquals(false, MemoryRules.afterLoad(fresh, old).second)
        assertEquals(false, MemoryRules.sessionChanged(null, 1))
        assertEquals(false, MemoryRules.sessionChanged(1, 1))
        assertEquals(true, MemoryRules.sessionChanged(1, 2))
    }

    @Suppress("unused")
    private val keep = JsonPrimitive(0)
}
