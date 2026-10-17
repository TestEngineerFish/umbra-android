package com.geek.umbra.feature.task

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 「从别处要求弹停止确认」的一次性标记 —— 进行中通知上的「停止」（设计侧第 199 封答复第 9 条：点了拉起 App 内的停止确认）。
 *
 * 为什么不直接在通知里停：停止是破坏性操作，必须过确认弹窗（CLAUDE.md 第 10 条），而通知动作在 App 外没有弹窗可用。
 * 所以通知那一头只记下「要停哪个」+ 推入这条任务的详情；详情页拿到详情之后读一次、清掉、弹现成的停止确认。
 * 用 StateFlow 而不是普通变量：详情页已经开着（同一条路由不会重进）时也要能收到。
 */
object TaskStopRequest {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    /** 记下要停的任务 id（通知动作的跳板 Activity 调）。 */
    fun request(id: String) { _pending.value = id }

    /** 是这个任务的就取走（只弹一次）。 */
    fun consume(id: String): Boolean {
        if (_pending.value != id) return false
        _pending.value = null
        return true
    }
}
