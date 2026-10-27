// 保险箱 · 记录详情（iOS VaultViews.swift:1100 `UmbraVaultRecordView`）与画布节点卡（VaultNodeViews.swift `VaultNodeCard`）。
// 画布版（fmt == 2）以节点为准，按 y → x 排成一列卡；旧记录按控件显示（迁移由 PC 做）。
package com.geek.umbra.feature.vault

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraEmptyState
import com.geek.umbra.designsystem.UmbraFieldLabel
import com.geek.umbra.designsystem.UmbraFixedColors
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraProgressBar
import com.geek.umbra.designsystem.UmbraRowDivider
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraSearchField
import com.geek.umbra.designsystem.UmbraStripAction
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTopBarIcon
import com.geek.umbra.designsystem.UmbraTopBarText
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.navigation.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 节点种类元数据。 */
object VaultNodeKind {
    fun name(kind: String): String = when (kind) {
        "text" -> "文本"; "acct" -> "账号"; "table" -> "表格"; "img" -> "图片"; "file" -> "附件"; else -> kind
    }

    fun icon(kind: String): String = when (kind) {
        "text" -> UmbraIconPaths.messageText; "acct" -> UmbraIconPaths.user; "table" -> UmbraIconPaths.columns
        "img" -> UmbraIconPaths.image; "file" -> UmbraIconPaths.paperclip; else -> UmbraIconPaths.layoutGrid
    }

    /** 卡头的标题：节点自己的标题，空了用种类名。 */
    fun title(n: VNode): String = n.title.ifEmpty { name(n.kind) }

    /** 附件大小（1 KB = 1000，同访达 / iOS ByteCountFormatter .file）。 */
    fun sizeText(bytes: Double): String {
        if (!bytes.isFinite() || bytes < 0) return ""
        val n = VNum.long(bytes) ?: return ""
        return when {
            n < 1000 -> "$n 字节"
            n < 1_000_000 -> "%.0f KB".format(n / 1000.0)
            n < 1_000_000_000 -> "%.1f MB".format(n / 1_000_000.0)
            else -> "%.2f GB".format(n / 1_000_000_000.0)
        }
    }
}

/** 控件类型元数据（旧记录）。与电脑端一致：account / secret / field / text / images / files。 */
object VaultBlockKind {
    val all = listOf("account" to "账号", "secret" to "密文", "field" to "字段", "text" to "文本", "images" to "图片", "files" to "文件")
    fun name(type: String) = all.firstOrNull { it.first == type }?.second ?: type
    fun icon(type: String) = when (type) {
        "account" -> UmbraIconPaths.user; "secret" -> UmbraIconPaths.key; "field" -> UmbraIconPaths.textLines
        "text" -> UmbraIconPaths.messageText; "images" -> UmbraIconPaths.image; else -> UmbraIconPaths.file
    }

    /** 新建一个空控件。字段形状必须和电脑端一致，否则两端读同一条记录会缺字段。 */
    fun make(type: String): VBlock {
        val data = when (type) {
            "account" -> mapOf("username" to js(""), "password" to js(""), "url" to js(""), "otp" to jb(false))
            "images", "files" -> mapOf("atts" to ja(emptyList()))
            else -> mapOf("value" to js(""))
        }
        return VBlock("b" + java.util.UUID.randomUUID().toString().take(8).lowercase(), type, name(type), data)
    }
}

/** 引用指向的节点现在叫什么：本记录查自己，别的记录查当前保险箱。 */
internal fun mentionResolver(item: VItem, items: List<VItem>): MentionResolve = { itemId, nodeId ->
    val src = if (itemId == item.id) item else items.firstOrNull { it.id == itemId }
    src?.nodes?.firstOrNull { it.id == nodeId }?.let { VaultNodeKind.title(it) }
}

