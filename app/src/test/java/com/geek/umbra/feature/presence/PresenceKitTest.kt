package com.geek.umbra.feature.presence

import com.geek.umbra.net.UmbraJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 输入输出照抄 iOS Tools/swifttests/PresenceKitTests.swift。 */
class PresenceKitTest {
    private val K = PresenceKit

    @Test fun grid() {
        // 契约示例的范围：2025-10-17（周五）… 2026-10-16（周五）。
        val days = listOf(PresenceDayDTO("2026-10-16", 135, 42, 3), PresenceDayDTO("2025-10-17", 5, 1, 1), PresenceDayDTO("2026-01-01", 60, 9, 2))
        val g = K.grid("2025-10-17", "2026-10-16", days)!!
        assertEquals("过去一年 = 53 列", 53, g.columns.size)
        assertTrue(g.columns.all { it.size == 7 })
        assertEquals(false, g.columns[0][0].inRange)
        assertEquals(false, g.columns[0][3].inRange)
        assertEquals("2025-10-17", g.columns[0][4].day)
        assertEquals(1, g.columns[0][4].level)
        val last = g.columns[52]
        assertEquals("2026-10-16", last[4].day)
        assertEquals(3, last[4].level)
        assertEquals(42, last[4].actions)
        assertEquals(false, last[5].inRange)
        assertEquals(false, last[6].inRange)
        assertEquals(2, g.columns.flatten().first { it.day == "2026-01-01" }.level)
        val all = g.columns.flatten().filter { it.inRange }
        assertEquals(365, all.size)
        assertEquals(3, all.count { it.level > 0 })
        val monthsAt = g.months.associate { it.month to it.column }
        assertEquals("第一列的 10 月离 11 月太近 → 丢掉", 11, g.months.first().month)
        assertNotNull(monthsAt[1])
        assertEquals(12, g.months.size)
        assertTrue(g.months.zipWithNext().all { (a, b) -> b.column - a.column >= K.MONTH_LABEL_MIN_GAP })
        val mon = K.grid("2026-10-12", "2026-10-18", emptyList())!!
        assertEquals(1, mon.columns.size)
        assertEquals("2026-10-12", mon.columns[0][0].day)
        assertEquals("2026-10-18", mon.columns[0][6].day)
        assertNull(K.grid("2026-10-16", "2026-10-01", emptyList()))
        assertNull(K.grid("bad", "2026-10-01", emptyList()))
        assertEquals(listOf(0, 2, 4), K.weekdayLabelRows)
    }

    @Test fun level() {
        assertEquals(3, K.level(3, 10, 2))
        assertEquals(4, K.level(9, 10, 2))
        assertEquals(1, K.level(0, 10, 0))
        assertEquals(0, K.level(4, 0, 0))
        assertEquals(1, K.level(-2, 0, 3))
    }

    @Test fun duration() {
        assertEquals("presence.dur.none", K.duration(0).first)
        assertEquals("presence.dur.none", K.duration(-5).first)
        assertEquals("presence.dur.m" to listOf(45), K.duration(45))
        assertEquals("presence.dur.h" to listOf(2), K.duration(120))
        assertEquals("presence.dur.hm" to listOf(2, 15), K.duration(135))
    }

    @Test fun categories() {
        for (c in listOf("chat", "money", "vault", "phrases", "inspirations", "reminders", "tasks", "knowledge", "files", "settings", "other")) {
            assertEquals("presence.cat.$c", K.categoryKey(c))
        }
        assertEquals("presence.cat.other", K.categoryKey("shop"))
        assertEquals(listOf(null, 2026, 2025), K.yearOptions(listOf(2025, 2026, 2026)))
        assertEquals(listOf<Int?>(null), K.yearOptions(emptyList()))
        assertEquals("上海 10-13 0 点 → 2026-10", "2026-10", K.month(1_791_820_800_000))
        assertEquals("上海已经是 10 月 1 日 → 10 月", "2026-10", K.month(1_790_785_800_000))
        assertEquals(2026 to 9, K.yearMonth("2026-09"))
        assertNull(K.yearMonth("2026-13"))
    }

    @Test fun decode() {
        val c = UmbraJson.decodeFromString(PresenceCalendarDTO.serializer(),
            """{"range": {"from": "2025-10-17", "to": "2026-10-16"}, "years": [2026, 2025], "learn": true, "total_days": 120, "total_actions": 3456, "days": [{"day": "2026-10-16", "active_min": 135, "actions": 42, "level": 3}]}""")
        assertEquals("2025-10-17", c.range.from)
        assertEquals(listOf(2026, 2025), c.years)
        assertEquals(3456, c.total_actions)
        assertEquals(135, c.days.first().active_min)
        val sparse = UmbraJson.decodeFromString(PresenceCalendarDTO.serializer(), """{"range": {"from": "2026-01-01", "to": "2026-12-31"}}""")
        assertEquals("缺 learn 按默认开", true, sparse.learn)
        assertEquals(0, sparse.days.size)
        val a = UmbraJson.decodeFromString(PresenceActivityDTO.serializer(), """{"month": "2026-10", "items": [{"category": "money", "count": 34, "days": 12}], "prev": "2026-09"}""")
        assertEquals(34, a.items.first().count)
        assertEquals("2026-09", a.prev)
        val a2 = UmbraJson.decodeFromString(PresenceActivityDTO.serializer(), """{"month": "2026-08", "items": [], "prev": null}""")
        assertNull("prev 为 null → 到头了", a2.prev)
    }

    @Test fun profile() {
        val n = K.normalizedSlots(listOf(0.5, 2.0, -1.0, Double.NaN))
        assertEquals(48, n.size)
        assertEquals(listOf(0.5, 1.0, 0.0, 0.0), n.take(4))
        assertEquals(48, K.normalizedSlots(List(60) { 0.3 }).size)
        assertEquals(0, K.profileLevel(0.0))
        assertEquals(1, K.profileLevel(0.01))
        assertEquals(3, K.profileLevel(0.5))
        assertEquals(4, K.profileLevel(1.0))
        assertEquals(0, K.profileLevel(Double.NaN))
        assertEquals("8:40", K.clockText("08:40"))
        assertEquals("23:10", K.clockText("23:10"))
        assertEquals("8点", K.clockText("8点"))
        val line = K.profileLine(UmbraJson.decodeFromString(PresenceProfileDTO.Part.serializer(), """{"start": "08:40", "end": "23:10"}"""))
        assertEquals("presence.profile.range", line.first)
        assertEquals(listOf("8:40", "23:10"), line.second)
        assertEquals("presence.profile.unknown", K.profileLine(PresenceProfileDTO.Part(start = "", end = "23:10")).first)
        val p = UmbraJson.decodeFromString(PresenceProfileDTO.serializer(), """{"ready": true, "days_used": 23, "workday": {"start": "08:40", "end": "23:10", "slots": [0, 0.5, null, 1]},
         "restday": {"start": "10:00", "end": "23:30", "slots": []}, "unusual": false}""")
        assertEquals(true, p.ready)
        assertEquals(23, p.days_used)
        assertEquals("一格 null 不让整条没了", listOf(0.0, 0.5, 0.0, 1.0), p.workday.slots)
        assertEquals("10:00", p.restday.start)
        val cold = UmbraJson.decodeFromString(PresenceProfileDTO.serializer(), """{"ready": false, "days_used": 3}""")
        assertEquals(false, cold.ready)
        assertEquals(0, cold.workday.slots.size)
    }
}
