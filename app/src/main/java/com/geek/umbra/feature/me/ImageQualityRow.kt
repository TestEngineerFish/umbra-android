// 「发图画质」那一行 —— iOS Features/Me/ImageQualityRow.swift。两个入口共用这一个件：通用页「发图画质」、
// 记账设置「凭证画质」（记账模块要的话调 [imageQualityRow] 传 ImagePurpose.Money）。
//
// 稿 `imageQuality.shareWhat`：分档选择器共用、缩图函数共用、**默认值绝不共用** —— 每个用途读写自己的
// `image.quality.<用途>`，默认值由服务端那一份给。⚠️ 像素数一个都不写在这儿：随 `GET /settings` 的 `image_tiers` 下来。
package com.geek.umbra.feature.me

import com.geek.umbra.R
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraChoice
import com.geek.umbra.designsystem.UmbraChoiceItem
import com.geek.umbra.designsystem.UmbraSettingRow
import com.geek.umbra.net.ApiResult
import com.geek.umbra.net.ImagePurpose
import com.geek.umbra.net.ImageTier
import com.geek.umbra.net.ServerSettings
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** 档位名（iOS ImageTier.label）。只有这一处 —— 两个入口共用同一组字面。 */
fun ImageTier.label(): String = AppGraph.context.getString(
    when (this) {
        ImageTier.Saver -> R.string.imgQ_tierSaver
        ImageTier.Standard -> R.string.imgQ_tierStandard
        ImageTier.Clear -> R.string.imgQ_tierClear
        ImageTier.Origin -> R.string.imgQ_tierOrigin
    },
)

/**
 * 这一档的人话（iOS ImageTier.hint）。前三档带服务端给的长边像素；拿不到那份表时说「还没拿到」，**不编一个数**。
 */
fun ImageTier.hint(tiers: Map<String, Int>): String {
    val ctx = AppGraph.context
    if (this == ImageTier.Origin) return ctx.getString(R.string.imgQ_hintOrigin)
    val px = tiers[wire] ?: return ctx.getString(R.string.imgQ_pending)
    return when (this) {
        ImageTier.Saver -> ctx.getString(R.string.imgQ_hintSaver, px)
        ImageTier.Standard -> ctx.getString(R.string.imgQ_hintStandard, px)
        ImageTier.Clear -> ctx.getString(R.string.imgQ_hintClear, px)
        ImageTier.Origin -> ctx.getString(R.string.imgQ_hintOrigin)
    }
}

/**
 * 设置页里那一行。设置还没拉到（或那一键没值）时**不画一个猜的档位**（iOS ImageQualityRow.swift:62）——
 * 画了的话用户会以为那就是当前值，而他点一下改的其实是另一个数。
 * [settings] / [tiers] 由调用方从 ServerSettings 的 StateFlow 收进来（这样服务端广播 settings_updated 时这一行跟着变）。
 */
fun imageQualityRow(
    purpose: ImagePurpose,
    label: String,
    settings: kotlinx.serialization.json.JsonObject?,
    tiers: Map<String, Int>,
): UmbraSettingRow {
    val raw = (settings?.get(purpose.settingKey) as? JsonPrimitive)?.content
    val tier = if (settings == null) null else ImageTier.of(raw)
    if (tier == null) {
        return UmbraSettingRow(label = label, sub = AppGraph.context.getString(R.string.imgQ_unknown), tint = null, enabled = false)
    }
    return UmbraSettingRow(label = label, sub = tier.hint(tiers), value = tier.label(), chevron = true, action = {
        UmbraChoice.present(
            title = label,
            items = ImageTier.entries.map { t ->
            UmbraChoiceItem(t.label(), checked = t == tier, note = t.hint(tiers)) {
                // 已经是这一档就别发请求：一次没有变化的 PUT 看起来像点错了（iOS ImageQualityRow.swift:76）。
                if (t != tier) AppGraph.scope.launch {
                    val r = ServerSettings.put(mapOf(purpose.settingKey to JsonPrimitive(t.wire)))
                    if (r !is ApiResult.Ok) AppGraph.router.showToast(AppGraph.context.getString(R.string.moneySet_putFailed))
                }
            }
        },
        )
    })
}
