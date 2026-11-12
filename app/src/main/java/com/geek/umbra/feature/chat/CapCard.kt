// 秘书能力的权限确认卡（服务端 docs/assistant-skills-protocol.md §8.2 `meta.cap`；设计侧《权限确认卡 · 分区稿》E / F / F2 / F3、C、L）。
// 手机是「别的端」：画完整的卡、点了走 POST /cap/calls/{id}/decide，参数 / 范围 / 指纹都在执行端，这里只交「决定 + 第几档」。
// iOS 还没有这张卡；字面照 PC（UmbraPC src/i18n/locales/zh-CN.json `capCard.*`），三端一套说法。
// 纯 Kotlin（只用 kotlinx.serialization.json），CapCardRulesTest 有用例。
package com.geek.umbra.feature.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** 预览的一段（§8.2 分段数组）。认不得的 kind 落 [Plain]：只当文字显示，不解析、不执行（单子红线）。 */
sealed interface CapPreview {
    val title: String?
    data class Graph(override val title: String?, val nodes: List<Pair<String, String?>>) : CapPreview
    data class Code(override val title: String?, val lang: String, val text: String) : CapPreview
    data class Fields(override val title: String?, val rows: List<Pair<String, String>>) : CapPreview
    data class Diff(override val title: String?, val lines: List<Pair<Char, String>>) : CapPreview
    data class Items(override val title: String?, val items: List<String>) : CapPreview
    data class Plain(override val title: String?, val text: String) : CapPreview
}

/** 一种警告：kind + 执行端写的那句（服务端可能只给 kind，见 [CapCardRules.warningText]）。 */
data class CapWarning(val kind: String, val text: String?)

/** 外来内容（§9.1）：没信任的域名、没有域名的类别、可选的具体条目（「物业通知.pdf」）。 */
data class CapTaint(val domains: List<String>, val kinds: List<String>, val items: List<Pair<String, String>>)

/** 执行端（设备能力才有）。 */
data class CapRunOn(val deviceId: String, val name: String, val online: Boolean)

/** 点过以后：在哪一端点的（`client:android` / `device:<id>` …）和显示名。 */
data class CapDecidedOn(val byClient: String, val name: String)

