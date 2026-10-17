// 执勤接口 —— 照搬 iOS Networking/HTTPService.swift:932-1145（契约 §15.2 / §16 / §17.3）。
//
// 一律回 [DutyRes]：失败那一支带服务端 `detail` 的原话（409 `duty_exists`、额度不够这类），页面据此出一句话，
// 不吞成「没成功」。离线 / 解不开 → 原话是空串，页面兜一句「检查网络后再试」（同 iOS sendJSONEither）。
// 全部经由统一出口 AppGraph.http（Bearer、Initiator、会话失效分档都在那里）。
package com.geek.umbra.feature.duty

import com.geek.umbra.app.AppGraph
import com.geek.umbra.net.HttpResult
import com.geek.umbra.net.UmbraHttp
import com.geek.umbra.net.UmbraJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.RequestBody.Companion.toRequestBody

/** 接口结果：成功带值；失败带服务端原话（[why] 可能是空串）与错误码（只用来认 `duty_exists` 这类，不显示）。 */
sealed interface DutyRes<out T> {
    data class Ok<T>(val value: T) : DutyRes<T>
    data class Failed(val why: String, val code: String? = null) : DutyRes<Nothing>
}

/** 失败的原话里（或错误码）带不带某个服务端码 —— iOS 是 `e.contains("duty_exists")`，这里码与原话都看。 */
internal fun DutyRes.Failed.mentions(code: String): Boolean = this.code == code || why.contains(code)

object DutyApi {
    private val http: UmbraHttp get() = AppGraph.http

    /**
     * 路径里的 id 段转义一次：服务端 id 今天是 uuid，但这一段拼进 URL，`?` / `/` 混进来会打到别的路由上。
     * iOS 是 `addingPercentEncoding(withAllowedCharacters: .alphanumerics)` —— 除字母数字外一律百分号编码。
     */
    internal fun seg(id: String): String {
        val sb = StringBuilder()
        for (b in id.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (c < 0x80 && (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9')) sb.append(ch)
            else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xf])
        }
        return sb.toString()
    }

    /** 发一次、把回包解成 T。非 2xx → 原话；连不上 / 回包不是对象 → 空串（同 iOS：别把一段 HTML 错误页当成给用户看的话）。 */
    private suspend fun <T> req(
        method: String, path: String, body: JsonElement? = null, query: Map<String, Any?> = emptyMap(), parse: (JsonObject) -> T,
    ): DutyRes<T> {
        val rb = if (method == "GET" || method == "DELETE" && body == null) null else (body ?: JsonObject(emptyMap())).toString().toRequestBody(UmbraHttp.JSON)
        return when (val r = http.raw(method, path, query, rb)) {
            is HttpResult.Ok -> {
                val o = runCatching { UmbraJson.parseToJsonElement(r.body) as? JsonObject }.getOrNull()
                if (o == null) DutyRes.Failed("") else DutyRes.Ok(parse(o))
            }
            is HttpResult.Fail -> DutyRes.Failed(r.detail ?: "", r.code)
            is HttpResult.Offline -> DutyRes.Failed("")
            is HttpResult.Blocked -> DutyRes.Failed("")
        }
    }

    // MARK: M0（契约 §15.2）

    suspend fun fetchDuties() = req("GET", "/duties") { DutyListDTO.from(it) }

    /** 开早报（只允许 `preset = morning_brief`，每人一条）。 */
    suspend fun createBrief(params: DutyParams) =
        req("POST", "/duties", buildJsonObject { put("preset", DutyRules.MORNING_BRIEF); put("params", params.json) }) { DutyItem.from(it) }

    /** 改一条：`enabled` / `params`（整份）/ `paused_until_ms` / 整份草稿任选。 */
    suspend fun patchDuty(id: String, body: JsonObject) = req("PATCH", "/duties/${seg(id)}", body) { DutyItem.from(it) }

    /** 立即试一次。结果走 WS（`duty_shift` + 早报那条 `chat_message`），这里只回「收到了」。 */
    suspend fun runDuty(id: String) = req("POST", "/duties/${seg(id)}/run") { DutyRunDTO.from(it) }

    /** 全局规矩（`enabled` / `consented` / 静默时段 / 预算 / 每天次数）任意子集。回改完的 settings。 */
    suspend fun putSettings(patch: JsonObject) = req("PUT", "/duties/settings", patch) { DutySettings.from(it) }

    /** 一键暂停：`span` 取 [DutyRules.pauseSpans] 之一。 */
    suspend fun pause(span: String) = req("POST", "/duties/pause", buildJsonObject { put("for", span) }) { DutySettings.from(it) }

    suspend fun resume() = req("POST", "/duties/resume") { DutySettings.from(it) }

    /** 执勤日志（新的在前）。执勤页看最近 14 天；「全部日志」页拉更长一段、可按条目筛。 */
    suspend fun fetchLog(days: Int = 14, dutyId: String? = null): DutyRes<DutyLogDTO> {
        val q = mutableMapOf<String, Any?>("days" to days)
        if (!dutyId.isNullOrEmpty()) q["duty_id"] = dutyId
        return req("GET", "/duties/log", query = q) { DutyLogDTO.from(it) }
    }

