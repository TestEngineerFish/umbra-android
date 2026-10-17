// 执勤 M1b「流程」的三处画法 —— 照搬 iOS Features/Duty/DutyFlowViews.swift（契约 §17.4）：
//   ① 执勤页「流程」区（[dutyFlowSection]）：每个流程一行 —— 名字、节点数、约多少额度 / 次、被哪些执勤引用、上一次结果；
//   ② 流程页（[DutyFlowView]）：一键跑 → 最近运行 → 节点（竖排：节点名 + 配置摘要，按连线顺序拓扑排序，分支标是 / 否）；
//   ③ 运行详情（[DutyFlowRunView]）：逐节点状态，out_summary / error。
//
// **手机上不做图编辑**：每一处都写一句「在电脑上编辑」，而不是给一颗点了没反应的「编辑」。
// 节点 / 步骤的完整内容点开用说明框看（同日志的「为什么」）：配置和 out_summary 都可能是一大段，就地展开会把列表顶出一屏。
package com.geek.umbra.feature.duty

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraColorScheme
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.navigation.Route

// MARK: - 执勤页上的「流程」区

internal fun dutyFlowSection(ctx: Context, tr: DutyTr, c: UmbraColorScheme, flows: List<DutyFlow>): DutySection {
    var rows = DutyFlowKit.sortFlows(flows).map { f ->
        DutyRow(
            f.name.ifEmpty { ctx.getString(R.string.duty_edit_unnamed) },
            sub = DutyFlowKit.listSub(f, tr),
            value = f.last_run?.let { tr.k(DutyFlowKit.statusKey(it.status)) } ?: ctx.getString(R.string.duty_flow_neverRun),
            valueTint = flowTint(c, f.last_run?.status), chevron = true,
        ) { AppGraph.router.go(Route.MeDutyFlow(f.id)) }
    }
    if (rows.isEmpty()) rows = listOf(DutyRow(ctx.getString(R.string.duty_flow_empty), tint = c.faint))
    return DutySection(
        "flows", header = ctx.getString(R.string.duty_flow), headerCount = if (flows.isEmpty()) null else flows.size,
        footer = ctx.getString(R.string.duty_flow_help), rows = rows,
    )
}

/** 结果的值色：同日志那一行（成了绿、失败红，其余灰）。 */
internal fun flowTint(c: UmbraColorScheme, status: String?): Color? = when (status) {
    "ok" -> c.success
    "failed" -> c.dangerText
    else -> null
}

private val pagePadding = PaddingValues(start = UmbraDimens.pagePadX, end = UmbraDimens.pagePadX, top = UmbraDimens.sp5, bottom = 28.dp)

// MARK: - 流程页

@Composable
internal fun DutyFlowView(id: String) {
    val st by DutyStore.state.collectAsState()
    val ctx = LocalContext.current
    val tr = rememberDutyTr()
    val c = UmbraTheme.colors
    var loadError by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        DutyStore.flowAppear()
        onDispose { DutyStore.flowDisappear() }
    }
    LaunchedEffect(id, tick) { loadError = DutyStore.loadFlow(id) }

    // 详情（带 graph）没拉到之前先用列表里那一份画上半页（名字、额度、引用），节点区说「正在读取」。
    val f = st.flowDetail[id] ?: st.flows?.firstOrNull { it.id == id }
    val title = f?.let { it.name.ifEmpty { ctx.getString(R.string.duty_edit_unnamed) } } ?: ctx.getString(R.string.duty_flow)
    UmbraScreen(title = title, onBack = { AppGraph.router.back() }, spacing = UmbraDimens.sp6, contentPadding = pagePadding) {
        when {
            f != null -> {
                item(key = "intro") { DutyIntro(ctx.getString(if (f.isBuiltin) R.string.duty_flow_builtinIntro else R.string.duty_flow_editOnPC)) }
                dutySection(summarySection(ctx, tr, f))
                val starting = f.id in st.flowStarting
                dutySection(DutySection(
                    "run", footer = ctx.getString(R.string.duty_flow_runHelp),
                    rows = listOf(DutyRow(ctx.getString(if (starting) R.string.duty_brief_running else R.string.duty_flow_run), tint = c.orangeText) { runFlow(ctx, f) }),
                ))
                dutySection(runsSection(ctx, tr, c, st.flowRuns[f.id] ?: emptyList()))
                dutySection(nodesSection(ctx, tr, c, f, st.catalog, loadError))
            }
            loadError != null -> dutySection(DutySection(
                "err", footer = loadError?.ifEmpty { ctx.getString(R.string.duty_loadFailed) }, footerTone = DutyFooterTone.Danger,
                rows = listOf(DutyRow(ctx.getString(R.string.duty_retry), tint = c.orangeText) { tick++ }),
            ))
            else -> item(key = "loading") { DutyIntro(ctx.getString(R.string.duty_loading)) }
        }
    }
}

