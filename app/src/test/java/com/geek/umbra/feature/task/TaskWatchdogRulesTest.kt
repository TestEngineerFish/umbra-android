package com.geek.umbra.feature.task

import com.geek.umbra.feature.chat.ChatBlock
import com.geek.umbra.feature.chat.ChatEchoRules
import com.geek.umbra.net.UmbraJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 看门狗提醒（服务端 #182 字段，设计侧 326 b 那张表；geek-7f 11-12 单子第三、四节）。
class TaskWatchdogRulesTest {
    private fun meta(s: String) = UmbraJson.parseToJsonElement(s) as JsonObject
    private val clock = { _: Long -> "14:05" }

    @Test fun parsesAndIgnoresLegacyAction() {
        val w = TaskWatchdogRules.parse(meta("""{"kind":"task_watchdog","task_id":"t1","reminder":"interrupted","action":"resume_task",
            "actions":["resume","stop"],"step":2,"step_name":"清理","since_ms":1762900000000}"""))!!
        assertEquals("t1", w.taskId)
        assertTrue(TaskWatchdogRules.canResume(w))
        assertTrue(TaskWatchdogRules.canStop(w))
        assertEquals("被服务器重启打断以后还没接着跑", TaskWatchdogRules.line(w, clock))
        // 有没有「接着跑」只看 actions：overdue 只有 stop
        val od = TaskWatchdogRules.parse(meta("""{"kind":"task_watchdog","task_id":"t1","reminder":"overdue","action":"view_task","actions":["stop"]}"""))!!
        assertFalse(TaskWatchdogRules.canResume(od))
        // 收掉以后一颗都不给
        assertFalse(TaskWatchdogRules.canStop(w.copy(cleared = true)))
        // 别的 kind、认不得的 reminder、没 task_id：不认
        assertNull(TaskWatchdogRules.parse(meta("""{"kind":"task_unfinished","task_id":"t1","reminder":"overdue"}""")))
        assertNull(TaskWatchdogRules.parse(meta("""{"kind":"task_watchdog","task_id":"t1","reminder":"later"}""")))
        assertNull(TaskWatchdogRules.parse(meta("""{"kind":"task_watchdog","reminder":"overdue"}""")))
    }

    @Test fun lines() {
        val od = TaskWatchdogRules.parse(meta("""{"kind":"task_watchdog","task_id":"t","reminder":"overdue","actions":["stop"],"step":3,"step_name":"下载","since_ms":1762900000000}"""))!!
        assertEquals("第 3 步「下载」从 14:05 起没动静了", TaskWatchdogRules.line(od, clock))
        // 缺字段兜底：不留空引号、不写几点
        assertEquals("这一步一直没动静了", TaskWatchdogRules.line(od.copy(step = null, stepName = null, sinceMs = null), clock))
        val sus = TaskWatchdogRules.parse(meta("""{"kind":"task_watchdog","task_id":"t","reminder":"suspended","actions":["stop"],"step":2,
            "suspended_secs":10800,"suspend_reason":"device_offline","waiting_devices":[{"id":"m","name":"Sam 的 MacBook"}]}"""))!!
        assertEquals("已经挂起 3 小时了：等「Sam 的 MacBook」上线", TaskWatchdogRules.line(sus, clock))
        assertEquals("已经挂起很久了：等能做这一步的设备上线", TaskWatchdogRules.line(sus.copy(suspendedSecs = null, suspend = TaskSuspendInfo()), clock))
        assertEquals(" 40 分钟", TaskWatchdogRules.durationText(2400))
        assertEquals(" 2 天", TaskWatchdogRules.durationText(200000))
    }

    @Test fun staleByTaskStatus() {
        val od = TaskWatchdog("t", "overdue", listOf("stop"))
        val it = TaskWatchdog("t", "interrupted", listOf("resume", "stop"))
        assertFalse(TaskWatchdogRules.stale(od, null))
        assertFalse(TaskWatchdogRules.stale(od, "running"))
        assertTrue(TaskWatchdogRules.stale(od, "done"))
        assertTrue(TaskWatchdogRules.stale(it, "running"))
        assertFalse(TaskWatchdogRules.stale(it, "suspended"))
    }

    // 卡在会话里：气泡不画，最新那条挂到卡上；收掉了就都不挂。卡不在：气泡照画。
    @Test fun mergesIntoCard() {
        val card = ChatBlock.Task(id = "c", taskId = "t1", goal = "g", pct = 40, status = "suspended", message = "")
        val w1 = TaskWatchdog("t1", "suspended", listOf("stop"))
        val w2 = TaskWatchdog("t1", "interrupted", listOf("resume", "stop"))
        val a1 = ChatBlock.Assistant(id = "a1", text = "x", watchdog = w1)
        val a2 = ChatBlock.Assistant(id = "a2", text = "y", watchdog = w2)
        assertEquals(listOf(card.copy(watchdog = w2)), ChatEchoRules.visible(listOf(card, a1, a2)))
        assertEquals(listOf(card), ChatEchoRules.visible(listOf(card, a1, a2.copy(watchdog = w2.copy(cleared = true)))))
        assertEquals(listOf(a1), ChatEchoRules.visible(listOf(a1)))
    }
}
