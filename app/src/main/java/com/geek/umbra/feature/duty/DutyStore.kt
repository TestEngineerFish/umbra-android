// 执勤页的数据与动作 —— 照搬 iOS Features/Duty/DutyStore.swift（契约 §15.2 / §16 / §17）。页面只画，所有请求、WS 事件的就地刷新都收在这里。
//
// 为什么是进程级单例：`duty_updated` / `duty_shift` 从聊天长连接进来，那一层拿不到页面的状态；
// 单例让「页开着就地刷新、页没开就只记一笔下次进来重拉」只有一处判断（iOS 同一句）。
package com.geek.umbra.feature.duty

import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.net.BackgroundWork
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 整页状态一份不可变快照（页面 `collectAsState` 一处）。字段与 iOS DutyStore 的 @Published 一一对应。
 * 「null = 还没读到 / 老服务端没有这条（那一区就不画）」与「空 = 读到了、确实没有」严格分开。
 */
data class DutyState(
    /** `GET /duties` 的整份。null = 还没读到（首次进页 / 读失败）。 */
    val list: DutyListDTO? = null,
    /** 最近 14 天的班次，新的在前。 */
    val log: List<DutyShift> = emptyList(),
    /** 上一次读列表失败的原话（空串 = 没原话，页面兜一句「这次没读到」）；null = 没失败。 */
    val loadError: String? = null,
    val loading: Boolean = false,
    /** 有写操作在途（开关、暂停、试一次）—— 页面据此不让连点。 */
    val busy: Boolean = false,
    /** 关注列表。null = 还没读到 / 老服务端没有这条。 */
    val follows: List<DutyFollow>? = null,
    /** 预设模板（点「新建」时才拉，拉过就留着）。 */
    val presets: List<DutyPreset> = emptyList(),
    /** 「全部日志」页那一段（按条目筛过的）；和执勤页那 14 天分开放 —— 筛选不该把执勤页的日志也筛掉。 */
    val fullLog: List<DutyShift> = emptyList(),
    val fullLogError: String? = null,
    val fullLogLoading: Boolean = false,
    /** 聊天里执勤卡的就地状态（只在这次进程里记：服务端是准的，这几格只为「点完立刻看得见点过了」）。键 = DutyKit.cardKey。 */
    val cardFollowed: Map<String, String> = emptyMap(),
    val cardNotInterested: Set<String> = emptySet(),
    /** 一班给过的反馈（up / mute）：卡底那三颗钮换成一句「已记下」。 */
    val shiftFeedback: Map<String, String> = emptyMap(),
    /** 「还关注吗？」答过的：关注 id → active / ended。 */
    val followAnswered: Map<String, String> = emptyMap(),
    /** 额度用完那一行上的重置卡已用掉的（道具 id）。 */
    val usedCards: Set<Long> = emptySet(),
    /** 正在用的重置卡（按钮变「正在使用…」）。iOS 是视图里的 @State；Compose 里卡片随列表回收，放进 Store 才不丢。 */
    val usingCards: Set<Long> = emptySet(),
    /** 流程列表。null = 还没读到 / 老服务端没有这条（那一区就不画、编辑器不给「跑一个流程」）。 */
    val flows: List<DutyFlow>? = null,
    /** 带 graph 的那一份（流程页进来才拉），按流程 id 存。 */
    val flowDetail: Map<String, DutyFlow> = emptyMap(),
    /** 每个流程的最近运行（新的在前，不带 steps）。 */
    val flowRuns: Map<String, List<DutyFlowRun>> = emptyMap(),
    /** 带 steps 的运行（运行详情页），按 run id 存。 */
    val runDetail: Map<String, DutyFlowRun> = emptyMap(),
    /** 节点目录（拉一次就留着）。null = 没拉到 —— 节点名退回类型名。 */
    val catalog: DutyFlowCatalog? = null,
    /** 秘书的提议。null = 还没读到 / 老服务端没有这条；[] = 暂时没有。 */
    val proposals: List<DutyProposal>? = null,
    /** 提议那一区有一下在途：那一区的钮一起不让连点（不用 busy：「现在看一遍」要好几秒，别的开关不该跟着点不动）。 */
    val proposalBusy: Boolean = false,
    /**
     * 「现在看一遍」在跑（钮上「正在看…」+ 旋转弧）。和 [proposalBusy] 分开：点「加上」那一下也置 proposalBusy，
     * 那一两百毫秒里区头的钮不该跟着变成「正在看…」—— 人会以为自己点到了别的。iOS DutyStore.swift:64
     */
    val scanning: Boolean = false,
    /**
     * 点过、还在「撤销」那几秒里的提议（批次 261 §二：点完任何一颗都出吐司带「撤销」）。卡先收起，请求等窗口过了才发：
     * 服务端没有「撤回一次采纳 / 拒绝」的接口，「不用」更是落了库就 30 天不再提 —— 先发再撤做不到，只能晚发。
     */
    val heldProposals: Set<String> = emptySet(),
    /** 正在一键跑的流程（按 id）：按钮变「正在跑…」、不许连点。 */
    val flowStarting: Set<String> = emptySet(),
)

