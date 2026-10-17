// 记忆模块的页面入口（函数签名不改，分发表在调）。样式照设计侧《批次 237 · 记忆 iOS》，对照 iOS Features/Me/MemoryViews.swift：
//   列表 meMemory：第一组「告诉秘书要记住什么」（橙字，点开弹层）+「从其他 AI 导入」；满 200 条那句落在这一组；
//                  「生成与读取」三个开关；你 / 话题 / 领域 / 人物（组头带条数）；底部「N / 200 条」；右上「+」新建、⋯ 只有全部清空。
//   告诉秘书 = 弹层（取消 / 发送）。**发送后弹层立刻关**，那一行就地变「转圈 + 整理中… + 原话」。
//   详情 meMemoryItem(id)：大字标题 +「分类 · 最后更新」、一句话一张卡、「详情 · N 条」；右上「编辑」+ ⋯ 删除 / 清空。
//   编辑 meMemoryEdit(id, kind)：冲突卡在最上面；新建时顶上分段选分类；偏好 / 档案标题与一句话只读。kind == "import" 是导入页。
package com.geek.umbra.feature.memory

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraColorScheme
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraErrorCard
import com.geek.umbra.designsystem.UmbraErrorStrip
import com.geek.umbra.designsystem.UmbraFieldLabel
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraMenuItem
import com.geek.umbra.designsystem.UmbraMoreMenu
import com.geek.umbra.designsystem.UmbraRowDivider
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraSegment
import com.geek.umbra.designsystem.UmbraSegmentedControl
import com.geek.umbra.designsystem.UmbraSettingRow
import com.geek.umbra.designsystem.UmbraSkeleton
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTone
import com.geek.umbra.designsystem.UmbraTopBarIcon
import com.geek.umbra.designsystem.UmbraTopBarText
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.designsystem.settingSection
import com.geek.umbra.feature.me.copyPlainText
import com.geek.umbra.feature.me.footnoteItem
import com.geek.umbra.feature.me.meInputShape
import com.geek.umbra.feature.me.MeRow
import com.geek.umbra.feature.me.meSection
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import com.geek.umbra.navigation.UmbraSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

private val router get() = AppGraph.router

/** 告诉秘书 / 看改了哪条那句吐司：有改动的第一条就带「看改了哪条」。 */
private fun toastWithJump(msg: String, first: String?) {
    if (first != null) router.showToast(msg, "看改了哪条") { router.go(Route.MeMemoryItem(first)) }
    else router.showToast(msg)
}

// MARK: - 列表

