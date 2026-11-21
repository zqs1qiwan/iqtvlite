# 第三轮修复独立验收核查（t14）

日期：2026-11-18。角色：acceptance（质量负责人）。结论：**failed / reject**。
本轮核查对象：[t13 修复说明](UI_UX_REPAIR_ROUND3.md) 声称修复的 S01–S06，回归核查原 A01–A14 与 [验收矩阵](UI_UX_ACCEPTANCE.md)，并与 [设计 third-round 复查](UI_UX_DESIGN_REVIEW_ROUND3.md)（t15，needs_revision，残余 T01/T02）交叉验证。

依据层级严格区分：**源码静态核查** / **当前工作区构建与自动化检查** / **模拟器或真机运行**。本轮**有**源码核查与本工作区 Gradle 重跑；**无**设备/遥控器/视觉运行，相关项一律标记“未验证”，不冒称通过，也不把“仅无设备”当作源码缺陷。

## 1. 本轮实际执行的构建与自动化检查（本工作区重跑，非引用 t13 数据）

三条命令在 `C:\Users\Q\Documents\Projects\iqtv` 顺序执行（JDK 17.0.21，Gradle 8.13，AGP 8.13.2）：

| 命令 | 退出码 | 结果 |
|---|---|---|
| `./gradlew.bat --no-daemon :app:assembleDebug` | 0 | BUILD SUCCESSFUL；多数任务 UP-TO-DATE |
| `./gradlew.bat --no-daemon :app:testDebugUnitTest` | 0 | BUILD SUCCESSFUL；JUnit XML 实际统计见下 |
| `./gradlew.bat --no-daemon :app:lintDebug` | 0 | BUILD SUCCESSFUL；lint XML 实际分类见下 |

- 单元测试：读取 `app/build/test-results/testDebugUnitTest/TEST-*.xml` 共 8 个文件，**71 tests / 0 failures / 0 errors / 0 skipped**，其中 `Round3LogicTest` 14 项、`Round2LogicTest` 10 项、`RepairLogicTest` 12 项。
- Lint：读取 `app/build/reports/lint-results-debug.xml`（lint 8.13.2），**75 issues：0 Error / 69 Warning / 6 Hint**，与 t13 自报一致；按规则 `abortOnError=false`，退出 0 不作为 lint 干净依据，本报告以报告文件为准。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，24,423,431 bytes，**SHA256 = F7D0AB79480E6951EFAB9530A716151679A7B0854E9C33BBB46179B39FA1FA03**，与 t13 报告及 t10 之后当前工作区 APK 一致；工作区 git 非 clean，无独立 t13 提交，无法用提交哈希锁定版本，故以 APK SHA 作为交付指纹。
- 构建通过 ≠ lint 无告警 ≠ 生产接线正确 ≠ 真机体验通过；以上只证明“当前源码可编译、71 项 JVM 逻辑测试通过、lint 无 Error”。

## 2. 逐 S 结论（第三轮 6 条修复目标）

判定口径：源码缺陷才算失败；仅有运行证据缺口则标“部分通过/未验证”，不单独判失败。

