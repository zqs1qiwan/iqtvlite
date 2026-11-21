# 第二轮修复后独立功能复验报告（t11）

日期：2026-11-18。复验人：acceptance（独立验收，未修改任何 Kotlin，未运行除三条标准命令外的构建）。

**最终判定：failed / 不通过，不予交付。** 当前工作区 t10 后快照的构建、单测、lint 三项自动化检查均通过（APK 可构建、57 项单测通过、lint 0 Error），且 t8 所列 R01–R11 的多数缺陷已获**实质源码修复**（尤其 R03 源写入竞态已收敛至同一进程内互斥事务）。但独立源码复核与 designer t12 正式复查（`docs/UI_UX_DESIGN_REVIEW_ROUND2.md`，needs_revision）交叉确认仍存在 **3 个源码可证 P0（S01/S02/S03）+ 3 个 P1（S04/S05/S06）生产路径缺陷**，其中 S01（恢复成功后再次断流被永久丢弃）、S02（回调事件来源仍取当前媒体而非真实事件源）、S03（ON_STOP 未失效探测意图，旧 probe 可后台起播）直接命中 A03/A04/A10/A12 观看与恢复主闭环。按验收规则「出现 P0 即不通过；P1 核心范围须全通过」，不得交付。所有设备/首帧/遥控器/视觉项因无可用设备保持**未验证**，与源码缺陷分开判定。

## 0. 依据、对象与证据边界

- 验收矩阵：`docs/UI_UX_ACCEPTANCE.md`（A01–A14，P0/P1/P2 与证据分层规则）。
- 上轮失败报告：`docs/UI_UX_REVERIFICATION.md`（t8，适用于 t7 快照）。
- 设计复查：`docs/UI_UX_DESIGN_REREVIEW.md`（t9，R01–R11）。
- 修复自述：`docs/UI_UX_REPAIR_ROUND2.md`（t10，**自述不作为通过证据**）。
- 设计二轮复查：`docs/UI_UX_DESIGN_REVIEW_ROUND2.md`（t12，needs_revision，S01–S06，已与 designer 交叉核对）。
- 本轮新证据：当前工作区源码逐行阅读；`artifacts/reverify2/` 三条命令日志与时间戳；`app/build/reports/lint-results-debug.xml`；`app/build/test-results/testDebugUnitTest/*.xml`；APK 文件哈希与 `aapt2 dump badging`；一次 adb 设备可用性检查。
- 源码基线：Git HEAD 早于本轮，`git status` 显示大量既有未提交修改（32 个 tracked 文件改动 + 多个新增文件），无独立 t10 提交；本轮对象为「当前工作区 t10 后快照」。t10 关键新增文件：`PlaybackControl.kt`、`SourceRepository.kt`、`EpgOutcome.kt`、`Round2LogicTest.kt`（均存在并已读）。
- 证据分层：构建通过 ≠ lint 无问题 ≠ 自动化覆盖生产入口 ≠ 真机体验通过；历史 Linux 构建/真机记录不计入本轮。源码确认的缺陷判失败；无设备支撑的运行项记未验证，二者不互相抵消。

## 1. 构建 / 单测 / lint / APK（本轮独立执行与读报告）

命令在 `C:\Users\Q\Documents\Projects\iqtv` 执行，2026-11-18 12:32:05–12:32:18（+08:00）。日志与退出码：`artifacts/reverify2/assembleDebug.log`、`testDebugUnitTest.log`、`lintDebug.log`、`t11_stamp.txt`。

| 检查 | 命令 | 退出码 | 报告实证 |
|---|---|---|---|
| 构建 | `./gradlew.bat :app:assembleDebug` | 0 | BUILD SUCCESSFUL，37 tasks（均 up-to-date，因 t10 后源码未再变）；含 `l8DexDesugarLibDebug`、`desugarDebugFileDependencies` |
| 单测 | `./gradlew.bat :app:testDebugUnitTest` | 0 | 7 个测试类共 **57 项：0 失败 0 错误 0 跳过**（ChannelGrouper 20、ChannelNumbering 7、Example 1、M3uParserUA 2、RepairLogic 12、Round2Logic 10、TvTokens 5） |
| lint | `./gradlew.bat :app:lintDebug` | 0 | `lint-results-debug.xml`：severity **0 Error / 67 Warning / 5 Hint**。项目 `abortOnError=false`，退出码 0 不充分，以报告为准 |
| APK | assemble 产物 | — | `app/build/outputs/apk/debug/app-debug.apk`，24,403,603 字节，SHA256 `68630F6C7C09BA6170FDC09FC67B43563713DCCDB7B26A8F63FBC4E964BB18AD`，与 t10 自述一致 |

