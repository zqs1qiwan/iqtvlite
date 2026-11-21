# 首帧看门狗复查（t225 / run10134）

任务 t226（attempt 1），designer2，2026-11-21（Asia/Hong_Kong）。**结论：needs_revision。** `FirstFrameWatchdogPolicy` 的 8s/1 次、RETRY→GIVE_UP 后 inert、代际化、超时可注入、release/首帧/换台取消的纯策略方向正确；`StreamPlayback` 在 prepare 末尾 arm、8s 超时 → 同 media item seekToDefaultPosition+prepare+play、第二次 8s Toast、日志门控、Main 线程也都对。但接入层有**两个会破坏验收/安全的缺陷**：

1. **重试成功后看门狗不会取消**：`Metrics.onRenderedFirstFrame` 先 `if (firstFrame) return`，而 `onFirstFrameForWatchdog` 在 `firstFrame=true` 之后才调用；retry 不调用 `Metrics.begin`，`firstFrame` 仍为 true。即使重试后再次渲染首帧，回调也在 `firstFrame` 早退处被吞掉，第二计时器必然 GIVE_UP/Toast（“该频道暂不可播”），即“重试成功仍报失败”。
2. **release 后定时器仍访问已释放 player**：`release()` 只 `watchdogs.remove(player)?.cancel()`，没有取消 `watchdogScope` 的 Job；8s/16s 定时器醒来先读 `player.playWhenReady`，再判断 `armed`。Activity 退出/换播放器后可能 use-after-release；另外旧代际 timer 在新频道暂停时会 `watchdog.cancel()` **误杀新代际**（未先校验 gen）。

