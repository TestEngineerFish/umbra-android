// 聊天里的执勤消息 —— 照搬 iOS Features/Duty/DutyChatViews.swift（契约 §15.2「端上 M0 要有」第 4 条、§16.8 第 4 条）：
//   · 所有带 `meta.duty` 的消息：气泡之上一行标头「执勤 · 早报 · 08:00」（`simple` 时跟一个「简版」小标）；
//   · `kind == "brief"` 且有 items：正文画成早报卡（分节列出 items），不再画那段 markdown；
//   · 每条右下「关注 / 不感兴趣」，「你关注的」里的「后续」能点回上一次那条，「还关注吗？」那节「继续 / 结束」；
//   · 卡底「有用 / 别发这类了 / 为什么现在发」；额度预报（forecast_note）画在卡的最后一行（muted + 图标）；「另外有 N 条建议」单起一行带「看建议」（批次 261 §四）；
//   · 额度用完的系统行下面「用一张周重置卡」。
//
// 时刻用 `planned_at_ms`（这一班本该什么时候值），不用消息落库时刻：8:00 的早报 8:02 才写完，标头仍该是「08:00」。
package com.geek.umbra.feature.duty

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraChoice
import com.geek.umbra.designsystem.UmbraChoiceItem
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraNotice
import com.geek.umbra.designsystem.UmbraStripAction
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.feature.chat.ChatApi
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert

/** 早报消息整块：标头 → 卡（右边留气泡的让位）→ 卡底三颗（iOS ChatThreadView.swift:440-469 的那一段）。 */
@Composable
internal fun DutyBriefMessage(m: DutyMeta) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        DutyHeaderLine(m)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DutyBriefCardView(m, Modifier.padding(end = UmbraDimens.bubbleGutter))
            DutyFooterActions(m)
        }
    }
}

/** 标头一行：时钟图标（橙，图标不是正文）+ 「执勤 · 名字 · 时刻」11.5/560 faint + 「简版」小标。 */
@Composable
internal fun DutyHeaderLine(m: DutyMeta) {
    val ctx = LocalContext.current
    val c = UmbraTheme.colors
    val text = (listOf(ctx.getString(R.string.duty_title)) + DutyRules.headerParts(m.name, m.plannedAtMs)).joinToString(" · ")
    Row(
        Modifier.clearAndSetSemantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        UmbraIcon(UmbraIconPaths.clock, c.orange, size = 12.dp)
        Text(text, color = c.faint, style = TextStyle(fontSize = 11.5.sp, fontWeight = UmbraType.weight560))
        if (m.simple) DutyTag(ctx.getString(R.string.duty_tag_simple), warning = true)
    }
}

/** 软底小标：「简版」（warningSoft + warningText）、「后续」（orangeSoft + orangeText）。 */
@Composable
internal fun DutyTag(text: String, warning: Boolean = false) {
    val c = UmbraTheme.colors
    Text(
        text, color = if (warning) c.warningText else c.orangeText,
        style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (warning) c.warningSoft else c.orangeSoft)
            .padding(horizontal = 6.dp, vertical = 1.5.dp),
    )
}

/** 早报卡：分节（天气 → 提醒 → 任务 → … → 还关注吗？），每节一个小标题、下面一条一行（标题 + 一句摘要 + 一排文字钮）。 */
@Composable
internal fun DutyBriefCardView(m: DutyMeta, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val c = UmbraTheme.colors
    val st by DutyStore.state.collectAsState()
    val shape = RoundedCornerShape(UmbraDimens.radiusCard)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.card).border(UmbraDimens.borderW, c.border, shape)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (sec in m.sections) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(ctx.getString(sectionRes(sec.section)), color = c.orangeText, style = UmbraType.fieldLabel)
                for (item in sec.items) BriefItem(m, item, sec.section, st)
            }
        }
        // 卡末尾（批次 261 §四 末行）：预报行 muted + 图标，不用橙 / 琥珀；「另外有 N 条建议」不是预报，单起一行 +
        // 次钮「看建议」直达执勤页（提议区就在总开关组之后）。服务端把两句接在同一格 forecast_note 里，拆法见 DutyKit.splitForecast；
        // 新消息带 proposals_n 就用它（DutyKit.forecastTail）。iOS DutyChatViews.swift:70
        val tail = DutyKit.forecastTail(m.forecastNote, m.proposalsN)
        if (tail.forecast.isNotEmpty()) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                UmbraIcon(UmbraIconPaths.info, c.muted, size = 13.dp, strokeWidth = 2f, modifier = Modifier.padding(top = 2.5.dp))
                Text(tail.forecast, color = c.muted, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp))
            }
        }
        tail.proposals?.let { n ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ctx.getString(R.string.duty_card_forecastMore, "$n"), color = c.muted, style = TextStyle(fontSize = 12.5.sp), modifier = Modifier.weight(1f))
                UmbraStripAction(ctx.getString(R.string.duty_card_seeProposals), c.orangeText, { AppGraph.router.jump(Route.MeDuty) })
            }
        }
    }
}

