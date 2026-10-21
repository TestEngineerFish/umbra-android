// 官方主题的 id 与英文对照（iOS `ThemeOfficial.swift` + `ThemeMigration.swift` 里的 `ThemeCopy`）。
// 2026-10-21 起**只有「默认」内置**（iOS 02cf7a9，sam：「主题就应该上架在商城，不应该内置」）：海盐 / 石墨是商城里的免费主题，
// tokens 不抄进 App —— 正本只在 umbra-plugins `themes/com.umbra.theme.{sea-salt,graphite}/`，装的时候按包验签、过判据。
//
// 「默认」就是 `designsystem/UmbraTokens.kt` 里的内置值（照 iOS v2 底色，和 PC 默认包不同，设计侧有意的平台差异），
// 选默认 = 不装调色板。官方的默认包（PC 值）只在用例里拿来对拍判据。
//
// Android 是新装，**不移 iOS 的 ThemeMigration**（升级上来的人在后台自动装回海盐 / 石墨那一截）：这里没有「以前内置过」的版本。
// ⚠️ 只许纯 Kotlin：和判据一起进 JUnit。
package com.geek.umbra.feature.theme

object ThemeOfficial {
    const val DEFAULT_ID = "com.umbra.theme.default"
    const val SEA_SALT_ID = "com.umbra.theme.sea-salt"
    const val GRAPHITE_ID = "com.umbra.theme.graphite"

    /** 内置主题（外观页卡片最前面那几张）：只有「默认」。 */
    val BUILTIN_IDS = listOf(DEFAULT_ID)
}

/**
 * 官方主题的英文名与简介：包和后台只有中文 `name` / `brief`，英文界面时查这张表，没命中退回服务端给的（iOS `ThemeCopy`，批次 275 §一）。
 * 中文一律以包里的为准，这里不抄中文。
 */
object ThemeCopy {
    private val english = mapOf(
        ThemeOfficial.SEA_SALT_ID to ("Sea Salt" to "Cool blue-gray with an indigo accent. The furthest from the default warm gray."),
        ThemeOfficial.GRAPHITE_ID to ("Graphite" to "Neutral gray with no tint and a muted mauve accent. The quietest of the set."),
    )

    /** 界面上显示的名字：英文界面且表里有就用英文，否则用给的那个。 */
    fun name(id: String, served: String, english: Boolean): String =
        if (english) this.english[id]?.first ?: served else served

    /** 界面上显示的简介：规则同上。 */
    fun brief(id: String, served: String, english: Boolean): String =
        if (english) this.english[id]?.second ?: served else served
}
