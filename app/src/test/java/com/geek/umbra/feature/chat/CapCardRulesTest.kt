package com.geek.umbra.feature.chat

import com.geek.umbra.net.UmbraJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 权限确认卡的解析与判据（协议 §8.2 / 服务端 cap_gateway 实际形状 / 设计侧《权限确认卡》规则；字面照 PC zh-CN capCard.*）。
class CapCardRulesTest {
    private fun card(json: String): CapCard = CapCard.parse(UmbraJson.parseToJsonElement(json) as JsonObject)!!

    private val ask = """{"call_id":"c_1","capability":"pc.workflow","action":"create","name":"工作流 · 新建","verb":"write","where":"device",
        "run_on":{"device_id":"mac","device_name":"演示电脑","online":true},"phase":"ask","title":"生成随机密码",
        "warnings":[{"kind":"runs_code","text":"装上以后，打 pw 就会执行预览里的脚本。"}],
        "preview":[{"kind":"graph","nodes":[{"label":"关键词 pw","sub":"{长度}"},{"label":"脚本过滤器"}]},
                   {"kind":"code","title":"脚本","lang":"bash","text":"a\nb\nc\nd\ne\nf\ng"},{"kind":"weird","foo":1}],
        "buttons":["once","always","reject"],"can_always":true,"bind":true,
        "always":[{"label":"以后改「生成随机密码」不再问"},{"label":"以后改任何工作流都不再问"}],
        "expires_at_ms":1000,"pending_count":1,"turn_id":"t1","turn_index":1}"""

    @Test fun parseAskCard() {
        val c = card(ask)
        assertEquals("c_1", c.callId)
        assertEquals("演示电脑", c.runOn?.name)
        assertEquals(listOf("reject", "always", "once"), CapCardRules.buttons(c))
        assertEquals("这一次", CapCardRules.buttonLabel("once"))
        assertEquals("点「总是允许」时记下", CapCardRules.scopeTitle(c))
        assertEquals("脚本一改就会重新问。", CapCardRules.bindNote(c))
        assertNull(CapCardRules.footnote(c))
        assertEquals("会在 「演示电脑」 上执行" to false, CapCardRules.runOnLine(c))
        assertEquals("装上以后，打 pw 就会执行预览里的脚本。", CapCardRules.warningText(c.warnings[0], c))
        // 认不得的预览段降级成纯文字，不崩
        assertTrue(c.preview[2] is CapPreview.Plain)
        assertEquals("脚本 · bash · 7 行", CapCardRules.segHead(c.preview[1]))
        assertEquals("节点 · 2 个", CapCardRules.segHead(c.preview[0]))
        assertNull(CapCard.parse(UmbraJson.parseToJsonElement("""{"phase":"ask"}""") as JsonObject))
    }

    @Test fun onceOnlyCardsAndFootnotes() {
        // 服务端老形状：warnings 只有字符串、没 once_reason —— 按卡上看得到的推
        val delete = card("""{"call_id":"c","name":"回收站 · 彻底删除","phase":"ask","warnings":["irreversible"],"buttons":["once","reject"],"can_always":false}""")
        assertEquals(listOf("reject", "once"), CapCardRules.buttons(delete))
        // text 为空的警告不画，不自己编
        assertNull(CapCardRules.warningText(delete.warnings[0], delete))
        assertEquals("删了找不回的事每次都要你点头，所以没有「总是允许」。", CapCardRules.footnote(delete))
        assertTrue(CapCardRules.isDanger("irreversible"))
        // 没信任的网站：外来内容警告 + 信任入口（最多 3 个）
        val web = card("""{"call_id":"c","phase":"ask","warnings":[],"buttons":["once","always","reject"],"can_always":false,
            "taint":{"domains":["bbs.example-forum.cn"],"kinds":[]},"trust_offers":["bbs.example-forum.cn","a","b","c"]}""")
        assertEquals(listOf("reject", "once"), CapCardRules.buttons(web))
        assertEquals("external_content", CapCardRules.warnings(web).single().kind)
        assertEquals("这一轮秘书读过你没信任过的网站。如果不是你自己要改的，点「不要」。", CapCardRules.warningText(CapCardRules.warnings(web)[0], web))
        assertEquals(3, web.trustOffers.size)
        assertTrue(CapCardRules.footnote(web)!!.startsWith("读过没信任过的网站"))
        // 没有域名的外来内容：说读过什么，不给信任入口
        val file = card("""{"call_id":"c","phase":"ask","buttons":["once","reject"],"taint":{"domains":[],"kinds":["file"],"items":[{"kind":"file","label":"物业通知.pdf"}]}}""")
        assertEquals("这一轮秘书读过不是你写的内容。如果不是你自己要改的，点「不要」。", CapCardRules.warningText(CapCardRules.warnings(file)[0], file))
        assertEquals("文件、邮件这类内容没有网站可以信任，读过这些内容的这一轮，只能允许一次。", CapCardRules.footnote(file))
        // 执行端说了原因就用它
        val ask = card("""{"call_id":"c","phase":"ask","buttons":["once","reject"],"once_reason":"ask_rule"}""")
        assertEquals("你设过这件事每次都问。", CapCardRules.footnote(ask))
    }

