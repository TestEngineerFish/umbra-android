package com.geek.umbra.feature.task

import com.geek.umbra.feature.widget.TaskNotifyRules
import com.geek.umbra.model.StepError
import com.geek.umbra.net.UmbraJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 设计侧 320「任务没做成」的判据与字面；服务端形状照 #175 tasks_engine._fail_unfinished。
class TaskUnfinishedRulesTest {
    @Test fun onlyFailedPlusUnfinished() {
        assertTrue(TaskUnfinishedRules.isUnfinished("failed", "unfinished"))
        assertFalse(TaskUnfinishedRules.isUnfinished("failed", "unknown"))
        assertFalse(TaskUnfinishedRules.isUnfinished("failed", null))
        assertFalse(TaskUnfinishedRules.isUnfinished("done", "unfinished"))
    }

    @Test fun reasonDropsPrefixes() {
        assertEquals("任务里调不到「工作流」这个秘书能力。", TaskUnfinishedRules.reason("没做成：任务里调不到「工作流」这个秘书能力。"))
        assertEquals("调不到", TaskUnfinishedRules.reason("服务器说：没做成: 调不到"))
        // 只剥开头，正文里的不动
        assertEquals("原因是没做成：别的", TaskUnfinishedRules.reason("原因是没做成：别的"))
        assertNull(TaskUnfinishedRules.reason("没做成："))
        assertNull(TaskUnfinishedRules.reason("  "))
    }

    @Test fun reasonPrefersFullDetail() {
        // 服务端 message 截到 200、detail 是全文：同一句话只取一份，detail 优先
        assertEquals("全文", TaskUnfinishedRules.reasonOf("截断", "全文", "没做成：摘要"))
        assertEquals("截断", TaskUnfinishedRules.reasonOf("截断", "", "没做成：摘要"))
        assertEquals("摘要", TaskUnfinishedRules.reasonOf(null, null, "没做成：摘要"))
    }

    @Test fun metaNextListNotify() {
        assertEquals("停在第 2 步「建工作流」", TaskUnfinishedRules.stoppedAt(2, "建工作流", false))
        assertEquals("停在第 2 步", TaskUnfinishedRules.stoppedAt(2, " ", false))
        assertNull(TaskUnfinishedRules.stoppedAt(0, "x", false))
        assertNull(TaskUnfinishedRules.nextStep("  "))
        assertEquals("下一步：先登录", TaskUnfinishedRules.nextLine(TaskUnfinishedRules.nextStep(" 先登录 ")!!, false))
        assertEquals("没做成：表格里没有这一列", TaskUnfinishedRules.listSub("没做成：表格里没有这一列", false))
        assertNull(TaskUnfinishedRules.listSub(null, false))
        assertEquals("没做成：新建 pw 随机密码工作流", TaskUnfinishedRules.notifyTitle("新建 pw 随机密码工作流", false))
    }

    @Test fun notifyHasNoButtons() {
        assertEquals(emptyList<Any>(), TaskNotifyRules.failActions("failed", "unfinished", null, null))
        assertEquals(emptyList<Any>(), TaskNotifyRules.failActions("failed", "unfinished", null, "operate"))
        // 别的失败不变
        assertTrue(TaskNotifyRules.failActions("failed", "unknown", null, null).isNotEmpty())
    }

    @Test fun decodesNextStep() {
        val e = UmbraJson.decodeFromString(StepError.serializer(),
            """{"kind":"unfinished","message":"m","detail":"d","reason_kind":"unfinished","next_step":"先登录"}""")
        assertEquals("先登录", e.next_step)
        assertNull(UmbraJson.decodeFromString(StepError.serializer(), """{"kind":"x"}""").next_step)
    }

