package com.geek.umbra.feature.duty

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 测试用的 JSON 解析（与 iOS 用例里的 `json(...)` / `decode(...)` 同义）。 */
internal fun json(s: String): JsonElement = Json.parseToJsonElement(s)

internal fun obj(s: String): JsonObject = json(s).jsonObject

/** JsonArray → [Int]（iOS 用例里 `as? [Int]`）；不是整数数组 → null。 */
internal fun JsonElement?.ints(): List<Int>? = (this as? JsonArray)?.map { (it as JsonPrimitive).content.toInt() }

/** 照 `String(format:)` 的位置参数填模板（`%1$@` 按位置、`%@` 按顺序）。 */
internal fun swiftFormat(template: String, args: List<String>): String {
    var s = template
    args.forEachIndexed { i, a -> s = s.replace("%${i + 1}\$@", a) }
    for (a in args) {
        val i = s.indexOf("%@")
        if (i >= 0) s = s.substring(0, i) + a + s.substring(i + 2)
    }
    return s
}

/** 中文模板表 → 文案出口；表里没有的 key 由 [missing] 决定怎么吐（用例里出现 key 本身，就说明代码用了一个模板表里没有的 key）。 */
internal fun trOf(zh: Map<String, String>, missing: (String) -> String = { it }): DutyTr = { key, args ->
    zh[key]?.let { swiftFormat(it, args) } ?: missing(key)
}
