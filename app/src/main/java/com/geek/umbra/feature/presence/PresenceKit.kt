// 使用记录（GitHub 贡献图那张表，需求 §14.8 / 契约 §15.1）的线上形状与纯判据（iOS: Features/Presence/PresenceKit.swift）。
// 纯 Kotlin（java.time），配 JUnit 用例（PresenceKitTest.kt，照抄 iOS Tools/swifttests/PresenceKitTests.swift）。
// 文案的 key 在这里定（"presence.x.y" ↔ R.string.presence_x_y），这里不碰资源。
package com.geek.umbra.feature.presence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// MARK: - 线上形状（契约 §15.1，字段名一字不差）。解码宽松：缺键给默认值 —— 一个字段对不上就让整张表空白，比少显示一个数更糟。

@Serializable
data class PresenceDaySpan(val from: String = "", val to: String = "")

/** GET /presence/calendar 的回包。days 只回有数据的天。 */
@Serializable
data class PresenceCalendarDTO(
    val range: PresenceDaySpan = PresenceDaySpan(),
    val years: List<Int> = emptyList(),
    /** 缺键按默认开。 */
    val learn: Boolean = true,
    val total_days: Int = 0,
    val total_actions: Int = 0,
    val days: List<PresenceDayDTO> = emptyList(),
)

/** 一天。level 1–4 由服务端按「有活跃的那些天」的四分位算（同 GitHub），端上不再自己切。 */
@Serializable
data class PresenceDayDTO(val day: String = "", val active_min: Int = 0, val actions: Int = 0, val level: Int = 0)

/** GET /presence/activity?month= 的回包。prev = 更早一个有数据的月份，null = 到头了。 */
@Serializable
data class PresenceActivityDTO(val month: String = "", val items: List<Item> = emptyList(), val prev: String? = null) {
    @Serializable
    data class Item(val category: String = "other", val count: Int = 0, val days: Int = 0)
}

/** GET /presence/profile（执勤 M1a 契约 §16.5）：作息画像。不足 7 天 ready = false。 */
@Serializable
data class PresenceProfileDTO(
    val ready: Boolean = false,
    val days_used: Int = 0,
    val workday: Part = Part(),
    val restday: Part = Part(),
    val unusual: Boolean = false,
) {
    /** 一组（工作日 / 休息日）。slots 是 48 个半小时格的「在线占比」0–1。 */
    @Serializable
    data class Part(
        val start: String = "",
        val end: String = "",
        /** 逐格宽松收：一格认不出（null / 字符串）不该让整条浓淡条没了，认不出的当 0。 */
        @SerialName("slots") val slotsRaw: List<JsonElement> = emptyList(),
    ) {
        val slots: List<Double> get() = slotsRaw.map { (it as? JsonPrimitive)?.doubleOrNull ?: 0.0 }
    }
}

// MARK: - 纯判据

object PresenceKit {
    /** 服务端按 Asia/Shanghai 切天。端上排方格、算「本月」也按它 —— 用设备时区的话人在海外时最右那格永远是空的。 */
    val tz: ZoneId = ZoneId.of("Asia/Shanghai")

    private val ymd = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val ym = DateTimeFormatter.ofPattern("yyyy-MM")

    /** 'YYYY-MM-DD' → 那天。认不出回 null。 */
    fun date(day: String): LocalDate? = runCatching { LocalDate.parse(day, ymd) }.getOrNull()

    fun dayString(d: LocalDate): String = d.format(ymd)

    /** 现在所在的月份 'YYYY-MM'（上海）。活动那一节默认展开本月。 */
    fun month(nowMs: Long): String = Instant.ofEpochMilli(nowMs).atZone(tz).format(ym)

    /** 'YYYY-MM' → (年, 月)。认不出回 null。 */
    fun yearMonth(s: String): Pair<Int, Int>? {
        val p = s.split("-")
        if (p.size != 2) return null
        val y = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        return if (m in 1..12) y to m else null
    }

    /**
     * 一格画第几档（0 灰，1–4 橙由浅到深）：服务端的 level 夹到 1–4；**没有活跃也没有操作的天一律 0 档**
     * （深橙一格、悬停却说操作 0 次是假话）；有活动却回了 0 档给 1 档（灰格 = 没来，同样是假话）。
     */
    fun level(raw: Int, activeMin: Int, actions: Int): Int {
        if (activeMin <= 0 && actions <= 0) return 0
        return raw.coerceIn(1, 4)
    }

    /** 一格。day 为空串的是范围外的占位，画成透明。 */
    data class Cell(val day: String, val level: Int, val activeMin: Int, val actions: Int) {
        val inRange: Boolean get() = day.isNotEmpty()

        companion object { val blank = Cell("", 0, 0, 0) }
    }

    data class MonthLabel(val column: Int, val month: Int)

    /** 一张表：columns[列][行]，每列 7 格（行 0 = 周一 … 行 6 = 周日）。 */
    data class Grid(val columns: List<List<Cell>>, val months: List<MonthLabel>)

