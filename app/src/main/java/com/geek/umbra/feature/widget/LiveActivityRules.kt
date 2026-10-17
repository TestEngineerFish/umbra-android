// 任务进行中常驻通知的纯判据 —— 照抄 iOS Utils/LiveActivityRules.swift（Live Activity 控制器里的两条判据）。
// 纯 Kotlin，配 JUnit 用例 LiveActivityRulesTest.kt（照抄 Tools/swifttests/LiveActivityRulesTests.swift）。
//
// ⛔ 灵动岛 Android 没有对等物。iOS 的「灵动岛 + 锁屏实况」在 Android 上换成一条进行中通知（TaskProgressNotifier）：
// Android 16+ 用 Notification.ProgressStyle（并申请提升为 Live Update），以下用普通 setProgress。
// 判据的口径（认领、收尾那一句、挂起留不留）原样沿用 —— 两端对同一个任务说同一句话。
package com.geek.umbra.feature.widget

object LiveActivityRules {

    /**
     * 冷启动认领（umbra-ios#22）：系统里还挂着的那几条进行中通知，留哪一条、撤掉哪些。
     *
     * 通知和 Live Activity 一样**活得比进程久**：杀掉 App，通知栏那条照样在。控制器只在内存里记「当前那条」的话，
     * 冷启动后必然是 null，下一次 sync 无条件再发一条 —— Android 上同 tag 会覆盖、不会叠，但「换了任务」那一支
     * 会以为没有旧的、不去撤它，旧任务那条就永远停在杀进程那一刻的进度。
     *
     * - live：还挂着的 `(通知 key, 它跟的 taskId)`，按系统给的顺序。
     * - running：当前执行中任务 id。
     * - 返回：留下那条的 key（null = 一条都不留）与要当场撤掉的那些。
     * 留哪条：跟着当前执行中任务的优先；都不是就留第一条，交给 sync 去收尾。
     */
    fun adopt(live: List<Pair<String, String>>, running: String?): Pair<String?, List<String>> {
        if (live.isEmpty()) return null to emptyList()
        val keep = live.firstOrNull { running != null && it.second == running } ?: live[0]
        return keep.first to live.filter { it.first != keep.first }.map { it.first }
    }

    /**
     * 任务离开「执行中」之后，最后那一帧说什么、算不算失败。
     * 绿勾只给真的 `done`；cancelled / failed / 找不到（null）/ 不认识的各说各的，**不替它说成功**。
     * ⚠️ 只在 [keepsAlive] 为假之后才调。
     */
    fun finalFrame(status: String?): Pair<String, Boolean> = when (status) {
        "done" -> "任务完成" to false
        "failed" -> "任务失败" to true
        "cancelled" -> "任务已取消" to true
        else -> "任务已结束" to true
    }

    /**
     * 离开执行中之后，通知**留不留着**（留 = 推一帧当前状态、不收尾）。
     * 服务端非终态就 pending / running / suspended：挂起（等设备上线 / 缺能力）之后人按「接着跑」还会回到 running，
     * 不能当成「任务死了」收尾。不认识的状态与 null 不留 —— 留着的代价是一条永远不收的通知。
     */
    fun keepsAlive(status: String?): Boolean = status != null && status in HOLD_STATUSES

    /** 离开执行中之后仍留着通知的那几个状态。往里加之前先去服务端 tasks_store.py 确认它不是终态。 */
    val HOLD_STATUSES: Set<String> = setOf("pending", "suspended")
}
