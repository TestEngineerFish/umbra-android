package com.geek.umbra.feature.money

import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS Tools/swifttests/BizKitTests.swift 逐条照抄。 */
class BizKitTest {
    /** 不含税：舍入这一档。 */
    @Test fun netOf() {
        assertEquals("1% 那一档", 101980L, MoneyBiz.netOf(103000, 100))
        assertEquals("3% 那一档", 97087L, MoneyBiz.netOf(100000, 300))
        // 995 ÷ 1.06 = 938.679… → 939（截断会得 938）。
        assertEquals("舍入写成截断了 —— 差一分不会报错，它只会让屏上念的和库里存的不是同一个数", 939L, MoneyBiz.netOf(995, 600))
        assertEquals("零元那一笔", 0L, MoneyBiz.netOf(0, 600))
        assertEquals("0% 时不含税就是价税合计", 103000L, MoneyBiz.netOf(103000, 0))
        assertEquals("金额还没填时不报一个数", null, MoneyBiz.netOf(null, 100))
    }

    /** 百分数 ↔ 万分数。 */
    @Test fun bp() {
        assertEquals("1% = 100", 100, MoneyBiz.bp("1"))
        assertEquals("6% = 600", 600, MoneyBiz.bp("6"))
        assertEquals("0.5% = 50", 50, MoneyBiz.bp("0.5"))
        assertEquals("空着当 0", 0, MoneyBiz.bp(""))
        assertEquals("打到一半的 `6.` 不该变成别的数", 600, MoneyBiz.bp("6."))
        assertEquals("打不出数就是 0，不是崩", 0, MoneyBiz.bp("abc"))
        assertEquals("税率框粘进 17 位数 —— 回 0，不是崩", 0, MoneyBiz.bp("99999999999999999"))
        assertEquals("超过 100% 但在上限内的照原样交出去，由服务端那道 0–10000 拒 —— 别悄悄改成免税", 10100, MoneyBiz.bp("101"))
    }

    /** 不含税手改框。 */
    @Test fun centsFromText() {
        assertEquals("手改不含税 1019.80", 101980L, MoneyBiz.cents("1019.80"))
        assertEquals("删空 = 这一格没有值", null, MoneyBiz.cents(""))
        assertEquals("不含税框粘进 17 位数 —— 回 nil，不是崩", null, MoneyBiz.cents("99999999999999999"))
        assertEquals("上限那一分还收", 999_999_999L, MoneyBiz.cents("9999999.99"))
        assertEquals("过了金额上限", null, MoneyBiz.cents("10000000"))
    }

    /** 算式记号里那个除数。 */
    @Test fun divisor() {
        assertEquals("1% 的除数", "1.01", MoneyBiz.divisorText(100))
        assertEquals("6% 的除数", "1.06", MoneyBiz.divisorText(600))
        assertEquals("0% 时不该念成 1.0000", "1", MoneyBiz.divisorText(0))
        assertEquals("0.5% 的除数", "1.005", MoneyBiz.divisorText(50))
    }

    /** 方向决定形状的那两处（directionShapesTheForm）。 */
    @Test fun direction() {
        assertEquals("收入侧摆不含税", true, MoneyBiz.showsNet("income"))
        assertEquals("⚠️ 支出侧摆出了不含税 —— 一个没有消费者的数，最危险的形态不是没用，是有人会去用它", false, MoneyBiz.showsNet("expense"))
        assertEquals("支出侧税率降进票据组", true, MoneyBiz.rateLivesInReceiptGroup("expense"))
        assertEquals("收入侧税率留在金额带", false, MoneyBiz.rateLivesInReceiptGroup("income"))
    }

    /** 那条常驻校验位（recomputeRule）。 */
    @Test fun drift() {
        assertEquals("没手改过却在报差额", null, MoneyBiz.netDriftNote(103000, 100, 101980, false))
        assertEquals("一分以内的舍入差不该报出来", null, MoneyBiz.netDriftNote(103000, 100, 101981, true))
        assertEquals("手改之后那句话", "不含税差 0.20，保存时照你填的存。", MoneyBiz.netDriftNote(103000, 100, 101960, true))
    }

    /** 四档 / 三档都在，而且名字不重；「不进成本」问的是字段在不在。 */
    @Test fun enums() {
        assertEquals("扣除比例四档", 4, DeductTier.entries.size)
        assertEquals("四档里有两档念同一句话", 4, DeductTier.entries.map { it.label }.toSet().size)
        assertEquals("票据状态只有三档", 3, ReceiptState.entries.size)
        assertEquals("三档里有两档念同一句话", 3, ReceiptState.entries.map { it.label }.toSet().size)
        assertEquals("无票那一档的字面值不是 none 了", "none", ReceiptState.NoReceipt.wire)

        assertEquals("生活账本的支出被判成了不进成本（字段根本没发）", false, ReceiptState.notCost(null, false))
        assertEquals("生活账本的收入也不该念这句", false, ReceiptState.notCost(null, true))
        assertEquals("经营侧无票的支出要念这句", true, ReceiptState.notCost("none", false))
        assertEquals("收入没有成本可言，不该念「不进成本」", false, ReceiptState.notCost("none", true))
        assertEquals("待补票不是不可扣", false, ReceiptState.notCost("pending", false))
        assertEquals("有票的那一笔当然进成本", false, ReceiptState.notCost("has", false))
        assertEquals("空串被当成了无票", false, ReceiptState.notCost("", false))
    }

    /** 税率框跟着框外的值走（台账 2026-10-01 Money f）。 */
    @Test fun resync() {
        assertEquals("扫票回填后框里还是空的", "6", MoneyBiz.rateTextResync("", 600))
        assertEquals("清零之后框里还留着上一张票的 6", "", MoneyBiz.rateTextResync("6", 0))
        assertEquals("换成别的税率", "6.5", MoneyBiz.rateTextResync("13", 650))
        assertEquals("敲到一半的「6.」被改写了", null, MoneyBiz.rateTextResync("6.", 600))
        assertEquals("「6.0」被改写了", null, MoneyBiz.rateTextResync("6.0", 600))
        assertEquals("人敲的免税 0 被改写了", null, MoneyBiz.rateTextResync("0", 0))
        assertEquals("空框和 0 本来就一致", null, MoneyBiz.rateTextResync("", 0))
        for (bp in listOf(0, 1, 100, 300, 600, 650, 900, 1300, 1, 2550, 10000)) {
            assertEquals("rateText($bp) 解不回原值", bp, MoneyBiz.bp(MoneyBiz.rateText(bp)))
            assertEquals("rateText($bp) 写进框之后还要再改一次", null, MoneyBiz.rateTextResync(MoneyBiz.rateText(bp), bp))
        }
    }
}
