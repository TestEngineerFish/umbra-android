// 知识库两页的数据（iOS KnowledgeViews.swift 的 KbListModel / KbDocsModel + 上传队列）。
// 在线优先、没有本地缓存（同灵感）。
package com.geek.umbra.feature.knowledge

import android.net.Uri
import android.provider.OpenableColumns
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.navigation.Route
import com.geek.umbra.net.BackgroundWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.geek.umbra.feature.me.MeSheetItem
import com.geek.umbra.feature.me.presentChoice

/** 库列表（iOS KbListModel）。最近一次的档：disabled 时错误卡说「没启用」，不说「拿不到」。 */
object KbListStore {
    val kbs = MutableStateFlow<List<KbDTO>>(emptyList())
    val loading = MutableStateFlow(true)
    val loadedOnce = MutableStateFlow(false)
    val lastOutcome = MutableStateFlow<KbOutcome>(KbOutcome.Ok)

    suspend fun load() {
        loading.value = true
        val (r, list) = KbNet.fetchKbs()
        lastOutcome.value = KnowledgeRules.outcome(r)
        if (list != null) { kbs.value = list; loadedOnce.value = true }
        loading.value = false
    }

    fun clear() {
        kbs.value = emptyList(); loadedOnce.value = false; loading.value = true; lastOutcome.value = KbOutcome.Ok
    }
}

/** 正在上传的那一份。`queued`：后面还排着几份。 */
data class KbUploading(val name: String, val queued: Int = 0, val sent: Long = 0, val total: Long = 0) {
    val progress: Float get() = if (total > 0) sent.toFloat() / total else 0f
}

/** 一个库的文档列表 + 它的上传队列。按库 id 各一份，进程级活着：切 Tab 回来进度条还在走（kb-upload-cancelled-on-tab-switch）。 */
class KbDocsModel(val kbId: String) {
    val docs = MutableStateFlow<List<KbDocDTO>>(emptyList())
    val loading = MutableStateFlow(true)
    val loadedOnce = MutableStateFlow(false)
    val lastOutcome = MutableStateFlow<KbOutcome>(KbOutcome.Ok)
    val uploading = MutableStateFlow<KbUploading?>(null)
    private val seq = KbLoadSeq()
    private var uploadJob: Job? = null

    val anyBusy: Boolean get() = docs.value.any { it.docState.busy }

    /** 只认最后发出的那次加载；自己取消的不算一次失败，别拿它盖掉上一次的档。 */
    suspend fun load() {
        loading.value = true
        val ticket = seq.next()
        val (r, dto) = KbNet.fetchKbDocs(kbId)
        if (!seq.isLatest(ticket)) return
        val o = KnowledgeRules.outcome(r)
        if (o != KbOutcome.Cancelled) lastOutcome.value = o
        if (dto != null) { docs.value = dto.docs; loadedOnce.value = true }
        loading.value = false
    }

    /** 兜底轮询一轮：有文档在排队 / 解析 / 向量化、且上次的档不是「重拉也不会好」的那三档才拉。不是人点的：后台路径。 */
    suspend fun pollOnce() {
        if (KnowledgeRules.shouldPoll(anyBusy, lastOutcome.value)) withContext(BackgroundWork) { load() }
    }

    fun reloadDetached() { AppGraph.scope.launch { load() } }

    // MARK: 上传

    /**
     * 选完之后：先按后缀挡（sam 2026-10-07 第 5 条），能传的排队传，挡下的逐个说（一个：标题说是哪个；多个：正文逐个列名字）。
     * 有挡下的时先弹那句、人点「知道了」再开始传。
     */
    fun picked(files: List<Pair<Uri, String>>) {
        val ok = files.filter { KnowledgeRules.isPickable(it.second) }
        val bad = files.filterNot { KnowledgeRules.isPickable(it.second) }.map { it.second }
        if (bad.isEmpty()) { if (ok.isNotEmpty()) startQueue(ok); return }
        val ctx = AppGraph.context
        val title = if (bad.size == 1) ctx.getString(R.string.kb_reject_one, bad[0]) else ctx.getString(R.string.kb_reject_many, bad.size)
        val list = if (bad.size == 1) "" else bad.joinToString("\n") { "「$it」" } + "\n\n"
        AppGraph.router.confirm(com.geek.umbra.navigation.UmbraAlert(
            title = title,
            message = list + ctx.getString(R.string.kb_reject_supported),
            confirmLabel = ctx.getString(R.string.kb_reject_ok),
            // iOS 这个弹窗只有「知道了」一颗；Android 的确认弹窗固定两颗，「取消」= 连能传的也先不传。
            cancelLabel = ctx.getString(R.string.kb_cancel),
        ) { if (ok.isNotEmpty()) startQueue(ok, afterAlert = true) })
    }