@Composable
internal fun VaultRecordContent(id: String, initialFind: String) {
    val st by VaultStore.state.collectAsState()
    if (st.tree != null) { VaultTreeNodeContent(id, initialFind); return }
    val router = AppGraph.router
    val c = UmbraTheme.colors
    val item = st.items.firstOrNull { it.id == id }
    var findOpen by rememberSaveable { mutableStateOf(false) }
    var findQuery by rememberSaveable { mutableStateOf("") }
    var findCurrent by rememberSaveable { mutableStateOf<String?>(null) }
    var findSeeded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val resolve = item?.let { mentionResolver(it, st.items) }
    val hits = if (findOpen && item != null && item.isCanvas) VaultFindRules.hits(item.sortedNodes, findQuery, resolve!!) else emptyList()

    // 带词进来：开查找条、填词，只做一回（从编辑页返回时不再把人拽回第一个命中）。
    LaunchedEffect(Unit) {
        if (findSeeded) return@LaunchedEffect
        findSeeded = true
        if (item != null && item.isCanvas && VaultFindRules.norm(initialFind).isNotEmpty()) { findOpen = true; findQuery = initialFind.trim() }
    }
    // 词一改就从第一个命中重新数。
    LaunchedEffect(findQuery) { findCurrent = hits.firstOrNull() }
    // 记录被同步改过：命中列表变了，当前那张还在就跟着它。
    LaunchedEffect(hits) {
        val i = VaultFindRules.reindex(findCurrent, hits)
        findCurrent = if (i >= 0) hits[i] else null
    }
    // 滚到当前命中（晚一拍：刚推入时卡片还没排好版）。
    LaunchedEffect(findCurrent) {
        val cur = findCurrent ?: return@LaunchedEffect
        val nodes = item?.sortedNodes ?: return@LaunchedEffect
        val idx = nodes.indexOfFirst { it.id == cur }
        if (idx >= 0) { delay(120); listState.animateScrollToItem(idx + 1) }
    }
    fun closeFind() { findOpen = false; findQuery = ""; findCurrent = null }

    UmbraScreen(
        title = item?.title ?: "",
        onBack = { router.back() },
        listState = listState,
        actions = {
            if (item != null) {
                UmbraTopBarText("编辑", { router.go(Route.VaultEdit(item.id)) }, strong = true)
                // 只有画布版有查找 —— 旧记录没有节点，开了也永远是「没有找到」。
                if (item.isCanvas) UmbraTopBarIcon(
                    UmbraIconPaths.search, stringResource(if (findOpen) R.string.vault_find_close else R.string.vault_find_open),
                    { if (findOpen) closeFind() else findOpen = true }, tint = if (findOpen) c.orange else c.faint,
                )
                UmbraTopBarIcon(
                    UmbraIconPaths.star, if (item.favorite == true) "取消收藏" else "收藏",
                    { AppGraph.scope.launch { VaultStore.toggleFav(item.id) } }, tint = if (item.favorite == true) c.orange else c.faint,
                )
            }
        },
        topContent = if (findOpen && item != null && item.isCanvas) ({
            FindBar(item, findQuery, { findQuery = it }, hits, findCurrent, onStep = { dir ->
                val i = VaultFindRules.step(hits.indexOf(findCurrent), hits.size, dir)
                if (i >= 0) findCurrent = hits[i]
            }, onClose = ::closeFind)
        }) else null,
    ) {
        if (item == null) {
            item("missing") {
                UmbraEmptyState(UmbraIconPaths.lockKeyhole, "这条记录不在了", hint = "可能是在别的设备上删掉了。", action = "回到保险箱", onAction = { router.back() })
            }
            return@UmbraScreen
        }
        item("head") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 画布版没有「第一个控件的类型」可念，胶囊不画。
                if (!item.isCanvas) {
                    Text(VaultBlockKind.name(item.blocks.firstOrNull()?.type ?: "field"), color = c.muted,
                        style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.chip).padding(horizontal = 9.dp, vertical = 2.dp))
                    Spacer(Modifier.width(8.dp))
                }
                val raw = item.title.ifEmpty { VaultRecordCopy.TITLE_PLACEHOLDER }
                val q = VaultFindRules.norm(findQuery)
                // 标题命中也上浅橙底，但**不计进「n / 共 m」**（标题本来就在页顶看得见）。
                Text(
                    if (findOpen && item.isCanvas && item.title.isNotEmpty() && q.isNotEmpty()) markedText(raw, VaultFindMark(q, false)) else AnnotatedString(raw),
                    color = if (item.title.isEmpty()) c.faint else c.text,
                    style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (item.isCanvas) {
            if (item.nodes.isEmpty()) item("emptyCanvas") { VaultNote("这条记录的画布还是空的，在电脑上加节点。", size = 13f) }
            for (n in item.sortedNodes) item("n-${n.id}") {
                val mark = if (n.id in hits) VaultFindMark(VaultFindRules.norm(findQuery), n.id == findCurrent) else null
                VaultNodeCard(n, item, st.items, mark)
            }
        } else {
            for (b in item.blocks) item("b-${b.id}") { BlockCard(b) }
        }
        item("info") {
            Column {
                UmbraFieldLabel("记录信息", Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
                VaultRowsCard(rows = listOf(
                    { InfoRow("创建", fmtTime(item.createdAt)) },
                    { InfoRow("更新", fmtTime(item.updatedAt)) },
                ))
            }
        }
        item("tail") {
            // ⚠️ 这句是**指路文案**，删除口子搬家时必须跟着改（今天在「编辑」页底部）。
            VaultNote("敏感值显示 8 秒后会自动重新遮盖；复制出来的内容 60 秒后自动清空剪贴板。删除在「编辑」页底部。")
        }
    }
}

private fun fmtTime(ms: Double): String =
    SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(Date(ms.toLong()))

@Composable
private fun InfoRow(label: String, value: String) {
    val c = UmbraTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = UmbraDimens.rowMinH).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.text, style = TextStyle(fontSize = 15.sp), modifier = Modifier.weight(1f))
        Text(value, color = c.muted, style = TextStyle(fontSize = 14.sp))
    }
}