@Composable
private fun BriefItem(m: DutyMeta, item: DutyMeta.Item, section: String, st: DutyState) {
    val ctx = LocalContext.current
    val c = UmbraTheme.colors
    val key = DutyKit.cardKey(m.sourceId, item.id)
    // 点过的「不感兴趣」：这次进程里点的看内存，之前点的看服务端写回 meta 的那一格（重启 / 重拉 /history 之后）。
    val dismissed = key in st.cardNotInterested || item.notInterested
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (item.title.isNotEmpty()) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.isUpdate) Box(Modifier.padding(top = 2.dp)) { DutyTag(ctx.getString(R.string.duty_card_update)) }
                Text(item.title, color = if (dismissed) c.muted else c.text, style = TextStyle(fontSize = 14.5.sp, fontWeight = UmbraType.weight560, lineHeight = 20.sp))
            }
        }
        if (item.summary.isNotEmpty()) {
            Text(item.summary, color = c.muted, style = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp))
        }
        ItemActions(m, item, section, key, dismissed, st)
    }
}

/**
 * 一条下面那一排文字钮。来源 id（班次 id，一键跑的用运行 id）也没有时一颗都不画：没有对象可指。
 */
@Composable
private fun ItemActions(m: DutyMeta, item: DutyMeta.Item, section: String, key: String, dismissed: Boolean, st: DutyState) {
    if (m.sourceId.isEmpty() || item.id.isEmpty()) return
    val ctx = LocalContext.current
    val c = UmbraTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.Center) {
        val fid = item.followId
        if (section == "follow_check" && fid != null) {
            val answered = st.followAnswered[fid]
            if (answered != null) {
                DoneText(ctx.getString(if (answered == "ended") R.string.duty_card_followEnded else R.string.duty_card_followKept))
            } else {
                UmbraStripAction(ctx.getString(R.string.duty_card_keep), c.orangeText, { answer(fid, true) })
                UmbraStripAction(ctx.getString(R.string.duty_card_end), c.muted, { answer(fid, false) })
            }
        } else {
            val prev = item.prev
            if (item.isUpdate && prev != null) {
                // 点「后续」回到上一次那条：聊天的定位入口（ChatApi.focus，执勤消息都在秘书会话里）。
                UmbraStripAction(ctx.getString(R.string.duty_card_prev), c.orangeText, { ChatApi.focus("assistant", prev.messageId) })
            }
            if (item.followId != null || st.cardFollowed[key] != null) {
                DoneText(ctx.getString(R.string.duty_card_followed))
            } else if (section != "weather") {
                // 天气不给「关注」：每天本来就说，关注它没有「后续」可言（需求 §14.3.4：天气不进去重）。
                UmbraStripAction(ctx.getString(R.string.duty_card_follow), c.orangeText, {
                    dutyLaunch {
                        val e = DutyStore.follow(m.sourceId, item.id)
                        if (e != null) dutyReport(e) else AppGraph.router.showToast(ctx.getString(R.string.duty_card_followToast))
                    }
                })
            }
            if (dismissed) {
                DoneText(ctx.getString(R.string.duty_card_notInterestedDone))
            } else {
                UmbraStripAction(ctx.getString(R.string.duty_card_notInterested), c.muted, {
                    dutyLaunch { dutyReport(DutyStore.notInterested(m.sourceId, item.id)) }
                })
            }
        }
    }
}

/** 点过之后那一句（「已关注」「已记下」）：12.5 muted，占满 44 的行高，和旁边的钮对齐。 */
@Composable
private fun DoneText(text: String) {
    Box(Modifier.heightIn(min = UmbraDimens.tapMin), contentAlignment = Alignment.Center) {
        Text(text, color = UmbraTheme.colors.muted, style = TextStyle(fontSize = 12.5.sp))
    }
}

private fun answer(followId: String, keep: Boolean) {
    dutyLaunch { dutyReport(DutyStore.answerFollowCheck(followId, keep)) }
}

