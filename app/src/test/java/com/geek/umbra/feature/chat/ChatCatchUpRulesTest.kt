package com.geek.umbra.feature.chat

import com.geek.umbra.feature.chat.ChatCatchUpRules.Plan
import com.geek.umbra.feature.chat.ChatCatchUpRules.Row
import org.junit.Assert.assertEquals
import org.junit.Test

// 断线重连后补拉一个会话：接到后面 / 整段重拉 / 不动（geek-13 11-12）。
class ChatCatchUpRulesTest {
    private fun r(id: Long, role: String = "assistant", c: String = "m$id") = Row(id, role, c)

    @Test fun plans() {
        assertEquals(Plan.None, ChatCatchUpRules.plan(10, emptyList(), emptyList(), 40))
        assertEquals("没有新的", Plan.None, ChatCatchUpRules.plan(10, listOf(r(9), r(10)), emptyList(), 40))
        assertEquals(Plan.Append(listOf(11, 12)), ChatCatchUpRules.plan(10, listOf(r(12), r(9), r(10), r(11)), emptyList(), 40))
        assertEquals("整页都新且满页：可能有缺口", Plan.Reload, ChatCatchUpRules.plan(10, (11L..50L).map { r(it) }, emptyList(), 40))
        assertEquals("整页都新但没满：就是全部", Plan.Append(listOf(11, 12)), ChatCatchUpRules.plan(10, listOf(r(11), r(12)), emptyList(), 40))
        assertEquals("手上没落库的块", Plan.Reload, ChatCatchUpRules.plan(null, listOf(r(1)), emptyList(), 40))
    }

    // 断线前刚发 / 刚推到、还没拿到 id 的那几条不再接一遍；一样的话逐条认领
    @Test fun echoesClaimedOnce() {
        val rows = listOf(r(9), r(11, "user", "好"), r(12, "assistant", "收到"), r(13, "user", "好"))
        assertEquals(Plan.Append(listOf(13)), ChatCatchUpRules.plan(10, rows, listOf("user" to "好", "assistant" to "收到"), 40))
        assertEquals(Plan.None, ChatCatchUpRules.plan(10, listOf(r(9), r(11, "user", "好")), listOf("user" to "好"), 40))
    }
}
