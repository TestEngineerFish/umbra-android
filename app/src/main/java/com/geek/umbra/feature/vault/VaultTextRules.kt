// 保险箱里几条与文字相关的纯判据：@ 引用（VaultMentionRules.swift）、记录内查找（VaultFindRules.swift）。
// 纯 Kotlin，有 JUnit 用例（VaultMentionRulesTest 吃 PC 夹具 mentionCases.json、VaultFindRulesTest 照抄 iOS 用例）。
package com.geek.umbra.feature.vault

/** @ 引用解析出现名：回现名（调用方已经按「空标题用种类名」处理好），找不到回 null。 */
typealias MentionResolve = (itemId: String, nodeId: String) -> String?

/**
 * 保险箱节点 @ 引用（PC 2026-10-13 起写入，手机只读不写）：`@[备份名](记录id/节点id)`。
 * ⚠️ 正则与标记格式是**两端冻结的契约**（PC `mentionKit.ts` 的 `MENTION_RE`），改之前先改 PC 和夹具。
 */
object VaultMentionRules {
    /**
     * 与 PC `tree/mention.ts` 的 `MENTION_RE`（10b3c88）逐字相同：fmt 3 写单段 `@[名](节点id)`，fmt 2 旧写法 `@[名](记录id/节点id)` 也认
     * （迁移改写不过去的旧 token 原样留着）。单段时 [Seg.Mention.itemId] 是空串，按节点 id 找。
     */
    const val PATTERN = """@\[([^\[\]()]*)\]\(([A-Za-z0-9_-]+)(?:/([A-Za-z0-9_-]+))?\)"""
    private val regex = Regex(PATTERN)

    sealed interface Seg {
        data class Text(val value: String) : Seg
        data class Mention(val itemId: String, val nodeId: String, val fallback: String) : Seg
    }

    /**
     * 切段。空串回空列表；一个引用都没有时回一整段文字（同 PC `parseMentions`）。
     * Kotlin 的 String 下标就是 UTF-16 下标（与 iOS NSRegularExpression 同口径），emoji 在前面也不会切歪。
     */
    fun parse(text: String): List<Seg> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<Seg>()
        var last = 0
        for (m in regex.findAll(text)) {
            if (m.range.first > last) out += Seg.Text(text.substring(last, m.range.first))
            // 旧写法第三段才是节点 id；单段的只有节点 id。
            val two = m.groups[3] != null
            out += Seg.Mention(if (two) m.groupValues[2] else "", if (two) m.groupValues[3] else m.groupValues[2], m.groupValues[1])
            last = m.range.last + 1
        }
        if (last < text.length) out += Seg.Text(text.substring(last))
        return if (out.isEmpty()) listOf(Seg.Text(text)) else out
    }

    /** 有没有至少一个引用。编辑页拿它决定这一格能不能在手机上改。 */
    fun hasMention(text: String): Boolean = regex.containsMatchIn(text)

    /** 一个引用显示成什么：`@现名`；找不到时 `@备份名`（备份名空就「未命名」），并标缺失。 */
    fun label(itemId: String, nodeId: String, fallback: String, resolve: MentionResolve): Pair<String, Boolean> {
        resolve(itemId, nodeId)?.let { return "@$it" to false }
        val fb = fallback.trim()
        return "@" + (fb.ifEmpty { "未命名" }) to true
    }

    /** 明文：每个引用换成 `@现名`。复制、遮罩点数、搜索都用它 —— 标记本身不该离开这台手机。 */
    fun plainText(text: String, resolve: MentionResolve): String = parse(text).joinToString("") { seg ->
        when (seg) {
            is Seg.Text -> seg.value
            is Seg.Mention -> label(seg.itemId, seg.nodeId, seg.fallback, resolve).first
        }
    }

    /** 不查现名的明文（拿不到保险箱目录的地方用）：一律用备份名。 */
    fun plainTextOffline(text: String): String = plainText(text) { _, _ -> null }
}

/** 一张卡此刻在查找里的状态：要高亮的词（已规整）+ 它是不是「当前那一个」命中。 */
data class VaultFindMark(val query: String, val current: Boolean)

/** 查找条计数格此刻该说什么（设计侧 243 §三③）。 */
enum class VaultFindOutcome { Idle, Nodes, OnlyName, OnlyTags, None }

/**
 * 保险箱记录详情里的「查找」（iOS VaultFindRules.swift）。命中顺序直接就是卡片顺序（sortedNodes）。
 * ⚠️ 安全不变量：密文（遮盖的正文、pwd、seed、表格密文列）永不参与匹配 —— 哪些字段能被搜到只在 [VNode.searchParts] 定义。
 */
object VaultFindRules {
    /** 计数格的档位：节点 → 记录名 → 标签 → 没有。 */
    fun outcome(hitCount: Int, title: String, tags: List<String>?, query: String): VaultFindOutcome {
        val q = norm(query)
        if (q.isEmpty()) return VaultFindOutcome.Idle
        if (hitCount > 0) return VaultFindOutcome.Nodes
        if (title.lowercase().contains(q)) return VaultFindOutcome.OnlyName
        if ((tags ?: emptyList()).any { it.lowercase().contains(q) }) return VaultFindOutcome.OnlyTags
        return VaultFindOutcome.None
    }

    /** 规整查找词：去首尾空白、转小写。空串 = 不在查找。 */
    fun norm(q: String): String = q.trim().lowercase()

    fun matches(n: VNode, q: String, resolve: MentionResolve = { _, _ -> null }): Boolean {
        if (q.isEmpty()) return false
        return n.searchParts(resolve).any { it.lowercase().contains(q) }
    }

    /** 命中的节点 id，按传进来的顺序（调用方传 sortedNodes）。 */
    fun hits(nodes: List<VNode>, query: String, resolve: MentionResolve = { _, _ -> null }): List<String> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        return nodes.filter { matches(it, q, resolve) }.map { it.id }
    }

    /** 上一个 / 下一个：在 [0, count) 里循环；没有命中回 -1；当前是 -1 时往下到第一个、往上到最后一个。 */
    fun step(idx: Int, count: Int, dir: Int): Int {
        if (count <= 0) return -1
        if (idx < 0 || idx >= count) return if (dir >= 0) 0 else count - 1
        val d = if (dir >= 0) 1 else -1
        return (idx + d + count) % count
    }

    /** 命中列表变了之后当前下标落在哪：原来那个 id 还在就跟着它，不在了回到第一个。 */
    fun reindex(previousId: String?, hits: List<String>): Int {
        if (hits.isEmpty()) return -1
        if (previousId != null) { val i = hits.indexOf(previousId); if (i >= 0) return i }
        return 0
    }

    /** 「2 / 5」里那两个数（从 1 起）。没有命中时 null。 */
    fun counter(idx: Int, count: Int): Pair<Int, Int>? {
        if (count <= 0 || idx < 0 || idx >= count) return null
        return (idx + 1) to count
    }

    /** 一段**明文**里查找词出现的位置（不重叠、从左到右、大小写不敏感），给高亮用。⚠️ 只许拿非密文调它。 */
    fun ranges(text: String, query: String): List<IntRange> {
        val q = norm(query)
        if (q.isEmpty() || text.isEmpty()) return emptyList()
        val out = mutableListOf<IntRange>()
        var from = 0
        while (from < text.length) {
            val i = text.indexOf(q, from, ignoreCase = true)
            if (i < 0) break
            out += i until i + q.length
            from = i + q.length
        }
        return out
    }
}
