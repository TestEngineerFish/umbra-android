package com.geek.umbra.feature.chat

import com.geek.umbra.feature.chat.ChatTaskKit as K
import org.junit.Assert.assertEquals
import org.junit.Test

/** 照抄 iOS Tools/swifttests/TaskRetryRulesTests.swift / TaskSuspendRulesTests.swift 里与聊天卡相关的几组。 */
class ChatTaskKitTest {
    private fun resp(s: Int, code: String? = null, state: String? = null, detail: String = "") = TaskActionResponse(s, code, state, detail)
    private val sc = "state_conflict"
    private val pay = "https://pay.example.com/topup"

    @Test fun retryOutcome() {
        assertEquals(TaskRetryOutcome.Ok, K.retryOutcome(resp(200)))
        assertEquals(TaskRetryOutcome.Missing, K.retryOutcome(resp(404, "not_found")))
        assertEquals(TaskRetryOutcome.Missing, K.retryOutcome(resp(409, sc, "missing")))
        assertEquals(TaskRetryOutcome.WrongStatus, K.retryOutcome(resp(409, sc, "wrong_status")))
        assertEquals(TaskRetryOutcome.NothingToRetry, K.retryOutcome(resp(409, sc, "nothing_to_retry")))
        assertEquals(TaskRetryOutcome.Operate, K.retryOutcome(resp(409, sc, "operate")))
        assertEquals(TaskRetryOutcome.NotRetryable("引擎拒绝"), K.retryOutcome(resp(409, sc, "not_retryable", "引擎拒绝")))
        assertEquals(TaskRetryOutcome.Unknown("新的一档"), K.retryOutcome(resp(409, sc, "brand_new_state", "新的一档")))
        assertEquals(TaskRetryOutcome.Offline, K.retryOutcome(resp(0, detail = "offline")))
        assertEquals(TaskRetryOutcome.ServerError(502), K.retryOutcome(resp(502, detail = "HTTP 502")))
        assertEquals(TaskRetryOutcome.ServerError(500), K.retryOutcome(resp(500)))
        assertEquals(TaskRetryOutcome.Unknown("HTTP 409"), K.retryOutcome(resp(409, "other_code", "operate", "HTTP 409")))
        assertEquals(TaskRetryOutcome.Unknown("HTTP 400"), K.retryOutcome(resp(400, detail = "HTTP 400")))
        assertEquals("服务端这次没有响应（HTTP 502），过一会儿再按一次。", TaskRetryOutcome.ServerError(502).message)
        assertEquals("这个任务不能重试：X", TaskRetryOutcome.NotRetryable("X").message)
        assertEquals(null, TaskRetryOutcome.Ok.message)
        for (o in listOf(TaskRetryOutcome.Ok, TaskRetryOutcome.WrongStatus, TaskRetryOutcome.Offline, TaskRetryOutcome.ServerError(500), TaskRetryOutcome.Unknown("x"))) assertEquals(true, o.keepsRetry)
        for (o in listOf(TaskRetryOutcome.Missing, TaskRetryOutcome.NothingToRetry, TaskRetryOutcome.Operate, TaskRetryOutcome.NotRetryable("x"))) assertEquals(false, o.keepsRetry)
        assertEquals(true, TaskRetryOutcome.WrongStatus.refreshes)
        assertEquals(false, TaskRetryOutcome.Offline.refreshes)
    }

    @Test fun cardAction() {
        fun card(st: String, fail: TaskFailAction, operate: Boolean = false, gone: Boolean = false, blocked: Boolean = false) =
            K.cardAction(st, fail, blocked, operate, gone)
        assertEquals(TaskCardAction.Retry, card("failed", ChatReasonRules.taskFailAction("upstream_error", null)))
        assertEquals(TaskCardAction.Topup(pay), card("failed", ChatReasonRules.taskFailAction("insufficient_balance", pay)))
        assertEquals(TaskCardAction.Relaunch, card("failed", TaskFailAction.AsBefore, operate = true))
        assertEquals(TaskCardAction.Relaunch, card("cancelled", TaskFailAction.AsBefore, operate = true))
        for (w in listOf("auth_failed", "bad_request", "unavailable")) assertEquals(TaskCardAction.ViewResult, card("failed", ChatReasonRules.taskFailAction(w, null)))
        assertEquals(TaskCardAction.ViewResult, card("failed", ChatReasonRules.taskFailAction("insufficient_balance", null)))
        assertEquals(TaskCardAction.ViewResult, card("done", TaskFailAction.AsBefore))
        assertEquals(null, card("failed", TaskFailAction.AsBefore, gone = true))
        assertEquals(null, card("failed", TaskFailAction.AsBefore, blocked = true))
        assertEquals(true, K.isOperate("operate"))
        assertEquals(false, K.isOperate(null))
        assertEquals(false, K.isOperate("claude_code"))
        assertEquals("/tasks/abc123/retry", K.retryPath("abc123"))
        assertEquals("/tasks/a%2Fb%3Fc%23d%25e/retry", K.retryPath("a/b?c#d%e"))
        assertEquals("导出封面", K.relaunchText("  导出封面  ", "封面"))
        assertEquals("封面", K.relaunchText(" ", "封面"))
        assertEquals(null, K.relaunchText("", null))
    }

