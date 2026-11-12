package com.geek.umbra.net

/**
 * 长连接断线重连后的「各模块补同步一轮」判据（geek-13 11-12）。纯函数，App 侧的调度在 app/ReconnectCatchUp。
 *
 * 为什么要补：各模块靠长连接的广播（reminder_updated、task_update、聊天消息…）知道服务端变了，
 * 断着的那段时间里的广播收不到，重连以后也不会补发 —— 秘书在那段时间改的提醒时间、发的消息就一直不上屏。
 */
object ReconnectRules {
    /** 两次补同步之间至少隔这么久：网络抖动反复重连时不连发，落在间隔里的那次延到间隔满了再补（合并成一次）。 */
    const val MIN_GAP_MS = 20_000L

    /**
     * 这一次状态变化算不算「从断线回到在线」。[wasOnline] = null 表示这次登录 / 解锁以来还没在线过 ——
     * 第一次在线不补（登录 / 解锁那一刻各模块已经同步过）。
     */
    fun isReconnect(wasOnline: Boolean?, nowOnline: Boolean): Boolean = nowOnline && wasOnline == false

    /** 记住的「上一次在线与否」：没在线过时只有见到在线才开始记（离线 → 离线不算「断过」）。 */
    fun nextWasOnline(wasOnline: Boolean?, nowOnline: Boolean): Boolean? = if (wasOnline == null && !nowOnline) null else nowOnline

    /**
     * 现在补要等多久（毫秒）：0 = 马上；上一次补离现在不足 [MIN_GAP_MS] 就等到满。
     * ⚠️ 时钟往回拨（差值为负）当成很久没补，不当成刚补过（同 MoneyRefreshRules.onEnter）。
     */
    fun delayMs(lastCatchUpMs: Long?, nowMs: Long): Long {
        val last = lastCatchUpMs ?: return 0
        val gap = nowMs - last
        return if (gap < 0 || gap >= MIN_GAP_MS) 0 else MIN_GAP_MS - gap
    }
}
