# 换台埋点 / 缓冲单变量 / EPG 懒加载 独立复查（t197 / run10122）

任务 t200（attempt 1），designer2，2026-11-21（Asia/Hong_Kong）。**结论：needs_revision。** A2 的 `LoadControlTuning` 单变量结构、`isValid` 不变量、回退路径，以及 B1 的“缓存优先不改代码”边界方向正确；但 **A1 的 TTFF 埋点在生产代码中没有 `onZap` 调用**（`ZapTraceTest` 自己调 `onZap` 所以测试绿，实际 logcat 永远不会出现 `zap=…`），且 **B2 把 `epgUpdated` 收集器和 `manualEpgRequests` 一起搬进了 `firstFrameRendered` 的 LaunchedEffect**，与“收集仍立即启动/不漏背景刷新”声明相反；另外 `LoadControlConfigTest` 仍用旧 500，未覆盖生产的 300。

> 只读固定 commit `5e7f0be9`；HEAD `2802d94`（docs-only）。工作树 clean（本任务相关）。未改代码、未跑 Gradle/CI/ADB、未操作 TV。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`；被审查 commit `5e7f0be9364d234c45f42c0b7eee4b35d63280ca`。
- `git show --stat`：4 files +232/−6：`ZapTrace.kt`、`ZapTraceTest.kt`、`PlayerActivity.kt`、`StreamPlayback.kt`；docs `ZAP_COLDSTART_OPTIMIZATION.md` 2802d94。
- pair：主 `13,059,987B` SHA `631f885e…a08f25`、test `502,955B` `61db6b5a…93bd7`、run 44845259619（run10122）、10122；设备未验证。
- 关键行：`StreamPlayback` L64–72（prepare/onPrepare）、`PlayerActivity` L600–610（LoadControl）、L689–700（onRenderedFirstFrame）、L908–945（EPG effect）、`ZapTrace` 118 行、`ZapTraceTest` 81 行、`LoadControlConfigTest` 现有 3 项。

## 2. 已通过/正确部分

### A2 LoadControl 单变量 — 结构 pass
- `LoadControlTuning`：MIN 2500、MAX 6000、PLAYBACK 300、AFTER_REBUFFER 2000；`isValid` = MIN≥BUFFER_FOR_PLAYBACK(500) && MIN≥AFTER_REBUFFER && PLAYBACK in 1..500；测试 `ZapTraceTest.loadControlKeepsTheCrashInvariantWithTheNewBufferArm` 断言 2500/6000/300/2000 和 300<500。
- `PlayerActivity` L606–609 已改用 `LoadControlTuning.minBufferMs()/maxBufferMs()/playbackBufferMinMs()/playbackBufferAfterRebufferMs()`，`setBufferDurationsMs(2500,6000,300,2000)`；min/max/after 未改；回退=`PLAYBACK_BUFFER_MIN_MS=500` 一个常量。
- `LoadControlConfigTest` 的 `invalidBufferDurationsThrow`（800/300/1000）仍验证 Media3 约束；但生产参数测试见 §3 LOADCONTROL-TEST-01。

### A1 埋点位置/逻辑 — 部分 pass（生产调用缺失）
- `StreamPlayback.prepare` L68 `ZapTrace.onPrepare(elapsedRealtime)` 在 `player.prepare()` 前（L69）——位置正确。
- `PlayerActivity.onRenderedFirstFrame` L692–700 `ZapTrace.onFirstFrame(now)`，在 `onRenderedFirstFrame` 回调中——位置正确；`summaryLine()` 只在 `Log.isLoggable(ZAP_TAG, DEBUG)` 时 `Log.d`。
- `ZapTrace` 纯 Kotlin：onZap 重置、onPrepare/onFirstFrame、prepareDelay/firstFrameDelay/zapToFirstFrame、summaryLine；`ZapTraceTest` 覆盖 split/incomplete/new zap reset；日志字段可解析 `zap=…ms prepare=…ms firstFrame=…ms`。
- **但生产代码没有调用 `ZapTrace.onZap`**，见 §3 ZAP-ONZAP-01。

### B2 EPG 懒加载 — 部分 pass
- `firstFrameRendered` L502 `remember { false }`；`onRenderedFirstFrame` L695 置 true；`LaunchedEffect(firstFrameRendered)` L912 + `ColdStartPolicy.mayLoadEpgCache(firstFrameRendered)` 门控；`epgCache=…ms` 计时 L917–920；缓存读取 `EpgRepository.load(forceRefresh=false)`。
- **但 `epgUpdated` 收集和 `manualEpgRequests` 收集被一起移进该 effect**（L926–943），见 §3 EPG-COLLECT-01。

### B1 缓存优先 / B3 — pass（边界诚实）
- `ColdStartPolicy.usesCacheFirstPlaylist(cachedAvailable)` 纯规则，`ZapTraceTest` 覆盖；B1 没有改播放列表读取代码（文档说明），启动网络刷新 1500ms 未改（`AutoUpdateScheduler.STARTUP_DELAY_MS` 仍 1500）。
- A3 预连接未实现；文档明确“deferred, not shipped”、风险/下一步；诚实。
- B1/B2/B3 的 before/after 表均标 pending，不宣称设备结果；诚实。

### 无回归/体积 — 代码层 pass
- diff 仅 4 文件；未改 F1/F3/F4、MP2、零拷贝、去圆角、频道键、数字 OSD、台标、最近观看、EPG 数据层、SR、品牌；APK 13,059,987B≤30MB；三守卫报告 OK（CI）。
- 唯一行为变化是 `playbackBufferMin 500→300`（A/B 臂）和 EPG 缓存延迟；后者有 §3 风险。

## 3. 阻断/需修复

### ZAP-ONZAP-01（high）生产代码从未调用 `ZapTrace.onZap`，TTFF 日志永远不会出现

**证据**：
- 全仓 grep `ZapTrace.` 只有：
  - `PlayerActivity` L694 `ZapTrace.onFirstFrame`；
  - `StreamPlayback` L68 `ZapTrace.onPrepare`；
  - 测试 `ZapTraceTest` 4 处 `onZap`。
- `PlayerActivity.prepareRequest`/频道切换/`onChannelStep` **均无** `ZapTrace.onZap(...)`；`StreamPlayback.prepare` 也没有。
- `ZapTrace.onFirstFrame` 里 `val ms = if (zapAt > 0L) ... else -1L`；`summaryLine()` 在 total<0 时返回 null；`PlayerActivity` L697–699 因此不会 `Log.d`。设备上 `setprop log.tag.IQTV-ZAP DEBUG` 后 **没有 `zap=` 行**，验收①无法执行。
- 文档 A1 写 “`onZap(now)` a channel change is requested (available for the acceptance harness)”，但项目没有 acceptance harness 注入点；`ZapTraceTest` 手动 onZap 只是单元自证。

**修复**：
- 在 **用户换台入口** 调用 `ZapTrace.onZap(SystemClock.elapsedRealtime())`：例如 `PlayerActivity` 的 `onChannelStep`/channel select/数字 submit/回看切台，在 `prepareRequest` 前；或 `StreamPlayback.prepare` 开头（但同一次布局/预取重复 prepare 需避免重置；用 `zapSequence`/`onZap` 只在用户意图）。
- 在 `PlayerActivity.onRenderedFirstFrame` 日志前确保 `onZap` 已被调用；`ZapTraceTest` 加“无 onZap 时 no log”/“换台调用”集成测试。
- 文档把“available for harness”改为“生产入口已接线”，或明确 harness 脚本。

### EPG-COLLECT-01（high）`epgUpdated`/`manualEpgRequests` 收集器被延迟到首帧，背景刷新会丢

**证据**：
- t193 旧 effect（`LaunchedEffect(Unit)`）在缓存读取同一块内启动 `BackgroundSourceUpdater.epgUpdated.collect` 和 `manualEpgRequests.collect`；t197 把整块改为 `LaunchedEffect(firstFrameRendered)`，二者在 L926–943，**只有 firstFrameRendered=true 才订阅**。
- `BackgroundSourceUpdater._epgUpdated = MutableSharedFlow(extraBufferCapacity=1)`（L34，**无 replay**）；`AutoUpdateScheduler` 启动 pass 在 1.5s 后刷新 EPG，若首帧 >1.5s（或播放失败/格式不支持导致首帧不出现），`tryEmit` 时没有订阅者 → 事件丢失；EPG 数据不会更新，直到下一轮。
- `manualEpgRequests = MutableSharedFlow<Unit>(extraBufferCapacity=1)`（L498，**无 replay**）；首帧前用户按“立即刷新 EPG”/设置的更新 EPG（若首帧未出现），`tryEmit` 丢；`manualEpgRequests.collect` 也未启动。
- **首帧永不出现**（离线、解码失败、无可用视频）时：`firstFrameRendered` 永 false，EPG 缓存不读、`epgUpdated` 不收集、手动 EPG 不工作；旧行为至少在 `LaunchedEffect(Unit)` 读缓存/启动收集。这是回归。
- 文档 B1 写“`epgUpdated` collector still starts immediately (cold Flow...)”与代码不符。

**修复**：
- 把 **collector** 放回 **`LaunchedEffect(Unit)`** 立即启动；只把 **缓存读取** 放到 `LaunchedEffect(firstFrameRendered)`（或 `if (firstFrameRendered) { cache load }`）。例如：
  ```kotlin
  LaunchedEffect(Unit) {
      launch { epgUpdated.collect { ... } }
      launch { manualEpgRequests.collect { ... } }
  }
  LaunchedEffect(firstFrameRendered) { if (mayLoad) loadCachedEpg() }
  ```
- 加首帧超时/失败 fallback（例如 8s 或 `onPlayerError` 时）加载缓存，避免无首帧永远无 EPG。
- `epgUpdated` 可改 `MutableSharedFlow(extraBufferCapacity=1, replay=1)` 作为兜底（注意重复 last value 语义）。
- 测试：首帧前 emit epgUpdated/manual 不丢；首帧永不出现时缓存仍加载；首帧后缓存计时。

### LOADCONTROL-TEST-01（medium）`LoadControlConfigTest` 仍用旧 500，未覆盖生产 300

**证据**：
- `LoadControlConfigTest.productionBufferDurationsAreValid` L? `setBufferDurationsMs(2_500, 6_000, 500, 2_000)`；`productionMinBufferCoversAfterRebuffer` 也用 500。
- 生产 `LoadControlTuning.PLAYBACK_BUFFER_MIN_MS=300`；测试名“production”但值不是生产，无法捕获未来把 300 改错/改回；`ZapTraceTest` 只测 object 值，不验证真实 Builder 用 300。
- 两个测试对“生产参数”的断言不一致（500 vs 300），留下漂移。

**修复**：`LoadControlConfigTest` 改用 `LoadControlTuning` 的四个值构造 `DefaultLoadControl.Builder`（import 纯 Kotlin object 可在 JVM 测试用）；或在 `PlayerActivity` 抽 `buildLoadControl()` 供测试。`invalidBufferDurationsThrow` 保留。

### TEST-01（medium）纯单测未覆盖生产接线/首帧顺序

**证据**：`ZapTraceTest` 6 项全部直接调 `ZapTrace`，没有：
- 生产 `StreamPlayback.prepare`/`PlayerActivity` 是否调用 `onZap`；
- `onRenderedFirstFrame` 后 EPG effect 顺序/collector 是否仍在；
- 首帧前 `epgUpdated`/manual emit 不丢；
- 真实 `LoadControlConfigTest` 300；
- 日志 gate 下 release 静默。
JVM 169 全绿不能捕获 ZAP-ONZAP-01/EPG-COLLECT-01。

**修复**：加 `scripts/check_zap_instrumentation.py` 源码扫描（必须存在 `ZapTrace.onZap(` 生产调用、`onPrepare` 在 `prepare()` 前、`onFirstFrame` 在 `onRenderedFirstFrame`、`epgUpdated.collect` 不在 firstFrame gated 块）+ Activity/Robolectric 测试；设备 acceptance 按文档跑 10 zaps。

### DOC-01（low/medium）文档两处与代码不符
- A1 称 `onZap` “available for acceptance harness”，生产未接线；B1 称 `epgUpdated` collector 立即启动，实际在 firstFrame effect。修正文档或代码，二者必须一致。
- A1 注释提 `[enabledByDefault]` 字段，`ZapTrace` 中不存在（只有 `Log.isLoggable` 门控）；删除引用。
- B1 “cold Flow no work until data arrives” 不改变“无 replay 时会丢首帧前事件”的事实。

### B1-USES-01（low）`ColdStartPolicy.usesCacheFirstPlaylist` 只有测试调用，无生产引用
- 任务说“固化规则，无代码行为变化”，因此可接受；但若声称“固化”，至少让读取 playlist 的代码调用它（或文档明确仅文档/测试规则），否则是死代码。低。

### PERF-01（low）`ZapTrace` 在 debug 关闭时仍每次 prepare/firstFrame 写 volatile
- “零开销”严格说不成立：`onPrepare`/`onFirstFrame` 无条件写 `@Volatile` 字段（两次/次换台），不是零；量级可忽略。若要严格零开销，用 `if (BuildConfig.DEBUG)` 门控或 `enabled` volatile 标志。

## 4. 真机/视觉未验证

- A/B 10 zaps 的 zap/prepare/firstFrame p50/p95、rebuffer；冷启首屏/首帧/epgCache；300ms 是否导致 rebuffer；EPG 首帧前更新是否可见；均归 acceptance2。本报告不宣称设备通过。

## 5. 放行判断

- **源码结论：needs_revision。** A2 结构/不变量/回退正确，B1 边界诚实；但 **ZAP-ONZAP-01（埋点未接线）** 使验收①无法执行，**EPG-COLLECT-01（收集器延后丢事件/无首帧无 EPG）** 是功能回归，**LOADCONTROL-TEST-01** 让生产 300 无真实 Builder 守卫。
- 建议顺序：**ZAP-ONZAP-01 → EPG-COLLECT-01（collector 立即 + 首帧 fallback）→ LOADCONTROL-TEST-01 → TEST/DOC/PERF**；重出 r27 pair 后真机 A/B。

## 6. 只读命令与终态

`git rev-parse HEAD`、`git status --short`、`git show --stat 5e7f0be9`、`git show 5e7f0be9`、`read ZapTrace.kt/ZapTraceTest.kt/PlayerActivity.kt/StreamPlayback.kt/LoadControlConfigTest.kt`、`Get-Content docs/ZAP_COLDSTART_OPTIMIZATION.md`。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 本报告完成后停止读取目标源码。
