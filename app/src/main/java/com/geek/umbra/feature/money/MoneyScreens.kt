// 记账模块的页面入口。路由分发表（navigation/RouteScreens.kt）只调这里的函数，函数签名不要改。
// 每个入口只转给同名 iOS 视图的移植（各自的文件），这里不放页面逻辑。
package com.geek.umbra.feature.money

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

/** 记账首屏（统计）。iOS MoneyViews.swift UmbraMoneyHomeView */
@Composable
fun MoneyHomeScreen() = MoneyHomeView()

/** 流水。iOS MoneyViews.swift UmbraMoneyListView */
@Composable
fun MoneyListScreen() = MoneyListView()

/** 记一笔 / 编辑一笔。iOS MoneyAddView.swift */
@Composable
fun MoneyAddScreen(id: String?, focusAccount: Boolean) = MoneyAddView(id, focusAccount)

/**
 * 分类管理。经营账本换一张**只读科目表** —— 判据取服务端给的 readonly（MoneyStore.catsAreReadonly），
 * 不取「当前账本是不是 business」。iOS AppShell.swift:510
 */
@Composable
fun MoneyCatsScreen() {
    val cats by MoneyStore.cats.watch()
    if (MoneyLedgerRules.readonlyBatch(cats.map { it.isReadonly })) MoneyAccountsView() else MoneyCatsView()
}

/** 一个分类的详情（子类、换槽、停用、合并、删除）。iOS MoneyCatsView.swift UmbraMoneyCatDetailView */
@Composable
fun MoneyCatScreen(slug: String) = MoneyCatDetailView(slug)

/** 记账设置。iOS MoneySettingsView.swift */
@Composable
fun MoneySettingsScreen() = MoneySettingsView()

/** 周期记账列表。iOS MoneyRecurViews.swift UmbraMoneyRecurListView */
@Composable
fun MoneyRecurScreen() = MoneyRecurListView()

/** 周期规则编辑。iOS MoneyRecurViews.swift UmbraMoneyRecurEditView */
@Composable
fun MoneyRecurEditScreen(id: String?) = MoneyRecurEditView(id)
