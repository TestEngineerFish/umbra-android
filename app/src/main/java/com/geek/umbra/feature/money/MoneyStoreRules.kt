// 记账数据源的纯判据 —— 照抄 iOS Features/Money/MoneyStoreRules.swift 与 Networking/Ledger.swift 的 readonlyBatch。
//
// 为什么要一个代次，而不是只核账本（台账 month-switch-stale-response）：
// 一轮重拉的口径有两个维度（账本、月份）。连点两下「←」，两轮都是同一个账本 ——
// 先发的那一轮后到时照样落地，于是抬头写 8 月、列表是 9 月的。
// > 一次迟到的响应只按部分维度作废，等于在剩下那几个维度上不作废。
// 所以每发一轮领一个号，回来时只认最新那个号。
package com.geek.umbra.feature.money

/**
 * 一轮重拉的号牌。**只有最后发出去的那一轮能落地。**
 * iOS 是 struct + mutating（挂在 @MainActor 的 store 上）；这里是 class，调用方同样只在主线程用，不加锁。
 * iOS: MoneyStoreRules.swift:28
 */
class MoneyReloadGate {
    /** 已经发出去的最大号。0 = 一轮都还没发过。 */
    var latest: Int = 0
        private set

    /** 发一轮之前调：领一个新号。 */
    fun begin(): Int {
        latest += 1
        return latest
    }

    /** 这一轮回来时调：它还是不是最新发出去的那一轮。用 == 不用 >=：号只会涨，「比最新还新」不是一种可能。 */
    fun isCurrent(gen: Int): Boolean = gen == latest
}

/** 账本相关的纯判据（iOS 挂在 UmbraLedger 上，Android 的 Ledger 在 net 包里，判据放这边）。 */
object MoneyLedgerRules {
    /**
     * 这一批分类是**只读科目**吗 —— 参数是每一条自己报的 readonly。
     * ⚠️ 判据是 contains(true) 而不是 all：空集上 all 回 true，「一次没拉到分类」会被读成「这些是只读科目」。
     * > 一个在空集上为真的判据，会在最没有信息的时候给出最肯定的答案。
     * iOS: Networking/Ledger.swift:38
     */
    fun readonlyBatch(flags: List<Boolean>): Boolean = flags.contains(true)
}

/** 进记账页时做什么（[MoneyRefreshRules.onEnter]）：整页拉 / 静默同步一轮 / 不发。 */
enum class MoneyEnterRefresh { Full, Sync, None }

/**
 * 进记账首页 / 流水页时要不要向服务端同步（geek-13 11-12，三端一致）。秘书能在云端记账、改账，记账没有变更推送，
 * 只有进页面 / 下拉时去拉。iOS: Features/Money/MoneyStoreRules.swift MoneyRefreshRules（86c9fe2）
 */
object MoneyRefreshRules {
    /** 进页面同步的最小间隔：首页 ⇄ 流水页来回每次都算「进页面」，不设间隔翻一趟就是一串同步。下拉刷新不受它管。 */
    const val ENTER_SYNC_GAP_MS = 30_000L

    /**
     * [ready]：手上有没有一份拉成了的数据；[offline]：全局离线标志（离线照样整页重拉一次，重探）；
     * [lastSyncMs]：上次进页面 / 下拉同步的时刻，null = 这次启动还没同步过。
     */
    fun onEnter(ready: Boolean, offline: Boolean, lastSyncMs: Long?, nowMs: Long): MoneyEnterRefresh {
        if (!ready || offline) return MoneyEnterRefresh.Full
        val last = lastSyncMs ?: return MoneyEnterRefresh.Sync
        // ⚠️ 时钟往回拨时差值是负的 —— 当成很久没同步，不当成刚同步过，否则拨回去的那段时间里一直不同步。
        val gap = nowMs - last
        return if (gap >= ENTER_SYNC_GAP_MS || gap < 0) MoneyEnterRefresh.Sync else MoneyEnterRefresh.None
    }
}
