package com.geek.umbra.feature.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * ReminderAdvance 的行为用例 —— 逐条照抄 iOS Tools/swifttests/ReminderAdvanceTests.swift。
 * 要钉的是**与 PC 逐毫秒相同**（夹具由 node 执行 PC scanner.ts 取得）、写回形状、排通知护栏、天文数字不炸、
 * 工作日认节假日、月末锚定、按提醒时区推进、每小时直接算、整周期快进、结束日推进与排通知同一截点、每周多选星期几。
 */
class ReminderAdvanceTest {
    private val sh: ZoneId = ZoneId.of("Asia/Shanghai")
    private val ny: ZoneId = ZoneId.of("America/New_York")
    private val utc: ZoneId = ZoneId.of("UTC")
    private val kiri: ZoneId = ZoneId.of("Pacific/Kiritimati")
    private val dayMs = 86_400_000L

    private fun rem(
        at: Long, rule: String = "不重复", freq: String = "天", n: Long = 1, end: Long? = null, updated: Long = 1,
        dirty: Boolean = false, tz: String = "Asia/Shanghai", anchor: Int? = null, days: List<Int>? = null, id: String = "x",
    ) = Reminder(id = id, text = "x", atMs = at, repeatRule = rule, customFreq = freq, customN = n, repeatEndMs = end,
        updatedAtMs = updated, dirty = dirty, tz = tz, anchorDay = anchor, weekDays = days)

    @Test fun parity() {
        for (f in pcFixtures) {
            val tz = ReminderAdvance.zone(f.tz, sh)
            val got = ReminderAdvance.advance(f.at, f.rule, f.freq, f.n, f.end, false, f.now, tz, anchorDay = f.anchor)
            // ③：tz 合法时 fallback 换成别的结果必须不变 —— 推进的时区是提醒的，不是这台设备的。
            if (ReminderAdvance.zone(f.tz, kiri).id == tz.id) {
                val other = ReminderAdvance.advance(f.at, f.rule, f.freq, f.n, f.end, false, f.now, ReminderAdvance.zone(f.tz, utc), anchorDay = f.anchor)
                assertEquals("${f.name}：换了本机时区结果就变了", got, other)
            }
            assertEquals("${f.name}（${f.tz}）：与 PC 推出两个 at", f.want, got)
            // 推完再推一次必须不动。
            if (got != null && !got.done) {
                assertNull("${f.name}：推到未来之后再推一次又动了",
                    ReminderAdvance.advance(got.atMs, f.rule, f.freq, f.n, f.end, false, f.now, tz, anchorDay = f.anchor))
            }
        }
        assertNull("已完成的重复提醒被推进了", ReminderAdvance.advance(0, "daily", "day", 1, null, true, dayMs * 3, sh))
    }

    @Test fun applied() {
        val now = 1_791_180_000_000L
        val clean = rem(1_790_816_400_000L, rule = "每天", updated = 1_700_000_000_000L)
        val v = ReminderAdvance.applied(clean, now, fallbackTz = sh)!!
        assertEquals(1_791_248_400_000L, v.atMs)
        assertEquals("推进的戳应是原值 + 1", 1_700_000_000_001L, v.updatedAtMs)
        assertTrue("推进之后没标脏", v.dirty)
        assertEquals("干净行被推进后，横幅应从现在起算", now, v.pendingSinceMs)
        assertTrue("推进不该让上行带 atts 键", !v.attsTouched && ReminderAttsWire.upstream(v.atts, v.attsTouched) == null)
        assertNull(ReminderAdvance.applied(v, now, fallbackTz = sh))

        // 服务端不收的那一行被自动推进之后，标记跟着留下。
        val refused = clean.copy(pushRejection = ReminderPushRejection(400, "x"))
        assertEquals(400, ReminderAdvance.applied(refused, now, fallbackTz = sh)?.pushRejection?.status)

        // 原来就脏着：横幅时刻保持它自己的（null），戳照样 +1。
        val edited = clean.copy(dirty = true, updatedAtMs = now - 60_000)
        val e = ReminderAdvance.applied(edited, now, fallbackTz = sh)!!
        assertNull(e.pendingSinceMs); assertEquals(now - 59_999, e.updatedAtMs)

        // 每月 1/31 → 2/28，写回带锚定日 31，再推一次回到 3/31。
        val monthly = rem(1_769_821_200_000L, rule = "每月")
        val feb = ReminderAdvance.applied(monthly, 1_771_084_800_000L, fallbackTz = sh)!!
        assertEquals(1_772_240_400_000L, feb.atMs)
        assertEquals(31, feb.anchorDay)
        assertEquals(1_774_918_800_000L, ReminderAdvance.applied(feb, 1_772_294_400_000L, fallbackTz = sh)?.atMs)
        assertEquals("2/28 没存锚定日只能推到 3/28", 1_774_659_600_000L,
            ReminderAdvance.applied(feb.copy(anchorDay = null), 1_772_294_400_000L, fallbackTz = sh)?.atMs)
        // 写回的锚定日按推进前那一版定：改到 15 号、存着旧的 31 → 写回 15。
        assertEquals(15, ReminderAdvance.applied(monthly.copy(atMs = 1_771_117_200_000L, anchorDay = 31), 1_771_516_800_000L, fallbackTz = sh)?.anchorDay)
        // 自定义每 3 小时。
        val hourly = rem(1_791_133_200_000L, rule = "自定义", freq = "小时", n = 3, tz = "")
        assertEquals(1_791_187_200_000L, ReminderAdvance.applied(hourly, now, fallbackTz = sh)?.atMs)
        // 越过结束日：标完成、at 不动。
        val d = ReminderAdvance.applied(clean.copy(repeatEndMs = 1_791_043_199_999L), now, fallbackTz = sh)!!
        assertTrue(d.done); assertEquals(clean.atMs, d.atMs)
        // 不重复 / 还没到点：不动。
        assertNull(ReminderAdvance.applied(clean.copy(repeatRule = "不重复"), now, fallbackTz = sh))
        assertNull(ReminderAdvance.applied(clean.copy(atMs = now + 3_600_000), now, fallbackTz = sh))
    }

