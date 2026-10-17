// 工具模块的页面入口。路由分发表（navigation/RouteScreens.kt）只调这里的函数；
// 移植时替换占位实现，函数签名不要改（改了要同步改分发表）。
package com.geek.umbra.feature.tool

import androidx.compose.runtime.Composable
import com.geek.umbra.designsystem.PlaceholderScreen

@Composable
fun ToolHomeScreen() {
    PlaceholderScreen("工具")
}

@Composable
fun ToolWidgetsScreen() {
    PlaceholderScreen("小组件与快捷方式")
}
