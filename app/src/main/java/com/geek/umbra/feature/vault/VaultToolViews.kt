// 保险箱的工具页（iOS VaultToolViews.swift）：生成器、安全体检、分组、身份库、导入、创建、恢复、Secret Key、设置、改主密码，
// 外加自动填充说明页（iOS 那一页是填充面板的形态演示；Android 改成「去系统设置选 Umbra」的说明 + 一颗按钮）。
// 判断标准只有一条：**这台手机上做得到吗。** 做得到就做真的；做不到就给一个说清楚状况、并指出下一步在哪的承接页。
package com.geek.umbra.feature.vault

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraButton
import com.geek.umbra.designsystem.UmbraButtonKind
import com.geek.umbra.designsystem.UmbraCard
import com.geek.umbra.designsystem.UmbraChoice
import com.geek.umbra.designsystem.UmbraChoiceItem
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraFieldLabel
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraProgressBar
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraSectionLabel
import com.geek.umbra.designsystem.UmbraSettingRow
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.designsystem.settingSection
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import com.geek.umbra.navigation.UmbraSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 设置页顶上那段说明（iOS UmbraSettingsPage 的 intro / footnote）。 */
@Composable
private fun Intro(text: String) {
    Text(text, color = UmbraTheme.colors.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp), modifier = Modifier.padding(horizontal = 4.dp))
}

// MARK: - 密码生成器（真做）

@Composable
internal fun VaultGenContent() {
    val c = UmbraTheme.colors
    var opts by remember { mutableStateOf(PasswordGen.Options()) }
    var value by rememberSaveable { mutableStateOf("") }
    fun regen() { value = PasswordGen.make(opts) }
    LaunchedEffect(Unit) { if (value.isEmpty()) regen() }
    UmbraScreen(title = "密码生成器", onBack = { AppGraph.router.back() }) {
        item("value") {
            VaultCardBox(padding = 16.dp) {
                // 不给系统文本选择：系统「复制」走的是普通剪贴板。复制只走敏感入口。
                Text(value, color = c.text, style = TextStyle(fontSize = 17.sp, lineHeight = 29.sp, fontFamily = FontFamily.Monospace))
                Spacer(Modifier.height(11.dp))
                val st = PasswordStrength.of(value)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UmbraProgressBar(st.pct / 100f, strengthColor(st.level), Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text("强度 ${PasswordStrength.label(st.level)}", color = strengthTextColor(st.level), style = TextStyle(fontSize = 11.5.sp, fontWeight = UmbraType.weight560))
                    Spacer(Modifier.width(8.dp))
                    Text("换一个", color = c.orangeText, style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560),
                        modifier = Modifier.heightIn(min = UmbraDimens.tapMin).clickable { regen() }.padding(top = 13.dp))
                }
            }
        }
        item("len") {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UmbraFieldLabel("长度", Modifier.weight(1f))
                    Text("${opts.length}", color = c.text, style = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = UmbraType.weight560))
                }
                Slider(opts.length.toFloat(), { opts = opts.copy(length = it.toInt()); regen() }, valueRange = 8f..40f, steps = 31,
                    colors = SliderDefaults.colors(thumbColor = c.orange, activeTrackColor = c.orange, inactiveTrackColor = c.track, activeTickColor = androidx.compose.ui.graphics.Color.Transparent, inactiveTickColor = androidx.compose.ui.graphics.Color.Transparent))
            }
        }
        settingSection("opts", null, listOf(
            UmbraSettingRow("包含大写字母", toggle = opts.upper, onToggle = { opts = opts.copy(upper = it); regen() }),
            UmbraSettingRow("包含数字", toggle = opts.digit, onToggle = { opts = opts.copy(digit = it); regen() }),
            UmbraSettingRow("包含符号", toggle = opts.symbol, onToggle = { opts = opts.copy(symbol = it); regen() }),
            UmbraSettingRow("避免易混字符 l 1 I O 0", toggle = opts.noAmbiguous, onToggle = { opts = opts.copy(noAmbiguous = it); regen() }),
        ))
        item("note") { VaultNote("生成的密码不会自动保存。复制之后回到记录里粘进密码字段，再存一次。") }
        item("copy") {
            UmbraButton("复制这个密码", { VaultClipboard.copySensitive(value); AppGraph.router.showToast("已复制 · 60 秒后自动清除") }, Modifier.fillMaxWidth(), height = 52.dp)
        }
    }
}

