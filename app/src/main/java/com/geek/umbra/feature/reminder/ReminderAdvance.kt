// 重复提醒「到点之后往下推」—— 逐行照抄 iOS Features/Reminder/ReminderAdvance.swift（它又逐条照抄 PC
// `electron/core/notify/scanner.ts` 的 stepOnce / advance）。
//
// ⚠️ **为什么是逐条照抄而不是 java.time 的 plusMonths**：三端都会推进同一条、各自写回，服务端逐条 last-write-wins。
// **同一个 at 推出来的结果三端必须一模一样**。java.time 的日历加法和 JS Date 有同样的三处不同（iOS 文件头列过）：
//   1. 月末：锚定原始那一天、短月取月末、够长的月份回到它（1/31 → 2/28 → 3/31）；plusMonths 不记锚定日；
//   2. 夏令时缺口：不存在的 2:30 解成 3:30（缺口之前的偏移），重复的 1:30 取较早那个 —— 自己按同一规则解（utcFromLocal）；
//   3. 「工作日」写死周六周日，再认法定节假日与调休（umbra#93）。
// 「每 N 小时」按绝对时长算；天 / 周 / 月 / 年 / 工作日都在**提醒自己的时区**取墙上时间算，最后一次换回 UTC。
// 全程整数毫秒，溢出 / 越过 JS Date 范围（±8.64e15）一律回 null，不抛（iOS umbra-ios#68：溢出 trap = 冷启动必崩）。
//
// 用例（ReminderAdvanceTest）的期望值是 iOS 夹具里那份 —— 由 node 直接执行 PC 的 scanner.ts 取得。
// 纯 Kotlin：只用 java.time 取时区偏移（tzdb，与 Apple / Intl 同一份数据）。
package com.geek.umbra.feature.reminder

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** 推进用的节假日日历 —— PC `HolidayCalendar { off, work, years }`。见 HolidayCalendar.kt。 */
data class HolidayCalendar(val off: Set<String>, val work: Set<String>, val years: Set<Int>)

/** 推进的结果：at 推到哪儿 / 是否越过结束（越过时 at 保持原值）。 */
data class Advanced(val atMs: Long, val done: Boolean)

object ReminderAdvance {
    /** 一次推进最多逐步走几步。与 PC `ADVANCE_MAX_STEPS` 同一个数，不许单改。iOS: ReminderAdvance.swift:44 */
    const val maxSteps = 100_000

    /** 半天：utcFromLocal 取候选偏移的前后距离，与 PC `fromWall` 的 ±12 小时同一个数。 */
    private const val halfDayMs = 43_200_000L
    private const val dayMs = 86_400_000L

    /** 「工作日」一步最多往后找几天；找满停在第 60 天（不是 null），与 PC 同。iOS: ReminderAdvance.swift:56 */
    const val workdayScanMax = 60

    // MARK: 结束重复的截点（umbra-ios#81）

    /** 结束重复的截点：就是存着的原值，不在任何时区里重取「那天 23:59:59」。推进与排通知唯一的取法。 */
    fun endCapMs(r: Reminder): Long? = r.repeatEndMs

    /** 这一次越过结束了吗：等于截点不算越过（PC `at > repeatEndMs`）。 */
    fun pastEnd(atMs: Long, endCapMs: Long?): Boolean = endCapMs != null && atMs > endCapMs

