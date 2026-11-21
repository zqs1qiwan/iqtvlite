# Zap 稳定 key / 用户入口计时 / EPG IO 复查（t204 / run10124）

任务 t207（attempt 1），designer2，2026-11-21（Asia/Hong_Kong）。**结论：needs_revision。** t204 修好了稳定的 `zapKey(channelId,url)`（去 sequence）、把 `ZapTrace.onZapRequested` 放到 `playChannel`、把 EPG `load` 移到 `Dispatchers.IO`，并删除了无生产的 `mayLoadEpgCache`；`ZapTraceTest` 10 项覆盖稳定 key/同频道不重置/首次 fallback。但 **`playChannel` 不是所有换台路径的汇入口**：D-pad 上下、数字跳台、上一台、清晰度/变体切换都直接走 `playGroupAuto`/`playGroupVariant`，不会调用 `playChannel`，因此没有用户入口 `onZap`，StreamPlayback 的 `onPrepare` fallback 把 `zapAt=prepareAt`，`prepareDelay=0`；验收①“按上下换台 prepare 非 0”仍不成立。另有“同频道重进/重 prepare 不出第二行”缺少 log-once 保护。

> 只读固定 commit `24ae116e`；HEAD `437447c`（docs-only）。工作树 clean。未改代码、未跑 Gradle/CI/ADB、未操作 TV。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`；被审查 commit `24ae116ed1e01c241d34b903a7b252300241b22d`。
- `git show --stat`：4 files +78/−30：`ZapTrace.kt`、`ZapTraceTest.kt`、`PlayerActivity.kt`、`StreamPlayback.kt`；docs `ZAP_COLDSTART_OPTIMIZATION3.md` 437447c。
- pair：主 `13,059,987B` SHA `4bf33d93…82490`、test `502,955B` `407b78f1…7ef2`、run 44849027943（run10124）、10124；设备未验证。
- 关键行：`PlayerActivity` `playChannel` L1174–1192（onZapRequested L1176）、`playGroupVariant` L1085、`playGroupAuto` L1122、`setOnChannelStep` L1273–1285、`setOnNumberJump` L1265–1271、`playLastChannel` L1195–1208；`StreamPlayback` L64–75；`ZapTrace` L37–60；`PlayerActivity` EPG effect L906–950。

## 2. 已通过/正确部分

### ZAP-KEY-01 — pass（key 本身）
- `ZapTrace.zapKey(channelId: Long, url: String) = "c$channelId-l${url.hashCode().toUInt()}"`，**不含 `s${sequence}`**；`StreamPlayback` 不再用 mediaId 武装，只 `onPrepare`。
- `ZapTrace.onZapRequested(key,now)` 相同 key 且 `zapAt>0` 直接 return；不同 key reset。
- `ZapTraceTest.theKeyIsTheChannelNotThePerPrepareMediaId` 覆盖同 channelId 同 url 同 key、同 channelId 不同 url 不同 key；`theStopwatchIsArmedOncePerRealZap` 覆盖同 key 不重置/不同 key 重置；`prepareSelfArmsWhenNoUserEntryHappened` 覆盖首次 autoplay fallback；`theProductionArmProducesASummaryLine` 覆盖 summary 字符串。
- **但 `playChannel` 不是所有入口**，见 §3 ZAP-ENTRY-01。

### ZAP-TIMING-01 — 部分 pass
- `playChannel` L1176 首行 `ZapTrace.onZapRequested(zapKey(channel.id, channel.url), elapsedRealtime())`；StreamPlayback L71 只 `onPrepare`；`zap=用户入口→首帧`、`prepare=入口→prepare` 的结构正确。
- `onPrepare` 未武装时自武装（首次 autoplay fallback），测试 `prepareSelfArms...` 覆盖 prepareDelay=0 的 fallback。
- **但 D-pad/数字/上一台/变体切换绕过 playChannel**，见 ZAP-ENTRY-01；所以主路径仍是 0 gap。

### EPG-CACHE-MAIN-01 — pass
- `PlayerActivity` L942–945 `withContext(Dispatchers.IO) { EpgRepository.load(...) }`；10s fallback `withTimeoutOrNull(EPG_CACHE_FALLBACK_MS) { snapshotFlow{firstFrameRendered}.first{it} }` L938–941；collector 仍在 `LaunchedEffect(Unit)` L926–943；首帧前事件不丢；离线 10s 后读；`epgCache... firstFrame=` 日志；不再卡主线程的 XMLTV parse。
- `EPG_CACHE_FALLBACK_MS=10_000` 有测试。

### DOC/PERF 清理 — 部分 pass
- `ColdStartPolicy.mayLoadEpgCache` 已删除（含旧测试改为 `EPG_AFTER_FIRST_FRAME` 常量）。
- **但 “debug 关闭零写入零拼接” 仍不实**：`onZapRequested`/`onZap`/`onPrepare`/`onFirstFrame` 仍无条件写 `@Volatile` 状态（仅日志/字符串被 `Log.isLoggable` gate）；见 PERF-01。

### 无回归/体积 — pass
- diff 仅 4 文件；未改 F1/F3/F4、LoadControl 数值、MP2、零拷贝、0dp、频道键、数字 OSD、台标、最近观看、EPG 数据层、SR、品牌；APK 13,059,987B≤30MB；三守卫报告 OK。

## 3. 阻断/需修复

### ZAP-ENTRY-01（high）`playChannel` 不是所有换台路径的汇入口，D-pad/数字/上一台/变体切换无 onZap

**证据**：
- `playChannel` 只在 L1323 `onSelectChannel`（抽屉点选）和 L1227 `onReturnToLive` 调用；`onChannelStep` L1273–1285 直接 `playGroupAutoState.value(nextGroupIndex)`；`onNumberJump` L1265–1271 直接 `playGroupAutoState.value(number-1)`；`playLastChannel` L1195–1208 直接 `playGroupVariant(target...)`；清晰度/变体 L1586/1611 直接 `playGroupVariant`。
- 这些路径都不经过 L1176 `ZapTrace.onZapRequested`；`StreamPlayback.onPrepare` L? `if (zapAt==0L) zapAt=now` 自武装 → `prepareAt=zapAt`，`prepareDelayMs()` 恒 0。D-pad 上下（验收主路径）的 `prepare` 仍为 0，不是真实 gap。
- `playGroupAuto` 还可能先做 `SourceLatencyProber.probe`（异步、网络），用户按键到实际 prepare 的这段最大；当前完全未计入 zap。

**修复**：
- 把 `onZapRequested` 放到**所有**换台路径的统一汇入口：`playGroupAuto` 首行（group 的默认/待选 channel key）和 `playGroupVariant` 首行（实际 channel key）；`playChannel` 只保留其一，避免重复（同 key 幂等）。
- 或 `onChannelStep`/`onNumberJump`/`playLastChannel`/quality variant 回调都调用 `ZapTrace.onZapRequested(ZapTrace.zapKey(channel.id, channel.url), ...)`。
- 补测试：D-pad、数字、上一台、变体切换均调用 onZapRequested；prepareDelay>0（可用 fake clock）。

### ZAP-REPEAT-01（medium）同频道重进/重 prepare 可能输出第二行

**证据**：
- `ZapTrace.onZapRequested` 同 key 不 reset；`onPrepare` 同 key 更新 `prepareAt`；`PlayerActivity.onRenderedFirstFrame` L694–700 每次首帧都 `ZapTrace.onFirstFrame(now)` + `summaryLine()`，无 log-once。
- 同一频道在首帧后重进/重 prepare（抽屉再选同一频道、surface 重建、重试）→ `lastZapMs = now - 旧 zapAt`（可能很大），`prepareDelay` 用新 prepareAt，输出第二行；验收②“同一频道重进出第二行也不重置”不成立。
- `ZapTraceTest` 只验证 `zapToFirstFrameMs` 保持，不验证不重复 log。

**修复**：`ZapTrace` 增加 `loggedKey`/`lastLoggedZapKey`，`onFirstFrame` 只在 key/测量变化时返回 true 或 `summaryLine` 清空后标记；`onZapRequested` 同 key 且已完成 → 不重新武装、不再 log；或 `onRenderedFirstFrame` 只在 `ZapTrace.shouldLog(key)` 时 log。补重复首帧测试。

### TEST-01（medium）纯单测不覆盖生产入口/重复行/真实 EPG fallback

**证据**：`ZapTraceTest` 10 项使用手工 `zapKey`/`onZapRequested`；没有：
- `onChannelStep`/`onNumberJump`/`playLastChannel`/`playGroupVariant` 调用 onZapRequested 的集成；
- `onRenderedFirstFrame` 重复不 log；
- `epgUpdated`/`manualEpgRequests` 首帧前 emit 在真实 effect 不丢；
- 10s fallback 的 UI 不卡（IO 调用）；
JVM 174 全绿无法捕获 ZAP-ENTRY-01/ZAP-REPEAT-01。

**修复**：加 Activity/Compose/Robolectric 测按键路径；抽 `playChannel` 统一入口或 fake `ZapTrace` 计数；加 `scripts/check_zap_instrumentation.py` 源码扫描所有换台路径含 onZapRequested；Epg effect 首帧前 emit 测试。

### PERF-01（low）debug 关闭仍写 volatile / 创建 key
- 每次 `onZapRequested` 构造 `ZapTrace.zapKey` 字符串并写 `zapKey/zapAt`；`onPrepare`/`onFirstFrame` 写 2 个 volatile；release 不是“零写入”。建议 `enabled` gate（BuildConfig.DEBUG）或文档改为“无日志/无字符串拼接，少量 volatile 写”。

### DOC-01（low）文档口径与实现差异
- `ZAP_COLDSTART_OPTIMIZATION3.md` 称“playChannel 按键/点击/数字/整组播放汇入处首行 onZapRequested”，实际 D-pad/数字/上一台/变体不经过 playChannel；请改为“目前仅 playChannel 抽屉/回直播路径，D-pad/数字待接入”或修代码。
- “debug 关闭零写入”改为“日志/字符串零，状态 volatile 写仍在”。

## 4. 真机/视觉未验证

- 10 次换台 10 行且 prepare 真实非0；同频道重进不第二行/不重置；10124 A/B vs 996/1317；飞行模式 EPG 10s 内加载且 UI 不卡、手动单次；冷启 epgCache 不早于首帧；rebuffer 不增——均归 acceptance2。本报告不宣称设备通过。

## 5. 放行判断

- **源码结论：needs_revision。** Stable key/EPG IO/collector fallback/LoadControl 测试已修；但 **ZAP-ENTRY-01** 使 D-pad/数字等主换台路径的 prepare=0、zap 偏小，**ZAP-REPEAT-01** 同频道重进可能多行；TEST-01 需补。
- 建议顺序：**ZAP-ENTRY-01（所有换台入口统一 onZapRequested）→ ZAP-REPEAT-01（log-once）→ TEST-01 → PERF/DOC**；重出 r27 pair 后真机 A/B。

## 6. 只读命令与终态

`git rev-parse HEAD`、`git status --short`、`git show --stat 24ae116e`、`git show 24ae116e`、`read ZapTrace.kt/ZapTraceTest.kt/StreamPlayback.kt/PlayerActivity.kt`、`Get-Content docs/ZAP_COLDSTART_OPTIMIZATION3.md`。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 本报告完成后停止读取目标源码。
