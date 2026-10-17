# Umbra Android 开发文档

最后更新：2026-10-17 · 参照 iOS 端 `UmbraiOS` 6076afa

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
| 构建 | Gradle / AGP | 9.8.1 / 9.4.1 | AGP 9 内置 Kotlin 支持，不再需要 `kotlin-android` 插件 |
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
│   ├── designsystem/ token（颜色/字号/间距）、主题、通用组件 —— 对应 iOS DesignSystem/
│   ├── navigation/   路由表、Router（每 Tab 一条栈）、外壳、深链 —— 对应 iOS Navigation/
│   ├── net/          配置、安全存储、HTTP 统一出口、鉴权、会话失效、WebSocket —— 对应 iOS Networking/
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
7. **文案**：进 `res/values/strings.xml`（中文默认）与 `res/values-en/strings.xml`。与 PC / iOS 逐字一致的句子（附件失败、reasonKind、任务重试、删除后果、CSV 列头）不许意译。

### 构建与验证

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug        # 编出 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 纯判据用例
./gradlew :app:lintDebug            # Android Lint
```

## 四、iOS → Android 平台映射

| iOS | Android | 备注 |
|---|---|---|
| SwiftUI TabView + 每 Tab NavigationStack | Compose `NavigationBar` + 每 Tab 一条 Navigation 3 返回栈 | 再点当前 Tab 回根；推入页隐藏底栏 |
| 系统左缘返回 | 系统返回手势 / Predictive Back | Android 平台惯例 |
| `.swipeActions` 左滑 | `SwipeToDismissBox`（只露按钮不自动执行） | 破坏性操作一律走确认弹窗 |
| 系统 alert / confirm | M3 `AlertDialog` | |
| 底部弹层 / 选择器 | M3 `ModalBottomSheet` | |
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

图例：✅ 已实现　🚧 部分实现　📋 计划中（本期要做）　⛔ Android 无法实现　⏳ 待服务端

> 这张表随开发推进更新；每一行的细节规格见 `reference/` 对应章节。

### 基础
| 功能 | 状态 | 说明 |
|---|---|---|
| 工程骨架 / 版本目录 / 构建 | ✅ | AGP 9.4.1 + Kotlin 2.4.21 + compileSdk 37 已编通 |
| 设计系统 token 与通用组件 | 📋 | |
| 四 Tab 外壳 + 每 Tab 返回栈 + 跨 Tab 跳转 | 📋 | |
| 深链 `umbra://` | 📋 | |
| 本机配置（DataStore） | 📋 | |
| 统一出口（Bearer、Initiator、ledger、请求闸、分档） | 📋 | |
| 登录（local） | 📋 | |
| 登录（OIDC + PKCE，Custom Tabs） | 📋 | |
| 会话失效与静默续期 | 📋 | |
| 单机模式 / 会话守卫 | 📋 | |
| `/ws/chat` 长连接（ws_ready、心跳、退避、4001） | 📋 | |
| 中英双语 + App 内切换 | 📋 | |
| 浅色 / 深色 / 跟随系统 | 📋 | |

### 功能模块
| 功能 | 状态 | 说明 |
|---|---|---|
| 聊天 · 会话列表 | 📋 | |
| 聊天 · 对话（流式、工具轨迹、Markdown、表格、引用、长按菜单） | 📋 | |
| 聊天 · 卡片（任务 / 完成 / 确认 / 问答 / 求助 / 早报 / 出处） | 📋 | |
| 聊天 · 发图（相册 / 拍照 / 文件、上传进度、识图） | 📋 | |
| 聊天 · 按住说话 | 📋 | 依赖系统语音识别服务，国行无 GMS 机型不可用 |
| 提醒（列表 / 详情 / 编辑 / 重复推进 / 同步） | 📋 | |
| 提醒 · 本地通知与「完成 / 再等 10 分钟」动作 | 📋 | |
| 工具页 | 📋 | |
| 任务（列表 / 详情 / 停止 / 重试 / 接着跑） | 📋 | |
| 灵感（本机优先 + 同步 + 调研 / 整理） | 📋 | |
| 记账（生活账本本机 + 经营账本在线、分类、周期、导出） | 📋 | |
| 保险箱（加密互通、解锁、记录 / 节点、TOTP、生成器、体检、同步） | 📋 | |
| 常用语（同步、占位展开） | 📋 | |
| 我 · 账号 / 会员 / 积分 / 商城 / 存储 / 改密码 / 注销 | 📋 | |
| 我 · 设备与能力 / 连接 / 通知 / 通用 / 关于 / 回收站 | 📋 | |
| 记忆 | 📋 | |
| 知识库 | 📋 | |
| 执勤 | 📋 | |
| 使用记录（活跃上报 + 方格图） | 📋 | |
| 远程控制（WebRTC 控制端、快照降级、文件面板） | 📋 | 最后做，体量最大 |

### 系统集成
| 功能 | 状态 | 说明 |
|---|---|---|
| 小组件：任务（小）/ 今日提醒（中） | 📋 | Glance |
| 任务进行中常驻通知 | 📋 | 替代 Live Activity |
| 灵动岛 | ⛔ | Android 没有对等物 |
| 密码自动填充 | 📋 | AutofillService |
| 分享到灵感 | 📋 | |
| App Shortcuts（记一笔、记灵感） | 📋 | 替代 App Intents / Siri |
| 轻点背面 | ⛔ | 无系统 API |
| 服务端推送（执勤推送、有事找你） | ⏳ | 服务端只支持 APNs，见第六节 |
| 系统身份库（QuickType 提示） | ⛔ | Android 自动填充没有预登记身份的机制，每次由服务实时匹配 |

## 六、需要服务端配合

1. **推送**：服务端只有 APNs（`/push/apns/register`、`apns.py`）。Android 需要：
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
- 这些平台差异的取舍交 Claude Design 裁定（信：`docs/design/交办单-Android端平台差异.md`），裁定前按上面的默认实现。

## 八、分期

| 期 | 内容 |
|---|---|
| 一期（本期） | 骨架 + 设计系统 + 网络 / 鉴权 / 长连接 + 四 Tab 外壳；提醒、工具、任务、灵感、常用语、我 · 设置类页面、登录；聊天主链路（文字、流式、卡片） |
| 二期 | 保险箱（含自动填充）、记账、聊天发图与语音、知识库、记忆、账号 / 商城 / 存储、小组件、分享 |
| 三期 | 执勤、使用记录、远程控制、推送（待服务端） |

每期结束更新第五节的状态列。
