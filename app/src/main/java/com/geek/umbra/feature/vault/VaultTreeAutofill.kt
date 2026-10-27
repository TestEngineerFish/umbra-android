// fmt 3 的自动填充候选（方案 §五；293 §六的字面）。PC 参考实现 autofill3.ts（PC 不做自动填充，那份是给两个手机端对齐用的，
// 向量 `autofill` 一节由它产出，VaultTreeVectorsTest 逐条比对）。纯 Kotlin。
//
// 候选：当前身份库的活节点里，`acct` 一条、`multi` 每条活的条目一条（`set.autofill == false` 整组跳过）。
// 顺序：树的前序（兄弟按 ord），多账号内按条目数组顺序。父节点不在 / 在回收站里的活节点照 [VaultTreeIndex] 挂到根上。
package com.geek.umbra.feature.vault

object VaultAutofill3 {
    /** 账户空着时第一行写的字（和 fmt 2 时一样）。 */
    const val NO_USER = "（没有账号）"
    private const val SEP = " › "

    data class Candidate(
        /** 第一行：账户；空的写「（没有账号）」。 */
        val line1: String,
        /** 第二行：多账号条目 `{名称} · {路径} › {节点}`；账号节点 `{节点名} · {路径}`；在根上时省掉路径。 */
        val line2: String,
        /** 条目 `<nodeId>:<entryId>`，账号节点 `<nodeId>`（与 iOS `recordIdentifier` 同一种写法）。 */
        val recordIdentifier: String,
        /** 网址的 host；网址空就是名称（293 §六）。 */
        val service: String,
        val user: String,
        val pwd: String,
        val url: String,
        /** 用来按名称匹配的那个名字：条目的名称 / 账号节点的标题。 */
        val name: String,
        /** 账号节点的备注 / 条目的备注（选账号页搜索用，不显示）。 */
        val note: String = "",
    )

    /** 路径段 → 文字：超过两层只留最后两层，前面写「…」。 */
    fun pathLabel(titles: List<String>): String = (if (titles.size > 2) listOf("…") + titles.takeLast(2) else titles).joinToString(SEP)

    /** 网址里的 host（小写、去协议、去路径 / 用户名 / 端口、去开头的 www.）。不是网址就当它本身是 host。 */
    fun hostOf(url: String): String {
        var s = url.trim().lowercase()
        if (s.isEmpty()) return ""
        s = s.replace(Regex("^[a-z][a-z0-9+.-]*://"), "")
        s = s.split('/', '?', '#')[0].replace(Regex("^[^@]*@"), "").replace(Regex(":\\d+$"), "")
        return s.removePrefix("www.")
    }

    fun candidates(t: Tree3): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (n in VaultTreeIndex.preorder(t.nodes)) {
            if (n.kind != "acct" && n.kind != "multi") continue
            val path = VaultTreeIndex.ancestors(t.nodes, n.id).map { it.title }
            if (n.kind == "acct") {
                val user = n.data["user"].vString; val url = n.data["url"].vString
                out += Candidate(
                    line1 = user.ifEmpty { NO_USER },
                    line2 = if (path.isNotEmpty()) "${n.title} · ${pathLabel(path)}" else n.title,
                    recordIdentifier = n.id, service = hostOf(url).ifEmpty { n.title },
                    user = user, pwd = n.data["pwd"].vString, url = url, name = n.title, note = n.data["note"].vString,
                )
                continue
            }
            if (!n.flag("autofill", true)) continue
            val where = if (path.isNotEmpty()) pathLabel(path) + SEP + n.title else n.title
            for (e in n.entries) {
                if (!e.live) continue
                out += Candidate(
                    line1 = e.user.ifEmpty { NO_USER }, line2 = "${e.name} · $where",
                    recordIdentifier = "${n.id}:${e.id}", service = hostOf(e.url).ifEmpty { e.name },
                    user = e.user, pwd = e.pwd, url = e.url, name = e.name, note = e.note,
                )
            }
        }
        return out
    }

    /**
     * 名称匹配（网址空着时用，293 §五第 2 条）：名称转小写、去所有空白，**至少 3 个字符**，等于目标（网页域名或 App 包名）
     * 按 `.` 切开的某一整段就算命中。例：「GitHub」命中 `github.com`、`com.github.android`；「Git」不命中 `github.com`。
     */
    fun nameMatches(name: String, target: String): Boolean {
        val k = name.lowercase().replace(Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+"), "")   // JS 的 \s 也含这些 Unicode 空白
        if (k.length < 3) return false
        return target.lowercase().split('.').contains(k)
    }
}