/** 查找条：输入框 · 计数格 · 上一个 · 下一个 · 收起（设计侧 243：条高 52、输入框最小 120）。 */
@Composable
private fun FindBar(item: VItem, query: String, onQuery: (String) -> Unit, hits: List<String>, current: String?, onStep: (Int) -> Unit, onClose: () -> Unit) {
    val c = UmbraTheme.colors
    val at = hits.indexOf(current)
    val outcome = VaultFindRules.outcome(hits.size, item.title, item.tags, query)
    Column {
        Row(Modifier.fillMaxWidth().height(52.dp).background(c.bg).padding(horizontal = UmbraDimens.pagePadX), verticalAlignment = Alignment.CenterVertically) {
            UmbraSearchField(query, onQuery, Modifier.weight(1f).widthIn(min = 120.dp), placeholder = stringResource(R.string.vault_find_placeholder))
            val counter: Pair<String, androidx.compose.ui.graphics.Color>? = when (outcome) {
                VaultFindOutcome.Idle -> null
                VaultFindOutcome.Nodes -> VaultFindRules.counter(at, hits.size)?.let { stringResource(R.string.vault_find_count, it.first, it.second) to c.muted }
                VaultFindOutcome.OnlyName -> stringResource(R.string.vault_find_onlyName) to c.muted
                VaultFindOutcome.OnlyTags -> stringResource(R.string.vault_find_onlyTags) to c.muted
                VaultFindOutcome.None -> stringResource(R.string.vault_find_none) to c.dangerText
            }
            if (counter != null) {
                val a11y = if (outcome == VaultFindOutcome.Nodes) VaultFindRules.counter(at, hits.size)?.let { stringResource(R.string.vault_find_countA11y, it.first, it.second) } else null
                Text(counter.first, color = counter.second, style = TextStyle(fontSize = 13.sp), maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp).semantics { if (a11y != null) contentDescription = a11y })
            }
            for ((icon, label, dir) in listOf(Triple(UmbraIconPaths.chevronUp, stringResource(R.string.vault_find_prev), -1), Triple(UmbraIconPaths.chevronDown, stringResource(R.string.vault_find_next), 1))) {
                Box(Modifier.size(UmbraDimens.tapMin).clickable(enabled = hits.isNotEmpty(), role = Role.Button) { onStep(dir) }, contentAlignment = Alignment.Center) {
                    UmbraIcon(icon, if (hits.isNotEmpty()) c.muted else c.faint, size = 18.dp, strokeWidth = 2f, contentDescription = label)
                }
            }
            UmbraStripAction(stringResource(R.string.vault_find_close), c.orangeText, onClose)
        }
        UmbraRowDivider()
    }
}

