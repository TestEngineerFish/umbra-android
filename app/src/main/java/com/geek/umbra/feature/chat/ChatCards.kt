// 对话页里的各种卡（iOS: ChatThreadView.swift:1215-1942）：工具轨迹卡、任务进度卡、完成卡、执行前确认卡、错误卡、
// 识图那一轮的完成行 / 错误卡、取消收尾的琥珀「已动过」行、「接着跑」、知识库出处 chip。
package com.geek.umbra.feature.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraErrorCard
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraMotion
import com.geek.umbra.designsystem.UmbraProgressBar
import com.geek.umbra.designsystem.UmbraSpinningIcon
import com.geek.umbra.designsystem.UmbraStatus
import com.geek.umbra.designsystem.UmbraStatusBadge
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.feature.knowledge.KbApi
import com.geek.umbra.navigation.Route
import com.geek.umbra.net.ReasonKind
import kotlinx.coroutines.launch

/** 与同屏的轨迹卡 / 任务卡 / 确认卡同宽。 */
val CARD_W = 300.dp

private val cardShape = RoundedCornerShape(UmbraDimens.radiusCard)

@Composable
private fun cardModifier(border: Color = UmbraTheme.colors.border, bg: Color = UmbraTheme.colors.card) =
    Modifier.width(CARD_W).clip(cardShape).background(bg).border(UmbraDimens.borderW, border, cardShape)

/** 外链：交给系统浏览器（充值页、任务产出的非图片文件）。 */
fun openExternal(url: String) {
    runCatching { AppGraph.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** 工具轨迹卡：「工具轨迹 · N 步」，可展开，等宽，带序号。trace 是一行行字符串（不拆三段，那要改服务端协议）。 */
@Composable
fun TraceCard(a: ChatBlock.Assistant) {
    val c = UmbraTheme.colors
    val rot by animateFloatAsState(if (a.traceOpen) 90f else 0f, tween(UmbraMotion.tint), label = "trace")
    Column(cardModifier()) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.tapMin).clickable(role = Role.Button) { ChatStore.toggleTrace(a.id) }
                .padding(horizontal = UmbraDimens.sp4, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UmbraIcon(UmbraIconPaths.wrench, c.muted, size = 15.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.chat_toolTrace, a.trace.size), color = c.muted, style = TextStyle(fontSize = 13.5.sp, fontWeight = UmbraType.weight560), modifier = Modifier.weight(1f))
            UmbraIcon(UmbraIconPaths.chevronRight, c.muted, Modifier.rotate(rot), size = 16.dp, strokeWidth = 2.2f)
        }
        if (a.traceOpen) a.trace.forEachIndexed { i, line ->
            Box(Modifier.fillMaxWidth().background(c.borderSoft).padding(top = UmbraDimens.borderW)) {
                Row(Modifier.fillMaxWidth().background(c.card).padding(horizontal = UmbraDimens.sp4, vertical = UmbraDimens.sp3)) {
                    Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(c.chip), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = c.faint, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
                    }
                    Spacer(Modifier.width(UmbraDimens.sp3))
                    Text(line, color = c.muted, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 任务进度卡：状态图标 + 目标 + 徽标 + 进度条 + 消息（≤8 行）+ 百分比；可内嵌授权钮；收尾钮按 ChatTaskKit.cardAction。 */
@Composable
fun TaskCard(j: ChatBlock.Task, conv: String) {
    val c = UmbraTheme.colors
    val st = taskStatus(j.status)
    // 「收尾了」只认三种终态（按映射后的枚举认，不按「不是 running / pending」排除）
    val finished = st == UmbraStatus.Done || st == UmbraStatus.Failed || st == UmbraStatus.Cancelled
    val suspended = j.status == "suspended"
    val cardText = ChatTaskKit.suspendCardText(j.status, j.suspend)
    Column(
        cardModifier().clickable(enabled = suspended && !j.gone) { AppGraph.router.go(Route.TaskDetail(j.taskId)) }
            .padding(horizontal = 13.dp, vertical = UmbraDimens.sp4),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(st.soft()), contentAlignment = Alignment.Center) {
                if (st == UmbraStatus.Running) UmbraSpinningIcon(st.icon, st.fg(), 14.dp) else UmbraIcon(st.icon, st.fg(), size = 14.dp, strokeWidth = 2.1f)
            }
            Spacer(Modifier.width(UmbraDimens.sp3))
            Text(j.goal, color = c.text, style = TextStyle(fontSize = 14.5.sp, fontWeight = UmbraType.weight560, lineHeight = 21.sp), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(UmbraDimens.sp3))
            UmbraStatusBadge(st)
        }
        UmbraProgressBar(j.pct / 100f, st.bar())
        Row(verticalAlignment = Alignment.Bottom) {
            // 服务端的步骤结论可能长达几百字 —— 聊天卡是概览，限 8 行兜底，全文看任务详情
            Text(cardText ?: j.message, color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 19.5.sp), maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text("${j.pct}%", color = c.faint, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
        }
        j.confirmId?.let { ConfirmActions(it) }
        if (suspended) SuspendedCardAction(j) else if (finished) TaskCardAction(j, conv)
    }
}

/** 钮下的那一行失败话（iOS TaskRetryFailLine）。 */
@Composable
private fun FailLine(text: String) {
    val c = UmbraTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        UmbraIcon(UmbraIconPaths.alertOctagon, c.dangerText, Modifier.padding(top = 2.dp), size = 13.dp, strokeWidth = 2f)
        Spacer(Modifier.width(6.dp))
        Text(text, color = c.dangerText, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp))
    }
}

/** 「已不在」那一句（iOS TaskGoneNote）。 */
@Composable
private fun GoneNote(text: String) {
    val c = UmbraTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        UmbraIcon(UmbraIconPaths.info, c.muted, Modifier.padding(top = 2.dp), size = 13.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, color = c.muted, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp))
    }
}

