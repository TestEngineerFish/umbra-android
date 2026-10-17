// 聊天模块的页面入口。路由分发表（navigation/RouteScreens.kt）只调这里的函数；函数签名不要改（改了要同步改分发表）。
package com.geek.umbra.feature.chat

import androidx.compose.runtime.Composable

/** 聊天 Tab 根页：会话列表（秘书 + 设备）。 */
@Composable
fun ChatContactsScreen() {
    ChatContactsPage()
}

/** 对话页。conv = "assistant" 或 "device:<id>"。 */
@Composable
fun ChatThreadScreen(conv: String) {
    ChatThreadPage(conv)
}