object DutyStore {
    private val _state = MutableStateFlow(DutyState())
    val state: StateFlow<DutyState> = _state
    private val s: DutyState get() = _state.value
    private val scope get() = AppGraph.scope

    /** 执勤页开着几个（推进去、再推一层又回来时会有短暂的 2）。0 = 没开：WS 事件只记「旧了」，不发请求。 */
    private var visible = 0
    /** 页没开时收到过刷新信号：下次进页一定重拉（反正进页就会拉，这一格只为说清楚这件事）。 */
    private var stale = false
    /** 流程页 / 运行详情页开着几个（它们不调 appear()：进一个流程页不该把整张执勤表和日志重拉一遍）。 */
    private var flowVisible = 0

    private inline fun set(f: (DutyState) -> DutyState) = _state.update(f)

    /** 守卫「关」：换号 / 登出时上一个人的执勤设置不许留在屏上。目录不是哪个人的数据，但换了服务端它可能不一样 —— 一起清。 */
    fun clear() {
        _state.value = DutyState()
    }

    // MARK: 页面生命周期

    fun appear() {
        visible += 1
        stale = false
        scope.launch { reload() }
    }

    fun disappear() { visible = maxOf(0, visible - 1) }

    /** 列表、日志、关注、流程、提议一起拉（互不依赖，并发）。 */
    suspend fun reload() = coroutineScope {
        set { it.copy(loading = true) }
        val l = async { DutyApi.fetchDuties() }
        val g = async { DutyApi.fetchLog(14) }
        val f = async { DutyApi.fetchFollows() }
        val fl = async { DutyApi.fetchFlows() }
        val pr = async { DutyApi.fetchProposals() }
        when (val r = l.await()) {
            is DutyRes.Ok -> set { it.copy(list = r.value, loadError = null) }
            is DutyRes.Failed -> set { it.copy(loadError = r.why) }
        }
        // 日志读不到不影响上半页；留着旧的，比清成「没有记录」诚实。关注 / 流程 / 提议同理：读不到留旧的。
        (g.await() as? DutyRes.Ok)?.let { r -> set { it.copy(log = r.value.items) } }
        (f.await() as? DutyRes.Ok)?.let { r -> set { it.copy(follows = r.value.items) } }
        (fl.await() as? DutyRes.Ok)?.let { r -> set { it.copy(flows = r.value.items) } }
        (pr.await() as? DutyRes.Ok)?.let { r -> set { it.copy(proposals = r.value.items) } }
        set { it.copy(loading = false) }
    }

    private suspend fun reloadProposals() {
        (DutyApi.fetchProposals() as? DutyRes.Ok)?.let { r -> set { it.copy(proposals = r.value.items) } }
    }

    private suspend fun reloadFollows() {
        (DutyApi.fetchFollows() as? DutyRes.Ok)?.let { r -> set { it.copy(follows = r.value.items) } }
    }

