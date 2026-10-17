// 执勤几页共用的画法：设置式分组 / 行（iOS UmbraSettingsKit 的 UmbraSettingSection / UmbraSettingRow）、底部选择器、
// 填一格文字的弹层、只有「知道了」的说明框、时间面板、通知权限。
//
// 为什么不直接用 designsystem 的 UmbraSettingRow / settingSection：
//   ① iOS 的行「有开关就只认开关」（整行不可点，防误触，UmbraSettingsKit.swift:298），Android 那一版整行点击 = 切换；
//   ② iOS 的 `subWarn` 是一段带三角的警示文字（再接 sub），Android 那一版是布尔（把 sub 染成警示色）；
//   ③ 组脚 danger 档 iOS 是圆叉 + dangerText。
// 不改地基（不许改已有 designsystem 文件），在本包里照 iOS 写一份；尺寸、颜色全取 token。
package com.geek.umbra.feature.duty

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraGroupCard
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraRowDivider
import com.geek.umbra.designsystem.UmbraSectionLabel
import com.geek.umbra.designsystem.UmbraSwitch
import com.geek.umbra.designsystem.UmbraTextField
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.navigation.UmbraSheet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

// MARK: - 分组 / 行

/** 一行（iOS UmbraSettingRow 的执勤用子集，字段名照抄）。 */
internal data class DutyRow(
    val label: String,
    /** 主文下面的一行小字（muted）。 */
    val sub: String? = null,
    /** 副行的前置提醒：12 三角 + 这半句走 warningText，后面接 sub。 */
    val subWarn: String? = null,
    val value: String? = null,
    /** 值用等宽（时刻）。 */
    val mono: Boolean = false,
    /** 值的颜色，默认 muted。 */
    val valueTint: Color? = null,
    /** 主文字色。默认正文色；橙字用于动作行。 */
    val tint: Color? = null,
    val chevron: Boolean = false,
    /** 非 null 就画开关；此时**只有开关可点**（防误触，iOS 同）。 */
    val toggle: Boolean? = null,
    val action: (() -> Unit)? = null,
)

internal enum class DutyFooterTone { Warning, Danger }

/** 一组（iOS UmbraSettingSection）。 */
internal data class DutySection(
    val key: String,
    val header: String? = null,
    val headerCount: Int? = null,
    val footer: String? = null,
    val footerTone: DutyFooterTone? = null,
    val rows: List<DutyRow>,
)

/** 组头（FieldLabel + 等宽计数）+ 分组卡 + 组脚。 */
@Composable
internal fun DutySectionView(sec: DutySection) {
    Column {
        if (sec.header != null) {
            UmbraSectionLabel(sec.header, trailing = sec.headerCount?.toString())
            Spacer(Modifier.height(UmbraDimens.sp2))
        }
        if (sec.rows.isNotEmpty()) {
            UmbraGroupCard {
                sec.rows.forEachIndexed { i, r ->
                    if (i > 0) UmbraRowDivider(Modifier.padding(start = 14.dp))
                    DutyRowView(r)
                }
            }
        }
        if (sec.footer != null) {
            Spacer(Modifier.height(UmbraDimens.sp2))
            DutyFooter(sec.footer, sec.footerTone)
        }
    }
}

/** 组脚：说明 12 muted；warning 档三角 + warningText；danger 档圆叉 + dangerText。 */
@Composable
internal fun DutyFooter(text: String, tone: DutyFooterTone? = null) {
    val c = UmbraTheme.colors
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
        if (tone != null) {
            UmbraIcon(
                if (tone == DutyFooterTone.Danger) UmbraIconPaths.xCircle else UmbraIconPaths.alertTriangle,
                if (tone == DutyFooterTone.Danger) c.dangerText else c.warningText, size = 13.dp, modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text,
            color = when (tone) { DutyFooterTone.Danger -> c.dangerText; DutyFooterTone.Warning -> c.warningText; null -> c.muted },
            style = TextStyle(fontSize = if (tone != null) 13.sp else 12.sp, lineHeight = 18.sp),
        )
    }
}

