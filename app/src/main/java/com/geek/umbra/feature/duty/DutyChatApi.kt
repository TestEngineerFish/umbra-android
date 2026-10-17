package com.geek.umbra.feature.duty

import androidx.compose.runtime.Composable
import kotlinx.serialization.json.JsonObject

/**
 * 聊天里的执勤内容（执勤模块实现；iOS Features/Duty/DutyChatViews.swift + DutyModels.swift:470-603）。
 * 聊天模块只做：秘书消息的 meta 里有 duty / duty_notice 时，问这里要不要接管渲染。
 *
 * 契约：
 * - [isBrief] 为 true → 用 [DutyBriefCard] **替换**气泡（`text` 是同一份早报的 markdown 兜底，给老端看的；两样都画就是同一份早报说两遍）；
 *   [DutyBriefCard] 自带标头「执勤 · 早报 · 08:00」和卡底「有用 / 别发这类了 / 为什么现在发」，聊天那边不用再画。
 * - 系统行带 `meta.duty_notice` → 系统行下面放 [DutyNoticeAction]（没卡 / 认不出时它什么都不画）。
 * - 另有两个**可选**的件（不改上面三个签名、只是追加）：不是早报的执勤消息（say 那种）在气泡上方画 [DutyMessageHeader]、
 *   气泡下方画 [DutyCardFooter]（iOS ChatThreadView.swift:446 / 469 对所有带 meta.duty 的消息都画这两样）。
 */
object DutyChatApi {
    /** `meta.duty.kind == "brief"` 且有条目 → 早报卡**替换**气泡（iOS ChatThreadView.swift:451）。入参是 meta 里的 `duty` 那一段。 */
    fun isBrief(duty: JsonObject?): Boolean {
        val m = DutyMeta.parse(duty) ?: return false
        return m.isBrief && m.items.isNotEmpty()
    }
}

/** 早报卡（替换气泡）：标头 + 分节卡 + 卡底反馈。messageId / createdAt 是这条消息自己的（反馈走 meta 里的班次 id，不靠它们）。 */
@Composable
fun DutyBriefCard(duty: JsonObject, messageId: Long?, createdAt: String?) {
    val m = DutyMeta.parse(duty) ?: return
    DutyBriefMessage(m)
}

/** 系统行下面那颗「用一张周重置卡」（执勤额度用完时，meta.duty_notice）。入参是 `duty_notice` 那一段。 */
@Composable
fun DutyNoticeAction(notice: JsonObject) {
    val n = DutyNotice.parse(notice) ?: return
    DutyNoticeButton(n)
}

/** （追加）执勤消息的标头一行「执勤 · 早报 · 08:00」。非早报的执勤消息由聊天在气泡上方调；早报卡已自带，不要重复调。 */
@Composable
fun DutyMessageHeader(duty: JsonObject) {
    val m = DutyMeta.parse(duty) ?: return
    DutyHeaderLine(m)
}

/** （追加）执勤消息卡底「有用 / 别发这类了 / 为什么现在发」。非早报的执勤消息由聊天在气泡下方调（流式中不调）；早报卡已自带。 */
@Composable
fun DutyCardFooter(duty: JsonObject) {
    val m = DutyMeta.parse(duty) ?: return
    DutyFooterActions(m)
}
