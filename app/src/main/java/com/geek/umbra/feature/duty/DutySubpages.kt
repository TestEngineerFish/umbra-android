// 执勤页收成推入行之后的下一级页 —— 照搬 iOS Features/Duty/DutySubpages.swift（设计侧批次 261 §四：「关注 / 规矩 / 日志 / 例程
// 收成推入行，页内不展开。下一级页照 PC 同构，列表左滑放静音 / 结束 / 删除」）+ 执勤页头上那几小块（页头状态、今天执勤预算、额度档位）。
//
//   · 例程（[DutyFlowsView]）：原来执勤页上的「流程」区原样搬进来（dutyFlowSection），点一行进 DutyFlowView；
//     例程在电脑上编辑，手机上不删不改，所以这一页没有左滑。
//   · 关注（[DutyFollowsView]）：左滑「删除 / 结束 / 静音」（UmbraRevealSwipeRow：只露钮、不滑到底自动执行，同 iOS allowsFullSwipe: false），
//     点一行仍是原来那张动作表（改重要度那一项只在那里）。
//   · 规矩（[DutyRulesView]）：静默时段、每天最多说几次、预算 —— 原来执勤页「规矩」组整组搬进来。
//   · 日志：推到原有的「全部日志」页（DutyLogView），不另起一页。
//
// 三页都 appear() / disappear()：执勤页推走之后它的 visible 计数会掉到 0，
// 这几页开着时 WS 来的 `duty_updated` 也要就地刷新，不然在关注页结束一条，回来之前列表都是旧的。
package com.geek.umbra.feature.duty

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraGroupCard
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraProgressBar
import com.geek.umbra.designsystem.UmbraPrompt
import com.geek.umbra.designsystem.UmbraRevealSwipeRow
import com.geek.umbra.designsystem.UmbraRowDivider
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraStripAction
import com.geek.umbra.designsystem.UmbraSwipeAction
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.navigation.UmbraAlert
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalTime

private val pagePadding = PaddingValues(start = UmbraDimens.pagePadX, end = UmbraDimens.pagePadX, top = UmbraDimens.sp5, bottom = 28.dp)

// MARK: - 图标

/** 判据回的图标名 → path（iOS `UmbraIconPath.named`）。表里没有就不画，不印一个错的形。 */
internal fun dutyIconPath(name: String): String? = when (name) {
    "check" -> UmbraIconPaths.check
    "xCircle" -> UmbraIconPaths.xCircle
    "clock" -> UmbraIconPaths.clock
    "minusCircle" -> UmbraIconPaths.minusCircle
    else -> null
}

/** 结局 pill 的图标（批次 261 §四：说了 / 建了任务 = 对勾，没事 = 横线圈，跳过 = 钟，没跑成 = 圆叉）。iOS DutyLogView.swift:DutyLogRow.icon */
internal fun dutyOutcomeIconPath(s: DutyShift): String? = DutyKit.outcomeIcon(s)?.let(::dutyIconPath)

// MARK: - 页头状态

/**
 * 页头那一行（批次 261 §四「页头状态加图标，只有文字不够」）：执勤中（success 实心点）/ 已暂停 · 到 X（warningText 暂停圈）/
 * 没开（faint 横线圈）。整行同色：图标和字是同一件事，分两个色读起来像两件事（227 §一 4）。
 * 「执勤中」用实心 success（不是 *Text 那支）：iOS 同，这一行是状态标记，点和字同色。
 */
