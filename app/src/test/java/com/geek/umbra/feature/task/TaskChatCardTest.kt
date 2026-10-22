package com.geek.umbra.feature.task

import com.geek.umbra.model.TaskWaitingDevice
import com.geek.umbra.net.ReasonRules
import com.geek.umbra.net.TaskFailAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 聊天任务卡用到的那几组（照抄 iOS Tools/swifttests/TaskRetryRulesTests.swift / TaskSuspendRulesTests.swift）。
 * 从前聊天包另有一份 ChatTaskKit，这些用例是对着它写的；合并后改对着任务包的同一份判据跑，确认聊天卡上的行为没变。
 */
class TaskChatCardTest {
    private val R = TaskRetryRules
    private val S = TaskSuspendRules
    private fun resp(s: Int, code: String? = null, state: String? = null, detail: String = "") = TaskRetryResponse(s, code, state, detail)
    private val sc = "state_conflict"
    private val pay = "https://pay.example.com/topup"

    @Test fun retryOutcome() {
        assertEquals(TaskRetryOutcome.Ok, R.outcome(resp(200)))
        assertEquals(TaskRetryOutcome.Missing, R.outcome(resp(404, "not_found")))
        assertEquals(TaskRetryOutcome.Missing, R.outcome(resp(409, sc, "missing")))
        assertEquals(TaskRetryOutcome.WrongStatus, R.outcome(resp(409, sc, "wrong_status")))
        assertEquals(TaskRetryOutcome.NothingToRetry, R.outcome(resp(409, sc, "nothing_to_retry")))
        assertEquals(TaskRetryOutcome.Operate, R.outcome(resp(409, sc, "operate")))
        assertEquals(TaskRetryOutcome.NotRetryable("引擎拒绝"), R.outcome(resp(409, sc, "not_retryable", "引擎拒绝")))
        assertEquals(TaskRetryOutcome.Unknown("新的一档"), R.outcome(resp(409, sc, "brand_new_state", "新的一档")))
        assertEquals(TaskRetryOutcome.Offline, R.outcome(resp(0, detail = "offline")))
        assertEquals(TaskRetryOutcome.ServerError(502), R.outcome(resp(502, detail = "HTTP 502")))
        assertEquals(TaskRetryOutcome.ServerError(500), R.outcome(resp(500)))
        assertEquals(TaskRetryOutcome.Unknown("HTTP 409"), R.outcome(resp(409, "other_code", "operate", "HTTP 409")))
        assertEquals(TaskRetryOutcome.Unknown("HTTP 400"), R.outcome(resp(400, detail = "HTTP 400")))
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
            R.cardAction(st, fail, blocked, operate, gone)
        assertEquals(TaskCardAction.Retry, card("failed", ReasonRules.taskFailAction("upstream_error", null)))
        assertEquals(TaskCardAction.Topup(pay), card("failed", ReasonRules.taskFailAction("insufficient_balance", pay)))
        assertEquals(TaskCardAction.Relaunch, card("failed", TaskFailAction.AsBefore, operate = true))
        assertEquals(TaskCardAction.Relaunch, card("cancelled", TaskFailAction.AsBefore, operate = true))
        for (w in listOf("auth_failed", "bad_request", "unavailable")) assertEquals(TaskCardAction.ViewResult, card("failed", ReasonRules.taskFailAction(w, null)))
        assertEquals(TaskCardAction.ViewResult, card("failed", ReasonRules.taskFailAction("insufficient_balance", null)))
        assertEquals(TaskCardAction.ViewResult, card("done", TaskFailAction.AsBefore))
        assertEquals(null, card("failed", TaskFailAction.AsBefore, gone = true))
        assertEquals(null, card("failed", TaskFailAction.AsBefore, blocked = true))
        assertEquals(true, R.isOperate("operate"))
        assertEquals(false, R.isOperate(null))
        assertEquals(false, R.isOperate("claude_code"))
        assertEquals("/tasks/abc123/retry", R.retryPath("abc123"))
        assertEquals("/tasks/a%2Fb%3Fc%23d%25e/retry", R.retryPath("a/b?c#d%e"))
        assertEquals("导出封面", R.relaunchText("  导出封面  ", "封面"))
        assertEquals("封面", R.relaunchText(" ", "封面"))
        assertEquals(null, R.relaunchText("", null))
    }