    private suspend fun reloadListOnly() {
        (DutyApi.fetchDuties() as? DutyRes.Ok)?.let { r -> set { it.copy(list = r.value, loadError = null) } }
    }

    // MARK: WS 事件（DutyModule 从 AppGraph.socket.frames 转进来；这些重拉是后台性质，不带「人引起的」头）

    /** 长连接里一帧。按 type 分给下面几个 handle。 */
    fun handleFrame(o: JsonObject) {
        when (o["type"].strictString()) {
            // 执勤条目或设置变了（别的端改的、秘书改的、到点算了下一次）→ 重拉 GET /duties
            "duty_updated" -> handleUpdated()
            "duty_follows_updated" -> handleFollowsUpdated()
            "duty_proposals_updated" -> handleProposalsUpdated()
            "duty_flow_run" -> handleFlowRun(o["run"])
            "duty_flows_updated" -> handleFlowsUpdated()
            "duty_shift" -> handleShift(o["shift"])
        }
    }

    private fun bg(block: suspend () -> Unit) {
        scope.launch { withContext(BackgroundWork) { block() } }
    }

    fun handleUpdated() {
        if (visible <= 0) { stale = true; return }
        bg { reloadListOnly() }
    }

    /** `duty_shift`：一个班次结案 → 日志就地并一行；条目卡上的「上一次 / 下一次」跟着变，重拉列表。 */
    fun handleShift(json: kotlinx.serialization.json.JsonElement?) {
        val shift = DutyShift.parse(json) ?: return
        set { st ->
            st.copy(
                log = DutyRules.merge(shift, st.log),
                fullLog = if (st.fullLog.any { it.id == shift.id }) DutyRules.merge(shift, st.fullLog) else st.fullLog,
            )
        }
        if (visible <= 0) { stale = true; return }
        bg { reloadListOnly() }
    }

    /** `duty_follows_updated`：关注变了 → 执勤页开着就重拉关注那一区。 */
    fun handleFollowsUpdated() {
        if (visible <= 0) { stale = true; return }
        bg { reloadFollows() }
    }

    /** `duty_proposals_updated`（M2）：提议变了 → 执勤页开着就重拉那一区。 */
    fun handleProposalsUpdated() {
        if (visible <= 0) { stale = true; return }
        bg { reloadProposals() }
    }

    // MARK: M2：秘书的提议（只提议、点头才生效）

    /** 跑一下提议那一区的写操作：在途时置 proposalBusy；已在途时回 null 什么都不做（不吐司）。 */
    private suspend fun <T> proposalWrite(op: suspend () -> DutyRes<T>): DutyRes<T>? {
        if (s.proposalBusy) return null
        set { it.copy(proposalBusy = true) }
        try { return op() } finally { set { it.copy(proposalBusy = false) } }
    }

    /**
     * 「好」。成功先把这一条从屏上拿掉（WS 那一推到之前不让它还挂着、被再点一次），再整页重拉 ——
     * 一个提议能改条目 / 设置 / 流程 / 关注里的任一样，挑着并迟早漏一样。
     * 回 null = 成功（或在途时连点）；空串 = 失败但没原话。
     */
    suspend fun acceptProposal(p: DutyProposal, schedule: Boolean): String? {
        val r = proposalWrite { DutyApi.acceptProposal(p.id, schedule) } ?: return null
        return when (r) {
            is DutyRes.Ok -> {
                set { st -> st.copy(proposals = st.proposals?.filter { it.id != p.id }) }
                reload(); null
            }
            // 404（已经定过）、409 gone：屏上那一条已经是过期的，重拉一次让它消失。
            is DutyRes.Failed -> { reloadProposals(); r.why }
        }
    }

    /** 「不用」：同一件事 30 天内不再提。只改提议那一区。 */
    suspend fun rejectProposal(p: DutyProposal): String? {
        val r = proposalWrite { DutyApi.rejectProposal(p.id) } ?: return null
        return when (r) {
            is DutyRes.Ok -> { set { st -> st.copy(proposals = st.proposals?.filter { it.id != p.id }) }; null }
            is DutyRes.Failed -> { reloadProposals(); r.why }
        }
    }

