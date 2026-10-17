// VaultModels.kt 画布版（v2）那一半的**行为**用例：逐条照抄 iOS Tools/swifttests/VaultModelsTests.swift（方案 §二 / §四 / §八，2026-10-12）。
//
// 最要紧的一条是 §四：**不认识的键一个都不许丢**。扫文本看得出「有没有一个 `extra` 字段」，
// 看不出「一条带 nodes / edges / 不认识的键的 JSON 过一遍 VItem 之后还剩什么」—— 丢键的后果是
// 手机上点一下收藏，电脑上那张画布就没了（revision 大者胜，PC 会乖乖收下空记录）。
package com.geek.umbra.feature.vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

/** 一条断言（iOS 的 `expect(cond, what)`）。 */
private fun expectM(cond: Boolean, what: String) = assertTrue(what, cond)

/**
 * 把一段 JSON 规整成 JsonElement 再比：JsonObject 的相等与键序无关；数字一律按数值规整
 * （`1700000000000` 与 `1.7E12`、`64` 与 `64.0` 算同一个）—— 要比的是**内容一个字节都没少**，不是两家序列化器的排版习惯。
 */
private fun canonical(text: String): JsonElement = normNumbers(Json.parseToJsonElement(text))

private fun normNumbers(e: JsonElement): JsonElement = when (e) {
    is JsonObject -> JsonObject(e.mapValues { normNumbers(it.value) })
    is JsonArray -> JsonArray(e.map { normNumbers(it) })
    is JsonNull -> e
    is JsonPrimitive -> if (e.isString || e.booleanOrNull != null) e else JsonPrimitive(BigDecimal(e.content).stripTrailingZeros())
}

/**
 * 一条 PC 画布版写出来的记录：两个节点、一条关联线、`fmt` / `iconImg` / `order`，外加两个今天谁都不认识的键
 * （一个在记录上、一个在节点上）—— 将来 PC 再加键就是这个形状。
 */
private val canvasJSON = """
{"id":"i1","typeId":"","title":"GitHub","icon":"🐙","iconImg":false,"favorite":true,"tags":["dev"],
 "blocks":[],"attachments":[{"id":"a1","name":"logo.png","mime":"image/png","size":1234,"addedAt":1,"w":64,"h":64,"fileId":"f-9"}],
 "createdAt":1700000000000,"updatedAt":1700000001000,"revision":3,"fmt":2,"order":1.5,
 "nodes":[
   {"id":"n2","kind":"text","x":360,"y":40,"w":280,"h":130,"title":"备注","data":{"body":"secret body"},"set":{"masked":true,"fmt":"md"},"zzFuture":{"a":[1,2,{"b":null}]}},
   {"id":"n1","kind":"acct","x":40,"y":40,"w":280,"h":240,"title":"账号","data":{"user":"alice","pwd":"p@ss","seed":"JBSWY3DPEHPK3PXP","url":"https://github.com","note":"work"},"set":{"note":true,"strength":true}},
   {"id":"n3","kind":"table","x":40,"y":300,"w":360,"h":150,"title":"卡","data":{"cols":["项","内容"],"rows":[["卡号","6222"],["有效期","12/30"]]},"set":{"mask":[1]}}
 ],
 "edges":[{"id":"e1","a":"n1","b":"n2","label":"用于"}],
 "zzUnknownOnItem":{"x":"y","n":6.2e19}}
""".trimIndent()

/** 旧记录（没有 `fmt`）：控件照旧。 */
private val legacyJSON = """
{"id":"i0","typeId":"t1","title":"Old","blocks":[{"id":"b1","type":"account","label":"主","data":{"username":"bob","password":"pw-bob","url":"https://x.com","otp":true}},
 {"id":"b2","type":"account","data":{"username":"carol","password":"pw-carol"}}],
 "attachments":[],"createdAt":1,"updatedAt":2,"revision":1}
""".trimIndent()

/** iOS `try? JSONDecoder().decode(VItem.self, ...)`：解不开就是 null。 */
private fun parseItem(text: String): VItem? = runCatching { VItem.parse(text) }.getOrNull()

/** 保险箱画布模型的行为用例：未知键往返不丢、节点视图与写回、AutoFill 取账号、敏感值不进搜索、彻底删除、快照 v1/v2。 */
class VaultModelsTest {

