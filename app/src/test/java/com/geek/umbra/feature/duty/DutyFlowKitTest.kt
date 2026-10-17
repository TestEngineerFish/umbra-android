package com.geek.umbra.feature.duty

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject as JObj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iOS Tools/swifttests/DutyFlowKitTests.swift 逐条照抄（执勤 M1b 契约 §17）：拓扑排序、分支标注、配置摘要、
 * FlowRun 解析与并入、列表一行、执勤编辑器「跑一个流程」。
 */
class DutyFlowKitTest {
    private val zh = mapOf(
        "duty.list.sep" to "、",
        "duty.ctx.yes" to "是", "duty.ctx.no" to "否",
        "duty.flow.branch.yes" to "「%@」为是时",
        "duty.flow.branch.no" to "「%@」为否时",
        "duty.flow.cfg.pair" to "%1\$@：%2\$@",
        "duty.flow.cost" to "约 %@ 点 / 次",
        "duty.flow.cost.free" to "不花额度",
        "duty.flow.builtin" to "内置",
        "duty.flow.nodes" to "%@ 个节点",
        "duty.flow.usedBy" to "被%@用",
        "duty.flow.usedByNone" to "没有执勤在用",
        "duty.flow.quoted" to "「%@」",
        "duty.flow.run.manual" to "手动",
        "duty.flow.run.byDuty" to "执勤触发",
        "duty.why.secs" to "%@ 秒",
        "duty.why.points" to "%@ 点",
        "duty.edit.unnamed" to "没起名字",
        "duty.act.flow" to "跑执勤流程",
        "duty.act.flowNamed" to "跑流程：%@",
    )
    private val tr = trOf(zh) { "<$it>" }

    private fun node(id: String, type: String = "t", cfg: Map<String, JsonElement> = emptyMap()) = DutyFlowNode(id, type, config = JObj(cfg))

    private val catalog = DutyFlowCatalog(listOf(
        DutyFlowNodeType("collect.news", group = "collect", name = "搜新闻",
            config = listOf(DutyFlowField("query", "搜什么", required = true), DutyFlowField("limit", "几条", kind = "number"))),
        DutyFlowNodeType("utility.filter", group = "process", name = "筛选",
            config = listOf(DutyFlowField("keyword", "关键词"),
                DutyFlowField("section", "只留", kind = "multiselect",
                    options = listOf(DutyFlowOption("news", "新闻"), DutyFlowOption("weather", "天气"))))),
        DutyFlowNodeType("ai.judge", group = "ai", name = "判断一下", ports = listOf("yes", "no"), ai = true),
        DutyFlowNodeType("ai.summarize", group = "ai", name = "总结", ai = true,
            config = listOf(DutyFlowField("brief", "要求", kind = "textarea"),
                DutyFlowField("kind", "写成", kind = "select", options = listOf(DutyFlowOption("brief", "早报卡"), DutyFlowOption("text", "一段话"))),
                DutyFlowField("model", "模型", kind = "select"))),
        DutyFlowNodeType("collect.money", group = "collect", name = "记账", config = listOf(DutyFlowField("budget", "带预算", kind = "bool"))),
        DutyFlowNodeType("output.chat", group = "output", name = "发到聊天"),
    ))