    /** 撤销窗口里挂着的那几下（按提议 id）。 */
    private val holdJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    /** 撤销窗口：比吐司停留（带动作 3 秒，`Router.showToast`）多 0.2 秒，吐司收起之前点「撤销」一定来得及。iOS DutyStore.swift:70 */
    const val UNDO_WINDOW_MS = 3_200L

    /**
     * 先收起一张提议、过了撤销窗口再真发（[commit] 就是 [acceptProposal] / [rejectProposal]）。iOS DutyStore.swift:224
     * 窗口里点了「撤销」（[releaseProposal]）就什么都不发、卡原样回来。发失败也把卡放回来，[onFail] 吐那句原话。
     * 挂在单例（AppGraph.scope）上而不是卡片上：人点完就返回「我」页时卡已经没了，这一下照样要发出去。
     * 两张连着点时第二张可能撞上第一张的在途（[proposalWrite] 在途回 null = 什么都没做）—— 先等它落地再发，不然那一下被静默吞掉。
     */
    fun holdProposal(id: String, commit: suspend () -> String?, onFail: (String) -> Unit) {
        set { it.copy(heldProposals = it.heldProposals + id) }
        holdJobs[id]?.cancel()
        holdJobs[id] = scope.launch {
            kotlinx.coroutines.delay(UNDO_WINDOW_MS)
            if (id !in s.heldProposals) return@launch
            while (s.proposalBusy) kotlinx.coroutines.delay(150)
            val err = commit()
            set { it.copy(heldProposals = it.heldProposals - id) }
            holdJobs.remove(id)
            if (err != null) onFail(err)
        }
    }

    /** 「撤销」：窗口还没过就取消那一下，卡回到原位。窗口已过（请求已发）时什么都不做 —— 吐司那时也已经收了。 */
    fun releaseProposal(id: String) {
        holdJobs.remove(id)?.cancel()
        set { it.copy(heldProposals = it.heldProposals - id) }
    }

    /** 「现在看一遍」→ 新冒出来几条（Ok）或失败原话（Failed）。在途时连点 → null。 */
    suspend fun scanProposals(): DutyRes<Int>? {
        if (s.scanning) return null
        set { it.copy(scanning = true) }
        try {
            while (s.proposalBusy) kotlinx.coroutines.delay(150)
            val r = proposalWrite { DutyApi.scanProposals() } ?: return null
            return when (r) {
                is DutyRes.Ok -> { reloadProposals(); DutyRes.Ok(r.value.added) }
                is DutyRes.Failed -> r
            }
        } finally {
            set { it.copy(scanning = false) }
        }
    }

    // MARK: M1b：流程（只读 + 一键跑）

    fun flowAppear() { flowVisible += 1 }
    fun flowDisappear() { flowVisible = maxOf(0, flowVisible - 1) }

    private val anyVisible: Boolean get() = visible > 0 || flowVisible > 0

    private suspend fun reloadFlows() {
        (DutyApi.fetchFlows() as? DutyRes.Ok)?.let { r -> set { it.copy(flows = r.value.items) } }
    }

    /** 目录拉一次就留着；失败不报（节点名退回类型名，页面照样能看）。 */
    suspend fun loadCatalog() {
        if (s.catalog != null) return
        (DutyApi.fetchCatalog() as? DutyRes.Ok)?.let { r -> set { it.copy(catalog = r.value) } }
    }