    @Test fun upcoming() {
        val now = 1_791_180_000_000L
        val t0 = System.currentTimeMillis()
        val stale = ReminderAdvance.upcoming(1_759_626_000_000L, "custom", "hour", 1, null, now, sh, 12)
        val staleAdv = ReminderAdvance.advance(1_759_626_000_000L, "custom", "hour", 1, null, false, now, sh)
        assertTrue(stale.size == 12 && stale.first() == staleAdv?.atMs && stale.first() == 1_791_183_600_000L)
        assertTrue("一年前起的每小时：排期花了太久", System.currentTimeMillis() - t0 < 2000)

        val daily = ReminderAdvance.upcoming(1_790_312_400_000L, "daily", "day", 1, null, now, sh, 12)
        val adv = ReminderAdvance.advance(1_790_312_400_000L, "daily", "day", 1, null, false, now, sh)
        assertEquals(12, daily.size)
        assertEquals(adv?.atMs, daily.first())
        assertTrue(daily.zipWithNext().all { (a, b) -> a < b } && daily.first() > now)

        assertEquals("结束日 10-08：应只排三次", 3, ReminderAdvance.upcoming(1_790_816_400_000L, "daily", "day", 1, 1_791_475_199_000L, now, sh, 12).size)
        assertTrue(ReminderAdvance.upcoming(now - 1, "none", "day", 1, null, now, sh, 12).isEmpty())
    }