/** 旧记录的一个控件卡。 */
@Composable
private fun BlockCard(b: VBlock) {
    val c = UmbraTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UmbraIcon(VaultBlockKind.icon(b.type), c.faint, size = 14.dp)
            Spacer(Modifier.width(7.dp))
            UmbraFieldLabel(b.label ?: VaultBlockKind.name(b.type))
        }
        val rows: List<@Composable () -> Unit> = when (b.type) {
            "account" -> buildList {
                add { VaultFieldRow("账号", b.data["username"].vString, mono = true) }
                add { VaultFieldRow("密码", b.data["password"].vString, secret = true, showStrength = true) }
                val url = b.data["url"].vString
                if (url.isNotEmpty()) add { VaultFieldRow("网址", url, mono = true) }
                add { TwoFactorRow(b.data["otp"].vBool) }
            }
            "secret" -> listOf { VaultFieldRow(b.label ?: "密文", b.data["value"].vString, secret = true, showStrength = true) }
            "field" -> listOf { VaultFieldRow(b.label ?: "字段", b.data["value"].vString) }
            "text" -> listOf { VaultFieldRow(b.label ?: "文本", b.data["value"].vString, multiline = true) }
            "images", "files" -> {
                val ids = b.data["atts"].vStrings
                if (ids.isEmpty()) listOf { VaultFieldRow(b.label ?: "附件", "") }
                else ids.map { aid -> @Composable { VaultFieldRow(if (b.type == "images") "图片" else "文件", VaultStore.attName(aid)) } }
            }
            else -> listOf { VaultFieldRow(b.label ?: b.type, "") }
        }
        VaultRowsCard(rows = rows)
    }
}

/** 两步验证：旧数据模型里只有一个布尔标记，没有密钥，所以只能显示状态 —— 不编一个跳动的假数字。 */
@Composable
private fun TwoFactorRow(on: Boolean) {
    val c = UmbraTheme.colors
    val ink = if (on) c.success else c.muted
    Column(Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(horizontal = 13.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        UmbraFieldLabel("两步验证")
        Row(verticalAlignment = Alignment.CenterVertically) {
            UmbraIcon(if (on) UmbraIconPaths.shieldCheck else UmbraIconPaths.shield, ink, size = 13.dp, strokeWidth = 2f)
            Spacer(Modifier.width(5.dp))
            Text(if (on) "已启用两步验证 (2FA)" else "未启用", color = ink, style = TextStyle(fontSize = 15.sp))
        }
        if (on) Text("验证码要在存了密钥的那台设备上看 —— 这条记录里只存了「已启用」这个标记。", color = c.muted, style = TextStyle(fontSize = 11.5.sp, lineHeight = 17.sp))
    }
}

// MARK: - 节点卡（详情页）

/** 卡头（图标 + 标题）+ 一张圆角卡。命中的卡换橙描边，当前那张更粗更实。 */
@Composable
internal fun NodeShell(node: VNode, mark: VaultFindMark?, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val c = UmbraTheme.colors
    val stroke = if (mark == null) c.border else if (mark.current) c.orange else c.orange.copy(alpha = 0.5f)
    val width: Dp = if (mark == null) UmbraDimens.borderW else if (mark.current) 2.dp else 1.5.dp
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UmbraIcon(VaultNodeKind.icon(node.kind), c.faint, size = 14.dp)
            Spacer(Modifier.width(7.dp))
            Text(markedText(VaultNodeKind.title(node), mark), color = c.muted, style = UmbraType.fieldLabel, modifier = Modifier.weight(1f))
            trailing()
        }
        val shape = RoundedCornerShape(UmbraDimens.radiusCard)
        Box(Modifier.fillMaxWidth().clip(shape).background(c.card).border(width, stroke, shape)) { content() }
    }
}

@Composable
private fun NoteRow(text: String) {
    Text(text, color = UmbraTheme.colors.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp), modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp))
}

@Composable
private fun Rows(rows: List<@Composable () -> Unit>) {
    Column { rows.forEachIndexed { i, r -> if (i > 0) UmbraRowDivider(); r() } }
}

