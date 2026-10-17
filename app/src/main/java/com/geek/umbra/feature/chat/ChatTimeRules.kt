// 会话列表右上角的时间（iOS: ChatContactsView.swift:202 `timeLabel` + UmbraShared.parseServerDate）。
//
// 服务端时间两种写法：/history 的 SQLite `2026-08-08 14:15:09`（UTC，无时区）和广播的 ISO（带时区 / 微秒）。
// iOS 原来自己挂 ISO 解析器，吃不下 SQLite 那种，会话列表就直接显示一串原始时间戳 —— 这里两种都认（同 ChatHistoryMerge.epoch）。
// 纯判据，有用例。
package com.geek.umbra.feature.chat

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ChatTimeRules {
    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * 服务端时间 → 「09:41」/「昨天」/「7月28日」。解析不了就原样返回，不给个假时间；空 → ""。
     */
    fun label(raw: String?, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        if (raw.isNullOrEmpty()) return ""
        val sec = ChatHistoryMerge.epoch(raw) ?: return raw
        val t = Instant.ofEpochMilli((sec * 1000).toLong()).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val d: LocalDate = t.toLocalDate()
        if (d == today) return t.format(hm)
        if (d == today.minusDays(1)) return "昨天"
        return "${t.monthValue}月${t.dayOfMonth}日"
    }

    /** 本机此刻的 ISO 串（乐观气泡 / 预览用）。 */
    fun nowIso(): String = Instant.now().toString()
}
