# Android TV IPTV UI/UX 重构独立验证报告（t5）

日期：2026-11-18。验证人：acceptance。对象：t4 当前工作区快照（Compose `:app` A+B）。
依据：[验收标准 A01–A14](UI_UX_ACCEPTANCE.md)、[设计 v1](UI_UX_DESIGN.md)、[t4 实施说明](UI_UX_IMPLEMENTATION_NOTES.md)、[设计独立源码审查 D01–D16](UI_UX_DESIGN_REVIEW.md)。

## 0. 结论

**t4 当前快照验收失败，不建议交付/合入**。失败不是因为构建不过，而是因为存在多个源码可确认、会导致观看/恢复/配置闭环错误的功能缺陷（P0/P1），以及 lint 静态问题显著扩散。构建/单测通过仅证明工具链与已覆盖逻辑可运行，不代表产品通过。

本报告为独立核对：未采信 t4 自报结论；对 designer t6 的 D01–D16 逐条在源码复核，成立者引用 D 编号；另列出 acceptance 额外发现。证据类型为**源码审查 + 当前工作区构建 + 静态报告**。**模拟器遥控体验、目标真机体验、真实首帧、扫码推送、视觉/可访问性：未验证**（环境限制见第 4 节），不以源码推断冒充通过。

**证据边界（重要）**：本报告全部源码、构建与 lint 证据均采集自**修复前的 t4 快照**（验证期间未修改 Kotlin）。 captain 已在收到本报告结论后创建 t7 开发修复任务；t7 一旦改动源码，工作区即不再是本报告的快照，因此后续复验（t8/t9）不得把变化中的源码当成本报告证据，也不得以本报告的“失败”结论直接套用到修复后代码。所有结论只针对 t4 快照有效。

## 1. 验证命令与构建证据（当前工作区，真实本次执行）

环境：Windows / JDK 17.0.21 (`JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot`)、SDK `C:\Users\Q\AppData\Local\Android\Sdk`（`local.properties` 指向）、Gradle wrapper 8.13 / AGP 8.13.2 / Kotlin 2.0.21、minSdk 24 / targetSdk 36。

| 命令 | 退出码 | 结论 | 证据 |
|---|---|---|---|
| `./gradlew.bat :app:assembleDebug` | 0 | Debug APK 生成成功（A14 编译部分成立） | [artifacts/verify/assembleDebug.log](../artifacts/verify/assembleDebug.log) |
| `./gradlew.bat :app:testDebugUnitTest` | 0 | 单测任务通过；本次为 UP-TO-DATE，实际测试类执行来自此前运行，未新增针对本次缺陷的测试 | [artifacts/verify/testDebugUnitTest.log](../artifacts/verify/testDebugUnitTest.log) |
| `./gradlew.bat :app:lintDebug` | 0 | 退出码不具意义：项目 `abortOnError=false`；报告实际含 **134 errors / 57 warnings / 4 hints** | [artifacts/verify/lintDebug.log](../artifacts/verify/lintDebug.log)、[lint-results-debug.xml](../app/build/reports/lint-results-debug.xml) |

汇总记录：[artifacts/verify/summary.txt](../artifacts/verify/summary.txt)。

Lint 错误分布（按 id）：`NewApi` 95、`UnsafeOptInUsageError` 19、`RestrictedApi` 17、`GestureBackNavigation` 1、`ImpliedTouchscreenHardware` 1、`MissingLeanbackSupport` 1。
按文件：`PlayerActivity.kt` 54、`ChannelPanel.kt` 40、`XmlTvParser.kt` 21、`CatchupHelper.kt` 9、`ChannelInfoBanner.kt` 8、`AndroidManifest.xml` 2。

