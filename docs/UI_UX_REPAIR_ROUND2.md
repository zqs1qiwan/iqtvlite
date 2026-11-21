# Android TV IPTV 第二轮修复说明（t10）

日期：2026-11-18。执行者：developer。对象：修复 [设计复查 R01–R11](UI_UX_DESIGN_REREVIEW.md) 与 [复验报告](UI_UX_REVERIFICATION.md)。本文件是修复实施记录，不是验收通过证明；设备/遥控器/首帧/视觉仍待 t11/t12 复验。未缩减 A+B 范围，未禁用或普遍抑制 lint。

## 1. 命令证据（当前工作区，本次执行）

baseline：`build_repair2_baseline.log`（未覆盖用户原有未提交改动）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./gradlew.bat :app:assembleDebug` | 0 | Debug APK 成功 |
| `./gradlew.bat :app:testDebugUnitTest` | 0 | **57 项**全通过（新增 Round2LogicTest 10） |
| `./gradlew.bat :app:lintDebug` | 0 | **0 Error / 67 Warning / 5 Hint** |

- APK：`app/build/outputs/apk/debug/app-debug.apk`，24,403,603 bytes，SHA256 `68630F6C7C09BA6170FDC09FC67B43563713DCCDB7B26A8F63FBC4E964BB18AD`。
- 环境：JDK17.0.21 / Gradle 8.13 / AGP 8.13.2 / Kotlin 2.0.21 / minSdk24 / targetSdk36；core library desugaring 生效。

## 2. 逐 R 修复

| R | 修复内容（生产入口） | 证据 |
|---|---|---|
| R01 / P0 | 新增 `PlaybackIntentGate` + `ProbeCoordinator`。`playGroupAuto` 先 `newIntent()`，探测每候选前后校验同一意图；`startSession/playGroupVariant/回看/上一台/手动源/失败重试` 均 `newIntent()`，使旧探测 await 后返回 null 不再覆盖新选择 | `PlaybackControl.kt`、`PlayerActivity.playGroupAuto/startSession`、`Round2LogicTest.newerManualIntentAbortsInFlightProbe` |
| R02 / P0 | 预览媒体项 `MediaItem.mediaId` 携带 `MediaToken(intent-attempt-seq)`；`onPlayerError/READY/ENDED` 读取 `player.currentMediaItem.mediaId` 而非当前状态盖章；`prepareMediaNow` 统一生成 token 并触达 12s 监控；`ON_STOP` 取消 recovery 与监控、`ON_START` 走统一 prepare 重建 | `PlaybackControl.MediaToken`、`PlayerActivity`（prepareMediaNow/listener/LaunchedEffect(activeMediaToken)/Lifecycle）、`Round2LogicTest.mediaTokenRoundTrip...` |
| R03 / P0 | 新增 `SourceRepository`（接口化 Prefs/Cache，Mutex + 持久 version）。手动 `commitSource` 与后台 `commitBackground(expectedVersion)` 共用单一事务；缓存 `PlaylistCache.writeAtomic` 改用 `androidx.core.util.AtomicFile`（唯一 new/bak，失败保留旧文件，不先删目标） | `SourceRepository.kt`、`PlaylistCache.kt`、`MainActivity.startPlayer`、`BackgroundSourceUpdater.refreshPlaylist`、`Round2LogicTest`（4 项：晚到/ A→B→A / 写失败 / 指针变） |
| R04 / P1 | `EpgLoadResult`（Success/CacheFallback/Failure + sourceId）+ `EpgSourceCache` 按源隔离内存；`EpgRepository.loadStructured(fetcher 可注入)`；仅网络成功更新最近成功时间，缓存回退记 STALE | `EpgOutcome.kt`、`EpgRepository.kt`、`BackgroundSourceUpdater.refreshEpg`、`PlayerActivity.refreshEpgNow`、`Round2LogicTest`（3 项） |
| R05 / P1 | 设置根页稳定 item id + `rememberLazyListState`，布局后 `scrollToItem` 再 `requestFocus`；选项页焦点落当前值；PlaybackInfo 返回按钮绑定焦点；ChannelPanel 节目加载/状态变化后重请求焦点、回看确认取消恢复节目焦点；恢复默认确认使用独立 requester 初始聚焦 | `SettingsDrawer.kt`、`ChannelPanel.kt` |
| R06 / P1 | 导航请求携带 `requestedChannelUrl + requestedEntryGuide`；Guide 直接定位当前 liveUrl（覆盖上次浏览聚焦） | `PlayerActivity.setOnOpenPrograms`、`ChannelPanel` 参数/effect |
| R07 / P1 | Menu 及所有开层动作前 `cancelNumberInput()`；数字提交校验媒体/面板状态；`onKeyDown` 未处理键改调 `super.onKeyDown` | `PlayerActivity.onKeyDown` |
| R08 / P0 | MANUAL 且无缓存时设置 `pendingFailure("没有可用缓存，请手动刷新列表或重新配置源")`；失败面板“重试”在无频道时执行手动列表刷新，绝不进入空播放器 | `PlayerActivity` 初始加载、`PlaybackFailurePanel.onRetry` |
| R09 / P1 | 播放列表/EPG 最近成功与尝试时间改为**按源隔离**；启动被 5 分钟退避挡住时不标记“已尝试”，交给周期补做；EPG DAILY 用当天成功判定 + 退避补做 | `AppSettings`（per-source）、`BackgroundSourceUpdater.runStartupPass/runPeriodicPass`、`PlayerActivity` EPG 周期 |
| R10 / P1 | 提交历史用稳定频道键 `group.key`（`CommitInfo.channelKey`），仅真实成功媒体事件（token==active）后提交；`MainActivity.startPlayer` 不再首帧前写 LastChannel，只提交源+缓存指针+待播放目标 | `PlayerActivity.commitSuccessfulPlayback/startSession`、`MainActivity` |
| R11 / P2 | Now/Next 独立计算（无当前有后续也显示 Next）；回看能力用 `CatchupHelper.buildCatchupUrl` 实际构造判断；探测显式四态（未测/测速中/可用 ms/不可用），自动项文案改为“选择可用线路”；手动列表刷新进行中/去重/时间展示 | `PlayerActivity` now/next、`ChannelPanel.canCatchup`、`SettingsDrawer` SourceSelectPage、`QualitySelectorDialog.SourceVariantUi.probed` |

## 3. 生产入口测试覆盖与缺口

新增 `Round2LogicTest`（10 项，真实类 + 可控并发/IO/数据）：
- `SourceRepository`：**晚到后台结果不得覆盖新手动源**、**A→B→A 版本识别**、**缓存写失败保留旧指针**、**源指针变化拒绝后台提交**。无事务/无版本时会失败。
- `ProbeCoordinator`：用 `CompletableDeferred` 挂起探测，中途 `newIntent()` 后旧探测返回 null（**较新手动选择最终生效**）；当前意图仍可选可达线路。
- `MediaToken`：编码/解析往返、拒绝外部/空/垃圾 id。
- `EpgOutcome`/`EpgSourceCache`：成功/回退/失败分类，A/B 两套数据按源隔离，缓存回退不算成功。

仍未覆盖（如实记录，未以纯函数冒充）：
- R02“旧 ExoPlayer 回调在切换后排队”未做真实 Player 注入测试（无设备）；已用 mediaId 绑定 + token 归属在源码层闭合，运行归属需设备。
- R05 焦点/滚动、R06 Guide 定位、R07 事件消费顺序、R11 探测四态需遥控器事件注入。
- R09 按源时间戳与退避补做仅有源码路径，未做可控时钟的 Scheduler 入口测试（BackgroundSourceUpdater 依赖 Android Context/Prefs）。
- `SourceRepository` 并发用顺序版本捕获模拟真实交错，未复用真实 Android AtomicFile（其原子性由平台保证）。

## 4. Lint 分类（前后）

本轮保持 0 Error：NewApi/UnsafeOptIn/RestrictedApi/GestureBackNavigation/Manifest 首轮已清零且未回归。Warning 67 主要为既有 `UseKtx`23、`GradleDependency`15、`UnusedResources`12、`NewerVersionAvailable`4、`DiscouragedApi`3、`IconLocation`2、`MonochromeLauncherIcon`2、`CustomSplashScreen`1、`AndroidGradlePluginVersion`2；本轮新增 `ModifierParameter`3、`AutoboxingStateCreation`5（Hint 级），均为非阻断警告，未抑制。

## 5. 待设备/复验项

- 真实首帧 + 60 秒持续播放（60 秒仅为验收观察时长，代码未加产品保存延迟）、503/断网/空列表/无缓存冷启动、扫码真实推送、LAN 重启、720p/1080p 焦点与可读、透明台标、API24 真机启动。
- 遥控器 D-pad/OK/Back/数字/长按事件注入（R05/R06/R07/R11 运行行为）。
- 旧回调排队（R02）与源事务真实 IO 交错（R03）在设备/注入环境复验。

## 6. 残余风险

- R02 事件来源已改为真实 mediaId，但 `PlaybackException` 不携带 mediaItem，依赖 `player.currentMediaItem.mediaId`；极端并发仍需设备观测。
- R05 设置根使用稳定 id + LazyListState；动态项（返回直播/源选择）出现时 id 仍是稳定的，但极端重排滚动需设备确认。
- R09 调度仍由播放器与 BackgroundSourceUpdater 两处驱动（列表在 Updater、EPG 在播放器），语义一致但非单一进程入口。