/** 挂起卡收尾：就绪时一颗次级「接着跑」就地调接口；没成那一行写在钮下；已不在时只留那句话。 */
@Composable
private fun SuspendedCardAction(j: ChatBlock.Task) {
    val scope = rememberCoroutineScope()
    val show = !j.gone && ChatTaskKit.showsResume(j.status, j.suspend) && (j.resumeOutcome?.keepsResume ?: true)
    if (!(j.gone || show || j.resumeFailText != null)) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (j.gone) GoneNote(TaskRetryCopy.MISSING)
        if (show) UmbraButton(if (j.resuming) TaskSuspendCopy.RESUMING else TaskSuspendCopy.RESUME, {
            if (!j.resuming) scope.launch { ChatStore.resumeTask(j.taskId, ChatStore.ResumeSource.Card) }
        }, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary, height = 44.dp)
        if (!j.gone) j.resumeFailText?.let { FailLine(it) }
    }
}

/**
 * 任务卡收尾那颗钮（设计侧批次 222 §二 2e）：可重试类「重试任务」就地调接口；电脑操作任务「重新发起」；
 * 欠费有地址「去充值」外链；其余与完成态「查看结果」开详情。
 */
@Composable
private fun TaskCardAction(j: ChatBlock.Task, conv: String) {
    val scope = rememberCoroutineScope()
    // 上一次重试被服务端说死就不再给钮；operate 那一档不算说死（它换成「重新发起」）
    val blocked = j.retryOutcome?.let { !it.keepsRetry && it != TaskRetryOutcome.Operate } ?: false
    val action = ChatTaskKit.cardAction(j.status, if (j.status == "failed") j.failAction else TaskFailAction.AsBefore, blocked, j.operate, j.gone)
    // 补救类失败（批次 278，同 PC 聊天任务卡）：额度用完先给［用重置卡］，原来那颗（去买 / 查看结果）照旧，再加一颗「重试任务」——
    // 用完卡人要有地方接着跑。顺序同 PC failCardActions：重置 · 去买 · 重试。
    val failed = j.status == "failed" && !j.gone
    val fixRetry = ChatTaskKit.cardFixRetry(j.status, j.failReasonKind, blocked, j.gone, action)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (j.gone) GoneNote(TaskRetryCopy.MISSING)
        if (failed && j.failReasonKind == "quota_exhausted") UseResetCardButton(j.quotaWindow)
        when (action) {
            TaskCardAction.Retry -> UmbraButton(if (j.retrying) TaskRetryCopy.RETRYING else TaskRetryCopy.RETRY, {
                if (!j.retrying) scope.launch { ChatStore.retryTaskCard(j.taskId) }
            }, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary, height = 44.dp)
            TaskCardAction.Relaunch -> UmbraButton(TaskRetryCopy.RELAUNCH, {
                val text = ChatTaskKit.relaunchText(j.goal, null) ?: return@UmbraButton
                ChatStore.prefillRelaunch(text)
                // 卡在设备会话里时，原目标要回到和秘书的会话里去发
                if (conv != ChatStore.MAIN_CONV) AppGraph.router.jump(Route.ChatThread(ChatStore.MAIN_CONV))
            }, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary, height = 44.dp)
            is TaskCardAction.Topup -> UmbraButton(stringResource(chatKeyRes(j.failTopupKey)), { openExternal(action.url) },
                Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary, height = 44.dp, external = true)
            TaskCardAction.ViewResult -> UmbraButton("查看结果", { AppGraph.router.go(Route.TaskDetail(j.taskId)) },
                Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary, height = 44.dp)
            null -> Unit
        }
        if (fixRetry) UmbraButton(if (j.retrying) TaskRetryCopy.RETRYING else TaskRetryCopy.RETRY, {
            if (!j.retrying) scope.launch { ChatStore.retryTaskCard(j.taskId) }
        }, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary, height = 44.dp)
        if (!j.gone) j.retryOutcome?.message?.let { FailLine(it) }
    }
}

