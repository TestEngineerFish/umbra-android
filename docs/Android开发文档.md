# Umbra Android 开发文档

最后更新：2026-10-22（技术债五项及三条余项收口）· 参照 iOS 端 `UmbraiOS` 0464ea7（逐功能对齐到的 iOS 提交写在第五节各行）

**这一份是 Android 端的总入口。** 技术选型、工程约定、iOS→Android 平台映射、功能清单与实现状态都在这里；
逐模块的接口字段、业务规则、页面细节在 `docs/reference/` 下四份盘点里（从 iOS 源码逐文件读出来的）：

| 文件 | 内容 |
|---|---|
| `reference/01-网络层与接口契约.md` | 服务端地址、登录（local / OIDC+PKCE）、会话失效、守卫、全部 HTTP 接口、`/ws/chat` 全部帧、深链、推送、22 条坑 |
| `reference/02-聊天-任务-灵感-使用记录-远程-工具.md` | 聊天（流式、卡片、id 认领、图片）、任务、灵感（本机优先同步）、使用记录、远程控制（WebRTC 信令）、工具页 |
| `reference/03-导航-我-知识库-执勤-设计系统.md` | 完整导航树、「我」下全部页面、知识库、执勤、**设计 token 全部取值**、多语言 |
| `reference/04-提醒-记账-保险箱-常用语-扩展.md` | 提醒（同步、重复推进）、记账（本机账本、金额闸门、CSV）、**保险箱加密逐字节规格**、常用语、三个扩展 |

---

## 一、定位

Umbra =「AI 秘书（服务端）+ 一台或多台执行设备（PC）」。手机端不是 PC 的缩小版，而是**随身的那一半**：
收「有事找你」、随手记（提醒 / 灵感 / 记账）、随手查（保险箱 / 任务进度 / 常用语）、随手答（问答卡 / 执行前确认）。

Android 端**功能与 iOS 端对齐**，服务端完全共用（同一套接口、同一条 `/ws/chat`），
数据格式与 iOS / PC 逐字节互通（保险箱密文、记账导出 CSV、提醒重复推进都有跨端夹具）。

## 二、技术栈（2026-10-17 调研，均为最新稳定版）

版本取自 Google Maven / Maven Central 元数据，写在 `gradle/libs.versions.toml` 一处。

| 项 | 选型 | 版本 | 理由 |
|---|---|---|---|
| 语言 | Kotlin | 2.4.21 | 要求 |
| 构建 | Gradle / AGP | 9.8.1 / 9.1.0 | AGP 9 内置 Kotlin 支持。**暂用 9.1.0**：sam 的 Android Studio 2025.3 最高认 9.1；装 Studio Quail 4 / Rabbit 1 后升回 9.4.1 并删 `gradle.properties` 里的 `suppressUnsupportedCompileSdk` |
| SDK | compileSdk / targetSdk / minSdk | 37 / 37 / 28 | 37 = Android 17 最新稳定；28 起 BiometricPrompt、AutofillService、Keystore AES-GCM 完整可用，覆盖 >95% |
| JDK | 构建用 Android Studio 自带 JBR 21，字节码目标 17 | — | |
| UI | Jetpack Compose（BOM）+ Material 3 | 2026.09.00 / M3 1.4.0 | 原生声明式 UI，与 SwiftUI 心智同构 |
| 导航 | Navigation 3 | 1.2.0 | 返回栈是普通的可观察 List，与 iOS `Router.paths`（每 Tab 一条数组）同构 |
| 并发 | Kotlin Coroutines / Flow | 1.11.0 | 对应 Swift async/await + Combine |
| 序列化 | kotlinx.serialization | 1.11.0 | 宽松解码（`ignoreUnknownKeys`、`coerceInputValues`）对应 iOS 的手写宽松 Decodable |
| 网络 | OkHttp（HTTP + WebSocket） | 5.5.0 | 一个库同时覆盖 HTTP 与 WS；**不用 Retrofit**：iOS 的统一出口有大量自定义分档逻辑，手写客户端更好照搬 |
| 本地存储 | DataStore Preferences + JSON 文件 | 1.2.1 | iOS 用 UserDefaults + JSON 文件 + 合并规则，同形存储才能让合并规则逐条照翻；**不用 Room** |
| 加密存储 | Android Keystore（AES-GCM 包裹） | 系统 | 对应 Keychain；不用已废弃的 security-crypto |
| 生物识别 | androidx.biometric | 1.1.0 | BiometricPrompt + CryptoObject |
| 图片 | Coil 3 | 3.6.3 | |
| 后台 | WorkManager | 2.12.0 | 同步补推、提醒重排兜底 |
| 小组件 | Glance AppWidget | 1.2.0 | 对应 WidgetKit |
| WebRTC | io.github.webrtc-sdk:android | 150.7871.01 | 远程控制（控制端 answerer） |
| 依赖注入 | 手写 `AppGraph` | — | iOS 端本身是若干单例 Store；手写容器最直接，省掉 Hilt/KSP 的编译成本与 AGP 9 兼容风险 |

