# UI/UX 修复独立复验报告（t8）

日期：2026-11-18。复验人：acceptance（独立验收，不修改 Kotlin）。

**结论：failed / 不通过。** 当前 t7 快照已将首轮 t5 的 134 个 lint error 清零、47 项单测通过、APK 可构建且已确认 minSdk 24（Android 7.0）兼容；但源码复审与 designer t9 正式复查（`docs/UI_UX_DESIGN_REREVIEW.md`）交叉确认仍存在 **4 个 P0（R01/R02/R03/R08）+ 6 个 P1（R04/R05/R06/R07/R09/R10）+ 1 个 P2（R11）** 残余缺陷，覆盖观看主闭环、数据一致性与核心交互，按验收规则（P0 即不通过、P1 核心范围须全通过）不得交付。所有真实设备/首帧/遥控器体验项因无可用设备全部标记**未验证**。

## 0. 依据与证据边界

- 验收标准：`docs/UI_UX_ACCEPTANCE.md`（A01–A14、严重度与证据分层规则）。
- 首轮失败报告：`docs/UI_UX_VERIFICATION_REPORT.md`（t5，结论仅适用于修复前 t4 快照）。
- 设计审查：`docs/UI_UX_DESIGN_REVIEW.md`（D01–D16）。
- 修复自述：`docs/UI_UX_REPAIR_NOTES.md`（t7，自述不作为通过证据）。
- 设计独立复查：`docs/UI_UX_DESIGN_REREVIEW.md`（t9，needs_revision，R01–R11）。
- 本轮新证据：当前工作区源码逐行阅读、`artifacts/reverify/` 三条命令日志、`app/build/reports/lint-results-debug.xml`、APK 文件哈希与 `aapt2 dump badging`。
- 源码快照：当前 Git 基线早于 t4，diff 含大量既有未提交修改，并非独立 t7 提交；本轮以“当前工作区 t7 后快照”为对象。
- 证据分层：构建通过 ≠ lint 无问题 ≠ 真机体验通过；历史 Linux 构建/真机记录不计入本轮。

## 1. 构建 / 单测 / lint / APK 证据（独立执行）

命令在 `C:\Users\Q\Documents\Projects\iqtv` 执行，时间 2026-11-18 12:12:00–12:12:12（+08:00），均因 t7 后源码未变而 UP-TO-DATE，但报告文件为当前工作区实际产物。日志：`artifacts/reverify/assembleDebug.log`、`artifacts/reverify/testDebugUnitTest.log`、`artifacts/reverify/lintDebug.log`；退出码记录：`artifacts/reverify/t8_stamp.txt`。

| 检查 | 命令 | 退出码 | 报告实证 |
|---|---|---|---|
| 构建 | `./gradlew.bat :app:assembleDebug` | 0 | BUILD SUCCESSFUL，37 tasks（37 up-to-date）；含 `l8DexDesugarLibDebug`、`desugarDebugFileDependencies`（core library desugaring 生效） |
| 单测 | `./gradlew.bat :app:testDebugUnitTest` | 0 | 6 个测试类共 **47 项：0 失败 0 错误 0 跳过**（ChannelGrouperTest 20、ChannelNumberingTest 7、ExampleUnitTest 1、M3uParserUserAgentTest 2、RepairLogicTest 12、TvTokensTest 5）；XML 在 `app/build/test-results/testDebugUnitTest/` |
| lint | `./gradlew.bat :app:lintDebug` | 0 | `app/build/reports/lint-results-debug.xml`：severity **0 Error / 62 Warning / 6 Hint**。项目 `abortOnError=false`，退出码 0 本身不证明清洁，以报告为准 |
| APK | 间接（assemble 产物） | — | `app/build/outputs/apk/debug/app-debug.apk`，24,376,204 字节，SHA256 `EE1F0B7D729CFC52971C3B838A6D838D4A0A229AEBE37EBF6D1E6C62061903F4`，与 t7 自述一致 |

**lint 警告实际分类（以 XML 为准，纠正 t7 自述分类合计不等于 62 的问题）**：UseKtx 19、GradleDependency 15、UnusedResources 12、AutoboxingStateCreation 6（Hint）、NewerVersionAvailable 4、DiscouragedApi 3、AndroidGradlePluginVersion 2、IconLocation 2、ModifierParameter 2、MonochromeLauncherIcon 2、CustomSplashScreen 1。其中 Warning 62 = 19+15+12+4+3+2+2+2+2+1（t7 自述漏列 AndroidGradlePluginVersion 2，并把 AutoboxingStateCreation 6 误计为 Warning，实为 Hint）；6 Hint 即 AutoboxingStateCreation。这些为非阻断警告级，不构成 A14 失败，但 t7 自述统计须更正。

