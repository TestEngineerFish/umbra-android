package com.geek.umbra.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记录详情里的「查找」（sam 2026-10-14）—— 逐条翻译 iOS `Tools/swifttests/VaultFindRulesTests.swift`。
 *
 * 最要紧的是安全不变量：**密文永不参与匹配**（pwd / seed / 遮盖正文 / 表格密文列），否则查找框就是猜密码的 oracle。
 * 另外钉住：该搜到的都搜得到、命中顺序 = 卡片顺序（先 y 后 x）、上一个 / 下一个循环、高亮范围、列表与详情同口径、计数格档位。
 */
class VaultFindRulesTest {
    /** 一条画布记录：账号、遮盖文本、明文文本、带密文列的表格、附件、图片。坐标故意打乱。 */
    private val recordJSON = """
{"id":"i1","typeId":"","title":"招商银行","tags":["bank"],"blocks":[],"attachments":[],
 "createdAt":1,"updatedAt":2,"revision":1,"fmt":2,
 "nodes":[
   {"id":"nFile","kind":"file","x":40,"y":600,"w":280,"h":100,"title":"附件","data":{"items":[{"attId":"a1","name":"合同扫描.pdf"}]},"set":{}},
   {"id":"nAcct","kind":"acct","x":40,"y":40,"w":280,"h":240,"title":"网银","data":{"user":"zhangsan","pwd":"Hunter2zz","seed":"JBSWY3DPEHPK3PXP","url":"https://cmbchina.com","note":"柜台办的 U 盾"},"set":{}},
   {"id":"nMask","kind":"text","x":360,"y":40,"w":280,"h":130,"title":"密语","data":{"body":"取款密码 885500"},"set":{"masked":true}},
   {"id":"nText","kind":"text","x":40,"y":300,"w":280,"h":130,"title":"说明","data":{"body":"U 盾放在书房抽屉，借记卡也在"},"set":{"masked":false}},
   {"id":"nTable","kind":"table","x":360,"y":300,"w":360,"h":150,"title":"卡片","data":{"cols":["项","卡号"],"rows":[["借记卡","6222885500"],["信用卡","4392000011"]]},"set":{"mask":[1]}},
   {"id":"nImg","kind":"img","x":40,"y":460,"w":280,"h":120,"title":"图片","data":{"items":[{"attId":"a2","name":"U盾正面.png"}]},"set":{}}
 ],"edges":[]}
"""

    private val it: VItem = VItem.parse(recordJSON)

    private fun hits(item: VItem, q: String) = VaultFindRules.hits(item.sortedNodes, q)

    /** 把某个节点按 [f] 改掉后写回记录（iOS 的 `setNodes`）。 */
    private fun VItem.editNode(id: String, f: (VNode) -> VNode) = withNodes(sortedNodes.map { n -> if (n.id == id) f(n) else n })

    private fun inList(item: VItem, q: String) = item.searchHaystack.joinToString(" ").lowercase().contains(VaultFindRules.norm(q))

    /** 安全不变量：密码 / 两步验证密钥 / 遮盖正文 / 表格密文列都不参与匹配；遮盖文本的标题照常能搜到。 */
    @Test fun secretsNeverMatch() {
        assertTrue("账号的密码不参与匹配：${hits(it, "Hunter2")}", hits(it, "Hunter2").isEmpty())
        assertTrue("两步验证密钥不参与匹配", hits(it, "JBSWY3DP").isEmpty())
        assertTrue("遮盖着的文本正文不参与匹配（标题「密语」才算）", hits(it, "取款密码").isEmpty())
        assertEquals("遮盖文本的**标题**照常能搜到", listOf("nMask"), hits(it, "密语"))
        assertTrue("表格密文列与遮盖正文都不参与匹配：${hits(it, "885500")}", hits(it, "885500").isEmpty())
        assertTrue("密文列的每一行都不参与匹配", hits(it, "4392").isEmpty())
        val all = it.sortedNodes.flatMap { n -> n.searchParts() }.joinToString("\n")
        assertTrue("searchParts 里不含任何密文",
            !all.contains("Hunter2zz") && !all.contains("JBSWY3DP") && !all.contains("885500") && !all.contains("4392"))
        // 遮盖一翻开，正文就该能搜到 —— 证明上面那条挡的是遮盖，而不是这个节点根本没进来。
        val open = it.editNode("nMask") { n -> n.copy(set = n.set + ("masked" to jb(false))) }
        assertEquals("取消遮盖后正文能搜到", listOf("nMask"), hits(open, "取款密码"))
    }

