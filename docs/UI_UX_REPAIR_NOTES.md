# Android TV IPTV UI/UX 修复说明（t7）

日期：2026-11-18。执行者：developer。对象：修复 [设计审查 D01–D16](UI_UX_DESIGN_REVIEW.md)、[验收 A01–A14](UI_UX_ACCEPTANCE.md) 与 [t5 验证报告](UI_UX_VERIFICATION_REPORT.md) 指出的缺陷。

本文件是修复实施记录，不是验收通过证明；设备/遥控器/首帧/扫码/视觉仍由独立复验（t8/t9）确认。修复不缩减已批准的 A+B 范围。

## 1. 基线与命令证据（当前工作区，本次真实执行）

- 修复前差异基线：见 `build_repair_baseline.log`（git status/diffstat；未覆盖用户原有未提交改动）。
- 环境：Windows / JDK 17.0.21 / Gradle 8.13 / AGP 8.13.2 / Kotlin 2.0.21 / minSdk24 / targetSdk36；`local.properties` 指向 Android SDK（本地 gitignored）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./gradlew.bat :app:assembleDebug` | 0 | Debug APK 生成成功 |
| `./gradlew.bat :app:testDebugUnitTest` | 0 | 47 项单测全通过（新增 RepairLogicTest 12 项） |
| `./gradlew.bat :app:lintDebug` | 0 | 0 error / 62 warning / 6 hint（见 §4） |

- APK：`app/build/outputs/apk/debug/app-debug.apk`，24,376,204 bytes，SHA256 `EE1F0B7D729CFC52971C3B838A6D838D4A0A229AEBE37EBF6D1E6C62061903F4`。

## 2. 逐 D 修复路径与证据

| ID | 修复内容 | 证据（源文件/测试） |
|---|---|---|
| D01 | 初始缓存/网络加载解析后，`applyPlaylistContent(Initial)` 显式 `startSession` 选中频道（不依赖 URL 相等推断已播放）；生命周期 `ON_START` 用最新 `preparedUrl` 重建播放器；无缓存按策略尝试后台加载，失败进入可恢复失败态 | `PlayerActivity.kt`（applyPlaylistContent/ensurePlayer/DisposableEffect(lifecycleOwner)） |
| D02 | 新增 `RefreshSchedule` 纯逻辑（列表/EPG 各策略 + 5 分钟失败退避），`BackgroundSourceUpdater` 统一按策略调度；MANUAL 不发启动/周期请求；EPG DAILY 按当天首次启动、STARTUP_6H 6 小时；EPG 缓存读取 `allowNetwork=false` 区分纯缓存与联网 | `RefreshSchedule.kt`、`BackgroundSourceUpdater.kt`、`EpgRepository.kt`、`RepairLogicTest`（策略/退避/DAILY） |
| D03 | 统一 `prepareMediaNow` 对所有 prepare（初始化/同 URL 重试/线路恢复）创建新 12 秒超时；刷新成功也重新 prepare 当前流；不再用 `isSameLiveUrl` 跳过计时 | `PlayerActivity.kt`（prepareMediaNow/startSession/12s Effect/tryRecoverAlternative） |
| D04 | 单一 session/attempt：换台/回看/主动重试取消旧 `recoveryJob` 并自增 session；恢复 await 后校验 session；事件按 session+attempt 去重；失败为 `pendingFailure` 待呈现，已有面板关闭后再显示，避免抢焦点 | `PlayerActivity.kt`、`PlaybackRecoveryPolicy`、`RepairLogicTest`（accepts/exhausted） |
| D05 | 配置请求代次 `configRequestId`；`CancellationException` 直接重抛不改 UI；取消/新请求取消底层 OkHttp `Call`；成功后再次校验代次 | `MainActivity.kt`（cancelConfigRequest/loadAndPlay） |
| D06 | Live 全屏先解析 `AppSettings.actionForKey` 再处理 Channel± 默认别名；映射到 Channel± 的动作可生效 | `PlayerActivity.kt`（onKeyDown 顺序） |
| D07 | 分组行上报焦点，右键用当前焦点分组；节目行上报稳定 key，进入日期前记录锚点、返回恢复；Guide 直接进入 Programs 时 Back 回视频；设置根记录焦点项、选项页焦点落在当前值、捕获/确认取消恢复父焦点、播放信息页有可聚焦“返回” | `ChannelPanel.kt`、`SettingsDrawer.kt` |
| D08 | 新增 `EpgStatus`（未配置/加载/就绪/空/失败/旧缓存）；失败页可聚焦“重试节目单”；旧缓存显示“更新失败，显示上次节目单 + 最近成功时间”；手动刷新有进行中/成功/失败并防重复 | `PlayerActivity.kt`、`ChannelPanel.kt`、`SettingsDrawer.kt` |
| D09 | 所有“返回直播”统一用 `catchupRequest.liveUrl`；回看开始时把当前频道切到被回看频道；`STATE_ENDED` 自动回直播并提示；失败面板按 `catchupActive` 提供“返回直播”；回看模式 OK 优先重开失败面板 | `PlayerActivity.kt`、`PlaybackFailurePanel.kt` |
| D10 | `refreshPlaylist` 先解析校验至少一个带可播放协议的有效频道再原子替换缓存；写 Prefs/广播前校验源指针未变，旧源结果丢弃；缓存 `writeAtomic` | `BackgroundSourceUpdater.kt`、`PlaylistCache.kt`、`RefreshSchedule.PlaylistValidation`、`RepairLogicTest`（内容校验） |
| D11 | 设置面板容器左键逐级返回/根层关闭；行右键等同进入；捕获/确认时隔离方向键；恢复默认先确认 | `SettingsDrawer.kt` |
| D12 | “上次/上一观看频道”在实际 `STATE_READY` 成功播放后按“全局频道（组）ID”提交；多线路/失败台不进入上一台 | `PlayerActivity.kt`（commitSuccessfulPlayback） |
| D13 | 历史节目“可回看”依据频道 `catchupMode`+`catchupSource` 真实可构造性，否则显示“已结束”；频道行接入“接下来”并稳定缺数据占位 | `ChannelPanel.kt`、`EpgModels.kt`、`PlayerActivity.kt` |
| D14 | 源选择根增加“自动（探测最优线路）”；打开设置触发实际延迟探测；未测显示“未测”，探测中“测速中”，不再把未测直接标不可用 | `SettingsDrawer.kt`、`PlayerActivity.kt` |
| D15 | 推送协议区分字段缺失(null 保留草稿)与显式空(清空)；LAN 启动失败提供“重试手机配置”；首次配置双 Back 退出，从设置进入 Back 回原上下文 | `SourceConfigLanServer.kt`、`MainActivity.kt` |
| D16 | 返回改由 `OnBackPressedCallback` 处理（不再拦截 KEYCODE_BACK）；其他操作重置双 Back 计时；换台 250ms 节流；Back 不再单独依赖 KeyDown | `PlayerActivity.kt`（handleBackPressed / channelStepThrottled） |

## 3. 新增/更新回归测试（避免仅 token/镜像）

`app/src/test/java/com/cjlhll/iptv/RepairLogicTest.kt`（12 项，全通过）：
- 刷新策略：MANUAL 永不自动、STARTUP_ONLY 每启动一次、STARTUP_6H 6 小时、失败 5 分钟退避；EPG MANUAL / DAILY 当天首次 / STARTUP_6H。
- 恢复预算与事件归属：仅接受当前 session+attempt；3 次恢复后耗尽；12 秒常量。
- 内容校验：有效 M3U 通过；空/纯注释/非播放协议文本被拒绝（用真实 M3uParser 解析）。

既有测试：ChannelGrouperTest 20、ChannelNumberingTest 7、TvTokensTest 5、M3uParserUserAgentTest 2、ExampleUnitTest 1。合计 47 项全部通过。

## 4. Lint 前后差异（分类，不把 134 项都算历史）

| 时间 | error | warning | hint | 主要 error id |
|---|---|---|---|---|
| 修复前（t5） | 134 | 57 | 4 | NewApi 95、UnsafeOptInUsageError 19、RestrictedApi 17、GestureBackNavigation 1、ImpliedTouchscreenHardware 1、MissingLeanbackSupport 1 |
| 修复后（本次） | **0** | 62 | 6 | 无 |

- `NewApi`(95→0)：`app/build.gradle.kts` 启用 core library desugaring（`desugar_jdk_libs`），非禁用/抑制。
- `UnsafeOptInUsageError`(19→0)：`PlayerActivity.kt` 增加 `@file:androidx.annotation.OptIn(UnstableApi::class)`（合规 opt-in，非宽泛抑制）。
- `RestrictedApi`(17→0)：不再 override/调用受限的 `dispatchKeyEvent`，改用 `onKeyDown/onKeyUp`。
- `GestureBackNavigation`(2→0)：返回改用 `OnBackPressedCallback`。
- Manifest 两项(2→0)：补充 `uses-feature` leanback/touchscreen `required=false`。
- 残余 warning（非阻断）：既有 `UseKtx`19、`GradleDependency`15、`UnusedResources`12、`NewerVersionAvailable`4、`DiscouragedApi`3、`IconLocation`2、`MonochromeLauncherIcon`2、`CustomSplashScreen`1；本轮新增低风险 warning `AutoboxingStateCreation`6、`ModifierParameter`2，均非 error，已在此列明，不以抑制掩盖。

## 5. 未验证 / 环境限制（不冒充通过）

- `adb devices` 仅 `192.168.2.132:5555 offline`，无可用 AVD；未做真机/模拟器运行。
- 因而以下仍未验证：真实首帧与 60 秒持续播放、实体遥控器 D-pad/OK/Back/数字/长按、D05/D06/D11/D16 事件注入、扫码真实推送、断网/坏源/503、720p/1080p 视觉与可访问性、透明台标边缘。
- 构建/单测/lint 通过只证明工具链与已覆盖逻辑，不代表遥控器、解码首帧或视觉通过；需 t8 独立源码复查与 t9 A01–A14 设备验收。

## 6. 已知残余风险（需复验确认）

- `pendingCommit` 以组序号判断“上一台”，极端换源后组序号变化需设备验证。
- EPG 失败/旧缓存状态依赖真实网络失败样本复现。
- 遥控器捕获键为厂商扩展键时的可达性需设备注入验证。
