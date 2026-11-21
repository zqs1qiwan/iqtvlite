# Android TV IPTV 第四轮独立复验（t17）

日期：2026-11-18。执行者：acceptance（独立验收）。对象：[t16 第四轮修复说明](UI_UX_REPAIR_ROUND4.md) 的 T01（DAILY 启动判定）与 T02（设置捕获/恢复默认模态边界），并审查 S01/S02/S03/S05、R01–R11 是否回退。

本文为独立验证记录，不是“全量产品验收通过”证明。证据类型严格分开：源码审查 / JVM 单测 / 当前工作区构建 / 设备体验。A 编号与标题沿用 [原始验收标准](UI_UX_ACCEPTANCE.md) 第 2 节 A01–A14，不重命名、不重排。

## 1. 命令与证据（本工作区，本次独立执行）

| 命令 | 退出码 | 结果 |
|---|---|---|
| `./gradlew.bat --no-daemon :app:assembleDebug` | 0 | BUILD SUCCESSFUL（37 tasks up-to-date） |
| `./gradlew.bat --no-daemon :app:testDebugUnitTest --rerun-tasks` | 0 | BUILD SUCCESSFUL（24 tasks executed，强制重跑） |
| `./gradlew.bat :app:lintDebug` | 0 | BUILD SUCCESSFUL（28 tasks，2 executed） |

> 基础设施事件（非代码失败）：首次以 `./gradlew.bat --no-daemon :app:lintDebug --rerun-tasks` 运行时 Gradle daemon 意外退出（退出码 1，报 “Gradle build daemon disappeared unexpectedly”）。随后以不带 `--no-daemon --rerun-tasks` 的常规命令重跑，退出码 0 且 lintAnalyzeDebug 实际执行（2 executed）。以下报告读取的是成功那次生成的产物；daemon 崩溃未发现与源码相关。

报告读取（不只看退出码）：

- JUnit：`app/build/test-results/testDebugUnitTest/*.xml` 共 8 个文件，**77 tests / 0 failures / 0 errors / 0 skipped**；含本次目标用例 `t01_todaySuccessStartupSkipsAndNoMidnightRefresh`、`t01_oldSuccessStartupBackoffThenCatchUp`、`t01_catchUpDoneThenNoMidnightRefresh`、`t02_captureSubmitsOnceOnMatchingDownUp`、`t02_captureConsumesDirectionsAndCancel`、`t02_captureIgnoresMismatchedUpBetweenPresses`。
- Lint：`app/build/reports/lint-results-debug.xml` 共 75 issues = **0 Error / 69 Warning / 6 Hint**（项目 `abortOnError=false`，退出码 0 不代表无问题，故单独读取报告）。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，24,427,137 bytes，SHA256 `BAA9D1E897F085CB87B3A9A3F53BCBF782AF6BBF0DD756508B1D4E681E486AE1`（与 t16 声明一致，且晚于其声明的写入时间，说明本轮 APK 与 t16 产物同一内容）。
- 版本快照：JDK `openjdk 17.0.21`；Gradle wrapper `8.13-bin`；AGP `8.13.2`；Kotlin `2.0.21`；minSdk 24 / targetSdk 36。构建入口 `./gradlew.bat`。

## 2. T01 独立复验（DAILY 启动判定）——源码/自动化部分通过，另发现 1 个 P1 边界残余

### 2.1 三条已声明分支：源码确认修复

`EpgRefreshCoordinator.kt:60-73` 的 `shouldRunAuto(DAILY)` 逐行确认：

1. `dailySatisfied.contains(source)` → false（已满足启动判定，不再按日期触发）。
2. `dailyLaunchDay.getOrPut(source){ today }`；`today != launchDay` → false（常驻跨午夜不刷新）。
3. `lastDay == today`（启动今日已有成功）→ 置 `dailySatisfied` 并 false（完成该次启动判定）。
4. 需刷新但命中 5 分钟退避 → false 且**不置** satisfied（保留补做资格）。
5. 否则 true。
6. `markAutoAttempted(DAILY)` 同时置 `dailySeeded` 与 `dailySatisfied`（:83-89），实际尝试后不再触发。

