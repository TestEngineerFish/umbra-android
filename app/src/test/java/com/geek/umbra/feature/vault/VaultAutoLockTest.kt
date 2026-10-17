package com.geek.umbra.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 保险箱自动上锁（umbra-ios#70）—— 逐条翻译 iOS `Tools/swifttests/VaultAutoLockTests.swift`。
 *
 * #70 的失败形状是「什么都没发生」：停在记录页 10 分钟没锁。这里拿假时间逐秒推演。
 * Android 时间一律毫秒：iOS 的 `t0 + s 秒` 换成 `t0Ms + s × 1000`。
 */
class VaultAutoLockTest {
    private val t0 = 1_800_000_000_000L
    private fun at(s: Long) = t0 + s * 1000

    /** 判据本身：整 10 分钟到点；0 分钟 = 不自动锁定；墙钟小幅回拨不算、大幅回拨按上锁。 */
    @Test fun expiredRule() {
        assertEquals("10 分钟差 1 秒就锁了", false, VaultAutoLockRules.expired(t0, at(599), 10))
        assertEquals("整 10 分钟没锁", true, VaultAutoLockRules.expired(t0, at(600), 10))
        assertEquals("自动锁定关掉了（0 分钟）还在锁", false, VaultAutoLockRules.expired(t0, at(86_400), 0))
        assertEquals("NTP 校时往回 5 秒就锁了", false, VaultAutoLockRules.expired(t0, at(-5), 10))
        assertEquals("系统时间被往回拨了一小时，表永远到不了点", true, VaultAutoLockRules.expired(t0, at(-3600), 10))
    }

    /** #70 ①：解锁后停在记录页 / 别的 tab，没有任何页面调用，到点照样锁。 */
    @Test fun leavesHomeStillExpires() {
        val c = VaultIdleClock(t0)
        c.setOpen(true, at(0))
        assertEquals("离开首页 9 分钟就锁了", false, c.expired(at(9 * 60), 10))
        assertEquals("离开首页 10 分钟没锁（#70 原形）", true, c.expired(at(10 * 60), 10))
    }

    /** #70 ②：已经开着时再报一次「开」不清零。 */
    @Test fun reopenDoesNotReset() {
        val r = VaultIdleClock(t0)
        r.setOpen(true, at(0))
        r.setOpen(true, at(9 * 60))
        assertEquals("回首页把空闲计时清零了（#70 ②）", true, r.expired(at(10 * 60), 10))
    }

    /** #70 ④：后台待了很久，回前台按墙钟算。 */
    @Test fun backgroundUsesWallClock() {
        val b = VaultIdleClock(t0)
        b.setOpen(true, at(0))
        b.touch(at(60))
        assertEquals("后台三小时回来没锁（#70 ④）", true, b.expired(at(60 + 3 * 3600), 10))
    }

    /** 动作续命、锁着不算、重新解开从头数。 */
    @Test fun touchLockAndReopen() {
        val a = VaultIdleClock(t0)
        a.setOpen(true, at(0))
        a.touch(at(500))
        assertEquals("有动作之后还按解锁那一刻算", false, a.expired(at(700), 10))
        a.setOpen(false, at(800))
        assertEquals("已经锁着了还报到点", false, a.expired(at(5000), 10))
        a.setOpen(true, at(5000))
        assertEquals("重新解开没从头数（锁着那段被算进来了）", false, a.expired(at(5100), 10))
        assertEquals("重新解开后 10 分钟没锁", true, a.expired(at(5600), 10))
    }

    /** #70 ③：上锁那一刻各 tab 栈怎么退 —— 退回保险箱首页；没有首页的整段退掉；别的 tab 不动。 */
    @Test fun retreatStacks() {
        val isVault: (String) -> Boolean = { it.startsWith("vault") }
        val isHome: (String) -> Boolean = { it == "vaultHome" }
        assertEquals("记录页 / 编辑页没退回首页（#70 ③）", listOf("vaultHome"),
            VaultAutoLockRules.retreat(listOf("vaultHome", "vaultRecord", "vaultEdit"), isVault, isHome))
        assertEquals("保险箱前面那段非保险箱的栈被一起退掉了", listOf("moneyHome", "vaultHome"),
            VaultAutoLockRules.retreat(listOf("moneyHome", "vaultHome", "vaultCheck"), isVault, isHome))
        assertEquals("回收站没退掉 / 往「我」的栈里塞了一张保险箱首页", listOf("setGeneral"),
            VaultAutoLockRules.retreat(listOf("setGeneral", "vaultTrash"), isVault, isHome))
        assertEquals("别的 tab 正在写的提醒被一起退掉了", listOf("remDetail", "remEdit"),
            VaultAutoLockRules.retreat(listOf("remDetail", "remEdit"), isVault, isHome))
        assertEquals("停在首页上被退出了保险箱", listOf("vaultHome"),
            VaultAutoLockRules.retreat(listOf("vaultHome"), isVault, isHome))
        assertEquals("空栈", emptyList<String>(), VaultAutoLockRules.retreat(emptyList(), isVault, isHome))
    }
}