    // ① 拓扑排序
    @Test fun orderCases() {
        // 两个起点（a、b）按节点数组顺序；a 的下游按连线顺序：先 d 后 c。e 有两个上游（c、d），要等两个都排完才排。
        val g = DutyFlowGraph(
            listOf(node("a"), node("b"), node("c"), node("d"), node("e")),
            listOf(DutyFlowConn("a", "d"), DutyFlowConn("a", "c"), DutyFlowConn("c", "e"), DutyFlowConn("d", "e"), DutyFlowConn("b", "e")),
        )
        assertEquals("按连线顺序的拓扑序（菱形 + 两个起点）", listOf("a", "b", "d", "c", "e"), DutyFlowKit.order(g).map { it.node.id })
        assertEquals("序号从 1 数", listOf(1, 2, 3, 4, 5), DutyFlowKit.order(g).map { it.index })
        assertFalse("无环时没有一个标「成环」", DutyFlowKit.order(g).any { it.inCycle })

        val rev = DutyFlowGraph(listOf(node("z"), node("y"), node("x")), listOf(DutyFlowConn("x", "y"), DutyFlowConn("y", "z")))
        assertEquals("数组顺序与依赖相反时照依赖排", listOf("x", "y", "z"), DutyFlowKit.order(rev).map { it.node.id })

        val junk = DutyFlowGraph(listOf(node("a"), node("b")), listOf(DutyFlowConn("a", "ghost"), DutyFlowConn("b", "b"), DutyFlowConn("a", "b")))
        assertEquals("坏线（悬空 / 自环）忽略", listOf("a", "b"), DutyFlowKit.order(junk).map { it.node.id })
        assertFalse("自环不算成环（直接忽略）", DutyFlowKit.order(junk).any { it.inCycle })

        val cyc = DutyFlowGraph(listOf(node("c"), node("a"), node("b")), listOf(DutyFlowConn("a", "b"), DutyFlowConn("b", "c"), DutyFlowConn("c", "b")))
        val o = DutyFlowKit.order(cyc)
        assertEquals("成环的点按原顺序追在最后", listOf("a", "c", "b"), o.map { it.node.id })
        assertEquals("成环的点标出来", listOf(false, true, true), o.map { it.inCycle })

        assertTrue("空图 → 空列表", DutyFlowKit.order(DutyFlowGraph()).isEmpty())
    }

    // ② 分支标注
    @Test fun branchCases() {
        val g = DutyFlowGraph(
            listOf(node("n", "collect.news"), node("j", "ai.judge"), node("y", "output.chat"), node("x", "record.sent"), node("m", "mystery.type")),
            listOf(DutyFlowConn("n", "j"), DutyFlowConn("j", "y", "yes"), DutyFlowConn("j", "x", "no"), DutyFlowConn("m", "x", "yes")),
        )
        val byId = DutyFlowKit.order(g).associateBy { it.node.id }
        assertEquals("默认出口进来的不标分支", emptyList<DutyFlowKit.Branch>(), byId["j"]?.branches)
        assertEquals("yes 出口", listOf(DutyFlowKit.Branch("j", "yes")), byId["y"]?.branches)
        assertEquals("分支那一句用上游节点在目录里的名字", "「判断一下」为是时", DutyFlowKit.branchText(byId["y"]!!.branches, g, catalog, tr))
        assertEquals("两个分支口进来：按连线顺序接；目录里没有的退回类型名", "「判断一下」为否时、「mystery.type」为是时",
            DutyFlowKit.branchText(byId["x"]!!.branches, g, catalog, tr))
        assertEquals("目录没拉到：退回类型名", "「ai.judge」为是时", DutyFlowKit.branchText(byId["y"]!!.branches, g, null, tr))
        assertEquals("节点名取目录", "搜新闻", DutyFlowKit.nodeName("collect.news", catalog))
        assertEquals("不认识的类型 → 类型名本身", "x.y", DutyFlowKit.nodeName("x.y", catalog))
    }

