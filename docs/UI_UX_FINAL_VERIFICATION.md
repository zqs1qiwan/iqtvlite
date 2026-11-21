# Android TV IPTV 最终独立复验与交付状态（t20）

日期：2026-11-18。执行者：acceptance（独立验收）。对象：[t19 DAILY 跨午夜 pending 修复](UI_UX_DAILY_PENDING_FIX.md) —— 上一轮 [第四轮复验 §2.2](UI_UX_VERIFICATION_ROUND4.md) 记录的唯一 P1 源码残余（A09 EPG 刷新时机）。同时汇总 A01–A14 最终交付状态。

本文为独立验证记录，不宣称完整产品验收通过。证据类型严格分层：源码审查 / JVM 单测 / 当前工作区构建 / 设备与视觉体验。A 编号与标题沿用 [原始验收标准](UI_UX_ACCEPTANCE.md) 第 2 节，不重命名、不重排。

## 1. 最终源码快照与命令证据（本工作区，本次独立执行）

| 命令 | 退出码 | 结果 |
|---|---|---|
| `./gradlew.bat --no-daemon :app:assembleDebug` | 0 | BUILD SUCCESSFUL（37 tasks up-to-date） |
| `./gradlew.bat --no-daemon :app:testDebugUnitTest --rerun-tasks` | 1 | Gradle daemon 意外退出（基础设施事件，非代码失败） |
| `./gradlew.bat :app:testDebugUnitTest --rerun-tasks` | 0 | BUILD SUCCESSFUL（24 tasks executed，强制重跑） |
| `./gradlew.bat :app:lintDebug` | 0 | BUILD SUCCESSFUL（lint 分析 UP-TO-DATE） |
| `./gradlew.bat :app:lintDebug --rerun-tasks` | 0 | BUILD SUCCESSFUL（28/28 tasks executed，强制全新 lint 分析） |

> 说明：`--no-daemon --rerun-tasks` 组合两次导致 daemon 崩溃（t17 的 lint、本轮的 test），属该执行方式的基础设施问题；改用默认 daemon 重跑均成功。最终报告读取的是成功执行产生的产物。

报告读取（不只看退出码）：

- JUnit：`app/build/test-results/testDebugUnitTest/*.xml` 共 8 个文件，**79 tests / 0 failures / 0 errors / 0 skipped**；其中 `TEST-com.cjlhll.iptv.Round3LogicTest.xml` 为 22 tests / 0 fail / 0 error，含全部目标用例：`t19_pendingDeferredAcrossMidnightCatchesUpThenNoRefresh`、`t19_nonPendingSourceAcrossMidnightDoesNotStartNewDaily`、`t01_todaySuccessStartupSkipsAndNoMidnightRefresh`、`t01_oldSuccessStartupBackoffThenCatchUp`、`t01_catchUpDoneThenNoMidnightRefresh` 与三条 `t02_*`。
- Lint：`app/build/reports/lint-results-debug.xml` 共 75 issues = **0 Error / 69 Warning / 6 Hint**（`abortOnError=false`，退出码 0 不代表无问题，已单独读报告；强制重跑后数值与 t16/t19 一致，无新增）。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，24,427,088 bytes，SHA256 `8AD8788E327C13D0961EFFB1B40754E047F0D370D5320491A2EA68AA217ABC3C`（与 t19 声明一致；晚于 t17 的 `BAA9D1E8...`，为 t19 修复后的最终产物）。
- 版本快照：JDK `openjdk 17.0.21`；Gradle wrapper `8.13-bin`；AGP `8.13.2`；Kotlin `2.0.21`；compileSdk 36 / minSdk 24 / targetSdk 36。
- 设备条件复查（短检查，无长超时）：`adb devices` 仅表头、无设备；`~/.android/avd` 不存在。与历轮一致，设备条件未变化。

## 2. t19 最小修复独立复验 —— 通过

### 2.1 diff 范围

按文件修改时间核对：t19 仅触碰 `EpgRefreshCoordinator.kt`（13:06:58）与 `Round3LogicTest.kt`（13:07:03）。T02 相关（`SettingsDrawer.kt` 12:55、`CaptureKeyState.kt` 12:53）与 S/R 相关（`PlayerActivity.kt` 12:41、`SourceRepository.kt` 12:40、`EpgRepository.kt` 12:42、`Prefs.kt` 12:40 等）均未改动，生产接线 `PlayerActivity.kt` 的 `tryBegin`(:857)/`markAutoAttempted`(:859)/启动 `shouldRunAuto`(:924)/周期 `shouldRunAuto`(:945) 与 t17 复验时一致。与“最小修复”声明相符，无夹带改动。

