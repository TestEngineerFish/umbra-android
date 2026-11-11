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
        assertEquals("停在第 2 步「建工作流」", TaskUnfinishedRules.stoppedAt(2, "建工作流"))
        assertEquals("停在第 2 步", TaskUnfinishedRules.stoppedAt(2, " "))
        assertNull(TaskUnfinishedRules.stoppedAt(0, "x"))
        assertNull(TaskUnfinishedRules.nextStep("  "))
        assertEquals("下一步：先登录", TaskUnfinishedRules.nextLine(TaskUnfinishedRules.nextStep(" 先登录 ")!!))
        assertEquals("没做成：表格里没有这一列", TaskUnfinishedRules.listSub("没做成：表格里没有这一列"))
        assertEquals("没做成", TaskUnfinishedRules.listSub(null))
        assertEquals("没做成：新建 pw 随机密码工作流", TaskUnfinishedRules.notifyTitle("新建 pw 随机密码工作流"))
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
}
