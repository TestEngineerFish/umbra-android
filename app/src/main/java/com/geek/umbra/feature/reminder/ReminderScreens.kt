// 提醒模块的页面入口 —— 对应 iOS ReminderViews.swift 的列表 / 详情（就地编辑）/ 新建 / 表单 / 滑动完成。
// 路由分发表（navigation/RouteScreens.kt）只调这里的三个函数，签名不改。
package com.geek.umbra.feature.reminder

import androidx.compose.foundation.layout.widthIn
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraDateText
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraEmptyState
import com.geek.umbra.designsystem.UmbraErrorCard
import com.geek.umbra.designsystem.UmbraErrorStrip
import com.geek.umbra.designsystem.UmbraFieldLabel
import com.geek.umbra.designsystem.UmbraGroupCard
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraPickers
import com.geek.umbra.designsystem.UmbraRevealSwipeRow
import com.geek.umbra.designsystem.UmbraRowDivider
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraSegment
import com.geek.umbra.designsystem.UmbraSegmentedControl
import com.geek.umbra.designsystem.UmbraSettingRow
import com.geek.umbra.designsystem.UmbraSettingRowView
import com.geek.umbra.designsystem.UmbraSkeleton
import com.geek.umbra.designsystem.UmbraStripAction
import com.geek.umbra.designsystem.UmbraSwipeAction
import com.geek.umbra.designsystem.UmbraTagPill
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTone
import com.geek.umbra.designsystem.UmbraTopBarIcon
import com.geek.umbra.designsystem.UmbraTopBarText
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.designsystem.UmbraWhenTab
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import com.geek.umbra.net.ImageTier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToInt

private val todoOrder = listOf("已过期", "今天", "明天", "本周", "更远")
/** 完成段两组：「已完成」是这件事做完了，「重复已结束」是这套重复结束了（两端同序，PC GROUP_ORDER）。 */
private val doneOrder = listOf("已完成", "重复已结束")

// MARK: - 列表

/**
 * 提醒列表（Tab 根页）。分段「待办 / 已完成」带计数；待办按 已过期 → 今天 → 明天 → 本周 → 更远 分组；
 * 横幅：同步卡住 / 删除被拒 / 没通知权限 /（Android 独有）精确闹钟没授权。iOS: ReminderViews.swift:1089
 */