lint 警告按 XML 实际分类：UseKtx 23、GradleDependency 15、UnusedResources 12、AutoboxingStateCreation 5（Hint）、NewerVersionAvailable 4、DiscouragedApi 3、ModifierParameter 3、AndroidGradlePluginVersion 2、IconLocation 2、MonochromeLauncherIcon 2、CustomSplashScreen 1。首轮清零项（NewApi 95 / UnsafeOptIn 19 / RestrictedApi 17 / GestureBackNavigation / Manifest）未回归；均为非阻断警告，不构成 A14 失败。

**API24 构建兼容（非设备运行）**：`app/build.gradle.kts` 为 `minSdk 24 / targetSdk 36 / compileSdk 36`，`isCoreLibraryDesugaringEnabled=true` + `desugar_jdk_libs 2.1.5`；assemble 日志确认 L8/desugar 任务执行；`aapt2 dump badging` 显示 `sdkVersion:'24'`、`targetSdkVersion:'36'`。结论：**构建层面 Android 7.0 兼容证据成立**；API24 真机启动/播放未验证（见 §4）。

## 2. R01–R11 逐条复验（源码 + 自动化 + 交叉证据）

判定口径：源码已闭合仅代表该子问题改善，不等于对应用例通过；带设备依赖的项记未验证。

| R / 严重度 | t10 修复 | 本轮判定 | 依据与残余 |
|---|---|---|---|
| R01 / P0 | 新意图代次 + ProbeCoordinator，探测前后校验 | **部分修复** | `playGroupAuto:1020` 建意图、探测后 1039–1041 双校验，`startSession:520` 提升共同意图；t8「旧探测覆盖新手动源」已闭合。`Round2LogicTest.newerManualIntentAbortsInFlightProbe` 用 CompletableDeferred 真交错覆盖。**但 ON_STOP 未失效意图（S03/P0）** |
| R02 / P0 | mediaId 携 token，事件按媒体身份校验，12s 统一，生命周期 prepare | **未修复完整（S01+S02 阻断）** | token 编码/解码在 `PlaybackControl.kt`，`prepareMediaNow:509-514` 写 mediaId，12s 监控 `613-619`；但 `onPlaybackStateChanged/onPlayerError:584-603` 回调时读 `p.currentMediaItem?.mediaId` 而非事件源（S02），且 READY:589 `attempt=0` 使恢复后再错被 828 丢弃（S01）。媒体归属测试只有 token 往返，无真实 Player 排队回调测试 |
| R03 / P0 | SourceRepository 单锁事务 + AtomicFile | **进程内竞态实质修复（边界保留）** | 当前全部生产写者收敛：`MainActivity:142` → `commitSource`，`BackgroundSourceUpdater:151/169` → `currentVersion/commitBackground`，同包名复用同一 repo 的 Mutex；A→B→A 版本拒绝、写失败保留旧指针有 4 项真实类测试。`AtomicFile` 替代删旧文件回退。**残余边界（designer §4）**：`version:37-38` 仅内存、非持久；Android 适配器 `70-75` 三次 Prefs.apply 非单 Editor 提交，非崩溃原子；平台原子性需设备 IO 验证，FakeCache 不证明 |
| R04 / P1 | EpgLoadResult 三态分类 + 按源缓存 | **主要已修复，回退有一处缺口（S05/P1）** | `EpgRepository.loadStructured` 真实按 URL 隔离 `sourceCache`、仅 Success 记成功时间；`refreshEpgNow:861-880` 三态呈现正确。但 `forceRefresh=true` 时 `cached=null`（`EpgRepository:49-52`），CacheFallback 分支不可达；`refreshEpg` 的 fetcher 对 null 回退真实网络（`BackgroundSourceUpdater:196-197`）。测试只测 EpgOutcome 分类与 EpgSourceCache map，未走 loadStructured 入口 |
| R05 / P1 | 稳定 id + LazyListState + 多帧返焦 | **部分修复（S04/P1）** | 设置根页按稳定 id 滚动再 request，播放信息按钮绑 requester 已落地；但回看确认取消 requester 绑当前节目而非历史调用行，Remote/reset 首项无布局保证。设备焦点验收未做 |
| R06 / P1 | 导航携带 requestedChannelUrl/EntryGuide，Guide 定位当前台 | **源码主要已修复，待设备** | ChannelPanel 参数与 effect、`onSetOnOpenPrograms:1020` 共用意图已核对；需实际验证当前台/不同组/反复 Guide 与 Back |
| R07 / P1 | 开层前 cancelNumberInput，未处理键回 super | **源码已修复，待设备** | `onKeyDown` 与 Menu 路径源码核对通过；Compose 子控件消费后全屏按键可达性须注入验证，不能因存在方法判通过 |
| R08 / P0 | MANUAL 无缓存可操作失败终态 | **源码已补出口，待设备** | `PlayerActivity:747-764` 设置「没有可用缓存，请手动刷新列表或重新配置源」，`onRetry:1327-1336` 无频道时手动刷新；断网/坏列表/取消/首台需设备 |
| R09 / P1 | 按源时间戳、退避暂缓不标尝试、EPG DAILY 补做 | **部分修复（S06/P1）** | `AppSettings` 有 per-source 键；`runStartupPass:92-96` 仅实际尝试才标 attempted；EPG 周期 `launchAttempted = policy != DAILY`（`:951`）。但 DAILY 周期不检查当天成功即刷新（跨天常驻也刷新），启动/手动/周期 EPG 不共享 inFlight；无可控时钟的生产 Scheduler 测试 |
| R10 / P1 | 稳定 group.key + 仅真实成功事件提交 | **方向正确，受 S01/S02 阻断（P1）** | `CommitInfo` 传 `group.key`（`:530/812/1014/1246`），`commitSuccessfulPlayback:534-547` 校验 mediaToken==active，`MainActivity` 不再播放前写 LastChannel。但成功判定依赖 S02 来源、恢复后再次错误依赖 S01，故不能宣称「真实成功历史」可靠 |
| R11 / P2 | Now/Next 独立、catchup 构造判定、探测四态 | **源码主要已修复，待设备** | 独立 now/next、`CatchupHelper.buildCatchupUrl` 判定、`SourceVariantUi.probed` 与文案已核对；实际布局与四态呈现待设备 |