## 三、工程结构与约定

```
UmbraAndroid/
├── app/src/main/java/com/geek/umbra/
│   ├── app/          Application、MainActivity、AppGraph（依赖容器）、会话守卫
│   ├── designsystem/ token（颜色/字号/间距）、主题、通用组件（三态页、左滑行、输入 / 说明弹层…）—— 对应 iOS DesignSystem/
│   ├── navigation/   路由表、Router（每 Tab 一条栈）、外壳、深链 —— 对应 iOS Navigation/
│   ├── net/          配置、安全存储、HTTP 统一出口（含按字节读、上传、带鉴权的取图）、reason_kind 判定、鉴权、会话失效、WebSocket —— 对应 iOS Networking/
│   ├── model/        跨功能数据模型
│   ├── feature/<功能>/  每个功能的页面 + 状态 + 纯判据（*Rules.kt）
│   └── util/         真被两个以上功能用到的工具
├── app/src/test/     纯判据的 JUnit 用例（照搬 iOS Tools/swifttests 的输入输出）
└── docs/
```

1. **包与 iOS 目录一一对应**：`feature/chat` ↔ `Features/Chat`。移植时打开同名文件对照。
2. **命名**：只有 `designsystem/` 里可复用的视觉基元带 `Umbra` 前缀（`UmbraButton`、`UmbraColors`），理由同 iOS：
   去掉会和 Compose / Material 撞名（`Button`、`Card`、`Switch`）。业务类型不带前缀。
3. **注释**：类、函数、模块级常量上方写中文注释，说明**为什么**这么写；踩过的坑写进注释。
   照搬 iOS 规则时注明出处（`// iOS: TaskRetryRules.swift:42`）。
4. **纯判据**：iOS 的 `*Rules.swift` / `*Kit.swift` 一律翻成**不依赖 Android 的纯 Kotlin**（`feature/x/XxxRules.kt`），
   配 JUnit 用例，输入输出照抄 iOS 的 `Tools/swifttests/*Tests.swift`。
5. **页面里不许出现字面量颜色 / 字号 / 圆角**，一律从 `designsystem` 取。前景色走 `*Text` 那一支。
6. **「没拉到」= null，「空」= emptyList()**，全工程不许混。
7. **文案**：与 iOS 逐字一致。iOS 走 `L("x.y")` 的用 `stringResource(R.string.x_y)`（`res/values*/strings_ios.xml` 由 `tools/gen_strings.py` 从 iOS 生成，**不手改**）；
   iOS 写死中文的，Kotlin 里也写中文字面量；Android 独有的句子放手写的 `strings.xml` / `strings_*_android.xml` / `strings_share.xml` / `strings_widget.xml`。
   与 PC / iOS 逐字一致的句子（附件失败、reasonKind、任务重试、删除后果、CSV 列头）不许意译。
8. **通用件先找 designsystem / net**：页面三态 `umbraScreenPhase`、左滑 `UmbraRevealSwipeRow`、一格输入 `UmbraPrompt`、单钮说明 `UmbraNotice`、
   选择列表 `UmbraChoice`、「附件已删除」`UmbraAttGonePlaceholder`、
   取二进制 `UmbraHttp.bytes`、带鉴权的图 `UmbraImages.loader`、`reason_kind` 判定 `ReasonRules`、设备列表 `DevicesStore` —— 都只有一份，别在模块里再抄。

