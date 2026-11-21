# ZAP & COLD-START OPTIMIZATION (t197)

2026-11-21. Task t197 (developer2), on CI10121 source (after t193). **Measurement first, one variable at a time, always revertible.** Device A/B execution belongs to acceptance2 — this document defines the arms and the instrumentation; no device result is claimed here.

## A. Zap TTFF

### A1 — TTFF stopwatch (measurement, no behaviour change)

New **`ZapTrace`** (pure Kotlin, unit-tested):

| call site | when |
|---|---|
| `ZapTrace.onZap(now)` | a channel change is requested (available for the acceptance harness) |
| `ZapTrace.onPrepare(now)` | right before `player.prepare()` in `StreamPlayback` |
| `ZapTrace.onFirstFrame(now)` | `onRenderedFirstFrame` in `PlayerActivity` |

It reports three numbers, which is exactly the split the task asked for:

```
zap=780ms prepare=120ms firstFrame=660ms
```

- `zap` = what the user perceives (user action → first frame),
- `prepare` = playlist/format/decoder setup before the network work,
- `firstFrame` = network + decode + first present.

The log line is **debug-gated**: `adb shell setprop log.tag.IQTV-ZAP DEBUG` then `adb logcat -s IQTV-ZAP`. Nothing is logged (and nothing costs money) in release.

### A2 — `playbackBufferMin` 500 → 300 (single variable)

All four LoadControl numbers now live in **`LoadControlTuning`**, so an arm is one constant:

| parameter | CI10121 (before) | t197 arm |
|---|---:|---:|
| minBufferMs | 2 500 | 2 500 *(unchanged — t157 CPU winner)* |
| maxBufferMs | 6 000 | 6 000 |
| **playbackBufferMinMs** | **500** | **300** |
| bufferForPlaybackAfterRebufferMs | 2 000 | 2 000 |

Rationale: playback starts as soon as 300 ms are buffered instead of 500 ms, which typically removes ~100–200 ms of the TTFF without touching the loader's margin (`minBuffer` stays 2 500 so the loader still fills and idles — the t157 CPU win is preserved).

**Invariant kept** (USAB2-01 / CI10077): `minBufferMs ≥ bufferForPlaybackAfterRebufferMs` (2 500 ≥ 2 000) and `playbackBufferMin < bufferForPlayback` (300 < 500). `LoadControlTuning.isValid` encodes it and a JVM test asserts it.

**A/B table (to be filled by acceptance on device, 10 zaps each):**

| arm | p50 TTFF | p95 TTFF | rebuffers |
|---|---:|---:|---:|
| A — CI10121 baseline (bufferMin 500) | 996 ms | 1 317 ms | 0 |
| B — t197 (bufferMin 300) | _pending_ | _pending_ | _pending_ |

Target: p50 ≤ 700 ms **with no rebuffer increase**. If B does not reach it, the fallback is documented below.

### A3 — neighbour pre-connect: **deferred, not shipped**

A pre-connect (open the TCP/TLS session for the next channel so the TLS/DNS handshake is already done) is the obvious next lever, but it was **not** implemented in this task because:

