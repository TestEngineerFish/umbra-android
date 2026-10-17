// 「工作日」重复认法定节假日与调休（umbra#93）—— 纯判据：日历怎么拼、回包怎么分档、本地缓存怎么并。
// 对应 iOS Features/Reminder/HolidayCalendar.swift（三端约定写在那个文件头）：
//   GET /calendar/cn-holidays?year=YYYY → {year, days:[{date,name,isOffDay}], stale, published?, rev?}
//   · 端上同步时拉当年 + 次年；502 / 断网 / 旧服务端 → 保留本地已有的，不清；
//   · years 只收服务端真回过的年份（days 为空的也收 —— 结果等于周六日）；
//   · 某年没表 → 退回只认周六日。
// 纯 Kotlin，不 import android.*。
package com.geek.umbra.feature.reminder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId

/** 服务端 days 里的一行。name 只给人看，推进不用。 */
data class CnHolidayDay(val date: String, val isOffDay: Boolean, val name: String? = null)

/** 本机存的一年：days + 服务端给的 published / rev（只存不用，调试日志里说得出用的是哪一版表）。 */
data class HolidayYearEntry(val days: List<CnHolidayDay>, val published: Boolean? = null, val rev: String? = null)

/** 拉一年节假日表的结果分档。502 与断网处置一样（保留本地），但分开记 —— 查的是两个地方。 */
sealed interface CnHolidayFetch {
    data class Ok(val entry: HolidayYearEntry) : CnHolidayFetch
    /** 服务端上游没拉到、回的是它的旧缓存 —— 照用。 */
    data class Stale(val entry: HolidayYearEntry) : CnHolidayFetch
    data object UpstreamFailed : CnHolidayFetch
    data object Failed : CnHolidayFetch
}

val CnHolidayFetch.entry: HolidayYearEntry?
    get() = when (this) {
        is CnHolidayFetch.Ok -> entry
        is CnHolidayFetch.Stale -> entry
        else -> null
    }

/** 本机那份节假日缓存：哪台服务端给的 + 按年的 days。换了服务端，旧那台给的表不再认（iOS 分区，PC 不分区）。 */
data class HolidayCacheFile(val server: String, val years: Map<String, HolidayYearEntry>)

object HolidayRules {
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 服务端按年回的 days 拼成日历 —— PC `calendarFrom`。年份键认不出整数的跳过；日期形状不对的那一行跳过。 */
    fun from(byYear: Map<String, List<CnHolidayDay>>): HolidayCalendar {
        val off = mutableSetOf<String>(); val work = mutableSetOf<String>(); val years = mutableSetOf<Int>()
        for ((y, days) in byYear) {
            val yr = y.toIntOrNull() ?: continue
            years.add(yr)
            for (d in days) if (isDateKey(d.date)) { if (d.isOffDay) off.add(d.date) else work.add(d.date) }
        }
        return HolidayCalendar(off, work, years)
    }

    /** `^\d{4}-\d{2}-\d{2}$`，只认 ASCII 数字（全角数字不认，PC 的 \d 同）。 */
    fun isDateKey(s: String): Boolean {
        if (s.length != 10 || s[4] != '-' || s[7] != '-') return false
        return s.withIndex().all { (i, c) -> i == 4 || i == 7 || c in '0'..'9' }
    }

    /**
     * 由状态码与回包分档：只有 2xx 且 days 是数组才算拿到；502 单独一档；其余一律不动本地。
     * days 逐行解、丢坏留好；isOffDay 缺键按 false（PC `Boolean(undefined)`）。iOS: HolidayCalendar.swift:139
     */
    fun classify(status: Int?, body: String?): CnHolidayFetch {
        if (status == null) return CnHolidayFetch.Failed
        if (status == 502) return CnHolidayFetch.UpstreamFailed
        if (status !in 200..299 || body == null) return CnHolidayFetch.Failed
        val o = runCatching { lenient.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return CnHolidayFetch.Failed
        val arr = o["days"] as? JsonArray ?: return CnHolidayFetch.Failed
        val days = arr.mapNotNull { dayOf(it) }
        val e = HolidayYearEntry(days, o.strictBool("published"), o.strictString("rev"))
        return if (o.strictBool("stale") == true) CnHolidayFetch.Stale(e) else CnHolidayFetch.Ok(e)
    }

    private fun dayOf(e: JsonElement): CnHolidayDay? {
        val o = e as? JsonObject ?: return null
        val date = o.strictString("date") ?: return null
        return CnHolidayDay(date, o.strictBool("isOffDay") ?: false, o.strictString("name"))
    }

    /** 一轮拉取之后本地该存成什么样：不是这台服务端的从空开始；拿到的整年替换；没拿到的保留。 */
    fun merged(stored: HolidayCacheFile?, server: String, results: List<Pair<Int, CnHolidayFetch>>): HolidayCacheFile {
        val years = (if (stored?.server == server) stored.years else null)?.toMutableMap() ?: mutableMapOf()
        for ((y, f) in results) f.entry?.let { years[y.toString()] = it }
        return HolidayCacheFile(server, years)
    }

    /** 推进该用的日历：存的那份是这台服务端的才用，否则 null（只认周六日）。 */
    fun calendar(file: HolidayCacheFile?, server: String): HolidayCalendar? {
        if (file == null || file.server != server) return null
        return from(file.years.mapValues { it.value.days })
    }

    /** 调试日志用：「2026@01a743e88cb5 2027@97d170e1550e」。 */
    fun revSummary(file: HolidayCacheFile?): String {
        if (file == null || file.years.isEmpty()) return "无表"
        return file.years.keys.sorted().joinToString(" ") { "$it@${file.years[it]?.rev ?: "?"}" }
    }

    /** 同步时要拉哪几年：当年 + 次年，按公历取（java.time 本来就是 ISO 公历，不跟用户的佛历 / 和历走）。 */
    fun yearsToFetch(nowMs: Long, tz: ZoneId): List<Int> {
        val y = Instant.ofEpochMilli(nowMs).atZone(tz).year
        return listOf(y, y + 1)
    }

    // MARK: 缓存文件编解码

    fun encode(f: HolidayCacheFile): JsonObject = buildJsonObject {
        put("server", f.server)
        put("years", buildJsonObject {
            for ((y, e) in f.years) put(y, buildJsonObject {
                put("days", buildJsonArray {
                    e.days.forEach { d -> add(buildJsonObject { put("date", d.date); put("isOffDay", d.isOffDay); d.name?.let { put("name", it) } }) }
                })
                e.published?.let { put("published", it) }
                e.rev?.let { put("rev", it) }
            })
        })
    }

    /** 读坏了当没有（只是缓存，退回周六日；下一轮同步重拉）。缺 published / rev 的老缓存照解。 */
    fun decode(text: String): HolidayCacheFile? = runCatching {
        val o = lenient.parseToJsonElement(text) as JsonObject
        val server = o.strictString("server")!!
        val ys = o["years"] as JsonObject
        HolidayCacheFile(server, ys.mapValues { (_, v) ->
            val yo = v as JsonObject
            HolidayYearEntry((yo["days"] as JsonArray).mapNotNull { dayOf(it) }, yo.strictBool("published"), yo.strictString("rev"))
        })
    }.getOrNull()
}