@Composable
fun RemListScreen() {
    val store = ReminderStore
    val router = AppGraph.router
    val items by store.items.collectAsState()
    val notifyOk by store.notifyAuthorized.collectAsState()
    val exactOk by store.exactAlarmAllowed.collectAsState()
    val firstSyncing by store.firstSyncing.collectAsState()
    val loadedOnce by store.loadedOnce.collectAsState()
    val refusedTombs by store.refusedTombIds.collectAsState()
    val locked by AppGraph.guard.locked.collectAsState()
    val offline by AppGraph.http.offline.collectAsState()
    var seg by rememberSaveable { mutableStateOf(0) }
    var leavingId by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val requestNotify = rememberNotificationPermission()

    LifecycleResumeEffect(Unit) {
        store.refreshAuthorization()
        // 离线时进页面重探一次：离线条只在真实请求上翻转，提醒是缓存优先、进页面本来一个请求都不发。
        if (AppGraph.http.offline.value) store.syncNow()
        onPauseOrDispose { }
    }

    /** 勾选动画：圈立即变绿 → 停 0.9 秒 → 数据真正翻转；完成给带「撤销」的吐司。iOS: ReminderViews.swift:1559 */
    fun toggleAnimated(r: Reminder) {
        if (leavingId == r.id) { leavingId = null; return } // 正在收合时又点一次 = 反悔
        if (r.done || leavingId != null) {
            store.setDone(r.id, !r.done)
            router.showToast(if (r.done) "已标回待办" else "「${r.text}」已完成")
            return
        }
        leavingId = r.id
        AppGraph.scope.launch {
            delay(900)
            if (leavingId != r.id) return@launch
            leavingId = null
            store.setDone(r.id, true)
            router.showToast("「${r.text}」已完成", "撤销") { store.setDone(r.id, false) }
        }
    }

    // 首屏三态：有内容先给内容；没内容 + 首次同步在路上 → 骨架；没内容 + 没成功同步过 → 错误卡。
    // 未登录（单机）不算「没同步成功」—— 单机本来就不同步，直接给空态（提醒是本地功能）。
    val hasContent = items.isNotEmpty()
    val phase = when {
        hasContent -> 0
        firstSyncing && !locked -> 1
        !loadedOnce && !locked -> 2
        else -> 0
    }
    val pool = items.filter { if (seg == 0) !it.done else it.done }
    val groups = (if (seg == 0) todoOrder else doneOrder).mapNotNull { name -> pool.filter { it.group == name }.takeIf { it.isNotEmpty() }?.let { name to it } }

    UmbraScreen(
        title = "提醒",
        actions = { UmbraTopBarIcon(UmbraIconPaths.plus, "新建", { router.go(Route.RemEdit(null)) }, tint = UmbraTheme.colors.orange) },
        refreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; store.reloadAsync(); refreshing = false } },
        topContent = if (offline && !locked && phase == 0) {
            { UmbraErrorStrip("${stringResource(R.string.state_offline_title)} · ${stringResource(R.string.state_offline_reason)}", tone = UmbraTone.Warning, action = stringResource(R.string.state_retry) to { store.reload() }) }
        } else null,
    ) {
        when (phase) {
            1 -> { item("skeleton") { UmbraSkeleton() }; return@UmbraScreen }
            2 -> {
                item("failed") {
                    UmbraErrorCard(
                        title = "同步不上提醒",
                        body = stringResource(if (offline) R.string.state_failed_reason_offline else R.string.state_failed_reason_server),
                        actions = listOf(stringResource(R.string.state_retry) to { store.reload() }),
                    )
                }
                return@UmbraScreen
            }
        }
        item("seg") {
            UmbraSegmentedControl(
                listOf(UmbraSegment("待办", items.count { !it.done }), UmbraSegment("已完成", items.count { it.done })), seg, { seg = it },
            )
        }
        if (store.syncStuck) item("syncBanner") { SyncBanner(offline) }
        if (refusedTombs.isNotEmpty()) item("refusedTombs") { RefusedTombsBanner(refusedTombs) }
        if (notifyOk == false && items.isNotEmpty()) item("perm") {
            WarnBanner(
                UmbraIconPaths.alertTriangle, "没有通知权限，到点不会响", "去系统设置 › 通知 › Umbra 打开就行。",
                listOf("去开启" to { requestNotify() }),
            )
        }
        // Android 独有：Android 12+ 精确闹钟要用户授权（14+ 默认拒）。没授权时退非精确闹钟，可能晚几分钟。
        if (!exactOk && items.any { !it.done }) item("exact") {
            val ctx = LocalContext.current
            WarnBanner(
                UmbraIconPaths.clock, "没开「闹钟和提醒」，到点可能晚几分钟才响", "系统默认不让 App 准点叫你，去设置里允许 Umbra 就能准时。",
                listOf("去授权" to {
                    if (Build.VERSION.SDK_INT >= 31) runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                    }
                }),
            )
        }
        if (groups.isEmpty()) {
            item("empty") {
                UmbraEmptyState(
                    UmbraIconPaths.bell,
                    if (seg == 0) "还没有提醒" else "还没有完成过的提醒",
                    hint = if (seg == 0) "点右上角加一条。到点会用系统通知叫你，这一版提醒存在这台手机上，还没有跟服务端同步。" else "完成过的提醒会收在这里。",
                    action = if (seg == 0) "加一条提醒" else null,
                    onAction = { router.go(Route.RemEdit(null)) },
                )
            }
        } else {
            groups.forEach { (name, rows) ->
                item("g-$name") {
                    val c = UmbraTheme.colors
                    Column {
                        // 组头「已过期  3 条」：这个数是这一组的行数，不是角标（三个数口径不同，别合并）。
                        Row(Modifier.padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            val ink = if (name == "已过期") c.dangerText else c.muted
                            Text(name, color = ink, style = UmbraType.fieldLabel.copy(fontWeight = FontWeight.SemiBold))
                            Spacer(Modifier.width(8.dp))
                            Text("${rows.size} 条", color = ink, style = UmbraType.fieldLabel.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
                        }
                        UmbraGroupCard {
                            rows.forEachIndexed { i, r ->
                                if (i > 0) UmbraRowDivider()
                                ReminderRow(r, leavingId == r.id, onToggle = { toggleAnimated(r) })
                            }
                        }
                        if (name == "重复已结束") {
                            Text(
                                "这套重复到期了，不再自动生成。里面的每一条是不是被处理过，看它自己那一行。",
                                color = c.muted, style = TextStyle(fontSize = 11.5.sp), modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 列表一行：点圈 = 完成，点行 = 进详情；左滑「删除 / 再等 10 分钟」，右滑「完成 / 取消完成」（都只露按钮）。 */
@Composable
private fun ReminderRow(r: Reminder, leaving: Boolean, onToggle: () -> Unit) {
    val c = UmbraTheme.colors
    val router = AppGraph.router
    val store = ReminderStore
    val trailing = buildList {
        add(UmbraSwipeAction("删除", UmbraIconPaths.trash, c.danger) {
            router.confirm(UmbraAlert(
                title = "删除「${r.text}」？", message = "删除后移入回收站，保留 30 天，之后彻底删除。",
                confirmLabel = "移入回收站", destructive = true,
            ) { store.delete(r.id); router.showToast("已移入回收站 · 保留 30 天") })
        })
        if (r.canSnoozeNow) add(UmbraSwipeAction("再等 10 分钟", UmbraIconPaths.clock, c.warning) {
            store.snooze(r.id); router.showToast("好，10 分钟后再叫你")
        })
    }
    val checked = r.done || leaving
    val leading = listOf(UmbraSwipeAction(if (checked) "取消完成" else "完成", if (checked) UmbraIconPaths.x else UmbraIconPaths.check, c.success, onClick = onToggle))
    UmbraRevealSwipeRow(leading = leading, trailing = trailing, resetKey = r) {
        Row(
            Modifier.fillMaxWidth().background(c.card).clickable { router.go(Route.RemDetail(r.id)) }
                .defaultMinSize(minHeight = UmbraDimens.rowMinH).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // 勾选圈：热区就是视觉的 24×24（嵌在整行可点里，按外层净空定，不撑 44 —— iOS 同一个悬案）。
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(if (checked) c.success else Color.Transparent)
                    .border(1.8.dp, if (checked) c.success else c.border, CircleShape)
                    .clickable(role = Role.Checkbox, onClick = onToggle)
                    .semantics { contentDescription = if (r.done) "标记为未完成" else "标记为完成" },
                contentAlignment = Alignment.Center,
            ) { if (checked) UmbraIcon(UmbraIconPaths.check, Color.White, size = 13.dp, strokeWidth = 3.4f) }
            Spacer(Modifier.width(UmbraDimens.sp4))
            Column(Modifier.weight(1f)) {
                Text(
                    r.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (r.done) c.faint else c.text,
                    style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, textDecoration = if (r.done) TextDecoration.LineThrough else null),
                )
                Spacer(Modifier.height(5.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.timeLabel, color = if (r.overdue) c.dangerText else c.muted, style = TextStyle(fontSize = 13.sp))
                    if (r.overdue) Chip("已过期", c.dangerText, c.dangerSoft)
                    if (r.pushRejection != null) Chip("服务端不收这条", c.warningText, c.warningSoft, UmbraIconPaths.alertTriangle)
                    if (r.repeatRule != "不重复") Chip(r.repeatLabel + r.repeatEndSuffix, c.orangeText, c.orangeSoft, UmbraIconPaths.repeatArrows)
                    if (r.atts.isNotEmpty()) Chip("${r.atts.size}", c.muted, c.chip, UmbraIconPaths.image)
                }
            }
        }
    }
}

/** 行上的小胶囊：图标 + 文字（状态不只靠颜色）。 */
@Composable
private fun Chip(text: String, fg: Color, bg: Color, icon: String? = null) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { UmbraIcon(icon, fg, size = 10.dp, strokeWidth = 2.4f); Spacer(Modifier.width(4.dp)) }
        Text(text, color = fg, maxLines = 1, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
    }
}

/** warning 软底横幅：标题 12.5/560 + 说明 12 + 文字动作钮。 */
@Composable
private fun WarnBanner(icon: String, title: String, body: String, actions: List<Pair<String, () -> Unit>>) {
    val c = UmbraTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(UmbraDimens.radiusInput)).background(c.warningSoft).padding(horizontal = UmbraDimens.sp4, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        UmbraIcon(icon, c.warningText, size = 15.dp, strokeWidth = 2f, modifier = Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(UmbraDimens.sp3))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.warningText, style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560))
            Spacer(Modifier.height(4.dp))
            Text(body, color = c.warningText, style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp))
            if (actions.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(UmbraDimens.sp4)) {
                actions.forEach { (l, a) -> UmbraStripAction(l, c.warningText, a) }
            }
        }
        if (actions.size == 1) UmbraStripAction(actions[0].first, c.warningText, actions[0].second)
    }
}

