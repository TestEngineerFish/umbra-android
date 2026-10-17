package com.geek.umbra.feature.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** HolidayCalendar 的行为用例 —— 照抄 iOS Tools/swifttests/HolidayCalendarTests.swift（回包分档 · 缓存并法 · 拼日历 · 拉哪几年）。 */
class HolidayCalendarTest {
    @Test fun classify() {
        val fresh = """{"year":2026,"days":[{"date":"2026-10-01","name":"国庆节","isOffDay":true},{"date":"2026-10-10","name":"国庆节","isOffDay":false}],"fetched_at_ms":1,"stale":false,"published":true,"rev":"01a743e88cb5"}"""
        val ok = HolidayRules.classify(200, fresh) as CnHolidayFetch.Ok
        assertTrue(ok.entry.days.size == 2 && ok.entry.days[0].isOffDay && !ok.entry.days[1].isOffDay)
        assertTrue(ok.entry.published == true && ok.entry.rev == "01a743e88cb5")
        val stale = HolidayRules.classify(200, """{"year":2026,"days":[{"date":"2026-10-01","isOffDay":true}],"stale":true}""") as CnHolidayFetch.Stale
        assertTrue(stale.entry.days.size == 1 && stale.entry.published == null && stale.entry.rev == null)
        val empty = HolidayRules.classify(200, """{"year":2027,"days":[],"stale":false,"published":false,"rev":"97d170e1550e"}""") as CnHolidayFetch.Ok
        assertTrue(empty.entry.days.isEmpty() && empty.entry.published == false)
        assertEquals(CnHolidayFetch.UpstreamFailed, HolidayRules.classify(502, """{"detail":"upstream_failed"}"""))
        assertEquals(CnHolidayFetch.Failed, HolidayRules.classify(null, null))
        assertEquals(CnHolidayFetch.Failed, HolidayRules.classify(404, """{"detail":"not found"}"""))
        assertEquals(CnHolidayFetch.Failed, HolidayRules.classify(401, fresh))
        assertEquals(CnHolidayFetch.Failed, HolidayRules.classify(200, """{"year":2026,"days":"x"}"""))
        assertEquals(CnHolidayFetch.Failed, HolidayRules.classify(200, "not json"))
        for (f in listOf(CnHolidayFetch.UpstreamFailed, CnHolidayFetch.Failed)) assertNull(f.entry)
        val lossy = HolidayRules.classify(200, """{"days":[{"date":"2026-10-01","isOffDay":true},{"name":"缺 date"},{"date":5},{"date":"2026-10-10"}],"stale":false}""") as CnHolidayFetch.Ok
        assertEquals(listOf("2026-10-01", "2026-10-10"), lossy.entry.days.map { it.date })
        assertEquals(false, lossy.entry.days.last().isOffDay)
    }

    @Test fun merge() {
        val d26 = HolidayYearEntry(listOf(CnHolidayDay("2026-10-01", true)), true, "a")
        val d27 = HolidayYearEntry(emptyList(), false, "b")
        val stored = HolidayCacheFile("https://s1", mapOf("2026" to d26, "2027" to d27))
        assertEquals(stored, HolidayRules.merged(stored, "https://s1", listOf(2026 to CnHolidayFetch.UpstreamFailed, 2027 to CnHolidayFetch.Failed)))
        val new26 = HolidayYearEntry(listOf(CnHolidayDay("2026-10-02", true)), true, "c")
        val m = HolidayRules.merged(stored, "https://s1", listOf(2026 to CnHolidayFetch.Stale(new26), 2027 to CnHolidayFetch.Failed))
        assertTrue(m.years["2026"] == new26 && m.years["2027"] == d27)
        val other = HolidayRules.merged(stored, "https://s2", listOf(2026 to CnHolidayFetch.Failed))
        assertTrue(other.server == "https://s2" && other.years.isEmpty())
        assertNull(HolidayRules.calendar(stored, "https://s2"))
        assertNull(HolidayRules.calendar(null, "https://s1"))
        val first = HolidayRules.merged(null, "https://s1", listOf(2026 to CnHolidayFetch.Ok(d26), 2027 to CnHolidayFetch.UpstreamFailed))
        assertEquals(setOf(2026), HolidayRules.calendar(first, "https://s1")?.years)
        assertEquals(m, HolidayRules.decode(HolidayRules.encode(m).toString()))
        assertNull(HolidayRules.decode("""{"server":"https://s1","years":{"2026":{"days":[{"date":"2026-10-01","isOffDay":true}]}}}""")?.years?.get("2026")?.rev)
        assertEquals("2026@a 2027@b", HolidayRules.revSummary(stored))
        assertEquals("无表", HolidayRules.revSummary(null))
    }

    @Test fun calendar() {
        val c = HolidayRules.from(mapOf(
            "2026" to listOf(CnHolidayDay("2026-10-01", true), CnHolidayDay("2026-10-10", false), CnHolidayDay("bad", true),
                CnHolidayDay("2026-1-5", true), CnHolidayDay("２０２６-10-02", true)),
            "2027" to emptyList(),
            "x" to listOf(CnHolidayDay("2026-10-03", true)),
        ))
        assertEquals(setOf(2026, 2027), c.years)
        assertTrue(c.off == setOf("2026-10-01") && c.work == setOf("2026-10-10"))
    }

    @Test fun years() {
        val sh = ZoneId.of("Asia/Shanghai")
        assertEquals(listOf(2026, 2027), HolidayRules.yearsToFetch(1_798_731_000_000L, sh))
        assertEquals(listOf(2027, 2028), HolidayRules.yearsToFetch(1_798_734_600_000L, sh))
    }
}
