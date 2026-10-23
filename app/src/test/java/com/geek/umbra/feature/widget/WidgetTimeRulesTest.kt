package com.geek.umbra.feature.widget

import com.geek.umbra.model.TaskItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 输入输出照抄 iOS Tools/swifttests/WidgetSnapshotTests.swift（umbra-ios#85）：写入 10/15 22:00，渲染次日 / 第三天。
 * 「旧快照 JSON 解得出来」那一条 Android 没有（提醒不落快照文件），换成任务快照 JSON 缺键也解得出来。
 */
class WidgetTimeRulesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun t(d: Int, h: Int, m: Int = 0): Long = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private val R = WidgetTimeRules
    private val write = t(15, 22)
    private val sources = listOf(
        WidgetTimeRules.ReminderSource("weekly", "交周报", t(15, 23), false, emptyList()),
        WidgetTimeRules.ReminderSource("tomorrow", "开会", t(16, 10), false, emptyList()),
        WidgetTimeRules.ReminderSource("far", "体检", t(20, 9), false, emptyList()),
        WidgetTimeRules.ReminderSource("old", "交房租", t(14, 9), false, emptyList()),
        // 每天 08:00，已推进到 10/16 08:00；upcoming 从 10/16 08:00 起。
        WidgetTimeRules.ReminderSource("daily", "吃药", t(16, 8), true, listOf(t(16, 8), t(17, 8), t(18, 8))),
    )
    private val rows = R.snapshotRows(sources, write, zone)

    @Test fun snapshotRows() {
        assertEquals("快照放了哪几行", listOf("old", "weekly", "daily", "tomorrow"), rows.map { it.id })
        assertEquals("重复提醒只带窗口里的后续", listOf(t(17, 8)), rows.first { it.id == "daily" }.later)
        assertEquals("窗口外还有下一次没记下", true, rows.first { it.id == "daily" }.continues)
    }

    @Test fun visibleAtWrite() {
        val atWrite = R.visible(rows, write, zone)
        assertEquals("22:00 那一帧", listOf("old", "weekly"), atWrite.map { it.id })
        assertEquals(listOf(true, false), atWrite.map { it.overdue })
        assertEquals("时刻按时区", listOf("09:00", "23:00"), atWrite.map { it.time })
    }

    @Test fun nextMorning() {
        val next9 = R.visible(rows, t(16, 9), zone)
        assertEquals("次日 09:00：昨晚那条、今天 10:00 那条都该在", listOf("old", "weekly", "tomorrow"), next9.map { it.id })
        assertEquals(true, next9.first { it.id == "weekly" }.overdue)
        assertEquals(false, next9.first { it.id == "tomorrow" }.overdue)
        assertFalse("过了点的重复提醒没跟着推进到下一次", next9.any { it.id == "daily" })
        assertEquals("次日 07:00 该画今天 08:00 那次", "08:00", R.visible(rows, t(16, 7), zone).first { it.id == "daily" }.time)
        val day3 = R.visible(rows, t(17, 7), zone)
        assertEquals("第三天 07:00", listOf("old", "weekly", "tomorrow", "daily"), day3.map { it.id })
        assertEquals(listOf(true, true, true, false), day3.map { it.overdue })
        assertFalse("重复提醒窗口用完后被画成了已过期", R.visible(rows, t(17, 9), zone).any { it.id == "daily" })
    }

    @Test fun endedRepeatAndLimitsAndLegacy() {
        val ended = R.snapshotRows(listOf(WidgetTimeRules.ReminderSource("e", "x", t(15, 8), true, emptyList())), write, zone)
        assertEquals("到头的重复提醒", listOf(true), R.visible(ended, write, zone).map { it.overdue })
        val many = (0 until 30).map { WidgetTimeRules.ReminderSource("m$it", "x", t(15, 23) + it * 60_000L, false, emptyList()) }
        assertEquals("行数上限", R.MAX_ROWS, R.snapshotRows(many, write, zone).size)
        val legacy = listOf(ReminderWidgetRow("l", "旧", "18:00", true))
        assertEquals("旧快照兜底", listOf(WidgetTimeRules.Shown("l", "旧", "18:00", true)), R.visible(legacy, t(16, 9), zone))
    }

    @Test fun refreshDates() {
        val dates = R.refreshDates(rows, write, zone)
        assertTrue("23:00 到点没换帧", t(15, 23) in dates)
        assertTrue("零点没换帧", t(16, 0) in dates)
        assertTrue("第二个零点没换帧", t(17, 0) in dates)
        assertTrue("次日 10:00 到点没换帧", t(16, 10) in dates)
        assertTrue("时间线越出窗口", dates.all { it > write && it <= write + R.HORIZON_MS })
        assertEquals("时间线没排好", dates.sorted(), dates)
    }

    @Test fun taskTodayDoneAndStale() {
        val snap = TaskWidgetSnapshot(todayDone = 3, savedAtMs = write)
        assertEquals("当天的今天完成", 3, R.todayDone(snap, t(15, 23, 30), zone))
        assertEquals("第二天还画着昨天的今天完成", 0, R.todayDone(snap, t(16, 9), zone))
        assertNull("没过 10 分钟就说更新于", R.staleText(write, t(15, 22, 5), zone))
        assertEquals("更新于 22:00", R.staleText(write, t(15, 23), zone))
        assertEquals("更新于 昨天 22:00", R.staleText(write, t(16, 9), zone))
        assertEquals("更新于 10月15日 22:00", R.staleText(write, t(18, 9), zone))
    }

    /** iOS WidgetBridge.syncTasks：执行中只认 running；今天完成按本机日界；SQLite 那种无时区时间串按 UTC。 */
    @Test fun taskSnapshotFromList() {
        val items = listOf(
            TaskItem(id = "a", status = "done", updated_at = "2026-10-15 13:00:00"), // UTC 13:00 = 上海 21:00，今天
            TaskItem(id = "b", status = "done", updated_at = "2026-10-15 16:30:00"), // UTC 16:30 = 上海 10/16 00:30，不是今天
            TaskItem(id = "c", status = "done", updated_at = null),
            TaskItem(id = "r", name = " 连连看网页游戏 ", goal = "做一个连连看的网页游戏，要有计时……", status = "running", steps_done = 2, steps_total = 5),
            TaskItem(id = "r2", status = "running"),
        )
        val s = WidgetTaskRules.snapshot(items, write, zone)
        assertEquals("r", s.runningId)
        assertEquals("存短标题不存 goal（umbra-ios#23）", "连连看网页游戏", s.runningTitle)
        assertEquals(2, s.stepsDone); assertEquals(5, s.stepsTotal)
        assertEquals("已完成 2/5 步", s.statusLine)
        assertEquals(1, s.todayDone)
        assertEquals(write, s.savedAtMs)
        val idle = WidgetTaskRules.snapshot(items.filter { it.status != "running" }, write, zone)
        assertNull("没有执行中 → 不画假任务", idle.runningId)
        assertNull(idle.runningTitle)
        assertNull(idle.percent)
    }

    @Test fun lineAndPercent() {
        assertEquals("没给步骤数 → 状态词，不编进度", "执行中", WidgetTaskRules.line(TaskItem(status = "running")))
        assertEquals("分子不越过总数", "已完成 5/5 步", WidgetTaskRules.line(TaskItem(status = "running", steps_done = 7, steps_total = 5)))
        assertEquals("40%", WidgetTaskRules.percentText(2, 5))
        assertEquals("没有步骤数不编百分比", "…", WidgetTaskRules.percentText(0, 0))
    }

    @Test fun taskSnapshotJsonMissingKeys() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val s = json.decodeFromString(TaskWidgetSnapshot.serializer(), """{"todayDone":2,"savedAtMs":5,"extra":1}""")
        assertNull(s.runningTitle); assertEquals(2, s.todayDone)
    }
}
