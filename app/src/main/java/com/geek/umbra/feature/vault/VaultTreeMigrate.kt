// fmt 2（一条记录一张画布）→ fmt 3（一棵树）的迁移 + 迁移自检。方案正本 doc/保险箱树形-实现方案.md §二；
// PC 参考实现 UmbraPC electron/core/vault/migrate3.ts（v1 旧控件那一段沿用 migrate.ts 的 blockToNode，nodeDefaults.ts 的默认值）。
//
// ⚠️⚠️ **确定性**：同一份 fmt 2 在哪台设备上迁，出来的 id / ord / rev / 时间戳都一样 —— 不读时钟、不出随机数。
// 没有这一条，两台设备各迁一次，合并完是两棵长得一样的树。VaultTreeVectorsTest 拿测试向量 `migrate` 一节逐条比对。
//
// 输入是原始 JSON（`VItem.toJson()` 或快照里的那一份）而不是 VItem：迁移要照 PC 的 JS 语义读每个键（`typeof`、真假值、
// `String(v)`），按 VItem 的强类型先过一遍会把「不是字符串的 label」这类边角先改了样。
// 纯 Kotlin，不 import android.*。
package com.geek.umbra.feature.vault

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

// MARK: - 照 JS 语义读值（迁移要和 PC 逐字一致）

/** JS 那几种取值语义。只给迁移用：别处读 JSON 用 VaultModels 的 vString / vNumber。 */
internal object JsSem {
    fun obj(v: JsonElement?): Map<String, JsonElement> = v as? JsonObject ?: emptyMap()
    fun arr(v: JsonElement?): List<JsonElement>? = v as? JsonArray

    /** `typeof v === "number" && Number.isFinite(v)`。 */
    fun num(v: JsonElement?): Double? {
        val p = v as? JsonPrimitive ?: return null
        if (p is JsonNull || p.isString || p.booleanOrNull != null) return null
        return p.doubleOrNull?.takeIf { it.isFinite() }
    }

