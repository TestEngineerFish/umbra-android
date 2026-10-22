// 主题的四屏（设计侧批次 269 §五 + 275，照 iOS `Features/Theme/ThemeViews.swift` 现在的正式稿）：
//   · 外观页（[AppearancePage]）：回落卡（有时）→ 外观三档勾选行（跟随系统排第一）→ 主题卡两列（缩略图：标题、一张卡、
//     一颗强调色钮、tab）→ 页尾那句 →「去商城看更多主题」；点卡试用 30 秒，长按出菜单（商城装的：用这个 / 卸载红字；内置：用这个；
//     在用：一项灰着的「在用」）。iOS 是系统上下文菜单，Android 换成长按 + DropdownMenu（最小宽 180，同设计侧 201）。
//   · 试用横幅（[ThemeTrialBar]）：钉在顶栏下面，只挂在外观页和商城的主题详情（试用是从那儿点的）。离开页面试用照常走，到 0 自动退回并出吐司。
//   · 回落错误卡（[ThemeFallbackCard]）：外观页顶上常驻 warning 卡，点 × 或处理掉才消失；启动时另有一条吐司 +［去看看］。
//   · 商城 › 主题（[shopThemesSection] + [ShopThemePage]）：两列卡 + 列表底下一句；详情的钮与 PC 一致。
//
// 和 iOS 的出入：**Android 卖付费主题**（sam 10-21），所以价格写「N 积分」、没授权的付费主题点「获取」先问一句「将扣 N 积分」再买
// （字面照 PC `theme.buyTitle / buyBody / buyBtn`）；列表底下那句换成 Android 自有的（`android_theme_shopFoot`，待设计侧认）。
// 主题和「浅 / 深 / 跟随系统」是两个独立选项（方案 §3.6）：外观页两组各管各的。
package com.geek.umbra.feature.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.app.Brand
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.Appearance
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraSectionLabel
import com.geek.umbra.designsystem.UmbraSettingRow
import com.geek.umbra.designsystem.UmbraStripAction
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.settingSection
import com.geek.umbra.designsystem.themeColor
import com.geek.umbra.feature.me.MeNote
import com.geek.umbra.feature.me.label
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import com.geek.umbra.net.LoginState
import com.geek.umbra.net.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

private val router get() = AppGraph.router
private fun str(id: Int, vararg a: Any): String = AppGraph.context.getString(id, *a)

// MARK: - 缩略图

/**
 * 一个主题在某一套（浅 / 深）下的手机缩略：标题一条、一张卡（两行字 + 一颗强调色钮）、底部 tab（一颗强调色点 + 两颗 faint）。
 * 稿画的是手机的样子，不是 PC 那种导航竖条。`palette` 缺的键回落默认那几色。iOS `ThemeScreenThumb`。
 */
@Composable
fun ThemeScreenThumb(palette: ThemePalette, mode: ThemeMode, modifier: Modifier = Modifier, height: Dp = 112.dp) {
    val u = height.value / 112f
    fun c(k: ThemeKey): Color {
        val hex = palette[mode]?.get(k) ?: ThemeStore.defaultSwatch[mode]?.get(k)
        return hex?.let(::themeColor) ?: Color.Gray
    }
    Box(modifier.fillMaxWidth().height(height).background(c(ThemeKey.Bg)).clearAndSetSemantics { }) {
        Column(Modifier.padding(start = (9 * u).dp, end = (9 * u).dp, top = (9 * u).dp), verticalArrangement = Arrangement.spacedBy((5 * u).dp)) {
            Box(Modifier.fillMaxWidth(0.46f).height((5 * u).dp).clip(RoundedCornerShape(2.dp)).background(c(ThemeKey.Text)))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape((6 * u).dp)).background(c(ThemeKey.Card))
                    .padding(horizontal = (7 * u).dp, vertical = (6 * u).dp),
                verticalArrangement = Arrangement.spacedBy((4 * u).dp),
            ) {
                Box(Modifier.fillMaxWidth(0.7f).height((4 * u).dp).clip(RoundedCornerShape(2.dp)).background(c(ThemeKey.Text)))
                Box(Modifier.fillMaxWidth(0.9f).height((3 * u).dp).clip(RoundedCornerShape(2.dp)).background(c(ThemeKey.Muted)))
                Box(Modifier.width((40 * u).dp).height((11 * u).dp).clip(CircleShape).background(c(ThemeKey.Accent)))
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c(ThemeKey.Border)))
            Row(
                Modifier.fillMaxWidth().height((16 * u).dp).background(c(ThemeKey.Card)),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(ThemeKey.Accent, ThemeKey.Faint, ThemeKey.Faint).forEach {
                    Box(Modifier.size((7 * u).dp).clip(CircleShape).background(c(it)))
                }
            }
        }
    }
}

