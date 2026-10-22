// 常用语的页面入口 —— 对应 iOS Features/Me/PhrasesView.swift（设计稿 me.phrases / phrase.edit）。
// 路由分发表只调这里的两个函数，签名不改。
package com.geek.umbra.feature.phrases

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraEmptyState
import com.geek.umbra.designsystem.UmbraFieldLabel
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraExtraIcons
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraRevealSwipeRow
import com.geek.umbra.designsystem.UmbraRowDivider
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraSpinningIcon
import com.geek.umbra.designsystem.UmbraSwipeAction
import com.geek.umbra.designsystem.UmbraTagPill
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTopBarIcon
import com.geek.umbra.designsystem.UmbraTopBarText
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 常用语列表：头部「N 条」+ 同步按钮（三态，未登录不画）；行 = 名称 + 关键词胶囊 + 首行预览 + 常驻复制钮；
 * 左滑「删除（确认）/ 编辑」；长按拖动排序；底部明文警示；下拉 = 真同步。iOS: PhrasesView.swift:192
 */
@Composable
fun PhrasesScreen() {
    val store = PhraseStore
    val router = AppGraph.router
    remember { store.ensureLoaded(); 0 }
    val items by store.items.collectAsState()
    val locked by AppGraph.guard.locked.collectAsState()
    val syncing by store.syncing.collectAsState()
    val note by store.syncNote.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { store.sync() }

    UmbraScreen(
        title = "常用语",
        onBack = { router.back() },
        actions = { UmbraTopBarIcon(UmbraIconPaths.plus, "新建", { router.go(Route.MePhraseEdit(null)) }, tint = UmbraTheme.colors.orange) },
        refreshing = refreshing,
        // 下拉是真同步，不是重读本地（本地数据改完界面自己就更新了）。
        onRefresh = if (items.isEmpty()) null else ({ scope.launch { refreshing = true; store.sync(); refreshing = false } }),
    ) {
        if (items.isEmpty()) {
            item("empty") {
                UmbraEmptyState(
                    UmbraIconPaths.messageText, "还没有常用语",
                    hint = "把经常要说的话存成一条，之后在任意端直接拿来用。",
                    action = "新建一条", onAction = { router.go(Route.MePhraseEdit(null)) },
                )
            }
            return@UmbraScreen
        }
        item("header") {
            val c = UmbraTheme.colors
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text("${items.size} 条", color = c.muted, style = TextStyle(fontSize = 13.sp))
                    // 失败信息不能只藏在按钮态里 —— 失败时补一行说明。
                    if (!locked && note.startsWith("同步失败")) Text(note, color = c.warningText, style = TextStyle(fontSize = 12.sp))
                }
                if (!locked) SyncButton(syncing, note)
            }
        }
        item("list") { ReorderableList(items) }
        item("footer") {
            val c = UmbraTheme.colors
            Column(verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp3)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(c.warningSoft).padding(horizontal = UmbraDimens.sp4, vertical = 11.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    UmbraIcon(UmbraIconPaths.alertTriangle, c.warningText, size = 15.dp, strokeWidth = 2f, modifier = Modifier.padding(top = 1.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("内容为明文存储，密钥类请放保险箱", color = c.warningText, style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560, lineHeight = 19.sp))
                }
                Text("点复制图标复制全文，点条目改内容。", color = c.muted, style = TextStyle(fontSize = 12.sp), modifier = Modifier.padding(horizontal = 2.dp))
            }
        }
    }
}

/** 同步按钮三态：同步中（橙、转圈）/ 刚刚同步 / 立即同步。 */
@Composable
private fun SyncButton(syncing: Boolean, note: String) {
    val c = UmbraTheme.colors
    Box(Modifier.heightIn(min = UmbraDimens.tapMin).clickable(enabled = !syncing, role = Role.Button) { PhraseStore.syncNow() }, contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(34.dp).clip(RoundedCornerShape(50)).background(if (syncing) c.orangeSoft else c.card)
                .border(UmbraDimens.borderW, if (syncing) c.orange else c.border, RoundedCornerShape(50)).padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (syncing) { UmbraSpinningIcon(UmbraIconPaths.spinnerArc, c.orangeText, 13.dp); Spacer(Modifier.width(6.dp)) }
            Text(
                if (syncing) "同步中…" else if (note == "刚刚同步") "刚刚同步" else "立即同步",
                color = if (syncing) c.orangeText else c.text, style = TextStyle(fontSize = 13.sp, fontWeight = UmbraType.weight560),
            )
        }
    }
}

