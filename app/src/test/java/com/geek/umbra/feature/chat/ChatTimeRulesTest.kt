package com.geek.umbra.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 会话列表时间（iOS ChatContactsView.timeLabel）：两种服务端写法都认，解析不了原样返回。 */
class ChatTimeRulesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 10, 17, 15, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun label() {
        assertEquals("今天：SQLite 写法按 UTC 换算到本地", "09:41", ChatTimeRules.label("2026-10-17 01:41:00", now, zone))
        assertEquals("09:41", ChatTimeRules.label("2026-10-17T09:41:00+08:00", now, zone))
        assertEquals("昨天", ChatTimeRules.label("2026-10-16T09:41:00.123456+08:00", now, zone))
        assertEquals("7月28日", ChatTimeRules.label("2026-07-28 03:00:00", now, zone))
        assertEquals("解析不了原样返回", "随便", ChatTimeRules.label("随便", now, zone))
        assertEquals("", ChatTimeRules.label(null, now, zone))
    }
}
