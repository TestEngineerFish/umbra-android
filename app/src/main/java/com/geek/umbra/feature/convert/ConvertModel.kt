// 「用电脑转换」面板的状态（设计侧批次 269 §四 / 271 / 272；稿 01 选格式 / 02 三段进度 / 03 结果）。照 iOS `ConvertModel.swift`。
//
// sam 10-18 拍板：**转换只在电脑上做**。手机只做四件事：问哪台电脑在线 → 问它这个文件能转成什么 →
// 把文件传上去、让电脑转 → 等着、把成品取回来交给系统的「存到文件 / 存到相册 / 分享」。**没有电脑在线就明说，不给变通**。
//
// 主 App（设备详情页，电脑预选）与分享透明 Activity（第一屏）共用这一份。几件不能错的：
// 1. **先问会话守卫**：未登录一个请求都不发（读 Prefs 里守卫落下的 signedOut，和分享记灵感同一个判据）。
// 2. **面板关掉作业不丢**：作业在服务端跑、电脑在转，这里只是看着。回到 App 时设备详情页顶上挂「X 转好了」——
//    认「是不是我发起的」靠 [ConvertJobLog] 记下的 id。iOS 另有「转完推送给发起的那台」，Android 等服务端接 FCM。
// 3. **「没回话」的秒数照实说**：服务端同步派活上限 60 秒（[ConvertRules.ASK_TIMEOUT_SECONDS]）。
// 4. **电脑中途断开**：每两次轮询顺手问一次在线列表，连着 [ConvertRules.LOST_AFTER_MISSES] 次看不到那台 → 第二段 x-circle，给「重试」。
package com.geek.umbra.feature.convert

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.net.UmbraJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

/** 取一句面板文案（`share_conv_*` 由 gen_strings 从 iOS 生成；按 App 内语言取）。 */
internal fun convText(id: Int, vararg args: Any): String = AppGraph.context.getString(id, *args)

/**
 * 本机发起过的作业：`filesDir/convert/jobs.json`（iOS 是 App Group 里的 `umbra.convert.jobs`）。
 * Android 的分享 Activity 和主 App 同进程同目录，一份文件两边都读写。读坏了改名 `.corrupt-<时间>` 留底再从空开始（工程约定第 11 条）。
 */
object ConvertJobLog {
    private val ser = ListSerializer(ConvertJobRecord.serializer())
    private val file get() = File(File(AppGraph.context.filesDir, "convert").apply { mkdirs() }, "jobs.json")

    @Synchronized
    fun all(): List<ConvertJobRecord> {
        val f = file
        if (!f.exists()) return emptyList()
        return runCatching { UmbraJson.decodeFromString(ser, f.readText()) }.getOrElse {
            runCatching { f.renameTo(File(f.parentFile, "jobs.json.corrupt-${System.currentTimeMillis()}")) }
            emptyList()
        }
    }

    @Synchronized
    private fun save(list: List<ConvertJobRecord>) {
        runCatching {
            val tmp = File(file.parentFile, "jobs.json.tmp")
            tmp.writeText(UmbraJson.encodeToString(ser, list))
            tmp.renameTo(file)
        }
    }

    fun remember(r: ConvertJobRecord) = save(ConvertRules.remember(r, all(), nowMs()))
    fun markSeen(id: String) = save(ConvertRules.markSeen(id, all()))
    fun record(id: String): ConvertJobRecord? = all().firstOrNull { it.id == id }
    fun nowMs() = System.currentTimeMillis()
}