@Composable
internal fun DutyHeadLine(state: DutyKit.HeadState) {
    val ctx = LocalContext.current
    val tr = rememberDutyTr()
    val c = UmbraTheme.colors
    val tint = when (state) {
        DutyKit.HeadState.On -> c.success
        is DutyKit.HeadState.PausedUntil, DutyKit.HeadState.PausedForever -> c.warningText
        DutyKit.HeadState.Off -> c.faint
    }
    val text = when (state) {
        DutyKit.HeadState.On -> ctx.getString(R.string.duty_head_on)
        is DutyKit.HeadState.PausedUntil -> ctx.getString(R.string.duty_head_paused, DutyKit.whenText(state.ms, System.currentTimeMillis(), tr))
        DutyKit.HeadState.PausedForever -> ctx.getString(R.string.duty_head_pausedForever)
        DutyKit.HeadState.Off -> ctx.getString(R.string.duty_head_off)
    }
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            when (state) {
                DutyKit.HeadState.On -> Box(Modifier.size(7.dp).clip(CircleShape).background(c.success))
                is DutyKit.HeadState.PausedUntil, DutyKit.HeadState.PausedForever -> UmbraIcon(UmbraIconPaths.pauseCircle, tint, size = 14.dp, strokeWidth = 2f)
                DutyKit.HeadState.Off -> UmbraIcon(UmbraIconPaths.minusCircle, tint, size = 14.dp, strokeWidth = 2f)
            }
        }
        Text(text, color = tint, style = TextStyle(fontSize = 15.sp))
    }
}

// MARK: - 今天执勤预算

/**
 * 「今天执勤预算」进度条（iOS 稿 01 总开关组第三行；口径按天，服务端 ae8cc4f）。橙色只给这根条（批次 261 §四）。
 * 设置行装不下一根条，单独一块紧贴总开关组画；budget 为 null（没会员 / 不限 / 老服务端）调用方就不画。iOS DutyViews.swift:budgetBar
 */
@Composable
internal fun DutyBudgetBar(b: DutyBudget) {
    val ctx = LocalContext.current
    val tr = rememberDutyTr()
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(UmbraDimens.radiusCard)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.card).border(UmbraDimens.borderW, c.border, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ctx.getString(R.string.duty_budgetToday), color = c.text, style = TextStyle(fontSize = 16.sp), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(DutyKit.budgetText(b, tr), color = c.muted, style = TextStyle(fontSize = 14.5.sp, fontFeatureSettings = "tnum"))
        }
        UmbraProgressBar(DutyKit.budgetFraction(b).toFloat(), c.orange, Modifier.clearAndSetSemantics {}, height = 6.dp)
    }
}

// MARK: - 额度档位

/**
 * 额度档位那一块（批次 261 §四：档位是状态，不用橙字 —— 橙色只给预算进度条）。
 * 正常档不出这一块；节省 / 只保要紧 / 停：warningSoft 小标 + 三角图标 + 一句「做了什么、什么时候恢复」；
 * 次钮（批次 265）：预算用完「调高预算」/ 没会员「开会员」/ 不出。iOS DutySubpages.swift:82
 */
@Composable
internal fun DutyTierCard(tag: String, detail: String?, action: DutyKit.TierAction, onAction: () -> Unit) {
    val ctx = LocalContext.current
    val c = UmbraTheme.colors
    val offers = action != DutyKit.TierAction.None
    val shape = RoundedCornerShape(UmbraDimens.radiusCard)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.card).border(UmbraDimens.borderW, c.border, shape)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = if (offers) 0.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(c.warningSoft).padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            UmbraIcon(UmbraIconPaths.alertTriangle, c.warningText, size = 12.dp, strokeWidth = 2f)
            Text(tag, color = c.warningText, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
        }
        if (!detail.isNullOrEmpty()) Text(detail, color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp))
        if (offers) {
            // iOS UmbraStripAction(align: .leading)：Android 那一颗自带左右 8 内边距，往左收回去让字和上面对齐。
            UmbraStripAction(
                ctx.getString(if (action == DutyKit.TierAction.Membership) R.string.duty_tier_join else R.string.duty_tier_raise),
                c.orangeText, onAction, Modifier.offset(x = (-8).dp),
            )
        }
    }
}

// MARK: - 例程

