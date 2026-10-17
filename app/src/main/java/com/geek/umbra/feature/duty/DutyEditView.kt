// 执勤通用编辑器 —— 照搬 iOS Features/Duty/DutyEditView.swift（契约 §16.8 第 1 条「自己定」，也用来改预设建出来的条目）：
//   名字 → 触发四选一（一次 / 周期 / 时间窗口 / 隔一段做一次（M2 巡视），周期里有「我通常上线的时候」）→ 动作（发一段话 / 建任务 /
//   跑一个例程（例程本身在电脑上编 —— 批次 263：执勤的「流程」定名「例程」，代码标识 flow 不改）；跑 PC 上的工作流置灰并写原因）→ 规矩（要不要秘书判断、优先级、冷却、每天最多几点、遵守静默）→ 保存。
//   删除在右上 ⋯。
//
// 文字（名字、要秘书说什么、任务目标）在弹层里填，同早报「城市」那一格 —— 这一页没有长文本框。
// 改的是一份本地草稿（DutyDraft），「保存」时整份回写；返回 = 不存（这一页算编辑页，跨 Tab 跳转会先问）。
package com.geek.umbra.feature.duty

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraColorScheme
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraMenuItem
import com.geek.umbra.designsystem.UmbraMoreMenu
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import java.time.LocalTime

/** 正在选哪一个时间。巡视（interval）的起止也走 WindowStart / WindowEnd：同两个字段（start_hhmm / end_hhmm）。 */
private enum class EditTimeField { Recur, WindowStart, WindowEnd }

/** 正在填哪一格文字。 */
private enum class TextSlot { Name, Brief, Goal }

@Composable
internal fun DutyEditView(id: String?) {
    val st by DutyStore.state.collectAsState()
    val ctx = LocalContext.current
    val tr = rememberDutyTr()
    val c = UmbraTheme.colors
    var draft by remember { mutableStateOf<DutyDraft?>(null) }
    var saving by remember { mutableStateOf(false) }
    var timeField by remember { mutableStateOf<EditTimeField?>(null) }
    var showWhen by remember { mutableStateOf(false) }

    val item = id?.let { i -> st.list?.items?.firstOrNull { it.id == i } }
    val route = Route.MeDutyEdit(id)
    val today = DutyRules.day(System.currentTimeMillis())

    // iOS onAppear：草稿只起一次（之后的改动都在草稿上，列表刷新不许冲掉正在改的内容）。
    LaunchedEffect(item != null) {
        if (draft == null) {
            if (item != null) draft = DutyDraft.of(item, today) else if (id == null) draft = DutyDraft.new(today)
        }
    }
    DutyPushHost()

    val ed = Editor(ctx, tr, c, st) { f -> draft?.let { draft = f(it) } }
    val d = draft
    UmbraScreen(
        title = ctx.getString(if (id == null) R.string.duty_edit_newTitle else R.string.duty_edit_title),
        onBack = { AppGraph.router.back() },
        actions = {
            // 破坏性动作进右上 ⋯（骨架 noDestructiveAtBottom），点了还要过确认弹窗。
            if (id != null && d != null) {
                UmbraMoreMenu(listOf(UmbraMenuItem(ctx.getString(R.string.duty_edit_delete), danger = true) { askDelete(ctx, item, route) }))
            }
        },
        spacing = UmbraDimens.sp6,
        contentPadding = PaddingValues(start = UmbraDimens.pagePadX, end = UmbraDimens.pagePadX, top = UmbraDimens.sp5, bottom = 28.dp),
    ) {
        if (d == null) {
            // 推进来时条目已经不在列表里（别的端刚删了 / 列表还没读到）。
            item(key = "gone") { DutyIntro(ctx.getString(if (st.list == null) R.string.duty_loading else R.string.duty_edit_gone)) }
        } else {
            dutySection(ed.nameSection(d))
            dutySection(ed.triggerSection(d, { timeField = it }, { showWhen = true }))
            dutySection(ed.actionSection(d))
            dutySection(ed.rulesSection(d))
            dutySection(DutySection("save", rows = listOf(DutyRow(
                ctx.getString(if (saving) R.string.duty_edit_saving else if (id == null) R.string.duty_edit_create else R.string.duty_save),
                tint = c.orangeText,
            ) {
                if (saving) return@DutyRow
                val cur = draft ?: return@DutyRow
                cur.problem(System.currentTimeMillis())?.let { AppGraph.router.showToast(tr.k(it)); return@DutyRow }
                saving = true
                dutyLaunch {
                    val it0 = DutyStore.state.value.list?.items?.firstOrNull { it.id == id }
                    val err: DutyRes.Failed? = if (id != null && it0 != null) DutyStore.save(it0, cur) else {
                        val e = DutyStore.createCustom(cur)
                        // 新建这一下也算「开执勤」：要通知权限（契约 §16.7）。
                        if (e == null) DutyPush.requestIfNeeded()
                        e
                    }
                    saving = false
                    if (err != null) AppGraph.router.showToast(friendly(ctx, err))
                    else {
                        AppGraph.router.showToast(ctx.getString(R.string.duty_edit_saved))
                        AppGraph.router.back(ifShowing = route)
                    }
                }
            })))
        }
    }

    timeField?.let { f ->
        val t = draft?.trigger
        val (label, raw) = when (f) {
            EditTimeField.Recur -> R.string.duty_brief_time to (t?.time_hhmm ?: "09:00")
            EditTimeField.WindowStart -> R.string.duty_edit_windowStart to (t?.start_hhmm ?: "07:00")
            EditTimeField.WindowEnd -> R.string.duty_edit_windowEnd to (t?.end_hhmm ?: "10:00")
        }
        // 认不出就给 09:00（iOS 同）。暂用 M3 TimePicker；UmbraDateTimePanel（提醒代理另一分支）合并后统一替换。
        DutyTimePickerDialog(ctx.getString(label), parseHHMM(raw, LocalTime.of(9, 0)), { timeField = null }) { picked ->
            val s = picked.hhmm()
            if (DutyRules.validHHMM(s)) draft = draft?.let { dd ->
                when (f) {
                    EditTimeField.Recur -> dd.copy(trigger = dd.trigger.copy(time_hhmm = s))
                    EditTimeField.WindowStart -> dd.copy(trigger = dd.trigger.copy(start_hhmm = s))
                    EditTimeField.WindowEnd -> dd.copy(trigger = dd.trigger.copy(end_hhmm = s))
                }
            }
        }
    }
    if (showWhen) {
        DutyWhenPickerDialog(ctx.getString(R.string.duty_edit_at), draft?.trigger?.at_ms?.takeIf { it > 0 } ?: System.currentTimeMillis(), { showWhen = false }) { ms ->
            draft = draft?.let { it.copy(trigger = it.trigger.copy(at_ms = ms)) }
        }
    }
}

