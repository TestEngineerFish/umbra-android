// 经营记账表单那几件的纯算术与枚举（设计侧 G2）—— 照抄 iOS Features/Money/BizKit.swift。纯 Kotlin。
//
// ReceiptState / DeductTier 两个枚举 iOS 也在 BizKit.swift 里，Android 已放进 MoneyModels.kt（DTO 要用），这里不再重复。
// 这里只放「算什么」和「有哪几档」，不放「长什么样」。
package com.geek.umbra.feature.money

import java.util.Locale

/**
 * 记账那几句两端逐字同源的话。宿主在两个仓库里 —— 改一端不会有任何东西提醒另一端。iOS: BizKit.swift:60
 */
object MoneyCopy {
    /** 挪进经营之后科目是空的，那一格自己说出它空着（C9 / 设计侧 215 §四⒝）。不新造徽章。 */
    const val CAT_MISSING = "缺科目"
}

/**
 * 记一笔那一屏要往上传的经营字段。**只在经营账本那一路构造** —— 生活账本传 null，七个字段整个不出现。
 * iOS: BizKit.swift:88
 */
data class BizPayload(
    val invoiceNo: String,
    val invoiceKind: String,
    val receipt: ReceiptState,
    val rateBp: Int,
    /** ⚠️ 只有收入侧有值（支出侧根本不该算出这个数）。 */
    val netCents: Long?,
    val tier: DeductTier,
)

/** iOS: BizKit.swift:98 */
object MoneyBiz {
    /** 金额框与不含税框共用的上界（分）：不含税本身就是一笔金额，不该比价税合计还能装。 */
    private const val MAX_CENTS = 999_999_999.0

    /**
     * 不含税金额 = 价税合计 ÷ (1 + 税率)。**全端唯一一处算它的地方。**
     * > 一句「这两处必须一致」的注释，是这两处迟早不一致的最好证据。
     * ⚠️ 舍入用「远离零的四舍五入」（Swift .toNearestOrAwayFromZero，对正数等于 PC 的 Math.round）；
     * 截断会差一分，而两个数都看起来对。iOS: BizKit.swift:109
     */
    fun netOf(cents: Long?, rateBp: Int): Long? {
        if (cents == null) return null
        return MoneyAmount.roundHalfAwayFromZero(cents.toDouble() / (1 + rateBp.toDouble() / 10000)).toLong()
    }

    /**
     * 税率的界面值（百分数）→ 库里的值（万分数）。1% = 100。
     * ⚠️ 越过上限回 0（和「打不出数」同一档）；0–100% 之外但在上限内的照原样交出去，由服务端 0–10000 拒 ——
     * 在这里悄悄改成 0 的话，人敲的 101% 会安静地变成免税存进账里。iOS: BizKit.swift:119
     */
    fun bp(fromPercentText: String): Int {
        val n = keepNumberChars(fromPercentText).toDoubleOrNull() ?: return 0
        return hundredths(n)?.toInt() ?: 0
    }

    /** 万分数 → 税率框里的那串字（600 → "6"、650 → "6.5"）。0 回空串：占位符就是「0」，空着人更好接着敲。 */
    fun rateText(bp: Int): String {
        if (bp == 0) return ""
        return trimZeros(String.format(Locale.ROOT, "%.2f", bp.toDouble() / 100))
    }

    /**
     * 税率的值从框外变了（扫票回填、编辑页灌值、「接着记」清零）时，框里那串字该换成什么。null = 不用换。
     * ⚠️ 判据是「这串字解出来的值是不是已经等于它」，不是字面相等：敲到一半的「6.」解出来就是 600，
     * 这时改写他的字，光标会跳、他打的那个点会被吃掉。iOS: BizKit.swift:144
     */
    fun rateTextResync(text: String, bp: Int): String? =
        if (this.bp(text) == bp) null else rateText(bp)

    /** 手改「不含税金额」那一格的文本 → 分。越过上限、或打不出数都回 null（= 这一格没有值）。 */
    fun cents(fromDecimalText: String): Long? =
        keepNumberChars(fromDecimalText).toDoubleOrNull()?.let { hundredths(it) }

    /**
     * n × 100 四舍五入成整数，**先在 Double 上判上界、判完才转整数**（umbra-ios#36：iOS 的 Int(Double) 超界会 trap）。
     * iOS: BizKit.swift:161
     */
    fun hundredths(n: Double): Long? {
        if (!n.isFinite() || n < 0) return null
        val scaled = MoneyAmount.roundHalfAwayFromZero(n * 100)
        if (scaled > MAX_CENTS) return null
        return scaled.toLong()
    }

    /** 算式记号里念的除数：「= 1,030.00 ÷ 1.01」。⚠️ 尾随的 0 要去掉 —— 「÷ 1.0100」念起来像精确到四位。 */
    fun divisorText(rateBp: Int): String =
        trimZeros(String.format(Locale.ROOT, "%.4f", 1 + rateBp.toDouble() / 10000))

    /**
     * 支出侧**不摆**「不含税金额」：小规模纳税人进项抵扣不了，一笔支出的成本就是价税合计。
     * > 一个没有消费者的数，最危险的形态不是没用，是有人会去用它。iOS: BizKit.swift:184
     */
    fun showsNet(direction: String): Boolean = direction == "income"

    /** 支出侧的税率降进票据组并改叫「票面税率」—— 它是发票上的一个事实，只是不是算式的输入。 */
    fun rateLivesInReceiptGroup(direction: String): Boolean = direction == "expense"

    /**
     * 那条常驻校验位要不要说话：人手改过不含税，而它和自动值对不上。null = 没话说。
     * ⚠️ 它不拦保存 —— 「哪个对」只有人知道。一分以内不说话：那是舍入，不是分歧。iOS: BizKit.swift:195
     */
    fun netDriftNote(cents: Long?, rateBp: Int, netCents: Long?, netEdited: Boolean): String? {
        if (!netEdited || netCents == null) return null
        val auto = netOf(cents, rateBp) ?: return null
        val diff = Math.abs(auto - netCents)
        if (diff <= 1) return null
        return "不含税差 ${String.format(Locale.ROOT, "%.2f", diff.toDouble() / 100)}，保存时照你填的存。"
    }

    /**
     * 等价 Swift `s.filter { $0.isNumber || $0 == "." }`：留下数字类字符和点。
     * 全角数字之类会留下来，但随后 toDoubleOrNull 解不出（与 Swift Double(String) 一样只认 ASCII）→ 0 / null。
     * 差别：Swift 的 isNumber 还认「一」这类带数值属性的汉字，这里不认（都是解不出 vs 解出，只在混着汉字的畸形输入上分岔）。
     */
    private fun keepNumberChars(s: String): String = s.filter {
        it == '.' || when (Character.getType(it)) {
            Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
            else -> false
        }
    }

    /** 去尾随 0 和孤零零的小数点（iOS 那两个 while 循环）。 */
    private fun trimZeros(s0: String): String {
        var s = s0
        while (s.endsWith("0")) s = s.dropLast(1)
        if (s.endsWith(".")) s = s.dropLast(1)
        return s
    }
}