    fun str(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** JS 真假值：undefined / null / false / 0 / NaN / "" 为假，其余（含空数组、空对象）为真。 */
    fun truthy(v: JsonElement?): Boolean {
        val p = v ?: return false
        if (p is JsonNull) return false
        if (p !is JsonPrimitive) return true
        if (p.isString) return p.content.isNotEmpty()
        p.booleanOrNull?.let { return it }
        val d = p.doubleOrNull ?: return false
        return d != 0.0 && !d.isNaN()
    }

    /** JS `String(v)`；`undefined` / `null` 由调用方先挡（PC 的 `str()` 把它们写成 ""）。 */
    fun string(v: JsonElement?): String = when (val p = v) {
        null, JsonNull -> ""
        is JsonArray -> p.joinToString(",") { if (it is JsonNull) "" else string(it) }
        is JsonObject -> "[object Object]"
        is JsonPrimitive -> when {
            p.isString -> p.content
            p.booleanOrNull != null -> p.content
            else -> p.doubleOrNull?.let { number(it) } ?: p.content
        }
    }

    /**
     * JS `Number.prototype.toString()`：最短往返数字；小数点位置 n 在 (-6, 21] 之外用指数（`1e+21`、`1.5e-7`）。
     * JDK 19+ 的 `Double.toString` 给的就是最短往返数字，这里只改写成 JS 的排版。
     */
    fun number(x: Double): String {
        if (x.isNaN()) return "NaN"
        if (x.isInfinite()) return if (x > 0) "Infinity" else "-Infinity"
        if (x == 0.0) return "0"
        val bd = java.math.BigDecimal(java.lang.Double.toString(Math.abs(x))).stripTrailingZeros()
        val digits = bd.unscaledValue().toString()
        val k = digits.length
        val n = k - bd.scale()
        val body = when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val e = n - 1
                val mant = if (k == 1) digits else digits[0] + "." + digits.substring(1)
                mant + "e" + (if (e >= 0) "+" else "-") + Math.abs(e)
            }
        }
        return if (x < 0) "-$body" else body
    }

    /**
     * `JSON.stringify(v, null, 2)`：两空格缩进、冒号后一个空格、键按原顺序、空对象 / 空数组写 `{}` / `[]`。
     * ⚠️ PC 特别提醒：v1 里不认识的块正文要和它逐字一致（Swift 的 prettyPrinted 会写成 `"foo" : 1`；kotlinx 的 prettyPrint 缩进四格）。
     */
    fun stringify2(v: JsonElement, level: Int = 0): String {
        val pad = "  ".repeat(level + 1)
        val end = "  ".repeat(level)
        return when (v) {
            is JsonObject -> if (v.isEmpty()) "{}" else v.entries.joinToString(",\n", "{\n", "\n$end}") { (k, x) -> pad + quote(k) + ": " + stringify2(x, level + 1) }
            is JsonArray -> if (v.isEmpty()) "[]" else v.joinToString(",\n", "[\n", "\n$end]") { pad + stringify2(it, level + 1) }
            JsonNull -> "null"
            is JsonPrimitive -> when {
                v.isString -> quote(v.content)
                v.booleanOrNull != null -> v.content
                else -> v.doubleOrNull?.let { if (it.isFinite()) number(it) else "null" } ?: v.content
            }
        }
    }

    /** JSON 字符串字面（JS `JSON.stringify` 的转义：`"` `\` 与 0x00–0x1F；孤立代理项写 `\udxxx`；其余原样）。 */
    fun quote(s: String): String {
        val b = StringBuilder("\"")
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> b.append("\\\"")
                c == '\\' -> b.append("\\\\")
                c == '\b' -> b.append("\\b")
                c == '\u000C' -> b.append("\\f")
                c == '\n' -> b.append("\\n")
                c == '\r' -> b.append("\\r")
                c == '\t' -> b.append("\\t")
                c < ' ' -> b.append("\\u").append(String.format("%04x", c.code))
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { b.append(c).append(s[i + 1]); i++ }
                Character.isSurrogate(c) -> b.append("\\u").append(String.format("%04x", c.code))
                else -> b.append(c)
            }
            i++
        }
        return b.append('"').toString()
    }

    /** JS `Math.round`：.5 往正无穷那边进（不是银行家舍入，也不是 Kotlin 的 roundToInt 对负数的行为）。 */
    fun round(x: Double): Double = Math.floor(x + 0.5)
}

// MARK: - fmt 2 节点默认值（PC nodeDefaults.ts）

/** 五种 fmt 2 节点的默认 data / set、默认标题。迁移先铺默认值再盖原值。 */
object VaultNodeDefaults {
    val FMT2_KINDS = listOf("text", "acct", "table", "img", "file")
    val TITLES = mapOf("text" to "文本", "acct" to "账号", "table" to "表格", "img" to "图片", "file" to "附件")

    fun data(kind: String): Map<String, JsonElement> = when (kind) {
        "text" -> linkedMapOf("body" to js(""))
        "acct" -> linkedMapOf("user" to js(""), "pwd" to js(""), "seed" to js(""), "url" to js(""), "note" to js(""))
        "table" -> linkedMapOf("cols" to ja(listOf(js("项"), js("内容"))), "rows" to ja(listOf(ja(listOf(js(""), js(""))), ja(listOf(js(""), js(""))))))
        "img", "file" -> linkedMapOf("items" to ja(emptyList()))
        "cat" -> Fmt3.catData()
        "multi" -> Fmt3.multiData()
        else -> emptyMap()
    }

