// 保险箱数据模型 **fmt 3（树形）**：节点 / 多账号条目 / 关联线 / 附件四种对象，以及分数索引与「扁平表 → 树」。
// 方案正本 doc/保险箱树形-实现方案.md §一；PC 参考实现 UmbraPC electron/core/vault/fmt3.ts、ord.ts、tree.ts（7bfebdb）。
//
// 逻辑上是一棵树 `{id, kind, title, children[]}`；**存的是扁平表**：每个节点只记自己的 `parent` + `ord`，children 读进来现算。
// 理由（方案 §一第 2 条）：合并按节点逐个比 `rev`，移动一个节点只改它自己那一行；存成嵌套数组的话，两台设备改同一棵子树
// 只能整棵比新旧，必有一边丢。
//
// ⚠️ 纯 Kotlin，不许 import android.*：VaultTreeVectorsTest 拿 doc/保险箱树形-测试向量.json（与 PC tests/fixtures 同一份）逐条比对。
// ⚠️ 和 VItem 一样，每种对象都把**不认识的键**收进 extra、写回时原样带上：以后 PC 再加键，手机存一次不会把它抹掉。
// ⚠️⚠️ 以后升 fmt 4 时，必须把 fmt 3 的键（nodes / links / atts）留成兼容键（方案 §三之二第 5 条），fmt 3 的客户端才「还能看」。
package com.geek.umbra.feature.vault

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// MARK: - 四种对象

/** 在回收站里。`grp` = 被删的那个节点的 id（整棵子树同一个 grp，恢复时整组回来）；多账号单条是 `"<nodeId>:<entryId>"`。 */
data class Del3(val at: Double, val grp: String) {
    fun toJson(): JsonObject = JsonObject(linkedMapOf("at" to jn(at), "grp" to js(grp)))

