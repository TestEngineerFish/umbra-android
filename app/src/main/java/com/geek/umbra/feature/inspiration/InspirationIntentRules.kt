// 「记个灵感」页外入口的纯判据（iOS: Features/Inspiration/InspirationIntentRules.swift，idea umbra-ios#24）：
// 放不放行、请求体里带什么、回包 → 哪一档结果、每一档说哪句、算成功还是失败。
// iOS 那边是 App Intent（Siri / 快捷指令）；Android 对应的 App Shortcuts / 桌面快捷方式下一波做，判据先移植好。
// 纯 Kotlin，配 JUnit 用例（InspirationIntentRulesTest.kt）。文案只给 key（"intent.insp.x" ↔ R.string.intent_insp_x）。
//
// 为什么要单拎出来：页外入口没有页面，结果只剩那一句话 —— 失败时仍然说「记下了」，自动化照常往下走，
// 而这条灵感一个字都没存。这件事只有失败时才看得出来，所以写成判据、钉用例。
package com.geek.umbra.feature.inspiration

import com.geek.umbra.net.UmbraUrl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/** 执行前那一道：放不放、为什么不放。 */
sealed interface InspIntentGate {
    data class Send(val text: String) : InspIntentGate
    /** 守卫说未登录：一个请求都不发。 */
    data object SignedOut : InspIntentGate
    /** 正文全是空白。 */
    data object Empty : InspIntentGate
}

/** 一次创建请求的结果。分得这么细是为了那一句话 —— 后半句告诉人该去做什么。 */
sealed interface InspCreateOutcome {
    data object Saved : InspCreateOutcome
    data object SignedOut : InspCreateOutcome
    data object NotConfigured : InspCreateOutcome
    /** 请求**没发出去**（断网、DNS、连不上主机、TLS）：服务端一定没收到。 */
    data object Offline : InspCreateOutcome
    /** 请求可能已经到了服务端、只是回应没回来（超时、中途断了、被取消）—— **这条可能已经记下了**。 */
    data object Uncertain : InspCreateOutcome
    data class Rejected(val status: Int) : InspCreateOutcome
    data class ServerError(val status: Int) : InspCreateOutcome
    data object Failed : InspCreateOutcome
}

/** 最后交出去的东西：成没成 + 那一句的 key。**ok == false 时入口必须报失败**，不能报成功。 */
data class InspIntentReply(val ok: Boolean, val key: String)

/**
 * 传输层出错的种类（iOS 按 URLError.code 分；Android 由 [InspIntentRules.errorKind] 从异常类型映射）。
 * 分档的界线是**请求体有没有可能已经到了服务端**。
 */
enum class InspTransportError { BadUrl, TimedOut, ConnectionLost, Cancelled, NotConnected, CannotConnect, CannotFindHost, Tls, SignedOutBlocked, Other }

object InspIntentRules {
    /**
     * 页外入口上行时自报的来源。iOS 报 "ios"（服务端白名单 manual / ios）；Android 报自己的端标识，
     * 服务端认不认见 docs/Android开发文档.md §六 —— 认不得时改 UmbraUrl.CLIENT_SRC 一处。
     * **只有页外入口报它**：App 内新建不传，服务端照旧落 manual。
     */
    val sourceChannel: String get() = UmbraUrl.CLIENT_SRC

    /** 页外入口那一条请求的超时：默认 60 秒太长，人以为卡住、又说一遍，而第一条可能其实已经存上了。 */
    const val TIMEOUT_MS = 15_000L

    /** 执行前：先问守卫，再看正文。**守卫在前**：未登录时连「内容是空的」都不该说。 */
    fun gate(locked: Boolean, content: String): InspIntentGate {
        if (locked) return InspIntentGate.SignedOut
        val text = content.trim()
        return if (text.isEmpty()) InspIntentGate.Empty else InspIntentGate.Send(text)
    }

    /** POST /inspirations 的请求体。sourceChannel 为 null 时这个键**根本不出现**（不是 null / "manual"）。 */
    fun createBody(raw: String, title: String, summary: String, tags: List<String>, research: Boolean, sourceChannel: String?): JsonObject =
        buildJsonObject {
            put("raw", raw); put("title", title); put("summary", summary)
            put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
            put("research", research)
            if (sourceChannel != null) put("source_channel", sourceChannel)
        }

