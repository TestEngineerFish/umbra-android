// 执勤 M1b「执勤流程」的线上形状与纯判据 —— 照搬 iOS Features/Duty/DutyFlowKit.swift（契约 §17.1 图格式、§17.2 节点目录、§17.3 接口）。
//
// ⚠️ 纯 Kotlin，不 import android.*：拓扑排序与分支标注、配置摘要、FlowRun 解析与就地并入都是「错了不报错、只是画错」的那一类，
// 用例在 app/src/test/.../duty/DutyFlowKitTest.kt（照抄 iOS Tools/swifttests/DutyFlowKitTests.swift）。
//
// 手机上不做图编辑（契约 §17.4：手机上编辑节点图不现实）：这里只有「读」—— 列表、只读看节点、一键跑、看运行。
package com.geek.umbra.feature.duty

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.Locale

// MARK: - 图（§17.1）

/** 一个节点。`x` / `y` 是 PC 画布上的坐标 —— 手机上不画画布，解出来只为不丢。配置随节点类型走，用任意 JSON 收。 */
data class DutyFlowNode(
    val id: String,
    val type: String,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val config: JsonElement = JsonObject(emptyMap()),
) {
    companion object {
        fun from(c: JsonObject) = DutyFlowNode(
            id = c.looseString("id"), type = c.looseString("type"),
            x = c["x"].strictDouble() ?: 0.0, y = c["y"].strictDouble() ?: 0.0,
            config = c["config"] ?: JsonObject(emptyMap()),
        )
    }
}

/** 一条连线（同 PC `WFConn`）。`fromPort` = "" 默认出口 / "yes" / "no"。 */
data class DutyFlowConn(val from: String, val to: String, val fromPort: String = "") {
    companion object {
        fun from(c: JsonObject) = DutyFlowConn(c.looseString("from"), c.looseString("to"), c.looseString("fromPort"))
    }
}

data class DutyFlowGraph(val nodes: List<DutyFlowNode> = emptyList(), val connections: List<DutyFlowConn> = emptyList()) {
    companion object {
        /** 没有 id 的节点 / 两头缺一头的线丢掉：它们连不进图，留着只会让排序多出一个「谁都不认识」的点。 */
        fun from(c: JsonObject) = DutyFlowGraph(
            nodes = (c["nodes"].objects { DutyFlowNode.from(it) } ?: emptyList()).filter { it.id.isNotEmpty() },
            connections = (c["connections"].objects { DutyFlowConn.from(it) } ?: emptyList()).filter { it.from.isNotEmpty() && it.to.isNotEmpty() },
        )
    }
}

// MARK: - 流程（§17.3 `Flow`）

/** `used_by` 的一项：哪条执勤引用了这个流程。 */
data class DutyFlowUser(val duty_id: String, val name: String) {
    companion object {
        fun from(c: JsonObject) = DutyFlowUser(c.looseString("duty_id"), c.looseString("name"))
    }
}

/** `last_run`：上一次结果（列表上那一格）。 */
data class DutyFlowLastRun(val id: String, val status: String, val at_ms: Long) {
    companion object {
        fun from(c: JsonObject) = DutyFlowLastRun(c.looseString("id"), c.looseString("status"), c.looseLong("at_ms") ?: 0)
    }
}