/** 一行：左右 14、上下 11、最小高 48；主文 16 + 副行 12.5；值 14.5（等宽 13.5）最大宽 150；开关 / chevron。 */
@Composable
internal fun DutyRowView(row: DutyRow) {
    val c = UmbraTheme.colors
    // 有开关的行，点击区归开关（否则点标签也会切，容易误触）；其它有动作的行整行可点。
    val click = if (row.toggle == null) row.action else null
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.rowMinH)
            .then(if (click != null) Modifier.clickable(role = Role.Button, onClick = click) else Modifier)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                row.label, color = row.tint ?: c.text,
                style = TextStyle(fontSize = if (row.mono && row.value == null) 15.sp else 16.sp, lineHeight = 21.sp,
                    fontFamily = if (row.mono && row.value == null) FontFamily.Monospace else FontFamily.Default),
            )
            if (row.subWarn != null || row.sub != null) {
                Row(verticalAlignment = Alignment.Top) {
                    if (row.subWarn != null) {
                        UmbraIcon(UmbraIconPaths.alertTriangle, c.warningText, size = 12.dp, strokeWidth = 2f, modifier = Modifier.padding(top = 2.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    // 警示那半句与 sub 同一行接着读；两段颜色不同，用 AnnotatedString 拼
                    androidx.compose.foundation.text.BasicText(
                        androidx.compose.ui.text.buildAnnotatedString {
                            if (row.subWarn != null) {
                                pushStyle(androidx.compose.ui.text.SpanStyle(color = c.warningText))
                                append(if (row.sub == null) row.subWarn else "${row.subWarn} · ")
                                pop()
                            }
                            if (row.sub != null) {
                                pushStyle(androidx.compose.ui.text.SpanStyle(color = c.muted))
                                append(row.sub)
                                pop()
                            }
                        },
                        style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
                    )
                }
            }
        }
        if (row.value != null) {
            Text(
                row.value, color = row.valueTint ?: c.muted,
                style = TextStyle(fontSize = if (row.mono) 13.5.sp else 14.5.sp, fontFamily = if (row.mono) FontFamily.Monospace else FontFamily.Default),
                maxLines = 1, overflow = TextOverflow.MiddleEllipsis, textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = 150.dp),
            )
        }
        if (row.toggle != null) UmbraSwitch(row.toggle, { row.action?.invoke() })
        if (row.chevron) UmbraIcon(UmbraIconPaths.chevronRight, c.faint, size = 15.dp, strokeWidth = 2.2f)
    }
}

/** LazyColumn 里加一组。 */
internal fun LazyListScope.dutySection(sec: DutySection) {
    item(key = "duty-sec-${sec.key}") { DutySectionView(sec) }
}

/** 页首说明（iOS UmbraSettingsPage.intro）：12.5 muted。 */
@Composable
internal fun DutyIntro(text: String) {
    Text(
        text, color = UmbraTheme.colors.muted, style = TextStyle(fontSize = 12.5.sp, lineHeight = 21.sp),
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

// MARK: - 弹层

/** 底部选择器的一项（iOS UmbraSheetItem）。 */
internal data class DutySheetItem(
    val label: String,
    val note: String? = null,
    val checked: Boolean? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 行式底部选择器（iOS `UmbraSheet(title:subtitle:items:)`）：标题 + 副标题 + 一张行卡 + 取消。
 * 点一行先收起再执行 —— 执行里常常还要再开一层（确认弹窗 / 下一步选择器）。
 */
internal fun dutyPresentList(key: String, title: String, subtitle: String? = null, items: List<DutySheetItem>) {
    AppGraph.router.present(UmbraSheet("duty-$key") { dismiss ->
        val c = UmbraTheme.colors
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = c.text, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
                if (subtitle != null) Text(subtitle, color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp))
            }
            UmbraGroupCard {
                items.forEachIndexed { i, it ->
                    if (i > 0) UmbraRowDivider(Modifier.padding(start = 14.dp))
                    Row(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp)
                            .clickable(role = Role.Button) { dismiss(); it.onClick() }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(it.label, color = if (it.destructive) c.dangerText else c.text, style = TextStyle(fontSize = 16.sp))
                            if (it.note != null) Text(it.note, color = c.muted, style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp))
                        }
                        if (it.checked == true) {
                            Spacer(Modifier.width(8.dp))
                            UmbraIcon(UmbraIconPaths.check, c.orange, size = 17.dp)
                        }
                    }
                }
            }
            UmbraButton(stringResource(R.string.duty_cancel), dismiss, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary, height = 50.dp)
        }
    })
}

