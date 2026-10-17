// 聊天引用（服务端助手消息的 `meta.citations`）的解析与 chip 上那行字（iOS: Features/Knowledge/KnowledgeRules.swift:17-80）。
//
// 纯判据，用例照抄 iOS Tools/swifttests/KnowledgeRulesTests.swift 的 citation / label / pageTrap / historyDecode 几组。
// 出处给机器的那一份走 meta，不走正文 —— 正文里的〔…〕原样当文字。
package com.geek.umbra.feature.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 一处引用：哪个库的哪个文件，哪一节、第几页（PDF 1 起，其它 0）。 */
data class KbCitation(val kbId: String, val path: String, val title: String, val headingPath: String, val page: Int)

object KbCitations {
    /** 标题路径各级的分隔（服务端 `knowledge_split.HEADING_SEP`）。 */
    const val SEP = " › "

    /**
     * `meta.citations` → 引用列表。WS 那一路与历史那一路都交给这一个函数（iOS 台账 kb-history-citation-strict-decode：
     * 原来历史那一路合成 Codable，一项坏了整页历史解码失败）。认不出的项丢掉，不猜；同一处只留一颗。
     */
    fun parse(raw: JsonElement?): List<KbCitation> {
        val arr = raw as? JsonArray ?: return emptyList()
        val out = mutableListOf<KbCitation>()
        val seen = HashSet<String>()
        for (item in arr) {
            val o = item as? JsonObject ?: continue
            val kb = str(o["kb_id"])
            val path = str(o["path"])
            if (kb.isEmpty() || path.isEmpty()) continue
            val rawTitle = str(o["title"])
            val title = rawTitle.ifEmpty { fileName(path) }
            val heading = str(o["heading_path"])
            val page = maxOf(0, intValue(o["page"]))
            val key = "$kb\u0000$path\u0000$heading\u0000$page"
            if (seen.add(key)) out += KbCitation(kb, path, title, heading, page)
        }
        return out
    }

    /** chip 上那行字：「标题 › 小节」。小节以标题打头时不重复写；页码由视图层加（带本地化）。 */
    fun label(c: KbCitation): String {
        var head = c.headingPath
        if (head == c.title) head = ""
        else if (head.startsWith(c.title + SEP)) head = head.drop((c.title + SEP).length)
        return if (head.isEmpty()) c.title else c.title + SEP + head
    }

    /** 路径最后一段（标题缺省时用）。 */
    fun fileName(path: String): String = path.split("/").lastOrNull { it.isNotEmpty() } ?: path

    /** 只收字符串（kb_id 是数字的那项当缺失丢掉，同 iOS `as? String`）。 */
    private fun str(e: JsonElement?): String = (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

    /**
     * 页码：只收「正好是整数、而且装得下」的；2.5 / 1e20 / NaN / 字符串一律 0（iOS 台账 kb-citation-page-double-trap）。
     * 页码没有半页，宁可不标页码，也不猜成第 2 页。
     */
    private fun intValue(e: JsonElement?): Int {
        val p = e as? JsonPrimitive ?: return 0
        if (p.isString) return 0
        val d = p.content.toDoubleOrNull() ?: return 0
        if (d.isNaN() || d.isInfinite() || d != Math.rint(d)) return 0
        if (d > Int.MAX_VALUE || d < Int.MIN_VALUE) return 0
        return d.toInt()
    }
}