/**
 * 同步没成那一档：「有 N 条提醒还没传到别的设备」+「它们在这台设备上到点照样会响」（第二句是这条横幅存在的全部理由）。
 * warning 不用 danger：它和权限横幅会同屏，较轻的问题不能比较重的更红。
 */
@Composable
private fun SyncBanner(offline: Boolean) {
    // unsynced 读的是 items 快照：只靠上层订阅的话 offline 不变这里会被跳过，「有 N 条」停在旧数。订阅并经由它读。
    val items by ReminderStore.items.collectAsState()
    val u = items.let { ReminderStore.unsynced }
    val n = u?.first ?: 0
    val hours = maxOf(1L, (System.currentTimeMillis() - (u?.second ?: System.currentTimeMillis())) / 3_600_000)
    val why = if (offline) "这台设备现在没有网络。" else "已经 $hours 小时没能传上去。"
    WarnBanner(UmbraIconPaths.shareUp, "有 $n 条提醒还没传到别的设备", why + "它们在这台设备上到点照样会响。", listOf("重试同步" to {
        AppGraph.router.showToast("在重试 —— 传上去了这条提示就会自己消失")
        ReminderStore.syncNow()
    }))
}

/** 服务端不收的删除：汇总一句、两个动作（都不过确认：「放弃改动」在这里是把删掉的拉回来，不丢东西）。 */
@Composable
private fun RefusedTombsBanner(refused: Map<String, ReminderPushRejection>) {
    val router = AppGraph.router
    WarnBanner(
        UmbraIconPaths.alertTriangle, "有 ${refused.size} 条删除服务端不收",
        "这几条在这台设备上已经删了，别的设备上还在，也不会再自动重试。" + ReminderRejectCopy.said(refused.values.firstOrNull()),
        listOf(
            "再试一次" to { router.showToast("在重试 —— 传上去了这条提示就会自己消失"); ReminderStore.retryRefusedTombs() },
            "放弃改动" to { router.showToast("已放弃本机改动，那几条会从服务端拉回来"); ReminderStore.discardRefusedTombs() },
        ),
    )
}

/**
 * 通知权限：Android 13+ 是运行时权限。请求被拒过（系统不再弹）时退到 App 的通知设置页。
 * 返回一个「去开启」动作。
 */
@Composable
private fun rememberNotificationPermission(): () -> Unit = { com.geek.umbra.app.NotificationPermission.enableTapped() }

/** 第一次建提醒时才要权限（用户正想让它响，最容易被同意）。已经有了就什么都不做。 */
@Composable
private fun rememberAskNotificationOnce(): () -> Unit = { com.geek.umbra.app.NotificationPermission.requestIfNeeded() }

// MARK: - 详情（就地编辑）

/**
 * 提醒详情。查看态：标题是主角、备注紧跟、时间放大；字段卡只留「重复 / 提前提醒」；来源与创建时间降为脚注。
 * 底部吸底栏：滑动完成 / 稍后提醒（点 = 10 分钟，长按换时间）；已完成的给「标回待办」。
 * 「编辑」就地切换成表单（同页，不推新页）；已完成的没有「编辑」，只有「再来一条」。iOS: ReminderViews.swift:1605
 */