@Composable
fun MemoryScreen() {
    val c = UmbraTheme.colors
    val items by MemoryStore.items.collectAsState()
    val failed by MemoryStore.loadFailed.collectAsState()
    val switches by MemoryStore.switches.collectAsState()
    val telling by MemoryStore.telling.collectAsState()
    val fullNote by MemoryStore.fullNote.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { MemoryStore.load() }
    val count = items?.size ?: 0
    val retry: () -> Unit = { scope.launch { MemoryStore.load() } }

    UmbraScreen(
        title = "记忆", onBack = { router.back() }, refreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; MemoryStore.load(); refreshing = false } },
        actions = {
            // 新建走右上「+」（237：列表里不放三行「新建话题 / 领域 / 人物」）；满 200 禁用。
            UmbraTopBarIcon(UmbraIconPaths.plus, "新建一条记忆", { router.go(Route.MeMemoryEdit(null, "topic")) }, tint = c.orangeText, enabled = count < 200)
            if (items != null) UmbraMoreMenu(listOf(UmbraMenuItem("全部清空", danger = true) { askClearAll(count) }))
        },
    ) {
        val list = items
        if (list != null && failed) {
            // 读失败但手里有上次那份：照画，顶上说清楚这不是最新的（umbra-ios#86 ②）。
            item("stale") { UmbraErrorStrip("没读到最新的记忆，眼前这份是上次读到的。", tone = UmbraTone.Warning, action = AppGraph.context.getString(R.string.state_retry) to retry) }
        }
        if (list == null) {
            item("state") {
                if (failed) UmbraErrorCard(title = "没读到记忆", body = stringResource(R.string.state_failed_reason_server), actions = listOf(stringResource(R.string.state_retry) to retry))
                else UmbraSkeleton()
            }
            return@UmbraScreen
        }
        // 第一组：让秘书去改记忆的两件事。
        val first = buildList {
            val t = telling
            if (t != null) add(UmbraSettingRow("整理中…", sub = "「$t」", value = "", valueIcon = UmbraIconPaths.spinnerArc, valueIconSpins = true, tint = c.muted))
            else add(UmbraSettingRow("告诉秘书要记住什么", tint = c.orangeText, action = { presentTell() }))
            add(UmbraSettingRow("从其他 AI 导入", chevron = true, action = { router.go(Route.MeMemoryEdit(null, "import")) }))
        }
        when (val n = MemoryStore.fullNoteShown(list, fullNote)) {
            FullNote.Tell -> settingSection("first", null, first, footer = MemoryRules.FULL_TEXT, footerTone = UmbraTone.Danger)
            is FullNote.Imported -> settingSection("first", null, first, footer = MemoryRules.importFullText(n.skipped), footerTone = UmbraTone.Warning)
            null -> settingSection("first", null, first)
        }
        switches?.let { sw ->
            fun row(key: String, label: String) = UmbraSettingRow(label, toggle = sw[key] ?: false, onToggle = {
                AppGraph.scope.launch { if (!MemoryStore.flip(key)) router.showToast("开关没改成，稍后再试") }
            })
            settingSection("switches", "生成与读取", listOf(
                row(MemoryRules.KEY_GENERATE, "从对话生成记忆"),
                row(MemoryRules.KEY_SEARCH_CHATS, "搜索历史对话"),
                row(MemoryRules.KEY_SENSITIVE, "记录敏感话题"),
            ), footer = "健康、财务、感情这类话题默认不记。打开后照常记，你随时能删。")
        }
        val now = Instant.now()
        MemoryRules.grouped(list).forEach { (label, group) ->
            settingSection("g-$label", label, group.map { m ->
                val empty = MemoryRules.isEmpty(m)
                UmbraSettingRow(m.title, sub = if (empty) "还没有内容" else m.summary.ifEmpty { null },
                    value = MemoryRules.updatedText(m.updated_at, now).ifEmpty { null }, chevron = true,
                    action = { router.go(Route.MeMemoryItem(m.id)) })
            }, headerCount = group.size)
        }
        footnoteItem("$count / 200 条")
    }
}

private fun askClearAll(count: Int) {
    router.confirm(UmbraAlert(
        title = "清空全部 $count 条记忆？",
        message = "清空后移入回收站，保留 30 天。偏好和档案留着、内容清空，其余整条删掉。",
        confirmLabel = "全部清空", destructive = true,
    ) {
        AppGraph.scope.launch {
            val r = MemoryApi.clear()
            if (r is MemoryWrite.Failed) { router.showToast(r.say); return@launch }
            MemoryStore.absorb(r)
            router.showToast("记忆已全部清空")
        }
    })
}

/** 「告诉秘书」弹层：取消 / 发送，大输入框自动聚焦；发送后弹层立刻关，结果回列表等。 */
private fun presentTell() {
    router.present(UmbraSheet("memory-tell") { dismiss ->
        val c = UmbraTheme.colors
        var text by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(120); runCatching { focus.requestFocus() } }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UmbraTopBarText("取消", dismiss)
                Text("告诉秘书", color = c.text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                UmbraTopBarText("发送", {
                    val t = text.trim()
                    dismiss()
                    MemoryStore.tell(t, ::toastWithJump)
                }, enabled = text.isNotBlank(), strong = true)
            }
            Spacer(Modifier.height(6.dp))
            MemoryEditor(text, { text = it }, "", c, minHeight = 150, modifier = Modifier.focusRequester(focus))
            Spacer(Modifier.height(8.dp))
            Text("例如：列表别超过五条。秘书会自己判断改哪条、要不要新建，要几秒到几十秒。发出去这一页就关，回列表等结果。",
                color = c.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp), modifier = Modifier.padding(horizontal = 4.dp))
        }
    })
}

