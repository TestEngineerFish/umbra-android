// 保险箱 · 首页（上锁 / 解锁）—— 照抄 iOS VaultViews.swift:19-1096 `UmbraVaultHomeView`。
package com.geek.umbra.feature.vault

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraEmptyState
import com.geek.umbra.designsystem.UmbraFieldLabel
import com.geek.umbra.designsystem.UmbraFilterChips
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraNotice
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraSearchField
import com.geek.umbra.designsystem.UmbraStripAction
import com.geek.umbra.designsystem.UmbraTagPill
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTopBarIcon
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.designsystem.UmbraExtraIcons
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun VaultHomeContent() {
    val st by VaultStore.state.collectAsState()
    val se by VaultSession.state.collectAsState()
    val locked = !st.unlocked || se.softLocked
    val router = AppGraph.router
    val activity = LocalContext.current.findFragmentActivity()

    var password by rememberSaveable { mutableStateOf("") }
    var secretKey by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    /** "" 全部 / "fav" 收藏 / typeId */
    var cat by rememberSaveable { mutableStateOf("") }
    var wrongPasswordCount by remember { mutableIntStateOf(0) }
    /** 条幅这一次关掉了没有（「×」）。**只活在这一次进保险箱里**；「不再提示」是另一件事，落在会话上。 */
    var noteClosed by remember { mutableStateOf(false) }
    val pwFocus = remember { FocusRequester() }

    // 首页出现就拉一次（解锁态下 pullRecord 自己转去 refreshUnlocked）。
    LaunchedEffect(Unit) { VaultStore.pullRecord() }
    // 离开页面就把「这一趟已经自动识别过」的标记清掉。
    OnLeave { VaultSession.resetVisit() }

    fun startScan() {
        val act = activity ?: return
        if (VaultSession.s.softLocked) {
            VaultSession.scanForSoftUnlock(act)
        } else {
            if (!VaultSession.s.hasBiometricCredential) {
                router.showToast("还没开启${VaultSession.biometryName}解锁，先用主密码进一次"); return
            }
            VaultSession.scanForPassword(act) { pw ->
                AppGraph.scope.launch {
                    VaultStore.unlock(pw, "")
                    if (VaultStore.s.unlocked) {
                        VaultSession.markUnlocked()
                        router.showToast("已解锁")
                    }
                }
            }
        }
    }

    // 进页面自动刷一次：延迟 420ms 再发起，让转场先走完。上锁状态是异步变的，所以盯着 locked 判。
    LaunchedEffect(locked) {
        if (locked) noteClosed = false
        if (!VaultSession.shouldAutoScan(st.unlocked)) return@LaunchedEffect
        delay(420)
        val now = VaultStore.s
        if ((!now.unlocked || VaultSession.s.softLocked) && VaultSession.s.faceState == FaceState.Idle) startScan()
    }

    fun tapFaceIcon() {
        if (VaultSession.s.faceState == FaceState.Scanning) return
        if (!VaultSession.s.faceIDEnabled) { router.showToast("${VaultSession.biometryName}已在保险箱设置里关掉"); return }
        if (!VaultSession.biometryAvailable) { router.showToast("这台手机还没录入指纹或面部"); return }
        startScan()
    }

    fun submitPassword() {
        val attempted = password
        AppGraph.scope.launch {
            VaultStore.unlock(attempted, secretKey)
            if (VaultStore.s.unlocked) {
                password = ""; secretKey = ""
                VaultSession.markUnlocked()
                offerBiometryIfFirstTime(attempted)
            } else wrongPasswordCount++
        }
    }

    val c = UmbraTheme.colors
    if (locked) {
        UmbraScreen(title = "保险箱", onBack = { router.back() }) {
            item("locked") {
                LockedBody(
                    st, se, password, { password = it }, secretKey, { secretKey = it }, pwFocus, wrongPasswordCount,
                    onFace = ::tapFaceIcon, onRetryFace = ::startScan, onSubmit = ::submitPassword,
                    onSoftUnlock = { activity?.let { VaultSession.scanForSoftUnlock(it) } },
                    onClearError = { password = ""; VaultStore.clearError(); runCatching { pwFocus.requestFocus() } },
                )
            }
        }
        return
    }

    val audit = remember(st.items) { VaultAudit(st.items) }
    val q = query.trim().lowercase()
    val rows = st.items.filter { it ->
        if (cat == "fav" && it.favorite != true) return@filter false
        if (cat.isNotEmpty() && cat != "fav" && it.typeId != cat) return@filter false
        if (q.isEmpty()) true else it.searchHaystack.joinToString(" ").lowercase().contains(q)
    }
    // 分组那一套 UI 画不画：画布版没有分组；过渡期只要还有一条旧记录就照旧画。
    val showsGroups = st.types.isNotEmpty() && st.items.any { !it.isCanvas }
    val groups = buildList {
        val favs = rows.filter { it.favorite == true }
        if (cat.isEmpty() && favs.isNotEmpty()) add(Triple("fav", "收藏", favs))
        if (!showsGroups) {
            val rest = if (cat.isEmpty()) rows.filter { it.favorite != true } else rows
            if (rest.isNotEmpty()) add(Triple("_all", "记录", rest))
        } else {
            for (t in st.types) rows.filter { !it.isCanvas && it.typeId == t.id }.takeIf { it.isNotEmpty() }?.let { add(Triple(t.id, t.name, it)) }
            val known = st.types.map { it.id }.toSet()
            rows.filter { !it.isCanvas && it.typeId !in known }.takeIf { it.isNotEmpty() }?.let { add(Triple("_orphan", "未分组", it)) }
            rows.filter { it.isCanvas }.takeIf { it.isNotEmpty() }?.let { add(Triple("_canvas", "记录", it)) }
        }
    }
    fun typeName(id: String) = st.types.firstOrNull { it.id == id }?.name ?: "未分组"
    val isFiltered = query.isNotBlank() || cat.isNotEmpty()
    val signedOut by com.geek.umbra.app.AppGraph.guard.locked.collectAsState()

    UmbraScreen(
        title = "保险箱",
        onBack = { router.back() },
        refreshing = st.loading,
        onRefresh = { AppGraph.scope.launch { VaultStore.syncNow() } },
        actions = {
            UmbraTopBarIcon(UmbraIconPaths.plus, "新建", { router.go(Route.VaultEdit(null)) }, tint = c.orange)
            UmbraTopBarIcon(UmbraExtraIcons.more, "更多", { presentMoreSheet(audit) }, tint = c.orange)
        },
    ) {
        if (VaultLockNote.shows(se.autoLockMinutes, noteClosed, se.lockNoteSilenced)) {
            item("note") { UnlockedNote(se.autoLockMinutes, onSilence = { VaultSession.silenceLockNote() }, onClose = { noteClosed = true }) }
        }
        // 未登录（单机）时本机这份就是全部数据：「离线 / 连上网会补推」都不成立，不画；syncProblem 照旧画。
        if ((st.offline && !signedOut) || st.syncProblem.isNotEmpty()) item("offline") { OfflineNote(st) }
        item("profile") { ProfileCard(st) { switchProfile(st) { cat = "" } } }
        item("search") { UmbraSearchField(query, { query = it }, placeholder = "搜名称、账号或网址") }
        item("cats") {
            UmbraFilterChips {
                UmbraTagPill("全部", cat == "", { cat = "" }, count = st.items.size)
                UmbraTagPill("收藏", cat == "fav", { cat = "fav" }, count = st.items.count { it.favorite == true })
                for (t in st.types) UmbraTagPill(t.name, cat == t.id, { cat = t.id }, count = st.items.count { it.typeId == t.id })
            }
        }
        item("check") { CheckupLine(audit) }
        if (rows.isEmpty()) {
            item("empty") {
                val qt = query.trim()
                val title = if (qt.isNotEmpty()) "没有匹配「$qt」的记录" else if (st.items.isEmpty()) "还没有记录" else "这个分组下还没有记录"
                val hint = if (qt.isEmpty()) "加一条登录信息，工作流也能直接取用。" else "密码与密文内容不参与搜索，可以试试名称、账号或网址。"
                if (isFiltered) UmbraEmptyState(UmbraIconPaths.filter, title, hint = hint, action = "清掉筛选", onAction = { query = ""; cat = "" })
                else UmbraEmptyState(UmbraIconPaths.lockKeyhole, title, hint = hint, action = "存一条新的", onAction = { router.go(Route.VaultEdit(null)) })
            }
        } else {
            for ((gid, name, items) in groups) {
                item("h-$gid") {
                    Row(Modifier.padding(horizontal = 4.dp).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        UmbraFieldLabel(name, Modifier.weight(1f))
                        Text("${items.size} 条", color = c.faint, style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                    }
                }
                for (it in items) item("r-$gid-${it.id}") {
                    RecordRow(it, showsGroups, typeName(it.typeId), query)
                }
            }
        }
    }
}

/** 页面里清掉错误卡（「重新输入」）。 */
internal fun VaultStore.clearError() = run { setErrorForUi("") }

// MARK: - 上锁态

@Composable
private fun LockedBody(
    st: VaultState, se: VaultSessionState,
    password: String, onPassword: (String) -> Unit, secretKey: String, onSecretKey: (String) -> Unit,
    pwFocus: FocusRequester, wrongCount: Int,
    onFace: () -> Unit, onRetryFace: () -> Unit, onSubmit: () -> Unit, onSoftUnlock: () -> Unit, onClearError: () -> Unit,
) {
    val c = UmbraTheme.colors
    val router = AppGraph.router
    val name = VaultSession.biometryName
    // 软锁只要验身份，不需要存过主密码；硬锁必须有存好的凭证。
    val faceUsable = VaultSession.biometryAvailable && se.faceIDEnabled && (se.hasBiometricCredential || se.softLocked)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FaceIconArea(se.faceState, faceUsable, name, onFace)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            val title = when (se.faceState) {
                FaceState.Scanning -> "识别中…"
                FaceState.Failed -> "${name}没通过"
                FaceState.Idle -> VaultLockNote.LOCKED_TITLE
            }
            val sub = when (se.faceState) {
                // 不写「看一下屏幕」：指纹机上不成立（设计侧 199 答复第 13 条）
                FaceState.Scanning -> "按系统提示验证"
                FaceState.Failed -> "系统没能识别，可能是没对准或超过了等待时间。点一下识别图标再试一次，或者输入主密码。"
                FaceState.Idle -> when {
                    se.softLocked -> VaultLockNote.lockedLine(se.autoLockMinutes)
                    !se.faceIDEnabled -> "$name 已关闭，输入主密码解锁。主密码不保存、不上传，忘记无法找回。"
                    se.hasBiometricCredential -> "进来时会自动识别 $name。也可以输入主密码，主密码存在这台设备的安全硬件里，不上传，忘记无法找回。"
                    else -> "用主密码解锁。主密码不保存、不上传，忘记无法找回。"
                }
            }
            Text(title, color = c.text, style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold))
            Text(sub, color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 21.sp), textAlign = TextAlign.Center)
        }
        if (!se.softLocked) {
            VaultInput(password, onPassword, Modifier.focusRequester(pwFocus), placeholder = "主密码", password = true, mono = true, center = true,
                error = st.error.isNotEmpty(), imeDone = onSubmit)
            // 本机第一次解锁要 Secret Key（电脑端 Emergency Kit 里那串）。存过就不再问。
            if (!st.hasSecretKey) {
                VaultInput(secretKey, onSecretKey, placeholder = "Secret Key（电脑端 Emergency Kit）", mono = true, fontSize = 14f, allCaps = true)
            }
        }
        val f = se.faceFailure
        if (f != null && se.faceState == FaceState.Failed) FaceErrorCard(name, f, onRetryFace) {
            VaultSession.preferPassword(); runCatching { pwFocus.requestFocus() }
        }
        if (st.error.isNotEmpty()) ErrorCard(st.error, wrongCount, onClearError)
        if (se.softLocked) {
            UmbraButton("解锁保险箱", onSoftUnlock, Modifier.fillMaxWidth(), height = 52.dp)
            UmbraButton("改用主密码（会完全上锁）", { VaultStore.lock(); VaultSession.markManualLock() }, Modifier.fillMaxWidth(), kind = UmbraButtonKind.Secondary)
        } else {
            UmbraButton(if (st.loading) "解锁中…" else "解锁保险箱", onSubmit, Modifier.fillMaxWidth(), enabled = !st.loading, height = 52.dp)
            // 用不了就不摆这颗按钮 —— 摆一个点了只会弹「已关掉」的按钮比不摆更糟。
            if (faceUsable) {
                val label = when (se.faceState) { FaceState.Scanning -> "识别中…"; FaceState.Failed -> "再识别一次"; FaceState.Idle -> "${name}解锁" }
                Box(Modifier.heightIn(min = UmbraDimens.tapMin).clickable(enabled = se.faceState != FaceState.Scanning, role = Role.Button, onClick = onFace), contentAlignment = Alignment.Center) {
                    Text(label, color = if (se.faceState == FaceState.Scanning) c.faint else c.orangeText, style = TextStyle(fontSize = 15.sp, fontWeight = UmbraType.weight560))
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 4.dp)) {
            VaultLinkText("换了新设备？输入 Secret Key") { router.go(Route.VaultRecover) }
            VaultLinkText("还没有保险箱？在这台手机上建一个") { router.go(Route.VaultCreate) }
        }
    }
}