@Composable
fun RemDetailScreen(id: String) {
    val store = ReminderStore
    val router = AppGraph.router
    val all by store.items.collectAsState()
    val item = all.firstOrNull { it.id == id }
    var draft by remember { mutableStateOf<Reminder?>(null) }
    var base by remember { mutableStateOf<Reminder?>(null) }
    var pending by remember { mutableStateOf<List<ReminderPendingImage>>(emptyList()) }
    var fails by remember { mutableStateOf<List<ReminderAttFail>>(emptyList()) }
    var landed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val editing = draft != null

    /** 保存：先传图（传成的记进这一行，传不成的留在编辑态等重发）—— **失败不拦保存**；落库的是「最新那行 + 这一页真改过的字段」。 */
    fun saveEdit() {
        val d0 = draft ?: return
        if (saving) return
        val b = base ?: item ?: d0
        var d = d0.copy(text = d0.text.trim())
        if (d.text.isEmpty()) { router.showToast("提醒内容不能是空的"); return }
        d.repeatEndProblem?.let { router.showToast(it); return }
        d = d.copy(note = d.note.trim())
        saving = true
        AppGraph.scope.launch {
            try {
                var bad = emptyList<ReminderAttFail>()
                var e = d
                if (pending.isNotEmpty()) {
                    val (atts, uploaded, failed) = ReminderAttUploader.upload(pending, d.atts)
                    e = ReminderDraftMerge.afterUpload(d, atts, draft ?: d)
                    pending = pending.filter { it.id !in uploaded }
                    bad = failed
                }
                val saved = ReminderDraftMerge.merge(b, e, store.item(id))
                store.save(saved)
                fails = bad
                if (bad.isNotEmpty()) {
                    draft = saved; base = saved; landed = true
                    router.showToast(ReminderSaveToast.partial(bad.size))
                    return@launch
                }
                draft = null; base = null; landed = false
                router.showToast(ReminderSaveToast.saved)
            } finally { saving = false }
        }
    }

    /** 就地重发一张：只传这一张，成了就并进这条提醒再存一次（走保存同一条路）。 */
    fun retry(p: ReminderPendingImage, tier: ImageTier?) {
        val d = draft ?: return
        if (saving) return
        val b = base ?: item ?: d
        saving = true
        AppGraph.scope.launch {
            try {
                val (atts, uploaded, failed) = ReminderAttUploader.upload(listOf(p), d.atts, tier)
                val e = ReminderDraftMerge.afterUpload(d, atts, draft ?: d)
                pending = pending.filter { it.id !in uploaded }
                fails = fails.filter { it.id != p.id } + failed
                val saved = ReminderDraftMerge.merge(b, e, store.item(id))
                store.save(saved)
                draft = saved; base = saved
                if (failed.isEmpty()) router.showToast("这张传上了")
            } finally { saving = false }
        }
    }

    // 编辑态的系统返回 = 「取消」（iOS 编辑态藏返回，会丢改动的出口只留一个）。
    BackHandler(enabled = editing) { draft = null; base = null; pending = emptyList(); fails = emptyList(); landed = false }

    UmbraScreen(
        title = if (editing) stringResource(R.string.reminder_title_edit) else "提醒详情",
        onBack = { router.back() },
        // 编辑态藏返回：会丢改动的出口只留「取消」一个。落过库之后它叫「完成」（点它不会撤销任何东西）。
        leading = if (editing) ({
            UmbraTopBarText(stringResource(if (landed) R.string.common_done else R.string.reminder_btn_cancel), {
                draft = null; base = null; pending = emptyList(); fails = emptyList(); landed = false
            })
        }) else null,
        actions = {
            if (editing) {
                UmbraTopBarText(stringResource(if (saving) R.string.reminder_btn_saving else R.string.reminder_btn_save), { saveEdit() }, enabled = !saving, strong = true)
            } else if (item != null) {
                if (item.done) UmbraTopBarText("再来一条", { store.stashCloneTemplate(item.cloneAsNew()); router.go(Route.RemEdit(null)) }, strong = true)
                else UmbraTopBarText("编辑", { draft = item; base = item }, strong = true)
            }
        },
        bottomBar = if (!editing && item != null) ({ DetailBottomBar(item, id) }) else null,
    ) {
        val d = draft
        when {
            d != null -> item("form") {
                ReminderForm(
                    draft = d, onDraft = { f -> draft = draft?.let(f) },
                    pending = pending, onPending = { pending = it }, fails = fails, onFails = { fails = it },
                    creating = false, savedOnce = true, landed = landed, onRetry = ::retry,
                )
            }
            item != null -> preview(item)
            else -> item("gone") {
                // 端上在同步里发现这一条已经不在了（服务端这一族不发 404）。第二段说清它还找得回来。
                UmbraEmptyState(
                    UmbraIconPaths.bell, "这条提醒已经不在了",
                    hint = "可能是在电脑上把它删掉了 —— 删掉的提醒会在回收站里留 30 天，能找回来。",
                    action = "回到提醒列表", onAction = { router.back() },
                )
            }
        }
    }
}

/** 查看态。「服务端不收这条」放在最上（一条要人做决定的条幅不能压在主角后面）。 */
private fun androidx.compose.foundation.lazy.LazyListScope.preview(r: Reminder) {
    r.pushRejection?.let { why -> item("rejected") { RejectedCard(r, why) } }
    item("head") {
        val c = UmbraTheme.colors
        Column(Modifier.padding(top = UmbraDimens.sp4, bottom = 4.dp)) {
            Text(r.text, color = c.text, style = TextStyle(fontSize = 26.sp, fontWeight = UmbraType.weight650, lineHeight = 34.sp, letterSpacing = (-0.26).sp))
            if (r.note.isNotEmpty()) {
                Text(r.note, color = c.muted, style = TextStyle(fontSize = 15.sp, lineHeight = 24.sp), modifier = Modifier.padding(top = 8.dp))
            }
            // 过期时铃铛、时间、胶囊一起转警示色 —— 状态不只靠一个小胶囊表达。
            val ink = if (r.overdue) c.dangerText else c.text
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                UmbraIcon(UmbraIconPaths.bell, ink, size = 18.dp, strokeWidth = 2f)
                Spacer(Modifier.width(8.dp))
                Text(r.timeLabel, color = ink, style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold))
                if (r.overdue) {
                    Spacer(Modifier.width(8.dp))
                    Text("已过期", color = ink, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.dangerSoft).padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
        }
    }
    item("fields") {
        UmbraGroupCard {
            UmbraSettingRowView(UmbraSettingRow(stringResource(R.string.reminder_row_repeat), value = r.repeatRowValue))
            UmbraRowDivider(Modifier.padding(start = 14.dp))
            UmbraSettingRowView(UmbraSettingRow(stringResource(R.string.reminder_row_ahead), value = r.aheadRowValue))
        }
    }
    if (r.atts.isNotEmpty()) item("atts") { ReminderAttsPreviewRow(r.atts) }
    item("foot") {
        Text("来自${r.source} · 创建于 ${r.createdLabel}", color = UmbraTheme.colors.faint, style = TextStyle(fontSize = 12.sp), modifier = Modifier.padding(start = 4.dp))
    }
}