    /** §四 未知键往返不丢：画布版记录过一遍 VItem，规整后一个键都不少；改收藏再编，节点 / 关联线 / 未知键原样。 */
    @Test fun roundTripCases() {
        val it = parseItem(canvasJSON) ?: return fail("画布版记录解不开")
        expectM(it.isCanvas, "fmt == 2 应当认成画布版")
        expectM(it.extra.keys.sorted() == listOf("edges", "fmt", "iconImg", "nodes", "order", "zzUnknownOnItem"),
            "不认识的键应当全进 extra：${it.extra.keys.sorted()}")
        expectM(it.title == "GitHub" && it.favorite == true && it.tags == listOf("dev") && it.icon == "🐙" && it.revision == 3.0,
            "已知键照旧解")
        expectM(it.attachments.firstOrNull()?.fileId == "f-9" && it.attachments.firstOrNull()?.w == 64.0, "附件的 fileId / w / h 要解出来")
        val out = it.toJson().toString()
        expectM(canonical(out) == canonical(canvasJSON),
            "往返一遍字节级有出入（规整后比）：\n  原 ${canonical(canvasJSON)}\n  回 ${canonical(out)}")
        // 改一个已知字段再编：未知键还在、节点一个不少 —— 这正是「手机上点个收藏」那一下。
        val fav = it.copy(favorite = false, revision = it.revision + 1)
        val back = parseItem(fav.toJson().toString())
        if (back != null) {
            expectM(back.nodes.size == 3 && back.edges.size == 1 && back.extra["zzUnknownOnItem"] == it.extra["zzUnknownOnItem"],
                "改收藏再编，节点 / 关联线 / 未知键应当原样")
            expectM(back.favorite == false && back.revision == 4.0, "改过的已知字段应当是新的")
        } else expectM(false, "改收藏再编解不回来")
        // 旧记录：没有 fmt，extra 为空，编出来不多一个键。
        val old = parseItem(legacyJSON)
        if (old != null) {
            val out3 = old.toJson().toString()
            expectM(!old.isCanvas && old.extra.isEmpty() && old.nodes.isEmpty(), "旧记录不是画布、extra 为空、nodes 为空")
            expectM(canonical(out3) == canonical(legacyJSON), "旧记录往返应当一字不差")
        } else expectM(false, "旧记录解不开 / 编不出")
        // 必填键缺了仍然解不开（两端约定：PC v2 写空值，不是不写）。
        val noBlocks = """{"id":"x","typeId":"","title":"t","attachments":[],"createdAt":0,"updatedAt":0,"revision":0,"fmt":2}"""
        expectM(parseItem(noBlocks) == null, "缺 blocks 应当解不开（必填键，PC 写 []）")
    }

    /** 节点视图 / 写回：按 y 再 x 排；set 开关读得出；withNodes 只换 nodes 一个键；解析失败按空；默认账号节点的形状。 */
    @Test fun nodeCases() {
        var it = parseItem(canvasJSON) ?: return fail("解不开")
        expectM(it.sortedNodes.map { n -> n.id } == listOf("n1", "n2", "n3"), "按 y 再 x 排：${it.sortedNodes.map { n -> n.id }}")
        val text = it.nodes.firstOrNull { n -> n.id == "n2" }
        expectM(text?.flag("masked", false) == true && text.set["fmt"].vString == "md", "text 节点的 set 要读得出来")
        expectM(text?.extra?.get("zzFuture") != null, "节点上不认识的键应当留在 node.extra")
        val acct = it.nodes.firstOrNull { n -> n.id == "n1" }
        expectM(acct?.flag("user", true) == true && acct.flag("note", false), "set 里缺的键按默认值、写了的按写的")
        // 写回：改一个格子，别的节点 / 节点上的未知键 / edges / fmt 原样。
        val list = it.sortedNodes.toMutableList()
        list[0] = list[0].copy(data = list[0].data + ("user" to js("alice2")))
        it = it.withNodes(list)
        expectM(it.nodes.firstOrNull { n -> n.id == "n1" }?.data?.get("user").vString == "alice2", "setNodes 要写回 extra")
        expectM(it.nodes.firstOrNull { n -> n.id == "n2" }?.extra?.get("zzFuture") != null, "写回后节点上的未知键还在")
        expectM(it.edges.firstOrNull()?.label == "用于" && it.isCanvas && it.extra["order"].vNumber == 1.5, "写回只换 nodes 这一个键")
        val back = parseItem(it.toJson().toString())
        if (back != null) {
            expectM(back.nodes.firstOrNull { n -> n.id == "n1" }?.data?.get("user").vString == "alice2"
                && back.nodes.firstOrNull { n -> n.id == "n2" }?.extra?.get("zzFuture") == text?.extra?.get("zzFuture"),
                "写回再往返一遍：改的在、没改的也在")
        } else expectM(false, "写回后编不出 / 解不回")
        // 解析失败按空：nodes 不是数组、或项缺 id / kind。
        var bad = it.copy(extra = it.extra + ("nodes" to js("oops")))
        expectM(bad.nodes.isEmpty(), "nodes 不是数组应当按空")
        bad = bad.copy(extra = bad.extra + ("nodes" to ja(listOf(
            jo(mapOf("kind" to js("text"))),
            jo(mapOf("id" to js("k"), "kind" to js("file"))),
        ))))
        expectM(bad.nodes.map { n -> n.id } == listOf("k"), "缺 id 的那一项丢掉、其余照留：${bad.nodes.map { n -> n.id }}")
        // 新建记录的默认账号节点（任务书：(40,40)、280×240、233 §三 的键）。
        val d = VNode.defaultAcct("n0")
        expectM(d.kind == "acct" && d.x == 40.0 && d.y == 40.0 && d.w == 280.0 && d.h == 240.0, "默认账号节点的位置 / 尺寸")
        expectM(d.data.keys == setOf("user", "pwd", "seed", "url", "note"), "默认账号节点 data 五个键")
        expectM(d.flag("pwdMasked", false) && !d.flag("note", true) && !d.flag("strength", true),
            "默认开关照 233：pwdMasked 真、note 假、strength 假")
        // 从字面 JSON 直接解一个节点（走 json 形状）。
        val n = runCatching {
            VNode.from(Json.parseToJsonElement("""{"id":"q","kind":"img","data":{"items":[{"attId":"a1","name":"x.png"}]}}"""))
        }.getOrNull()
        expectM(n?.kind == "img" && n.w == 0.0 && n.data["items"].vArray?.size == 1, "节点缺 x/y/w/h 按 0")
    }

