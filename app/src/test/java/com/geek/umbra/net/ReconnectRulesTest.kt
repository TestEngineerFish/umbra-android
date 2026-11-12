package com.geek.umbra.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 长连接断线重连后补同步（geek-13 11-12）：什么时候算重连、节流怎么算。
class ReconnectRulesTest {
    @Test fun reconnectOnlyAfterBeingOnline() {
        // 登录 / 解锁后的第一次在线不补
        assertFalse(ReconnectRules.isReconnect(null, true))
        assertTrue(ReconnectRules.isReconnect(false, true))
        assertFalse("在线 → 在线不算", ReconnectRules.isReconnect(true, true))
        assertFalse(ReconnectRules.isReconnect(false, false))
        // 没在线过时离线不开始记；见过在线以后照实记
        assertNull(ReconnectRules.nextWasOnline(null, false))
        assertEquals(true, ReconnectRules.nextWasOnline(null, true))
        assertEquals(false, ReconnectRules.nextWasOnline(true, false))
    }

    @Test fun throttle() {
        val t0 = 1_762_930_800_000L
        assertEquals("没补过：马上", 0L, ReconnectRules.delayMs(null, t0))
        assertEquals("5 秒前补过：等满 20 秒", 15_000L, ReconnectRules.delayMs(t0 - 5_000, t0))
        assertEquals(0L, ReconnectRules.delayMs(t0 - 20_000, t0))
        assertEquals("时钟往回拨：当成很久没补", 0L, ReconnectRules.delayMs(t0 + 60_000, t0))
    }
}