data class DutyFlow(
    val id: String,
    val name: String = "",
    /** 内置早报流程是 `morning_brief`；用户自建的为空。 */
    val preset: String = "",
    /** 内置的只读。手机本来就不改，这一格只决定「内置」那个小标。 */
    val readonly: Boolean = false,
    val created_by: String = "user",
    /** 只有 `GET /duties/flows/{id}` 带；列表里是 null。 */
    val graph: DutyFlowGraph? = null,
    val node_count: Int = 0,
    val ai_nodes: Int = 0,
    /** 约多少点 / 次。 */
    val est_points: Int = 0,
    /** 如果每天跑一次，约占周额度的百分之几。 */
    val est_weekly_pct_if_daily: Double = 0.0,
    val used_by: List<DutyFlowUser> = emptyList(),
    val last_run: DutyFlowLastRun? = null,
    val created_at_ms: Long = 0,
    val updated_at_ms: Long = 0,
) {
    /** 内置（早报）：有 preset 或只读的都算 —— 少给一样也不至于把内置的当成用户的。 */
    val isBuiltin: Boolean get() = preset.isNotEmpty() || readonly

    companion object {
        fun from(c: JsonObject): DutyFlow {
            val graph = (c["graph"] as? JsonObject)?.let { DutyFlowGraph.from(it) }
            // `null` / 没 id 的都当「还没跑过」。
            val lr = (c["last_run"] as? JsonObject)?.let { DutyFlowLastRun.from(it) }?.takeIf { it.id.isNotEmpty() }
            return DutyFlow(
                id = c.looseString("id"), name = c.looseString("name"), preset = c.looseString("preset"),
                readonly = c.looseBool("readonly") ?: false, created_by = c.optString("created_by") ?: "user",
                graph = graph,
                // 列表里没带 graph 时 node_count 是唯一的节点数来源；带了 graph 而 node_count 缺，就自己数。
                node_count = c.looseInt("node_count") ?: graph?.nodes?.size ?: 0,
                ai_nodes = c.looseInt("ai_nodes") ?: 0,
                est_points = c.looseInt("est_points") ?: 0,
                est_weekly_pct_if_daily = c["est_weekly_pct_if_daily"].strictDouble() ?: 0.0,
                used_by = (c["used_by"].objects { DutyFlowUser.from(it) } ?: emptyList()).filter { it.duty_id.isNotEmpty() },
                last_run = lr,
                created_at_ms = c.looseLong("created_at_ms") ?: 0,
                updated_at_ms = c.looseLong("updated_at_ms") ?: 0,
            )
        }
    }
}

data class DutyFlowsDTO(val items: List<DutyFlow>) {
    companion object {
        fun from(c: JsonObject) = DutyFlowsDTO((c["items"].objects { DutyFlow.from(it) } ?: emptyList()).filter { it.id.isNotEmpty() })
    }
}

/** `POST /duties/flows/{id}/run` 的回包。 */
data class DutyFlowRunStartDTO(val ok: Boolean, val run_id: String) {
    companion object {
        fun from(c: JsonObject) = DutyFlowRunStartDTO(c.looseBool("ok") ?: true, c.looseString("run_id"))
    }
}

// MARK: - 运行（§17.3 `FlowRun`）

/** 一个节点这一次跑得怎样。 */
data class DutyFlowStep(
    val node_id: String,
    val type: String = "",
    /** ok / failed / skipped（走不到的分支上的节点记 skipped） */
    val status: String = "ok",
    val ms: Int = 0,
    val points: Int = 0,
    val out_summary: String = "",
    val error: String = "",
) {
    companion object {
        fun from(c: JsonObject) = DutyFlowStep(
            node_id = c.looseString("node_id"), type = c.looseString("type"), status = c.looseString("status"),
            ms = c.looseInt("ms") ?: 0, points = c.looseInt("points") ?: 0,
            out_summary = c.looseString("out_summary"), error = c.looseString("error"),
        )
    }
}

