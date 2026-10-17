// 灵感的数据模型（iOS: Features/Inspiration/InspirationModel.swift）。纯 Kotlin，判据用例连同它一起跑。
package com.geek.umbra.feature.inspiration

import kotlinx.serialization.Serializable

/**
 * 灵感速记：raw 原文一字不改；title / summary / tags 是秘书的轻整理。
 * 整理 / 调研 / 同步那几格都给默认值：连到旧服务端时字段是缺的，非可空会让整条解码失败 ——
 * 整个灵感列表变空白，而用户只会看到「没有灵感」，完全看不出是版本不匹配。
 */
@Serializable
data class Inspiration(
    val id: Long = 0,
    val raw: String = "",
    val title: String = "",
    val summary: String = "",
    val tags: List<String> = emptyList(),
    /** open / done / archived */
    val status: String = "open",
    val source_channel: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null,
    /** pending 待补整理 / done 已整理 / failed 整理失败 */
    val organize_status: String? = null,
    /** 轻调研纪要（Markdown）。没查过是空串。 */
    val research: String? = null,
    /** idle / queued / running / done / failed */
    val research_status: String? = null,
    val research_at: String? = null,
    /** 端上生成的稳定 id（老数据没有 → 服务端补 `i<id>`）。 */
    val uid: String? = null,
    /** 后写覆盖先写。 */
    val updated_at_ms: Long? = null,
    /** 配图（只读，带图消息记灵感时服务端挂的）。 */
    val atts: List<InspAtt>? = null,
    /** 本机改过还没推上去（只在本机缓存里有意义）。 */
    val dirty: Boolean? = null,
) {
    /** 本机 / 跨端的身份：uid，没有就是服务端整数 id 折成的 `i<id>`。 */
    val key: String get() = uid ?: "i$id"
    val organizeState: String get() = organize_status ?: "done"
    val researchState: String get() = research_status ?: "idle"
    val researchText: String get() = research ?: ""
    /** 排队中或正在跑 —— 详情页据此决定要不要开轮询盯进度。 */
    val researchInFlight: Boolean get() = researchState == "queued" || researchState == "running"

    /**
     * 列表 / 详情显示用的标题。秘书的整理是异步补的，补上之前拿原文前 20 字顶着 ——
     * 用户刚记完就看到自己写的东西，才知道确实记下了。
     */
    val displayTitle: String
        get() {
            val t = title.trim()
            if (t.isNotEmpty()) return t
            val body = raw.trim().replace("\n", " ")
            return if (body.length <= 20) body else body.take(20) + "…"
        }
}

@Serializable
data class InspAtt(val file_id: String = "", val label: String = "")

@Serializable
data class InspTombDTO(val uid: String = "", val deleted_at_ms: Long = 0)

/** GET /inspirations/sync?since= 的回包。 */
@Serializable
data class InspSyncPullDTO(val items: List<Inspiration> = emptyList(), val deleted: List<InspTombDTO> = emptyList(), val synced_at_ms: Long = 0)

/** PUT /inspirations/by-uid/{uid} 的回包：written = false = 库里那份更新，item 是它。 */
@Serializable
data class InspPutDTO(val item: Inspiration = Inspiration(), val written: Boolean? = null)

/** 本机删除的墓碑。 */
@Serializable
data class InspTomb(val id: String, val deletedAtMs: Long)

/**
 * 一次改动（字段名同 PATCH 体）。iOS 是 `[String: Any]`；这里写成显式可空字段 —— null = 这个键根本不出现
 * （服务端语义「缺键 = 不动」），空串 / 空数组是人有意清空。
 */
data class InspPatch(
    val raw: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val tags: List<String>? = null,
    val status: String? = null,
) {
    val isEmpty: Boolean get() = raw == null && title == null && summary == null && tags == null && status == null
    /** 带了哪些键（用例比对用）。 */
    val keys: Set<String>
        get() = buildSet {
            if (raw != null) add("raw"); if (title != null) add("title"); if (summary != null) add("summary")
            if (tags != null) add("tags"); if (status != null) add("status")
        }
}