### 2.2 跨午夜 pending 回归（上一轮唯一 P1）——源码确认修复

`EpgRefreshCoordinator.kt` 新增 `dailyPending: HashSet<String>`（:24），`resetForTest` 清空（:28），修改点逐行核对：

- **退避暂缓记 pending**（:79-81）：同启动日、今日无成功、命中 5 分钟退避 → `dailyPending.add(source)` 并返回 false，不置 satisfied。
- **跨午夜区分 pending**（:65-71）：`today != launchDay` 时，非 pending 源返回 false（常驻跨午夜不得新生请求）；pending 源若仍在退避则 false 且保留 pending；退避结束则返回 true 允许补做一次。
- **实际发起清 pending**（`markAutoAttempted`，:94-101）：置 `dailySatisfied` 并 `dailyPending.remove`。
- **今日成功分支清 pending**（:72-77）：置 satisfied 同时 remove pending，完成判定无残留。

逐帧回放上一轮 P1 场景：23:58 冷启动、旧数据、lastAttempt 23:55 → 首次 `shouldRunAuto` 命中 :79 记 pending 返回 false；00:03 跨午夜 → :65 进入、pending 存在、距 23:55 已 8 分钟（>5min）→ :70 返回 true 补做；`markAutoAttempted` 清 pending 置 satisfied；再跨午夜 → :63 satisfied 直接 false。**原缺陷（跨午夜直接吞掉补做）已消除，且不改变“常驻跨午夜不新生请求”的产品语义。**

测试 `t19_pendingDeferredAcrossMidnightCatchesUpThenNoRefresh`（`Round3LogicTest.kt:289-316`）直接驱动生产 coordinator + 可控时钟，断言序列与上述回放一致；`t19_nonPendingSourceAcrossMidnightDoesNotStartNewDaily`（:319-333）守住非 pending 跨午夜不新生请求。缺陷实现（无 pending 集合）会在前者第二次断言失败——反例有效性成立。本次独立强制重跑，79 项全通过且这两项均实际执行。

### 2.3 原三 DAILY 分支不退化

`t01_todaySuccessStartupSkipsAndNoMidnightRefresh`、`t01_oldSuccessStartupBackoffThenCatchUp`、`t01_catchUpDoneThenNoMidnightRefresh` 与 `dailyDoesNotRefreshAgainWhenResidentAcrossMidnight` 均通过；源码上 `dailySatisfied`(:63)、`lastDay==today`(:72-77)、退避不置 satisfied(:79-82) 三条路径未被 t19 改变（仅在同一退避分支增加了 pending 记录，不改变返回值）。STARTUP_6H(:85-88) 与 MANUAL(:55/:60) 分支未动。

### 2.4 T02 / S/R 无相关回退

文件时间戳确认 t19 未触碰 T02 与 S/R 代码；上一轮 [第四轮复验](UI_UX_VERIFICATION_ROUND4.md) §3/§4 的源码结论继续有效：T02 逐子项禁用、完整 Down/Up 消费、一次提交/重复忽略/Back 取消、确认组焦点封闭与滚动返焦均保持；S01 `PlaybackAttemptState`、S02 `ProbeCoordinator`/`AttemptEventRouter`/`MediaToken`、S03 STARTED 校验、S05 `loadStructuredCore`、S06 inFlight、R03 `SourceRepository` Mutex+持久版本+单 Editor、`PlaylistCache` AtomicFile 均保持。无相关回退。

## 3. 逐 A01–A14 最终判定（原始 ID / 标题 / 严重度）

“未验证”表示无设备/视觉证据，不是失败；“部分通过（源码+自动化）”仅指源码与 JVM 逻辑层，不代表原用例设备判定通过。所有源码层缺陷（含上一轮唯一 P1）已清零。