/** 识别图标区：96 / 圆角 26，56 的识别图标。scanning 时呼吸 + 一条扫描线上下走 —— 看起来在动的东西必须真的在动。 */
@Composable
private fun FaceIconArea(state: FaceState, usable: Boolean, name: String, onTap: () -> Unit) {
    val c = UmbraTheme.colors
    val scanning = state == FaceState.Scanning
    val failed = state == FaceState.Failed
    val bg by animateColorAsState(if (scanning) c.orangeSoft else if (failed) c.warningSoft else c.chip, tween(200), label = "fbg")
    val fg = if (scanning) c.orange else if (failed) c.warning else c.faint
    val t = rememberInfiniteTransition(label = "breath")
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val line by t.animateFloat(-18f, 18f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "l")
    Box(
        Modifier.size(96.dp).clip(RoundedCornerShape(26.dp)).background(bg).clickable(role = Role.Button, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        // 刷脸走不通时画一把锁 —— 画了识别图标就是在暗示「点我能刷」。
        UmbraIcon(
            if (usable) UmbraIconPaths.faceId else UmbraIconPaths.lock, fg, size = 56.dp, strokeWidth = 1.4f,
            modifier = if (scanning) Modifier.alpha(0.55f + 0.45f * breath).scale(0.97f + 0.06f * breath) else Modifier,
            contentDescription = if (!usable) "保险箱已上锁" else if (failed) "再识别一次" else "用${name}解锁",
        )
        if (scanning) {
            Box(Modifier.offset(y = line.dp).padding(horizontal = 22.dp).fillMaxWidth().height(2.dp).alpha(0.7f).clip(RoundedCornerShape(1.dp)).background(c.orange))
        }
    }
}

/** 生物识别未通过（琥珀）。三段式。 */
@Composable
private fun FaceErrorCard(name: String, f: VaultBiometricStore.Failure, onRetry: () -> Unit, onPassword: () -> Unit) {
    val c = UmbraTheme.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(UmbraDimens.radiusCard)).background(c.warningSoft).padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UmbraIcon(UmbraIconPaths.alertTriangle, c.warningText, size = 14.dp, strokeWidth = 2.1f)
            Spacer(Modifier.width(7.dp))
            Text(f.title(name), color = c.warningText, style = TextStyle(fontSize = 14.5.sp, fontWeight = UmbraType.weight560))
        }
        Text(f.message(name), color = c.warningText, style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            UmbraButton("再试一次", onRetry, Modifier.weight(1f), kind = UmbraButtonKind.Secondary, height = 40.dp)
            UmbraButton("用主密码解锁", onPassword, Modifier.weight(1f), kind = UmbraButtonKind.Secondary, height = 40.dp)
        }
    }
}