// MARK: - 详情

@Composable
fun MemoryItemScreen(id: String) {
    val c = UmbraTheme.colors
    val items by MemoryStore.items.collectAsState()
    val m = items?.firstOrNull { it.id == id }
    UmbraScreen(
        title = "", onBack = { router.back() },
        actions = {
            if (m != null) {
                UmbraTopBarText("编辑", { router.go(Route.MeMemoryEdit(m.id, m.kind)) }, strong = true)
                val fixed = MemoryRules.isFixed(m.kind)
                UmbraMoreMenu(listOf(UmbraMenuItem(if (fixed) "清空「${m.title}」" else "删除「${m.title}」", danger = true) { askDelete(m) }))
            }
        },
    ) {
        if (m == null) {
            // 被删了 / 列表还没读到：说一句，不画空架子。
            item("gone") { Text("这条记忆已经不在了", color = c.muted, style = TextStyle(fontSize = 14.sp), modifier = Modifier.padding(horizontal = 4.dp)) }
            return@UmbraScreen
        }
        item("head") {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(m.title, color = c.text, style = TextStyle(fontSize = 26.sp, fontWeight = UmbraType.weight650, lineHeight = 32.sp))
                Text("${MemoryRules.groupLabel(m.kind)} · 最后更新 ${MemoryRules.agoText(m.updated_at, Instant.now())}", color = c.muted, style = TextStyle(fontSize = 13.sp))
            }
        }
        val lines = MemoryRules.bodyLines(m.body)
        // 一句话一张卡。
        meSection("summary", null, listOf(MeRow(UmbraSettingRow(m.summary.ifEmpty { "还没有内容" }, tint = if (m.summary.isEmpty()) c.faint else null))))
        // 「详情 · N 条」一条一行（长的折行，不截断）。
        meSection("lines", "详情 · ${lines.size} 条",
            if (lines.isEmpty()) listOf(MeRow(UmbraSettingRow("还没有内容。秘书会在对话里慢慢记，你也可以点「编辑」自己写。", tint = c.muted)))
            else lines.map { MeRow(UmbraSettingRow(it)) })
    }
}

private fun askDelete(m: MemoryItemDTO) {
    val fixed = MemoryRules.isFixed(m.kind)
    router.confirm(UmbraAlert(
        title = if (fixed) "清空「${m.title}」？" else "删除「${m.title}」？",
        message = if (fixed) "清空后移入回收站，保留 30 天。这一条本身留着，秘书会重新开始记。" else "删除后会移入回收站，保留 30 天。",
        confirmLabel = if (fixed) "清空" else "删除", destructive = true,
    ) {
        AppGraph.scope.launch {
            val r = MemoryApi.delete(m.id)
            if (r is MemoryWrite.Failed) { router.showToast(r.say); return@launch }
            MemoryStore.load()
            if (!fixed) router.back(ifShowing = Route.MeMemoryItem(m.id))
        }
    })
}

// MARK: - 编辑 / 新建 / 导入

/** 导入上限 20,000 字（iOS MemoryViews.swift:376）。 */
private const val IMPORT_MAX = 20_000

