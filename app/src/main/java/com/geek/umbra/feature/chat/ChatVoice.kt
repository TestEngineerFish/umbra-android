// 按住说话（iOS: Features/Chat/VoiceInput.swift + SpeechRecognizer.swift）：按住录音 → 上滑到左边取消、右边转文字、松开发送。
//
// 产物是**文字**不是音频（服务端没有语音消息类型）：松开发送 = 把识别出的文字当普通消息发；松开转文字 = 填进输入框；取消 = 丢弃。
// 平台替换：iOS SFSpeechRecognizer + AVAudioEngine 的 RMS → Android SpeechRecognizer（partialResults + onRmsChanged）。
// ⛔ 没有系统语音识别服务的机型（多数国行无 GMS）SpeechRecognizer.isRecognitionAvailable 为 false：麦克风按钮置灰并说明。
// 差异：iOS 松手时直接取当下的识别文字；Android 的识别结果在 stopListening 之后才定稿，这里松手后最多再等 1.5 秒拿最终结果，
// 等不到就用最后一份中间结果。
package com.geek.umbra.feature.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraDimens
import com.geek.umbra.designsystem.UmbraFixedColors
import com.geek.umbra.designsystem.UmbraIcon
import com.geek.umbra.designsystem.UmbraIconPaths
import com.geek.umbra.designsystem.UmbraMotion
import com.geek.umbra.designsystem.UmbraTheme
import com.geek.umbra.designsystem.UmbraType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 录音 + 实时识别。状态都是 Compose 的 mutableState（页面直接读），所有回调都在主线程（SpeechRecognizer 要求）。
 */
class HoldRecorder(private val context: Context) {
    enum class Zone { Send, Cancel, Text }

    sealed interface Result {
        data class Send(val text: String) : Result
        data class ToText(val text: String) : Result
        data object Cancel : Result
        data object TooShort : Result
        data object Unavailable : Result
    }

    var active by mutableStateOf(false); private set
    /** 0.1 秒一跳的真计时（界面刷新与计时同一个源）。 */
    var tenths by mutableIntStateOf(0); private set
    var text by mutableStateOf(""); private set
    /** 26 根波形柱的高度系数（0…1），左进右出 —— 真实音量（onRmsChanged），不是编出来的固定高度。 */
    var levels by mutableStateOf(List(BAR_COUNT) { 0.12f }); private set
    var zone by mutableStateOf(Zone.Send)

    /** 浮层里「取消 / 转文字」两个圆钮的实测窗口坐标，落区命中判定用。 */
    var cancelZone: Rect = Rect.Zero
    var textZone: Rect = Rect.Zero
    /** 超时自动收尾 / 识别不可用时通知外面。 */
    var onAutoFinish: ((Result) -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private var ticker: Job? = null
    private var finalText: String? = null
    private var finalWaiter: ((String) -> Unit)? = null

    companion object {
        const val BAR_COUNT = 26
        /** 最长 60 秒，到点按「发送」收尾。 */
        const val MAX_TENTHS = 600
        /** 短于 0.4 秒当误触。 */
        const val MIN_TENTHS = 4

        /** 这台手机有没有系统语音识别服务（国行无 GMS 的多半没有）。 */
        fun available(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)
    }

    val durationText: String get() { val s = tenths / 10; return "%d:%02d".format(s / 60, s % 60) }

    /** 识别语言跟 App 界面语言走（iOS LanguageManager.speechLocaleIdentifier）。 */
    private fun language(): String =
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags().startsWith("en")) "en-US" else "zh-CN"

