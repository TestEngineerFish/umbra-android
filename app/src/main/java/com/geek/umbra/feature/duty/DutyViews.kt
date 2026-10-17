// 「我 › 执勤」二级页 —— 照搬 iOS Features/Duty/DutyViews.swift（需求 §8.2、契约 §15.2「端上 M0 要有」第 3 条）。
//
// 一页从上到下：总开关 + 档位 + 暂停（+ 通知被拒时「去设置里打开通知」）→ 秘书的提议（M2）→ 早报卡（没建时是「开启早报」）
// → 执勤表（其余条目 + 新建）→ 流程（只读 + 一键跑）→ 关注 → 规矩（静默、每天最多说几次、预算）
// → 最近 14 天日志（点一行看「为什么」，底下进全部日志）。
// 骨架用设置类页面那一套（分组 + 行）：这一页的每一格都是「一个设置 + 一个值」。
//
// 未登录：路由层拦（Route.MeDuty 属于 needsLogin），这一页不再自己判。
package com.geek.umbra.feature.duty

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraColorScheme
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalTime

/** 在进程级作用域里跑一下（页面退了吐司照样出，同 iOS 的 Task）。 */
internal fun dutyLaunch(block: suspend () -> Unit) {
    AppGraph.scope.launch { block() }
}

/** 正在选哪一个时间（早报时间 / 静默开始 / 静默结束）。 */
private enum class HomeTimeField { Brief, QuietStart, QuietEnd }

@Composable
internal fun DutyHomeView() {
    val st by DutyStore.state.collectAsState()
    val ctx = LocalContext.current
    val tr = rememberDutyTr()
    val c = UmbraTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val denied by DutyPush.denied.collectAsState()
    var timeField by remember { mutableStateOf<HomeTimeField?>(null) }

    DisposableEffect(Unit) {
        DutyStore.appear()
        onDispose { DutyStore.disappear() }
    }
    DutyPushHost()

    val title = ctx.getString(R.string.duty_title)
    UmbraScreen(
        title = title, onBack = { AppGraph.router.back() }, spacing = UmbraDimens.sp6,
        contentPadding = PaddingValues(start = UmbraDimens.pagePadX, end = UmbraDimens.pagePadX, top = UmbraDimens.sp5, bottom = 28.dp),
    ) {
        val list = st.list
        when {
            list != null -> {
                item(key = "intro") { DutyIntro(ctx.getString(R.string.duty_intro)) }
                dutySection(mainSection(ctx, tr, c, list, denied))
                // 秘书的提议（M2）：放在早报卡之前 —— 它是这一页唯一「等你拍板」的东西，压在执勤表下面就没人看见。老服务端没有这条就不画。
                st.proposals?.let { pr -> item(key = "proposals") { DutyProposalsCard(pr, st.proposalBusy) } }
                dutySection(briefSection(ctx, tr, c, list, st) { timeField = HomeTimeField.Brief })
                item(key = "items") {
                    DutyItemsCard(
                        items = list.items.filter { !it.isBrief }, st = st,
                        onNew = { askNew(ctx, list) },
                        onToggle = { patch(it, buildJsonObject { put("enabled", !it.enabled) }) },
                    )
                }
                // 流程（M1b §17.4）：老服务端没有这条（null）就不画。
                st.flows?.let { dutySection(dutyFlowSection(ctx, tr, c, it)) }
                st.follows?.let { dutySection(followsSection(ctx, tr, c, it)) }
                dutySection(rulesSection(ctx, list.settings, { timeField = HomeTimeField.QuietStart }, { timeField = HomeTimeField.QuietEnd }))
                // 日志：每天一组（组头是日期）。分组身份按天：新的一班并进来时其余各组不被拆了重建。
                val groups = DutyRules.groupByDay(st.log)
                if (groups.isEmpty()) {
                    dutySection(DutySection("log", header = ctx.getString(R.string.duty_log), rows = listOf(DutyRow(ctx.getString(R.string.duty_log_empty), tint = c.faint))))
                } else {
                    for (g in groups) {
                        dutySection(DutySection("log-${g.day}", header = dutyDayHeader(g.day, locale), rows = g.items.map { s -> dutyLogRow(ctx, tr, c, s, st.log) }))
                    }
                }
                dutySection(DutySection("logAll", rows = listOf(DutyRow(ctx.getString(R.string.duty_log_all), chevron = true) { AppGraph.router.go(Route.MeDutyLog) })))
            }
            st.loadError != null -> {
                val why = st.loadError ?: ""
                dutySection(DutySection(
                    "err", footer = why.ifEmpty { ctx.getString(R.string.duty_loadFailed) }, footerTone = DutyFooterTone.Danger,
                    rows = listOf(DutyRow(ctx.getString(R.string.duty_retry), tint = c.orangeText) { dutyLaunch { DutyStore.reload() } }),
                ))
            }
            else -> item(key = "loading") { DutyIntro(ctx.getString(R.string.duty_loading)) }
        }
    }

    // 时间面板确认时才写；初值取那一格当前的值。
    timeField?.let { f ->
        val l = st.list
        val (label, raw) = when (f) {
            HomeTimeField.Brief -> R.string.duty_brief_time to (l?.brief?.params?.time ?: "08:00")
            HomeTimeField.QuietStart -> R.string.duty_quietStart to (l?.settings?.quiet_start ?: "22:30")
            HomeTimeField.QuietEnd -> R.string.duty_quietEnd to (l?.settings?.quiet_end ?: "07:30")
        }
        // 认不出就给 08:00（iOS 同）。暂用 M3 TimePicker；UmbraDateTimePanel（提醒代理另一分支）合并后统一替换。
        DutyTimePickerDialog(ctx.getString(label), parseHHMM(raw, LocalTime.of(8, 0)), { timeField = null }) { t ->
            applyTime(f, t.hhmm())
        }
    }
}

