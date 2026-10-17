// 行内 Markdown 解析（iOS: Features/Chat/UmbraMarkdown.swift `UmbraMarkdown.inline`）。
//
// iOS 用系统的 `AttributedString(markdown:, .inlineOnlyPreservingWhitespace)`；Android 没有对等的系统解析器，
// 这里手写一个够聊天用的子集（CommonMark 行内规则的简化版）：反斜杠转义、`代码`、**粗体** / __粗体__、*斜体* / _斜体_、
// ~~删除线~~、[文字](目标)、<scheme:自动链接>、![图](地址)（只显示替代文字）。不自动识别裸网址（系统解析器也不）。
// 空白与换行原样保留（preservingWhitespace）。
//
// ⚠️ 解析完**必须过 ChatLinkRules.sanitize**：不合格的链接剥成纯文本（umbra-ios#31）。parse() 已经替调用方做了。
// 纯 Kotlin，不 import android.*；链接闸的用例从这里走起（同 iOS 用例「从一段 Markdown 原文走起」）。
package com.geek.umbra.feature.chat

/** 一段行内文字及其样式。linkId 区分同一段里的不同链接（同一个链接被粗体拆成几段时 linkId 相同）。 */
data class InlineRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
    val linkId: Int = -1,
)

object ChatInline {
    private sealed interface Node {
        class Text(val s: String) : Node
        class Code(val s: String) : Node
        class Delim(val c: Char, var count: Int, val canOpen: Boolean, val canClose: Boolean) : Node
        class Link(val children: List<Node>, val url: String) : Node
        class Emph(val kind: Char, val strong: Boolean, val children: List<Node>) : Node
    }

    private const val PUNCT = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    /** 解析并过链接闸。 */
    fun parse(md: String): List<InlineRun> = ChatLinkRules.sanitize(parseRaw(md))

    /** 不过闸的原始解析（只给闸自己和用例用）。 */
    fun parseRaw(md: String): List<InlineRun> {
        val out = mutableListOf<InlineRun>()
        var nextLink = 0
        fun flatten(nodes: List<Node>, b: Boolean, i: Boolean, s: Boolean, link: String?, lid: Int) {
            for (n in nodes) when (n) {
                is Node.Text -> if (n.s.isNotEmpty()) out += InlineRun(n.s, b, i, false, s, link, lid)
                is Node.Code -> out += InlineRun(n.s, b, i, true, s, link, lid)
                is Node.Delim -> if (n.count > 0) out += InlineRun(n.c.toString().repeat(n.count), b, i, false, s, link, lid)
                is Node.Link -> { val id = nextLink++; flatten(n.children, b, i, s, n.url, id) }
                is Node.Emph -> when {
                    n.kind == '~' -> flatten(n.children, b, i, true, link, lid)
                    n.strong -> flatten(n.children, true, i, s, link, lid)
                    else -> flatten(n.children, b, true, s, link, lid)
                }
            }
        }
        flatten(inlines(md, allowLinks = true), false, false, false, null, -1)
        // 相邻同样式的片段合并，渲染时少几个 span
        val merged = mutableListOf<InlineRun>()
        for (r in out) {
            val last = merged.lastOrNull()
            if (last != null && last.copy(text = "") == r.copy(text = "")) merged[merged.lastIndex] = last.copy(text = last.text + r.text)
            else merged += r
        }
        return merged
    }

