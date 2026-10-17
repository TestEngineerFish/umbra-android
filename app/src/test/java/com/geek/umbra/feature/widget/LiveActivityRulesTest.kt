package com.geek.umbra.feature.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 输入输出照抄 iOS Tools/swifttests/LiveActivityRulesTests.swift（umbra-ios#22 / #23）。 */
class LiveActivityRulesTest {
    private val R = LiveActivityRules

    @Test fun adopt() {
        // #22 原型：杀掉 App 再开，系统里还挂着一条跟 T1 的 —— 必须认回来，不许再开一条。
        assertEquals("a1" to emptyList<String>(), R.adopt(listOf("a1" to "T1"), "T1"))
        // 历史上已经叠过：留跟当前任务的那条，其余结束。
        val many = R.adopt(listOf("a1" to "T0", "a2" to "T1", "a3" to "T1"), "T1")
        assertEquals("a2", many.first)
        assertEquals(setOf("a1", "a3"), many.second.toSet())
        // 没有执行中任务：留一条交给 sync 推收尾。
        assertEquals("a1" to listOf("a2"), R.adopt(listOf("a1" to "T1", "a2" to "T2"), null))
        // 跟的是别的任务：留着，让 sync 走「换了任务」那一支。
        assertEquals("a1" to emptyList<String>(), R.adopt(listOf("a1" to "T0"), "T1"))
        val empty = R.adopt(emptyList(), "T1")
        assertNull(empty.first); assertTrue(empty.second.isEmpty())
    }

    @Test fun finalFrame() {
        assertEquals("任务完成" to false, R.finalFrame("done"))
        assertEquals("任务失败" to true, R.finalFrame("failed"))
        // 原来被取消的任务以绿勾 +「任务完成」收尾。
        assertEquals("任务已取消" to true, R.finalFrame("cancelled"))
        val gone = R.finalFrame(null)
        assertTrue(gone.second); assertNotEquals("任务完成", gone.first)
        for (st in listOf("failed", "cancelled", "running", "queued", "paused", "")) assertTrue("「$st」拿到了绿勾", R.finalFrame(st).second)
    }

    @Test fun keepsAlive() {
        assertTrue("挂起的任务被收尾了", R.keepsAlive("suspended"))
        assertTrue("待执行的任务被收尾了", R.keepsAlive("pending"))
        for (st in listOf("done", "failed", "cancelled")) assertFalse("终态「$st」没收尾", R.keepsAlive(st))
        assertFalse(R.keepsAlive(null))
        for (st in listOf("", "paused", "queued", "SUSPENDED", "weird_new_state")) assertFalse("不认识的「$st」被留着", R.keepsAlive(st))
        assertEquals(setOf("pending", "suspended"), R.HOLD_STATUSES)
    }
}