    @Test fun overflow() {
        val now = 1_791_180_000_000L
        val soon = now + 3_600_000
        val freqs = listOf("hour", "day", "week", "month", "year")
        for (f in freqs) {
            assertNull("每 Long.MAX $f", ReminderAdvance.stepOnce(soon, "custom", f, Long.MAX_VALUE, sh))
            assertTrue(ReminderAdvance.upcoming(now - 1, "custom", f, 2_562_047_788_000L, null, now, sh, 3).isEmpty())
            assertEquals(listOf(soon), ReminderAdvance.upcoming(soon, "custom", f, 2_562_047_788_000L, null, now, sh, 3))
            assertNull(ReminderAdvance.advance(now - 1, "custom", f, Long.MAX_VALUE, null, false, now, sh))
        }
        assertEquals(soon + Reminder.customNMax.toLong() * dayMs, ReminderAdvance.stepOnce(soon, "custom", "day", Reminder.customNMax.toLong(), sh))
        assertNull(ReminderAdvance.stepOnce(soon, "custom", "day", Reminder.customNMax + 1L, sh))
        assertEquals("上限只管 custom", soon + dayMs, ReminderAdvance.stepOnce(soon, "daily", "day", Long.MAX_VALUE, sh))

        val bad = listOf("hour" to 2_562_047_788_000L, "day" to 106_751_991_167L, "year" to 292_277_000L, "week" to 15_250_284_453L, "month" to 3_507_324_000L) +
            freqs.map { it to Long.MAX_VALUE }
        for ((f, k) in bad) assertNull("算术层 · 每 $k $f", ReminderAdvance.step(soon, "custom", f, k, sh))
        // 界内一侧逐毫秒与 PC 相同（`--edge` 段，2026-10-17 重跑生成器值不变）。
        val pcEdge = listOf(
            Triple("hour", 2_562_047L, 11_014_552_800_000L), Triple("day", 106_751L, 11_014_470_000_000L),
            Triple("year", 270_000L, 8_522_168_223_600_000L), Triple("year", 292_277L, null),
            Triple("day", 10_000L, 2_655_183_600_000L), Triple("hour", 2_562_047_788_000L, null),
            Triple("month", 3_240_000L, 8_522_168_223_600_000L), Triple("month", 3_507_324L, null),
        )
        for ((f, k, want) in pcEdge) assertEquals("算术层 · 每 $k $f", want, ReminderAdvance.step(soon, "custom", f, k, sh))

        for (at in listOf(Long.MAX_VALUE, Long.MIN_VALUE, ReminderAdvance.jsDateMaxMs + 1)) {
            for (rule in listOf("daily", "weekly", "monthly", "weekday", "custom")) assertNull(ReminderAdvance.stepOnce(at, rule, "hour", 1, sh))
            assertTrue(ReminderAdvance.upcoming(at, "daily", "day", 1, null, now, sh, 3).isEmpty())
        }
        val edge = ReminderAdvance.jsDateMaxMs - 3_600_000
        assertNull(ReminderAdvance.stepOnce(edge, "daily", "day", 1, sh))
        val inside = ReminderAdvance.jsDateMaxMs - 3 * dayMs
        assertEquals(inside + dayMs, ReminderAdvance.stepOnce(inside, "daily", "day", 1, sh))

        assertNull("at = Long.MAX：不该推进", ReminderAdvance.applied(rem(Long.MAX_VALUE, rule = "每天"), now, fallbackTz = sh))
        val hugeN = rem(now - 1, rule = "自定义", freq = "小时", n = Long.MAX_VALUE)
        assertNull(ReminderAdvance.applied(hugeN, now, fallbackTz = sh))
        assertEquals("超上限的 custom_n 上行应原样带回去", Long.MAX_VALUE, hugeN.toDto().custom_n)

        val inputs = listOf("" to 1, "0" to 1, "30" to 30, "1a2" to 12, "10000" to 10_000, "10001" to 10_000,
            "2562047788000" to 10_000, "99999999999999999999999" to 10_000, "１２" to 1)
        for ((s, want) in inputs) assertEquals("输入框「$s」", want, Reminder.customN(s))

        assertEquals(10_080, Reminder.aheadMaxMinutes)
        val aheads = listOf(Triple(0, false, 0), Triple(-5, false, 0), Triple(30, true, 30), Triple(1440, true, 1440),
            Triple(10_080, true, 10_080), Triple(10_081, true, 10_080), Triple(525_600, true, 10_080),
            Triple(Int.MAX_VALUE, true, 10_080), Triple(Int.MIN_VALUE, false, 0))
        for ((m, want, clamped) in aheads) {
            assertEquals("提前 $m 分钟要不要排", want, Reminder.aheadSchedulable(m))
            assertEquals("提前 $m 分钟压成", clamped, Reminder.aheadClamped(m))
        }
    }

    @Test fun holidays() {
        val cals = holidayCals.mapValues { HolidayRules.from(it.value) }
        for (f in pcHolidayFixtures) {
            val cal = f.cal?.let { cals.getValue(it) }
            val got = ReminderAdvance.advance(f.at, f.rule, f.freq, f.n, f.end, false, f.now, ZoneId.of(f.tz), cal)
            assertEquals("${f.name}（${f.tz}）：节假日认法两端不一样", f.want, got)
        }
        // cal = null / 空日历 与原行为完全一致：老夹具逐条再跑。
        val empty = HolidayCalendar(emptySet(), emptySet(), emptySet())
        for (f in pcFixtures) {
            val tz = ReminderAdvance.zone(f.tz, sh)
            val base = ReminderAdvance.advance(f.at, f.rule, f.freq, f.n, f.end, false, f.now, tz, anchorDay = f.anchor)
            for (c in listOf(null, empty)) assertEquals(f.name, base, ReminderAdvance.advance(f.at, f.rule, f.freq, f.n, f.end, false, f.now, tz, c, f.anchor))
        }
        val national = cals.getValue("国庆2026"); val full = cals.getValue("2026整年放假")
        val nowSh = 1_790_726_400_000L
        val base = 1_790_730_000_000L
        assertEquals("排通知：国庆那几天不排、10/10 调休周六排", listOf(0L, 8, 9, 10, 12, 13).map { base + it * dayMs },
            ReminderAdvance.upcoming(base, "weekday", "day", 1, null, nowSh, sh, 6, national))
        assertEquals(listOf(0L, 1, 2).map { base + it * dayMs }, ReminderAdvance.upcoming(base, "weekday", "day", 1, null, nowSh, sh, 3, null))
        val m1 = 1_772_326_800_000L
        assertEquals("整年放假：应停在第 60 天", m1 + ReminderAdvance.workdayScanMax * dayMs, ReminderAdvance.stepOnce(m1, "weekday", "day", 1, sh, full))
        assertEquals(60, ReminderAdvance.workdayScanMax)
        assertEquals("2026-01-05", ReminderAdvance.dateKey(2026, 1, 5))
        assertEquals("2026-10-10", ReminderAdvance.dateKey(2026, 10, 10))
    }