| A（原始 ID / 标题 / 严重度） | 最终判定 | 依据与边界 |
|---|---|---|
| A01 首次配置 / P0 | 未验证 | 配置页/二维码/LAN/双地址/首帧/错误可操作需设备；无设备未跑 |
| A02 手机扫码推源 / P1 | 未验证 | 真实扫码与 LAN 推送需手机+TV 互通；无设备 |
| A03 播放与启动恢复 / P0 | 未验证 | 60 秒持续播放、重启恢复/关闭/重开需设备；源码路由已读不作运行通过 |
| A04 连续数字跳台 / P0 | 未验证 | 数字缓冲 112/OK/1.2s/Back 与映射一致需遥控器；逻辑单测通过但遥控器/视觉未验证 |
| A05 频道浏览与分组 / P0 | 未验证 | 侧栏宽度、焦点不改播放、当前标记与焦点并存需设备/视觉 |
| A06 节目单层级与焦点恢复 / P1 | 未验证 | 历史节目 OK/确认/取消返焦、逐层 Back 需遥控器；S04 源码修复待设备复验 |
| A07 EPG 与信息条 / P1 | 部分通过（源码+自动化） | Now/Next/进度、缺数据占位、刷新失败与无节目区分的源码与 JVM 逻辑已确认；真实 EPG 数据/时间需设备 |
| A08 回看 / P1 | 未验证 | catch-up 能力生成、seek、Back 回直播需设备；源码接线已读未运行 |
| A09 独立设置与持久化 / P1 | 部分通过（源码+自动化，无剩余源码缺陷） | T02 模态边界与 T01/T19 跨午夜 pending 补做均已源码修复并经 JVM 可控时钟/状态机测试通过；上一轮唯一 P1 已清零。各设置项重启后生效仍属设备用例，未验证 |
| A10 播放失败与恢复 / P0 | 部分通过（源码+自动化） | S01/S02/S03 结构修复与单测确认；真实 ExoPlayer 错误注入、503 归因、遥控器可达需设备 |
| A11 空列表、坏源与断网缓存 / P0 | 部分通过（源码+自动化） | 空/坏列表状态、零频道不进空白播放器、断网缓存同源回退的源码+JVM 逻辑已确认；坏地址/断网冷启动需设备 |
| A12 遥控器焦点与返回优先级 / P0 | 源码通过，设备未验证 | T02 模态焦点边界已按源码完整修复；真实 D-pad/连续 Back/快速重复输入无运行环境，未验证；无源码缺陷 |
| A13 可访问性与可读性 / P1 | 未验证 | 可见焦点、状态非仅颜色、≥16sp、远距可读、透明台标比例需设备视觉 |
| A14 构建与自动化质量 / P0 | **通过（独立判定）** | 本轮独立执行 assemble/test/lint：强制重跑单测 79/0/0/0，lint 报告 0 Error/69 Warning/6 Hint，均记录命令、退出码、报告路径与 APK SHA；daemon 崩溃事件已标注 |

## 4. 终态与交付结论

- **源码/自动化检查：通过。** 全部已知源码缺陷清零（含上一轮唯一 P1 —— DAILY 跨午夜 pending 补做）；t19 最小 diff 经独立复验确认修复正确、原三分支不退化、T02/S/R 无相关回退；构建/79 单测/lint 报告全过；A14 通过。
- **完整产品验收：待设备。** 无设备（adb 无连接、无 AVD），A01–A06、A08 的首帧/遥控/扫码/回看/视觉，A07/A10/A11 的真实数据与 ExoPlayer 时序，A12 的真实 D-pad/Back，A13 的远距可读与台标，均为未验证，不计失败、不伪造缺陷。
- **剩余待设备用例清单**：首次配置首帧与错误卡（A01）；扫码推源（A02）；60 秒持续播放与三种启动恢复重启（A03）；112 数字跳台 OK/1.2s/Back（A04）；侧栏宽度与焦点/播放标记并存（A05）；节目分层确认/取消与逐层 Back 返焦（A06）；真实 EPG 数据与缺数据占位（A07）；catch-up seek/Back 回直播（A08）；各设置项重启生效与冲突拒绝（A09）；503/替代线路/超时终态（A10）；坏 URL/空列表/断网缓存冷启动（A11）；真实 D-pad 连续 Back/快速重复（A12）；远距可读/字号/台标比例（A13）；API24 真机兼容。
- **最终产物**：源码快照为 t19 修复后工作区；APK `app/build/outputs/apk/debug/app-debug.apk`（24,427,088 bytes，SHA256 `8AD8788E327C13D0961EFFB1B40754E047F0D370D5320491A2EA68AA217ABC3C`）；报告 `app/build/reports/lint-results-debug.xml` 与 `app/build/test-results/testDebugUnitTest/*.xml`。
- 本任务到此**停止读源码与运行命令**；未修改任何 Kotlin/Gradle/生产代码，仅新增本报告。无剩余源码缺陷需修复；后续任何工作仅限设备环境具备时补做上述未验证用例。
