// 聊天 Markdown 的**块级拆分**（iOS: Features/Chat/MarkdownBlocks.swift）：段落 / 标题 / 列表 / 代码 / 引用 / 分隔线 / 表格。
//
// 纯判据，用例照抄 iOS Tools/swifttests/MarkdownBlocksTests.swift（参照系统 `.full` 解析器的那几条在 Android 没有对等物，略去，
// 我们这一侧的期望值照抄）。
//
// 为什么自己拆块：秘书的回复里全是「**开发步骤**：」「1. 需求分析」和对比表，不处理的话满屏星号、井号和竖线。
// 刻意不引第三方 Markdown 库：聊天里出现的语法就这么几种；渲染不了的语法宁可原样显示文本，也不能吞掉用户的字。
package com.geek.umbra.feature.chat

/** 一段 Markdown 拆出来的块。顺序即渲染顺序。 */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    /** level 1~6。 */
    data class Heading(val level: Int, val text: String) : MdBlock
    /** marker：无序为「•」，有序为「1.」。indent 0~3。 */
    data class ListItem(val marker: String, val text: String, val indent: Int) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
    /** 单元格里存的是**未解析的行内 Markdown 原文**，由视图逐格交给 ChatInline（链接闸只在那一处）。 */
    data class Table(val table: MarkdownTable) : MdBlock
}

/**
 * 一张 Markdown 表格（iOS: MarkdownBlocks.swift:340）。
 * 不按 GFM：行内代码里的 `|` 不切、多出的格不截、分隔行格数不一致也认（与 PC 同口径，「绝不吞内容」）。
 */
data class MarkdownTable(val header: List<String>, val rows: List<List<String>>, val aligns: List<Align>) {
    enum class Align { Leading, Center, Trailing }

    /** 画的时候要几列：表头和各数据行里最宽的那个。 */
    val columnCount: Int get() = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)

    fun align(col: Int): Align = if (col < aligns.size) aligns[col] else Align.Leading

    companion object {
        /** Swift `.whitespaces`（Zs 类空白 + 制表符，含全角空格 U+3000、不含换行）。 */
        internal fun isWs(c: Char) = c == '\t' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()
        internal fun String.trimWs() = trim { isWs(it) }

        /** 行首行尾都要有竖线（PC `/^\s*\|.*\|\s*$/`）：一句话里顺带出现的 `a | b` 不能被当成表。 */
        fun isRow(line: String): Boolean {
            val t = line.trimWs()
            return t.length >= 2 && t.startsWith("|") && t.endsWith("|")
        }

        /** 只由空白、`:`、`|`、`-` 组成，且至少有一个 `-`。不要求格数和表头一致（与 PC 同）。 */
        fun isSeparator(line: String): Boolean {
            val t = line.trimWs()
            if (t.isEmpty() || !t.contains("-")) return false
            return t.all { it == ':' || it == '|' || it == '-' || isWs(it) }
        }

        /** 从 lines[start] 起能不能读出一张表；能的话返回表和表后第一行的下标。流式只到表头（分隔行还没来）返回 null。 */
        fun read(lines: List<String>, start: Int): Pair<MarkdownTable, Int>? {
            if (start + 1 >= lines.size || !isRow(lines[start]) || !isSeparator(lines[start + 1])) return null
            val header = splitRow(lines[start])
            val aligns = splitRow(lines[start + 1]).map { alignOf(it) }
            val rows = mutableListOf<List<String>>()
            var i = start + 2
            while (i < lines.size && isRow(lines[i])) { rows += splitRow(lines[i]); i++ }
            return MarkdownTable(header, rows, aligns) to i
        }

        private fun alignOf(cell: String): Align {
            val c = cell.trimWs()
            if (c.startsWith(":") && c.endsWith(":") && c.length > 1) return Align.Center
            if (c.endsWith(":")) return Align.Trailing
            return Align.Leading
        }

        /**
         * 一行 `| a | b |` → 各格（trim 过）。`\|` 是格子里的字面竖线（去掉反斜杠）；成对反引号里的 `|` 不切
         * （偏离 GFM：GFM 照切会丢后半截）；其它转义原样留给行内解析器。iOS: MarkdownBlocks.swift:413
         */
        fun splitRow(line: String): List<String> {
            var s = line.trimWs()
            if (s.startsWith("|")) s = s.drop(1)
            if (s.endsWith("|") && !escapedAt(s, s.length - 1)) s = s.dropLast(1)
            val chars = s.toList()
            val cells = mutableListOf<String>()
            val cur = StringBuilder()
            var i = 0
            while (i < chars.size) {
                val c = chars[i]
                if (c == '\\' && i + 1 < chars.size) {
                    if (chars[i + 1] == '|') cur.append('|') else { cur.append(c); cur.append(chars[i + 1]) }
                    i += 2; continue
                }
                if (c == '`') {
                    var n = 0
                    while (i + n < chars.size && chars[i + n] == '`') n++
                    val close = closingTicks(chars, i + n, n)
                    if (close != null) {
                        var j = i
                        while (j < close + n) {
                            if (chars[j] == '\\' && j + 1 < close && chars[j + 1] == '|') { cur.append('|'); j += 2; continue }
                            cur.append(chars[j]); j++
                        }
                        i = close + n
                    } else {
                        repeat(n) { cur.append('`') }
                        i += n
                    }
                    continue
                }
                if (c == '|') { cells += cur.toString().trimWs(); cur.clear(); i++; continue }
                cur.append(c); i++
            }
            cells += cur.toString().trimWs()
            return cells
        }

        private fun escapedAt(s: String, idx: Int): Boolean {
            var n = 0; var k = idx
            while (k > 0) { k--; if (s[k] == '\\') n++ else break }
            return n % 2 == 1
        }

        private fun closingTicks(chars: List<Char>, from: Int, count: Int): Int? {
            var i = from
            while (i < chars.size) {
                if (chars[i] == '`') {
                    var n = 0
                    while (i + n < chars.size && chars[i + n] == '`') n++
                    if (n == count) return i
                    i += n
                } else i++
            }
            return null
        }
    }
}

