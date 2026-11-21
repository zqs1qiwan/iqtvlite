# FIRST-FRAME WATCHDOG (t225)

2026-11-21. Task t225 (developer2). Adds a bounded, **default-on** reliability net for channels that never render a first frame (high-bitrate / multicast, e.g. 睛彩 ~8.3 Mbps, and weak-network channels). Normal channels are unaffected: they render far below the 8 s timeout.

## Behaviour

1. **Arm** on every `prepare()` (every zap, every variant probe, cold start) — generation-stamped, so a newer zap owns the watchdog.
2. **8 s without `onRenderedFirstFrame`** → **one** automatic retry: `seekToDefaultPosition() + prepare() + play()` on the *same* media item. No new `MediaSource`, no zap re-arm, no playlist refresh — so it cannot fight the playback-intent / SR guard.
3. **Another 8 s without a frame** → Toast **「该频道暂不可播（网络/源响应慢）」** and stop (never a second retry, never a loop).
4. **Cancel** on: first frame (`onRenderedFirstFrame`), a new prepare (new generation makes the old timer inert), `release()`, and pause — checked as `!player.playWhenReady` at fire time. A *rebuffer* keeps `playWhenReady == true`, so genuine buffering still counts as "should be playing" and is still retried.
5. Paused/exit/overlay: a paused channel never retries (see 4); exit removes the watchdog; an overlay does not pause playback, so the watchdog may still retry underneath it — harmless (it only re-prepares the same item) and documented here.

## Logging / A-B knob

```
IQTV-WATCHDOG firstFrameTimeout retry=1 channel=8134
```

- DEBUG-gated (`setprop log.tag.IQTV-WATCHDOG DEBUG`); no key/log work when off.
- Timeout and retry count are constants in `FirstFrameWatchdogPolicy` (`TIMEOUT_MS = 8_000`, `MAX_RETRIES = 1`) and the `Watchdog` constructor takes both, so an A/B build only needs to pass a different timeout. Default behaviour: enabled.

## Tests — `FirstFrameWatchdogPolicyTest` (6)

| test | asserts |
|---|---|
| `eightSecondsIsTheTimeoutAndOnlyOneRetryIsAllowed` | 8 000 ms, 1 retry |
| `theFirstTimeoutRetriesOnceThenGivesUp` | timeout → RETRY → timeout → GIVE_UP, then inert |
| `aFirstFrameCancelsEverything` | first frame cancels; stale timer → IGNORE |
| `aNewZapCancelsTheOldTimerAndStartsFreshRetries` | new zap resets retries and makes the old timer inert (**no double prepare**) |
| `staleTimersCannotDoublePrepare` | cancelled watchdog never retries |
| `theRetryLogLineIsStable` | log line + toast text |

JVM **203 → 209**.

## Preserved

F1 remote habits, F3 auto-update, F4 audio UI, default buffer **稳/500**, LoadControl tiers, MP2 FFmpeg extension, zero-copy SurfaceView, flat UI, channel keys, digit OSD (t223 fix intact), logo cache, recent channels, EPG, SR, brand. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **209 tests 0 failures** (`FirstFrameWatchdogPolicyTest` 6); lint **0 errors**/89 warnings; pair gate missing 0; keep 210 identical; dex 7 OK; `check_player_threading` / `check_refresh_paths` / `check_settings_scroll` all OK; APK 16,087,352 B ≤ 30 MB.
- Note: the first commit of this task tripped `check_player_threading` (timer lambdas touched `player.*` without a Main hint); fixed by pinning both `launch(Dispatchers.Main)` sites — the cancelled CI run is superseded by the green one below.
- CI pair receipt (run 44870898798, source `ffaff4ed`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **209 tests 0 failures** (`FirstFrameWatchdogPolicyTest` 6); lint 0 errors / 87 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,092,813| `829687d8e933d70a04264d4da2295365f39974687e5ef9c4f8fffb535fde99d4` |
| app-debug-androidTest.apk |502,955| `e361b6ac45dad8b27f6d464796d038bf8cfdebab5516ebb44dd16b0b3b09ccf2` |

- Artifacts: pair `14693418041` (`sha256:7d1309c8427769ff6f84130bc5acee3a3a6bd523e8cf34ce022d4bdf95e11b7b`, zip 10,363,920 B); reports `14693932153` (`sha256:63c4262b21c94bef66515527d7f3c2de63697b1dd0313002fe7a084a57649436`). Signer `a2a87b77a8133b25da57d3e53ed51cd6bb534a0a78dde0d80ae38bdbc030b61e`.
- Version: `tv.iqtvlite.app.validation / 10134 / 2.2.0-tivimini-ffaff4ed-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,092,813 B ≤ 30 MB.

## Device protocol (acceptance, not claimed here)

1. `setprop log.tag.IQTV-WATCHDOG DEBUG`; zap to 睛彩 117/118/119 → after ~8 s expect **exactly one** `firstFrameTimeout retry=1` line and a re-prepare; after ~16 s the toast appears once.
2. Zap to a healthy channel → **no** watchdog log at all (first frame ≪ 8 s).
3. Zap away during the 8 s window → no retry for the abandoned channel (new generation).
4. Pause during the window → no retry/toast.
5. Confirm no duplicate `IQTV-ZAP` line from the retry (same media item, no re-arm).