    /** AutoFill 取账号：画布版取 acct 节点（user / pwd / seed / url），旧记录取 account 块；画布版不看 blocks。 */
    @Test fun accountCases() {
        val canvas = parseItem(canvasJSON)
        val old = parseItem(legacyJSON)
        if (canvas == null || old == null) { expectM(false, "解不开"); return }
        val a = canvas.accounts
        expectM(a.size == 1 && a[0].id == "n1" && a[0].user == "alice" && a[0].pwd == "p@ss" && a[0].seed == "JBSWY3DPEHPK3PXP"
            && a[0].url == "https://github.com" && a[0].hasOtp,
            "画布版应当从 acct 节点取 user / pwd / seed / url：$a")
        expectM(canvas.accountBlocks.isEmpty(), "画布版 blocks 为空、accountBlocks 自然为空")
        expectM(canvas.account("alice")?.id == "n1" && canvas.account("nobody") == null && canvas.account("")?.id == "n1",
            "画布版 account(forUser:) 的三档")
        val b = old.accounts
        expectM(b.map { it.id } == listOf("b1", "b2") && b[0].user == "bob" && b[0].pwd == "pw-bob" && b[0].otpFlag && b[0].hasOtp
            && b[1].user == "carol" && !b[1].hasOtp,
            "旧记录照旧从 account 块取：$b")
        expectM(old.account("carol")?.id == "b2", "旧记录按用户名取块")
        // fmt == 2 时以节点为准：就算 blocks 里残留一个 account 块也不取。
        val mixed = canvas.copy(blocks = listOf(VBlock("b9", "account", null, mapOf("username" to js("ghost")))))
        expectM(mixed.accounts.map { it.id } == listOf("n1"), "画布版不看 blocks：${mixed.accounts.map { it.id }}")
    }

