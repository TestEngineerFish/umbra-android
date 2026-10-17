// 执勤模块的页面入口。路由分发表（navigation/RouteScreens.kt）只调这里的函数；函数签名不要改（改了要同步改分发表）。
package com.geek.umbra.feature.duty

import androidx.compose.runtime.Composable

/** 「我 › 执勤」首页（iOS DutyHomeView）。 */
@Composable
fun DutyScreen() {
    DutyHomeView()
}

/** 编辑 / 新建一条（id = null 是新建，iOS DutyEditView）。 */
@Composable
fun DutyEditScreen(id: String?) {
    DutyEditView(id)
}

/** 全部日志（iOS DutyLogView）。 */
@Composable
fun DutyLogScreen() {
    DutyLogView()
}

/** 一个流程（只读 + 一键跑，iOS DutyFlowView）。 */
@Composable
fun DutyFlowScreen(id: String) {
    DutyFlowView(id)
}

/** 一次流程运行的详情（iOS DutyFlowRunView）。 */
@Composable
fun DutyFlowRunScreen(id: String) {
    DutyFlowRunView(id)
}