    fun set(kind: String): Map<String, JsonElement> = when (kind) {
        "text" -> linkedMapOf("masked" to jb(false), "fmt" to js("text"))
        "acct" -> linkedMapOf(
            "user" to jb(true), "pwd" to jb(true), "totp" to jb(true), "url" to jb(true),
            "note" to jb(false), "pwdMasked" to jb(true), "copy" to jb(true), "strength" to jb(false),
        )
        // cellCopy 新建默认关（设计侧 248 §二）；迁移时缺键的旧表格按 true 补。
        "table" -> linkedMapOf("head" to jb(true), "cellCopy" to jb(false), "mask" to ja(emptyList()), "masked" to jb(true), "colW" to JsonNull, "rowH" to JsonNull)
        "img" -> linkedMapOf("blur" to jb(false), "names" to jb(true), "layout" to js("grid"))
        "file" -> linkedMapOf("size" to jb(true), "date" to jb(false), "expBtn" to jb(false))
        "multi" -> Fmt3.multiSet()
        else -> emptyMap()
    }
}

// MARK: - 迁移

object VaultMigrate3 {
    // 方案 §七第 6 条，名称待设计侧定稿；与 PC 逐字一致。
    const val ORPHAN_ATT_TITLE = "未归类的附件"

    private val ID_RE = Regex("^[A-Za-z0-9_-]{1,64}$")

    private class Raw5(val id: String, val kind: String, val title: String, val data: Map<String, JsonElement>, val set: Map<String, JsonElement>)
    private class Kids(val list: List<Raw5>, val firstAcct: String?)

    /** 类别顺序：有 `order` 的在前按升序；没有的按 `updatedAt` 降序；再按 id（迁移要确定）。 */
    private val itemOrder = Comparator<Map<String, JsonElement>> { a, b ->
        val ao = JsSem.num(a["order"]) ?: Double.POSITIVE_INFINITY
        val bo = JsSem.num(b["order"]) ?: Double.POSITIVE_INFINITY
        if (ao != bo) return@Comparator if (ao < bo) -1 else 1
        // JS 的 `a.updatedAt !== b.updatedAt`：数值相等（5000 与 5000.0）算同一个；不同时按数值差，差为 0（都不是数字）就当相等、不再比 id。
        val ua = JsSem.num(a["updatedAt"]); val ub = JsSem.num(b["updatedAt"])
        val same = if (ua != null || ub != null) ua == ub else a["updatedAt"] == b["updatedAt"]
        if (!same) {
            val d = (ub ?: 0.0) - (ua ?: 0.0)
            return@Comparator if (d < 0) -1 else if (d > 0) 1 else 0
        }
        JsSem.string(a["id"]).compareTo(JsSem.string(b["id"]))
    }

    private fun clampOrNull(v: JsonElement?, lo: Int, hi: Int): JsonElement {
        val x = JsSem.num(v) ?: return JsonNull
        if (x <= 0) return JsonNull
        return jn(Math.min(hi.toDouble(), Math.max(lo.toDouble(), JsSem.round(x))))
    }

    /**
     * 表格 set 的宽高换算（方案 §一第 6 条）：旧 `colW` 本来就是像素 —— 长度对得上列数才认，每项四舍五入夹到 60–480；
     * 旧 `rowH` 按行数截断或补齐，夹到 30–200（没设过的留 null，新默认 36）。整组都是 null 就写 null。
     */
    fun tableSet3(set: Map<String, JsonElement>, data: Map<String, JsonElement>): Map<String, JsonElement> {
        val cols = JsSem.arr(data["cols"])?.size ?: 0
        val rows = JsSem.arr(data["rows"])?.size ?: 0
        var colW: JsonElement = JsonNull
        val cw = JsSem.arr(set["colW"])
        if (cw != null && cw.size == cols && cols > 0) {
            val l = cw.map { clampOrNull(it, Fmt3.COL_W_MIN, Fmt3.COL_W_MAX) }
            if (l.any { it !is JsonNull }) colW = ja(l)
        }
        var rowH: JsonElement = JsonNull
        val rh = JsSem.arr(set["rowH"])
        if (rh != null && rows > 0) {
            val l = (0 until rows).map { clampOrNull(rh.getOrNull(it), Fmt3.ROW_H_MIN, Fmt3.ROW_H_MAX) }
            if (l.any { it !is JsonNull }) rowH = ja(l)
        }
        return LinkedHashMap(set).apply { put("colW", colW); put("rowH", rowH) }
    }

