package com.geek.umbra.feature.autofill

import com.geek.umbra.feature.vault.Tree3
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 树形库的自动填充（方案 §五第 4 条）：树用向量 `autofill.input` 那一棵（与 PC / iOS 同一份）。 */
class AutofillTreeTest {
    private val tree: Tree3 = Tree3.from(Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("vault/vaultTreeVectors.json")!!.readText(),
    ).jsonObject["autofill"]!!.jsonObject["input"]!!.jsonObject)

    private fun web(d: String) = AutofillTarget(d, "com.android.chrome")
    private fun app(p: String) = AutofillTarget("", p)

    @Test fun nameMatchWhenUrlEmpty() {
        val c = AutofillRules.treeDirect(tree, web("gitlab.com"))
        assertEquals("网址空着的 GitLab 按名称命中 gitlab.com", listOf("cloud:glab"), c.map { it.recordIdentifier })
        assertEquals(listOf("cloud:glab"), AutofillRules.treeDirect(tree, app("com.gitlab.android")).map { it.recordIdentifier })
        assertTrue("有网址的不拿名称凑：Steam 的网址是 store.steampowered.com", AutofillRules.treeDirect(tree, web("steam.com")).isEmpty())
    }

    @Test fun directSuggestionsByUrl() {
        val gh = AutofillRules.treeDirect(tree, web("github.com"))
        assertEquals(listOf("gh"), gh.map { it.recordIdentifier })
        assertEquals("sam" to "GitHub · 个人 › 工作", gh[0].line1 to gh[0].line2)
        assertTrue("回收站里 / 自动填充关掉的不出", AutofillRules.treeDirect(tree, web("old.example")).isEmpty() && AutofillRules.treeDirect(tree, web("q.example")).isEmpty())
    }

    @Test fun pickerScoresAndFilters() {
        val all = AutofillRules.treeCandidates(tree, web("aliyun.com"), "")
        assertEquals("清空搜索框看全部，同站的排最前", "cloud:ali", all.first().recordIdentifier)
        assertEquals(7, all.size)
        val scoped = AutofillRules.treeCandidates(tree, web("aliyun.com"), "aliyun.com")
        assertEquals("还停在预填的域名上：只给这个站的", listOf("cloud:ali"), scoped.map { it.recordIdentifier })
        val typed = AutofillRules.treeCandidates(tree, web(""), "jira")
        assertEquals(listOf("deep"), typed.map { it.recordIdentifier })
        assertFalse("密码不参与搜索", AutofillRules.treeCandidates(tree, web(""), "pw-ali").any())
    }
}
