// 服务端时间串的解析与显示（iOS: TaskViews.swift:1146 `UmbraTime` + Utils/WidgetShared.swift:134 `parseServerDate`）。
// 纯 Kotlin（java.time，minSdk 28 自带），任务和灵感共用。
package com.geek.umbra.feature.task

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object TaskTime {
    /**
     * 服务端时间串 → 时刻。认 ISO8601（带时区、带 / 不带小数秒）、Python isoformat（6 位小数 +「+00:00」）、
     * SQLite 的「2026-08-08 14:15:09」（按 UTC）。解不出回 null —— iOS 原来解不了 SQLite 那种，界面上直接显示一串原始时间戳。
     */
    fun parse(raw: String?): Instant? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { return OffsetDateTime.parse(s).toInstant() }
        runCatching { return Instant.parse(s) }
        val normalized = s.replace(' ', 'T')
        runCatching { return OffsetDateTime.parse(normalized).toInstant() }
        runCatching { return LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC) }
        return null
    }

    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private val md = DateTimeFormatter.ofPattern("M月d日")
    private val mdhm = DateTimeFormatter.ofPattern("M月d日 HH:mm")
    private val ymdhm = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")

    /** 「09:41」/「昨天」/「7月28日」。解析不了就原样返回 —— 编一个「刚刚」会让人以为任务刚跑过。 */
    fun relative(iso: String?, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String {
        val d = parse(iso) ?: return iso ?: ""
        val local = d.atZone(zone)
        // 不用 LocalDate.ofInstant：那是 Java 9 / API 34 才有的，minSdk 28 上会崩
        val today: LocalDate = now.atZone(zone).toLocalDate()
        return when (local.toLocalDate()) {
            today -> local.format(hm)
            today.minusDays(1) -> "昨天"
            else -> local.format(md)
        }
    }

    /** 「7月28日 09:41」。今年的不写年份。 */
    fun absolute(iso: String?, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String {
        val d = parse(iso) ?: return iso ?: "—"
        val local = d.atZone(zone)
        return if (local.year == now.atZone(zone).year) local.format(mdhm) else local.format(ymdhm)
    }
}