// MARK: - 安全体检（真做，全在本机算）

@Composable
internal fun VaultCheckContent() {
    val st by VaultStore.state.collectAsState()
    val c = UmbraTheme.colors
    val audit = remember(st.items) { VaultAudit(st.items) }
    UmbraScreen(title = "安全体检", onBack = { AppGraph.router.back() }) {
        item("score") {
            VaultCardBox(padding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VaultScoreRing(audit.score, 72.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (audit.total == 0) "没有发现问题" else "${audit.total} 项需要处理", color = c.text, style = UmbraType.rowTitle)
                        Text("共 ${st.items.size} 条记录 · 只在本机计算，不上传", color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp))
                    }
                }
            }
        }
        if (audit.groups.isEmpty()) item("ok") {
            VaultNote(if (st.items.isEmpty()) "还没有记录，体检没什么可看的。" else "重复密码、弱密码、没开两步验证、长期未更新 —— 四项都没问题。", size = 13f)
        }
        for (g in audit.groups) item("g-${g.id}") {
            val (solid, ink) = when (g.tone) {
                VaultAudit.Tone.Danger -> c.danger to c.dangerText
                VaultAudit.Tone.Warning -> c.warning to c.warningText
                VaultAudit.Tone.Muted -> c.muted to c.muted
            }
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UmbraIcon(g.icon, solid, size = 14.dp)
                    Spacer(Modifier.width(7.dp))
                    Text(g.name, color = ink, style = UmbraType.fieldLabel, modifier = Modifier.weight(1f))
                    Text("${g.findings.size} 条", color = c.faint, style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                }
                VaultRowsCard(rows = g.findings.map { f ->
                    @Composable {
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { AppGraph.router.go(Route.VaultRecord(f.item.id)) }.padding(horizontal = 13.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            VaultRecordIcon(null, f.item.title.take(1), side = 30.dp, corner = 9.dp)
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(f.item.title, color = c.text, style = TextStyle(fontSize = 15.sp, fontWeight = UmbraType.weight560))
                                Text(f.why, color = c.faint, style = TextStyle(fontSize = 12.5.sp))
                            }
                            UmbraIcon(UmbraIconPaths.chevronRight, c.faint, size = 15.dp, strokeWidth = 2.2f)
                        }
                    }
                })
            }
        }
        item("tail") { VaultNote("体检只看密码本身的强度、重复与更新时间，不会去任何网站校验，也不查泄漏库 —— 那要把密码发出去。") }
    }
}

// MARK: - 分组（只读 + 移动记录）/ 身份库（只读 + 切换）

@Composable
internal fun VaultGroupsContent() {
    val st by VaultStore.state.collectAsState()
    val faint = UmbraTheme.colors.faint
    UmbraScreen(title = "分组", onBack = { AppGraph.router.back() }, refreshing = st.loading, onRefresh = { AppGraph.scope.launch { VaultStore.syncNow() } }) {
        item("intro") { Intro("分组是记录的分类（登录、密钥、证件…）。手机上可以把记录移到别的分组里，新建、改名、删除分组要去电脑上做 —— 那会改到快照结构，风险不该由手机端承担。") }
        settingSection("types", "共 ${st.types.size} 个",
            if (st.types.isEmpty()) listOf(UmbraSettingRow("（还没有分组）", tint = faint))
            else st.types.map { t -> UmbraSettingRow(t.name, value = "${st.items.count { it.typeId == t.id }} 条") },
            footer = "在记录列表长按一条记录，选「移动到分组」就能换组。")
    }
}

@Composable
internal fun VaultProfilesContent() {
    val st by VaultStore.state.collectAsState()
    UmbraScreen(title = "身份库", onBack = { AppGraph.router.back() }, refreshing = st.loading, onRefresh = { AppGraph.scope.launch { VaultStore.syncNow() } }) {
        item("intro") { Intro("身份库之间数据隔离，同一时刻只有一个是当前库。手机上可以切换，新建、改名、删除要去电脑上做。") }
        settingSection("vaults", "共 ${st.vaults.size} 个", st.vaults.map { v ->
            val cur = v.id == st.curVaultId
            UmbraSettingRow(v.name, sub = if (cur) "当前库" else null, value = if (cur) "使用中" else "切到这个", chevron = !cur) {
                if (!cur) { VaultStore.switchVault(v.id); AppGraph.router.showToast("已切到身份库「${v.name}」") }
            }
        }, footer = "切换后列表、搜索、体检都只看当前库。")
    }
}