/**
 * 填一格文字（iOS 是系统 alert 里一格 TextField）：Android 的 UmbraAlert 只有两颗钮、没有输入框，
 * 落成一张带输入框的底部弹层：标题 + 一句说明 + 输入框 + 取消 / 确认。
 */
internal fun dutyAskText(
    key: String,
    title: String,
    message: String?,
    placeholder: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
) {
    AppGraph.router.present(UmbraSheet("duty-text-$key") { dismiss ->
        val c = UmbraTheme.colors
        var text by remember { mutableStateOf(initial) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
            if (!message.isNullOrEmpty()) Text(message, color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp))
            UmbraTextField(text, { text = it }, Modifier.focusRequester(focus), placeholder = placeholder)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                UmbraButton(stringResource(R.string.duty_cancel), dismiss, Modifier.weight(1f), kind = UmbraButtonKind.Secondary, height = 44.dp)
                UmbraButton(confirmLabel, { dismiss(); onConfirm(text) }, Modifier.weight(1f), height = 44.dp)
            }
        }
    })
}

/**
 * 只有一颗「知道了」的说明框（iOS `UmbraAlert(showsCancel: false)`，「为什么」/ 节点配置 / 步骤输出）。
 * Android 地基的 UmbraAlert 固定两颗钮（取消 + 确认），不改地基，落成一张底部弹层：标题 + 正文 + 「知道了」。
 * 正文可能有七八行（上下文摘要），弹层能滚。
 */
internal fun dutyInfo(title: String, body: String) {
    AppGraph.router.present(UmbraSheet("duty-info-${System.nanoTime()}") { dismiss ->
        val c = UmbraTheme.colors
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
            Text(body, color = c.text, style = TextStyle(fontSize = 14.sp, lineHeight = 21.sp))
            UmbraButton(stringResource(R.string.presence_ok), dismiss, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary, height = 48.dp)
        }
    })
}

/** 写操作失败：吐司说服务端那句原话，没有就兜一句。成功什么都不说（开关自己会翻过去）。iOS DutyViews.swift:513 */
internal fun dutyReport(err: String?) {
    val e = err ?: return
    AppGraph.router.showToast(e.ifEmpty { AppGraph.context.getString(R.string.duty_writeFailed) })
}

// MARK: - 时间面板

/** "HH:mm" → LocalTime；认不出给 [fallback]。 */
internal fun parseHHMM(raw: String, fallback: LocalTime): LocalTime {
    val p = raw.split(":").mapNotNull { it.toIntOrNull() }
    return if (p.size == 2 && p[0] in 0..23 && p[1] in 0..59) LocalTime.of(p[0], p[1]) else fallback
}

/** LocalTime → "HH:mm"。 */
internal fun LocalTime.hhmm(): String = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

/**
 * 时分选择（iOS `.umbraTimePicker`）—— 走共用的 UmbraDateTimePanel 时分滚轮（设计侧 199 答复第 8 条：Android 也用它，
 * 不用 M3 原生选择器）。弹层由 Router 托管，弹出去之后立刻 onDismiss 放下「要弹」的旗子；取消就是什么都不回。
 */
