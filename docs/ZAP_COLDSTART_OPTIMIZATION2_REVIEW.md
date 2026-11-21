# Zap 接线 / EPG 收集器 / 缓冲测试 复查（t201 / run10123）

任务 t202（attempt 1），designer2，2026-11-21（Asia/Hong_Kong）。**结论：needs_revision。** t201 修掉了 t200 的“无 onZap”和“collector 首帧门控丢事件”两个主问题：`StreamPlayback` 已调用 `onZapIfChanged`，`epgUpdated/manualEpgRequests` 已回到 `LaunchedEffect(Unit)`，缓存读取有 10s fallback；`LoadControlConfigTest` 已读 `LoadControlTuning` 的 300 真实 Builder。但仍有：

1. **`onZapIfChanged` 的 key 是每次 prepare 都递增的 MediaItem id**（`...-s${sequence.incrementAndGet()}`），同频道 re-prepare/重试/布局变化也会换 key → 重置计时；“同频道不重置”实际不成立，10 次换台里每次重试都会多一行/打断测量。
2. **`onZap` 与 `onPrepare` 在 StreamPlayback 相邻调用**：`zap` 起点在 `player.prepare()` 前，`prepare` 段恒约 0ms；用户换台入口到 StreamPlayback 之间的准备时间没有计入，TTFF 语义偏小。
3. **`ZapTraceTest` 用 `chan-1` 稳定 key 自测，不覆盖生产 `id` 的 sequence 行为**；没有真实 `StreamPlayback.prepare` 接线/EPG fallback 集成测试；`LoadControlConfigTest` 已修但 `ZapTrace` 生产 id 的 bug 仍绿。