object ChatMarkdown {
    /** 把整段文本拆成块。纯函数（iOS: MarkdownBlocks.swift:494）。 */
    fun parse(raw: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val paragraph = mutableListOf<String>()
        val code = mutableListOf<String>()
        var inCode = false
        fun flushParagraph() {
            val text = paragraph.joinToString("\n").trim()
            paragraph.clear()
            if (text.isNotEmpty()) out += MdBlock.Paragraph(text)
        }
        // Swift `components(separatedBy: .newlines)`：\r\n 会切出一个空行，这里按 \r\n / \r / \n 都当换行
        val lines = raw.split("\r\n", "\n", "\r")
        var idx = 0
        while (idx < lines.size) {
            val line = lines[idx]
            idx++
            val trimmed = line.trim { it == ' ' || it == '\t' || it == '　' }
            if (trimmed.startsWith("```")) {
                if (inCode) {
                    out += MdBlock.Code(code.joinToString("\n")); code.clear(); inCode = false
                } else { flushParagraph(); inCode = true }
                continue
            }
            if (inCode) { code += line; continue }
            if (trimmed.isEmpty()) { flushParagraph(); continue }
            // 表格放在分隔线之前：`| a |` 下面那行 `---` 是这张表的分隔行，不是分隔线（PC 同序）
            val t = MarkdownTable.read(lines, idx - 1)
            if (t != null) { flushParagraph(); out += MdBlock.Table(t.first); idx = t.second; continue }
            if (trimmed == "---" || trimmed == "***" || trimmed == "___") { flushParagraph(); out += MdBlock.Rule; continue }
            val h = heading(trimmed)
            if (h != null) { flushParagraph(); out += h; continue }
            val li = listItem(line)
            if (li != null) { flushParagraph(); out += li; continue }
            if (trimmed.startsWith("> ")) { flushParagraph(); out += MdBlock.Quote(trimmed.drop(2)); continue }
            paragraph += line
        }
        // 没闭合的围栏也要把攒下的内容吐出来，不能吞掉用户的文字。
        if (inCode && code.isNotEmpty()) out += MdBlock.Code(code.joinToString("\n"))
        flushParagraph()
        return out
    }

    /** `# 标题`：# 后面必须有空格（「#标签」不是标题）。 */
    private fun heading(trimmed: String): MdBlock? {
        val level = trimmed.takeWhile { it == '#' }.length
        if (level < 1 || level > 6) return null
        val rest = trimmed.drop(level)
        if (!rest.startsWith(" ")) return null
        return MdBlock.Heading(level, rest.trim { MarkdownTable.isWs(it) })
    }

    /** 列表项：缩进按行首空格数折算，两格一级，最多 3 级。有序「1. 」「12) 」。 */
    private fun listItem(line: String): MdBlock? {
        val leading = line.takeWhile { it == ' ' || it == '\t' }.length
        val indent = minOf(leading / 2, 3)
        val trimmed = line.trim { it == ' ' || it == '\t' || it == '　' }
        for (b in listOf("- ", "* ", "+ ")) if (trimmed.startsWith(b)) return MdBlock.ListItem("•", trimmed.drop(2), indent)
        val digits = trimmed.takeWhile { it.isDigit() }
        if (digits.isNotEmpty()) {
            val rest = trimmed.drop(digits.length)
            if (rest.startsWith(". ") || rest.startsWith(") ")) return MdBlock.ListItem("$digits.", rest.drop(2), indent)
        }
        return null
    }
}
