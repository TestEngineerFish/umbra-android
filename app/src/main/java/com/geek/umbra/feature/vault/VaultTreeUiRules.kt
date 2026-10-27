// 保险箱树形（fmt 3）界面上的纯判据：列表行的第二行、删除确认的字面、搜索命中、多账号页的说明句。
// 字面照设计侧 293 §三（七种行）、§五（多账号）、§八（删除）、§九（移动端）。纯 Kotlin，VaultTreeUiRulesTest 有用例。
package com.geek.umbra.feature.vault

object VaultTreeUi {
    /** fmt 3 节点给沿用 fmt 2 的渲染代码（节点卡、搜索字段）用。 */
    fun asVNode(n: Node3): VNode = VNode(n.id, n.kind, title = n.title, data = n.data, set = n.set)

    /** 行上的标题：空了用种类名。 */
    fun title(n: Node3): String = n.title.ifEmpty { Fmt3.kindName(n.kind) }

    /**
     * 列表行的第二行（293 §三）：类别 = 副标题（空时「N 个节点」）；账号 = 用户名；多账号 = 「5 个账号 · 阿里云、腾讯云、AWS…」；
     * 文本 = 正文第一行（遮盖时「已遮盖」）；表格 = 「4 列 × 6 行 · 主机、IP、端口、用途」；图片 = 「2 张 · 已模糊」；附件 = 「2 个 · 1.4 MB」。
     */
    fun sub(n: Node3, t: Tree3, kids: Int): String = when (n.kind) {
        "cat" -> n.data["sub"].vString.trim().ifEmpty { "$kids 个节点" }
        "acct" -> n.data["user"].vString
        "multi" -> {
            val live = n.entries.filter { it.live }
            if (live.isEmpty()) "还没有账号"
            else "${live.size} 个账号 · " + live.take(3).joinToString("、") { it.name } + if (live.size > 3) "…" else ""
        }
        "text" -> if (n.flag("masked", false)) "已遮盖" else n.data["body"].vString.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        "table" -> {
            val cols = n.data["cols"].vStrings
            val rows = n.data["rows"].vArray?.size ?: 0
            "${cols.size} 列 × $rows 行" + if (cols.any { it.isNotBlank() }) " · " + cols.filter { it.isNotBlank() }.joinToString("、") else ""
        }
        "img" -> {
            val k = n.data["items"].vArray?.size ?: 0
            "$k 张" + if (n.flag("blur", false)) " · 已模糊" else ""
        }
        "file" -> {
            val ids = (n.data["items"].vArray ?: emptyList()).map { it.vObject?.get("attId").vString }
            val bytes = t.atts.filter { it.id in ids && !it.gone }.sumOf { it.size }
            "${ids.size} 个" + if (bytes > 0) " · " + VaultNodeKind.sizeText(bytes) else ""
        }
        else -> "在电脑上查看"
    }

    /** 删除确认的标题 + 正文（293 §八）。N = 全部后代；账号数 = 账号节点 + 多账号条数，为 0 时那半句不写。 */
    fun deleteCopy(n: Node3, impact: VaultTrash3.Impact): Pair<String, String> {
        val head = if (impact.descendants > 0) "删除后会和它下面的 ${impact.descendants} 个节点一起移入回收站，保留 30 天。恢复时整组一起回来。"
        else "删除后会移入回收站，保留 30 天。"
        val fill = when {
            impact.accounts == 0 -> ""
            impact.descendants == 0 && n.kind == "acct" -> "它会同时从自动填充里撤下。"
            else -> "其中 ${impact.accounts} 个账号会同时从自动填充里撤下。"
        }
        return "删除「${title(n)}」？" to head + fill
    }

    /** 多账号里的一条（293 §八）。 */
    fun deleteEntryCopy(e: Entry3): Pair<String, String> =
        "删除账号「${e.name.ifEmpty { "未命名" }}」？" to "删除后会移入回收站，保留 30 天。它会同时从自动填充里撤下。"

