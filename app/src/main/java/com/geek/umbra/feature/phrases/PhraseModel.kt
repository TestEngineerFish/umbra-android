// 常用语的模型本体：一条 / 墓碑 / 回包，以及上行的 wire 形状 —— 对应 iOS Features/Me/PhraseModel.swift。
// 纯 Kotlin（PhraseSyncRules 的 JUnit 用例直接用它）。
package com.geek.umbra.feature.phrases

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 一条常用语。两个**毫秒**时间戳，合并时各比各的（umbra-pc#83，服务端 e897062 起）：
 * - [updatedAt]：内容（name / content / keyword）的最后改动时间；
 * - [orderUpdatedAt]：位置（order）的最后改动时间。可空 = 旧缓存 / 旧服务端没有这个键，读的时候一律经 [orderStamp]。
 * 两个戳怎么盖只在 PhraseSyncRules.stamp 一处判。
 */
data class Phrase(
    val id: String,
    val name: String,
    val content: String,
    val keyword: String? = null,
    val order: Int,
    val updatedAt: Long,
    val orderUpdatedAt: Long? = null,
) {
    /** 顺序的有效时间戳：没有单独记过就当 updatedAt（服务端对不带它的旧客户端也是这么当的）。 */
    val orderStamp: Long get() = orderUpdatedAt ?: updatedAt

    /**
     * 发给服务端的形状（驼峰字段）。keyword 空 / null 时**不带这个键**（避免服务端把 null 当「清空关键字」）；
     * orderUpdatedAt **总是带**（缺了取 updatedAt，同 PC `p.orderUpdatedAt || p.updatedAt`）。iOS: PhraseModel.swift:37
     */
    fun wire(): JsonObject = buildJsonObject {
        put("id", id); put("name", name); put("content", content)
        put("order", order); put("updatedAt", updatedAt); put("orderUpdatedAt", orderStamp)
        if (!keyword.isNullOrEmpty()) put("keyword", keyword)
    }

    companion object {
        /** 解一条（本机缓存与服务端回包同形）。必填格缺了 / 类型不对 → null（逐条丢坏留好）。 */
        fun decode(e: JsonElement): Phrase? {
            val o = e as? JsonObject ?: return null
            return Phrase(
                id = o.s("id") ?: return null,
                name = o.s("name") ?: return null,
                content = o.s("content") ?: return null,
                keyword = o.s("keyword"),
                order = o.l("order")?.toInt() ?: return null,
                updatedAt = o.l("updatedAt") ?: return null,
                orderUpdatedAt = o.l("orderUpdatedAt"),
            )
        }

        /** 本机缓存的完整形状（orderUpdatedAt 可空也照存）。 */
        fun encode(p: Phrase): JsonObject = buildJsonObject {
            put("id", p.id); put("name", p.name); put("content", p.content)
            p.keyword?.let { put("keyword", it) }
            put("order", p.order); put("updatedAt", p.updatedAt)
            p.orderUpdatedAt?.let { put("orderUpdatedAt", it) }
        }
    }
}

/** 删除墓碑。没有它，A 端删掉的条目会被 B 端一推又复活。 */
data class PhraseTomb(val id: String, val deletedAt: Long) {
    fun wire(): JsonObject = buildJsonObject { put("id", id); put("deletedAt", deletedAt) }

    companion object {
        fun decode(e: JsonElement): PhraseTomb? {
            val o = e as? JsonObject ?: return null
            return PhraseTomb(o.s("id") ?: return null, o.l("deletedAt") ?: return null)
        }
    }
}

/** /phrases/sync 的响应体（也是本机缓存的形状）：合并后的全量 + 墓碑。 */
data class PhraseBundle(val items: List<Phrase>, val deleted: List<PhraseTomb>) {
    fun encode(): JsonObject = buildJsonObject {
        put("items", buildJsonArray { items.forEach { add(Phrase.encode(it)) } })
        put("deleted", buildJsonArray { deleted.forEach { add(it.wire()) } })
    }

    companion object {
        /**
         * 逐条丢坏（台账 `cache-decode-fail-empties`）：一条坏不能让整份当空。deleted 缺键当空。
         * 返回 null = 整份不是这个形状；第二个值 = 有没有丢掉的条目（调用方据此留底）。
         */
        fun decodeLossy(e: JsonElement?): Pair<PhraseBundle, Boolean>? {
            val o = e as? JsonObject ?: return null
            val items = o["items"] as? JsonArray ?: return null
            val tombs = o["deleted"] as? JsonArray ?: JsonArray(emptyList())
            val good = items.mapNotNull { Phrase.decode(it) }
            val goodTombs = tombs.mapNotNull { PhraseTomb.decode(it) }
            return PhraseBundle(good, goodTombs) to (good.size < items.size || goodTombs.size < tombs.size)
        }
    }
}

private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull
private fun JsonObject.l(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.longOrNull
