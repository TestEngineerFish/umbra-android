package com.geek.umbra.feature.task

import com.geek.umbra.feature.widget.TaskNotifyRules
import com.geek.umbra.feature.widget.WidgetTaskRules
import com.geek.umbra.model.TaskItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 任务步数同一口径 —— 照抄 iOS Tools/swifttests/TaskProgressRulesTests.swift（5758d19），字面按设计侧批次 290 §六：
 * 同一对 steps_done / steps_total 喂给详情、小组件、通知三处，分子相同；边界：0 步、做完、分子越界、没步数。
 */
class TaskProgressRulesTest {
    @Test fun sameNumbersEverywhere() {
        val p = TaskProgress.make(2, 3)!!
        assertEquals("已完成 2/3 步", p.stepLine)
        assertEquals("2 of 3 steps done", p.stepLineEn)
        assertEquals(3, p.currentStep)
        assertEquals("详情先取任务上的计数", p, TaskProgress.detail(2, 3, doneSteps = 1, stepCount = 3))
        assertEquals("小组件同一句", p.stepLine, WidgetTaskRules.line(TaskItem(status = "running", steps_done = 2, steps_total = 3)))
        assertTrue("通知第一行同一句", TaskNotifyRules.runningBody(2, 3, null).startsWith(p.stepLine))
        assertEquals("67%", WidgetTaskRules.percentText(p.done, p.total))
    }

    @Test fun edges() {
        assertNull("没步数不编进度", TaskProgress.make(1, null))
        assertNull(TaskProgress.make(0, 0))
        assertEquals("已完成 0/4 步", TaskProgress.make(null, 4)!!.stepLine)
        assertEquals("分子越界夹回总数", TaskProgress(4, 4), TaskProgress.make(9, 4))
        assertNull("做完了没有「正在做」", TaskProgress.make(4, 4)!!.currentStep)
        assertEquals("任务没带计数才数步骤列表", TaskProgress(1, 3), TaskProgress.detail(null, null, 1, 3))
        assertEquals("正在做第 3 步 · 打开价格页", TaskProgress.currentLine(3, " 打开价格页 "))
    }

    /** 调用点都走 TaskProgress：不许再出现按 done + 1 写「第 N/M 步」或自己除一遍。 */
    @Test fun noSecondFormula() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val bad = Regex("""第 \$\{[^}]*\+ ?1[^}]*}/|steps_done[^\n]*/ ?[a-z]*total|doneCount\(""")
        val hits = listOf("feature/task/TaskViews.kt", "feature/widget/TaskNotifyRules.kt", "feature/widget/WidgetTimeRules.kt", "feature/widget/TaskWidget.kt")
            .flatMap { rel -> root.resolve("com/geek/umbra/$rel").readLines().mapIndexedNotNull { i, l -> if (bad.containsMatchIn(l)) "$rel:${i + 1}" else null } }
        assertTrue("还有自己算步数的地方：$hits", hits.isEmpty())
    }
}
