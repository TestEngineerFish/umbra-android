// fmt 3 的合并（方案 §一、§二第 6 条）与回收站（§一第 5 条）。PC 参考实现 merge3.ts、trash3.ts。
//
// 合并：节点 / 多账号条目 / 关联线 / 附件四种对象，**各自按 id 逐个比**；两边都有时 rev 大的赢 → updatedAt 大的赢 →
// gone > del > 活的（删除赢，别让删掉的复活）→ 都相同留本机那份。迁移来的 rev 0 节点有一条首合规则（[pickNode]）。
// ⚠️ 结果要和参数顺序无关（A 并 B 与 B 并 A 得到同一份），否则两台设备合完各是各的 —— 向量两个方向都比。
//
// 回收站：删一个节点 = 它和它全部活的后代打上同一个 `del = {at, grp: 它的 id}`、各自 rev+1；恢复按 grp 整组回来。
// 不挪进单独的 trash[]：挪出去以后别的设备上还留着这些节点（rev 旧），合并时没有对手可比，就原样复活了。
// 这里一律回新的 Tree3（PC 是原地改），Store 拿回来换掉快照。纯 Kotlin。
package com.geek.umbra.feature.vault

object VaultMerge3 {
    // 方案 §七第 4 条，后缀待设计侧定稿；与 PC 逐字一致。
    const val LOCAL_COPY_SUFFIX = "（另一台设备上没同步的改动）"

    private fun tomb(gone: Boolean, del: Boolean): Int = if (gone) 2 else if (del) 1 else 0

    /** 两份同 id 的对象谁赢。true = b 赢。 */
    private fun newer(aRev: Double, aAt: Double, aTomb: Int, bRev: Double, bAt: Double, bTomb: Int): Boolean {
        if (aRev != bRev) return bRev > aRev
        if (aAt != bAt) return bAt > aAt
        return bTomb > aTomb
    }

    private fun newer(a: Node3, b: Node3) = newer(a.rev, a.updatedAt, tomb(a.gone, a.del != null), b.rev, b.updatedAt, tomb(b.gone, b.del != null))
    private fun newer(a: Entry3, b: Entry3) = newer(a.rev, a.updatedAt, tomb(a.gone, a.del != null), b.rev, b.updatedAt, tomb(b.gone, b.del != null))
    private fun newer(a: Link3, b: Link3) = newer(a.rev, a.updatedAt, tomb(a.gone, false), b.rev, b.updatedAt, tomb(b.gone, false))
    private fun newer(a: Att3, b: Att3) = newer(a.rev, a.updatedAt, tomb(a.gone, false), b.rev, b.updatedAt, tomb(b.gone, false))

    /** 内容相同（首合规则判「本机这份是不是改过」）：标题 + parent + data + set。JsonObject 的相等是按值、不看键序。 */
    private fun sameContent(a: Node3, b: Node3): Boolean = a.title == b.title && a.parent == b.parent && a.data == b.data && a.set == b.set

    /** 合并后的多账号条目：以赢的那份（[order]）的数组顺序为主，另一份独有的按 id 升序接在后面 —— 和参数顺序无关。 */
    private fun entriesFor(order: Node3, other: Node3): List<Entry3> {
        val merged = LinkedHashMap<String, Entry3>()
        for (e in order.entries) merged[e.id] = e
        for (e in other.entries) { val c = merged[e.id]; if (c == null || newer(c, e)) merged[e.id] = e }
        val head = order.entries.map { merged.getValue(it.id) }
        val seen = head.map { it.id }.toSet()
        return head + merged.values.filter { it.id !in seen }.sortedBy { it.id }
    }

    /** 两边都没彻底删除的多账号：条目逐条合并，挂在赢的那个节点上。 */
    private fun withEntries(win: Node3, lose: Node3): Node3 {
        if (win.kind != "multi" || lose.kind != "multi" || win.gone || lose.gone) return win
        return win.withEntries(entriesFor(win, lose))
    }

