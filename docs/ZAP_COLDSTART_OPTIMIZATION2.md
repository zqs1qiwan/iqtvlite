# ZAP & COLD-START 2 — wiring fixes after t200 review (t201)

2026-11-21. Task t201 (developer2), on CI10122 source (after t197). Fixes the two blockers the t200 review found in the t197 instrumentation work. Device A/B belongs to acceptance2 (t203) — **no performance claim is made here.**

## ZAP-ONZAP-01 (high) — `ZapTrace` is now armed by production code

**Symptom (device, CI10122):** 10 zaps produced **0** `IQTV-ZAP` lines. The stopwatch was only *completed* (`onPrepare`/`onFirstFrame`) but never *armed*: no production call to `ZapTrace.onZap`, so `zapAt == 0` and `summaryLine()` returned null forever.

**Fix:** `StreamPlayback` arms it on the real preparation path, once per channel change:

```kotlin
ZapTrace.onZapIfChanged(id, SystemClock.elapsedRealtime())   // id == MediaItem.mediaId
ZapTrace.onPrepare(SystemClock.elapsedRealtime())
player.prepare()
```

`onZapIfChanged(key, now)` re-arms **only when the media identity changes**, so a re-prepare for the same channel (layout change, retry, audio re-select, surface recreate) keeps the running measurement instead of wiping it. Verified by `theStopwatchIsArmedOncePerRealZap` and `theProductionArmProducesASummaryLine` (the latter asserts the exact line `zap=765ms prepare=150ms firstFrame=615ms`).

Net effect: `adb shell setprop log.tag.IQTV-ZAP DEBUG` + `adb logcat -s IQTV-ZAP` now shows one `zap=…` line per channel change.

## EPG-COLLECT-01 (high) — collectors back on the first composition

**Symptom:** in t197 both `epgUpdated.collect` and `manualEpgRequests.collect` were moved inside `LaunchedEffect(firstFrameRendered)`. Those are cold `SharedFlow`s **without replay**, so every event emitted before the first frame was dropped; when the first frame never arrived (offline/broken stream) the cached EPG was never read and manual EPG refresh stopped working altogether.

**Fix:** the effect is `LaunchedEffect(Unit)` again and starts three things:

1. `BackgroundSourceUpdater.epgUpdated.collect` — from the first composition (no event can be lost),
2. `manualEpgRequests.collect` — manual refresh works even without a first frame,
3. **only the cache read is deferred**:

```kotlin
withTimeoutOrNull(ColdStartPolicy.EPG_CACHE_FALLBACK_MS) {   // 10 s
    snapshotFlow { firstFrameRendered }.first { it }
}
// first frame arrived OR the fallback elapsed → the read is allowed either way
EpgRepository.load(context, epgSource, forceRefresh = false)
```

So the cold-start win of t197 (no XMLTV parse competing with the first frame) is kept, but correctness is restored: the cached schedule always loads within 10 s, and the collectors never miss an event. The `epgCache=…ms firstFrame=…` line now also reports whether the read was frame-gated or fallback-driven.

## LOADCONTROL-TEST-01 — the guard reads the production numbers

`LoadControlConfigTest` asserted literal `2_500, 6_000, 500, 2_000` while production had already moved to **300** — the guard had drifted. It now reads `LoadControlTuning.minBufferMs()/maxBufferMs()/playbackBufferMinMs()/playbackBufferAfterRebufferMs()` and a new test pins the A/B arm to **300** and re-checks `isValid` through the real `DefaultLoadControl.Builder`.

## B1-USES-01 / DOC-01 / PERF-01

- `ColdStartPolicy.usesCacheFirstPlaylist(...)` had **no production caller** → removed (with its test) instead of documenting a helper that does nothing. The cache-first behaviour is already implemented by the existing playlist cache + scheduler path; that stays a doc statement, not a fake API.
- `EPG_CACHE_FALLBACK_MS = 10_000L` is the documented, tested ceiling for the frame gate.
- Debug logging stays gated by `Log.isLoggable` — **zero writes and zero string building when the tag is off**.

## Tests

`ZapTraceTest` (**8**): the zap/prepare/first-frame split; incomplete measurement reports nothing; a new zap resets the previous numbers; **arming once per real zap** (re-prepare keeps the start, new channel re-arms); **the production arm produces the summary line**; the LoadControl invariant with the 300 arm; EPG not readable before the first frame; **the fallback window is bounded (5–30 s)**.

`LoadControlConfigTest` (**4**): production durations accepted through the real builder; **the arm is actually 300**; min-buffer covers after-rebuffer; an invalid set still throws (CI10077 regression).

JVM total **169 → 172**.

## Preserved

F1 remote habits, F3 auto-update, F4 audio UI, LoadControl **2 500/6 000/300/2 000**, MP2 FFmpeg extension, zero-copy SurfaceView, flat UI, channel keys, digit OSD, logo cache, recent channels, EPG, SR, brand. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **172 tests 0 failures**; lint **0 errors**/86 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` / `check_refresh_paths` / `check_settings_scroll` all OK; APK 16,126,395 B ≤ 30 MB.
- CI pair receipt (run 44847564722, source `65ccb3fd`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **172 tests 0 failures**; lint 0 errors / 84 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,059,991| `490208a98a257b33a6b6b4212cc7c31e4d4ee7b95780febfc2464ef455c91a9d` |
| app-debug-androidTest.apk |502,955| `e1e3f66af202f11c09840ac415b57ebdfafe8fd5190e7f7c86b40eda1b754cd0` |

- Artifacts: pair `14684575228` (`sha256:7b4f3887ed7f45e78cefa70a3b00b91bd08ca0a1a190d907969cd58b36769b93`, zip 10,332,896 B); reports `14683781793` (`sha256:01ad71b5c6b6a6b7d1a93899a8a250bef96e08293ce6798055aff04181ceed95`). Signer `09dfdf79cee3d31afdefd36fbcda98b761468c279f9559fb788b64ebc8f065be`.
- Version: `tv.iqtvlite.app.validation / 10123 / 2.2.0-tivimini-65ccb3fd-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,059,991 B ≤ 30 MB.

## Device protocol (acceptance, not claimed here)

1. `setprop log.tag.IQTV-ZAP DEBUG`, zap 10 times → **10** `zap=…ms prepare=…ms firstFrame=…ms` lines (before: 0). Re-open settings/return to the same channel → no extra line/restart of the measurement.
2. Same 10 zaps: record p50/p95 and compare with CI10122's interim (765 ms / 1 413 ms, no ZAP lines) and the CI10121 baseline (996 / 1 317 ms).
3. Airplane mode → open the player → EPG cache still loads within 10 s (`epgCache=…ms firstFrame=false`), and 「立即刷新 EPG」 still triggers exactly one attempt.
4. Full cold start: the `epgCache` line must appear after the first frame (or after the 10 s fallback), never before.
5. Rebuffer count must not increase versus CI10122; if it does, set `PLAYBACK_BUFFER_MIN_MS` back to 500 and re-run.
