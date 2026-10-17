// 提醒模块的页面入口。路由分发表（navigation/RouteScreens.kt）只调这里的函数；
// 移植时替换占位实现，函数签名不要改（改了要同步改分发表）。
package com.geek.umbra.feature.reminder

import androidx.compose.runtime.Composable
import com.geek.umbra.designsystem.PlaceholderScreen

@Composable
fun RemListScreen() {
    PlaceholderScreen("提醒")
}

@Composable
fun RemDetailScreen(id: String) {
    PlaceholderScreen("提醒详情")
}

@Composable
fun RemEditScreen(id: String?, prefill: String?) {
    PlaceholderScreen("编辑提醒")
}
