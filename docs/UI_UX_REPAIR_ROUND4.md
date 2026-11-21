# Android TV IPTV 第四轮修复说明（t16）

日期：2026-11-18。执行者：developer。对象：修复 [第三轮设计复查 T01/T02](UI_UX_DESIGN_REVIEW_ROUND3.md) 与 [复验报告](UI_UX_VERIFICATION_ROUND3.md)。保留已确认 S01/S02/S03/S05 与 R01–R11，不重写。本文为实施记录，不是验收通过证明；设备/遥控器/视觉仍待 t17 独立复验。

验收 A 编号未改名重排：A04 数字跳台、A08 回看、A10 播放失败恢复、A14 构建质量。

## 1. 命令证据（本工作区，本次执行）

| 命令 | 退出码 | 结果 |
|---|---|---|
| `./gradlew.bat :app:assembleDebug` | 0 | Debug APK 成功 |
| `./gradlew.bat :app:testDebugUnitTest` | 0 | **77 项**全通过（Round3LogicTest 增至 20） |
| `./gradlew.bat :app:lintDebug` | 0 | **0 Error / 69 Warning / 6 Hint** |

- APK：`app/build/outputs/apk/debug/app-debug.apk`，24,427,137 bytes，SHA256 `BAA9D1E897F085CB87B3A9A3F53BCBF782AF6BBF0DD756508B1D4E681E486AE1`。
- 环境：JDK17.0.21 / Gradle 8.13 / AGP 8.13.2 / Kotlin 2.0.21 / minSdk24 / targetSdk36；core library desugaring 生效。

## 2. T01 修复：DAILY 启动判定与实际请求分离

**根因**：`EpgRefreshCoordinator` 仅用 `dailySeeded && lastDay!=today` 判断；启动时今日已有成功记录 → `shouldRunAuto=false` 且不调用 `markAutoAttempted` → `dailySeeded` 不含源；常驻跨午夜后 `lastDay!=today` 变 true 触发刷新。

**修复**（`EpgRefreshCoordinator`）：
- 新增 `dailyLaunchDay: Map<source, epochDay>`：本进程该源首次 DAILY 判定日即“启动基准日”。
- 新增 `dailySatisfied: Set<source>`：启动判定已满足（今日已有成功或已实际尝试）。
- `shouldRunAuto(DAILY)`：
  1. `dailySatisfied` 含源 → false；
  2. 记录/读取 `dailyLaunchDay`；若 `today != launchDay` → **false（常驻跨午夜不刷新）**；
  3. 若 `lastDay == today`（启动今日已有成功）→ 标记 `dailySatisfied` 并 **false（完成该次启动判定）**；
  4. 需要刷新但命中 5 分钟退避 → false 且 **不置 satisfied（保留补做资格）**；
  5. 否则 true。
- `markAutoAttempted(DAILY)` 置 `dailySatisfied`（实际尝试后不再按日期触发）。
- 生产入口核实：`PlayerActivity` 启动 `LaunchedEffect` 无条件（非 MANUAL）调用 `shouldRunAuto` 完成启动登记；周期循环复用同一函数；`performEpgRefresh` 统一 `tryBegin/markAutoAttempted/end`。

**可控时钟测试**（Round3LogicTest，直接驱动生产 coordinator）：
- `t01_todaySuccessStartupSkipsAndNoMidnightRefresh`：今日已有成功 → 启动跳过 → 跨午夜不刷。
- `t01_oldSuccessStartupBackoffThenCatchUp`：旧成功 + 退避 → 启动暂缓 → 退避后补做。
- `t01_catchUpDoneThenNoMidnightRefresh`：补做完成 → 跨午夜不刷。
- 原 `dailyDoesNotRefreshAgainWhenResidentAcrossMidnight` 保持通过。**缺陷实现（仅 dailySeeded）在第 1/3 条会失败。**