/** 商城目录里的色块（浅 / 深各 bg / card / accent）→ 缩略用的整套（缺的回落默认）。 */
fun thumbPalette(preview: Map<String, Map<String, String>>): ThemePalette = ThemeMode.entries.associateWith { m ->
    (ThemeStore.defaultSwatch[m].orEmpty()) + preview[m.wire].orEmpty().mapNotNull { (k, v) -> ThemeKey.of(k)?.let { it to v } }
}

// MARK: - 试用横幅

/**
 * 「正在试用「X」· N 秒后换回「Y」」+［退回］［用这个］（themeKit.ios：钉在顶栏下，card 底 + 下描边）。
 * 「退回」是设计侧补的出口（每个演示态都要有出口）；「取消」只用来关弹窗，所以不叫取消。
 */
@Composable
fun ThemeTrialBar() {
    val c = UmbraTheme.colors
    val t by ThemeStore.trial.collectAsState()
    val applied by ThemeStore.applied.collectAsState()
    val trial = t ?: return
    var keeping by remember { mutableStateOf(false) }
    var why by remember(trial.theme.id) { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(trial.untilMs) {
        while (true) { now = System.currentTimeMillis(); delay(250) }
    }
    val scope = rememberCoroutineScope()
    val n = maxOf(0L, (trial.untilMs - now + 999) / 1000)
    Column(Modifier.fillMaxWidth().background(c.card)) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.theme_trialLine, ThemeStore.shownName(trial.theme.id, trial.theme.name), n.toInt(), ThemeStore.shownName(applied.id, applied.name)),
                    color = c.text, style = TextStyle(fontSize = 13.sp, lineHeight = 19.5.sp), modifier = Modifier.weight(1f),
                )
                TrialPill(stringResource(R.string.theme_trialBack), primary = false) { scope.launch { ThemeStore.endTrial(keep = false) } }
                TrialPill(stringResource(if (keeping) R.string.theme_getting else R.string.theme_use), primary = true) {
                    if (keeping) return@TrialPill
                    keeping = true
                    scope.launch {
                        why = ThemeStore.endTrial(keep = true)
                        keeping = false
                    }
                }
            }
            why?.takeIf { it.isNotEmpty() }?.let { Text(stringResource(R.string.theme_notGot, it), color = c.warningText, style = TextStyle(fontSize = 12.5.sp)) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
    }
}

/** 横幅上的两颗：视觉 32、热区 48。主钮强调实底 + onAccent（≥ 600 字重）；次钮描边。 */
@Composable
private fun TrialPill(title: String, primary: Boolean, onClick: () -> Unit) {
    val c = UmbraTheme.colors
    Box(Modifier.heightIn(min = UmbraDimens.tapMin).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.height(32.dp).clip(CircleShape)
                .background(if (primary) c.orange else Color.Transparent)
                .then(if (primary) Modifier else Modifier.border(1.dp, c.border, CircleShape))
                .padding(horizontal = if (primary) 13.dp else 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(title, color = if (primary) c.onAccent else c.text, style = TextStyle(fontSize = 14.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal))
        }
    }
}

