package com.geek.umbra.feature.autofill

import com.geek.umbra.feature.vault.VItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动填充列表的判据（照 iOS CredentialProviderViewController.swift 的 matched / candidates / rowTitle），
 * 外加 Android 才有的「按包名匹配」。最要紧的是同站判断**按标签边界、不按子串**：子串是钓鱼的口子。
 */
class AutofillRulesTest {
    private fun canvas(id: String, title: String, vararg accts: Triple<String, String, String>, fav: Boolean = false): VItem {
        val nodes = accts.mapIndexed { i, (nid, user, url) ->
            """{"id":"$nid","kind":"acct","x":40,"y":${40 + i * 300},"w":280,"h":240,"title":"账号","data":{"user":"$user","pwd":"pw-$user","seed":"","url":"$url","note":"work"},"set":{}}"""
        }.joinToString(",")
        return VItem.parse("""{"id":"$id","typeId":"","title":"$title","favorite":$fav,"blocks":[],"attachments":[],"createdAt":0,"updatedAt":0,"revision":1,"fmt":2,"nodes":[$nodes],"edges":[]}""")
    }

    private val items = listOf(
        canvas("i1", "GitHub", Triple("n1", "alice", "https://github.com"), Triple("n2", "bob", "https://gitlab.com")),
        canvas("i2", "Example", Triple("n3", "carol", "https://accounts.example.com/login"), fav = true),
        canvas("i3", "Phish", Triple("n4", "eve", "https://ple.com")),
        VItem.parse("""{"id":"i4","typeId":"t","title":"Note","blocks":[],"attachments":[],"createdAt":0,"updatedAt":0,"revision":1}"""),
    )

    /** 网页：按标签边界判同站；子域算、`ple.com` 不算 `example.com`。 */
    @Test fun webSameSite() {
        val t = AutofillTarget("example.com", "com.android.chrome")
        val d = AutofillRules.directSuggestions(items, t)
        assertEquals(listOf("carol"), d.map { it.account.user })
        assertTrue(AutofillRules.sameTarget(t, "https://www.example.com"))
        assertFalse(AutofillRules.sameTarget(t, "https://ple.com"))
    }

    /** App：拿记录网址的主体那一节比包名的整段（`github` ∈ com.github.android），不按子串。 */
    @Test fun packageMatch() {
        assertTrue(AutofillRules.packageMatches("com.github.android", "github.com"))
        assertFalse(AutofillRules.packageMatches("com.github.android", "hub.com"))
        assertTrue(AutofillRules.packageMatches("com.taobao.taobao", "login.taobao.com"))
        assertTrue(AutofillRules.packageMatches("com.sina.weibo", "weibo.com.cn"))
        assertFalse(AutofillRules.packageMatches("com.x.y", "localhost"))
        val d = AutofillRules.directSuggestions(items, AutofillTarget("", "com.github.android"))
        assertEquals(listOf("alice"), d.map { it.account.user })
    }

    /** 预填域名时只看这个站；清空搜索框就全给，同站的排最前；自己打的字是子串搜索。 */
    @Test fun matchedAndCandidates() {
        val t = AutofillTarget("github.com", "com.android.chrome")
        assertEquals(listOf("alice"), AutofillRules.candidates(items, t, "github.com", "").map { it.account.user })
        val all = AutofillRules.candidates(items, t, "", "")
        assertEquals("alice", all.first().account.user)
        assertEquals(4, all.size)   // 没账号的那条不进
        // 打了字之后同站那行仍有 +2、照样留着并排在最前（iOS 同：筛的是「分数 > 0」）。
        assertEquals(listOf("alice", "bob"), AutofillRules.candidates(items, t, "gitlab", "").map { it.account.user })
        assertEquals(listOf("carol"), AutofillRules.candidates(items, t, "", "fav").map { it.account.user })
        assertTrue(AutofillRules.domainScoped(" GitHub.com ", "github.com"))
        assertEquals("", AutofillRules.typedQuery("GITHUB.COM", "github.com"))
    }

    /** 行标题「记录 · 节点」；节点标题空或同名只写记录名。空态 / 小标题分档。 */
    @Test fun titlesAndCopy() {
        val r = AutofillRules.candidates(items, AutofillTarget("", ""), "", "").first { it.account.user == "alice" }
        assertEquals("GitHub · 账号", AutofillRules.rowTitle(r))
        assertEquals("「github.com」的匹配项", AutofillRules.listCaption("github.com", "github.com", "", null))
        assertEquals("搜索结果", AutofillRules.listCaption("x", "github.com", "", null))
        assertEquals("全部可填充的记录", AutofillRules.listCaption("", "github.com", "", null))
        assertTrue(AutofillRules.emptyText(false, "github.com", "github.com", "").startsWith("没有属于「github.com」的记录"))
        assertEquals("这个身份库里还没有记录。", AutofillRules.emptyText(true, "", "", ""))
    }
}
