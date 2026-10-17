// 执勤的线上形状与纯判据 —— 照搬 iOS Features/Duty/DutyModels.swift（契约 §15.2 / §16，字段名一字不差）。
//
// ⚠️ 纯 Kotlin，不 import android.*：早报消息 meta 的解析、日志按天分组、暂停态、时间格式都在
// app/src/test/.../duty/DutyModelsTest.kt 里真的跑（输入输出照抄 iOS Tools/swifttests/DutyModelsTests.swift）。
//
// 解码一律宽松（缺键给默认值、id 数字 / 字符串都收）：服务端和端上是并行开工的，
// 一个字段的类型对不上就让整页「没读到」，比少显示一格更糟（iOS 同一句）。
// 每个模型的 `from(JsonObject)` 与 Swift 的 `init(from:)` 一行对一行。
package com.geek.umbra.feature.duty

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// MARK: - 设置 / 列表

/** `GET /duties` 里的 `settings`（也是 `PUT /duties/settings`、暂停 / 恢复的回包）。iOS DutyModels.swift:61 */
data class DutySettings(
    val enabled: Boolean = true,
    val consented: Boolean = false,
    val paused_until_ms: Long = 0,
    val quiet_start: String = "22:30",
    val quiet_end: String = "07:30",
    val budget_pct: Double = 15.0,
    /** 每天最多主动说几次（M1a 契约 §16.1 默认 3）。认 `daily_nudges`，也收设置键原名 `dailyNudges`。 */
    val daily_nudges: Int = 3,
) {
    companion object {
        /** 默认值照契约 `app_settings.DEFAULTS`：服务端缺键时端上不至于画出一个空时间。 */
        fun from(c: JsonObject): DutySettings {
            val d = DutySettings()
            return DutySettings(
                enabled = c["enabled"].strictBool() ?: d.enabled,
                consented = c["consented"].strictBool() ?: d.consented,
                paused_until_ms = c.looseLong("paused_until_ms") ?: 0,
                quiet_start = c["quiet_start"].strictString() ?: d.quiet_start,
                quiet_end = c["quiet_end"].strictString() ?: d.quiet_end,
                budget_pct = c["budget_pct"].strictDouble() ?: d.budget_pct,
                daily_nudges = c.looseInt("daily_nudges") ?: c.looseInt("dailyNudges") ?: d.daily_nudges,
            )
        }
    }
}

/** 早报参数（契约「早报参数 params」）。端上只改参数，不碰 trigger / action。iOS DutyModels.swift:94 */
data class DutyParams(
    val time: String = "08:00",
    /** "workday" | "everyday" */
    val days: String = "workday",
    /** 空 = 读记忆档案里的所在地，再没有就不出天气那节。 */
    val city: String = "",
    /** "weather" / "reminders" / "tasks" 的子集。 */
    val blocks: List<String> = allBlocks,
    val email: Boolean = false,
) {
    /** 回写给 PATCH / POST 的那份（**整份 params**，不是差量：契约没说 params 按键合并，整份写最不会被误读）。 */
    val json: JsonObject
        get() = buildJsonObject {
            put("time", time); put("days", days); put("city", city)
            putJsonArray("blocks") { blocks.forEach { add(JsonPrimitive(it)) } }
            put("email", email)
        }

    companion object {
        val allBlocks = listOf("weather", "reminders", "tasks")

        fun from(c: JsonObject): DutyParams {
            val d = DutyParams()
            return DutyParams(
                time = c["time"].strictString() ?: d.time,
                days = c["days"].strictString() ?: d.days,
                city = c["city"].strictString() ?: "",
                blocks = c["blocks"].strictStringList() ?: d.blocks,
                email = c["email"].strictBool() ?: false,
            )
        }
    }
}

/** 一个班次（执勤日志的一行；`duty_shift` 事件也带它）。iOS DutyModels.swift:129 */
data class DutyShift(
    val id: String,
    val duty_id: String = "",
    val duty_name: String = "",
    val planned_at_ms: Long,
    val state: String = "closed",
    /** "ran" | "skipped" | "missed" | "failed" | null（还没结案） */
    val outcome: String? = "ran",
    val reason_code: String = "",
    val reason_text: String = "",
    val manual: Boolean = false,
    val simple: Boolean = false,
    val message_id: Long? = null,
    val points: Int = 0,
    val closed_at_ms: Long? = null,
    /** 哪一次判断做的决定；规则直接拦下的 / `mode = always` 的为空 —— 「为什么」就只给理由那一行。 */
    val decision_id: String = "",
    /** `state = deferred` 时延后到几时（0 = 没延后）。 */
    val defer_until_ms: Long = 0,
    /** 被并进哪一班一起发（空 = 没被并）：日志上写「并进 08:00 早报」。 */
    val merged_into: String = "",
    /** 用户对这次产出的反馈（up / down / mute；空 = 没给过）。 */
    val feedback: String = "",
    /** task 动作建出的任务。 */
    val task_id: String = "",
) {
    companion object {
        fun from(c: JsonObject) = DutyShift(
            id = c.looseString("id"),
            duty_id = c.looseString("duty_id"),
            duty_name = c["duty_name"].strictString() ?: "",
            planned_at_ms = c.looseLong("planned_at_ms") ?: 0,
            state = c["state"].strictString() ?: "",
            // Swift `try? decodeIfPresent(String.self)`：null / 缺键 / 类型不对都是 nil
            outcome = c["outcome"].strictString(),
            reason_code = c["reason_code"].strictString() ?: "",
            reason_text = c["reason_text"].strictString() ?: "",
            manual = c["manual"].strictBool() ?: false,
            simple = c["simple"].strictBool() ?: false,
            message_id = c["message_id"].strictLong(),
            points = c["points"].strictLong()?.toInt() ?: 0,
            closed_at_ms = c.looseLong("closed_at_ms"),
            decision_id = c.looseString("decision_id"),
            defer_until_ms = c.looseLong("defer_until_ms") ?: 0,
            merged_into = c.looseString("merged_into"),
            feedback = c.looseString("feedback"),
            task_id = c.looseString("task_id"),
        )

        fun parse(e: JsonElement?): DutyShift? = (e as? JsonObject)?.let { from(it) }
    }
}