    @Test fun suspend() {
        fun dev(n: String?, id: String? = null) = id to n
        assertEquals("我的 MacBook Pro", K.devicePhrase(listOf(dev("我的 MacBook Pro"))))
        assertEquals("我的 MacBook Pro 等 2 台设备", K.devicePhrase(listOf(dev("我的 MacBook Pro"), dev("办公室"))))
        assertEquals("办公室 等 3 台设备", K.devicePhrase(listOf(dev(""), dev("办公室"), dev(null))))
        assertEquals("能做这一步的设备", K.devicePhrase(emptyList()))
        assertEquals("能做这一步的设备", K.devicePhrase(listOf(dev(""), dev("  "))))
        assertEquals("能做这一步的设备", K.devicePhrase(listOf(dev(null, "mac-1"))))
        assertEquals("图片生成、设计等 3 项", K.capabilityNames(listOf("图片生成", "设计", "写代码")))
        assertEquals(null, K.capabilityNames(emptyList()))
        val info = TaskSuspendInfo(reason = "device_offline", waitingDevices = listOf(dev("我的 MacBook Pro")), step = 3)
        assertEquals("等「我的 MacBook Pro」上线，上线后会问你要不要接着跑", K.suspendCardText("suspended", info))
        assertEquals("等能做这一步的设备上线，上线后会问你要不要接着跑", K.suspendCardText("suspended", TaskSuspendInfo(reason = "device_offline")))
        assertEquals(null, K.suspendCardText("running", info))
        assertEquals(false, K.showsResume("suspended", info))
        val cap = TaskSuspendInfo(reason = "no_capability", missingCapabilities = listOf("image_gen", "design"), labels = listOf("图片生成", "设计"))
        assertEquals("缺能力：图片生成、设计，接上后会问你要不要接着跑", K.suspendCardText("suspended", cap))
        assertEquals("缺一项程序或 Skill，接上后会问你要不要接着跑", K.suspendCardText("suspended", TaskSuspendInfo(reason = "no_capability")))
        val ready = info.copy(resumeReady = true, readyReason = "device_online")
        assertEquals("「我的 MacBook Pro」上线了，可以接着跑了", K.suspendCardText("suspended", ready))
        assertEquals(true, K.showsResume("suspended", ready))
        assertEquals(false, K.showsResume("running", ready))
        assertEquals("需要的能力接上了，可以接着跑了", K.suspendCardText("suspended", cap.copy(resumeReady = true, readyReason = "capability_added")))
        val back = K.afterNotReady(ready)
        assertEquals(false, back.resumeReady)
        assertEquals(false, K.showsResume("suspended", back))
        assertEquals("「我的 MacBook Pro」刚才又离线了，任务还挂着。", TaskResumeOutcome.NotReady.message(ready))
    }

    @Test fun resumeOutcome() {
        assertEquals(TaskResumeOutcome.Ok, K.resumeOutcome(resp(200)))
        assertEquals(TaskResumeOutcome.NotReady, K.resumeOutcome(resp(409, sc, "not_ready", "现在还不能接着跑")))
        assertEquals(TaskResumeOutcome.WrongStatus, K.resumeOutcome(resp(409, sc, "wrong_status")))
        assertEquals(TaskResumeOutcome.Missing, K.resumeOutcome(resp(404, "not_found")))
        assertEquals(TaskResumeOutcome.Offline, K.resumeOutcome(resp(0, detail = "offline")))
        assertEquals(TaskResumeOutcome.ServerError(503), K.resumeOutcome(resp(503)))
        assertEquals(TaskResumeOutcome.Unknown("新的一档"), K.resumeOutcome(resp(409, sc, "brand_new", "新的一档")))
        assertEquals(TaskResumeOutcome.Unknown("HTTP 400"), K.resumeOutcome(resp(400, detail = "HTTP 400")))
    }
}