    @Test fun warningOrderAndOffline() {
        val c = card("""{"call_id":"c","phase":"ask","buttons":["once","reject"],"warnings":["runs_code","costs_money","irreversible"],
            "run_on":{"device_id":"m","device_name":"演示电脑","online":false}}""")
        assertEquals(listOf("costs_money", "irreversible", "runs_code"), CapCardRules.warnings(c).map { it.kind })
        assertEquals("「演示电脑」现在不在线。点了以后它一上线就执行（24 小时内）。" to true, CapCardRules.runOnLine(c))
        // 服务端能力：没有 run_on → 不出执行端行
        assertNull(CapCardRules.runOnLine(card("""{"call_id":"c","phase":"ask","where":"cloud"}""")))
    }

    @Test fun receipts() {
        val mine = card("""{"call_id":"c","name":"工作流 · 新建","title":"生成随机密码","phase":"result","outcome":"done","decision":"once","decided_on":{"by_client":"client:android","name":"Android 手机"}}""")
        assertEquals(CapReceipt(CapPill.Ok, "已做", "工作流 · 新建 · 生成随机密码", listOf("你允许了这一次"), false), CapCardRules.receipt(mine))
        val ios = card("""{"call_id":"c","name":"工作流 · 运行","phase":"queued","decision":"once","decided_on":{"by_client":"client:ios","name":"iPhone"}}""")
        assertEquals(CapReceipt(CapPill.Wait, "已允许 · 等电脑上线", "工作流 · 运行", listOf("在 iPhone 上允许了这一次"), false), CapCardRules.receipt(ios))
        val rule = card("""{"call_id":"c","name":"提醒 · 新建","title":"明天十点","phase":"result","outcome":"done","by":"rule"}""")
        assertEquals(listOf("按你之前的允许"), CapCardRules.receipt(rule).tail)
        val def = card("""{"call_id":"c","name":"提醒 · 新建","phase":"result","outcome":"done","by":"default"}""")
        assertEquals(listOf("系统默认允许"), CapCardRules.receipt(def).tail)
        val rej = card("""{"call_id":"c","name":"回收站 · 彻底删除","phase":"result","outcome":"rejected","decision":"reject","decided_on":{"by_client":"device:mac","name":"演示电脑"}}""")
        assertEquals(CapReceipt(CapPill.Off, "没做", "回收站 · 彻底删除", listOf("在 演示电脑 上点了不要"), true), CapCardRules.receipt(rej))
        val exp = card("""{"call_id":"c","name":"工作流 · 运行","phase":"result","outcome":"expired"}""")
        assertEquals(listOf("等了 24 小时没人点"), CapCardRules.receipt(exp).tail)
        assertEquals(CapReceipt(CapPill.Wait, "已处理", "工作流 · 新建 · 生成随机密码", listOf("已经在「演示电脑」上处理过了"), true),
            CapCardRules.receipt(mine, supersededBy = "演示电脑"))
        assertEquals("没跑完", CapCardRules.receipt(card("""{"call_id":"c","phase":"result","outcome":"failed","error":"超时"}""")).label)
    }

