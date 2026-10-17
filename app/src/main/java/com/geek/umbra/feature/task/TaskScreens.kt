// 任务模块的页面入口。路由分发表（navigation/RouteScreens.kt）只调这里的函数；函数签名不要改。
package com.geek.umbra.feature.task

import androidx.compose.runtime.Composable

/** 任务列表（工具页「任务」卡推入）。实现在 TaskViews.kt。 */
@Composable
fun TaskListScreen() {
    TaskListView()
}

/** 任务详情（列表、聊天卡、通知、深链 umbra://task/<id> 都进这里）。 */
@Composable
fun TaskDetailScreen(id: String) {
    TaskDetailView(id)
}
