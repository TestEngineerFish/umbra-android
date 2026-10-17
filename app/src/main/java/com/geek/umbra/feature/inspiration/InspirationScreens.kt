// 灵感模块的页面入口。路由分发表（navigation/RouteScreens.kt）只调这里的函数；函数签名不要改。
package com.geek.umbra.feature.inspiration

import androidx.compose.runtime.Composable

/** 灵感列表（工具页「灵感」卡推入）。本机优先：未登录也能用。实现在 InspirationViews.kt。 */
@Composable
fun InspListScreen() {
    InspListView()
}

@Composable
fun InspDetailScreen(id: String) {
    InspDetailView(id)
}

/** id == null 新建；带 id 进来也能编（兼容旧入口）。 */
@Composable
fun InspEditScreen(id: String?) {
    InspEditView(id)
}
