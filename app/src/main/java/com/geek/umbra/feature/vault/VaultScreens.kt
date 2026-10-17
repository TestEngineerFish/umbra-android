// 保险箱模块的页面入口。路由分发表（navigation/RouteScreens.kt）只调这里的函数；签名不改。
// 每一页都包在 [VaultFrame] 里：FLAG_SECURE（遮罩开着时）、按下即 touch（自动锁定计时）、输入框复制改道敏感剪贴板。
package com.geek.umbra.feature.vault

import androidx.compose.runtime.Composable

@Composable
fun VaultHomeScreen() = VaultFrame { VaultHomeContent() }

@Composable
fun VaultCreateScreen() = VaultFrame { VaultCreateContent() }

@Composable
fun VaultKeyScreen() = VaultFrame { VaultKeyContent() }

@Composable
fun VaultRecoverScreen() = VaultFrame { VaultRecoverContent() }

@Composable
fun VaultRecordScreen(id: String, find: String) = VaultFrame { VaultRecordContent(id, find) }

@Composable
fun VaultEditScreen(id: String?) = VaultFrame { VaultEditContent(id) }

@Composable
fun VaultGenScreen() = VaultFrame { VaultGenContent() }

@Composable
fun VaultCheckScreen() = VaultFrame { VaultCheckContent() }

@Composable
fun VaultGroupsScreen() = VaultFrame { VaultGroupsContent() }

@Composable
fun VaultProfilesScreen() = VaultFrame { VaultProfilesContent() }

@Composable
fun VaultTrashScreen() = VaultFrame { VaultTrashContent() }

@Composable
fun VaultImportScreen() = VaultFrame { VaultImportContent() }

@Composable
fun VaultSettingsScreen() = VaultFrame { VaultSettingsContent() }

@Composable
fun VaultPwdScreen() = VaultFrame { VaultPwdContent() }

@Composable
fun VaultAutofillScreen() = VaultFrame { VaultAutofillContent() }
