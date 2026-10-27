package com.geek.umbra.feature.vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保险箱树形（fmt 3）跨端向量：doc/保险箱树形-测试向量.json 的副本（app/src/test/resources/vault/vaultTreeVectors.json，
 * 与 UmbraPC tests/fixtures/vaultTreeVectors.json 逐字节相同；PC 改了要重新拷一份）。
 * 比对口径照 doc/保险箱树形-测试向量.md：JSON 语义相等（键顺序不计，数组顺序要一致）。
 */
class VaultTreeVectorsTest {
    private val v: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("vault/vaultTreeVectors.json")!!.readText(),
    ).jsonObject

    /** 语义相等：对象按键（不计顺序）、数组按顺序、数字按数值（1 与 1.0 算同一个）。不等时报出路径。 */
    private fun same(path: String, a: JsonElement?, b: JsonElement?) {
        when {
            a is JsonObject && b is JsonObject -> {
                assertEquals("$path 的键", a.keys.sorted(), b.keys.sorted())
                for (k in a.keys) same("$path.$k", a[k], b[k])
            }
            a is JsonArray && b is JsonArray -> {
                assertEquals("$path 的长度", a.size, b.size)
                a.indices.forEach { same("$path[$it]", a[it], b[it]) }
            }
            a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString && a !is JsonNull && b !is JsonNull &&
                a.content.toDoubleOrNull() != null && b.content.toDoubleOrNull() != null ->
                assertEquals(path, a.content.toDouble(), b.content.toDouble(), 0.0)
            else -> assertEquals(path, a, b)
        }
    }

    @Test fun ord() {
        for (c in v["ord"]!!.jsonArray) {
            val o = c.jsonObject
            val b = o["b"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            assertEquals("between(${o["a"]}, $b)", o["out"]!!.jsonPrimitive.content, VaultOrd.between(o["a"]!!.jsonPrimitive.content, b))
        }
        assertEquals("000V", VaultOrd.at(0)); assertEquals("00zV", VaultOrd.at(61)); assertEquals("010V", VaultOrd.at(62))
    }

    @Test fun migrate() {
        val m = v["migrate"]!!.jsonObject
        val items = m["input"]!!.jsonObject["items"]!!.jsonArray
        val got = Fmt3.dataJson(VaultMigrate3.migrate(items))
        same("migrate", m["expected"], got)
        // 确定性：再迁一次逐字节相同。
        assertEquals(got.toString(), Fmt3.dataJson(VaultMigrate3.migrate(items)).toString())
        // 自检：迁移前后各项计数相等。
        assertEquals(emptyList<String>(), VaultMigrate3.selfCheck(VaultMigrate3.countsBefore(items), VaultMigrate3.countsAfter(VaultMigrate3.migrate(items))))
    }

    /** @ 引用改写（向量 md 第二节第 13 条）：同记录 / 跨记录 / 撞名换过 id 的照新 id；找不到的原样留着；显示名不改。 */
    @Test fun mentionRewrite() {
        val map = mapOf("iWork/nMail" to "nMail", "iDup/nMail" to "iDup-0")
        assertEquals("@[邮箱](nMail) @[另一个](iDup-0) @[旧](iGone/nX)", VaultMigrate3.rewriteMentions("@[邮箱](iWork/nMail) @[另一个](iDup/nMail) @[旧](iGone/nX)", map))
        assertEquals("没有引用原样回", "a@b.com", VaultMigrate3.rewriteMentions("a@b.com", map))
    }

    /** v1 不认识的块：正文要和 JS 的 JSON.stringify(data, null, 2) 逐字一致（PC 特别提醒）。 */
    @Test fun unknownBlockBodyMatchesJsStringify() {
        val data = Json.parseToJsonElement("""{"foo":1,"bar":[1.5,true,null,"a\"b\n"],"o":{},"a":[],"big":1e21,"tiny":1.5e-7,"neg":-0.000001}""")
        assertEquals(
            "{\n  \"foo\": 1,\n  \"bar\": [\n    1.5,\n    true,\n    null,\n    \"a\\\"b\\n\"\n  ],\n  \"o\": {},\n  \"a\": [],\n  \"big\": 1e+21,\n  \"tiny\": 1.5e-7,\n  \"neg\": -0.000001\n}",
            JsSem.stringify2(data),
        )
        assertEquals("100", JsSem.number(100.0)); assertEquals("0.1", JsSem.number(0.1)); assertEquals("123456789012345680000", JsSem.number(1.2345678901234568E20))
    }

    @Test fun merge() {
        for (c in v["merge"]!!.jsonArray) {
            val o = c.jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            val local = Tree3.from(o["local"]!!.jsonObject)
            val remote = Tree3.from(o["remote"]!!.jsonObject)
            // 两个方向带同一个设备名（空串 = 没有，副本标题走兜底那句）。
            val device = o["deviceName"]?.jsonPrimitive?.content ?: ""
            fun js(t: Tree3) = JsonObject(mapOf("nodes" to JsonArray(t.nodes.map { it.toJson() }), "links" to JsonArray(t.links.map { it.toJson() }), "atts" to JsonArray(t.atts.map { it.toJson() })))
            same("$name（本机并云端）", o["expected"], js(VaultMerge3.merge(local, remote, device)))
            same("$name（云端并本机）", o["expected"], js(VaultMerge3.merge(remote, local, device)))
        }
    }

    @Test fun autofill() {
        val a = v["autofill"]!!.jsonObject
        val got = VaultAutofill3.candidates(Tree3.from(a["input"]!!.jsonObject))
        val want = a["candidates"]!!.jsonArray
        assertEquals("候选条数", want.size, got.size)
        want.forEachIndexed { i, w ->
            val o = w.jsonObject
            val c = got[i]
            val p = "候选[$i]"
            assertEquals("$p.line1", o["line1"]!!.jsonPrimitive.content, c.line1)
            assertEquals("$p.line2", o["line2"]!!.jsonPrimitive.content, c.line2)
            assertEquals("$p.recordIdentifier", o["recordIdentifier"]!!.jsonPrimitive.content, c.recordIdentifier)
            assertEquals("$p.service", o["service"]!!.jsonPrimitive.content, c.service)
            assertEquals("$p.user", o["user"]!!.jsonPrimitive.content, c.user)
            assertEquals("$p.url", o["url"]!!.jsonPrimitive.content, c.url)
            assertEquals("$p.name", o["name"]!!.jsonPrimitive.content, c.name)
        }
        for (m in a["nameMatches"]!!.jsonArray) {
            val o = m.jsonObject
            val name = o["name"]!!.jsonPrimitive.content; val target = o["target"]!!.jsonPrimitive.content
            assertEquals("nameMatches($name, $target)", o["out"]!!.jsonPrimitive.boolean, VaultAutofill3.nameMatches(name, target))
        }
    }

    @Test fun treeFallbacks() {
        fun n(id: String, parent: String?, rev: Double = 1.0, ord: String = "V", del: Del3? = null) = Node3(id, "text", id, parent, ord, rev = rev, del = del)
        // 成环：a ↔ b，(rev, id) 最小的那个挂根。
        val ring = listOf(n("a", "b", rev = 3.0), n("b", "a", rev = 2.0))
        assertEquals(mapOf("a" to "b", "b" to null), VaultTreeIndex.effectiveParents(ring))
        // 孤儿：父节点找不到 / 在回收站里 → 显示在根上，parent 字段不改。
        val orphans = listOf(n("x", "ghost"), n("p", null, del = Del3(1.0, "p")), n("c", "p"))
        assertEquals(mapOf("x" to null, "c" to null), VaultTreeIndex.effectiveParents(orphans))
        assertEquals(listOf("x", "c").sorted(), VaultTreeIndex.preorder(orphans).map { it.id }.sorted())
    }

    @Test fun trashWholeGroup() {
        val t = Tree3(listOf(
            Node3("c", "cat", "工作", null, "0V", rev = 1.0),
            Node3("k", "acct", "邮箱", "c", "0V", rev = 1.0),
            Node3("m", "multi", "云", "c", "1V", rev = 1.0).withEntries(listOf(Entry3("e1", "阿里云", rev = 1.0), Entry3("e2", "腾讯云", rev = 1.0))),
            Node3("r", "text", "恢复码", "k", "0V", rev = 1.0),
        ))
        assertEquals(VaultTrash3.Impact(3, 3), VaultTrash3.deleteImpact(t, "c"))
        // 先单独删 m 里的一条，再删整组：恢复整组时那一条不跟着回来。
        val t1 = VaultTrash3.trashEntry(t, "m", "e2", 10.0)!!
        val (t2, k) = VaultTrash3.trashNode(t1, "c", 20.0)
        assertEquals(4, k)
        assertTrue(t2.nodes.all { it.del?.grp == "c" && it.rev == if (it.id == "m") 3.0 else 2.0 })
        val g = VaultTrash3.groups(t2, 20.0)
        assertEquals(listOf("c", "m:e2"), g.map { it.grp })
        assertEquals(3, g[0].descendants); assertEquals(2, g[0].accounts)
        val (t3, back) = VaultTrash3.restore(t2, "c", 30.0)
        assertEquals(4, back)
        assertTrue(t3.nodes.all { it.live })
        assertFalse("单独删的那一条没跟着回来", t3.node("m")!!.entries.first { it.id == "e2" }.live)
        // 恢复条目时，所在节点也在回收站：连节点那一组一起恢复。
        val (t4, _) = VaultTrash3.trashNode(t3, "m", 40.0)
        val (t5, n5) = VaultTrash3.restore(t4, "m:e2", 50.0)
        assertEquals(2, n5)
        assertTrue(t5.node("m")!!.live && t5.node("m")!!.entries.all { it.live })
        // 彻底删除：墓碑留 id / parent，内容清空；只被这一组引用的附件写成 gone。
        val withAtt = t.withNode(Node3("pic", "img", "图", "k", "1V", data = mapOf("items" to ja(listOf(jo(mapOf("attId" to js("a1"))))))))
            .copy(atts = listOf(Att3(mapOf("id" to js("a1"), "name" to js("x.png"), "fileId" to js("f1"), "rev" to jn(0.0)))))
        val (t6, _) = VaultTrash3.trashNode(withAtt, "k", 60.0)
        val p = VaultTrash3.purge(t6, "k", 70.0)
        assertEquals(3, p.purged)
        assertEquals(listOf("a1" to "f1"), p.atts)
        assertTrue(p.tree.node("k")!!.gone && p.tree.node("k")!!.title.isEmpty() && p.tree.node("k")!!.parent == "c")
        assertTrue(p.tree.atts[0].gone && p.tree.atts[0].fileId == null)
        assertEquals(listOf("c"), VaultTrash3.expired(VaultTrash3.trashNode(t, "c", 0.0).first, Fmt3.TRASH_KEEP_MS))
    }

    /** 表格宽高换算：colW 长度对不上列数不认；rowH 按行数截断补齐；全 null 写 null。 */
    @Test fun tableSet3() {
        fun cols(n: Int) = mapOf("cols" to ja(List(n) { js("c") }), "rows" to ja(List(2) { ja(emptyList()) }))
        val s1 = VaultMigrate3.tableSet3(mapOf("colW" to ja(listOf(jn(10.0), jn(200.4))), "rowH" to ja(listOf(jn(500.0), jn(-1.0), jn(40.0)))), cols(2))
        assertEquals(ja(listOf(jn(60.0), jn(200.0))), s1["colW"])
        assertEquals(ja(listOf(jn(200.0), JsonNull)), s1["rowH"])
        val s2 = VaultMigrate3.tableSet3(mapOf("colW" to ja(listOf(jn(100.0))), "rowH" to ja(listOf(JsonNull))), cols(2))
        assertEquals(JsonNull, s2["colW"]); assertEquals(JsonNull, s2["rowH"])
    }
}