// MARK: - 回落的错误卡

/** 「10-12」这种月-日（fallbackCopy / 随会员那句的写法）。按本机时区。 */
fun themeMonthDay(i: Instant): String {
    val d = i.atZone(ZoneId.systemDefault())
    return "%02d-%02d".format(java.util.Locale.ROOT, d.monthValue, d.dayOfMonth)
}

/**
 * 选着的主题用不了、整套退回了默认（themeKit.fallbackCopy：一类一句话 + 下一步）。warning 软底三段式，点 × 或处理掉才消失。
 * 授权到期那一档的下一步：设计侧 269 §五「没有会员页的话这颗换成『知道了』」—— Android 同 iOS 没有单独的会员页，所以是「知道了」。
 */
@Composable
fun ThemeFallbackCard(f: ThemeStore.Fallback) {
    val c = UmbraTheme.colors
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    val name = ThemeStore.shownName(f.id, f.name)
    val what = when (f.fail.kind) {
        ThemeFailKind.Broken -> stringResource(R.string.theme_fbBrokenWhat, name)
        ThemeFailKind.Signature -> stringResource(R.string.theme_fbSignatureWhat, name)
        ThemeFailKind.License -> stringResource(R.string.theme_fbLicenseWhat, name)
        ThemeFailKind.Contrast -> stringResource(R.string.theme_fbContrastWhat, name)
    }
    val why = when (f.fail.kind) {
        ThemeFailKind.Broken -> stringResource(R.string.theme_fbBrokenWhy)
        ThemeFailKind.Signature -> stringResource(R.string.theme_fbSignatureWhy)
        ThemeFailKind.License -> f.until?.let(ThemeLicense::date)?.let {
            stringResource(if (f.src == "membership") R.string.theme_memberUntil else R.string.theme_fbLicenseUntil, themeMonthDay(it))
        } ?: stringResource(R.string.theme_fbLicenseWhyBare)
        ThemeFailKind.Contrast -> stringResource(R.string.theme_fbContrastWhy, f.fail.why)
    }
    val act = when (f.fail.kind) {
        ThemeFailKind.Broken, ThemeFailKind.Signature -> stringResource(if (working) R.string.theme_getting else R.string.theme_redownload)
        ThemeFailKind.License -> stringResource(R.string.theme_gotIt)
        ThemeFailKind.Contrast -> stringResource(R.string.theme_pickAnother)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.warningSoft).padding(start = 16.dp, end = 0.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        UmbraIcon(UmbraIconPaths.alertTriangle, c.warningText, size = 18.dp, strokeWidth = 2.1f, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(what, color = c.warningText, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp))
            Text(why, color = c.text, style = TextStyle(fontSize = 14.sp, lineHeight = 23.sp))
            Box(
                Modifier.heightIn(min = UmbraDimens.tapMin).clickable {
                    when (f.fail.kind) {
                        ThemeFailKind.Broken, ThemeFailKind.Signature -> {
                            if (working) return@clickable
                            working = true
                            scope.launch {
                                ThemeStore.redownload(f.id)?.let { router.showToast(str(R.string.theme_notGot, it)) }
                                working = false
                            }
                        }
                        ThemeFailKind.License, ThemeFailKind.Contrast -> ThemeStore.dismissFallback()
                    }
                },
                contentAlignment = Alignment.CenterStart,
            ) { Text(act, color = c.warningText, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)) }
        }
        val close = stringResource(R.string.theme_close)
        Box(
            Modifier.size(UmbraDimens.tapMin).clickable { ThemeStore.dismissFallback() }.semantics { contentDescription = close },
            contentAlignment = Alignment.Center,
        ) { UmbraIcon(UmbraIconPaths.x, c.warningText, size = 14.dp, strokeWidth = 2.3f) }
    }
}