// MARK: 总开关 + 暂停

private fun mainSection(ctx: Context, tr: DutyTr, c: UmbraColorScheme, list: DutyListDTO, denied: Boolean): DutySection {
    val s = list.settings
    val on = DutyRules.switchOn(s)
    // 档位（M1a §16.3 四档）：非 normal 时开关下一行一句警示；服务端的原因与额度预报（forecast.note）接在后面。
    val tierLine = DutyKit.tierLine(list.tier, list.tier_reason, list.forecast?.note, tr)
    val rows = mutableListOf(
        DutyRow(ctx.getString(R.string.duty_switch), sub = tierLine.detail, subWarn = tierLine.headline, toggle = on) { toggleMaster(ctx, list) },
    )
    // 通知被拒了（契约 §16.7）：执勤到点发的消息在手机上弹不出来 —— 说一句，给一条去系统设置的路。
    if (on && denied) {
        rows += DutyRow(ctx.getString(R.string.duty_push_open), sub = ctx.getString(R.string.duty_push_deniedSub), tint = c.orangeText, chevron = true) {
            DutyPush.openSettings(ctx)
        }
    }
    if (on) {
        when (val p = DutyRules.pause(s.paused_until_ms, System.currentTimeMillis())) {
            DutyPause.None -> rows += DutyRow(ctx.getString(R.string.duty_pause), value = ctx.getString(R.string.duty_pause_none), chevron = true) { askPause(ctx) }
            is DutyPause.Until -> {
                rows += DutyRow(ctx.getString(R.string.duty_pausedUntil, DutyRules.monthDayTime(p.ms)))
                rows += DutyRow(ctx.getString(R.string.duty_resume), tint = c.orangeText) { dutyLaunch { dutyReport(DutyStore.resume()) } }
            }
            DutyPause.Forever -> {
                rows += DutyRow(ctx.getString(R.string.duty_pausedForever))
                rows += DutyRow(ctx.getString(R.string.duty_resume), tint = c.orangeText) { dutyLaunch { dutyReport(DutyStore.resume()) } }
            }
        }
    }
    return DutySection("main", footer = ctx.getString(R.string.duty_estimate, DutyRules.pctText(list.estimate_weekly_pct)), rows = rows)
}

private val busy: Boolean get() = DutyStore.state.value.busy

/** 总开关：第一次打开先弹知情确认，确认后照契约写 `{consented, enabled}`；关就直接关。 */
private fun toggleMaster(ctx: Context, list: DutyListDTO) {
    if (busy) return
    val on = DutyRules.switchOn(list.settings)
    when {
        DutyRules.needsConsent(turningOn = !on, consented = list.settings.consented) ->
            askConsent(ctx, list) { dutyLaunch { afterEnable(DutyStore.consentAndEnable()) } }
        !on -> dutyLaunch { afterEnable(DutyStore.putSettings(buildJsonObject { put("enabled", true) })) }
        else -> dutyLaunch { dutyReport(DutyStore.putSettings(buildJsonObject { put("enabled", false) })) }
    }
}