    /** 删除三态（PC trash.ts `trashStateOf`）：deleted 为假 → live；内容（nodes / blocks / attachments）还在 → trashed；否则 purged。 */
    private fun trashState(it: Map<String, JsonElement>): String {
        if (!JsSem.truthy(it["deleted"])) return "live"
        val has = (JsSem.arr(it["nodes"])?.size ?: 0) > 0 || (JsSem.arr(it["blocks"])?.size ?: 0) > 0 || (JsSem.arr(it["attachments"])?.size ?: 0) > 0
        return if (has) "trashed" else "purged"
    }

    /** 一条记录的 fmt 2 节点：认识的种类才收，id 不合法 / 重复的换成 `<记录id>-<下标>`；按 (y, x) 排，再按原下标。 */
    private fun v2Children(itId: String, it: Map<String, JsonElement>, seen: MutableSet<String>): Kids {
        class Got(val r: Raw5, val y: Double, val x: Double, val i: Int)
        val got = ArrayList<Got>()
        var firstAcct: String? = null
        (JsSem.arr(it["nodes"]) ?: emptyList()).forEachIndexed { i, x ->
            val n = JsSem.obj(x)
            val kind = JsSem.str(n["kind"])?.takeIf { k -> k in VaultNodeDefaults.FMT2_KINDS } ?: return@forEachIndexed
            var id = JsSem.str(n["id"])?.takeIf { s -> ID_RE.matches(s) } ?: "$itId-$i"
            if (id in seen) id = "$itId-$i"
            seen += id
            val data = VaultNodeDefaults.data(kind) + JsSem.obj(n["data"])
            var set = VaultNodeDefaults.set(kind) + (if (kind == "table") mapOf("cellCopy" to jb(true)) else emptyMap()) + JsSem.obj(n["set"])
            if (kind == "table") set = tableSet3(set, data)
            if (kind == "acct" && firstAcct == null) firstAcct = id
            got += Got(Raw5(id, kind, JsSem.str(n["title"]) ?: "", data, set), JsSem.num(n["y"]) ?: 0.0, JsSem.num(n["x"]) ?: 0.0, i)
        }
        val sorted = got.sortedWith(compareBy<Got> { it.y }.thenBy { it.x }.thenBy { it.i })
        return Kids(sorted.map { it.r }, firstAcct)
    }

    /** 旧 `atts: string[]` → `items[{attId, name}]`（名字从附件元数据查；查不到只留 attId）。 */
    private fun attItems(d: Map<String, JsonElement>, atts: List<JsonElement>, withName: Boolean): JsonElement {
        val ids = JsSem.arr(d["atts"])?.map { JsSem.string(it) } ?: emptyList()
        return ja(ids.map { attId ->
            if (!withName) return@map JsonObject(mapOf("attId" to js(attId)))
            val a = atts.map { JsSem.obj(it) }.firstOrNull { JsSem.str(it["id"]) == attId }
            if (a != null) JsonObject(linkedMapOf("attId" to js(attId), "name" to js(JsSem.string(a["name"])))) else JsonObject(mapOf("attId" to js(attId)))
        })
    }

    /** `String(b.label || "").trim()`。 */
    private fun labelOf(b: Map<String, JsonElement>): String = if (JsSem.truthy(b["label"])) JsSem.string(b["label"]).trim() else ""

    /** PC `str()`：undefined / null → ""，其余 `String(v)`。 */
    private fun str(v: JsonElement?): String = if (v == null || v is JsonNull) "" else JsSem.string(v)

