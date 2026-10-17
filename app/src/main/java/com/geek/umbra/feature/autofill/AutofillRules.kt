// 密码自动填充的纯判据 —— 照抄 iOS AutoFillExtension/CredentialProviderViewController.swift 的列表那一半
// （matched / candidates / rowTitle / listCaption / emptyText），外加 Android 才有的「按 App 包名匹配」。
// 纯 Kotlin，AutofillRulesTest 有用例。
package com.geek.umbra.feature.autofill

import com.geek.umbra.feature.vault.VAccount
import com.geek.umbra.feature.vault.VItem
import com.geek.umbra.net.UmbraUrl

/** 列表里的一行 = 一条记录里的**一个**账号（umbra-ios#14：一条记录两个账号就是两行）。 */
data class AutofillRow(val item: VItem, val account: VAccount) {
    val id: String get() = item.id + "#" + account.id
}

/** 系统交来的「当前在哪个网站 / App 上要填」。 */
data class AutofillTarget(
    /** 网页里的域名（`ViewNode.webDomain`），已经过 [UmbraUrl.host] 归一；不是网页时为空串。 */
    val domain: String,
    /** 请求填充的那个 App 的包名（网页里是浏览器的包名）。 */
    val packageName: String,
)

object AutofillRules {
    /**
     * App 里的登录框（没有 webDomain）：拿记录网址的主体那一节（`github.com` → `github`）去比包名的各段
     * （`com.github.android` → github）。**按整段比，不按子串**：子串会让 `hub` 命中 `com.github.android`。
     * 这是 Android 才有的一档（iOS 系统直接给关联域名）；判得出才 +2，判不出就当普通记录排在后面，不挡人自己挑。
     */
    fun packageMatches(pkg: String, host: String): Boolean {
        if (pkg.isEmpty() || host.isEmpty()) return false
        val labels = host.split('.').filter { it.isNotEmpty() }
        if (labels.size < 2) return false
        // 主体那一节：去掉末尾的顶级域（以及 com.cn / co.uk 这类两节后缀里的那一节）。
        val tail2 = labels.takeLast(2).joinToString(".")
        val core = if (labels.size >= 3 && tail2 in TWO_PART_SUFFIXES) labels[labels.size - 3] else labels[labels.size - 2]
        if (core.length < 3) return false
        return pkg.lowercase().split('.').any { it == core.lowercase() }
    }

    private val TWO_PART_SUFFIXES = setOf("com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "co.uk", "org.uk", "co.jp", "com.hk", "com.tw", "com.au")

    /** 这一行是不是「属于当前站点 / App」：网页按域名标签边界比（防钓鱼），App 按包名段比。 */
    fun sameTarget(target: AutofillTarget, url: String): Boolean {
        val host = UmbraUrl.host(url)
        if (host.isEmpty()) return false
        if (target.domain.isNotEmpty()) return UmbraUrl.sameSite(host, target.domain)
        return packageMatches(target.packageName, host)
    }

    /** 用户**自己动过**搜索框之后的那个词；还停在预填的域名上时是空串（搜索和「属于这个站点」是两件事）。 */
    fun typedQuery(query: String, domain: String): String {
        val q = query.trim()
        if (q.isEmpty()) return ""
        return if (q.equals(domain, ignoreCase = true)) "" else q.lowercase()
    }

    /** 还停在「只看这个站」那一档吗：系统给了域名，搜索框里仍是预填的那个域名、一字未改。清空 = 不再按站收窄。 */
    fun domainScoped(query: String, domain: String): Boolean =
        domain.isNotEmpty() && query.trim().equals(domain, ignoreCase = true)

    /**
     * 某个分类下，按当前搜索框命中的行和分数（没排序）。芯片计数和列表共用这一份。
     * +2 同站（按这一块自己的网址算，不按整条记录）；+1 用户自己打的字命中（记录标题、节点标题、网址、用户名、备注，子串）。
     * 有筛选条件（在搜、或还停在预填的域名上）就只留命中的；搜索框清空了就全给。
     */
    fun matched(items: List<VItem>, target: AutofillTarget, query: String, cat: String): List<Pair<AutofillRow, Int>> {
        val q = typedQuery(query, target.domain)
        var withAccount = items.filter { it.accounts.isNotEmpty() }
        if (cat == "fav") withAccount = withAccount.filter { it.favorite == true }
        else if (cat.isNotEmpty()) withAccount = withAccount.filter { it.typeId == cat }
        val scored = withAccount.flatMap { it ->
            it.accounts.map { acc ->
                var score = 0
                if (sameTarget(target, acc.url)) score += 2
                if (q.isNotEmpty() && listOf(it.title, acc.title, acc.url, acc.user, acc.note).any { h -> h.lowercase().contains(q) }) score += 1
                AutofillRow(it, acc) to score
            }
        }
        val filtering = q.isNotEmpty() || domainScoped(query, target.domain)
        return if (filtering) scored.filter { it.second > 0 } else scored
    }

    /** 可填充的每一个账号一行；先按分数排，再按标题，同分同标题保持记录里的块顺序（稳定排序）。 */
    fun candidates(items: List<VItem>, target: AutofillTarget, query: String, cat: String): List<AutofillRow> =
        matched(items, target, query, cat).sortedWith(compareByDescending<Pair<AutofillRow, Int>> { it.second }.thenBy { it.first.item.title }).map { it.first }

    /** 不用打开面板就能直接给的那几行：**只给同站 / 同 App 的**（直接出现在输入框下面的建议必须是确定属于这里的）。 */
    fun directSuggestions(items: List<VItem>, target: AutofillTarget, max: Int = 5): List<AutofillRow> =
        items.flatMap { it -> it.accounts.map { acc -> AutofillRow(it, acc) } }
            .filter { sameTarget(target, it.account.url) }
            .sortedBy { it.item.title }
            .take(max)

    /** 行标题：「记录 · 节点」。节点标题空或和记录同名时只写记录名，不拼出「GitHub · GitHub」。 */
    fun rowTitle(r: AutofillRow): String {
        val rec = r.item.title.trim()
        val node = r.account.title.trim()
        if (node.isEmpty() || node == rec) return rec
        if (rec.isEmpty()) return node
        return "$rec · $node"
    }

    /** 列表小标题：跟着当前筛选走。 */
    fun listCaption(query: String, domain: String, cat: String, typeName: String?): String {
        if (typedQuery(query, domain).isNotEmpty()) return "搜索结果"
        if (cat == "fav") return "收藏"
        if (typeName != null) return typeName
        return if (domainScoped(query, domain)) "「$domain」的匹配项" else "全部可填充的记录"
    }

    /** 空态文案分几种情况说清楚，别一句「没有记录」打发。 */
    fun emptyText(itemsEmpty: Boolean, query: String, domain: String, cat: String): String {
        if (itemsEmpty) return "这个身份库里还没有记录。"
        if (typedQuery(query, domain).isNotEmpty()) return "没有匹配的记录。换个词，或者清空搜索看全部。"
        if (domainScoped(query, domain)) return "没有属于「$domain」的记录。清空搜索框（点框里的 ×）可以看全部 —— 但那时列表里的记录不是这个站的。"
        if (cat.isNotEmpty()) return "这个分类下没有可填充的记录。点「全部」看看别的。"
        return "没有带账号的记录 —— 只有含账号控件的记录才能用来填充。"
    }
}