    fun start() {
        if (active) return
        active = true; tenths = 0; text = ""; zone = Zone.Send; finalText = null; finalWaiter = null
        levels = List(BAR_COUNT) { 0.12f }
        startTicker()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { teardown(); onAutoFinish?.invoke(Result.Unavailable); return }
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (r == null) { teardown(); onAutoFinish?.invoke(Result.Unavailable); return }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                if (!active) return
                // onRmsChanged 大约 -2…10 dB，线性映射看不出起伏：归一化到 0…1，留一点底（全静音也看得出有柱子）
                val norm = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                levels = levels.drop(1) + maxOf(0.12f, norm)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                // 识别出错（没听到 / 网络）：停掉音频，已经有的文字留着；在等最终结果的那一趟立即用它收尾
                deliverFinal(text)
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && active) { teardown(); onAutoFinish?.invoke(Result.Unavailable) }
            }
            override fun onResults(results: Bundle?) {
                val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!best.isNullOrBlank()) text = best
                deliverFinal(text)
            }
            override fun onPartialResults(partial: Bundle?) {
                val best = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!best.isNullOrBlank()) text = best
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // 按住期间别因为停顿就自己收：给足静音时长
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 60_000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 60_000L)
        runCatching { r.startListening(intent) }.onFailure { teardown(); onAutoFinish?.invoke(Result.Unavailable) }
    }

    private fun deliverFinal(t: String) {
        finalText = t
        finalWaiter?.let { w -> finalWaiter = null; w(t) }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = AppGraph.scope.launch {
            while (active) {
                delay(100)
                if (!active) return@launch
                tenths++
                if (tenths >= MAX_TENTHS) { stop(Zone.Send) { onAutoFinish?.invoke(it) }; return@launch }
            }
        }
    }

    /**
     * 松手。取消立刻收；太短判误触；否则停听、等最终结果（最多 1.5 秒）再交出去。一个字都没识别出来也按「太短」说。
     */
    fun stop(z: Zone, done: (Result) -> Unit) {
        if (!active) { done(Result.Cancel); return }
        val t = tenths
        if (z == Zone.Cancel) { teardown(); done(Result.Cancel); return }
        if (t < MIN_TENTHS) { teardown(); done(Result.TooShort); return }
        ticker?.cancel(); ticker = null
        active = false
        var finished = false
        fun finish(captured: String) {
            if (finished) return
            finished = true
            teardown()
            val s = captured.trim()
            done(if (s.isEmpty()) Result.TooShort else if (z == Zone.Text) Result.ToText(s) else Result.Send(s))
        }
        val already = finalText
        if (already != null) { finish(already); return }
        finalWaiter = { finish(it) }
        runCatching { recognizer?.stopListening() }
        AppGraph.scope.launch { delay(1500); finish(text) }
    }

    /** 中断（切后台 / 页面消失）：一律丢弃，不猜用户想发。 */
    fun abort() {
        if (!active && recognizer == null) return
        teardown()
    }

    private fun teardown() {
        ticker?.cancel(); ticker = null
        finalWaiter = null
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        active = false
    }
}

/**
 * 按住说话的浮层（iOS UmbraVoiceHoldOverlay）。遮罩 + 计时 + 识别文字 + 橙色波形气泡 + 底板（取消 / 提示 / 转文字两个 56 圆钮 + 状态条）。
 * 配色随主题：深色 = 近黑底板 + 白字；浅色 = 白底板 + 深字、遮罩减半。
 */