    private fun at9(y: Int, m: Int, d: Int) = ReminderAdvance.fromWall(y.toLong(), m - 1L, d.toLong(), 9, 0, tz = sh)!!

    @Test fun anchors() {
        var t = at9(2026, 1, 31)
        val seen = mutableListOf<Long>()
        repeat(4) { ReminderAdvance.stepOnce(t, "monthly", "day", 1, sh, anchorDay = 31)?.let { t = it; seen.add(it) } }
        assertEquals(listOf(at9(2026, 2, 28), at9(2026, 3, 31), at9(2026, 4, 30), at9(2026, 5, 31)), seen)
        assertEquals(at9(2028, 2, 29), ReminderAdvance.stepOnce(at9(2028, 1, 31), "monthly", "day", 1, sh, anchorDay = 31))
        assertEquals(at9(2026, 2, 28), ReminderAdvance.stepOnce(at9(2026, 1, 31), "monthly", "day", 1, sh))
        assertEquals(at9(2026, 4, 30), ReminderAdvance.stepOnce(at9(2026, 1, 31), "custom", "month", 3, sh, anchorDay = 31))
        val y1 = ReminderAdvance.stepOnce(at9(2028, 2, 29), "custom", "year", 1, sh, anchorDay = 29)
        assertEquals(at9(2029, 2, 28), y1)
        assertEquals(at9(2032, 2, 29), ReminderAdvance.stepOnce(y1!!, "custom", "year", 3, sh, anchorDay = 29))
        assertEquals(at9(2026, 3, 31), ReminderAdvance.advance(at9(2026, 1, 31), "monthly", "day", 1, null, false, at9(2026, 3, 15), sh)?.atMs)
        val ad = listOf(Triple(at9(2026, 2, 28), 31, 31), Triple(at9(2026, 2, 15), 31, 15), Triple(at9(2026, 3, 30), 31, 30),
            Triple(at9(2026, 4, 30), null, 30), Triple(at9(2026, 4, 30), 99, 30), Triple(at9(2026, 4, 30), 0, 30), Triple(at9(2026, 4, 30), -5, 30))
        for ((ms, stored, want) in ad) assertEquals(want, ReminderAdvance.anchorDayOf(ms, stored, sh))
        assertEquals(listOf(28L, 29, 30, 28, 29, 31), listOf(
            ReminderAdvance.daysInMonth(2026, 1), ReminderAdvance.daysInMonth(2028, 1), ReminderAdvance.daysInMonth(2026, 3),
            ReminderAdvance.daysInMonth(1900, 1), ReminderAdvance.daysInMonth(2000, 1), ReminderAdvance.daysInMonth(2026, 12)))
        assertEquals(listOf(at9(2026, 1, 31), at9(2026, 2, 28), at9(2026, 3, 31), at9(2026, 4, 30), at9(2026, 5, 31)),
            ReminderAdvance.upcoming(at9(2026, 1, 31), "monthly", "day", 1, null, at9(2026, 1, 30), sh, 5))
        val up2 = ReminderAdvance.upcoming(at9(2026, 2, 28), "monthly", "day", 1, null, at9(2026, 3, 1), sh, 2, anchorDay = 31)
        assertEquals(listOf(at9(2026, 3, 31), at9(2026, 4, 30)), up2)
        assertEquals(ReminderAdvance.advance(at9(2026, 2, 28), "monthly", "day", 1, null, false, at9(2026, 3, 1), sh, anchorDay = 31)?.atMs, up2.first())
    }

    private fun nyAt(y: Long, m: Long, d: Long, h: Long, mi: Long = 0) = ReminderAdvance.fromWall(y, m - 1, d, h, mi, tz = ny)!!
    private fun utcMs(y: Long, m: Long, d: Long, h: Long = 0, mi: Long = 0) = ReminderAdvance.fromWall(y, m - 1, d, h, mi, tz = utc)!!
    private fun wall(ms: Long, tz: ZoneId) = ReminderAdvance.wall(ms, tz)