R03 说明：按 captain 要求，**不把已实质修复的进程内 check/commit 竞态继续列为未修**；上面仅如实记录持久性与崩溃一致性的边界。

## 3. 生产入口异步交错测试覆盖度（专项）

t10 新增 `Round2LogicTest` 10 项，全部在 57 项通过名单内。本轮逐项核对「是否走生产入口 + 是否注入可控并发/IO/数据」：

- **旧 probe 交错**：`newerManualIntentAbortsInFlightProbe` 用 `CompletableDeferred` 挂起真实 `ProbeCoordinator.selectFirstReachable`，中途 `newIntent()` 后旧探测返回 null，当前意图仍可选可达线路——**有效生产入口级并发测试**，R01 核心闭合。
- **源原子提交**：4 项（晚到后台不得覆盖新手动源、A→B→A 版本识别、写失败保留旧指针、指针变化拒绝后台）用真实 `SourceRepository` + FakePrefs/FakeCache + 顺序版本捕获——**覆盖进程内交错与 IO 失败**；未用真实 AtomicFile、无持久版本/崩溃测试（边界见 R03）。
- **旧媒体事件 / 排队回调**：**无**。`mediaTokenRoundTripAndRejectsForeignIds` 仅编码/解析往返与垃圾 id，未调用真实 `PlayerActivity` 监听器、未注入旧回调排队到新选择之后。t8 要求的「旧 ExoPlayer 回调归属」仍未由自动化测试覆盖，且 S01/S02 源码缺陷说明现有 mediaId 方案本身不足以闭合（见 §5）。
- **缓存失败 / 部分写**：仅 `cacheWriteFailureKeepsOldPointer`（FakeCache.writeAtomic 返回 false）。未测真实 AtomicFile 部分写/中断恢复（需 Android IO 环境）。
- **EPG 源隔离与退避**：`epgSourceCacheIsolatedPerSource`、`classifyPrefersNetworkSuccessThenCacheThenFailure`、`cacheFallbackIsNotNetworkSuccess` 仅测 `EpgOutcome.classify` 与 `EpgSourceCache` map；**未调用 `EpgRepository.loadStructured`（虽有可注入 fetcher）与实际网络/缓存回退路径**；退避/调度无可控时钟测试（S06）。