/** 例程列表（iOS DutyFlowsView）：原来执勤页上的「流程」区原样搬进来。 */
@Composable
internal fun DutyFlowsView() {
    val st by DutyStore.state.collectAsState()
    val ctx = LocalContext.current
    val tr = rememberDutyTr()
    val c = UmbraTheme.colors
    DisposableEffect(Unit) {
        DutyStore.appear()
        onDispose { DutyStore.disappear() }
    }
    UmbraScreen(title = ctx.getString(R.string.duty_flow), onBack = { AppGraph.router.back() }, spacing = UmbraDimens.sp6, contentPadding = pagePadding) {
        dutySection(dutyFlowSection(ctx, tr, c, st.flows ?: emptyList()))
    }
}

// MARK: - 规矩

private enum class RulesTimeField { QuietStart, QuietEnd }

/** 规矩（iOS DutyRulesView）：静默时段、每天最多说几次、预算。 */
@Composable
internal fun DutyRulesView() {
    val st by DutyStore.state.collectAsState()
    val ctx = LocalContext.current
    var timeField by remember { mutableStateOf<RulesTimeField?>(null) }
    DisposableEffect(Unit) {
        DutyStore.appear()
        onDispose { DutyStore.disappear() }
    }
    val title = ctx.getString(R.string.duty_rules)
    UmbraScreen(title = title, onBack = { AppGraph.router.back() }, spacing = UmbraDimens.sp6, contentPadding = pagePadding) {
        val s = st.list?.settings
        if (s != null) {
            dutySection(DutySection(
                "rules", footer = ctx.getString(R.string.duty_rules_help),
                rows = listOf(
                    DutyRow(ctx.getString(R.string.duty_quietStart), value = s.quiet_start, mono = true, chevron = true) { timeField = RulesTimeField.QuietStart },
                    DutyRow(ctx.getString(R.string.duty_quietEnd), value = s.quiet_end, mono = true, chevron = true) { timeField = RulesTimeField.QuietEnd },
                    DutyRow(ctx.getString(R.string.duty_nudges), value = ctx.getString(R.string.duty_nudges_value, "${s.daily_nudges}"), chevron = true) { askNudges(ctx, s) },
                    DutyRow(ctx.getString(R.string.duty_budget), value = ctx.getString(R.string.duty_budget_value, DutyRules.pctText(s.budget_pct)), chevron = true) {
                        askBudget(ctx, s)
                    },
                ),
            ))
        } else {
            item(key = "loading") { DutyIntro(st.loadError ?: ctx.getString(R.string.duty_loading)) }
        }
    }
    // 时间面板确认时才写；初值取那一格当前的值（认不出给 08:00，同 iOS DutyTime.date）。
    timeField?.let { f ->
        val l = st.list?.settings
        val (label, raw) = when (f) {
            RulesTimeField.QuietStart -> R.string.duty_quietStart to (l?.quiet_start ?: "22:30")
            RulesTimeField.QuietEnd -> R.string.duty_quietEnd to (l?.quiet_end ?: "07:30")
        }
        DutyTimePickerDialog(ctx.getString(label), parseHHMM(raw, LocalTime.of(8, 0)), { timeField = null }) { t ->
            val v = t.hhmm()
            if (DutyRules.validHHMM(v) && !DutyStore.state.value.busy) {
                val key = if (f == RulesTimeField.QuietEnd) "quiet_end" else "quiet_start"
                dutyLaunch { dutyReport(DutyStore.putSettings(buildJsonObject { put(key, v) })) }
            }
        }
    }
}

/** 「每天最多主动说几次」（契约 §16.1 `duty.dailyNudges`，回包键名 daily_nudges，2026-10-16 对齐）。 */
private fun askNudges(ctx: Context, s: DutySettings) {
    dutyPresentList("nudges", ctx.getString(R.string.duty_nudges), ctx.getString(R.string.duty_nudges_sub), DutyKit.nudgeOptions.map { v ->
        DutySheetItem(ctx.getString(R.string.duty_nudges_value, "$v"), checked = s.daily_nudges == v) {
            if (!DutyStore.state.value.busy) dutyLaunch { dutyReport(DutyStore.putSettings(buildJsonObject { put("daily_nudges", v) })) }
        }
    })
}

