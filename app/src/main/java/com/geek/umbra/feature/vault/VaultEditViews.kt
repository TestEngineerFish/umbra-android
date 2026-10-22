// 保险箱 · 记录编辑（iOS VaultViews.swift:1565 `UmbraVaultEditView` + VaultNodeViews.swift `VaultNodeEditor`）。
//
// ⒠ 自动保存：停手 900ms 落一笔，离开前把没落的那笔落掉；左上是返回箭头不是「取消」——
// 自动保存与「取消」不能共存（一颗撤不回任何东西的「取消」比没有它更糟）。
// 新建一律是画布记录（一个账号节点）；旧记录可增删控件、上下移、就地生成密码。
package com.geek.umbra.feature.vault

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraChoice
import com.geek.umbra.designsystem.UmbraChoiceItem
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraExtraIcons
import com.geek.umbra.designsystem.UmbraFieldLabel
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraSwitch
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTopBarText
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.navigation.UmbraAlert
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 编辑页的一份草稿（跟着这一页的组合走），离开时把没落的那笔落库。 */
private class EditDraft(val id: String?) {
    var loaded = false
    /**
     * 新建记录的 id **只取一次**：iOS 那边 `draftItem()` 对新建记录每次都 `"i" + UUID…` 现取，
     * 自动保存落第二笔时就会多出一条记录。这里记住第一次的 id，之后每笔都是同一条。
     */
    var newId: String = "i" + java.util.UUID.randomUUID().toString().replace("-", "").take(10).lowercase()
    var title by mutableStateOf("")
    var typeId by mutableStateOf("")
    var blocks by mutableStateOf<List<VBlock>>(emptyList())
    var canvas by mutableStateOf(false)
    var nodes by mutableStateOf<List<VNode>>(emptyList())
    var favorite by mutableStateOf(false)
    var sv by mutableStateOf(VaultSaveState.Saved)
    var savedAt by mutableStateOf<Long?>(null)
    var saveJob: Job? = null
}

/** PC `rid("n")` 同形：`n` + 18 位 hex。 */
private fun newNodeId() = "n" + java.util.UUID.randomUUID().toString().replace("-", "").take(18).lowercase()