// MARK: - 触发与动作（M1a 契约 §16.1）

/**
 * 触发（`trigger`）：四种，一条只有一种 —— `at` 一次性 / `recur` 周期 / `window` 条件窗口 /
 * `interval` 隔一段做一次（M2 巡视）。回写时由 [json] 只带该种类自己的键（多带别的种类的键，服务端校验可能把整条拒掉）。
 * iOS DutyModels.swift:197
 */
data class DutyTrigger(
    val type: String = "recur",
    val at_ms: Long = 0,
    /** `recur` / `window`：day / week / month / year。 */
    val cycle: String = "day",
    val every_n: Int = 1,
    /** 0 = 周一（同 `reminders.week_days`）。 */
    val week_days: List<Int> = emptyList(),
    val workday_only: Boolean = false,
    val first_date: String = "",
    /** "HH:mm"，或 `"auto"` =「我通常上线的时候」。 */
    val time_hhmm: String = "08:00",
    val end_date: String? = null,
    val start_hhmm: String = "07:00",
    val end_hhmm: String = "10:00",
    /** first_seen_today / device_online / none */
    val signal: String = "first_seen_today",
    val device_id: String? = null,
    /** `interval`：隔多少分钟一班（服务端收 30–720）。 */
    val every_min: Int = 120,
) {
    /** 回写给 `POST /duties` / `PATCH /duties/{id}` 的那份。**只带这一种自己的键**。iOS DutyModels.swift:261 */
    val json: JsonObject
        get() = when (type) {
            "at" -> buildJsonObject { put("type", "at"); put("at_ms", at_ms) }
            "window" -> buildJsonObject {
                put("type", "window"); put("cycle", cycle)
                put("week_days", if (cycle == "week") intArray(week_days.sorted()) else JsonNull)
                put("workday_only", workday_only); put("start_hhmm", start_hhmm); put("end_hhmm", end_hhmm)
                put("signal", signal); put("device_id", device_id?.let { JsonPrimitive(it) } ?: JsonNull)
            }
            "interval" -> {
                // 线上不认 cycle = week（见解码那段）：挑了几天就写 week_days，没挑 / 只在工作日就写 null。
                val picked = cycle == "week" && !workday_only && week_days.isNotEmpty()
                buildJsonObject {
                    put("type", "interval"); put("every_min", every_min); put("start_hhmm", start_hhmm); put("end_hhmm", end_hhmm)
                    put("workday_only", workday_only)
                    put("week_days", if (picked) intArray(week_days.sorted()) else JsonNull)
                    put("first_date", first_date)
                    put("end_date", end_date?.let { JsonPrimitive(it) } ?: JsonNull)
                }
            }
            else -> buildJsonObject {
                put("type", "recur"); put("cycle", cycle); put("every_n", every_n)
                put("workday_only", workday_only); put("first_date", first_date)
                put("time_hhmm", time_hhmm); put("end_date", end_date?.let { JsonPrimitive(it) } ?: JsonNull)
                put("week_days", if (cycle == "week") intArray(week_days.sorted()) else JsonNull)
            }
        }

    companion object {
        const val AUTO = "auto"

        fun from(c: JsonObject): DutyTrigger {
            val d = DutyTrigger()
            val type = c.looseString("type").ifEmpty { d.type }
            val weekDays = (c["week_days"].strictIntList() ?: emptyList()).filter { it in 0..6 }
            val workdayOnly = c.looseBool("workday_only") ?: false
            var cycle = c.optString("cycle") ?: d.cycle
            var start = c.optString("start_hhmm") ?: d.start_hhmm
            var end = c.optString("end_hhmm") ?: d.end_hhmm
            if (type == "interval") {
                // 巡视的起止缺省是 09:00–21:00（服务端 `patrol` 预设），不是窗口那套 07:00–10:00。
                if (c.optString("start_hhmm") == null) start = "09:00"
                if (c.optString("end_hhmm") == null) end = "21:00"
                // 线上 `cycle` 永远是 "day"、星期几放在 `week_days` 里。端上把「挑了几天」记成 cycle = week，
                // 和窗口型同一套：编辑器「哪些天」那一格、problem 的「每周一天都没选」两处都不用为巡视另写一份。
                val picked = !workdayOnly && weekDays.isNotEmpty() && weekDays.size < 7
                cycle = if (picked) "week" else "day"
            }
            return DutyTrigger(
                type = type,
                at_ms = c.looseLong("at_ms") ?: 0,
                cycle = cycle,
                every_n = maxOf(1, c.looseInt("every_n") ?: 1),
                week_days = weekDays,
                workday_only = workdayOnly,
                first_date = c.looseString("first_date"),
                time_hhmm = c.optString("time_hhmm") ?: d.time_hhmm,
                end_date = c.optString("end_date"),
                start_hhmm = start,
                end_hhmm = end,
                signal = c.optString("signal") ?: d.signal,
                device_id = c.optString("device_id"),
                // 不在这里收进 30–720：收了的话一条越界的（别的端写坏的）在编辑器里看起来是好的，存回去才被拒，
                // 人不知道是哪一格 —— 原样留着，DutyDraft.problem 指名道姓地说「间隔」。
                every_min = c.looseInt("every_min") ?: d.every_min,
            )
        }

        fun parse(e: JsonElement?): DutyTrigger? = (e as? JsonObject)?.let { from(it) }

        private fun intArray(xs: List<Int>) = JsonArray(xs.map { JsonPrimitive(it) })
    }
}