    /** 「取消上传」：取消的是**整个队**（人点取消时不会想让后面的接着传）。 */
    fun cancelUpload() { uploadJob?.cancel() }

    /** 这一页真的不在任何一条导航栈里了（返回 / 登出清栈）才取消；切 Tab 时它还在自己那条栈里，上传接着跑。 */
    fun leftPage() {
        val stillStacked = AppGraph.router.stacks.values.any { s -> s.any { it is Route.KbDocs && it.id == kbId } }
        if (!stillStacked) cancelUpload()
    }

    private fun startQueue(files: List<Pair<Uri, String>>, afterAlert: Boolean = false) {
        if (uploadJob?.isActive == true) return
        uploading.value = KbUploading(files[0].second, queued = files.size - 1)
        uploadJob = AppGraph.scope.launch {
            try {
                // 刚点掉的弹窗还在收起的动画里：这时弹同名询问会和它叠在一起。等它收完。
                if (afterAlert) delay(450)
                for ((i, f) in files.withIndex()) {
                    if (!currentCoroutineContext().isActive) break
                    uploadOne(f.first, f.second, files.size - 1 - i)
                }
            } finally {
                uploading.value = null
                uploadJob = null
            }
        }
    }

    /**
     * 一份：查同名（第 6 条）→ 必要时问 → 读文件 → 传 → 收尾。查同名**每一份都现拉一次列表**：页面上那份可能还没拉到、
     * 可能是几秒前的，一次多选里前一份「两个都保留」改出来的名字下一份也得看得见。拉失败按「可能覆盖」问，不静默覆盖。
     */
    private suspend fun uploadOne(uri: Uri, original: String, queued: Int) {
        val ctx = AppGraph.context
        val router = AppGraph.router
        uploading.value = KbUploading(original, queued)
        val (lr, dto) = KbNet.fetchKbDocs(kbId)
        if (!currentCoroutineContext().isActive) { router.showToast(ctx.getString(R.string.kb_uploadCancelled)); return }
        if (KnowledgeRules.outcome(lr) == KbOutcome.Cancelled) return
        val existing = dto?.docs?.map { it.path }
        val check = KnowledgeRules.nameCheck(original, existing)
        var choice = KbClashChoice.Overwrite
        if (check != KbNameCheck.Free) choice = askClash(check, original, existing ?: emptyList())
        // 选了「取消」= 只是这一份不传，队里别的照传；不说话（人刚亲手点的取消）。
        val fileName = KnowledgeRules.uploadName(original, check, choice, existing ?: emptyList()) ?: return
        if (!currentCoroutineContext().isActive) return
        uploading.value = KbUploading(fileName, queued)
        val (r, up) = try {
            val bytes = readFile(uri)
            KbNet.uploadKbFile(kbId, bytes, fileName) { sent, total -> uploading.value = uploading.value?.copy(sent = sent, total = total) }
        } catch (e: KbFileError) {
            KnowledgeRules.transport(e) to null
        }
        val end = KnowledgeRules.uploadEnd(r, up?.indexed, up?.changed, taskCancelled = !currentCoroutineContext().isActive)
        when (val s = end.say) {
            KbUploadEnd.Say.Uploaded -> router.showToast(ctx.getString(R.string.kb_uploaded, fileName))
            KbUploadEnd.Say.Unchanged -> router.showToast(ctx.getString(R.string.kb_unchanged, fileName))
            KbUploadEnd.Say.Unindexed -> router.showToast(ctx.getString(R.string.kb_unindexed))
            KbUploadEnd.Say.Cancelled -> router.showToast(ctx.getString(R.string.kb_uploadCancelled))
            KbUploadEnd.Say.Silent -> Unit
            KbUploadEnd.Say.TooLarge -> router.showToast(ctx.getString(R.string.kb_tooLarge, fileName, KnowledgeRules.UPLOAD_LIMIT_MB))
            is KbUploadEnd.Say.Fail -> router.showToast(kbFailText(s.o))
        }
        // ⚠️ 刷列表要另起一个协程：走到这里时上传这个协程可能已经被取消了（人点了取消），在它里面拉会立刻被取消 ——
        // 而取消之后恰恰最需要刷（服务端可能已经 commit 了）。
        if (end.reload) reloadDetached()
    }