/**
 * 长按拖动排序的列表（iOS List.onMove）。长按一行后上下拖，越过邻行一半就换位；松手按新顺序落地（只盖顺序戳）。
 * 列表很短（常用语几十条），整张放在一个分组卡里排，不走懒加载。
 */
@Composable
private fun ReorderableList(items: List<Phrase>) {
    val c = UmbraTheme.colors
    val order = remember { mutableStateListOf<String>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    // 不在拖的时候顺序跟着 store 走（同步落地、增删）；拖起来那一刻拷一份，拖动只改这份。
    val latest by rememberUpdatedState(items)
    val shown = if (dragging == null) items.map { it.id } else order.toList()
    val byId = items.associateBy { it.id }
    val shape = RoundedCornerShape(UmbraDimens.radiusCard)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.card).border(UmbraDimens.borderW, c.border, shape)) {
        shown.forEachIndexed { i, id ->
            val p = byId[id] ?: return@forEachIndexed
            if (i > 0) UmbraRowDivider()
            val me = dragging == id
            Box(
                Modifier.zIndex(if (me) 1f else 0f)
                    .offset { IntOffset(0, if (me) dragY.roundToInt() else 0) }
                    .then(if (me) Modifier.shadow(6.dp) else Modifier)
                    .onSizeChanged { heights[id] = it.height }
                    .pointerInput(id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { order.clear(); order.addAll(latest.map { it.id }); dragging = id; dragY = 0f },
                            onDragEnd = { dragging = null; dragY = 0f; PhraseStore.reorder(order.toList()) },
                            onDragCancel = { dragging = null; dragY = 0f },
                        ) { change, amount ->
                            change.consume()
                            dragY += amount.y
                            val idx = order.indexOf(id)
                            // 越过下一行 / 上一行的一半就换位，偏移按换过去的那一行高度回调
                            if (dragY > 0 && idx < order.size - 1) {
                                val h = heights[order[idx + 1]] ?: 0
                                if (dragY > h / 2f) { order.removeAt(idx); order.add(idx + 1, id); dragY -= h }
                            } else if (dragY < 0 && idx > 0) {
                                val h = heights[order[idx - 1]] ?: 0
                                if (-dragY > h / 2f) { order.removeAt(idx); order.add(idx - 1, id); dragY += h }
                            }
                        }
                    },
            ) { PhraseRow(p) }
        }
    }
}