三条可控时钟测试（`Round3LogicTest.kt:211-257`）确为驱动生产 coordinator 而非替身，覆盖：今日成功启动跳过且跨午夜不刷；旧成功+退避暂缓后当天补做并 mark；补做完成后跨午夜不刷。缺陷实现（仅 `dailySeeded`）在第 1、3 条会失败——反例有效性成立。

**Activity 启动接线确认**：`PlayerActivity.kt:919-934` 启动 `LaunchedEffect` 在 MANUAL 早退后**无条件**调用 `shouldRunAuto`（:924），即使返回 false（如上述第 2/3 条）也已让 coordinator 完成内部副作用（登记 launchDay / 置 satisfied）；周期循环（:937-954）复用同一函数。`performEpgRefresh`（:856-892）统一 `tryBegin/markAutoAttempted/end`。即“发起请求”与“完成启动判定”已解耦，但 t16 的副作用依赖 `shouldRunAuto` 被调用这一前提成立。

### 2.2 新发现 P1 残余：跨午夜吞掉“启动暂缓补做”

- 复现条件（源码层，可控时钟可复现；无设备依赖）：23:58 冷启动，DAILY 策略，今日无成功记录但 5 分钟退避生效中（例如 23:55 有 lastAttempt）。
- 操作步骤：调用一次 `shouldRunAuto(source, DAILY, lastSuccess=昨天, lastAttempt=23:55)`；把时钟推进到次日 00:03，再调用一次。
- 实际（源码）：首次调用 `dailyLaunchDay=D`，命中 :71 退避返回 false，未置 satisfied，也未显式记录“待补做”；第二次调用 `today=D+1 != launchDay=D` 命中 :64 直接 false。生产侧无其他补做入口（Activity 启动/周期均复用 `shouldRunAuto`；`BackgroundSourceUpdater.refreshEpg` 也仅按 `lastAttempt` 退避，无跨午夜特殊路径）。于是本次启动被暂缓的补做资格在跨午夜后永久丢失，直到下一次重新启动才重新登记 launchDay。
- 预期（原始 A09 刷新时机语义）：启动被退避暂缓时“保留补做资格”，退避结束后（本例 00:03 已远超 5 分钟）应完成该次启动待补请求一次；常驻跨午夜不应新增请求，但不应吞掉已产生的待补资格。
- 边界：这是 t16 新引入 `dailyLaunchDay`/`dailySatisfied` 与既有“退避保留补做”语义的组合缺口，三条 T01 测试均未覆盖 23:58+退避跨午夜场景。**不是** A07 EPG 展示缺陷，归属 A09（设置项“EPG 刷新时机”的生效行为）。与 designer（t18 及其勘误）交叉确认一致。
- 修复要求（不改产品语义）：在 coordinator 显式维护 pending 集合（退避暂缓时记录该源待补）；`today != launchDay` 的跨午夜分支仅对非 pending 源返回 false，pending 源待退避结束后允许补做一次，实际发起后经 `markAutoAttempted` 清除 pending。补 1 个可控时钟用例：23:58 退避暂缓 → 00:03 补做成功 → 再跨午夜不刷。修复后由 t17 同源复验。

### 2.3 结论

T01 已声明的三分支与 Activity 接线源码/自动化通过；2.2 的跨午夜 pending 边界为**唯一源码 P1 残余**，阻断 A09 全量通过。

## 3. T02 独立复验（设置捕获/恢复默认模态边界）——源码通过

逐行确认 4 项承诺均在真实生产代码接线（非仅测试替身）：