/** 这条错误是「服务端不认这台手机的登录」（401 / 403 那句）。 */
private fun isLoginProblem(why: String) = why.contains("登录")

/** 这条错误是「连接 / 令牌」还是「密码」：两类问题下一步要做的事完全不同，给错按钮比不给更糟。 */
private fun isConnectionProblem(why: String): Boolean =
    isLoginProblem(why) || listOf("令牌", "服务器", "连不上", "地址", "同步接口").any { why.contains(it) }

/** 主密码错误 / 连接问题（砖红）。第三段按问题类型给不同按钮；连错 3 次改提示 SK 恢复。 */
@Composable
private fun ErrorCard(why: String, wrongCount: Int, onClear: () -> Unit) {
    val c = UmbraTheme.colors
    val router = AppGraph.router
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(UmbraDimens.radiusCard)).background(c.dangerSoft).padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UmbraIcon(UmbraIconPaths.xCircle, c.dangerText, size = 15.dp, strokeWidth = 2.1f)
            Spacer(Modifier.width(7.dp))
            Text("没能解锁", color = c.dangerText, style = TextStyle(fontSize = 14.5.sp, fontWeight = UmbraType.weight560))
        }
        Text(
            if (wrongCount >= 3 && !isConnectionProblem(why)) "已经错了 $wrongCount 次。忘记主密码只能用 Secret Key 恢复，Umbra 这边没有备份。" else why,
            color = c.dangerText, style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            when {
                isLoginProblem(why) -> {
                    UmbraButton(stringResource(R.string.vault_goAccount), { router.go(Route.SetAccount) }, Modifier.weight(1f), height = 40.dp)
                    UmbraButton("重新输入", onClear, Modifier.weight(1f), kind = UmbraButtonKind.Secondary, height = 40.dp)
                }
                isConnectionProblem(why) -> {
                    UmbraButton("去填连接", { router.go(Route.SetConn) }, Modifier.weight(1f), height = 40.dp)
                    UmbraButton("重新输入", onClear, Modifier.weight(1f), kind = UmbraButtonKind.Secondary, height = 40.dp)
                }
                else -> {
                    UmbraButton("重新输入", onClear, Modifier.weight(1f), kind = UmbraButtonKind.Secondary, height = 40.dp)
                    UmbraButton("用 Secret Key 恢复", { router.go(Route.VaultRecover) }, Modifier.weight(1f), kind = UmbraButtonKind.Secondary, height = 40.dp)
                }
            }
        }
    }
}

