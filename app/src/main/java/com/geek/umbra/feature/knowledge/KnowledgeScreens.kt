// 知识库模块的页面入口（函数签名不改，分发表在调）。对照 iOS Features/Knowledge/KnowledgeViews.swift：
// 「我」页入口 → 库列表 → 文档列表。文档列表：状态点 + 状态字（状态不只靠颜色）、失败原因 +「重试」、左滑删除、
// 点一行看原文；上传走系统文件选择器，带真实进度；删除带 If-Match。所有显示文案走字符串目录（kb.*）。
package com.geek.umbra.feature.knowledge

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraEmptyState
import com.geek.umbra.designsystem.UmbraGroupCard
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraPhaseFailedCard
import com.geek.umbra.designsystem.UmbraProgressBar
import com.geek.umbra.designsystem.UmbraPrompt
import com.geek.umbra.designsystem.UmbraRevealSwipeRow
import com.geek.umbra.designsystem.UmbraRowDivider
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.UmbraScreenPhase
import com.geek.umbra.designsystem.UmbraSkeleton
import com.geek.umbra.designsystem.UmbraSoftChip
import com.geek.umbra.designsystem.UmbraSpinningIcon
import com.geek.umbra.designsystem.UmbraStatusDot
import com.geek.umbra.designsystem.UmbraStripAction
import com.geek.umbra.designsystem.UmbraSwipeAction
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraTopBarIcon
import com.geek.umbra.designsystem.UmbraType
import com.geek.umbra.designsystem.umbraPhaseTop
import com.geek.umbra.feature.me.MeNote
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraAlert
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val router get() = AppGraph.router

// MARK: - 库列表

