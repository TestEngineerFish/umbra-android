package com.geek.umbra.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工作流密钥那一类（类别 `data.tags` 里带 `workflow-secret`，PC 的 getSecret / putSecret 靠它和 `secretAcct` 定位）：
 * Android 不读，但同步、合并、落盘、改名时都要**原样保留** —— 丢了 tag，PC 再存密钥就会另建一个类别；丢了 secretAcct，
 * 旧的 vault:// 引用就会落到别的账号上。
 */
class VaultWorkflowSecretKeepTest {
    private val catData = mapOf(
        "icon" to js("flow"), "iconImg" to jb(false), "sub" to js(""),
        "tags" to ja(listOf(js("workflow-secret"))), "secretAcct" to js("k1"), "pcOnly" to js("留着"),
    )
    private val cat = Node3("wf", "cat", "工作流密钥", null, "0V", data = catData, rev = 2.0, updatedAt = 10.0, extra = mapOf("future" to js("x")))
    private val key = Node3("k1", "acct", "OpenAI", "wf", "0V", data = mapOf("pwd" to js("sk-1")), rev = 1.0)
    private val tree = Tree3(listOf(cat, key))

    @Test fun survivesSnapshotRoundTrip() {
        val back = VSnapshot.parse(VSnapshot(3, emptyList(), mapOf("v" to VData(emptyList(), emptyList(), emptyMap(), 3, tree))).encode())!!
        assertEquals(cat, back.data["v"]!!.tree!!.node("wf"))
    }

    @Test fun survivesMergeBothWays() {
        // 另一边改了别的节点（rev 更高），这个类别两边一样：合并后逐字不变。
        val other = Tree3(listOf(cat, key.copy(title = "OpenAI 改名", rev = 2.0)))
        for ((a, b) in listOf(tree to other, other to tree)) assertEquals(cat, VaultMerge3.merge(a, b, "").node("wf"))
        // 云端那份 rev 更高：赢的那份的 data 原样带回，不和本机的拼。
        val cloud = cat.copy(title = "工作流密钥（PC 改过）", rev = 3.0)
        assertEquals(cloud, VaultMerge3.merge(tree, Tree3(listOf(cloud, key)), "").node("wf"))
    }

    @Test fun survivesTrashAndRestoreAndRename() {
        val (t1, _) = VaultTrash3.trashNode(tree, "wf", 20.0)
        val (t2, _) = VaultTrash3.restore(t1, "wf", 30.0)
        assertEquals(catData, t2.node("wf")!!.data)
        // 手机上改名（编辑页只动 title / sub）：tags、secretAcct、不认识的键都还在。
        val renamed = VaultTreeUi.asVNode(cat).let { cat.copy(title = "密钥", data = it.data, set = it.set) }
        assertEquals(catData, renamed.data)
        assertEquals(cat.extra, renamed.extra)
    }
}