> 只读固定 commit `ffaff4ed`（t225 两个提交 `bc0cd0f`+`ffaff4ed`）；HEAD `74b9afb`（docs-only）。工作树 clean。未改代码、未跑 Gradle/CI/ADB、未操作 TV。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`；被审查 final commit `ffaff4ede72d9bc386de99fbe5601b57c3e4aed9`（功能首版 `bc0cd0f`）。
- `git diff --stat ed1e06f..ffaff4ed`：3 files +236：`FirstFrameWatchdogPolicy.kt`（101 行）、`FirstFrameWatchdogPolicyTest.kt`（74 行）、`StreamPlayback.kt`（+61）；docs `FIRSTFRAME_WATCHDOG.md` 74b9afb。
- pair：主 `13,092,813B` SHA `829687d8…de99d4`、test `502,955B` `e361b6ac…3b09ccf2`、run 44870898798（run10134）、10134；设备未验证。
- 关键行：`StreamPlayback` release L40、prepare L83–89、armWatchdog L92–154、onFirstFrameForWatchdog L157、Metrics.onRenderedFirstFrame L292–296、begin firstFrame=false；`FirstFrameWatchdogPolicy` 101 行；`PlayerActivity` 未改。

## 2. 已通过/正确部分

### 策略 — pass
- `TIMEOUT_MS=8_000`、`MAX_RETRIES=1`；`Watchdog(timeoutMs,maxRetries)` 可注入；`arm()` generation++/retries=0/armed=true；`onTimeout(gen)`：
  - `!armed` → IGNORE；
  - `gen != generation` → IGNORE（旧 timer inert）；
  - `retries<maxRetries` → retries++/RETRY；
  - 否则 armed=false/GIVE_UP；再次 onTimeout → IGNORE。
- `onFirstFrame/cancel` armed=false；`reset` generation++/clear；`retryLine`/`GIVE_UP_MESSAGE` 稳定；`FirstFrameWatchdogPolicyTest` 6 项覆盖 8s/1 次、RETRY→GIVE_UP→inert、首帧取消、新 zap 旧 timer IGNORE、cancel stale、日志文案。JVM 203→209（CI 209 0 失败）。

### 接入 — 部分 pass
- `StreamPlayback.prepare` L85–89 `player.prepare(); player.play(); armWatchdog(player,context,channelId)`；arm 在 prepare 末尾。
- `armWatchdog`：`watchdogs.getOrPut(player)`；`watchdog.arm("$channelId")`；`watchdogScope.launch(Dispatchers.Main)` delay(timeout)；`!player.playWhenReady` 取消；`RETRY` 时 log（`IQTV-WATCHDOG` DEBUG gate）→ `seekToDefaultPosition()+prepare()+play()`（同一 media item、不新建 MediaSource、不 zap re-arm）；再 delay 8s → `playWhenReady && onTimeout(gen)==GIVE_UP` Toast；`GIVE_UP` Toast；`IGNORE` no-op。
- `onFirstFrameForWatchdog` 由 `Metrics.onRenderedFirstFrame` 调用（但顺序问题见 §3）。
- `release` L40 `watchdogs.remove(player)?.cancel()`；`Metrics.begin` L? `firstFrame=false`；`PlayerActivity` 无改动。
- 计时器在 `Dispatchers.Main`，`player.*` 主线程；`check_player_threading` 报告 OK；日志 `Log.isLoggable` gate。**接入层缺陷见 §3。**

### 无回归/体积 — pass
- diff 仅 3 文件；未改 F1/F3/F4、LoadControl tiers、MP2、零拷贝、0dp、频道键、数字 OSD、台标、最近、EPG、SR、品牌；APK 13,092,813B≤30MB；三守卫报告 OK。
- 默认启用看门狗是产品行为变化，文档已声明；对健康频道 8s 阈值无影响。

## 3. 阻断/需修复

### WATCHDOG-RETRY-01（high）重试成功后看门狗仍 GIVE_UP/Toast

**证据**：
- `StreamPlayback.Metrics.onRenderedFirstFrame` L292–296：
  ```kotlin
  if (!matches(eventTime) || firstFrame) return
  onFirstFrameForWatchdog(player)
  firstFrame = true
  emit(...)
  ```
  即 `firstFrame` 已 true 时**直接 return，不再调用** `onFirstFrameForWatchdog`。
- `armWatchdog` retry 只 `player.seekToDefaultPosition(); player.prepare(); player.play()`，**没有调用 `Metrics.begin`**；所以 `firstFrame` 仍是 true。
- 重试成功（同一 media item 重新渲染首帧）时 `onRenderedFirstFrame` 若再次回调，也会在 `firstFrame` 早退；`watchdog` 仍 armed，第二计时器 8s 后 `onTimeout(gen)` retries=1 → GIVE_UP → Toast「该频道暂不可播」。用户看到“重试成功却提示不可播”，且没有第二次取消路径。
- 若重试本身没有再触发 `onRenderedFirstFrame`（Media3 可能 per media item 只回调一次），同样必然 Toast；需要独立成功判据。

**修复**：
- 把 `onFirstFrameForWatchdog(player)` 放在 `firstFrame` 早退**之前**（但保留 `matches` 检查）；或给 Metrics 增加 `onRetryStarted()` 重置 `firstFrame=false`，并在 retry 调用；或让 watchdog 监听 `onPlaybackStateChanged(STATE_READY)`/`onIsPlayingChanged` 作为 retry 成功判据。
- 加 `armWatchdog` retry 后显式 `watchdog.retryStarted()`/`onFirstFrame` 路径测试；设备验“重试成功不 Toast”。

### WATCHDOG-RELEASE-01（high/medium）release 后计时器仍访问已释放 player；旧计时器可误杀新代际

**证据**：
- `release` L40 `watchdogs.remove(player)?.cancel()`；**没有取消 `watchdogScope` 的 Job**（scope 全局，Job 未记录）。
- 第一计时器 L?：`delay(8s); if (!player.playWhenReady) { watchdog.cancel(); return }` —— 先访问 `player`，后看 armed；release 后 player 已 released，属 use-after-release/潜在 IllegalStateException；随后仍可能读取。
- 第二计时器 `if (player.playWhenReady && watchdog.onTimeout(gen)==GIVE_UP)` 同样先访问 player；release 后仍执行。
- **旧代际 timer 误杀新代际**：channel A timer(gen1) 未取消；用户 zap B `arm(gen2)`；B 暂停（playWhenReady=false）；A timer 醒来先 `!player.playWhenReady` → `watchdog.cancel()` **取消 gen2**，B 永远不重试。这是未先校验 gen/armed 的直接后果。
- `cancel()` 只 armed=false，不 generation++；虽然新 arm 会++，但上述旧 timer 在 cancel 前已读 player。

**修复**：
- `watchdogs` 存 `(Watchdog, Job)`（或 `Watchdog` 内 cancel 时取消 job）；`release` 先 `job.cancel()`，再 cancel policy。
- 计时器第一行：`if (!watchdog.isCurrent(gen)) return`（新增 `isCurrent`），**再**读 `player.playWhenReady`；`isCurrent` 可在 policy 内检查 `armed && gen==generation`。
- pause cancel 仅在 `watchdog.isCurrent(gen)` 时执行；旧 timer 不取消新代际。
- `cancel()` 增加 generation++（或 `invalidate()`）更稳。
- 测试：release 后 timer 不访问 player；A timer 在 B arm/pause 后不取消 B；用 fake clock/job 或 Robolectric。

### WATCHDOG-ARM-01（low/medium）arm 在 `player.play()` 之后，快速首帧可能漏掉

**证据**：`StreamPlayback.prepare` L85–89 `player.prepare(); player.play(); armWatchdog(...)`。健康/本地频道首帧可能在 arm 前已回调 `onRenderedFirstFrame`，此时 `onFirstFrameForWatchdog` 无 watchdog（或旧），arm 后无事件 → 8s 后误 retry/Toast。
**修复**：在 `player.prepare()` 前/同时 `armWatchdog`（或在 `player.play()` 前），再触发 prepare/play；保持“prepare 末尾 arm”可接受的低风险路径也可，但需处理 fast-first-frame；加测试。

### TEST-01（medium）只有纯策略 6 项，无接入层 retry/release/pause 测试

**证据**：`FirstFrameWatchdogPolicyTest` 6 项全部 Watchdog 纯类；没有：
- retry 成功后第二次 `onRenderedFirstFrame` 取消；
- `release()` 后 timer 不访问 player / 不误杀新代际；
- pause/rebuffer 行为；
- `armWatchdog` 的 main 线程、no re-arm zap、同 media item；
- Toast 只一次。
JVM 209 全绿不能捕获 WATCHDOG-RETRY-01/RELEASE-01。

**修复**：把 `StreamPlayback` 的 watchdog 接入抽成可注入 `WatchdogRunner`（player 接口/clock/scope），JVM/Robolectric 测 retry→firstFrame→cancel、release→cancel job、A/B generation、pause、Toast once；设备 acceptance 按文档。

### DOC-01（low）文档“首帧取消”在 retry 场景不成立
- `FIRSTFRAME_WATCHDOG.md` 行为 4 写“first frame (`onRenderedFirstFrame`) cancels”；实际 retry 后 Metrics `firstFrame` 早退，取消不到。修代码或改文档为“仅首次首帧；retry 需额外成功判据（待修）”。
- 文档“overlay does not pause playback, watchdog may retry underneath”可接受；但“重试成功”判据缺失需说明。

## 4. 真机/视觉未验证

- 睛彩 117/118/119 8s 一条 retry、16s 一次 Toast；健康频道无 watchdog；8s 内换台旧频道不重试；暂停不重试；retry 不产生第二条 IQTV-ZAP；均归 acceptance2。本报告不宣称设备通过。

## 5. 放行判断

- **源码结论：needs_revision。** 纯策略/代际/8s/1 次/同 item retry/主线程方向正确；但 **WATCHDOG-RETRY-01（重试成功仍 Toast）** 与 **WATCHDOG-RELEASE-01（release 后访问/误杀新代际）** 是明确缺陷，WATCHDOG-ARM-01/TEST-01 需补。
- 建议顺序：**WATCHDOG-RETRY-01（首帧取消判据）→ WATCHDOG-RELEASE-01（job/current gen）→ WATCHDOG-ARM-01 → TEST-01 → DOC**；重出 r27 pair 后真机验收。

## 6. 只读命令与终态

`git rev-parse HEAD`、`git status --short`、`git diff --stat ed1e06f..ffaff4ed`、`git show bc0cd0f`、`git show ffaff4ed`、`read FirstFrameWatchdogPolicy.kt/FirstFrameWatchdogPolicyTest.kt/StreamPlayback.kt`、`Get-Content docs/FIRSTFRAME_WATCHDOG.md`。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 本报告完成后停止读取目标源码。
