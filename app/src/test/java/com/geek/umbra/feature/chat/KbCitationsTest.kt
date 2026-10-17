package com.geek.umbra.feature.chat

import com.geek.umbra.net.UmbraJson
import org.junit.Assert.assertEquals
import org.junit.Test

/** 照抄 iOS Tools/swifttests/KnowledgeRulesTests.swift 的 citation / label / pageTrap / historyDecode 几组。 */
class KbCitationsTest {
    private fun p(json: String) = KbCitations.parse(UmbraJson.parseToJsonElement(json))
    private val good = """{"kb_id":"k1","path":"合同/采购.pdf","title":"采购合同","heading_path":"采购合同 › 第四条 付款","page":3}"""

    @Test fun citations() {
        val parsed = p("[$good]")
        assertEquals(1, parsed.size)
        assertEquals(3, parsed.first().page)
        assertEquals("k1", parsed.first().kbId)
        assertEquals(emptyList<KbCitation>(), KbCitations.parse(null))
        assertEquals(emptyList<KbCitation>(), p("\"oops\""))
        assertEquals(0, p("""[{"path":"a.md"}]""").size)
        assertEquals(0, p("""[{"kb_id":"k1"}]""").size)
        assertEquals(0, p("""[{"kb_id":"","path":"a.md"}]""").size)
        assertEquals(1, p("""[42, "x", $good]""").size)
        assertEquals(1, p("[$good, $good]").size)
        assertEquals(2, p("""[$good, {"kb_id":"k1","path":"合同/采购.pdf","title":"采购合同","heading_path":"采购合同 › 第四条 付款","page":4}]""").size)
        val noTitle = p("""[{"kb_id":"k1","path":"资料/说明书.md"}]""")
        assertEquals("说明书.md", noTitle.first().title)
        assertEquals(0, noTitle.first().page)
        assertEquals("", noTitle.first().headingPath)
        assertEquals(2, p("""[{"kb_id":"k","path":"a.pdf","page":2.0}]""").first().page)
        assertEquals(0, p("""[{"kb_id":"k","path":"a.pdf","page":-1}]""").first().page)
    }

    @Test fun labels() {
        fun c(t: String, h: String) = KbCitation("k", "p", t, h, 0)
        assertEquals("采购合同 › 第四条 付款", KbCitations.label(c("采购合同", "采购合同 › 第四条 付款")))
        assertEquals("采购合同", KbCitations.label(c("采购合同", "采购合同")))
        assertEquals("采购.pdf › 第四条 付款", KbCitations.label(c("采购.pdf", "第四条 付款")))
        assertEquals("采购.pdf", KbCitations.label(c("采购.pdf", "")))
        assertEquals("采购 › 采购合同 › 附件", KbCitations.label(c("采购", "采购合同 › 附件")))
    }

    @Test fun pageTrap() {
        fun page(v: String) = p("""[{"kb_id":"k","path":"a.pdf","page":$v}]""").first().page
        assertEquals(0, page("1e20"))
        assertEquals(0, page("-1e20"))
        assertEquals(0, page("2.5"))
        assertEquals(0, page("-1"))
        assertEquals(7, page("7"))
        assertEquals(0, page("\"3\""))
        assertEquals(listOf(0, 4), p("""[{"kb_id":"k","path":"a.pdf","page":1e20},{"kb_id":"k","path":"b.pdf","page":4}]""").map { it.page })
    }

    @Test fun historyShape() {
        val g = """{"kb_id":"k","path":"a.pdf","title":"甲","page":3}"""
        assertEquals("混进来的坏项丢掉、好的留下", 1, p("[null, 42, $g]").size)
        assertEquals("kb_id 类型不对的那项丢掉", 1, p("""[{"kb_id":7,"path":"a.pdf"},$g]""").size)
        assertEquals(0, p("""[{"kb_id":"k","path":"a.pdf","page":2.5}]""").first().page)
        assertEquals(0, p("{}").size)
    }
}