/**
 * 第一次用主密码解锁成功后，问一句要不要开生物识别（sam 2026-10-16）。答应 → 走和设置里那个开关同一条 enableBiometry
 * （当场验主密码 + 刷一次，两关都过才存）；不要 → 说一句以后在哪儿开。问过一次就不再问。
 */
private fun offerBiometryIfFirstTime(password: String) {
    val sp = VaultPrefs.sp
    if (!VaultSession.biometryAvailable || VaultSession.s.hasBiometricCredential || sp.getBoolean(VaultPrefs.FACE_ID_OFFERED, false)) return
    sp.edit().putBoolean(VaultPrefs.FACE_ID_OFFERED, true).apply()
    val name = VaultSession.biometryName
    val router = AppGraph.router
    router.confirm(UmbraAlert(
        title = "用 $name 解锁保险箱？",
        message = "打开后，这里和别的 App 的密码自动填充都可以用${name}解锁，没通过再输主密码。主密码存在这台设备的安全硬件里，不上传、不进备份。",
        confirmLabel = "打开", cancelLabel = "暂不",
        onCancel = { UmbraNotice.present("以后也可以打开", "保险箱「⋯」→「保险箱设置」→「用 $name 解锁」。") },
    ) {
        val act = VaultHostActivity.current ?: return@UmbraAlert
        VaultSession.enableBiometry(act, password) { err -> router.showToast(err ?: "$name 解锁已开启") }
    })
}