/** 动作（`action`）：`say` / `task` / `flow` / `workflow`（本期置灰，服务端回 422 `feature_unavailable`）。iOS DutyModels.swift:286 */
data class DutyAction(
    val type: String = "say",
    /** say：给秘书的要求。 */
    val brief: String = "",
    val blocks: List<String> = emptyList(),
    val city: String = "",
    /** task：任务目标。 */
    val goal: String = "",
    /** task：ask = 到点先问我 / never = 直接建。 */
    val confirm: String = "ask",
    val flow_id: String = "",
) {
    /** 回写那份：同触发，只带这一种自己的键。 */
    val json: JsonObject
        get() = when (type) {
            "task" -> buildJsonObject { put("type", "task"); put("goal", goal); put("confirm", confirm) }
            "flow" -> buildJsonObject { put("type", "flow"); put("flow_id", flow_id) }
            "workflow" -> buildJsonObject { put("type", "workflow") }
            else -> buildJsonObject {
                put("type", "say"); put("brief", brief)
                putJsonArray("blocks") { blocks.forEach { add(JsonPrimitive(it)) } }
                put("city", city)
            }
        }

    companion object {
        fun from(c: JsonObject) = DutyAction(
            type = c.optString("type") ?: "say",
            brief = c.looseString("brief"),
            blocks = c["blocks"].strictStringList() ?: emptyList(),
            city = c.looseString("city"),
            goal = c.looseString("goal"),
            confirm = c.optString("confirm") ?: "ask",
            flow_id = c.looseString("flow_id"),
        )

        fun parse(e: JsonElement?): DutyAction? = (e as? JsonObject)?.let { from(it) }
    }
}

/** 一条执勤条目。iOS DutyModels.swift:327 */
data class DutyItem(
    val id: String,
    val name: String = "早报",
    val preset: String = DutyRules.MORNING_BRIEF,
    val enabled: Boolean = true,
    val paused_until_ms: Long = 0,
    val params: DutyParams = DutyParams(),
    val next_at_ms: Long? = null,
    val last: DutyShift? = null,
    val created_at_ms: Long = 0,
    val updated_at_ms: Long = 0,
    /** null = 服务端没回（M0 老服务端）：列表照样画，触发那一行退回早报参数的说法。 */
    val trigger: DutyTrigger? = null,
    val action: DutyAction? = null,
    /** always = 到点就做；judge = 交给秘书判断。缺键按 always（M0 的早报就是这样跑的）。 */
    val mode: String = "always",
    val priority: Int = 3,
    val cooldown_min: Int = 0,
    val grace_min: Int = 30,
    val max_defer_min: Int = 90,
    val respect_quiet: Boolean = true,
    val created_by: String = "user",
    /** 这一条每天最多花几点（0–200；0 = 不单独设限）。 */
    val points_cap: Int = 0,
    /** 照哪条「秘书的提议」建出来的（空 = 不是）。 */
    val proposal_id: String = "",
) {
    /** 是不是早报那一条（早报保持原来的专用卡，契约 §16.8 第 1 条）。 */
    val isBrief: Boolean get() = preset == DutyRules.MORNING_BRIEF

    companion object {
        fun from(c: JsonObject) = DutyItem(
            id = c.looseString("id"),
            name = c["name"].strictString() ?: "",
            preset = c["preset"].strictString() ?: "",
            enabled = c["enabled"].strictBool() ?: true,
            paused_until_ms = c.looseLong("paused_until_ms") ?: 0,
            params = (c["params"] as? JsonObject)?.let { DutyParams.from(it) } ?: DutyParams(),
            next_at_ms = c.looseLong("next_at_ms"),
            last = DutyShift.parse(c["last"]),
            created_at_ms = c.looseLong("created_at_ms") ?: 0,
            updated_at_ms = c.looseLong("updated_at_ms") ?: 0,
            trigger = DutyTrigger.parse(c["trigger"]),
            action = DutyAction.parse(c["action"]),
            mode = c.optString("mode") ?: "always",
            priority = (c.looseInt("priority") ?: 3).coerceIn(1, 5),
            cooldown_min = maxOf(0, c.looseInt("cooldown_min") ?: 0),
            grace_min = c.looseInt("grace_min") ?: 30,
            max_defer_min = c.looseInt("max_defer_min") ?: 90,
            respect_quiet = c.looseBool("respect_quiet") ?: true,
            created_by = c.optString("created_by") ?: "user",
            points_cap = maxOf(0, c.looseInt("points_cap") ?: 0),
            proposal_id = c.looseString("proposal_id"),
        )

        fun parse(e: JsonElement?): DutyItem? = (e as? JsonObject)?.let { from(it) }
    }
}