/** 助手「要接着跑吗？」下面那颗主钮（问话可能离任务卡很远）。条件没了就收掉，不补字。 */
@Composable
fun ResumeAskAction(taskId: String) {
    val asks by ChatStore.resumeAsks.collectAsState()
    val ask = asks[taskId] ?: ResumeAsk()
    val scope = rememberCoroutineScope()
    if (!ask.showsButton && ask.failText == null) return
    Column(Modifier.width(CARD_W), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ask.showsButton) UmbraButton(if (ask.resuming) TaskSuspendCopy.RESUMING else TaskSuspendCopy.RESUME, {
            if (!ask.resuming) scope.launch { ChatStore.resumeTask(taskId, ChatStore.ResumeSource.Ask) }
        }, Modifier.fillMaxWidth(), height = 44.dp)
        ask.failText?.let { FailLine(it) }
    }
}

/** 绿色完成卡：产出行 36×36 缩略图；图片 App 内预览，其他交系统打开。没产出时只留「任务完成」头一行。 */
@Composable
fun DoneCard(goal: String, results: List<Map<String, String>>, onOpenImage: (String, String) -> Unit) {
    val c = UmbraTheme.colors
    Column(cardModifier(border = c.success, bg = c.successSoft).padding(horizontal = 13.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            UmbraIcon(UmbraIconPaths.check, c.success, Modifier.padding(top = 2.dp), size = 15.dp, strokeWidth = 2.2f)
            Spacer(Modifier.width(8.dp))
            Text("任务完成：$goal", color = c.success, style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.5.sp))
        }
        if (results.isNotEmpty()) {
            val shape = RoundedCornerShape(12.dp)
            Column(Modifier.fillMaxWidth().clip(shape).background(c.card).border(UmbraDimens.borderW, c.success, shape)) {
                results.forEachIndexed { i, r ->
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(0.dp).background(c.borderSoft).heightIn(min = UmbraDimens.borderW, max = UmbraDimens.borderW))
                    ResultRow(r, onOpenImage)
                }
            }
        }
    }
}

private fun looksImage(url: String): Boolean {
    val path = url.substringBefore('?').lowercase()
    return listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".heic", ".bmp").any { path.endsWith(it) }
}

