// 我模块的页面入口。路由分发表（navigation/RouteScreens.kt）只调这里的函数；
// 移植时替换占位实现，函数签名不要改（改了要同步改分发表）。
package com.geek.umbra.feature.me

import androidx.compose.runtime.Composable
import com.geek.umbra.designsystem.PlaceholderScreen

@Composable
fun MeHomeScreen() {
    PlaceholderScreen("我")
}

@Composable
fun DevicesScreen() {
    PlaceholderScreen("设备与能力")
}

@Composable
fun DeviceDetailScreen(id: String) {
    PlaceholderScreen("设备")
}

@Composable
fun CapsScreen() {
    PlaceholderScreen("能力")
}

@Composable
fun ConnScreen() {
    PlaceholderScreen("连接")
}

@Composable
fun NotifyScreen() {
    PlaceholderScreen("通知")
}

@Composable
fun GeneralScreen() {
    PlaceholderScreen("通用")
}

@Composable
fun AboutScreen() {
    PlaceholderScreen("关于")
}
