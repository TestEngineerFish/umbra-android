package com.geek.umbra.feature.task

import org.junit.Assert.assertEquals
import org.junit.Test

/** 补救完能接着跑（PC `retryAfterFix`，批次 278）：额度用完用了重置卡、开了会员、充了值之后，「重试任务」要在。 */
class TaskRetryFixTest {
    @Test fun retryAfterFix() {
        for (k in listOf("quota_exhausted", "need_membership", "insufficient_balance")) assertEquals(k, true, TaskRetryRules.retryAfterFix(k))
        for (k in listOf("auth_failed", "bad_request", "unavailable", "rate_limited", "unknown", null)) assertEquals("$k", false, TaskRetryRules.retryAfterFix(k))
    }

    @Test fun buttonForFixable() {
        val topup = TaskFailAction.Topup("https://x")
        assertEquals("额度用完失败：给重试", TaskRetryButton.Retry, TaskRetryRules.button("failed", topup, blocked = false, operate = false, fixable = true))
        assertEquals("不是补救类：照旧不给", null, TaskRetryRules.button("failed", topup, blocked = false, operate = false))
        assertEquals("上一次重试已被说死：不给", null, TaskRetryRules.button("failed", topup, blocked = true, operate = false, fixable = true))
        assertEquals("电脑操作：从头再来", TaskRetryButton.Relaunch, TaskRetryRules.button("failed", TaskFailAction.NoRetry, blocked = false, operate = true, fixable = true))
    }
}
