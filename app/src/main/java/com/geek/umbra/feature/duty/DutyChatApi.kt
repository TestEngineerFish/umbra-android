package com.geek.umbra.feature.duty

import androidx.compose.runtime.Composable
import kotlinx.serialization.json.JsonObject

/**
 * 聊天里的执勤内容（桩，由执勤模块实现；iOS Features/Duty/DutyChatViews.swift + DutyModels.swift:470-603）。
 * 聊天模块只做：秘书消息的 meta 里有 duty / duty_notice 时，问这里要不要接管渲染。
 */
object DutyChatApi {
    /** `meta.duty.kind == "brief"` 且有条目 → 早报卡**替换**气泡。 */
    fun isBrief(duty: JsonObject?): Boolean = false
}

/** 早报卡（替换气泡）。messageId 用于反馈接口。 */
@Composable
fun DutyBriefCard(duty: JsonObject, messageId: Long?, createdAt: String?) {}

/** 系统行下面那颗「用一张周重置卡」（执勤额度用完时，meta.duty_notice）。 */
@Composable
fun DutyNoticeAction(notice: JsonObject) {}