    /**
     * 旧控件 → 新节点（PC migrate.ts `blockToNode`）：account → acct（`otp` 是字符串才进 seed）、secret → 遮盖的文本（标题缺省「密文」）、
     * text / field → 文本、images → img、files → file；不认识的 → 文本，正文是 `value`，没有就把整个 data 写成 JSON（[JsSem.stringify2]）。
     * 节点 id 由块 id 派生（`n<块id>`），块 id 不合法时用 [rid] 给的。
     */
    private fun blockToNode(b: Map<String, JsonElement>, atts: List<JsonElement>, rid: () -> String): Raw5 {
        val d = b["data"] as? JsonObject ?: JsonObject(emptyMap())
        val id = JsSem.str(b["id"])?.takeIf { ID_RE.matches(it) }?.let { "n$it" } ?: rid()
        fun make(kind: String, title: String, data: Map<String, JsonElement>, set: Map<String, JsonElement>) =
            Raw5(id, kind, title, VaultNodeDefaults.data(kind) + data, VaultNodeDefaults.set(kind) + set)
        fun titleOf(kind: String) = labelOf(b).ifEmpty { VaultNodeDefaults.TITLES.getValue(kind) }
        return when (JsSem.str(b["type"])) {
            "account" -> make("acct", titleOf("acct"), linkedMapOf(
                "user" to js(str(d["username"])), "pwd" to js(str(d["password"])), "url" to js(str(d["url"])),
                "seed" to js(JsSem.str(d["otp"]) ?: ""),
            ), emptyMap())
            "secret" -> make("text", labelOf(b).ifEmpty { "密文" }, mapOf("body" to js(str(d["value"]))), mapOf("masked" to jb(true)))
            "text", "field" -> make("text", titleOf("text"), mapOf("body" to js(str(d["value"]))), emptyMap())
            "images" -> make("img", titleOf("img"), mapOf("items" to attItems(d, atts, true)), mapOf("blur" to jb(false)))
            "files" -> make("file", titleOf("file"), mapOf("items" to attItems(d, atts, false)), emptyMap())
            else -> {
                val body = if (d.containsKey("value")) str(d["value"]) else if (d.isNotEmpty()) JsSem.stringify2(d) else ""
                val type = if (JsSem.truthy(b["type"])) JsSem.string(b["type"]) else "未知控件"
                make("text", labelOf(b).ifEmpty { type }, mapOf("body" to js(body)), emptyMap())
            }
        }
    }

    /** v1 的旧控件：按块的顺序，id 由块 id 派生，派生不了的给 `<记录id>-b<序号>`（序号从 0 起，每生成一个加 1）。 */
    private fun v1Children(itId: String, it: Map<String, JsonElement>, atts: List<JsonElement>, seen: MutableSet<String>): Kids {
        var k = 0
        var firstAcct: String? = null
        val list = (JsSem.arr(it["blocks"]) ?: emptyList()).map { x ->
            val n = blockToNode(JsSem.obj(x), atts) { "$itId-b${k++}" }
            var id = n.id
            if (id in seen) id = "$itId-b${k++}"
            seen += id
            val set = if (n.kind == "table") tableSet3(n.set, n.data) else n.set
            if (n.kind == "acct" && firstAcct == null) firstAcct = id
            Raw5(id, n.kind, n.title, n.data, set)
        }
        return Kids(list, firstAcct)
    }

    private fun referencedAtts(list: List<Raw5>): Set<String> {
        val out = HashSet<String>()
        for (n in list) {
            if (n.kind != "img" && n.kind != "file") continue
            for (x in JsSem.arr(n.data["items"]) ?: emptyList()) JsSem.str(JsSem.obj(x)["attId"])?.let { out += it }
        }
        return out
    }

    /** 迁移用的那批记录：去掉提示记录和没有 id 的，按类别顺序排好。 */
    private fun sourceItems(items: List<JsonElement>): List<Map<String, JsonElement>> = items
        .map { JsSem.obj(it) }
        .filter { val id = JsSem.str(it["id"]); !id.isNullOrEmpty() && id != Fmt3.NOTICE_ID }
        .sortedWith(itemOrder)