> 只读固定 commit `65ccb3fd`；HEAD `0df1daf`（docs-only）。工作树 clean。未改代码、未跑 Gradle/CI/ADB、未操作 TV。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`；被审查 commit `65ccb3fde97cf59f33ec01c57ab40df4c2fe0a44`。
- `git show --stat`：5 files +119/−36：`ZapTrace.kt`、`ZapTraceTest.kt`、`LoadControlConfigTest.kt`、`PlayerActivity.kt`、`StreamPlayback.kt`；docs `ZAP_COLDSTART_OPTIMIZATION2.md` 0df1daf。
- pair：主 `13,059,991B` SHA `490208a9…1a9d`、test `502,955B` `e1e3f66a…4cd0`、run 44847564722（run10123）、10123；设备未验证。
- 关键行：`StreamPlayback` L55 `id = c…-s${sequence.incrementAndGet()}`、L69 `onZapIfChanged(id, now)`、L70 `onPrepare(now)`、L71 `player.prepare()`；`PlayerActivity` L906–950 EPG effect；`ZapTrace` L37–44 `onZapIfChanged`；`LoadControlConfigTest` 4 项；`ZapTraceTest` 8 项。

## 2. 已通过/正确部分

### ZAP-ONZAP-01 接线 — 部分 pass
- `StreamPlayback.prepare` L69 `ZapTrace.onZapIfChanged(id, elapsedRealtime())`，L70 `ZapTrace.onPrepare(...)`，L71 `player.prepare()`：生产路径已武装，**不再恒 null**；`summaryLine` 在 `onRenderedFirstFrame`（PlayerActivity L694–700）debug gate 后输出 `zap=…ms prepare=…ms firstFrame=…ms`；`ZapTraceTest.theProductionArmProducesASummaryLine` 覆盖字符串 `zap=765ms prepare=150ms firstFrame=615ms`。
- **但 key 用每次 prepare 递增的 id**，见 §3 ZAP-KEY-01；且 onZap 与 onPrepare 相邻导致语义问题，见 ZAP-TIMING-01。

### EPG-COLLECT-01 — pass
- `LaunchedEffect(Unit)` 恢复：`epgUpdated.collect` L926–931、`manualEpgRequests.collect` L934–943 均在首次 composition 启动，首帧前事件不丢；`manualEpgRequests` 首帧前可用。
- 仅缓存读取被门控：`withTimeoutOrNull(ColdStartPolicy.EPG_CACHE_FALLBACK_MS=10_000) { snapshotFlow { firstFrameRendered }.first { it } }` L938–941，超时/首帧到达后 `EpgRepository.load(forceRefresh=false)` L942–949；离线/首帧不出时 10s 内仍读；`epgCache=... firstFrame=...` L943–948。
- `EPG_CACHE_FALLBACK_MS` 常量 10_000 有测试范围；`ColdStartPolicy.usesCacheFirstPlaylist` 已删除；collector 与 cache 解耦，符合 t200 要求。

### LOADCONTROL-TEST-01 — pass
- `LoadControlConfigTest.productionBufferDurationsAreValid` L16–28 使用 `LoadControlTuning.minBufferMs()/maxBufferMs()/playbackBufferMinMs()/playbackBufferAfterRebufferMs()`；新增 `theProductionArmIsActuallyThreeHundred` 断言 300 + 真实 `DefaultLoadControl.Builder`；`productionMinBufferCoversAfterRebuffer` 也读 tuning；`invalidBufferDurationsThrow` 保留。生产 300 有真实 Builder 守卫。

### B1/DOC/PERF — 部分 pass
- `usesCacheFirstPlaylist` 无生产调用已删除（t201 声明诚实）；`EPG_CACHE_FALLBACK_MS` 有测试；`ZapTrace` 日志调用有 `Log.isLoggable` gate；release 不 `Log.d`。
- **但 “zero writes” 不实**：`onZapIfChanged/onPrepare/onFirstFrame` 仍无条件写 `@Volatile` 字段，见 PERF-01。
- **文档 A1 “same channel re-prepare keeps” 与生产 id 冲突**，见 DOC-01。

### 无回归/体积 — pass
- diff 仅上述 5 文件；未改 F1/F3/F4、MP2、零拷贝、0dp、频道键、数字 OSD、台标、最近观看、EPG 数据层、SR、品牌；APK 13,059,991B≤30MB；三守卫报告 OK。
- 唯一行为变化仍是 A/B `playbackBufferMin=300` 与 EPG 缓存延迟（现已带 fallback）。

## 3. 阻断/需修复

### ZAP-KEY-01（high）`onZapIfChanged` 使用每次 prepare 递增的 id，同频道 re-prepare 仍重置

**证据**：
- `StreamPlayback` L55：
  ```kotlin
  val id = "c${channelId}-l${channel.url.hashCode().toUInt()}-s${sequence.incrementAndGet()}"
  ```
  每次 `prepare()` 调 `sequence.incrementAndGet()`，**同一频道、同一 URL 的 re-prepare（布局/重试/音轨重选/surface 重建）都会得到新的 id**。
- L69 `ZapTrace.onZapIfChanged(id, now)`；`ZapTrace` L37–41：
  ```kotlin
  if (key == zapKey && zapAt > 0L) return
  zapKey = key; onZap(now)
  ```
  由于 id 每次都不同，条件永不命中 → 每次 re-prepare 都 reset；与文档“re-prepare for same channel keeps the running measurement”和验收①“同频道不重置/10行”不符。
- `ZapTraceTest.theStopwatchIsArmedOncePerRealZap` 用固定字符串 `"chan-1"`，只验证了纯函数；生产 id 是 sequence 生成，测试没覆盖，典型“测试绿但生产错”。

**修复**：
- 用**稳定 channel identity** 作为 key：`channelId`（或 `channelId + streamUrl`，不含 sequence）传给 `onZapIfChanged`；MediaItem id 仍需 sequence 只用于 session，不可作 zap key。
- 或在 `PlayerActivity.onChannelStep`/channel select 用户入口调用 `ZapTrace.onZap(stableChannelKey)`，StreamPlayback 只 `onPrepare`；重试用同一 key。
- 新增接线测试：连续两次用相同 channelId 不同 mediaId prepare，断言不会重置；新 channelId 才重置。

### ZAP-TIMING-01（medium/high）`zap` 起点在 prepare，`prepare` 段恒约 0，TTFF 语义偏小

**证据**：`StreamPlayback` L69–71 三行连续：
```kotlin
ZapTrace.onZapIfChanged(id, elapsedRealtime())
ZapTrace.onPrepare(elapsedRealtime())
player.prepare()
```
`zapAt` 与 `prepareAt` 基本同一时刻，`prepareDelayMs()` 通常 0–1ms；t201 测试 expected `prepare=150ms` 只在手工调用。`zap` 未包含用户按上下/数字到 `StreamPlayback.prepare` 之间的 channel 查找/`setMediaSource` 准备，与验收“用户感知 TTFF”不一致，可能系统性偏小。
**修复**：在用户换台入口（`onChannelStep`、数字 `submitNumber`、channel select、回看切台）记录 `onZap`；`StreamPlayback` 只记录 `onPrepare`。若坚持在 StreamPlayback 内，可把 `onZapIfChanged` 移到方法入口/`setMediaSource` 前，不要与 `onPrepare` 相邻；明确文档“zap 起点=prepare 开始”并接受语义。补集成测试用可注入时钟。

### TEST-01（medium）生产接线/首帧前事件/真实 Builder/release 静默仍缺集成覆盖

**证据**：
- `ZapTraceTest` 8 项：`theStopwatchIsArmedOncePerRealZap` 用固定 key，不覆盖生产 id；`theProductionArmProducesASummaryLine` 手工 onZap/onPrepare/onFirstFrame，不经过 `StreamPlayback.prepare`；
- 无 `epgUpdated`/`manualEpgRequests` 在首帧前 emit 的测试；无 `withTimeoutOrNull` 10s fallback 的真实 effect 测试（只测常量范围）；
- `LoadControlConfigTest` 已用 tuning（pass），但无 `ZapTrace` release 静默/volatile 写测试；
- 172 全绿仍无法捕获 ZAP-KEY-01/ZAP-TIMING-01。

**修复**：加 `StreamPlayback` 测试（fake player/clock）验证 id 相同 key 不重置/新 id 重置；Activity/Robolectric 测 EPG collector 首帧前 emit；EPG fallback 用注入 `firstFrameRendered`/虚拟时钟；源码扫描 `onZapIfChanged` key 不含 `sequence`；release 模式断言 `Log.isLoggable` 不输出。

### DOC-01（low/medium）文档与生产 id/零写不符
- `ZAP_COLDSTART_OPTIMIZATION2.md` A1 写“re-prepare same channel keeps”，但生产 `id` 含 `sequence` → 不 keeps；应改 key 或文档降级。
- “zero writes and zero string building when tag off”：`onZapIfChanged/onPrepare/onFirstFrame` 仍写 `@Volatile` 状态；只有日志/字符串被 gate。改为“release 不输出日志，状态写入仅两次 volatile/换台”。
- `mayLoadEpgCache` 仍无生产调用（只测试/常量），可删或注明纯规则。

### PERF-01（low）debug 关闭仍写 volatile
- `onZapIfChanged` L37 `zapKey=...`、`onZap` L45–49、`onPrepare` L52、`onFirstFrame` L56–59 无条件写 `@Volatile`；release 切换台写数次，量级可忽略但非“零开销”。建议 `if (BuildConfig.DEBUG || enabled)` gate，或接受并文档化。

### EPG-CACHE-MAIN-01（low/medium）10s 后 `EpgRepository.load` 仍在 Compose 主线程
- `LaunchedEffect(Unit)` 默认 Main；`EpgRepository.load(forceRefresh=false)` 在 L942 主线程做磁盘/XMLTV 解析（旧行为也如此，但 t197 目的是避免首帧竞争；首帧后/10s fallback 时解析仍可能卡 UI）。建议 `withContext(Dispatchers.IO)` 读取并按需切回 state；加首帧后/fallback 的 jank 验收。

## 4. 真机/视觉未验证

- 10 次换台 10 行 `zap=`、同频道不重置；p50/p95 与 CI10122 765/1413、CI10121 996/1317；飞行模式 EPG 10s 内加载且 firstFrame=false；冷启 epgCache 不早于首帧；rebuffer 不增；均归 acceptance2。本报告不宣称设备通过。

## 5. 放行判断

- **源码结论：needs_revision。** EPG collectors/fallback、LoadControl 守卫已修；但 **ZAP-KEY-01（同频道 id 重置）** 使验收①“同频道不重置/每切台一次”不成立，**ZAP-TIMING-01** 使 zap 语义偏小；TEST-01 需补生产接线测试。
- 建议顺序：**ZAP-KEY-01（稳定 channel key）→ ZAP-TIMING-01（用户入口 onZap）→ TEST-01 → DOC/PERF/EPG-MAIN**；重出 r27 pair 后真机 A/B。

## 6. 只读命令与终态

`git rev-parse HEAD`、`git status --short`、`git show --stat 65ccb3fd`、`git show 65ccb3fd`、`read ZapTrace.kt/ZapTraceTest.kt/StreamPlayback.kt/PlayerActivity.kt/LoadControlConfigTest.kt`、`Get-Content docs/ZAP_COLDSTART_OPTIMIZATION2.md`。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 本报告完成后停止读取目标源码。
