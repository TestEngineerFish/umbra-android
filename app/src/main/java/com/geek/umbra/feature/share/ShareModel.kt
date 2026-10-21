// 分享面板的状态与「保存」那一趟：守卫 → 传图 → 建灵感 → **等结果** → 成功才关面板。
// 对应 iOS ShareExtension/ShareModel.swift + ShareClient.swift。
//
// 三件不能错的事（与「记个灵感」页外入口同一套，判据也是同一份）：
// 1. **先问会话守卫**：未登录时**一个请求都不发**，面板里说「先打开 Umbra 登录」。请求闸（UmbraHttp 第一句）是兜底不是主闸。
// 2. **等结果再关**（#60）：失败一律留在面板里如实说，内容原样还在。面板一关人就回到原来的 App，没有草稿可找。
// 3. **来源报端标识**：请求体只经 ShareInspRules.createBody（意图那一份 + atts），source_channel = UmbraUrl.CLIENT_SRC。
//
// Android 差异：分享 Activity 和主 App 同进程，网络直接走 AppGraph.http / Uploads（iOS 扩展是独立进程，自己写了一段最小网络）。
package com.geek.umbra.feature.share

import android.content.ContentResolver
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geek.umbra.app.AppGraph
import com.geek.umbra.feature.convert.ConvertInput
import com.geek.umbra.feature.convert.ConvertModel
import com.geek.umbra.feature.convert.ConvertSource
import com.geek.umbra.feature.convert.readConvertInput
import com.geek.umbra.feature.inspiration.InspCreateOutcome
import com.geek.umbra.feature.inspiration.InspIntentGate
import com.geek.umbra.feature.inspiration.InspIntentRules
import com.geek.umbra.net.HttpResult
import com.geek.umbra.net.Uploads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class ShareModel : ViewModel() {
    /** 正文：预填分享进来的文字 + 链接，人可以改。 */
    var text by mutableStateOf("")
    /** 「帮我查查」。默认不查（与 App 内新建、意图同一个取向：每条都查会烧 token）。 */
    var research by mutableStateOf(false)
    val images = mutableStateListOf<ShareImage>()
    /** 超出上限没带上的张数（只用于那一句提示）。 */
    var dropped by mutableStateOf(0); private set
    var loading by mutableStateOf(true); private set
    var saving by mutableStateOf(false); private set
    /** 失败时那一句的 key（留在面板里）。null = 不出。 */
    var messageKey by mutableStateOf<String?>(null); private set
    /** 被 413 拒掉的那一张（描一圈，人才知道「去掉那张」是哪张）。 */
    var tooLargeId by mutableStateOf<Long?>(null); private set
    /** 存上了 → 面板该关了。 */
    var finished by mutableStateOf(false); private set

    /**
     * 「用电脑转换」要转的那一件（iOS ShareModel.convertInput）：分享进来的文件（太大的只记名字大小），没有文件就是第一张图。
     * null = 这次不能转（只有文字 / 链接）。
     */
    var convertInput by mutableStateOf<ConvertInput?>(null); private set
    /** 分享进来的是**文件**（不是图 / 文字）：这时只能转换，灵感那一页不出（批次 269 §四）。 */
    var fileOnly by mutableStateOf(false); private set
    /** 转换面板的状态：放在 ViewModel 上，转屏时面板重建、作业不丢。 */
    private var convert: ConvertModel? = null

    fun convertModel(): ConvertModel? = convert ?: convertInput?.let { ConvertModel(it).also { m -> convert = m } }

    /** 面板真关（Activity 结束）时才停轮询；转屏 / 切到「记灵感」再切回来都不停。 */
    override fun onCleared() {
        convert?.close()
    }

    /** 已经传上去的图：图的 id → file_id。重试时不再传一遍（否则服务端多一个没人引用的孤儿文件）。按 id 不按下标。 */
    private val uploaded = mutableMapOf<Long, String>()
    private var started = false

    /** 守卫落下的「未登录」标记。**每次用到都现读**：面板开着的时候主 App 那边可能刚登出 / 登录。 */
    val locked: Boolean get() = AppGraph.prefs.signedOut

    /**
     * 读分享进来的东西、预填面板。未登录时**照样预填**（不发请求，只是让人看到自己分享了什么），
     * 但当场亮出那一句，免得他打完字才发现记不上。
     */
    fun load(cr: ContentResolver, intent: Intent) {
        if (started) return
        started = true
        viewModelScope.launch {
            val got = runCatching { ShareIntake.collect(cr, intent) }.getOrDefault(ShareIntakeResult())
            text = ShareInspRules.composeText(got.texts, got.urls)
            images.addAll(got.images)
            dropped = got.dropped
            val file = got.file?.let { readConvertInput(AppGraph.context, it) }
            fileOnly = file != null
            convertInput = file ?: got.images.firstOrNull()?.let { ConvertInput.File(ConvertSource(it.name, it.data)) }
            loading = false
            if (locked) messageKey = ShareInspRules.reply(InspIntentGate.SignedOut).key
        }
    }

    /** 去掉一张图（413 那一句叫人做的事，也是人改主意时的出口）。 */
    fun remove(id: Long) {
        images.removeAll { it.id == id }
        uploaded.remove(id)
        if (tooLargeId == id) { tooLargeId = null; messageKey = null }
    }

    /** 保存：守卫 → 逐张传图 → 建灵感 → 成功才关。 */
    fun save() {
        if (saving || loading) return
        // 第一句就问守卫：未登录时下面的请求一条都不该出现（判据 ShareInspRules.gate，用例钉着）。
        val gate = ShareInspRules.gate(locked, text, images.size)
        if (gate !is InspIntentGate.Send) { messageKey = ShareInspRules.reply(gate).key; return }
        saving = true
        messageKey = null
        tooLargeId = null
        viewModelScope.launch {
            try {
                val outcome = run(gate.text)
                val reply = ShareInspRules.reply(outcome)
                if (reply.ok) finished = true else messageKey = reply.key
            } finally {
                saving = false
            }
        }
    }

    /** 真正发请求的那一段。返回整条的结果（传图失败就停在那一档）。 */
    private suspend fun run(raw: String): ShareSaveOutcome {
        // 地址空、或拼不出带主机的 URL → 「没配」，不是「连不上」（同 iOS Endpoint.url）。也挡住空地址时 OkHttp 抛异常。
        if (AppGraph.prefs.serverUrl.toHttpUrlOrNull() == null) return ShareSaveOutcome.Create(InspCreateOutcome.NotConfigured)
        val ids = mutableListOf<String>()
        for (img in images.toList()) {
            uploaded[img.id]?.let { ids += it; continue }
            // 转码（HEIC → JPEG）是重活，放到主线程外。
            val (data, name) = withContext(Dispatchers.Default) { ShareIntake.prepareForUpload(img) }
            val (resp, err) = Uploads.upload(data, name)
            if (resp != null) {
                uploaded[img.id] = resp.file_id
                ids += resp.file_id
            } else {
                val o = ShareInspRules.uploadOutcome(err ?: com.geek.umbra.net.UploadError.Server)
                if (o == ShareSaveOutcome.ImageTooLarge) tooLargeId = img.id
                return o
            }
        }
        val body = ShareInspRules.createBody(raw, research, ids).toString().toRequestBody("application/json".toMediaType())
        return when (val r = AppGraph.http.raw("POST", "/inspirations", body = body, timeoutMs = InspIntentRules.TIMEOUT_MS)) {
            is HttpResult.Ok -> ShareInspRules.createOutcome(r.status)
            is HttpResult.Fail -> ShareInspRules.createOutcome(r.status)
            // 统一出口把传输层异常收成了 Offline（拿不到异常种类）：照 iOS 分享那张表，说不准也折回「连不上」。
            is HttpResult.Offline -> ShareSaveOutcome.Create(InspCreateOutcome.Offline)
            // 请求闸拦下 = 未登录（面板开着时主 App 那边登出了）。
            is HttpResult.Blocked -> ShareSaveOutcome.Create(InspCreateOutcome.SignedOut)
        }
    }
}