    // ③ 配置摘要
    @Test fun configCases() {
        val news = node("n", "collect.news", mapOf("limit" to JsonPrimitive(5), "query" to JsonPrimitive("手机 新品")))
        assertEquals("按 Field 顺序（不是 JSON 键序），数字不带小数点", "搜什么：手机 新品 · 几条：5", DutyFlowKit.configSummary(news, catalog, tr))

        val filt = node("f", "utility.filter", mapOf("keyword" to JsonPrimitive(""), "section" to JsonArray(listOf(JsonPrimitive("weather"), JsonPrimitive("odd")))))
        assertEquals("空串不列；multiselect 逐个换成选项名，认不出的原样", "只留：天气、odd", DutyFlowKit.configSummary(filt, catalog, tr))

        val long = "很".repeat(50)
        val sum = node("s", "ai.summarize", mapOf("brief" to JsonPrimitive(long), "kind" to JsonPrimitive("text"), "model" to JsonPrimitive("fast")))
        assertEquals("textarea 截到 30 字；select 换选项名；select 没给选项 → 原值",
            listOf("要求：" + "很".repeat(30) + "…", "写成：一段话", "模型：fast"), DutyFlowKit.configLines(sum, catalog, tr))

        val money = node("m", "collect.money", mapOf("budget" to JsonPrimitive(false)))
        assertEquals("bool 说是 / 否（false 也列：那是一个选择）", "带预算：否", DutyFlowKit.configSummary(money, catalog, tr))

        val extra = node("q", "collect.news", mapOf(
            "query" to JsonPrimitive("A"), "zeta" to JsonPrimitive(1.5), "alpha" to JsonPrimitive("x"),
            "obj" to JObj(mapOf("k" to JsonPrimitive("v"))), "nul" to JsonNull,
        ))
        assertEquals("目录里没有的键按键名追在后面；对象 / null 不列", "搜什么：A · alpha：x · zeta：1.5", DutyFlowKit.configSummary(extra, catalog, tr))

        val noCat = node("q", "collect.news", mapOf("query" to JsonPrimitive("A"), "limit" to JsonPrimitive(3)))
        assertEquals("目录没拉到：键名当标签、按键名排", "limit：3 · query：A", DutyFlowKit.configSummary(noCat, null, tr))
        assertEquals("没有配置 → 空串", "", DutyFlowKit.configSummary(node("e", "record.sent"), catalog, tr))
        assertEquals("config 不是对象 → 空串，不崩", "", DutyFlowKit.configSummary(DutyFlowNode("z", "t", config = JsonPrimitive("坏")), catalog, tr))
    }

