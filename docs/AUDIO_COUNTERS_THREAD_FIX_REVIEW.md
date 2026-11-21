# 音频计数 Player 线程修复 复查（t169 / run10111）

任务 t170（attempt 1），designer2，2026-11-21（Asia/Hong_Kong）。**结论：源码层可接受（pass）**。t169 已把 60s 汇总任务从 `player.currentMediaItem` 改为 `PlaybackSession` 主线程快照；`Metrics.matches()` 也改用快照，后台协程不再触碰 Player；计数关闭路径不启动 job，默认/release 安全。**但静态守卫脚本有漏检模式**（`withContext(Dispatchers.Default) { … }`、`p.` 别名），测试只覆盖 PlaybackSession，建议补强；这些不改变当前源码无后台 Player 访问的结论。真机未验证。

> 只读固定 commit `661b2b0f`；HEAD `8b51170`（docs-only）。工作树有后续未提交修改（t142/t167 等），未读取/未评价；本报告基于固定 commit。未改代码、未跑 Gradle/CI/ADB、未操作 TV。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`；被审查 commit `661b2b0fe0cec3bb08e7d11bca32cd21f03035cd`。
- `git show --stat`：4 files +183/−3：`PlaybackSession.kt`、`PlaybackSessionTest.kt`、`StreamPlayback.kt`、`scripts/check_player_threading.py`；docs `AUDIO_COUNTERS_THREAD_FIX.md` 8b51170。
- pair：主 `13,010,835B` SHA `92ab44c5…77544`、test `502,955B` `12b28c5b…c8de`、run 44821763419（run10111）、10111；设备未验证。
- 关键：`PlaybackSession` 32 行；`StreamPlayback.Metrics.begin` L90–114、`matches` L115–121、`audioContext` L128、`audioSummaryJob` L105–109、`release` L36–43；`PlayerActivity` 的 Player 读取均在 `LaunchedEffect`/主线程回调；`AudioCostCounters` 纯原子/无 Player。

## 2. 通过项

### 1) 后台任务不再触碰 Player（CANCEL 根因修复）
- `Metrics.begin` L101：`PlaybackSession.begin(prepared.mediaId)`（`prepare()` 主线程）；60s job L105–109 只 `delay` + `PlaybackSession.isCurrent(session)` + `AudioCostCounters.format(...)`，**无 `player.`**。
- `Metrics.matches` L115–121：`PlaybackSession.isCurrent(session)` + `event.timeline`，不再 `player.currentMediaItem`；所有 `onAudioUnderrun/onAudioSinkError/onAudioDisabled/onAudioTrackInitialized/onTracksChanged` 的 `matches` 入口线程安全。
- `PlaybackSession`：`@Volatile currentId`；`begin/isCurrent/current/clear` 纯 Kotlin；release L39 `PlaybackSession.clear(it.mediaIdForSession())` 清理快照；旧 job 不会误报。

### 2) 计数关闭路径同样安全
- `Metrics.begin` L102 `if (AudioCostCounters.enabled)` 才 `reset`/启动 `audioSummaryJob`；关闭时无 job、无 `player` 访问。
- `AudioCostCounters` 所有钩子首行 `if (!enabled) return`；`matches` 不再读 player；默认 release 不崩。
- `AudioCostCounters.enabled = BuildConfig.DEBUG && Log.isLoggable("IQTV-AUDIO", DEBUG)`，仅 debug + tag；release false。

### 3) Player 访问线程审查
- **`StreamPlayback`**：`prepare/release` 在 `PlayerActivity` 主线程路径调用；`Metrics.audioContext` L128 的 `player.volume/playbackSuppressionReason/playWhenReady/audioSessionId` 只在 `AnalyticsListener` 回调且 `observe`（enabled+matches）时执行。**已核对 Media3 1.11.1 字节码：`ExoPlayerImpl` 以 `applicationLooper` 调 `AnalyticsCollector.setPlayer`，`DefaultAnalyticsCollector` 用该 looper 的 `ListenerSet` 分发** → `AnalyticsListener` 回调在 applicationLooper/main；`audioContext` 安全。
- **`PlayerActivity`**：`p.currentMediaItem` 出现在 `LaunchedEffect(Unit)`（默认 Main）的错误恢复循环、`p.currentPosition` 在 `LaunchedEffect` Main、`setOnCatchupSeek` 主线程回调；`scope.launch` 的 `playGroupVariant` 由 `rememberCoroutineScope`（Main）恢复；`withContext(Dispatchers.Default)` 的 `parsePlaylist` 不访问 player；`withContext(IO)` 的缓存读取不访问 player。
- **`AudioCostCounters`**：`onActiveChanged/onUnderrun/onRetry/onDecoder/onTrack/onCounters` 只操作 Atomic/volatile，无 Player。
- **`StreamPlayback.audioSummaryScope = Default`**：只读 `PlaybackSession`/`AudioCostCounters`，无 Player。
- 未发现 `Dispatchers.Default { ... player ... }` / `Dispatchers.IO { ... player ... }` / 无 Main 的 `launch/async` 内 Player 访问；`scripts/check_player_threading.py` 当前源码扫描 OK（依赖报告）。

### 4) 不回归 / 体积
- diff 仅 `PlaybackSession/StreamPlayback/PlaybackSessionTest/scripts`；未改 LoadControl、MP2 AAR、零拷贝 SurfaceView、flat UI/0dp、频道键、数字 OSD、台标缓存、最近观看、EPG、SR、品牌/app name。
- APK 13,010,835B（≈12.41 MiB）≤30MB；keep 210 identical / contract missing 0 / dex 7 OK（CI）；JVM 109 全绿（CI 报告）。

## 3. 低风险 / 建议（不阻断源码放行）

### GUARD-01（low）静态守卫漏检 `withContext(Dispatchers.Default)` 与 `p.` 别名
- `scripts/check_player_threading.py` 规则1 的正则 `Dispatchers\.(Default|IO)\s*\{` **不匹配** `withContext(Dispatchers.Default) {`（`Default` 后是 `)` 不是 `{`）；规则2 只匹配 `player.`，不匹配 `p.currentMediaItem` 等别名。当前源码无违规，但未来可能漏检。
- 建议：规则1 改为 `withContext\(\s*Dispatchers\.(Default|IO)\s*\)\s*\{` 或同时匹配；`PLAYER_ACCESS` 加入常见别名/`currentTracks`/`isPlaying`；`body_until_balanced` 的 80 行上限调大或按 AST/括号精确。
- 该脚本未在 CI 中自动执行（仅 docs 本地建议）；建议加 CI step。

### SESSION-01（low）`PlaybackSession.clear(mediaId)` 的 check-then-set 非原子
- `if (mediaId == null || currentId == mediaId) currentId = null`：若 begin 与 clear 并发，可能清掉刚 begin 的新会话。当前 `begin`/`release` 都在主线程，实际序列化；若未来从其他线程调用，建议 `synchronized`/`AtomicReference.compareAndSet`，或在 `clear` 注释明确仅主线程。

### TEST-01（low）测试只覆盖 PlaybackSession，未覆盖 Metrics 钩子/关闭路径
- `PlaybackSessionTest` 5 项覆盖 begin/非当前/clear/stale/首次前；没有：
  - 计数关闭时 `Metrics.begin` 不启动 job（可通过测试钩子/静态断言）；
  - `matches()` 不访问 player（纯逻辑）；
  - `AudioCostCounters` 钩子线程安全/disabled 短路；
  - 真实 `AnalyticsListener` 回调线程（instrumented）。
- 建议补纯 JVM 测试：`AudioCostCounters` disabled 全短路、`PlaybackSession` 并发 begin/clear（若改原子）、`check_player_threading.py` 负向控制纳入 CI。

### STATIC-02（low）`PLAYER_ACCESS` 覆盖面
- 当前正则包含 `currentMediaItem/currentPosition/playbackState/playWhenReady/volume/isPlaying/duration/...`，但未含 `currentTracks`、`isCurrentMediaItemSeekable`、`audioSessionId` 等；`audioContext` 的 `audioSessionId` 只在主线程回调，安全；守卫可补全。

### INFO-01（info）`audioContext` 的 Player 读取
- 该函数仍读 `player.volume/playbackSuppressionReason/playWhenReady/audioSessionId`，但仅在 `AnalyticsListener` 回调（applicationLooper）且 debug enabled 时执行；**不是后台协程**。建议保留注释/文档说明这一前提，避免未来把 `audioContext` 移到后台。

## 4. 真机/视觉未验证

- 计数关闭播 CCTV-1 ≥2min 无 FATAL/wrong thread；`setprop log.tag.IQTV-AUDIO DEBUG` 后 60s 仅一行 IQTV-AUDIO 且不崩；armed 连续切台不崩；t167 清空硬断言——均归 acceptance2。本报告不宣称设备通过。
- 真机若仍崩，优先抓 `FATAL EXCEPTION` 线程名/堆栈；若线程名 `DefaultDispatcher` 且栈含 `StreamPlayback$Metrics`，说明仍有未覆盖的后台 Player 访问。

## 5. 放行判断

- **源码结论：pass**。后台汇总任务已完全脱离 Player；`Metrics.matches`/计数钩子用主线程快照；关闭/默认/release 路径安全；当前源码无 Default/IO 直接 Player。
- 建议修复 GUARD-01/SESSION-01/TEST-01（low）后再合并，但不阻断本轮源码放行；真机由 acceptance2 验证。

## 6. 只读命令与终态

`git rev-parse HEAD`、`git status --short`、`git show --stat 661b2b0f`、`git show 661b2b0f`、`read StreamPlayback.kt/PlaybackSession.kt/PlaybackSessionTest.kt/AudioCostCounters.kt`、`read scripts/check_player_threading.py`、`javap` Media3 `ExoPlayerImpl/DefaultAnalyticsCollector` 核对 applicationLooper。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 工作树后续修改未读取/未评价；本报告完成后停止读取目标源码。