**API 24 / 兼容性核查（源码 + APK，非设备）**：
- `app/build.gradle.kts`：`minSdk 24`、`isCoreLibraryDesugaringEnabled = true`、`coreLibraryDesugaring(libs.desugar.jdk.libs)` 版本 2.1.5；`java.time.*`（Instant/LocalDate/ZoneId/ZoneOffset/DateTimeFormatter）属 desugar_jdk_libs 支持范围；assemble 日志确认 L8/desugar 任务执行。
- `aapt2 dump badging`：`sdkVersion:'24'`、`targetSdkVersion:'36'`、`uses-feature-not-required` 含 touchscreen 与 leanback。首轮 NewApi 95 / UnsafeOptInUsageError 19 / RestrictedApi 17 / GestureBackNavigation 1 对应的 file-level `@OptIn(UnstableApi)`、`onKeyDown/onKeyUp`、`OnBackPressedCallback` 已在源码落地，lint 报告 0 Error。
- 结论：**构建层面的 Android 7.0 兼容性证据成立**；运行层面 API24 实际启动/播放仍因无设备未验证。

**设备可用性**：按 captain 要求再试一次常规恢复——`adb devices` 初始为空；`adb disconnect` 后 `adb connect 192.168.2.132:5555` 返回超时 10060；`adb get-state` = `error: no devices/emulators found`（exit 1）。故模拟器/真机遥控、首帧、60 秒播放、扫码、断网/503 等运行时验收全部未验证。

## 2. A01–A14 逐项判定

严重度沿用验收矩阵；“源码部分修复”不等于通过。交叉证据含 designer t9 的 R 项与本轮独立源码核对。

| ID | 结果 | 依据与残余 |
|---|---|---|
| A01 首次配置 / P0 | **未验证（源码部分修复）** | 配置页二维码/LAN/双字段与失败态见源码；首帧与可操作错误无设备证据；R08 缓存缺失+MANUAL 黑屏无出口（P0）阻断 |
| A02 扫码推源 / P1 | **未验证** | 取消/requestId 逻辑源码已修；真实手机推送、空字段、LAN 未实测；R03 源提交原子性未闭合 |
| A03 播放与启动恢复 / P0 | **失败** | 缓存路径 startSession 已建；但 R01 旧探测可覆盖、R02 生命周期 prepare 无监控、R08 无缓存黑屏、R10 提前写 LastChannel；首帧/60 秒/重启未验证 |
| A04 连续数字跳台 / P0 | **失败（源码+阻断）** | 缓冲显示/OK/1.2s/Back 逻辑在；R07：Menu 在清空前 return，定时切台可在设置打开时发生；事件归属/Compose 消费顺序未设备验证 |
| A05 频道浏览与分组 / P0 | **未验证（源码方向一致）** | 单层宽度 `min(360.dp, W/3)`、when 分层、焦点样式源码一致；R05 焦点请求可靠性；720p/1080p 视觉与焦点未测 |
| A06 节目单层级与焦点恢复 / P1 | **失败** | 直接/确认回看、日期锚点已接；R05（确认取消不返焦、LOADING→READY 无焦点重请求）、R06（Guide 沿用旧 focusedChannelUrl）阻断 |
| A07 EPG 与信息条 / P1 | **失败** | 状态区分已接；R04：网络失败回退缓存记成功/READY，无法呈现 STALE；R11 当前节目缺失时 Next 丢失、能力误标 |
| A08 回看 / P1 | **失败（源码部分修复）** | liveUrl 统一、ENDED 回直播、失败层返回直播已接；R01 旧探测覆盖回看、R11 能力误标；seek/ENDED 实际行为未验证 |
| A09 独立设置与持久化 / P1 | **失败** | 映射、策略、回显已接；R05（根列表返回重建焦点丢失）、R07（数字定时切台）、R09（时间戳全局共享/DAILY 永久错过）、R10（不稳定组序号）阻断 |
| A10 播放失败与恢复 / P0 | **失败** | 超时/预算/pendingFailure 已接；R02（事件不绑来源 + ensurePlayer 绕过监控，旧回调可被当新会话）、R08（无失败出口）阻断；503/断网真机未验证 |
| A11 空列表、坏源与断网缓存 / P0 | **失败** | PlaylistValidation 与缓存原子写已接；R03（校验/提交非原子、tmp 共用、失败删旧文件）、R08（MANUAL 无缓存黑屏）阻断 |
| A12 遥控器焦点与返回优先级 / P0 | **失败** | OnBackPressedCallback、映射顺序、250ms 节流已接（D06/D16 源码层修复）；R05（Requester 绑屏外项）、R07（未消费键不调 super、Menu 绕过取消）、R06；事件注入未测 |
| A13 可访问性与可读性 / P1 | **未验证** | 16sp、焦点样式、面板宽度源码方向一致；无图像输入/设备，透明台标、远距可读、焦点顺序不可测 |
| A14 构建与自动化质量 / P0 | **通过（当前工作区）** | 三条命令 exit 0、报告 0 Error、47 单测通过、APK SHA 与 t7 一致；证据分层记录见第 1 节；注意警告 62/Hint 6 与 t7 自述分类不一致 |

