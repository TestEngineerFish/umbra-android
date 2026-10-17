package com.geek.umbra.feature.chat

import com.geek.umbra.feature.chat.ChatHistoryMerge as M
import com.geek.umbra.feature.chat.ChatHistoryPaging as P
import com.geek.umbra.net.UmbraJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 照抄 iOS Tools/swifttests/ChatHistoryMergeTests.swift。 */
class ChatHistoryMergeTest {
    private fun dev(t: String) = M.Early(echoKey = M.echoKey("device", t))
    private fun sec(t: String) = M.Early(echoKey = M.echoKey("assistant", t))
    private val card = M.Early()

    @Test fun pushThenHistory() {
        val history = listOf(Triple(1L, "user", "帮我截个屏"), Triple(2L, "assistant", "好的"), Triple(3L, "device", "截屏完成"))
        val early = listOf("推送:截屏完成" to dev("截屏完成"), "授权卡" to card)
        val kept = M.keep(early.map { it.second }, history.map { it.first }.toSet(),
            history.filter { it.second != "user" }.map { M.echoKey(it.second, it.third) })
        val merged = history.map { "历史:${it.third}" } + kept.map { early[it].first }
        assertEquals("先推送后拉历史", listOf("历史:帮我截个屏", "历史:好的", "历史:截屏完成", "授权卡"), merged)
        assertEquals("历史里没有的推送留下", listOf(0), M.keep(listOf(dev("只广播了")), setOf(1, 2), listOf(M.echoKey("assistant", "好的"))))
        assertEquals("空历史：早到块全留", listOf(0, 1), M.keep(listOf(dev("a"), card), emptySet(), emptyList()))
    }

    @Test fun idCases() {
        assertEquals(emptyList<Int>(), M.keep(listOf(M.Early(serverId = 7)), setOf(5, 6, 7), emptyList()))
        assertEquals(listOf(0), M.keep(listOf(M.Early(serverId = 9)), setOf(5, 6, 7), emptyList()))
        assertEquals("有 id 就只看 id", listOf(0), M.keep(listOf(M.Early(serverId = 9, echoKey = M.echoKey("device", "x"))), setOf(1), listOf(M.echoKey("device", "x"))))
    }

    @Test fun echoCases() {
        val ok = M.echoKey("device", "ok")
        assertEquals(listOf(1), M.keep(listOf(dev("ok"), dev("ok")), emptySet(), listOf(ok)))
        assertEquals(emptyList<Int>(), M.keep(listOf(dev("ok"), dev("ok")), emptySet(), listOf(ok, ok, ok)))
        assertEquals("角色不同不抵", listOf(0), M.keep(listOf(sec("ok")), emptySet(), listOf(ok)))
        assertEquals(emptyList<Int>(), M.keep(listOf(sec("好")), emptySet(), listOf(M.echoKey("assistant", "好"))))
        assertEquals("卡片类一律留", listOf(0, 1), M.keep(listOf(card, card), setOf(1), listOf(ok)))
        assertEquals("键的两段切法不同不相撞", false, M.echoKey("device", "x") == M.echoKey("devic", "ex"))
    }

    @Test fun freshPushCases() {
        val ok = M.echoKey("device", "ok")
        val shot = M.echoKey("device", "截屏完成")
        assertEquals(listOf(0), M.keep(listOf(dev("ok")), emptySet(), listOf(ok, shot)))
        assertEquals(listOf(1), M.keep(listOf(dev("截屏完成"), dev("ok")), emptySet(), listOf(ok, shot)))
        assertEquals(listOf(1, 2), M.keep(listOf(dev("截屏完成"), card, dev("ok")), emptySet(), listOf(ok, shot)))
        assertEquals(listOf(0), M.keep(listOf(dev("截屏完成")), emptySet(), listOf(shot, ok)))
    }