1. **背景逐子项禁用**：`SettingsDrawer.kt:253` 向 `RemotePage(enabled = capturing == null && !confirmReset)`；`RemotePage` 将 `enabled` 下传每个动作 `SettingsRow`（:475）与 reset 行（:485）；`SettingsRow` 用 `.focusable(enabled)`（:612）与 `.clickable(enabled)`（:613），禁用时文字/leading/value 用 `TextDisabled`（:619/:625/:634）。是逐子项禁用，不是仅父容器 `canFocus=false`。
2. **完整 Down/Up 消费 + 一次提交 + 重复忽略 + Back 取消**：新 `CaptureKeyState.kt:19-34` 生产状态机；`CaptureOverlay.onPreviewKeyEvent`（`SettingsDrawer.kt:563-575`）对 Down 与 Up 都返回 Consume（不参与背景空间焦点搜索），只在匹配 Down→Up 时 Submit 一次；重复 Down（`repeatCount!=0`）不改写待提交键；Back（keycode 4）Down/Up 均 Cancel。三个 `t02_*` JVM 测试直接驱动该生产状态机（一次提交/重复忽略、方向消费与 Back 取消、错配 Up 不提交）。
3. **确认组焦点封闭**：确认弹层仅在 `confirmReset=true`（背景已全禁用）时显示（:306-330）；两按钮为唯一可聚焦项，`取消`按钮持 `confirmFocus`（:327）；`LaunchedEffect(confirmReset)` 用 `withFrameNanos` 等布局后请求初焦（:144-146）。
4. **关闭返焦（滚动后）**：`remoteFocusNonce` 在捕获取消（:142）与 reset 取消（:148）时自增；`RemotePage` 的 `LaunchedEffect(focusIndex, resetRowIndex, focusNonce)`（:457-462）先 `scrollToItem(目标)`，再多帧 `requestFocus` 到原动作行（`rowRequester`）或 reset 稳定行（`resetRequester`）；动作行与 reset 行分别绑定 requester（:476/:486），避免同节点双 requester。

**结论**：T02 源码层承诺完整，可从源码判“已修复”；JVM 状态机测试通过。**运行层（Compose 焦点/滚动/真实 D-pad）无 Android UI / Robolectric 环境，未验证**，不作为源码缺陷。

## 4. S/R 防回退审查

- 静态结构审查：S01 `PlaybackAttemptState`（`PlaybackControl.kt:82`）、S02 `ProbeCoordinator`/`AttemptEventRouter`/`MediaToken` 与每 attempt 隔离播放器（`PlaybackControl.kt:21/42/60`；`PlayerActivity.kt:559` 注释与接线）、S03 `STARTED` 提交前校验（`PlayerActivity.kt:1027-1028`）、S05 `loadStructuredCore` 可注入与同源缓存候选（`EpgRepository.kt:36/45/74`）、S06 inFlight（`EpgRefreshCoordinator.kt:19`）、R03 `SourceRepository` Mutex + 持久 version + `commitSource` 单 Editor（`SourceRepository.kt:38/43-48`；`Prefs.setSourceConfig` 单 Editor 在 `:71`）、`PlaylistCache` AtomicFile（`:35`）均保持。
- 最终 diff 为跨全部轮次的累积差异（git 仓库仅一个 `8c93e1b` 历史提交；`reconstruction/` 为未跟踪反编译目录，非本次改动）。本轮 t16 增量集中于 `EpgRefreshCoordinator`、`CaptureKeyState`、`SettingsDrawer`、`PlayerActivity` 接线与 `Round3LogicTest`；未发现删除或弱化已修 S/R 结构。
- 表述边界保持：R03 仍是 Mutex + 持久 version + 单 Editor 源元组，不声称跨文件/跨进程崩溃原子性；S02/S03/S05 的真实 ExoPlayer 回调排队、真实 Lifecycle 交错、真实文件/网络仍靠源码结构，未运行验证。
- 结论：无 S/R 回退证据。

## 5. 逐 A01–A14 判定（原始 ID / 标题 / 严重度）

“未验证”表示无设备/视觉证据，不是失败；“部分通过（源码+自动化）”仅指源码与 JVM 逻辑层，不代表原用例设备判定通过。