/** 产出行：36×36 前导块 + 名 + 等宽 meta（服务器路径，同名文件靠路径区分）+ chevron，行高 ≥52。 */
@Composable
private fun ResultRow(r: Map<String, String>, onOpenImage: (String, String) -> Unit) {
    val c = UmbraTheme.colors
    val url = r["url"] ?: ""
    val name = r["title"] ?: url.ifEmpty { "产出" }
    val isImg = looksImage(url)
    val full = if (url.isEmpty()) null else ChatImages.full(url)
    val meta = url.replace(Regex("^https?://[^/]+"), "")
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).clickable {
            if (full == null) return@clickable
            if (isImg) onOpenImage(full, name) else openExternal(full)
        }.padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(8.dp)
        Box(Modifier.size(36.dp).clip(shape).background(c.chip).border(UmbraDimens.borderW, c.borderSoft, shape), contentAlignment = Alignment.Center) {
            if (isImg && full != null) {
                // 内联缩略：截图任务的重点就是看那张图。失败退回图片图标，不留破图占位
                SubcomposeAsyncImage(
                    model = full, imageLoader = ChatImages.loader, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(36.dp),
                    error = { UmbraIcon(UmbraIconPaths.image, c.muted, size = 16.dp, strokeWidth = 1.8f) },
                    loading = { UmbraIcon(UmbraIconPaths.image, c.muted, size = 16.dp, strokeWidth = 1.8f) },
                )
            } else UmbraIcon(UmbraIconPaths.file, c.muted, size = 16.dp, strokeWidth = 1.8f)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = c.text, style = TextStyle(fontSize = 13.5.sp, fontWeight = UmbraType.weight560), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta.isNotEmpty()) Text(meta, color = c.faint, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        UmbraIcon(UmbraIconPaths.chevronRight, c.faint, size = 14.dp, strokeWidth = 2.2f)
    }
}

/** 批准 / 总是允许 / 拒绝。「拒绝」是描边红不是实心红（实心红只给确认弹窗的最终动作）。没发出去吐「没连上」。 */
@Composable
fun ConfirmActions(confirmId: String) {
    val notConnected = stringResource(R.string.chat_notConnected)
    fun toast(sent: Boolean) { if (!sent) AppGraph.router.showToast(notConnected) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        UmbraButton("批准", { toast(ChatStore.handleConfirm(confirmId, true)) }, Modifier.fillMaxWidth(), height = 44.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            UmbraButton("总是允许", { toast(ChatStore.handleConfirmAlways(confirmId)) }, Modifier.weight(1f), kind = UmbraButtonKind.Secondary, height = 44.dp)
            UmbraButton("拒绝", { toast(ChatStore.handleConfirm(confirmId, false)) }, Modifier.weight(1f), kind = UmbraButtonKind.DangerOutline, height = 44.dp)
        }
    }
}

/** 执行前确认卡：头部三态（需要确认 / 已批准执行中 / 已拒绝）+ 摘要 + 三颗钮。头部文字一律 *Text 那一支。 */
@Composable
fun ConfirmCard(cb: ChatBlock.Confirm) {
    val c = UmbraTheme.colors
    val (label, soft, ink, icon) = when (cb.resolved) {
        null -> Quad("需要执行前确认", c.warningSoft, c.warningText, UmbraIconPaths.shield)
        ConfirmStatus.Approved -> Quad("已批准，执行中…", c.orangeSoft, c.orangeText, UmbraIconPaths.spinnerArc)
        ConfirmStatus.Denied -> Quad("已拒绝", c.dangerSoft, c.dangerText, UmbraIconPaths.xCircle)
    }
    Column(cardModifier(border = soft)) {
        Row(Modifier.fillMaxWidth().background(soft).padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            UmbraIcon(icon, ink, size = 16.dp, strokeWidth = 2f)
            Spacer(Modifier.width(8.dp))
            Text(label, color = ink, style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold))
        }
        Column(Modifier.padding(horizontal = 13.dp, vertical = UmbraDimens.sp4), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(cb.summary, color = c.text, style = TextStyle(fontSize = 14.5.sp, lineHeight = 22.sp))
            if (cb.resolved == null) ConfirmActions(cb.confirmId)
        }
    }
}

private data class Quad(val a: String, val b: Color, val c: Color, val d: String)

/**
 * 错误卡「这一轮没能完成」。钮按 reason_kind 分档：欠费三类有 topup_url 才出外链；限流 / 模型服务抖动「再试一次」
 * （找不到能重发的那条就不出）；key 错 / 参数被拒 / 没启用无钮；无类别 / unknown「重新连接」。
 */