    @Test fun localExpiryWithGrace() {
        val c = card(ask) // expires_at_ms = 1000
        assertTrue(CapCardRules.isOpen(CapBlockRules.effective(c, 1000 + CapBlockRules.EXPIRY_GRACE_MS)))
        val e = CapBlockRules.effective(c, 1001 + CapBlockRules.EXPIRY_GRACE_MS)
        assertFalse(CapCardRules.isCard(e))
        assertEquals("已过期", CapCardRules.receipt(e).label)
    }

    @Test fun groupingReadsAndTurnHead() {
        fun read(id: String, turn: String?) = ChatBlock.Cap(id = id, card = card("""{"call_id":"$id","name":"工作流 · 列出","verb":"read","phase":"result","outcome":"done",
            "title":"","summary":"这台电脑上的 3 个工作流"${turn?.let { ",\"turn_id\":\"$it\"" } ?: ""}}"""))
        fun open(id: String, turn: String) = ChatBlock.Cap(id = id, card = card("""{"call_id":"$id","phase":"ask","buttons":["once","reject"],"turn_id":"$turn"}"""))
        val note = ChatBlock.Note(text = "x")
        val out = CapBlockRules.group(listOf(read("r1", "t"), read("r2", "t"), read("r3", null), note, read("r4", "t"), open("a", "u"), open("b", "u"), open("c", "u")), 0)
        assertEquals(listOf("r1", "r2", "r3"), (out[0] as ChatBlock.CapReads).items.map { it.callId })
        assertTrue(out[1] is ChatBlock.Note)
        assertEquals(1, (out[2] as ChatBlock.CapReads).items.size)
        assertEquals(3, (out[3] as ChatBlock.Cap).turnOpen)
        assertEquals(0, (out[4] as ChatBlock.Cap).turnOpen)
        assertEquals("这一轮有 3 件事等你同意", CapCardRules.turnHead(3))
        assertNull(CapCardRules.turnHead(2))
        assertEquals("工作流 · 列出　这台电脑上的 3 个工作流", CapCardRules.readLine((out[0] as ChatBlock.CapReads).items[0]))
        assertEquals("看了 3 样东西", CapCardRules.readHead(3))
    }

    @Test fun decideAndConflict() {
        assertEquals("""{"decision":"always","scope_index":1}""", CapCardRules.decideBody("always", 1))
        assertEquals("""{"decision":"once","scope_index":0}""", CapCardRules.decideBody("once", 1))
        val body = UmbraJson.parseToJsonElement("""{"code":"state_conflict","detail":"已经处理过了","decided_on":{"by_client":"device:mac","name":"演示电脑"}}""") as JsonObject
        assertEquals("演示电脑", CapCardRules.conflictName(body))
        assertNull(CapCardRules.conflictName(UmbraJson.parseToJsonElement("""{"detail":"过期了"}""") as JsonObject))
    }

    @Test fun vaultNotice() {
        val n = CapNotice.parse(UmbraJson.parseToJsonElement("""{"kind":"vault_bound","name":"发周报","title":"「发周报」用了保险箱里的密钥",
            "body":"本机句","body_remote":"所以我不能运行，也不能改它。你自己运行照常：在「办公室电脑」的快捷入口打 zb。要改的话，在「办公室电脑」上打开它改。"}""") as JsonObject)!!
        assertTrue(n.body.contains("办公室电脑"))
        assertNull(CapNotice.parse(UmbraJson.parseToJsonElement("""{"kind":"other"}""") as JsonObject))
    }