@Composable
internal fun VaultEditContent(id: String?) {
    val st by VaultStore.state.collectAsState()
    val router = AppGraph.router
    val c = UmbraTheme.colors
    val d = remember(id) { EditDraft(id) }
    val existing = (id ?: d.newId.takeIf { d.savedAt != null })?.let { rid -> st.items.firstOrNull { it.id == rid } }

    if (!d.loaded) {
        d.loaded = true
        val it = id?.let { rid -> st.items.firstOrNull { it.id == rid } }
        if (it != null) {
            d.title = it.title; d.typeId = it.typeId; d.blocks = it.blocks; d.canvas = it.isCanvas; d.nodes = it.sortedNodes; d.favorite = it.favorite == true
        } else {
            // 新建一律是画布版：一个账号节点在 (40, 40)，默认尺寸 280×240；typeId / blocks 写空。
            d.canvas = true; d.typeId = ""; d.blocks = emptyList(); d.nodes = listOf(VNode.defaultAcct(newNodeId()))
        }
    }

    /** 屏上这一份拼成要落库的记录。**唯一拼的地方**。 */
    fun draftItem(): VItem {
        val base = (id ?: d.newId).let { rid -> VaultStore.s.items.firstOrNull { it.id == rid } }
            ?: VItem(id ?: d.newId, d.typeId, d.title, null, false, emptyList(), emptyList(), emptyList(), 0.0, 0.0, 0.0, false)
        var item = base.copy(title = d.title)
        if (d.canvas) {
            item = item.copy(typeId = "", blocks = emptyList(), favorite = d.favorite).withNodes(d.nodes)
            item = item.copy(extra = item.extra + ("fmt" to jn(2.0)) + (if (item.extra["edges"] == null) mapOf("edges" to ja(emptyList())) else emptyMap()))
        } else {
            item = item.copy(typeId = d.typeId, blocks = d.blocks)
        }
        return item
    }

    /** 真的写一笔。**唯一落库的地方** —— 定时器、「现在就存」、「重试」、返回四条路都走它。 */
    suspend fun flush() {
        d.saveJob?.cancel()
        d.sv = VaultSaveState.Saving
        VaultStore.saveItem(draftItem())
        d.sv = VaultSaveState.Saved
        d.savedAt = System.currentTimeMillis()
    }

    /** 改了一下。⚠️ **每一个改 title / typeId / blocks / nodes 的地方都要过这里** —— 漏一个，那一类改动就安静地不被保存。 */
    fun touchDraft() {
        VaultSession.touch()
        d.sv = VaultSaveState.Dirty
        d.saveJob?.cancel()
        d.saveJob = AppGraph.scope.launch { delay(VaultSaveSlot.IDLE_MS); flush() }
    }

    // ⚠️ 走之前把还没落的那一笔落掉。这一端的返回本身就是一次写入。
    OnLeave {
        if (VaultSaveSlot.needsFlushBeforeLeave(d.sv)) {
            d.saveJob?.cancel()
            val item = draftItem()
            AppGraph.scope.launch { VaultStore.saveItem(item) }
        }
    }

    UmbraScreen(
        title = if (id == null) "存一条新的" else "编辑记录",
        onBack = { router.back() },
        // 「保存」留着但降为次级（字重 400）：返回本身就是一次写入，这颗只是「现在就存」。
        actions = { UmbraTopBarText("保存", { AppGraph.scope.launch { flush() } }) },
    ) {
        item("title") {
            // 标题是**一行大标题，不是一个字段**：19/650、无边框、占位「未命名记录」。
            BasicTextField(
                d.title, { d.title = it; touchDraft() }, singleLine = true,
                textStyle = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = c.text), cursorBrush = SolidColor(c.orange),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box { if (d.title.isEmpty()) Text(VaultRecordCopy.TITLE_PLACEHOLDER, color = c.faint, style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold)); inner() }
                },
            )
        }
        item("slot") { AutosaveSlot(d.sv, d.savedAt) { AppGraph.scope.launch { flush() } } }
        if (d.canvas) {
            item("fav") {
                Row(Modifier.fillMaxWidth().heightIn(min = UmbraDimens.tapMin), verticalAlignment = Alignment.CenterVertically) {
                    Text("收藏", color = c.text, style = TextStyle(fontSize = 15.sp), modifier = Modifier.weight(1f))
                    UmbraSwitch(d.favorite, { d.favorite = it; touchDraft() })
                }
            }
            d.nodes.forEachIndexed { i, n ->
                item("node-${n.id}") {
                    NodeEditor(n) { updated -> d.nodes = d.nodes.toMutableList().also { l -> l[i] = updated }; touchDraft() }
                }
            }
            // 节点的增删、尺寸位置、关联线不在手机上做。
            item("canvasNote") { VaultNote("节点布局在电脑上调整。") }
        } else {
            item("group") {
                VaultRowsCard(rows = listOf {
                    Row(Modifier.fillMaxWidth().heightIn(min = UmbraDimens.rowMinH).clickable { pickGroup(d.typeId) { d.typeId = it; touchDraft() } }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("分组", color = c.text, style = TextStyle(fontSize = 15.sp), modifier = Modifier.weight(1f))
                        Text(st.types.firstOrNull { it.id == d.typeId }?.name ?: "未分组", color = c.muted, style = TextStyle(fontSize = 14.sp))
                        Spacer(Modifier.width(6.dp))
                        UmbraIcon(com.geek.umbra.designsystem.UmbraIconPaths.chevronRight, c.faint, size = 16.dp)
                    }
                })
            }
            d.blocks.forEachIndexed { i, b ->
                item("blk-${b.id}") {
                    BlockEditor(b, i, d.blocks.size,
                        onChange = { nb -> d.blocks = d.blocks.toMutableList().also { l -> l[i] = nb }; touchDraft() },
                        onMove = { dir -> val l = d.blocks.toMutableList(); val j = i + dir; if (j in l.indices) { val t = l[i]; l[i] = l[j]; l[j] = t; d.blocks = l; touchDraft() } },
                        onRemove = { d.blocks = d.blocks.toMutableList().also { l -> l.removeAt(i) }; touchDraft() })
                }
            }
            item("add") {
                val shape = RoundedCornerShape(11.dp)
                Box(Modifier.fillMaxWidth().height(48.dp).clip(shape).border(1.dp, c.border, shape).clickable {
                    UmbraChoice.present(
                        title = "添加控件",
                        items = VaultBlockKind.all.map { (t, n) ->
                        UmbraChoiceItem(n) { d.blocks = d.blocks + VaultBlockKind.make(t); touchDraft() }
                    },
                        subtitle = "图片与文件只能在电脑上添加，这里加了也是空的。",
                    )
                }, contentAlignment = Alignment.Center) {
                    Text("添加控件", color = c.orangeText, style = TextStyle(fontSize = 15.5.sp, fontWeight = UmbraType.weight560))
                }
            }
            item("attNote") { VaultNote("图片与文件附件要在电脑上添加 —— 手机端只做查看。") }
        }
        // 删除在**页底**（设计侧 214：「破坏性操作不占详情页底部」说的是详情页，不是编辑页）。
        if (existing != null) {
            item("delete") {
                val shape = RoundedCornerShape(11.dp)
                Box(Modifier.fillMaxWidth().height(48.dp).clip(shape).border(UmbraDimens.borderW, c.danger, shape).clickable {
                    router.confirm(UmbraAlert(
                        title = "删除「${existing.title}」？", message = VaultRecordCopy.DELETE_BODY,
                        confirmLabel = VaultRecordCopy.DELETE_LABEL, destructive = true,
                    ) {
                        d.sv = VaultSaveState.Saved   // 删了就别在离开时再落一笔把它写回来
                        AppGraph.scope.launch { VaultStore.deleteItem(existing.id) }
                        router.back()
                        router.showToast("已移入回收站 · 保留 30 天")
                    })
                }, contentAlignment = Alignment.Center) {
                    Text(VaultRecordCopy.DELETE_LABEL, color = c.dangerText, style = TextStyle(fontSize = 15.5.sp, fontWeight = UmbraType.weight560))
                }
            }
        }
    }
}