    @Test fun timezones() {
        assertTrue(nyAt(2026, 1, 15, 9) == utcMs(2026, 1, 15, 14) && nyAt(2026, 7, 15, 9) == utcMs(2026, 7, 15, 13))
        assertEquals("缺口 02:30 往后挪到 03:30", listOf(2026, 3, 8, 3, 30), wall(nyAt(2026, 3, 8, 2, 30), ny))
        assertEquals("重叠 01:30 取先出现的 EDT", utcMs(2026, 11, 1, 5, 30), nyAt(2026, 11, 1, 1, 30))
        val d1 = ReminderAdvance.stepOnce(nyAt(2026, 3, 7, 9), "daily", "day", 1, ny)!!
        assertEquals(utcMs(2026, 3, 8, 13), d1); assertEquals(listOf(2026, 3, 8, 9, 0), wall(d1, ny))
        assertEquals("按上海推应是纽约 10 点（反面）", listOf(2026, 3, 8, 10, 0), wall(ReminderAdvance.stepOnce(nyAt(2026, 3, 7, 9), "daily", "day", 1, sh)!!, ny))
        assertEquals(listOf(2026, 2, 28, 21, 0), wall(ReminderAdvance.stepOnce(nyAt(2026, 1, 31, 21), "monthly", "day", 1, ny, anchorDay = 31)!!, ny))
        assertEquals(listOf(2026, 3, 16, 21, 0), wall(ReminderAdvance.stepOnce(nyAt(2026, 3, 13, 21), "weekday", "day", 1, ny)!!, ny))
        val r = rem(nyAt(2026, 3, 7, 9), rule = "每天", tz = "America/New_York")
        assertEquals(listOf(2026, 3, 8, 9, 0), wall(ReminderAdvance.applied(r, nyAt(2026, 3, 7, 12), fallbackTz = sh)!!.atMs, ny))
        val shAt = ReminderAdvance.fromWall(2026, 0, 31, 9, 0, tz = sh)!!
        for (bad in listOf("", "Not/AZone")) {
            val a = ReminderAdvance.applied(rem(shAt, rule = "每月", tz = bad), ReminderAdvance.fromWall(2026, 1, 5, 0, 0, tz = sh)!!, fallbackTz = sh)!!
            assertEquals("tz「$bad」应退回本机时区", listOf(2026, 2, 28, 9, 0), wall(a.atMs, sh))
        }
        val h0 = utcMs(2026, 1, 1)
        val hr = ReminderAdvance.advance(h0, "custom", "hour", 1, null, false, h0 + 30 * dayMs + 15 * 60_000, ny)
        assertEquals(Advanced(h0 + (30 * 24 + 1) * 3_600_000L, false), hr)
        assertEquals(h0 + 12 * 3_600_000, ReminderAdvance.advance(h0, "custom", "hour", 3, null, false, h0 + 9 * 3_600_000, sh)?.atMs)
        assertEquals(Advanced(h0, true), ReminderAdvance.advance(h0, "custom", "hour", 3, h0 + 10 * 3_600_000, false, h0 + 11 * 3_600_000, sh))
        val hd = ReminderAdvance.stepOnce(nyAt(2026, 3, 8, 1), "custom", "hour", 2, ny)!!
        assertEquals(nyAt(2026, 3, 8, 1) + 7_200_000, hd); assertEquals(listOf(2026, 3, 8, 4, 0), wall(hd, ny))
        val y2 = ReminderAdvance.fromWall(2024, 0, 1, 9, 0, tz = sh)!!
        val a2 = ReminderAdvance.advance(y2, "daily", "day", 1, null, false, ReminderAdvance.fromWall(2026, 0, 1, 12, 0, tz = sh)!!, sh)!!
        assertEquals(listOf(2026, 1, 2, 9, 0), wall(a2.atMs, sh))
        assertEquals(100_000, ReminderAdvance.maxSteps)
        val nowNy = nyAt(2026, 10, 6, 3, 17)
        for (rule in listOf("daily", "weekday")) {
            val t0 = System.currentTimeMillis()
            val a = ReminderAdvance.advance(utcMs(1970, 1, 1, 14), rule, "day", 1, null, false, nowNy, ny)!!
            assertTrue("1970 起的$rule：快进没生效？", System.currentTimeMillis() - t0 < 2000)
            assertEquals(listOf(2026, 10, 6, 9, 0), wall(a.atMs, ny))
        }
        val e0 = nyAt(2020, 1, 1, 9)
        assertEquals(Advanced(e0, true), ReminderAdvance.advance(e0, "daily", "day", 1, nyAt(2021, 1, 1, 0), false, nyAt(2026, 1, 1, 0), ny))
        assertNull(ReminderAdvance.advance(utcMs(1970, 1, 2), "custom", "day", Reminder.customNMax + 1L, utcMs(1990, 1, 1), false, nowNy, ny))
        val up = ReminderAdvance.upcoming(utcMs(1970, 1, 1, 14), "daily", "day", 1, null, nowNy, ny, 3)
        assertEquals(listOf(listOf(2026, 10, 6, 9, 0), listOf(2026, 10, 7, 9, 0), listOf(2026, 10, 8, 9, 0)), up.map { wall(it, ny) })
        assertEquals(listOf(h0 + 721 * 3_600_000L, h0 + 722 * 3_600_000L), ReminderAdvance.upcoming(h0, "custom", "hour", 1, null, h0 + 30 * dayMs + 15 * 60_000, ny, 2))

        // tz 名字的认法。iOS 那份「UTC → GMT」是 Apple 的别名，java.time 保留 UTC 原名 —— 判定（认得出 / 退回）一致。
        val names = listOf("America/New_York" to "America/New_York", "asia/shanghai" to "Asia/Shanghai", "" to null,
            "Not/AZone" to null, "GMT+8" to null, "Asia/Shanghai " to null, "UTC" to "UTC", "Etc/GMT-8" to "Etc/GMT-8", "US/Eastern" to "US/Eastern")
        for ((id, want) in names) assertEquals("tz「$id」", want ?: kiri.id, ReminderAdvance.zone(id, kiri).id)
        for ((id, secs) in listOf("+08:00" to 28_800, "+0800" to 28_800, "+08" to 28_800, "-05:30" to -19_800, "+14:00" to 50_400)) {
            assertEquals("tz「$id」", secs, ReminderAdvance.zone(id, kiri).rules.getOffset(Instant.EPOCH).totalSeconds)
        }
        for (id in listOf("+8:00", "+24:00", "+08:60", "08:00", "+08:0", "+23:59")) assertEquals("tz「$id」Intl 不认", kiri.id, ReminderAdvance.zone(id, kiri).id)
    }