/** 知情确认那一弹：带上服务端估的「约占周额度的 x%」。确认之后不再问。 */
private fun askConsent(ctx: Context, list: DutyListDTO, then: () -> Unit) {
    AppGraph.router.confirm(UmbraAlert(
        title = ctx.getString(R.string.duty_consent_title),
        message = ctx.getString(R.string.duty_consent_body, DutyRules.pctText(list.estimate_weekly_pct)),
        cancelLabel = ctx.getString(R.string.duty_cancel),
        confirmLabel = ctx.getString(R.string.duty_consent_ok),
        onConfirm = then,
    ))
}

private fun askPause(ctx: Context) {
    val labels = mapOf(
        "hour" to R.string.duty_pause_hour, "today" to R.string.duty_pause_today,
        "week" to R.string.duty_pause_week, "forever" to R.string.duty_pause_forever,
    )
    dutyPresentList("pause", ctx.getString(R.string.duty_pause), ctx.getString(R.string.duty_pause_sub), DutyRules.pauseSpans.map { span ->
        DutySheetItem(labels[span]?.let { ctx.getString(it) } ?: span) { dutyLaunch { dutyReport(DutyStore.pause(span)) } }
    })
}

/**
 * 打开执勤 / 开早报成功之后要通知权限（契约 §16.7）。没成就只报错、不弹权限 ——
 * 执勤没开着，问「要不要通知」是没头没尾的一问。
 */
internal fun afterEnable(err: String?) {
    if (err != null) { dutyReport(err); return }
    DutyPush.requestIfNeeded()
}

// MARK: 早报卡

private fun briefSection(ctx: Context, tr: DutyTr, c: UmbraColorScheme, list: DutyListDTO, st: DutyState, pickTime: () -> Unit): DutySection {
    val b = list.brief ?: return DutySection(
        "brief", header = ctx.getString(R.string.duty_brief), footer = ctx.getString(R.string.duty_brief_createHelp),
        rows = listOf(DutyRow(ctx.getString(R.string.duty_brief_create), tint = c.orangeText) { create(ctx, list) { DutyStore.createBrief() } }),
    )
    val p = b.params
    val rows = mutableListOf(
        DutyRow(ctx.getString(R.string.duty_brief), sub = if (b.enabled) nextLine(ctx, b) else null, toggle = b.enabled) { toggleBrief(b) },
        DutyRow(ctx.getString(R.string.duty_brief_time), value = p.time, mono = true, chevron = true) { pickTime() },
        DutyRow(
            ctx.getString(R.string.duty_brief_days),
            value = ctx.getString(if (p.days == "everyday") R.string.duty_days_everyday else R.string.duty_days_workday), chevron = true,
        ) { askDays(ctx, b) },
        DutyRow(ctx.getString(R.string.duty_brief_city), value = p.city.ifEmpty { ctx.getString(R.string.duty_brief_cityAuto) }, chevron = true) {
            dutyAskText(
                "city", ctx.getString(R.string.duty_brief_city), ctx.getString(R.string.duty_brief_cityHelp),
                ctx.getString(R.string.duty_brief_cityPlaceholder), p.city, ctx.getString(R.string.duty_save),
            ) { saveCity(it) }
        },
    )
    for ((block, res) in listOf("weather" to R.string.duty_block_weather, "reminders" to R.string.duty_block_reminders, "tasks" to R.string.duty_block_tasks)) {
        rows += DutyRow(ctx.getString(res), toggle = block in p.blocks) { patchParams(b) { it.copy(blocks = DutyRules.toggle(block, it.blocks)) } }
    }
    rows += DutyRow(ctx.getString(R.string.duty_brief_email), sub = ctx.getString(R.string.duty_brief_emailSub), toggle = p.email) {
        patchParams(b) { it.copy(email = !it.email) }
    }
    // 要不要秘书判断（M1a §16.1：早报老条目保持 always，可改）。
    rows += DutyRow(ctx.getString(R.string.duty_edit_judge), sub = ctx.getString(R.string.duty_edit_judgeSub), toggle = b.mode == "judge") {
        patch(b, buildJsonObject { put("mode", if (b.mode == "judge") "always" else "judge") })
    }
    b.last?.let { last ->
        rows += DutyRow(
            ctx.getString(R.string.duty_brief_last), sub = DutyKit.logSub(last, st.log, tr),
            value = tr.k(DutyRules.outcomeKey(last.outcome)), valueTint = outcomeTint(c, last.outcome),
        )
    }
    rows += DutyRow(ctx.getString(if (st.busy) R.string.duty_brief_running else R.string.duty_brief_run), tint = c.orangeText) { runOnce(ctx, b) }
    return DutySection("brief", header = ctx.getString(R.string.duty_brief), footer = ctx.getString(R.string.duty_brief_runHelp), rows = rows)
}