// MARK: - 解锁态

/**
 * 自动锁定那条**条幅**（设计侧 214 §一）：内容区第一张卡，不自动消失；「×」= 这一次关掉，「不再提示」= 以后不出。
 * 文案与 PC 那一句逐字同源（[VaultLockNote.text]）。
 */
@Composable
private fun UnlockedNote(minutes: Int, onSilence: () -> Unit, onClose: () -> Unit) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(c.card).border(UmbraDimens.borderW, c.border, shape).padding(start = 13.dp, top = 11.dp, bottom = 3.dp), verticalAlignment = Alignment.Top) {
        UmbraIcon(UmbraIconPaths.info, c.faint, size = 15.dp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(VaultLockNote.text(minutes), color = c.muted, style = TextStyle(fontSize = 12.5.sp, lineHeight = 19.sp))
            Box(Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button, onClick = onSilence), contentAlignment = Alignment.CenterStart) {
                Text("不再提示", color = c.orangeText, style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560))
            }
        }
        Box(Modifier.size(UmbraDimens.tapMin).clickable(role = Role.Button, onClick = onClose), contentAlignment = Alignment.Center) {
            UmbraIcon(UmbraIconPaths.x, c.faint, size = 15.dp, strokeWidth = 2f, contentDescription = "关闭")
        }
    }
}

