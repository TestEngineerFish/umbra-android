// 聊天气泡里表格的**尺寸判据**（iOS: Features/Chat/ChatTableRules.swift，设计侧批次 227 §二 1、228 §三.4 / tokens `chatTable`）。
//
// 纯判据，用例照抄 iOS Tools/swifttests/ChatTableRulesTests.swift。单位是 dp（iOS 的 pt），数值同稿。
package com.geek.umbra.feature.chat

object ChatTableRules {
    /** 一列最窄 72（含格内左右内边距）。窄列不设下限会缩成一条缝。 */
    const val COL_MIN = 72f
    /** 一列到 220 就在格内折行。一格写一大段时不设上限，表会被撑成几百宽、横滑半天读一句话。 */
    const val COL_MAX = 220f
    /** 格内字号（稿 14）。 */
    const val CELL_SIZE = 14f
    /** 格内内边距：上下 5、左右 10。 */
    const val PAD_V = 5f
    const val PAD_H = 10f
    /** 行高 1.5。 */
    const val LINE_HEIGHT = 1.5f

    /**
     * 各列的宽度（iOS: ChatTableRules.swift:52）：每列夹到 [72,220]；加起来比外框窄 → 余量全给第一列；
     * 比外框宽 → 不压任何一列（交给框内横滑）。available 为无穷 = 外框宽未知。
     */
    fun columnWidths(ideal: List<Float>, available: Float): List<Float> {
        if (ideal.isEmpty()) return emptyList()
        val w = ideal.map { minOf(maxOf(it, COL_MIN), COL_MAX) }.toMutableList()
        val sum = w.sum()
        if (available.isFinite() && sum < available) w[0] += available - sum
        return w
    }

    /** 气泡正文栏最终多宽：表放得下平时的宽就平时的宽；放不下撑到刚好放下，最多撑满。 */
    fun bodyWidth(proposed: Float, released: Float, tableNatural: Float): Float {
        val normal = maxOf(0f, proposed - released)
        return minOf(proposed, maxOf(normal, tableNatural))
    }

    /** 一张表的自然宽：各列宽之和 + 列间格线 + 左右外框。 */
    fun naturalWidth(columns: List<Float>, line: Float): Float {
        if (columns.isEmpty()) return 0f
        return columns.sum() + (columns.size - 1) * line + 2 * line
    }

    /** 这一列要不要用等宽数字：判据是**列内容是数字**（只看数据行；空格不算反例；全空不是）。 */
    fun isNumericColumn(t: MarkdownTable, col: Int): Boolean {
        var any = false
        for (row in t.rows) {
            if (col >= row.size) continue
            val c = row[col]
            if (c.trim { MarkdownTable.isWs(it) }.isEmpty()) continue
            if (!isNumericCell(c)) return false
            any = true
        }
        return any
    }

    private val numRe = Regex("""^[+\-−]?[¥￥$€£]?[+\-−]?(\d{1,3}(,\d{3})+|\d+)(\.\d+)?%?$""")

    /** 一格是不是「一个数」：可带正负号、货币、千分位、小数、末尾 %；可被 ** / ` 包着。刻意不收带单位的。 */
    fun isNumericCell(raw: String): Boolean {
        var s = raw.trim { MarkdownTable.isWs(it) }
        for (mark in listOf("**", "__", "`", "*", "_", "~~")) {
            while (s.length > 2 * mark.length && s.startsWith(mark) && s.endsWith(mark)) {
                s = s.drop(mark.length).dropLast(mark.length).trim { MarkdownTable.isWs(it) }
            }
        }
        // Swift 的 \d 默认只认 ASCII 之外也认其它数字，这里只认 ASCII 足够（服务端金额都是 ASCII）
        return numRe.matches(s)
    }

    /** 这段正文里有没有表 —— 先看有没有 `|` 再拆块（流式每来一个字都会问一次）。 */
    fun hasTable(raw: String): Boolean {
        if (!raw.contains("|")) return false
        return ChatMarkdown.parse(raw).any { it is MdBlock.Table }
    }
}