    @Test fun holidayWait() {
        val national = HolidayRules.from(holidayCals.getValue("国庆2026"))
        assertEquals(setOf(2026), national.years)
        val at = 1_790_730_000_000L
        val now = at + 23 * 3_600_000
        val r = rem(at, rule = "工作日", updated = 1_700_000_000_000L)
        val noTable = ReminderAdvance.applied(r, now, null, sh)!!
        val withTable = ReminderAdvance.applied(r, now, national, sh)!!
        assertEquals(at + dayMs, noTable.atMs)
        assertEquals(at + 8 * dayMs, withTable.atMs)
        assertNull(ReminderAdvance.applied(noTable, now, national, sh))
        assertTrue(ReminderAdvance.waitsForHolidayTable(r, noTable, now, null, sh))
        assertFalse(ReminderAdvance.waitsForHolidayTable(r, withTable, now, national, sh))
        assertTrue(ReminderAdvance.waitsForHolidayTable(r, noTable, now, HolidayCalendar(emptySet(), emptySet(), setOf(2027)), sh))
        val dec31 = 1_798_678_800_000L
        val nowDec = dec31 + 11 * 3_600_000
        val y = rem(dec31, rule = "工作日")
        val v = ReminderAdvance.applied(y, nowDec, national, sh)!!
        assertEquals(dec31 + dayMs, v.atMs)
        assertTrue(ReminderAdvance.waitsForHolidayTable(y, v, nowDec, national, sh))
        assertFalse(ReminderAdvance.waitsForHolidayTable(y, v, nowDec, national.copy(years = setOf(2026, 2027)), sh))
        val daily = rem(at, rule = "每天")
        assertFalse(ReminderAdvance.waitsForHolidayTable(daily, ReminderAdvance.applied(daily, now, null, sh)!!, now, null, sh))
    }

    @Test fun stampOverflow() {
        for (stamp in listOf(Long.MAX_VALUE, Long.MAX_VALUE - 1)) {
            val v = ReminderAdvance.applied(rem(1_790_816_400_000L, rule = "每天", updated = stamp), 1_791_180_000_000L, fallbackTz = sh)!!
            assertEquals(Long.MAX_VALUE, v.updatedAtMs)
            assertTrue(v.atMs == 1_791_248_400_000L && v.dirty)
        }
    }

