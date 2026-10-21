// 聊天的块模型（iOS: Features/Chat/ChatViewModel.swift:2458-2840 `ChatBlock` 及其子结构，Networking/ChatWebSocket.swift:790 ChatQuote / ChatToolRun）。
//
// 块一律是不可变数据类：改一块 = copy 出一份新的放回去，Compose 才认得出「变了」。
// 每块的 id 在创建时定死（UUID），给 LazyColumn 当 key —— 同一条消息从占位到收尾 id 不变，列表不会闪。
package com.geek.umbra.feature.chat

import com.geek.umbra.net.ImageTier
import com.geek.umbra.net.PreparedImage
import com.geek.umbra.net.ReasonKind
import com.geek.umbra.net.UploadError
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/** 新块的 id。 */
internal fun newBlockId(): String = UUID.randomUUID().toString()

/**
 * 消息的引用注脚：被引消息的 id、谁说的（user / assistant / device）、摘要（截 200）。
 * 只有三个字段 —— meta 要进库，不给客户端塞任意结构的口子。
 */
data class ChatQuote(val id: Long?, val role: String, val text: String) {
    val wire: JsonObject get() = buildJsonObject {
        put("role", role); put("text", text)
        if (id != null) put("id", id)
    }

    companion object {
        fun make(id: Long?, role: String, text: String) = ChatQuote(id, role, text.take(200))

        /** 从 WS / 历史的 meta.quote 读；空 text 的引用不要（两条入口口径一致）。 */
        fun of(json: JsonElement?): ChatQuote? {
            val o = json as? JsonObject ?: return null
            val text = (o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (text.isEmpty()) return null
            val id = (o["id"] as? JsonPrimitive)?.longOrNull
            val role = (o["role"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "user"
            return ChatQuote(id, role, text.take(200))
        }
    }
}

/** 取消收尾里「已经跑掉的那些工具」。args 是参数摘要（服务端已截到 200）。 */
data class ChatToolRun(val name: String, val args: String)

/** 为什么没发出去：没连上给「重新发送 + 检查服务端」；服务端拒了只给「重新发送」（现在还没有生产者）。 */
enum class SendFailure { Offline, Rejected }

enum class ConfirmStatus { Approved, Denied }

/** located=已指位；feedbackSent=已发纠偏；paused=已暂停（可继续）；resumed=已点继续。 */
enum class LocateStatus { Located, FeedbackSent, Paused, Resumed }

enum class ImageState {
    Uploading,
    /** 图都传完、WS 也送出去了，在等 message_saved。这一档不能再「取消上传」—— 服务端已经有这条了。 */
    AwaitingReceipt,
    Sent, Failed,
}

/** 一轮里的识图状态（iOS: ChatViewModel.swift:2657 VisionState）。服务端只报档位与数字，文案在端上拼。 */
data class VisionState(
    val messageId: Long? = null,
    val reading: Boolean = false,
    val readingLabel: String = "one",
    val n: Int = 0,
    /** none / manyImage / partial / allFailed；空串 = 还没收到 done。 */
    val label: String = "",
    val total: Int = 0,
    val ok: Int = 0,
    val fail: Int = 0,
    val failedAtts: List<String> = emptyList(),
    val secs: Double = 0.0,
    val reason: String = "",
    val reasonKind: ReasonKind? = null,
    val topupUrl: String? = null,
    /** 额度用完时是哪条窗口（服务端 1980e92）。 */
    val quotaWindow: String? = null,
    val retried: Boolean = false,
    val retrying: Int = 0,
    val wasAllFailed: Boolean = false,
    val timedOut: Boolean = false,
) {
    /** 错误卡只有全失败和超时两档出（部分失败不出：会把一次成功的回答框成失败）。 */
    val showsErrorCard: Boolean get() = timedOut || label == "allFailed"
    val topupLink: String? get() = if (label != "allFailed" || timedOut) null else ChatReasonRules.topupLink(reasonKind, topupUrl)
    val showsReread: Boolean get() = timedOut || ChatReasonRules.visionShowsReread(reasonKind)
}

data class QuestionItem(val id: String, val text: String, val multi: Boolean, val options: List<String>, val optional: Boolean, val allowCustom: Boolean) {
    companion object {
        /** 服务端已规整过题目，这里只做类型转换、不补默认值之外的东西。缺 id / text 的题丢掉。 */
        fun of(json: JsonElement): QuestionItem? {
            val o = json as? JsonObject ?: return null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun b(k: String) = (o[k] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
            val id = s("id")?.takeIf { it.isNotEmpty() } ?: return null
            val text = s("text")?.takeIf { it.isNotEmpty() } ?: return null
            val options = (o["options"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
            return QuestionItem(id, text, b("multi") ?: false, options, b("optional") ?: false, b("allow_custom") ?: true)
        }
    }
}

/** 「接着跑」问话那颗钮的状态（一个任务一份）。status 空串 = 还不知道 → 不出钮。 */
data class ResumeAsk(
    val status: String = "",
    val info: TaskSuspendInfo = TaskSuspendInfo(),
    val resuming: Boolean = false,
    val outcome: TaskResumeOutcome? = null,
    val failText: String? = null,
) {
    val showsButton: Boolean get() = ChatTaskKit.showsResume(status, info) && (outcome?.keepsResume ?: true)
}

/** 聊天里的一块。 */
sealed interface ChatBlock {
    val id: String
    /** 对应的服务端消息 id（没落库的块 —— 任务卡、确认卡、问答卡 —— 是 null）。删除、跳回引用都按它找。 */
    val serverId: Long? get() = null

    /** 我发出去的一条文字消息。 */
    data class User(
        override val id: String = newBlockId(),
        val text: String,
        val ts: String? = null,
        override val serverId: Long? = null,
        val quote: ChatQuote? = null,
        val atts: List<String> = emptyList(),
        val failure: SendFailure? = null,
    ) : ChatBlock {
        val failed: Boolean get() = failure != null
    }

    /**
     * 我发的一条图片消息（一条 = N 张，N ≤ 9）。上传逐张顺序做，atts 攒着已传成功的 file_id，断点续传从 atts.size 接着传。
     * data 是本地原图（认领回执后清空）；upSizes 是每张**真正发出去那份**的字节数，进度都按它算。
     */
    data class Image(
        override val id: String = newBlockId(),
        val atts: List<String> = emptyList(),
        val data: List<ByteArray> = emptyList(),
        val ts: String? = null,
        override val serverId: Long? = null,
        val state: ImageState = ImageState.Uploading,
        val sentBytes: Int = 0,
        val totalBytes: Int = 0,
        val upSizes: List<Int> = emptyList(),
        val currentFrac: Float = 0f,
        val failReason: String = "",
        val failKind: UploadError? = null,
        val tierOnce: ImageTier? = null,
        val preps: List<PreparedImage?> = emptyList(),
        /** 单图宽高比，发送时算一次存下（认领后本地原图清掉，现算会跳）。 */
        val ratioHint: Float? = null,
    ) : ChatBlock {
        val count: Int get() = maxOf(data.size, atts.size)
        // 不把几十 MB 的字节拿去比：同 id 同状态即视为同一份（iOS 哈希只用 id 的同一条理由）
        override fun equals(other: Any?) = other is Image && other.id == id && other.atts == atts && other.state == state &&
            other.sentBytes == sentBytes && other.totalBytes == totalBytes && other.currentFrac == currentFrac &&
            other.serverId == serverId && other.failReason == failReason && other.data.size == data.size && other.ratioHint == ratioHint
        override fun hashCode() = id.hashCode()
    }

    data class Assistant(
        override val id: String = newBlockId(),
        val thinking: Boolean = false,
        val streaming: Boolean = false,
        val text: String = "",
        val trace: List<String> = emptyList(),
        val traceOpen: Boolean = false,
        val ts: String? = null,
        override val serverId: Long? = null,
        /** 用户停在半截：时间戳后缀「只写到这里」。 */
        val interrupted: Boolean = false,
        val toolsRun: List<ChatToolRun> = emptyList(),
        val vision: VisionState? = null,
        /** 「要接着跑吗？」问的是哪个任务（meta.action == resume_task）。 */
        val resumeTaskId: String? = null,
        val citations: List<KbCitation> = emptyList(),
        /** 执勤消息的 meta.duty 原样（交给执勤模块的 DutyChatApi 解析）。 */
        val duty: JsonObject? = null,
    ) : ChatBlock

    /** 设备会话里设备说的一条。 */
    data class Device(override val id: String = newBlockId(), val text: String, val ts: String?, override val serverId: Long? = null) : ChatBlock

    /** 任务进度卡（task_update）。taskId 是任务 id；confirmId 是嵌在卡里的授权单号。 */
    data class Task(
        override val id: String = newBlockId(),
        val taskId: String,
        val goal: String,
        val pct: Int,
        val status: String,
        val message: String,
        val confirmId: String? = null,
        val results: List<Map<String, String>>? = null,
        val failAction: TaskFailAction = TaskFailAction.AsBefore,
        val failTopupKey: String = "vision.topup",
        /** 失败类别原文（`quota_exhausted` …）：补救类失败要多给「用重置卡」「重试任务」（批次 278，同 PC 聊天任务卡）。 */
        val failReasonKind: String? = null,
        /** 额度用完时是哪条窗口（服务端 1980e92）。 */
        val quotaWindow: String? = null,
        val operate: Boolean = false,
        val retrying: Boolean = false,
        val retryOutcome: TaskRetryOutcome? = null,
        val gone: Boolean = false,
        val suspend: TaskSuspendInfo = TaskSuspendInfo(),
        val resuming: Boolean = false,
        val resumeOutcome: TaskResumeOutcome? = null,
        val resumeFailText: String? = null,
    ) : ChatBlock

    data class Done(override val id: String = newBlockId(), val goal: String, val results: List<Map<String, String>>) : ChatBlock

    data class Confirm(override val id: String = newBlockId(), val confirmId: String, val summary: String, val resolved: ConfirmStatus? = null) : ChatBlock

    /** operate 人工求助：askId 是这次求助单号，runId 是这次操控运行（继续按它走），imageUrl 是服务端相对路径。 */
    data class Locate(
        override val id: String = newBlockId(),
        val askId: String, val runId: String, val imageUrl: String, val target: String, val hint: String,
        val resolved: LocateStatus? = null,
    ) : ChatBlock

    /** 问答卡：作答状态存在块里（放页面状态的话滚出屏再回来就丢了）。 */
    data class Question(
        override val id: String = newBlockId(),
        val cardId: String,
        val title: String,
        val questions: List<QuestionItem>,
        val at: Int = 0,
        val picked: Map<String, List<String>> = emptyMap(),
        val custom: Map<String, String> = emptyMap(),
        val done: Boolean = false,
        /** 提交后展示的「题 → 答」。别的端答的拿不到答案 → 空（不编）。 */
        val answered: List<Pair<String, String>> = emptyList(),
    ) : ChatBlock {
        fun filled(q: QuestionItem) = QuestionAnswerRules.filled(q.optional, picked[q.id] ?: emptyList(), custom[q.id])
        val allFilled: Boolean get() = questions.all { filled(it) }
    }

    /** 居中的一行系统说明。不接长按菜单（它不是消息）。dutyNotice 是 meta.duty_notice 原样。 */
    data class Note(
        override val id: String = newBlockId(),
        val text: String,
        override val serverId: Long? = null,
        val toolsRun: List<ChatToolRun> = emptyList(),
        val dutyNotice: JsonObject? = null,
    ) : ChatBlock

    /** 一轮没能完成的错误卡。kind 决定卡上的钮；topup 是已过 topupLink 的充值地址。 */
    /** `window`：额度用完时是哪条窗口（`5h` / `week`，服务端 1980e92），「用重置卡」挑对应窗口的卡。 */
    data class Error(override val id: String = newBlockId(), val text: String, val kind: ReasonKind? = null, val topup: String? = null, val window: String? = null) : ChatBlock
}

/** 会话列表预览。 */
data class ConvPreview(val text: String = "", val at: String? = null)

/** 翻页那一行的四档 + 空态判断要的几样（当前会话）。 */
data class HistoryRowState(val show: Boolean = false, val loading: Boolean = false, val failed: Boolean = false, val exhausted: Boolean = false)