data class DutyFlowRun(
    val id: String,
    val flow_id: String = "",
    /** 由执勤班次触发的那一班；一键跑的为空。 */
    val shift_id: String = "",
    /** running / ok / failed / skipped */
    val status: String = "running",
    val points: Int = 0,
    val started_at_ms: Long = 0,
    /** 还在跑时为 null。 */
    val ended_at_ms: Long? = null,
    /** 只有 `GET /duties/flows/runs/{run_id}` 带；列表与 WS 里是 null（**null ≠ 空列表**：并入时 null 不许冲掉已拉到的步骤）。 */
    val steps: List<DutyFlowStep>? = null,
) {
    val finished: Boolean get() = status != "running"

    companion object {
        fun from(c: JsonObject) = DutyFlowRun(
            id = c.looseString("id"), flow_id = c.looseString("flow_id"), shift_id = c.looseString("shift_id"),
            status = c.optString("status") ?: "running", points = c.looseInt("points") ?: 0,
            started_at_ms = c.looseLong("started_at_ms") ?: 0,
            ended_at_ms = c.looseLong("ended_at_ms")?.takeIf { it > 0 },
            // Swift `try? decodeIfPresent([Step].self)`：不是数组（含 null）→ nil
            steps = (c["steps"] as? JsonArray)?.let { a -> if (a.all { it is JsonObject }) a.map { DutyFlowStep.from(it as JsonObject) } else null },
        )

        /** WS 帧 `{"type": "duty_flow_run", "run": …}` 的 `run` 那一段 → 一次运行；认不出 / 没 id → null。 */
        fun parse(json: JsonElement?): DutyFlowRun? = (json as? JsonObject)?.let { from(it) }?.takeIf { it.id.isNotEmpty() }
    }
}

data class DutyFlowRunsDTO(val items: List<DutyFlowRun>) {
    companion object {
        fun from(c: JsonObject) = DutyFlowRunsDTO((c["items"].objects { DutyFlowRun.from(it) } ?: emptyList()).filter { it.id.isNotEmpty() })
    }
}

// MARK: - 节点目录（§17.2）

data class DutyFlowOption(val value: String, val label: String) {
    companion object {
        fun from(c: JsonObject) = DutyFlowOption(c.looseString("value"), c.looseString("label"))
    }
}

/** 配置表单的一格。手机不出表单，只用它把节点的 `config` 说成人话（标签、选项的名字）。 */
data class DutyFlowField(
    val key: String,
    val label: String,
    /** text / textarea / number / bool / select / city / multiselect */
    val kind: String = "text",
    val options: List<DutyFlowOption> = emptyList(),
    val required: Boolean = false,
) {
    companion object {
        fun from(c: JsonObject) = DutyFlowField(
            key = c.looseString("key"), label = c.looseString("label"), kind = c.optString("kind") ?: "text",
            options = c["options"].objects { DutyFlowOption.from(it) } ?: emptyList(),
            required = c.looseBool("required") ?: false,
        )
    }
}

data class DutyFlowNodeType(
    val type: String,
    /** collect / process / ai / output / record / device */
    val group: String = "",
    val name: String = "",
    val desc: String = "",
    /** [""] 或 ["yes", "no"] */
    val ports: List<String> = listOf(""),
    val ai: Boolean = false,
    val est_points: Int = 0,
    val config: List<DutyFlowField> = emptyList(),
) {
    companion object {
        fun from(c: JsonObject) = DutyFlowNodeType(
            type = c.looseString("type"), group = c.looseString("group"), name = c.looseString("name"), desc = c.looseString("desc"),
            ports = c["ports"].strictStringList() ?: listOf(""), ai = c.looseBool("ai") ?: false,
            est_points = c.looseInt("est_points") ?: 0,
            config = (c["config"].objects { DutyFlowField.from(it) } ?: emptyList()).filter { it.key.isNotEmpty() },
        )
    }
}

data class DutyFlowCatalog(val items: List<DutyFlowNodeType>) {
    fun item(type: String): DutyFlowNodeType? = items.firstOrNull { it.type == type }

    companion object {
        fun from(c: JsonObject) = DutyFlowCatalog((c["items"].objects { DutyFlowNodeType.from(it) } ?: emptyList()).filter { it.type.isNotEmpty() })
    }
}

// MARK: - 纯判据

object DutyFlowKit {

    /** 节点目录里认得的组（§17.2），文案 key 是 `duty.flow.group.<group>`；认不出的组不画组名。 */
    val groups = listOf("collect", "process", "ai", "output", "record", "device")