    @Test fun endCap() {
        fun w(tz: ZoneId, y: Long, m: Long, d: Long, h: Long, mi: Long = 0, s: Long = 0, ms: Long = 0) = ReminderAdvance.fromWall(y, m - 1, d, h, mi, s, ms, tz)!!
        fun picked(device: ZoneId, y: Long, m: Long, d: Long) = ReminderAdvance.endOfDay(w(device, y, m, d, 23, 59), device)
        fun reminder(tz: ZoneId, at: Long, rule: String = "每天", freq: String = "天", end: Long?) = rem(at, rule = rule, freq = freq, end = end, tz = tz.id, id = "e")
        fun lastByAdvance(r: Reminder): Long? {
            if (ReminderAdvance.pastEnd(r.atMs, ReminderAdvance.endCapMs(r))) return null
            var cur = r
            repeat(5000) {
                val v = ReminderAdvance.applied(cur, cur.atMs) ?: return null
                if (v.done) return cur.atMs
                cur = v
            }
            return null
        }
        fun scheduled(r: Reminder) = ReminderAdvance.upcoming(r.atMs, ReminderWire.ruleToWire(r.repeatRule), ReminderWire.freqToWire(r.customFreq), r.customN,
            ReminderAdvance.endCapMs(r), r.atMs - 1, ReminderAdvance.zone(r.tz), 5000, anchorDay = r.anchorDay)
        fun same(r: Reminder, last: Long, what: String) {
            assertEquals("$what：推进的最后一次", last, lastByAdvance(r))
            assertEquals("$what：排通知的最后一次", last, scheduled(r).lastOrNull())
        }
        val shEnd = picked(sh, 2026, 10, 10)
        assertEquals(w(sh, 2026, 10, 10, 23, 59, 59, 999), shEnd)
        assertEquals(shEnd, ReminderAdvance.endOfDay(shEnd, sh))
        assertEquals(shEnd, ReminderAdvance.endOfDay(w(sh, 2026, 10, 10, 0, 0), sh))
        val r0 = reminder(ny, w(ny, 2026, 10, 5, 20), end = shEnd)
        assertEquals(shEnd, ReminderAdvance.endCapMs(r0))
        assertNull(ReminderAdvance.endCapMs(reminder(ny, 0, end = null)))
        assertTrue(!ReminderAdvance.pastEnd(shEnd, shEnd) && ReminderAdvance.pastEnd(shEnd + 1, shEnd) && !ReminderAdvance.pastEnd(Long.MAX_VALUE, null))
        same(r0, w(ny, 2026, 10, 9, 20), "上海手机 / 纽约提醒 / 每天 20:00")
        assertFalse(scheduled(r0).contains(w(ny, 2026, 10, 10, 20)))
        assertTrue(ReminderAdvance.applied(reminder(ny, w(ny, 2026, 10, 9, 20), end = shEnd), w(ny, 2026, 10, 10, 20, 1))!!.done)
        val nyEnd = picked(ny, 2026, 10, 10)
        assertEquals(w(sh, 2026, 10, 11, 11, 59, 59, 999), nyEnd)
        same(reminder(sh, w(sh, 2026, 10, 5, 20), end = nyEnd), w(sh, 2026, 10, 10, 20), "纽约手机 / 上海提醒 / 每天 20:00")
        same(reminder(sh, w(sh, 2026, 10, 5, 9), end = nyEnd), w(sh, 2026, 10, 11, 9), "纽约手机 / 上海提醒 / 每天 09:00")
        val fall = picked(ny, 2026, 11, 1)
        assertEquals(w(utc, 2026, 11, 2, 4, 59, 59, 999), fall)
        same(reminder(ny, w(ny, 2026, 10, 25, 20), end = fall), w(ny, 2026, 11, 1, 20), "纽约拨慢那天结束 / 每天 20:00")
        same(reminder(ny, w(ny, 2026, 10, 31, 22), rule = "自定义", freq = "小时", end = fall), w(ny, 2026, 11, 1, 23), "纽约拨慢那天结束 / 每小时")
        same(reminder(ny, w(ny, 2026, 3, 1, 20), end = picked(sh, 2026, 3, 8)), w(ny, 2026, 3, 7, 20), "纽约拨快那天、上海手机结束")
        for (old in listOf(w(sh, 2026, 10, 10, 23, 59, 59), w(sh, 2026, 10, 10, 23, 59))) {
            val r = reminder(ny, w(ny, 2026, 10, 5, 20), end = old)
            assertEquals(old, ReminderAdvance.endCapMs(r))
            same(r, w(ny, 2026, 10, 9, 20), "老数据 $old")
        }
    }

    @Test fun crossYearTable() {
        val y2019 = listOf(CnHolidayDay("2018-12-29", false), CnHolidayDay("2018-12-30", true), CnHolidayDay("2018-12-31", true), CnHolidayDay("2019-01-01", true))
        val only = HolidayRules.from(mapOf("2019" to y2019))
        assertTrue(only.off.contains("2018-12-31") && only.work.contains("2018-12-29"))
        assertEquals(setOf(2019), only.years)
        val both = HolidayRules.from(mapOf("2018" to emptyList(), "2019" to y2019))
        val fri = 1_545_958_800_000L
        assertEquals(1_546_218_000_000L, ReminderAdvance.advance(fri, "weekday", "day", 1, null, false, fri + 3_600_000, sh, only)?.atMs)
        assertEquals(1_546_045_200_000L, ReminderAdvance.advance(fri, "weekday", "day", 1, null, false, fri + 3_600_000, sh, both)?.atMs)
        val sat = 1_546_045_200_000L
        assertEquals(1_546_390_800_000L, ReminderAdvance.advance(sat, "weekday", "day", 1, null, false, sat + 3_600_000, sh, both)?.atMs)
    }