/** 「服务端不收这条」：warning 错误卡，「再试一次」/「放弃改动」（后者过确认，放弃后退回列表）。 */
@Composable
private fun RejectedCard(r: Reminder, why: ReminderPushRejection) {
    val router = AppGraph.router
    UmbraErrorCard(
        title = "服务端不收这条",
        body = "这一版的改动只留在这台设备上，到点照样会响，但不会再自动重试。" + ReminderRejectCopy.said(why),
        tone = UmbraTone.Warning,
        actions = listOf(
            "再试一次" to { router.showToast("在重试 —— 传上去了这条提示就会自己消失"); ReminderStore.retryRejected(r.id) },
            "放弃改动" to {
                router.confirm(UmbraAlert(
                    title = "放弃这条提醒在本机的改动？",
                    message = "会改回服务端上的那一版；服务端上没有这一条的话，它会从这台设备上删掉。",
                    confirmLabel = "放弃改动", destructive = true,
                ) { ReminderStore.discardRejected(r.id); router.showToast("已放弃本机改动"); router.back() })
            },
        ),
    )
}

/** 详情吸底栏。 */
@Composable
private fun DetailBottomBar(r: Reminder, id: String) {
    val c = UmbraTheme.colors
    val router = AppGraph.router
    Column(
        Modifier.fillMaxWidth().background(c.bg).padding(start = UmbraDimens.pagePadX, end = UmbraDimens.pagePadX, top = 10.dp, bottom = UmbraDimens.sp5),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(UmbraDimens.borderW).background(c.borderSoft))
        if (r.done) {
            UmbraButton("标回待办", {
                ReminderStore.setDone(r.id, false); router.showToast("已标回待办"); router.back()
            }, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary)
        } else {
            SlideToComplete {
                ReminderStore.setDone(r.id, true)
                router.showToast("已完成「${r.text}」")
                // 组件停 420ms 之后的回调：这期间人可能已经返回 / 切了 Tab，只在详情页仍在眼前时退。
                router.back(ifShowing = Route.RemDetail(id))
            }
            if (r.canSnoozeNow) {
                SnoozeButton(r)
                Text("长按「稍后提醒」可以换时间", color = c.faint, textAlign = TextAlign.Center, style = TextStyle(fontSize = 11.sp), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** 稍后提醒：点一下 = 10 分钟；长按 = 菜单换时间（10 分 / 30 分 / 1 小时 / 明早 9 点）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SnoozeButton(r: Reminder) {
    val c = UmbraTheme.colors
    val router = AppGraph.router
    var open by remember { mutableStateOf(false) }
    fun doSnooze(minutes: Int, say: String) {
        ReminderStore.snooze(r.id, minutes); router.showToast("好，${say}再叫你"); router.back()
    }
    fun tomorrowNine() {
        // 跨天用日历算，不拿 86400 秒硬加 —— 夏令时切换那天会差一小时。
        val zone = ZoneId.systemDefault()
        val nine = LocalDate.now(zone).plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        ReminderStore.snooze(r.id, nine); router.showToast("好，明早 9 点再叫你"); router.back()
    }
    Box {
        Box(
            Modifier.fillMaxWidth().height(UmbraDimens.buttonH).clip(RoundedCornerShape(50)).background(c.card)
                .border(UmbraDimens.borderW, c.border, RoundedCornerShape(50))
                .combinedClickable(role = Role.Button, onClick = { doSnooze(10, "10 分钟后") }, onLongClick = { open = true }),
            contentAlignment = Alignment.Center,
        ) { Text("稍后提醒 · 10 分钟后", color = c.text, style = TextStyle(fontSize = 15.5.sp, fontWeight = UmbraType.weight560)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.card, modifier = Modifier.widthIn(min = 180.dp)) {
            listOf(10 to "10 分钟后", 30 to "30 分钟后", 60 to "1 小时后").forEach { (m, l) ->
                DropdownMenuItem(text = { Text(l, color = c.text) }, onClick = { open = false; doSnooze(m, l) })
            }
            DropdownMenuItem(text = { Text("明早 9 点", color = c.text) }, onClick = { open = false; tomorrowNine() })
        }
    }
}

/**
 * 滑动完成：轨道 54 胶囊（orangeSoft 底 + orange 描边）、滑块 46 橙圆；拖过 82% 吸附成对勾、停 420ms 再回调；
 * 不到就 .28s cubic-bezier(.2,.8,.3,1) 回弹；中央「滑动完成」随进度 1-(x/90) 淡出，带 2.4s 扫光 + 双箭头同拍右移 5。
 * iOS: ReminderViews.swift UmbraSlideToComplete
 */
@Composable
fun SlideToComplete(onComplete: () -> Unit) {
    val c = UmbraTheme.colors
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val x = remember { Animatable(0f) }
    var done by remember { mutableStateOf(false) }
    val t = rememberInfiniteTransition(label = "slide")
    val shimmer by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "shimmer")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "pulse")
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(50)).background(c.orangeSoft)
            .border(UmbraDimens.borderW, c.orange, RoundedCornerShape(50)),
    ) {
        val knob = with(density) { 46.dp.toPx() }
        val travel = maxOf(1f, with(density) { maxWidth.toPx() } - knob - with(density) { 8.dp.toPx() })
        val fade = if (done) 0f else maxOf(0f, 1f - with(density) { x.value.toDp().value } / 90f)
        Row(Modifier.align(Alignment.Center).alpha(fade), verticalAlignment = Alignment.CenterVertically) {
            val w = with(density) { 46.dp.toPx() }
            val center = shimmer * with(density) { 72.dp.toPx() } + with(density) { 36.dp.toPx() }
            Text(
                "滑动完成",
                style = TextStyle(
                    fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold,
                    brush = Brush.horizontalGradient(
                        listOf(c.orangeText, Color.White.copy(alpha = 0.75f).compositeOver(c.orangeText), c.orangeText),
                        startX = center - w / 2, endX = center + w / 2,
                    ),
                ),
            )
            Spacer(Modifier.width(5.dp))
            Row(Modifier.offset(x = (5 * pulse).dp)) {
                UmbraIcon(UmbraIconPaths.chevronRight, c.orangeText.copy(alpha = 0.65f), size = 13.dp, strokeWidth = 2.4f)
                UmbraIcon(UmbraIconPaths.chevronRight, c.orangeText.copy(alpha = 0.65f), size = 13.dp, strokeWidth = 2.4f, modifier = Modifier.offset(x = (-5).dp))
            }
        }
        Box(
            Modifier.offset { IntOffset((with(density) { 4.dp.toPx() } + x.value).roundToInt(), 0) }.align(Alignment.CenterStart)
                .size(46.dp).shadow(8.dp, CircleShape, ambientColor = c.orange.copy(alpha = 0.38f), spotColor = c.orange.copy(alpha = 0.38f))
                .clip(CircleShape).background(c.orange)
                .semantics { contentDescription = "滑动完成" }
                .pointerInput(travel) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (done) return@detectHorizontalDragGestures
                            scope.launch {
                                if (x.value >= travel * 0.82f) {
                                    // 吸附到最右、换对勾，停一拍再回调 —— 不瞬间跳走。
                                    done = true
                                    x.animateTo(travel, tween(220))
                                    delay(420)
                                    onComplete()
                                } else x.animateTo(0f, tween(280, easing = CubicBezierEasing(0.2f, 0.8f, 0.3f, 1f)))
                            }
                        },
                    ) { change, drag ->
                        if (done) return@detectHorizontalDragGestures
                        change.consume()
                        scope.launch { x.snapTo((x.value + drag).coerceIn(0f, travel)) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { UmbraIcon(if (done) UmbraIconPaths.check else UmbraIconPaths.arrowRight, c.onAccent, size = 20.dp, strokeWidth = 2.4f) }
    }
}

private fun Color.compositeOver(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a), 1f)
}