/** 「下一次 10-17 08:00」。暂停中 / 服务端没算出下一次 → 不出这一行副文。 */
private fun nextLine(ctx: Context, b: DutyItem): String? {
    val n = b.next_at_ms ?: return null
    if (n <= 0) return null
    return ctx.getString(R.string.duty_brief_next, DutyRules.monthDayTime(n))
}

/**
 * 建一条（开早报 / 用预设 / 自己定的都走这里）：没确认过就先确认（「开早报」就是第一次开执勤 ——
 * 需求 §14.4 第 1 条把它和总开关算作同一件事），建好了要通知权限。
 */
private fun create(ctx: Context, list: DutyListDTO, op: suspend () -> String?) {
    if (busy) return
    if (!list.settings.consented) {
        askConsent(ctx, list) {
            dutyLaunch {
                val e = DutyStore.consentAndEnable()
                if (e != null) { dutyReport(e); return@dutyLaunch }
                afterEnable(op())
            }
        }
    } else {
        dutyLaunch { afterEnable(op()) }
    }
}

private fun toggleBrief(b: DutyItem) {
    if (busy) return
    if (b.enabled) patch(b, buildJsonObject { put("enabled", false) })
    else dutyLaunch { afterEnable(DutyStore.patch(b, buildJsonObject { put("enabled", true) })?.why) }
}

// MARK: 新建（预设几选一或「自己定」）

private fun askNew(ctx: Context, list: DutyListDTO) {
    if (busy) return
    dutyLaunch {
        val e = DutyStore.loadPresets()
        if (e != null) { dutyReport(e); return@dutyLaunch }
        // 早报每人一条：已经有了就不在这里再给。
        val presets = DutyStore.state.value.presets.filter { !(it.preset == DutyRules.MORNING_BRIEF && list.brief != null) }
        val items = presets.map { pr ->
            DutySheetItem(pr.name, note = pr.desc.ifEmpty { null }) {
                if (pr.preset == DutyRules.MORNING_BRIEF) {
                    create(ctx, list) { DutyStore.createBrief() }
                } else {
                    create(ctx, list) {
                        val err = DutyStore.createFromPreset(pr.preset)
                        if (err == null) AppGraph.router.showToast(ctx.getString(R.string.duty_new_created, pr.name))
                        err
                    }
                }
            }
        } + DutySheetItem(ctx.getString(R.string.duty_new_custom), note = ctx.getString(R.string.duty_new_customNote)) {
            AppGraph.router.go(Route.MeDutyEdit(null))
        }
        dutyPresentList("new", ctx.getString(R.string.duty_new), ctx.getString(R.string.duty_new_sub), items)
    }
}

private fun askDays(ctx: Context, b: DutyItem) {
    dutyPresentList("days", ctx.getString(R.string.duty_brief_days), null, listOf(
        DutySheetItem(ctx.getString(R.string.duty_days_workday), checked = b.params.days != "everyday") { patchParams(b) { it.copy(days = "workday") } },
        DutySheetItem(ctx.getString(R.string.duty_days_everyday), checked = b.params.days == "everyday") { patchParams(b) { it.copy(days = "everyday") } },
    ))
}

private fun saveCity(raw: String) {
    val b = DutyStore.state.value.list?.brief ?: return
    val city = raw.trim()
    patchParams(b) { it.copy(city = city) }
}

private fun runOnce(ctx: Context, b: DutyItem) {
    if (busy) return
    dutyLaunch {
        val e = DutyStore.run(b)
        if (e != null) dutyReport(e) else AppGraph.router.showToast(ctx.getString(R.string.duty_brief_runSent))
    }
}