    /**
     * 一对同 id 节点的结果（可能多出一个副本）。
     *
     * 首合规则：一边是迁移来的 rev 0，另一边 rev ≥ 1（云端在迁移后又改过），而且 rev 0 那份的 `mig.src` 比另一边的大
     * （旧版设备在云端已是 fmt 3、自己推不上去的那段时间里改的），内容又确实不同 → **两份都留**：用 rev ≥ 1 那份，
     * rev 0 那份作为它的兄弟加进去（id `<原id>~<src>`，标题加后缀）。副本 id 是确定的，两台设备各自合出来是同一个。
     */
    fun pickNode(a: Node3, b: Node3): List<Node3> {
        val (old, cur) = when {
            a.rev == 0.0 && b.rev >= 1 -> a to b
            b.rev == 0.0 && a.rev >= 1 -> b to a
            else -> null to null
        }
        if (old != null && cur != null && old.migSrc != null && !old.gone && !cur.gone && old.migSrc > (cur.migSrc ?: -1.0) && !sameContent(old, cur)) {
            val copy = old.copy(
                id = "${old.id}~${VaultTreeText.num(old.migSrc)}", title = old.title + LOCAL_COPY_SUFFIX,
                parent = cur.parent, ord = cur.ord + "V", rev = 1.0, del = null, migSrc = null, pos = null,
            )
            return listOf(withEntries(cur, old), copy)
        }
        val win = if (newer(a, b)) b else a
        val lose = if (win === a) b else a
        if (old == null && a.rev == 0.0 && b.rev == 0.0 && a.migSrc != null && b.migSrc != null && a.migSrc != b.migSrc) {
            // 两边都是迁移来的：谁迁移前那条记录更新，就用谁的。
            return listOf(if (a.migSrc > b.migSrc) a else b)
        }
        return listOf(withEntries(win, lose))
    }

    /** 合并两份 fmt 3 的库。不改参数，回一份新的；三张表都按 id 升序。 */
    fun merge(local: Tree3, remote: Tree3): Tree3 {
        val byId = LinkedHashMap<String, Node3>()
        for (n in local.nodes) byId[n.id] = n
        val extra = ArrayList<Node3>()
        for (r in remote.nodes) {
            val l = byId[r.id]
            if (l == null) { byId[r.id] = r; continue }
            val picked = pickNode(l, r)
            byId[r.id] = picked[0]
            extra += picked.drop(1)
        }
        for (c in extra) { val x = byId[c.id]; if (x == null || newer(x, c)) byId[c.id] = c }
        val links = LinkedHashMap<String, Link3>()
        for (x in local.links) links[x.id] = x
        for (x in remote.links) { val c = links[x.id]; if (c == null || newer(c, x)) links[x.id] = x }
        val atts = LinkedHashMap<String, Att3>()
        for (x in local.atts) atts[x.id] = x
        for (x in remote.atts) { val c = atts[x.id]; if (c == null || newer(c, x)) atts[x.id] = x }
        return Tree3(byId.values.sortedBy { it.id }, links.values.sortedBy { it.id }, atts.values.sortedBy { it.id })
    }
}

/** 文字小工具：数字按 JS 的样子写（副本 id 里的 src、日志）。 */
internal object VaultTreeText {
    fun num(x: Double): String = JsSem.number(x)
}

// MARK: - 回收站：整组进、整组出（PC trash3.ts）

object VaultTrash3 {
    private fun bumped(n: Node3, now: Double) = n.copy(rev = n.rev + 1, updatedAt = now)
    private fun bumped(e: Entry3, now: Double) = e.copy(rev = e.rev + 1, updatedAt = now)

    /** 一组节点里有几个账号：acct 节点 + 多账号里活着的条目。 */
    fun accountsIn(list: List<Node3>): Int = list.sumOf { x ->
        when (x.kind) {
            "acct" -> 1
            "multi" -> x.entries.count { it.live }
            else -> 0
        }
    }