// MARK: - 导入 / 改主密码（如实说明）

@Composable
internal fun VaultImportContent() {
    UmbraScreen(title = "导入", onBack = { AppGraph.router.back() }) {
        item("intro") { Intro("从浏览器导出的密码 CSV 是明文。手机上不做导入 —— 明文文件在手机里过一遍，风险远大于省下的那点事。") }
        settingSection("how", "在电脑上怎么做", listOf(
            UmbraSettingRow("1. 从 Chrome / Safari 导出密码 CSV"),
            UmbraSettingRow("2. 在电脑端保险箱里选「从浏览器导入」"),
            UmbraSettingRow("3. 导入完成后删掉那个 CSV 文件"),
            UmbraSettingRow("4. 手机上下拉一次就同步过来了"),
        ))
        item("foot") { VaultNote("导出明文备份同理，只在电脑端可用。") }
    }
}

@Composable
internal fun VaultPwdContent() {
    UmbraScreen(title = "修改主密码", onBack = { AppGraph.router.back() }) {
        item("intro") { Intro("改主密码要重新派生密钥、把整份数据重新加密、换掉校验值，再一次性推上去。中途任何一步出错，数据就再也打不开了 —— 这个风险不该由手机端承担，所以只在电脑上做。") }
        settingSection("how", "在电脑上怎么做", listOf(
            UmbraSettingRow("1. 打开电脑端的「保险箱 › 设置」"),
            UmbraSettingRow("2. 选「修改主密码」，输旧密码与新密码"),
            UmbraSettingRow("3. 等它重新加密并同步完成"),
        ), footer = "改完之后手机这边会解锁失败，用新主密码重新解锁一次就行（Secret Key 不变）。")
    }
}

// MARK: - 创建保险箱（本机建库）

@Composable
internal fun VaultCreateContent() {
    val c = UmbraTheme.colors
    val router = AppGraph.router
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var fail by remember { mutableStateOf("") }
    /** 建好之后的 Secret Key（只在这一屏的内存里）。 */
    var createdKey by remember { mutableStateOf<String?>(null) }
    var revealed by remember { mutableStateOf(false) }
    val canCreate = VaultAudit.graphemeCount(password) >= 6 && password == confirm && !busy
    UmbraScreen(title = "创建保险箱", onBack = { router.back() }) {
        val sk = createdKey
        if (sk != null) {
            item("kit") {
                Column(verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp5)) {
                    VaultNote("建好了。这是你的 Secret Key —— 和主密码一起才能解开数据，换设备、重装应用都要它，丢了服务端帮不上忙。现在抄到安全的地方。", size = 12.5f)
                    KeyCard(sk, revealed, { revealed = !revealed })
                    UmbraButton("我已经抄好了，进保险箱", { router.back() }, Modifier.fillMaxWidth(), height = 52.dp)
                }
            }
        } else {
            item("form") {
                Column(verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp4)) {
                    VaultNote("设一个主密码。它不保存、不上传，忘了无法找回；主密码和 Secret Key 一起才能解开数据。建好的库先存在这台手机上，登录 Umbra 账号后自动同步到云端。", size = 12.5f)
                    UmbraFieldLabel("主密码（至少 6 位）")
                    VaultInput(password, { password = it }, placeholder = "主密码", password = true)
                    UmbraFieldLabel("再输一遍")
                    VaultInput(confirm, { confirm = it }, placeholder = "再输一遍", password = true, error = confirm.isNotEmpty() && confirm != password)
                    if (fail.isNotEmpty()) Text(fail, color = c.dangerText, style = TextStyle(fontSize = 12.5.sp))
                    UmbraButton(if (busy) "创建中…" else "创建保险箱", {
                        if (!canCreate) return@UmbraButton
                        busy = true; fail = ""
                        AppGraph.scope.launch {
                            // 派生 60 万轮 PBKDF2 要一两秒：在后台算（createLocal 内部切到 Default）。
                            val key = VaultStore.createLocal(password)
                            busy = false
                            if (key == null) { fail = if (VaultStore.s.recordExists) "这台手机上已经有保险箱了" else "没建成，再试一次"; return@launch }
                            VaultSession.markUnlocked()
                            password = ""; confirm = ""
                            createdKey = key
                            // 登着就马上推上云端；单机的等登录后自己推（pullRecord 看到本机 pending）。
                            if (!AppGraph.guard.locked.value) AppGraph.scope.launch { VaultStore.syncNow() }
                        }
                    }, Modifier.fillMaxWidth(), enabled = canCreate, height = 52.dp)
                }
            }
        }
    }
}