/** 节 → 小标题。认不出的节（服务端以后加的）归「其他」。iOS DutyChatViews.swift:182 */
private fun sectionRes(s: String): Int = when (s) {
    "weather" -> R.string.duty_sec_weather
    "reminders" -> R.string.duty_sec_reminders
    "tasks" -> R.string.duty_sec_tasks
    "news" -> R.string.duty_sec_news
    "money" -> R.string.duty_sec_money
    "inspirations" -> R.string.duty_sec_inspirations
    "knowledge" -> R.string.duty_sec_knowledge
    "web" -> R.string.duty_sec_web
    "follow" -> R.string.duty_sec_follow
    "follow_check" -> R.string.duty_sec_followCheck
    else -> R.string.duty_sec_other
}

/**
 * 执勤消息卡底三个操作（需求 §7.5）：有用 · 别发这类了 · 为什么现在发。给过反馈的那一班换成一句「已记下」。
 * 只认班次 id，不退回运行 id：一键跑没有班次，服务端的反馈 / 判断都挂在班次上，给了钮也点不成。
 */
@Composable
internal fun DutyFooterActions(m: DutyMeta) {
    if (m.shiftId.isEmpty()) return
    val ctx = LocalContext.current
    val c = UmbraTheme.colors
    val st by DutyStore.state.collectAsState()
    // 给过的反馈：这次进程里刚点的优先（它比消息 meta 新），没有就用服务端写回 meta 的那一格（重启之后）。
    val done = st.shiftFeedback[m.shiftId] ?: m.feedbackVerdict.ifEmpty { null }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (done != null) {
            DoneText(ctx.getString(if (done == "up") R.string.duty_fb_upDone else R.string.duty_fb_muteDone))
        } else {
            UmbraStripAction(ctx.getString(R.string.duty_fb_up), c.orangeText, { sendFeedback(m, "up", null) })
            UmbraStripAction(ctx.getString(R.string.duty_fb_mute), c.muted, {
                // 「别发这类了」弹三选一：停掉这一条 / 这周先别发 / 只是这次不需要。
                UmbraChoice.present(
                    title = ctx.getString(R.string.duty_fb_mute),
                    items = listOf(
                    UmbraChoiceItem(ctx.getString(R.string.duty_fb_mute_stop)) { sendFeedback(m, "mute", "stop") },
                    UmbraChoiceItem(ctx.getString(R.string.duty_fb_mute_week)) { sendFeedback(m, "mute", "week") },
                    UmbraChoiceItem(ctx.getString(R.string.duty_fb_mute_once)) { sendFeedback(m, "mute", "once") },
                ),
                    subtitle = m.name.ifEmpty { null },
                    key = "duty-mute-${m.shiftId}",
                )
            })
        }
        UmbraStripAction(ctx.getString(R.string.duty_fb_why), c.muted, {
            dutyLaunch {
                val r = DutyStore.whyLines(m)
                if (r.failed != null) { AppGraph.router.showToast(r.failed.ifEmpty { ctx.getString(R.string.duty_why_loadFailed) }); return@dutyLaunch }
                UmbraNotice.present(ctx.getString(R.string.duty_fb_why), r.lines.joinToString("\n"))
            }
        })
    }
}

private fun sendFeedback(m: DutyMeta, verdict: String, mute: String?) {
    dutyLaunch { dutyReport(DutyStore.feedback(m.shiftId, verdict, mute)) }
}

/**
 * 额度用完那一行下面的「用一张周重置卡」（契约 §16.3）。用前确认一次（写清后果），成功后变「已使用」。
 * 重置卡永远是人按的（需求 §14.4 第 5 条）—— 没卡时什么都不画，也不出购买引导。
 */
@Composable
internal fun DutyNoticeButton(n: DutyNotice) {
    val card = n.resetCard
    if (!n.isQuotaOut || card == null) return
    val ctx = LocalContext.current
    val c = UmbraTheme.colors
    val st by DutyStore.state.collectAsState()
    if (card.itemId in st.usedCards) {
        DoneText(ctx.getString(R.string.duty_notice_used))
        return
    }
    val name = card.name.ifEmpty { ctx.getString(R.string.duty_notice_cardFallback) }
    val using = card.itemId in st.usingCards
    UmbraStripAction(
        if (using) ctx.getString(R.string.duty_notice_using) else ctx.getString(R.string.duty_notice_use, name), c.orangeText,
        {
            if (card.itemId in DutyStore.state.value.usingCards) return@UmbraStripAction
            AppGraph.router.confirm(UmbraAlert(
                title = ctx.getString(R.string.duty_notice_confirmTitle, name),
                message = ctx.getString(R.string.duty_notice_confirmBody),
                cancelLabel = ctx.getString(R.string.duty_cancel),
                confirmLabel = ctx.getString(R.string.duty_notice_confirmOk),
            ) { dutyLaunch { dutyReport(DutyStore.useResetCard(card.itemId)) } })
        },
    )
}