    /** 删除确认框要的两个数（293 §八）：全部后代数（不只下一层）、账号数（为 0 时那半句不写）。 */
    data class Impact(val descendants: Int, val accounts: Int)

    fun deleteImpact(t: Tree3, id: String): Impact {
        val n = t.nodes.firstOrNull { it.id == id && it.live } ?: return Impact(0, 0)
        val ds = VaultTreeIndex.descendants(t.nodes, id)
        return Impact(ds.size, accountsIn(listOf(n) + ds))
    }

    /** 删一个节点（连同活的后代）进回收站。回新树 + 动了几个节点。 */
    fun trashNode(t: Tree3, id: String, now: Double): Pair<Tree3, Int> {
        val n = t.nodes.firstOrNull { it.id == id && it.live } ?: return t to 0
        val group = (listOf(n) + VaultTreeIndex.descendants(t.nodes, id)).map { it.id }.toSet()
        val nodes = t.nodes.map { if (it.id in group) bumped(it, now).copy(del = Del3(now, id)) else it }
        return t.copy(nodes = nodes) to group.size
    }

    /** 删多账号里的一条。节点本身 rev 也要抬：条目合并挂在节点上。 */
    fun trashEntry(t: Tree3, nodeId: String, entryId: String, now: Double): Tree3? {
        val n = t.nodes.firstOrNull { it.id == nodeId && it.live } ?: return null
        val es = n.entries
        if (es.none { it.id == entryId && it.live }) return null
        val list = es.map { if (it.id == entryId) bumped(it, now).copy(del = Del3(now, "$nodeId:$entryId")) else it }
        return t.withNode(bumped(n, now).withEntries(list))
    }

    data class Group(
        val grp: String,
        /** "node" / "entry"。 */
        val kind: String,
        /** 组根的标题 / 条目的名称。 */
        val title: String,
        val at: Double,
        val leftDays: Int,
        /** 组里除组根外的节点数（「和它下面的 N 个节点一起」）。条目组为 0。 */
        val descendants: Int,
        /** 恢复会带回自动填充的账号数。 */
        val accounts: Int,
        /** 组根的种类（列表上的图标）；条目组是 "entry"。 */
        val rootKind: String,
    )

    /** 回收站列表：一组一条，最近删的在前。 */
    fun groups(t: Tree3, now: Double): List<Group> {
        val byGrp = LinkedHashMap<String, MutableList<Node3>>()
        for (n in t.nodes) { if (n.del == null || n.gone) continue; byGrp.getOrPut(n.del.grp) { mutableListOf() } += n }
        val out = ArrayList<Group>()
        for ((grp, list) in byGrp) {
            val root = list.firstOrNull { it.id == grp } ?: list[0]
            val at = root.del!!.at
            out += Group(grp, "node", root.title, at, Fmt3.leftDays(at, now), list.size - 1, accountsIn(list), root.kind)
        }
        for (n in t.nodes) {
            if (n.gone) continue
            for (e in n.entries) {
                if (e.del == null || e.gone) continue
                out += Group(e.del.grp, "entry", e.name, e.del.at, Fmt3.leftDays(e.del.at, now), 0, 1, "entry")
            }
        }
        return out.sortedWith(compareByDescending<Group> { it.at }.thenBy { it.grp })
    }

