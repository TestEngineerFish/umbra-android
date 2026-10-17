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