    // ④ FlowRun 解析与并入
    @Test fun runCases() {
        val ws = DutyFlowRun.parse(json("""{"id": 77, "flow_id": "f1", "shift_id": "", "status": "ok", "points": 4, "started_at_ms": 1000, "ended_at_ms": 3500}"""))
        assertEquals("WS：数字 id 收成字符串", "77", ws?.id)
        assertEquals("WS：status", "ok", ws?.status)
        assertNull("WS 不带 steps → nil（不是空数组）", ws?.steps)
        assertEquals("耗时", "2.5 秒", ws?.let { DutyFlowKit.duration(it, tr) })
        assertEquals("运行一行的副文", "手动 · 4 点 · 2.5 秒", ws?.let { DutyFlowKit.runSub(it, tr) })
        assertNull("没 id → nil", DutyFlowRun.parse(json("""{"flow_id": "f1"}""")))
        assertNull("不是字典 → nil", DutyFlowRun.parse(JsonPrimitive("不是字典")))

        val running = DutyFlowRun.parse(json("""{"id": "r2", "flow_id": "f1", "status": "running", "started_at_ms": 1000, "ended_at_ms": null}"""))
        assertEquals("running 没结束", false, running?.finished)
        assertNull("ended_at_ms null → nil", running?.ended_at_ms)
        assertNull("没结束不写耗时", running?.let { DutyFlowKit.duration(it, tr) })

        val detail = DutyFlowRun.from(obj("""
        {"id": "r1", "flow_id": "f1", "shift_id": "s9", "status": "failed", "points": 3, "started_at_ms": 10, "ended_at_ms": 20,
         "steps": [{"node_id": "a", "type": "collect.news", "status": "ok", "ms": 1200, "points": 0, "out_summary": "5 条"},
                   {"node_id": "b", "type": "ai.judge", "status": "failed", "ms": "x", "error": "额度不够"}]}
        """))
        assertEquals("详情：steps", 2, detail.steps?.size)
        assertEquals("步骤 error", "额度不够", detail.steps?.last()?.error)
        assertEquals("ms 类型不对 → 0，不让整条解不开", 0, detail.steps?.last()?.ms)
        assertEquals("有 shift_id = 执勤触发", "执勤触发 · 3 点 · 0.0 秒", DutyFlowKit.runSub(detail, tr))

        val withSteps = DutyFlowRun("r1", flow_id = "f1", status = "running", steps = listOf(DutyFlowStep("a")))
        val older = DutyFlowRun("r0", flow_id = "f1", status = "ok")
        var runs = DutyFlowKit.merge(DutyFlowRun("r1", flow_id = "f1", status = "ok"), listOf(withSteps, older))
        assertEquals("同 id 就地替换，不新增一行", listOf("r1", "r0"), runs.map { it.id })
        assertEquals("替换成新状态", "ok", runs.first().status)
        assertEquals("WS 那条没带 steps → 留着已拉到的", 1, runs.first().steps?.size)
        runs = DutyFlowKit.merge(DutyFlowRun("r3", flow_id = "f1"), runs, limit = 2)
        assertEquals("新的插最前、超出 limit 截掉", listOf("r3", "r1"), runs.map { it.id })
        val replaced = DutyFlowKit.merge(DutyFlowRun("r1", status = "ok", steps = emptyList()), listOf(withSteps))
        assertEquals("新的带了（哪怕是空的）steps 就用新的", 0, replaced.first().steps?.size)

        val flows = listOf(DutyFlow("f1", last_run = DutyFlowLastRun("r5", "ok", 5000)), DutyFlow("f2"))
        val late = DutyFlowKit.applyLastRun(DutyFlowRun("r4", flow_id = "f1", status = "failed", started_at_ms = 3000, ended_at_ms = 4000), flows)
        assertEquals("晚到的旧一次不把新结果盖回去", "r5", late.first().last_run?.id)
        val fresh = DutyFlowKit.applyLastRun(DutyFlowRun("r6", flow_id = "f1", status = "running", started_at_ms = 6000), flows)
        assertEquals("新的一次（还在跑）记开始时刻", DutyFlowLastRun("r6", "running", 6000), fresh.first().last_run)
        assertNull("别的流程不动", fresh.last().last_run)
        val same = DutyFlowKit.applyLastRun(DutyFlowRun("r5", flow_id = "f1", status = "failed", started_at_ms = 1, ended_at_ms = 2), flows)
        assertEquals("同一次的更新照收（哪怕时刻更早）", "failed", same.first().last_run?.status)

        val g = DutyFlowGraph(listOf(node("c", "output.chat"), node("a", "collect.news"), node("b", "ai.judge")),
            listOf(DutyFlowConn("a", "b"), DutyFlowConn("b", "c", "yes")))
        val steps = listOf(DutyFlowStep("b", "ai.judge"), DutyFlowStep("a", "collect.news"), DutyFlowStep("gone", "record.sent"))
        val rows = DutyFlowKit.stepRows(g, steps)
        assertEquals("逐节点按图的顺序，图外的追在后面", listOf("a", "b", "c", "gone"), rows.map { it.nodeId })
        assertEquals("图里有、没记到的 → nil", listOf(false, false, true, false), rows.map { it.step == null })
        assertEquals("没有图 → 照步骤原顺序", listOf("b", "a", "gone"), DutyFlowKit.stepRows(null, steps).map { it.nodeId })
        assertEquals("状态 key", "duty.flow.status.ok", DutyFlowKit.statusKey("ok"))
        assertEquals("认不出的状态归 other（不印 key）", "duty.flow.status.other", DutyFlowKit.statusKey("weird"))
    }