/** 离线 / 没推上去的提示。syncProblem 优先：它是「连着网也推不上去」的那几种，说成「离线」就给错了下一步。 */
@Composable
private fun OfflineNote(st: VaultState) {
    val c = UmbraTheme.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(UmbraDimens.radiusControl)).background(c.warningSoft).padding(start = UmbraDimens.sp4, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.Top) {
        UmbraIcon(UmbraIconPaths.alertTriangle, c.warningText, size = 15.dp, strokeWidth = 2f, modifier = Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                if (st.syncProblem.isNotEmpty()) "有改动还没同步上去" else if (st.pendingPush) "离线 · 有改动还没同步上去" else "离线 · 看的是本机缓存",
                color = c.warningText, style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560),
            )
            Text(
                if (st.syncProblem.isNotEmpty()) st.syncProblem else if (st.pendingPush) "改动已经存在这台手机上了，连上网会自动补推。" else "别的设备上的新改动要等联网后才看得到。",
                color = c.warningText, style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            )
        }
        when (VaultSyncRules.retryAction(st.needsReunlock)) {
            VaultSyncRules.RetryAction.SyncNow -> UmbraStripAction("重试", c.warningText, { AppGraph.scope.launch { VaultStore.syncNow() } })
            VaultSyncRules.RetryAction.Relock -> UmbraStripAction("锁定并重新解锁", c.warningText, { confirmRelock() })
        }
    }
}

/**
 * 「锁定并重新解锁」先过一道确认（umbra-ios#28）：这批没推上去的改动是旧主密码加的密，换成新主密码之后带不过去。
 * 没有待推改动时不问。
 */
private fun confirmRelock() {
    if (!VaultStore.s.pendingPush) { VaultStore.relockForNewKey(); return }
    AppGraph.router.confirm(UmbraAlert(
        title = "锁定并用新主密码解锁？",
        message = "这台手机上还有没推上去的改动，是用旧主密码加密的，换成新主密码之后带不过去，会以云端那份为准。要先把它们抄下来，就先别锁。",
        confirmLabel = "锁定", destructive = true,
    ) { VaultStore.relockForNewKey() })
}

@Composable
private fun ProfileCard(st: VaultState, onClick: () -> Unit) {
    val c = UmbraTheme.colors
    val cur = st.vaults.firstOrNull { it.id == st.curVaultId }
    val shape = RoundedCornerShape(UmbraDimens.radiusCard)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.card).border(UmbraDimens.borderW, c.border, shape).clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(UmbraDimens.radiusControl)).background(c.orangeSoft), contentAlignment = Alignment.Center) {
            Text((cur?.name ?: "库").take(1), color = c.orangeText, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(cur?.name ?: "默认库", color = c.text, style = UmbraType.rowTitle)
            Text("当前身份库 · ${st.items.size} 条记录", color = c.faint, style = TextStyle(fontSize = 12.sp))
        }
        Text("切换", color = c.orangeText, style = TextStyle(fontSize = 13.sp, fontWeight = UmbraType.weight560))
    }
}

private fun switchProfile(st: VaultState, onSwitched: () -> Unit) {
    val items = st.vaults.map { v ->
        VaultSheetItem(v.name, checked = v.id == st.curVaultId) { VaultStore.switchVault(v.id); onSwitched() }
    } + VaultSheetItem("管理身份库") { AppGraph.router.go(Route.VaultProfiles) }
    presentVaultSheet("身份库", "身份库之间数据隔离，同一时刻只有一个是当前库。", items)
}