@Composable
fun KbListScreen() {
    val c = UmbraTheme.colors
    val kbs by KbListStore.kbs.collectAsState()
    val loading by KbListStore.loading.collectAsState()
    val loadedOnce by KbListStore.loadedOnce.collectAsState()
    val last by KbListStore.lastOutcome.collectAsState()
    val offline by AppGraph.http.offline.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { KbListStore.load() }
    val phase = UmbraScreenPhase.resolve(kbs.isNotEmpty(), loading && !loadedOnce, loadedOnce, offline)
    val retry: () -> Unit = { scope.launch { KbListStore.load() } }
    val ctx = AppGraph.context

    UmbraScreen(
        title = stringResource(R.string.kb_title), onBack = { router.back() },
        refreshing = refreshing, onRefresh = { scope.launch { refreshing = true; KbListStore.load(); refreshing = false } },
        actions = { UmbraTopBarIcon(UmbraIconPaths.plus, stringResource(R.string.kb_new), { askCreate() }, tint = c.orangeText) },
        topContent = umbraPhaseTop(phase, live = true, retry),
    ) {
        when (phase) {
            UmbraScreenPhase.Skeleton -> item("skel") { UmbraSkeleton() }
            is UmbraScreenPhase.Failed -> item("fail") {
                UmbraPhaseFailedCard(phase.offline, retry, title = ctx.getString(if (last == KbOutcome.Disabled) R.string.kb_fail_disabled else R.string.kb_list_failed))
            }
            is UmbraScreenPhase.Content -> {
                val dim = if (phase.offline) 0.6f else 1f
                if (kbs.isEmpty()) {
                    item("empty") {
                        UmbraEmptyState(UmbraIconPaths.folder, ctx.getString(R.string.kb_empty), hint = ctx.getString(R.string.kb_emptyBody),
                            action = ctx.getString(R.string.kb_new), onAction = { askCreate() })
                    }
                } else {
                    item("list") {
                        Column(Modifier.alpha(dim)) {
                            UmbraGroupCard {
                                kbs.forEachIndexed { i, kb ->
                                    if (i > 0) UmbraRowDivider()
                                    KbRow(kb)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            MeNote(ctx.getString(R.string.kb_listFoot), size = 11.5f, color = c.faint)
                        }
                    }
                }
            }
        }
    }
}

/** 一行：folder + 名称 15.5/560 +「N 份 · M 份处理中 · K 份失败」（有失败 dangerText）+ chevron，最小高 48。 */
@Composable
private fun KbRow(kb: KbDTO) {
    val c = UmbraTheme.colors
    val ctx = AppGraph.context
    // 没有的那一截不写。
    val parts = mutableListOf(ctx.getString(R.string.kb_docCount, kb.docs))
    if (kb.processing > 0) parts += ctx.getString(R.string.kb_processingCount, kb.processing)
    if (kb.failed > 0) parts += ctx.getString(R.string.kb_failedCount, kb.failed)
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.rowMinH)
            .clickable { router.go(Route.KbDocs(kb.kb_id, kb.displayName)) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UmbraIcon(UmbraIconPaths.folder, c.muted, size = 18.dp)
        Spacer(Modifier.width(UmbraDimens.sp3))
        Column(Modifier.weight(1f)) {
            Text(kb.displayName, color = c.text, style = TextStyle(fontSize = 15.5.sp, fontWeight = UmbraType.weight560), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(parts.joinToString(" · "), color = if (kb.failed > 0) c.dangerText else c.muted, style = TextStyle(fontSize = 12.sp), maxLines = 1)
        }
        UmbraIcon(UmbraIconPaths.chevronRight, c.faint, size = 14.dp, strokeWidth = 2f)
    }
}

/** 「+」新建库：起名弹层（iOS alert + TextField）。 */
private fun askCreate() {
    val ctx = AppGraph.context
    UmbraPrompt.present(
        title = ctx.getString(R.string.kb_new), message = ctx.getString(R.string.kb_newBody), initial = "",
        placeholder = ctx.getString(R.string.kb_namePlaceholder), confirmLabel = ctx.getString(R.string.kb_create), allowEmpty = false,
    ) { raw ->
        val name = raw.trim()
        if (name.isEmpty()) return@present
        AppGraph.scope.launch {
            val o = KnowledgeRules.outcome(KbNet.createKb(name, ""))
            if (o == KbOutcome.Ok) {
                router.showToast(ctx.getString(R.string.kb_created, name))
                KbListStore.load()
            } else if (o != KbOutcome.Cancelled) router.showToast(kbFailText(o))
        }
    }
}

// MARK: - 文档列表

/** 选择器里给的类型：服务端会进检索的那四种 + 保底（Markdown 常被报成 octet-stream）。选完按后缀再挡一道（isPickable）。 */
private val pickTypes = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "text/*",
    "application/octet-stream",
)

@Composable
fun KbDocsScreen(id: String, name: String) {
    val c = UmbraTheme.colors
    val ctx = AppGraph.context
    val model = remember(id) { KbDocsModel.of(id) }
    val docs by model.docs.collectAsState()
    val loading by model.loading.collectAsState()
    val loadedOnce by model.loadedOnce.collectAsState()
    val last by model.lastOutcome.collectAsState()
    val uploading by model.uploading.collectAsState()
    val opening by KbPreviewer.opening.collectAsState()
    val offline by AppGraph.http.offline.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) model.picked(uris.map { it to displayName(it) })
    }
    val pick: () -> Unit = { runCatching { picker.launch(pickTypes) } }

    LaunchedEffect(id) { model.load() }
    // 兜底轮询：有文档在处理时每 3 秒拉一次，全落定就停。页面离开组合时这个循环跟着取消。
    // Android 这一期同 iOS：不接 kb_doc_updated 推送。
    LaunchedEffect(id) { while (true) { delay(3_000); model.pollOnce() } }
    DisposableEffect(id) { onDispose { model.leftPage() } }

    val phase = UmbraScreenPhase.resolve(docs.isNotEmpty(), loading && !loadedOnce, loadedOnce, offline)
    val retry: () -> Unit = { scope.launch { model.load() } }

    UmbraScreen(
        title = name, onBack = { router.back() },
        refreshing = refreshing, onRefresh = { scope.launch { refreshing = true; model.load(); refreshing = false } },
        actions = { UmbraTopBarIcon(UmbraIconPaths.shareUp, stringResource(R.string.kb_upload), pick, tint = c.orangeText, enabled = uploading == null) },
        topContent = umbraPhaseTop(phase, live = true, retry),
    ) {
        uploading?.let { up -> item("upload") { UploadRow(up) { model.cancelUpload() } } }
        when (phase) {
            UmbraScreenPhase.Skeleton -> item("skel") { UmbraSkeleton() }
            is UmbraScreenPhase.Failed -> item("fail") {
                UmbraPhaseFailedCard(phase.offline, retry, title = ctx.getString(if (last == KbOutcome.Disabled) R.string.kb_fail_disabled else R.string.kb_docs_failed))
            }
            is UmbraScreenPhase.Content -> {
                if (docs.isEmpty()) {
                    item("empty") {
                        UmbraEmptyState(
                            UmbraIconPaths.file, ctx.getString(R.string.kb_docs_empty),
                            hint = ctx.getString(R.string.kb_docs_emptyBody, KnowledgeRules.UPLOAD_LIMIT_MB),
                            action = if (uploading == null) ctx.getString(R.string.kb_upload) else null, onAction = pick,
                        )
                    }
                } else {
                    item("docs") {
                        Column(Modifier.alpha(if (phase.offline) 0.6f else 1f)) {
                            UmbraGroupCard {
                                docs.forEachIndexed { i, d ->
                                    if (i > 0) UmbraRowDivider()
                                    DocRow(model, d, busy = opening == KbPreviewer.key(id, d.path))
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            MeNote(ctx.getString(R.string.kb_docs_foot, KnowledgeRules.UPLOAD_LIMIT_MB), size = 11.5f, color = c.faint)
                        }
                    }
                }
            }
        }
    }
}

/** 选中文件的显示名（OpenableColumns.DISPLAY_NAME）；拿不到用 URI 最后一段。 */
private fun displayName(uri: Uri): String {
    val cr = AppGraph.context.contentResolver
    val n = runCatching {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
    return n?.takeIf { it.isNotBlank() } ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "file")
}

/** 一行文档：左滑删除（失败的另有「重试」）；点一行看原文；失败行下面原因 +「重试」。 */
@Composable
private fun DocRow(model: KbDocsModel, d: KbDocDTO, busy: Boolean) {
    val c = UmbraTheme.colors
    val ctx = AppGraph.context
    val st = d.docState
    val actions = buildList {
        add(UmbraSwipeAction(ctx.getString(R.string.kb_delete), UmbraIconPaths.trash, c.danger, c.onDanger) { askDelete(model, d) })
        if (st == KbDocState.Failed) add(UmbraSwipeAction(ctx.getString(R.string.kb_retry), UmbraIconPaths.repeatArrows, c.warning, c.onWarning) { retry(model, d) })
    }
    UmbraRevealSwipeRow(trailing = actions) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = UmbraDimens.rowMinH - 16.dp)
                    .clickable(role = Role.Button) { KbPreviewer.open(model.kbId, d.path) },
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(d.displayTitle, color = c.text, style = TextStyle(fontSize = 15.sp, fontWeight = UmbraType.weight560), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val (dot, ink) = when (st.tone) {
                            KbTone.Success -> c.success to c.success
                            KbTone.Danger -> c.danger to c.dangerText
                            KbTone.Working -> c.warning to c.warningText
                        }
                        UmbraStatusDot(true, dot)
                        Text(kbString(st.labelKey), color = ink, style = TextStyle(fontSize = 12.sp, fontWeight = UmbraType.weight560))
                        if (d.stale == true) UmbraSoftChip(ctx.getString(R.string.kb_stale), c.warningText, c.warningSoft)
                        Text(metaLine(d), color = c.faint, style = TextStyle(fontSize = 12.sp), maxLines = 1)
                    }
                }
                if (busy) { Spacer(Modifier.width(8.dp)); UmbraSpinningIcon(UmbraIconPaths.spinnerArc, c.muted, 16.dp) }
            }
            if (st == KbDocState.Failed) {
                // 失败行：原因（服务端写的人话）+「重试」。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.error?.takeIf { it.isNotEmpty() } ?: ctx.getString(R.string.kb_failNoReason), color = c.dangerText,
                        style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp), modifier = Modifier.weight(1f))
                    UmbraStripAction(ctx.getString(R.string.kb_retry), c.orangeText, { retry(model, d) }, Modifier.widthIn(min = UmbraDimens.tapMin))
                }
            }
        }
    }
}