// MARK: - 外观页

/** 外观页上一张主题卡要的东西：内置的「默认」+ 商城装的。 */
private data class ThemeCard(
    val id: String,
    val name: String,
    /** 「内置」/「商城」/「商城 · 随会员」。 */
    val src: String,
    val palette: ThemePalette,
    val usable: Boolean,
    val builtin: Boolean,
    val why: String?,
)

/** 「我 › 设置 › 外观」：回落卡（有时）→ 外观三档 → 主题两列 → 页尾那句 →「去商城看更多主题」。本机偏好，不要登录。 */
@Composable
fun AppearancePage() {
    val c = UmbraTheme.colors
    val prefs by AppGraph.prefs.snapshot.collectAsState()
    val fallback by ThemeStore.fallback.collectAsState()
    val installed by ThemeStore.installed.collectAsState()
    val applied by ThemeStore.applied.collectAsState()
    val trial by ThemeStore.trial.collectAsState()
    val grants by ThemeStore.grants.collectAsState()
    val current = Appearance.of(prefs[Prefs.K.appearance])
    val dark = UmbraTheme.colors.isDark
    LaunchedEffect(Unit) {
        if (grants == null && AppGraph.auth.login.value is LoginState.SignedIn) ThemeStore.refreshLicense()
    }
    val cards = buildList {
        ThemeOfficial.BUILTIN_IDS.forEach { id ->
            add(ThemeCard(id, ThemeStore.builtinName(id) ?: id, stringResource(R.string.theme_srcBuiltin), ThemeStore.defaultSwatch, true, true, null))
        }
        installed.forEach { t ->
            val src = stringResource(if (t.entitlement.src == "membership") R.string.theme_srcShopMember else R.string.theme_srcShop)
            add(ThemeCard(t.id, ThemeStore.shownName(t.id, t.name), src, t.palette ?: ThemeStore.defaultSwatch, t.failure == null, false, t.failure?.why))
        }
    }
    val mode = if (dark) ThemeMode.Dark else ThemeMode.Light
    UmbraScreen(title = stringResource(R.string.theme_appearance), onBack = { router.back() }, topContent = { ThemeTrialBar() }) {
        fallback?.let { f -> item(key = "fallback") { ThemeFallbackCard(f) } }
        // 三档勾选行，「跟随系统」排第一（它是默认）：三选一、选完即生效。
        settingSection("appearance", str(R.string.theme_appearance), listOf(Appearance.System, Appearance.Light, Appearance.Dark).map { a ->
            UmbraSettingRow(a.label(), valueIcon = if (current == a) UmbraIconPaths.check else null, valueTint = c.orange, action = {
                AppGraph.prefs.set(Prefs.K.appearance, a.wire)
            })
        })
        item(key = "themes") {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                UmbraSectionLabel(stringResource(R.string.theme_themes))
                cards.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { card ->
                            AppearanceCard(card, mode, inUse = applied.id == card.id && trial == null, trying = trial?.theme?.id == card.id, Modifier.weight(1f))
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item(key = "foot") { MeNote(stringResource(R.string.theme_pageFoot), size = 13f, modifier = Modifier.padding(horizontal = 4.dp)) }
        settingSection("shop", null, listOf(
            UmbraSettingRow(str(R.string.theme_toShop), tint = c.orangeText, chevron = true, action = { router.go(Route.SetShop) }),
        ))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppearanceCard(card: ThemeCard, mode: ThemeMode, inUse: Boolean, trying: Boolean, modifier: Modifier) {
    val c = UmbraTheme.colors
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val a11y = if (inUse) stringResource(R.string.theme_a11yInUse, card.name) else card.name
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(c.card)
                .border(2.dp, if (inUse || trying) c.orange else Color.Transparent, shape)
                .combinedClickable(
                    onClick = { tap(card) },
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                )
                .semantics { contentDescription = a11y },
        ) {
            ThemeScreenThumb(card.palette, mode)
            Row(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(card.name, color = c.text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (card.usable) card.src else stringResource(R.string.theme_unusable),
                        color = if (card.usable) c.muted else c.warningText, style = TextStyle(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (inUse) {
                    Spacer(Modifier.width(6.dp))
                    UmbraIcon(UmbraIconPaths.check, c.orangeText, size = 13.dp, strokeWidth = 2.4f)
                    Spacer(Modifier.width(3.dp))
                    Text(stringResource(R.string.theme_inUse), color = c.orangeText, style = TextStyle(fontSize = 12.sp))
                }
            }
        }
        // 长按：在用的那张只有一项灰着的「在用」（不出空菜单）；内置的只有「用这个」；商城装的「用这个」+ 红字「卸载」；
        // 装了但用不了的只剩「卸载」（「用这个」点了也只会说用不了）。
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.card, modifier = Modifier.widthIn(min = 180.dp)) {
            if (inUse) {
                DropdownMenuItem(text = { Text(stringResource(R.string.theme_inUse), color = c.faint) }, onClick = {}, enabled = false)
            } else {
                if (card.usable) DropdownMenuItem(text = { Text(stringResource(R.string.theme_use), color = c.text) }, onClick = { menu = false; ThemeStore.use(card.id) })
                if (!card.builtin) DropdownMenuItem(
                    text = { Text(stringResource(R.string.theme_uninstall), color = c.dangerText) },
                    onClick = { menu = false; askUninstall(card) { scope.launch { ThemeStore.uninstall(card.id) } } },
                )
            }
        }
    }
}

/** 点卡片：用着的不动；用不了的说原因；试用中点回原来那张 = 退回；其余试用 30 秒（themeKit.appearancePage.trial）。 */
private fun tap(card: ThemeCard) {
    val applied = ThemeStore.applied.value
    val trial = ThemeStore.trial.value
    if (applied.id == card.id && trial == null) return
    if (!card.usable) { router.showToast(str(R.string.theme_unusableWhy, card.why.orEmpty())); return }
    if (applied.id == card.id) { AppGraph.scope.launch { ThemeStore.endTrial(keep = false) }; return }
    when (val r = ThemeStore.resolve(card.id)) {
        is ThemeStore.Resolution.Ok -> ThemeStore.startTrial(r.theme)
        is ThemeStore.Resolution.Fail -> router.showToast(str(R.string.theme_unusableWhy, r.fallback.fail.why))
    }
}

/** 卸载确认，字面与 PC 同一套（appearancePage.uninstall）：正在用它时在后面再加一句。卸载不进回收站，不套删除统一文案。 */
private fun askUninstall(card: ThemeCard, onConfirm: () -> Unit) {
    val using = ThemeStore.applied.value.id == card.id
    router.confirm(UmbraAlert(
        title = str(R.string.theme_uninstallTitle, card.name),
        message = str(R.string.theme_uninstallBody) + (if (using) str(R.string.theme_uninstallUsing) else ""),
        confirmLabel = str(R.string.theme_uninstall),
        destructive = true,
        onConfirm = onConfirm,
    ))
}

// MARK: - 商城 · 主题

/** 随会员的主题（许可证里来源是会员，或目录标了 `member_gift`）。 */
private fun isMemberTheme(e: ThemeCatalogEntry) = e.memberGift || ThemeStore.grantSource(e.id) == "membership"

/** 会员有没有在：许可证里那条会员授权现在还有效。 */
private fun memberActive(e: ThemeCatalogEntry, grants: List<ThemeGrant>?): kotlin.Pair<Boolean, Instant?> {
    val ent = ThemeLicense.entitlement(grants, e.id, Instant.now())
    if (ent.src != "membership" || ent.state != ThemeLicense.State.Active) return false to null
    return true to ent.until?.let(ThemeLicense::date)
}

/**
 * 价格：随会员 / 免费 / 已买（付费的、此刻有有效授权）/ N 积分（Android 卖，iOS 不卖所以 iOS 只有前三种）。
 * 价格解不出写「—」（点「获取」时说读不出、不买）。
 */
private fun priceText(e: ThemeCatalogEntry): String = when {
    isMemberTheme(e) -> str(R.string.theme_priceMember)
    e.price == 0 -> str(R.string.theme_priceFree)
    ThemeStore.hasActiveGrant(e.id) -> str(R.string.theme_priceBought)
    !ThemeShopRules.mayBuy(e.price) -> "—"
    else -> str(R.string.android_theme_priceN, e.price)
}

/** 状态字：未装不写；已装 muted；在用 orangeText。 */
private fun statusOf(e: ThemeCatalogEntry, applied: ThemeStore.Resolved, trial: ThemeStore.Trial?, installed: List<InstalledTheme>): kotlin.Pair<String, Boolean>? {
    if (applied.id == e.id && trial == null) return str(R.string.theme_inUse) to true
    if (ThemeStore.isBuiltin(e.id) || installed.any { it.id == e.id && it.failure == null }) return str(R.string.theme_installed) to false
    return null
}

/** 商城「主题」一栏（作为商城页 LazyColumn 的几项）：拿不到时顶上一条（装好的照常用）+ 两列卡 + 列表底下一句。 */
fun LazyListScope.shopThemesSection() {
    item(key = "themes-body") { ShopThemesBody() }
}

@Composable
private fun ShopThemesBody() {
    val c = UmbraTheme.colors
    val catalog by ThemeStore.catalog.collectAsState()
    val failed by ThemeStore.catalogFailed.collectAsState()
    val installed by ThemeStore.installed.collectAsState()
    val applied by ThemeStore.applied.collectAsState()
    val trial by ThemeStore.trial.collectAsState()
    @Suppress("UNUSED_VARIABLE") val grants by ThemeStore.grants.collectAsState()   // 读一下：授权变了价格字要跟着重画
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { ThemeStore.loadCatalog() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (failed) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UmbraIcon(UmbraIconPaths.alertTriangle, c.warningText, size = 16.dp, strokeWidth = 2.1f)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.theme_shopFailed), color = c.text, style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp), modifier = Modifier.weight(1f))
                UmbraStripAction(stringResource(R.string.theme_retry), c.orangeText, { scope.launch { ThemeStore.loadCatalog() } })
            }
        }
        val list = catalog
        if (list == null) {
            Text(stringResource(R.string.theme_loading), color = c.muted, style = TextStyle(fontSize = 14.5.sp))
        } else {
            if (list.isEmpty() && !failed) Text(stringResource(R.string.theme_shopEmpty), color = c.muted, style = TextStyle(fontSize = 14.5.sp))
            list.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { e -> ShopThemeCard(e, statusOf(e, applied, trial, installed), installed, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        MeNote(stringResource(R.string.android_theme_shopFoot), size = 13f, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** 一张卡：上半浅 / 深两片并排（同 PC 商城），名字、一句「浅色 + 深色两套，只换颜色」、价格 + 状态字（卡太窄，不画图标）。 */
@Composable
private fun ShopThemeCard(e: ThemeCatalogEntry, st: kotlin.Pair<String, Boolean>?, installed: List<InstalledTheme>, modifier: Modifier) {
    val c = UmbraTheme.colors
    val pal = installed.firstOrNull { it.id == e.id }?.palette ?: thumbPalette(e.preview)
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(c.card).clickable { router.go(Route.SetShopTheme(e.id)) }) {
        Row {
            ThemeScreenThumb(pal, ThemeMode.Light, Modifier.weight(1f), height = 96.dp)
            ThemeScreenThumb(pal, ThemeMode.Dark, Modifier.weight(1f), height = 96.dp)
        }
        Column(Modifier.padding(start = 11.dp, end = 11.dp, top = 10.dp, bottom = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(ThemeStore.shownName(e.id, e.name), color = c.text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.theme_bothModes), color = c.muted, style = TextStyle(fontSize = 12.sp), maxLines = 2)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(priceText(e), color = c.muted, style = TextStyle(fontSize = 12.sp), modifier = Modifier.weight(1f), maxLines = 1)
                st?.let { (text, accent) -> Text(text, color = if (accent) c.orangeText else c.muted, style = TextStyle(fontSize = 12.sp)) }
            }
        }
    }
}

/** 商城里一个主题的详情：浅 / 深两张大预览、名字、简介、规格三行、随会员那句，底部两颗钮（与 PC 一致）。数据在服务端，要登录（同商城）。 */
@Composable
fun ShopThemePage(id: String) {
    val c = UmbraTheme.colors
    val catalog by ThemeStore.catalog.collectAsState()
    val installed by ThemeStore.installed.collectAsState()
    val applied by ThemeStore.applied.collectAsState()
    val trial by ThemeStore.trial.collectAsState()
    val grants by ThemeStore.grants.collectAsState()
    val busy by ThemeStore.busyId.collectAsState()
    val scope = rememberCoroutineScope()
    var trying by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (ThemeStore.catalog.value == null) ThemeStore.loadCatalog() }
    val e = catalog?.firstOrNull { it.id == id }
    if (e == null) {
        UmbraScreen(title = "", onBack = { router.back() }, topContent = { ThemeTrialBar() }) {
            item(key = "gone") { MeNote(stringResource(if (catalog == null) R.string.theme_loading else R.string.theme_shopGone), size = 14f) }
        }
        return
    }
    val pal = installed.firstOrNull { it.id == e.id }?.palette ?: thumbPalette(e.preview)
    val isInstalled = ThemeStore.isBuiltin(e.id) || installed.any { it.id == e.id && it.failure == null }
    val member = isMemberTheme(e)
    val (memberOn, memberUntil) = memberActive(e, grants)
    UmbraScreen(
        title = "", onBack = { router.back() }, topContent = { ThemeTrialBar() },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().background(c.bg).padding(start = UmbraDimens.pagePadX, end = UmbraDimens.pagePadX, top = 10.dp, bottom = 12.dp).navigationBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 钮与 PC 一致：未装［试用］［获取］；已装［试用］［用这个］；在用整颗灰「在用」。随会员而没会员：「获取」灰着。
                if (applied.id == e.id && trial == null) {
                    UmbraButton(stringResource(R.string.theme_inUse), {}, Modifier.weight(1f), enabled = false, height = 50.dp)
                } else {
                    UmbraButton(stringResource(if (trying) R.string.theme_downloading else R.string.theme_trial), {
                        if (trying) return@UmbraButton
                        trying = true
                        scope.launch {
                            ThemeStore.trialFromShop(e) { keepFromTrial(e) }?.let {
                                router.showToast(str(R.string.theme_trialFailed, ThemeStore.shownName(e.id, e.name), it))
                            }
                            trying = false
                        }
                    }, Modifier.weight(1f), kind = UmbraButtonKind.Secondary, height = 50.dp)
                    if (isInstalled) {
                        UmbraButton(stringResource(R.string.theme_use), { ThemeStore.use(e.id) }, Modifier.weight(1f), height = 50.dp)
                    } else {
                        val blocked = member && !memberOn
                        UmbraButton(
                            stringResource(if (busy == e.id) R.string.theme_getting else R.string.theme_get),
                            { if (!blocked) get(e) }, Modifier.weight(1f), enabled = busy != e.id && !blocked, height = 50.dp,
                        )
                    }
                }
            }
        },
    ) {
        item(key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(ThemeMode.Light, ThemeMode.Dark).forEach { m ->
                        ThemeScreenThumb(pal, m, Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).border(1.dp, c.border, RoundedCornerShape(14.dp)), height = 220.dp)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(ThemeStore.shownName(e.id, e.name), color = c.text, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
                    if (e.brief.isNotEmpty()) Text(ThemeStore.shownBrief(e.id, e.brief), color = c.text, style = TextStyle(fontSize = 15.sp, lineHeight = 24.75.sp))
                    Text(stringResource(R.string.theme_detailNote), color = c.muted, style = TextStyle(fontSize = 15.sp, lineHeight = 24.75.sp))
                }
            }
        }
        settingSection("spec", null, listOf(
            UmbraSettingRow(str(R.string.theme_specBy), value = e.author.ifEmpty { str(R.string.brand_name) }),
            UmbraSettingRow(str(R.string.theme_specHas), value = str(R.string.theme_specModes)),
            UmbraSettingRow(str(R.string.theme_specPrice), value = priceText(e)),
        ))
        if (member) {
            // 随会员那一句：有会员「它随会员提供，你的会员 10-12 到期。」，没会员只写「它随会员提供。」（不给开会员的入口，同 193 的口径）
            val line = if (memberOn && memberUntil != null) str(R.string.theme_memberUntil, themeMonthDay(memberUntil)) else str(R.string.theme_memberOnly)
            item(key = "member") { MeNote(line, size = 13f, modifier = Modifier.padding(horizontal = 4.dp)) }
        }
    }
}