/** 安全体检的一行细摘要：22 的小分数环 + 一句话，整行可点。有问题时数字用警示色，没问题时整行都是弱色。 */
@Composable
private fun CheckupLine(audit: VaultAudit) {
    val c = UmbraTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = UmbraDimens.tapMin).clickable { AppGraph.router.go(Route.VaultCheck) }.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VaultScoreRing(audit.score, 22.dp)
        Spacer(Modifier.width(8.dp))
        Text("安全体检", color = c.muted, style = TextStyle(fontSize = 13.sp, fontWeight = UmbraType.weight560))
        Spacer(Modifier.width(8.dp))
        Text(if (audit.total == 0) "没有发现问题" else "${audit.total} 项需要处理", color = if (audit.total == 0) c.faint else c.warningText,
            style = TextStyle(fontSize = 13.sp), maxLines = 1, modifier = Modifier.weight(1f))
        UmbraIcon(UmbraIconPaths.chevronRight, c.faint, size = 14.dp, strokeWidth = 2.2f)
    }
}

/** 只有恰好一个账号带密码时才给左滑「复制密码」（多个账号走长按菜单，那里每个账号一项、带着用户名）。 */
private fun passwordOf(it: VItem): String? = it.accounts.map { a -> a.pwd }.filter { p -> p.isNotEmpty() }.singleOrNull()

/** 删除确认。左滑和长按菜单共用这一份。 */
private fun confirmDelete(it: VItem) {
    AppGraph.router.confirm(UmbraAlert(
        title = "删除「${it.title}」？",
        message = "会移入回收站保留 30 天，之后彻底删除。附件与图片一并移入。所有设备上都会一起进回收站。",
        confirmLabel = "移入回收站", destructive = true,
    ) {
        AppGraph.scope.launch { VaultStore.deleteItem(it.id) }
        AppGraph.router.showToast("已移入回收站 · 保留 30 天")
    })
}

/**
 * 一行记录：左滑露出「复制密码（只有一个密码时）/ 删除」两颗钮（**只露钮、不滑到底自动执行**，删除必过确认）；
 * 长按出菜单。副文优先显示账号，**永远不显示密码**。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordRow(it: VItem, showsGroups: Boolean, typeName: String, query: String) {
    val c = UmbraTheme.colors
    val router = AppGraph.router
    val scope = rememberCoroutineScope()
    val pwd = passwordOf(it)
    val density = LocalDensity.current
    val btnW = 76.dp
    val revealPx = with(density) { (btnW * (if (pwd != null) 2 else 1)).toPx() }
    val offset = remember(it.id) { Animatable(0f) }
    fun close() { scope.launch { offset.animateTo(0f, tween(160)) } }
    val shape = RoundedCornerShape(UmbraDimens.radiusSwipeRow)
    Box(Modifier.fillMaxWidth().clip(shape)) {
        // 背后的两颗钮
        Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
            if (pwd != null) {
                Box(Modifier.width(btnW).fillMaxHeight().background(c.orange).clickable {
                    close(); VaultClipboard.copySensitive(pwd); router.showToast("已复制密码 · 60 秒后自动清除")
                }, contentAlignment = Alignment.Center) { Text("复制密码", color = c.onAccent, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)) }
            }
            Box(Modifier.width(btnW).fillMaxHeight().background(c.danger).clickable { close(); confirmDelete(it) }, contentAlignment = Alignment.Center) {
                Text("删除", color = c.onDanger, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
            }
        }
        Row(
            Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }
                .fillMaxWidth().background(c.card)
                .draggable(
                    rememberDraggableState { d -> scope.launch { offset.snapTo((offset.value + d).coerceIn(-revealPx, 0f)) } },
                    Orientation.Horizontal,
                    onDragStopped = { v -> scope.launch { offset.animateTo(if (offset.value < -revealPx / 2 || v < -800f) -revealPx else 0f, tween(160)) } },
                )
                .combinedClickable(
                    onClick = { if (offset.value != 0f) close() else router.go(Route.VaultRecord(it.id, query)) },
                    onLongClick = { rowMenu(it, showsGroups) },
                )
                .heightIn(min = UmbraDimens.rowMinHSub).padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VaultRecordIcon(it.icon, it.title.take(1), side = 34.dp, corner = UmbraDimens.radiusControl)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it.title, color = c.text, style = UmbraType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (it.favorite == true) { Spacer(Modifier.width(6.dp)); UmbraIcon(UmbraIconPaths.star, c.orangeText, size = 13.dp, strokeWidth = 2f) }
                }
                val sub = it.accounts.map { a -> a.user }.firstOrNull { u -> u.isNotEmpty() }
                    ?: if (it.isCanvas) "${it.nodes.size} 个节点" else typeName
                Text(sub, color = c.muted, style = TextStyle(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (showsGroups && !it.isCanvas) {
                Text(typeName, color = c.faint, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(c.chip).padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.width(6.dp))
            }
            UmbraIcon(UmbraIconPaths.chevronRight, c.faint, size = 16.dp, strokeWidth = 2.2f)
        }
    }
}

/** 长按菜单：每个账号各给一组（多个时带上用户名）、收藏、移动到分组、删除。 */
private fun rowMenu(it: VItem, showsGroups: Boolean) {
    val items = mutableListOf<VaultSheetItem>()
    val accounts = it.accounts
    for (acc in accounts) {
        val who = if (accounts.size > 1) "（${acc.user.ifEmpty { "未填账号" }}）" else ""
        if (acc.user.isNotEmpty()) items += VaultSheetItem("复制账号$who") {
            VaultClipboard.copySensitive(acc.user); AppGraph.router.showToast("已复制账号 · 60 秒后自动清除")
        }
        if (acc.pwd.isNotEmpty()) items += VaultSheetItem("复制密码$who") {
            VaultClipboard.copySensitive(acc.pwd); AppGraph.router.showToast("已复制密码 · 60 秒后自动清除")
        }
    }
    items += VaultSheetItem(if (it.favorite == true) "取消收藏" else "加入收藏") { AppGraph.scope.launch { VaultStore.toggleFav(it.id) } }
    if (showsGroups && !it.isCanvas) items += VaultSheetItem("移动到分组") { movePicker(it) }
    items += VaultSheetItem("删除", destructive = true) { confirmDelete(it) }
    presentVaultSheet(it.title, null, items)
}

