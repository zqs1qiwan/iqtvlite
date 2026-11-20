# AUTO UPDATE SETTINGS 2 — single EPG path, failure state, grouping (t176)

2026-11-21. Task t176 (developer2), on CI10113 source (after t175). Fixes the t173 review findings on the F3 feature plus two device tails from t171.

## EPG-DUAL-01 (high) — one EPG executor

Before: a manual EPG refresh was executed **twice** — `VideoPlayerScreen`'s own loop called `EpgRepository.loadStructured(...)` *and* `AutoUpdateScheduler.refreshNow(epg = true)` ran the same download; the automatic H6/backoff path had the same double execution, and there was no shared EPG mutex, so two parses raced on `epgData` / `last_epg_update_time` and produced two Toasts. The loop also re-vetoed an `epgAutoDue` decision with its own `isDifferentDay / isOver6Hours` conditions.

Now **`AutoUpdateScheduler` is the only EPG executor**:

- `VideoPlayerScreen` keeps only: load the cached EPG for the first screen, collect `BackgroundSourceUpdater.epgUpdated` → `epgData`, and forward a manual request (`shouldRefreshEpg`) to `AutoUpdateScheduler.refreshNow(context, playlist = false, epg = true)`.
- No `EpgRepository.loadStructured(...)` call remains in the player screen (the whole `while(true) { … }` download loop was replaced by a 2 s manual-request pump).
- The scheduler holds **one mutex** around both the automatic pass and `refreshNow`, so a manual and an automatic refresh can never overlap.
- The due condition is now exactly `manual || epgAutoDue` (AutoUpdateConfig), with no second veto.

## EPG-FAILURE-01 (medium) — success clears the failure state

`AutoUpdateScheduler.onEpgResult(success)` now calls `Prefs.clearEpgFailure(context)` (resetting `epg_last_failure_at` + `epg_fail_count`) on success and `Prefs.recordEpgFailure(...)` on failure. Previously only `last_epg_update_time` was written, so the policy kept backing off even after a successful refresh. `AutoUpdateConfig.afterSuccess()/afterFailure()` make the rule explicit and testable.

## GROUP-01 (medium) — 基础 opens the group

Order is now: **基础**（源配置 / 更新EPG / 源选择 / 检查更新 / 最近观看）→ **音频与字幕** → **数据与更新** → **台标与缓存**. The 基础 header sits before 源配置 and 最近观看 moved up into that group, so no header wraps a single row and no group appears before 基础.

## PERF-01 — startup pass after the first frame

The scheduler's startup pass now waits `STARTUP_DELAY_MS = 1500 ms` before touching the network, so it no longer competes with cold start (the first frame / first playlist parse). Still entirely on `Dispatchers.IO`.

## WORKMANAGER-01 / STARTUP-RESET-01

- **Scope recorded**: updates run **only while the app process is alive** (process coroutine, policy persisted in Prefs). A reboot does not schedule background work; the next launch re-evaluates the policy. This is deliberate for a ≤30 MB TV app — no WorkManager dependency, no JobScheduler surface.
- `AutoUpdateScheduler.resetStartupFlag(context)` re-opens the startup gate and runs a new startup pass on IO. It is called when the user enters 源配置 (the source may have changed). `OFF` is still never revived by the reset (covered by a test).

## Device tails from t171

- **BACK assertable**: both dialogs now expose their `testTag`s as **resource ids** (`.semantics { testTagsAsResourceId = true }`), so `uiautomator dump` shows `recent_dialog` / `recent_row_*` / `recent_clear` / `audio_dialog` instead of an empty root. This unblocks the "BACK closes only the list" assertion.
- **最近观看（N）count**: the settings row now uses `recentEntries.size` (the in-memory list) instead of the playlist-resolved size, so a clear flips it to 0 in the same frame.

## Tests — `AutoUpdateSchedulerPolicyTest` (7, pure JVM)

- the manual path is honoured even while the automatic path is blocked (OFF + inside backoff);
- `afterSuccess()` clears the failure state → the next due check is interval-based again;
- `afterFailure()` increments and drives 5→10→20→30 min backoff;
- the startup pass runs once, only while `startupRefreshDone == false`;
- `STARTUP_DELAY_MS` is within 1–3 s (PERF-01 guard);
- `OFF` is not revived by a startup reset;
- clearing the recent list leaves the settings count at 0 in the same frame.

JVM total **127 → 134**.

## Preserved

F3 behaviour (intervals, manual refresh, last-update line), LoadControl **2500/6000/500/2000**, MP2 FFmpeg extension, zero-copy SurfaceView, flat UI (0dp, no blue focus), channel keys, digit OSD, logo cache + switches, recent channels, EPG rendering, audio-track UI, SR, brand. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **134 tests 0 failures**; lint **0 errors**/78 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` OK; APK 16,100,613 B ≤ 30 MB.
- CI pair receipt (run 44828902605, source `f357dd8d`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **134 tests 0 failures** (incl. AutoUpdateSchedulerPolicyTest 7); lint 0 errors / 76 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,043,607| `55ac7ef738fca1b57ffb0fbc1285e9bed3cbbe1a342a1d675cfac0b2fa9068e9` |
| app-debug-androidTest.apk |502,955| `ab35db0f24eaf0efc583d3a95d3b6c368029b8e84ab804900f0eb334569a9c95` |

- Artifacts: pair `14674203924` (`sha256:fedef3704f5b33139d4ba15e96c10b169198dfcd7ec71df70f2744d5aff62526`, zip 10,312,638 B); reports `14673879011` (`sha256:ed7322b74377c771b11778d9cd3e78b6b89970d798e66563a0e67f82f5a448b4`). Signer `39eca27699711d5cbd0a187788e993329eb12271ed47fa934d19d83ecf9bbc6a`.
- Version: `tv.iqtvlite.app.validation / 10114 / 2.2.0-tivimini-f357dd8d-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,043,607 B ≤ 30 MB.

## Device protocol (acceptance, not claimed here)

1. 手动「立即刷新 EPG」→ **exactly one** network request / one Toast, `last_epg_update_time` advances once.
2. Automatic + manual at the same time (short interval) → still one execution, no duplicate Toast.
3. Fail a refresh (airplane mode) then succeed → the backoff resets (`epg_fail_count` back to 0) and the next automatic pass happens on schedule.
4. Settings order: 基础 first (源配置…最近观看), then 音频与字幕, 数据与更新, 台标与缓存.
5. `uiautomator dump` while the recent list is open shows `recent_dialog` + `recent_row_0`; BACK closes only the list.
6. 最近观看（N）shows 0 immediately after 清空.