private fun pickGroup(cur: String, onPick: (String) -> Unit) {
    UmbraChoice.present(title = "分组", items = VaultStore.s.types.map { t -> UmbraChoiceItem(t.name, checked = t.id == cur) { onPick(t.id) } })
}

/** ⒠ 那一个槽：四态共用这一格；「现在就存」/「重试」只在真有未写入的改动（或没存上）时出现。 */
@Composable
private fun AutosaveSlot(sv: VaultSaveState, savedAt: Long?, onAction: () -> Unit) {
    val c = UmbraTheme.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // 「刚刚」要自己会老：每 30 秒重算一次相对时间。
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val slot = VaultSaveSlot.line(sv, savedAt, maxOf(now, savedAt ?: 0))
    Row(Modifier.fillMaxWidth().heightIn(min = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(slot.text, color = when (slot.tone) { VaultSlotTone.Fail -> c.dangerText; VaultSlotTone.Ok -> c.success; VaultSlotTone.Muted -> c.faint },
            style = TextStyle(fontSize = 11.5.sp))
        val act = slot.action
        if (act != null) {
            Spacer(Modifier.width(7.dp))
            Box(Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button, onClick = onAction), contentAlignment = Alignment.Center) {
                val shape = RoundedCornerShape(7.dp)
                Text(act.label, color = if (act.danger) c.dangerText else c.muted, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.border(UmbraDimens.borderW, if (act.danger) c.danger else c.border, shape).padding(horizontal = 9.dp, vertical = 3.dp))
            }
        }
    }
}

