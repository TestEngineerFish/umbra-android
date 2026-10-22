// 「我 › 执勤」二级页 —— 照搬 iOS Features/Duty/DutyViews.swift（需求 §8.2、契约 §15.2「端上 M0 要有」第 3 条；
// 形制照设计侧批次 261 / 263，iOS 稿《批次 261 · 执勤 iOS》01）。
//
// 一页从上到下：页头状态（图标 + 文字：执勤中 / 已暂停 · 到 X / 没开）
// → 总开关组（执勤 / 暂停（+ 通知被拒时「去设置里打开通知」））+ 今天执勤预算 + 额度档位（非正常档才出）
// → 秘书的提议（M2；执勤没开时整区不出）→ 早报卡（没建时是「开启早报」）
// → 执勤表（其余条目 + 新建）→ 例程 / 关注 / 规矩 / 日志四行推入（批次 261 §四：页内不展开，下一级页见 DutySubpages.kt 与 DutyLogView）。
// 骨架用设置类页面那一套（分组 + 行）：这一页的每一格都是「一个设置 + 一个值」。
//
// 稿上「本周执勤预算」那根进度条照服务端口径改成按天：「今天执勤预算 spent / cap 点」（`GET /duties.budget`，ae8cc4f 起）。
// 预算的百分比旋钮仍在「规矩」里。
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraColorScheme
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraNotice
import com.geek.umbra.designsystem.UmbraPrompt
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

@Composable
internal fun DutyHomeView() {
    val st by DutyStore.state.collectAsState()
    val ctx = LocalContext.current
    val tr = rememberDutyTr()
    val c = UmbraTheme.colors
    val denied by DutyPush.denied.collectAsState()
    /** 早报时间的面板开没开（静默时段的两格挪去了「规矩」页，那边自己管自己的面板）。 */
    var pickBriefTime by remember { mutableStateOf(false) }

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
                val now = System.currentTimeMillis()
                val on = DutyRules.switchOn(list.settings)
                // 页头状态（iOS UmbraSettingsPage 的 hero 位）。
                item(key = "head") { DutyHeadLine(DutyKit.headState(list.settings, now)) }
                dutySection(mainSection(ctx, tr, c, list, denied))
                if (on) list.budget?.let { b -> item(key = "budget") { DutyBudgetBar(b) } }
                if (on) {
                    DutyKit.tierLine(list.tier, list.tier_reason, list.forecast?.note, tr).headline?.let { tag ->
                        val copy = DutyKit.tierCopy(list, now, tr)
                        item(key = "tier") {
                            DutyTierCard(tag, copy.text, copy.action) {
                                when (copy.action) {
                                    DutyKit.TierAction.Budget -> askBudget(ctx, list.settings)
                                    DutyKit.TierAction.Membership -> AppGraph.router.jump(Route.SetShop)
                                    DutyKit.TierAction.None -> Unit
                                }
                            }
                        }
                    }
                }
                // 秘书的提议（M2）：总开关组之后、早报之前（批次 261 §一：两端同位，提议改的就是下面这些东西）。
                // 执勤没开时整区不出；老服务端没有这条（null）也不画。
                if (on) st.proposals?.let { pr -> item(key = "proposals") { DutyProposalsCard(pr, st) } }
                dutySection(briefSection(ctx, tr, c, list, st) { pickBriefTime = true })
                item(key = "items") {
                    DutyItemsCard(
                        items = list.items.filter { !it.isBrief }, st = st,
                        onNew = { askNew(ctx, list) },
                        onToggle = { patch(it, buildJsonObject { put("enabled", !it.enabled) }) },
                    )
                }
                dutySection(navSection(ctx, st, list.settings))
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

    // 时间面板确认时才写；初值取那一格当前的值（认不出就给 08:00，iOS DutyTime.date 同）。
    if (pickBriefTime) {
        val raw = st.list?.brief?.params?.time ?: "08:00"
        DutyTimePickerDialog(ctx.getString(R.string.duty_brief_time), parseHHMM(raw, LocalTime.of(8, 0)), { pickBriefTime = false }) { t ->
            applyBriefTime(t.hhmm())
        }
    }
}

// MARK: 推入行（批次 261 §四：例程 / 关注 / 规矩 / 日志收成推入行，页内不展开）—— iOS DutyViews.swift:navSection

private fun navSection(ctx: Context, st: DutyState, s: DutySettings): DutySection {
    val rows = mutableListOf<DutyRow>()
    // 例程（M1b）/ 关注（M1a）：老服务端没有这条（null）就不出那一行 —— 推进去也是一页空。
    st.flows?.let { fl -> rows += DutyRow(ctx.getString(R.string.duty_nav_flows), value = "${fl.size}", chevron = true) { AppGraph.router.go(Route.MeDutyFlows) } }
    st.follows?.let { fs -> rows += DutyRow(ctx.getString(R.string.duty_nav_follows), value = "${fs.size}", chevron = true) { AppGraph.router.go(Route.MeDutyFollows) } }
    rows += DutyRow(
        ctx.getString(R.string.duty_nav_rules), value = ctx.getString(R.string.duty_nav_quiet, "${s.quiet_start}–${s.quiet_end}"), chevron = true,
    ) { AppGraph.router.go(Route.MeDutyRules) }
    val today = DutyRules.day(System.currentTimeMillis())
    val n = st.log.count { DutyRules.day(it.planned_at_ms) == today }
    rows += DutyRow(ctx.getString(R.string.duty_nav_log), value = ctx.getString(R.string.duty_nav_logToday, "$n"), chevron = true) {
        AppGraph.router.go(Route.MeDutyLog)
    }
    return DutySection("nav", rows = rows)
}

