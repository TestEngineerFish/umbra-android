// 「用电脑转换」在主 App 里的入口与回来的路（设计侧批次 269 §四 / 272，照 iOS `Features/Convert/ConvertEntry.swift`）。
//
// - 设备详情页一行「用这台电脑转换文件」→ 系统文件选择器 → 同一张面板（[ConvertPanel]），电脑已经选好。离线时整行置灰。
// - 回到 App 时设备详情页顶上挂一条「合同.pdf 转好了」+［去看看］：服务端 `GET /convert/jobs`（这个人的全部作业）×
//   本机记着的「我发起过的」id（[ConvertJobLog]，分享里开的也在里面）。进页面、回前台都刷新。
// - iOS 另有「点推送 → 落到那台电脑的详情页、直接打开那件」：Android 今天没有服务端推送（FCM 待服务端），那一截没接；
//   判据 [ConvertRules.pushJob] 已备好，接上推送时在通知点击里调 [ConvertCenter.openFromPush] 即可。
package com.geek.umbra.feature.convert

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.geek.umbra.R
import com.geek.umbra.app.AppForeground
import com.geek.umbra.app.AppGraph
import com.geek.umbra.model.KnownDevice
import com.geek.umbra.navigation.Route
import com.geek.umbra.navigation.UmbraSheet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 横条的状态。单例：分享 Activity 开的作业、回前台、进设备详情页都要更新同一份。 */
object ConvertCenter {
    private val _banners = MutableStateFlow<Map<String, ConvertBanner>>(emptyMap())
    /** 每台电脑顶上那一条（只挂这台手机发起的、结束了、没看过的最新一件）。 */
    val banners: StateFlow<Map<String, ConvertBanner>> = _banners
    /** 点推送进来、等设备详情页接手打开的那一件（Android 今天没有推送，备着）。 */
    val pendingJob = MutableStateFlow<String?>(null)
    private var installed = false

    /** 回前台时刷新横条（只装一次）。 */
    fun install() {
        if (installed) return
        installed = true
        AppForeground.listeners += { fg -> if (fg) AppGraph.scope.launch { refresh() } }
    }

    /** 问一次服务端的作业表，重算各台电脑的横条。未登录不问。 */
    suspend fun refresh() {
        if (AppGraph.prefs.signedOut) { _banners.value = emptyMap(); return }
        val records = ConvertJobLog.all().filter { !it.seen }
        if (records.isEmpty()) { _banners.value = emptyMap(); return }
        val jobs = ConvertClient.jobs() ?: return
        val briefs = jobs.map { ConvertJobBrief(it.id, it.status, it.deviceId.orEmpty(), it.name.orEmpty(), it.result?.name, it.updatedMs ?: 0) }
        _banners.value = records.map { it.deviceId }.toSet().mapNotNull { dev -> ConvertRules.banner(records, briefs, dev)?.let { dev to it } }.toMap()
    }

    /** 横条点过 / 结果看过：标成看过、收起那一条。 */
    fun seen(jobId: String) {
        ConvertJobLog.markSeen(jobId)
        _banners.value = _banners.value.filterValues { it.jobId != jobId }
    }

    /** 点了「转换结束」的推送：记下要打开的那一件，跳到它那台电脑的详情页（接上 FCM 时用）。 */
    fun openFromPush(jobId: String) {
        pendingJob.value = jobId
        ConvertJobLog.record(jobId)?.deviceId?.takeIf { it.isNotEmpty() }?.let { AppGraph.router.jump(Route.DeviceDetail(it)); return }
        AppGraph.scope.launch {
            (ConvertClient.job(jobId) as? ConvertReply.Ok)?.value?.deviceId?.let { AppGraph.router.jump(Route.DeviceDetail(it)) }
        }
    }
}

/** 设备详情页上用的入口：选文件、弹面板、续看。由 [rememberConvertEntry] 建。 */
class ConvertEntry internal constructor(private val device: KnownDevice, private val pickLauncher: () -> Unit) {
    /** 「作业没了」那屏点了［选文件］：文件选择器开着的这一趟要带的预选。选完 / 取消都清掉 —— 不清的话下一次正常选文件会莫名其妙预选上一件的格式。 */
    internal var repick: ConvertModel.Repick? = null

    /** 「用这台电脑转换文件」那一行：先开系统文件选择器。 */
    fun pickFile() = pickLauncher()

    /** 弹面板（选好的文件 / 续看的作业）。 */
    fun present(input: ConvertInput?, resumeJob: String? = null, preferDevice: String? = null, preferTarget: String? = null) {
        val model = ConvertModel(input, preferDevice ?: device.device_id, preferTarget, resumeJob)
        AppGraph.router.present(UmbraSheet("convert-${System.nanoTime()}") { dismiss ->
            ConvertPanel(
                model, onClose = dismiss, modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f),
                // 「文件太大」→ 关面板、开远程连接的动作表（远程控制的文件面板能直接把文件传到电脑上）。
                onOpenRemote = { dismiss(); com.geek.umbra.feature.remote.RemoteLauncher.presentConnect(device) },
                // 「作业没了」的［选文件］：和设备详情那一行选文件是同一步，电脑与格式预选好。
                onPickFile = { r -> repick = r; dismiss(); pickLauncher() },
            )
        })
    }
}

/**
 * 设备详情页挂一个：文件选择器（OpenDocument，任何类型）+ 进页面 / 回前台刷新横条 + 推送落点（备着）。
 * 选择器要在组合里注册，所以做成 remember 出来的一个对象，设备详情页那一行和面板里的［选文件］共用。
 */
@Composable
fun rememberConvertEntry(device: KnownDevice): ConvertEntry {
    val ctx = LocalContext.current
    var holder: ConvertEntry? = null
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val e = holder ?: return@rememberLauncherForActivityResult
        val r = e.repick
        e.repick = null
        if (uri == null) return@rememberLauncherForActivityResult
        AppGraph.scope.launch {
            val input = readConvertInput(ctx, uri)
            if (input == null) AppGraph.router.showToast(ctx.getString(R.string.convert_unreadable))
            else e.present(input, preferDevice = r?.deviceId, preferTarget = r?.to)
        }
    }
    val entry = remember(device.device_id) { ConvertEntry(device) { launcher.launch(arrayOf("*/*")) } }
    holder = entry
    val pending by ConvertCenter.pendingJob.collectAsState()
    LaunchedEffect(Unit) { ConvertCenter.install(); ConvertCenter.refresh() }
    LaunchedEffect(pending) {
        val job = pending ?: return@LaunchedEffect
        if ((ConvertJobLog.record(job)?.deviceId ?: device.device_id) != device.device_id) return@LaunchedEffect
        ConvertCenter.pendingJob.value = null
        ConvertCenter.seen(job)
        entry.present(null, resumeJob = job)
    }
    return entry
}
