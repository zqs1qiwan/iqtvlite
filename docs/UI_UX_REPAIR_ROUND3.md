# Android TV IPTV 第三轮修复说明（t13）

日期：2026-11-18。执行者：developer。对象：按 [第二轮设计复查 S01–S06](UI_UX_DESIGN_REVIEW_ROUND2.md) 与 [复验报告](UI_UX_VERIFICATION_ROUND2.md) 修复生产状态与调度。保留已修的 R01–R11。本文为实施记录，不是验收通过证明；设备/遥控器/首帧/视觉仍待 t14/t15。

## 1. 命令证据（当前工作区，本次执行）

baseline：`build_repair2_baseline.log`（未覆盖用户原有改动）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./gradlew.bat :app:assembleDebug` | 0 | Debug APK 成功 |
| `./gradlew.bat :app:testDebugUnitTest` | 0 | **71 项**全通过（新增 Round3LogicTest 14） |
| `./gradlew.bat :app:lintDebug` | 0 | **0 Error / 69 Warning / 6 Hint** |

- APK：`app/build/outputs/apk/debug/app-debug.apk`，24,423,431 bytes，SHA256 `F7D0AB79480E6951EFAB9530A716151679A7B0854E9C33BBB46179B39FA1FA03`。
- 环境：JDK17.0.21 / Gradle 8.13 / AGP 8.13.2 / Kotlin 2.0.21 / minSdk24 / targetSdk36；core library desugaring 生效。

## 2. 逐 S 修复（生产路径 + 测试）

### S01 / P0 媒体 attempt 身份 vs 连续失败预算
- 新增 `PlaybackAttemptState`（`PlaybackControl.kt`）：`mediaAttempt` 是当前媒体身份，`recoveryBudget` 是连续失败预算。`onReady()` **只重置预算不重置媒体 attempt**；`onMediaError(tokenAttempt)` 仅在 tokenAttempt==mediaAttempt 时计入预算并推进 mediaAttempt，预算超 3 返回 `Terminal`。
- `PlayerActivity.prepareMediaNow` 用 `attemptState.mediaAttempt` 生成 mediaId token；READY 调 `attemptState.onReady()`；错误收集器改用 `attemptState.onMediaError(parsed.attempt)`。
- 测试：`Round3LogicTest.recoveryAfterReadyCanRecoverAgainInsteadOfDropping`（a0 失败→a1 READY→a1 再错必须 Recover，而非 Ignore）、`consecutiveFailuresExhaustBudget`、`staleAttemptIgnored`。**缺陷实现（READY 置 attempt=0）会失败**。

### S02 / P0 真实事件来源
- 每次 prepare **隔离播放器实例**（`prepareMediaNow` 先 `releasePlayer()` 再建新 ExoPlayer），监听器在 `attachAttemptListener(p, token)` 中**闭包捕获本次 token**；READY/ENDED/error 全部用该捕获 token 判定，不再读取回调时的 `currentMediaItem`。
- 新增 `AttemptEventRouter`：`activate/clear/isCurrent(sourceToken)`；旧实例/旧 attempt 回调晚到即被拒收。
- 测试：`Round3LogicTest.lateEventFromOldPlayerInstanceIsRejected`。生产 Activity 使用同一 `AttemptEventRouter`。**缺陷实现（读 currentMediaItem 盖新身份）会失败**。
- 说明：真实 ExoPlayer 回调排队在无设备/注入环境不可运行；已通过每 attempt 独立播放器实例 + 捕获 token 从结构上消除“盖新身份”。

### S03 / P0 ON_STOP 失效探测意图
- `ON_STOP`：`intentGate.newIntent()` + 取消 `probeJob` + 取消 `recoveryJob` + `eventRouter.clear()` + `activeMediaToken=null` + release。
- `playGroupAuto`：保存 `probeJob`，探测结束提交前校验 `intentGate.isCurrent(intent)` 且 `lifecycle.currentState.isAtLeast(STARTED)`；按稳定 `group.key` 重新解析目标组再播放。
- 测试：`Round3LogicTest.onStopInvalidatesInFlightProbe`（慢 probe 挂起→模拟 ON_STOP 提升意图→释放后 select 返回 null，不得起播）。**缺陷实现（ON_STOP 不失效意图）会失败**。

### S04 / P1 模态返焦与稳定 ID
- 进入回看确认前记录 `programAnchorKey/focusedProgramKey`（稳定节目 key）；取消后先 `scrollToItem(锚点)` 再 `requestFocus`。确认模态时对底层面板 `focusProperties { canFocus=false }` 隔离；确认初始焦点在“开始回看”。
- 设置“遥控器”页：记录 `remoteFocusIndex` 与 `resetRequester`；捕获取消恢复到原动作行、恢复默认取消恢复到“恢复默认映射”行；均按稳定索引滚动后再请求。
- 限制（如实）：Compose 焦点/滚动需设备或 instrumentation 注入验证；本机无设备，`长列表非首项取消返焦`未做运行验证，仅源码闭合。

### S05 / P1 forceRefresh 缓存回退 + 注入 fetcher
- `EpgRepository.loadStructured`：缓存读取（含反序列化）移入 `Dispatchers.IO`；**forceRefresh 仍读取同源缓存作为网络失败候选**；注入 `fetcher` 时返回 null 即网络失败，**不回退真实网络**（`if (fetcher!=null) fetcher else downloadBytes`）。
- 抽出 `loadStructuredCore(sourceId, cached, fetcher, parser?)` 生产核心供测试注入。
- 测试：`Round3LogicTest` 覆盖网络失败有缓存→CacheFallback(STALE,非成功)、无缓存→Failure、解析失败→CacheFallback、成功→Success、注入 fetcher null 不触网、A/B 源隔离。`EpgRepository` 默认 parser 仍为 `XmlTvParser`（测试注入 parser 规避 JVM 无 android.util.Xml）。

### S06 / P1 统一每源 EPG 刷新协调
- 新增 `EpgRefreshCoordinator`（object，`clock` 可注入）：`tryBegin/end` 共享每源 inFlight（启动/周期/手动/配置后刷新全部经此）；`shouldRunAuto` 按策略与退避；`markAutoAttempted` 仅在**实际发起**后标记。
- DAILY 严格“每天首次启动”：本进程内实际尝试过该源当天启动后，跨午夜常驻不再按日期触发；启动被 5 分钟退避暂缓时不标记，退避结束后周期补做。
- `PlayerActivity.performEpgRefresh` 作为启动/周期/手动统一入口；`BackgroundSourceUpdater.refreshEpg`（配置后刷新）也经 coordinator。
- 测试：`Round3LogicTest.startupBackoffDefersThenCatchesUp`、`dailyDoesNotRefreshAgainWhenResidentAcrossMidnight`、`manualAndAutoShareInFlight`（可控 clock/inFlight 锁）。**缺陷实现（仅靠日期或吞掉退避）会失败**。

### R03 边界
- `SourceRepository` 版本改为**持久化**（`Prefs.getSourceVersion/setSourceVersion`，接口新增）；`commitSource` 用 `Prefs.setSourceConfig` **单 Editor** 一次提交 live/epg/指针，避免三次 apply 中间态。
- 如实说明：AtomicFile 提供文件原子替换，进程崩溃一致性仍属平台行为；版本为持久计数，跨进程可识别旧后台结果。

## 3. 测试覆盖与缺口（不虚称全闭合）

新增 `Round3LogicTest` 14 项，全部驱动生产类：PlaybackAttemptState（S01）、AttemptEventRouter（S02）、ProbeCoordinator ON_STOP（S03）、EpgRepository.loadStructuredCore（S05 网络/解析/缓存/隔离/不触网）、EpgRefreshCoordinator（S06 退避补做/跨午夜/去重）。

仍未覆盖（明确报告）：
- S02 真实 ExoPlayer 回调排队：结构隔离已实现，但无法在无设备/无注入 Player 的 JVM 中运行；未做真实播放器旧回调注入测试。
- S04 遥控器焦点/滚动/确认隔离：仅源码实现，无设备/instrumentation 验证；“长列表非首项取消返焦”未运行。
- S03 的 STARTED 校验与 probe 取消：单元测试用 gate 模拟 ON_STOP；真实 Lifecycle/协程取消时序需设备或 instrumentation。
- S05 真实 Android 文件缓存（AtomicFile/磁盘反序列化）与真实网络：测试用注入 fetcher/parser，未跑真实网络/文件系统。
- R03 进程崩溃原子性：未做崩溃注入。

## 4. Lint 分类

保持 0 Error：首轮清零的 NewApi/UnsafeOptIn/RestrictedApi/GestureBackNavigation/Manifest 未回归。Warning 69 主要为既有 UseKtx/GradleDependency/UnusedResources/NewerVersionAvailable/DiscouragedApi/IconLocation/MonochromeLauncherIcon/CustomSplashScreen/AndroidGradlePluginVersion 及本轮少量 ModifierParameter/AutoboxingStateCreation（Hint），均非阻断，未禁用或普遍抑制。

## 5. 待设备/复验项

- 真实首帧 + 60 秒、503/断网/空列表/无缓存冷启动、扫码推送、LAN 重启、720p/1080p 焦点与可读、透明台标、API24 真机启动。
- 遥控器 D-pad/OK/Back/数字/长按注入；S02 旧回调排队、S03 ON_STOP 交错、S04 弹层返焦、S06 调度时钟生产入口。
- 本文件为修复实施记录，最终是否通过由 independent t14/t15 决定。
