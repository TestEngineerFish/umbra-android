// 记账模块的页面入口。路由分发表（navigation/RouteScreens.kt）只调这里的函数；
// 移植时替换占位实现，函数签名不要改（改了要同步改分发表）。
package com.geek.umbra.feature.money

import androidx.compose.runtime.Composable
import com.geek.umbra.designsystem.PlaceholderScreen

@Composable
fun MoneyHomeScreen() {
    PlaceholderScreen("记账")
}

@Composable
fun MoneyListScreen() {
    PlaceholderScreen("明细")
}

@Composable
fun MoneyAddScreen(id: String?, focusAccount: Boolean) {
    PlaceholderScreen("记一笔")
}

@Composable
fun MoneyCatsScreen() {
    PlaceholderScreen("分类")
}

@Composable
fun MoneyCatScreen(slug: String) {
    PlaceholderScreen("分类")
}

@Composable
fun MoneySettingsScreen() {
    PlaceholderScreen("记账设置")
}

@Composable
fun MoneyRecurScreen() {
    PlaceholderScreen("周期记账")
}

@Composable
fun MoneyRecurEditScreen(id: String?) {
    PlaceholderScreen("周期规则")
}
