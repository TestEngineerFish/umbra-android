package com.geek.umbra.feature.vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保险箱节点 @ 引用 —— 逐条翻译 iOS `Tools/swifttests/VaultMentionRulesTests.swift`。
 *
 * 夹具 `vault/mentionCases.json` 是 PC `UmbraPC/tests/fixtures/mentionCases.json` 的逐字节副本（三端共用）：
 * 每条比切段、明文（按 resolve 表查现名）、hasMention。正则与标记格式是两端冻结的契约。
 */
class VaultMentionRulesTest {
    private fun fixture(): JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResourceAsStream("vault/mentionCases.json")!!.readBytes().toString(Charsets.UTF_8),
    ).jsonObject

    /** 夹具里的一段 → Seg。字段不全回 null（由调用方断言字段齐全）。 */
    private fun seg(o: JsonObject): VaultMentionRules.Seg? = when (o["t"]?.jsonPrimitive?.content) {
        "text" -> o["v"]?.jsonPrimitive?.content?.let { VaultMentionRules.Seg.Text(it) }
        "m" -> {
            val i = o["itemId"]?.jsonPrimitive?.content
            val n = o["nodeId"]?.jsonPrimitive?.content
            val fb = o["fallback"]?.jsonPrimitive?.content
            if (i == null || n == null || fb == null) null else VaultMentionRules.Seg.Mention(i, n, fb)
        }
        else -> null
    }

    /** 两端共用夹具：条数 ≥ 12；每条的切段 / 明文 / hasMention 与 PC 相同。 */
    @Test fun sharedFixtureMatchesPc() {
        val root = fixture()
        val table = root["resolve"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val cases = root["cases"]!!.jsonArray
        assertTrue("夹具条数（≥ 12），实际 ${cases.size}", cases.size >= 12)
        val resolve: MentionResolve = { i, n -> table["$i/$n"] }
        for (e in cases) {
            val c = e.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val text = c["text"]!!.jsonPrimitive.content
            val plain = c["plain"]!!.jsonPrimitive.content
            val raw = c["segs"]!!.jsonArray
            val want = raw.mapNotNull { seg(it.jsonObject) }
            assertEquals("夹具「$name」·段的字段齐全", raw.size, want.size)
            assertEquals("夹具「$name」·切段", want, VaultMentionRules.parse(text))
            assertEquals("夹具「$name」·明文", plain, VaultMentionRules.plainText(text, resolve))
            assertEquals("夹具「$name」·hasMention", want.any { it is VaultMentionRules.Seg.Mention }, VaultMentionRules.hasMention(text))
        }
    }

    /** iOS 侧补的：拿不到目录时一律用备份名（搜索用它）；查不到且备份名全空白时标缺失。 */
    @Test fun offlineAndMissing() {
        assertEquals("离线明文用备份名", "见 @旧名", VaultMentionRules.plainTextOffline("见 @[旧名](i_1/n_a)"))
        assertEquals("缺失标记", true, VaultMentionRules.label("i", "n", "  ") { _, _ -> null }.second)
    }
}
