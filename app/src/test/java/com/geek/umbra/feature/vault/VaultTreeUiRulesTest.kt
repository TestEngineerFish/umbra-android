package com.geek.umbra.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 树形界面的纯判据：行的第二行、删除确认字面（293 §三 / §八）、搜索不碰密码、新建 id 的拆装。 */
class VaultTreeUiRulesTest {
    private val multi = Node3("m", "multi", "云服务", "c", "1V", rev = 1.0).withEntries(listOf(
        Entry3("e1", "阿里云", user = "sam@ali", pwd = "secret-ali"), Entry3("e2", "腾讯云", user = "1000"), Entry3("e3", "AWS"), Entry3("e4", "Vercel", url = "https://vercel.com"),
        Entry3("e5", "旧的", del = Del3(1.0, "m:e5")),
    ))
    private val t = Tree3(listOf(
        Node3("c", "cat", "工作", null, "0V", data = mapOf("sub" to js(""))),
        Node3("k", "acct", "GitHub", "c", "0V", data = mapOf("user" to js("sam-dev"), "pwd" to js("gh-pass"))),
        multi,
        Node3("x", "text", "VPN 说明", "c", "2V", data = mapOf("body" to js("\n公司 VPN 用 WireGuard\n第二行"))),
        Node3("s", "text", "PIN", "c", "3V", data = mapOf("body" to js("0000")), set = mapOf("masked" to jb(true))),
        Node3("tb", "table", "服务器", "c", "4V", data = mapOf("cols" to ja(listOf(js("主机"), js("IP"))), "rows" to ja(listOf(ja(emptyList()), ja(emptyList()), ja(emptyList()))))),
        Node3("im", "img", "身份证", "c", "5V", data = mapOf("items" to ja(listOf(jo(mapOf("attId" to js("a"))), jo(mapOf("attId" to js("b")))))), set = mapOf("blur" to jb(true))),
        Node3("r", "text", "恢复码", "k", "0V", data = mapOf("body" to js("abc"))),
    ))

    @Test fun rowSub() {
        assertEquals("类别副标题空时写节点数", "6 个节点", VaultTreeUi.sub(t.node("c")!!, t, 6))
        assertEquals("sam-dev", VaultTreeUi.sub(t.node("k")!!, t, 1))
        assertEquals("回收站里的条目不算", "4 个账号 · 阿里云、腾讯云、AWS…", VaultTreeUi.sub(multi, t, 0))
        assertEquals("公司 VPN 用 WireGuard", VaultTreeUi.sub(t.node("x")!!, t, 0))
        assertEquals("已遮盖", VaultTreeUi.sub(t.node("s")!!, t, 0))
        assertEquals("2 列 × 3 行 · 主机、IP", VaultTreeUi.sub(t.node("tb")!!, t, 0))
        assertEquals("2 张 · 已模糊", VaultTreeUi.sub(t.node("im")!!, t, 0))
    }

    @Test fun deleteCopy() {
        val (title, body) = VaultTreeUi.deleteCopy(t.node("c")!!, VaultTrash3.deleteImpact(t, "c"))
        assertEquals("删除「工作」？", title)
        assertEquals("删除后会和它下面的 7 个节点一起移入回收站，保留 30 天。恢复时整组一起回来。其中 5 个账号会同时从自动填充里撤下。", body)
        assertEquals("删除后会移入回收站，保留 30 天。", VaultTreeUi.deleteCopy(t.node("s")!!, VaultTrash3.deleteImpact(t, "s")).second)
        assertEquals("删除后会和它下面的 1 个节点一起移入回收站，保留 30 天。恢复时整组一起回来。其中 1 个账号会同时从自动填充里撤下。",
            VaultTreeUi.deleteCopy(t.node("k")!!, VaultTrash3.deleteImpact(t, "k")).second)
        assertEquals("删除账号「阿里云」？" to "删除后会移入回收站，保留 30 天。它会同时从自动填充里撤下。", VaultTreeUi.deleteEntryCopy(multi.entries[0]))
    }