/** 额度预报（M1a 契约 §16.3）。`runs_out_at_ms` null = 不会用完。 */
data class DutyForecast(val runs_out_at_ms: Long? = null, val note: String = "") {
    companion object {
        fun from(c: JsonObject) = DutyForecast(c.looseLong("runs_out_at_ms"), c.looseString("note"))
    }
}

/** `GET /duties` 整份。iOS DutyModels.swift:415 */
data class DutyListDTO(
    val settings: DutySettings = DutySettings(),
    /** "normal" | "save" | "essential" | "stopped"（M1a 契约 §16.3 四档） */
    val tier: String = "normal",
    val tier_reason: String = "",
    val estimate_weekly_pct: Double = 0.0,
    val items: List<DutyItem> = emptyList(),
    /** null = 服务端没回（M0）或者算不出来。 */
    val forecast: DutyForecast? = null,
) {
    /** 早报那一条（每人至多一条）。 */
    val brief: DutyItem? get() = items.firstOrNull { it.preset == DutyRules.MORNING_BRIEF }

    companion object {
        fun from(c: JsonObject) = DutyListDTO(
            settings = (c["settings"] as? JsonObject)?.let { DutySettings.from(it) } ?: DutySettings(),
            tier = c["tier"].strictString() ?: "normal",
            tier_reason = c["tier_reason"].strictString() ?: "",
            estimate_weekly_pct = c["estimate_weekly_pct"].strictDouble() ?: 0.0,
            items = c["items"].objects { DutyItem.from(it) } ?: emptyList(),
            forecast = (c["forecast"] as? JsonObject)?.let { DutyForecast.from(it) },
        )
    }
}

/** 日志回包 `{items: [Shift]}`。 */
data class DutyLogDTO(val items: List<DutyShift>) {
    companion object {
        fun from(c: JsonObject) = DutyLogDTO(c["items"].objects { DutyShift.from(it) } ?: emptyList())
    }
}

/** `POST /duties/{id}/run` 的回包。 */
data class DutyRunDTO(val ok: Boolean, val shift_id: String) {
    companion object {
        fun from(c: JsonObject) = DutyRunDTO(c["ok"].strictBool() ?: true, c.looseString("shift_id"))
    }
}

// MARK: - 早报消息的 meta（`chat_message.meta.duty`）—— iOS DutyModels.swift:470-601

/**
 * 早报卡要画的东西。WS 帧和 `/history` 走同一个 [parse]，两条入口口径一致。
 * Swift 那边入参是 JSONSerialization 的字典（NSNumber / String），这里是 JsonElement；
 * 取值口径照 Swift 的 `str()` / `int64()` / `as? Bool`。
 */