## 3. T02 修复：设置捕获/恢复默认的模态焦点与事件边界

**根因**：捕获/确认弹层与 RemotePage 同处组合树，底层仍可聚焦可点击；CaptureOverlay 仅在 KeyUp 捕获、KeyDown 返回 false，方向参与空间焦点搜索。

**修复**（`SettingsDrawer.kt` + 新 `CaptureKeyState.kt`）：
- **逐子项禁用背景**：`RemotePage(enabled = capturing == null && !confirmReset)`，把 `enabled` 传到每个 `SettingsRow`（`focusable(enabled)`/`clickable(enabled)`），捕获/确认期间所有背景动作行与“恢复默认映射”行均不可聚焦、不可点击——不是只父容器 `canFocus=false`。
- **捕获完整 Down/Up**：新 `CaptureKeyState` 纯状态机；`CaptureOverlay` 的 `onPreviewKeyEvent` **消费所有 Down/Up**（方向键不再触发背景焦点搜索），只在一次完整“Down→Up 同键”提交一次；重复 Down 不重复；Back 取消。
- **确认按钮组焦点封闭**：确认弹层仅在背景全禁用时显示，两按钮唯一可聚焦；`LaunchedEffect(confirmReset)` 用 `withFrameNanos` 等布局后请求初始焦点（`confirmFocus`）。
- **取消返焦（滚动后）**：`remoteFocusNonce` 在捕获/确认取消时自增；`RemotePage` 的 `LaunchedEffect(focusIndex, resetRowIndex, focusNonce)` 先 `scrollToItem(目标)` 再 `requestFocus` 到原动作行或 `resetRequester`（reset 稳定 ID）。
- 避免同一节点挂两个 FocusRequester：动作行仅 `index==focusIndex && index<resetRowIndex` 用 `rowRequester`，reset 行专用 `resetRequester`。

**测试**：
- `CaptureKeyState` JVM 测试 3 项：一次 Down/Up 仅提交一次、重复 Down 不重复、方向 Down/Up 被消费、Back 取消、错配 Up 不提交（`t02_*`）。
- Compose 焦点/滚动与真实遥控事件：**本机无 Android UI/Robolectric 运行环境，未运行**，明确标记未验证；源码隔离已完整（背景逐项 disabled + 覆层消费全键 + 滚动后返焦 + 帧后初焦）。

## 4. 已保留修复与边界（未回退）

- S01（PlaybackAttemptState）、S02（每 attempt 独立播放器 + 捕获 token + AttemptEventRouter）、S03（ON_STOP 失效意图/取消任务/STARTED + 稳定 key）、S05（forceRefresh 同源缓存候选/注入 fetcher 不触真网/IO 线程/loadStructuredCore）、S06 inFlight 与退避补做、R03（持久 version + 单 Editor 源元组）均保持。
- 准确表述：version 与源元组仍是两个 Editor 提交，文件与 Prefs 非跨资源事务；不声称崩溃原子或跨进程并发已证明。

## 5. Lint 分类

0 Error；69 Warning 主要为既有 UseKtx/GradleDependency/UnusedResources/NewerVersionAvailable/DiscouragedApi/IconLocation/MonochromeLauncherIcon/CustomSplashScreen/AndroidGradlePluginVersion 及本轮少量 ModifierParameter/AutoboxingStateCreation（Hint）。未禁用或普遍抑制。

## 6. 未验证 / 待设备项

- 遥控器注入：设置捕获/确认的焦点封闭、非首项取消返焦、四向/长按/Back、背景不被修改——无 Android UI 运行环境，未验证。
- 真实首帧/60 秒、断网缓存、扫码推送、回看 seek/ENDED、720p/1080p 焦点与远距视觉、透明台标、API24 真机启动——无设备，未验证。
- S02 真实 ExoPlayer 排队回调时序、S03 真实 Lifecycle 交错、S05 真实文件/网络仍靠源码结构，未运行。