### 构建与验证

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin   # 最快的编译检查
./gradlew :app:testDebugUnitTest    # 纯判据用例
./gradlew :app:assembleDebug        # 编出 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # R8 压缩混淆；改了序列化模型 / 加了依赖必须编一次并装到模拟器上跑
./gradlew :app:lintDebug            # Android Lint
```

release 包本机验证（调试证书签名，同包名要先卸载 debug 包）：`build-tools/37.0.0/zipalign -p 4` → `apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android` → `adb install`。
R8 的问题只在运行时暴露（序列化类被裁、反射找不到），所以「编过」不算数。模拟器 AVD `umbra_test`（API 34 arm64），开着就复用。

## 四、iOS → Android 平台映射

| iOS | Android | 备注 |
|---|---|---|
| SwiftUI TabView + 每 Tab NavigationStack | Compose `NavigationBar` + 每 Tab 一条 Navigation 3 返回栈 | 再点当前 Tab 回根；推入页隐藏底栏 |
| 系统左缘返回 | 系统返回手势 / Predictive Back | Android 平台惯例 |
| `.swipeActions` 左滑 | `UmbraRevealSwipeRow`：`SwipeToDismissBox` 拒绝滑走、摊开胶囊钮（只露按钮不自动执行，可设同一时刻只开一行） | 全 App 一份（批次 201）；破坏性操作一律走确认弹窗；钮上图标只用 umbra-icons.json 登记过的名字（批次 283：重试 refresh-cw、找回 undo、改名 / 编辑 edit、换图标 shapes、停止周期 ban），`SwipeIconsTest` 守着 |
| 系统 alert / confirm | `router.confirm(UmbraAlert)`（M3 `AlertDialog`） | 固定两颗钮 |
| alert 里塞一格 TextField | `UmbraPrompt`（底部弹层：标题 + 说明 + 一格 + 取消 / 确认） | UmbraAlert 没有输入框 |
| `UmbraAlert(showsCancel: false)` 单钮说明 | `UmbraNotice`（底部弹层，正文能滚） | |
| 底部弹层 / 选择器 | `router.present(UmbraSheet)`（M3 `ModalBottomSheet`） | |
| UIDatePicker 滚轮 | 自绘 `UmbraDateTimePanel`（月历 + 时分滚轮，照设计 token） | |
| UserDefaults | DataStore Preferences | 键名照搬 |
| Keychain（ThisDeviceOnly） | Keystore 包裹 + 排除 Auto Backup | |
| App Group（扩展共享） | 不需要：小组件 / 自动填充 / 分享都在同一个 App 进程空间 | |
| UNUserNotification 本地通知 | NotificationManager + AlarmManager 精确闹钟 | Android 14+ 精确闹钟需用户授权 |
| APNs 推送 | FCM / 厂商推送 | **待服务端**，见第六节 |
| WidgetKit 小组件 | Glance AppWidget | |
| ActivityKit 灵动岛 / 锁屏实况 | 进行中通知（Android 16 `ProgressStyle` / Live Updates） | 灵动岛本身**无法实现** |
| AutoFill Credential Provider | `AutofillService`（+ API 34 Credential Manager） | |
| Share Extension | `ACTION_SEND` 透明 Activity | |
| App Intents / Siri | App Shortcuts（`shortcuts.xml`）+ 桌面快捷方式 | 只能部分对等 |
| 轻点背面 | 无系统对应 | 说明页改讲桌面快捷方式 |
| Face ID | BiometricPrompt（BIOMETRIC_STRONG） | |
| UIPasteboard localOnly + 过期 | `EXTRA_IS_SENSITIVE` + 60 秒后自清 | |
| 后台遮罩窗口 | `FLAG_SECURE` | 更彻底，还防截屏 |
| SFSpeechRecognizer | `SpeechRecognizer` | 无识别服务的机型（多数国行无 GMS）不可用，按钮置灰并说明 |
| QuickLook | 下载到缓存 + `ACTION_VIEW` + FileProvider | |
| PhotosPicker / 相机 | Photo Picker / `TakePicture` | |
| WebRTC（stasel） | webrtc-sdk android | |

## 五、功能清单与状态

图例：✅ 已实现　🟡 已实现但需真机 / 联网验证　⛔ Android 无法实现（已用替代方案或不做）　⏳ 待服务端

> 2026-10-17 一期移植完成：全部模块已从 iOS 逐文件移植，全工程 374 条 JUnit 用例全过（纯判据照抄 iOS `Tools/swifttests` 与 PC 共享夹具）。
> 2026-10-22：之后补了主题、用电脑转换、就地用重置卡，并收口第五节的技术债五项；现在全工程 **401 条用例全过**，release 包（R8）在模拟器上走过记账 / 提醒 / 外观页。
> 模拟器（Android 14）单机模式实测过：登录屏、单机模式、四 Tab 外壳、提醒新建与闹钟排程、日期面板、工具页、记账记一笔、保险箱建库 / 解锁、App 内切语言、深链冷启动。
> **Mac 到服务器 443 被 reset（直连、走代理都一样），所有联网功能只验证到编译与用例，需要接真机（蜂窝网络）走一遍** —— 标 🟡 的就是这些。

### 基础
| 功能 | 状态 | 说明 |
|---|---|---|
| 工程骨架 / 版本目录 / 构建 | ✅ | AGP 9.1.0 + Kotlin 2.4.21 + compileSdk 37（Android Studio 2025.3 可直接打开） |
| 设计系统 token、图标、通用组件 | ✅ | token / 图标 path / 文案由 `tools/gen_*.py` 从 iOS 生成 |
| 四 Tab 外壳 + 每 Tab 返回栈 + 跨 Tab 跳转 | ✅ | Navigation 3；再点当前 Tab 回根 |
| 深链 `umbra://` | ✅ | 另加 Android 独有 `umbra://inspiration/new`（快捷方式用） |
| 统一出口（请求闸、Bearer、Initiator、ledger、分档、同主机重定向） | ✅ | 用例覆盖 |
| 登录（local） | 🟡 | |
| 登录（OIDC + PKCE，Custom Tabs） | 🟡 | Casdoor 回调白名单需含 `umbra://auth/callback` |
| 会话失效与静默续期 | 🟡 | 闩 + 30 秒冷却有用例 |
| 单机模式 / 会话守卫 | ✅ | 模拟器实测 |
| `/ws/chat` 长连接 | 🟡 | 协议层 ping 只容忍 1 颗没回（iOS 2 颗），弱网多一次重连 |
| 中英双语 + App 内切换 | ✅ | 1708 条 iOS 文案生成（`strings_ios.xml`）；iOS 写死中文的页面英文模式下也是中文（与 iOS 同） |
| 浅色 / 深色 / 跟随系统 | ✅ | |