    /** 该搜到的都搜得到：备注 / 用户名（大小写不敏感）/ 网址 / 附件名 / 图片名 / 非密文列 / 表头；@ 引用按现名。 */
    @Test fun everythingPlainIsReachable() {
        assertEquals("账号备注能搜到", listOf("nAcct"), hits(it, "柜台"))
        assertEquals("用户名能搜到，且大小写不敏感", listOf("nAcct"), hits(it, "ZhangSan"))
        assertEquals("网址能搜到", listOf("nAcct"), hits(it, "cmbchina"))
        assertEquals("附件名能搜到", listOf("nFile"), hits(it, "合同扫描"))
        assertEquals("图片名能搜到", listOf("nImg"), hits(it, "正面.png"))
        assertEquals("表格非密文列能搜到", listOf("nTable"), hits(it, "信用卡"))
        assertEquals("表格表头能搜到（哪怕它是密文列的表头）", listOf("nTable"), hits(it, "卡号"))
        assertEquals("查找词去首尾空白（同列表过滤）", listOf("nText"), hits(it, "   书房  "))
        assertTrue("空词 / 全空白不算在查找，一张卡都不命中", hits(it, "").isEmpty() && hits(it, "   ").isEmpty())
        assertTrue("只命中记录标题时按节点数是 0", hits(it, "招商银行").isEmpty())
        val cited = it.editNode("nText") { n -> n.copy(data = n.data + ("body" to js("见 @[旧名](i_x/n_y)"))) }
        assertEquals("@ 引用按现名命中", listOf("nText"), VaultFindRules.hits(cited.sortedNodes, "新名") { _, _ -> "新名" })
        assertEquals("不给现名查询时按备份名命中", listOf("nText"), VaultFindRules.hits(cited.sortedNodes, "旧名"))
        assertTrue("引用标记里的节点 id 不参与匹配", VaultFindRules.hits(cited.sortedNodes, "n_y").isEmpty())
    }

    /** 命中顺序 = 卡片顺序（先 y 后 x），不按数组顺序。 */
    @Test fun orderFollowsCards() {
        assertEquals("命中按卡片顺序（先 y 后 x），不按数组顺序", listOf("nAcct", "nText"), hits(it, "U 盾"))
        assertEquals("同一行 x 小的在前", listOf("nText", "nTable"), hits(it, "借记卡"))
        val order = it.sortedNodes.map { n -> n.id }
        val many = hits(it, "a")
        assertEquals("多个命中时与 sortedNodes 的相对顺序一致：$many", order.filter { id -> id in many }, many)
    }

    /** 上一个 / 下一个循环、命中变化后的下标、「2 / 5」计数。 */
    @Test fun stepping() {
        assertEquals("0 → 1", 1, VaultFindRules.step(0, 3, 1))
        assertEquals("到头再往下回到第一个", 0, VaultFindRules.step(2, 3, 1))
        assertEquals("第一个再往上回到最后一个", 2, VaultFindRules.step(0, 3, -1))
        assertTrue("只有一个时原地不动", VaultFindRules.step(1, 1, 1) == 0 && VaultFindRules.step(0, 1, -1) == 0)
        assertEquals("没有命中回 -1", -1, VaultFindRules.step(0, 0, 1))
        assertTrue("还没选中时往下到第一个、往上到最后一个", VaultFindRules.step(-1, 4, 1) == 0 && VaultFindRules.step(-1, 4, -1) == 3)
        assertEquals("下标越界（命中刚变少）按没选中处理", 0, VaultFindRules.step(7, 4, 1))
        assertEquals("命中列表变了，原来那张还在就跟着它", 1, VaultFindRules.reindex("b", listOf("a", "b", "c")))
        assertEquals("原来那张不在了回到第一个", 0, VaultFindRules.reindex("z", listOf("a", "b")))
        assertEquals("一个命中都没有回 -1", -1, VaultFindRules.reindex("a", emptyList()))
        assertEquals("计数从 1 起：「2 / 5」", "2/5", VaultFindRules.counter(1, 5)?.let { c -> "${c.first}/${c.second}" })
        assertEquals("没有命中不出「0 / 0」", null, VaultFindRules.counter(-1, 0))
    }