/** 详情页的一张节点卡。只往**可搜字段**的那几行传 mark —— 密码、验证码、遮盖正文、密文列一律不拿它。 */
@Composable
internal fun VaultNodeCard(node: VNode, item: VItem, items: List<VItem>, mark: VaultFindMark?) {
    val c = UmbraTheme.colors
    val resolve = mentionResolver(item, items)
    fun plain(s: String) = VaultMentionRules.plainText(s, resolve)
    val maskedCols = (node.set["mask"].vArray ?: emptyList()).mapNotNull { it.vNumber }.mapNotNull { VNum.int(it) }.toSet()
    var tableMasked by rememberSaveable(node.id) { mutableStateOf(node.flag("masked", true)) }
    NodeShell(node, mark, trailing = {
        if (node.kind == "table" && maskedCols.isNotEmpty()) {
            Box(Modifier.size(UmbraDimens.tapMin).clickable(role = Role.Button) { tableMasked = !tableMasked }, contentAlignment = Alignment.Center) {
                UmbraIcon(if (tableMasked) UmbraIconPaths.eye else UmbraIconPaths.eyeOff, c.muted, size = 18.dp, contentDescription = if (tableMasked) "显示密文列" else "隐藏密文列")
            }
        }
    }) {
        when (node.kind) {
            "text" -> {
                val body = plain(node.data["body"].vString)
                val fmt = node.set["fmt"].vString.ifEmpty { "text" }
                when {
                    // 密文：圆点 + 点按显示（8 秒自动盖回），复制走敏感入口。
                    node.flag("masked", false) -> VaultFieldRow("密文", body, secret = true, multiline = true)
                    // Markdown：手机上不做富文本渲染，按原文给（地基里没有共用的 Markdown 组件；聊天模块的渲染器不归这里用）。
                    else -> VaultFieldRow("正文", body, mono = fmt == "code" || fmt == "json", multiline = true, mark = mark)
                }
            }
            "acct" -> {
                val d = node.data
                val user = d["user"].vString; val pwd = d["pwd"].vString; val seed = d["seed"].vString
                val url = d["url"].vString; val note = d["note"].vString
                val rows = buildList<@Composable () -> Unit> {
                    if (node.flag("user", true) && user.isNotEmpty()) add { VaultFieldRow("账号", user, mono = true, mark = mark) }
                    // pwdMasked 假 = 这条记录的密码在 PC 上就是明文显示的；手机上照它。
                    if (node.flag("pwd", true) && pwd.isNotEmpty()) add { VaultFieldRow("密码", pwd, secret = node.flag("pwdMasked", true), mono = true, showStrength = node.flag("strength", false)) }
                    if (node.flag("totp", true) && seed.isNotEmpty()) add { TotpRow(seed) }
                    if (node.flag("url", true) && url.isNotEmpty()) add { VaultFieldRow("网址", url, mono = true, mark = mark) }
                    if (node.flag("note", false) && note.isNotEmpty()) add { VaultFieldRow("备注", plain(note), multiline = true, mark = mark) }
                }
                Rows(rows.ifEmpty { listOf<@Composable () -> Unit>({ VaultFieldRow("账号", "") }) })
            }
            "table" -> TableBody(node, maskedCols, tableMasked, mark, resolve)
            "img" -> ImgBody(node, item, mark)
            "file" -> {
                val list = node.data["items"].vArray ?: emptyList()
                if (list.isEmpty()) NoteRow("还没有附件。附件要在电脑上添加。")
                else Rows(list.map { v ->
                    @Composable {
                        val o = v.vObject ?: emptyMap()
                        val att = item.attachments.firstOrNull { it.id == o["attId"].vString }
                        val name = o["name"].vString.ifEmpty { att?.name ?: "文件" }
                        val size = if (node.flag("size", true)) VaultNodeKind.sizeText(att?.size ?: (o["size"].vNumber ?: 0.0)) else ""
                        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            UmbraIcon(UmbraIconPaths.file, c.faint, size = 18.dp, strokeWidth = 1.8f)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(markedText(name, mark), color = c.text, style = TextStyle(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (size.isEmpty()) "在电脑上管理" else "$size · 在电脑上管理", color = c.faint, style = TextStyle(fontSize = 12.sp))
                            }
                        }
                    }
                })
            }
            else -> NoteRow("这种节点要在电脑上查看。")
        }
    }
}