/** 预算面板。执勤页档位那一块的「调高预算」也开这一张（同一个设置，同一张面板）。iOS DutyRulesView.askBudget */
internal fun askBudget(ctx: Context, s: DutySettings) {
    dutyPresentList("budget", ctx.getString(R.string.duty_budget), ctx.getString(R.string.duty_budget_sub), DutyRules.budgetOptions.map { v ->
        DutySheetItem(ctx.getString(R.string.duty_budget_value, "$v%"), checked = Math.round(s.budget_pct).toInt() == v) {
            if (!DutyStore.state.value.busy) dutyLaunch { dutyReport(DutyStore.putSettings(buildJsonObject { put("budget_pct", v) })) }
        }
    })
}

// MARK: - 关注

/** 关注列表（iOS DutyFollowsView）：左滑删除 / 结束 / 静音，点一行出完整动作表，末行「加一个关注」；下拉重拉。 */
@Composable
internal fun DutyFollowsView() {
    val st by DutyStore.state.collectAsState()
    val ctx = LocalContext.current
    val tr = rememberDutyTr()
    val c = UmbraTheme.colors
    var refreshing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        DutyStore.appear()
        onDispose { DutyStore.disappear() }
    }
    UmbraScreen(
        title = ctx.getString(R.string.duty_follow), onBack = { AppGraph.router.back() }, spacing = UmbraDimens.sp2, contentPadding = pagePadding,
        refreshing = refreshing,
        onRefresh = { refreshing = true; dutyLaunch { try { DutyStore.reload() } finally { refreshing = false } } },
    ) {
        val follows = DutyKit.sortFollows(st.follows ?: emptyList())
        item(key = "follows") {
            UmbraGroupCard {
                follows.forEach { f ->
                    FollowRow(ctx, tr, f)
                    UmbraRowDivider(Modifier.padding(start = 14.dp))
                }
                Box(
                    Modifier.fillMaxWidth().heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button) { askAdd(ctx) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(ctx.getString(R.string.duty_follow_add), color = c.orangeText, style = TextStyle(fontSize = 16.sp))
                }
            }
        }
        item(key = "help") { DutyFooter(ctx.getString(R.string.duty_follow_help)) }
    }
}

/**
 * 一行关注。左滑三颗（批次 261 §四）：删除最外、结束、静音（只进早报）。删除走 router.confirm 二次确认。
 * 已结束的不给「结束 / 静音」，给「重新关注」。iOS 的 swipeActions 只有字；Android 地基的滑出钮要一颗图标，挑了意思最近的那颗。
 */
@Composable
private fun FollowRow(ctx: Context, tr: DutyTr, f: DutyFollow) {
    val c = UmbraTheme.colors
    val trailing = buildList {
        add(UmbraSwipeAction(ctx.getString(R.string.duty_follow_delete), UmbraIconPaths.trash, c.danger) { askDelete(ctx, f) })
        if (f.state == "ended") {
            add(UmbraSwipeAction(ctx.getString(R.string.duty_follow_restart), UmbraIconPaths.repeatArrows, c.orange) { write { DutyStore.patchFollow(f.id, state("active")) } })
        } else {
            add(UmbraSwipeAction(ctx.getString(R.string.duty_follow_end), UmbraIconPaths.stopCircle, c.faint) { write { DutyStore.patchFollow(f.id, state("ended")) } })
            add(UmbraSwipeAction(
                ctx.getString(if (f.state == "quiet") R.string.duty_follow_unquiet else R.string.duty_follow_quietShort),
                UmbraIconPaths.moon, c.warning,
            ) { write { DutyStore.patchFollow(f.id, state(if (f.state == "quiet") "active" else "quiet")) } })
        }
    }
    UmbraRevealSwipeRow(trailing = trailing, resetKey = f) {
        Row(
            Modifier.fillMaxWidth().background(c.card).defaultMinSize(minHeight = UmbraDimens.rowMinH)
                .clickable(role = Role.Button) { askFollow(ctx, tr, f) }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(f.topic, color = if (f.state == "ended") c.muted else c.text, style = TextStyle(fontSize = 16.sp))
                Text(ctx.getString(R.string.duty_follow_sub, DutyKit.priorityText(f.importance, tr)), color = c.muted, style = TextStyle(fontSize = 12.5.sp))
            }
            Text(tr.k(DutyKit.followStateKey(f.state)), color = c.muted, style = TextStyle(fontSize = 14.5.sp))
        }
    }
}