// MARK: - 新建

/**
 * 新建提醒（编辑走详情页的就地编辑；带 id 进来也能编，路由兼容）。「再来一条」/ 聊天「添加提醒」的模板优先；
 * [prefill] 是聊天「添加提醒」带过来的正文。存过一次之后（有图没传上留在这一页补传）左上角叫「完成」。iOS: ReminderViews.swift:1997
 */
@Composable
fun RemEditScreen(id: String?, prefill: String?) {
    val store = ReminderStore
    val router = AppGraph.router
    var draft by remember {
        mutableStateOf(
            store.takeCloneTemplate()
                ?: id?.let { store.item(it) }
                ?: Reminder(
                    id = UUID.randomUUID().toString().uppercase(), text = (prefill ?: "").take(Reminder.textMax),
                    atMs = System.currentTimeMillis() + 3_600_000, source = if (prefill != null) "聊天" else "手动添加",
                ),
        )
    }
    var base by remember { mutableStateOf(draft) }
    var pending by remember { mutableStateOf<List<ReminderPendingImage>>(emptyList()) }
    var fails by remember { mutableStateOf<List<ReminderAttFail>>(emptyList()) }
    var savedOnce by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val askNotify = rememberAskNotificationOnce()
    val canSave = draft.text.trim().isNotEmpty() && !saving

    fun save() {
        if (!canSave) { router.showToast("提醒内容还是空的，写一句才能存"); return }
        var d = draft.copy(text = draft.text.trim())
        d.repeatEndProblem?.let { router.showToast(it); return }
        d = d.copy(note = d.note.trim())
        saving = true
        AppGraph.scope.launch {
            try {
                var bad = emptyList<ReminderAttFail>()
                var e = d
                if (pending.isNotEmpty()) {
                    val (atts, uploaded, failed) = ReminderAttUploader.upload(pending, d.atts)
                    e = ReminderDraftMerge.afterUpload(d, atts, draft)
                    pending = pending.filter { it.id !in uploaded }
                    bad = failed
                }
                val saved = ReminderDraftMerge.merge(base, e, store.item(d.id))
                // ⚠️ 落库无条件（失败不拦保存）。d.id 是页面创建时就定的，落两次也是同一条，不会变出第二条。
                store.save(saved)
                draft = saved; base = saved; savedOnce = true
                askNotify()
                fails = bad
                if (bad.isNotEmpty()) { router.showToast(ReminderSaveToast.partial(bad.size)); return@launch }
                router.back(ifShowing = Route.RemEdit(id, prefill))
                router.showToast(ReminderSaveToast.saved)
            } finally { saving = false }
        }
    }

    fun retry(p: ReminderPendingImage, tier: ImageTier?) {
        if (!savedOnce || saving) return
        val d = draft
        saving = true
        AppGraph.scope.launch {
            try {
                val (atts, uploaded, failed) = ReminderAttUploader.upload(listOf(p), d.atts, tier)
                val e = ReminderDraftMerge.afterUpload(d, atts, draft)
                pending = pending.filter { it.id !in uploaded }
                fails = fails.filter { it.id != p.id } + failed
                val saved = ReminderDraftMerge.merge(base, e, store.item(d.id))
                store.save(saved)
                draft = saved; base = saved
                if (failed.isEmpty()) router.showToast("这张传上了")
            } finally { saving = false }
        }
    }

    UmbraScreen(
        title = stringResource(if (id == null) R.string.reminder_title_new else R.string.reminder_title_edit),
        onBack = { router.back() },
        leading = { UmbraTopBarText(stringResource(if (savedOnce) R.string.common_done else R.string.reminder_btn_cancel), { router.back() }) },
        actions = {
            UmbraTopBarText(stringResource(if (saving) R.string.reminder_btn_saving else R.string.reminder_btn_save), { save() }, enabled = !saving, strong = canSave)
        },
    ) {
        item("form") {
            ReminderForm(
                draft = draft, onDraft = { f -> draft = f(draft) },
                pending = pending, onPending = { pending = it }, fails = fails, onFails = { fails = it },
                creating = true, savedOnce = savedOnce, landed = savedOnce, onRetry = ::retry,
            )
        }
    }
}

