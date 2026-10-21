package com.geek.umbra.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** 聊天任务卡补救类失败多给一颗「重试任务」（PC 聊天任务卡 retryHere / failCardActions，批次 278）。 */
class ChatTaskFixRetryTest {
    private val K = ChatTaskKit

    @Test fun addsRetryForFixableFailures() {
        for (k in listOf("quota_exhausted", "need_membership", "insufficient_balance")) {
            assertEquals("$k + 去买：加重试", true, K.cardFixRetry("failed", k, blocked = false, gone = false, base = TaskCardAction.Topup("https://x")))
            assertEquals("$k + 查看结果：加重试", true, K.cardFixRetry("failed", k, blocked = false, gone = false, base = TaskCardAction.ViewResult))
        }
    }

    @Test fun noDuplicateOrDeadRetry() {
        assertEquals("原来那颗已经是重试：不再加", false, K.cardFixRetry("failed", "quota_exhausted", false, false, TaskCardAction.Retry))
        assertEquals("电脑操作是重新发起：不再加", false, K.cardFixRetry("failed", "quota_exhausted", false, false, TaskCardAction.Relaunch))
        assertEquals("上一次重试被说死：不加", false, K.cardFixRetry("failed", "quota_exhausted", true, false, TaskCardAction.ViewResult))
        assertEquals("任务已经不在：不加", false, K.cardFixRetry("failed", "quota_exhausted", false, true, null))
        assertEquals("不是补救类：不加", false, K.cardFixRetry("failed", "auth_failed", false, false, TaskCardAction.ViewResult))
        assertEquals("没失败：不加", false, K.cardFixRetry("running", "quota_exhausted", false, false, TaskCardAction.ViewResult))
        assertEquals("取消的不算补救类", false, K.cardFixRetry("cancelled", "quota_exhausted", false, false, TaskCardAction.ViewResult))
    }
}
