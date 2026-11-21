# EPG 手动单路径 / 保存源 / 音频 Back 复查（t182 / run10116）

任务 t183（attempt 1），designer2，2026-11-21（Asia/Hong_Kong）。**结论：needs_revision。** t182 已把手动 EPG 收敛为“只置 flag、泵消费一次”，`MainActivity` 保存源改调 `AutoUpdateScheduler.onSourceChanged`，`onSourceConfigClick` 不再 reset；`onPlaylistResult`/`onEpgResult` 统一写 last-success/清 failure，AUDIO-BACK-02 逐行复核仍生效。但仍有三个明确问题：

1. **`ManualRefreshPolicy` 是死代码**：`actionFor/shouldRefreshAfterSourceScreen/usesSingleRefreshPath` **无任何生产调用**；测试只测这些纯函数，真实泵仍是 `if (shouldRefreshEpg.value) { false; refreshNow }`，没有“已消费”标记，若 flag 被置两次（设置页 + 手动行）仍可能重复；测试与实现脱节。
2. **`onSourceChanged` 与 `refreshNow` 的“单次”语义不严格**：`onSourceChanged` 在 `mutex` 内做 playlist+EPG 两次刷新后 `startupDone=true`、emit 一次；但 `startupDone=false` 在协程外提前设置，且 `start()` 的 60s tick/`runDue` 可能并发看到 `startupDone=false` 再跑一次 startup；`_events` 无 replay，MainActivity `finish()` 后新 PlayerActivity 订阅可能收不到“源已更新”。
3. **AUDIO-BACK-02 没有测试**：源码 guard 生效（t179），但 t182 声称“逐行复核”，测试集里仍无 Activity/Compose Back 断言；任务点 3 要求“有测试”。