// MARK: - 表单（详情就地编辑与新建共用）

/**
 * 正文 ≤200（到 80% 显示计数）、备注 ≤1000、日期 / 时间面板、重复（自定义多出 频率 / 每 N；每周几芯片）、
 * 结束（永不 / 指定日期，就地标红校验）、提前提醒、附件。子项左缩进 32、行高 38、标签 15 灰。iOS: ReminderViews.swift:2145
 */
@Composable
private fun ReminderForm(
    draft: Reminder,
    onDraft: ((Reminder) -> Reminder) -> Unit,
    pending: List<ReminderPendingImage>, onPending: (List<ReminderPendingImage>) -> Unit,
    fails: List<ReminderAttFail>, onFails: (List<ReminderAttFail>) -> Unit,
    creating: Boolean, savedOnce: Boolean, landed: Boolean,
    onRetry: (ReminderPendingImage, ImageTier?) -> Unit,
) {
    val c = UmbraTheme.colors
    val focus = LocalFocusManager.current
    val endField = stringResource(R.string.reminder_row_ends)
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp6)) {
        Field(stringResource(R.string.reminder_field_text)) {
            MultiField(draft.text, { v -> onDraft { it.copy(text = v.take(Reminder.textMax)) } }, stringResource(R.string.reminder_placeholder_text), 88, 16f)
            Counter(draft.text.length, Reminder.textMax)
        }
        Field(stringResource(R.string.reminder_field_note)) {
            MultiField(draft.note, { v -> onDraft { it.copy(note = v.take(Reminder.noteMax)) } }, stringResource(R.string.reminder_placeholder_note), 64, 15.5f)
            Counter(draft.note.length, Reminder.noteMax)
        }
        Field(stringResource(R.string.reminder_field_time)) {
            FormCard {
                PickerRow(stringResource(R.string.reminder_sub_date), UmbraDateText.dayLabel(UmbraDateText.dayOffset(draft.atMs))) {
                    focus.clearFocus() // 先收键盘再弹面板：不收的话面板关掉时焦点回到输入框，键盘又蹦出来
                    UmbraPickers.presentWhen("什么时候", draft.atMs, UmbraWhenTab.Date) { ms -> onDraft { it.copy(atMs = ms) } }
                }
                Divider(14)
                PickerRow(stringResource(R.string.reminder_sub_time), UmbraDateText.hm(draft.atMs)) {
                    focus.clearFocus()
                    UmbraPickers.presentWhen("什么时候", draft.atMs, UmbraWhenTab.Time) { ms -> onDraft { it.copy(atMs = ms) } }
                }
            }
        }
        Field(stringResource(R.string.reminder_field_repeatAhead)) {
            FormCard {
                MenuRow(stringResource(R.string.reminder_row_repeat), draft.repeatRowValue, options = Reminder.repeatOptions,
                    display = { ReminderOptions.repeatOptionText(it) }, current = draft.repeatRule) { v -> onDraft { it.copy(repeatRule = v) } }
                if (draft.repeatRule == "每周") {
                    // 每周几（可多选、至少一个）：没存过亮 at 那一天；最后一颗点了不灭；at 不在选中日里时保存会挪到第一个选中日。
                    val shown = ReminderAdvance.shownWeekDays(draft)
                    val names = if (reminderEnglish) Reminder.weekDayNamesEn else Reminder.weekDayNamesZh
                    Divider(32)
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 32.dp, end = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (i in 0 until 7) UmbraTagPill(names[i], shown.contains(i), { onDraft { it.copy(weekDays = ReminderAdvance.toggleWeekDay(shown, i)) } })
                    }
                }
                if (draft.repeatRule == "自定义") {
                    Divider(32)
                    MenuRow(stringResource(R.string.reminder_row_frequency), ReminderOptions.customFreqText(draft.customFreq, draft.customN), sub = true,
                        options = Reminder.customFreqOptions, display = { ReminderOptions.customFreqText(it, draft.customN) }, current = draft.customFreq) { v -> onDraft { it.copy(customFreq = v) } }
                    Divider(32)
                    NumberRow(stringResource(R.string.reminder_row_every), draft.customN, ReminderOptions.customFreqText(draft.customFreq, draft.customN)) { s ->
                        onDraft { it.copy(customN = Reminder.customN(s).toLong()) }
                    }
                }
                if (draft.repeatRule != "不重复") {
                    Divider(32)
                    val cur = if (draft.repeatEndMs == null) "永不" else "指定日期"
                    MenuRow(endField, ReminderOptions.endOptionText(cur), sub = true, options = listOf("永不", "指定日期"),
                        display = { ReminderOptions.endOptionText(it) }, current = cur) { v ->
                        onDraft { d ->
                            if (v == "永不") d.copy(repeatEndMs = null)
                            else if (d.repeatEndMs == null) d.copy(repeatEndMs = d.defaultRepeatEnd) else d
                        }
                    }
                    if (draft.repeatEndMs != null) {
                        Divider(32)
                        PickerRow(stringResource(R.string.reminder_row_endDate), UmbraDateText.dayLabel(UmbraDateText.dayOffset(draft.repeatEndMs)), sub = true) {
                            focus.clearFocus()
                            // 结束日期带下界：早于提醒日的日子在面板里就点不动；写回规整成本机那天 23:59:59.999（#81）。
                            UmbraPickers.presentDate(endField, draft.repeatEndMs, minDayMs = draft.atMs, minNote = ReminderOptions.endMinNote(draft.atMs)) { ms ->
                                onDraft { it.copy(repeatEndMs = ReminderAdvance.endOfDay(ms)) }
                            }
                        }
                        draft.repeatEndProblem?.let { bad ->
                            Text(bad, color = c.dangerText, style = TextStyle(fontSize = 12.sp, fontWeight = UmbraType.weight560),
                                modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 14.dp, bottom = 10.dp))
                        }
                    }
                }
                Divider(14)
                MenuRow(stringResource(R.string.reminder_row_ahead), draft.aheadRowValue, options = Reminder.aheadOptions.map { it.first },
                    display = { o -> ReminderOptions.aheadOptionText(Reminder.aheadOptions.firstOrNull { it.first == o }?.second ?: 0) },
                    current = draft.aheadLabel) { v -> onDraft { it.copy(aheadMinutes = Reminder.aheadOptions.firstOrNull { o -> o.first == v }?.second ?: 0) } }
            }
        }
        ReminderAttsSection(
            atts = draft.atts, onAtts = { list -> onDraft { it.withAtts(list) } },
            pending = pending, onPending = onPending, fails = fails, onFails = onFails,
            creating = creating, savedOnce = savedOnce, landed = landed, onRetry = onRetry,
        )
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp2)) { UmbraFieldLabel(label); content() }
}