判定汇总：**A14 通过；A01/A02/A05/A13 未验证；A03/A04/A06/A07/A08/A09/A10/A11/A12 失败**（按 P0/A 规则整体不通过）。

## 3. D01–D16 销项交叉结论（与 designer t9 一致）

首轮源码缺陷调用链大多已替换，但 D01/D02/D03/D04/D07/D08/D09/D10/D12/D16 均因 R 项只能判“部分修复”，D05/D06/D11/D13/D14/D15 源码主链已修而设备/边界未闭合。无一项可在当前快照判“完全通过”。逐条证据见 `docs/UI_UX_DESIGN_REREVIEW.md` 第 1 节。本轮独立复核确认的关键残余（含 captain 与 designer 交叉要求）：

1. **R01 / P0**：`playGroupAuto`（PlayerActivity.kt:961）探测只比对 `sourceSelectGeneration`；`startSession`（:510）/手动源（:1220）/上一台/回看均不提升该代次，旧探测返回后 `playGroupVariant` 覆盖新会话。设计复查与 captain 指出一致，**确认成立**。
2. **R02 / P0**：`onPlayerError`（:573）用 `rememberUpdatedState` 当前值盖章，事件不绑产生它的 MediaItem/session/attempt；`ensurePlayer`（:482）在 ON_START 直接 prepare，不建新 loadToken/超时。测试只覆盖 `accepts` 整数比较（见第 4 节）。**确认成立**。
3. **R03 / P0**：`BackgroundSourceUpdater.refreshPlaylist`（:165）源相等检查与（:169–174）缓存/Prefs 提交不在同一锁，`Main.startPlayer`（MainActivity.kt:140–146）无共享锁；`PlaylistCache.writeAtomic`（:33–41）tmp 共用且 rename 失败先删旧目标。**确认成立**。
4. **R08 / P0**：`PlayerActivity.kt:724` MANUAL 直接跳过整个失败分支；缓存缺失时 `pendingFailure`（:731）不设置，空媒体黑屏。**确认成立**。
5. **R04 / P1**：`EpgRepository.load`（:84–103）IOException 回退旧缓存返回非 null；`BackgroundSourceUpdater.refreshEpg`（:197–199）据此写 LastSuccess/上层 READY；memoryCache 全局未按源分区。**确认成立**。
6. **R05 / P1**：`SettingsRootPage`（SettingsDrawer.kt:361）LazyColumn 无保存 state、无 scrollToItem，`rootFocus`（:124）可绑屏外目标；`ChannelPanel`（:224）只在 visible/layer 变化时请求焦点。**确认成立**。
7. **R06 / P1**：`ChannelPanel`（:147）`focusedChannelUrl` 非 null 时优先旧浏览频道，Guide 仅传 NavLayer.Programs（PlayerActivity.kt:1101）。**确认成立**。
8. **R07 / P1**：`PlayerActivity.kt:296` Menu 分支先 `return`，未取消数字 Runnable；`onNumberJump`（:212）不校验面板状态，1.2s 后可在设置内切台。onKeyDown 未消费键直接 false 不调 super，事件归属须真机注入。**确认成立**。
9. **R09 / P1**：`BackgroundSourceUpdater.runStartupPass`（:91–96）被退避挡住也设 `playlistLaunchAttempted=true`；时间戳全局 key、无每源状态。**确认成立**。
10. **R10 / P1**：`commitSuccessfulPlayback`（:523）用 `groupIndex` 判上一台；`MainActivity.kt:158` 首帧前写 LastChannel。**确认成立**。
11. **R11 / P2**：Next/回看能力/探测状态边界误导（PlayerActivity.kt:933、ChannelPanel.kt:380、SettingsDrawer.kt:447）。**确认成立**。