| A（原始 ID / 标题 / 严重度） | 本轮判定 | 依据与边界 |
|---|---|---|
| A01 首次配置 / P0 | 未验证 | 配置页/二维码/LAN/双地址/首帧/错误可操作需设备；无设备未跑 |
| A02 手机扫码推源 / P1 | 未验证 | 真实扫码与 LAN 推送需手机+TV 互通；无设备 |
| A03 播放与启动恢复 / P0 | 未验证 | 60 秒持续播放、重启恢复/关闭/重开需设备；源码路由已读不作运行通过 |
| A04 连续数字跳台 / P0 | 未验证 | 数字缓冲 112/OK/1.2s/Back 与映射一致需遥控器；逻辑单测通过但遥控器/视觉未验证 |
| A05 频道浏览与分组 / P0 | 未验证 | 侧栏宽度、焦点不改播放、当前标记与焦点并存需设备/视觉 |
| A06 节目单层级与焦点恢复 / P1 | 未验证 | 历史节目 OK/确认/取消返焦、逐层 Back 需遥控器；S04 源码修复待设备复验 |
| A07 EPG 与信息条 / P1 | 部分通过（源码+自动化） | Now/Next/进度、缺数据占位、刷新失败与无节目区分的源码与 JVM 逻辑已确认；真实 EPG 数据/时间需设备 |
| A08 回看 / P1 | 未验证 | catch-up 能力生成、seek、Back 回直播需设备；源码接线已读未运行 |
| A09 独立设置与持久化 / P1 | **失败（源码 P1）** | T02 已修复（源码通过）；**T01 跨午夜 pending 补做被吞为唯一残余源码缺陷**（见 §2.2），阻断本项全量通过；其余持久化项仅源码/JVM 证据，重启生效需设备 |
| A10 播放失败与恢复 / P0 | 部分通过（源码+自动化） | S01/S02/S03 结构修复与单测确认（预算/旧事件/STOP 慢 probe）；真实 ExoPlayer 错误注入、503 归因、遥控器可达需设备 |
| A11 空列表、坏源与断网缓存 / P0 | 部分通过（源码+自动化） | 空/坏列表状态、零频道不进空白播放器、断网缓存同源回退的源码+JVM 逻辑已确认；坏地址/断网冷启动需设备 |
| A12 遥控器焦点与返回优先级 / P0 | 源码通过，设备未验证 | T02 模态焦点边界已按源码完整修复（§3）；真实 D-pad/连续 Back/快速重复输入无运行环境，未验证。不再作为源码缺陷 |
| A13 可访问性与可读性 / P1 | 未验证 | 可见焦点、状态非仅颜色、≥16sp、远距可读、透明台标比例需设备视觉 |
| A14 构建与自动化质量 / P0 | **通过（独立判定）** | 本轮独立执行 assemble/test/lint，退出码均 0；单独读取 JUnit XML（77/0/0/0）与 lint XML（0 Error/69 Warning/6 Hint）；记录命令、报告路径与 APK SHA（§1）。首次 lint `--rerun-tasks --no-daemon` 遇 daemon 崩溃，常规重跑成功并实际执行 lintAnalyzeDebug，已在 §1 标注 |

## 6. 终态与停止点

- **源码/自动化检查结论**：T02 源码通过；T01 三分支+Activity 接线源码/自动化通过；S/R 无回退；构建/单测/lint（含报告读取）通过 → A14 通过。无 P0 源码缺陷。
- **唯一待修项**：T01 跨午夜 pending 补做被吞（§2.2，P1，A09）。已给明确修复要求与 1 个可控时钟回归用例；修复后由 t17 同源复验。designer（t18 及勘误）认可该结论，其不可变 pass 不覆盖该组合。
- **设备缺口（全部未验证，不计失败）**：A01–A06、A08 的遥控器/首帧/扫码/回看/视觉；A07/A10/A11 的真实数据与 ExoPlayer 时序；A12 的真实 D-pad/Back；A13 的远距可读与台标；无 AVD、adb 目标 offline。完整产品验收须待设备。
- 本任务到此**停止读源码与运行命令**；未修改任何 Kotlin/Gradle/生产代码，仅新增本报告。与 designer 已交叉，报告提交 captain，t17 结案（failed：存在唯一 P1 源码残余阻断 A09 全量通过；非因缺设备伪造 reject）。