private fun summarySection(ctx: Context, tr: DutyTr, f: DutyFlow): DutySection {
    val rows = mutableListOf(
        DutyRow(
            ctx.getString(R.string.duty_flow_nodeCount),
            value = if (f.ai_nodes > 0) ctx.getString(R.string.duty_flow_nodesAi, "${f.node_count}", "${f.ai_nodes}")
            else ctx.getString(R.string.duty_flow_nodes, "${f.node_count}"),
        ),
        DutyRow(ctx.getString(R.string.duty_flow_costLabel), value = DutyFlowKit.costText(f, tr)),
    )
    if (f.est_weekly_pct_if_daily > 0) rows += DutyRow(ctx.getString(R.string.duty_flow_weekly), value = DutyRules.pctText(f.est_weekly_pct_if_daily))
    rows += DutyRow(ctx.getString(R.string.duty_flow_usedByLabel), sub = DutyFlowKit.usedByText(f, tr))
    return DutySection("summary", rows = rows)
}

private fun runFlow(ctx: Context, f: DutyFlow) {
    if (f.id in DutyStore.state.value.flowStarting) return
    dutyLaunch {
        val e = DutyStore.runFlow(f.id)
        if (e != null) dutyReport(e) else AppGraph.router.showToast(ctx.getString(R.string.duty_flow_runSent))
    }
}

private fun runsSection(ctx: Context, tr: DutyTr, c: UmbraColorScheme, runs: List<DutyFlowRun>): DutySection {
    val rows = if (runs.isEmpty()) listOf(DutyRow(ctx.getString(R.string.duty_flow_runsEmpty), tint = c.faint))
    else runs.map { r ->
        DutyRow(
            DutyRules.monthDayTime(r.started_at_ms).ifEmpty { ctx.getString(R.string.duty_flow_run) },
            sub = DutyFlowKit.runSub(r, tr), value = tr.k(DutyFlowKit.statusKey(r.status)), valueTint = flowTint(c, r.status), chevron = true,
        ) { AppGraph.router.go(Route.MeDutyFlowRun(r.id)) }
    }
    return DutySection("runs", header = ctx.getString(R.string.duty_flow_runs), rows = rows)
}

// MARK: 节点（只读，竖排）

private fun nodesSection(ctx: Context, tr: DutyTr, c: UmbraColorScheme, f: DutyFlow, catalog: DutyFlowCatalog?, loadError: String?): DutySection {
    val g = f.graph ?: return DutySection(
        // 列表那一份没有 graph：详情还在路上（或读失败，失败的原话在组脚）。
        "nodes", header = ctx.getString(R.string.duty_flow_nodesHeader),
        footer = loadError?.ifEmpty { ctx.getString(R.string.duty_loadFailed) },
        footerTone = if (loadError == null) null else DutyFooterTone.Danger,
        rows = listOf(DutyRow(ctx.getString(R.string.duty_loading), tint = c.faint)),
    )
    val ordered = DutyFlowKit.order(g)
    val rows = if (ordered.isEmpty()) listOf(DutyRow(ctx.getString(R.string.duty_flow_noNodes), tint = c.faint))
    else ordered.map { o -> nodeRow(ctx, tr, o, g, catalog) }
    return DutySection(
        "nodes", header = ctx.getString(R.string.duty_flow_nodesHeader), headerCount = if (ordered.isEmpty()) null else ordered.size,
        footer = ctx.getString(R.string.duty_flow_nodesHelp), rows = rows,
    )
}