    companion object {
        fun from(e: JsonElement?): Del3? {
            val o = e as? JsonObject ?: return null
            val grp = (o["grp"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            return Del3(o["at"].vNumber ?: 0.0, grp)
        }
    }
}

/**
 * 一个节点。七种 kind：cat / text / acct / multi / table / img / file；不认识的照样保留（界面画成「在电脑上查看」）。
 * `data` / `set` 不展开成字段：展开一次就多一处「PC 加了键、手机存一次就丢」。
 */
data class Node3(
    val id: String,
    val kind: String,
    val title: String = "",
    /** null = 挂在身份库根上（293 §二第 1 条：根上也能放非类别节点）。 */
    val parent: String? = null,
    /** 兄弟间顺序：分数索引（[VaultOrd]），同 ord 再按 id。 */
    val ord: String = "V",
    val data: Map<String, JsonElement> = emptyMap(),
    val set: Map<String, JsonElement> = emptyMap(),
    /** 画布上手动拖过的位置（手机上不画画布，原样保留）。 */
    val pos: JsonElement? = null,
    /** 每改一次 +1（移动也算）。合并按它取新。 */
    val rev: Double = 0.0,
    val createdAt: Double = 0.0,
    val updatedAt: Double = 0.0,
    val del: Del3? = null,
    /** 彻底删除的墓碑：标题 / 内容清空，只留 id / parent / rev，跨端传播「这个没了」。 */
    val gone: Boolean = false,
    /** 迁移来的节点：原记录的 revision（首合规则用，方案 §二第 6 条）。 */
    val migSrc: Double? = null,
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    /** 活着：不在回收站、没彻底删除。 */
    val live: Boolean get() = del == null && !gone

    fun toJson(): JsonObject = JsonObject(LinkedHashMap<String, JsonElement>().apply {
        put("id", js(id)); put("kind", js(kind)); put("title", js(title))
        put("parent", parent?.let { js(it) } ?: JsonNull); put("ord", js(ord))
        put("data", JsonObject(data)); put("set", JsonObject(set))
        if (pos != null) put("pos", pos)
        put("rev", jn(rev)); put("createdAt", jn(createdAt)); put("updatedAt", jn(updatedAt))
        if (del != null) put("del", del.toJson())
        if (gone) put("gone", jb(true))
        if (migSrc != null) put("mig", JsonObject(mapOf("src" to jn(migSrc))))
        for (k in extra.keys.sorted()) if (k !in KNOWN) put(k, extra.getValue(k))
    })

    /** `set` 里的一个开关；键缺了、或不是布尔，按默认值。 */
    fun flag(key: String, default: Boolean): Boolean {
        val p = set[key] as? JsonPrimitive ?: return default
        if (p.isString) return default
        return p.content.toBooleanStrictOrNull() ?: default
    }

    companion object {
        val KINDS = listOf("cat", "text", "acct", "multi", "table", "img", "file")
        private val KNOWN = setOf("id", "kind", "title", "parent", "ord", "data", "set", "pos", "rev", "createdAt", "updatedAt", "del", "gone", "mig")

        /** 从 `nodes[]` 里的一项解。id / kind 缺了就不是一个节点（null）；其余缺了按默认值。 */
        fun from(e: JsonElement): Node3? {
            val o = e as? JsonObject ?: return null
            val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
            val kind = (o["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
            return Node3(
                id = id, kind = kind, title = o["title"].vString,
                parent = (o["parent"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                ord = (o["ord"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "V",
                data = o["data"] as? JsonObject ?: emptyMap(), set = o["set"] as? JsonObject ?: emptyMap(),
                pos = o["pos"]?.takeIf { it !is JsonNull },
                rev = o["rev"].vNumber ?: 0.0, createdAt = o["createdAt"].vNumber ?: 0.0, updatedAt = o["updatedAt"].vNumber ?: 0.0,
                del = Del3.from(o["del"]), gone = o["gone"].vBool,
                migSrc = (o["mig"] as? JsonObject)?.get("src").vNumber,
                extra = o.filterKeys { it !in KNOWN },
            )
        }
    }
}

/** 多账号里的一条（293 §五第 2 条：名称必填，其余可空）。存在多账号节点的 `data.entries` 里。 */
data class Entry3(
    val id: String,
    val name: String = "",
    val url: String = "",
    val user: String = "",
    val pwd: String = "",
    val note: String = "",
    val rev: Double = 0.0,
    val updatedAt: Double = 0.0,
    val del: Del3? = null,
    val gone: Boolean = false,
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    val live: Boolean get() = del == null && !gone

    fun toJson(): JsonObject = JsonObject(LinkedHashMap<String, JsonElement>().apply {
        put("id", js(id)); put("name", js(name)); put("url", js(url)); put("user", js(user)); put("pwd", js(pwd)); put("note", js(note))
        put("rev", jn(rev)); put("updatedAt", jn(updatedAt))
        if (del != null) put("del", del.toJson())
        if (gone) put("gone", jb(true))
        for (k in extra.keys.sorted()) if (k !in KNOWN) put(k, extra.getValue(k))
    })

    companion object {
        private val KNOWN = setOf("id", "name", "url", "user", "pwd", "note", "rev", "updatedAt", "del", "gone")

        fun from(e: JsonElement): Entry3? {
            val o = e as? JsonObject ?: return null
            val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
            return Entry3(
                id, o["name"].vString, o["url"].vString, o["user"].vString, o["pwd"].vString, o["note"].vString,
                o["rev"].vNumber ?: 0.0, o["updatedAt"].vNumber ?: 0.0, Del3.from(o["del"]), o["gone"].vBool,
                o.filterKeys { it !in KNOWN },
            )
        }
    }
}

/** 关联线：身份库这一层（整棵树一张画布，关联可以跨类别）。无向，同一对只留一条。手机上不画，只保住、只在详情里列。 */
data class Link3(
    val id: String, val a: String, val b: String, val label: String = "",
    val rev: Double = 0.0, val updatedAt: Double = 0.0, val gone: Boolean = false,
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    fun toJson(): JsonObject = JsonObject(LinkedHashMap<String, JsonElement>().apply {
        put("id", js(id)); put("a", js(a)); put("b", js(b)); put("label", js(label)); put("rev", jn(rev)); put("updatedAt", jn(updatedAt))
        if (gone) put("gone", jb(true))
        for (k in extra.keys.sorted()) if (k !in KNOWN) put(k, extra.getValue(k))
    })

    companion object {
        private val KNOWN = setOf("id", "a", "b", "label", "rev", "updatedAt", "gone")
        fun from(e: JsonElement): Link3? {
            val o = e as? JsonObject ?: return null
            val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
            return Link3(id, o["a"].vString, o["b"].vString, o["label"].vString, o["rev"].vNumber ?: 0.0, o["updatedAt"].vNumber ?: 0.0,
                o["gone"].vBool, o.filterKeys { it !in KNOWN })
        }
    }
}

/**
 * 附件元数据：库这一层（上传完回写 `fileId` 只抬这一行的 rev，不撞节点）。键就是 fmt 2 的附件元数据 + `rev` / `gone`，
 * 整个对象原样存（[raw]），只拿几个要用的键出来 —— 墓碑行的 name 是空的、mime 可能缺，按 VAtt 那样逐键必填会解不开。
 */
data class Att3(val raw: Map<String, JsonElement>) {
    val id: String get() = raw["id"].vString
    val rev: Double get() = raw["rev"].vNumber ?: 0.0
    val updatedAt: Double get() = raw["updatedAt"].vNumber ?: 0.0
    val gone: Boolean get() = raw["gone"].vBool
    val name: String get() = raw["name"].vString
    val mime: String get() = raw["mime"].vString
    val size: Double get() = raw["size"].vNumber ?: 0.0
    val fileId: String? get() = (raw["fileId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun toJson(): JsonObject = JsonObject(raw)
    /** 给沿用 fmt 2 渲染代码的地方（图片 / 附件格子）一个 VAtt。 */
    fun asVAtt(): VAtt = VAtt(id, name, mime, size, raw["addedAt"].vNumber ?: 0.0, raw["w"].vNumber, raw["h"].vNumber, fileId)

    companion object {
        fun from(e: JsonElement): Att3? {
            val o = e as? JsonObject ?: return null
            if ((o["id"] as? JsonPrimitive)?.isString != true) return null
            return Att3(o)
        }
    }
}

/** 一个身份库的 fmt 3 正文（`data[vid]` 去掉兼容键之后那三样）。 */
data class Tree3(val nodes: List<Node3>, val links: List<Link3> = emptyList(), val atts: List<Att3> = emptyList()) {
    fun node(id: String): Node3? = nodes.firstOrNull { it.id == id }

    /** 换掉一个节点（按 id）；没有就追加。 */
    fun withNode(n: Node3): Tree3 {
        val i = nodes.indexOfFirst { it.id == n.id }
        return copy(nodes = if (i < 0) nodes + n else nodes.toMutableList().also { it[i] = n })
    }

    companion object {
        val EMPTY = Tree3(emptyList())

        /** 从带 `nodes / links / atts` 的对象读（坏项丢掉）。 */
        fun from(o: Map<String, JsonElement>): Tree3 = Tree3(
            (o["nodes"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { Node3.from(it) },
            (o["links"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { Link3.from(it) },
            (o["atts"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { Att3.from(it) },
        )
    }
}

/** 多账号节点里的条目（坏数据当空）。 */
val Node3.entries: List<Entry3>
    get() = if (kind == "multi") (data["entries"] as? JsonArray)?.mapNotNull { Entry3.from(it) } ?: emptyList() else emptyList()

/** 换掉多账号节点的条目数组。 */
fun Node3.withEntries(list: List<Entry3>): Node3 = copy(data = data + ("entries" to JsonArray(list.map { it.toJson() })))

// MARK: - fmt 3 的常量与提示记录

object Fmt3 {
    /** 提示记录的固定 id。新版读到就跳过：不迁、不显示、不算数。 */
    const val NOTICE_ID = "fmt3-upgrade-notice"

    // 设计侧 296 §三第 1 条定稿；与 PC fmt3.ts（e578d9f）逐字一致 —— 这条记录写进数据里，三端必须一样，也没法跟着语言走，只写中文。
    const val NOTICE_TITLE = "先升级长尾雀：保险箱已换新格式"
    const val NOTICE_BODY = "保险箱已在别的设备上升级成新格式。这台的长尾雀版本太旧，还能看升级前的内容，但在这里的改动同步不上去。升级到最新版以后会自动合并，这台上的改动不会丢。"

    /** 给旧版看的那条 fmt 2 记录：旧三端都认 `fmt: 2` 的画布记录，必填键一个不缺（PC `noticeItem()`）。 */
    fun noticeItem(): JsonObject = JsonObject(linkedMapOf(
        "id" to js(NOTICE_ID), "title" to js(NOTICE_TITLE), "icon" to js("info"), "typeId" to js(""),
        "blocks" to ja(emptyList()), "attachments" to ja(emptyList()),
        "createdAt" to jn(0.0), "updatedAt" to jn(0.0), "revision" to jn(1.0), "fmt" to jn(2.0), "tags" to ja(emptyList()),
        "nodes" to ja(listOf(JsonObject(linkedMapOf(
            "id" to js("$NOTICE_ID-n"), "kind" to js("text"), "x" to jn(40.0), "y" to jn(40.0), "w" to jn(360.0), "h" to jn(180.0),
            "title" to js("说明"), "data" to JsonObject(mapOf("body" to js(NOTICE_BODY))),
            "set" to JsonObject(linkedMapOf("masked" to jb(false), "fmt" to js("text"))),
        )))),
        "edges" to ja(emptyList()),
    ))

    /**
     * 一个 fmt 3 库写进快照 / 本机的样子：旧版兼容键（`types` 空、`items` 只放提示记录、`attachments` 空）+ `fmt: 3` + 正文三张表。
     * 兼容键是硬性的：旧 iOS / Android 的 VData 解码时三个都必填，缺一个整份快照解不开 → 「解密失败」（方案 §一第 1 条）。
     * [extra] = 读进来时这一层不认识的键，原样带回。
     */
    fun dataJson(t: Tree3, extra: Map<String, JsonElement> = emptyMap()): JsonObject = JsonObject(LinkedHashMap<String, JsonElement>().apply {
        put("types", ja(emptyList())); put("items", ja(listOf(noticeItem()))); put("attachments", JsonObject(emptyMap()))
        put("fmt", jn(3.0))
        put("nodes", ja(t.nodes.map { it.toJson() })); put("links", ja(t.links.map { it.toJson() })); put("atts", ja(t.atts.map { it.toJson() }))
        for (k in extra.keys.sorted()) if (k !in DATA_KEYS) put(k, extra.getValue(k))
    })

    /** fmt 3 库这一层认识的键（其余进 extra）。 */
    val DATA_KEYS = setOf("types", "items", "attachments", "fmt", "nodes", "links", "atts")

    /** 类别的默认 data（293 §一）：图标 + 副标题（空时行上写「N 个节点」）；`tags` 从旧记录搬过来。 */
    fun catData(): Map<String, JsonElement> = linkedMapOf("icon" to js("folder"), "iconImg" to jb(false), "sub" to js(""), "tags" to ja(emptyList()))
    fun multiData(): Map<String, JsonElement> = linkedMapOf("entries" to ja(emptyList()))
    /** 多账号的「参与自动填充」在 set（默认开）。 */
    fun multiSet(): Map<String, JsonElement> = linkedMapOf("autofill" to jb(true))

    // 表格宽高（293 §九之二）：像素，null = 默认。
    const val COL_W_DEFAULT = 150; const val COL_W_MIN = 60; const val COL_W_MAX = 480
    const val ROW_H_DEFAULT = 36; const val ROW_H_MIN = 30; const val ROW_H_MAX = 200

    /** 种类的中文名（回收站「来自」、添加菜单、行上无障碍描述）。 */
    fun kindName(kind: String): String = when (kind) {
        "cat" -> "类别"; "text" -> "文本"; "acct" -> "账号"; "multi" -> "多账号"; "table" -> "表格"; "img" -> "图片"; "file" -> "附件"
        "entry" -> "多账号里的账号"
        else -> "节点"
    }

    /** 回收站保留期（同 fmt 2 / 服务端，30 天）。 */
    const val TRASH_KEEP_MS = 30.0 * 24 * 3600 * 1000

    /** 还剩几天：向上取整（删完当天写 30）；过期未清的回 0（PC trash.ts `leftDays`）。 */
    fun leftDays(atMs: Double, nowMs: Double, keepMs: Double = TRASH_KEEP_MS): Int {
        val left = atMs + keepMs - nowMs
        return if (left <= 0) 0 else VNum.int(Math.ceil(left / 86_400_000)) ?: Int.MAX_VALUE
    }

    fun isExpired(atMs: Double, nowMs: Double, keepMs: Double = TRASH_KEEP_MS): Boolean = nowMs - atMs >= keepMs
}

// MARK: - 分数索引（PC ord.ts）

/**
 * 兄弟顺序的**分数索引**：插到两个兄弟之间只给新节点一个夹在中间的串，别人一个都不用重新编号 ——
 * 重新编号会抬一排兄弟的 rev，两台设备各插一个就满屏冲突，这正是扁平表要躲开的那件事。
 * 算法照 Greenspan「Implementing Fractional Indexing」的 midpoint：base62、按 ASCII 比；任何 ord 都不以 "0" 结尾。
 * ⚠️ 中位数**向上取整** `floor((da + db + 1) / 2)`，各端逐字一致（向量 `ord` 一节）。
 */
object VaultOrd {
    const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val ZERO = '0'

    /** 夹在 a、b 之间的串。`a = ""` 表示开头，`b = null` 表示末尾。要求 a < b、两者都不以 "0" 结尾。 */
    fun between(a: String, b: String?): String {
        require(b == null || a < b) { "ord: $a >= $b" }
        require(!a.endsWith(ZERO) && (b == null || !b.endsWith(ZERO))) { "ord: 以 0 结尾" }
        if (b != null) {
            var n = 0
            while (n < b.length && (a.getOrNull(n) ?: ZERO) == b[n]) n++
            if (n > 0) return b.substring(0, n) + between(a.drop(n), b.substring(n))
        }
        val da = if (a.isNotEmpty()) DIGITS.indexOf(a[0]) else 0
        val db = if (b != null) DIGITS.indexOf(b[0]) else DIGITS.length
        if (db - da > 1) return DIGITS[(da + db + 1) / 2].toString()
        if (b != null && b.length > 1) return b.substring(0, 1)
        return DIGITS[da] + between(a.drop(1), null)
    }

    /** 迁移用的第 i 个 ord：三位 base62 定长 + "V"。只看下标，所以哪台设备迁出来都一样。 */
    fun at(i: Int): String {
        require(i in 0 until 62 * 62 * 62) { "ord: 下标越界 $i" }
        return "${DIGITS[i / 3844]}${DIGITS[(i / 62) % 62]}${DIGITS[i % 62]}V"
    }

    /** 兄弟排序：先 ord，再 id（两台设备在同一个空隙里插出同一个 ord 时，按 id 定先后，各端一致）。 */
    val cmp: Comparator<Node3> = Comparator { x, y -> if (x.ord != y.ord) x.ord.compareTo(y.ord) else x.id.compareTo(y.id) }

    /** 排在 [siblings]（已按 cmp 排好）最后的新 ord。 */
    fun after(siblings: List<Node3>): String = between(siblings.lastOrNull()?.ord ?: "", null)
}

// MARK: - 扁平表 → 树（PC tree.ts）

/**
 * **生效父节点是现算的，不回写**：父节点不在了（找不到 / 彻底删除）、或者父节点在回收站里而自己还活着，
 * 这个节点就**显示在根上** —— 不丢、看得见；父节点恢复以后它自己回到原处，因为 `parent` 字段从来没改过。
 * 合并出了环：环上 (rev, id) 最小的那个当根。只读、确定性、各端照抄。
 */
object VaultTreeIndex {
    /** 每个活节点的生效父节点（null = 根）。只看活节点。 */
    fun effectiveParents(nodes: List<Node3>): Map<String, String?> {
        val live = LinkedHashMap<String, Node3>()
        for (n in nodes) if (n.live) live[n.id] = n
        val out = LinkedHashMap<String, String?>()
        for (n in live.values) out[n.id] = if (n.parent != null && live.containsKey(n.parent) && n.parent != n.id) n.parent else null
        val done = HashSet<String>()
        for (start in live.keys) {
            if (start in done) continue
            val path = ArrayList<String>()
            val onPath = HashSet<String>()
            var cur: String? = start
            while (cur != null && cur !in done && cur !in onPath) {
                path += cur; onPath += cur
                cur = out[cur]
            }
            if (cur != null && cur in onPath) {
                val ring = path.subList(path.indexOf(cur), path.size)
                val pick = ring.map { live.getValue(it) }.sortedWith(compareBy<Node3> { it.rev }.thenBy { it.id }).first()
                out[pick.id] = null
            }
            done.addAll(path)
        }
        return out
    }

    /** 活节点按生效父节点分组、组内按 (ord, id) 排。键 null = 根。 */
    fun children(nodes: List<Node3>): Map<String?, List<Node3>> {
        val parents = effectiveParents(nodes)
        val byId = nodes.associateBy { it.id }
        val out = LinkedHashMap<String?, MutableList<Node3>>()
        for ((id, p) in parents) out.getOrPut(p) { mutableListOf() } += byId.getValue(id)
        return out.mapValues { it.value.sortedWith(VaultOrd.cmp) }
    }

    /** 前序遍历活节点（根 → 子，兄弟按 ord）。 */
    fun preorder(nodes: List<Node3>): List<Node3> {
        val idx = children(nodes)
        val out = ArrayList<Node3>()
        fun walk(p: String?) { for (c in idx[p] ?: emptyList()) { out += c; walk(c.id) } }
        walk(null)
        return out
    }

    /** 祖先（从根往下，不含自己）。节点不活或不存在回空。 */
    fun ancestors(nodes: List<Node3>, id: String): List<Node3> {
        val parents = effectiveParents(nodes)
        if (!parents.containsKey(id)) return emptyList()
        val byId = nodes.associateBy { it.id }
        val out = ArrayList<Node3>()
        var p = parents[id]
        while (p != null) { out.add(0, byId.getValue(p)); p = parents[p] }
        return out
    }

    /** 活的后代（不含自己），前序。 */
    fun descendants(nodes: List<Node3>, id: String): List<Node3> {
        val idx = children(nodes)
        val out = ArrayList<Node3>()
        fun walk(p: String) { for (c in idx[p] ?: emptyList()) { out += c; walk(c.id) } }
        walk(id)
        return out
    }
}