/** Secret Key 那张卡：遮住 / 显示 / 复制（走敏感剪贴板）。 */
@Composable
private fun KeyCard(sk: String, revealed: Boolean, onToggle: () -> Unit) {
    val c = UmbraTheme.colors
    VaultCardBox {
        Text(if (revealed) sk else "•".repeat(20), color = c.text, style = TextStyle(fontSize = 16.sp, lineHeight = 26.sp, fontFamily = FontFamily.Monospace, fontWeight = UmbraType.weight560))
        Spacer(Modifier.height(11.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UmbraButton(if (revealed) "遮起来" else "显示", onToggle, kind = UmbraButtonKind.Secondary, height = 40.dp)
            UmbraButton("复制", { VaultClipboard.copySensitive(sk); AppGraph.router.showToast("已复制 Secret Key · 60 秒后自动清除") }, kind = UmbraButtonKind.Secondary, height = 40.dp)
        }
    }
}

// MARK: - 用 Secret Key 恢复（真做）

/**
 * 格式只挡一下手滑（真正对不对靠解密结果说话）。⚠️ 这条正则照抄 iOS（VaultToolViews.swift:768），
 * 它描述的是 `A3-7KQP-…` 那种旧形制，和今天 `U1-XXXXX-…` 的 Secret Key 对不上 —— 输入真 SK 时会画红框和提示，
 * 但不挡按钮，照样能恢复。两端一起改之前不在这一端单独改（已在报告里列出）。
 */
private val skPattern = Regex("^[A-Za-z0-9]{2}(-[A-Za-z0-9]{4}){3}\\s+[A-Za-z0-9]{2}(-[A-Za-z0-9]{4}){3}$")

@Composable
internal fun VaultRecoverContent() {
    val st by VaultStore.state.collectAsState()
    val c = UmbraTheme.colors
    val router = AppGraph.router
    val ctx = LocalContext.current
    var key by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val looksValid = skPattern.matches(key.trim())
    UmbraScreen(title = "用 Secret Key 恢复", onBack = { router.back() }) {
        item("form") {
            Column(verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp5)) {
                VaultNote("换了新设备时用。Secret Key 在电脑端的 Emergency Kit 里，和主密码一起才能解开数据 —— 两者缺一不可，服务端两样都没有。", size = 12.5f)
                Column(verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp2)) {
                    UmbraFieldLabel("Secret Key")
                    VaultInput(key, { key = it }, placeholder = "A3-7KQP-XM4T-9WZR B8-2LNV-6HDC-5FYJ", mono = true, fontSize = 14f, allCaps = true,
                        borderColor = if (key.isEmpty()) null else if (looksValid) c.orange else c.danger)
                    if (key.isNotEmpty() && !looksValid) Text("格式应该像 A3-7KQP-XM4T-9WZR B8-2LNV-6HDC-5FYJ（两段，各 4 组）。", color = c.dangerText, style = TextStyle(fontSize = 12.sp))
                    Text("从剪贴板粘贴", color = c.orangeText, style = TextStyle(fontSize = 13.sp, fontWeight = UmbraType.weight560),
                        modifier = Modifier.heightIn(min = UmbraDimens.tapMin).clickable {
                            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let { key = it }
                        }.padding(top = 13.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp2)) {
                    UmbraFieldLabel("主密码")
                    VaultInput(password, { password = it }, placeholder = "原来的主密码", password = true, mono = true)
                }
                if (st.error.isNotEmpty()) Text(st.error, color = c.dangerText, style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp))
                UmbraButton(if (st.loading) "恢复中…" else "在这台设备上恢复", {
                    AppGraph.scope.launch {
                        VaultStore.unlock(password, key.trim())
                        if (VaultStore.s.unlocked) {
                            VaultSession.markUnlocked()
                            // 解锁要派生密钥 + 拉快照，好几秒；这期间人可能已经退出这一页 —— 只在眼前仍是恢复页时才退。
                            router.back(ifShowing = Route.VaultRecover)
                            router.showToast("已在这台设备上恢复保险箱")
                        }
                    }
                }, Modifier.fillMaxWidth(), enabled = !st.loading, height = 52.dp)
                VaultNote("恢复成功后 Secret Key 会存进这台设备的安全硬件，以后只要主密码。")
            }
        }
    }
}

