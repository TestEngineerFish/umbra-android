// 任务步数进度的唯一算法 —— 照抄 iOS Features/Task/TaskProgressRules.swift（5758d19），字面按设计侧批次 290 §六。
// ⚠️ 纯 Kotlin，不许 import android.*：小组件、通知、详情、列表都用它，JUnit 直接测。
package com.geek.umbra.feature.task

/**
 * 详情「总进度」、列表行进度条、小组件、进行中通知全取这里，**一律按「已完成几步」**（sam 10-22 真机：同一个任务详情写「2 / 3」、
 * 通知却写「第 3/3 步」—— 那句按 steps_done + 1 算「正在做第几步」，两种都长成「x/y」，读的人一定拿来对）。
 * 正在做的那一步另起一行说「正在做第 N 步」，不和完成数挤在一起（批次 290 §六）。
 */
data class TaskProgress(val done: Int, val total: Int) {
    /** 0…1，进度条用。 */
    val fraction: Float get() = done.toFloat() / total
    /** 「已完成 2/3 步」。设计侧改字面只改这里。手写：iOS 同样是常量 TaskProgress.stepLineFormat，不进文案表。 */
    val stepLine: String get() = "已完成 $done/$total 步"
    /** 英文："2 of 3 steps done"（批次 290 §六）。 */
    val stepLineEn: String get() = "$done of $total steps done"
    /** 正在做的那一步（第 done+1 步）；全做完了没有这一行。 */
    val currentStep: Int? get() = (done + 1).takeIf { done < total }

    companion object {
        /** 服务端没给步数（null 或 0）就是 null —— 各处据此不画进度、不编百分比。分子夹在 0…total。 */
        fun make(done: Int?, total: Int?): TaskProgress? {
            val t = total?.takeIf { it > 0 } ?: return null
            return TaskProgress((done ?: 0).coerceIn(0, t), t)
        }

        /** 详情：先取任务上的计数（和列表、小组件、通知同一对数），任务没带计数才数步骤列表。iOS: TaskProgress.detail */
        fun detail(stepsDone: Int?, stepsTotal: Int?, doneSteps: Int, stepCount: Int): TaskProgress? =
            make(stepsDone, stepsTotal) ?: make(doneSteps, stepCount)

        /** 「正在做第 3 步」，带步名时「正在做第 3 步 · 打开价格页」。 */
        fun currentLine(step: Int, title: String?): String =
            "正在做第 $step 步" + (title?.trim()?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
    }
}