private fun nodeRow(ctx: Context, tr: DutyTr, o: DutyFlowKit.OrderedNode, graph: DutyFlowGraph, catalog: DutyFlowCatalog?): DutyRow {
    val name = DutyFlowKit.nodeName(o.node.type, catalog)
    val sub = mutableListOf<String>()
    val br = DutyFlowKit.branchText(o.branches, graph, catalog, tr)
    if (br.isNotEmpty()) sub += br
    val cfg = DutyFlowKit.configSummary(o.node, catalog, tr)
    if (cfg.isNotEmpty()) sub += cfg
    return DutyRow(
        "${o.index}. $name",
        sub = if (sub.isEmpty()) null else sub.joinToString(" · "),
        subWarn = if (o.inCycle) ctx.getString(R.string.duty_flow_inCycle) else null,
        value = groupLabel(tr, o.node.type, catalog), chevron = true,
    ) { showNode(ctx, tr, o, name, br, catalog) }
}

/** 节点所属的组（收集 / 处理 / AI / 输出 / 记录 / 设备）。目录没拉到或组认不出就不写 —— 不印 key。 */
private fun groupLabel(tr: DutyTr, type: String, catalog: DutyFlowCatalog?): String? {
    val g = catalog?.item(type)?.group ?: return null
    return if (g in DutyFlowKit.groups) tr.k("duty.flow.group.$g") else null
}

/** 点开一个节点：说明 + 分支 + 全部配置（一格一行）。 */
private fun showNode(ctx: Context, tr: DutyTr, o: DutyFlowKit.OrderedNode, name: String, branch: String, catalog: DutyFlowCatalog?) {
    val lines = mutableListOf<String>()
    catalog?.item(o.node.type)?.desc?.takeIf { it.isNotEmpty() }?.let { lines += it }
    if (branch.isNotEmpty()) lines += branch
    val cfg = DutyFlowKit.configLines(o.node, catalog, tr)
    lines += cfg.ifEmpty { listOf(ctx.getString(R.string.duty_flow_noConfig)) }
    lines += ctx.getString(R.string.duty_flow_editOnPCShort)
    dutyInfo(name, lines.joinToString("\n"))
}

// MARK: - 运行详情

@Composable
internal fun DutyFlowRunView(runId: String) {
    val st by DutyStore.state.collectAsState()
    val ctx = LocalContext.current
    val tr = rememberDutyTr()
    val c = UmbraTheme.colors
    var loadError by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        DutyStore.flowAppear()
        onDispose { DutyStore.flowDisappear() }
    }
    LaunchedEffect(runId, tick) {
        loadError = DutyStore.loadRun(runId)
        // 逐节点按图的顺序列：图在流程详情里，没拉过就补一次（失败无妨，退回步骤原顺序）。
        val fid = DutyStore.state.value.runDetail[runId]?.flow_id
        if (!fid.isNullOrEmpty() && DutyStore.state.value.flowDetail[fid] == null) DutyStore.loadFlow(fid)
    }

    val run = st.runDetail[runId] ?: st.flowRuns.values.asSequence().mapNotNull { rs -> rs.firstOrNull { it.id == runId } }.firstOrNull()
    val flowName = run?.flow_id?.let { fid -> st.flowDetail[fid]?.name ?: DutyStore.flowName(fid, st) } ?: ""
    UmbraScreen(title = ctx.getString(R.string.duty_flow_runTitle), onBack = { AppGraph.router.back() }, spacing = UmbraDimens.sp6, contentPadding = pagePadding) {
        when {
            run != null -> {
                dutySection(headSection(ctx, tr, c, run, flowName))
                dutySection(stepsSection(ctx, tr, c, run, st))
            }
            loadError != null -> dutySection(DutySection(
                "err", footer = loadError?.ifEmpty { ctx.getString(R.string.duty_loadFailed) }, footerTone = DutyFooterTone.Danger,
                rows = listOf(DutyRow(ctx.getString(R.string.duty_retry), tint = c.orangeText) { tick++ }),
            ))
            else -> item(key = "loading") { DutyIntro(ctx.getString(R.string.duty_loading)) }
        }
    }
}