@Composable
internal fun DutyTimePickerDialog(title: String, initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val zone = java.time.ZoneId.systemDefault()
        val ms = java.time.LocalDate.now(zone).atTime(initial).atZone(zone).toInstant().toEpochMilli()
        com.geek.umbra.designsystem.UmbraPickers.presentTime(title, ms) { picked ->
            onPick(java.time.Instant.ofEpochMilli(picked).atZone(zone).toLocalTime().withSecond(0).withNano(0))
        }
        onDismiss()
    }
}

/**
 * 日期 + 时刻（iOS `.umbraWhenPicker`，一次性触发「哪个时刻」）—— 走共用的 UmbraDateTimePanel when 变体：
 * 一个瞬间一个值、同一个面板两段切（同提醒 / 记账）。按本机时区选；显示仍按上海（DutyRules.monthDayTime）。
 */
@Composable
internal fun DutyWhenPickerDialog(title: String, initialMs: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.geek.umbra.designsystem.UmbraPickers.presentWhen(title, initialMs, com.geek.umbra.designsystem.UmbraWhenTab.Date, onPick)
        onDismiss()
    }
}

// MARK: - 日志组头

/** 'YYYY-MM-DD' → 「10月16日 周四」（跟界面语言走，iOS setLocalizedDateFormatFromTemplate("MMMdEEE")）。 */
internal fun dutyDayHeader(day: String, locale: Locale): String {
    val d = runCatching { LocalDate.parse(day) }.getOrNull() ?: return day
    val pattern = DateFormat.getBestDateTimePattern(locale, "MMMdEEE")
    val f = SimpleDateFormat(pattern, locale).apply { timeZone = TimeZone.getTimeZone(DutyRules.tz) }
    return f.format(java.util.Date(d.atStartOfDay(DutyRules.tz).toInstant().toEpochMilli()))
}

// MARK: - 通知权限（iOS PushRegistry 的执勤那一半）

/**
 * 执勤到点发的消息要能在手机上弹出来：开执勤 / 开早报 / 新建一条成功之后申请通知权限（契约 §16.7）；
 * 被拒了在总开关下面给一条「去设置里打开通知」。
 *
 * Android 13+ 才有运行时的 POST_NOTIFICATIONS；更早的系统只能看「通知开没开」（用户在系统设置里关）。
 * ⚠️ 服务端推送（APNs 那一路）Android 还没有（待服务端 FCM / 厂商通道，见开发文档第六节）：
 * 现在通知只在 App 前台连着长连接时由本地通知发出，这一行权限是为那一天先铺好。
 */
internal object DutyPush {
    private val _denied = MutableStateFlow(false)
    /** 通知被关了（权限被拒或系统设置里关了）。 */
    val denied: StateFlow<Boolean> = _denied

    /**
     * 待弹的权限申请（申请必须从 Activity 发起，由页面里的 [DutyPushHost] 接）。用「挂着的标记」而不是一次性事件：
     * 编辑页存完立刻退回，事件若落在正被拆掉的那一页上就丢了；标记会被执勤页的那一个接住。
     */
    val pending = MutableStateFlow(false)

    fun refresh(ctx: Context = AppGraph.context) {
        _denied.value = !NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    /** 还没给权限就申请一次（已拒绝过的系统自己不再弹，无害）。 */
    fun requestIfNeeded(ctx: Context = AppGraph.context) {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        pending.value = true
    }

    /** 去系统的「这个 App 的通知」设置页。 */
    fun openSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }
}

/** 挂在执勤页 / 编辑页里：接 [DutyPush.pending] 弹系统权限框；回前台时刷新「被拒」那一格。 */
@Composable
internal fun DutyPushHost() {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { DutyPush.refresh(ctx) }
    LaunchedEffect(Unit) {
        DutyPush.refresh(ctx)
        DutyPush.pending.collect { want ->
            if (want) {
                DutyPush.pending.value = false
                if (Build.VERSION.SDK_INT >= 33) runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) DutyPush.refresh(ctx) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}
