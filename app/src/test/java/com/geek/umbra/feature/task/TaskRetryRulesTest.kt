package com.geek.umbra.feature.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 输入输出照抄 iOS Tools/swifttests/TaskRetryRulesTests.swift。 */
class TaskRetryRulesTest {
    private fun resp(status: Int, code: String? = null, state: String? = null, detail: String = "") = TaskRetryResponse(status, code, state, detail)
    private val sc = "state_conflict"

    @Test fun outcomes() {
        val o = TaskRetryRules::outcome
        assertEquals(TaskRetryOutcome.Ok, o(resp(200)))
        assertEquals(TaskRetryOutcome.Missing, o(resp(404, "not_found")))
        assertEquals(TaskRetryOutcome.Missing, o(resp(409, sc, "missing")))
        assertEquals(TaskRetryOutcome.WrongStatus, o(resp(409, sc, "wrong_status")))
        assertEquals(TaskRetryOutcome.NothingToRetry, o(resp(409, sc, "nothing_to_retry")))
        assertEquals(TaskRetryOutcome.Operate, o(resp(409, sc, "operate")))
        assertEquals(TaskRetryOutcome.NotRetryable("引擎拒绝"), o(resp(409, sc, "not_retryable", "引擎拒绝")))
        assertEquals(TaskRetryOutcome.Unknown("新的一档"), o(resp(409, sc, "brand_new_state", "新的一档")))
        assertEquals(TaskRetryOutcome.Offline, o(resp(0, detail = "offline")))
        assertEquals(TaskRetryOutcome.ServerError(502), o(resp(502, detail = "HTTP 502")))
        assertEquals(TaskRetryOutcome.ServerError(500), o(resp(500)))
        assertEquals("409 但不是 state_conflict → 不按 state 分", TaskRetryOutcome.Unknown("HTTP 409"), o(resp(409, "other_code", "operate", "HTTP 409")))
        assertEquals(TaskRetryOutcome.Unknown("HTTP 400"), o(resp(400, detail = "HTTP 400")))

        assertEquals("服务端这次没有响应（HTTP 502），过一会儿再按一次。", TaskRetryOutcome.ServerError(502).message)
        assertEquals("这个任务不能重试：X", TaskRetryOutcome.NotRetryable("X").message)
        assertNull(TaskRetryOutcome.Ok.message)
        assertEquals("原话", TaskRetryOutcome.Unknown("原话").message)
        for (x in listOf(TaskRetryOutcome.Missing, TaskRetryOutcome.WrongStatus, TaskRetryOutcome.NothingToRetry, TaskRetryOutcome.Operate,
            TaskRetryOutcome.NotRetryable("d"), TaskRetryOutcome.Offline, TaskRetryOutcome.ServerError(503), TaskRetryOutcome.Unknown("u"))) {
            assertFalse("$x 的字面里留着没填的占位", x.message?.contains("{{") ?: false)
        }
        for (x in listOf(TaskRetryOutcome.Ok, TaskRetryOutcome.WrongStatus, TaskRetryOutcome.Offline, TaskRetryOutcome.ServerError(502), TaskRetryOutcome.Unknown(""))) {
            assertTrue("$x 之后钮该留着", x.keepsRetry)
        }
        for (x in listOf(TaskRetryOutcome.Missing, TaskRetryOutcome.NothingToRetry, TaskRetryOutcome.Operate, TaskRetryOutcome.NotRetryable(""))) {
            assertFalse("$x 之后钮该收掉", x.keepsRetry)
        }
        assertTrue(TaskRetryOutcome.WrongStatus.refreshes)
        assertTrue(TaskRetryOutcome.Ok.refreshes)
        assertFalse(TaskRetryOutcome.Offline.refreshes)
    }

