# F3 收口复查（EPG 单路径 / 失败 / 分组 / 启动延后）— t176 / run10114

任务 t180（attempt 1），designer2，2026-11-21（Asia/Hong_Kong）。**结论：needs_revision。** t176 已删除 PlayerActivity 的 EPG 下载循环、scheduler 加单 mutex、失败统一、基础分组前移、启动延迟/重置、resource-id、recentCount 同帧 0 方向正确；但 **手动 EPG 仍然重复触发两次**（`onRefreshEpgNow` 既直接 `refreshNow`，又置 `shouldRefreshEpg` 让 2s 泵再发一次），**源配置保存后的 `MainActivity` 仍直接 `refreshAll` 绕过 scheduler**，且 **音频对话框的 Activity Back 问题没有因 testTags 修复**。这三项直接命中本轮验收。

> 只读固定 commit `f357dd8d`；HEAD `2284b3f`（docs-only）。工作树有 t179 的 `AudioTrackUi.kt` 修改/`TracksFlattening.kt` 未跟踪（未读取/未评价）。未改代码、未跑 Gradle/CI/ADB、未操作 TV。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`；被审查 commit `f357dd8d00ad3abd5f76f568e24ccd18922e3fca`。
- `git show --stat`：7 files +183/−65：`PlayerActivity.kt`、`AutoUpdateScheduler.kt`、`AutoUpdateConfig.kt`、`SettingsDrawer.kt`、`RecentChannelsDialog.kt`、`AudioTrackDialog.kt`、`AutoUpdateSchedulerPolicyTest.kt`；docs `AUTO_UPDATE_SETTINGS2.md` 2284b3f。
- pair：主 `13,043,607B` SHA `55ac7ef7…068e9`、test `502,955B` `ab35db0f…a9c95`、run 44828902605（run10114）、10114；设备未验证。
- 关键行：`PlayerActivity` EPG 缓存/收集 L824–853、`onRefreshEpgNow` L1358–1367、`onSourceConfigClick` L1276–1280；`AutoUpdateScheduler` start L46–61/refreshNow L64–82/reset L88–95/runDue L100–145；`AutoUpdateConfig` afterSuccess/Failure L84–96；`SettingsDrawer` 基础 L133–180；`MainActivity` scheduleRefresh（save path）。

## 2. 已通过/正确部分

### EPG-DUAL 主路径 — 部分通过
- `PlayerActivity` L824–853 **已删除** `EpgRepository.loadStructured` 下载/解析；只保留缓存加载、`epgUpdated.collect` 和 2s 手动请求泵 → `AutoUpdateScheduler.refreshNow`。
- `AutoUpdateScheduler.runDue` auto 与 `refreshNow` manual 共用 `mutex`；auto 走 `BackgroundSourceUpdater.refreshEpg`，手动也不直接碰 `EpgRepository`。
- 自动 due 条件只剩 `AutoUpdateConfig.shouldRefresh`（L106–121），已去掉 `isDifferentDay/isOver6Hours` 二次否决。
- **但手动入口仍 duplicate**，见 §3 EPG-MANUAL-01。

### EPG-FAILURE — 部分通过
- `AutoUpdateScheduler.onEpgResult` L152–160：成功 `setLastEpgUpdateTime + clearEpgFailure`，失败 `recordEpgFailure`；`AutoUpdateConfig.afterSuccess/afterFailure` 纯函数（测试）。
- **PlayerActivity 不再写 EPG 成功/失败**，避免旧的“成功不清 failure”问题。
- **但 `MainActivity` 保存源仍走 `BackgroundSourceUpdater.refreshAll`**，EPG 成功不 clear failure，见 §3 EPG-SOURCE-SAVE-01。

### 设置分组 — pass
- `SettingsGroupHeader("基础")` 已移到源配置前（L133–135），源配置/更新EPG/源选择/检查更新/最近观看归入；顺序：基础 → 音频与字幕 → 数据与更新 → 台标与缓存。
- header 仍为非 focusable Text、0dp、无背景/蓝框；默认焦点最近观看（`recentRequester`）；Back/Left 根关闭；TIV 源配置/EPG/检查更新路径保留。
- 最近观看行恢复在基础组内；`recentCount` 改用 `recentEntries.size`（L1263），清空同帧 0（测试 `clearingTheRecentList...`）。

### 启动/重置/文档 — 部分通过
- `STARTUP_DELAY_MS = 1_500L`，`start` 在 IO 上 delay 后 `runDue(startup=true)`；`resetStartupFlag(context)` 在 IO 上 delay 1.5s 重跑 startup；`onSourceConfigClick` 调用 reset。
- `AutoUpdateSchedulerPolicyTest` 7 项：manual bypass 语义、afterSuccess/Failure、backoff、startup gate、startup delay 范围、OFF 不复活、recent clear；JVM 127→134。
- `WORKMANAGER-01` 文档已写明仅进程存活；`OFF` 由 `shouldRefresh` 先返回 false，reset 不会复活。
- **但 reset 在“打开源配置”时提前执行、MainActivity 保存后仍直接 refreshAll**，见 §3。

### 真机尾巴 — 部分通过
- `RecentChannelsDialog` 根 `.semantics { contentDescription; testTagsAsResourceId=true }`；`AudioTrackDialog` 根 `.semantics { testTagsAsResourceId=true }`；uiautomator 可取 resource-id。
- **Recent 对话框 Back** 已有 Activity `isRecentChannelsOpen` guard；**Audio 对话框没有**，见 §3 AUDIO-BACK-02。
- `recentCount` 同帧 0 pass。

### 无回归 / 体积 — pass
- diff 仅上述 7 文件；未改 LoadControl 2500、MP2 AAR、零拷贝 SurfaceView、0dp flat、频道键、数字 OSD、台标缓存、EPG 数据层、SR、品牌。
- APK 13,043,607B（≈12.44 MiB）≤30MB；keep210/contract0；check_player_threading 无新增后台 Player。

## 3. 阻断/需修复

### EPG-MANUAL-01（high）手动 EPG 仍重复两次

**证据**：
- `PlayerActivity` `onRefreshEpgNow` L1358–1367：
  ```kotlin
  Toast "正在刷新 EPG…"
  shouldRefreshEpg.value = true
  AutoUpdateScheduler.refreshNow(context, playlist=false, epg=true) // 第1次
  ```
- 2s 泵 L850–853：
  ```kotlin
  if (shouldRefreshEpg.value) {
      shouldRefreshEpg.value = false
      AutoUpdateScheduler.refreshNow(context, playlist=false, epg=true) // 第2次
  }
  ```
  第一次设置 flag 后，泵在下一次 2s tick 仍会再发一次；`mutex` 只是串行，**不会去重**。结果：两次 EPG 网络/解析、两个 `_events` Toast（“EPG已更新”两次）、last update 前进两次；与 t176 验收“只一次请求/一个 Toast”直接冲突。
- `onEpgSettingsClick` L1284 只置 flag（1次），没有直调；`onRefreshEpgNow` 应二选一。

**修复**：
- `onRefreshEpgNow` 只走 2s 泵（设置 flag，不 direct refreshNow），或只直调（不设 flag）；`onEpgSettingsClick` 保持泵。
- 更稳妥：把“立即刷新”统一成一个 `requestManualEpg()`，用 `Channel`/`StateFlow` 合并请求，2s 泵只消费一次；scheduler 内对同类型请求做 in-flight 去重（当前 mutex 串行但不去重）。

### EPG-SOURCE-SAVE-01（high/medium）MainActivity 保存源仍直接 `refreshAll`，绕过 scheduler/单路径且不回写成功状态

**证据**：
- `MainActivity` 保存路径仍 `BackgroundSourceUpdater.scheduleRefresh(context, delayMs=3000L)`（t169 前逻辑），`BackgroundSourceUpdater.refreshAll` 直接 `refreshPlaylist + refreshEpg`。
- 这绕过 `AutoUpdateScheduler`：
  - 与 scheduler 的 startup/manual/auto pass 并发（scheduler mutex 不覆盖它），EPG 可重复；
  - EPG 成功只 `Prefs.setLastEpgUpdateTime`，**不 clearEpgFailure/failCount**；playlist 成功不 `setPlaylistLastSuccess`；scheduler 后续按旧 failure/lastSuccess 再重试，形成额外请求；
  - 旧源 reset 在 `onSourceConfigClick` 已提前触发，用户取消/改地址时可能刷新旧源。
**修复**：
- 保存源后不要直接 `scheduleRefresh`；改为 `AutoUpdateScheduler.resetStartupFlag(context)` 后 `AutoUpdateScheduler.refreshNow(playlist=true, epg=true)`（或新增 `onSourceChanged()` 统一写 last success/failure、清 failure）。所有 EPG 请求只经 scheduler。
- 将 `resetStartupFlag` 从“打开源配置”移到“保存成功后”，避免旧源/取消场景的 1.5s 误刷新。
- 若需要立即刷新源，使用 scheduler 的 manual 入口，保持单 mutex/去重。

### AUDIO-BACK-02（high，t177 遗留）testTags 只让 resource-id 可断言，没有修 Back 行为

**证据**：
- `AudioTrackDialog` 根新增 `testTagsAsResourceId`，但 `PlayerActivity` **仍无** `isAudioDialogOpen`/controller；`audioDialogOpen` 仍是 Compose 局部（L438）。
- `onAudioTrackClick` 打开音频对话框时 **SettingsDrawer 仍开着**；Activity BACK 先判 `isSettingsDrawerOpen` 并关设置，音频对话框仍在；设置关后数字/D-pad/OK/MENU/BACK 无 audio guard，第二次 Back 可双退。`AudioTrackDialog` 的 `onPreviewKeyEvent` 收不到。
- t176 只加了 uiautomator 资源 id；验收“Back 可断言”能拿到 `audio_dialog`，但按 Back 不会只关它。
**修复**：同 t177 AUDIO-BACK-01：Activity 增加 `isAudioDialogOpen` + controller/callback；所有 guard 加 `!isAudioDialogOpen`；BACK 最前关闭 audio；打开音频时关闭 SettingsDrawer；Compose/Activity 测试断言。

### STARTUP-RESET-02（medium）reset 在打开源配置时提前执行，旧源/取消也刷新

**证据**：`onSourceConfigClick` L1276–1280 立即 `AutoUpdateScheduler.resetStartupFlag(context)`；1.5s 后 `runDue(startup=true)` 用 **当前 Prefs 旧源** 刷新；用户此时还在输入新地址或可能取消。保存后 MainActivity 又 direct refresh，形成旧源+新源两次。
**修复**：移到 `MainActivity` 保存成功、`Prefs.setSourceConfig` 之后调用；或 `resetStartupFlag` 只在 source version 变化时执行；取消/返回不触发。

### TEST-01（medium）policy 测试多为自证/纯常量，未覆盖真实单路径

**证据**：`AutoUpdateSchedulerPolicyTest`：
- `manualEpgRefreshBypassesIntervalAndBackoff` 用 `val manual = true; assertTrue` 自证，未调用 scheduler；
- `startupDelayLeavesRoomForTheFirstFrame` 只断言常量 1000–3000，无 first-frame 信号；
- `offIsNotRevivedByTheStartupReset` 只测 `shouldRefresh(OFF)`，未调用 `resetStartupFlag`；
- 无 `AutoUpdateScheduler` 启动/refreshNow 去重、MainActivity source save、EPG 请求只一次、Audio Back 的测试。
**修复**：抽 scheduler `clock/executor` 可注入测试；用 fake source/BackgroundSourceUpdater 计数请求；补 reset 后 OFF/STARTUP、source save、manual pump 去重、Audio dialog Back/Activity guard 测试。

### PERF-01（low）1.5s 是固定延迟，不是真正首帧
- `start` delay 1500ms 后 runDue；`PlayerActivity` 首帧可能早于/晚于 1.5s；文档说“首帧后”，实现未监听首帧。建议用 `firstFrame` 信号/播放器 `onRenderedFirstFrame` 后再启动（或至少 3s 上限），避免弱机首帧 >1.5s 时仍竞争。

### UI-01（low）resource-id 仅根节点
- 两对话框根设置 `testTagsAsResourceId=true`，子行 testTag 可暴露；但 `SettingsDrawer`/Audio dialog 根 audio testTag 与 Activity guard 无关。建议 uiautomator 断言 `audio_dialog` 后按 Back，断言消失且播放未退出；resource-id 本身 pass。

## 4. 真机/视觉未验证

- 手动 EPG 只一次请求/一个 Toast/last update 只前进一次；短间隔 auto+manual 单执行；失败后成功 fail_count=0、下轮按间隔；设置顺序 基础→音频→数据→台标；uiautomator recent/audio resource-id；Back 只关列表/音频；清空 N=0——均归 acceptance2。本报告不宣称设备通过。

## 5. 放行判断

- **源码结论：needs_revision。** EPG 自动下载循环已删除、scheduler mutex/失败统一/分组/启动延后/测试方向正确；但 **手动 EPG 仍两次（EPG-MANUAL-01）**、**源保存仍绕过 scheduler（EPG-SOURCE-SAVE-01）**、**音频对话框 Back 未修（AUDIO-BACK-02）** 是明确缺陷。
- 建议顺序：**EPG-MANUAL-01 → EPG-SOURCE-SAVE-01 → AUDIO-BACK-02 → STARTUP-RESET-02 → TEST-01/PERF**；重出 r27 pair 后 t182/t178 真机验证。

## 6. 只读命令与终态

`git rev-parse HEAD`、`git status --short`、`git show --stat f357dd8d`、`git show f357dd8d`、`read PlayerActivity.kt/AutoUpdateScheduler.kt/AutoUpdateConfig.kt/MainActivity.kt/SettingsDrawer.kt/RecentChannelsDialog.kt/AudioTrackDialog.kt/AutoUpdateSchedulerPolicyTest.kt`、`Get-Content docs/AUTO_UPDATE_SETTINGS2.md`。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 工作树 t179 并发修改未读取/未评价；本报告完成后停止读取目标源码。
