package com.geek.umbra.feature.vault

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格格级密文 `set.maskCells`（《保险箱-格级密文-三端契约》）：Android 这一轮认得（显示遮盖、搜索不收）+ 存回去不丢。
 * 表：2 行 × 4 列，`mask: [2]`（第 3 列整列密文）、`maskCells: [[1, 1], [0, 3]]`（契约第九节那一张）。
 */
class VaultTableMaskTest {
    private val maskCells = ja(listOf(ja(listOf(jn(1.0), jn(1.0))), ja(listOf(jn(0.0), jn(3.0)))))
    private val set = mapOf("head" to jb(true), "mask" to ja(listOf(jn(2.0))), "masked" to jb(false), "maskCells" to maskCells, "cellCopy" to jb(true))
    private fun row(vararg v: String) = ja(v.map { js(it) })
    private val data = mapOf(
        "cols" to row("主机", "用户", "密码", "备注"),
        "rows" to ja(listOf(row("api", "root", "pw-api", "秘密备注A"), row("db", "秘密用户B", "pw-db", "公开备注"))),
    )
    private val node = VNode("t", "table", title = "服务器", data = data, set = set)

    @Test fun secretIsColumnOrCell() {
        val m = VaultTableMask.of(node)
        assertTrue("密文列", m.secret(0, 2) && m.secret(1, 2))
        assertTrue("格级", m.secret(1, 1) && m.secret(0, 3))
        assertFalse(m.secret(0, 1) || m.secret(1, 3) || m.secret(0, 0))
        assertTrue(m.any)
        assertFalse("没有这个键 = 没有格级密文", VaultTableMask.of(node.copy(set = set - "maskCells" - "mask")).any)
        assertFalse("空数组按没有处理", VaultTableMask.of(node.copy(set = set - "mask" + ("maskCells" to ja(emptyList())))).any)
    }

    @Test fun sanitize() {
        val raw = ja(listOf(
            ja(listOf(jn(1.0), jn(3.0))), ja(listOf(jn(0.0), jn(1.0))), ja(listOf(jn(0.0), jn(1.0))),   // 乱序、重复
            ja(listOf(jn(2.0), jn(0.0))), ja(listOf(jn(0.0), jn(4.0))), ja(listOf(jn(-1.0), jn(0.0))),  // 越界
            ja(listOf(jn(0.5), jn(0.0))), ja(listOf(js("0"), jn(0.0))), ja(listOf(jn(0.0))), js("x"),  // 不是整数 / 不是二元组
        ))
        assertEquals(listOf(0 to 1, 1 to 3), VaultTableMask.sanitizeCells(raw, 2, 4))
        assertNull(VaultTableMask.sanitizeCells(null, 2, 4))
        assertNull(VaultTableMask.sanitizeCells(js("坏"), 2, 4))
    }

    /** 搜索索引：密文格的值不进，不管 set.masked 开没开（这张表 masked = false，眼睛开着也不收）。 */
    @Test fun searchSkipsSecretCells() {
        val parts = node.searchParts()
        assertFalse("秘密备注A" in parts); assertFalse("秘密用户B" in parts); assertFalse("pw-api" in parts)
        assertTrue("root" in parts && "公开备注" in parts && "主机" in parts)
        assertTrue(VaultFindRules.hits(listOf(node), "秘密").isEmpty())
    }

    /** 门槛的核心：读进来 → 改别的字段 → 存回去，maskCells 逐字还在（Android 不写、不整形它，原样带回）。 */
    @Test fun treeRoundTripKeepsMaskCells() {
        val n3 = Node3.from(jo(mapOf("id" to js("t"), "kind" to js("table"), "title" to js("服务器"), "parent" to js("c"), "ord" to js("0V"),
            "data" to jo(data), "set" to jo(set), "rev" to jn(1.0))))!!
        // 编辑页的路子：节点转 VNode 给表格编辑件，改一格，再把 data / set 拷回 Node3（VaultTreeViews NodeEditorScreen）。
        val v = VaultTreeUi.asVNode(n3)
        val rows = (v.data["rows"] as JsonArray).map { r -> (r as JsonArray).toMutableList() }.toMutableList()
        rows[0][0] = js("api-2")
        val edited = v.copy(data = v.data + ("rows" to ja(rows.map { ja(it) })))
        val saved = n3.copy(title = "服务器（改名）", data = edited.data, set = edited.set)
        val back = Node3.from(VaultJsonRoundTrip.roundTrip(saved.toJson()))!!
        assertEquals(maskCells, back.set["maskCells"])
        // 合并（整节点按 rev）与迁移（表格宽高换算）都不丢它。
        assertEquals(maskCells, VaultMerge3.merge(Tree3(listOf(n3)), Tree3(listOf(back.copy(rev = 2.0))), "").node("t")!!.set["maskCells"])
        assertEquals(maskCells, VaultMigrate3.tableSet3(set, data)["maskCells"])
    }

    @Test fun canvasRoundTripKeepsMaskCells() {
        val item = VItem.from(jo(mapOf(
            "id" to js("r1"), "typeId" to js(""), "title" to js("r"), "blocks" to ja(emptyList()), "attachments" to ja(emptyList()),
            "createdAt" to jn(0.0), "updatedAt" to jn(0.0), "revision" to jn(1.0), "fmt" to jn(2.0), "edges" to ja(emptyList()),
            "nodes" to ja(listOf(node.toJson())),
        )))
        // fmt 2 编辑页：改一格的字 → withNodes 写回。
        val n = item.nodes.single()
        val saved = item.withNodes(listOf(n.copy(data = n.data + ("cols" to ja(listOf(js("主机2"), js("用户"), js("密码"), js("备注")))))))
        val back = VItem.parse(saved.toJson().toString())
        assertEquals(maskCells, back.nodes.single().set["maskCells"])
        val json = back.toJson()["nodes"]!!.jsonArray[0].jsonObject["set"] as JsonObject
        assertEquals(maskCells, json["maskCells"])
    }
}

/** 编码成字符串再解回来（真正落盘 / 推送走的那一趟）。 */
private object VaultJsonRoundTrip {
    fun roundTrip(o: JsonObject): JsonObject = VaultJson.parseToJsonElement(o.toString()).jsonObject
}
