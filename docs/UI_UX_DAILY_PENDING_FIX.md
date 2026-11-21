# DAILY 跨午夜 pending 补做修复（t19）

日期：2026-11-18。执行者：developer。对象：修复 [第四轮复验 §2.2](UI_UX_VERIFICATION_ROUND4.md) 唯一 P1（A09 EPG 刷新时机：启动被退避暂缓后跨午夜丢失补做资格）。仅改 `EpgRefreshCoordinator`、相关测试，新增本文件；不重开已通过 T02/S/R，不改产品语义。完成不代表产品设备验收。

## 1. 根因

t16 的 `shouldRunAuto(DAILY)` 中：退避暂缓只返回 false 而不记录“待补做”；`today != launchDay`（常驻跨午夜）分支无条件返回 false。于是 23:58 冷启动被 5 分钟退避暂缓 → 00:03 退避结束但跨午夜分支直接拒绝 → 该次启动的补做资格永久丢失，直到下次重启重新登记 launchDay。

## 2. 修复（不改产品语义）

`EpgRefreshCoordinator` 新增 `dailyPending: Set<source>`：
- **退避暂缓时显式记录 pending**：同启动日 `lastDay != today` 且命中 5 分钟退避 → `dailyPending.add(source)` 并返回 false（不置 satisfied）。
- **跨午夜分支区分 pending**：`today != launchDay` 时，非 pending 源仍返回 false（常驻跨午夜不得新生 DAILY 请求）；pending 源若退避已结束 → 返回 true 允许补做一次；若仍退避则 false 且保留 pending。
- **实际发起后清 pending**：`markAutoAttempted(DAILY)` 置 `dailySatisfied` 并 `dailyPending.remove`。
- 今日已有成功记录分支同时 `dailyPending.remove`（完成判定，无残留）。
- `resetForTest` 清空 pending。

生产接线未改动：`PlayerActivity` 启动/周期与 `performEpgRefresh` 已复用 `shouldRunAuto` + `markAutoAttempted`，pending 语义在既有入口生效，无需新增调用点。

## 3. 测试（真实 coordinator + 可控时钟）

新增 `Round3LogicTest`：
- `t19_pendingDeferredAcrossMidnightCatchesUpThenNoRefresh`：23:58（lastAttempt=23:55，退避中）→ shouldRun=false 且记录 pending；次日 00:03（退避已结束）→ shouldRun=true 补做；`markAutoAttempted` 清 pending；再跨午夜 → false。
- `t19_nonPendingSourceAcrossMidnightDoesNotStartNewDaily`：今日已有成功（非 pending）→ 启动跳过 → 跨午夜不新生请求。
- 原 T01 三条仍通过：`t01_todaySuccessStartupSkipsAndNoMidnightRefresh`、`t01_oldSuccessStartupBackoffThenCatchUp`、`t01_catchUpDoneThenNoMidnightRefresh`；`dailyDoesNotRefreshAgainWhenResidentAcrossMidnight` 亦通过。
- 缺陷实现（无 pending 集合）在 `t19_pendingDeferredAcrossMidnightCatchesUpThenNoRefresh` 第二次断言会失败。

## 4. 命令证据（本工作区，本次执行）

| 命令 | 退出码 | 结果 |
|---|---|---|
| `./gradlew.bat :app:assembleDebug` | 0 | Debug APK 成功 |
| `./gradlew.bat :app:testDebugUnitTest` | 0 | **79 项**全通过（Round3LogicTest 增至 22） |
| `./gradlew.bat :app:lintDebug` | 0 | **0 Error / 69 Warning / 6 Hint** |

- APK：`app/build/outputs/apk/debug/app-debug.apk`，24,427,088 bytes，SHA256 `8AD8788E327C13D0961EFFB1B40754E047F0D370D5320491A2EA68AA217ABC3C`。
- 环境：JDK17.0.21 / Gradle 8.13 / AGP 8.13.2 / Kotlin 2.0.21 / minSdk24 / targetSdk36。

## 5. 未验证 / 边界

- 仅源码 + JVM 可控时钟测试；真实跨午夜设备/长时间驻留未运行。
- 其余设备项（首帧/60s、断网缓存、扫码、回看、遥控器焦点、视觉、API24 真机）仍无设备，未验证。
- 未改 A 编号；A04/A08/A10/A14 语义不变。