private fun state(v: String): JsonObject = buildJsonObject { put("state", v) }

private fun write(op: suspend () -> String?) = dutyLaunch { dutyReport(op()) }

/** 一条关注的完整操作：改重要度 / 只进早报或恢复 / 结束 / 删除（左滑只放得下三颗，重要度只在这里）。 */
private fun askFollow(ctx: Context, tr: DutyTr, f: DutyFollow) {
    val items = mutableListOf(
        DutySheetItem(ctx.getString(R.string.duty_follow_importance), note = DutyKit.priorityText(f.importance, tr)) { askImportance(ctx, tr, f) },
    )
    if (f.state != "ended") {
        items += if (f.state == "quiet") DutySheetItem(ctx.getString(R.string.duty_follow_unquiet)) { write { DutyStore.patchFollow(f.id, state("active")) } }
        else DutySheetItem(ctx.getString(R.string.duty_follow_quiet), note = ctx.getString(R.string.duty_follow_quietNote)) { write { DutyStore.patchFollow(f.id, state("quiet")) } }
        items += DutySheetItem(ctx.getString(R.string.duty_follow_end)) { write { DutyStore.patchFollow(f.id, state("ended")) } }
    } else {
        items += DutySheetItem(ctx.getString(R.string.duty_follow_restart)) { write { DutyStore.patchFollow(f.id, state("active")) } }
    }
    items += DutySheetItem(ctx.getString(R.string.duty_follow_delete), destructive = true) { askDelete(ctx, f) }
    dutyPresentList("follow-${f.id}", f.topic, f.query.ifEmpty { null }, items)
}

/**
 * 删除要二次确认（批次 261 §四）。正文不用统一那句「移入回收站」：服务端删关注是物理删
 * （`DELETE /duties/follows/{id}`：「关注是轻东西，不进回收站」）—— 批次 265 定成「关注不进回收站，删除后就找不回了。」
 */
private fun askDelete(ctx: Context, f: DutyFollow) {
    AppGraph.router.confirm(UmbraAlert(
        title = ctx.getString(R.string.duty_follow_deleteTitle, f.topic),
        message = ctx.getString(R.string.duty_follow_deleteBody),
        cancelLabel = ctx.getString(R.string.duty_cancel),
        confirmLabel = ctx.getString(R.string.duty_follow_delete),
        destructive = true,
    ) { write { DutyStore.deleteFollow(f.id) } })
}

private fun askImportance(ctx: Context, tr: DutyTr, f: DutyFollow) {
    dutyPresentList("importance", ctx.getString(R.string.duty_follow_importance), ctx.getString(R.string.duty_follow_importanceSub), (5 downTo 1).map { v ->
        DutySheetItem(DutyKit.priorityText(v, tr), checked = f.importance == v) {
            write { DutyStore.patchFollow(f.id, buildJsonObject { put("importance", v) }) }
        }
    })
}

/** 手动加一个关注话题（iOS 系统 alert 里一格输入；Android 落成带输入框的底部弹层，同早报「城市」那一格）。 */
private fun askAdd(ctx: Context) {
    UmbraPrompt.present(
        key = "duty-follow", title = ctx.getString(R.string.duty_follow_add), message = ctx.getString(R.string.duty_follow_addHelp),
        placeholder = ctx.getString(R.string.duty_follow_addPlaceholder), confirmLabel = ctx.getString(R.string.duty_save),
    ) { raw ->
        val t = raw.trim()
        if (t.isNotEmpty()) write { DutyStore.addFollow(t) }
    }
}
