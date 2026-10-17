package com.geek.umbra.feature.tool

import androidx.compose.runtime.Composable
import com.geek.umbra.app.AppGraph
import com.geek.umbra.designsystem.UmbraSettingRow
import com.geek.umbra.designsystem.UmbraScreen
import com.geek.umbra.designsystem.settingSection

/**
 * 小组件与快捷方式说明页 —— iOS 是「小组件与轻点背面」（WidgetsGuideView.swift）。
 * ⛔ 轻点背面 Android 没有系统 API，这里改讲桌面快捷方式；页名与文案待设计侧第 199 封第 11 条裁定。
 */
@Composable
fun ToolWidgetsGuide() {
    UmbraScreen(title = "小组件与快捷方式", onBack = { AppGraph.router.back() }) {
        settingSection(
            "widgets", "桌面小组件",
            listOf(
                UmbraSettingRow("任务（2×2）", sub = "正在跑的任务进度，没有在跑的时候显示今天完成了几个。点一下打开任务。"),
                UmbraSettingRow("今日提醒（4×2）", sub = "已过期和今天还没完成的提醒，最多 4 行，过期的标红。点一下打开提醒。"),
            ),
            footer = "长按桌面空白处 → 小组件 → 找到 Umbra，拖到桌面上。",
        )
        settingSection(
            "shortcuts", "桌面快捷方式",
            listOf(
                UmbraSettingRow("记一笔", sub = "长按 Umbra 图标，点「记一笔」直接打开记账输入。"),
                UmbraSettingRow("记灵感", sub = "长按 Umbra 图标，点「记灵感」直接新建一条灵感。"),
            ),
            footer = "把快捷方式拖到桌面就是一个单独的图标。",
        )
    }
}