| S | 结论 | 证据与边界 |
|---|---|---|
| S01 恢复预算被 READY 清零 | **通过（源码+自动化）** | [PlaybackControl.kt:82](../app/src/main/java/com/cjlhll/iptv/PlaybackControl.kt#L82) 分离 mediaAttempt/recoveryBudget；[PlayerActivity.kt:539](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L539) READY 仅 `onReady()` 清预算；[834](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L834) 错误事件走 attemptState。`recoveryAfterReadyCanRecoverAgainInsteadOfDropping`、`consecutiveFailuresExhaustBudget`、`staleAttemptIgnored` 三项驱动生产状态机，71 测试全绿。真机 ExoPlayer 回调序列未验证 |
| S02 旧播放器迟发事件串扰 | **通过（源码结构+自动化；运行时未验证）** | [prepareMediaNow:562](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L562) 每次 activate→release→ensurePlayer(新实例)→attach 捕获 token 的监听→setMediaItem(mediaId=token)；[监听:524](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L524) READY/ENDED/error 先 `eventRouter.isCurrent(token)`；事件总线 [830](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L830) 再校验 router+intent+handledTokens。全仓唯一 prepare 入口，所有播放/回看/恢复路径经此（grep 确认）。`lateEventFromOldPlayerInstanceIsRejected` 仅驱动 AttemptEventRouter，不创建真实 ExoPlayer，不能单独揭露 Activity 若继续读可变 currentMediaItem 的错误；本条靠源码接线确认，不冒称运行时通过 |
| S03 STOP 后慢 probe/恢复覆盖新会话 | **通过（源码+自动化；生命周期时序未验证）** | [STOP:601](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L601) 提升 gate、cancel probe/recovery、清 router/token、release；[playGroupAuto:1028](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L1028) 提交前查 STARTED 且按稳定 `group.key` 重解析；[ProbeCoordinator](../app/src/main/java/com/cjlhll/iptv/PlaybackControl.kt) 探测前后双 gate 校验。`onStopInvalidatesInFlightProbe` 用 Deferred+`gate.newIntent()` 模拟 STOP，证明 gate 失效后协调器拒旧；真实 ON_STOP 观察器接线已读源码确认，真机交错未验证 |
| S04 历史节目取消返焦 | **部分通过** | [ChannelPanel:423](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L423) 确认前记节目 key、取消后滚锚点多帧 request；设置 Remote 取消返原动作/reset 行已实现。但设置捕获/确认弹层未建立模态焦点边界，构成残余 **T02**（见第 4 节），故本 S 不能销项为全通过 |
| S05 forceRefresh 丢同源缓存/注入回退真实网络/IO 线程 | **通过（源码+自动化；真实 IO/网络未验证）** | [EpgRepository:45–61](../app/src/main/java/com/cjlhll/iptv/EpgRepository.kt#L45) IO 内保留同源候选、`fetcher != null` 即用注入函数、null 不 Elvis 回真实网络；[loadStructuredCore:74](../app/src/main/java/com/cjlhll/iptv/EpgRepository.kt#L74) 可注入 fetcher/parser 且为生产调用核心。6 项 S05 测试全绿。`injectedFetcherNullDoesNotCallRealNetwork` 仅断言 Failure、无网络调用计数，防真网依赖源码确认而非测试本身 |
| S06 EPG 调度时钟/去重/首次启动 | **失败（源码确认缺陷 T01）** | inFlight/finally/退避结构正确（见第 3 节），但 DAILY “每天首次启动”语义存在明确遗漏分支，构成残余 **T01**（见第 4 节），不能判通过 |

## 3. 定向核查项结论（captain 指定）

| 核查项 | 结论 |
|---|---|
| recovery-success-then-error 真调用生产入口 | 成立：S01 三项测试驱动 `PlaybackAttemptState`，生产 READY/错误事件均接入同一状态机 |
| late old events 真调用生产入口 | 部分成立：测试驱动生产 `AttemptEventRouter`；每 attempt 独立 ExoPlayer + 捕获 token 由源码确认。测试本身无法发现 Activity 改回读 currentMediaItem 的回归，表述需受限 |
| STOP 慢 probe 真调用生产入口 | 部分成立：测试驱动生产 `ProbeCoordinator` + gate；`gate.newIntent()` 为测试模拟，真实生命周期观察器靠源码确认 |
| forceRefresh/loadStructured fallback/无真实网络逃逸 | 成立：生产 `loadStructured`/`loadStructuredCore` 可注入且全仓网络仅在无 fetcher 时的 `downloadBytes`；A/B 隔离、null 回退均测到 |
| EPG 时钟与 inFlight | 结构成立：可注入 clock；`tryBegin`/`end` 在 finally 释放（[PlayerActivity:888](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L888)、[BackgroundSourceUpdater:221](../app/src/main/java/com/cjlhll/iptv/BackgroundSourceUpdater.kt#L221)）；启动/周期/手动/配置后刷新全走共享通道，缓存回退不记成功。但 DAILY 时钟分支有 T01 缺陷 |
| R03 源版本+单 Editor 提交 | 成立且表述收敛：[Prefs.setSourceConfig:66](../app/src/main/java/com/cjlhll/iptv/Prefs.kt#L66) 源元组单 Editor；[SourceRepository:43](../app/src/main/java/com/cjlhll/iptv/SourceRepository.kt#L43) 持久 version + 单 repo Mutex + 后台 expectedVersion 校验 + AtomicFile 缓存。version 与源元组仍是两个 Editor、文件与 Prefs 非跨资源事务；不得声称崩溃原子或跨进程并发已证明。未发现本轮 R 项回归 |
| prepare 唯一入口 | 成立：全仓仅 [PlayerActivity:572](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L572) 调 `prepare()`/`setMediaItem`，startSession/回看/恢复/设置重置统一汇聚 `prepareMediaNow` |

## 4. 残余缺陷（源码确认，需修复后复验）

### T01 / P1 / A09：DAILY 今日已有成功数据时启动，常驻跨午夜仍会刷新

- 复现条件：EPG 策略=“每天首次启动”；同源今天已有一次成功更新（旧进程或手动）；冷启动新进程后保持运行跨午夜。
- 预期：按“每天首次启动”，本次已启动过即不再因跨午夜在未重启的进程内触发每日刷新。
- 实际（源码推导）：[EpgRefreshCoordinator:59](../app/src/main/java/com/cjlhll/iptv/EpgRefreshCoordinator.kt#L59) `DAILY -> !dailySeeded && lastDay != today`。今日成功启动使 `lastDay==today`，shouldRunAuto=false → performEpgRefresh 不执行 → [markAutoAttempted:67](../app/src/main/java/com/cjlhll/iptv/EpgRefreshCoordinator.kt#L67) 不被调用 → dailySeeded 不含该源。跨午夜后周期循环 [PlayerActivity:945](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L945) `lastDay!=today && !dailySeeded` 变 true，触发刷新。与 [修复说明](UI_UX_REPAIR_ROUND3.md) “常驻跨午夜不刷新”矛盾。
- 测试缺口：`dailyDoesNotRefreshAgainWhenResidentAcrossMidnight`（Round3LogicTest:184）先 `markAutoAttempted` 再跨午夜，只覆盖“启动需联网”分支，没有“今日成功→启动跳过→跨午夜”用例。
- 修复要求：把“本进程该源启动资格/基准日期”与“实际请求已尝试”分开记录；启动时已有今日成功数据应完成该次启动的 DAILY 判断。补三条可控时钟反例：今日成功→启动跳过→跨午夜不刷；旧成功→启动暂缓→退避后补做；补做完成→跨午夜不刷。测试直接驱动生产 coordinator 并核对生产入口确实记录启动状态。修复后 t14 复验。

### T02 / P1 / A09/A12：设置捕获/恢复默认弹层未建立模态焦点边界

- 复现条件：设置→遥控器→点击任一动作进入按键捕获，或进入“恢复默认映射”确认弹层；用遥控器方向键操作。
- 预期：捕获期间仅捕获层消费完整方向键 Down/Up，背景子项不可聚焦/不可点击；取消后焦点回到原动作/reset 行；确认期间焦点限制在确认按钮组。
- 实际（源码）：[SettingsDrawer:153](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L153) 背景 RemotePage 与捕获/确认层同处组合树，未使用 `focusProperties`/逐项禁用背景焦点；[CaptureOverlay:543](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L543) `ev.type != KeyUp` 直接返回 false，仅 KeyUp 捕获，方向 KeyDown 仍参与空间焦点搜索，焦点移出捕获层后 KeyUp 不一定经过该节点。左右键代码 [161](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L161) 只防底层处理 Left，不封闭其他方向。
- 边界：这是源码层面“承诺的模态约束未实现”，并非声称已在设备复现焦点逃逸；真实遥控体验保持未验证，不能因此把模态隔离判为已完成。
- 修复要求：捕获期间消费完整方向键 Down/Up 并把待分配键交给捕获状态机；确认期间把焦点限制在确认按钮组并禁用/隐藏背景焦点参与，逐项控制而非仅父容器 `canFocus=false`；弹层出现后等布局再请求初始焦点。补 Compose/遥控注入测试覆盖非首项、四向与长按、Back 取消、背景不被修改。修复后 t14 复验。

## 5. 逐 A01–A14 判定

> 勘误（2026-11-18，captain 指出）：本节 ID 与标题严格沿用 [原始验收矩阵](UI_UX_ACCEPTANCE.md) 第 2 节的 A01–A14，不自定义重排。初稿自 A02 起发生编号/标题漂移，已按原始矩阵整体校正；T01/T02 的失败结论不变（仍分别归属 A09、A09/A12）。第 4 节中 T01 标注 A09、T02 标注 A09/A12 与原始矩阵一致，未改动。

自动化与源码层面可判仅覆盖下列结构项；设备/视觉/首帧/扫码/断网播放等维持未验证。以下“未验证”不是失败，只表示当前无设备证据；“部分通过”仅指源码+JVM 逻辑层，不代表原用例的设备判定通过。

| A（原始 ID / 标题 / 严重度） | 本轮判定 | 说明 |
|---|---|---|
| A01 首次配置 / P0 | 未验证 | 配置页二维码/LAN/双地址字段、焦点可达、保存后真实首帧与错误可操作性均需设备；无设备未跑 |
| A02 手机扫码推源 / P1 | 未验证 | 真实扫码与 LAN 推送/提交需手机+TV 互通环境；无设备与扫码端 |
| A03 播放与启动恢复 / P0 | 未验证 | 恢复最近成功频道/首台切换、60 秒持续播放、重启持久化需设备；D01 缓存后显式起播的源码接线已读，不作运行通过依据 |
| A04 连续数字跳台 / P0 | 未验证 | 数字缓冲显示 112、OK 提交、1.2 秒自动提交、Back 取消、全局 112 映射需遥控器；ChannelNumberingTest(7)/TvTokensTest(5) 逻辑测试通过，遥控器/视觉未验证。数字 Menu 取消属已修项，本轮未重复设备验收 |
| A05 频道浏览与分组 / P0 | 未验证 | 左键开节目单、分组浏览、焦点不改变播放、当前标记与焦点样式区分、侧栏宽度需设备/视觉；源码宽度已读，不作通过依据 |
| A06 节目单层级与焦点恢复 / P1 | 未验证 | 历史节目 OK 回看、确认后回看弹层焦点与取消返原历史节目焦点、层级 Back 需设备/遥控器；S04 取消返焦源码修复已读（ChannelPanel:423/267），需设备复验 |
| A07 EPG 与信息条 / P1 | 部分通过（源码+逻辑） | 当前/下一节目与进度（R11）、缺 EPG 占位、刷新失败与“无节目”区分的源码与 JVM 逻辑已读；真实 EPG 数据/时间显示需设备 |
| A08 回看 / P1 | 未验证 | catch-up URL 仅在能力有效时生成、seek/长按、Back 回直播、回看失败重试需设备；回看 prepare 接线已读，未跑运行 |
| A09 独立设置与持久化 / P1 | **失败** | 设置入口焦点与关闭返原上下文、按键冲突/恢复默认、各项（含 EPG 刷新时机）重启持久化属本用例范围；T01（DAILY 刷新时机常驻跨午夜误刷）与 T02（捕获/重置弹层无模态焦点边界）均为源码确认缺陷，见第 4 节 |
| A10 播放失败与恢复 / P0 | 部分通过（源码+逻辑） | Loading 超时/完成、恢复耗尽常驻可读错误+可行动作、替代线路尝试、用户可换台/回配置——即 S01/S02/S03 结构修复（预算/旧事件/STOP 慢 probe）与单测已确认；真实 ExoPlayer 错误注入、503 归因与遥控器可达性需设备 |
| A11 空列表、坏源与断网缓存 / P0 | 部分通过（源码+逻辑） | 空/坏列表状态与文案、零频道不进入空白播放器（D10）、断网缓存启动与同源回退（R03/S05）的源码+JVM 逻辑已确认；坏地址/断网冷启动需设备 |
| A12 遥控器焦点与返回优先级 / P0 | **失败** | 仅顶层交互接收按键、焦点不进隐藏组件、Back 逐层关闭并恢复调用点——T02 的捕获/确认弹层未封闭背景焦点、仅 KeyUp 捕获，属源码缺口，见第 4 节；真实连续 Back/快速重复输入需设备 |
| A13 可访问性与可读性 / P1 | 未验证 | 可见焦点、状态非仅颜色、正文 ≥16sp、远距可读、透明 PNG 台标比例、空/错/loading 语义区分需设备视觉；源码字号/宽度不等于实际远距效果 |
| A14 构建与自动化质量 / P0 | **通过** | 本工作区已执行 `assembleDebug`/`testDebugUnitTest`/`lintDebug`（退出码均 0），并分别读取 JUnit XML（71 tests/0 fail）与 lint XML（0 Error/69 Warning/6 Hint），记录命令、退出码、报告路径与 APK SHA；无新增阻断编译/静态问题。见第 1 节 |

## 6. 停止点与复验条件

- 当前**停止全量 A+B 验收批准**：T01/T02 为源码确认的 P1，须先修复。按原始矩阵，T01 阻断 A09，T02 阻断 A09/A12。
- 复验范围：仅针对 T01、T02 的代码与测试；确认 S01–S05 未被回退、R03 表述仍收敛。复验将重跑 assemble/test/lint 并读取报告、重新记录 APK SHA，再更新本结论。A14 本轮已通过，无需重复作为阻断项。
- 设备侧待补（按原始矩阵 ID）：A01 首次配置与首帧；A02 扫码/LAN 推源；A03 播放 60 秒与启动恢复重启；A04 数字跳台（112/OK/1.2s/Back）；A05 频道浏览焦点与侧栏；A06 节目单层级与 Back 返焦；A07 EPG 数据/进度/缺数据占位；A08 回看 seek 与返回直播；A09 设置各项重启生效；A10 503/替代线路/重试/无永久 loading；A11 坏源/空列表/断网缓存；A12 遥控器焦点优先级与连续 Back；A13 远距可读与透明台标。另需补 S02/S03 真实 ExoPlayer 时序。无设备期间一律“未验证”，不作为通过或失败依据。
- 本任务到此停止读源码；未修改任何 Kotlin/Gradle，仅新增/勘误本报告。已与 designer（t15）交叉确认残余 T01/T02，向 captain 报告 failed/reject。

## 7. 终态补充证据（勘误记录）

- 2026-11-18，captain 复核指出初稿第 5 节自 A02 起 A 编号/标题漂移（如原 A04=连续数字跳台被误写为播放/暂停、原 A08=回看被误写为线路优选、原 A10=播放失败恢复被误写为 EPG 状态、原 A14=构建与自动化被误写为返回/视觉）。本次按 [原始验收矩阵](UI_UX_ACCEPTANCE.md) 第 2 节完整校正第 5 节与第 6 节设备映射，并补充本节作为终态证据。
- 勘误不改变任何验收结论：总体仍 failed/reject；T01（A09）与 T02（A09/A12）源码确认缺陷保持；S01/S02/S03/S05 通过、S04 部分通过、S06 失败的判定不变；第 1 节构建/测试/lint 证据与 APK SHA 不变。
- 校正带来的明确变化：**A14（构建与自动化质量）本轮实际已通过**（初稿误列为 A14 返回/视觉未验证）；A10（播放失败与恢复）对应 S01/S02/S03 结构修复，判部分通过；A06（节目单层级与焦点恢复）对应 S04 取消返焦源码修复，待设备复验。
- 本勘误未读变化源码、未运行任何命令，仅依据原始矩阵文档与本报告既有的第 1–4 节证据。后续所有报告沿用原始 A01–A14 ID，不再自定义重排。