    /** 流程页进来：带 graph 的那份 + 最近运行，并发拉。失败回原话（只看详情那一条：运行列表读不到留旧的）。 */
    suspend fun loadFlow(id: String): String? = coroutineScope {
        val d = async { DutyApi.fetchFlow(id) }
        val r = async { DutyApi.fetchFlowRuns(id) }
        val c = async { loadCatalog() }
        c.await()
        (r.await() as? DutyRes.Ok)?.let { rr -> set { it.copy(flowRuns = it.flowRuns + (id to rr.value.items)) } }
        when (val dd = d.await()) {
            is DutyRes.Ok -> {
                val v = dd.value
                set { st ->
                    // 详情比列表新（刚拉的）：列表上那一行跟着换掉 graph 以外的字段，两处说的「上一次结果」一致。
                    st.copy(
                        flowDetail = st.flowDetail + (id to v),
                        flows = st.flows?.map { if (it.id == id) v.copy(graph = null) else it },
                    )
                }
                null
            }
            is DutyRes.Failed -> dd.why
        }
    }

    /** 运行详情（带 steps）。 */
    suspend fun loadRun(runId: String): String? = when (val r = DutyApi.fetchFlowRun(runId)) {
        is DutyRes.Ok -> {
            val v = r.value
            set { st ->
                st.copy(
                    runDetail = st.runDetail + (runId to v),
                    flowRuns = if (v.flow_id.isNotEmpty()) st.flowRuns + (v.flow_id to DutyFlowKit.merge(v, st.flowRuns[v.flow_id] ?: emptyList())) else st.flowRuns,
                )
            }
            null
        }
        is DutyRes.Failed -> r.why
    }

    /**
     * 一键跑。成功先在「最近运行」最前面占一行「正在跑」（拿 run_id 当身份）—— WS 那一条到了按同 id 就地替换；
     * 不占的话点完之后一直到跑完那几秒屏上什么都不变，人会以为没点上再点一次。
     */
    suspend fun runFlow(id: String): String? {
        if (id in s.flowStarting) return null
        set { it.copy(flowStarting = it.flowStarting + id) }
        try {
            return when (val r = DutyApi.runFlow(id)) {
                is DutyRes.Ok -> {
                    val runId = r.value.run_id
                    if (runId.isNotEmpty()) {
                        val placeholder = DutyFlowRun(id = runId, flow_id = id, status = "running", started_at_ms = System.currentTimeMillis())
                        set { st ->
                            val cur = st.flowRuns[id] ?: emptyList()
                            // WS 可能比回包先到（服务端后台起跑就推）：已经有这一行就不拿占位盖掉它。
                            if (cur.any { it.id == runId }) st else st.copy(flowRuns = st.flowRuns + (id to DutyFlowKit.merge(placeholder, cur)))
                        }
                    }
                    null
                }
                is DutyRes.Failed -> r.why
            }
        } finally {
            set { it.copy(flowStarting = it.flowStarting - id) }
        }
    }

    /**
     * `duty_flow_run`（WS，不带 steps）：并进那个流程的最近运行、改列表上的「上一次结果」；
     * 运行详情页正开着这一次、且它跑完了 → 重拉带 steps 的那份（WS 不带步骤，跑完那一刻的逐节点状态得自己取）。
     */
    fun handleFlowRun(json: kotlinx.serialization.json.JsonElement?) {
        val run = DutyFlowRun.parse(json) ?: return
        var refetch = false
        set { st ->
            var n = st
            if (run.flow_id.isNotEmpty()) {
                n = n.copy(
                    flowRuns = n.flowRuns + (run.flow_id to DutyFlowKit.merge(run, n.flowRuns[run.flow_id] ?: emptyList())),
                    flows = n.flows?.let { DutyFlowKit.applyLastRun(run, it) },
                    flowDetail = n.flowDetail[run.flow_id]?.let { d ->
                        n.flowDetail + (run.flow_id to (DutyFlowKit.applyLastRun(run, listOf(d)).firstOrNull() ?: d))
                    } ?: n.flowDetail,
                )
            }
            val cur = n.runDetail[run.id]
            if (cur != null) {
                n = n.copy(runDetail = n.runDetail + (run.id to run.copy(steps = run.steps ?: cur.steps)))
                if (run.finished && flowVisible > 0) refetch = true
            }
            n
        }
        if (refetch) bg { loadRun(run.id) }
    }