### 功能模块
| 功能 | 状态 | 说明 |
|---|---|---|
| 聊天 · 会话列表 / 对话 / 流式 / 卡片 / Markdown / 表格 / 长按菜单 | 🟡 | 行内 Markdown 为自写子集（iOS 用系统解析器） |
| 聊天 · 发图（相册 / 拍照 / 文件、进度、续传、识图） | 🟡 | |
| 聊天 · 按住说话 | 🟡 | 依赖系统语音识别服务；**国行无 GMS 机型通常没有，按钮置灰并说明** |
| 提醒（列表 / 详情 / 编辑 / 重复推进 / 附件） | ✅ | 推进与 PC 夹具逐毫秒一致；同步 🟡 |
| 提醒 · 通知与「完成 / 再等 10 分钟」 | ✅ | 精确闹钟需用户授权（Android 14+），未授权退非精确并出横幅 |
| 工具页 | ✅ | |
| 任务（列表 / 详情 / 停止 / 重试 / 接着跑） | 🟡 | 挂起用例与 PC 共享夹具 |
| 灵感（本机优先 + 同步 + 调研 / 整理） | ✅ | 同步 🟡 |
| 记账（生活账本本机、经营账本在线、分类、周期、导出 CSV） | ✅ | 生活账本模拟器实测；经营账本与同步 🟡 |
| 保险箱（加密、建库、解锁、记录 / 节点、TOTP、生成器、体检、回收站） | ✅ | 与 PC `crypto.ts` 逐字节互通（node 生成向量做用例）；同步 🟡；页面开 FLAG_SECURE |
| 常用语（同步、占位展开） | ✅ | 同步 🟡；Android 12 及以下剪贴板没有敏感标记 |
| 我 · 首页 / 设备与能力 / 连接 / 通知 / 通用 / 关于 | ✅ | |
| 通知权限（冷启动问一次；没问过当场弹框，问过才跳设置） | ✅ | 照 iOS 4bad681，`app/NotificationPermission.kt` 一处管；模拟器实测拒绝后不再追问 |
| 账号 / 会员 / 积分 / 商城 / 存储 / 改密码 / 注销 | 🟡 | |
| 就地用重置卡（会员额度 80% 起出［重置］、额度用完的聊天错误卡 / 读图卡 / 任务失败卡 / 聊天任务卡出［用重置卡］+ 补救后「重试任务」） | 🟡 | 照 PC `resetCard.ts` / `QuotaRows.tsx`（设计侧批次 278）：**Android 照卖重置卡，和 PC 同一套**（iOS 32de292 起不卖，只递已有的卡）；用卡、张数、落到商城那一组待真机 |
| 记忆 | 🟡 | |
| 知识库 | 🟡 | 原文交系统里能打开该类型的 App（没有 QuickLook） |
| 执勤（含聊天里的早报卡、提议卡撤销、例程 / 关注 / 规则子页、今天预算） | 🟡 | 已对齐 iOS 4bad681；联网部分待真机 |
| 使用记录（活跃上报 + 方格图） | 🟡 | |
| 远程控制（WebRTC 控制端、快照降级、文件面板） | 🟡 | 已对齐 iOS 4bad681（规则 15 条用例）；服务端未部署，信令与视频全链路待真机联调；API 28–29 本机认证退到 BIOMETRIC_WEAK；文件面板「分享…」「存到「下载」」是 Android 自有文案 |
| 用电脑转换（分享进来的文件 / 图、设备详情页一行、三段进度、结果存文件 / 相册 / 分享、横条续看、「作业没了」两档） | 🟡 | 对齐 iOS 58cf4be / 63bc3c5 / de808df / 5157d4d / 5279537（批次 269 §四、271、272、273）；判据 66 条对拍；转完推送 ⏳（FCM 待服务端，回 App 看横条）；全链路待真机 |
| 主题（插件化：配色引擎、外观页、试用、回落卡、商城「主题」栏、装包验签、许可证、小组件跟随） | 🟡 | 对齐 iOS 02cf7a9 / 32de292；判据 159 条对拍（含 umbra-plugins 25 条跨语言用例与测试向量）；内置只有「默认」，海盐 / 石墨从商城装；**Android 卖付费主题**（积分买断，iOS 不卖）；许可证按账号（登出 / 换号清掉，读取核 sub）；Ed25519 手写（minSdk 28 没有系统实现）；拉目录 / 买 / 装 / 真签名待真机 |