@Composable
fun VoiceHoldOverlay(rec: HoldRecorder) {
    val c = UmbraTheme.colors
    val dark = c.isDark
    val primaryFg = if (dark) Color.White else c.text
    val secondaryFg = if (dark) Color.White.copy(alpha = 0.72f) else c.muted
    val tertiaryFg = if (dark) Color.White.copy(alpha = 0.5f) else c.faint
    val fillSoft = if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.07f)
    val fillFaint = if (dark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.05f)
    val panelBg = if (dark) c.nav else c.card
    Column(Modifier.fillMaxSize().background(UmbraFixedColors.viewerBg.copy(alpha = if (dark) 0.62f else 0.32f))) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 26.dp, vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 红点 1.1 秒闪一次 —— 和计时同一个源
                Box(Modifier.size(7.dp).clip(CircleShape).background(c.danger.copy(alpha = if (rec.tenths % 11 < 6) 1f else 0.25f)))
                Spacer(Modifier.width(UmbraDimens.sp2))
                Text(rec.durationText, color = if (dark) Color.White.copy(alpha = 0.6f) else c.muted, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = UmbraType.weight560))
            }
            if (rec.text.isNotEmpty()) {
                Spacer(Modifier.height(UmbraDimens.sp5))
                Text(
                    rec.text, color = primaryFg, textAlign = TextAlign.Center, style = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
                    modifier = Modifier.widthIn(max = 288.dp).clip(RoundedCornerShape(13.dp)).background(fillFaint).padding(horizontal = 15.dp, vertical = UmbraDimens.sp4),
                )
            }
            Spacer(Modifier.height(UmbraDimens.sp5))
            Row(
                Modifier.clip(RoundedCornerShape(15.dp)).background(c.orange).padding(horizontal = 22.dp, vertical = 18.dp).height(28.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom,
            ) {
                rec.levels.forEach { v ->
                    Box(Modifier.width(3.dp).height(maxOf(3f, 28f * v).dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.92f)))
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = UmbraDimens.radiusSheet, topEnd = UmbraDimens.radiusSheet)).background(panelBg)
                .navigationBarsPadding().padding(start = 26.dp, end = 26.dp, top = UmbraDimens.sp7, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(UmbraDimens.sp6),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                DropZone(UmbraIconPaths.x, "取消", rec.zone == HoldRecorder.Zone.Cancel, c.danger, fillSoft, secondaryFg, primaryFg, tertiaryFg) { rec.cancelZone = it }
                Text(
                    if (rec.zone == HoldRecorder.Zone.Send) "上滑到两边可以取消或转文字" else "", color = tertiaryFg, textAlign = TextAlign.Center,
                    style = TextStyle(fontSize = 12.sp, lineHeight = 19.sp), modifier = Modifier.weight(1f).padding(top = 16.dp),
                )
                DropZone(UmbraIconPaths.sortLines, "转文字", rec.zone == HoldRecorder.Zone.Text, c.orange, fillSoft, secondaryFg, primaryFg, tertiaryFg) { rec.textZone = it }
            }
            Row(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(if (rec.zone == HoldRecorder.Zone.Send) fillSoft else Color.Transparent),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                val fg = if (rec.zone == HoldRecorder.Zone.Send) primaryFg else tertiaryFg
                UmbraIcon(UmbraIconPaths.mic, fg, size = 19.dp, strokeWidth = 2f)
                Spacer(Modifier.width(UmbraDimens.sp3))
                Text(
                    when (rec.zone) { HoldRecorder.Zone.Cancel -> "松开 取消"; HoldRecorder.Zone.Text -> "松开 转文字"; HoldRecorder.Zone.Send -> "松开 发送" },
                    color = fg, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

/** 一个落区：56 圆钮 + 标签，选中时放大到 1.08；把整个落区的窗口坐标交回去给命中判定。 */
@Composable
private fun DropZone(icon: String, label: String, on: Boolean, onColor: Color, fillSoft: Color, secondaryFg: Color, primaryFg: Color, tertiaryFg: Color, report: (Rect) -> Unit) {
    val s by animateFloatAsState(if (on) 1.08f else 1f, tween(UmbraMotion.tint), label = "zone")
    Column(Modifier.width(74.dp).onGloballyPositioned { report(it.boundsInWindow()) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(56.dp).scale(s).clip(CircleShape).background(if (on) onColor else fillSoft), contentAlignment = Alignment.Center) {
            UmbraIcon(icon, if (on) Color.White else secondaryFg, size = 22.dp, strokeWidth = 2.1f)
        }
        Text(label, color = if (on) primaryFg else tertiaryFg, style = TextStyle(fontSize = 12.5.sp, fontWeight = UmbraType.weight560))
    }
}
