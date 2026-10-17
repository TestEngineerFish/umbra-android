package com.geek.umbra.feature.chat

import com.geek.umbra.feature.chat.MarkdownTable.Align
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 照抄 iOS Tools/swifttests/MarkdownBlocksTests.swift（拿系统 `.full` 解析器当参照答案的那几条 Android 没有对等物，
 * 只留「我们这一侧」的期望值）。
 */
class MarkdownBlocksTest {
    private fun kinds(md: String) = ChatMarkdown.parse(md).map {
        when (it) {
            is MdBlock.Paragraph -> "p:${it.text}"
            is MdBlock.Heading -> "h${it.level}:${it.text}"
            is MdBlock.ListItem -> "li:${it.text}"
            is MdBlock.Code -> "code:${it.text}"
            is MdBlock.Quote -> "q:${it.text}"
            MdBlock.Rule -> "hr"
            is MdBlock.Table -> "tbl:${it.table.columnCount}x${it.table.rows.size}"
        }
    }

    private fun table(md: String) = ChatMarkdown.parse(md).filterIsInstance<MdBlock.Table>().firstOrNull()?.table
    private fun render(cell: String) = ChatInline.parse(cell)

    @Test fun recognize() {
        assertEquals(listOf("tbl:2x2"), kinds("| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |"))
        assertEquals(listOf("p:说明：", "tbl:2x1", "p:后面接着说"), kinds("说明：\n| a | b |\n|---|---|\n| 1 | 2 |\n后面接着说"))
        assertEquals(listOf("tbl:2x1", "li:一项"), kinds("| a | b |\n|---|---|\n| 1 | 2 |\n\n- 一项"))
        assertEquals(listOf("p:| a | b |"), kinds("| a | b |"))
        assertEquals(listOf("p:| a | b |\n|"), kinds("| a | b |\n|"))
        assertEquals(listOf("tbl:2x0"), kinds("| a | b |\n|--"))
        assertEquals(listOf("tbl:2x0"), kinds("| a | b |\n|---|---|"))
        assertEquals(listOf("tbl:2x0", "p:| 1 | 2"), kinds("| a | b |\n|---|---|\n| 1 | 2"))
        assertEquals(listOf("p:a | b\n---|---\n1 | 2"), kinds("a | b\n---|---\n1 | 2"))
        assertEquals(listOf("p:| 选项一 | 选项二 |\n下一句"), kinds("| 选项一 | 选项二 |\n下一句"))
        assertEquals(listOf("tbl:2x1"), kinds("| a | b |\n|---|\n| 1 | 2 |"))
        assertEquals(listOf("tbl:1x1"), kinds("| a |\n---\n| 1 |"))
        assertEquals(listOf("hr"), kinds("---"))
        assertEquals(listOf("code:| a | b |\n|---|---|"), kinds("```\n| a | b |\n|---|---|\n```"))
        assertEquals(1, kinds("| a | b |\n| :--- | ---: |\n| 1 | 2 |").size)
    }

    @Test fun otherBlocks() {
        assertEquals(listOf("h2:标题", "p:#标签 不是标题"), kinds("## 标题\n#标签 不是标题"))
        assertEquals(listOf("li:一", "li:二", "q:引用"), kinds("- 一\n2. 二\n> 引用"))
        assertEquals("未闭合围栏也吐内容", listOf("p:前", "code:x = 1"), kinds("前\n```\nx = 1"))
        val li = ChatMarkdown.parse("    - 深").first() as MdBlock.ListItem
        assertEquals(2, li.indent)
        assertEquals("12.", (ChatMarkdown.parse("12) 十二").first() as MdBlock.ListItem).marker)
    }