### 系统集成
| 功能 | 状态 | 说明 |
|---|---|---|
| 小组件：任务（2×2）/ 今日提醒（4×2） | ✅ | Glance；任务进度画进度条（Glance 画不了描边弧） |
| 任务进行中通知 | ✅ | Android 16+ 申请 Live Update；**灵动岛本身没有** |
| 灵动岛 / 锁屏 Live Activity | ⛔ | 换成进行中通知 |
| 密码自动填充 | ✅ | AutofillService；需用户在系统设置里选 Umbra |
| 系统身份库（QuickType 预登记） | ⛔ | Android 自动填充没有预登记机制 |
| 分享到灵感 | ✅ | 透明 Activity；未登录不发请求 |
| 桌面快捷方式（记一笔、记灵感） | ✅ | 替代 App Intents / Siri |
| Siri / App Intents | ⛔ | 换成静态快捷方式 |
| 轻点背面 | ⛔ | 无系统 API；说明页改讲快捷方式 |
| 小组件时间线 | ⛔ | 换成 AlarmManager 只排下一个刷新时刻 |
| 剪贴板到期自清 | ⛔ | iOS 由系统清；Android 只能进程活着时 60 秒后清 |
| 服务端推送（执勤推送、有事找你） | ⏳ | 见第六节 |

### 与 iOS 有意不同的地方（实现时发现的 iOS 问题，已在 Android 修正，待 iOS 跟进）
- 执勤 `DutyRules.toggle`：iOS 只从原三块里挑，M2 新增的「两小时内的提醒」「重要关注的新动向」在编辑器里勾不上（DutyModels.swift:1040-1046）。
- 保险箱编辑页新建记录：iOS 每次自动保存都现取新 id，可能产生重复记录；Android 只取一次。
- 保险箱恢复页 Secret Key 格式提示正则是旧形制，与真实的 `U1-…` 对不上（两端都画红框但不挡按钮）。
- 单机模式下保险箱把请求闸的调试描述当离线原因显示；Android 改为未登录时安静回落本机。
- 主题许可证：iOS 本机许可证整机一份、读取时不核账号（同 PC #128）；Android 登出 / 换号即清、读取核 `sub` 与记下的账号。
- 主题试用到点：iOS 开着编辑页时多等 3 秒再退（SwiftUI 换根视图身份会丢草稿）；Compose 换配色只重组、不丢状态，Android 不用等。

### 技术债

**2026-10-22 已收口的五项**（每项单独提交，纯重构为主；界面上的变化另报 Claude Design）：

