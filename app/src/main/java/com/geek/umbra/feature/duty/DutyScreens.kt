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

/** 一个例程（只读 + 一键跑，iOS DutyFlowView；批次 263 起界面上叫「例程」，代码标识仍是 flow）。 */
@Composable
fun DutyFlowScreen(id: String) {
    DutyFlowView(id)
}

/** 一次例程运行的详情（iOS DutyFlowRunView）。 */
@Composable
fun DutyFlowRunScreen(id: String) {
    DutyFlowRunView(id)
}

/** 例程列表（执勤页「例程」那一行推进来，iOS DutyFlowsView）。 */
@Composable
fun DutyFlowsScreen() {
    DutyFlowsView()
}

/** 关注列表（执勤页「关注」那一行推进来，iOS DutyFollowsView）。 */
@Composable
fun DutyFollowsScreen() {
    DutyFollowsView()
}

/** 规矩（执勤页「规矩」那一行推进来，iOS DutyRulesView）。 */
@Composable
fun DutyRulesScreen() {
    DutyRulesView()
}