    // ⑤ 列表与执勤编辑器
    @Test fun listCases() {
        val dto = DutyFlowsDTO.from(obj("""
        {"items": [
          {"id": "u1", "name": "盯新闻", "preset": "", "readonly": 0, "node_count": 4, "ai_nodes": 1, "est_points": 12,
           "est_weekly_pct_if_daily": 3.5, "used_by": [{"duty_id": "d1", "name": "早上看看"}, {"duty_id": "d2", "name": ""}],
           "last_run": {"id": "r1", "status": "failed", "at_ms": 99}, "created_at_ms": 1, "updated_at_ms": 2},
          {"id": "mb", "name": "早报", "preset": "morning_brief", "readonly": true, "node_count": 6, "est_points": 0,
           "used_by": [], "last_run": null},
          {"name": "没 id 的丢掉"}
        ]}
        """))
        assertEquals("列表：没 id 的丢掉", listOf("u1", "mb"), dto.items.map { it.id })
        val u1 = dto.items.first()
        val mb = dto.items.last()
        assertEquals("readonly 0 → false", false, u1.readonly)
        assertEquals("用户流程不是内置", false, u1.isBuiltin)
        assertEquals("preset = morning_brief → 内置", true, mb.isBuiltin)
        assertNull("last_run null → nil", mb.last_run)
        assertEquals("last_run", "failed", u1.last_run?.status)
        assertEquals("列表副文：节点数、额度、被谁用（没名字的执勤说「没起名字」）", "4 个节点 · 约 12 点 / 次 · 被「早上看看」、「没起名字」用",
            DutyFlowKit.listSub(u1, tr))
        assertEquals("内置 + 不花额度 + 没人用", "内置 · 6 个节点 · 不花额度 · 没有执勤在用", DutyFlowKit.listSub(mb, tr))
        assertEquals("内置的排最前", listOf("mb", "u1"), DutyFlowKit.sortFlows(dto.items).map { it.id })
        assertEquals("「跑一个流程」只能选用户流程", listOf("u1"), DutyFlowKit.pickable(dto.items).map { it.id })
        assertEquals("只读也算内置（preset 漏给时）", true, DutyFlow("x", readonly = true).isBuiltin)

        val detail = DutyFlow.from(obj("""
        {"id": "u1", "name": "盯新闻", "graph": {"nodes": [{"id": "a", "type": "collect.news", "x": 10, "y": 20, "config": {"query": "手机"}},
                                                        {"type": "没 id"}],
                                               "connections": [{"from": "a", "to": "b", "fromPort": "yes"}, {"from": "", "to": "a"}]}}
        """))
        assertEquals("graph：没 id 的节点丢掉", listOf("a"), detail.graph?.nodes?.map { it.id })
        assertEquals("graph：缺一头的线丢掉", 1, detail.graph?.connections?.size)
        assertEquals("fromPort 原样", "yes", detail.graph?.connections?.first()?.fromPort)
        assertEquals("node_count 缺 → 按 graph 数", 1, detail.node_count)
        assertEquals("节点 config", "手机", detail.graph?.nodes?.first()?.config["query"].str)

        val cat = DutyFlowCatalog.from(obj("""
        {"items": [{"type": "ai.judge", "group": "ai", "name": "判断", "ports": ["yes", "no"], "ai": true, "est_points": 2,
                    "config": [{"key": "question", "label": "问什么", "kind": "text", "required": true}, {"label": "没 key"}]},
                   {"group": "x"}]}
        """))
        assertEquals("目录：没 type 的丢掉", listOf("ai.judge"), cat.items.map { it.type })
        assertEquals("目录：ports", listOf("yes", "no"), cat.item("ai.judge")?.ports)
        assertEquals("目录：没 key 的 Field 丢掉", listOf("question"), cat.item("ai.judge")?.config?.map { it.key })
        assertEquals("Field.required", true, cat.item("ai.judge")?.config?.first()?.required)
        assertEquals("一键跑回包 run_id", "12", DutyFlowRunStartDTO.from(obj("""{"ok": true, "run_id": 12}""")).run_id)

        var d = DutyDraft.new("2026-10-16")
        d = d.copy(name = "早上看看", action = DutyAction(type = "flow"))
        assertEquals("跑流程没选流程 → 不让存", "duty.edit.err.flow", d.problem(0))
        d = d.copy(action = d.action.copy(flow_id = "u1"))
        assertNull("选了流程 → 能存", d.problem(0))
        val a = d.payload["action"] as JsonObject
        assertEquals("回写 action.type", "flow", a["type"].strictString())
        assertEquals("回写 action.flow_id", "u1", a["flow_id"].strictString())
        assertEquals("回写只带 flow 自己的键", listOf("flow_id", "type"), a.keys.sorted())
        assertEquals("动作摘要带流程名", "跑流程：盯新闻", DutyKit.action(d.action, tr, flowName = "盯新闻"))
        assertEquals("找不到流程名 → 不带名字的那句", "跑执勤流程", DutyKit.action(d.action, tr))
    }
}