    /** 运行 / 步骤的状态（§17.3）；认不出的（服务端以后加的）归 `other`。 */
    val statuses = listOf("running", "ok", "failed", "skipped")

    fun statusKey(s: String): String = "duty.flow.status.${if (s in statuses) s else "other"}"

    // MARK: 只读看节点：按连线顺序的拓扑序 + 分支标注

    /** 竖排列表里的一行。 */
    data class OrderedNode(
        val node: DutyFlowNode,
        /** 从 1 数的序号（列表上的「1.」「2.」）。 */
        val index: Int,
        /** 从哪些分支口进来的（上游是 yes / no 出口）。默认出口进来的不在这里。 */
        val branches: List<Branch>,
        /** 在环上（图本该无环；服务端校验漏了 / 老数据）—— 排不进拓扑序，按原顺序追在最后，标一下。 */
        val inCycle: Boolean,
    )

    data class Branch(
        /** 上游节点 id。 */
        val from: String,
        /** "yes" / "no" */
        val port: String,
    )

    /**
     * 拓扑排序（Kahn）：入度 0 的起点按 nodes 里的顺序先排；排出一个点之后，按 connections 里的顺序看它的下游，
     * 入度减到 0 的依次排进队尾 —— 这就是「按连线顺序」：同一层里谁先接上就谁先列，和 PC 画布上摆在哪儿无关
     * （手机上没有画布可对照，坐标排序反而会让两个人看到的顺序不同）。
     * 指向不存在节点的线忽略；环上的点按原顺序追在最后、inCycle = true。
     */
    fun order(g: DutyFlowGraph): List<OrderedNode> {
        val ids = g.nodes.map { it.id }.toSet()
        val conns = g.connections.filter { it.from in ids && it.to in ids && it.from != it.to }
        val indeg = HashMap<String, Int>()
        for (n in g.nodes) indeg[n.id] = 0
        for (c in conns) indeg[c.to] = (indeg[c.to] ?: 0) + 1
        val queue = g.nodes.filter { indeg[it.id] == 0 }.map { it.id }.toMutableList()
        val seen = HashSet<String>()
        val out = mutableListOf<String>()
        var head = 0
        while (head < queue.size) {
            val id = queue[head]; head++
            if (!seen.add(id)) continue
            out += id
            for (c in conns) {
                if (c.from != id) continue
                indeg[c.to] = (indeg[c.to] ?: 0) - 1
                if (indeg[c.to] == 0) queue += c.to
            }
        }
        val byId = LinkedHashMap<String, DutyFlowNode>()
        for (n in g.nodes) byId.putIfAbsent(n.id, n)
        val cyclic = g.nodes.map { it.id }.filter { it !in seen }
        val seenCycle = HashSet<String>()
        val all = out.map { it to false } + cyclic.filter { seenCycle.add(it) }.map { it to true }
        return all.mapIndexedNotNull { i, (id, cyc) ->
            val n = byId[id] ?: return@mapIndexedNotNull null
            val br = conns.filter { it.to == n.id && (it.fromPort == "yes" || it.fromPort == "no") }.map { Branch(it.from, it.fromPort) }
            OrderedNode(n, i + 1, br, cyc)
        }
    }

    /** 节点的显示名：目录里的名字，目录没拉到 / 不认识这个类型就退回类型名本身。 */
    fun nodeName(type: String, catalog: DutyFlowCatalog?): String = catalog?.item(type)?.name?.ifEmpty { null } ?: type

    /** 分支那一句：「「有新内容吗」为是时」。多个分支口进来（少见）用分隔符接。 */
    fun branchText(branches: List<Branch>, graph: DutyFlowGraph, catalog: DutyFlowCatalog?, tr: DutyTr): String =
        branches.joinToString(tr.k("duty.list.sep")) { b ->
            val type = graph.nodes.firstOrNull { it.id == b.from }?.type ?: b.from
            tr.k(if (b.port == "yes") "duty.flow.branch.yes" else "duty.flow.branch.no", nodeName(type, catalog))
        }