class ConvertModel(
    val input: ConvertInput?,
    /** 主 App 从设备详情页进来时预选那台。 */
    private val preferDevice: String? = null,
    /** 「作业没了」那屏点［选文件］重转时，上一次选的目标格式（问到格子后 [ConvertRules.preselect] 认它）。 */
    preferTarget: String? = null,
    /** 从横条「去看看」进来：不选格式，直接看这件作业。 */
    private val resumeJob: String? = null,
) {
    enum class Screen { Pick, Run, Result }

    /** 第一屏（选格式）的状态。 */
    sealed interface Pick {
        data object Loading : Pick
        data object Asking : Pick
        data object Ready : Pick
        /** 它显示在线，但问了 60 秒没答复。 */
        data object NoReply : Pick
        data object NoComputer : Pick
        /** 文件超过 32 MB，根本没读进来。 */
        data object TooBig : Pick
        data class Failed(val why: String) : Pick
    }

    /** 三段进度每一段的状态（clock / 转圈 / check / x-circle）。 */
    enum class Step { Wait, Run, Done, Fail }

    /**
     * 服务端查不到这件作业（404）的两档（批次 272）：
     * - Expired：续看时第一次查就 404 —— 结束的作业只在内存里留 2 小时，人隔太久才回来。不是出错：clock、muted。
     * - Interrupted：转换途中轮询到 404 —— 只能是服务端重启丢了作业。alert-triangle、warningText。
     */
    enum class Gone { Expired, Interrupted }

    /** 结果屏。`file` = 成品在本机缓存目录里的位置（ok 时有）。 */
    data class Outcome(
        val ok: Boolean,
        val file: File? = null,
        val name: String,
        val size: Int? = null,
        val note: String? = null,
        val reason: String = "",
        val kind: ConvertKind = ConvertKind.Document,
        val gone: Gone? = null,
    )

    /** ［选文件］要带去下一次的东西：预选哪台电脑、哪个目标格式。 */
    data class Repick(val deviceId: String?, val to: String?)

    var screen by mutableStateOf(Screen.Pick); private set
    var pick by mutableStateOf<Pick>(Pick.Loading); private set
    val computers = mutableStateListOf<ConvertPC>()
    var deviceId by mutableStateOf<String?>(null); private set
    val targets = mutableStateListOf<ConvertTarget>()
    var chosen by mutableStateOf(preferTarget)

    val steps = mutableStateListOf(Step.Wait, Step.Wait, Step.Wait)
    var lost by mutableStateOf(false); private set
    /** 第二段从什么时候开始算「已用 N 秒」（作业的 created_ms）。 */
    var convertSinceMs by mutableStateOf<Long?>(null); private set
    var convertFraction by mutableStateOf<Double?>(null); private set
    var doneSize by mutableStateOf<Int?>(null); private set
    var uploadFraction by mutableStateOf<Float?>(null); private set
    var fetchFraction by mutableStateOf<Float?>(null); private set
    var outcome by mutableStateOf<Outcome?>(null); private set

    /** 结果页标题行「合同.docx › PDF」，续看时也要有：作业自己带的名字 / 目标 / 电脑名。 */
    var jobName by mutableStateOf(input?.name.orEmpty()); private set
    var jobTo by mutableStateOf(""); private set
    var jobDeviceName by mutableStateOf(""); private set

    var jobId: String? = null; private set
    private var runJob: Job? = null
    private var started = false
    /** 面板自己的协程：关面板时整个取消（服务端那件照常转完）。主线程上跑，状态只在这里改。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // MARK: 判断

    private val locked get() = AppGraph.prefs.signedOut

    val device: ConvertPC? get() = computers.firstOrNull { it.id == deviceId }
    val onlineCount: Int get() = computers.count { it.online }
    val available: List<ConvertTarget> get() = ConvertRules.ordered(targets.filter { it.available }) { it.to }
    val blocked: List<ConvertTarget> get() = ConvertRules.ordered(targets.filter { !it.available }) { it.to }
    val chosenTarget: ConvertTarget? get() = targets.firstOrNull { it.to == chosen && it.available }
    /** 这一件能不能从头再来（续看一件旧作业时手里没有原文件）。 */
    val hasSource: Boolean get() = input is ConvertInput.File

    /** 「作业没了」那屏［选文件］的预选：电脑取作业记录里那台，没有就用面板当前那台；格式取记录里的 `to`，没有就用选着的。 */
    val repick: Repick
        get() {
            val r = jobId?.let(ConvertJobLog::record)
            return Repick(r?.deviceId?.ifEmpty { null } ?: deviceId, r?.to?.ifEmpty { null } ?: chosen)
        }

    // MARK: 第一屏

    /** 打开面板：守卫 → 体积 → 问在线电脑 → 问能转什么。只跑一次（重组不重问）。 */
    fun start() {
        if (started) return
        started = true
        resumeJob?.let { resume(it); return }
        if (locked) { pick = Pick.Failed(convText(R.string.share_conv_signedOut)); return }
        if (input is ConvertInput.TooLarge) { pick = Pick.TooBig; return }
        scope.launch { loadComputers() }
    }

    /** 问在线的电脑（「再看一次」也走这里）。 */
    suspend fun loadComputers() {
        if (locked) { pick = Pick.Failed(convText(R.string.share_conv_signedOut)); return }
        pick = Pick.Loading
        when (val r = ConvertClient.onlineDevices()) {
            is ConvertReply.Ok -> {
                val online = r.value
                val offline = ConvertClient.offlineComputers().filter { o -> online.none { it.id == o.id } }
                computers.clear(); computers.addAll(online + offline)
                if (online.isEmpty()) { deviceId = null; pick = Pick.NoComputer; return }
                // 预选：从设备详情进来的那台在线就用它；原来选着的还在线就不换；否则第一台。
                if (preferDevice != null && online.any { it.id == preferDevice } && deviceId == null) deviceId = preferDevice
                else if (online.none { it.id == deviceId }) deviceId = online.first().id
                ask()
            }
            is ConvertReply.Rejected -> pick = Pick.Failed(r.detail ?: convText(R.string.share_conv_unreachable))
            is ConvertReply.Unreachable -> pick = Pick.Failed(convText(R.string.share_conv_unreachable))
        }
    }

    /** 换一台：格式清单是问电脑要的，换了电脑重新问一遍。离线的点不动。 */
    fun select(id: String) {
        if (id == deviceId || computers.firstOrNull { it.id == id }?.online != true) return
        deviceId = id
        scope.launch { ask() }
    }

    /** 问选着的那台能转成什么（「再问一次」也走这里）。 */
    suspend fun ask() {
        val d = device ?: return
        val name = input?.name ?: return
        pick = Pick.Asking
        targets.clear()
        when (val r = ConvertClient.targets(d.id, name)) {
            is ConvertReply.Ok -> {
                targets.addAll(r.value)
                chosen = ConvertRules.preselect(chosen, available.map { it.to })
                pick = Pick.Ready
            }
            is ConvertReply.Rejected -> {
                // 502 upstream_failed = 服务端等电脑等满了 60 秒 —— 那就是「没回话」。
                if (r.status == 502 || r.code == "upstream_failed") { pick = Pick.NoReply; return }
                // 422：要么那台刚刚掉线（重问一遍在线列表），要么电脑那边报了错（照原话说）。
                if (r.status == 422) {
                    val again = ConvertClient.onlineDevices()
                    if (again is ConvertReply.Ok && again.value.none { it.id == d.id }) { loadComputers(); return }
                }
                pick = Pick.Failed(r.detail ?: convText(R.string.share_conv_unreachable))
            }
            is ConvertReply.Unreachable -> pick = if (r.timedOut) Pick.NoReply else Pick.Failed(convText(R.string.share_conv_unreachable))
        }
    }

    fun launch(block: suspend () -> Unit) { scope.launch { block() } }

    // MARK: 第二屏

    /** 开始转换：传 → 开作业 → 等电脑 → 取回。 */
    fun convert() {
        val src = (input as? ConvertInput.File)?.src ?: return
        val d = device ?: return
        val t = chosenTarget ?: return
        if (locked) return
        runJob?.cancel()
        jobId = null
        jobName = src.name
        jobTo = t.label
        jobDeviceName = d.name
        steps[0] = Step.Run; steps[1] = Step.Wait; steps[2] = Step.Wait
        lost = false
        uploadFraction = null; fetchFraction = null; convertFraction = null; convertSinceMs = null; doneSize = null
        outcome = null
        screen = Screen.Run
        runJob = scope.launch { run(src, d, t.to) }
    }

    private suspend fun run(src: ConvertSource, d: ConvertPC, to: String) {
        val sent = ConvertClient.upload(src) { f -> scope.launch { uploadFraction = f } }
        if (!scope.isActive || runJob?.isCancelled == true) return
        val fileId = when (sent) {
            is ConvertReply.Ok -> sent.value
            is ConvertReply.Rejected -> {
                finish(if (sent.status == 413) convText(R.string.share_conv_tooBigBody, ConvertRules.MAX_MB) else convText(R.string.share_conv_uploadFailed)); return
            }
            is ConvertReply.Unreachable -> { finish(convText(R.string.share_conv_uploadFailed)); return }
        }
        uploadFraction = 1f
        steps[0] = Step.Done
        when (val started = ConvertClient.start(d.id, fileId, src.name, to)) {
            is ConvertReply.Ok -> {
                val job = started.value
                jobId = job.id
                ConvertJobLog.remember(ConvertJobRecord(job.id, d.id, d.name, src.name, to, job.createdMs ?: ConvertJobLog.nowMs()))
                follow(job)
            }
            // 422 = 那台刚刚离线；409 = 已经有 5 件在转（服务端那句话就是下一步）。
            is ConvertReply.Rejected -> if (started.status == 422) markLost() else finish(started.detail ?: convText(R.string.share_conv_failed))
            is ConvertReply.Unreachable -> finish(convText(R.string.share_conv_unreachable))
        }
    }

    /** 盯着一件作业到它结束（新开的、或从横条续看的）。 */
    private suspend fun follow(first: ConvertJob) {
        var job = first
        steps[1] = Step.Run
        convertSinceMs = job.createdMs ?: System.currentTimeMillis()
        var misses = 0
        var round = 0
        while (job.status == "running") {
            delay(ConvertRules.POLL_MS)
            when (val r = ConvertClient.job(job.id)) {
                is ConvertReply.Ok -> { job = r.value; convertFraction = ConvertRules.convertFraction(r.value.progress) }
                // 转换途中查不到了：刚刚还在的作业突然没了，只能是服务端重启丢了内存里的作业（批次 272 B 档）。
                is ConvertReply.Rejected -> if (r.status == 404) { finish(convText(R.string.share_conv_interruptedBody), Gone.Interrupted); return }
                is ConvertReply.Unreachable -> Unit   // 一次没问到不算什么，下一轮再问
            }
            round++
            val dev = job.deviceId ?: deviceId
            if (job.status == "running" && round % 2 == 0 && dev != null) {
                val online = ConvertClient.onlineDevices()
                if (online is ConvertReply.Ok) {
                    misses = if (online.value.any { it.id == dev }) 0 else misses + 1
                    if (misses >= ConvertRules.LOST_AFTER_MISSES) { markLost(); return }
                }
            }
        }
        val res = job.result
        if (job.status != "done" || res == null) {
            steps[1] = Step.Fail
            finish(job.error?.takeIf { it.isNotEmpty() } ?: convText(R.string.share_conv_failed))
            return
        }
        steps[1] = Step.Done
        doneSize = res.size
        fetch(res, job.to ?: jobTo)
    }

    private suspend fun fetch(res: ConvertJob.Result, to: String) {
        steps[2] = Step.Run
        fetchFraction = 0f
        val bytes = ConvertClient.fetch(res.fileId) { f -> scope.launch { fetchFraction = f } }
        if (bytes == null) { steps[2] = Step.Fail; finish(convText(R.string.share_conv_fetchFailed)); return }
        val name = ConvertRules.localName(res.name.orEmpty(), to.lowercase())
        // 落在 cacheDir/convert-out/<uuid>/ 下：FileProvider（${applicationId}.files）只开 cacheDir，分享 / 另存都从这里交出去。
        val saved = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(AppGraph.context.cacheDir, "convert-out/${UUID.randomUUID()}").apply { mkdirs() }
                File(dir, name).apply { writeBytes(bytes) }
            }.getOrNull()
        }
        if (saved == null) { steps[2] = Step.Fail; finish(convText(R.string.share_conv_fetchFailed)); return }
        fetchFraction = 1f
        steps[2] = Step.Done
        outcome = Outcome(true, saved, name, res.size ?: bytes.size, res.note, kind = ConvertRules.kind(name))
        screen = Screen.Result
        jobId?.let(ConvertJobLog::markSeen)
    }

    /** 没转成：进结果屏说一句原因。 */
    private fun finish(reason: String, gone: Gone? = null) {
        outcome = Outcome(false, null, jobName, reason = reason, gone = gone)
        screen = Screen.Result
        jobId?.let(ConvertJobLog::markSeen)
    }

    /** 电脑中途断开：第二段 x-circle，留在进度屏给「重试」。 */
    private fun markLost() {
        lost = true
        steps[1] = Step.Fail
        steps[2] = Step.Wait
    }

    /** 「停止」：整个作废。正在传的那一趟当场掐掉；作业已经开了就请服务端删掉。回到第一屏：格子还在，可以换一个再来。 */
    fun stop() {
        runJob?.cancel()
        runJob = null
        jobId?.let { id ->
            ConvertJobLog.markSeen(id)
            AppGraph.scope.launch { ConvertClient.cancel(id) }
        }
        jobId = null
        if (hasSource) screen = Screen.Pick else finish(convText(R.string.share_conv_stopped))
    }

    /** 「重试」：断开那一档 → 先看那台回来没有，回来了从头再来（原来那件请服务端作废）；没原文件（续看）就再问一次作业。 */
    fun retry() {
        if (hasSource) {
            val old = jobId
            scope.launch {
                val dev = deviceId
                if (lost && dev != null) {
                    val online = ConvertClient.onlineDevices()
                    // 那台还没回来就不白传一趟：留在断开那一档（稿：「电脑重新上线后点重试」）。
                    if (online is ConvertReply.Ok && online.value.none { it.id == dev }) return@launch
                }
                old?.let { ConvertClient.cancel(it) }
                convert()
            }
        } else jobId?.let(::resume)
    }

    /** 结果屏「换个格式」：回第一屏，格子还在。 */
    fun otherFormat() {
        screen = Screen.Pick
        if (targets.isEmpty()) scope.launch { loadComputers() }
    }

    /** 面板被关掉：不再轮询（服务端那件照常转完，回来时横条会说）。 */
    fun close() {
        runJob?.cancel()
        runJob = null
        scope.cancel()
    }

    // MARK: 续看（横条）

    /** 从横条「去看看」进来：问这件作业现在怎样，还在转就接着盯，转好了就取回。 */
    fun resume(id: String) {
        runJob?.cancel()
        started = true
        jobId = id
        lost = false
        outcome = null
        screen = Screen.Run
        steps[0] = Step.Done; steps[1] = Step.Run; steps[2] = Step.Wait
        uploadFraction = 1f
        ConvertJobLog.record(id)?.let { jobName = it.name; jobTo = it.to.uppercase(); jobDeviceName = it.deviceName }
        runJob = scope.launch {
            when (val r = ConvertClient.job(id)) {
                is ConvertReply.Ok -> {
                    val job = r.value
                    jobName = job.name ?: jobName
                    jobTo = (job.to ?: jobTo).uppercase()
                    jobDeviceName = job.deviceName ?: jobDeviceName
                    deviceId = job.deviceId ?: deviceId
                    follow(job)
                }
                // 续看第一次查就 404：多半是转好之后隔太久了（批次 272 A 档），不是「服务重启丢了」。
                is ConvertReply.Rejected -> finish(convText(if (r.status == 404) R.string.share_conv_goneBody else R.string.share_conv_unreachable), if (r.status == 404) Gone.Expired else null)
                is ConvertReply.Unreachable -> finish(convText(R.string.share_conv_unreachable))
            }
        }
    }
}

/**
 * 存相册（Pictures/Umbra 或 Movies/Umbra）。Android 10+ 走 MediaStore 不要权限；9 上写公共目录要存储权限 ——
 * 没给就存不进，面板如实说「没存进相册」（同聊天存图那一处的做法）。
 */
internal suspend fun saveToGallery(file: File, kind: ConvertKind): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val video = kind == ConvertKind.Video
        val ext = ConvertRules.ext(file.name)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: if (video) "video/mp4" else "image/jpeg"
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val dirName = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val cr = AppGraph.context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dirName/Umbra")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val d = File(Environment.getExternalStoragePublicDirectory(dirName), "Umbra").apply { mkdirs() }
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, File(d, file.name).absolutePath)
            }
        }
        val uri = cr.insert(collection, values) ?: return@runCatching false
        cr.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return@runCatching false
        if (Build.VERSION.SDK_INT >= 29) cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        true
    }.getOrDefault(false)
}