/** 「1.2 MB · 12 块」—— 块数只在就绪后有意义。 */
private fun metaLine(d: KbDocDTO): String {
    val parts = mutableListOf<String>()
    d.bytes?.takeIf { it > 0 }?.let { parts += KnowledgeRules.humanBytes(it) }
    if (d.docState == KbDocState.Ready) d.chunk_count?.let { parts += AppGraph.context.getString(R.string.kb_chunks, it) }
    return parts.joinToString(" · ")
}

/** 正在上传的那一份：名字（中间省略）+「取消」+ 进度条 + 排队数 + 已传 / 总量（还在读文件时「准备中…」）。 */
@Composable
private fun UploadRow(up: KbUploading, onCancel: () -> Unit) {
    val c = UmbraTheme.colors
    val ctx = AppGraph.context
    UmbraGroupCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ctx.getString(R.string.kb_uploading, up.name), color = c.text, style = TextStyle(fontSize = 13.5.sp, fontWeight = UmbraType.weight560),
                    maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
                Text(
                    ctx.getString(R.string.kb_uploadCancel), color = c.orangeText, style = TextStyle(fontSize = 13.5.sp, fontWeight = UmbraType.weight560),
                    modifier = Modifier.heightIn(min = UmbraDimens.tapMin).clickable(role = Role.Button, onClick = onCancel).padding(horizontal = 6.dp, vertical = 12.dp),
                )
            }
            UmbraProgressBar(up.progress, c.orange)
            if (up.queued > 0) Text(ctx.getString(R.string.kb_uploadQueued, up.queued), color = c.faint, style = TextStyle(fontSize = 11.5.sp))
            Text(
                if (up.total > 0) KnowledgeRules.humanBytes(up.sent) + " / " + KnowledgeRules.humanBytes(up.total) else ctx.getString(R.string.kb_uploadPreparing),
                color = c.faint, style = TextStyle(fontSize = 11.5.sp),
            )
        }
    }
}

