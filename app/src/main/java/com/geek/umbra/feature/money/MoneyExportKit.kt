// 导出账目的纯判据层（设计侧 162 §一，D3）—— 照抄 iOS Features/Money/MoneyExportKit.swift。纯 Kotlin。
//
// 为什么范围、句子、文件名要从同一处出（162 §一 自己返工过一次）：
// 范围句写对了，下一屏的文件名写成了字面 umbra-账目-2026-08.csv，选了「全部账目」之后它还叫某一个月。
// > 文件名是唯一会离开这个 App 的那一段字 —— 收到文件的人只看得到它。
// 所以「范围」做成一份东西（MoneyExportRange），范围句、文件名、主钮上的笔数全从它推。
package com.geek.umbra.feature.money

import java.util.Locale

/**
 * 导出的范围。只有两档 —— 跟着当前筛选，或者全部账目（跨所有月份）。
 * 不摆第三档「选个月份」：那一格的答案已经在流水页的筛选里了。iOS: MoneyExportKit.swift:28
 */
enum class MoneyExportScope { Filtered, All }

/** 范围的一段。spoken 进范围句（给人读），slug 进文件名（跟着文件走出 App）；两种写法必须在同一处产生。 */
data class MoneyExportSeg(val spoken: String, val slug: String)

/**
 * 「本季待补票」那一筛开着时导出的口径（台账 export-rcpt-filter-mislabeled）。
 * 那一筛的源是整季含往季那一份（只有上界 through），不是正在看的那一个月；还有一道 limit 会截断。
 * iOS: MoneyExportKit.swift:52
 */
data class MoneyExportRcpt(
    /** 取数的上界（本季末月，2026-12）—— 和 ReceiptKit.pendingThrough 同一个值。 */
    val through: String,
    /** 同一个上界给人读的写法（2026 年第 4 季），调用方用 ReceiptKit.quarterLabel 传进来。 */
    val throughSpoken: String,
    /** 服务端算的总笔数（totals.count）。 */
    val total: Int,
    /** 实际拿回来几笔（items.size）。比 total 少 = 被 limit 截断了。 */
    val fetched: Int,
) {
    val truncated: Boolean get() = fetched < total
}

/** 一次导出的范围 + 笔数。范围句、文件名、主钮文案都从它推。iOS: MoneyExportKit.swift:66 */
data class MoneyExportRange(
    val segs: List<MoneyExportSeg>,
    val count: Int,
    /** 「待补票」那一筛开着时的口径；null = 没开。只用来在范围句里说出「被截断了」。 */
    val rcpt: MoneyExportRcpt? = null,
) {
    /**
     * 面板上那句范围话。笔数在这句里（翻开关后整张面板重建，这句和按钮上的数字要一起动）。
     * ⚠️ 待补票被 limit 截断时明说 —— 不让一个被截断的列表看起来是全部。
     */
    val sentence: String
        get() {
            val scope = segs.joinToString(" · ") { it.spoken }
            var s = "要导的是：$scope · 共 $count 笔。"
            val r = rcpt
            if (r != null && r.truncated) {
                s += "待补票一共 ${r.total} 笔，这里只拿到了最近的 ${r.fetched} 笔，没拿到的不在这份文件里。"
            }
            return s + "生成一个 csv，接着挑去处。"
        }

    /** 主操作那颗钮上的字。 */
    val actionTitle: String get() = "导出这 $count 笔"

    /** 文件名（不含扩展名）。⚠️ 唯一会离开这个 App 的那一段字，必须跟着范围走，不能是写死的月份。 */
    val fileBase: String get() = "umbra-账目-" + segs.joinToString("-") { MoneyExportKit.safeFileSeg(it.slug) }
}

/** iOS: MoneyExportKit.swift:105 */
object MoneyExportKit {

    /**
     * 按范围拼出那几段。ymSpoken 由调用方传 MoneyFmt.ymLabel(ym)（与首页那一行同一个函数）。
     * rcpt 非空时第一段不是月份，是「待补票 · 截至某季」。iOS: MoneyExportKit.swift:117
     */
    fun segments(
        scope: MoneyExportScope, ym: String, ymSpoken: String,
        dirLabel: String?, catLabel: String?,
        rcpt: MoneyExportRcpt? = null,
    ): List<MoneyExportSeg> = when (scope) {
        // 全部那一档不带月份 —— 带了就是在文件名上说假话。
        MoneyExportScope.All -> listOf(MoneyExportSeg("全部账目", "全部"))
        MoneyExportScope.Filtered -> {
            // ⚠️ 待补票那一筛的源跨好几个季度，写月份就是在文件名上说假话。
            val out = mutableListOf(
                rcpt?.let { MoneyExportSeg("待补票（截至 ${it.throughSpoken}，含往季）", "待补票-截至${it.through}") }
                    ?: MoneyExportSeg(ymSpoken, ym)
            )
            if (!dirLabel.isNullOrEmpty()) out.add(MoneyExportSeg(dirLabel, dirLabel))
            if (!catLabel.isNullOrEmpty()) out.add(MoneyExportSeg(catLabel, catLabel))
            out
        }
    }

    /** 文件名里不许出现的那几个（不含控制字符类，那一类另按 Unicode Cc 判）。 */
    private const val BAD_FILE_CHARS = "/\\:*?\"<>|\r\n\t"