    // 设计侧 324：正在做、派活时不在线、没有域名的外来内容那张表、在云端
    @Test fun design324() {
        val busy = card("""{"call_id":"c","name":"工作流 · 运行","title":"x","phase":"approved","decision":"once","decided_on":{"by_client":"client:ios","name":"iPhone"}}""")
        assertEquals(CapReceipt(CapPill.Busy, "正在做", "工作流 · 运行 · x", listOf("已允许", "在 iPhone 上"), false), CapCardRules.receipt(busy))
        val mineBusy = card("""{"call_id":"c","name":"工作流 · 运行","phase":"approved","decided_on":{"by_client":"client:android","name":"Android 手机"}}""")
        assertEquals(listOf("已允许"), CapCardRules.receipt(mineBusy).tail)
        val wait = card("""{"call_id":"c","phase":"waiting_device","run_on":{"device_id":"m","device_name":"Sam 的 MacBook","online":false}}""")
        assertEquals("「Sam 的 MacBook」现在不在线，上线后会在这里问你" to true, CapCardRules.runOnLine(wait))
        assertTrue(CapCardRules.isCard(wait))
        assertTrue(CapCardRules.buttons(wait).isEmpty())
        val t = CapTaint(emptyList(), listOf("mail", "history", "tool"),
            listOf("file" to "a.xlsx", "file" to "b", "file" to "c", "file" to "d", "file" to "e", "mail" to "zhang@example.com", "knowledge" to "客户名单"))
        assertEquals(listOf("读过文件「a.xlsx」", "读过文件「b」", "读过文件「c」", "还有 2 样", "读过一封邮件（来自 zhang@example.com）",
            "读过知识库里的「客户名单」", "读过以前的聊天记录", "读过工具的输出"), CapCardRules.taintLines(t))
        val many = card("""{"call_id":"c","phase":"ask","buttons":["once","reject"],"taint":{"domains":[],"kinds":["file","tool"]}}""")
        assertEquals("这一轮秘书读过不是你写的内容。如果不是你自己要改的，点「不要」。", CapCardRules.warningText(CapCardRules.warnings(many)[0], many))
        val cloud = card("""{"call_id":"c","name":"常用语 · 删除","where":"cloud","phase":"result","outcome":"done","decision":"once"}""")
        assertEquals("常用语 · 删除 · 在云端", CapCardRules.headName(cloud))
        assertEquals(listOf("你允许了这一次", "在云端"), CapCardRules.receipt(cloud).tail)
        assertEquals("工作流 · 新建", CapCardRules.headName(card("""{"call_id":"c","name":"工作流 · 新建","where":"device"}""")))
    }

    // 设计侧 326：卡头 / 回执用短动词（按 action 查，服务端转发的长句不用）；回执只写对象短名，没带退回卡标题
    @Test fun design326() {
        val relayed = card("""{"call_id":"c","capability":"pc.workflow","action":"create","name":"工作流 · 建一个工作流","verb":"write","title":"新建工作流「随机密码」",
            "phase":"result","outcome":"done","decision":"once","summary":"建好了，快捷入口打 pw 试试"}""")
        assertEquals("工作流 · 新建", CapCardRules.headName(relayed))
        assertEquals("工作流 · 新建 · 新建工作流「随机密码」", CapCardRules.receipt(relayed).subject)
        val named = card("""{"call_id":"c","action":"update","name":"常用语 · 改一条常用语","object_name":"物业电话","where":"cloud","title":"改「物业电话」",
            "phase":"result","outcome":"done","decision":"once"}""")
        assertEquals(CapReceipt(CapPill.Ok, "已做", "常用语 · 修改 · 「物业电话」", listOf("你允许了这一次", "在云端"), false), CapCardRules.receipt(named))
        val busy = card("""{"call_id":"c","action":"run","name":"工作流 · 运行一个工作流","object_name":"随机密码","phase":"approved","decided_on":{"by_client":"client:android","name":"Android 手机"}}""")
        assertEquals(CapReceipt(CapPill.Busy, "正在做", "工作流 · 运行 · 「随机密码」", listOf("已允许"), false), CapCardRules.receipt(busy))
        // 认不得的 action 照 name
        assertEquals("回收站 · 彻底删除", CapCardRules.headName(card("""{"call_id":"c","action":"purge","name":"回收站 · 彻底删除"}""")))
    }