    /**
     * 写入结束日：**本机日历**上那一天的 23:59:59.999 —— 与 PC `reminderKit.endOfDay` 同一个写法。
     * 毫秒按整数加；越界保持原值。iOS: ReminderAdvance.swift:94
     */
    fun endOfDay(dayMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val s = runCatching {
            Instant.ofEpochMilli(dayMs).atZone(zone).toLocalDate().atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull() ?: return dayMs
        return add(s, 999) ?: s
    }

    // MARK: 时区

    /**
     * 一条提醒推进 / 排通知用哪个时区 —— PC `tzWall.resolveTz` 的对照：认得出就用它，空 / 认不出用 [fallback]（本机）。
     * 认法与 iOS 对齐（它又与 node Intl 实测对齐）：
     *  · 名字不分大小写（`asia/shanghai` 认作 Asia/Shanghai）；
     *  · `GMT+8` 这种不收（Intl 抛）；
     *  · `+08:00` / `+0800` / `+08` 按固定偏移收；其它以 +/- 开头的一律不收（java.time 会收 `+8:00`，Intl 不收）；
     *  · 只认 tzdb 里真有的地区名（java.time 还会收 `UTC+8` 这种前缀写法，Intl 不收）。
     * iOS: ReminderAdvance.swift:112
     */
    fun zone(id: String?, fallback: ZoneId = ZoneId.systemDefault()): ZoneId {
        if (id.isNullOrEmpty()) return fallback
        if (id.startsWith("+") || id.startsWith("-")) {
            val off = fixedOffsetSeconds(id) ?: return fallback
            return runCatching { ZoneOffset.ofTotalSeconds(off) as ZoneId }.getOrDefault(fallback)
        }
        val up = id.uppercase()
        if (up.startsWith("GMT+") || up.startsWith("GMT-")) return fallback
        val known = ZoneId.getAvailableZoneIds()
        if (id in known) return runCatching { ZoneId.of(id) }.getOrDefault(fallback)
        val k = known.firstOrNull { it.equals(id, ignoreCase = true) } ?: return fallback
        return runCatching { ZoneId.of(k) }.getOrDefault(fallback)
    }

    /** `±hh`、`±hhmm`、`±hh:mm`（hh ≤ 23、mm ≤ 59）→ 秒；别的形状 null。 */
    private fun fixedOffsetSeconds(s: String): Int? {
        val c = s.toList()
        val sign = c.firstOrNull() ?: return null
        if (sign != '+' && sign != '-') return null
        val body = c.drop(1).filter { it != ':' }
        val colons = c.count { it == ':' }
        if (!body.all { it in '0'..'9' } || !(body.size == 2 || body.size == 4)) return null
        if (!(colons == 0 || (colons == 1 && c.size == 6 && c[3] == ':'))) return null
        val hh = String(body.take(2).toCharArray()).toInt()
        val mm = if (body.size == 4) String(body.takeLast(2).toCharArray()).toInt() else 0
        if (hh > 23 || mm > 59) return null
        return (if (sign == '-') -1 else 1) * (hh * 3600 + mm * 60)
    }

    // MARK: 推进

    /**
     * 按规则往后走一步。不重复（或认不出的规则）返回 null。**规则名是线上英文枚举**（daily / custom…）。
     * 「自定义 · 每 N」的 N 超过上限也返回 null（iOS 比 PC 多的一道，#68）。iOS: ReminderAdvance.swift:155
     */
    fun stepOnce(
        atMs: Long, rule: String, freq: String, n: Long, tz: ZoneId,
        cal: HolidayCalendar? = null, anchorDay: Int? = null, weekDays: List<Int>? = null,
    ): Long? {
        if (rule == "custom" && n > Reminder.customNMax) return null
        return step(atMs, rule, freq, maxOf(1L, n), tz, cal, anchorDay, weekDays)
    }

    /** stepOnce 去掉 N 上限之后的算术本体（用例要越过上限直接打到算术上）。全程溢出安全。iOS: ReminderAdvance.swift:174 */
    fun step(
        atMs: Long, rule: String, freq: String, k: Long, tz: ZoneId,
        cal: HolidayCalendar? = null, anchorDay: Int? = null, weekDays: List<Int>? = null,
    ): Long? {
        if (!inJSRange(atMs)) return null
        val l = local(atMs, tz)
        // 墙上日期加 days 天、时分秒毫秒不动，一次换回（PC `plusDays`）。
        fun plusDays(days: Long): Long? = add(l.d, days)?.let { utc(makeDay(l.y, l.m0, it), l.tod, tz) }
        fun anchor(): Long = anchorDayOf(atMs, anchorDay, tz).toLong()
        return when (rule) {
            "daily" -> plusDays(1)
            "weekly" -> {
                val days = normWeekDays(weekDays)
                if (days.isEmpty()) return plusDays(7)
                for (i in 1L..7L) {
                    val dn = add(l.d, i)?.let { makeDay(l.y, l.m0, it) } ?: return null
                    if (days.contains(mod(dn + 3, 7).toInt())) return plusDays(i) // 天号 0 是周四 → 0=周一口径 +3
                }
                plusDays(7)
            }
            "monthly" -> addMonthsAnchored(l, 1, anchor(), tz)
            "weekday" -> {
                for (i in 1L..workdayScanMax.toLong()) {
                    val dn = add(l.d, i)?.let { makeDay(l.y, l.m0, it) } ?: return null
                    val c = civil(dn)
                    if (isWorkday(c.y, c.m - 1, c.d, mod(dn + 4, 7), cal) || i == workdayScanMax.toLong()) return plusDays(i)
                }
                plusDays(workdayScanMax.toLong())
            }
            "custom" -> when (freq) {
                "hour" -> {
                    val shift = mul(k, 3_600_000) ?: return null
                    val t = add(atMs, shift) ?: return null
                    if (inJSRange(t)) t else null
                }
                "week" -> mul(7, k)?.let { plusDays(it) }
                "month" -> addMonthsAnchored(l, k, anchor(), tz)
                // 每 k 年 = 推 12k 个月（PC 同）：2/29 → 平年 2/28 → 闰年回到 2/29。
                "year" -> mul(12, k)?.let { addMonthsAnchored(l, it, anchor(), tz) }
                else -> plusDays(k) // day 与认不出的单位，同 PC
            }
            else -> null
        }
    }

    /**
     * 把一条已经过点的重复提醒推进到下一个**未来**时刻（PC `advance`，f3f7331）。null = 不用动。
     * 越过结束 → done=true、at 保持原值。三段顺序与 PC 一致：① 每 N 小时直接算 ② 按天 / 按周整周期快进 ③ 逐步走。
     * iOS: ReminderAdvance.swift:244
     */
    fun advance(
        atMs: Long, rule: String, freq: String, n: Long, repeatEndMs: Long?, done: Boolean, nowMs: Long, tz: ZoneId,
        cal: HolidayCalendar? = null, anchorDay: Int? = null, weekDays: List<Int>? = null,
    ): Advanced? {
        if (done || rule == "none") return null
        if (atMs > nowMs) return null
        if (rule == "custom" && n > Reminder.customNMax) return null
        if (!inJSRange(atMs)) return null

        if (rule == "custom" && freq == "hour") {
            val stepMs = mul(maxOf(1L, n), 3_600_000) ?: return null
            val diff = sub(nowMs, atMs) ?: return null
            val jumps = add(diff / stepMs, 1) ?: return null // diff ≥ 0，整除即向下取整
            val span = mul(jumps, stepMs) ?: return null
            val next = add(atMs, span) ?: return null
            if (!inJSRange(next)) return null
            if (pastEnd(next, repeatEndMs)) return Advanced(atMs, true)
            return Advanced(next, false)
        }

        var at = atMs
        val start = fastForward(atMs, rule, freq, n, nowMs, tz)
        if (start != null) {
            if (pastEnd(start, repeatEndMs)) return Advanced(atMs, true) // 跳过的区间里越过了结束时刻
            if (start > at && start <= nowMs) at = start
        }
        val anchor = anchorDayOf(atMs, anchorDay, tz)
        repeat(maxSteps) {
            val next = stepOnce(at, rule, freq, n, tz, cal, anchor, weekDays)
            if (next == null || next <= at) return null // 规则不推进，别陷进去
            at = next
            if (pastEnd(at, repeatEndMs)) return Advanced(atMs, true)
            if (at > nowMs) return Advanced(at, false)
        }
        return null
    }

    /** 按天 / 按周的规则整周期快进到哪儿（PC f3f7331）；不快进回 null。iOS: ReminderAdvance.swift:298 */
    private fun fastForward(atMs: Long, rule: String, freq: String, n: Long, nowMs: Long, tz: ZoneId): Long? {
        val k = maxOf(1L, n)
        val period: Long? = when {
            rule == "daily" -> 1
            rule == "weekly" || rule == "weekday" -> 7
            rule == "custom" && freq == "day" -> k
            rule == "custom" && freq == "week" -> mul(7, k)
            else -> null
        }
        if (period == null || period <= 0 || !inJSRange(nowMs)) return null
        val w = local(atMs, tz)
        val nw = local(nowMs, tz)
        val diffDays = sub(nw.day, w.day) ?: return null
        val jump = floorDiv(diffDays, period) - 1
        if (jump <= 0) return null
        val days = mul(jump, period) ?: return null
        val d = add(w.d, days) ?: return null
        return utc(makeDay(w.y, w.m0, d), w.tod, tz)
    }

    /**
     * 本机这一行推进之后的样子；不用推进就是 null。时区取这条提醒自己的 tz（空 / 认不出退回 [fallbackTz]）。
     * ⚠️ updatedAtMs 取「原值 + 1」，不取「现在」：推进不是用户的改动，别的端之后的真改动永远要赢。
     * pendingSinceMs 只在原来不脏时写成 now（横幅从现在起算）。iOS: ReminderAdvance.swift:335
     */
    fun applied(r: Reminder, nowMs: Long, cal: HolidayCalendar? = null, fallbackTz: ZoneId = ZoneId.systemDefault()): Reminder? {
        if (!ReminderPlace.timed(r)) return null // 地点提醒 at_ms = 0，没有「下一次」可推
        val tz = zone(r.tz, fallbackTz)
        val a = advance(
            r.atMs, ReminderWire.ruleToWire(r.repeatRule), ReminderWire.freqToWire(r.customFreq), r.customN,
            endCapMs(r), r.done, nowMs, tz, cal, r.anchorDay, r.weekDays,
        ) ?: return null
        val bumped = add(r.updatedAtMs, 1) ?: r.updatedAtMs // 溢出时保持原值（= 饱和在 Long.MAX）
        return r.copy(
            done = if (a.done) true else r.done,
            atMs = if (a.done) r.atMs else a.atMs,
            anchorDay = anchorDayOf(r.atMs, r.anchorDay, tz),
            updatedAtMs = bumped,
            pendingSinceMs = if (!r.dirty) nowMs else r.pendingSinceMs,
            dirty = true,
        )
    }

    /**
     * 冷启动那次推进该不该**先放着这一行、等同步拉完节假日表再推**（umbra-ios#72）：「工作日」规则且日历缺 now 那一年
     * 或推进落点那一年（按提醒时区取公历年）→ 先放着。iOS: ReminderAdvance.swift:377
     */
    fun waitsForHolidayTable(r: Reminder, advanced: Reminder, nowMs: Long, cal: HolidayCalendar?, fallbackTz: ZoneId = ZoneId.systemDefault()): Boolean {
        if (ReminderWire.ruleToWire(r.repeatRule) != "weekday") return false
        if (cal == null) return true
        val tz = zone(r.tz, fallbackTz)
        for (ms in listOf(nowMs, advanced.atMs)) {
            if (!inJSRange(ms)) return true
            val y = local(ms, tz).y
            if (y > Int.MAX_VALUE || y < Int.MIN_VALUE || !cal.years.contains(y.toInt())) return true
        }
        return false
    }

    /**
     * 接下来的触发时刻（排通知用），从第一个**未来**的时刻起，最多 [limit] 个，越过 [endCapMs] 就停。
     * 「先走到未来」直接借 advance（不带结束时刻）—— 排出来的第一次就是推进会推到的 at。iOS: ReminderAdvance.swift:402
     */
    fun upcoming(
        atMs: Long, rule: String, freq: String, n: Long, endCapMs: Long?, nowMs: Long, tz: ZoneId, limit: Int,
        cal: HolidayCalendar? = null, anchorDay: Int? = null, weekDays: List<Int>? = null,
    ): List<Long> {
        if (!inJSRange(atMs)) return emptyList()
        val anchor = anchorDayOf(atMs, anchorDay, tz)
        var d = atMs
        if (d <= nowMs) {
            val a = advance(atMs, rule, freq, n, null, false, nowMs, tz, cal, anchor, weekDays) ?: return emptyList()
            d = a.atMs
        }
        val out = mutableListOf<Long>()
        var count = 0
        while (out.size < limit && count < maxSteps) {
            count += 1
            if (pastEnd(d, endCapMs)) break
            out.add(d)
            val next = stepOnce(d, rule, freq, n, tz, cal, anchor, weekDays)
            if (next == null || next <= d) break
            d = next
        }
        return out
    }

    /** 提醒时区里的墙上时间 → 绝对毫秒（PC `fromWall`）。月从 0 起、各字段可越界，越界 / 溢出回 null。 */
    fun fromWall(y: Long, m0: Long, d: Long, h: Long, mi: Long, s: Long = 0, ms: Long = 0, tz: ZoneId): Long? {
        val tod = mul(h, 3_600_000)?.let { a -> mul(mi, 60_000)?.let { add(a, it) } }
            ?.let { a -> mul(s, 1000)?.let { add(a, it) } }?.let { add(it, ms) } ?: return null
        return utc(makeDay(y, m0, d), tod, tz)
    }

    // MARK: 每周多选星期几

    fun normWeekDays(v: List<Int>?): List<Int> = Reminder.normWeekDays(v)

    /** 某时刻在 tz 里是星期几，**0=周一**。越界回 0。 */
    fun weekDayMon0(atMs: Long, tz: ZoneId): Int {
        if (!inJSRange(atMs)) return 0
        return mod(local(atMs, tz).day + 3, 7).toInt()
    }

    /** 把 at 挪到不早于它的第一个选中日（tz 墙上时间、时分秒不变）—— PC `snapToWeekDays`。 */
    fun snapToWeekDays(atMs: Long, weekDays: List<Int>?, tz: ZoneId): Long {
        val days = normWeekDays(weekDays)
        if (days.isEmpty() || !inJSRange(atMs)) return atMs
        val l = local(atMs, tz)
        for (i in 0L until 7L) {
            val dn = add(l.d, i)?.let { makeDay(l.y, l.m0, it) } ?: return atMs
            if (days.contains(mod(dn + 3, 7).toInt())) {
                return if (i == 0L) atMs else (utc(dn, l.tod, tz) ?: atMs)
            }
        }
        return atMs
    }

    /**
     * 编辑页保存时把「每周几」规整（PC `normalizeReminder`）：at 校正到第一个选中日，「只剩 at 那一天」收成 []，
     * 非每周收成 []；nil 保持 nil（上行不写键，不抹掉别端的选择）。iOS: ReminderAdvance.swift:470
     */
    fun normalizedWeekDays(r: Reminder): Reminder {
        if (r.weekDays == null) return r
        val weekly = ReminderWire.ruleToWire(r.repeatRule) == "weekly"
        val picked = if (weekly) normWeekDays(r.weekDays) else emptyList()
        val tz = zone(r.tz)
        val at = if (weekly) snapToWeekDays(r.atMs, picked, tz) else r.atMs
        val wd = if (picked.size == 1 && picked[0] == weekDayMon0(at, tz)) emptyList() else picked
        return r.copy(atMs = at, weekDays = wd)
    }

    /** 编辑页周几芯片当前亮哪几颗：存了就是存的；没存亮 at 那一天。PC `shownWeekDays`。 */
    fun shownWeekDays(r: Reminder): List<Int> {
        val days = normWeekDays(r.weekDays)
        return days.ifEmpty { listOf(weekDayMon0(r.atMs, zone(r.tz))) }
    }

    /** 点一颗周几芯片后的新集合：亮的灭、灭的亮；至少留一颗。PC `toggleWeekDay`。 */
    fun toggleWeekDay(shown: List<Int>, d: Int): List<Int> {
        if (shown.contains(d)) return if (shown.size <= 1) shown else normWeekDays(shown.filter { it != d })
        return normWeekDays(shown + d)
    }

    /**
     * 列表 / 详情 / 编辑页上的重复文案 —— 与 PC `reminderKit.repeatLabel` 逐字同源（中英两份）。
     * rule / freq 是界面中文值。iOS: ReminderAdvance.swift:505
     */
    fun repeatText(rule: String, freq: String, n: Long, weekDays: List<Int>?, atMs: Long, tz: ZoneId, english: Boolean): String {
        when (rule) {
            "不重复" -> return ""
            "每周" -> {
                val days = normWeekDays(weekDays)
                if (days.isEmpty() || (days.size == 1 && days[0] == weekDayMon0(atMs, tz))) return if (english) "Weekly" else "每周"
                if (days.size == 7) return if (english) "Weekly on Mon–Sun" else "每周一至日"
                return if (english) "Weekly on " + days.joinToString(", ") { Reminder.weekDayNamesEn[it] }
                else "每周" + days.joinToString("、") { Reminder.weekDayNamesZh[it] }
            }
            "自定义" -> {
                val k = maxOf(1L, n)
                if (english) {
                    val names = mapOf("小时" to ("hour" to "hours"), "天" to ("day" to "days"), "周" to ("week" to "weeks"),
                        "月" to ("month" to "months"), "年" to ("year" to "years"))
                    val (one, many) = names[freq] ?: ("day" to "days")
                    return if (k == 1L) "Every $one" else "Every $k $many"
                }
                return if (k == 1L) "每$freq" else "每 $k $freq"
            }
            else -> {
                if (!english) return rule
                return mapOf("每天" to "Daily", "每月" to "Monthly", "工作日" to "Weekdays")[rule] ?: rule
            }
        }
    }

    // MARK: 月末锚定

    /** 这条提醒按哪一天锚定 —— PC `anchorDayOf`：存了（1–31）且和 at 对得上就用它，否则取 at 的日。 */
    fun anchorDayOf(atMs: Long, anchorDay: Int?, tz: ZoneId): Int {
        val stored = anchorDay?.takeIf { it in 1..31 }
        if (!inJSRange(atMs)) return stored ?: 1
        val l = local(atMs, tz)
        if (stored != null && minOf(stored.toLong(), daysInMonth(l.y, l.m0)) == l.d) return stored
        return l.d.toInt()
    }

    /** 某年某月（0 起，可越界、进位到年）有几天。纯日历、与时区无关。 */
    fun daysInMonth(y: Long, m0: Long): Long {
        val yy = y + floorDiv(m0, 12)
        return when (mod(m0, 12) + 1) {
            2L -> if (mod(yy, 4) == 0L && (mod(yy, 100) != 0L || mod(yy, 400) == 0L)) 29 else 28
            4L, 6L, 9L, 11L -> 30
            else -> 31
        }
    }

    private fun addMonthsAnchored(l: Local, months: Long, anchor: Long, tz: ZoneId): Long? {
        val m = add(l.m0, months) ?: return null
        return utc(makeDay(l.y, m, minOf(anchor, daysInMonth(l.y, m))), l.tod, tz)
    }

    // MARK: 工作日判定（umbra#93）

    /** off 有这天 → 不是；work 有 → 是；其余（含没表、这年没表）→ 周一到周五。weekday 0 = 周日。 */
    private fun isWorkday(y: Long, m0: Long, d: Long, weekday: Long, cal: HolidayCalendar?): Boolean {
        if (cal != null && y in Int.MIN_VALUE..Int.MAX_VALUE && cal.years.contains(y.toInt())) {
            val key = dateKey(y, m0 + 1, d)
            if (cal.off.contains(key)) return false
            if (cal.work.contains(key)) return true
        }
        return weekday != 0L && weekday != 6L
    }

    /** `YYYY-MM-DD`，与 PC `localDateKey` 同形（月日补零两位，不跟 locale 走）。 */
    fun dateKey(y: Long, m: Long, d: Long): String {
        fun p2(v: Long) = if (v < 10) "0$v" else "$v"
        return "$y-${p2(m)}-${p2(d)}"
    }

    // MARK: JS Date 的本地时间算术（ECMA-262 §21.4.1）

    private class Local(val day: Long, val y: Long, val m0: Long, val d: Long, val tod: Long, val weekday: Long)

    private fun local(ms: Long, tz: ZoneId): Local {
        val lm = ms + offsetMs(ms, tz)
        val day = floorDiv(lm, dayMs)
        val tod = lm - day * dayMs
        val c = civil(day)
        return Local(day, c.y, c.m - 1, c.d, tod, mod(day + 4, 7)) // 1970-01-01 是周四
    }

    /** JS `MakeDay`：月份越界进位到年、日子越界顺延。年份超出 ±1e6 → null（同 V8）。 */
    private fun makeDay(y: Long, m0: Long, d: Long): Long? {
        val ym = add(y, floorDiv(m0, 12)) ?: return null
        if (ym < -yearLimit || ym > yearLimit) return null
        val mn = mod(m0, 12)
        return add(daysFromCivil(ym, mn + 1, 1), d)?.let { add(it, -1) }
    }

    private fun utc(day: Long?, tod: Long, tz: ZoneId): Long? {
        if (day == null) return null
        val x = mul(day, dayMs) ?: return null
        val lm = add(x, tod) ?: return null
        return utcFromLocal(lm, tz)
    }

    /**
     * 墙上时间换回绝对时刻（PC `tzWall.fromWall`，同 JS）：对得上的偏移有两个（秋令重复）取较早；
     * 一个都对不上（春令缺口）用缺口之前的偏移。候选偏移取前后各半天处。iOS: ReminderAdvance.swift:635
     */
    private fun utcFromLocal(localMs: Long, tz: ZoneId): Long? {
        if (localMs < -(jsDateMaxMs + 2 * dayMs) || localMs > jsDateMaxMs + 2 * dayMs) return null
        val before = offsetMs(localMs - halfDayMs, tz)
        val after = offsetMs(localMs + halfDayMs, tz)
        val hits = mutableListOf<Long>()
        for (o in setOf(before, after)) {
            val inst = localMs - o
            if (offsetMs(inst, tz) == o) hits.add(inst)
        }
        val t = hits.minOrNull() ?: (localMs - before)
        return if (inJSRange(t)) t else null
    }

    /** JS Date 能表示的最大绝对毫秒（TimeClip）。 */
    const val jsDateMaxMs: Long = 8_640_000_000_000_000L
    private const val yearLimit: Long = 1_000_000

    private fun inJSRange(ms: Long): Boolean = ms >= -jsDateMaxMs && ms <= jsDateMaxMs

    private fun add(a: Long, b: Long): Long? {
        val r = a + b
        return if (((a xor r) and (b xor r)) < 0) null else r
    }

    private fun sub(a: Long, b: Long): Long? {
        val r = a - b
        return if (((a xor b) and (a xor r)) < 0) null else r
    }

    private fun mul(a: Long, b: Long): Long? = try { Math.multiplyExact(a, b) } catch (_: ArithmeticException) { null }

    /** 某绝对时刻在 tz 里的偏移（毫秒）。按整秒取（同 iOS `secondsFromGMT(for:)`）。 */
    private fun offsetMs(ms: Long, tz: ZoneId): Long =
        tz.rules.getOffset(Instant.ofEpochSecond(floorDiv(ms, 1000))).totalSeconds * 1000L

    private class Civil(val y: Long, val m: Long, val d: Long)

    // Howard Hinnant 的 days_from_civil / civil_from_days（前推格里历，同 JS）。
    private fun daysFromCivil(y0: Long, m: Long, d: Long): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = floorDiv(y, 400)
        val yoe = y - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    private fun civil(z0: Long): Civil {
        val z = z0 + 719_468
        val era = floorDiv(z, 146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return Civil(yoe + era * 400 + (if (m <= 2) 1 else 0), m, d)
    }

    private fun floorDiv(a: Long, b: Long): Long = Math.floorDiv(a, b)
    private fun mod(a: Long, b: Long): Long = Math.floorMod(a, b)

    /** 测试 / 显示用：某时刻在 tz 里的墙上 [年, 月, 日, 时, 分]。 */
    fun wall(ms: Long, tz: ZoneId): List<Int> {
        val z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), tz)
        return listOf(z.year, z.monthValue, z.dayOfMonth, z.hour, z.minute)
    }
}