// MARK: - Secret Key 查看

@Composable
internal fun VaultKeyContent() {
    var revealed by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { if (revealed) { delay(8_000); revealed = false } }
    val sk = remember { VaultKeychain.load() }
    UmbraScreen(title = "Secret Key", onBack = { AppGraph.router.back() }) {
        item("intro") { VaultNote("Secret Key 和主密码一起才能解开数据。换设备、重装应用都要用它 —— 丢了就再也进不去，服务端帮不上忙。", size = 12.5f) }
        if (sk != null) {
            item("key") { KeyCard(sk, revealed, { revealed = !revealed; tick++ }) }
            item("note") { VaultNote("显示 8 秒后会自动遮起来。别把它和主密码存在同一个地方 —— 那等于把两把钥匙拴在一起。") }
        } else {
            item("none") {
                UmbraCard {
                    Column(verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp2)) {
                        UmbraSectionLabel("这台设备上还没有 Secret Key")
                        Text("解锁过一次之后它才会存进这台设备。去保险箱首页用主密码 + Secret Key 解锁一次就有了。", color = UmbraTheme.colors.text, style = UmbraType.body)
                    }
                }
            }
        }
    }
}

// MARK: - 保险箱设置

@Composable
internal fun VaultSettingsContent() {
    val st by VaultStore.state.collectAsState()
    val se by VaultSession.state.collectAsState()
    val c = UmbraTheme.colors
    val router = AppGraph.router
    val name = VaultSession.biometryName
    /** 通用区的条数只有服务端知道；拉不到就先按只有保险箱那部分算（宁可少报也不报一个编出来的数）。 */
    var genericTrash by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { VaultStore.fetchTrash()?.let { genericTrash = it.items.size } }
    val trashN = genericTrash + st.trashCount
    val bioAvailable = VaultSession.biometryAvailable

    val lockRows = buildList {
        add(UmbraSettingRow("自动锁定", value = "${se.autoLockMinutes} 分钟", chevron = true) {
            UmbraChoice.present(
                title = VaultLockNote.PICKER_TITLE,
                items = listOf(1, 5, 10, 30).map { m ->
                UmbraChoiceItem("$m 分钟", checked = se.autoLockMinutes == m) { VaultSession.setAutoLockMinutes(m) }
            },
            )
        })
        // Android 上「遮盖」落成 FLAG_SECURE：切走时任务切换器里看不到内容，也截不了屏。
        add(UmbraSettingRow("切到后台立即遮盖", sub = "App 切走时遮住内容，也不许截屏", toggle = se.maskEnabled, onToggle = { VaultSession.setMaskEnabled(it) }))
        if (bioAvailable) {
            val on = se.faceIDEnabled && se.hasBiometricCredential
            add(UmbraSettingRow(
                // 标题与副标题是设计侧 199 答复第 13 条定稿
                "用${name}解锁",
                sub = "仍需主密码作为最终凭证",
                toggle = on,
                onToggle = {
                    when {
                        on -> { VaultSession.disableBiometry(); router.showToast("已关闭，存的主密码也一并清掉了") }
                        // 没解锁就没法验主密码对不对（校验值在 record 里）。
                        !st.unlocked -> router.showToast("先解锁保险箱，再开这个开关")
                        else -> presentPasswordConfirm()
                    }
                },
            ))
        } else {
            // 不可用时不给一个点不动的开关，直接说明为什么。
            add(UmbraSettingRow("用${name}解锁", sub = "这台手机还没录入指纹或面部", value = "去系统设置", tint = c.faint, chevron = true) {
                runCatching { AppGraph.context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
        }
        if (se.hasBiometricCredential) add(UmbraSettingRow("忘掉已存的主密码", tint = c.danger) {
            VaultSession.forgetPassword(); router.showToast("已清掉，下次要用主密码解锁")
        })
        add(UmbraSettingRow("立即上锁", tint = c.orange) {
            VaultStore.lock()
            // 主动上锁这一档不自动刷 —— 刚锁完就弹识别面板等于没锁。
            VaultSession.markManualLock()
            router.back()
            router.showToast("已重新上锁")
        })
    }

    UmbraScreen(
        title = "保险箱设置", onBack = { router.back() }, refreshing = refreshing,
        onRefresh = {
            AppGraph.scope.launch {
                refreshing = true
                VaultStore.syncNow()
                VaultStore.fetchTrash()?.let { genericTrash = it.items.size }
                refreshing = false
            }
        },
    ) {
        settingSection("lock", "锁定", lockRows, footer = VaultLockNote.SETTINGS_FOOTER)
        settingSection("org", "整理", listOf(
            UmbraSettingRow("分组", value = "${st.types.size} 个", chevron = true) { router.go(Route.VaultGroups) },
            UmbraSettingRow("身份库", value = "${st.vaults.size} 个", chevron = true) { router.go(Route.VaultProfiles) },
            UmbraSettingRow("回收站", value = if (trashN > 0) "$trashN 项" else "没有", chevron = true) { router.go(Route.VaultTrash) },
        ))
        settingSection("pwd", "主密码与恢复", listOf(
            UmbraSettingRow("修改主密码", sub = "要在电脑上做", chevron = true) { router.go(Route.VaultPwd) },
            UmbraSettingRow("查看 Secret Key", chevron = true) { router.go(Route.VaultKey) },
        ), footer = "主密码不保存、不上传，忘记无法找回。")
        settingSection("io", "导入导出", listOf(
            UmbraSettingRow("从浏览器导入", sub = "Chrome / Safari 的密码 CSV", chevron = true) { router.go(Route.VaultImport) },
        ), footer = "导出的是明文，只在电脑上做。")
        settingSection("fill", "系统填充", listOf(
            UmbraSettingRow("密码自动填充", sub = "在别的 App 和浏览器里填保险箱的账号密码", chevron = true) { router.go(Route.VaultAutofill) },
        ), footer = "真的填充由系统在别的 App 里唤起；这里说明怎么打开它。")
        settingSection("sync", "同步与本机数据", listOf(
            UmbraSettingRow(if (st.loading) "同步中…" else "立即同步", value = if (st.pendingPush) "有改动待推" else null, tint = c.orange) {
                AppGraph.scope.launch {
                    VaultStore.syncNow()
                    val now = VaultStore.s
                    router.showToast(if (now.syncProblem.isNotEmpty()) "没推上去，原因写在保险箱首页" else if (now.pendingPush) "还是没推上去，等联网" else "已同步")
                }
            },
            UmbraSettingRow("忘掉这台设备上的本地数据", sub = "清掉密文缓存与 Secret Key，下次要重新输一遍", tint = c.danger) {
                router.confirm(UmbraAlert(
                    title = "忘掉本机数据？",
                    message = "清掉本机的密文缓存和 Secret Key。云端的数据不受影响，但这台设备之后要重新输主密码和 Secret Key，而且断网时打不开保险箱。",
                    confirmLabel = "忘掉", destructive = true,
                ) {
                    VaultStore.forgetLocalData()
                    VaultSession.forgetPassword()
                    router.back()
                    router.showToast("已清掉本机数据")
                })
            },
        ), footer = if (st.offline) "当前离线，用的是本机那份密文缓存。缓存是加密的，没有主密码谁也读不懂。"
        else "服务端只存密文，解不开也读不懂。本机也留一份同样的密文，断网时照样能开。")
    }
}

/**
 * 开启生物识别时的主密码确认面板：**先验主密码**（证明是保险箱主人），**再验生物识别**（证明人在跟前），两关都过才写入。
 * iOS 是页面上的浮层；Android 放进底部面板（Router 的确认弹窗里放不下输入框）。
 */
private fun presentPasswordConfirm() {
    AppGraph.router.present(UmbraSheet("vault-bio-confirm") { dismiss ->
        val c = UmbraTheme.colors
        val name = VaultSession.biometryName
        var pwd by remember { mutableStateOf("") }
        var err by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        VaultFrame {
            Column(Modifier.fillMaxWidth().padding(horizontal = 17.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text("先验一次主密码", color = c.text, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
                Text("确认之后会立刻验一次 $name，两关都过才把主密码存进这台设备的安全硬件。", color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp))
                VaultInput(pwd, { pwd = it }, placeholder = "主密码", password = true, mono = true, center = true, height = 48.dp, onCard = false, error = err.isNotEmpty())
                if (err.isNotEmpty()) Text(err, color = c.dangerText, style = TextStyle(fontSize = 12.5.sp, lineHeight = 19.sp), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    UmbraButton("取消", dismiss, Modifier.weight(1f), kind = UmbraButtonKind.Secondary, height = 44.dp)
                    UmbraButton(if (busy) "验证中…" else "继续", {
                        val act = VaultHostActivity.current ?: return@UmbraButton
                        busy = true; err = ""
                        VaultSession.enableBiometry(act, pwd) { e ->
                            busy = false
                            if (e != null) err = e else { dismiss(); AppGraph.router.showToast("$name 解锁已开启") }
                        }
                    }, Modifier.weight(1f), enabled = !busy && pwd.isNotEmpty(), height = 44.dp)
                }
            }
        }
    })
}

// MARK: - 自动填充说明页

/**
 * iOS 这一页是「系统填充面板长什么样」的形态演示 + 一颗「去系统设置启用」。Android 的自动填充服务要用户在
 * 系统设置里选成「自动填充服务」才生效：这一页说清楚它做什么、显示当前是不是已经选了 Umbra，并给一颗直达系统设置的按钮
 * （`Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE`）。
 */
@Composable
internal fun VaultAutofillContent() {
    val ctx = LocalContext.current
    val c = UmbraTheme.colors
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    // 从系统设置回来时重查一次。
    LifecycleResumeEffect(Unit) {
        enabled = runCatching { ctx.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() }.getOrNull()
        onPauseOrDispose { }
    }
    UmbraScreen(title = "自动填充", onBack = { AppGraph.router.back() }) {
        item("status") {
            VaultCardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(Modifier.size(34.dp).clip(RoundedCornerShape(UmbraDimens.radiusControl)).background(if (enabled == true) c.successSoft else c.chip),
                        contentAlignment = Alignment.Center) {
                        UmbraIcon(if (enabled == true) UmbraIconPaths.shieldCheck else UmbraIconPaths.key, if (enabled == true) c.success else c.muted, size = 17.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(when (enabled) { null -> "启用状态查询中…"; true -> "系统里已选 Umbra 作为自动填充服务"; false -> "还没在系统里选 Umbra" },
                        color = c.text, style = UmbraType.rowTitle)
                }
            }
        }
        item("btn") {
            UmbraButton(if (enabled == true) "去系统设置看看" else "去系统设置启用", {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:" + ctx.packageName)))
                }.onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
            }, Modifier.fillMaxWidth(), height = 52.dp, external = true)
        }
        settingSection("how", "它怎么工作", listOf(
            UmbraSettingRow("1. 在别的 App 或浏览器里点账号 / 密码框", sub = "输入框下方会出现 Umbra 的建议"),
            UmbraSettingRow("2. 同一网站 / 同一 App 的账号排在最前", sub = "按域名标签边界比，不按子串（防钓鱼）"),
            UmbraSettingRow("3. 保险箱锁着时先解锁", sub = "主密码或已开启的生物识别；解开后直接填进去"),
        ), footer = "填充只读这台手机上的保险箱，不联网取密码；没登录 Umbra 账号也能填。", footerTone = null)
        item("tail") { VaultNote("Android 没有 iOS 那种「预先登记账号、键盘上直接建议」的系统身份库，每次由 Umbra 当场按网站 / App 匹配。") }
    }
}

