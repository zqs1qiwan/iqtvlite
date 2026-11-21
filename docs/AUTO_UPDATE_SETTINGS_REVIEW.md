# 自动更新设置 / 设置分组 独立复查（t172 / run10112）

任务 t173（attempt 1），designer2，2026-11-21（Asia/Hong_Kong）。**结论：needs_revision。** 设置分组标题、默认焦点、Back、自动更新策略纯逻辑、退避、IptvApplication 接线、Prefs 键、手动 bypass 方向基本正确；但 **EPG 现在有两条自动刷新路径**（`AutoUpdateScheduler` 的 60s 策略循环 + `PlayerActivity` 原有 EPG 协调器循环），**手动/自动都可能重复请求 EPG**，且 `PlayerActivity` 的 EPG 成功路径**不清 `epg_last_failure/失败计数`**，导致策略层继续按退避重试；“基础”分组标题的位置也与分组语义不符。源码可接受需修这些点。

> 只读固定 commit `094609d7`；HEAD `224097e`（docs-only）。工作树有 t? 的 `PlayerActivity/SettingsDrawer/ChannelInfoBanner/AudioTrack*` 并发修改（未读取/未评价）。未改代码、未跑 Gradle/CI/ADB、未操作 TV。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`；被审查 commit `094609d7e5694242463354a0a25fdd786377ef0c`。
- `git show --stat`：7 files +574/−8：`AutoUpdateConfig.kt`、`AutoUpdateScheduler.kt`、`AutoUpdateConfigTest.kt`、`Prefs.kt`、`IptvApplication.kt`、`PlayerActivity.kt`、`SettingsDrawer.kt`；docs `AUTO_UPDATE_SETTINGS.md` 224097e。
- pair：主 `13,027,219B` SHA `4eabf6b3…ac288`、test `502,955B` `54bb7cff…330e`、run 44824103325（run10112）、10112；设备未验证。
- 关键：`AutoUpdateConfig` 96 行；`AutoUpdateScheduler` 145 行；`IptvApplication` L12 `AutoUpdateScheduler.start`；`PlayerActivity` L795–801 scheduler/事件、L803–870 EPG 协调器、L1320–1352 设置回调；`SettingsDrawer` L86–90 focus、L165–213 分组/行。

## 2. 已通过/正确部分

### 设置分组骨架（部分 pass）
- 新增 `SettingsGroupHeader`（L266–276）：`Text` 无 `focusable`/`clickable`，0dp、无背景/无蓝框；不可聚焦。
- 三个 header 存在：`数据与更新`（L166）、`基础`（L201）、`台标与缓存`（L213）。
- `LaunchedEffect(visible)` 默认 `recentRequester.requestFocus()`（L86–90，t167 承接）；Back/Left 仍 `onClose`（L104–111）；TIV 源配置/更新EPG/检查更新路径保留。

### 自动更新策略（配置纯逻辑 pass）
- `AutoUpdateConfig.Interval` 5 档：OFF/STARTUP/H6/H12/H24；`parse/encode/next` 正确；`OFF` 永远 `shouldRefresh=false`。
- `backoffMs`：0/5/10/20/30 封顶；`shouldRefresh`：
  - OFF false；
  - lastFailure 存在时按 `now-lastFailure >= backoff` 重试；
  - 首次启动 `startupRefreshDone=false` → true；
  - STARTUP 之后 false；
  - 周期按 lastSuccess+period。
- `AutoUpdateConfigTest` 10 项覆盖默认/回环/OFF/启动/6/12/24/退避/启动门控/label/isPeriodic；JVM 119 全绿（CI 报告）。

### 调度/接线（部分 pass）
- `AutoUpdateScheduler` `scope=SupervisorJob+IO`，`start` idempotent（`started`），启动 `runDue(startup=true)` + 60s tick；`mutex` 串行 auto/manual；`refreshNow` bypass 间隔/退避；手动结果写 last success/failure；SharedFlow `events` + `PlayerActivity` Toast/`autoUpdateTick`。
- `IptvApplication` 已移除 `BackgroundSourceUpdater.scheduleStartupRefresh`，改 `AutoUpdateScheduler.start`；「关闭」= 启动不拉取（`shouldRefresh(OFF)=false`）。
- 手动播放列表刷新走 `BackgroundSourceUpdater.refreshPlaylistUrgent`（复用 mutex/原子 commit）；EPG 走 `BackgroundSourceUpdater.refreshEpg`。
- `Prefs` 键：auto_update_playlist/epg、playlist_last_success/failure/fail_count、epg_last_failure/fail_count；复用 `last_epg_update_time`；默认 STARTUP。
- `lastUpdateSummary` 人类可读（刚刚/N分钟/N小时/N天前），事件/tick 后刷新；手动播放列表走 scheduler → event → tick。

### 无回归 / 体积（pass）
- diff 仅上述 7 文件；未改 LoadControl 2500、MP2 AAR、零拷贝 SurfaceView、flat 0dp、频道键、数字 OSD、台标缓存开关、最近观看、EPG 数据层、SR、品牌。
- APK 13,027,219B（≈12.42 MiB）≤30MB；keep 210 identical/contract0；`check_player_threading` 不涉及本次 Player 访问（CI 记录）。

## 3. 需修复

### EPG-DUAL-01（high/medium）EPG 有两条刷新路径，手动/自动重复请求且无共享 mutex

**证据**：
- `PlayerActivity.onRefreshEpgNow` L1348–1352：
  ```kotlin
  shouldRefreshEpg.value = true
  AutoUpdateScheduler.refreshNow(context, playlist=false, epg=true)
  ```
  同时 **协调器循环** L853 `if (shouldRefreshEpg.value || isDifferentDay || isOver6Hours)` 会 `EpgRepository.loadStructured(..., forceRefresh=true)`；`AutoUpdateScheduler.refreshNow` 又调 `BackgroundSourceUpdater.refreshEpg` → **两次 EPG 网络/解析**，两个 Toast（“EPG更新成功” + “EPG已更新”），`epgData`/last update 竞争。
- 自动周期（如 EPG=H6）时：`AutoUpdateScheduler.runDue` 在 tick 调 `refreshEpg`；同时 `PlayerActivity` 循环 L834 `epgAutoDue` 为 true，L853 也调 `EpgRepository.loadStructured`。两者没有共享 EPG mutex（scheduler 的 `mutex` 只包 scheduler，不包 loop）。
- `PlayerActivity` 循环还用旧 `isDifferentDay/isOver6Hours` 作为实际刷新条件；如果 `epgAutoDue` 因**失败退避**变 true、但未跨天且未过6h，L853 仍跳过（循环层不重试），只能靠 scheduler 重试——两套条件不一致。

**修复（择一，推荐单一路径）**：
1. **让 AutoUpdateScheduler 成为 EPG 唯一自动/手动执行者**：删除 `PlayerActivity` 循环里的自动/手动 `EpgRepository.loadStructured` 分支，只保留 cached EPG 加载 + `BackgroundSourceUpdater.epgUpdated.collect`；`onRefreshEpgNow` 只调 scheduler。手动/自动都走 `BackgroundSourceUpdater.refreshEpg` + `onEpgResult`，共享 scheduler mutex。
2. 或者让 `PlayerActivity` 循环保留，但 `AutoUpdateScheduler` 的 EPG 只发“到点”事件，不实际请求；手动只走 loop；并加一个**共享 EPG mutex**（例如 `EpgRepository` 内/BackgroundSourceUpdater 内）避免并发。
- 无论哪种，`epgAutoDue` 与刷新条件统一：`if (manual || epgAutoDue)`，不要再用 `isDifferentDay/isOver6Hours` 二次否决。

### EPG-FAILURE-01（medium）EPG 成功不清失败计数/失败时间，策略会继续退避重试

**证据**：`PlayerActivity` L857–864 成功只 `Prefs.setLastEpgUpdateTime(...)`，**没有** `Prefs.clearEpgFailure(context)`/`epg_fail_count=0`；而 `AutoUpdateConfig.shouldRefresh` 优先看 `lastFailureAt>0`，会按 5/10/20/30min 继续判定 due。手动/循环成功若 scheduler 侧失败/未执行，失败记录仍在，下一次 tick 仍按退避重试；「上次更新」与策略状态不一致。
**修复**：成功路径统一调用 `onEpgResult(true)`/`Prefs.clearEpgFailure`；失败调用 `recordEpgFailure`。最好把 EPG 结果处理收敛到 `AutoUpdateScheduler` 一处（见 EPG-DUAL-01）。

### GROUP-01（low/medium）“基础” header 位置与分组语义不符

**证据**：`SettingsDrawer` L131–161 先放 **源配置/更新EPG/源选择/检查更新**，L166 才是 `数据与更新` header，L201 才 `基础` header（其下只有 `最近观看`）。t172 摘要说 **基础**（源配置/EPG/清晰度/检查更新/最近观看），实际这些基础项位于“数据与更新”之前、没有 header；`基础` 只覆盖最近观看。
**影响**：三组标题虽可见，但“基础”组名误导；TiviMate 式分组不成立。
**修复**：把 `SettingsGroupHeader("基础")` 移到 `源配置` 之前（包住源配置/EPG/源选择/检查更新/最近观看），或把 `最近观看` 归入“基础”并调整数据组顺序；保持 header 不可聚焦/0dp/无蓝框、默认 focus 最近观看、Back 只关设置。

### TEST-01（medium）只有 config 单元测试，调度/持久化/EPG 单一路径无测试

**证据**：`AutoUpdateConfigTest` 10 项只测纯策略；没有：
- `AutoUpdateScheduler` 启动一次+60s tick、OFF 不 fetch、失败退避/成功清零、手动 bypass；
- `Prefs` 键持久化/重启读取；
- EPG 双路径冲突（手动/自动只发一次、失败计数清空）；
- `SettingsDrawer` 分组 header 不可聚焦/default focus/Back。
CI 119 全绿不能捕获 EPG-DUAL-01/FALURE-01/GROUP-01。

**修复**：抽 `AutoUpdateScheduler` 的 clock/source/executor 接口做 JVM 测试；用 Robolectric/Prefs 单测持久化；Compose UI test 断言 header 无 focus、默认 recent、Back；设备 acceptance 测实际请求数（logcat SourceRefresh/EPG 只一次）。

### PERF-01（low）启动策略 0ms 立即网络，可能与冷启竞争

**证据**：`AutoUpdateScheduler.start` 的 `runDue(startup=true)` 立即在 IO 执行；`AutoUpdateScheduler` 文档/代码 `0 ms delay`（沿用 BackgroundSourceUpdater 的 t50 行为）。不阻塞主线程，但启动网络/解析仍可能与首帧/EPG 缓存读取争 CPU/IO。
**修复**：启动 pass 可延迟到首帧后 1–3s（或复用 `FirstFrame` 信号），尤其 6/12/24h 模式；「启动时」可在播放器建立后执行。验收测冷启 CPU/首帧不倒退。

### WORKMANAGER-01（low/info）进程死后的自动更新不再发生

**证据**：选 coroutine/process scope，理由文档合理（≤30MB、无 WorkManager、运行期刷新）；但进程被系统杀死/设备待机时 6h/12h/24h 不会到期执行，只在下次启动 pass 补一次。若产品要求“后台也按时更新”，需 WorkManager/AlarmManager；当前验收只重启/运行期，可接受，建议文档明示“仅应用运行期”。
**修复**：产品确认；若接受，文档/设置文案加“应用运行时”；若要后台，另立项引入 WorkManager（不在本轮）。

### STARTUP-RESET-01（low）`resetStartupFlag` 未被调用
- `AutoUpdateScheduler.resetStartupFlag` 存在但无调用；源配置变更/从 OFF 切 STARTUP 时同一进程不会重新触发启动 pass。当前 `MainActivity.scheduleRefresh` 会直接刷新（3s），但语义不统一。建议源配置保存后调用 `resetStartupFlag` 或显式 `refreshNow`。

## 4. 真机/视觉未验证

- 三组标题可见/焦点最近/Back 只关抽屉；设「关闭」重启无 SourceRefresh；设「每6小时」重启只一次启动请求且6h内不再；立即刷新 Toast/上次更新；飞行模式 5min 不重试/之后退避/手动可用；设置重启保持——均归 acceptance2。本报告不宣称设备通过。

## 5. 放行判断

- **源码结论：needs_revision。** 纯策略/退避/接线方向正确，但 **EPG 双路径重复请求（EPG-DUAL-01）**、**EPG 成功不清失败（EPG-FAILURE-01）** 是功能缺陷；GROUP-01/TEST-01 需修。
- 建议顺序：**EPG-DUAL-01（单一路径+统一条件）→ EPG-FAILURE-01 → GROUP-01 → TEST-01 → PERF/STARTUP/WORKMANAGER 文档**；重出 r27 pair 后再做 t174 真机验收。

## 6. 只读命令与终态

`git rev-parse HEAD`、`git status --short`、`git show --stat 094609d7`、`git show 094609d7`、`read AutoUpdateConfig.kt/AutoUpdateScheduler.kt/Prefs.kt/IptvApplication.kt/PlayerActivity.kt/SettingsDrawer.kt`、`read AutoUpdateConfigTest.kt`、`Get-Content docs/AUTO_UPDATE_SETTINGS.md`。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 工作树 t? 并发修改未读取/未评价；本报告完成后停止读取目标源码。