// MARK: 总开关 + 暂停

private fun mainSection(ctx: Context, tr: DutyTr, c: UmbraColorScheme, list: DutyListDTO, denied: Boolean): DutySection {
    val s = list.settings
    val on = DutyRules.switchOn(s)
    // 正常档不标（批次 261 §四）；额度预报（`forecast.note`）在正常档也说 —— 「照这周的节奏，周四会用完额度」
    // 正是在还没降档的时候说才有用。非正常档的原因和预报挪进档位那一块（DutyTierCard），这里不重复。
    val forecast = if (list.tier == "normal" || list.tier.isEmpty()) list.forecast?.note else null
    val rows = mutableListOf(
        DutyRow(ctx.getString(R.string.duty_switch), sub = if (on && !forecast.isNullOrEmpty()) forecast else null, toggle = on) { toggleMaster(ctx, list) },
    )
    // 通知被拒了（契约 §16.7）：执勤到点发的消息在手机上弹不出来 —— 说一句，给一条去系统设置的路。
    if (on && denied) {
        rows += DutyRow(ctx.getString(R.string.duty_push_open), sub = ctx.getString(R.string.duty_push_deniedSub), tint = c.orangeText, chevron = true) {
            DutyPush.openSettings(ctx)
        }
    }
    if (on) {
        // 暂停一行（批次 261 §四：动作表四项不变；暂停中这一行换成「恢复」）。
        val now = System.currentTimeMillis()
        when (val p = DutyRules.pause(s.paused_until_ms, now)) {
            DutyPause.None -> rows += DutyRow(ctx.getString(R.string.duty_pause), value = ctx.getString(R.string.duty_pause_none), chevron = true) { askPause(ctx) }
            is DutyPause.Until -> rows += DutyRow(
                ctx.getString(R.string.duty_pause), value = ctx.getString(R.string.duty_pause_untilResume, DutyKit.whenText(p.ms, now, tr)),
                valueTint = c.warningText, chevron = true,
            ) { doResume(ctx) }
            DutyPause.Forever -> rows += DutyRow(
                ctx.getString(R.string.duty_pause), value = ctx.getString(R.string.duty_pause_foreverResume), valueTint = c.warningText, chevron = true,
            ) { doResume(ctx) }
        }
    }
    return DutySection("main", footer = ctx.getString(R.string.duty_main_help), rows = rows)
}

private fun doResume(ctx: Context) {
    if (busy) return
    dutyLaunch {
        val e = DutyStore.resume()
        if (e != null) dutyReport(e) else AppGraph.router.showToast(ctx.getString(R.string.duty_resumed))
    }
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
            UmbraPrompt.present(
                key = "duty-city", title = ctx.getString(R.string.duty_brief_city), message = ctx.getString(R.string.duty_brief_cityHelp),
                placeholder = ctx.getString(R.string.duty_brief_cityPlaceholder), initial = p.city, confirmLabel = ctx.getString(R.string.duty_save),
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
            value = tr.k(DutyRules.outcomeKey(last.outcome)), valueIcon = dutyOutcomeIconPath(last), valueTint = outcomeTint(c, last.outcome),
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

// MARK: 早报时间

/** 时间面板确认那一下才写。 */
private fun applyBriefTime(t: String) {
    if (!DutyRules.validHHMM(t) || busy) return
    DutyStore.state.value.list?.brief?.let { b -> patchParams(b) { it.copy(time = t) } }
}

// MARK: 日志一行（早报「上一次」与「全部日志」页共用，iOS DutyLogView.swift 的 DutyLogRow）

/** 结局的值色：做了 = 成功绿、失败 = 红（dangerText：前景字走 *Text 那支）、其余照旧灰。 */
internal fun outcomeTint(c: UmbraColorScheme, o: String?): Color? = when (o) {
    "ran" -> c.success
    "failed" -> c.dangerText
    else -> null
}

/** 一班的标题：「08:00 · 早报」（时刻空就只剩名字）。 */
internal fun dutyShiftTitle(s: DutyShift): String = listOf(DutyRules.hhmm(s.planned_at_ms), s.duty_name).filter { it.isNotEmpty() }.joinToString(" · ")

internal fun dutyLogRow(ctx: Context, tr: DutyTr, c: UmbraColorScheme, s: DutyShift, log: List<DutyShift>) = DutyRow(
    dutyShiftTitle(s), sub = DutyKit.logSub(s, log, tr), value = tr.k(DutyRules.stateKey(s)),
    valueIcon = dutyOutcomeIconPath(s), valueTint = outcomeTint(c, s.outcome),
) { presentWhy(ctx, s) }

/** 「为什么」用一个说明框展开：上下文摘要有七八行，就地展开会把一天的日志顶出一屏；框看完就走，回来还在原位。 */
internal fun presentWhy(ctx: Context, s: DutyShift) {
    dutyLaunch {
        val r = DutyStore.whyLines(s)
        if (r.failed != null) { AppGraph.router.showToast(r.failed.ifEmpty { ctx.getString(R.string.duty_why_loadFailed) }); return@dutyLaunch }
        UmbraNotice.present(dutyShiftTitle(s).ifEmpty { ctx.getString(R.string.duty_fb_why) }, r.lines.joinToString("\n"))
    }
}