data class CapCard(
    val callId: String,
    val capability: String,
    val action: String,
    val name: String,
    val verb: String?,
    /** 回执里的对象短名（「随机密码」「物业电话」，设计侧 326 §1）。字段名等服务端定，先按 geek-7f 建议的 `object_name` 读；没带退回卡标题。 */
    val objectName: String?,
    val where: String?,
    val runOn: CapRunOn?,
    val phase: String,
    val title: String,
    val warnings: List<CapWarning>,
    val preview: List<CapPreview>,
    val previewTruncated: Boolean,
    val buttons: List<String>,
    val canAlways: Boolean,
    val always: List<String>,
    val bind: Boolean,
    val onceReason: String?,
    val taint: CapTaint?,
    val trustOffers: List<String>,
    val expiresAtMs: Long?,
    val pendingCount: Int,
    val turnId: String?,
    val turnIndex: Int?,
    // 定了以后（服务端 cap_gateway：phase result + outcome / summary / error / decision / decided_on / by）
    val outcome: String?,
    val summary: String?,
    val error: String?,
    val decision: String?,
    val decidedOn: CapDecidedOn?,
    val by: String?,
) {
    companion object {
        /** `meta.cap` → 卡。没有 call_id 的不认（回 null，那条消息照普通文字画）。 */
        fun parse(o: JsonObject?): CapCard? {
            o ?: return null
            val id = o.s("call_id")?.takeIf { it.isNotEmpty() } ?: return null
            val runOn = (o["run_on"] as? JsonObject)?.let { r ->
                CapRunOn(r.s("device_id") ?: "", r.s("device_name") ?: "", r.b("online") ?: true)
            }
            val taint = (o["taint"] as? JsonObject)?.let { t ->
                CapTaint(
                    t.strings("domains"), t.strings("kinds"),
                    (t["items"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { x -> (x.s("kind") ?: "") to (x.s("label") ?: "") } }
                        ?.filter { it.second.isNotEmpty() } ?: emptyList(),
                )
            }?.takeIf { it.domains.isNotEmpty() || it.kinds.isNotEmpty() }
            // warnings：协议写字符串数组，PC 本机发的是 {kind, text}；两种都认。
            val warnings = (o["warnings"] as? JsonArray)?.mapNotNull { w ->
                when (w) {
                    is JsonPrimitive -> w.contentOrNull?.let { CapWarning(it, null) }
                    is JsonObject -> w.s("kind")?.let { CapWarning(it, w.s("text")?.takeIf { t -> t.isNotBlank() }) }
                    else -> null
                }
            } ?: emptyList()
            // 旧形状：preview 是单个 {kind, …} 时当成一段。
            val pv = when (val p = o["preview"]) {
                is JsonArray -> p.mapNotNull { (it as? JsonObject)?.let(::previewOf) }
                is JsonObject -> listOfNotNull(previewOf(p))
                else -> emptyList()
            }
            val decidedOn = (o["decided_on"] as? JsonObject)?.let { CapDecidedOn(it.s("by_client") ?: "", it.s("name") ?: "") }
            return CapCard(
                callId = id, capability = o.s("capability") ?: "", action = o.s("action") ?: "",
                name = o.s("name") ?: "", verb = o.s("verb"), objectName = o.s("object_name")?.takeIf { it.isNotBlank() }, where = o.s("where"), runOn = runOn,
                phase = o.s("phase") ?: "ask", title = o.s("title") ?: "",
                warnings = warnings, preview = pv, previewTruncated = o.b("preview_truncated") == true,
                buttons = o.strings("buttons"), canAlways = o.b("can_always") ?: o.strings("buttons").contains("always"),
                always = (o["always"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.s("label")?.takeIf { l -> l.isNotEmpty() } } ?: emptyList(),
                bind = o.b("bind") == true, onceReason = o.s("once_reason"),
                taint = taint, trustOffers = o.strings("trust_offers").take(3),
                expiresAtMs = (o["expires_at_ms"] as? JsonPrimitive)?.longOrNull,
                pendingCount = (o["pending_count"] as? JsonPrimitive)?.intOrNull ?: 1,
                turnId = o.s("turn_id"), turnIndex = (o["turn_index"] as? JsonPrimitive)?.intOrNull,
                outcome = o.s("outcome"), summary = o.s("summary"), error = o.s("error"),
                decision = o.s("decision"), decidedOn = decidedOn, by = o.s("by"),
            )
        }

        private fun previewOf(o: JsonObject): CapPreview? {
            val title = o.s("title")?.takeIf { it.isNotEmpty() }
            return when (o.s("kind")) {
                "none" -> null
                "graph" -> CapPreview.Graph(title, (o["nodes"] as? JsonArray)?.mapNotNull { n ->
                    when (n) {
                        is JsonObject -> n.s("label")?.let { it to n.s("sub")?.takeIf { s -> s.isNotEmpty() } }
                        is JsonPrimitive -> n.contentOrNull?.let { it to null }
                        else -> null
                    }
                } ?: emptyList())
                "code" -> CapPreview.Code(title, o.s("lang") ?: "", o.s("text") ?: "")
                "fields" -> CapPreview.Fields(title, (o["rows"] as? JsonArray)?.mapNotNull { r ->
                    (r as? JsonArray)?.takeIf { it.size >= 2 }?.let { str(it[0]) to str(it[1]) }
                } ?: emptyList())
                "diff" -> CapPreview.Diff(title, (o["lines"] as? JsonArray)?.mapNotNull { l ->
                    (l as? JsonObject)?.let { x -> (x.s("op")?.firstOrNull() ?: ' ') to (x.s("text") ?: "") }
                } ?: emptyList())
                "list" -> CapPreview.Items(title, (o["items"] as? JsonArray)?.map { str(it) } ?: emptyList())
                // 认不得：有 text 就显示 text，没有就把这一段原样当文字（不解析成可点的东西）。
                else -> CapPreview.Plain(title, o.s("text") ?: o.toString())
            }
        }

        private fun str(e: JsonElement): String = (e as? JsonPrimitive)?.contentOrNull ?: e.toString()
        private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.b(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
        private fun JsonObject.strings(k: String): List<String> =
            (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotEmpty() } } ?: emptyList()
    }
}

/** 回执行胶囊的几档颜色（稿「状态胶囊」）。 */
enum class CapPill { Ok, Wait, Off, Bad, Busy }

/** 一行回执（38 高）：胶囊 + 主体 + 灰字。 */
data class CapReceipt(val pill: CapPill, val label: String, val subject: String, val tail: List<String>, val dim: Boolean)

/** 带保险箱密钥的工作流说明卡（§13.4 `meta.cap_notice`，设计侧 310 Q10·I / 312）。手机永远不是那台电脑：用 body_remote、不出按钮。 */
data class CapNotice(val title: String, val body: String) {
    companion object {
        const val HEAD = "工作流 · 秘书不能动"

        fun parse(o: JsonObject?): CapNotice? {
            o ?: return null
            if ((o["kind"] as? JsonPrimitive)?.contentOrNull != "vault_bound") return null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val name = s("name") ?: ""
            val title = s("title") ?: "「$name」用了保险箱里的密钥"
            // 旧消息没有 body_remote：用 name / keyword 照服务端同一套句子拼（设备名不知道时写「另一台电脑」，同 PC otherComputer）。
            val body = s("body_remote") ?: run {
                val dev = "「另一台电脑」"
                val kw = s("keyword")
                if (kw != null) "所以我不能运行，也不能改它。你自己运行照常：在${dev}的快捷入口打 $kw。要改的话，在${dev}上打开它改。"
                else "所以我不能运行，也不能改它。要改的话，在${dev}上打开它改。"
            }
            return CapNotice(title, body)
        }
    }
}

object CapCardRules {
    /** 本端在 `X-Umbra-Client` 里报的名字；服务端记成 `client:android`（§8.3）。 */
    const val CLIENT = "android"
    const val MY_CLIENT = "client:$CLIENT"

    /** 还在等人点的阶段（有按钮的卡）。paused = 无人值守停住，同样能点（这轮不画 G 的来源头）。 */
    fun isOpen(c: CapCard): Boolean = (c.phase == "ask" || c.phase == "paused") && c.buttons.isNotEmpty()

    /** 卡还完整摊开画（未定）；其余收成回执行。waiting_device 是没按钮的完整卡（等电脑上线才出可点的卡）。 */
    fun isCard(c: CapCard): Boolean = c.phase == "ask" || c.phase == "paused" || c.phase == "waiting_device"

    /** 有没有「总是允许」：要 can_always、按钮里有 always、至少一档范围。 */
    fun canAlways(c: CapCard): Boolean = c.canAlways && "always" in c.buttons && c.always.isNotEmpty()

    /** 按钮行（左 → 右）：不要 · 总是允许 · 这一次。认不得的按钮不出（单子红线）。 */
    fun buttons(c: CapCard): List<String> = listOfNotNull(
        "reject".takeIf { it in c.buttons },
        "always".takeIf { canAlways(c) },
        "once".takeIf { it in c.buttons },
    )

    fun buttonLabel(b: String): String = when (b) {
        "reject" -> "不要"
        "always" -> "总是允许"
        else -> "这一次" // 手机上「允许这一次」缩成「这一次」（306 §2）
    }

    /** 警告顺序：花钱 → 找不回 → 外来内容 → 执行代码（306 §1）；认不得的 kind 排最后。 */
    private val WARN_ORDER = listOf("costs_money", "irreversible", "external_content", "runs_code")

    /** 要画的警告（外来内容另画成域名块，这里只排序、去重）。 */
    fun warnings(c: CapCard): List<CapWarning> {
        val list = c.warnings.toMutableList()
        if (list.none { it.kind == "external_content" } && c.taint != null) list += CapWarning("external_content", null)
        return list.distinctBy { it.kind }.sortedBy { WARN_ORDER.indexOf(it.kind).let { i -> if (i < 0) WARN_ORDER.size else i } }
    }

    /** 危险档（danger-soft）还是提醒档（warning-soft）。 */
    fun isDanger(kind: String): Boolean = kind == "costs_money" || kind == "irreversible"

    /**
     * 一种警告的那句话：执行端写了就用它；没写（老消息 text 为空）就不画这条，不自己编（geek-7f 11-11：服务端 #180 照传 {kind, text}）。
     * 外来内容例外：按 taint 写稿上那句（PC warnUntrusted / warnNoDomain），因为列域名 / 说读过什么本来就是端上的事。
     */
    fun warningText(w: CapWarning, c: CapCard): String? {
        if (w.kind == "external_content") {
            val t = c.taint
            if (t != null && t.domains.isNotEmpty()) return "这一轮秘书读过你没信任过的网站。如果不是你自己要改的，点「不要」。"
            if (t != null) {
                // 不讲网站的那句，读过什么在下面逐行列（[taintLines]，设计侧 326 c）。
                return "这一轮秘书读过不是你写的内容。如果不是你自己要改的，点「不要」。"
            }
        }
        return w.text
    }

    /**
     * 没有域名的外来内容逐行列（设计侧 324 §6c 那张表）：有细节（文件名 / 发件地址 / 资料标题 / 工具名）写细节，拿不到写兜底句；
     * 每类最多 3 行，再多一行「还有 N 样」。都不出「以后信任它」。
     */
    fun taintLines(t: CapTaint): List<String> {
        val kinds = (t.items.map { it.first } + t.kinds).filter { it.isNotEmpty() }.distinct()
        val out = mutableListOf<String>()
        for (k in kinds) {
            val labels = t.items.filter { it.first == k }.map { it.second }
            if (labels.isEmpty()) { out += taintFallback(k); continue }
            labels.take(3).forEach { out += taintLine(k, it) }
            if (labels.size > 3) out += "还有 ${labels.size - 3} 样"
        }
        return out.ifEmpty { listOf("读过外来内容") }
    }

    private fun taintLine(kind: String, label: String): String = when (kind) {
        "file" -> "读过文件「$label」"
        "mail" -> "读过一封邮件（来自 $label）"
        "knowledge" -> "读过知识库里的「$label」"
        "history" -> "读过以前的聊天记录"
        "tool" -> "读过「$label」的输出"
        else -> "读过外来内容「$label」"
    }

    private fun taintFallback(kind: String): String = when (kind) {
        "file" -> "读过一个文件"
        "mail" -> "读过一封邮件"
        "knowledge" -> "读过知识库里的资料"
        "history" -> "读过以前的聊天记录"
        "tool" -> "读过工具的输出"
        else -> "读过外来内容"
    }

    /** 「一份文件「物业通知.pdf」」/「邮件、工具的输出」（PC kind* 字面）。 */
    fun taintWhat(t: CapTaint): String {
        if (t.items.isNotEmpty()) return t.items.joinToString("、") { (k, label) -> "一份${kindName(k)}「$label」" }
        return t.kinds.distinct().joinToString("、") { kindName(it) }.ifEmpty { "外来内容" }
    }

    fun kindName(k: String): String = when (k) {
        "file" -> "文件"
        "mail" -> "邮件"
        "knowledge" -> "知识库里的资料"
        "history" -> "以前的聊天记录"
        "tool" -> "工具的输出"
        else -> "外来内容"
    }

    /** 范围那一块：2 档以上出单选（标题），1 档只出一行。执行类加「脚本一改就会重新问。」 */
    fun scopeTitle(c: CapCard): String? = if (canAlways(c) && c.always.size >= 2) "点「总是允许」时记下" else null

    fun scopeOneLine(c: CapCard): String? {
        if (!canAlways(c) || c.always.size != 1) return null
        return "点「总是允许」以后：${c.always[0]}" + if (c.bind) "。脚本一改就会重新问。" else ""
    }

    fun bindNote(c: CapCard): String? = if (canAlways(c) && c.always.size >= 2 && c.bind) "脚本一改就会重新问。" else null

    /**
     * 卡底脚注：只给「这一次」时说为什么（稿规则「没有总是允许的卡」）。执行端给了 once_reason 就用它；
     * 没给按卡上看得到的推（外来内容 → 找不回 / 花钱 → 每次问）。有「总是允许」的卡没有脚注。
     */
    fun footnote(c: CapCard): String? {
        if (canAlways(c) || !isOpen(c)) return null
        val reason = c.onceReason ?: when {
            c.taint?.domains?.isNotEmpty() == true -> "untrusted_domain"
            c.taint != null -> "no_domain"
            c.warnings.any { it.kind == "irreversible" } -> "irreversible"
            c.warnings.any { it.kind == "costs_money" } -> "costs_money"
            else -> "ask_rule"
        }
        return when (reason) {
            "untrusted_domain" -> "读过没信任过的网站，这一次只能允许一次，免得网页里的一句话换来一条长期的允许。点「以后信任它」只把这个网站加进信任列表，这张卡还是只能允许一次；下一次再读到它，就照常了。"
            "no_domain" -> "文件、邮件这类内容没有网站可以信任，读过以后这一次只能允许一次。"
            "irreversible" -> "删了找不回的事每次都要你点头，所以没有「总是允许」。"
            "costs_money" -> "要花钱的事每次都要你点头，所以没有「总是允许」。"
            "once_only" -> when {
                c.warnings.any { it.kind == "costs_money" } -> "要花钱的事每次都要你点头，所以没有「总是允许」。"
                c.warnings.any { it.kind == "irreversible" } -> "删了找不回的事每次都要你点头，所以没有「总是允许」。"
                else -> "这件事每次都要你点头，所以没有「总是允许」。"
            }
            else -> "你设过这件事每次都问。"
        }
    }

    /** 执行端行（F / F2）：别的电脑执行才出；不在线（或还在等它上线）换黄条那句。服务端能力没有 run_on → 不出。 */
    fun runOnLine(c: CapCard): Pair<String, Boolean>? {
        val r = c.runOn ?: return null
        val name = "「${r.name.ifEmpty { "另一台电脑" }}」"
        // 派活时就不在线（卡还没生成、没有按钮）和「卡已生成、执行端掉线」（按钮照常能点）是两回事（设计侧 324 §6b）。
        if (c.phase == "waiting_device") return "${name}现在不在线，上线后会在这里问你" to true
        return if (!r.online) "${name}现在不在线。点了以后它一上线就执行（24 小时内）。" to true else "会在 $name 上执行" to false
    }

    /**
     * 动作的短动词，按 action 查（PC gate.ts VERB_NAME 同一张表，设计侧 326 §2「三端用同一套短动词」）。
     * 卡上的 verb 是风险类（write / delete…），分不出新建和修改，不能拿来当字。
     */
    private val VERB_NAME = mapOf("list" to "列出", "get" to "查看", "create" to "新建", "update" to "修改", "delete" to "删除", "run" to "运行")

    /**
     * 「能力 · 动作」：服务端转发设备能力时 name 拼的是动作说明的长句（「工作流 · 建一个工作流」），回执行一窄就把后面的
     * 「在 iPhone 上」挤没了（326 §2）。认得的 action 换成短动词，认不得的照 name。
     */
    fun capName(c: CapCard): String {
        val short = VERB_NAME[c.action] ?: return c.name
        val cap = c.name.substringBefore(" · ").trim()
        return if (cap.isEmpty()) c.name else "$cap · $short"
    }

    /** 卡头的能力名：服务端能力（where=cloud）后面写「 · 在云端」（设计侧 324 §5）；设备能力的执行端另起一行。 */
    fun headName(c: CapCard): String = if (c.where == "cloud") "${capName(c)} · 在云端" else capName(c)

    /** 卡头右端胶囊。 */
    fun askPill(c: CapCard): String = if (c.phase == "paused") "停住了 · 等你同意" else "等你同意"

    /** 有 N 轮停在这一步（§7.3，N ≥ 2 才出）。 */
    fun pendingLine(c: CapCard): String? = if (c.pendingCount >= 2)
        "有 ${c.pendingCount} 轮停在这一步。点一次「允许」，这 ${c.pendingCount} 轮都从这一步接着跑；点「不要」，这 ${c.pendingCount} 轮都结束。" else null

    /** code 段收起的行数：手机 6 行（306 §3）。 */
    const val CODE_FOLD_LINES = 6

    /** code 段头部「bash · 14 行」。 */
    fun codeHead(p: CapPreview.Code): String = "${p.lang.ifEmpty { "文本" }} · ${p.text.lines().size} 行"

    /** 各段的头部计数（PC pv*）。 */
    fun segHead(p: CapPreview): String = when (p) {
        is CapPreview.Graph -> (p.title ?: "节点") + " · ${p.nodes.size} 个"
        is CapPreview.Code -> (p.title ?: "脚本") + " · " + codeHead(p)
        is CapPreview.Diff -> (p.title ?: "改动") + " · ${p.lines.count { it.first == '+' || it.first == '-' }} 处"
        is CapPreview.Items -> (p.title ?: "列表") + " · ${p.items.size} 项"
        is CapPreview.Fields -> p.title ?: ""
        is CapPreview.Plain -> p.title ?: ""
    }

    // ── 回执 ──────────────────────────────────────────────────────────

    /** 别的端点的才写设备（「在 iPhone 上允许了这一次」）；本端点的、服务端代点的（server:cancel）不写。 */
    private fun remoteName(d: CapDecidedOn?): String? {
        d ?: return null
        if (d.byClient.isEmpty() || d.byClient == MY_CLIENT || d.byClient.startsWith("server:")) return null
        return d.name.ifEmpty {
            when (d.byClient) {
                "client:ios" -> "iPhone"
                "client:android" -> "Android 手机"
                "client:web" -> "网页版"
                else -> "另一台设备"
            }
        }
    }

    /** 「你允许了这一次」/「在 iPhone 上允许了这一次」/「按你之前的允许」/「系统默认允许」。 */
    fun byWhom(c: CapCard): String? {
        if (c.by == "default") return "系统默认允许"
        if (c.by == "rule") return "按你之前的允许"
        val at = remoteName(c.decidedOn)
        return when (c.decision) {
            "always" -> if (at != null) "在 $at 上选了总是允许" else "你选了总是允许"
            "reject" -> if (at != null) "在 $at 上点了不要" else "你点了不要"
            "once" -> if (at != null) "在 $at 上允许了这一次" else "你允许了这一次"
            else -> null
        }
    }

    /**
     * 回执主体：「能力 · 动作 · 「名字」」（设计侧 326 §1：前面已说做的是什么，后面只补对谁做的；不带卡标题，不用执行端回给秘书的 text）。
     * 没带对象短名时退回卡标题，至少不空着。
     */
    fun subject(c: CapCard): String {
        val obj = c.objectName?.let(::quoted) ?: c.title
        return listOf(capName(c), obj).filter { it.isNotBlank() }.distinct().joinToString(" · ")
    }

    /**
     * 定了以后那一行（稿 C / F2 / F3）。[supersededBy]：本端点了却拿到 409（别的端先点了），写「已处理 · 已经在「X」上处理过了」。
     */
    fun receipt(c: CapCard, supersededBy: String? = null): CapReceipt {
        val subj = subject(c)
        if (supersededBy != null) return CapReceipt(CapPill.Wait, "已处理", subj, listOf("已经在${quoted(supersededBy)}上处理过了"), dim = true)
        val by = byWhom(c)
        val cloud = if (c.where == "cloud") "在云端" else null
        return when {
            c.phase == "undone" -> CapReceipt(CapPill.Off, "已撤销", subj, emptyList(), dim = true)
            c.phase == "expired" || c.outcome == "expired" -> CapReceipt(CapPill.Off, "已过期", subj, listOf("等了 24 小时没人点"), dim = true)
            c.outcome == "rejected" || c.decision == "reject" -> CapReceipt(CapPill.Off, "没做", subj, listOfNotNull(by), dim = true)
            c.phase == "queued" -> CapReceipt(CapPill.Wait, "已允许 · 等电脑上线", subj, listOfNotNull(by), dim = false)
            // approved = 点了、执行端正在做（稿里没画手机形制，先按「正在做」，问题清单第 4 条）
            // 点了、正在做（设计侧 324 §6a，三端统一）：胶囊「正在做」+ 转圈，灰字「能力 · 动作 · 「名字」 · 已允许」（326 补名字），别的端点的加「在 X 上」。
            c.phase == "approved" -> CapReceipt(CapPill.Busy, "正在做", subj, listOfNotNull("已允许", remoteName(c.decidedOn)?.let { "在 $it 上" }, cloud), dim = false)
            c.outcome == "failed" -> CapReceipt(CapPill.Bad, "没跑完", subj, listOfNotNull(c.error?.takeIf { it.isNotBlank() }, cloud), dim = false)
            else -> CapReceipt(CapPill.Ok, "已做", subj, listOfNotNull(by, cloud), dim = false)
        }
    }

    fun quoted(name: String): String = if (name.startsWith("「")) name else "「$name」"

    // ── 只读活动行（314 a / 稿 L） ──────────────────────────────────────

    /** verb = read 的回执不单独画，收进「看了 N 样东西」。 */
    fun isReadReceipt(c: CapCard): Boolean = c.verb == "read" && !isCard(c)

    /** 活动行展开后的一行：「能力 · 动作　对象」—— 一个全角空格；对象取结果的说法 summary，空了才用 title（同 PC，tokens batch339 readActivity.look）。 */
    fun readLine(c: CapCard): String = capName(c) + ((c.summary?.takeIf { it.isNotBlank() } ?: c.title).takeIf { it.isNotBlank() }?.let { "　$it" } ?: "")

    fun readHead(n: Int): String = "看了 $n 样东西"

    /** 同一轮还在等的卡数（≥ 3 时第一张上面出「这一轮有 N 件事等你同意」，306 D）。 */
    fun turnHead(open: Int): String? = if (open >= 3) "这一轮有 $open 件事等你同意" else null

    // ── 点卡的回包 ────────────────────────────────────────────────────

    /** decide 只发这两样（§8.3）；scope_index 只在「总是允许」时有意义，其余发 0。 */
    fun decideBody(decision: String, scopeIndex: Int): String =
        """{"decision":"$decision","scope_index":${if (decision == "always") scopeIndex.coerceAtLeast(0) else 0}}"""

    /** 409 里是谁先点的：detail 的 decided_on.name（服务端 cap_gateway.decide）；没给回 null（按过期处理）。 */
    fun conflictName(body: JsonObject?): String? {
        val d = (body?.get("decided_on") as? JsonObject) ?: ((body?.get("detail") as? JsonObject)?.get("decided_on") as? JsonObject)
        return (d?.get("name") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