    // MARK: 配置摘要

    /** 一格配置值 → 一句短话；空值（空串、空数组、null）→ null（那种空是缺省，不是配置）。 */
    fun fieldValue(v: JsonElement?, field: DutyFlowField?, tr: DutyTr): String? {
        fun optionLabel(raw: String): String = field?.options?.firstOrNull { it.value == raw }?.label?.ifEmpty { null } ?: raw
        if (v.isNullish) return null
        if (v is JsonArray) {
            val xs = v.mapNotNull { it.str }.filter { it.isNotEmpty() }.map { optionLabel(it) }
            return if (xs.isEmpty()) null else DutyKit.clip(xs.joinToString(tr.k("duty.list.sep")), 40)
        }
        if (v is JsonObject) return null
        v.bool?.let { return tr.k(if (it) "duty.ctx.yes" else "duty.ctx.no") }
        val s = (v.str ?: "").trim()
        if (s.isEmpty()) return null
        if (field?.kind == "select") return optionLabel(s)
        // textarea（要求、提示词、模板）是一大段：摘要里只放开头。
        return DutyKit.clip(s, if (field?.kind == "textarea") 30 else 40)
    }

    /** 节点配置 → 「标签：值」的若干段。先按目录 Field 的顺序，再把目录里没有的键按键名追在后面。 */
    fun configLines(node: DutyFlowNode, catalog: DutyFlowCatalog?, tr: DutyTr): List<String> {
        val cfg = node.config as? JsonObject ?: return emptyList()
        val fields = catalog?.item(node.type)?.config ?: emptyList()
        val out = mutableListOf<String>()
        for (f in fields) {
            val t = fieldValue(cfg[f.key] ?: continue, f, tr) ?: continue
            out += tr.k("duty.flow.cfg.pair", f.label.ifEmpty { f.key }, t)
        }
        val known = fields.map { it.key }.toSet()
        for (k in cfg.keys.filter { it !in known }.sorted()) {
            val t = fieldValue(cfg[k], null, tr) ?: continue
            out += tr.k("duty.flow.cfg.pair", k, t)
        }
        return out
    }

    /** 列表上一行的配置摘要（几段用「 · 」接）；没有配置 → 空串。 */
    fun configSummary(node: DutyFlowNode, catalog: DutyFlowCatalog?, tr: DutyTr): String =
        configLines(node, catalog, tr).joinToString(" · ")

    // MARK: 列表上一行

    /** 约多少额度 / 次：不花额度的直说「不花额度」—— 「约 0 点」读起来像没估出来。 */
    fun costText(f: DutyFlow, tr: DutyTr): String =
        if (f.est_points <= 0) tr.k("duty.flow.cost.free") else tr.k("duty.flow.cost", f.est_points.toString())

    /** 列表一行的副文：「内置 · 5 个节点 · 约 12 点 / 次 · 被「早报」「盯新闻」用」。 */
    fun listSub(f: DutyFlow, tr: DutyTr): String {
        val parts = mutableListOf<String>()
        if (f.isBuiltin) parts += tr.k("duty.flow.builtin")
        parts += tr.k("duty.flow.nodes", f.node_count.toString())
        parts += costText(f, tr)
        parts += usedByText(f, tr)
        return parts.joinToString(" · ")
    }

    fun usedByText(f: DutyFlow, tr: DutyTr): String {
        val names = f.used_by.map { it.name.ifEmpty { tr.k("duty.edit.unnamed") } }
        if (names.isEmpty()) return tr.k("duty.flow.usedByNone")
        return tr.k("duty.flow.usedBy", names.joinToString(tr.k("duty.list.sep")) { tr.k("duty.flow.quoted", it) })
    }

    /** 列表的顺序：内置的在最前（那是每个人都有的那一条），其余保持服务端顺序。 */
    fun sortFlows(xs: List<DutyFlow>): List<DutyFlow> = xs.filter { it.isBuiltin } + xs.filter { !it.isBuiltin }