    @Test fun tailCollisionCases() {
        val ok = M.echoKey("device", "ok")
        val t0 = 1_000_000.0
        fun devAt(t: String, at: Double?) = M.Early(echoKey = M.echoKey("device", t), at = at)
        assertEquals(emptyList<Int>(), M.keep(listOf(dev("ok")), emptySet(), listOf(ok)))
        assertEquals(listOf(0), M.keep(listOf(devAt("ok", t0 + 60)), emptySet(), listOf(ok), listOf(t0)))
        assertEquals(emptyList<Int>(), M.keep(listOf(devAt("ok", t0 + 0.4)), emptySet(), listOf(ok), listOf(t0)))
        assertEquals(emptyList<Int>(), M.keep(listOf(devAt("ok", t0 + 0.95)), emptySet(), listOf(ok), listOf(t0)))
        assertEquals(emptyList<Int>(), M.keep(listOf(devAt("ok", t0 + 8)), emptySet(), listOf(ok), listOf(t0)))
        assertEquals(listOf(0), M.keep(listOf(devAt("ok", t0 - 30)), emptySet(), listOf(ok), listOf(t0)))
        assertEquals(emptyList<Int>(), M.keep(listOf(devAt("ok", null)), emptySet(), listOf(ok), listOf(t0)))
        assertEquals(emptyList<Int>(), M.keep(listOf(devAt("ok", t0 + 60)), emptySet(), listOf(ok), listOf(null)))
        assertEquals(emptyList<Int>(), M.keep(listOf(devAt("ok", t0 + 60)), emptySet(), listOf(ok, ok), listOf(t0)))
        assertEquals(listOf(1), M.keep(listOf(devAt("ok", t0 + 0.3), devAt("ok", t0 + 40)), emptySet(), listOf(ok), listOf(t0)))
        assertEquals(listOf(0), M.keep(listOf(M.Early(serverId = 99, echoKey = ok, at = t0 + 0.2)), setOf(5), listOf(ok), listOf(t0)))
    }

    @Test fun pushIdCases() {
        fun pid(text: String): Long? = M.pushId(UmbraJson.parseToJsonElement(text) as JsonObject)
        assertEquals(42L, pid("""{"type":"device_message","message_id":42}"""))
        assertEquals(null, pid("""{"type":"device_message","text":"ok"}"""))
        assertEquals(null, pid("""{"message_id":"42"}"""))
        assertEquals(null, pid("""{"message_id":null}"""))
        assertEquals(null, pid("""{"message_id":true}"""))
        assertEquals(null, pid("""{"message_id":4.5}"""))
        assertEquals(null, pid("""{"message_id":0}"""))
        assertEquals(null, pid("""{"id":42}"""))
        val ok = M.echoKey("device", "ok")
        assertEquals(listOf(0), M.keep(listOf(M.Early(serverId = pid("""{"message_id":99}"""), echoKey = ok)), setOf(5), listOf(ok)))
        assertEquals(emptyList<Int>(), M.keep(listOf(M.Early(serverId = pid("""{"message_id":5}"""), echoKey = ok)), setOf(5), listOf(ok)))
    }

    @Test fun epochCases() {
        fun s(v: Double?) = v?.let { String.format(java.util.Locale.ROOT, "%.3f", it) } ?: "nil"
        assertEquals("1791290096.000", s(M.epoch("2026-10-06 12:34:56")))
        assertEquals("1791290096.123", s(M.epoch("2026-10-06T12:34:56.123456+00:00")))
        assertEquals("1791290096.000", s(M.epoch("2026-10-06T12:34:56Z")))
        assertEquals("1791290096.000", s(M.epoch("2026-10-06T20:34:56+08:00")))
        assertEquals("1791290096.000", s(M.epoch("2026-10-06T07:34:56-0500")))
        assertEquals("nil", s(M.epoch("昨天")))
        assertEquals("nil", s(M.epoch(null)))
    }

    @Test fun pagingCases() {
        val fresh = P.Cursor(null, true, false, false)
        val full = (1L..40L).toList()
        assertEquals(P.Cursor(null, true, false, true), P.afterFirst(null, true, fresh))
        assertEquals(P.Cursor(null, false, false, false), P.afterFirst(emptyList(), true, fresh))
        assertEquals(P.Cursor(5, false, false, false), P.afterFirst(listOf(5, 6, 7), true, fresh))
        assertEquals(P.Cursor(1, true, true, false), P.afterFirst(full, true, fresh))
        val failed = P.afterFirst(null, true, fresh)
        assertEquals(P.Cursor(9, false, false, false), P.afterFirst(listOf(9), true, failed))
        val paged = P.Cursor(3, true, true, false)
        assertEquals(paged, P.afterFirst((100L..139L).toList(), false, paged))
        assertEquals(paged, P.afterFirst(null, false, paged))
        assertEquals(P.Cursor(3, true, true, true), P.afterOlder(null, paged))
        assertEquals(P.Cursor(3, false, true, false), P.afterOlder(emptyList(), paged))
        assertEquals(P.Cursor(1, false, true, false), P.afterOlder(listOf(1, 2), paged))
        assertEquals(P.Cursor(1, true, true, false), P.afterOlder(full, paged))
    }
}