@Composable
private fun FormCard(content: @Composable () -> Unit) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(UmbraDimens.radiusCard)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.card).border(UmbraDimens.borderW, c.borderSoft, shape)) { content() }
}

@Composable
private fun Divider(inset: Int) = UmbraRowDivider(Modifier.padding(start = inset.dp))

/** 多行输入：card 底、圆角 12、borderSoft；超过上限就地截断。 */
@Composable
private fun MultiField(value: String, onChange: (String) -> Unit, placeholder: String, minH: Int, size: Float) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(UmbraDimens.radiusInput)
    BasicTextField(
        value = value, onValueChange = onChange,
        textStyle = TextStyle(fontSize = size.sp, lineHeight = (size * 1.55f).sp, color = c.text),
        cursorBrush = SolidColor(c.orange),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().heightIn(min = minH.dp).clip(shape).background(c.card).border(UmbraDimens.borderW, c.borderSoft, shape).padding(12.dp)) {
                if (value.isEmpty()) Text(placeholder, color = c.faint, style = TextStyle(fontSize = size.sp))
                inner()
            }
        },
    )
}

/** 快到上限（≥80%）才显示计数 —— 常态下那个数字只是噪音。 */
@Composable
private fun Counter(n: Int, max: Int) {
    if (n < (max * 0.8).toInt()) return
    val c = UmbraTheme.colors
    Text("$n / $max", color = if (n >= max) c.dangerText else c.faint, textAlign = TextAlign.End, style = TextStyle(fontSize = 11.sp), modifier = Modifier.fillMaxWidth())
}

/** 点击弹面板的行（整行可点，不是点图标）。 */
@Composable
private fun PickerRow(label: String, value: String, sub: Boolean = false, onClick: () -> Unit) {
    val c = UmbraTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = if (sub) 38.dp else 48.dp)
            .padding(start = if (sub) 32.dp else 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (sub) c.muted else c.text, style = TextStyle(fontSize = if (sub) 15.sp else 16.sp))
        Spacer(Modifier.weight(1f))
        Text(value, color = c.muted, maxLines = 1, style = TextStyle(fontSize = 15.sp))
        Spacer(Modifier.width(6.dp))
        UmbraIcon(UmbraIconPaths.chevronRight, c.faint, size = 14.dp, strokeWidth = 2.2f)
    }
}

/** 选项行：锚定小菜单（≤6 项纯选择不该用全宽底部弹层）。options / current / choose 走存储值，display 只管念什么。 */
@Composable
private fun MenuRow(label: String, value: String, sub: Boolean = false, options: List<String>, display: (String) -> String, current: String, choose: (String) -> Unit) {
    val c = UmbraTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { open = true }.heightIn(min = if (sub) 38.dp else 48.dp)
                .padding(start = if (sub) 32.dp else 14.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = if (sub) c.muted else c.text, style = TextStyle(fontSize = if (sub) 15.sp else 16.sp))
            Spacer(Modifier.weight(1f))
            Text(value, color = c.muted, maxLines = 1, style = TextStyle(fontSize = 15.sp))
            Spacer(Modifier.width(6.dp))
            UmbraIcon(UmbraIconPaths.chevronDown, c.faint, size = 14.dp, strokeWidth = 2.2f)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.card, modifier = Modifier.align(Alignment.CenterEnd).widthIn(min = 180.dp)) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(display(o), color = c.text) },
                    trailingIcon = if (o == current) ({ UmbraIcon(UmbraIconPaths.check, c.orange, size = 15.dp) }) else null,
                    onClick = { open = false; choose(o) },
                )
            }
        }
    }
}

/** 「间隔（每）」：只许数字，空 / 0 落回 1，封顶 10000（判据 Reminder.customN，umbra-ios#68）。 */
@Composable
private fun NumberRow(label: String, n: Long, suffix: String, onInput: (String) -> Unit) {
    val c = UmbraTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).padding(start = 32.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.muted, style = TextStyle(fontSize = 15.sp))
        Spacer(Modifier.weight(1f))
        BasicTextField(
            value = n.toString(), onValueChange = onInput, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(fontSize = 15.sp, fontFamily = FontFamily.Monospace, color = c.text, textAlign = TextAlign.End),
            cursorBrush = SolidColor(c.orange),
            modifier = Modifier.width(56.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(suffix, color = c.muted, style = TextStyle(fontSize = 15.sp))
    }
}