**基线归因（不把新增问题泛化为历史遗留，也不把历史问题误算新增）**：
- 确认既有/基线（证据限度说明）：`ImpliedTouchscreenHardware` 与 `MissingLeanbackSupport`（HEAD 版 Manifest 已含 `LAUNCHER`+`LEANBACK_LAUNCHER` intent-filter 且未声明对应 uses-feature）；`GestureBackNavigation`（[PlayerActivity:209](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L209) 使用 `KEYCODE_BACK`，HEAD 版亦有同类拦截写法）。`XmlTvParser`、`CatchupHelper` 为 HEAD 已跟踪文件。但 **HEAD 并不必然等于任务开始时的基线**：工作区存在大量未提交修改，且 `[build_baseline.log](../build_baseline.log)` 仅为编译记录（1 条 Kotlin 废弃警告），**无 lint 快照**，故“HEAD 已跟踪文件”只能证明该文件非本轮新建，不能证明其内部 lint 错误数量在任务开始时完全相同；上述判断仅为可确认的保守归因，不作无依据的定量宣称。
- 确认本轮引入/扩散：新增/重写文件 `PlayerActivity.kt`(54)、`ChannelPanel.kt`(40)、`ChannelInfoBanner.kt`(8) 中的 `NewApi`/`UnsafeOptInUsageError`/`RestrictedApi` 属重构引入或显著扩散；新代码使用 `java.time`(ZoneId/Instant 等) 与 minSdk 24 冲突，是 `NewApi` 的重要来源。基线 [build_baseline.log](../build_baseline.log) 仅有 1 条 Kotlin 废弃警告、无 lint 快照，故不对“既有文件错误总数”作无依据的定量断言，但可确认上述新增文件错误为本轮产物。
- 结论：A14 中“无新增阻断编译/静态问题”不成立；要求修复阶段清理新增错误并对既有错误建立 baseline/baseline 文件或显式豁免记录。
- **API 24 兼容问题**：当前实现（如 `ChannelPanel`、`PlayerActivity`、`EpgRepository` 及新增 `TvTokens`/`AppSettings` 等）直接调用 `java.time.ZoneId/Instant/LocalDate`，minSdk 为 24，属 `NewApi` 主要来源之一；需引入 core library desugaring 或改用兼容实现，并在 API 24 设备/模拟器上验证。

## 2. 缺陷清单（源码可确认，失败项）

以下 D 编号为对 [设计审查](UI_UX_DESIGN_REVIEW.md) 的独立交叉核对结果；“额外”项为本次验收新增发现。严重度 P0=阻断交付、P1=本轮核心范围缺陷。