    @Test fun weekDays() {
        for (f in pcWeekFixtures) {
            val tz = ReminderAdvance.zone(f.tz)
            val got = ReminderAdvance.advance(f.at, "weekly", "day", 1, f.end, false, f.now, tz, weekDays = f.days)
            assertEquals("每周几 · ${f.name}", f.want, got)
            if (f.want != null && !f.want.done) {
                assertEquals(f.want.atMs, ReminderAdvance.upcoming(f.at, "weekly", "day", 1, f.end, f.now, tz, 1, weekDays = f.days).firstOrNull())
            }
        }
        for (f in pcSnapFixtures) assertEquals("校正 at · ${f.name}", f.want, ReminderAdvance.snapToWeekDays(f.at, f.days, ReminderAdvance.zone(f.tz)))

        fun w(y: Long, m: Long, d: Long, h: Long) = ReminderAdvance.fromWall(y, m - 1, d, h, 0, tz = sh)!!
        fun r(at: Long, days: List<Int>?, rule: String = "每周") = rem(at, rule = rule, updated = 100, days = days)
        val mon = w(2026, 10, 5, 9); val thu = w(2026, 10, 8, 9)
        assertTrue(ReminderAdvance.weekDayMon0(mon, sh) == 0 && ReminderAdvance.weekDayMon0(w(2026, 10, 11, 9), sh) == 6)
        val a = ReminderAdvance.applied(r(mon, listOf(0, 2, 4)), mon + 3_600_000)!!
        assertEquals(w(2026, 10, 7, 9), a.atMs); assertEquals(listOf(0, 2, 4), a.weekDays)
        assertEquals(listOf(w(2026, 10, 7, 9), w(2026, 10, 9, 9), w(2026, 10, 12, 9), w(2026, 10, 14, 9)),
            ReminderAdvance.upcoming(mon, "weekly", "day", 1, null, mon + 1, sh, 4, weekDays = listOf(0, 2, 4)))
        assertEquals(w(2026, 10, 6, 9), ReminderAdvance.stepOnce(mon, "daily", "day", 1, sh, weekDays = listOf(3)))
        val n1 = ReminderAdvance.normalizedWeekDays(r(thu, listOf(4, 0, 2)))
        assertTrue(n1.atMs == w(2026, 10, 9, 9) && n1.weekDays == listOf(0, 2, 4))
        val n2 = ReminderAdvance.normalizedWeekDays(r(thu, listOf(0)))
        assertTrue(n2.atMs == w(2026, 10, 12, 9) && n2.weekDays == emptyList<Int>())
        val n3 = ReminderAdvance.normalizedWeekDays(r(thu, listOf(0, 2), rule = "每天"))
        assertTrue(n3.atMs == thu && n3.weekDays == emptyList<Int>())
        val n4 = ReminderAdvance.normalizedWeekDays(r(thu, null))
        assertTrue(n4.atMs == thu && n4.weekDays == null)
        assertTrue(ReminderAdvance.shownWeekDays(r(mon, null)) == listOf(0) && ReminderAdvance.shownWeekDays(r(mon, listOf(2, 4))) == listOf(2, 4))
        assertTrue(ReminderAdvance.toggleWeekDay(listOf(0), 2) == listOf(0, 2) && ReminderAdvance.toggleWeekDay(listOf(0, 2), 0) == listOf(2) && ReminderAdvance.toggleWeekDay(listOf(2), 2) == listOf(2))

        fun label(rule: String, days: List<Int>?, freq: String = "天", n: Long = 1, en: Boolean = false) = ReminderAdvance.repeatText(rule, freq, n, days, mon, sh, en)
        val labels = listOf(
            label("每周", emptyList()) to "每周", label("每周", listOf(0)) to "每周", label("每周", null) to "每周",
            label("每周", listOf(0), en = true) to "Weekly",
            label("每周", listOf(0, 2, 4)) to "每周一、三、五", label("每周", listOf(6)) to "每周日",
            label("每周", listOf(0, 2, 4), en = true) to "Weekly on Mon, Wed, Fri",
            label("每周", listOf(0, 1, 2, 3, 4, 5, 6)) to "每周一至日",
            label("每周", listOf(0, 1, 2, 3, 4, 5, 6), en = true) to "Weekly on Mon–Sun",
            label("每天", listOf(2)) to "每天", label("每天", null, en = true) to "Daily",
            label("工作日", null, en = true) to "Weekdays",
            label("自定义", null, "天", 1, true) to "Every day", label("自定义", null, "天", 3, true) to "Every 3 days",
            label("自定义", null, "小时", 1, true) to "Every hour", label("自定义", null, "小时", 3, true) to "Every 3 hours",
            label("自定义", null, "年", 1, true) to "Every year",
            label("自定义", null, "天", 1) to "每天", label("自定义", null, "天", 3) to "每 3 天",
            label("自定义", null, "小时", 1) to "每小时", label("自定义", null, "小时", 3) to "每 3 小时",
            label("自定义", null, "月", 1) to "每月", label("不重复", null) to "",
        )
        for ((got, want) in labels) assertEquals("重复文案", want, got)
    }
}