    /**
     * 整个身份库迁成 fmt 3。输入是 fmt 2 的 `items`（可以混着 v1 记录、回收站里的、墓碑）；提示记录跳过。
     * 已经是 fmt 3 的别往这里送。
     */
    fun migrate(items: List<JsonElement>): Tree3 {
        val its = sourceItems(items)
        val nodes = ArrayList<Node3>()
        val links = ArrayList<Link3>()
        val atts = ArrayList<Att3>()
        val attSeen = HashSet<String>()
        val seen = its.map { JsSem.str(it["id"])!! }.toHashSet()   // 节点 id 不许和任何类别 id 撞

        its.forEachIndexed { ci, it ->
            val itId = JsSem.str(it["id"])!!
            val src = JsSem.num(it["revision"]) ?: 0.0
            val createdAt = JsSem.num(it["createdAt"]) ?: 0.0
            val updatedAt = JsSem.num(it["updatedAt"]) ?: 0.0
            val state = trashState(it)
            val cat = Node3(itId, "cat", JsSem.str(it["title"]) ?: "", null, VaultOrd.at(ci), emptyMap(), emptyMap(),
                rev = 0.0, createdAt = createdAt, updatedAt = updatedAt, migSrc = src)
            if (state == "purged") { nodes += cat.copy(title = "", gone = true); return@forEachIndexed }

            val itAtts = JsSem.arr(it["attachments"]) ?: emptyList()
            val isV2 = JsSem.num(it["fmt"]) == 2.0
            val a = v2Children(itId, it, seen)
            // 两者都沾一点的记录（旧 iOS 存过的 v2：nodes 被抹掉、blocks 还在）：已有节点在前，旧控件接在后面。
            val b = if (isV2) Kids(emptyList(), null) else v1Children(itId, it, itAtts, seen)
            val list = (a.list + b.list).toMutableList()
            val firstAcct = a.firstAcct ?: b.firstAcct

            val refs = referencedAtts(list)
            val orphans = itAtts.filter { x -> val id = JsSem.str(JsSem.obj(x)["id"]); id != null && id !in refs }
            if (orphans.isNotEmpty()) {
                list += Raw5("$itId-att", "file", ORPHAN_ATT_TITLE,
                    mapOf("items" to ja(orphans.map { x -> JsonObject(mapOf("attId" to js(JsSem.str(JsSem.obj(x)["id"])!!))) })),
                    VaultNodeDefaults.set("file"))
            }

            val catData = LinkedHashMap<String, JsonElement>().apply {
                put("icon", js(JsSem.str(it["icon"]) ?: "")); put("iconImg", jb(JsSem.truthy(it["iconImg"]))); put("sub", js(""))
                put("tags", ja((JsSem.arr(it["tags"]) ?: emptyList()).map { t -> js(JsSem.string(t)) }))
                if (firstAcct != null) put("secretAcct", js(firstAcct))
            }
            val del = if (state == "trashed") Del3(updatedAt, itId) else null
            nodes += cat.copy(data = catData, del = del)
            list.forEachIndexed { i, r ->
                nodes += Node3(r.id, r.kind, r.title, itId, VaultOrd.at(i), r.data, r.set,
                    rev = 0.0, createdAt = createdAt, updatedAt = updatedAt, del = del, migSrc = src)
            }

            // 关联线：两端都得是这条记录里的节点、不自连、同一对只留第一条（同 sanitizeEdges）。
            if (isV2) {
                val ids = list.map { r -> r.id }.toSet()
                val pairs = HashSet<String>()
                (JsSem.arr(it["edges"]) ?: emptyList()).forEachIndexed { i, x ->
                    val e = JsSem.obj(x)
                    val ea = str(e["a"]); val eb = str(e["b"])
                    if (ea !in ids || eb !in ids || ea == eb) return@forEachIndexed
                    val key = if (ea < eb) "$ea|$eb" else "$eb|$ea"
                    if (!pairs.add(key)) return@forEachIndexed
                    val id = JsSem.str(e["id"])?.takeIf { s -> ID_RE.matches(s) } ?: "$itId-e$i"
                    links += Link3(id, ea, eb, JsSem.str(e["label"]) ?: "", 0.0, updatedAt)
                }
            }
            for (x in itAtts) {
                val o = x as? JsonObject ?: continue
                val id = JsSem.str(o["id"]) ?: continue
                if (!attSeen.add(id)) continue
                atts += Att3(LinkedHashMap(o).apply { put("rev", jn(0.0)) })
            }
        }
        return Tree3(nodes, links, atts)
    }