- it needs a network seam that does not exist yet (`StreamPlayback` builds `MediaSource`s directly; there is no injectable HTTP client for the manifest/segment pre-warm),
- a wrong pre-connect on a live TS stream can hold a second socket open indefinitely on this 512 MB-class box, which is exactly the resource profile we must protect,
- it cannot be measured honestly in this task (device A/B is acceptance2's), so shipping it would be an unmeasured regression risk.

Rollback is therefore trivial (nothing to roll back). The documented next step is: add `ZapPreconnect` behind `Prefs.zapPreconnectEnabled` (default **off**), open/close a socket on the *next* channel's host with a 2 s cap, and A/B it with the same 10-zap harness.

## B. Cold start (~14 s on the device, dominated by playlist + EPG reload)

### B1 — EPG cache read deferred to the first frame

Before: `LaunchedEffect(Unit)` parsed the XMLTV cache **before** the first frame, competing with playlist load and decoder init.

Now: the cache read runs in `LaunchedEffect(firstFrameRendered)`, gated by `ColdStartPolicy.mayLoadEpgCache(firstFrameRendered)`; `firstFrameRendered` is set in `onRenderedFirstFrame`. The `epgUpdated` collector still starts immediately (it is a cold Flow with no work until data arrives), so a background refresh is never missed.

`ColdStartPolicy` (pure, tested) records the rules:
- `EPG_AFTER_FIRST_FRAME = true`,
- `usesCacheFirstPlaylist(cachedAvailable)` — the cached playlist is what the first screen shows.

### B2 — cache-first playlist

The player already opens the **cached playlist** (Prefs + `PlaylistCache`) and only replaces it when `AutoUpdateScheduler` / `BackgroundSourceUpdater` delivers fresh data; the startup network pass is additionally delayed 1.5 s (t176). No change was needed here beyond recording the policy, and no flash/duplicate jump is introduced because the cached list is the first list the UI ever composes.

### B3 — startup refresh timing

Unchanged at 1 500 ms (t176). With the EPG parse moved behind the first frame, the remaining contender for the first frame is the playlist refresh; the next measurement step is to raise the startup delay only if the acceptance A/B shows the playlist refresh still landing inside the first-frame window.

**Cold-start before/after (to be filled by acceptance, full cold start):**

| metric | CI10121 | t197 |
|---|---:|---:|
| 首屏可操作 | ~14 s (reported) | _pending_ |
| 首帧 | _pending_ | _pending_ |
| `epgCache=…ms` (`IQTV-ZAP`) | n/a | _pending_ |

## Risks

| risk | mitigation |
|---|---|
| 300 ms playback start may underrun on a slow segment | rollback = one constant (`PLAYBACK_BUFFER_MIN_MS = 500`); `minBufferMs` unchanged; rebuffer count is part of the A/B |
| EPG appears a moment later on a cold start | it is only the *cache read* that moved (~tens of ms to a few hundred ms); the first screen never needed EPG, and the channel banner shows the programme once loaded |
| debug logging left on | gated by `Log.isLoggable`, default off, no disk writes |

## Rollback

- A2: set `LoadControlTuning.PLAYBACK_BUFFER_MIN_MS = 500` (one line, documented).
- A1: `ZapTrace` is inert unless called; the calls can be removed without touching behaviour.
- B1: delete the `firstFrameRendered` gate → the original `LaunchedEffect(Unit)` ordering returns.

## Preserved

F1 remote habits, F3 auto-update, F4 audio UI, LoadControl **2 500/6 000/300/2 000** (only `playbackBufferMin` changed), MP2 FFmpeg extension, zero-copy SurfaceView, flat UI, channel keys, digit OSD, logo cache, recent channels, EPG, SR, brand. Size ≤30 MB.

## Tests

`ZapTraceTest` (**6**, pure JVM): the zap/prepare/first-frame split and its summary line; an incomplete measurement reports nothing; a new zap resets the previous numbers; the LoadControl invariant with the new arm; EPG cache not readable before the first frame; cache-first playlist rule.

JVM total **163 → 169**.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **169 tests 0 failures**; lint **0 errors**/86 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` / `check_refresh_paths` / `check_settings_scroll` all OK; APK 16,125,188 B ≤ 30 MB.
- CI pair receipt (run 44845259619, source `5e7f0be9`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **169 tests 0 failures** (ZapTraceTest 6); lint 0 errors / 84 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,059,987| `631f885ea744abd19396bfb854014777debdd6e3871790985d3615b640a08f25` |
| app-debug-androidTest.apk |502,955| `61db6b5af8f3c8e0fd8dfa57d834cb8483d5f1f3df2f83e67047179de9f93bd7` |

- Artifacts: pair `14682548087` (`sha256:3a3f8a6bef65803d1e5b5cb56182c9a5d7f3816b45f3199f47b17805930b16bc`, zip 10,332,834 B); reports `14683086946` (`sha256:f3082649f82457ebf0b9a364b30305e639ed9db2ccdd63fe98bf8bd8bf7f8a04`). Signer `3cdfc8802ff9499da69c9fd2534711324f092d3192280f7054661be6380be605`.
- Version: `tv.iqtvlite.app.validation / 10122 / 2.2.0-tivimini-5e7f0be9-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,059,987 B ≤ 30 MB.

## Device protocol (acceptance, not claimed here)

1. `adb shell setprop log.tag.IQTV-ZAP DEBUG`, zap 10 times: each line shows `zap/prepare/firstFrame`; record p50/p95 for arm B and compare with the CI10121 baseline (996/1317 ms).
2. Watch rebuffers during the same 10 zaps and a 2-minute steady play: must not increase.
3. Full cold start (force-stop + clear from recents): record the time until the first screen is operable and until the first frame; the `epgCache=…ms` line must appear **after** the first frame.
4. Channel banner/subtitle still shows EPG once the cache line appears; no flash, no duplicate jump.
5. If B regresses (rebuffers or TTFF), flip `PLAYBACK_BUFFER_MIN_MS` back to 500 and re-run.