    @Test fun suspend() {
        fun dev(n: String?, id: String = "") = TaskWaitingDevice(id, n)
        assertEquals("我的 MacBook Pro", S.devicePhrase(listOf(dev("我的 MacBook Pro"))))
        assertEquals("我的 MacBook Pro 等 2 台设备", S.devicePhrase(listOf(dev("我的 MacBook Pro"), dev("办公室"))))
        assertEquals("办公室 等 3 台设备", S.devicePhrase(listOf(dev(""), dev("办公室"), dev(null))))
        assertEquals("能做这一步的设备", S.devicePhrase(emptyList()))
        assertEquals("能做这一步的设备", S.devicePhrase(listOf(dev(""), dev("  "))))
        assertEquals("能做这一步的设备", S.devicePhrase(listOf(dev(null, "mac-1"))))
        assertEquals("图片生成、设计等 3 项", S.capabilityNames(listOf("图片生成", "设计", "写代码")))
        assertEquals(null, S.capabilityNames(emptyList()))
        val info = TaskSuspendInfo(reason = "device_offline", waitingDevices = listOf(dev("我的 MacBook Pro")), step = 3)
        assertEquals("等「我的 MacBook Pro」上线，上线后会问你要不要接着跑", S.display("suspended", info)?.cardText)
        assertEquals("等能做这一步的设备上线，上线后会问你要不要接着跑", S.display("suspended", TaskSuspendInfo(reason = "device_offline"))?.cardText)
        assertEquals(null, S.display("running", info)?.cardText)
        assertEquals(false, S.showsResume("suspended", info))
        val cap = TaskSuspendInfo(reason = "no_capability", missingCapabilities = listOf("image_gen", "design"), labels = listOf("图片生成", "设计"))
        assertEquals("缺能力：图片生成、设计，接上后会问你要不要接着跑", S.display("suspended", cap)?.cardText)
        assertEquals("缺一项程序或 Skill，接上后会问你要不要接着跑", S.display("suspended", TaskSuspendInfo(reason = "no_capability"))?.cardText)
        val ready = info.copy(resumeReady = true, readyReason = "device_online")
        assertEquals("「我的 MacBook Pro」上线了，可以接着跑了", S.display("suspended", ready)?.cardText)
        assertEquals(true, S.showsResume("suspended", ready))
        assertEquals(false, S.showsResume("running", ready))
        assertEquals("需要的能力接上了，可以接着跑了", S.display("suspended", cap.copy(resumeReady = true, readyReason = "capability_added"))?.cardText)
        val back = S.afterNotReady(ready)
        assertEquals(false, back.resumeReady)
        assertEquals(false, S.showsResume("suspended", back))
        assertEquals("「我的 MacBook Pro」刚才又离线了，任务还挂着。", TaskResumeOutcome.NotReady.message(ready))
    }

    @Test fun resumeOutcome() {
        assertEquals(TaskResumeOutcome.Ok, S.outcome(resp(200)))
        assertEquals(TaskResumeOutcome.NotReady, S.outcome(resp(409, sc, "not_ready", "现在还不能接着跑")))
        assertEquals(TaskResumeOutcome.WrongStatus, S.outcome(resp(409, sc, "wrong_status")))
        assertEquals(TaskResumeOutcome.Missing, S.outcome(resp(404, "not_found")))
        assertEquals(TaskResumeOutcome.Offline, S.outcome(resp(0, detail = "offline")))
        assertEquals(TaskResumeOutcome.ServerError(503), S.outcome(resp(503)))
        assertEquals(TaskResumeOutcome.Unknown("新的一档"), S.outcome(resp(409, sc, "brand_new", "新的一档")))
        assertEquals(TaskResumeOutcome.Unknown("HTTP 400"), S.outcome(resp(400, detail = "HTTP 400")))
    }
}