/** 一行：点标题区进编辑，点右侧图标复制全文（两块热区分开）；左滑「删除 / 编辑」。 */
@Composable
private fun PhraseRow(p: Phrase) {
    val c = UmbraTheme.colors
    val router = AppGraph.router
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var resetJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    UmbraRevealSwipeRow(
        trailing = listOf(
            UmbraSwipeAction("删除", UmbraIconPaths.trash, c.danger) {
                // 推的是墓碑，服务端留 30 天（回收站里找得回来）—— 跟 PC phrases.deleteBody 逐字对齐。
                router.confirm(UmbraAlert(
                    title = "删除常用语「${p.name}」？", message = "删除后会移入回收站，保留 30 天。其它设备上的这条也会一并删掉。",
                    confirmLabel = "删除", destructive = true,
                ) { PhraseStore.delete(p.id); router.showToast("已删除「${p.name}」") })
            },
            UmbraSwipeAction("编辑", UmbraExtraIcons.edit, c.muted) { router.go(Route.MePhraseEdit(p.id)) },
        ),
        resetKey = p,
    ) {
        Row(Modifier.fillMaxWidth().background(c.card).defaultMinSize(minHeight = UmbraDimens.rowMinH).padding(start = 14.dp, end = 4.dp, top = 11.dp, bottom = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { router.go(Route.MePhraseEdit(p.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = 15.5.sp, fontWeight = UmbraType.weight560), modifier = Modifier.weight(1f, fill = false))
                    p.keyword?.takeIf { it.isNotEmpty() }?.let { k ->
                        Spacer(Modifier.width(7.dp))
                        Text(k, color = c.muted, style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(c.chip).padding(horizontal = 7.dp, vertical = 2.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(p.content.split("\n").firstOrNull { it.isNotEmpty() } ?: "", color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = 13.sp))
            }
            // 44×44 复制钮：成功后换勾 +「已复制」两秒复位。内容是明文，不做 60 秒清剪贴板（那条只管保险箱）。
            Column(
                Modifier.size(UmbraDimens.tapMin).clickable(role = Role.Button) {
                    val clip = PhraseHandout.copy(ctx, p)
                    copied = true
                    router.showToast(when (clip) {
                        PhraseHandout.ClipNote.Withheld -> "已复制「${p.name}」全文；剪贴板里是保险箱的内容，{clipboard} 留空了"
                        PhraseHandout.ClipNote.Denied -> "已复制「${p.name}」全文；没允许读剪贴板，{clipboard} 留空了"
                        PhraseHandout.ClipNote.Ok -> "已复制「${p.name}」全文"
                    })
                    resetJob?.cancel()
                    resetJob = scope.launch { delay(2000); copied = false }
                }.semantics { contentDescription = "复制「${p.name}」" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                UmbraIcon(if (copied) UmbraIconPaths.check else UmbraIconPaths.copy, if (copied) c.success else c.muted, size = 19.dp)
                if (copied) Text("已复制", color = c.success, style = TextStyle(fontSize = 9.5.sp, fontWeight = UmbraType.weight560))
            }
        }
    }
}

/**
 * 新建 / 编辑：名称、标签（已有关键词芯片预选，点一下写进输入框、再点取消）、内容（最小高 180）。
 * 没填够不置灰 —— 点了用吐司告诉你缺什么。iOS: PhrasesView.swift:449
 */
@Composable
fun PhraseEditScreen(id: String?) {
    val store = PhraseStore
    val router = AppGraph.router
    remember { store.ensureLoaded(); 0 }
    val items by store.items.collectAsState()
    val editing = id?.let { i -> items.firstOrNull { it.id == i } }
    // 只在第一次出现时灌入草稿：返回再进来不能把改到一半的字冲掉。
    var name by rememberSaveable { mutableStateOf(editing?.name ?: "") }
    var keyword by rememberSaveable { mutableStateOf(editing?.keyword ?: "") }
    var content by rememberSaveable { mutableStateOf(editing?.content ?: "") }
    val valid = name.isNotBlank() && content.isNotBlank()
    val existing = items.mapNotNull { it.keyword?.takeIf { k -> k.isNotEmpty() } }.distinct()

    fun save() {
        val n = name.trim(); val c = content.trim(); val k = keyword.trim()
        if (n.isEmpty()) { router.showToast("先给常用语起个名字"); return }
        if (c.isEmpty()) { router.showToast("内容不能是空的"); return }
        val e = editing
        if (e != null) {
            store.update(e.copy(name = n, content = c, keyword = k.ifEmpty { null }))
            router.showToast("已保存")
        } else {
            store.add(n, c, k.ifEmpty { null })
            router.showToast("已保存常用语「$n」")
        }
        router.back()
    }

    UmbraScreen(
        title = if (id == null) "新建常用语" else "编辑常用语",
        onBack = { router.back() },
        leading = { UmbraTopBarText("取消", { router.back() }) },
        actions = { UmbraTopBarText("保存", { save() }, strong = valid) },
        spacing = UmbraDimens.sp5,
    ) {
        item("name") { Field("名称") { Input(name, { name = it }, "例如「日报模板」", single = true) } }
        item("kw") {
            Field("标签") {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (existing.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            existing.take(10).forEach { k -> UmbraTagPill(k, keyword == k, { keyword = if (keyword == k) "" else k }) }
                        }
                    }
                    Input(keyword, { keyword = it }, "快捷标签，例如「日报」，可留空", single = true)
                }
            }
        }
        item("content") { Field("内容") { Input(content, { content = it }, "秘书会把这段原文直接拿去用", single = false) } }
        item("note") { Text("内容为明文存储，密钥类请放保险箱。", color = UmbraTheme.colors.muted, style = TextStyle(fontSize = 12.sp, lineHeight = 20.sp)) }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { UmbraFieldLabel(label); content() }
}

/** 输入框：card 底、圆角 12、borderSoft；单行高 48，多行最小高 180。 */
@Composable
private fun Input(value: String, onChange: (String) -> Unit, placeholder: String, single: Boolean) {
    val c = UmbraTheme.colors
    val shape = RoundedCornerShape(UmbraDimens.radiusInput)
    val size = if (single) 16.sp else 15.5.sp
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = single,
        textStyle = TextStyle(fontSize = size, color = c.text, lineHeight = if (single) size * 1.3f else 24.sp),
        cursorBrush = SolidColor(c.orange),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = if (single) 48.dp else 180.dp).clip(shape).background(c.card)
                    .border(UmbraDimens.borderW, c.borderSoft, shape).padding(horizontal = 14.dp, vertical = if (single) 0.dp else 14.dp),
                contentAlignment = if (single) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty()) Text(placeholder, color = c.faint, style = TextStyle(fontSize = size))
                inner()
            }
        },
    )
}