/**
 * 「获取」：此刻有有效授权或免费 → 直接拿；付费没授权 → 先问一句「将扣 N 积分，买断后一直可用」（PC `theme.buyTitle / buyBody / buyBtn`）再买；
 * 价格读不出 → 不买，说一句（iOS #94：不知道扣多少就不扣）。
 */
private fun get(e: ThemeCatalogEntry) {
    if (ThemeStore.busyId.value != null) return
    val run = {
        AppGraph.scope.launch {
            val why = ThemeStore.acquire(e)
            router.showToast(if (why != null) str(R.string.theme_notGot, why) else str(R.string.theme_gotToast, ThemeStore.shownName(e.id, e.name)))
        }
    }
    if (ThemeStore.hasActiveGrant(e.id) || e.price == 0) { run(); return }
    if (!ThemeShopRules.mayBuy(e.price)) { router.showToast(str(R.string.android_theme_priceUnknown)); return }
    router.confirm(buyAlert(e) { run() })
}

private fun buyAlert(e: ThemeCatalogEntry, onCancel: (() -> Unit)? = null, onConfirm: () -> Unit) = UmbraAlert(
    title = str(R.string.android_theme_buyTitle, ThemeStore.shownName(e.id, e.name)),
    message = str(R.string.android_theme_buyBody, e.price),
    confirmLabel = str(R.string.android_theme_buyBtn, e.price),
    onCancel = onCancel,
    onConfirm = onConfirm,
)

/**
 * 试用一个没装的商城主题、点横幅上的「用这个」：付费且没授权的同样先问一句再买（不能因为是从试用条点的就不问直接扣积分）；
 * 问了不买 = 没拿到，试用照旧（回空串：横幅不写原因）。回 null = 拿到了。
 */
private suspend fun keepFromTrial(e: ThemeCatalogEntry): String? {
    if (!ThemeStore.hasActiveGrant(e.id) && e.price != 0) {
        if (!ThemeShopRules.mayBuy(e.price)) return str(R.string.android_theme_priceUnknown)
        if (ThemeShopRules.needsConfirm(e.price)) {
            val ok = CompletableDeferred<Boolean>()
            router.confirm(buyAlert(e, onCancel = { ok.complete(false) }) { ok.complete(true) })
            if (!ok.await()) return ""      // 不买：不算出错，横幅上不写「没拿到」
        }
    }
    return ThemeStore.acquire(e)
}