    /** 高亮范围：大小写不敏感、不重叠、先去首尾空白、前面有 emoji 不切歪。 */
    @Test fun highlightRanges() {
        val s = "Abc abc ABC"
        assertEquals("大小写不敏感、全部找出", listOf("Abc", "abc", "ABC"), VaultFindRules.ranges(s, "abc").map { r -> s.substring(r) })
        assertEquals("不重叠：aaaa 里的 aa 是两处，不是三处", 2, VaultFindRules.ranges("aaaa", "aa").size)
        assertEquals("查找词先去首尾空白再找", 1, VaultFindRules.ranges("柜台办的 U 盾", " U 盾 ").size)
        assertTrue("空词 / 空文本没有范围", VaultFindRules.ranges("abc", "").isEmpty() && VaultFindRules.ranges("", "a").isEmpty())
        val e = "🔑钥匙🔑钥匙"
        assertEquals("前面有 emoji 也不切歪", listOf("钥匙", "钥匙"), VaultFindRules.ranges(e, "钥匙").map { r -> e.substring(r) })
    }

    /** 列表（searchHaystack）与详情（按节点）同一口径：详情能命中的列表一定有；密文两边都不中。 */
    @Test fun sameAsList() {
        for (q in listOf("柜台", "合同扫描", "借记卡", "zhangsan", "书房", "密语")) {
            assertTrue("「$q」列表与详情同时命中", inList(it, q) && hits(it, q).isNotEmpty())
        }
        for (q in listOf("Hunter2", "885500", "JBSWY3DP")) {
            assertTrue("密文「$q」列表与详情都不命中", !inList(it, q) && hits(it, q).isEmpty())
        }
    }

    /** 计数格的档位（设计侧 243 §三③）：节点 → 记录名 → 标签 → 没有找到。 */
    @Test fun outcomeTiers() {
        fun oc(q: String) = VaultFindRules.outcome(hits(it, q).size, it.title, it.tags, q)
        assertTrue("空词 / 全空白：不在查找，计数格不画", oc("") == VaultFindOutcome.Idle && oc("   ") == VaultFindOutcome.Idle)
        assertEquals("节点里有命中：报 n / m", VaultFindOutcome.Nodes, oc("柜台"))
        assertTrue("夹具前提：「招商」不在任何节点里", hits(it, "招商").isEmpty())
        assertEquals("只是记录名含这个词：「只在记录名里」", VaultFindOutcome.OnlyName, oc("招商"))
        assertEquals("记录名那一档也先去首尾空白", VaultFindOutcome.OnlyName, oc(" 招商 "))
        assertEquals("只在标签里，大小写不敏感", VaultFindOutcome.OnlyTags, oc("BANK"))
        assertEquals("哪儿都没有才是「没有找到」", VaultFindOutcome.None, oc("不存在的词"))
        assertEquals("密码不参与：哪一档都不因为它成立", VaultFindOutcome.None, oc("Hunter2"))
        assertEquals("节点与记录名都含：按节点报 n / m", VaultFindOutcome.Nodes, VaultFindRules.outcome(2, "网银", null, "网银"))
        assertEquals("记录名与标签都含：报记录名那一档", VaultFindOutcome.OnlyName, VaultFindRules.outcome(0, "网银", listOf("网银"), "网银"))
        assertEquals("没有记录名、没有标签：没有找到", VaultFindOutcome.None, VaultFindRules.outcome(0, "", null, "x"))
        for (q in listOf("招商", "bank", "柜台", "密语")) {
            assertTrue("「$q」列表搜得到", inList(it, q))
            assertNotEquals("「$q」列表搜得到，详情里就不报「没有找到」", VaultFindOutcome.None, oc(q))
        }
    }
}