    // MARK: 自检（方案 §二第 4 条）：迁移前后各数一遍，任何一项不等就一个字节都不写
    // 「前」那一侧**不复用迁移的代码**，自己从原始记录数 —— 拿迁移自己的结果去验迁移，等于没验。

    data class Counts(val cats: Int, val children: Int, val accounts: Int, val atts: Int, val trash: Int, val links: Int)

    /** 迁移前：从 fmt 2 原始记录数。 */
    fun countsBefore(items: List<JsonElement>): Counts {
        var cats = 0; var children = 0; var accounts = 0; var trash = 0; var linkN = 0
        val attIds = HashSet<String>()
        for (x in items) {
            val it = JsSem.obj(x)
            val id = JsSem.str(it["id"])
            if (id.isNullOrEmpty() || id == Fmt3.NOTICE_ID) continue
            cats++
            val st = trashState(it)
            if (st == "purged") continue
            if (st == "trashed") trash++
            val isV2 = JsSem.num(it["fmt"]) == 2.0
            val ns = (JsSem.arr(it["nodes"]) ?: emptyList()).map { JsSem.obj(it) }.filter { n -> JsSem.str(n["kind"]) in VaultNodeDefaults.FMT2_KINDS }
            val bs = if (isV2) emptyList() else (JsSem.arr(it["blocks"]) ?: emptyList()).map { JsSem.obj(it) }
            children += ns.size + bs.size
            accounts += ns.count { n -> JsSem.str(n["kind"]) == "acct" } + bs.count { b -> JsSem.str(b["type"]) == "account" }
            for (a in JsSem.arr(it["attachments"]) ?: emptyList()) JsSem.str(JsSem.obj(a)["id"])?.let { s -> attIds += s }
            if (isV2) {
                val ids = ns.map { n -> n["id"] }.toSet()
                val pairs = HashSet<String>()
                for (e in (JsSem.arr(it["edges"]) ?: emptyList()).map { JsSem.obj(it) }) {
                    val a = str(e["a"]); val b = str(e["b"])
                    if (js(a) !in ids || js(b) !in ids || a == b) continue
                    pairs += if (a < b) "$a|$b" else "$b|$a"
                }
                linkN += pairs.size
            }
        }
        return Counts(cats, children, accounts, attIds.size, trash, linkN)
    }

    /** 迁移后：从 fmt 3 数（「未归类的附件」那个节点是迁移加的，不算子节点）。 */
    fun countsAfter(t: Tree3): Counts {
        val cats = t.nodes.filter { it.kind == "cat" }
        val kids = t.nodes.filter { it.kind != "cat" && it.id != "${it.parent}-att" }
        return Counts(cats.size, kids.size, kids.count { it.kind == "acct" }, t.atts.size,
            cats.count { it.del != null && it.del.grp == it.id }, t.links.size)
    }

    /** 自检没过回不等的那几项（空 = 过）。 */
    fun selfCheck(before: Counts, after: Counts): List<String> = buildList {
        if (before.cats != after.cats) add("cats")
        if (before.children != after.children) add("children")
        if (before.accounts != after.accounts) add("accounts")
        if (before.atts != after.atts) add("atts")
        if (before.trash != after.trash) add("trash")
        if (before.links != after.links) add("links")
    }
}
