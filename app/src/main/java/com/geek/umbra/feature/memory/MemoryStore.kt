// 记忆三页共享的数据（iOS MemoryViews.swift:15 MemoryModel）+ /memory 那几条接口（iOS HTTPService.swift:1437-1515）。
package com.geek.umbra.feature.memory

import com.geek.umbra.app.AppGraph
import com.geek.umbra.net.ApiResult
import com.geek.umbra.net.HttpResult
import com.geek.umbra.net.ServerSettings
import com.geek.umbra.net.UmbraHttp
import com.geek.umbra.net.UmbraJson
import com.geek.umbra.net.UmbraUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.RequestBody.Companion.toRequestBody

/** /memory 的接口。读回 null = 没读到（断网 / 5xx / 读不懂），**不是**「没有记忆」。写一律落档在 MemoryRules.write 一处判。 */
object MemoryApi {
    /**
     * 一次请求：要**非 2xx 的响应体**（409 里的 state / current、detail），所以不走丢掉错误体的 send。没连上回 status null。
     * [timeoutMs]：告诉秘书 90 秒、导入 120 秒（模型那一趟可能要几十秒）。
     */
    private suspend fun call(path: String, method: String, body: JsonObject?, timeoutMs: Long? = null): Pair<Int?, JsonObject?> {
        val rb = body?.toString()?.toRequestBody(UmbraHttp.JSON)
        return when (val r = AppGraph.http.raw(method, path, body = rb, timeoutMs = timeoutMs)) {
            is HttpResult.Ok -> r.status to parse(r.body)
            is HttpResult.Fail -> r.status to parse(r.body)
            is HttpResult.Offline, is HttpResult.Blocked -> null to null
        }
    }

    private fun parse(s: String): JsonObject? = runCatching { UmbraJson.parseToJsonElement(s) as? JsonObject }.getOrNull()

    suspend fun fetch(): List<MemoryItemDTO>? {
        val (status, json) = call("/memory", "GET", null)
        if (status == null || status !in 200..299) return null
        return MemoryRules.items(json)
    }

    /** 新建一条（只认 topic / area / person）。 */
    suspend fun create(kind: String, title: String, summary: String, body: String): MemoryWrite {
        val (s, j) = call("/memory", "POST", buildJsonObject { put("kind", kind); put("title", title); put("summary", summary); put("body", body) })
        return MemoryRules.write(s, j)
    }

    /** 改一条。`base` = 开始编辑时那条的 updated_at（编辑期间秘书改过 → 409 冲突，带回现在那份）。 */
    suspend fun update(id: String, title: String?, summary: String?, body: String?, base: String?): MemoryWrite {
        val b = buildJsonObject {
            title?.let { put("title", it) }; summary?.let { put("summary", it) }
            body?.let { put("body", it) }; base?.let { put("base", it) }
        }
        val (s, j) = call("/memory/${UmbraUrl.value(id)}", "PUT", b)
        return MemoryRules.write(s, j)
    }

    /** 删一条（偏好 / 档案是清空内容）。 */
    suspend fun delete(id: String): MemoryWrite {
        val (s, j) = call("/memory/${UmbraUrl.value(id)}", "DELETE", null)
        return MemoryRules.write(s, j)
    }

    suspend fun clear(): MemoryWrite {
        val (s, j) = call("/memory/clear", "POST", JsonObject(emptyMap()))
        return MemoryRules.write(s, j)
    }

    /** 「告诉秘书要记住什么」：90 秒（iOS timeout: 90）。 */
    suspend fun instruct(text: String): MemoryWrite {
        val (s, j) = call("/memory/instruct", "POST", buildJsonObject { put("text", text) }, timeoutMs = 90_000)
        return MemoryRules.write(s, j)
    }