    /** 两个月份标签之间至少隔几列；太挤时**丢前面那个**（第一列的月份常常只露出一两天）。 */
    const val MONTH_LABEL_MIN_GAP = 3

    /**
     * 从 from 到 to（含两端）排一张周一在上的表。第一列从 from 所在那周的周一开始，最后一列是 to 所在那周；
     * 过去一年落成 53 列，偶尔 54。日期认不出 / from 晚于 to → null（调用方画「这次没读到」）。
     */
    fun grid(from: String, to: String, days: List<PresenceDayDTO>): Grid? {
        val start = date(from) ?: return null
        val end = date(to) ?: return null
        if (start.isAfter(end)) return null
        val firstMonday = start.minusDays((start.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
        val byDay = HashMap<String, PresenceDayDTO>()
        for (d in days) if (!byDay.containsKey(d.day)) byDay[d.day] = d
        val span = ChronoUnit.DAYS.between(firstMonday, end).toInt()
        val columnCount = span / 7 + 1
        val columns = ArrayList<List<Cell>>(columnCount)
        val candidates = mutableListOf<MonthLabel>()
        for (col in 0 until columnCount) {
            val cells = ArrayList<Cell>(7)
            var labelMonth: Int? = null
            for (row in 0 until 7) {
                val d = firstMonday.plusDays((col * 7 + row).toLong())
                if (d.isBefore(start) || d.isAfter(end)) { cells += Cell.blank; continue }
                val key = dayString(d)
                val got = byDay[key]
                cells += Cell(key, level(got?.level ?: 0, got?.active_min ?: 0, got?.actions ?: 0), got?.active_min ?: 0, got?.actions ?: 0)
                // 这一列里有某月 1 号 → 标那个月；第一列标它第一个在范围里的那天所在的月。
                if (d.dayOfMonth == 1 || (col == 0 && labelMonth == null)) labelMonth = d.monthValue
            }
            columns += cells
            labelMonth?.let { candidates += MonthLabel(col, it) }
        }
        val months = candidates.filterIndexed { i, c -> !(i + 1 < candidates.size && candidates[i + 1].column - c.column < MONTH_LABEL_MIN_GAP) }
        return Grid(columns, months)
    }

    /** 左侧只标「一 / 三 / 五」三行。 */
    val weekdayLabelRows = listOf(0, 2, 4)

    /** 活跃分钟 → 一句话的 key 与参数。负数按 0。 */
    fun duration(minutes: Int): Pair<String, List<Int>> {
        val m = maxOf(0, minutes)
        return when {
            m == 0 -> "presence.dur.none" to emptyList()
            m < 60 -> "presence.dur.m" to listOf(m)
            m % 60 == 0 -> "presence.dur.h" to listOf(m / 60)
            else -> "presence.dur.hm" to listOf(m / 60, m % 60)
        }
    }

    /** 契约 §15.1 的类别取值。 */
    val categories = listOf("chat", "money", "vault", "phrases", "inspirations", "reminders", "tasks", "knowledge", "files", "settings", "other")

    /** 类别 → 那句话的 key。认不出的（服务端以后加了新类别）落「其他操作 N 次」—— 不画 key 本身，也不丢掉那一行。 */
    fun categoryKey(category: String): String = "presence.cat." + if (category in categories) category else "other"

    /** 年份选项：null =「过去一年」排第一，其后是有数据的年份（新的在前，去重）。 */
    fun yearOptions(years: List<Int>): List<Int?> = listOf<Int?>(null) + years.sortedDescending().distinct()

    /** 一天 48 个半小时格。 */
    const val SLOT_COUNT = 48

    /** 收成正好 48 格、每格夹到 0–1：少了补 0、多了截掉、NaN 当 0 —— 少一格整条就往左错半小时。 */
    fun normalizedSlots(xs: List<Double>): List<Double> = (0 until SLOT_COUNT).map { i ->
        val v = xs.getOrNull(i) ?: return@map 0.0
        if (!v.isFinite()) 0.0 else v.coerceIn(0.0, 1.0)
    }

    /** 一格占比 → 第几档（与方格同一套 0–4 档颜色）。 */
    fun profileLevel(v: Double): Int = when {
        !v.isFinite() || v <= 0 -> 0
        v < 0.25 -> 1
        v < 0.5 -> 2
        v < 0.75 -> 3
        else -> 4
    }

    /** "08:40" → "8:40"。认不出原样回。 */
    fun clockText(hhmm: String): String {
        val p = hhmm.split(":")
        if (p.size != 2) return hhmm
        val h = p[0].toIntOrNull() ?: return hhmm
        if (p[1].length != 2) return hhmm
        return "$h:${p[1]}"
    }

    /** 一组那一句：起止都有 →「通常 8:40–23:10 在线」；缺一个 →「还看不出通常几点在」（不编一个区间）。 */
    fun profileLine(part: PresenceProfileDTO.Part): Pair<String, List<String>> {
        if (part.start.isEmpty() || part.end.isEmpty()) return "presence.profile.unknown" to emptyList()
        return "presence.profile.range" to listOf(clockText(part.start), clockText(part.end))
    }
}
