package com.geek.umbra.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自动锁定那条条幅（C5）—— 逐条翻译 iOS `Tools/swifttests/VaultLockNoteTests.swift`。
 *
 * 这一族大多数时候的正确表现是「什么都不写」。最要紧的是 `minutes == 0`（用户关掉了自动锁定）时整条不出，
 * 否则它会念「0 分钟后保险箱会自己上锁」—— 一句字面上正好说反的话。
 */
class VaultLockNoteTest {
    /** 那句话：开头半句、三处宿主都念「没用保险箱」、锁屏一句与标题、与 PC 逐字同源的半句、末句入口、说清后果。 */
    @Test fun wording() {
        val s = VaultLockNote.text(10)
        assertEquals("开头那半句不对", true, s.contains("没用保险箱的话，10 "))
        val hosts = listOf(s, VaultLockNote.lockedLine(10), VaultLockNote.SETTINGS_FOOTER, VaultLockNote.PICKER_TITLE)
        for (h in hosts) {
            assertEquals("「$h」里没有「没用保险箱」", true, h.contains("没用保险箱"))
            for (old in listOf("无操作", "没有操作", "没动了")) {
                assertEquals("「$h」里还念着「$old」", false, h.contains(old))
            }
        }
        assertEquals("锁屏那一句", "已自动锁定 · 5 分钟没用保险箱", VaultLockNote.lockedLine(5))
        assertEquals("锁屏标题", "保险箱已上锁", VaultLockNote.LOCKED_TITLE)
        assertEquals("锁屏标题和状态行把「自动」说了两遍", false, VaultLockNote.LOCKED_TITLE.contains("自动"))
        assertEquals("与 PC 逐字同源的那半句不在里面", true, s.contains(VaultLockNote.SHARED))
        assertEquals("末句不是这一端的入口", true, s.endsWith(VaultLockNote.WHERE))
        assertEquals("末句指的入口不是这一端的", true, VaultLockNote.WHERE.contains("保险箱设置"))
        assertEquals("共用那半句里混进了入口名", false, VaultLockNote.SHARED.contains("设置"))
        assertEquals("没说清后果", true, VaultLockNote.SHARED.contains("重新输主密码"))
    }

    /** 出不出：0 分钟不出；「×」与「不再提示」各自都能挡住，两样都有时也不出。 */
    @Test fun showsOrNot() {
        assertEquals("正常情况下它该出", true, VaultLockNote.shows(10, closed = false, silenced = false))
        assertEquals("自动锁定关掉了，它还在念「0 分钟后保险箱会自己上锁」", false, VaultLockNote.shows(0, closed = false, silenced = false))
        assertEquals("这一次关掉了还在出", false, VaultLockNote.shows(10, closed = true, silenced = false))
        assertEquals("勾过「不再提示」还在出", false, VaultLockNote.shows(10, closed = false, silenced = true))
        assertEquals("两样都有时还在出", false, VaultLockNote.shows(10, closed = true, silenced = true))
    }
}