    /** 把一段文字切成节点，再按 CommonMark 的分隔符规则配对强调。 */
    private fun inlines(src: String, allowLinks: Boolean): List<Node> {
        val nodes = mutableListOf<Node>()
        val text = StringBuilder()
        fun flushText() { if (text.isNotEmpty()) { nodes += Node.Text(text.toString()); text.clear() } }
        var i = 0
        while (i < src.length) {
            val c = src[i]
            // 反斜杠转义：只转 ASCII 标点，其余反斜杠原样
            if (c == '\\' && i + 1 < src.length && src[i + 1] in PUNCT) { text.append(src[i + 1]); i += 2; continue }
            if (c == '`') {
                var n = 0
                while (i + n < src.length && src[i + n] == '`') n++
                val close = findTicks(src, i + n, n)
                if (close != null) {
                    flushText()
                    var body = src.substring(i + n, close).replace('\n', ' ')
                    // CommonMark：两端各有一个空格且不全是空格时各去一个
                    if (body.length >= 2 && body.startsWith(" ") && body.endsWith(" ") && body.isNotBlank()) body = body.substring(1, body.length - 1)
                    nodes += Node.Code(body)
                    i = close + n
                } else { text.append("`".repeat(n)); i += n }
                continue
            }
            if (c == '<') {
                val auto = autolink(src, i)
                if (auto != null && allowLinks) {
                    flushText(); nodes += Node.Link(listOf(Node.Text(auto.first)), auto.first); i = auto.second; continue
                }
            }
            val isImage = c == '!' && i + 1 < src.length && src[i + 1] == '['
            if ((c == '[' || isImage) && allowLinks) {
                val start = if (isImage) i + 1 else i
                val l = link(src, start)
                if (l != null) {
                    flushText()
                    val inner = inlines(l.first, allowLinks = false)
                    // 图片：只显示替代文字（聊天气泡里不内嵌外链图）
                    nodes += if (isImage) Node.Text(plain(inner)) else Node.Link(inner, l.second)
                    i = l.third; continue
                }
            }
            if (c == '*' || c == '_' || c == '~') {
                var n = 0
                while (i + n < src.length && src[i + n] == c) n++
                val prev = if (i > 0) src[i - 1] else ' '
                val next = if (i + n < src.length) src[i + n] else ' '
                val leftFlank = !next.isWhitespace() && (!isPunct(next) || prev.isWhitespace() || isPunct(prev))
                val rightFlank = !prev.isWhitespace() && (!isPunct(prev) || next.isWhitespace() || isPunct(next))
                val (open, close) = when (c) {
                    '_' -> (leftFlank && (!rightFlank || isPunct(prev))) to (rightFlank && (!leftFlank || isPunct(next)))
                    else -> leftFlank to rightFlank
                }
                if (c == '~' && n != 2) { text.append("~".repeat(n)); i += n; continue }
                flushText()
                nodes += Node.Delim(c, n, open, close)
                i += n; continue
            }
            text.append(c); i++
        }
        flushText()
        return processEmphasis(nodes)
    }

    private fun plain(nodes: List<Node>): String = buildString {
        for (n in nodes) when (n) {
            is Node.Text -> append(n.s)
            is Node.Code -> append(n.s)
            is Node.Delim -> append(n.c.toString().repeat(n.count))
            is Node.Link -> append(plain(n.children))
            is Node.Emph -> append(plain(n.children))
        }
    }

    private fun isPunct(c: Char) = c in PUNCT || Character.getType(c).let {
        it == Character.CONNECTOR_PUNCTUATION.toInt() || it == Character.DASH_PUNCTUATION.toInt() ||
            it == Character.START_PUNCTUATION.toInt() || it == Character.END_PUNCTUATION.toInt() ||
            it == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || it == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            it == Character.OTHER_PUNCTUATION.toInt()
    }

    /** 配对强调：从左往右找闭合分隔符，往回找同字符、能开的开分隔符（简化版 CommonMark process emphasis）。 */
    private fun processEmphasis(input: List<Node>): List<Node> {
        val nodes = input.toMutableList()
        var ci = 0
        while (ci < nodes.size) {
            val closer = nodes[ci] as? Node.Delim
            if (closer == null || !closer.canClose || closer.count == 0) { ci++; continue }
            var oi = ci - 1
            var found = -1
            while (oi >= 0) {
                val o = nodes[oi] as? Node.Delim
                if (o != null && o.c == closer.c && o.canOpen && o.count > 0) {
                    // 「三的倍数」规则：两边都能开能闭时，长度之和是 3 的倍数且各自不是 3 的倍数 → 不配
                    val bothWays = (o.canClose || closer.canOpen) && closer.c != '~'
                    val ruleOf3 = bothWays && (o.count + closer.count) % 3 == 0 && !(o.count % 3 == 0 && closer.count % 3 == 0)
                    val tildeOk = closer.c != '~' || (o.count == 2 && closer.count == 2)
                    if (!ruleOf3 && tildeOk) { found = oi; break }
                }
                oi--
            }
            if (found < 0) { ci++; continue }
            val opener = nodes[found] as Node.Delim
            val use = if (closer.c == '~') 2 else if (opener.count >= 2 && closer.count >= 2) 2 else 1
            val inner = nodes.subList(found + 1, ci).toList()
            val emph = Node.Emph(closer.c, use == 2 && closer.c != '~', inner)
            opener.count -= use; closer.count -= use
            // 把 opener..closer 之间换成一个 Emph 节点；用完的分隔符删掉
            repeat(ci - found - 1) { nodes.removeAt(found + 1) }
            nodes.add(found + 1, emph)
            var closerIdx = found + 2
            if (opener.count == 0) { nodes.removeAt(found); closerIdx-- }
            if (closer.count == 0) { nodes.removeAt(closerIdx); ci = closerIdx } else ci = closerIdx
        }
        return nodes
    }

