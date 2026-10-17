// 账号模块的页面入口。路由分发表（navigation/RouteScreens.kt）只调这里的函数；
// 移植时替换占位实现，函数签名不要改（改了要同步改分发表）。
package com.geek.umbra.feature.account

import androidx.compose.runtime.Composable
import com.geek.umbra.designsystem.PlaceholderScreen

@Composable
fun AccountScreen() {
    PlaceholderScreen("账号")
}

@Composable
fun ShopScreen() {
    PlaceholderScreen("商城")
}

@Composable
fun ShopHistoryScreen() {
    PlaceholderScreen("道具历史")
}

@Composable
fun StorageScreen() {
    PlaceholderScreen("管理存储")
}

@Composable
fun PasswordScreen() {
    PlaceholderScreen("改登录密码")
}

@Composable
fun SessionsScreen() {
    PlaceholderScreen("登录设备")
}

@Composable
fun DeactivateScreen() {
    PlaceholderScreen("注销账号")
}
