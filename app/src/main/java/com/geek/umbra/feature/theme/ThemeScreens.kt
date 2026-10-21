// 主题模块的页面入口（由 navigation/RouteScreens.kt 调用，签名固定）。
// 主题是本机偏好，不在会话守卫上登记（没有 ThemeModule）：没登录也能换默认 / 已授权的主题；许可证的登录 / 登出处理在 ThemeStore 里盯 auth.login。
package com.geek.umbra.feature.theme

import androidx.compose.runtime.Composable

/** 「我 › 外观」（iOS `.setAppearance`）：外观三档 + 主题卡片（试用、换、卸）。不要登录。 */
@Composable
fun AppearanceScreen() = AppearancePage()

/** 商城里一个主题的详情（iOS `.setShopTheme(id:)`）。数据在服务端，要登录（同商城）。 */
@Composable
fun ShopThemeScreen(id: String) = ShopThemePage(id)
