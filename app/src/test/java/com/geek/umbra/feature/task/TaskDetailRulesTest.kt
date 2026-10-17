package com.geek.umbra.feature.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 输入输出照抄 iOS Tools/swifttests/TaskDetailRulesTests.swift。 */
class TaskDetailRulesTest {
    private val R = TaskDetailRules

    @Test fun missOnly404IsNotFound() {
        assertEquals(TaskDetailMiss.NotFound, R.miss(404))
        assertEquals(TaskDetailMiss.Failed, R.miss(null))
        assertEquals(TaskDetailMiss.Failed, R.miss(500))
        assertEquals(TaskDetailMiss.Failed, R.miss(503))
        assertEquals(TaskDetailMiss.Failed, R.miss(401))
        assertEquals("200 但解不出来 → 暂时拿不到", TaskDetailMiss.Failed, R.miss(200))
    }

    @Test fun phase() {
        assertEquals(TaskDetailPhase.Skeleton, R.phase(false, null))
        assertEquals("首拉失败 → 错误卡，不是永远的骨架", TaskDetailPhase.Failed, R.phase(false, TaskDetailMiss.Failed))
        assertEquals(TaskDetailPhase.NotFound, R.phase(false, TaskDetailMiss.NotFound))
        assertEquals(TaskDetailPhase.Content, R.phase(true, null))
        assertEquals("轮询偶尔失败 → 留着内容", TaskDetailPhase.Content, R.phase(true, TaskDetailMiss.Failed))
        assertEquals("看着看着被删了 → 已经不在了", TaskDetailPhase.NotFound, R.phase(true, TaskDetailMiss.NotFound))
    }

    @Test fun keepPolling() {
        assertTrue(R.keepPolling(null, TaskDetailMiss.Failed))
        assertFalse(R.keepPolling(null, TaskDetailMiss.NotFound))
        assertFalse(R.keepPolling("running", TaskDetailMiss.NotFound))
        for (s in listOf("running", "pending", "suspended")) {
            assertTrue(R.keepPolling(s, null)); assertTrue(R.keepPolling(s, TaskDetailMiss.Failed)); assertTrue(R.isActive(s))
        }
        for (s in listOf("done", "failed", "cancelled")) {
            assertFalse(R.keepPolling(s, null)); assertFalse(R.isActive(s))
        }
        assertEquals(3.5, R.POLL_SECONDS, 0.0)
        assertEquals(3_500L, R.POLL_MS)
    }

    @Test fun lateResponses() {
        assertTrue(R.acceptsResponse(true, 0, 0))
        assertFalse(R.acceptsResponse(false, 0, 1))
        assertFalse(R.acceptsResponse(false, 1, 1))
        assertFalse(R.acceptsResponse(true, 0, 1))
        // 按 VM 的用法串一遍：发出时取代次、关页 +1、回来时比。
        val open = mutableSetOf("t1"); val epoch = mutableMapOf<String, Int>()
        val sent = epoch["t1"] ?: 0
        open.remove("t1"); epoch["t1"] = (epoch["t1"] ?: 0) + 1
        assertFalse("在飞时关页 → 回应丢掉", R.acceptsResponse("t1" in open, sent, epoch["t1"] ?: 0))
        open.add("t1")
        assertFalse("关了又开 → 旧回应仍丢掉", R.acceptsResponse("t1" in open, sent, epoch["t1"] ?: 0))
        val sent2 = epoch["t1"] ?: 0
        assertTrue("新页自己那一次 → 收", R.acceptsResponse("t1" in open, sent2, epoch["t1"] ?: 0))
    }

    @Test fun dropsResumeFail() {
        for (s in listOf("running", "pending", "done", "failed", "cancelled")) assertTrue(R.dropsResumeFail(s))
        assertFalse(R.dropsResumeFail("suspended"))
        assertFalse(R.dropsResumeFail(null))
    }

    @Test fun channelLabel() {
        assertNull(R.channelLabel(null))
        assertNull(R.channelLabel(""))
        assertNull(R.channelLabel("  \n"))
        assertEquals("feishu", R.channelLabel("feishu"))
        assertEquals("ios", R.channelLabel(" ios "))
    }

    @Test fun screenPhase() {
        assertEquals(ScreenPhase.Content(false), ScreenPhase.resolve(true, true, false, false))
        assertEquals(ScreenPhase.Skeleton, ScreenPhase.resolve(false, true, false, false))
        assertEquals(ScreenPhase.Failed(true), ScreenPhase.resolve(false, false, false, true))
        assertEquals("拉完确实是空的 → 内容（页面画空态）", ScreenPhase.Content(false), ScreenPhase.resolve(false, false, true, false))
    }

    @Test fun statusMapping() {
        assertEquals(TaskStatusKind.Done, taskStatusOf("succeeded"))
        assertEquals(TaskStatusKind.Failed, taskStatusOf("error"))
        assertEquals(TaskStatusKind.AwaitingReview, taskStatusOf("awaiting_confirm"))
        assertEquals(TaskStatusKind.Suspended, taskStatusOf("paused"))
        assertEquals(TaskStatusKind.Pending, taskStatusOf("queued"))
        assertEquals(TaskStatusKind.Cancelled, taskStatusOf("stopped"))
        assertEquals(TaskStatusKind.Running, taskStatusOf("whatever"))
    }

    @Test fun time() {
        val z = java.time.ZoneOffset.UTC
        val now = java.time.Instant.parse("2026-10-17T12:00:00Z")
        assertEquals("09:41", TaskTime.relative("2026-10-17T09:41:00Z", z, now))
        assertEquals("SQLite 形状按 UTC", "09:41", TaskTime.relative("2026-10-17 09:41:00", z, now))
        assertEquals("Python isoformat 6 位小数 + 偏移", "09:41", TaskTime.relative("2026-10-17T09:41:00.123456+00:00", z, now))
        assertEquals("昨天", TaskTime.relative("2026-10-16T23:00:00Z", z, now))
        assertEquals("7月28日", TaskTime.relative("2026-07-28T09:41:00Z", z, now))
        assertEquals("7月28日 09:41", TaskTime.absolute("2026-07-28T09:41:00Z", z, now))
        assertEquals("2025年7月28日 09:41", TaskTime.absolute("2025-07-28T09:41:00Z", z, now))
        assertEquals("解析不了原样返回", "刚才", TaskTime.relative("刚才", z, now))
        assertEquals("—", TaskTime.absolute(null, z, now))
    }
}
