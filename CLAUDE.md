# UmbraAndroid 工程约定

Kotlin + Jetpack Compose，**minSdk 28 / targetSdk 37**，AGP 9.1.0（为兼容 sam 的 Android Studio 2025.3 暂不用 9.4.1，见 libs.versions.toml 注释），Kotlin 2.4.21。
功能与 iOS 端（`../UmbraiOS`）对齐，界面复用 iOS 的设计稿与 token。
**总入口：`docs/Android开发文档.md`**（技术选型、平台映射、功能清单与状态）；逐模块规格在 `docs/reference/`。

## 目录

```
app/src/main/java/com/geek/umbra/
├── app/            Application、MainActivity、AppGraph（依赖容器）、SessionGuard、FeatureGraph
├── designsystem/   token（UmbraTokens.kt）、主题、图标、通用组件、UmbraScreen 页面骨架
├── navigation/     Route（路由表）、Router（每 Tab 一条栈）、AppShell（外壳）、RouteScreens（分发表）、DeepLinks
├── net/            Prefs、SecureStore、UmbraHttp（统一出口）、AuthService、ChatSocket、NetRules（纯判据）
└── feature/<功能>/  XxxScreens.kt（页面入口，签名固定）、XxxModule.kt（守卫登记）、其余文件自建
```

包与 iOS 目录一一对应：`feature/chat` ↔ `UmbraiOS/UmbraiOS/Features/Chat`。移植时打开同名 Swift 文件逐段对照。

## 移植规则

1. **以 iOS 源码为准**。`docs/reference/` 是盘点摘要，有出入看 Swift 原文。照搬的规则在注释里写出处（`// iOS: TaskRetryRules.swift:42`）。
2. **纯判据**（iOS 的 `*Rules.swift` / `*Kit.swift`）翻成不 import android.* 的纯 Kotlin（`feature/x/XxxRules.kt`），
   在 `app/src/test/java/com/geek/umbra/...` 配 JUnit 用例，输入输出照抄 iOS `Tools/swifttests/*Tests.swift`。
3. **页面入口签名不改**：`XxxScreens.kt` 里的函数由 `navigation/RouteScreens.kt` 调用。
   新增路由要同时改 `Route.kt`（四张穷举表都要归类）和 `RouteScreens.kt`。
4. **页面骨架一律 `UmbraScreen`**（列表页）或 `UmbraPlainScreen`（聊天页这类自排版的页）。根页 `onBack = null`，推入页 `onBack = { AppGraph.router.back() }`。
5. **不许字面量颜色 / 字号 / 圆角**：从 `UmbraTheme.colors`、`UmbraType`、`UmbraDimens` 取。前景文字用 `*Text` 那一支（`orangeText`、`dangerText`、`warningText`），实心色只给图标 / 描边 / 实底。
6. **图标**用 `UmbraIcon(UmbraIconPaths.xxx, tint)`（与 iOS 同一份 path）；没有的加进 `UmbraExtraIcons`。不用 Material Icons。
7. **网络只走 `AppGraph.http`**（`get<T>` / `send<T>` / `call` / `raw`）。`get` 返回 null = 没拉到，与空列表严格区分。
   后台性质的请求（轮询、同步）包 `withContext(BackgroundWork)`。记账接口不用自己带 `ledger=`。
8. **长连接**：`AppGraph.socket.frames` 按 `type` 过滤；发帧 `AppGraph.socket.send(json)`，返回 false = 没发出去，必须如实告诉用户。
9. **联网功能的开 / 关**登记在自己的 `XxxModule.register(guard)` 里；本地功能（提醒、保险箱、常用语、记账生活账本、灵感本机账本）不看登录。
10. **弹窗 / 吐司 / 底部弹层**走 `AppGraph.router.confirm(...)` / `showToast(...)` / `present(UmbraSheet)`。破坏性操作必过确认弹窗。
11. **本地持久化**：JSON 文件放 `context.filesDir/<功能>/`，用 `UmbraJson`；读坏的文件改名 `.corrupt-<时间>` 留底再从空开始。小开关用 `AppGraph.prefs`（新键在 `Prefs.K` 里加，名字沿用 iOS 键名）。
12. **文案**：与 iOS 逐字一致。iOS 走 `L("x.y")` 的，用 `stringResource(R.string.x_y)`（`res/values/strings_ios.xml` 由 `tools/gen_strings.py` 生成，**不要手改**）；iOS 写死中文的，Kotlin 里也写中文字面量。
13. 每个类、函数、模块级常量上方写中文注释，说明**为什么**；踩过的坑写进注释。业务类型不带 `Umbra` 前缀，只有 designsystem 里的视觉基元带。
14. Android 实现不了的（灵动岛、轻点背面、系统身份库…）在代码里留一句注释，并在 `docs/Android开发文档.md` 第五节标 ⛔。

## 构建与验证

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin     # 最快的编译检查
./gradlew :app:testDebugUnitTest      # 纯判据用例
./gradlew :app:assembleDebug          # 出 APK
./gradlew :app:assembleRelease        # R8 压缩混淆；改了序列化模型 / 加了依赖必须编一次并装到模拟器上跑
```

release 包本机验证（调试证书签名，同包名要先卸载 debug 包）：
`build-tools/37.0.0/zipalign -p 4` → `apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android` → `adb install`。
R8 的问题只在运行时暴露（序列化类被裁、反射找不到），所以「编过」不算数。

模拟器：AVD `umbra_test`（API 34 arm64）。保险箱页面开了 FLAG_SECURE，截图是黑的，用 `uiautomator dump` 读界面。
Compose 坑：组合函数里读 `StateFlow.value` / Store 辅助函数不会订阅；`val x by flow.collectAsState()` 不读 x 也不订阅。

生成脚本（iOS 改了图标 / 文案后重跑）：`python3 -I tools/gen_icons.py`、`python3 -I tools/gen_strings.py`。在 worktree 里跑要加 `UMBRA_IOS=<UmbraiOS 路径>`。

## 提交

验证通过后直接 commit 并 push（远程已建），中文 commit 信息。