    // 设计侧 328：网站和文件混着时标题用不讲网站的那句
    @Test fun mixedTaintTitle() {
        val mixed = card("""{"call_id":"c","phase":"ask","buttons":["once","reject"],"taint":{"domains":["forum.example.com"],"kinds":["file"],"items":[{"kind":"file","label":"物业通知.pdf"}]}}""")
        assertEquals("这一轮秘书读过不是你写的内容。如果不是你自己要改的，点「不要」。", CapCardRules.warningText(CapCardRules.warnings(mixed)[0], mixed))
    }

    // 设计侧 330 五.1：回执上的［撤销］
    // 332 二：名字不会少于「引号 + 前 4 字 +…」；放得下全给；压不动时截的是行尾
    // 服务端 action_word 优先：提醒 update 只标完成时写「完成这一次」，不按 action 拼「修改」；没带退回查表，再退回 name
    @Test fun actionWordWins() {
        val c = card("""{"call_id":"c1","capability":"reminders","action":"update","name":"提醒 · 完成这一次","action_word":"完成这一次",
            "verb":"write","where":"cloud","object_name":"交房租","phase":"asked","title":"完成这一次「交房租」"}""")
        assertEquals("提醒 · 完成这一次", CapCardRules.capName(c))
        assertEquals("提醒 · 完成这一次 · 在云端", CapCardRules.headName(c))
        assertEquals("提醒 · 完成这一次 · 「交房租」", CapCardRules.subject(c))
        assertEquals("提醒 · 完成这一次　交房租", CapCardRules.readLine(c.copy(summary = null, title = "交房租")))
        assertEquals("老卡按表", "提醒 · 修改", CapCardRules.capName(c.copy(actionWord = null)))
        assertEquals("认不得的 action 照 name", "提醒 · 完成这一次", CapCardRules.capName(c.copy(actionWord = null, action = "mark")))
        // PC tests/r330chat.test.ts 最后一组（0b821ce）逐条移植
        fun n(name: String, action: String, word: String? = null) =
            CapCardRules.capName(card("""{"call_id":"c","capability":"x","action":"$action","name":"$name","phase":"asked","title":"t"${word?.let { ",\"action_word\":\"$it\"" } ?: ""}}"""))
        assertEquals("提醒 · 完成这一次", n("提醒 · 完成这一次", "update", "完成这一次"))
        assertEquals("提醒 · 标回未完成", n("提醒 · 修改", "update", "标回未完成"))
        assertEquals("工作流 · 新建", n("工作流 · 建一个工作流", "create"))
        assertEquals("工作流 · 恢复", n("工作流 · 恢复到之前", "restore"))
        assertEquals("提醒 · 稍后再提醒", n("提醒 · 稍后再提醒", "snooze"))
        assertEquals("提醒", n("提醒", "update", "完成这一次"))
        assertEquals("空白当没有", "提醒 · 修改", n("提醒 · 标回未完成", "update", "  "))
    }

    // 服务端 #189：outcome = skipped（允许了但没动）→「没做」档、不写谁允许、行下 note、没有撤销；认不得的 outcome 同样兜底
    @Test fun skippedReceipt() {
        val c = card("""{"call_id":"c1","capability":"reminders","action":"update","name":"提醒 · 完成这一次","action_word":"完成这一次",
            "where":"cloud","object_name":"交房租","phase":"result","outcome":"skipped","title":"完成这一次「交房租」",
            "note":"这一次已经过了，现在的下次是 2099-02-10 09:00（周二），没动","decision":"once","buttons":[]}""")
        val r = CapCardRules.receipt(c)
        assertEquals(CapPill.Off, r.pill)
        assertEquals("没做", r.label)
        assertEquals("提醒 · 完成这一次 · 「交房租」", r.subject)
        assertEquals(listOf("在云端"), r.tail)
        assertEquals("这一次已经过了，现在的下次是 2099-02-10 09:00（周二），没动", r.note)
        assertFalse(CapCardRules.showsUndo(c, blocked = false))
        val odd = CapCardRules.receipt(c.copy(outcome = "partial", note = null))
        assertEquals("没做", odd.label)
        assertNull(odd.note)
        assertEquals("已做", CapCardRules.receipt(c.copy(outcome = "done", note = null)).label)
        // 对照 PC d066806 tests/capCard.test.ts「#189」：note 去首尾空白、空白当没有；误带 undo 也不出；rejected 没带 decision 按拒绝
        fun withNote(n: String) = card("""{"call_id":"c2","capability":"reminders","action":"update","name":"提醒 · 完成这一次","where":"cloud",
            "phase":"result","outcome":"skipped","title":"t","note":"$n","buttons":["undo"],"undo":{"label":"撤销"}}""")
        assertEquals("没动", CapCardRules.receipt(withNote("  没动 ")).note)
        assertNull(CapCardRules.receipt(withNote("   ")).note)
        assertFalse(CapCardRules.showsUndo(withNote("x"), blocked = false))
        assertEquals("没做", CapCardRules.receipt(c.copy(outcome = "rejected", decision = null)).label)
    }