    @Test fun split() {
        assertEquals(listOf("a", "b", "c"), MarkdownTable.splitRow("| a | b | c |"))
        assertEquals(listOf("a", "b"), MarkdownTable.splitRow("|a|b|"))
        assertEquals(listOf("a", "", "c"), MarkdownTable.splitRow("| a |  | c |"))
        assertEquals(listOf(""), MarkdownTable.splitRow("||"))
        assertEquals(listOf("x | y", "z"), MarkdownTable.splitRow("| x \\| y | z |"))
        assertEquals(listOf("`p|q`", "z"), MarkdownTable.splitRow("| `p|q` | z |"))
        assertEquals(listOf("`p|q`", "z"), MarkdownTable.splitRow("| `p\\|q` | z |"))
        assertEquals(listOf("``a`|b``", "z"), MarkdownTable.splitRow("| ``a`|b`` | z |"))
        assertEquals(listOf("`a", "b"), MarkdownTable.splitRow("| `a | b |"))
        assertEquals(listOf("a \\* b", "c"), MarkdownTable.splitRow("| a \\* b | c |"))
        assertEquals(listOf("a |"), MarkdownTable.splitRow("| a \\|"))
        assertEquals(listOf("名称", "金额"), MarkdownTable.splitRow("| 名称 | 金额 |"))
        assertEquals(listOf("甲｜乙", "丙"), MarkdownTable.splitRow("| 甲｜乙 | 丙 |"))
        assertEquals(listOf("全角空格", "b"), MarkdownTable.splitRow("|　全角空格　| b |"))
        val t = table("| 左 | 中 | 右 | 默认 |\n|:--|:-:|--:|---|\n| 1 | 2 | 3 | 4 |")!!
        assertEquals(listOf(Align.Leading, Align.Center, Align.Trailing, Align.Leading), t.aligns)
        assertEquals(Align.Leading, t.align(9))
        val r = table("| a | b |\n|---|---|\n| 1 |\n| 1 | 2 | 3 |")!!
        assertEquals(3, r.columnCount)
        assertEquals(listOf(listOf("1"), listOf("1", "2", "3")), r.rows)
        assertEquals("支持中文宽字符：一二三四五六七八九十", table("| 项目 | 说明 |\n|---|---|\n| 记账 | 支持中文宽字符：一二三四五六七八九十 |")!!.rows.first().last())
    }

    @Test fun links() {
        val t = table("| 名称 | 链接 |\n|---|---|\n| 拨号 | [https://apple.com](tel:10086) |\n| 文档 | [文档](https://a.com/doc) |\n| 伪装 | [apple.com](https://evil.example) |")!!
        val tel = render(t.rows[0][1])
        assertEquals(false, tel.any { it.link != null })
        assertEquals("https://apple.com", tel.joinToString("") { it.text })
        assertEquals(true, render(t.rows[1][1]).any { it.link != null })
        assertEquals(false, render(t.rows[2][1]).any { it.link != null })
        val u = table("| a |\n|---|\n| [x \\| y](https://a.com) |")!!
        assertEquals(listOf(listOf("[x | y](https://a.com)")), u.rows)
        assertEquals("x | y", render(u.rows[0][0]).joinToString("") { it.text })
    }

    /** iOS 用例里「系统会吞字」那三处差异：我们这一侧不吞。 */
    @Test fun noSwallow() {
        assertEquals(listOf(listOf("`p|q`", "z")), table("| a | b |\n|---|---|\n| `p|q` | z |")!!.rows)
        assertEquals(listOf(listOf("1", "2", "3")), table("| a | b |\n|---|---|\n| 1 | 2 | 3 |")!!.rows)
        assertEquals(true, table("| a | b |\n|---|\n| 1 | 2 |") != null)
        // 规整的表：逐格画出来的纯文字（iOS 参照用例里与系统 .full 相同的那几张）
        val t = table("| a | b | c |\n|:--|:-:|--:|\n| 1 | **2** | `3` |")!!
        assertEquals(listOf("1", "2", "3"), t.rows[0].map { c -> render(c).joinToString("") { it.text } })
        val t2 = table("| x \\| y | z |\n|---|---|\n| `p\\|q` | [链接](https://a.com) |")!!
        assertEquals(listOf("x | y", "z"), t2.header.map { c -> render(c).joinToString("") { it.text } })
        assertEquals(listOf("p|q", "链接"), t2.rows[0].map { c -> render(c).joinToString("") { it.text } })
    }
}