/** 表格：横向可滚的格子；点一格复制（cellCopy 开时）；密文列按眼睛遮。 */
@Composable
private fun TableBody(node: VNode, maskedCols: Set<Int>, tableMasked: Boolean, mark: VaultFindMark?, resolve: MentionResolve) {
    val c = UmbraTheme.colors
    val cols = node.data["cols"].vStrings
    val rows = (node.data["rows"].vArray ?: emptyList()).map { it.vStrings }
    val head = node.flag("head", true)
    val canCopy = node.flag("cellCopy", true)
    if (cols.isEmpty() && rows.isEmpty()) { NoteRow("空表格。"); return }
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        if (head && cols.isNotEmpty()) {
            Row {
                for (col in cols) Text(markedText(col, mark), color = c.muted, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), maxLines = 1,
                    modifier = Modifier.widthIn(min = 110.dp).padding(horizontal = 12.dp, vertical = 8.dp))
            }
            Box(Modifier.height(1.dp).widthIn(min = 110.dp * cols.size).background(c.borderSoft))
        }
        rows.forEachIndexed { ri, row ->
            if (ri > 0) Box(Modifier.height(1.dp).widthIn(min = 110.dp * maxOf(1, row.size)).background(c.borderSoft))
            Row {
                row.forEachIndexed { ci, raw ->
                    val masked = ci in maskedCols
                    val value = VaultMentionRules.plainText(raw, resolve)
                    val hidden = masked && tableMasked && value.isNotEmpty()
                    val text: AnnotatedString = when {
                        hidden -> AnnotatedString("•".repeat(VaultAudit.graphemeCount(value).coerceIn(4, 12)))
                        value.isEmpty() -> AnnotatedString("—")
                        // ⚠️ 密文列哪怕点了眼睛显出来也不标：高亮会说出「这个词就在密文里」。
                        mark != null && !masked && VaultFindRules.ranges(value, mark.query).isNotEmpty() -> markedText(value, mark)
                        else -> mentionText(raw, resolve)
                    }
                    Box(
                        Modifier.widthIn(min = 110.dp).heightIn(min = UmbraDimens.tapMin)
                            .clickable(enabled = canCopy && value.isNotEmpty()) {
                                VaultClipboard.copySensitive(value); AppGraph.router.showToast("已复制 · 60 秒后自动清除")
                            }
                            .padding(horizontal = 12.dp)
                            .semantics { contentDescription = if (hidden) "已遮盖的格子" else value },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(text, color = if (value.isEmpty()) c.faint else c.text, maxLines = 2,
                            style = TextStyle(fontSize = 14.sp, fontFamily = if (masked) FontFamily.Monospace else FontFamily.Default))
                    }
                }
            }
        }
    }
}

/** 带引用的一格：引用画成橙色「@现名」，缺失的画成灰色中划线（同 PC 查看态）。只认得出、不能点着跳。 */
@Composable
private fun mentionText(raw: String, resolve: MentionResolve): AnnotatedString {
    val c = UmbraTheme.colors
    return buildAnnotatedString {
        for (seg in VaultMentionRules.parse(raw)) when (seg) {
            is VaultMentionRules.Seg.Text -> append(seg.value)
            is VaultMentionRules.Seg.Mention -> {
                val (text, missing) = VaultMentionRules.label(seg.itemId, seg.nodeId, seg.fallback, resolve)
                val st = if (missing) SpanStyle(color = c.faint, textDecoration = TextDecoration.LineThrough) else SpanStyle(color = c.orangeText)
                pushStyle(st); append(text); pop()
            }
        }
    }
}

/** 两步验证码那一行：每秒重算一次，倒计时条跟着走；复制走敏感入口。 */
@Composable
private fun TotpRow(seed: String) {
    val c = UmbraTheme.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000 - System.currentTimeMillis() % 1000); now = System.currentTimeMillis() } }
    var copied by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { if (copied) { delay(1500); copied = false } }
    val code = VaultTotp.code(seed, now)
    val left = VaultTotp.secondsLeft(seed, now)
    val period = VaultTotp.period(seed)
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            UmbraFieldLabel("验证码")
            if (code != null) {
                Text(VaultTotp.grouped(code), color = c.text, style = TextStyle(fontSize = 22.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UmbraProgressBar(left.toFloat() / maxOf(period, 1), if (left <= 5) c.warning else c.orange, Modifier.width(72.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("$left 秒后换", color = if (left <= 5) c.warningText else c.faint, style = TextStyle(fontSize = 11.5.sp, fontWeight = UmbraType.weight560))
                }
            } else {
                // 密钥解不出来：说出来，不显示一个假数字。
                Text("密钥格式不对，算不出验证码", color = c.muted, style = TextStyle(fontSize = 13.sp))
            }
        }
        if (code != null) {
            Column(
                Modifier.defaultMinSize(UmbraDimens.tapMin, UmbraDimens.tapMin).clickable(role = Role.Button) { VaultClipboard.copySensitive(code); copied = true; tick++ },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                val tint = if (copied) c.success else c.muted
                UmbraIcon(if (copied) UmbraIconPaths.check else UmbraIconPaths.copy, tint, size = 19.dp, contentDescription = if (copied) "已复制" else "复制验证码")
                if (copied) Text("已复制", color = tint, style = TextStyle(fontSize = 9.5.sp, fontWeight = UmbraType.weight560))
            }
        }
    }
}

