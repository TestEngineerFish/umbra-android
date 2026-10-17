// 保险箱模块的页面入口。路由分发表（navigation/RouteScreens.kt）只调这里的函数；
// 移植时替换占位实现，函数签名不要改（改了要同步改分发表）。
package com.geek.umbra.feature.vault

import androidx.compose.runtime.Composable
import com.geek.umbra.designsystem.PlaceholderScreen

@Composable
fun VaultHomeScreen() {
    PlaceholderScreen("保险箱")
}

@Composable
fun VaultCreateScreen() {
    PlaceholderScreen("新建保险箱")
}

@Composable
fun VaultKeyScreen() {
    PlaceholderScreen("Secret Key")
}

@Composable
fun VaultRecoverScreen() {
    PlaceholderScreen("恢复")
}

@Composable
fun VaultRecordScreen(id: String, find: String) {
    PlaceholderScreen("记录")
}

@Composable
fun VaultEditScreen(id: String?) {
    PlaceholderScreen("编辑记录")
}

@Composable
fun VaultGenScreen() {
    PlaceholderScreen("密码生成器")
}

@Composable
fun VaultCheckScreen() {
    PlaceholderScreen("安全体检")
}

@Composable
fun VaultGroupsScreen() {
    PlaceholderScreen("分组")
}

@Composable
fun VaultProfilesScreen() {
    PlaceholderScreen("身份库")
}

@Composable
fun VaultTrashScreen() {
    PlaceholderScreen("回收站")
}

@Composable
fun VaultImportScreen() {
    PlaceholderScreen("导入")
}

@Composable
fun VaultSettingsScreen() {
    PlaceholderScreen("保险箱设置")
}

@Composable
fun VaultPwdScreen() {
    PlaceholderScreen("主密码")
}

@Composable
fun VaultAutofillScreen() {
    PlaceholderScreen("自动填充")
}