    /**
     * 恢复一组。节点组：同一 grp 的全部清掉 del（组里先单独删过的 grp 不同，不跟着回来 —— 那是另一次删除）；组根原来的父节点
     * 不在了的不用改，[VaultTreeIndex] 现算生效父节点时自己落到根上。条目组：清掉条目的 del；所在多账号节点也在回收站的，
     * 连节点那一组一起恢复。回新树 + 恢复了几个对象。
     */
    fun restore(t: Tree3, grp: String, now: Double): Pair<Tree3, Int> {
        val sep = grp.indexOf(':')
        if (sep > 0) {
            val nodeId = grp.substring(0, sep); val entryId = grp.substring(sep + 1)
            val n = t.nodes.firstOrNull { it.id == nodeId && !it.gone } ?: return t to 0
            if (n.entries.none { it.id == entryId && it.del?.grp == grp && !it.gone }) return t to 0
            val n2 = bumped(n, now).withEntries(n.entries.map { if (it.id == entryId) bumped(it, now).copy(del = null) else it })
            val t2 = t.withNode(n2)
            if (n2.del == null) return t2 to 1
            val (t3, k) = restore(t2, n2.del.grp, now)
            return t3 to 1 + k
        }
        var k = 0
        val nodes = t.nodes.map { if (!it.gone && it.del?.grp == grp) { k++; bumped(it, now).copy(del = null) } else it }
        return t.copy(nodes = nodes) to k
    }

    /** 彻底删除一组回的东西：新树、擦掉几个、要去云端删字节的附件（id + fileId）。 */
    data class Purged(val tree: Tree3, val purged: Int, val atts: List<Pair<String, String?>>)

    /**
     * 彻底删除一组：节点写成墓碑（标题 / 内容 / 位置清空，留 id / parent / rev）；条目同理。端点没了的关联线写成 gone；
     * **只被这一组引用的附件**写成 gone，并回它们的 id + fileId。（手机端不删云端字节：方案 §三之二，删字节只走 PC。）
     */
    fun purge(t: Tree3, grp: String, now: Double): Purged {
        val sep = grp.indexOf(':')
        if (sep > 0) {
            val n = t.nodes.firstOrNull { it.id == grp.substring(0, sep) && !it.gone } ?: return Purged(t, 0, emptyList())
            val entryId = grp.substring(sep + 1)
            if (n.entries.none { it.id == entryId && it.del?.grp == grp && !it.gone }) return Purged(t, 0, emptyList())
            val list = n.entries.map {
                if (it.id == entryId) bumped(it, now).copy(name = "", url = "", user = "", pwd = "", note = "", gone = true, del = null) else it
            }
            return Purged(t.withNode(bumped(n, now).withEntries(list)), 1, emptyList())
        }
        var hit = 0
        val nodes = t.nodes.map {
            if (!it.gone && it.del?.grp == grp) {
                hit++
                bumped(it, now).copy(title = "", data = emptyMap(), set = emptyMap(), gone = true, pos = null, del = null)
            } else it
        }
        val gone = nodes.filter { it.gone }.map { it.id }.toSet()
        val links = t.links.map {
            if (!it.gone && (it.a in gone || it.b in gone)) it.copy(gone = true, label = "", rev = it.rev + 1, updatedAt = now) else it
        }
        val stillUsed = HashSet<String>()
        for (n in nodes) {
            if (n.gone || (n.kind != "img" && n.kind != "file")) continue
            for (x in n.data["items"].vArray ?: emptyList()) x.vObject?.get("attId")?.let { a -> JsSem.str(a)?.let { stillUsed += it } }
        }
        val dropped = ArrayList<Pair<String, String?>>()
        val atts = t.atts.map { a ->
            if (a.gone || a.id in stillUsed) return@map a
            dropped += a.id to a.fileId
            Att3(LinkedHashMap(a.raw).apply {
                put("gone", jb(true)); put("name", js("")); remove("fileId"); put("rev", jn(a.rev + 1))
            })
        }
        return Purged(Tree3(nodes, links, atts), hit, dropped)
    }

    /** 到期（30 天）的组。解锁时清扫（同 fmt 2 `sweepExpiredTrash` 的时机）。 */
    fun expired(t: Tree3, now: Double, keepMs: Double = Fmt3.TRASH_KEEP_MS): List<String> =
        groups(t, now).filter { Fmt3.isExpired(it.at, now, keepMs) }.map { it.grp }
}