private fun movePicker(it: VItem) {
    presentVaultSheet("移动到分组", null, VaultStore.s.types.map { t ->
        VaultSheetItem(t.name, checked = t.id == it.typeId) {
            AppGraph.scope.launch { VaultStore.moveItem(it.id, t.id) }
            AppGraph.router.showToast("已移到「${t.name}」")
        }
    })
}

/** 「⋯」动作面板：每一行带**当下的真实值**（几项待处理、几个分组、几分钟自动锁）。 */
private fun presentMoreSheet(audit: VaultAudit) {
    val st = VaultStore.s
    val se = VaultSession.s
    val r = AppGraph.router
    val vaultName = st.vaults.firstOrNull { it.id == st.curVaultId }?.name ?: "默认库"
    val bio = if (se.faceIDEnabled && se.hasBiometricCredential) "${VaultSession.biometryName}已开启" else "只用主密码"
    val items = mutableListOf(
        VaultSheetItem("安全体检", if (audit.total == 0) "没有发现问题" else "${audit.total} 项需要处理") { r.go(Route.VaultCheck) },
        VaultSheetItem("密码生成器", "本机随机，不联网") { r.go(Route.VaultGen) },
    )
    // 画布版没有分组：types 为空时这一行不给（给了点进去是一页空的）。
    if (st.types.isNotEmpty()) items += VaultSheetItem("分组", "${st.types.size} 个") { r.go(Route.VaultGroups) }
    items += listOf(
        VaultSheetItem("身份库", "当前：$vaultName · 共 ${st.vaults.size} 个") { r.go(Route.VaultProfiles) },
        VaultSheetItem("回收站", if (st.trashCount > 0) "${st.trashCount} 项" else "保留 30 天") { r.go(Route.VaultTrash) },
        VaultSheetItem("从别处导入", "这一版在电脑上") { r.go(Route.VaultImport) },
        VaultSheetItem("保险箱设置", "${se.autoLockMinutes} 分钟自动锁定 · $bio") { r.go(Route.VaultSettings) },
        VaultSheetItem("立即上锁", "${se.autoLockMinutes} 分钟后也会自动锁") {
            VaultStore.lock(); VaultSession.markManualLock(); r.showToast("已重新上锁")
        },
    )
    presentVaultSheet("保险箱", "${st.items.size} 条记录 · $vaultName", items)
}