    /** 搜索框是一个能无限次提问的口子：密码永远搜不到；条目结果写「多账号里的账号 · 账户」，路径写到节点为止。 */
    @Test fun searchNeverHitsSecrets() {
        assertTrue(VaultTreeUi.search(t, "secret-ali").isEmpty())
        assertTrue(VaultTreeUi.search(t, "gh-pass").isEmpty())
        assertTrue("遮盖的正文不进", VaultTreeUi.search(t, "0000").isEmpty())
        val h = VaultTreeUi.search(t, "ali").single()
        assertEquals("e1", h.entryId)
        assertEquals("多账号里的账号 · sam@ali", h.sub)
        assertEquals(listOf("工作", "云服务"), h.path)
        assertTrue("回收站里的条目搜不到", VaultTreeUi.search(t, "旧的").isEmpty())
    }

    @Test fun ids() {
        assertEquals("m" to "e1", VaultTreeUi.splitId("m:e1"))
        assertEquals("m" to null, VaultTreeUi.splitId("m"))
        assertEquals(VaultTreeUi.NewSpec("acct", "c"), VaultTreeUi.parseNew(VaultTreeUi.newId("acct", "c")))
        assertEquals(VaultTreeUi.NewSpec("cat", null), VaultTreeUi.parseNew(VaultTreeUi.newId("cat", null)))
        assertNull(VaultTreeUi.parseNew("m:e1"))
        assertEquals("选中的是类别 → 加到它下面", "c", VaultTreeUi.addTarget(t.node("c"), true, null))
        assertEquals("选中的普通节点没有子节点 → 加成兄弟", "c", VaultTreeUi.addTarget(t.node("x"), false, "c"))
    }

    @Test fun foots() {
        assertEquals("这 4 个账号都能被搜到，也会出现在 iPhone 和 Android 的密码自动填充里：先按网址匹配，没填网址的按名称匹配。", VaultTreeUi.multiFoot(multi))
        assertEquals("参与自动填充 · 匹配 vercel.com。网址留空时按名称「Vercel」匹配。", VaultTreeUi.entryFoot(multi, multi.entries[3]))
        val off = multi.copy(set = mapOf("autofill" to jb(false)))
        assertEquals("自动填充已关：这些账号在保险箱里仍能搜到，但 iPhone 和 Android 填密码时查不到。", VaultTreeUi.multiFoot(off))
    }

    /** @ 引用：节点 id 迁移时沿用，按节点 id 在整棵树里解析现名；回收站里的 / 找不到的回 null（显示备份名并标缺失）。 */
    @Test fun mentions() {
        val r = VaultTreeUi.mentionResolver(t)
        assertEquals("GitHub", r("old-record-id", "k"))
        assertNull(r("c", "ghost"))
        val t2 = VaultTrash3.trashNode(t, "k", 1.0).first
        assertNull(VaultTreeUi.mentionResolver(t2)("c", "k"))
        assertEquals("见 @GitHub", VaultMentionRules.plainText("见 @[旧名](c/k)", r))
        // fmt 3 的单段写法 @[名](节点id)：同样按节点 id 解析；旧两段写法照认（迁移改写不过去的原样留着）。
        assertEquals("见 @GitHub 和 @GitHub", VaultMentionRules.plainText("见 @[旧名](k) 和 @[旧名](c/k)", r))
        assertEquals("找不到的退回备份名", "@已删", VaultMentionRules.plainText("@[已删](ghost)", r))
        assertEquals(listOf(VaultMentionRules.Seg.Mention("", "k", "名")), VaultMentionRules.parse("@[名](k)"))
    }

    /** 「默认模糊」是隐私设置：开了的图片节点格子里不露图（293 §九之二）；没开 / 不是图片节点照常。 */
    @Test fun imageBlur() {
        assertTrue(VaultNodeKind.blurred(VaultTreeUi.asVNode(t.node("im")!!)))
        assertFalse(VaultNodeKind.blurred(VaultTreeUi.asVNode(t.node("im")!!.copy(set = emptyMap()))))
        assertFalse("迁移来的旧图片节点默认不模糊", VaultNodeKind.blurred(VNode("x", "img", set = VaultNodeDefaults.set("img"))))
        assertFalse(VaultNodeKind.blurred(VaultTreeUi.asVNode(t.node("x")!!)))
    }
}