| 项 | 提交 | 现在在哪 |
|---|---|---|
| `reason_kind` 判定聊天、任务各一份 | e108d7a | `net/ReasonRules.kt`（含 `TaskFailAction`） |
| 聊天 `ChatTaskKit` 与任务 `TaskRetryRules` / `TaskSuspendRules` 重复 | 22a8da0 | 只留任务包那份，聊天卡直接用 |
| `UmbraHttp.raw` 只回字符串，二进制各模块自己拼请求；`Uploads.upload` 不交原始结果 | 78f5a21 | `UmbraHttp.bytes()`（令牌只给自己的服务端、可设上限 / 进度 / 跳别家 https 去登录头）、`Uploads.send` |
| 设备列表聊天与「我」各存一份 | 80ad58d | `feature/me` 的 `DevicesStore` |
| 各模块自补的组件 | 65e066f / 983c459 / b9d11b9 / a80fde3 | 三态 `designsystem/UmbraStates.kt`、取图 `net/UmbraImages.kt`、弹层 `designsystem/UmbraSheets.kt`、左滑 `designsystem/UmbraRevealSwipeRow.kt` |

**同日又收口的三条余项**：

| 项 | 提交 | 现在在哪 |
|---|---|---|
| 「附件已删除」占位聊天、提醒、记账各一份 | c5d5e6c | `designsystem/UmbraAttGone.kt`（照 iOS `UmbraAttGonePlaceholder`） |
| 选择弹层四份（我 / 记账 / 执勤 / 保险箱） | 87aada8 | `designsystem/UmbraSheets.kt` 的 `UmbraChoice`（照 iOS `UmbraSheetLayer`） |
| 提醒列表、任务详情、记忆、记账设置、存储用量自写三态 | 173e4c0 | 都走 `UmbraScreenPhase` / `UmbraPhaseFailedCard`；任务详情的四档判据仍在 `TaskDetailRules`（多「不存在」一档，同 iOS） |

## 六、需要服务端配合

1. **推送**：服务端只有 APNs（执勤推送、有事找你、用电脑转换转完的那一条 `convert_job` 都走它）（`/push/apns/register`、`apns.py`）。Android 需要：
   `/push/fcm/register`（或泛化成带 `platform`）、放宽令牌校验（FCM 令牌含 `: - _`）、FCM 发送器、令牌表。
   **国行设备没有 GMS 时 FCM 不可用**，要么接厂商通道（华为 / 小米 / OPPO / vivo / 荣耀）或统一推送联盟，要么常驻前台服务保活长连接（耗电，不推荐）。
   在服务端接好之前：Android 靠「App 在前台时的 `/ws/chat` + 本地通知」，提醒是本机闹钟，不受影响。
2. **端标识**：WS `src=android`、`rc_request.client_kind="android"`、`source_channel="android"` 是否被服务端接受，接入前核对 `routes/ws_chat.py` 等。
3. **OIDC 回调**：Casdoor 应用的回调白名单要包含 `umbra://auth/callback`（与 iOS 相同则无需改）。

## 七、设计稿

**决定：复用 Claude Design 上的 iOS 稿（`Umbra iOS 端.dc.html` 与设计交接包 token），不另画一套 Android 稿。**

- 视觉层（色板、字号、间距、圆角、组件形制、文案、语气）**完全沿用 iOS 稿**，token 逐值搬到 `designsystem/`。
- 平台交互层按 Android 惯例替换，替换清单见第四节（底栏、返回、底部弹层、对话框、左滑）。
  iOS 稿里的「液态玻璃胶囊底栏」在 Android 上改为不透明的 M3 NavigationBar，色板仍用 Umbra token。
- 平台差异的取舍已由 Claude Design 裁定：第 199 封 → `docs/design/答复实现侧-Android平台差异.md`（十四条 + 全局热区 48dp、字重映射），
  2026-10-17 全部落地（e3a016c），回执第 201 封；201 / 202 / 203 设计侧均已答复并落地，出图在 Claude Design 项目 `Android 端/Pages/平台差异.dc.html`。

## 八、分期

| 期 | 内容 | 状态 |
|---|---|---|
| 一期 | 全部模块从 iOS 移植（基础、聊天、提醒、工具、任务、灵感、记账、保险箱、常用语、我 / 账号 / 记忆 / 知识库、执勤、使用记录、远程控制、小组件、分享、快捷方式、自动填充） | ✅ 2026-10-17 |
| 二期 | 真机联网验证（登录、聊天、同步、远程控制、主题、转换、重置卡）；Claude Design 第 199 封的平台差异裁定落地（✅ e3a016c）；技术债五项收口（✅ 2026-10-22，见第五节） | 真机验证待做 |
| 三期 | 服务端推送（FCM / 厂商通道，待服务端）；上架准备（签名、混淆规则核对、隐私说明） | 待服务端 |
