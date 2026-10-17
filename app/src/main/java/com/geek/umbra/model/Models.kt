// 跨功能的数据模型 —— 对应 iOS Models/Models.swift。只放被两个以上功能用到的（任务、设备、上传、回收站）；
// 某个功能自己的 DTO 放回 feature/<功能>/。
// 解码一律宽松（UmbraJson：ignoreUnknownKeys + coerceInputValues），字段全给默认值：
// 服务端少给一个字段不许让整条丢掉。
package com.geek.umbra.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 「整数或字符串都收」的 id（显示器 id、回收站 id 都有这种情况）。统一转成字符串。 */
object LooseStringSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("LooseString", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String {
        val d = decoder as? JsonDecoder ?: return decoder.decodeString()
        val e = d.decodeJsonElement()
        return (e as? JsonPrimitive)?.contentOrNull ?: ""
    }
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

@Serializable
data class TaskWaitingDevice(val id: String = "", val name: String? = null)

/** 任务。标题 name 优先，没有才用 goal。 */
@Serializable
data class TaskItem(
    val id: String = "",
    val name: String? = null,
    val goal: String = "",
    val status: String = "",
    val result_summary: String? = null,
    val channel: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null,
    val steps_total: Int? = null,
    val steps_done: Int? = null,
    /** "operate" = 电脑操作任务（重试按钮换成「重新发起」）。 */
    val executor: String? = null,
    val suspend_reason: String? = null,
    val missing_capabilities: List<String>? = null,
    /** 元素可以是 null（服务端认不出那项能力的中文名）。 */
    val missing_capability_labels: List<String?>? = null,
    val waiting_devices: List<TaskWaitingDevice>? = null,
    val suspended_step: Int? = null,
    val resume_ready: Boolean? = null,
    val ready_reason: String? = null,
) {
    val title: String get() = name?.trim()?.takeIf { it.isNotEmpty() } ?: goal
}

@Serializable
data class StepError(
    val kind: String? = null, val message: String? = null, val detail: String? = null,
    val reason_kind: String? = null, val topup_url: String? = null,
)

@Serializable
data class TaskStep(
    val id: String = "", val seq: Int = 0, val title: String? = null, val status: String = "",
    val detail: String? = null, val device_id: String? = null, val elapsed_ms: Long? = null,
    val error: StepError? = null, val result_json: String? = null,
)

/** payload 是 JSON 文本或对象，原样留着由任务模块自己解析。 */
@Serializable
data class TaskEvent(
    val id: Long = 0, val type: String = "", val message: String? = null, val step_id: String? = null,
    val payload: kotlinx.serialization.json.JsonElement? = null, val created_at: String? = null,
)

@Serializable
data class TaskDetail(val task: TaskItem = TaskItem(), val steps: List<TaskStep> = emptyList(), val events: List<TaskEvent> = emptyList())

@Serializable
data class SkillInfo(val name: String = "", val description: String = "")

@Serializable
data class RemoteFeatures(val control: Boolean? = null, val unattended: Boolean? = null, val files: Boolean? = null, val clipboard: Boolean? = null)

@Serializable
data class RemoteDisplay(
    @Serializable(with = LooseStringSerializer::class) val id: String = "",
    val name: String? = null, val primary: Boolean? = null, val width: Int? = null, val height: Int? = null,
)

/** 设备上报的一项能力。`provider == "remote"` 那一项是远程控制的 manifest。 */
@Serializable
data class ProviderInfo(
    val provider: String = "",
    val display_name: String = "",
    val kind: String = "",
    val available: Boolean = false,
    val unavailable_reason: String = "",
    val version: String? = null,
    val skills: List<SkillInfo> = emptyList(),
    val unavailable_text: String? = null,
    val features: RemoteFeatures? = null,
    val displays: List<RemoteDisplay>? = null,
)

@Serializable
data class Capability(val device_id: String = "", val device_name: String = "", val platform: String = "", val providers: List<ProviderInfo> = emptyList())

/** `GET /devices/all` 的一台设备。聊天联系人、设备与能力、远程控制入口都用它。 */
@Serializable
data class KnownDevice(
    val device_id: String = "",
    val device_name: String = "",
    val platform: String = "",
    val online: Boolean = false,
    val last_seen: String? = null,
    val providers: List<ProviderInfo> = emptyList(),
) {
    val conversation: String get() = "device:$device_id"
    val remote: ProviderInfo? get() = providers.firstOrNull { it.provider == "remote" }
}

@Serializable
data class UploadResponse(val file_id: String = "", val filename: String = "", val url: String = "")

/** 回收站一条。id 可能是整数也可能是字符串；找回 / 彻底删除时 idea 的 id 要发整数。 */
@Serializable
data class TrashItem(
    val kind: String = "",
    @Serializable(with = LooseStringSerializer::class) val id: String = "",
    val title: String = "",
    val deleted_at_ms: Double = 0.0,
    val left_days: Int = 0,
)

@Serializable
data class TrashList(val items: List<TrashItem> = emptyList(), val counts: Map<String, Int>? = null, val keep_days: Int? = null)