private fun patch(b: DutyItem, body: kotlinx.serialization.json.JsonObject) {
    if (busy) return
    dutyLaunch { dutyReport(DutyStore.patch(b, body)?.why) }
}

private fun patchParams(b: DutyItem, edit: (DutyParams) -> DutyParams) {
    if (busy) return
    dutyLaunch { dutyReport(DutyStore.patchParams(b, edit)) }
}

// MARK: 规矩

private fun rulesSection(ctx: Context, s: DutySettings, pickStart: () -> Unit, pickEnd: () -> Unit) = DutySection(
    "rules", header = ctx.getString(R.string.duty_rules), footer = ctx.getString(R.string.duty_rules_help),
    rows = listOf(
        DutyRow(ctx.getString(R.string.duty_quietStart), value = s.quiet_start, mono = true, chevron = true) { pickStart() },
        DutyRow(ctx.getString(R.string.duty_quietEnd), value = s.quiet_end, mono = true, chevron = true) { pickEnd() },
        DutyRow(ctx.getString(R.string.duty_nudges), value = ctx.getString(R.string.duty_nudges_value, "${s.daily_nudges}"), chevron = true) { askNudges(ctx, s) },
        DutyRow(ctx.getString(R.string.duty_budget), value = ctx.getString(R.string.duty_budget_value, DutyRules.pctText(s.budget_pct)), chevron = true) { askBudget(ctx, s) },
    ),
)

/** 「每天最多主动说几次」（回包键名 daily_nudges，2026-10-16 对齐）。 */
private fun askNudges(ctx: Context, s: DutySettings) {
    dutyPresentList("nudges", ctx.getString(R.string.duty_nudges), ctx.getString(R.string.duty_nudges_sub), DutyKit.nudgeOptions.map { v ->
        DutySheetItem(ctx.getString(R.string.duty_nudges_value, "$v"), checked = s.daily_nudges == v) {
            if (!busy) dutyLaunch { dutyReport(DutyStore.putSettings(buildJsonObject { put("daily_nudges", v) })) }
        }
    })
}

private fun askBudget(ctx: Context, s: DutySettings) {
    dutyPresentList("budget", ctx.getString(R.string.duty_budget), ctx.getString(R.string.duty_budget_sub), DutyRules.budgetOptions.map { v ->
        DutySheetItem(ctx.getString(R.string.duty_budget_value, "$v%"), checked = Math.round(s.budget_pct).toInt() == v) {
            if (!busy) dutyLaunch { dutyReport(DutyStore.putSettings(buildJsonObject { put("budget_pct", v) })) }
        }
    })
}

// MARK: 关注（M1a §16.8 第 5 条）

private fun followsSection(ctx: Context, tr: DutyTr, c: UmbraColorScheme, fs: List<DutyFollow>): DutySection {
    val rows = DutyKit.sortFollows(fs).map { f ->
        DutyRow(
            f.topic, sub = ctx.getString(R.string.duty_follow_sub, DutyKit.priorityText(f.importance, tr)),
            value = tr.k(DutyKit.followStateKey(f.state)), chevron = true,
        ) { askFollow(ctx, tr, f) }
    } + DutyRow(ctx.getString(R.string.duty_follow_add), tint = c.orangeText) {
        dutyAskText(
            "follow", ctx.getString(R.string.duty_follow_add), ctx.getString(R.string.duty_follow_addHelp),
            ctx.getString(R.string.duty_follow_addPlaceholder), "", ctx.getString(R.string.duty_save),
        ) { raw ->
            val t = raw.trim()
            if (t.isNotEmpty()) dutyLaunch { dutyReport(DutyStore.addFollow(t)) }
        }
    }
    return DutySection(
        "follows", header = ctx.getString(R.string.duty_follow), headerCount = if (fs.isEmpty()) null else fs.size,
        footer = ctx.getString(R.string.duty_follow_help), rows = rows,
    )
}