@Composable
fun ErrorCard(e: ChatBlock.Error) {
    val c = UmbraTheme.colors
    Column(cardModifier(border = c.dangerSoft).padding(horizontal = 13.dp, vertical = UmbraDimens.sp4), verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp3)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UmbraIcon(UmbraIconPaths.xCircle, c.dangerText, size = 15.dp, strokeWidth = 2f)
            Spacer(Modifier.width(7.dp))
            Text("这一轮没能完成", color = c.dangerText, style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold))
        }
        Text(e.text, color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 20.8.sp))
        when (e.kind) {
            ReasonKind.InsufficientBalance, ReasonKind.NeedMembership, ReasonKind.QuotaExhausted -> {
                // 额度用完：手里有对应窗口的卡就出［用重置卡］（不另确认，点了转圈；批次 278，照 PC），0 张不出、只留去买。
                if (e.kind == ReasonKind.QuotaExhausted) UseResetCardButton(e.window)
                e.topup?.let { url ->
                    UmbraButton(stringResource(chatKeyRes(ChatReasonRules.topupButtonKey(e.kind))), { openExternal(url) }, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary, height = 44.dp, external = true)
                }
            }
            ReasonKind.RateLimited, ReasonKind.UpstreamError -> if (ChatStore.turnToRetry(e.id) != null) {
                UmbraButton("再试一次", { ChatStore.retryTurn(e.id) }, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary, height = 44.dp)
            }
            ReasonKind.AuthFailed, ReasonKind.BadRequest, ReasonKind.Unavailable -> Unit
            ReasonKind.Unknown, null -> UmbraButton("重新连接", {
                AppGraph.socket.reconnect()
                AppGraph.router.showToast("正在重新连接")
            }, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary, height = 44.dp)
        }
    }
}

/** 错误卡上那颗［用重置卡］：0 张不出（批次 278 §二 ②）；没问过张数就出、点了再说。 */
@Composable
private fun UseResetCardButton(window: String?) {
    val offer = com.geek.umbra.feature.account.ResetCardOffer
    LaunchedEffect(Unit) { offer.maybeRefresh() }
    if (!com.geek.umbra.feature.account.ResetCardRules.showUseCard(window, offer.counts)) return
    UmbraButton(
        stringResource(if (offer.spending) R.string.android_quota_using else R.string.android_quota_useCard),
        { offer.useFromFailCard(window) }, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary, height = 44.dp, enabled = !offer.spending,
    )
}