/** 一个可编辑的字段：标签 + 输入框（单行可带「生成」，多行最小高 88）。 */
@Composable
private fun EditField(label: String, value: String, mono: Boolean = false, secure: Boolean = false, multiline: Boolean = false, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp2)) {
        UmbraFieldLabel(label)
        // 密码用明文输入框：编辑时看不见自己在打什么最容易出错（真正要遮的是详情页的展示）。长按复制由外框改道走敏感剪贴板。
        if (multiline) VaultInput(value, onChange, mono = mono, onCard = false, singleLine = false, height = 88.dp)
        else VaultInput(value, onChange, mono = mono, onCard = false, height = 44.dp,
            trailing = if (secure) ({ VaultGenButton { onChange(PasswordGen.make()) } }) else null)
    }
}

/** 旧记录的一个控件块编辑器。卡头一颗「⋯」，三项与 PC 逐字同源（上移 / 下移 / 删除这个控件，后者不过二次确认）。 */
@Composable
private fun BlockEditor(b: VBlock, idx: Int, count: Int, onChange: (VBlock) -> Unit, onMove: (Int) -> Unit, onRemove: () -> Unit) {
    val c = UmbraTheme.colors
    var menu by remember { mutableStateOf(false) }
    fun str(k: String) = b.data[k].vString
    fun setStr(k: String, v: String) = onChange(b.copy(data = b.data + (k to js(v))))
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UmbraIcon(VaultBlockKind.icon(b.type), c.faint, size = 14.dp)
            Spacer(Modifier.width(7.dp))
            UmbraFieldLabel(b.label ?: VaultBlockKind.name(b.type), Modifier.weight(1f))
            Box {
                Box(Modifier.size(UmbraDimens.tapMin).clickable(role = Role.Button) { menu = true }, contentAlignment = Alignment.Center) {
                    UmbraIcon(UmbraExtraIcons.more, c.faint, size = 18.dp, contentDescription = "更多")
                }
                DropdownMenu(menu, { menu = false }, containerColor = c.card, modifier = Modifier.widthIn(min = 180.dp)) {
                    DropdownMenuItem({ Text(VaultRecordCopy.BLOCK_MENU[0], color = if (idx > 0) c.text else c.faint) }, { menu = false; onMove(-1) }, enabled = idx > 0)
                    DropdownMenuItem({ Text(VaultRecordCopy.BLOCK_MENU[1], color = if (idx < count - 1) c.text else c.faint) }, { menu = false; onMove(1) }, enabled = idx < count - 1)
                    DropdownMenuItem({ Text(VaultRecordCopy.BLOCK_MENU[2], color = c.dangerText) }, { menu = false; onRemove() })
                }
            }
        }
        VaultCardBox(padding = 13.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (b.type) {
                    "account" -> {
                        EditField("账号", str("username"), mono = true) { setStr("username", it) }
                        EditField("密码", str("password"), mono = true, secure = true) { setStr("password", it) }
                        EditField("网址", str("url"), mono = true) { setStr("url", it) }
                        Row(Modifier.fillMaxWidth().heightIn(min = UmbraDimens.tapMin), verticalAlignment = Alignment.CenterVertically) {
                            Text("含两步验证 (2FA)", color = c.text, style = TextStyle(fontSize = 15.sp), modifier = Modifier.weight(1f))
                            UmbraSwitch(b.data["otp"].vBool, { onChange(b.copy(data = b.data + ("otp" to jb(it)))) })
                        }
                    }
                    "images", "files" -> {
                        val n = b.data["atts"].vStrings.size
                        Text(if (n == 0) "还没有附件。附件要在电脑上添加。" else "有 $n 个附件，在详情页查看。附件要在电脑上增删。", color = c.muted, style = TextStyle(fontSize = 13.sp))
                    }
                    "text" -> EditField(b.label ?: "文本", str("value"), multiline = true) { setStr("value", it) }
                    else -> EditField(b.label ?: VaultBlockKind.name(b.type), str("value"), mono = b.type == "secret", secure = b.type == "secret") { setStr("value", it) }
                }
            }
        }
    }
}