    /** `duty_flows_updated`：流程被增删改（多半是 PC 上）→ 重拉列表；开着的流程页的那份详情也重拉（节点可能变了）。 */
    fun handleFlowsUpdated() {
        if (!anyVisible) { stale = true; return }
        val open = if (flowVisible > 0) s.flowDetail.keys.toList() else emptyList()
        bg {
            reloadFlows()
            for (id in open) loadFlow(id)
        }
    }

    /** 流程 id → 名字（执勤条目动作摘要「跑流程：xxx」用）。找不到给空串（调用方退回不带名字的说法）。 */
    fun flowName(id: String, st: DutyState = s): String {
        if (id.isEmpty()) return ""
        return st.flows?.firstOrNull { it.id == id }?.name ?: ""
    }

    // MARK: 写

    /** 跑一个写操作：在途时置 busy，失败回那句话（空串交给调用方兜底）；成功回 null。 */
    private suspend fun <T> write(op: suspend () -> DutyRes<T>, apply: (T) -> Unit): DutyRes.Failed? {
        set { it.copy(busy = true) }
        try {
            return when (val r = op()) {
                is DutyRes.Ok -> { apply(r.value); null }
                is DutyRes.Failed -> r
            }
        } finally {
            set { it.copy(busy = false) }
        }
    }

    private fun adopt(settings: DutySettings) = set { st ->
        st.copy(list = st.list?.copy(settings = settings) ?: DutyListDTO(settings = settings))
    }

    private fun adopt(item: DutyItem) = set { st ->
        val l = st.list ?: return@set st
        val i = l.items.indexOfFirst { it.id == item.id }
        st.copy(list = l.copy(items = if (i >= 0) l.items.toMutableList().also { it[i] = item } else l.items + item))
    }

    /** 全局规矩任意子集（`PUT /duties/settings`）。 */
    suspend fun putSettings(patch: JsonObject): String? = write({ DutyApi.putSettings(patch) }, ::adopt)?.why

    /** 知情确认之后那一下：照契约写 `{consented: true, enabled: true}`。 */
    suspend fun consentAndEnable(): String? = putSettings(buildJsonObject { put("consented", true); put("enabled", true) })

    suspend fun pause(span: String): String? = write({ DutyApi.pause(span) }, ::adopt)?.why

    suspend fun resume(): String? = write({ DutyApi.resume() }, ::adopt)?.why

    /** 开早报。409 `duty_exists`（别的端刚开过）→ 不算失败：重拉一次，屏上就有那一条了。 */
    suspend fun createBrief(): String? {
        val err = write({ DutyApi.createBrief(DutyParams()) }, ::adopt)
        if (err != null && err.mentions("duty_exists")) { reloadListOnly(); return null }
        if (err == null) reloadListOnly()
        return err?.why
    }

    /** 改一条（`enabled` / `params` / `mode` / 整份草稿）。回包就是改完的那条，原地换掉。 */
    suspend fun patch(item: DutyItem, body: JsonObject): DutyRes.Failed? = write({ DutyApi.patchDuty(item.id, body) }, ::adopt)

    /** 改早报参数（整份 params 回写）。没变就不发。 */
    suspend fun patchParams(item: DutyItem, edit: (DutyParams) -> DutyParams): String? {
        val p = edit(item.params)
        if (p == item.params) return null
        return patch(item, buildJsonObject { put("params", p.json) })?.why
    }

    /** 试一次：服务端后台跑，结果走 WS（`duty_shift` 进日志、早报那条进聊天）。 */
    suspend fun run(item: DutyItem): String? = write({ DutyApi.runDuty(item.id) }) {}?.why

    // MARK: M1a：条目

    /** 预设拉一次就留着（几条，几乎不变）。 */
    suspend fun loadPresets(): String? {
        if (s.presets.isNotEmpty()) return null
        return when (val r = DutyApi.fetchPresets()) {
            is DutyRes.Ok -> { set { it.copy(presets = r.value.items) }; null }
            is DutyRes.Failed -> r.why
        }
    }