/** 完成行 / 读图行的统一壳：一行小字 +（可选）一颗行内钮。行撑到真实的 44。 */
@Composable
private fun VisionLine(text: String, action: Pair<String, () -> Unit>? = null) {
    val c = UmbraTheme.colors
    Row(Modifier.defaultMinSize(minHeight = UmbraDimens.tapMin), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = c.faint, style = TextStyle(fontSize = 11.5.sp))
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.defaultMinSize(minHeight = UmbraDimens.tapMin).clickable(role = Role.Button, onClick = action.second), contentAlignment = Alignment.Center) {
                Text(action.first, color = c.orangeText, style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

private fun secsText(s: Double): String = if (s == Math.rint(s)) s.toInt().toString() else "%.1f".format(s)

/** 回复气泡下面那行（识图那一轮）：读图中 / 重读中 / manyImage 张数 / partial 部分失败 + 行内「重读这 N 张」。 */
@Composable
fun VisionFootnote(v: VisionState, conv: String, blockId: String) {
    when {
        v.reading -> VisionLine(if (v.readingLabel == "manyImage") stringResource(R.string.vision_readingMany, v.n) else stringResource(R.string.chat_visionReading))
        v.retrying > 0 -> VisionLine(stringResource(R.string.vision_retrying, v.retrying))
        v.label == "manyImage" -> VisionLine(
            if (v.wasAllFailed) stringResource(R.string.vision_doneCount, v.ok) else stringResource(R.string.vision_doneCountSecs, v.ok, secsText(v.secs)),
        )
        // 这一档不出错误卡（会把一次成功的回答框成失败）。ok==0 是「带图的文字消息全挂」那一支
        v.label == "partial" -> VisionLine(
            if (v.ok == 0) stringResource(R.string.vision_doneNoneRead, v.fail) else stringResource(R.string.vision_donePartial, v.ok, v.fail),
            stringResource(R.string.vision_retryN, v.fail) to { ChatStore.retryVision(conv, blockId) },
        )
    }
}

/** 识图错误卡（全失败 / 60 秒超时）：**原地替换占位气泡**。重读那颗按原因出；「去充值」按原因出、外链。 */
@Composable
fun VisionErrorCard(v: VisionState, conv: String, blockId: String) {
    val busy = v.retrying > 0
    val canRetry = v.messageId != null
    val count = maxOf(v.failedAtts.size, v.n)
    val actions = mutableListOf<Pair<String, () -> Unit>>()
    if (v.showsReread) {
        val label = when {
            busy -> stringResource(R.string.vision_retrying, v.retrying)
            canRetry -> stringResource(R.string.vision_retryN, count)
            else -> stringResource(R.string.vision_resendTimeout, count)
        }
        actions += label to { if (!busy) ChatStore.retryVision(conv, blockId) }
    }
    // 额度用完：［用重置卡］排在去买前面（PC failCardActions 的顺序：重置 · 去买 · 重试）。
    if (v.label == "allFailed" && !v.timedOut && v.reasonKind == ReasonKind.QuotaExhausted) {
        val offer = com.geek.umbra.feature.account.ResetCardOffer
        LaunchedEffect(Unit) { offer.maybeRefresh() }
        if (com.geek.umbra.feature.account.ResetCardRules.showUseCard(v.quotaWindow, offer.counts)) {
            actions += stringResource(if (offer.spending) R.string.android_quota_using else R.string.android_quota_useCard) to { offer.useFromFailCard(v.quotaWindow) }
        }
    }
    // 地基 UmbraErrorCard 的动作钮不带外链角标（iOS 次动作带 external-link），这里只给文字
    v.topupLink?.let { url -> actions += stringResource(chatKeyRes(ChatReasonRules.topupButtonKey(v.reasonKind))) to { openExternal(url) } }
    val base = when {
        v.timedOut -> stringResource(R.string.vision_timeoutReason)
        v.reasonKind?.let { ChatReasonRules.visionCopyKey(it) } != null -> stringResource(chatKeyRes(ChatReasonRules.visionCopyKey(v.reasonKind)!!))
        else -> v.reason.ifEmpty { stringResource(R.string.vision_failReasonUnknown) }
    }
    val reason = if (!v.timedOut && v.retried) stringResource(R.string.vision_retriedPrefix) + base else base
    // 「原始返回」只在第二段已经换成类别人话时给
    val raw = if (!v.timedOut && v.reasonKind?.let { ChatReasonRules.visionCopyKey(it) } != null && v.reason.isNotEmpty()) v.reason else null
    UmbraErrorCard(
        title = if (v.timedOut) stringResource(R.string.vision_timeoutTitle) else stringResource(R.string.vision_failTitle, v.n),
        body = reason, actions = actions, raw = raw, modifier = Modifier.width(CARD_W),
    )
}

/** 取消收尾的琥珀行：只在跑过**会留下东西**的工具时出，文案具体到那一条，并给一个能去看的钮。 */
@Composable
fun KeptToolsRow(tools: List<ChatToolRun>) {
    val c = UmbraTheme.colors
    val kept = ChatKeptTool.line(tools) ?: return
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.width(CARD_W).clip(shape).background(c.warningSoft).border(UmbraDimens.borderW, c.warning, shape).padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            UmbraIcon(UmbraIconPaths.alertTriangle, c.warningText, Modifier.padding(top = 2.dp), size = 16.dp)
            Spacer(Modifier.width(9.dp))
            Text(kept.text, color = c.warningText, style = TextStyle(fontSize = 13.sp, lineHeight = 20.8.sp))
        }
        val bShape = RoundedCornerShape(10.dp)
        Box(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.tapMin).clip(bShape).border(UmbraDimens.borderW, c.warning, bShape)
                .clickable(role = Role.Button) { AppGraph.router.jump(kept.route) },
            contentAlignment = Alignment.Center,
        ) { Text(kept.button, color = c.warningText, style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)) }
    }
}