    /**
     * 读选中的文件：先看大小元数据（便宜），再边读边数（元数据可能是 0 / 说谎）。IO 线程上做 —— 最多 32 MiB，主线程读会卡。
     */
    private suspend fun readFile(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val cr = AppGraph.context.contentResolver
        val limit = KnowledgeRules.UPLOAD_LIMIT_MB.toLong() * 1024 * 1024
        val size = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
        if (size > limit) throw KbFileError(KbFileError.Kind.TooLarge)
        val input = runCatching { cr.openInputStream(uri) }.getOrNull() ?: throw KbFileError(KbFileError.Kind.Unreadable)
        KnowledgeRules.readCapped(input, limit)
    }

    /**
     * 同名询问（iOS KbClashPrompt）：底部弹层三选一 =「覆盖成新版本」「两个都保留」+「取消」。下滑关掉 = 取消。
     * 上传被取消时顺手收起还开着的那张。列表没拉到（Unknown）只给「照样上传」—— 算不出保证不撞的名字。
     */
    private suspend fun askClash(check: KbNameCheck, picked: String, existing: List<String>): KbClashChoice {
        val ctx = AppGraph.context
        val d = CompletableDeferred<KbClashChoice>()
        val key = "kbclash-${System.nanoTime()}"
        withContext(Dispatchers.Main) {
            if (check is KbNameCheck.Clash) {
                presentChoice(
                    ctx.getString(R.string.kb_clash_title, check.existing),
                    listOf(
                        // 「覆盖」标破坏性：库里那份的内容被换掉，手机上找不回旧版本（只在服务端 git 历史里）。
                        MeSheetItem(ctx.getString(R.string.kb_clash_overwrite), destructive = true) { d.complete(KbClashChoice.Overwrite) },
                        MeSheetItem(ctx.getString(R.string.kb_clash_keepBoth)) { d.complete(KbClashChoice.KeepBoth) },
                    ),
                    subtitle = ctx.getString(R.string.kb_clash_keepBothNote, KnowledgeRules.keepBothName(picked, existing)),
                    key = key,
                    onDismissed = { if (!it) d.complete(KbClashChoice.Cancel) },
                )
            } else {
                presentChoice(
                    ctx.getString(R.string.kb_clash_unknownTitle, picked),
                    listOf(MeSheetItem(ctx.getString(R.string.kb_clash_uploadAnyway), destructive = true) { d.complete(KbClashChoice.Overwrite) }),
                    subtitle = ctx.getString(R.string.kb_clash_unknownBody),
                    key = key,
                    onDismissed = { if (!it) d.complete(KbClashChoice.Cancel) },
                )
            }
        }
        return try {
            d.await()
        } catch (e: CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { AppGraph.router.sheets.removeAll { it.key == key } }
            throw e
        }
    }

    companion object {
        private val models = HashMap<String, KbDocsModel>()
        fun of(kbId: String): KbDocsModel = models.getOrPut(kbId) { KbDocsModel(kbId) }
        /** 守卫「关」：取消所有上传、清掉各库的列表（上一个账号的文档不留在屏上）。 */
        fun clearAll() {
            models.values.forEach { it.cancelUpload() }
            models.clear()
        }
    }
}
