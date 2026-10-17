package com.geek.umbra.feature.money

import com.geek.umbra.net.Ledger
import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS Tools/swifttests/LedgerTests.swift 里 readonlyBatch 与 other 那几条（路径表那部分 net 包已有）。 */
class LedgerTest {
    @Test fun readonlyBatch() {
        // ⚠️ 写成 all 的话，一次没拉到分类会被读成「这些是只读科目」。
        assertEquals("空集被读成了只读科目 —— 一个在空集上为真的判据，会在最没有信息的时候给出最肯定的答案",
            false, MoneyLedgerRules.readonlyBatch(emptyList()))
        assertEquals("生活分类不是只读科目", false, MoneyLedgerRules.readonlyBatch(listOf(false, false)))
        assertEquals("经营科目是只读的", true, MoneyLedgerRules.readonlyBatch(listOf(true, true)))
        // 混着只可能是解码漏了某几条，那时按只读走是安全的那一边。
        assertEquals("混着时按只读走", true, MoneyLedgerRules.readonlyBatch(listOf(false, true)))
    }

    @Test fun other() {
        assertEquals("生活的另一个是经营", true, Ledger.Personal.other == Ledger.Business)
        assertEquals("经营的另一个是生活", true, Ledger.Business.other == Ledger.Personal)
    }
}