    /** 导入第一步：拿去别家 AI 里问的那段提示词（服务端一份，两端不各抄）。读不到回 null。 */
    suspend fun importPrompt(): String? {
        val (s, j) = call("/memory/import-prompt", "GET", null)
        if (s == null || s !in 200..299) return null
        return (j?.get("prompt") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** 导入第二步：120 秒。 */
    suspend fun import(text: String): MemoryWrite {
        val (s, j) = call("/memory/import", "POST", buildJsonObject { put("text", text) }, timeoutMs = 120_000)
        return MemoryRules.write(s, j)
    }
}

/** 碰上满 200 条那一句（第一组组脚）：告诉秘书 = 红字「没记上」；导入 = 提醒色「导入了一部分…」。两句互斥。 */
sealed interface FullNote {
    data object Tell : FullNote
    data class Imported(val skipped: Int) : FullNote
}

/** 三页共用。`items` null = 还没读到 / 读失败（`loadFailed` 分开这两样）。 */
object MemoryStore {
    val items = MutableStateFlow<List<MemoryItemDTO>?>(null)
    val loadFailed = MutableStateFlow(false)
    /** 三个开关；null = 没读到 /settings（那一组不画，别画一组拨了没用的开关）。 */
    val switches = MutableStateFlow<Map<String, Boolean>?>(null)
    /** 正在整理的「告诉秘书」原话：列表第一行就地变「整理中… + 原话」。 */
    val telling = MutableStateFlow<String?>(null)
    val fullNote = MutableStateFlow<FullNote?>(null)

    /** 屏上这份是替哪个会话读的（服务端地址 + 令牌的摘要）。换账号 / 换服务端后对不上，load() 先清再读（umbra-ios#86）。 */
    private var loadedFor: Int? = null

    /** 当前会话的摘要。不存令牌本身 —— 只需要「是不是同一个」。 */
    private fun sessionKey(): Int = (AppGraph.prefs.serverUrl + "\u0000" + AppGraph.session.token).hashCode()

    /**
     * 清掉屏上的记忆（umbra-ios#86）。守卫「关」调它：A 登出、B 登录后进「我 › 记忆」，首帧不许画 A 的；
     * 更糟的是偏好 / 档案两条的 id 每个账号都一样 —— B 在 A 的旧数据上「覆盖保存」，A 的正文就写进了 B 的账号。
     */
    fun reset() {
        items.value = null; switches.value = null; loadFailed.value = false
        telling.value = null; fullNote.value = null; loadedFor = null
    }

    /** 满 200 才显示那一句；条数降到 200 以下就消失。 */
    fun fullNoteShown(list: List<MemoryItemDTO>?, note: FullNote?): FullNote? = if ((list?.size ?: 0) >= 200) note else null

    suspend fun load() {
        val key = sessionKey()
        if (MemoryRules.sessionChanged(loadedFor, key)) reset()
        val got = MemoryApi.fetch()
        // 读的路上会话换了（登出 / 换账号）：这份回包是上一个会话的，不落地。
        if (sessionKey() != key) return
        val (list, failed) = MemoryRules.afterLoad(got, items.value)
        items.value = list; loadFailed.value = failed
        if (got != null) loadedFor = key
        val s = ServerSettings.load() ?: ServerSettings.settings.value
        if (s != null) {
            fun b(k: String, d: Boolean) = (s[k] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: d
            switches.value = mapOf(
                MemoryRules.KEY_GENERATE to b(MemoryRules.KEY_GENERATE, true),
                MemoryRules.KEY_SEARCH_CHATS to b(MemoryRules.KEY_SEARCH_CHATS, true),
                MemoryRules.KEY_SENSITIVE to b(MemoryRules.KEY_SENSITIVE, false),
            )
        }
    }

    /** 拨一个开关：先改屏上，服务端没认就拨回去。回是否成功。 */
    suspend fun flip(key: String): Boolean {
        val sw = switches.value ?: return false
        val cur = sw[key] ?: return false
        switches.value = sw + (key to !cur)
        val r = ServerSettings.put(mapOf<String, JsonElement>(key to JsonPrimitive(!cur)))
        if (r !is ApiResult.Ok) {
            switches.value = switches.value?.plus(key to cur)
            return false
        }
        return true
    }

    fun item(id: String): MemoryItemDTO? = items.value?.firstOrNull { it.id == id }

    /** 写成功后：服务端带回了全量就用它，带回一条就地替换后再重拉，都没有就重拉。 */
    suspend fun absorb(r: MemoryWrite) {
        if (r !is MemoryWrite.Ok) return
        r.items?.let { items.value = it; return }
        val one = r.item
        val list = items.value
        if (one != null && list != null) {
            val i = list.indexOfFirst { it.id == one.id }
            items.value = if (i >= 0) list.toMutableList().also { it[i] = one } else list + one
        }
        load()
    }

    /** 告诉秘书（sheet 已经关了才调）：那一行转圈，完了吐司 / 满了红字。 */
    fun tell(text: String, toast: (String, String?) -> Unit) {
        telling.value = text
        fullNote.value = null
        AppGraph.scope.launch {
            val r = MemoryApi.instruct(text)
            telling.value = null
            when (r) {
                is MemoryWrite.Ok -> {
                    absorb(r)
                    if (r.full) fullNote.value = FullNote.Tell else toast(MemoryRules.toldText(r.changed), r.firstChanged)
                }
                is MemoryWrite.Conflict -> load()
                is MemoryWrite.Failed -> toast(r.say, null)
            }
        }
    }
}