    /** 拿到了 HTTP 响应：按状态码分档。401 归「未登录」（人该去重新登录）；3xx 是跨源重定向没跟，没到创建路由。 */
    fun outcome(status: Int): InspCreateOutcome = when (status) {
        in 200..299 -> InspCreateOutcome.Saved
        in 300..399 -> InspCreateOutcome.Failed
        401 -> InspCreateOutcome.SignedOut
        in 400..499 -> InspCreateOutcome.Rejected(status)
        else -> InspCreateOutcome.ServerError(status)
    }

    /** 没拿到 HTTP 响应：按错误分档。超时 / 中途断 / 取消 → 说不准（POST 可能已经落库）。 */
    fun outcome(error: InspTransportError): InspCreateOutcome = when (error) {
        InspTransportError.SignedOutBlocked -> InspCreateOutcome.SignedOut
        InspTransportError.BadUrl -> InspCreateOutcome.NotConfigured
        InspTransportError.TimedOut, InspTransportError.ConnectionLost, InspTransportError.Cancelled -> InspCreateOutcome.Uncertain
        InspTransportError.NotConnected, InspTransportError.CannotConnect, InspTransportError.CannotFindHost, InspTransportError.Tls -> InspCreateOutcome.Offline
        InspTransportError.Other -> InspCreateOutcome.Failed
    }

    /**
     * OkHttp / java.net 的异常 → 种类（Android 独有：iOS 直接看 URLError.code）。
     * SocketTimeoutException 是 InterruptedIOException 的子类，先判它；OkHttp 的「Canceled」也是 IOException。
     */
    fun errorKind(t: Throwable): InspTransportError = when (t) {
        is CancellationException -> InspTransportError.Cancelled
        is SocketTimeoutException -> InspTransportError.TimedOut
        is UnknownHostException -> InspTransportError.CannotFindHost
        is ConnectException, is NoRouteToHostException -> InspTransportError.CannotConnect
        is SSLException -> InspTransportError.Tls
        is MalformedURLException, is IllegalArgumentException -> InspTransportError.BadUrl
        is InterruptedIOException -> InspTransportError.TimedOut
        is java.io.IOException -> if (t.message?.contains("Canceled", ignoreCase = true) == true) InspTransportError.Cancelled else InspTransportError.ConnectionLost
        else -> InspTransportError.Other
    }

    /** 执行前就被挡下时回什么。放行那一档不该走到这里，给一个失败兜底而不是成功。 */
    fun reply(gate: InspIntentGate): InspIntentReply = when (gate) {
        InspIntentGate.SignedOut -> InspIntentReply(false, "intent.insp.signedOut")
        InspIntentGate.Empty -> InspIntentReply(false, "intent.insp.empty")
        is InspIntentGate.Send -> InspIntentReply(false, "intent.insp.failed")
    }

    /** 请求回来之后回什么。**只有 Saved 是 ok**；说不准时仍算失败，但那一句不说「没记上」。 */
    fun reply(outcome: InspCreateOutcome, research: Boolean): InspIntentReply = when (outcome) {
        InspCreateOutcome.Saved -> InspIntentReply(true, if (research) "intent.insp.savedResearch" else "intent.insp.saved")
        InspCreateOutcome.SignedOut -> InspIntentReply(false, "intent.insp.signedOut")
        InspCreateOutcome.NotConfigured -> InspIntentReply(false, "intent.insp.notConfigured")
        InspCreateOutcome.Offline -> InspIntentReply(false, "intent.insp.offline")
        InspCreateOutcome.Uncertain -> InspIntentReply(false, "intent.insp.uncertain")
        is InspCreateOutcome.Rejected -> InspIntentReply(false, "intent.insp.rejected")
        is InspCreateOutcome.ServerError -> InspIntentReply(false, "intent.insp.serverError")
        InspCreateOutcome.Failed -> InspIntentReply(false, "intent.insp.failed")
    }

    /** 上面两个函数会给出的全部 key（用例核对它们都在 strings_ios.xml 里）。 */
    val allKeys = listOf(
        "intent.insp.saved", "intent.insp.savedResearch", "intent.insp.signedOut", "intent.insp.empty",
        "intent.insp.notConfigured", "intent.insp.offline", "intent.insp.uncertain", "intent.insp.rejected",
        "intent.insp.serverError", "intent.insp.failed",
    )
}