/** 一个节点的输入：只改值（text 正文、acct 五个字段、table 单元格）；img / file 只说一句「在电脑上管理」。 */
@Composable
private fun NodeEditor(node: VNode, onChange: (VNode) -> Unit) {
    val c = UmbraTheme.colors
    fun str(k: String) = node.data[k].vString
    fun setStr(k: String, v: String) = onChange(node.copy(data = node.data + (k to js(v))))
    NodeShell(node, null) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (node.kind) {
                "text" -> {
                    val body = str("body")
                    val fmt = node.set["fmt"].vString
                    // 含 @ 引用的正文在手机上**只读**：编辑框里只能放原始标记，人一改就容易改坏。
                    if (VaultMentionRules.hasMention(body)) MentionLocked("正文", body)
                    else EditField("正文", body, mono = fmt == "code" || fmt == "json", multiline = true) { setStr("body", it) }
                }
                "acct" -> {
                    EditField("账号", str("user"), mono = true) { setStr("user", it) }
                    EditField("密码", str("pwd"), mono = true, secure = true) { setStr("pwd", it) }
                    EditField("两步验证密钥", str("seed"), mono = true) { setStr("seed", it) }
                    EditField("网址", str("url"), mono = true) { setStr("url", it) }
                    EditField("备注", str("note"), multiline = true) { setStr("note", it) }
                }
                "table" -> TableEditor(node, onChange)
                "img" -> Text("图片要在电脑上增删，这里只能看。", color = c.muted, style = TextStyle(fontSize = 13.sp))
                "file" -> Text("附件要在电脑上增删，这里只能看。", color = c.muted, style = TextStyle(fontSize = 13.sp))
                else -> Text("这种节点要在电脑上改。", color = c.muted, style = TextStyle(fontSize = 13.sp))
            }
        }
    }
}

@Composable
private fun MentionLocked(label: String, raw: String) {
    val c = UmbraTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp2)) {
        UmbraFieldLabel(label)
        Text(VaultMentionRules.plainTextOffline(raw), color = c.muted, style = TextStyle(fontSize = 15.sp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(UmbraDimens.radiusControl)).background(c.bg).padding(10.dp))
        Text("里面有 @ 引用，要在电脑上改。", color = c.faint, style = TextStyle(fontSize = 12.sp))
    }
}

/** 表格编辑：行按列数补齐；带 @ 引用的格子只读；行列增删在电脑上。 */
@Composable
private fun TableEditor(node: VNode, onChange: (VNode) -> Unit) {
    val c = UmbraTheme.colors
    val cols = node.data["cols"].vStrings
    if (cols.isEmpty()) { Text("空表格。行列要在电脑上加。", color = c.muted, style = TextStyle(fontSize = 13.sp)); return }
    val rows = (node.data["rows"].vArray ?: emptyList()).map { r -> r.vStrings.let { if (it.size < cols.size) it + List(cols.size - it.size) { "" } else it } }
    Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { for (col in cols) UmbraFieldLabel(col, Modifier.width(140.dp)) }
        rows.forEachIndexed { ri, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { ci, v ->
                    Box(Modifier.width(140.dp)) {
                        if (VaultMentionRules.hasMention(v)) {
                            Text(VaultMentionRules.plainTextOffline(v), color = c.muted, style = TextStyle(fontSize = 14.sp), maxLines = 1,
                                modifier = Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(UmbraDimens.radiusControl)).background(c.bg).padding(10.dp))
                        } else {
                            VaultInput(v, { nv ->
                                val rs = rows.map { it.toMutableList() }.toMutableList()
                                if (ri < rs.size && ci < rs[ri].size) {
                                    rs[ri][ci] = nv
                                    onChange(node.copy(data = node.data + ("rows" to ja(rs.map { r -> ja(r.map { s -> js(s) }) }))))
                                }
                            }, onCard = false, height = 40.dp, fontSize = 14f)
                        }
                    }
                }
            }
        }
        Text(if (rows.any { r -> r.any { VaultMentionRules.hasMention(it) } }) "加行 / 加列、列宽，以及带 @ 引用的格子，在电脑上改。" else "加行 / 加列、列宽在电脑上调。",
            color = c.faint, style = TextStyle(fontSize = 12.sp))
    }
}