    private fun findTicks(s: String, from: Int, count: Int): Int? {
        var i = from
        while (i < s.length) {
            if (s[i] == '`') {
                var n = 0
                while (i + n < s.length && s[i + n] == '`') n++
                if (n == count) return i
                i += n
            } else i++
        }
        return null
    }

    private val schemeRe = Regex("^[A-Za-z][A-Za-z0-9+.-]{1,31}:[^\\s<>]*$")

    /** `<scheme:xxx>` 自动链接。返回（目标, 结束下标）。 */
    private fun autolink(s: String, at: Int): Pair<String, Int>? {
        val end = s.indexOf('>', at + 1)
        if (end < 0) return null
        val body = s.substring(at + 1, end)
        if (!schemeRe.matches(body)) return null
        return body to end + 1
    }

    /**
     * `[文字](目标 "标题")`。返回（文字原文, 目标, 结束下标）；不成形返回 null（`[` 按字面）。
     * 文字里允许嵌套成对的方括号；目标里允许成对的圆括号（`javascript:alert(1)`）或用 `<…>` 包起来。
     */
    private fun link(s: String, at: Int): Triple<String, String, Int>? {
        var depth = 0
        var i = at
        var close = -1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { i += 2; continue }
            if (c == '`') {
                var n = 0
                while (i + n < s.length && s[i + n] == '`') n++
                val t = findTicks(s, i + n, n)
                i = if (t != null) t + n else i + n
                continue
            }
            if (c == '[') depth++
            if (c == ']') { depth--; if (depth == 0) { close = i; break } }
            i++
        }
        if (close < 0 || close + 1 >= s.length || s[close + 1] != '(') return null
        var j = close + 2
        while (j < s.length && (s[j] == ' ' || s[j] == '\t' || s[j] == '\n')) j++
        val dest = StringBuilder()
        if (j < s.length && s[j] == '<') {
            j++
            while (j < s.length && s[j] != '>' && s[j] != '\n') { dest.append(s[j]); j++ }
            if (j >= s.length || s[j] != '>') return null
            j++
        } else {
            var paren = 0
            while (j < s.length) {
                val c = s[j]
                if (c == '\\' && j + 1 < s.length && s[j + 1] in PUNCT) { dest.append(s[j + 1]); j += 2; continue }
                if (c.isWhitespace()) break
                if (c == '(') paren++
                if (c == ')') { if (paren == 0) break; paren-- }
                dest.append(c); j++
            }
        }
        while (j < s.length && (s[j] == ' ' || s[j] == '\t' || s[j] == '\n')) j++
        if (j < s.length && (s[j] == '"' || s[j] == '\'' || s[j] == '(')) {
            val endQ = if (s[j] == '(') ')' else s[j]
            val e = s.indexOf(endQ, j + 1)
            if (e < 0) return null
            j = e + 1
            while (j < s.length && (s[j] == ' ' || s[j] == '\t' || s[j] == '\n')) j++
        }
        if (j >= s.length || s[j] != ')') return null
        // 目标解不成一个带 scheme 的 URL：系统解析器同样不出链接，这里按纯文字（把文字部分留下）
        val target = dest.toString()
        return Triple(s.substring(at + 1, close), target, j + 1)
    }
}
