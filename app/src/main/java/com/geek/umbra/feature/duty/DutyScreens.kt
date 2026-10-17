// 执勤模块的页面入口。路由分发表（navigation/RouteScreens.kt）只调这里的函数；
// 移植时替换占位实现，函数签名不要改（改了要同步改分发表）。
package com.geek.umbra.feature.duty

import androidx.compose.runtime.Composable
import com.geek.umbra.designsystem.PlaceholderScreen

@Composable
fun DutyScreen() {
    PlaceholderScreen("执勤")
}

@Composable
fun DutyEditScreen(id: String?) {
    PlaceholderScreen("执勤条目")
}

@Composable
fun DutyLogScreen() {
    PlaceholderScreen("全部日志")
}

@Composable
fun DutyFlowScreen(id: String) {
    PlaceholderScreen("流程")
}

@Composable
fun DutyFlowRunScreen(id: String) {
    PlaceholderScreen("运行详情")
}
