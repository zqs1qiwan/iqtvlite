# AUTO UPDATE SETTINGS 4 — one manual EPG entry point, refresh gate, replay events (t189)

2026-11-21. Task t189 (developer2), on CI10118 source (after t187). Closes the t183 review findings on F3.

## MANUAL-POLICY-01 (medium) — the policy is finally on the production path

Before: `ManualRefreshPolicy` existed only in tests, and the player had a hand-written `while (true) { if (flag) … }` pump with no consumed-marker; three call sites could each trigger a download.

Now:

- `AutoUpdateScheduler.requestManualEpg(context)` is the **single manual entry point** (`refreshNow(playlist = false, epg = true)`).
- `PlayerActivity` owns one `MutableSharedFlow<Unit> manualEpgRequests` and a `requestManualEpg()` helper; **all three** call sites (「更新EPG」 row, 「立即刷新 EPG」 row, legacy flag path) funnel through it.
- The EPG effect consumes the flow **once per emission** and asks `ManualRefreshPolicy.actionFor(...)` before calling the scheduler — no hand-written flag check remains (`shouldRefreshEpg` was deleted).
- **Source scan**: `scripts/check_refresh_paths.py` replaces the pure `usesSingleRefreshPath` helper and asserts against the real sources:
  1. no `refreshAll(...)` / `scheduleRefresh(...)` calls and no definitions (dead code),
  2. `requestManualEpg` exists in the scheduler and `replay = 1` is set on the events flow,
  3. `PlayerActivity` never calls `refreshNow(..., epg = true)` directly and does use `requestManualEpg()`,
  4. the pump consumes `manualEpgRequests.collect` and consults `ManualRefreshPolicy.actionFor(`.
  Verified: `check_refresh_paths: OK`.

## SOURCE-SAVE-02 (medium) — one pass at a time, nothing lost

- `ManualRefreshPolicy.RefreshGate` (pure, `@Synchronized`) replaces the bare boolean: `tryEnter()`/`exit()`/`isInFlight()`.
- `onSourceChanged` now sets **and clears** `startupDone` **inside** the mutex, and takes the gate — a 60 s tick can no longer observe "reset but not yet done" and start a second startup pass; `runDue` and `refreshNow` also take the gate, so a manual request cannot stack on an automatic one.
- `_events` is now `MutableSharedFlow(replay = 1, extraBufferCapacity = 4)`, so a `PlayerActivity` created **after** the refresh (MainActivity finishing) still shows the result Toast instead of missing it.
- `onSourceChanged` also returns early (`源未配置`) when neither source is configured.

## SOURCE-EVENT-01 — never claim success on failure

`onSourceChanged` tracks the outcome of both refreshes and emits **`源已更新` only when every attempted source succeeded**, otherwise `源更新失败` (and `源未配置` when there was nothing to do).

## Single source for the EPG transitions (TEST-01)

`onEpgResult` now writes the state produced by `AutoUpdateConfig.afterSuccess()` / `afterFailure(now, count)` through the new `Prefs.setEpgFailureState(...)`, so the scheduler and the unit tests share one definition (success → `failCount = 0`, `lastFailureAt = 0`).

## RESET-01 — dead code removed

`BackgroundSourceUpdater.scheduleStartupRefresh`, `scheduleRefresh`, `refreshAll` and the `startupRefreshScheduled` flag had **no call sites** since the scheduler took over; all removed. `PlayerActivity.shouldRefreshEpg` was removed with the pump.

## PERF-01

The fixed `STARTUP_DELAY_MS = 1500` is still recorded as a follow-up (real first-frame callback), unchanged here.

## Tests

`ManualRefreshPolicyTest` (**4**): manual EPG runs exactly once; only a saved source resets; **`RefreshGate` admits one pass at a time and re-opens after `exit()`** (replaces the pure `usesSingleRefreshPath` test, now covered by the source scan); success/failure transitions are single-sourced.

JVM total **160** (unchanged count, one test traded for the gate test).

## Preserved

F1 remote habits, F4 audio UI, LoadControl **2500/6000/500/2000**, MP2 FFmpeg extension, zero-copy SurfaceView, flat UI (0dp, no blue focus), channel keys, digit OSD, logo cache + switches, recent channels, EPG rendering, SR, brand. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **160 tests 0 failures**; lint **0 errors**/86 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` OK; `check_refresh_paths` OK; APK 16,121,319 B ≤ 30 MB.
- CI pair receipt (run 44838813263, source `3b243d9a`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **160 tests 0 failures**; lint 0 errors / 84 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,059,991| `f2aa2e1898ee569264f67dc923eb8a51a9634e94ce3914b37ff6db365bc89380` |
| app-debug-androidTest.apk |502,955| `cfa20a2a4c9903732a126b6090156fb07312490b4cf81f62cd0b992d3a009d74` |

- Artifacts: pair `14680620213` (`sha256:693945eeee9392420b49192d0cc821f4d47240be8be84b15b1753f5bd84434b1`, zip 10,333,232 B); reports `14680225934` (`sha256:ed9896444bbbb980067f7371e9453288799d5e230dfd8128b6937e23b1bb4738`). Signer `81be06db4b53ead5139c6cdbb0cb6fd52edcd9fa8710c204e45d50c23bdfcd30`.
- Version: `tv.iqtvlite.app.validation / 10119 / 2.2.0-tivimini-3b243d9a-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,059,991 B ≤ 30 MB.

## Device protocol (acceptance, not claimed here)

1. 立即刷新 EPG → exactly one request, one Toast, `last_epg_update_time` advances once.
2. Save a source → exactly one refresh of each source; Toast `源已更新` on success, `源更新失败` when offline; playlist last-success updated.
3. Open then cancel the config screen → no refresh.
4. Offline then back online → the failure counters reset after the first success and the schedule resumes.
