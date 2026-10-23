package com.geek.umbra.feature.widget

import com.geek.umbra.model.TaskStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务通知四态与小组件行数（设计侧第 199 封答复第 9、10 条）。Android 独有、iOS 没有对应用例，
 * 输入输出取答复与出图 #9 / #10 里的例子。
 */
class TaskNotifyRulesTest {
    private val R = TaskNotifyRules

    @Test fun phase() {
        assertEquals(TaskNotifyPhase.Running, R.phase("running"))
        assertEquals(TaskNotifyPhase.Awaiting, R.phase("awaiting_confirm"))
        // 挂起 / 待执行不是结束（LiveActivityRules.keepsAlive）。
        assertEquals(TaskNotifyPhase.Hold, R.phase("suspended"))
        assertEquals(TaskNotifyPhase.Hold, R.phase("pending"))
        assertEquals(TaskNotifyPhase.Done, R.phase("done"))
        assertEquals(TaskNotifyPhase.Failed, R.phase("failed"))
        assertEquals(TaskNotifyPhase.Failed, R.phase("error"))
        assertEquals(TaskNotifyPhase.Ended, R.phase("cancelled"))
        assertEquals(TaskNotifyPhase.Ended, R.phase(null))
        assertTrue(R.isLive(TaskNotifyPhase.Awaiting))
        assertFalse(R.isLive(TaskNotifyPhase.Done))
    }

    @Test fun runningBody() {
        // 批次 290 §六：按「已完成几步」，正在做的那一步另起一行。
        assertEquals("已完成 2/8 步\n正在做第 3 步 · 打开价格页并截图", R.runningBody(2, 8, "打开价格页并截图"))
        assertEquals("已完成 2/8 步\n正在做第 3 步", R.runningBody(2, 8, null))
        // 全做完那一刻只剩完成数，不写「正在做第 9 步」。
        assertEquals("已完成 8/8 步", R.runningBody(8, 8, ""))
        // 服务端没给步骤数：不编进度。
        assertEquals("执行中", R.runningBody(0, 0, "打开价格页并截图"))
    }

    @Test fun currentStepTitle() {
        val steps = listOf(
            TaskStep(seq = 2, title = "登录后台", status = "pending"),
            TaskStep(seq = 1, title = "打开价格页并截图", status = "done"),
        )
        assertEquals("登录后台", R.currentStepTitle(steps, 1))
        val withRunning = steps + TaskStep(seq = 3, title = "汇总", status = "running")
        assertEquals("汇总", R.currentStepTitle(withRunning, 1))
        assertEquals(null, R.currentStepTitle(listOf(TaskStep(seq = 1, title = "  ", status = "running")), 0))
    }

    @Test fun chipAndAwaiting() {
        assertEquals("38%", R.chipText(TaskNotifyPhase.Running, 3, 8))
        assertEquals("待确认", R.chipText(TaskNotifyPhase.Awaiting, 4, 8))
        assertEquals("…", R.chipText(TaskNotifyPhase.Running, 0, 0))
        assertEquals("竞品价格表整理 · 等你确认", R.awaitingTitle("竞品价格表整理"))
        assertEquals("用保险箱里的账号吗", R.awaitingBody(" 用保险箱里的账号吗 ", "等你授权"))
        assertEquals("等你授权", R.awaitingBody(null, "等你授权"))
        assertEquals("等你授权", R.awaitingBody("", "等你授权"))
    }

    @Test fun failCopy() {
        assertEquals("第 2 步中断：模型没有响应", R.failTitle(TaskStep(seq = 2, title = "模型没有响应", status = "failed")))
        assertEquals("第 2 步中断：未命名步骤", R.failTitle(TaskStep(seq = 2, status = "failed")))
        assertEquals("任务失败", R.failTitle(null))
        assertEquals("竞品价格表整理 · glm-4v-plus 返回 429", R.failBody("竞品价格表整理", "glm-4v-plus 返回 429"))
        assertEquals("竞品价格表整理", R.failBody("竞品价格表整理", "  "))
        assertEquals("竞品价格表整理", R.failBody("竞品价格表整理", null))
    }