结论：测试在 R01/R03 上确属生产入口级并发覆盖，较 t7 的纯整数/常量断言有实质进步；但**「旧事件/排队回调」和「EPG 生产入口与退避」两个专项仍是空白**，且前者被源码缺陷 S01/S02 抵消。不能据 57 项通过推断 R01–R11 全闭合。

## 4. 设备与运行证据（一次检查）

按 captain「合理一次检查」要求：`adb devices` 为空；`adb get-state` = `error: no devices/emulators found`（exit 1）。无可用真机/模拟器。因此 A01–A13 中所有依赖真实运行的项均**未验证**，不计通过：

- 真实首帧 + 持续 60 秒播放、503/断网/空列表/无缓存冷启动、扫码真实推送、LAN 重启、回看 seek/ENDED、透明台标、720p/1080p 远距视觉、API24 真机启动；
- 遥控器 D-pad/OK/Back/数字/长按事件注入（A04/A06/A07/A12/A13 焦点与返回优先级）；
- R02 旧回调排队、R03 真实 IO 交错、R05/R06/R07/R11 运行行为。

上述未验证与 §5 的源码缺陷是两类独立结论；本报告不因无设备而豁免已确认的源码 P0/P1。

## 5. 源码确认的残余缺陷（下一轮明确修复清单，源自 t12 + 本轮独立复核）

按 captain 收束要求，以 t12 的 S01–S06 为修复清单；已实质修复项不重复列为未修。

### S01 / P0：恢复成功后再次报错被永久丢弃
证据 `PlayerActivity.kt:584-590`（READY 行 589 `attempt=0`）、`:822-843`（事件接收）。路径：媒体 a0 失败→接收分支 `attempt=1`→`prepareMediaNow` 生成 token `iX-a1-sY`→恢复 READY 把 `attempt` 归零，但 `activeMediaToken` 与 MediaItem 仍为 a1→再次断流 error 读出 a1→828 行 `parsed.attempt(1) != attempt(0)` 直接 return，不再恢复也不进入失败终态。属生产状态机缺陷（非「仅极端设备观察」）。修复：区分「当前媒体 attempt」与「连续恢复预算」，成功重置预算但保留有效媒体身份；补 a0 失败→a1 成功→再次错误应重新恢复的生产状态测试。

### S02 / P0：回调仍取当前媒体项而非事件来源身份
证据 `PlayerActivity.kt:580-604`（`onPlaybackStateChanged`、`onPlayerError` 均读 `p.currentMediaItem?.mediaId`）。若旧加载回调到达时播放器已切新媒体，旧事件被盖上新 token，与本轮要修的「当前 session 盖章」同类；代码中无 `AnalyticsListener.EventTime`/timeline/mediaPeriod 归属，无每 attempt 隔离播放器/监听器。t10 修复说明第 60 行已承认此风险。修复：采用可回溯事件媒体期身份的监听方案或每 attempt 隔离播放器实例，READY/ENDED/错误统一来源规则；补旧回调排队到新选择之后的测试。

### S03 / P0：ON_STOP 未失效探测意图
证据 `PlayerActivity.kt:559-565`（ON_STOP 仅取消 recovery、清 token、releasePlayer，无 `newIntent()`/取消 probe Job）、`:1017-1042`（probe 结束仍 `isCurrent(intent)` 即 `playGroupVariant`）。路径：启动慢 probe→Home/进配置→ON_STOP→probe 结束 gate 仍 current→后台创建播放器起播。修复：ON_STOP 提升代次/取消 probe Job，并在 probe 回调处校验生命周期状态；补生命周期交错测试。

### S04 / P1：确认取消与模态初始焦点
证据 `docs/UI_UX_DESIGN_REVIEW_ROUND2.md` §S04。确认回看弹层取消时 requester 绑「当前节目」而非历史调用行；Remote/reset 恢复默认确认没有让首项在布局后实际获焦。修复：取消恢复指向调用项；复用 R05 多帧 requester 模式绑定首项。

### S05 / P1：EPG 缓存回退实现不可达 + fetcher null 触发真实网络
证据 `EpgRepository.kt:49-52`（`forceRefresh=true` 时 `cached=null`，CacheFallback 分支不可达）、`BackgroundSourceUpdater.kt:196-197`（fetcher 返回 null 会回退 `downloadBytes(url)` 真实网络）。修复：按调用方需求区分「有网且失败时用缓存」与「纯缓存读取」，注入 fetcher 时不得偷跑网络；并以真实 `loadStructured` 入口补 A/B 源隔离与断网回退测试。