    /** 执勤编辑器「跑一个流程」能选哪些：只有用户流程（早报那条仍是 params 那套）。 */
    fun pickable(xs: List<DutyFlow>): List<DutyFlow> = xs.filter { !it.isBuiltin }

    // MARK: 运行

    /** 耗时 → 「1.2 秒」。没结束 / 认不出 → null。 */
    fun duration(r: DutyFlowRun, tr: DutyTr): String? {
        val end = r.ended_at_ms ?: return null
        if (r.started_at_ms <= 0 || end < r.started_at_ms) return null
        return secs((end - r.started_at_ms).toInt(), tr)
    }

    fun secs(ms: Int, tr: DutyTr): String = tr.k("duty.why.secs", String.format(Locale.ROOT, "%.1f", maxOf(0, ms) / 1000.0))

    /** 运行一行的副文：手动 / 执勤触发 · 用了几点 · 耗时。 */
    fun runSub(r: DutyFlowRun, tr: DutyTr): String {
        val parts = mutableListOf(tr.k(if (r.shift_id.isEmpty()) "duty.flow.run.manual" else "duty.flow.run.byDuty"))
        if (r.points > 0) parts += tr.k("duty.why.points", r.points.toString())
        duration(r, tr)?.let { parts += it }
        return parts.joinToString(" · ")
    }

    /**
     * 收到一次运行（WS `duty_flow_run`、或一键跑之后自己先占的那一行）时并进「最近运行」：
     * 同 id 就地替换 —— **新的没带 steps（WS 不带）时留着旧的 steps**，否则看着运行详情时一条 WS 进来步骤就全没了；
     * 新的插到最前，超出 [limit] 的截掉。
     */
    fun merge(run: DutyFlowRun, runs: List<DutyFlowRun>, limit: Int = 20): List<DutyFlowRun> {
        val out = runs.toMutableList()
        val i = out.indexOfFirst { it.id == run.id }
        if (i >= 0) out[i] = if (run.steps == null) run.copy(steps = out[i].steps) else run else out.add(0, run)
        return out.take(limit)
    }

    /**
     * 一次运行改了哪条流程的「上一次结果」：只改 flow_id 对得上的那条，而且只在它不比已有的旧时改
     * （WS 顺序不保证：先结束的旧一次后到，不许把列表上的新结果盖回旧的）。
     */
    fun applyLastRun(run: DutyFlowRun, flows: List<DutyFlow>): List<DutyFlow> = flows.map { f ->
        if (f.id != run.flow_id) return@map f
        val at = run.ended_at_ms ?: run.started_at_ms
        val cur = f.last_run
        if (cur != null && cur.id != run.id && cur.at_ms > at) return@map f
        f.copy(last_run = DutyFlowLastRun(run.id, run.status, at))
    }

    /** 运行详情里逐节点那一列的一行。step == null = 图里有、这次没记到。 */
    data class StepRow(val nodeId: String, val type: String, val step: DutyFlowStep?)

    /**
     * 按图的拓扑序列（和「节点」那一区同一个顺序）；图里有而步骤里没有的记 null；
     * 步骤里有而图里没有的（流程跑完后被改过）按步骤顺序追在后面。没有图就照步骤原顺序。
     */
    fun stepRows(graph: DutyFlowGraph?, steps: List<DutyFlowStep>): List<StepRow> {
        if (graph == null || graph.nodes.isEmpty()) return steps.map { StepRow(it.node_id, it.type, it) }
        val byNode = HashMap<String, DutyFlowStep>()
        for (s in steps) byNode[s.node_id] = s
        val ordered = order(graph)
        val rows = ordered.map { StepRow(it.node.id, it.node.type, byNode[it.node.id]) }.toMutableList()
        val inGraph = ordered.map { it.node.id }.toSet()
        rows += steps.filter { it.node_id !in inGraph }.map { StepRow(it.node_id, it.type, it) }
        return rows
    }
}
