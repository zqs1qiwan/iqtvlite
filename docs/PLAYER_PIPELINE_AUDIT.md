# PLAYER PIPELINE AUDIT — idle-playback CPU (t152, read-only)

2026-11-21. Scope: **read-only audit, no code change, no new pair** (per captain's re-scope: the actual fix waits for the CI10093 vs CI10101 same-channel A/B result). Source: `integration/tivimini-baseline` @ `4d4f054` (CI10103).

Observed by acceptance: AAC idle playback **p50 87.2 %** app CPU (CI10101) vs **46.6 %** (CI10093); `top -H` threads: main 88.5 %, **ExoPlayer:Loader 45.7 %**, Playback ~20 %, codec ~7 %. Follow-up sampling by the captain showed **idle main thread 0.5 % and ~0 UI frames**, so "main 88.5 %" was a `top -H` instant misread; the real cost is the **media pipeline (Loader + Playback)**.

## 1. Current configuration (as built)

| Item | Value | Where |
|---|---|---|
| Player | `ExoPlayer.Builder(context)` — **created once** (`initializePlayer()`, guarded by `if (player != null) return`) | PlayerActivity.kt L489-532 |
| LoadControl | `DefaultLoadControl` `setBufferDurationsMs(**1_000, 6_000, 300, 1_000**)`, `setBackBuffer(0,false)`, `setPrioritizeTimeOverSizeThresholds(true)` | L493-506 |
| Renderers | `DefaultRenderersFactory` + `setEnableDecoderFallback(true)` + `EXTENSION_RENDERER_MODE_ON` | L508-514 |
| Data source | `OkHttpDataSource.Factory(NetUtil.ipv4OkHttpClient)` → wrapped in `DefaultDataSource.Factory` | StreamPlayback.kt L47-51 |
| HTTP client | `OkHttpClient.Builder().dns(ipv4FirstDns).addInterceptor(UA)`, **no explicit timeouts / connection-pool tuning → OkHttp defaults** (5 idle connections, 5 min keep-alive, connect/read 10 s) | NetUtil.kt |
| Extractors | `DefaultExtractorsFactory().setTsSubtitleFormats(cea608).setTsExtractorTimestampSearchBytes(1_000_000)` | StreamPlayback.kt L50 |
| Prepare | `factory.createMediaSource(item)` + `player.prepare()` + `player.play()` — **only** from `StreamPlayback.prepare()`, called on channel switch / playlist commit | StreamPlayback.kt L56-58 |
| IDs | Media id `c<id>-l<urlHash>-s<seq>` (one per prepare) | StreamPlayback.kt L43 |

## 2. Does UI/EPG state re-prepare the player? — **No** (evidence)

- `player` is `var player by remember { mutableStateOf<ExoPlayer?>(null) }` and is **never re-created** on state change; `initializePlayer()` early-returns when non-null.
- The only `player.prepare()` call sites are `StreamPlayback.prepare(...)` (channel switch, source-variant switch, playlist commit `policy.prepare`), plus the catchup retry path.
- `recentTick`, `recentChannelsOpen`, logo cache state, `nowMillis` and `epgData` are **not** in any `DisposableEffect(player…)` key or in `initializePlayer`'s path, so they cannot trigger `setMediaItem`/`prepare`.
- `commitPlaylist` only prepares when `PlaybackIntent` returns a `policy.prepare` (initial / recovery), not on every playlist/EPG update.

**Conclusion: no re-prepare or seek on EPG/logo/recent updates.** This rules out suspicion #1 of the task.

## 3. Suspects, ranked, with evidence

### S1 (highest) — LoadControl tuning for short-segment live TS
`minBufferMs = 1_000`, `bufferForPlayback = 300`, `bufferForPlaybackAfterRebuffer = 1_000`, max 6 000.
For a progressive live TS the loader fills to `maxBufferMs` and then idles, but `minBufferMs = 1 s` makes ExoPlayer call `shouldContinueLoading` with a very small margin: on a jittery IPTV feed the buffer repeatedly dips under 1 s → `Loader` wakes, re-reads, and the playback thread re-evaluates (`Playback` 20 %). Combined with `setPrioritizeTimeOverSizeThresholds(true)` (time-based, byte cap passive) a short-bitrate stream can also bounce between loading and idle.
Evidence: configuration only (no A/B yet). **Recommendation (do not apply yet):** A/B `minBufferMs = 2_000 … 3_000` with `bufferForPlayback = 500`, `afterRebuffer = 2_000`, keep max 6 000. This trades ≤1 s extra zap latency for fewer loader wake-ups.

### S2 (medium) — 30 s clock tick recomputes the EPG map for every group
`LaunchedEffect(Unit) { while (true) { nowMillis = …; delay(30_000) } }` (L827-833) → `LaunchedEffect(epgData, nowMillis, channelGroups, selectedGroup)` recomputes `nowProgramByUrl` for **all** visible groups on `Dispatchers.Default`, then recomposes the drawer/banner inputs.
Cost: a burst every 30 s, **not continuous** — consistent with 0 UI frames when idle, but it can add jitter on a weak v2 box. **Recommendation:** tick only while the drawer/banner/EPG panel is visible, or drop to 60 s and skip when `epgData == null`.

### S3 (medium) — EPG refresh loop wakes every 10 s
`while (true) { … if (!shouldRefreshEpg && now-last < 5 min) { delay(10_000); continue } … delay(600_000) }` (L789-825). The loop itself is cheap (no I/O), but it keeps a coroutine on the main dispatcher waking 6×/min and reads `Prefs` on refresh days. Network refresh happens at most every 5 min / on a new day / after 6 h — and **only** if `shouldRefreshEpg`, a date change or 6 h elapsed, so it is not a "network every minute" loop.
**Recommendation:** keep behaviour, but move the idle wait to 30 s and check `no EPG configured` early-exit.

### S4 (low) — startup prefetch burst (already bounded, but it is the *only* heavy thing right after start)
`commitPlaylist` waits for `heavyWorkEnabled` (1.5 s after start), then `LogoLoader.prefetch(first 60 @ drawer size)` and 800 ms later the same set at banner size. Under the t137/t145 gates this is 2-wide and short; it should not explain a *steady-state* 42 % Loader, but it does explain a **startup spike** in a 10 s idle window measured right after launch. Acceptance should measure after ±30 s of steady playback.

### S5 (low) — OkHttp / DataSource
- `OkHttpDataSource` **is** used (not `DefaultHttpDataSource`), so connection reuse/keep-alive is in place (OkHttp default pool).
- No explicit `connectTimeout/readTimeout` → 10 s defaults; a stalled segment can hold a connection open for 10 s, which looks like "Loader busy".
- **Recommendation:** set explicit `connectTimeout(5s)` / `readTimeout(10s)` and `retryOnConnectionFailure(true)` for the stream client; add `callTimeout` only for EPG.
- The factory is re-created per prepare (cheap, shares one `OkHttpClient`), so **no per-segment client creation**.

### S6 (informational) — `EXTENSION_RENDERER_MODE_ON`
With `MODE_ON`, `DefaultRenderersFactory` builds the FFmpeg extension renderer **only after** the platform renderer rejects a track (`EXTENSION_RENDERER_MODE_ON` = use extensions only when platform decoders can't handle the format). AAC on this box is handled by MediaCodec, so the extension is **not** in the steady-state path; it is probed once per unsupported track (one-time cost, visible as a single `audio_format`/`audio_decoder` pair in IQTV-METRICS logs).
**Verification:** check `adb logcat -s IQTV-METRICS` for `audio_decoder name=…` — `ffmpeg` only when the platform fails.

### S7 (informational) — stream flavour
`DefaultMediaSourceFactory` + `DefaultDataSource` classify by URI/Content-Type: `.m3u8` → HLS, `.ts/flv/mp4` → progressive. The test source `https://iptv.laobaitv.net` serves progressive TS/FLV style URLs for most channels, but this **must be confirmed from the device** (`formats=` in the `state`/`format` IQTV-METRICS lines). If a channel is HLS with 2 s segments, a Loader share of 40 % can be normal for live playback and the fix target should be channel-dependent.

## 4. What is NOT a cause (checked and excluded)

- No `while(true)` loop touching the player or network in the steady state other than S2/S3 (EPG loop + clock).
- `SourceLatencyProber` probes only on demand (drawer open / source switch), cache TTL 10 min — no periodic probing.
- `BackgroundSourceUpdater` does one startup playlist refresh and one EPG refresh (delays 0/1.5 s) — no repeating timer.
- Logo prefetch filler is inside `LaunchedEffect(visible, …)` and cancelled when the drawer closes (t137), so it is inactive during idle playback.
- Recent-channels debounce writes only on channel switch (400 ms), decode is ≤10 short rows.
- Main thread idle measured at 0.5 % with 0 UI frames (t151) — no Compose invalidation loop.

## 5. Proposed A/B / instrumentation (for the fix task, not applied here)

1. **Before/after matrix**: CI10093 (baseline) vs CI10101 (current) on the *same* channel, 60 s steady state, `dumpsys cpuinfo` + `top -H` sampled 5×, plus `adb shell dumpsys gfxinfo` frame stats.
2. **LoadControl A/B**: 1000/6000/300/1000 (current) vs 2500/6000/500/2000, same channel; compare Loader thread share and rebuffer count (`state rebuffers=` in IQTV-METRICS).
3. **Confirm E1**: `formats=`/`mime=` line for the channel (HLS vs TS) and `audio_decoder` name (MediaCodec vs ffmpeg).
4. **Confirm S2/S3**: log the 30 s tick and the 10 s EPG wake; measure CPU with the clock tick paused (temporary debug flag) to bound its contribution.
5. Only then pick the fix; keep `setBufferDurationsMs` ≥ `bufferForPlaybackAfterRebuffer` (the 800/…/1000 combination crashed in CI10077).

## 6. Verdict

- No re-prepare/seek triggered by UI or EPG state (the task's suspicion #1 is **not** the cause).
- The dominant suspect is **LoadControl sizing for a short-buffer live TS feed** plus normal live-loader work; the remaining UI/EPG loops are periodic small bursts, not continuous.
- Nothing changed in this audit: no code edit, no new pair, size/logic untouched. Awaiting the same-channel A/B before touching `DefaultLoadControl` or the DataSource timeouts.