    /** 敏感值不进搜索：密码 / 两步验证密钥 / 遮罩正文 / 表格密文列不进；@ 引用去掉标记再进。 */
    @Test fun searchCases() {
        val it = parseItem(canvasJSON)
        val old = parseItem(legacyJSON)
        if (it == null || old == null) { expectM(false, "解不开"); return }
        val hay = it.searchHaystack.joinToString("\n")
        expectM(hay.contains("GitHub") && hay.contains("dev") && hay.contains("alice") && hay.contains("github.com") && hay.contains("work"),
            "标题 / 标签 / 用户名 / 网址 / 备注进搜索")
        expectM(!hay.contains("p@ss") && !hay.contains("JBSWY3DP"), "密码 / 两步验证密钥不进搜索")
        expectM(!hay.contains("secret body"), "set.masked 为真的正文不进搜索")
        expectM(hay.contains("卡号") && hay.contains("有效期") && hay.contains("项") && !hay.contains("6222") && !hay.contains("12/30"),
            "表格：普通列进、set.mask 那一列不进")
        val list = it.sortedNodes.toMutableList()
        val i = list.indexOfFirst { n -> n.id == "n2" }
        if (i >= 0) list[i] = list[i].copy(set = list[i].set + ("masked" to jb(false)))
        val open = it.withNodes(list)
        expectM(open.searchHaystack.joinToString("\n").contains("secret body"), "明文正文进搜索")
        // @ 引用：标记换成「@备份名」再进搜索 —— 搜名字能中，记录 / 节点 id 不进。
        val nodes = open.sortedNodes.toMutableList()
        val j = nodes.indexOfFirst { n -> n.id == "n2" }
        if (j >= 0) nodes[j] = nodes[j].copy(data = nodes[j].data + ("body" to js("登录见 @[网银](i_zz9/n_qq7)")))
        val cited = open.withNodes(nodes)
        val citedHay = cited.searchHaystack.joinToString("\n")
        expectM(citedHay.contains("登录见 @网银") && !citedHay.contains("i_zz9") && !citedHay.contains("n_qq7"),
            "正文里的 @ 引用进搜索时去掉标记")
        val oldHay = old.searchHaystack.joinToString("\n")
        expectM(oldHay.contains("bob") && oldHay.contains("x.com") && !oldHay.contains("pw-bob"), "旧记录照旧：用户名 / 网址进、密码不进")
    }

    /** 彻底删除：画布也要擦干净 —— nodes / edges 清成空数组（键还在），fmt / order 留着；旧记录擦完不多出 nodes 键。 */
    @Test fun purgeCases() {
        var it = parseItem(canvasJSON)?.copy(deleted = true) ?: return fail("解不开")
        expectM(it.hasContent, "画布版进回收站：nodes 非空就是还有内容（blocks 本来就空）")
        val onlyNodes = it.copy(attachments = emptyList())
        expectM(onlyNodes.hasContent, "只有节点、没有附件也算还有内容")
        it = it.purgedContent()
        expectM(!it.hasContent && it.nodes.isEmpty() && it.edges.isEmpty() && it.title.isEmpty() && it.attachments.isEmpty(),
            "彻底删除后 nodes / edges / 标题 / 附件都要空")
        expectM(it.extra["nodes"].vArray?.isEmpty() == true && it.extra["edges"].vArray?.isEmpty() == true,
            "nodes / edges 清成**空数组**（键还在，PC 读到的是 []，不是「没这个键」）")
        expectM(it.isCanvas && it.extra["order"].vNumber == 1.5, "fmt / order 这类不是内容的键留着")
        val back = parseItem(it.toJson().toString())
        if (back != null) {
            expectM(!back.hasContent && back.isCanvas, "墓碑往返一遍仍是空的画布墓碑")
        } else expectM(false, "墓碑编不出 / 解不回")
        // 旧记录照旧。
        val old0 = parseItem(legacyJSON)
        if (old0 != null) {
            var old = old0.copy(deleted = true)
            expectM(old.hasContent, "旧记录有控件就是还有内容")
            old = old.purgedContent()
            expectM(!old.hasContent && old.extra.isEmpty(), "旧记录擦完 extra 仍为空，不多出 nodes 键")
        }
    }

    /** 快照 v:1 / v:2 都收；整份 v:2 快照往返一遍不丢键。 */
    @Test fun snapshotCases() {
        val v2 = """
        {"v":2,"vaults":[{"id":"v1","name":"我","owner":"self","icon":"🧑","order":0,"keyWrapped":"k"}],
         "data":{"v1":{"types":[],"items":[$canvasJSON],"attachments":{}}}}
        """.trimIndent()
        val snap = VSnapshot.parse(v2)
        expectM(snap?.v == 2 && snap.data["v1"]?.types?.isEmpty() == true && snap.data["v1"]?.items?.firstOrNull()?.isCanvas == true,
            "v:2 快照（types [] / attachments {}）应当解得开")
        val v1 = """
        {"v":1,"vaults":[],"data":{"v1":{"types":[{"id":"t1","name":"登录","icon":"🔑","order":0}],"items":[$legacyJSON],"attachments":{"a":"AA=="}}}}
        """.trimIndent()
        val s1 = VSnapshot.parse(v1)
        expectM(s1?.v == 1 && s1.data["v1"]?.items?.firstOrNull()?.isCanvas == false, "v:1 快照照旧")
        if (snap != null) {
            expectM(canonical(snap.encode()) == canonical(v2), "整份 v:2 快照往返一遍不丢键")
        }
    }
}