    @Test fun sayAndEcho() {
        assertEquals("帮我建一个 pw 工作流", TaskUnfinishedRules.say(" 帮我建一个 pw 工作流 "))
        assertNull(TaskUnfinishedRules.say(""))
        // 只带 task_id 的是复述；「要接着跑吗？」那条带 action，不算
        assertEquals("t1", TaskUnfinishedRules.echoTaskId(null, "t1"))
        assertNull(TaskUnfinishedRules.echoTaskId("resume_task", "t1"))
        assertNull(TaskUnfinishedRules.echoTaskId(null, ""))
        assertEquals("新建 pw 随机密码工作流", TaskUnfinishedRules.echoName("❌ 「新建 pw 随机密码工作流」没做成：调不到\n下一步：…"))
        assertEquals("x", TaskUnfinishedRules.echoName("❌「x」没做成：y"))
        assertNull(TaskUnfinishedRules.echoName("好的，「x」没做成也没关系"))
        assertEquals("没做成：x", TaskUnfinishedRules.previewText("x", false))
    }

    @Test fun echoHiddenOnlyWhenCardInConversation() {
        val card = com.geek.umbra.feature.chat.ChatBlock.Task(taskId = "t1", goal = "g", pct = 50, status = "failed", message = "")
        val echo = com.geek.umbra.feature.chat.ChatBlock.Assistant(text = "❌ 「g」没做成：r", echoOfTask = "t1")
        val other = com.geek.umbra.feature.chat.ChatBlock.Assistant(text = "❌ 「h」没做成：r", echoOfTask = "t2")
        val plain = com.geek.umbra.feature.chat.ChatBlock.Assistant(text = "hi")
        assertEquals(listOf(card, other, plain), com.geek.umbra.feature.chat.ChatEchoRules.visible(listOf(card, echo, other, plain)))
        // 卡不在（重进会话、历史里没有卡）：照常画
        assertEquals(listOf(echo, plain), com.geek.umbra.feature.chat.ChatEchoRules.visible(listOf(echo, plain)))
    }

    @Test fun decodesNextAction() {
        val e = UmbraJson.decodeFromString(StepError.serializer(),
            """{"kind":"unfinished","reason_kind":"unfinished","next_step":"s","next_action":{"say":"帮我建一个 pw 工作流"}}""")
        assertEquals("帮我建一个 pw 工作流", e.next_action?.say)
    }

    // 英文整套与 iOS TaskUnfinishedRulesTests（3824824）同一组输入输出（设计侧 322 §4）。
    @Test fun englishMatchesIos() {
        val R = TaskUnfinishedRules
        assertEquals("Stopped at step 2 \u201C建工作流\u201D", R.stoppedAt(2, "建工作流", true))
        assertEquals("Stopped at step 2", R.stoppedAt(2, "", true))
        assertEquals("Couldn't be done: 缺一项", R.listSub("没做成：缺一项", true))
        assertEquals("Couldn't be done: pw", R.previewText("pw", true))
        assertEquals("Couldn't be done: pw", R.notifyTitle("pw", true))
        assertEquals("Couldn't be done", TaskUnfinishedCopy.title(true))
        assertEquals("Next step", TaskUnfinishedCopy.nextTitle(true))
        assertEquals("第 2 步", R.stoppedCell(2, false))
        assertEquals("Step 2", R.stoppedCell(2, true))
        assertNull(R.stoppedCell(null, false))
        assertEquals("停在", TaskUnfinishedCopy.stoppedLabel(false))
        assertEquals("Stopped at", TaskUnfinishedCopy.stoppedLabel(true))
        assertEquals("下一步：", TaskUnfinishedCopy.nextPrefix(false))
        assertEquals("Next step: ", TaskUnfinishedCopy.nextPrefix(true))
        assertEquals("在聊天里说这句", TaskUnfinishedCopy.sayInChat(false))
        assertEquals("Say this in chat", TaskUnfinishedCopy.sayInChat(true))
        assertEquals("没做成：新建 pw 随机密码工作流", R.previewText(" 新建 pw 随机密码工作流 ", false))
        // 叠着的前缀剥干净、列表副行只留一层
        assertEquals("缺一项", R.reason("没做成：服务器说：缺一项"))
        assertEquals("没做成：缺一项", R.listSub("没做成：没做成：缺一项", false))
        assertTrue(R.isUnfinished("error", "unfinished"))
    }
}