private fun askDelete(model: KbDocsModel, d: KbDocDTO) {
    val ctx = AppGraph.context
    router.confirm(UmbraAlert(
        title = ctx.getString(R.string.kb_deleteTitle, d.displayTitle),
        message = ctx.getString(R.string.kb_deleteBody),
        cancelLabel = ctx.getString(R.string.kb_cancel),
        confirmLabel = ctx.getString(R.string.kb_deleteConfirm),
        destructive = true,
    ) {
        AppGraph.scope.launch {
            // 带 If-Match: <blob_sha> 删。412 = 别处刚改过这份：说清楚、顺手刷新，让人看过新版再决定。
            when (val o = KnowledgeRules.outcome(KbNet.deleteKbDoc(model.kbId, d.doc_id, d.blob_sha))) {
                KbOutcome.Ok -> router.showToast(ctx.getString(R.string.kb_deleted))
                KbOutcome.Missing -> router.showToast(ctx.getString(R.string.kb_fail_missing))
                KbOutcome.Cancelled -> Unit
                else -> router.showToast(kbFailText(o))
            }
            model.load()
        }
    })
}

private fun retry(model: KbDocsModel, d: KbDocDTO) {
    AppGraph.scope.launch {
        val o = KnowledgeRules.outcome(KbNet.reindexKbDoc(model.kbId, d.doc_id))
        if (o != KbOutcome.Cancelled) router.showToast(if (o == KbOutcome.Ok) AppGraph.context.getString(R.string.kb_retried) else kbFailText(o))
        model.load()
    }
}