    @Test fun failActions() {
        // 值得再跑一次（限流 / 缺字段按 unknown）：重试任务 + 去看能力。
        assertEquals(listOf(TaskNotifyFailAction.Retry, TaskNotifyFailAction.Caps), R.failActions("failed", "rate_limited", null, null))
        assertEquals(listOf(TaskNotifyFailAction.Retry, TaskNotifyFailAction.Caps), R.failActions("failed", null, null, null))
        // 电脑操作任务换「重新发起」。
        assertEquals(listOf(TaskNotifyFailAction.Relaunch, TaskNotifyFailAction.Caps), R.failActions("failed", "upstream_error", null, "operate"))
        // 欠费且给了地址：只有「去充值」（不给重试类）。
        assertEquals(
            listOf(TaskNotifyFailAction.Topup("https://pay.example.com/topup")),
            R.failActions("failed", "insufficient_balance", "https://pay.example.com/topup", null),
        )
        // 欠费没给地址 / key 错：不给钮。
        assertEquals(emptyList<TaskNotifyFailAction>(), R.failActions("failed", "insufficient_balance", null, null))
        assertEquals(emptyList<TaskNotifyFailAction>(), R.failActions("failed", "auth_failed", null, null))
        // javascript: 地址不收。
        assertEquals(emptyList<TaskNotifyFailAction>(), R.failActions("failed", "need_membership", "javascript:alert(1)", null))
    }

    @Test fun elapsed() {
        assertEquals("4分08秒", R.elapsedText(248_000))
        assertEquals("1分12秒", R.elapsedText(72_999))
        assertEquals("59秒", R.elapsedText(59_000))
        assertEquals("0秒", R.elapsedText(-5_000))
        assertEquals("1分00秒", R.elapsedText(60_000))
        assertEquals("1小时0分", R.elapsedText(3_600_000))
        assertEquals("2小时5分", R.elapsedText(2 * 3_600_000L + 5 * 60_000 + 59_000))
        assertEquals("全部完成 · 耗时 4分08秒", R.doneBody(1_000, 249_000))
        assertEquals("全部完成", R.doneBody(null, 249_000))
    }

    @Test fun postsDone() {
        assertFalse(R.postsDone(foreground = true, showingThisDetail = true))
        assertTrue(R.postsDone(foreground = false, showingThisDetail = true))
        assertTrue(R.postsDone(foreground = true, showingThisDetail = false))
    }

    @Test fun reminderSlots() {
        // 4×2 的矮格子也至少 3 个行位（2 行 + 收尾）。
        assertEquals(3, R.reminderSlots(110f, 28f, 16f, 27f))
        assertEquals(4, R.reminderSlots(160f, 28f, 16f, 29f))
        // 再高也不超过 7（6 行 + 收尾）。
        assertEquals(7, R.reminderSlots(600f, 28f, 16f, 29f))
    }

    @Test fun reminderRows() {
        assertEquals(0 to 0, R.reminderRows(0, 3))
        assertEquals(2 to 0, R.reminderRows(2, 3))
        // 正好 3 条：第三行直接显示，不写「还有 1 条」。
        assertEquals(3 to 0, R.reminderRows(3, 3))
        assertEquals(2 to 3, R.reminderRows(5, 3))
        assertEquals(5 to 0, R.reminderRows(5, 5))
        assertEquals(4 to 2, R.reminderRows(6, 5))
        // 4×4：最多 6 行。
        assertEquals(6 to 0, R.reminderRows(6, 7))
        assertEquals(6 to 2, R.reminderRows(8, 7))
    }

    /** 设计侧批次 201 第 7 条定稿：已取消正文带步数；步数拿不到不编「第 ?/? 步」；本机停的记号只取一次。 */
    @org.junit.Test fun cancelledAndLocalStop() {
        org.junit.Assert.assertEquals("已取消，已完成 3/8 步。做完的部分留在任务详情里", TaskNotifyRules.cancelledBody(3, 8))
        org.junit.Assert.assertEquals("已取消。做完的部分留在任务详情里", TaskNotifyRules.cancelledBody(null, 8))
        org.junit.Assert.assertEquals("已取消，已完成 8/8 步。做完的部分留在任务详情里", TaskNotifyRules.cancelledBody(9, 8))
        TaskLocalStops.mark("t1")
        org.junit.Assert.assertTrue(TaskLocalStops.take("t1"))
        org.junit.Assert.assertFalse(TaskLocalStops.take("t1"))
    }
}