    @Test fun receiptNameNeverEmptied() {
        assertEquals(80, CapCardRules.receiptNameWidth(avail = 200, full = 80, min = 40, tail = 100))
        assertEquals(60, CapCardRules.receiptNameWidth(avail = 160, full = 80, min = 40, tail = 100))
        assertEquals("行尾太长也不压到 min 以下", 40, CapCardRules.receiptNameWidth(avail = 120, full = 80, min = 40, tail = 300))
        assertEquals("名字本来就短", 30, CapCardRules.receiptNameWidth(avail = 50, full = 30, min = 40, tail = 300))
        assertEquals("整格连 min 都放不下才跟着截", 25, CapCardRules.receiptNameWidth(avail = 25, full = 80, min = 40, tail = 300))
        assertEquals(0, CapCardRules.receiptNameWidth(avail = 0, full = 80, min = 40, tail = 300))
    }

    @Test fun receiptUndo() {
        val done = card("""{"call_id":"c","action":"update","name":"常用语 · 改一条常用语","object_name":"物业电话","where":"cloud",
            "phase":"result","outcome":"done","decision":"once","buttons":["undo"],"undo":{"label":"撤销"}}""")
        assertTrue(CapCardRules.showsUndo(done, blocked = false))
        assertFalse(CapCardRules.showsUndo(done, blocked = true))
        assertEquals("撤销", CapCardRules.undoLabel(done))
        assertFalse("没带 undo 就不出钮", CapCardRules.showsUndo(card("""{"call_id":"c","phase":"result","outcome":"done"}"""), false))
        // 撤成功：已撤销 + 名字，别的端撤的加「在 X 上撤销」，云端照旧，不再写「你允许了这一次」
        val undone = card("""{"call_id":"c","action":"update","name":"常用语 · 改","object_name":"物业电话","where":"cloud","phase":"undone",
            "decision":"once","undone_on":{"by_client":"client:ios","name":"iPhone"}}""")
        assertEquals(CapReceipt(CapPill.Off, "已撤销", "常用语 · 修改 · 「物业电话」", listOf("在 iPhone 上撤销", "在云端"), true), CapCardRules.receipt(undone))
        // 撤不了：按原因码出字
        val R = CapCardRules
        assertEquals("「物业电话」之后又有过改动（撤销也算一次），撤了会盖掉，所以不能撤销。", R.undoOutcome(409, "stale", "x", done)!!.note)
        assertEquals("回收站里已经没有这条了，找不回。", R.undoOutcome(409, "gone", null, done)!!.note)
        val failed = R.undoOutcome(409, "failed", "服务器暂时连不上。", done)!!
        assertEquals("没撤成：服务器暂时连不上。可以再点一次撤销。", failed.note); assertFalse("失败保留钮", failed.blocked)
        assertEquals("找不到这次操作的记录了，不能撤销。", R.undoOutcome(404, null, null, done)!!.note)
        assertTrue(R.undoOutcome(409, "undone", null, done)!!.undone)
        assertEquals(null, R.undoOutcome(409, "not_undoable", null, done)!!.note)
        assertEquals(null, R.undoOutcome(500, null, null, done))
        // 拆段：先截名字用
        assertEquals("常用语 · 修改" to "「物业电话」", R.receiptParts(done, R.receipt(done)))
    }
}