data class DutyMeta(
    val dutyId: String,
    val shiftId: String,
    /** 一键跑流程发的卡没有班次（`shift_id` 空串），带的是运行 id（`fr-…`）。服务端「关注 / 不感兴趣」两条都认它。 */
    val runId: String = "",
    val name: String,
    /** "brief" 画成早报卡；其它值只画标头。 */
    val kind: String,
    val plannedAtMs: Long,
    val simple: Boolean,
    val items: List<Item>,
    /** 额度预报：早报卡最后画一行橙字。空 = 没有。 */
    val forecastNote: String = "",
    /** 这条消息是哪一次判断发的；空串 = 没判断，「为什么现在发」按 shiftId 去日志里找理由。 */
    val decisionId: String = "",
    /** 服务端写回的卡底反馈 verdict = "up" / "mute"，空串 = 没给过（重启之后「已反馈」靠它，不靠内存）。 */
    val feedbackVerdict: String = "",
    /** "stop" / "week" / "once"；verdict 不是 mute 时空串。 */
    val feedbackMute: String = "",
) {
    data class Item(
        val id: String,
        /** "weather" | "reminders" | "tasks" | "news" | "follow" | "follow_check" … */
        val section: String,
        val title: String,
        val summary: String,
        val sourceURL: String?,
        val topicKey: String = "",
        /** 来自哪条关注；非空 = 已经在关注着（卡上画「已关注」不再给「关注」钮）。 */
        val followId: String? = null,
        /** 是「后续」：画一个小标，能点回上一次那条。 */
        val isUpdate: Boolean = false,
        val prev: Prev? = null,
        /** 服务端写回的「不感兴趣」：重启 / 重拉之后靠这一格还画「已标记不感兴趣」。 */
        val notInterested: Boolean = false,
    ) {
        data class Prev(val messageId: Long, val itemId: String)
    }

    data class Section(val section: String, val items: List<Item>)

    val isBrief: Boolean get() = kind == "brief"

    /** 条目上「关注 / 不感兴趣」指向哪一次：班次 id，一键跑的卡没有班次就用运行 id；都没有 → 空串（一颗都不画）。 */
    val sourceId: String get() = shiftId.ifEmpty { runId }

    /**
     * 早报卡的分节：按固定顺序，只出有内容的节；认不出的节归「其他」；「还关注吗？」永远在最后。
     * 顺序写死而不是按 items 出现的顺序：服务端流程的节点顺序以后会变，卡面上「天气在最前」不该跟着漂。
     */
    val sections: List<Section>
        get() {
            val out = mutableListOf<Section>()
            for (s in SECTION_ORDER) {
                val xs = items.filter { it.section == s }
                if (xs.isNotEmpty()) out += Section(s, xs)
            }
            val rest = items.filter { it.section !in SECTION_ORDER && it.section !in TAIL_SECTIONS }
            if (rest.isNotEmpty()) out += Section("other", rest)
            for (s in TAIL_SECTIONS) {
                val xs = items.filter { it.section == s }
                if (xs.isNotEmpty()) out += Section(s, xs)
            }
            return out
        }

    companion object {
        val SECTION_ORDER = listOf("weather", "reminders", "tasks", "money", "inspirations", "knowledge", "follow", "news", "web")
        val TAIL_SECTIONS = listOf("follow_check")

        /** 从 `meta`（整份）里取 `duty` 那一段。不是对象 / 没有 `duty` → null（这条不是执勤消息）。 */
        fun parseMeta(meta: JsonElement?): DutyMeta? = parse((meta as? JsonObject)?.get("duty"))

        /** 坏掉的 item（不是对象、标题和摘要都空）丢掉，好的留下 —— 同知识库引用「丢坏留好」的口径。 */
        fun parse(duty: JsonElement?): DutyMeta? {
            val d = duty as? JsonObject ?: return null
            val items = (d["items"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { el ->
                val e = el as? JsonObject ?: return@mapNotNull null
                val title = nsStr(e["title"])
                val summary = nsStr(e["summary"])
                if (title.isEmpty() && summary.isEmpty()) return@mapNotNull null
                val url = e["source_url"].strictString()?.ifEmpty { null }
                val fid = nsStr(e["follow_id"])
                // `prev` 只认「指得回去」的那种：message_id 认不出就当没有（画了「点回上一条」却跳不过去更糟）。
                val p = e["prev"] as? JsonObject
                val mid = p?.let { nsStr(it["message_id"]).toLongOrNull() }
                val prev = if (p != null && mid != null && mid > 0) Item.Prev(mid, nsStr(p["item_id"])) else null
                Item(
                    id = nsStr(e["id"]), section = nsStr(e["section"]), title = title, summary = summary, sourceURL = url,
                    topicKey = nsStr(e["topic_key"]), followId = fid.ifEmpty { null },
                    isUpdate = nsBool(e["is_update"]) ?: false, prev = prev,
                    notInterested = nsStr(e["feedback"]) == "not_interested",
                )
            }
            // 反馈只认得的两种：认不出的 verdict（服务端以后加的）当没给过 —— 照旧给钮，比画一句对不上的「已记下」好。
            var verdict = ""
            var mute = ""
            (d["feedback"] as? JsonObject)?.let { fb ->
                val v = nsStr(fb["verdict"])
                if (v == "up" || v == "mute") verdict = v
                if (v == "mute") mute = nsStr(fb["mute"])
            }
            return DutyMeta(
                dutyId = nsStr(d["duty_id"]), shiftId = nsStr(d["shift_id"]), runId = nsStr(d["run_id"]), name = nsStr(d["name"]),
                kind = nsStr(d["kind"]), plannedAtMs = nsInt64(d["planned_at_ms"]),
                simple = nsBool(d["simple"]) ?: false, items = items,
                forecastNote = nsStr(d["forecast_note"]), decisionId = nsStr(d["decision_id"]),
                feedbackVerdict = verdict, feedbackMute = mute,
            )
        }
    }
}

/** Swift `str(v)`：字符串原样；NSNumber 取 stringValue（布尔是 "1" / "0"）；其余空串。 */
internal fun nsStr(v: JsonElement?): String {
    v.strictString()?.let { return it }
    val p = v.scalar ?: return ""
    return when (p.content) { "true" -> "1"; "false" -> "0"; else -> p.content }
}

/** Swift `int64(v)`：数字取整（小数截断），字符串 / 其他 → 0。 */
internal fun nsInt64(v: JsonElement?): Long {
    val p = v.scalar ?: return 0
    if (p.content == "true") return 1
    if (p.content == "false") return 0
    return p.content.toLongOrNull() ?: p.content.toDoubleOrNull()?.toLong() ?: 0
}

/** Swift `as? Bool`（NSNumber 桥接）：true / false，以及数字 0 / 1。 */
internal fun nsBool(v: JsonElement?): Boolean? {
    v.strictBool()?.let { return it }
    return when (v.strictLong()) { 1L -> true; 0L -> false; else -> null }
}

// MARK: - 额度用完那一行（`meta.duty_notice`，契约 §16.3）—— iOS DutyModels.swift:606

/** 系统行上的说明 + 重置卡按钮。`reset_card` = 背包里一张能用的周 / 月重置卡（`GET /shop/items` 同一个来源）。 */
data class DutyNotice(
    /** 今天只有 "quota_out"；认不出的 kind 只画那一行字、不画按钮。 */
    val kind: String,
    val resumeAtMs: Long,
    val resetCard: Card?,
) {
    /** 商城的道具 id（`POST /shop/items/{id}/use` 的 id 是整数），字符串形的整数也收。 */
    data class Card(val itemId: Long, val name: String)

    val isQuotaOut: Boolean get() = kind == "quota_out"

    companion object {
        fun parseMeta(meta: JsonElement?): DutyNotice? = parse((meta as? JsonObject)?.get("duty_notice"))

        fun parse(notice: JsonElement?): DutyNotice? {
            val d = notice as? JsonObject ?: return null
            var card: Card? = null
            (d["reset_card"] as? JsonObject)?.let { c ->
                val raw = c["item_id"]
                val id = raw.strictLong() ?: raw.strictDouble()?.toLong() ?: raw.strictString()?.toLongOrNull()
                if (id != null && id > 0) card = Card(id, c["name"].strictString() ?: "")
            }
            val resume = d["resume_at_ms"].strictLong() ?: d["resume_at_ms"].strictDouble()?.toLong() ?: 0
            return DutyNotice(d["kind"].strictString() ?: "", resume, card)
        }
    }
}

// MARK: - 预设 / 关注 / 判断（M1a 契约 §16.1 / §16.4 / §16.2）

/** `GET /duties/presets` 的一项。iOS DutyModels.swift:640 */
data class DutyPreset(
    val preset: String,
    val name: String,
    val desc: String = "",
    val trigger: DutyTrigger? = null,
    val action: DutyAction? = null,
    val mode: String = "judge",
    val priority: Int = 3,
    /** 预设自带的「每天最多几点」（巡视 `patrol` 是 6）。 */
    val points_cap: Int = 0,
) {
    companion object {
        fun from(c: JsonObject) = DutyPreset(
            preset = c.looseString("preset"), name = c.looseString("name"), desc = c.looseString("desc"),
            trigger = DutyTrigger.parse(c["trigger"]), action = DutyAction.parse(c["action"]),
            mode = c.optString("mode") ?: "judge",
            priority = (c.looseInt("priority") ?: 3).coerceIn(1, 5),
            points_cap = maxOf(0, c.looseInt("points_cap") ?: 0),
        )
    }
}

data class DutyPresetsDTO(val items: List<DutyPreset>) {
    companion object {
        /** 坏一项丢一项（没有 preset 的）。 */
        fun from(c: JsonObject) = DutyPresetsDTO((c["items"].objects { DutyPreset.from(it) } ?: emptyList()).filter { it.preset.isNotEmpty() })
    }
}

/** 一条关注（契约 §16.4 `Follow`）。iOS DutyModels.swift:682 */
data class DutyFollow(
    val id: String,
    val topic: String,
    val query: String = "",
    /** news / task / reminder / money */
    val kind: String = "news",
    val ref_id: String = "",
    /** 1–5；≥ 4 才可能在早报以外单独开口。 */
    val importance: Int = 3,
    /** active / quiet（只进早报不单独说）/ ended */
    val state: String = "active",
    val last_checked_ms: Long = 0,
    val last_new_ms: Long = 0,
    val created_at_ms: Long = 0,
) {
    companion object {
        fun from(c: JsonObject) = DutyFollow(
            id = c.looseString("id"), topic = c.looseString("topic"), query = c.looseString("query"),
            kind = c.optString("kind") ?: "news", ref_id = c.looseString("ref_id"),
            importance = (c.looseInt("importance") ?: 3).coerceIn(1, 5),
            state = c.optString("state") ?: "active",
            last_checked_ms = c.looseLong("last_checked_ms") ?: 0,
            last_new_ms = c.looseLong("last_new_ms") ?: 0,
            created_at_ms = c.looseLong("created_at_ms") ?: 0,
        )
    }
}

data class DutyFollowsDTO(val items: List<DutyFollow>) {
    companion object {
        fun from(c: JsonObject) = DutyFollowsDTO((c["items"].objects { DutyFollow.from(it) } ?: emptyList()).filter { it.id.isNotEmpty() })
    }
}

/** `GET /duties/decisions/{id}`（契约 §16.2）。context / result 形状随服务端走，留成任意 JSON。iOS DutyModels.swift:787 */
data class DutyDecision(
    val id: String,
    val at_ms: Long = 0,
    /** 结构化摘要：now, holiday, busy, presence, reminders_today, tasks, nudges_today, candidates。 */
    val context: JsonElement = JsonNull,
    /** {run, defer, skip}。 */
    val result: JsonElement = JsonNull,
    val why: String = "",
    val model_key: String = "",
    val ms: Int = 0,
    val points: Int = 0,
) {
    companion object {
        fun from(c: JsonObject) = DutyDecision(
            id = c.looseString("id"), at_ms = c.looseLong("at_ms") ?: 0,
            context = c["context"] ?: JsonNull, result = c["result"] ?: JsonNull,
            why = c.looseString("why"), model_key = c.looseString("model_key"),
            ms = c.looseInt("ms") ?: 0, points = c.looseInt("points") ?: 0,
        )
    }
}

/** `{"ok": true}` 一类的回包（删除、不感兴趣）。 */
data class DutyOKDTO(val ok: Boolean) {
    companion object {
        fun from(c: JsonObject) = DutyOKDTO(c.looseBool("ok") ?: true)
    }
}

/** `POST /duties/shifts/{id}/feedback` 的回包：`{"ok": true, "duty": Duty}`（停掉 / 这周先别发会改条目本身）。 */
data class DutyFeedbackDTO(val ok: Boolean, val duty: DutyItem?) {
    companion object {
        fun from(c: JsonObject) = DutyFeedbackDTO(c.looseBool("ok") ?: true, DutyItem.parse(c["duty"]))
    }
}

// MARK: - 秘书的提议（M2 §九 / §14.2：只提议、点头才生效）—— iOS DutyModels.swift:844

/**
 * `GET /duties/proposals` 的一条。`draft` 的形状随 `kind` 走：new = 自建条目体；tune = `{patch, from?, to?}`；
 * quiet = `{settings, from}`；block = `{patch: {params: {blocks}}}`；flow = `{name, graph, time_hhmm, nodes}`；follow = `{topic, query}`。
 * 所以 draft 留成任意 JSON，画卡时按种类取那几格（[DutyKit.proposalDetail]）。
 */
data class DutyProposal(
    val id: String,
    val kind: String,
    val title: String = "",
    val why: String = "",
    val duty_id: String = "",
    val draft: JsonElement = JsonNull,
    val est_points: Int = 0,
    /** pending / accepted / rejected / expired */
    val state: String = "pending",
    val result: JsonElement = JsonNull,
    val created_at_ms: Long = 0,
    val decided_at_ms: Long? = null,
) {
    companion object {
        /** 认得的种类。不认识的整条丢掉：画得出标题却不知道「好」会改什么，不该让人点。 */
        val KINDS = listOf("new", "tune", "quiet", "block", "flow", "follow")

        fun from(c: JsonObject) = DutyProposal(
            id = c.looseString("id"), kind = c.looseString("kind"), title = c.looseString("title"), why = c.looseString("why"),
            duty_id = c.looseString("duty_id"), draft = c["draft"] ?: JsonNull,
            est_points = maxOf(0, c.looseInt("est_points") ?: 0),
            state = c.optString("state") ?: "pending", result = c["result"] ?: JsonNull,
            created_at_ms = c.looseLong("created_at_ms") ?: 0, decided_at_ms = c.looseLong("decided_at_ms"),
        )
    }
}

data class DutyProposalsDTO(val items: List<DutyProposal>) {
    companion object {
        /** 没 id 的、种类认不出的丢掉：坏一条丢一条，不让整区「没读到」。 */
        fun from(c: JsonObject) = DutyProposalsDTO(
            (c["items"].objects { DutyProposal.from(it) } ?: emptyList()).filter { it.id.isNotEmpty() && it.kind in DutyProposal.KINDS },
        )
    }
}

/** `POST /duties/proposals/scan` → `{ok, added: [Proposal]}`。端上只要条数。 */
data class DutyProposalScanDTO(val ok: Boolean, val added: Int) {
    companion object {
        fun from(c: JsonObject) = DutyProposalScanDTO(c.looseBool("ok") ?: true, (c["added"] as? JsonArray)?.size ?: 0)
    }
}

// MARK: - 纯判据 —— iOS DutyModels.swift:909-1058

/** 日志的一天（组头是日期）。 */
data class DutyDayGroup(val day: String, val items: List<DutyShift>)

/** 暂停态。 */
sealed interface DutyPause {
    data object None : DutyPause
    data class Until(val ms: Long) : DutyPause
    /** 「直到我恢复」：服务端写的是一个很远的时刻（契约没给哨兵值），十年之外一律当「直到恢复」。 */
    data object Forever : DutyPause
}

object DutyRules {
    const val MORNING_BRIEF = "morning_brief"

    /**
     * 时刻按 Asia/Shanghai 显示：早报的 `params.time` 是服务端按这个时区跑的（契约 §15.1 时区），
     * 标头「执勤 · 早报 · 08:00」要和他设的那个 08:00 对得上，人在别的时区也不该看到 09:00。
     */
    val tz: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun fmt(pattern: String): DateTimeFormatter = DateTimeFormatter.ofPattern(pattern, Locale.US).withZone(tz)
    private val HHMM = fmt("HH:mm")
    private val DAY = fmt("yyyy-MM-dd")
    private val MDHM = fmt("MM-dd HH:mm")

    /** 毫秒 → "08:00"。0 / 负数 → 空串（标头就不带时刻）。 */
    fun hhmm(ms: Long): String = if (ms <= 0) "" else HHMM.format(Instant.ofEpochMilli(ms))

    /** 毫秒 → 'YYYY-MM-DD'（日志按天分组的键）。 */
    fun day(ms: Long): String = DAY.format(Instant.ofEpochMilli(ms))

    /** 毫秒 → "10-17 08:00"（下一次 / 暂停到）。 */
    fun monthDayTime(ms: Long): String = if (ms <= 0) "" else MDHM.format(Instant.ofEpochMilli(ms))

    /** 聊天标头的三段：「执勤」· 名字 · 时刻。名字空 → 直接接时刻；时刻也空就只剩「执勤」。 */
    fun headerParts(name: String, plannedAtMs: Long): List<String> = listOf(name, hhmm(plannedAtMs)).filter { it.isNotEmpty() }

    /** 日志按天分组，**保持服务端给的顺序**（新的在前）：组的顺序按每天第一次出现，组内照原顺序。按 `planned_at_ms` 分天。 */
    fun groupByDay(shifts: List<DutyShift>): List<DutyDayGroup> {
        val groups = LinkedHashMap<String, MutableList<DutyShift>>()
        for (s in shifts) groups.getOrPut(day(s.planned_at_ms)) { mutableListOf() } += s
        return groups.map { (d, xs) -> DutyDayGroup(d, xs) }
    }

    /** 收到 `duty_shift` 时把那一班并进日志：同 id 的就地替换（结案前后各推一次也只占一行），新的插到最前。 */
    fun merge(shift: DutyShift, log: List<DutyShift>): List<DutyShift> {
        val out = log.toMutableList()
        val i = out.indexOfFirst { it.id == shift.id && shift.id.isNotEmpty() }
        if (i >= 0) out[i] = shift else out.add(0, shift)
        return out
    }

    /** 结局 → 文案 key。null / 认不出 → `duty.outcome.pending`（「进行中」）。 */
    fun outcomeKey(outcome: String?): String = when (outcome) {
        "ran" -> "duty.outcome.ran"
        "skipped" -> "duty.outcome.skipped"
        "missed" -> "duty.outcome.missed"
        "failed" -> "duty.outcome.failed"
        "blocked" -> "duty.outcome.blocked"
        else -> "duty.outcome.pending"
    }

    /** 日志一行的状态字：结了案按结局；没结案的按 `state` 分「延后了 / 等信号 / 进行中」。 */
    fun stateKey(s: DutyShift): String {
        if (s.outcome != null) return outcomeKey(s.outcome)
        return when (s.state) {
            "deferred" -> "duty.outcome.deferred"
            "waiting" -> "duty.outcome.waiting"
            else -> "duty.outcome.pending"
        }
    }

    const val FOREVER_THRESHOLD_MS: Long = 3650L * 24 * 3600 * 1000

    fun pause(pausedUntilMs: Long, nowMs: Long): DutyPause {
        if (pausedUntilMs <= nowMs) return DutyPause.None
        if (pausedUntilMs - nowMs >= FOREVER_THRESHOLD_MS) return DutyPause.Forever
        return DutyPause.Until(pausedUntilMs)
    }

    /** 暂停下拉的四档（契约 `POST /duties/pause {"for": …}` 的取值），顺序就是菜单顺序。 */
    val pauseSpans = listOf("hour", "today", "week", "forever")

    /** 预算旋钮的可选值（默认 15，可调 5%–40%）。5 一档，一屏放得下。 */
    val budgetOptions = listOf(5, 10, 15, 20, 25, 30, 35, 40)

    /** 百分比 → 「7.5%」「15%」：整数不带小数点，其余留一位。负数 / NaN 当 0。 */
    fun pctText(v: Double): String {
        if (!v.isFinite() || v <= 0) return "0%"
        // Swift `.rounded()` 是四舍五入（远离零）；v > 0，Math.round 同义
        val r = Math.round(v * 10) / 10.0
        if (r == Math.rint(r)) return "${r.toLong()}%"
        return String.format(Locale.ROOT, "%.1f%%", r)
    }

    /** "HH:mm" 认不认得（时间格回写前校验，认不出就不发）。 */
    fun validHHMM(s: String): Boolean {
        val p = s.split(":")
        if (p.size != 2 || p[0].length != 2 || p[1].length != 2) return false
        val h = p[0].toIntOrNull() ?: return false
        val m = p[1].toIntOrNull() ?: return false
        if (p[0].any { !it.isDigit() } || p[1].any { !it.isDigit() }) return false
        return h in 0..23 && m in 0..59
    }

    /** 把一个勾选块切进 / 切出 `blocks`，结果按固定顺序（天气、提醒、任务），不重复；认不出的块原样留着。 */
    fun toggle(block: String, blocks: List<String>): List<String> {
        val set = blocks.toMutableSet()
        if (block in set) set.remove(block) else set.add(block)
        val known = DutyParams.allBlocks.filter { it in set }
        val unknown = blocks.filter { it !in DutyParams.allBlocks && it in set }
        // ⚠️ 与 iOS 有意不同：Swift 版的 unknown 只从**原** blocks 里挑，于是新勾上的非三老块（编辑器 M2 的
        // reminders_soon / follows_hot）永远勾不上 —— iOS 的 bug（DutyModels.swift:1040-1046）。这里把新勾上的追在最后。
        return known + unknown + (if (block !in DutyParams.allBlocks && block in set && block !in blocks) listOf(block) else emptyList())
    }

    /** 总开关要不要先弹知情确认：**第一次打开**（从关到开、且还没确认过）才弹。 */
    fun needsConsent(turningOn: Boolean, consented: Boolean): Boolean = turningOn && !consented

    /**
     * 总开关画成开还是关：**开着且确认过**才算开。
     * 服务端默认 `enabled = True`、`consented = False` —— 照 enabled 画的话，新用户看到的是一颗已经开着的开关，
     * 「第一次打开」那一下永远发生不了，知情确认也就永远弹不出来。
     */
    fun switchOn(s: DutySettings): Boolean = s.enabled && s.consented
}