> 只读固定 commit `1181aaf1`；HEAD `cf5be83`（docs-only）。工作树 clean（本任务相关）。未改代码、未跑 Gradle/CI/ADB、未操作 TV。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`；被审查 commit `1181aaf1e3dca4600011e84b69c783ddd04033ff`。
- `git show --stat`：5 files +142/−4：`ManualRefreshPolicy.kt`、`ManualRefreshPolicyTest.kt`、`AutoUpdateScheduler.kt`、`MainActivity.kt`、`PlayerActivity.kt`；docs `AUTO_UPDATE_SETTINGS3.md` cf5be83。
- pair：主 `13,043,603B` SHA `bb314323…de3ba`、test `502,955B` `0bc1466f…11b22`、run 44832360654（run10116）、10116；设备未验证。
- 关键行：`PlayerActivity` 泵 L849–856、`onRefreshEpgNow` L1373–1379、`onSourceConfigClick` L1281–1287、`onRefreshPlaylistNow` L1369–1373；`AutoUpdateScheduler` onSourceChanged L72–92、refreshNow L95–113、resetStartupFlag L119–126、runDue L131–176；`MainActivity` L208–212；`ManualRefreshPolicy` 全 36 行（无调用）。

## 2. 已通过/正确部分

### EPG-MANUAL-01（行为）— pass
- `PlayerActivity` `onRefreshEpgNow` L1373–1379 **只** `Toast + shouldRefreshEpg.value = true`，**删除了直调 `refreshNow`**。
- 泵 L849–856 每 2s 检查 `shouldRefreshEpg.value`，置 false 后调一次 `AutoUpdateScheduler.refreshNow(epg=true)`；因此用户点一次 → 泵一次 → 一个请求/一个 Toast（scheduler `_events` 只在 refreshNow 内 emit 一次）。
- `onEpgSettingsClick` L1284 只置 flag；两条入口都走泵，单路径成立。

### EPG-SOURCE-SAVE-01 — pass（路径）
- `MainActivity` L208–212：保存成功后 `AutoUpdateScheduler.onSourceChanged(context)`，**删除** `BackgroundSourceUpdater.scheduleRefresh`；不再 direct `refreshAll`。
- `onSourceChanged` L72–92：`mutex.withLock` 内 `refreshPlaylistUrgent` → `onPlaylistResult`（成功写 `setPlaylistLastSuccess`，含清 failure/count）→ `refreshEpg` → `onEpgResult`（成功 `setLastEpgUpdateTime` + `clearEpgFailure`）；最后 `_events.tryEmit("源已更新")`。
- `Prefs.setPlaylistLastSuccess` L150–156 同时清零 failure/count；`clearEpgFailure` L186–190 清零；`onPlaylistResult/onEpgResult` 是唯一写入点。
- 全仓 grep：`scheduleRefresh` 只剩 `BackgroundSourceUpdater` 定义，无调用者；`refreshAll` 无外部调用者；`resetStartupFlag` 无调用（见 §3）。

### STARTUP-RESET-02 — pass（位置）
- `PlayerActivity` `onSourceConfigClick` L1281–1287 **不再** `resetStartupFlag`，只 `startActivity(MainActivity)`；打开/取消不触发。
- reset 语义改由 `onSourceChanged` 内 `startupDone=false`（保存后）承担；`ManualRefreshPolicy.shouldRefreshAfterSourceScreen(saved)` 测试覆盖“仅保存”。
- 保存后无 direct 双重刷新：`MainActivity` 只调 `onSourceChanged`，scheduler 内 playlist/EPG 各一次。

### AUDIO-BACK-02 — 源码 pass / 测试缺
- `PlayerActivity` L113 `isAudioDialogOpen` + L185 controller；数字 L242、LAST L260、OK L267、LEFT L274、RIGHT L286、MENU L298、UP L354、DOWN L364 全部 `&& !isAudioDialogOpen`；**BACK L305 首判 audio → setter(false)+return true**；`onAudioTrackClick` 先 `settingsDrawerOpen=false`。
- 结论：行为仍生效（t179 未被 t182 破坏）；**但没有 Activity/Compose 测试**（§3 TEST-01）。

### 不回归 / 体积 — pass
- diff 仅上述 5 文件；未改 LoadControl 2500、MP2 AAR、零拷贝/0dp flat、频道键、数字 OSD、台标、最近观看、EPG 数据层、SR、品牌。
- APK 13,043,603B≤30MB；keep210/contract0；`check_player_threading` 无新增后台 Player。

## 3. 需修复

### MANUAL-POLICY-01（medium）`ManualRefreshPolicy` 未被生产代码使用，测试与实现脱节

**证据**：
- `ManualRefreshPolicy.kt` 只有定义；全仓 grep `ManualRefreshPolicy` 命中仅 **定义文件 + 测试**，`PlayerActivity`/`AutoUpdateScheduler`/`MainActivity` 均未引用。
- 泵 L849–856 仍是手写 `if (shouldRefreshEpg.value) { shouldRefreshEpg.value = false; refreshNow(...) }`；没有 `pumpAlreadyConsumed` 状态。`actionFor` 的“已消费→NONE”只存在于测试。
- 若未来 `onEpgSettingsClick`/`onRefreshEpgNow` 在同一 2s 窗口各置一次 flag（Boolean 会合并为一次，但若改成计数/事件就会重复），或有人再直调 `refreshNow`，测试不会失败。
- `usesSingleRefreshPath(schedulerUsed, legacyRefreshAllUsed)` 也是自证布尔，未检查真实调用图。

**修复**：
- 让生产代码真正使用 policy：泵用 `ManualRefreshPolicy.actionFor(flag, consumed)` 决定；或引入 `manualEpgRequests = MutableSharedFlow<Unit>(extraBufferCapacity=1)` + `tryEmit`，泵 `collect` 一次（天然去重/合并）。
- 若保留纯函数，至少把 `onRefreshEpgNow`/`onEpgSettingsClick`/泵三处都改为调用同一 `requestManualEpg()`；测试改为调用该函数（可注入 fake scheduler）断言一次。
- `usesSingleRefreshPath` 删除或改为源码扫描断言（grep 无 `scheduleRefresh(`/`refreshAll(` 调用）。

### SOURCE-SAVE-02（medium）`onSourceChanged` 的 startup gate 与 tick 竞态、事件不送达

**证据**：
- `onSourceChanged` L74 `startupDone = false` **在 `scope.launch` 外**立即执行；随后 `mutex` 内做两次网络（可能数秒）。此时 `start()` 的 60s `runDue(startup=false)` 若在 `startupDone=false` 后、`mutex` 释放前到达，会看到 `firstPass = startup && !startupDone`（startup=false → false，OK），但 `runDue` 的 `shouldRefresh` 仍可能因 lastSuccess/lastFailure 判定 due，与 onSourceChanged 的刷新重复（mutex 串行但不去重）。
- 若 `runDue(startup=true)`（reset/start）与 onSourceChanged 并发，`startupDone` 被两处写 false/true，顺序不定。
- `_events = MutableSharedFlow(extraBufferCapacity=4)` **无 replay**；`MainActivity` 保存后立即 `finish()` 并 `startActivity(PlayerActivity)`；新 PlayerActivity 的 `LaunchedEffect(Unit)` 订阅 `events` 可能晚于 `tryEmit("源已更新")`，Toast 丢失（验收点②“一个源已更新 Toast”不稳）。

**修复**：
- 在 `mutex` 内设置 `startupDone=false` 并在刷新完成后置 true；或用一个 `refreshInFlight` 标记让 `runDue` 跳过（mutex + 去重）。
- `_events` 改 `replay = 1`（或 `extraBufferCapacity` + `StateFlow<String?>` 让新订阅者读最后一条），确保保存后新 Activity 能收到“源已更新”。
- 加测试：fake source 记录调用次数，并发 `onSourceChanged` + `runDue` 断言 playlist/EPG 各一次。

### TEST-01（medium）AUDIO-BACK-02 仍无 Activity/Compose 测试

**证据**：`ManualRefreshPolicyTest` 4 项只测纯 policy/afterSuccess；没有：
- Activity `isAudioDialogOpen` guard/Back 只关音频（t182 只“逐行复核”）；
- `onSourceChanged` 单次刷新/Toast/失败清零；
- 泵手动 EPG 一次（测试只测 `actionFor`，未调用真实泵）；
- `MainActivity` 保存后无 `scheduleRefresh`（源码扫描）。
JVM 141 全绿不能证明验收点①–④。

**修复**：`createAndroidComposeRule`/ActivityScenario 测 audio Back/数字穿透；fake `BackgroundSourceUpdater` 计数测 onSourceChanged/manual pump；CI 加 `grep -L` 源码守卫（无 scheduleRefresh/refreshAll 调用）。

### SOURCE-EVENT-01（low）`onSourceChanged` 成功/失败只 emit “源已更新”
- 即使 playlist 或 EPG 失败，L90 仍 emit “源已更新”，用户看到成功假象；`onPlaylistResult/onEpgResult` 已记录失败但 Toast 不区分。
- 建议：emit `源已更新（播放列表成功/失败，EPG 成功/失败）` 或按结果分别 emit；失败时仍保留失败状态。

### PERF-01（low，遗留）1500ms 固定延迟非真正首帧
- t182 文档已记录未改；`start`/`resetStartupFlag` 仍 `delay(STARTUP_DELAY_MS)`，非首帧信号。建议后续用 `onRenderedFirstFrame`。

### RESET-01（low）`resetStartupFlag` 变成死代码
- 全仓无调用（onSourceConfigClick 已移除，onSourceChanged 直接写 `startupDone`）；保留但未用，易误解。建议删除或在 onSourceChanged 内复用。

## 4. 真机/视觉未验证

- “立即刷新 EPG”一次请求/一个 Toast/last update 一次；保存源后一次刷新两项/一个 Toast/playlist lastSuccess；取消配置不触发；音频框 Back 只关自身/数字方向不穿透——均归 acceptance2。本报告不宣称设备通过。

## 5. 放行判断

- **源码结论：needs_revision。** EPG 手动单路径/保存源/STARTUP-RESET 位置/AUDIO-BACK 行为已修；但 **ManualRefreshPolicy 死代码 + 测试自证**、**onSourceChanged 竞态/事件丢失**、**AUDIO-BACK 无测试** 需补。
- 建议顺序：**MANUAL-POLICY-01 → SOURCE-SAVE-02（含 replay/去重）→ TEST-01 → SOURCE-EVENT/RESET 清理**；重出 r27 pair 后真机验收。

## 6. 只读命令与终态

`git rev-parse HEAD`、`git status --short`、`git show --stat 1181aaf1`、`git show 1181aaf1`、`read PlayerActivity.kt/AutoUpdateScheduler.kt/MainActivity.kt/ManualRefreshPolicy.kt/Prefs.kt/ManualRefreshPolicyTest.kt`、grep `ManualRefreshPolicy|scheduleRefresh|refreshAll|resetStartupFlag`。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 本报告完成后停止读取目标源码。
