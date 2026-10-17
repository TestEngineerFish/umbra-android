// 知识库模块的页面入口。路由分发表（navigation/RouteScreens.kt）只调这里的函数；
// 移植时替换占位实现，函数签名不要改（改了要同步改分发表）。
package com.geek.umbra.feature.knowledge

import androidx.compose.runtime.Composable
import com.geek.umbra.designsystem.PlaceholderScreen

@Composable
fun KbListScreen() {
    PlaceholderScreen("知识库")
}

@Composable
fun KbDocsScreen(id: String, name: String) {
    PlaceholderScreen("文档")
}
