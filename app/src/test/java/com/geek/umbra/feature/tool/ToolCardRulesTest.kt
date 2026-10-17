package com.geek.umbra.feature.tool

import com.geek.umbra.feature.tool.ToolCardRules.Load
import org.junit.Assert.assertEquals
import org.junit.Test

/** 照抄 iOS Tools/swifttests/ToolCardRulesTests.swift 的取值。 */
class ToolCardRulesTest {
    @Test fun loadTiers() {
        assertEquals(Load.Loaded, ToolCardRules.load(loadedOnce = true, firstLoading = true))
        assertEquals(Load.Loading, ToolCardRules.load(false, true))
        assertEquals(Load.Failed, ToolCardRules.load(false, false))
    }

    @Test fun taskLine() {
        assertEquals("—", ToolCardRules.taskSub(Load.Loading, 0, 0, 0, 0, 30))
        assertEquals("没拉到，下拉重试", ToolCardRules.taskSub(Load.Failed, 0, 0, 0, 0, 30))
        assertEquals("还没有任务", ToolCardRules.taskSub(Load.Loaded, 0, 0, 0, 0, 30))
        assertEquals("1 个执行中 · 2 个失败", ToolCardRules.taskSub(Load.Loaded, 5, 1, 2, 0, 30))
        assertEquals("最近 30 个：1 个执行中 · 0 个失败 · 3 个可接着跑", ToolCardRules.taskSub(Load.Loaded, 30, 1, 0, 3, 30))
    }

    @Test fun others() {
        assertEquals("还没有灵感", ToolCardRules.inspSub(Load.Loaded, 0, 0))
        assertEquals("4 条 · 2 条待整理", ToolCardRules.inspSub(Load.Loaded, 4, 2))
        assertEquals("—", ToolCardRules.phraseSub(0, everSynced = false))
        assertEquals("还没有常用语", ToolCardRules.phraseSub(0, everSynced = true))
        assertEquals("3 条 · 跨端同步", ToolCardRules.phraseSub(3, false))
        assertEquals("7 条 · 已解锁", ToolCardRules.vaultSub(true, 7, true))
        assertEquals("已锁定", ToolCardRules.vaultSub(false, 0, true))
        assertEquals("—", ToolCardRules.vaultSub(false, 0, false))
        assertEquals("—", ToolCardRules.moneySub("生活账本", null, "¥1"))
        assertEquals("生活账本 · 10月 ¥12.00", ToolCardRules.moneySub("生活账本", "10月", "¥12.00"))
        assertEquals("10月 ¥12.00", ToolCardRules.moneySub(null, "10月", "¥12.00"))
    }
}
