package com.geek.umbra.designsystem

import androidx.compose.runtime.Composable
import com.geek.umbra.app.AppGraph
import com.geek.umbra.navigation.Tab

/** 还没移植的页面。各功能移植完会替换掉对它的调用；全工程不再引用时删掉这个文件。 */
@Composable
fun PlaceholderScreen(title: String) {
    val router = AppGraph.router
    val isRoot = router.stack().isEmpty() && Tab.entries.any { it.root == router.top }
    UmbraScreen(title = title, onBack = if (isRoot) null else ({ router.back() })) {
        item { UmbraEmptyState(UmbraIconPaths.wrench, "这一页还在移植", hint = "Android 端正在对照 iOS 补齐。") }
    }
}