@Composable
fun MemoryEditScreen(id: String?, kind: String) {
    val c = UmbraTheme.colors
    val items by MemoryStore.items.collectAsState()
    val isImport = kind == "import"
    val editing = id?.let { i -> items?.firstOrNull { it.id == i } }
    var title by rememberSaveable { mutableStateOf("") }
    var summary by rememberSaveable { mutableStateOf("") }
    var bodyText by rememberSaveable { mutableStateOf("") }
    var newKind by rememberSaveable { mutableStateOf(if (MemoryRules.freeKinds.any { it.first == kind }) kind else "topic") }
    // 开始编辑时那条的 updated_at（编辑期间秘书改过 → 服务端回冲突）。
    var base by rememberSaveable { mutableStateOf<String?>(null) }
    // 409 memory_changed 带回的现在那份：非 null 时最上面出「被更新过」卡，让他选。
    var conflict by remember { mutableStateOf<MemoryItemDTO?>(null) }
    var busy by remember { mutableStateOf(false) }
    var importPrompt by remember { mutableStateOf<String?>(null) }
    // 只在第一次出现时灌入（返回再进来不冲掉改到一半的字）。
    var seeded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(editing?.id) {
        if (!seeded) {
            editing?.let { title = it.title; summary = it.summary; bodyText = it.body; base = it.updated_at }
            if (editing != null || id == null) seeded = true
        }
    }
    LaunchedEffect(isImport) { if (isImport) importPrompt = MemoryApi.importPrompt() }

    val fixed = MemoryRules.isFixed(editing?.kind ?: kind)
    val over = isImport && bodyText.length > IMPORT_MAX
    // 带 id 进来却没灌进那一条（列表被清了 / 换了账号 / 还没读到）：保存等于拿空标题去盖那个 id —— 不给点（umbra-ios#86）。
    val canSubmit = when {
        busy -> false
        isImport -> bodyText.isNotBlank() && !over
        id != null && base == null -> false
        else -> true
    }
    val navTitle = if (isImport) "导入" else if (editing != null) "编辑" else "新建记忆"

    fun submit() {
        if (!canSubmit) {
            if (isImport && bodyText.isBlank()) router.showToast("先把其他 AI 的回答贴进来")
            return
        }
        if (!isImport && !fixed && title.isBlank()) { router.showToast("标题不能为空"); return }
        busy = true
        AppGraph.scope.launch {
            val r = when {
                isImport -> { MemoryStore.fullNote.value = null; MemoryApi.import(bodyText.trim()) }
                id != null -> MemoryApi.update(id, if (fixed) null else title, if (fixed) null else summary, bodyText, base)
                else -> MemoryApi.create(newKind, title, summary, bodyText)
            }
            busy = false
            when (r) {
                is MemoryWrite.Ok -> {
                    MemoryStore.absorb(r)
                    router.back(ifShowing = Route.MeMemoryEdit(id, kind))
                    if (isImport) {
                        // 238：满了那句落在列表第一组组脚；改了几条照常吐司（带「看改了哪条」），N = 0 不出吐司。
                        if (r.full) MemoryStore.fullNote.value = FullNote.Imported(r.skipped)
                        val n = r.changed
                        if (n != null && n > 0) toastWithJump(MemoryRules.importedText(n), r.firstChanged)
                        else if (!r.full) router.showToast(MemoryRules.importedText(0))
                    } else router.showToast("已保存")
                }
                is MemoryWrite.Conflict -> conflict = r.current
                is MemoryWrite.Failed -> router.showToast(r.say)
            }
        }
    }

    UmbraScreen(
        title = navTitle, onBack = { router.back() },
        actions = { UmbraTopBarText(if (busy) "整理中…" else if (isImport) "导入" else "保存", { submit() }, enabled = canSubmit, strong = true) },
    ) {
        item("form") {
            Column(verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp5)) {
                conflict?.let { cur ->
                    UmbraErrorCard(
                        title = "这条记忆在你编辑期间被更新过",
                        body = MemoryRules.changedText(cur.source, cur.updated_at),
                        tone = UmbraTone.Warning,
                        actions = listOf(
                            "载入新版" to { title = cur.title; summary = cur.summary; bodyText = cur.body; base = cur.updated_at; conflict = null },
                            "覆盖保存" to {
                                router.confirm(UmbraAlert(
                                    title = "用你这一版覆盖？", message = "秘书在你编辑期间记下的新内容会被换掉，不可恢复。",
                                    confirmLabel = "覆盖", destructive = true,
                                ) { base = cur.updated_at; conflict = null; submit() })
                            },
                        ),
                    )
                }
                if (isImport) {
                    Note("把别的 AI 对你的了解搬过来。秘书只新增和修改，不删已有的。")
                    Field("1 · 把这段话发给另一个 AI") {
                        Column(Modifier.meInputShape(c)) {
                            Text(importPrompt ?: "正在读取提示词…", color = if (importPrompt == null) c.faint else c.text,
                                style = TextStyle(fontSize = 14.sp, lineHeight = 22.sp), modifier = Modifier.fillMaxWidth().padding(14.dp))
                            UmbraRowDivider()
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = UmbraDimens.tapMin)
                                    .clickable(enabled = importPrompt != null, role = Role.Button) { importPrompt?.let { copyPlainText(it); router.showToast("已复制") } },
                                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val tint = if (importPrompt == null) c.faint else c.orangeText
                                UmbraIcon(UmbraIconPaths.copy, tint, size = 16.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("复制提示词", color = tint, style = TextStyle(fontSize = 15.sp))
                            }
                        }
                    }
                    Field("2 · 把它的回答整段贴到这里") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            MemoryEditor(bodyText, { bodyText = it }, "贴进来就行，不用整理格式", c, minHeight = 160, error = over, enabled = !busy)
                            Row {
                                Text(if (over) "超了 ${bodyText.length - IMPORT_MAX} 字，删掉一些再导入" else if (busy) "要几十秒。" else "",
                                    color = if (over) c.dangerText else c.faint, style = TextStyle(fontSize = 13.sp), modifier = Modifier.weight(1f))
                                Text("${bodyText.length} / 20,000", color = if (over) c.dangerText else c.faint,
                                    style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                            }
                        }
                    }
                } else {
                    if (editing == null) {
                        val idx = MemoryRules.freeKinds.indexOfFirst { it.first == newKind }.coerceAtLeast(0)
                        UmbraSegmentedControl(MemoryRules.freeKinds.map { UmbraSegment(it.second) }, idx, { newKind = MemoryRules.freeKinds[it].first })
                    }
                    if (fixed) {
                        Field("标题 · 一句话") {
                            Column(Modifier.fillMaxWidth().meInputShape(c).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(title, color = c.text, style = TextStyle(fontSize = 15.sp))
                                Text(summary, color = c.muted, style = TextStyle(fontSize = 13.sp))
                            }
                        }
                        Note("固定的一条，标题和一句话不能改")
                    } else {
                        Field("标题") { MemoryLine(title, { title = it }, "例如：Umbra 设计稿", c) }
                        Field("一句话 · 是什么，什么时候读") { MemoryLine(summary, { summary = it }, "例如：项目的批次往来。聊到设计稿或批次号时读。", c) }
                    }
                    Field("详情 · 一行一条") { MemoryEditor(bodyText, { bodyText = it }, "一行写一条要点", c, minHeight = 180, enabled = !busy) }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, body: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        UmbraFieldLabel(label, Modifier.padding(horizontal = 4.dp))
        body()
    }
}