### 2.1 启动/恢复闭环
- **D01 / P0 确认成立**：[SplashActivity:23–32](../app/src/main/java/com/cjlhll/iptv/SplashActivity.kt#L23) 在“恢复关闭”时不传 `VIDEO_URL`；[PlayerActivity:424–426](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L424) 在 initialUrl 为空时仍 `prepare()` 而无 MediaItem；缓存解析按 [PlayerActivity:543](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L543) 只选首台索引，而 [PlayerActivity:569](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L569) 明确 `mode != Initial` 才切流——首台只更新 UI、不启动播放。另 [PlayerActivity:436–446](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L436) 生命周期观察器只在建立时捕获 currentChannel/url，重建播放器可能播放旧 URL。
- **D12 / P1 确认成立**：[PlayerActivity:765](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L765) 在 `startPlayback` 之后才写 LastChannel，且 [PlayerActivity:753](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L753) 按 URL 变化写“上一台”，多线路/失败台会被错误记为恢复目标，违反 A03“持续播放至少 60 秒后成功频道才可恢复”。

### 2.2 刷新策略与数据完整性
- **D02 / A09 阻断 / P1 确认成立**：[IptvApplication:8](../app/src/main/java/com/cjlhll/iptv/IptvApplication.kt#L8) 无条件调用 `scheduleStartupRefresh`；[BackgroundSourceUpdater:36–56](../app/src/main/java/com/cjlhll/iptv/BackgroundSourceUpdater.kt#L36) 不读 `AppSettings` 策略、无列表 6h 调度；生产代码中 `getPlaylistRefreshPolicy` 仅出现在设置 UI。EPG 虽在 [PlayerActivity:687–701](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L687) 读策略且 MANUAL 关闭轮询，但启动 [BackgroundSourceUpdater:50](../app/src/main/java/com/cjlhll/iptv/BackgroundSourceUpdater.kt#L50) 仍 `forceRefresh=true`，且 [EpgRepository:31–54](../app/src/main/java/com/cjlhll/iptv/EpgRepository.kt#L31) 无缓存时 `forceRefresh=false` 也会联网——违背“仅手动”。DAILY 按跨天轮询实现而非“每天首次启动”。
- **D10 / P0 确认成立**：[BackgroundSourceUpdater:89–117](../app/src/main/java/com/cjlhll/iptv/BackgroundSourceUpdater.kt#L89) 仅检查 HTTP 200 与非空 body 即写缓存、改 Prefs、广播成功，不解析验证至少一个有效频道（HTTP200 零频道会摧毁最后可用缓存）；请求未校验源代次，旧源后台返回可覆写新源文件名指针。

### 2.3 错误预算/恢复竞态
- **D03 / P0 确认成立**：12s 监控 [PlayerActivity:480–488](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L480) 依赖 `loadingGeneration>0`；`initializePlayer` 未调用 `startLoading`，同 URL 重试 [PlayerActivity:743–745](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L743) 因 `isSameLiveUrl` 跳过计时——首次挂起与同 URL 重试都不会进入超时/失败终态。
- **D04 / P0 确认成立**：[PlayerActivity:623–653](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L623) 的恢复用普通 `scope.launch`，无 session/取消/await 后校验，旧 A 频道恢复返回可按 failedUrl=A 覆写用户新切换到 B 的状态；`failureAttempt` 被任意 READY 清零，错误事件未绑定 MediaItem；耗尽时直接 `playbackFailed=true` 不与现有面板互斥。
- **额外（acceptance）/ P0**：失败终态面板 [PlaybackFailurePanel.kt:84–88](../app/src/main/java/com/cjlhll/iptv/PlaybackFailurePanel.kt#L84) 只有“重试/选择频道/源配置”，**没有 A10/A08 要求的“返回直播”按钮**；t4 [实施说明](UI_UX_IMPLEMENTATION_NOTES.md) 亦承认此缺口，目前仅能通过源配置或双 Back 间接返回，回看失败时不能一步回直播。

### 2.4 配置流程
- **D05 / P0 确认成立**：[MainActivity:175–210](../app/src/main/java/com/cjlhll/iptv/MainActivity.kt#L175) 中 `loadJob?.cancel()` 后，通用 `catch (e: Exception)` 会捕获 `CancellationException` 并置 `phase=ERROR`；点击“取消”([MainActivity:368](../app/src/main/java/com/cjlhll/iptv/MainActivity.kt#L368)) 或 Back([MainActivity:244](../app/src/main/java/com/cjlhll/iptv/MainActivity.kt#L244)) 反而显示错误。连续推送 A/B 无请求代次保护，旧请求异常可覆盖新状态。

### 2.5 遥控器映射与焦点
- **D06 / A09 阻断 / P1 确认成立**：[PlayerActivity:285–295](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L285) 在查询 `AppSettings.actionForKey`（第 292 行）之前就对 `KEYCODE_CHANNEL_UP/DOWN` 硬编码换台并 return；把“节目单/上一台”等映射到 Channel± 后按该键永远执行默认换台，设置不生效。
- **D07 / P1 确认成立**：Group 右键使用 `targetGroupIndex`（基于 `focusGroupName`，仅在打开时初始化、无焦点上报更新）而非实际焦点分组；`ProgramRow`([ChannelPanel:668–677](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L668)) 不报告聚焦节目 ID，`focusProgramIndex`([ChannelPanel:173](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L173)) 由“当前时间”重算，日期 Back 后回不到进入前节目；设置根焦点 [SettingsDrawer:119–122](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L119) 每次回根都指向第一项而非调用项。违反 A06/A09/A12 的焦点恢复要求。
- **D11 / P1 源码成立**：设置主容器无左右键父/子层切换处理，仅捕获页注册 preview 事件。

### 2.6 EPG 与回看
- **D08 / P1 确认成立**：EPG 加载失败时 `epgData` 保持 null，节目页固定显示“正在加载节目单…”([ChannelPanel:617](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L617)) 且 `CenteredNote` 不可聚焦，无失败/重试状态，无法与真正加载中或“本日无节目”区分。
- **D09 / P1 确认成立**：设置“返回直播” [PlayerActivity:1027–1031](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L1027) 清空 `catchupRequest` 后播放 `currentChannel.url`（A），而双 Back 路径 [PlayerActivity:853–858](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L853) 使用 `req.liveUrl`（B）——从 B 频道回看后由设置返回会回错台；回看 B 不改 `currentChannel` 还会使信息条台名错乱。播放器无 `STATE_ENDED` 回直播分支（仅状态文本映射 [PlayerActivity:1128](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L1128)）。失败面板缺返回直播（见额外项）。
- **D13 / P1 源码成立**：所有历史节目一律标“可回看”([ChannelPanel:664](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L664))，不校验频道 catch-up 能力；频道行缺“下一节目”字段。

### 2.7 其他设计偏差（已纳入 t6 报告，本验收入证但不重复展开）
D14（自动线路入口未接延迟探测）、D15（推送空 EPG 字段存在性丢失、LAN 失败无重试、首次配置直接退出）、D16（Back 仅消费 Down、双 Back 计时不重置、无换台节流）均为 P1 源码偏差；其中 D15/D16 的运行表现需设备事件注入实测，当前标记“源码成立 / 运行未验证”。

## 3. A01–A14 逐项验收状态

判定规则：只有源码可确认的缺陷才判“失败”；仅因无设备/无运行证据而无法判定的判“未验证”；A14 的编译/单测子项有本次证据，但静态质量子项失败。

| ID | 结论 | 依据 |
|---|---|---|
| A01 首次配置 | 未验证（源码 D05/D15 有失败风险） | 无清数据冷启动/遥控器/首帧设备证据；取消→ERROR 已源码确认 |
| A02 扫码推源 | 未验证 | 无真实手机/LAN 推送设备证据；D05 旧请求覆盖风险源码成立 |
| A03 播放与启动恢复 | **失败** | D01、D12；60 秒真实首帧与设备重启未验证 |
| A04 数字跳台 | 未验证（源码结构存在） | 112 台样本与遥控器注入未执行；`dispatchKeyEvent` 有数字缓冲/OK/Back 路径但无运行证据 |
| A05 频道浏览与分组 | **失败** | D07（分组右键焦点错误）、D13；面板宽度 `min(360dp,W/3)` 源码符合但视觉未验证 |
| A06 节目单层级与焦点恢复 | **失败** | D07；确认弹层存在但取消回焦、日期 Back 锚点缺陷源码确认 |
| A07 EPG 与信息条 | **失败** | D08；缺数据/失败无法区分 |
| A08 回看 | **失败** | D09 + 失败面板无返回直播；seek/ENDED 运行未验证 |
| A09 设置与持久化 | **失败** | D02、D06、D07、D11；值持久化成立但多项目行为不生效，运行未验证 |
| A10 播放失败与恢复 | **失败** | D03、D04 + 失败面板缺陷；真实 503/断网设备复验未执行 |
| A11 坏源/空列表/断网缓存 | **失败（源码 D10）** | 空列表校验路径源码存在，但后台零频道覆写缓存/旧源覆写指针 D10 成立；断网冷启动设备未验证 |
| A12 遥控器焦点与返回 | **失败** | D04（面板互斥）、D07、D16；实体遥控注入未验证 |
| A13 可访问性与可读性 | 未验证（部分源码偏差 D13） | 无 720p/1080p 目视、字号/对比/透明边缘测量；设计 token 源码集中但不能替代目视 |
| A14 构建与自动化质量 | **失败** | 构建/单测子项通过（第 1 节证据）；lint 134 errors 且新增文件错误扩散，子项失败；无新增针对性单测 |

A14 细分：**构建通过、自动化检查（单测任务）通过、静态质量失败**；整体按验收规则判失败。

## 4. 环境限制与未验证项（不以源码推断替代）

- `adb devices` 仅返回 `192.168.2.132:5555 offline`；t4 记录无可用 AVD。未连接/未启动可用设备，也未清除应用数据或改动用户源码。
- 因此以下全部**未验证**：真实视频首帧与持续播放 60 秒、实体遥控器 D-pad/OK/Back/数字键/长按事件、扫码真实推送、断网/坏源现场、720p/1080p 视觉与可读性、透明 PNG 台标裁切、predictive back 手势。
- 历史 Linux 构建记录与以往真机记录只作背景，不计入本次通过证据。
- 本次未使用图像读取；无截图/录屏证据。
- 本次未使用图像读取；无截图/录屏证据。
- 修复后应补做（属 t8/t9 复验范围，不在本 t4 快照报告内）：模拟器或真机事件注入复验 D05/D06/D11/D16，A01/A02 端到端，以及 A13 目视测量。

## 5. 修复要求与复验门槛（给开发/复验）

1. **P0 必修**：D01（初始缓存解析后显式启动选中频道；播放器重建使用最新会话状态）、D03（所有 prepare 尝试统一 attempt + 12s 监控）、D04（单一播放 session/attempt，换台取消旧恢复，await 后校验，顶层互斥）、D05（CancellationException 重抛 + 请求代次 + 取消底层 call）、D10（下载内容临时校验有效频道后原子替换，写 Prefs 前校验源代次）。
2. **P1 必修**：D02（统一刷新调度入口，区分纯缓存与允许联网，MANUAL 不发网、DAILY 按当天首次启动、策略即时重算）、D06（先解析映射再处理 Channel± 默认）、D07（稳定焦点 ID 与入口锚点）、D08（EPG Loading/Ready/Empty/Failure/Cache 状态 + 可聚焦重试）、D09（所有返回直播统一用 `req.liveUrl`、ENDED 自动回直播、失败面板加“返回直播”）、D11/D12/D13/D15。
3. **A14 静态质量**：清理本轮新增/扩散的 lint 错误（NewApi/UnsafeOptIn/RestrictedApi），对 `java.time` 加 desugaring 或兼容实现；对既有错误建立 baseline 或豁免清单并说明。补针对 D01–D10 的状态/调度单测（可控时钟、请求记录、过期会话拒收）。
4. 修复后重新执行 t5 全量：构建/单测/lint 三命令 + 独立源码复查 + 设备/模拟器遥控器与首帧复验；按 A01–A14 重新给证。源码缺陷未清零前不得宣称通过。

报告人：acceptance。状态：t5 **failed**（当前 t4 快照不予交付）。