### S06 / P1：EPG DAILY 跨天常驻刷新 + 多入口不共享 inFlight
证据 `PlayerActivity.kt:951`（周期 `launchAttempted = policy != DAILY`，不检查当天成功）、启动 `:917`、手动 `:1250`、周期 `:953` 各自独立请求。修复：DAILY 周期加当天成功判定，启动/手动/周期共享 `epgRefreshInProgress`；补可控时钟与 inFlight 去重入口测试。

边界说明（不计为未修缺陷、仅记录）：R03 的 `version` 仅内存、Prefs 三次 apply 非崩溃原子事务；平台 AtomicFile 中断恢复需设备 IO 验证。

## 6. A01–A14 判定汇总

| ID | 结果 | 关键依据 |
|---|---|---|
| A01 首次配置 / P0 | 未验证（源码部分） | 配置页与 MANUAL 无缓存出口已补（R08），首帧/错误可操作无设备；S01–S03 影响进入播放后的闭环 |
| A02 扫码推源 / P1 | 未验证 | requestId/取消源码已修；真实推送与 LAN 未测；R03 进程内写者已收敛，持久边界保留 |
| A03 播放与启动恢复 / P0 | **失败** | S01（恢复后再错丢失）+ S02（事件来源）命中；60 秒真实播放与重启未验证 |
| A04 连续数字跳台 / P0 | 未验证（源码已修 R07） | 数字缓冲/1.2s/Back 源码在；112 真实流与遥控器注入无设备 |
| A05 频道浏览与分组 / P0 | 未验证 | 侧栏 min(360dp,W/3) 与标记/焦点并存源码在，视觉与焦点无设备 |
| A06 节目单与焦点恢复 / P1 | 未验证（S04 源码缺口） | 确认取消返焦/Remote 模态初始焦点未闭合；确认/取消/当前未来节目需设备 |
| A07 EPG 与信息条 / P1 | 未验证（S05/S06 源码缺口） | Now/Next 独立计算在，缓存回退不可达与调度缺口未闭合；占位/刷新失败需设备 |
| A08 回看 / P1 | 未验证 | catchup 构造与 ENDED 回直播源码在，seek/ENDED/返回直播需设备 |
| A09 设置与持久化 / P1 | 未验证 | 各项设置源码在，冲突拒绝/恢复默认与重启生效需遥控器 |
| A10 播放失败与恢复 / P0 | **失败** | S01 直接破坏二次失败恢复，S02 事件归属不可靠；503/断网/替代线路终态需设备 |
| A11 空列表/坏源/断网缓存 / P0 | 未验证（源码部分） | R08 出口已补，AtomicFile 保留旧缓存；断网冷启动/真实 IO 无设备 |
| A12 遥控器焦点与返回 / P0 | **失败（S03 源码缺陷）+ 未验证** | ON_STOP 旧 probe 可后台起播违反顶层事件独占；其余焦点优先级无设备 |
| A13 可访问性与可读性 / P1 | 未验证 | 16sp/透明台标等源码 token 在，观看距离/分辨率/焦点视觉无设备 |
| A14 构建与自动化质量 / P0 | 通过 | 三命令 exit 0，lint 0 Error/67 Warning/5 Hint（已读报告），57 单测通过，APK SHA 已记录；未因 S 项缺陷回溯为构建失败 |

最终：**3 个 P0（S01/S02/S03）+ 3 个 P1（S04/S05/S06）源码可证缺陷未闭合**，A03/A10/A12 判失败，其余运行项未验证；不满足交付门槛。

## 7. 证据停止点与复验要求

本轮源码读取与命令执行至此全部停止；不再读取源码、不再执行命令。请 captain 按 S01–S03（先）与 S04–S06（后）安排下一轮修复，并以六项**反例驱动的生产入口测试**（旧回调排队、恢复后再错、ON_STOP 交错、确认取消返焦、`loadStructured` 断网回退、时钟/inFlight EPG 调度）作为复验门槛；同时明确 R03 持久版本/单 Editor 提交的边界处理。所有遥控器/首帧/扫码/断网/视觉项仍须在可用 TV 设备或模拟器上补证。本轮不批准交付。
