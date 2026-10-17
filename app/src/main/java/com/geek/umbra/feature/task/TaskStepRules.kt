// 任务详情里**步骤截图的地址怎么拼**、**时间线的点上什么色**、**事件 payload 怎么读**三条纯判据
// （iOS: Features/Task/TaskStepRules.swift，审查台账 2026-10-02 段 b / e）。
// 纯 Kotlin，配 JUnit 用例（TaskStepRulesTest.kt，照抄 iOS Tools/swifttests/TaskStepRulesTests.swift）。
package com.geek.umbra.feature.task

import com.geek.umbra.net.UmbraJson
import com.geek.umbra.net.UmbraUrl
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

/** 时间线那颗点的色调。视图把它映射到 token，判据只说「这是哪一类」。 */
enum class TaskEventTone { Danger, Success, Warning, Neutral }

/** 事件 payload 里我们要的那两格。 */
data class TaskEventPayload(val status: String?, val runId: String?) {
    companion object {
        /**
         * ⚠️ 服务端的 payload 是**一段 JSON 文本**（task_events.payload 列存的是 json.dumps），不是对象；
         * 也兼容将来直接给对象。**解不出来一律当没有，绝不抛**：一条脏事件不该把整个详情页拖成「拉失败」。
         * 地基的 TaskEvent.payload 是 JsonElement?，所以整条事件本来就不会因它解码失败，这里只管「读」。
         */
        fun parse(e: JsonElement?): TaskEventPayload {
            val obj: JsonObject? = when (e) {
                is JsonObject -> e
                is JsonPrimitive -> if (e.isString) runCatching { UmbraJson.parseToJsonElement(e.content) as? JsonObject }.getOrNull() else null
                else -> null
            }
            fun s(k: String) = (obj?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
            return TaskEventPayload(s("status"), s("run_id"))
        }
    }
}

object TaskStepRules {
    /**
     * 步骤结果里那个 url → 真正去取图的地址；不认的返回 null（不画图）。
     *
     * ⚠️ 原来 iOS 写的是 `u.hasPrefix("http") ? u : serverUrl + u` —— 字符串直接拼：`@evil.com/x.png` 拼完
     * 真实 host 是 evil.com；`.evil.com/x.png` 变成别人的子域；`httpx://` 当绝对地址原样用。
     * 步骤结果是执行轮 / 设备回来的**不可信文本**，图又是 App 自己去取的。现在的规矩（iOS: TaskStepRules.swift:349）：
     * 相对地址只认**以单个 `/` 开头**、不带 `\` 的路径；绝对地址只认与服务端**同源**的 http(s)；
     * 拼完再判一次同源、且不许带 userinfo。
     */
    fun shotUrl(raw: String, serverBase: String): String? {
        val u = raw.trim()
        if (u.isEmpty()) return null
        val base = runCatching { URI(serverBase) }.getOrNull() ?: return null
        if (base.host == null) return null
        val candidate = if (u.startsWith("/")) {
            if (u.startsWith("//") || u.contains('\\')) return null
            val b = if (serverBase.endsWith("/")) serverBase.dropLast(1) else serverBase
            b + u
        } else {
            val s = runCatching { URI(u).scheme }.getOrNull()?.lowercase()
            if (s != "http" && s != "https") return null
            u
        }
        val url = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (url.userInfo != null || url.host == null) return null
        if (!UmbraUrl.followsRedirect(serverBase, candidate)) return null
        return url.toString()
    }

    /** 像不像图（iOS: UmbraImageViewer.swift:25 `looksImage`）：/files/<id> 通常无扩展名，也按图处理。 */
    fun looksImage(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/files/") || listOf(".png", ".jpg", ".jpeg", ".gif", ".webp").any { lower.contains(it) }
    }

    /**
     * 时间线一颗点该是哪一类：先看 payload.status（failed 红、done 绿、suspended 黄），再看 type（result /
     * milestone 绿、locate 黄），其余灰。cancelled 给灰：人自己按的停，不是出错（iOS: TaskStepRules.swift:451）。
     */
    fun eventTone(type: String, status: String?): TaskEventTone {
        when (status) {
            "failed" -> return TaskEventTone.Danger
            "done" -> return TaskEventTone.Success
            "suspended" -> return TaskEventTone.Warning
        }
        return when (type) {
            "result", "milestone" -> TaskEventTone.Success
            "locate" -> TaskEventTone.Warning
            else -> TaskEventTone.Neutral
        }
    }
}
