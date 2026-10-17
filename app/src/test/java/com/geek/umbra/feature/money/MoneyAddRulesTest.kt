package com.geek.umbra.feature.money

import com.geek.umbra.net.Ledger
import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS Tools/swifttests/MoneyAddRulesTests.swift 逐条照抄。 */
class MoneyAddRulesTest {
    /** 「没存上」三段（219 §四4 / §四5）。 */
    @Test fun saveFail() {
        assertEquals("第一段", "没存上：服务端没有响应", MoneySaveFail.TITLE)
        assertEquals("新增档第二段", "这一笔还没进账本。填好的内容都还在，联网后再点一次就行。", MoneySaveFail.reason(editing = false))
        assertEquals("编辑档第二段", "账本里还是改之前那一版。改过的内容都还在，联网后再点一次就行。", MoneySaveFail.reason(editing = true))
        assertEquals("编辑档还在说「还没进账本」", false, MoneySaveFail.reason(editing = true).contains("还没进账本"))
        assertEquals("钮名（iOS 豁免：单说「重试」读不出重试的是什么）", "重试保存", MoneySaveFail.ACTION_LABEL)
        for (r in listOf(MoneySaveFail.reason(editing = false), MoneySaveFail.reason(editing = true))) {
            assertEquals("「$r」许诺了一个不存在的离线队列", false, r.contains("自动重试"))
        }
    }

    /** 已被挪到另一个账本（umbra#102 / 服务端 323fdc0）。 */
    @Test fun putFail() {
        val F = MoneyPutFail
        assertEquals("409 other_ledger + business → 被挪到经营", MoneyPutFail.OtherLedger(Ledger.Business),
            F.classify(409, "state_conflict", "other_ledger", "business"))
        assertEquals("409 other_ledger + personal → 被挪到生活", MoneyPutFail.OtherLedger(Ledger.Personal),
            F.classify(409, "state_conflict", "other_ledger", "personal"))
        assertEquals("不带 ledger → 仍是这一档（账本未知）", MoneyPutFail.OtherLedger(null),
            F.classify(409, "state_conflict", "other_ledger", null))
        assertEquals("认不出的账本 → 账本未知", MoneyPutFail.OtherLedger(null),
            F.classify(409, "state_conflict", "other_ledger", "travel"))
        assertEquals("不是 409 → 不认", MoneyPutFail.Unreachable, F.classify(400, "state_conflict", "other_ledger", "business"))
        assertEquals("没有码 → 不认", MoneyPutFail.Unreachable, F.classify(409, null, "other_ledger", "business"))
        assertEquals("别的 state → 不认", MoneyPutFail.Unreachable, F.classify(409, "state_conflict", "same_ledger", "business"))
        assertEquals("没拿到响应 → 没存上", MoneyPutFail.Unreachable, F.classify(0, null, null, null))
        assertEquals("500 → 没存上", MoneyPutFail.Unreachable, F.classify(500, "internal", null, null))
        assertEquals("带账本名", "这笔已被挪到经营账本，这次的改动没存上", F.movedAwayText(Ledger.Business))
        assertEquals("带账本名（生活）", "这笔已被挪到生活账本，这次的改动没存上", F.movedAwayText(Ledger.Personal))
        assertEquals("账本未知", "这笔已被挪到另一个账本，这次的改动没存上", F.movedAwayText(null))
        for (t in listOf(F.movedAwayText(Ledger.Business), F.movedAwayText(null))) {
            assertEquals("「$t」叫人重试 —— 这一档重试永远是同一个 409", false, t.contains("再") || t.contains("重试"))
        }
    }

    /** 这张表代表哪一笔（money-saved-entry-overwrite-in-new-mode）。 */
    @Test fun target() {
        val T = MoneyAddTarget
        assertEquals("新建未存 → 不是已有的一笔", false, T.isExisting(null, null))
        assertEquals("新建未存 → 不带 id（store 生成）", null, T.saveId(null, null))
        assertEquals("新建未存 → 有「再记一笔」", true, T.allowsAgain(null, null))
        assertEquals("新建未存 → 扫描随保存挂", true, T.attachesScanOnSave(null, null))
        assertEquals("编辑态 → 已有的一笔", true, T.isExisting("e1", null))
        assertEquals("编辑态 → 写回原 id", "e1", T.saveId("e1", null))
        assertEquals("编辑态 → 没有「再记一笔」（#35）", false, T.allowsAgain("e1", null))
        assertEquals("编辑态 → 扫的那一刻已挂，保存不再挂", false, T.attachesScanOnSave("e1", null))
        assertEquals("新建后存上留页 → 已有的一笔", true, T.isExisting(null, "s1"))
        assertEquals("新建后存上留页 → 再存写回 s1，不再新建一笔", "s1", T.saveId(null, "s1"))
        assertEquals("新建后存上留页 → 不出「再记一笔」（不然改完金额点它，PUT 回去的是上一笔）", false, T.allowsAgain(null, "s1"))
        assertEquals("新建后存上留页 → 再存不再挂扫描（同一张票挂两份）", false, T.attachesScanOnSave(null, "s1"))
        assertEquals("路由 id 优先", "e1", T.saveId("e1", "s1"))
    }

    /** 存上之后能不能离开这张表（台账 2026-10-01 Money e）。 */
    @Test fun settled() {
        val T = MoneyAddTarget
        assertEquals("图和票都落地了 → 可以退屏 / 接着记", true, T.settled(0, true))
        assertEquals("有图没传上 → 留页", false, T.settled(1, true))
        assertEquals("发票 PDF 没挂上 → 留页", false, T.settled(0, false))
        assertEquals("两样都没落地 → 留页", false, T.settled(2, false))
    }
}