    /** 多账号页底下那句（293 §五第 6 条，两句都照它写全；iOS 03 稿上那句是缩写）。 */
    fun multiFoot(n: Node3): String {
        val k = n.entries.count { it.live }
        return if (!n.flag("autofill", true)) "自动填充已关：这些账号在保险箱里仍能搜到，但 iPhone 和 Android 填密码时查不到。"
        else "这 $k 个账号都能被搜到，也会出现在 iPhone 和 Android 的密码自动填充里：先按网址匹配，没填网址的按名称匹配。"
    }

    /** 单个账号页底下那句（293 §九 iOS 04）。 */
    fun entryFoot(n: Node3, e: Entry3): String {
        if (!n.flag("autofill", true)) return "「${title(n)}」的自动填充已关，填密码时查不到这个账号。"
        val host = VaultAutofill3.hostOf(e.url)
        return if (host.isNotEmpty()) "参与自动填充 · 匹配 $host。网址留空时按名称「${e.name}」匹配。"
        else "参与自动填充 · 没填网址，按名称「${e.name}」匹配。"
    }

    /** 新建账号时名称没填（293 §五第 2 条）。 */
    const val NAME_REQUIRED = "名称要填：搜索和自动填充都靠它认出这个账号"

    /** 一条搜索结果：节点，或多账号里的一个账号（[entryId] 非空）。[path] = 祖先标题（不含自己），从根往下。 */
    data class Hit(val node: Node3, val entryId: String?, val title: String, val sub: String, val path: List<String>)

    /**
     * 搜索（293 §五「搜索」）：节点按标题与可搜字段（**pwd / seed / 遮盖正文 / 密文列永不进**，同 [VNode.searchParts]）；
     * 多账号里的每一条按名称 / 账户 / 网址 / 备注，结果写「多账号里的账号 · {账户}」，路径写到节点为止。
     */
    fun search(t: Tree3, query: String): List<Hit> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<Hit>()
        val idx = VaultTreeIndex.children(t.nodes)
        for (n in VaultTreeIndex.preorder(t.nodes)) {
            val path = VaultTreeIndex.ancestors(t.nodes, n.id).map { title(it) }
            if (asVNode(n).searchParts().any { it.lowercase().contains(q) } || (n.kind == "cat" && n.data["sub"].vString.lowercase().contains(q))) {
                out += Hit(n, null, title(n), sub(n, t, idx[n.id]?.size ?: 0), path)
            }
            if (n.kind == "multi") for (e in n.entries) {
                if (!e.live) continue
                if (listOf(e.name, e.user, e.url, e.note).any { it.lowercase().contains(q) }) {
                    out += Hit(n, e.id, e.name.ifEmpty { "未命名" }, "多账号里的账号 · ${e.user.ifEmpty { VaultAutofill3.NO_USER }}", path + title(n))
                }
            }
        }
        return out
    }

    /** 路由 id 拆开：多账号里的一条是 `<nodeId>:<entryId>`。 */
    fun splitId(id: String): Pair<String, String?> {
        val i = id.indexOf(':')
        return if (i > 0) id.substring(0, i) to id.substring(i + 1) else id to null
    }

    /**
     * 新建的路由 id：`new:<kind>:<parentId>`（父在根上写空）。新建的东西**保存时才落库**：先建再编辑的话，
     * 人点了「+」又反悔，树上就多一个空节点。多账号里新增一条是 `<nodeId>:new`。
     */
    fun newId(kind: String, parent: String?): String = "new:$kind:${parent ?: ""}"

    data class NewSpec(val kind: String, val parent: String?)

    fun parseNew(id: String): NewSpec? {
        if (!id.startsWith("new:")) return null
        val rest = id.removePrefix("new:")
        val i = rest.indexOf(':')
        if (i <= 0) return null
        return NewSpec(rest.substring(0, i), rest.substring(i + 1).ifEmpty { null })
    }

    /** 手机上能新建的种类（293 §二第 7 条的添加菜单；图片 / 附件的字节手机上传不了，不给）。 */
    val ADDABLE = listOf("cat", "acct", "multi", "text", "table")

    /** 添加节点加到哪一层（293 §二第 7 条）：选中的是类别或有子节点的节点 → 加到它下面；否则加成它的兄弟。 */
    fun addTarget(selected: Node3?, hasKids: Boolean, effectiveParent: String?): String? = when {
        selected == null -> null
        selected.kind == "cat" || hasKids -> selected.id
        else -> effectiveParent
    }
}
