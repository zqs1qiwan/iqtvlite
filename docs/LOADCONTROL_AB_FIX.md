# LOADCONTROL A/B FIX — live playback CPU regression (t157)

2026-11-21. Task t157 (developer2), on CI10105 source (after t156). **Only** the LoadControl buffer sizes changed, so the CPU effect is attributable to this one variable.

## The evidence (from acceptance, same channel, 60 s steady state)

Channel CCTV1, **multicast RTP progressive TS** (not HLS). CI10093 (pre-prefetch baseline) vs CI10101 (current):

| Metric | CI10093 | CI10101 | ratio |
|---|---:|---:|---:|
| app CPU p50 | 31.2 % | 44.2 % | 1.42× |
| `ExoPlayer:Loader` | 5.0 % | 16.9 % | 3.38× |
| `Playback` | 11.6 % | 18.1 % | 1.56× |
| codec | unchanged | unchanged | — |
| rebuffers | 0 | 0 | — |
| UI frames (idle) | 2 | 2 | — |

So: no re-decoding, no re-render, no rebuffering — the cost is the **loader waking up**. That is exactly the signature of an under-sized `minBufferMs` on a live feed: the buffer dips below the 1 s minimum, `shouldContinueLoading` turns the loader back on, and the playback thread re-evaluates each time.

This matches the read-only audit (`docs/PLAYER_PIPELINE_AUDIT.md`, suspect S1) that predicted the LoadControl values were the prime suspect and proposed 2500/6000/500/2000 as the A/B candidate.

## The change (one line of parameters, nothing else)

`PlayerActivity.initializePlayer()`:

```kotlin
// before (CI10101)
.setBufferDurationsMs(1_000, 6_000, 300, 1_000)
// after (CI10105+)
.setBufferDurationsMs(2_500, 6_000, 500, 2_000)
```

- `minBufferMs` **1 000 → 2 500**: the loader fills to 2.5 s and then idles instead of being re-armed by every jitter.
- `bufferForPlayback` 300 → **500**: a slightly larger start margin (still fast).
- `bufferForPlaybackAfterRebuffer` 1 000 → **2 000**: after a real stall the player waits for 2 s of buffer rather than oscillating.
- `maxBufferMs` unchanged at 6 000; `setBackBuffer(0, false)` and `setPrioritizeTimeOverSizeThresholds(true)` unchanged.

**Constraint kept**: `minBufferMs (2500) >= bufferForPlaybackAfterRebufferMs (2000)` and `>= bufferForPlaybackMs (500)`. The historical crash (CI10077, `800/6000/300/1000`) came from violating this; the JVM guard `LoadControlConfigTest` now asserts the production values *and* that min ≥ afterRebuffer, so a future edit fails in CI instead of on the TV.

## Risk / trade-off

- **Zap/cold start**: the player still starts after 500 ms of buffer, but the loader now targets 2.5 s, so a very slow source may take marginally longer to reach "playing" on the first frame. On the A/B feed the first frame was already dominated by network setup, not by the 300 ms start margin.
- **Memory**: `maxBufferMs` is unchanged (6 s), so the RAM ceiling does not move.
- **Live latency**: a larger minimum buffer adds up to ~1.5 s of buffered content, i.e. slightly more delay behind live. For a 1080p IPTV box this is the intended trade (the baseline CI10093 was ~1.5 s anyway).

## Verification protocol (acceptance, not claimed here)

1. Install the new pair, open **the same channel CCTV1** (progressive TS) and let it run **60 s undisturbed** (no drawer, no zapping, no EPG interaction).
2. `adb shell dumpsys cpuinfo | Select-String iqtvlite` → app CPU p50; and `top -H -p <pid>` sampled 5× for the thread split (`ExoPlayer:Loader`, `Playback`, codec).
3. Target: **app CPU back to ~31–35 %**, `Loader` back to single digits, `Playback` ~11–13 %, rebuffers still 0, UI frames still ≈0.
4. Also confirm no regression in: zap time to first frame (cold start), MP2 channel audio (extension renderer), 1080p playback smoothness, and that a real network stall still recovers (afterRebuffer 2 s).
5. If CPU is still >38 %, the next single-variable A/B candidates are **2000/6000/500/1500** or **3000/8000/500/2000** — change one set per build, keep this protocol identical.

## Verification (build gates)

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **79 tests 0 failures** (LoadControlConfigTest now 3); lint **0 errors**/68 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; APK 16,057,638 B ≤ 30 MB.
- CI pair receipt (run 44810654405, source `b8850420`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **79 tests 0 failures** (LoadControlConfigTest 3); lint 0 errors / 66 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |12,994,451| `8130bea6bef357d6d0750f1562df36a9f4d00f71846e1b3696912bac14297054` |
| app-debug-androidTest.apk |502,955| `6635b8ef15227ce41ce7d78253da590b583f220ca68a10297c1e2c5592a5e490` |

- Artifacts: pair `14667715492` (`sha256:5cf2ee05ed49909f84686181c353310f3a504177ff9b26d41de30e0c1d373e12`, zip 10,267,449 B); reports `14668760227` (`sha256:9fff839ac1e2d65591fcdd8b3569e1b5eae3a144f188695a2e9878e93f35db57`). Signer `96d6594ff31b99c8dc7dbe4816f9e1a50d409fc6bc076bdbd35032dc8b7ec343`.
- Version: `tv.iqtvlite.app.validation / 10106 / 2.2.0-tivimini-b8850420-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 12,994,451 B ≤ 30 MB.

## Not changed

DataSource, decoder/renderer factory (`EXTENSION_RENDERER_MODE_ON` for MP2), UI/Compose, zero-copy SurfaceView, channel keys, digit OSD, logo cache/prefetch, recent channels, EPG, SR, brand. Size ≤30 MB.