/** 一条关注的操作：改重要度 / 只进早报（quiet）或恢复 / 结束 / 删除。 */
private fun askFollow(ctx: Context, tr: DutyTr, f: DutyFollow) {
    fun w(op: suspend () -> String?) = dutyLaunch { dutyReport(op()) }
    fun state(v: String) = buildJsonObject { put("state", v) }
    val items = mutableListOf(
        DutySheetItem(ctx.getString(R.string.duty_follow_importance), note = DutyKit.priorityText(f.importance, tr)) { askImportance(ctx, tr, f) },
    )
    if (f.state != "ended") {
        items += if (f.state == "quiet") DutySheetItem(ctx.getString(R.string.duty_follow_unquiet)) { w { DutyStore.patchFollow(f.id, state("active")) } }
        else DutySheetItem(ctx.getString(R.string.duty_follow_quiet), note = ctx.getString(R.string.duty_follow_quietNote)) { w { DutyStore.patchFollow(f.id, state("quiet")) } }
        items += DutySheetItem(ctx.getString(R.string.duty_follow_end)) { w { DutyStore.patchFollow(f.id, state("ended")) } }
    } else {
        items += DutySheetItem(ctx.getString(R.string.duty_follow_restart)) { w { DutyStore.patchFollow(f.id, state("active")) } }
    }
    items += DutySheetItem(ctx.getString(R.string.duty_follow_delete), destructive = true) {
        AppGraph.router.confirm(UmbraAlert(
            title = ctx.getString(R.string.duty_follow_deleteTitle, f.topic),
            message = ctx.getString(R.string.duty_follow_deleteBody),
            cancelLabel = ctx.getString(R.string.duty_cancel),
            confirmLabel = ctx.getString(R.string.duty_follow_delete),
            destructive = true,
        ) { w { DutyStore.deleteFollow(f.id) } })
    }
    dutyPresentList("follow-${f.id}", f.topic, f.query.ifEmpty { null }, items)
}

private fun askImportance(ctx: Context, tr: DutyTr, f: DutyFollow) {
    dutyPresentList("importance", ctx.getString(R.string.duty_follow_importance), ctx.getString(R.string.duty_follow_importanceSub), (5 downTo 1).map { v ->
        DutySheetItem(DutyKit.priorityText(v, tr), checked = f.importance == v) {
            dutyLaunch { dutyReport(DutyStore.patchFollow(f.id, buildJsonObject { put("importance", v) })) }
        }
    })
}

// MARK: 时间面板

private fun applyTime(f: HomeTimeField, s: String) {
    if (!DutyRules.validHHMM(s) || busy) return
    when (f) {
        HomeTimeField.Brief -> DutyStore.state.value.list?.brief?.let { b -> patchParams(b) { it.copy(time = s) } }
        HomeTimeField.QuietStart -> dutyLaunch { dutyReport(DutyStore.putSettings(buildJsonObject { put("quiet_start", s) })) }
        HomeTimeField.QuietEnd -> dutyLaunch { dutyReport(DutyStore.putSettings(buildJsonObject { put("quiet_end", s) })) }
    }
}

// MARK: 日志一行（执勤页与「全部日志」页共用，iOS DutyLogView.swift 的 DutyLogRow）

/** 结局的值色：做了 = 成功绿、失败 = 红（dangerText：前景字走 *Text 那支）、其余照旧灰。 */
internal fun outcomeTint(c: UmbraColorScheme, o: String?): Color? = when (o) {
    "ran" -> c.success
    "failed" -> c.dangerText
    else -> null
}

/** 一班的标题：「08:00 · 早报」（时刻空就只剩名字）。 */
internal fun dutyShiftTitle(s: DutyShift): String = listOf(DutyRules.hhmm(s.planned_at_ms), s.duty_name).filter { it.isNotEmpty() }.joinToString(" · ")

internal fun dutyLogRow(ctx: Context, tr: DutyTr, c: UmbraColorScheme, s: DutyShift, log: List<DutyShift>) = DutyRow(
    dutyShiftTitle(s), sub = DutyKit.logSub(s, log, tr), value = tr.k(DutyRules.stateKey(s)), valueTint = outcomeTint(c, s.outcome),
) { presentWhy(ctx, s) }

/** 「为什么」用一个说明框展开：上下文摘要有七八行，就地展开会把一天的日志顶出一屏；框看完就走，回来还在原位。 */
internal fun presentWhy(ctx: Context, s: DutyShift) {
    dutyLaunch {
        val r = DutyStore.whyLines(s)
        if (r.failed != null) { AppGraph.router.showToast(r.failed.ifEmpty { ctx.getString(R.string.duty_why_loadFailed) }); return@dutyLaunch }
        dutyInfo(dutyShiftTitle(s).ifEmpty { ctx.getString(R.string.duty_fb_why) }, r.lines.joinToString("\n"))
    }
}