/** 图片节点：横排缩略图。 */
@Composable
private fun ImgBody(node: VNode, item: VItem, mark: VaultFindMark?) {
    val list = (node.data["items"].vArray ?: emptyList()).mapNotNull { v ->
        val o = v.vObject ?: return@mapNotNull null
        val attId = o["attId"].vString
        val att = item.attachments.firstOrNull { it.id == attId }
        Triple(attId, o["name"].vString.ifEmpty { att?.name ?: "图片" }, att)
    }
    if (list.isEmpty()) { NoteRow("还没有图片。图片要在电脑上添加。"); return }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(13.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((attId, name, att) in list) ImageTile(attId, name, att, node.flag("names", true), mark)
    }
}

/** 一张缩略图。字节来源三档：快照里内联的旧 base64 → 云端 fileId（拉密文、VaultKey 解）→ 都没有就只有名字。 */
@Composable
private fun ImageTile(attId: String, name: String, att: VAtt?, showName: Boolean, mark: VaultFindMark?) {
    val c = UmbraTheme.colors
    var image by remember(attId) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(attId) { mutableStateOf(false) }
    var failed by remember(attId) { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    val onlyOnPC = att?.fileId.isNullOrEmpty() && VaultStore.imageData(attId) == null
    LaunchedEffect(attId) {
        VaultStore.imageData(attId)?.let { d -> BitmapFactory.decodeByteArray(d, 0, d.size)?.let { image = it.asImageBitmap(); return@LaunchedEffect } }
        val fid = att?.fileId?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        loading = true
        val d = VaultStore.attachmentBytes(fid)
        val bmp = d?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        loading = false
        if (bmp != null) image = bmp.asImageBitmap() else failed = true
    }
    val shape = RoundedCornerShape(UmbraDimens.radiusControl)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(112.dp, 84.dp).clip(shape).background(c.chip).clickable(enabled = image != null) { preview = true }.semantics { contentDescription = name },
            contentAlignment = Alignment.Center) {
            val img = image
            when {
                img != null -> Image(img, null, Modifier.size(112.dp, 84.dp), contentScale = ContentScale.Crop)
                loading -> CircularProgressIndicator(Modifier.size(20.dp), color = c.muted, strokeWidth = 2.dp)
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    UmbraIcon(UmbraIconPaths.image, c.faint, size = 20.dp, strokeWidth = 1.7f)
                    Text(if (onlyOnPC) "只在电脑上" else if (failed) "暂时看不了" else "", color = c.faint, style = TextStyle(fontSize = 10.5.sp, fontWeight = UmbraType.weight560))
                }
            }
        }
        if (showName) Text(markedText(name, mark), color = c.muted, style = TextStyle(fontSize = 11.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(112.dp))
    }
    val img = image
    if (preview && img != null) ImagePreview(img, name) { preview = false }
}

/**
 * 全屏看一张解开的图。不走通用看图器：它按 URL 取图，而这张图只在内存里 —— 落成临时文件给它读，
 * 就是把保险箱里的明文写到盘上。黑底、双指缩放、点空白关。Dialog 是单独的窗口，FLAG_SECURE 要另外加上。
 */
@Composable
private fun ImagePreview(img: ImageBitmap, name: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
        var scale by remember { mutableFloatStateOf(1f) }
        Box(Modifier.fillMaxSize().background(UmbraFixedColors.viewerBg).clickable { if (scale <= 1f) onClose() }) {
            Image(img, null, Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 3f) }
            }.graphicsLayer { scaleX = scale; scaleY = scale }, contentScale = ContentScale.Fit)
            Row(Modifier.statusBarsPadding().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(UmbraDimens.tapMin).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    UmbraIcon(UmbraIconPaths.x, androidx.compose.ui.graphics.Color.White, size = 20.dp, strokeWidth = 2f, contentDescription = "关闭")
                }
                Text(name, color = UmbraFixedColors.onNavText, style = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace), maxLines = 1)
            }
            Text("双指缩放 · 点空白关闭", color = UmbraFixedColors.onNavText.copy(alpha = 0.6f), style = TextStyle(fontSize = 12.sp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
        }
    }
}