/** 服务端的错误码换成人话（422 feature_unavailable / 409 duty_limit），其余原话照吐。 */
private fun friendly(ctx: Context, e: DutyRes.Failed): String = when {
    e.mentions("feature_unavailable") -> ctx.getString(R.string.duty_edit_err_workflow)
    e.mentions("duty_limit") -> ctx.getString(R.string.duty_edit_err_limit)
    else -> e.why.ifEmpty { ctx.getString(R.string.duty_writeFailed) }
}

private fun askDelete(ctx: Context, item: DutyItem?, route: Route) {
    val it = item ?: return
    AppGraph.router.confirm(UmbraAlert(
        title = ctx.getString(R.string.duty_edit_deleteTitle, it.name),
        message = ctx.getString(R.string.duty_edit_deleteBody),
        cancelLabel = ctx.getString(R.string.duty_cancel),
        confirmLabel = ctx.getString(R.string.duty_edit_delete),
        destructive = true,
    ) {
        dutyLaunch {
            val e = DutyStore.delete(it)
            if (e != null) AppGraph.router.showToast(friendly(ctx, e))
            else {
                AppGraph.router.showToast(ctx.getString(R.string.duty_edit_deleted))
                AppGraph.router.back(ifShowing = route)
            }
        }
    })
}

/** 编辑器各组的画法与动作。[edit] 改草稿（传一个变换）。 */
private class Editor(
    val ctx: Context,
    val tr: DutyTr,
    val c: UmbraColorScheme,
    val st: DutyState,
    val edit: ((DutyDraft) -> DutyDraft) -> Unit,
) {
    private fun s(id: Int) = ctx.getString(id)

    // MARK: 名字

    fun nameSection(d: DutyDraft) = DutySection("name", rows = listOf(
        DutyRow(s(R.string.duty_edit_name), value = d.name.ifEmpty { s(R.string.duty_edit_namePlaceholder) }, chevron = true) {
            editText(TextSlot.Name, d.name)
        },
    ))

    // MARK: 触发（四选一）

    fun triggerSection(d: DutyDraft, pickTime: (EditTimeField) -> Unit, pickWhen: () -> Unit): DutySection {
        val t = d.trigger
        val rows = mutableListOf(DutyRow(s(R.string.duty_edit_trigger), value = tr.k("duty.edit.trigger.${t.type}"), chevron = true) { askTriggerType(t.type) })
        when (t.type) {
            "at" -> rows += DutyRow(s(R.string.duty_edit_at), value = DutyRules.monthDayTime(t.at_ms), mono = true, chevron = true) { pickWhen() }
            "window" -> {
                rows += DutyRow(s(R.string.duty_brief_days), value = windowDaysLabel(t), chevron = true) { askWindowDays(t) }
                if (t.cycle == "week") rows += weekdayRows(t)
                rows += DutyRow(s(R.string.duty_edit_windowStart), value = t.start_hhmm, mono = true, chevron = true) { pickTime(EditTimeField.WindowStart) }
                rows += DutyRow(s(R.string.duty_edit_windowEnd), value = t.end_hhmm, mono = true, chevron = true) { pickTime(EditTimeField.WindowEnd) }
                rows += DutyRow(s(R.string.duty_edit_signal), value = DutyKit.signalText(t.signal, tr), chevron = true) { askSignal(t) }
            }
            "interval" -> {
                // 巡视：哪些天（同窗口那一格）→ 起止 → 间隔。
                rows += DutyRow(s(R.string.duty_brief_days), value = windowDaysLabel(t), chevron = true) { askWindowDays(t) }
                if (t.cycle == "week") rows += weekdayRows(t)
                rows += DutyRow(s(R.string.duty_edit_windowStart), value = t.start_hhmm, mono = true, chevron = true) { pickTime(EditTimeField.WindowStart) }
                rows += DutyRow(s(R.string.duty_edit_windowEnd), value = t.end_hhmm, mono = true, chevron = true) { pickTime(EditTimeField.WindowEnd) }
                rows += DutyRow(s(R.string.duty_edit_every), value = DutyKit.everyText(t.every_min, tr), chevron = true) { askEvery(t) }
            }
            else -> {
                rows += DutyRow(s(R.string.duty_brief_days), value = recurLabel(d), chevron = d.recurKind != DutyDraft.RecurKind.Other) { askRecurKind(d) }
                if (d.recurKind == DutyDraft.RecurKind.Weekly) rows += weekdayRows(t)
                rows += DutyRow(
                    s(R.string.duty_brief_time), value = DutyKit.timeText(t.time_hhmm, tr), mono = t.time_hhmm != DutyTrigger.AUTO, chevron = true,
                ) { askTime(t, pickTime) }
            }
        }
        // 起止填反（结束不晚于开始）当场说，不等按「保存」才吐一句（批次 261 §三 2：iOS 稿 02「起止填错」那一态 ——
        // 组脚换成红字那一句，告诉人该怎么办）。巡视的组脚是「隔一段做一次」那句说明（§三 3），其余照旧是触发说人话。iOS DutyEditView.swift:137
        val p = d.problem(System.currentTimeMillis())
        if (p == "duty.edit.err.intervalRange" || p == "duty.edit.err.window") {
            return DutySection("trigger", header = s(R.string.duty_edit_when), footer = tr.k(p), footerTone = DutyFooterTone.Danger, rows = rows)
        }
        return DutySection(
            "trigger", header = s(R.string.duty_edit_when),
            footer = if (t.type == "interval") s(R.string.duty_edit_intervalHint) else DutyKit.trigger(t, tr), rows = rows,
        )
    }

    private fun weekdayRows(t: DutyTrigger) = (0..6).map { w ->
        DutyRow(tr.k("duty.edit.wd.$w"), toggle = w in t.week_days) { edit { it.toggleWeekday(w) } }
    }

    private fun recurLabel(d: DutyDraft) = when (d.recurKind) {
        DutyDraft.RecurKind.Daily -> s(R.string.duty_days_everyday)
        DutyDraft.RecurKind.Workday -> s(R.string.duty_days_workday)
        DutyDraft.RecurKind.Weekly -> s(R.string.duty_edit_weekly)
        DutyDraft.RecurKind.Other -> s(R.string.duty_edit_recurOther)
    }

    private fun windowDaysLabel(t: DutyTrigger): String {
        if (t.cycle == "week") return s(R.string.duty_edit_weekly)
        return s(if (t.workday_only) R.string.duty_days_workday else R.string.duty_days_everyday)
    }

    private fun askTriggerType(cur: String) {
        val now = System.currentTimeMillis()
        dutyPresentList("edit-trigger", s(R.string.duty_edit_trigger), null, listOf("recur", "at", "window", "interval").map { k ->
            DutySheetItem(tr.k("duty.edit.trigger.$k"), note = tr.k("duty.edit.trigger.$k.note"), checked = cur == k) {
                edit { it.setTriggerType(k, now) }
            }
        })
    }

    private fun askRecurKind(d: DutyDraft) {
        // 每月 / 每年 / 每 N 天（预设或别的端建的）这里改不了周期，只能改时间 —— 选了别的档就换成那一档。
        val kinds = listOf(
            DutyDraft.RecurKind.Daily to s(R.string.duty_days_everyday),
            DutyDraft.RecurKind.Workday to s(R.string.duty_days_workday),
            DutyDraft.RecurKind.Weekly to s(R.string.duty_edit_weekly),
        )
        dutyPresentList(
            "edit-recur", s(R.string.duty_brief_days),
            if (d.recurKind == DutyDraft.RecurKind.Other) s(R.string.duty_edit_recurOtherSub) else null,
            kinds.map { (k, label) -> DutySheetItem(label, checked = d.recurKind == k) { edit { it.setRecurKind(k) } } },
        )
    }

    private fun askWindowDays(t: DutyTrigger) {
        dutyPresentList("edit-wdays", s(R.string.duty_brief_days), null, listOf(
            DutySheetItem(s(R.string.duty_days_everyday), checked = t.cycle != "week" && !t.workday_only) {
                edit { it.copy(trigger = it.trigger.copy(cycle = "day", workday_only = false)) }
            },
            DutySheetItem(s(R.string.duty_days_workday), checked = t.cycle != "week" && t.workday_only) {
                edit { it.copy(trigger = it.trigger.copy(cycle = "day", workday_only = true)) }
            },
            DutySheetItem(s(R.string.duty_edit_weekly), checked = t.cycle == "week") {
                edit {
                    val tt = it.trigger.copy(cycle = "week", workday_only = false)
                    it.copy(trigger = if (tt.week_days.isEmpty()) tt.copy(week_days = listOf(0, 1, 2, 3, 4)) else tt)
                }
            },
        ))
    }

    /** 巡视的间隔（M2）：那几档之外的值（别的端写的）照样标出当前值 —— 列表里没有就不打勾，problem 会指出越界。 */
    private fun askEvery(t: DutyTrigger) {
        dutyPresentList("edit-every", s(R.string.duty_edit_every), s(R.string.duty_edit_err_everyMin), DutyKit.intervalOptions.map { m ->
            DutySheetItem(DutyKit.everyText(m, tr), checked = t.every_min == m) { edit { it.copy(trigger = it.trigger.copy(every_min = m)) } }
        })
    }

    private fun askSignal(t: DutyTrigger) {
        dutyPresentList("edit-signal", s(R.string.duty_edit_signal), null, listOf("first_seen_today", "device_online", "none").map { sig ->
            DutySheetItem(DutyKit.signalText(sig, tr), note = tr.k("duty.edit.signal.$sig"), checked = t.signal == sig) {
                // 任意一台（选具体哪台本期不做）
                edit { it.copy(trigger = it.trigger.copy(signal = sig, device_id = if (sig == "device_online") null else it.trigger.device_id)) }
            }
        })
    }

    /** 周期型的时间：选一个时刻，或「我通常上线的时候」（契约 §16.1 `time_hhmm = "auto"`）。 */
    private fun askTime(t: DutyTrigger, pickTime: (EditTimeField) -> Unit) {
        dutyPresentList("edit-time", s(R.string.duty_brief_time), null, listOf(
            DutySheetItem(s(R.string.duty_edit_pickTime)) { pickTime(EditTimeField.Recur) },
            DutySheetItem(tr.k("duty.trig.auto"), note = s(R.string.duty_edit_autoNote), checked = t.time_hhmm == DutyTrigger.AUTO) {
                edit { it.copy(trigger = it.trigger.copy(time_hhmm = DutyTrigger.AUTO)) }
            },
        ))
    }

    // MARK: 动作

    fun actionSection(d: DutyDraft): DutySection {
        val a = d.action
        val rows = mutableListOf(DutyRow(s(R.string.duty_edit_action), value = tr.k("duty.edit.action.${a.type}"), chevron = true) { askActionType(a) })
        when (a.type) {
            "task" -> {
                rows += DutyRow(s(R.string.duty_edit_goal), sub = a.goal.ifEmpty { s(R.string.duty_edit_goalPlaceholder) }, chevron = true) {
                    editText(TextSlot.Goal, a.goal)
                }
                rows += DutyRow(s(R.string.duty_edit_confirmAsk), sub = s(R.string.duty_edit_confirmAskSub), toggle = a.confirm != "never") {
                    edit { it.copy(action = it.action.copy(confirm = if (it.action.confirm == "never") "ask" else "never")) }
                }
            }
            "flow" -> rows += DutyRow(s(R.string.duty_edit_flow), value = flowValue(a.flow_id, DutyStore.flowName(a.flow_id, st)), chevron = true) { askFlow(a) }
            "say" -> {
                rows += DutyRow(s(R.string.duty_edit_brief), sub = a.brief.ifEmpty { s(R.string.duty_edit_briefPlaceholder) }, chevron = true) {
                    editText(TextSlot.Brief, a.brief)
                }
                // M2 多两块（两小时内的提醒、重要关注的新动向）：块表在 DutyKit.sayBlocks，文案 key 在 DutyKit.blockKey。
                for (block in DutyKit.sayBlocks) {
                    rows += DutyRow(tr.k(DutyKit.blockKey(block)), toggle = block in a.blocks) {
                        edit { it.copy(action = it.action.copy(blocks = DutyRules.toggle(block, it.action.blocks))) }
                    }
                }
            }
        }
        return DutySection("action", header = s(R.string.duty_edit_what), footer = actionFooter(a), rows = rows)
    }

    private fun askActionType(a: DutyAction) {
        val items = mutableListOf(
            DutySheetItem(s(R.string.duty_edit_action_say), note = s(R.string.duty_edit_action_say_note), checked = a.type == "say") { setAction("say") },
            DutySheetItem(s(R.string.duty_edit_action_task), note = s(R.string.duty_edit_action_task_note), checked = a.type == "task") { setAction("task") },
        )
        // 「跑一个流程」：老服务端没有流程（列表 null）就不给这一项 —— 选了也存不成。
        // 选中后紧接着弹流程列表：一个没选流程的「跑流程」是存不了的半成品。
        if (st.flows != null) {
            items += DutySheetItem(s(R.string.duty_edit_action_flow), note = s(R.string.duty_edit_action_flow_note), checked = a.type == "flow") {
                setAction("flow")
                if (a.flow_id.isEmpty()) askFlow(a.copy(type = "flow"))
            }
        }
        // workflow 置灰写原因（等 PC「工作流作为设备能力」，本期服务端回 422）。点了只说一句。
        items += DutySheetItem(s(R.string.duty_edit_action_workflow), note = s(R.string.duty_edit_action_workflow_note)) {
            AppGraph.router.showToast(s(R.string.duty_edit_err_workflow))
        }
        dutyPresentList("edit-action", s(R.string.duty_edit_action), null, items)
    }

    /** 「流程」那一格的值：没选 →「选一个」；找得到 → 名字；列表拉到了却找不到 →「这个流程不在了」；列表没拉到 → 不下结论。 */
    private fun flowValue(id: String, name: String): String {
        if (id.isEmpty()) return s(R.string.duty_edit_flowPick)
        if (name.isNotEmpty()) return name
        return s(if (st.flows == null) R.string.duty_act_flow else R.string.duty_edit_flowGone)
    }

    /** 动作那一组的组脚：发一段话说「勾的几块会带上」；跑流程说「流程在电脑上编」；其余是动作摘要。 */
    private fun actionFooter(a: DutyAction): String = when (a.type) {
        "say" -> s(R.string.duty_edit_blocksHelp)
        "flow" -> s(R.string.duty_edit_flowHelp)
        else -> DutyKit.action(a, tr)
    }

    /** 选流程：只列用户流程（内置早报那条走早报卡）。一个都没有就说去电脑上建。 */
    private fun askFlow(a: DutyAction) {
        val flows = DutyFlowKit.pickable(DutyStore.state.value.flows ?: emptyList())
        if (flows.isEmpty()) {
            dutyInfo(s(R.string.duty_edit_flowNone), s(R.string.duty_edit_flowNoneBody))
            return
        }
        dutyPresentList("edit-flow", s(R.string.duty_edit_flow), s(R.string.duty_edit_flowSub), flows.map { f ->
            DutySheetItem(f.name.ifEmpty { s(R.string.duty_edit_unnamed) }, note = DutyFlowKit.listSub(f, tr), checked = a.flow_id == f.id) {
                // 名字还空着就借流程名（用户多半就想叫这个），已经起了名的不动。
                edit { it.copy(action = it.action.copy(flow_id = f.id), name = it.name.ifEmpty { f.name }) }
            }
        })
    }

    private fun setAction(type: String) = edit {
        if (it.action.type == type) it
        else {
            var a = it.action.copy(type = type)
            if (type == "task" && a.confirm.isEmpty()) a = a.copy(confirm = "ask")
            it.copy(action = a)
        }
    }

    // MARK: 规矩

    fun rulesSection(d: DutyDraft) = DutySection("rules", header = s(R.string.duty_rules), footer = s(R.string.duty_edit_rulesHelp), rows = listOf(
        DutyRow(s(R.string.duty_edit_judge), sub = s(R.string.duty_edit_judgeSub), toggle = d.mode == "judge") {
            edit { it.copy(mode = if (it.mode == "judge") "always" else "judge") }
        },
        DutyRow(s(R.string.duty_edit_priority), value = DutyKit.priorityText(d.priority, tr), chevron = true) {
            dutyPresentList("edit-prio", s(R.string.duty_edit_priority), s(R.string.duty_edit_prioritySub), (5 downTo 1).map { p ->
                DutySheetItem(DutyKit.priorityText(p, tr), checked = d.priority == p) { edit { it.copy(priority = p) } }
            })
        },
        DutyRow(s(R.string.duty_edit_cooldown), value = DutyKit.cooldownText(d.cooldown_min, tr), chevron = true) {
            dutyPresentList("edit-cool", s(R.string.duty_edit_cooldown), s(R.string.duty_edit_cooldownSub), DutyKit.cooldownOptions.map { m ->
                DutySheetItem(DutyKit.cooldownText(m, tr), checked = d.cooldown_min == m) { edit { it.copy(cooldown_min = m) } }
            })
        },
        // 「每天最多几点」（M2 points_cap）：到了这一条当天就不再做，明天再来。
        DutyRow(s(R.string.duty_edit_pointsCap), value = DutyKit.pointsCapText(d.points_cap, tr), chevron = true) {
            dutyPresentList("edit-cap", s(R.string.duty_edit_pointsCap), s(R.string.duty_edit_pointsCapSub), DutyKit.pointsCapOptions.map { n ->
                DutySheetItem(DutyKit.pointsCapText(n, tr), checked = d.points_cap == n) { edit { it.copy(points_cap = n) } }
            })
        },
        DutyRow(s(R.string.duty_edit_quiet), sub = s(R.string.duty_edit_quietSub), toggle = d.respect_quiet) {
            edit { it.copy(respect_quiet = !it.respect_quiet) }
        },
    ))

    // MARK: 文字格

    private fun editText(slot: TextSlot, current: String) {
        val (title, placeholder, help) = when (slot) {
            TextSlot.Name -> Triple(R.string.duty_edit_name, R.string.duty_edit_namePlaceholder, null)
            TextSlot.Brief -> Triple(R.string.duty_edit_brief, R.string.duty_edit_briefPlaceholder, R.string.duty_edit_briefHelp)
            TextSlot.Goal -> Triple(R.string.duty_edit_goal, R.string.duty_edit_goalPlaceholder, R.string.duty_edit_goalHelp)
        }
        dutyAskText("edit-${slot.name}", s(title), help?.let { s(it) }, s(placeholder), current, s(R.string.duty_edit_done)) { raw ->
            val t = raw.trim()
            edit {
                when (slot) {
                    TextSlot.Name -> it.copy(name = t)
                    TextSlot.Brief -> it.copy(action = it.action.copy(brief = t))
                    TextSlot.Goal -> it.copy(action = it.action.copy(goal = t))
                }
            }
        }
    }
}