@Composable
private fun Note(s: String) {
    Text(s, color = UmbraTheme.colors.muted, style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp), modifier = Modifier.padding(horizontal = 4.dp))
}

/** 单行输入：高 48、圆角 12、card 底 + borderSoft（iOS inputShape）。 */
@Composable
private fun MemoryLine(value: String, onChange: (String) -> Unit, placeholder: String, c: UmbraColorScheme) {
    BasicTextField(
        value, onChange, singleLine = true, textStyle = TextStyle(fontSize = 16.sp, color = c.text), cursorBrush = SolidColor(c.orange),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).meInputShape(c).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, color = c.faint, style = TextStyle(fontSize = 16.sp))
                inner()
            }
        },
    )
}

/** 多行编辑框：15.5 字、圆角 12、card 底；出错描边换 danger（导入超字数）。 */
@Composable
private fun MemoryEditor(
    value: String, onChange: (String) -> Unit, placeholder: String, c: UmbraColorScheme,
    minHeight: Int, error: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier,
) {
    BasicTextField(
        value, onChange, enabled = enabled, textStyle = TextStyle(fontSize = 15.5.sp, lineHeight = 23.sp, color = c.text), cursorBrush = SolidColor(c.orange),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = minHeight.dp).meInputShape(c)
                    .then(if (error) Modifier.border(1.dp, c.danger, RoundedCornerShape(UmbraDimens.radiusInput)) else Modifier).padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, color = c.faint, style = TextStyle(fontSize = 15.5.sp))
                inner()
            }
        },
    )
}

