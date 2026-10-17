// 记账里要上行给服务端的那几种日期字符串（ym、周期规则的 first_date / end_date / time_hhmm）唯一的格式化 / 解析出口。
// 照抄 iOS Features/Money/MoneyWireDate.swift。纯 Kotlin。
//
// 为什么要钉死历法和地区（台账 2026-10-01 Money f）：手机默认的历法、地区、12/24 小时设置会改写字符串 ——
// 佛历手机把 2026-09-05 读成公元 1483 年、原样再存就改坏了 first_date；12 小时制下 HH:mm 被写成 8:30 PM；
// 阿拉伯语地区出来的是 ٢٠٢٦-٠٩-٠٥。
// > 一个会离开这台手机的字符串，不能由这台手机的偏好来决定长相。
// 只有上行 / 解析服务端字段走这里；给人看的照旧跟着手机的设置。
//
// Android 这边用 java.time：DateTimeFormatter 的数字字形（DecimalStyle.STANDARD）与历法都不从默认地区继承，
// 但这里仍然**写下来**（Locale.ROOT + IsoChronology），而不是依赖一个推出来的结论。
package com.geek.umbra.feature.money

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.chrono.Chronology
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.ResolverStyle
import java.util.Locale

/** iOS: MoneyWireDate.swift:26 */
object MoneyWireDate {
    /** 线上口径的历法：公历。时区 = 这台手机当前的时区（规则的日期与时刻都是本地墙上时间，服务端另拿 tz_offset_min 换算）。 */
    val chronology: Chronology = IsoChronology.INSTANCE

    /**
     * 钉死了历法 / 地区 / 数字字形 / 解析严格度的 formatter。
     * ⚠️ 用 uuuu 不用 yyyy：STRICT 下 yyyy 是「纪元年」，没有纪元字段时整个解不出来。
     * ⚠️ STRICT：2026-13-40、25:00 回 null，不被宽松解析「进位」成一个别的日期。
     * 时区每次现取（系统时区会变），所以不缓存成常量。
     */
    private fun formatter(pattern: String): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern, Locale.ROOT)
            .withChronology(chronology)
            .withDecimalStyle(DecimalStyle.STANDARD)
            .withResolverStyle(ResolverStyle.STRICT)
            .withZone(ZoneId.systemDefault())

    /** 2026-09-05（first_date / end_date）。 */
    fun dateString(ms: Long): String = formatter("uuuu-MM-dd").format(Instant.ofEpochMilli(ms))

    /** 08:30（time_hhmm），24 小时制。 */
    fun timeString(ms: Long): String = formatter("HH:mm").format(Instant.ofEpochMilli(ms))

    /** 2026-09（流水 / 统计的 ym）。 */
    fun ym(ms: Long): String {
        val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        return String.format(Locale.ROOT, "%04d-%02d", d.year, d.monthValue)
    }

    /** 解析服务端给的 first_date + time_hhmm（本地墙上时间）。解不出回 null（调用方保留原来的默认值）。 */
    fun parse(date: String, time: String): Long? = try {
        LocalDateTime.parse("$date $time", formatter("uuuu-MM-dd HH:mm"))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }

    /** 解析服务端给的 end_date，取当地 00:00。 */
    fun parse(date: String): Long? = try {
        LocalDate.parse(date, formatter("uuuu-MM-dd"))
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }
}