## 4. 自动化检查充分性审查（回应 captain 关注点）

- `RepairLogicTest` 12 项均为纯函数/常量断言：`RefreshSchedule.*` 策略、`PlaybackRecoveryPolicy.accepts/exhausted`、`PREPARE_TIMEOUT_MS==12000`、`PlaylistValidation.isValid`。生产调用路径存在（`BackgroundSourceUpdater:84/106`、`PlayerActivity:794/797`），但测试**未覆盖生产异步交错**：无“旧探测 vs 新手动选择”“旧 MediaItem 回调 vs 新会话”“源指针交错提交”“EPG 断网回退”等入口级测试。
- 尤其 `accepts(evt.session, evt.attempt, sessionId, attempt)` 比较的是回调发生时读取的当前值，R02 下旧回调被重新盖章后该纯函数恒真——测试通过不代表旧会话被拒。
- 结论：47 项通过仅证明编译与既有/纯逻辑回归；不能替代生产入口、可控时钟/锁闩交错与 UI 焦点测试。

## 5. 事件归属专项（回应 captain 对 onKeyDown/onKeyUp + OnBackPressedCallback 的关注）

- 源码确认：数字（含 1.2s Runnable）、Channel± 默认别名在 `AppSettings.actionForKey` 之后处理（:329–347），映射优先级顺序正确（D06 源码层成立）；`OnBackPressedCallback`（:130）替代 `KEYCODE_BACK` 拦截（D16 lint 层成立）；设置捕获层用 `onKeyDown` 判 KEYCODE_BACK（SettingsDrawer.kt:502）。
- 但：浮层/焦点层是否消费方向键、未消费键不调 `super` 对 Android 7.0 Back 跟踪的影响、数字/OK/Back 与自定义映射在 Compose 子控件消费后的可达性，**均无法由源码或 lint 证明，须遥控器事件注入；本轮无设备，判未验证**。R07 的 Menu 绕过取消为源码可确认缺陷，须修复后复验。

## 6. 明确修复要求（下一轮，按优先级）

1. **R01/R02/R03/R08（P0，先修）**：统一用户意图代次，覆盖探测/恢复/生命周期 prepare；MediaItem 携带不可复用 session/attempt 并从事件取真实归属；源指针校验+缓存/Prefs 提交纳入同一串行事务、唯一 tmp、失败保留最后可用缓存；MANUAL 无缓存立即显示“没有可用缓存”与手动刷新/源配置，禁止空播放器。
2. **R04–R10（P1）**：EPG 结果区分成功/回退/失败并按源隔离缓存与时间戳；稳定条目 ID + 保存 LazyListState + 布局后 scrollToItem 再请求可见焦点，状态/弹层变化纳入返焦；Guide 用当前 liveUrl；所有开层动作前清数字定时器且提交校验会话；每源调度状态区分“退避暂缓/已尝试”；稳定频道组 ID 并只在成功首帧提交历史。
3. **R11（P2）**：Now/Next 独立计算、能力同 URL 构造判定、探测四态显式呈现，选择逻辑与文案一致。
4. **测试要求**：新增测试必须走生产入口并注入可控延迟/锁闩/时钟（旧探测并发、旧回调归属、源交错、EPG 断网回退、焦点请求），不得只测 `accepts`/常量/纯策略函数。
5. **设备复验清单**：TR100-G9/Android TV 模拟器注入 D-pad/OK/Back/数字/长按；真实首帧+60 秒、503/断网/空列表/无缓存、扫码推送、720p/1080p 焦点与可读、API24 真机启动。

## 7. 证据停止点说明

本轮源码读取与命令执行已全部停止；t8 不再读取源码、不再启动新命令。上述结论固定基于当前工作区 t7 后快照与 `artifacts/reverify/` 证据。请 captain 从 R01/R02/R03/R08 开始安排下一轮修复；修复后需再次独立复验，本轮不批准交付。
