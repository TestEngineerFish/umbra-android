// 扫发票 PDF 那一件的纯逻辑（B2d① / invoicePdfIsNotAnAttachment）—— 照抄 iOS Features/Money/InvoicePdfKit.swift。纯 Kotlin。
//
// 这份 PDF 不是附件：它是票据组里那四个字段的来源。
// > 一个被解析过的文件要能点回去看解析出了什么，而这件事一张附件缩略图做不到。
// 所以它在票据那一组里出一条文件行，不占附件那四张（服务端 kind='invoice'）。
// InvoiceFields 已在 MoneyModels.kt（DTO 要解它）。
package com.geek.umbra.feature.money

import java.util.Locale

/** 一次扫描的结果，连同它的来源文件。missing 是服务端给的没读出来的字段名。iOS: InvoicePdfKit.swift:26 */
data class InvoiceScan(
    val fileId: String,
    val filename: String,
    val fields: InvoiceFields,
    val missing: List<String>,
)

/** 要往哪几格里填。**只填空着的那几格。** iOS: InvoicePdfKit.swift:35 */
data class InvoiceFillPlan(
    val no: String? = null,
    val cents: Long? = null,
    val rateBp: Int? = null,
) {
    val isEmpty: Boolean get() = no == null && cents == null && rateBp == null
}

/** iOS: InvoicePdfKit.swift:42 */
object InvoicePdfKit {
    /** 四个字段的键。**顺序就是稿上那一行的顺序**，不要按字母排。 */
    val fieldKeys: List<String> = listOf("invoice_no", "invoice_date", "total_cents", "rate_bp")

    /** 字段在屏上的名字。 */
    fun fieldLabel(key: String): String = when (key) {
        "invoice_no" -> "发票号码"
        "invoice_date" -> "开票日期"
        "total_cents" -> "价税合计"
        else -> "税率"
    }

    /** 读出来几个。文件行那句「读出 N 个字段」用它。 */
    fun readCount(scan: InvoiceScan): Int =
        maxOf(0, fieldKeys.size - scan.missing.count { it in fieldKeys })

    /** 文件行那一句。 */
    fun readLine(scan: InvoiceScan): String = "读出 ${readCount(scan)} 个字段：号码、开票日期、价税合计、税率"

    /**
     * 哪几格该被填上。⚠️⚠️ 只填空着的那几格，不覆盖人已经填过的：
     * > 一个用来核对的动作，一旦顺手改了被核对的那一方，它就不再是核对了。
     * ⚠️⚠️ 开票日期不写进这笔账的时间，一格都不写（票上那一天 ≠ 钱动的那一天）。
     * iOS: InvoicePdfKit.swift:78
     */
    fun fillPlan(scan: InvoiceScan, currentNo: String, currentCents: Long?, currentRateBp: Int?): InvoiceFillPlan {
        val no = scan.fields.invoice_no
        val currentEmpty = currentNo.trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }.isEmpty()
        return InvoiceFillPlan(
            no = if (!no.isNullOrEmpty() && currentEmpty) no else null,
            cents = if (currentCents == null) scan.fields.total_cents else null,
            rateBp = if (currentRateBp == null) scan.fields.rate_bp else null,
        )
    }

    /**
     * 「看解析结果」面板里一格上念的那个值。null = 没读出来。
     * ⚠️ 金额走整数分，Double 一次都不碰 —— 核对用的屏上少了一分钱的数，比不显示更坏。
     * 税率非整百时用 Double 的十进制写法（iOS "\(Double(bp)/100)"，如 6.5%；与 Kotlin Double.toString 在这个量级上一致）。
     * iOS: InvoicePdfKit.swift:94
     */
    fun valueText(scan: InvoiceScan, key: String): String? = when (key) {
        "invoice_no" -> scan.fields.invoice_no?.ifEmpty { null }
        "invoice_date" -> scan.fields.invoice_date?.ifEmpty { null }
        "total_cents" -> scan.fields.total_cents?.let { c ->
            "¥${c / 100}." + String.format(Locale.ROOT, "%02d", Math.abs(c % 100))
        }
        else -> scan.fields.rate_bp?.let { bp ->
            if (bp % 100 == 0) "${bp / 100}%" else "${bp.toDouble() / 100}%"
        }
    }

    /** 从一笔账的附件里挑出那份发票 PDF。认 kind == "invoice"，不靠文件名猜（老服务端不回 kind → null，就是「还没扫过」）。 */
    fun pickInvoice(atts: List<MoneyAttDTO>): MoneyAttDTO? = atts.firstOrNull { it.kind == "invoice" }

    /** 附件那四张里该数哪几张。发票 PDF 不在内（和服务端同一条口径）。 */
    fun photos(atts: List<MoneyAttDTO>): List<MoneyAttDTO> = atts.filter { it.kind != "invoice" }

    /** 附件区底下那一句（稿明写）。只在经营账本上出。 */
    const val NOT_AN_ATTACHMENT_HINT = "发票 PDF 不占这四张，它在票据那一组里。"

    /** 「看解析结果」面板里，一格没读出来时说的话。 */
    const val MISSING_TEXT = "没读出来"

    /** 已填过的格子没被动过 —— 这一句是那个面板的重点。 */
    const val ONLY_EMPTY_HINT = "只填了原本空着的格子 —— 你已经填过的没有被改动。"

    /** 三步各有各的失败，说出来的是哪一步失败了：一句把可重试和不可重试合并的错误，会让人一直重试下去。 */
    const val UPLOAD_FAIL_TEXT = "这份 PDF 没传上去 —— 可以再试一次。"
    const val PARSE_FAIL_TEXT =
        "这份 PDF 读不出字段：它可能是扫描件（只有图，没有文字层），或者不是发票。手填也一样能记。"

    /** 第四种失败：读出来了、字段也填上了，但 PDF 没挂到这笔账上（台账 2026-10-01 Money e）。重试得动。 */
    const val ATTACH_FAIL_TEXT =
        "字段已经填上了，但这份 PDF 没挂到这笔账上 —— 联网后再扫一次同一份就能挂上。"
}