    /**
     * 用预设建一条（`{"preset": …}`，用预设缺省 —— 想改再点进去改）。
     * 不在端上拼「预设 + 覆盖字段」：`task_patrol` 服务端可能一次建两条，端上拼的覆盖字段对不上第二条。
     */
    suspend fun createFromPreset(key: String): String? {
        val err = write({ DutyApi.createDuty(buildJsonObject { put("preset", key) }) }, ::adopt)
        if (err == null) reloadListOnly()
        return err?.why
    }

    /** 「自己定」那一条。回失败（带码，编辑页要认 duty_limit / feature_unavailable）。 */
    suspend fun createCustom(draft: DutyDraft): DutyRes.Failed? {
        val err = write({ DutyApi.createDuty(draft.payload) }, ::adopt)
        if (err == null) reloadListOnly()
        return err
    }

    /** 改一条：整份回写（触发 / 动作 / 规矩一起），回包原地换掉。 */
    suspend fun save(item: DutyItem, draft: DutyDraft): DutyRes.Failed? = patch(item, draft.payload)

    suspend fun delete(item: DutyItem): DutyRes.Failed? {
        val err = write({ DutyApi.deleteDuty(item.id) }) {}
        if (err == null) set { st -> st.copy(list = st.list?.let { l -> l.copy(items = l.items.filter { it.id != item.id }) }) }
        return err
    }

    // MARK: M1a：日志与「为什么」

    /** 「全部日志」页：拉一段更长的、可按条目筛。90 天：契约没写日志留多久，再长服务端也未必有。 */
    suspend fun loadFullLog(dutyId: String?) {
        set { it.copy(fullLogLoading = true) }
        when (val r = DutyApi.fetchLog(90, dutyId)) {
            is DutyRes.Ok -> set { it.copy(fullLog = DutyKit.filter(r.value.items, dutyId), fullLogError = null) }
            is DutyRes.Failed -> set { it.copy(fullLogError = r.why) }
        }
        set { it.copy(fullLogLoading = false) }
    }

    /** 「为什么」的结果：行，或失败原话（[failed] 非 null 时 lines 无意义）。 */
    data class Why(val lines: List<String>, val failed: String?)

    /** 日志里的一班：有 decision_id 就取那次判断；没有（规则拦下的 / 到点就做的）只给理由。 */
    suspend fun whyLines(shift: DutyShift): Why = why(shift.id, shift.decision_id, shift.reason_text)

    /**
     * 聊天里执勤消息的「为什么现在发」：直接用 meta.duty.decision_id（空串 = 没判断）；
     * 空串时只显示理由 —— 理由在班次上，日志里有就拿那一句。
     */
    suspend fun whyLines(meta: DutyMeta): Why {
        val shift = (s.log + s.fullLog).firstOrNull { it.id == meta.shiftId }
        return why(meta.shiftId, meta.decisionId, shift?.reason_text ?: "")
    }

    private suspend fun why(shiftId: String, decisionId: String, reason: String): Why {
        val tr = dutyAppTr
        val ctx = AppGraph.context
        if (decisionId.isEmpty()) {
            val lines = mutableListOf<String>()
            if (reason.isNotEmpty()) lines += reason
            lines += ctx.getString(R.string.duty_why_noDecision)
            return Why(lines, null)
        }
        return when (val r = DutyApi.fetchDecision(decisionId)) {
            is DutyRes.Ok -> {
                val lines = DutyKit.decisionLines(r.value, shiftId, tr)
                Why(lines.ifEmpty { listOf(reason.ifEmpty { ctx.getString(R.string.duty_why_empty) }) }, null)
            }
            is DutyRes.Failed -> Why(emptyList(), r.why)
        }
    }

    // MARK: M1a：反馈与关注（聊天里执勤卡上的动作：不占 busy —— 执勤页的开关不该因为聊天里点了一下而点不动）