    /**
     * 把一段文本收成能安全当文件名的一段。分类名是用户自己起的，什么都可能有：
     * `/` 会被当路径分隔符，前导 `.` 得到隐藏文件，控制字符让收件人看到一个没法输入的名字。
     * 非法字符逐个换成 `_`（不是删掉）；清空了退回「未命名」，不返回空串。
     *
     * 「控制字符」按 Unicode Cc 类判（iOS CharacterSet.controlCharacters 是 Cc + Cf，
     * 这里按约定只取 Cc —— 零宽连接符这类 Cf 字符会留下）。
     * 长度上限 40 按 code point 数（iOS 按 Character ≈ 字素簇；只在带组合符 / emoji 的名字上有出入）。
     * iOS: MoneyExportKit.swift:145
     */
    fun safeFileSeg(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            if ((cp < 0x10000 && cp.toChar() in BAD_FILE_CHARS) || Character.getType(cp) == Character.CONTROL.toInt()) {
                sb.append('_')
            } else {
                sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        // iOS .whitespaces = Zs 类 + 制表符（不含换行；换行此时已经换成 _ 了）
        var s = sb.toString().trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }
        while (s.startsWith(".")) s = s.substring(1)
        if (s.codePointCount(0, s.length) > 40) s = s.substring(0, s.offsetByCodePoints(0, 40))
        return s.ifEmpty { "未命名" }
    }

    // ── 临时文件（台账 export-plaintext-left-in-tmp）──

    /** 一次导出的所有文件都放进一个以它开头的临时目录里：为了删得干净，按前缀整个删。 */
    const val TMP_DIR_PREFIX = "umbra-money-export-"

    /** 旧版散在 tmp 根上的那几样的前缀（= MoneyExportRange.fileBase 的开头）。 */
    const val LEGACY_PREFIX = "umbra-账目-"

    /**
     * tmp 下这一项是不是导出留下的（要清掉的）。⚠️ 只认这个前缀，不认 `umbra-` 这类更宽的 ——
     * 扫宽了就是删别人正在分享的文件。另认旧版的形状（升级上来的手机里还躺着那几份明文）。
     * iOS: MoneyExportKit.swift:170
     */
    fun isExportTmp(name: String): Boolean =
        (name.startsWith(TMP_DIR_PREFIX) && name.length > TMP_DIR_PREFIX.length) ||
            (name.startsWith(LEGACY_PREFIX) && name.length > LEGACY_PREFIX.length)

    // ── CSV ──

    /** CSV 的列头。**与 PC 的 importKit.COLUMNS 逐字同源**，顺序也一样 —— 导出的这份要能在 PC 上原样导回去。 */
    val columns: List<String> = listOf(
        "日期时间", "方向", "分类", "二级分类", "金额（元）", "商家/备注", "订单号", "附件", "流水ID",
    )

    /**
     * 一笔账在 CSV 里的样子。不用 DTO：映射在导出那一屏做，这里保持纯。
     * at 写本地墙上时间「2026-08-31 23:30」（PC cellToLocal 按本地时间解，两端同一个约定才闭环）。
     * iOS: MoneyExportKit.swift:189
     */
    data class Row(
        val at: String,
        /** 支出 / 收入 */
        val direction: String,
        val cat: String,
        val sub: String,
        /** "38.00" */
        val amountYuan: String,
        val merchant: String,
        val orderNo: String,
        /** 分号隔开的附件名 */
        val atts: String,
        val id: String,
    ) {
        /** 这一行进 CSV 的九格。文本格先过 neutralizeFormula，金额格原样（-38.00 是合法负数，加引号就成文字）。 */
        val cells: List<String>
            get() {
                val t = ::neutralizeFormula
                return listOf(t(at), t(direction), t(cat), t(sub), amountYuan, t(merchant), t(orderNo), t(atts), t(id))
            }
    }

    /** 整数分 → "38.00"。不用地区格式化 —— 千分位里的逗号进了 CSV 就是一个分隔符。 */
    fun yuanPlain(cents: Long): String {
        val neg = cents < 0
        val v = Math.abs(cents)
        return (if (neg) "-" else "") + "${v / 100}." + String.format(Locale.ROOT, "%02d", v % 100)
    }

    /**
     * 公式注入中和（umbra-ios#26；与 PC buildCsv 逐字一致）：文本格首字符是 = + - @ \t \r 之一就前面加一个 '。
     * 只看首字符：「楼下-那家」不动。商家 / 备注里有账单导入、截图认字写进来的外部文字。
     */
    fun neutralizeFormula(s: String): String {
        val f = s.firstOrNull() ?: return s
        return if (f in formulaLeads) "'$s" else s
    }

    /** 公式前导符。和 PC 端同一张表，增删要两端一起改。 */
    val formulaLeads: Set<Char> = setOf('=', '+', '-', '@', '\t', '\r')

    /** 一格 → CSV 字面。含逗号 / 引号 / 换行时加引号，引号自身翻倍。 */
    fun csvCell(s: String): String {
        if (!(s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r"))) return s
        return "\"" + s.replace("\"", "\"\"") + "\""
    }

    /** 整份 CSV 的文本。⚠️ 开头带 UTF-8 BOM（不加 Excel 打开中文是乱码；PC 读的时候会剥掉），行尾 \r\n。 */
    fun csvText(rows: List<Row>): String {
        val out = StringBuilder("﻿")
        out.append(columns.joinToString(",") { csvCell(it) }).append("\r\n")
        for (r in rows) out.append(r.cells.joinToString(",") { csvCell(it) }).append("\r\n")
        return out.toString()
    }
}