    // MARK: M1a（契约 §16）

    suspend fun fetchPresets() = req("GET", "/duties/presets") { DutyPresetsDTO.from(it) }

    /** 新建一条：`{"preset": …}` 或完整字段自建。409 `duty_limit` / `duty_exists`、422 `feature_unavailable` 走失败那一支。 */
    suspend fun createDuty(body: JsonObject) = req("POST", "/duties", body) { DutyItem.from(it) }

    /** 删一条（进回收站）。 */
    suspend fun deleteDuty(id: String) = req("DELETE", "/duties/${seg(id)}") { DutyOKDTO.from(it) }

    suspend fun fetchDecision(id: String) = req("GET", "/duties/decisions/${seg(id)}") { DutyDecision.from(it) }

    /** 对一班的反馈：`{"verdict": "up"}` 或 `{"verdict": "mute", "mute": "stop" | "week" | "once"}`。 */
    suspend fun feedback(shiftId: String, verdict: String, mute: String?) =
        req("POST", "/duties/shifts/${seg(shiftId)}/feedback", buildJsonObject {
            put("verdict", verdict)
            if (mute != null) put("mute", mute)
        }) { DutyFeedbackDTO.from(it) }

    /** 早报里一条「不感兴趣」。shiftId 也可以是一键跑的运行 id（`fr-…`）—— 调用方传 DutyMeta.sourceId。 */
    suspend fun notInterested(shiftId: String, itemId: String) =
        req("POST", "/duties/shifts/${seg(shiftId)}/items/${seg(itemId)}/not_interested") { DutyOKDTO.from(it) }

    suspend fun fetchFollows() = req("GET", "/duties/follows") { DutyFollowsDTO.from(it) }

    /** 加一条关注：`{"from": {"shift_id", "item_id"}}`（早报卡上点「关注」）或 `{"topic", "kind": "news"}`（手动加）。 */
    suspend fun createFollow(body: JsonObject) = req("POST", "/duties/follows", body) { DutyFollow.from(it) }

    suspend fun patchFollow(id: String, body: JsonObject) = req("PATCH", "/duties/follows/${seg(id)}", body) { DutyFollow.from(it) }

    suspend fun deleteFollow(id: String) = req("DELETE", "/duties/follows/${seg(id)}") { DutyOKDTO.from(it) }

    // MARK: M2：秘书的提议

    suspend fun fetchProposals() = req("GET", "/duties/proposals") { DutyProposalsDTO.from(it) }

    /** 「好」。`schedule` 只对「做成流程」那种有意义；别的种类服务端不看，照传 true。 */
    suspend fun acceptProposal(id: String, schedule: Boolean) =
        req("POST", "/duties/proposals/${seg(id)}/accept", buildJsonObject { put("schedule", schedule) }) { DutyOKDTO.from(it) }

    /** 「不用」：同一件事 30 天内不再提。 */
    suspend fun rejectProposal(id: String) = req("POST", "/duties/proposals/${seg(id)}/reject") { DutyOKDTO.from(it) }

    /** 「现在看一遍」：不等夜里，马上跑一次提议生成。总开关关着 / 没会员 409，detail 原话走失败那一支。 */
    suspend fun scanProposals() = req("POST", "/duties/proposals/scan") { DutyProposalScanDTO.from(it) }

    // MARK: M1b：流程（只读 + 一键跑）

    suspend fun fetchFlows() = req("GET", "/duties/flows") { DutyFlowsDTO.from(it) }

    suspend fun fetchFlow(id: String) = req("GET", "/duties/flows/${seg(id)}") { DutyFlow.from(it) }

    /** 一键跑：服务端后台跑（受额度限制、不受静默 / 暂停），结果走 WS `duty_flow_run`。这里只回 run_id。 */
    suspend fun runFlow(id: String) = req("POST", "/duties/flows/${seg(id)}/run") { DutyFlowRunStartDTO.from(it) }

    suspend fun fetchFlowRuns(id: String, limit: Int = 20) =
        req("GET", "/duties/flows/${seg(id)}/runs", query = mapOf("limit" to limit)) { DutyFlowRunsDTO.from(it) }

    suspend fun fetchFlowRun(runId: String) = req("GET", "/duties/flows/runs/${seg(runId)}") { DutyFlowRun.from(it) }

    suspend fun fetchCatalog() = req("GET", "/duties/flows/catalog") { DutyFlowCatalog.from(it) }

    // MARK: 额度用完那一行的重置卡（复用商城的用卡调用，iOS AuthService.shopUseItem）

    /** `POST /shop/items/{id}/use {}`。只要成败与原话。 */
    suspend fun useShopItem(itemId: Long) = req("POST", "/shop/items/$itemId/use") { it }
}
