// 执勤线上形状的宽松取值 —— 对应 iOS DutyModels.swift:11-56 的 looseString / looseInt64 / looseBool / optString。
//
// 为什么手写取值而不用 @Serializable：iOS 的解码一律「宽松」（缺键给默认值、id 数字 / 字符串都收、0/1 当布尔），
// 而且**每个字段宽松的口径不一样**（有的只认 JSON 布尔、有的 0/1 也收；有的只认字符串、有的整数也收）。
// 逐字段写在模型的 `from` 里，和 Swift 的 init(from:) 一行对一行，比给 kotlinx 写一堆自定义序列化器好对照。
//
// ⚠️ 纯 Kotlin，不 import android.*：模型与判据要在 JVM 单测里真的跑（app/src/test/.../duty/）。
package com.geek.umbra.feature.duty

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 这一格是不是 JSON 字符串（不是数字 / 布尔 / null）。 */
internal val JsonElement?.isStr: Boolean get() = this is JsonPrimitive && this !is JsonNull && this.isString

/** 非字符串的标量（数字 / 布尔）。 */
internal val JsonElement?.scalar: JsonPrimitive? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }

/** JSON 字面布尔（true / false）；字符串 "true"、数字 1 都不算 —— 对应 Swift `try? c.decode(Bool.self)`。 */
internal fun JsonElement?.strictBool(): Boolean? = scalar?.content?.let { if (it == "true") true else if (it == "false") false else null }

/** 只认 JSON 字符串 —— 对应 Swift `try? c.decode(String.self)`。 */
internal fun JsonElement?.strictString(): String? = if (isStr) (this as JsonPrimitive).content else null

/** 只认整数（不收小数、不收字符串）—— 对应 Swift `try? c.decode(Int.self)`。 */
internal fun JsonElement?.strictLong(): Long? {
    val p = scalar ?: return null
    if (p.content == "true" || p.content == "false") return null
    p.content.toLongOrNull()?.let { return it }
    // 12.0 这种整值小数 Foundation 也认作整数
    val d = p.content.toDoubleOrNull() ?: return null
    return if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 9.2e18) d.toLong() else null
}

/** 只认数字（整数小数都收）—— 对应 Swift `try? c.decode(Double.self)`。 */
internal fun JsonElement?.strictDouble(): Double? {
    val p = scalar ?: return null
    if (p.content == "true" || p.content == "false") return null
    return p.content.toDoubleOrNull()
}

/**
 * id 可能是字符串也可能是整数（契约只写了 `"id"`，没写类型）—— 两种都收成字符串（iOS looseString）。
 * 小数、布尔、null → 空串。
 */
internal fun JsonObject.looseString(k: String): String {
    val v = this[k]
    v.strictString()?.let { return it }
    v.strictLong()?.let { return it.toString() }
    return ""
}

/** 整数或小数（截断）都收（iOS looseInt64）。 */
internal fun JsonObject.looseLong(k: String): Long? {
    val v = this[k]
    v.strictLong()?.let { return it }
    return v.strictDouble()?.toLong()
}

internal fun JsonObject.looseInt(k: String): Int? = looseLong(k)?.toInt()

/** 布尔可能是 true/false，也可能是 0/1（SQLite 整数列直接回出来：`respect_quiet INTEGER`）—— 两种都收（iOS looseBool）。 */
internal fun JsonObject.looseBool(k: String): Boolean? {
    val v = this[k]
    v.strictBool()?.let { return it }
    return v.strictLong()?.let { it != 0L }
}

/** 可空的字符串：null / 缺键 / 空串都当没有（iOS optString）。 */
internal fun JsonObject.optString(k: String): String? = looseString(k).ifEmpty { null }

/** 整个数组都是字符串才收（Swift `decode([String].self)`：有一个元素不是字符串就整段失败）。 */
internal fun JsonElement?.strictStringList(): List<String>? {
    val a = this as? JsonArray ?: return null
    val out = ArrayList<String>(a.size)
    for (e in a) out += e.strictString() ?: return null
    return out
}

/** 整个数组都是整数才收（Swift `decode([Int].self)`）。 */
internal fun JsonElement?.strictIntList(): List<Int>? {
    val a = this as? JsonArray ?: return null
    val out = ArrayList<Int>(a.size)
    for (e in a) out += e.strictLong()?.toInt() ?: return null
    return out
}

/** 数组里能解的留下、解不开的丢掉（Swift 那边各模型的 init(from:) 本身不抛，所以「坏一项丢一项」）。 */
internal inline fun <T> JsonElement?.objects(parse: (JsonObject) -> T?): List<T>? {
    val a = this as? JsonArray ?: return null
    // Swift `decode([T].self)`：元素不是对象 → 整段失败。这里同样整段回 null。
    if (a.any { it !is JsonObject }) return null
    return a.mapNotNull { parse(it as JsonObject) }
}

// MARK: - DutyJSON（任意 JSON）的取值 —— iOS DutyModels.swift:731-784

/** `j["k"]`：对象里取一格，不是对象 → null。 */
internal operator fun JsonElement?.get(k: String): JsonElement? = (this as? JsonObject)?.get(k)

/** 数组本身（不是数组 → null）。 */
internal val JsonElement?.arr: List<JsonElement>? get() = this as? JsonArray

/** 布尔（只认字面 true / false）。 */
internal val JsonElement?.bool: Boolean? get() = strictBool()

/**
 * 字符串说法（iOS DutyJSON.string）：字符串原样；数字是整值且 < 1e15 → 整数写法，否则照小数写；其余 nil。
 * DutyJSON 把所有数字都解成 Double，所以 5 和 5.0 都说成 "5"。
 */
internal val JsonElement?.str: String?
    get() {
        if (isStr) return (this as JsonPrimitive).content
        val p = scalar ?: return null
        if (p.content == "true" || p.content == "false") return null
        val n = p.content.toDoubleOrNull() ?: return null
        return if (n == Math.rint(n) && Math.abs(n) < 1e15) n.toLong().toString() else n.toString()
    }

/** 是不是 JSON null（或缺这一格）。 */
internal val JsonElement?.isNullish: Boolean get() = this == null || this is JsonNull