    /** 卡底「有用」/「别发这类了」。mute 会改条目本身（停掉 / 这周先别发），回包里的 Duty 原地换掉。 */
    suspend fun feedback(shiftId: String, verdict: String, mute: String?): String? =
        when (val r = DutyApi.feedback(shiftId, verdict, mute)) {
            is DutyRes.Ok -> {
                set { it.copy(shiftFeedback = it.shiftFeedback + (shiftId to verdict)) }
                r.value.duty?.let { adopt(it) }
                null
            }
            is DutyRes.Failed -> r.why
        }

    /** sourceId = DutyMeta.sourceId：班次 id，一键跑的卡用运行 id（服务端两种都认）。 */
    suspend fun notInterested(sourceId: String, itemId: String): String? =
        when (val r = DutyApi.notInterested(sourceId, itemId)) {
            is DutyRes.Ok -> { set { it.copy(cardNotInterested = it.cardNotInterested + DutyKit.cardKey(sourceId, itemId)) }; null }
            is DutyRes.Failed -> r.why
        }

    /** 早报卡上点「关注」：服务端用那一条起草话题与检索式。键名仍是 shift_id，值可以是运行 id。 */
    suspend fun follow(sourceId: String, itemId: String): String? {
        val body = buildJsonObject { putJsonObject("from") { put("shift_id", sourceId); put("item_id", itemId) } }
        return when (val r = DutyApi.createFollow(body)) {
            is DutyRes.Ok -> {
                set { it.copy(cardFollowed = it.cardFollowed + (DutyKit.cardKey(sourceId, itemId) to r.value.id)) }
                upsert(r.value); null
            }
            is DutyRes.Failed -> r.why
        }
    }

    /** 手动加一个话题（执勤页「关注」区）。 */
    suspend fun addFollow(topic: String): String? =
        when (val r = DutyApi.createFollow(buildJsonObject { put("topic", topic); put("kind", "news") })) {
            is DutyRes.Ok -> { upsert(r.value); null }
            is DutyRes.Failed -> r.why
        }

    /** 改重要度 / 静音 / 结束（PATCH 的字段任选）。 */
    suspend fun patchFollow(id: String, body: JsonObject): String? =
        when (val r = DutyApi.patchFollow(id, body)) {
            is DutyRes.Ok -> { upsert(r.value); null }
            is DutyRes.Failed -> r.why
        }

    suspend fun deleteFollow(id: String): String? =
        when (val r = DutyApi.deleteFollow(id)) {
            is DutyRes.Ok -> { set { st -> st.copy(follows = st.follows?.filter { it.id != id }) }; null }
            is DutyRes.Failed -> r.why
        }

    /** 「还关注吗？」：继续 = PATCH state=active（last_new_ms 由服务端自己刷）；结束 = state=ended。 */
    suspend fun answerFollowCheck(followId: String, keep: Boolean): String? {
        val state = if (keep) "active" else "ended"
        val err = patchFollow(followId, buildJsonObject { put("state", state) })
        if (err == null) set { it.copy(followAnswered = it.followAnswered + (followId to state)) }
        return err
    }

    private fun upsert(f: DutyFollow) = set { st ->
        val cur = st.follows ?: return@set st.copy(follows = listOf(f))
        val i = cur.indexOfFirst { it.id == f.id }
        st.copy(follows = if (i >= 0) cur.toMutableList().also { it[i] = f } else listOf(f) + cur)
    }

    // MARK: M1a：额度用完那一行的重置卡

    /** 复用商城的用卡调用（`POST /shop/items/{id}/use`）。失败回服务端原话（409：已用过 / 过期…）。 */
    suspend fun useResetCard(itemId: Long): String? {
        set { it.copy(usingCards = it.usingCards + itemId) }
        try {
            return when (val r = DutyApi.useShopItem(itemId)) {
                is DutyRes.Ok -> {
                    set { it.copy(usedCards = it.usedCards + itemId) }
                    // 额度回来了：执勤页的档位跟着变（开着就重拉，没开就等进页）。
                    if (visible > 0) reloadListOnly() else stale = true
                    null
                }
                is DutyRes.Failed -> r.why
            }
        } finally {
            set { it.copy(usingCards = it.usingCards - itemId) }
        }
    }
}