private fun headSection(ctx: Context, tr: DutyTr, c: UmbraColorScheme, r: DutyFlowRun, flowName: String): DutySection {
    val rows = mutableListOf(
        DutyRow(ctx.getString(R.string.duty_flow_runStatus), value = tr.k(DutyFlowKit.statusKey(r.status)), valueTint = flowTint(c, r.status)),
    )
    if (flowName.isNotEmpty()) rows += DutyRow(ctx.getString(R.string.duty_edit_flow), value = flowName)
    if (r.started_at_ms > 0) rows += DutyRow(ctx.getString(R.string.duty_flow_runAt), value = DutyRules.monthDayTime(r.started_at_ms), mono = true)
    rows += DutyRow(ctx.getString(R.string.duty_flow_runSource), value = ctx.getString(if (r.shift_id.isEmpty()) R.string.duty_flow_run_manual else R.string.duty_flow_run_byDuty))
    if (r.points > 0) rows += DutyRow(ctx.getString(R.string.duty_flow_runPoints), value = ctx.getString(R.string.duty_why_points, "${r.points}"))
    DutyFlowKit.duration(r, tr)?.let { rows += DutyRow(ctx.getString(R.string.duty_flow_runDuration), value = it) }
    return DutySection("head", footer = if (r.finished) null else ctx.getString(R.string.duty_flow_runningHelp), rows = rows)
}

private fun stepsSection(ctx: Context, tr: DutyTr, c: UmbraColorScheme, r: DutyFlowRun, st: DutyState): DutySection {
    val graph = st.flowDetail[r.flow_id]?.graph
    val stepRows = DutyFlowKit.stepRows(graph, r.steps ?: emptyList())
    // 还没拉到带 steps 的那份（只有列表 / WS 那一行）：说「正在读取」，而不是「没有步骤」。
    if (r.steps == null && stepRows.isEmpty()) {
        return DutySection("steps", header = ctx.getString(R.string.duty_flow_steps), rows = listOf(DutyRow(ctx.getString(R.string.duty_loading), tint = c.faint)))
    }
    val rows = if (stepRows.isEmpty()) listOf(DutyRow(ctx.getString(R.string.duty_flow_noSteps), tint = c.faint))
    else stepRows.mapIndexed { i, row -> stepRow(ctx, tr, c, i + 1, row, !r.finished, st.catalog) }
    return DutySection("steps", header = ctx.getString(R.string.duty_flow_steps), footer = ctx.getString(R.string.duty_flow_stepsHelp), rows = rows)
}

private fun stepRow(ctx: Context, tr: DutyTr, c: UmbraColorScheme, n: Int, row: DutyFlowKit.StepRow, running: Boolean, catalog: DutyFlowCatalog?): DutyRow {
    val name = DutyFlowKit.nodeName(row.type, catalog)
    val s = row.step
        // 图里有、这次没记到：还在跑 = 没轮到；跑完了还没有 = 服务端没记（老数据），照实说「没有记录」。
        ?: return DutyRow("$n. $name", value = ctx.getString(if (running) R.string.duty_flow_status_waiting else R.string.duty_flow_status_none))
    val meta = mutableListOf<String>()
    if (s.ms > 0) meta += DutyFlowKit.secs(s.ms, tr)
    if (s.points > 0) meta += ctx.getString(R.string.duty_why_points, "${s.points}")
    val text = if (s.error.isEmpty()) s.out_summary else ""
    val sub = (listOf(DutyKit.clip(text, 80)).filter { it.isNotEmpty() } + meta).joinToString(" · ")
    val hasMore = !(s.out_summary.isEmpty() && s.error.isEmpty())
    return DutyRow(
        "$n. $name",
        sub = sub.ifEmpty { null },
        // 失败原因走「提醒」那一格（三角 + 警示色）：一眼要能从一列「成了」里挑出它。
        subWarn = if (s.error.isEmpty()) null else DutyKit.clip(s.error, 80),
        value = tr.k(DutyFlowKit.statusKey(s.status)), valueTint = flowTint(c, s.status),
        chevron = hasMore,
    ) {
        if (hasMore) {
            val lines = mutableListOf<String>()
            if (s.error.isNotEmpty()) lines += ctx.getString(R.string.duty_flow_stepError, s.error)
            if (s.out_summary.isNotEmpty()) lines += s.out_summary
            dutyInfo(name, lines.joinToString("\n\n"))
        }
    }
}