/**
 * 「停之前它已经建好了提醒「…」。这条留着，不跟着撤销。」—— 只认会留下东西的工具；表与 PC chat.ts 的 KEPT_TOOLS 一一对应。
 * iOS: ChatViewModel.swift:2849 ChatKeptTool。
 */
object ChatKeptTool {
    data class Kept(val verb: Int, val route: Route, val button: Int)
    data class Line(val text: String, val route: Route, val button: String)

    val table: Map<String, Kept> = mapOf(
        "create_reminder" to Kept(R.string.chat_kept_createReminder, Route.RemList, R.string.chat_goSeeReminder),
        "update_reminder" to Kept(R.string.chat_kept_updateReminder, Route.RemList, R.string.chat_goSeeReminder),
        "delete_reminder" to Kept(R.string.chat_kept_deleteReminder, Route.RemList, R.string.chat_goSeeReminder),
        "add_money_entry" to Kept(R.string.chat_kept_addMoney, Route.MoneyList, R.string.chat_goSeeMoney),
        "add_phrase" to Kept(R.string.chat_kept_addPhrase, Route.MePhrases, R.string.chat_goSeePhrases),
        "save_inspiration" to Kept(R.string.chat_kept_saveIdea, Route.InspList, R.string.chat_goSeeIdeas),
        "create_task" to Kept(R.string.chat_kept_createTask, Route.TaskList, R.string.chat_goSeeTasks),
    )

    /** 从 args（JSON 串）里捞一个能指认对象的词：text / title / name；捞不到就只说动词，不编名字。超 24 字截断。 */
    fun objectOf(args: String): String {
        val o = runCatching { com.geek.umbra.net.UmbraJson.parseToJsonElement(args) as kotlinx.serialization.json.JsonObject }.getOrNull() ?: return ""
        fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        val raw = (s("text") ?: s("title") ?: s("name") ?: "").trim()
        if (raw.isEmpty()) return ""
        return AppGraph.context.getString(R.string.chat_keptObject, if (raw.length > 24) raw.take(24) + "…" else raw)
    }

    fun line(tools: List<ChatToolRun>): Line? {
        val kept = tools.mapNotNull { t -> table[t.name]?.let { t to it } }
        val first = kept.firstOrNull() ?: return null
        val ctx = AppGraph.context
        val parts = kept.map { (run, spec) -> ctx.getString(spec.verb) + objectOf(run.args) }
        val tail = ctx.getString(if (kept.size > 1) R.string.chat_toolsKeptTailMany else R.string.chat_toolsKeptTailOne)
        return Line(ctx.getString(R.string.chat_toolsKeptHead) + parts.joinToString(ctx.getString(R.string.chat_keptJoin)) + tail, first.second.route, ctx.getString(first.second.button))
    }
}

/**
 * 助手气泡下面那一组出处（meta.citations）。每颗一整行「标题 › 小节 · 第 N 页」，点开看原文（交知识库模块的 KbApi.openRaw）。
 * 一颗一行：标题常常很长，竖排让每颗都是一整条可点的行（热区 44）。
 */
@Composable
fun KbCitationChips(citations: List<KbCitation>) {
    val c = UmbraTheme.colors
    Column(Modifier.widthIn(max = CARD_W), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.kb_cite_head), color = c.faint, style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 2.dp))
        citations.forEach { ct ->
            val text = if (ct.page > 0) KbCitations.label(ct) + " · " + stringResource(R.string.kb_cite_page, ct.page) else KbCitations.label(ct)
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.tapMin).clip(RoundedCornerShape(10.dp)).background(c.orangeSoft)
                    .clickable(role = Role.Button) { KbApi.openRaw(ct.kbId, ct.path, ct.title) }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UmbraIcon(UmbraIconPaths.file, c.orangeText, size = 13.dp, strokeWidth = 2f)
                Spacer(Modifier.width(7.dp))
                Text(text, color = c.orangeText, style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
        }
    }
}