    @Test fun buttons() {
        val pay = "https://pay.example.com/topup"
        val retryable = TaskReason.taskFailAction("rate_limited", null)
        assertEquals(TaskRetryButton.Retry, TaskRetryRules.button("failed", retryable, false, false))
        assertEquals("已取消（不带原因）→ 重试", TaskRetryButton.Retry, TaskRetryRules.button("cancelled", TaskReason.taskFailAction(null, null), false, false))
        assertEquals(TaskRetryButton.Relaunch, TaskRetryRules.button("failed", retryable, false, true))
        assertEquals(TaskRetryButton.Relaunch, TaskRetryRules.button("cancelled", TaskFailAction.AsBefore, false, true))
        for (st in listOf("running", "pending", "suspended", "done")) assertNull(TaskRetryRules.button(st, TaskFailAction.AsBefore, false, false))
        for (w in listOf("insufficient_balance", "auth_failed", "bad_request", "unavailable")) {
            assertNull("$w 不出重试", TaskRetryRules.button("failed", TaskReason.taskFailAction(w, null), false, false))
        }
        assertNull("欠费带地址也不出重试", TaskRetryRules.button("failed", TaskReason.taskFailAction("insufficient_balance", pay), false, false))
        assertNull("被服务端说死之后不出", TaskRetryRules.button("failed", TaskFailAction.AsBefore, true, false))

        assertEquals(TaskRetryCopy.hintFailed, TaskRetryRules.hint("failed", TaskRetryButton.Retry))
        assertEquals(TaskRetryCopy.hintCancelled, TaskRetryRules.hint("cancelled", TaskRetryButton.Retry))
        assertNull(TaskRetryRules.hint("cancelled", TaskRetryButton.Relaunch))
        assertNull(TaskRetryRules.hint("failed", null))
    }

    @Test fun cards() {
        val pay = "https://pay.example.com/topup"
        fun card(st: String, f: TaskFailAction, blocked: Boolean = false, operate: Boolean = false, gone: Boolean = false) =
            TaskRetryRules.cardAction(st, f, blocked, operate, gone)
        assertEquals(TaskCardAction.Retry, card("failed", TaskReason.taskFailAction("upstream_error", null)))
        assertEquals(TaskCardAction.Topup(pay), card("failed", TaskReason.taskFailAction("insufficient_balance", pay)))
        assertEquals(TaskCardAction.Relaunch, card("failed", TaskFailAction.AsBefore, operate = true))
        assertEquals(TaskCardAction.Relaunch, card("cancelled", TaskFailAction.AsBefore, operate = true))
        for (w in listOf("auth_failed", "bad_request", "unavailable")) assertEquals(TaskCardAction.ViewResult, card("failed", TaskReason.taskFailAction(w, null)))
        assertEquals("欠费没地址 → 查看结果", TaskCardAction.ViewResult, card("failed", TaskReason.taskFailAction("insufficient_balance", null)))
        assertEquals(TaskCardAction.ViewResult, card("done", TaskFailAction.AsBefore))
        assertNull(card("failed", TaskFailAction.AsBefore, gone = true))
        assertNull(card("failed", TaskFailAction.AsBefore, blocked = true))
    }

    @Test fun misc() {
        assertTrue(TaskRetryRules.isOperate("operate"))
        assertFalse(TaskRetryRules.isOperate(null))
        assertFalse(TaskRetryRules.isOperate("claude_code"))
        assertEquals("/tasks/abc123/retry", TaskRetryRules.retryPath("abc123"))
        assertEquals("id 里的分隔符按一个段转义", "/tasks/a%2Fb%3Fc%23d%25e/retry", TaskRetryRules.retryPath("a/b?c#d%e"))
        assertEquals("导出封面", TaskRetryRules.relaunchText("  导出封面  ", "封面"))
        assertEquals("封面", TaskRetryRules.relaunchText(" ", "封面"))
        assertNull(TaskRetryRules.relaunchText("", null))
    }

    @Test fun topupLinkOnlyHttp() {
        assertNull("不是欠费类不出", TaskReason.topupLink(com.geek.umbra.net.ReasonKind.RateLimited, "https://x.com"))
        assertNull("javascript: 不收", TaskReason.topupLink(com.geek.umbra.net.ReasonKind.InsufficientBalance, "javascript:alert(1)"))
        assertEquals("https://x.com/pay", TaskReason.topupLink(com.geek.umbra.net.ReasonKind.NeedMembership, " https://x.com/pay "))
    }
}
