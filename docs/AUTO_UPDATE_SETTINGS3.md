# AUTO UPDATE SETTINGS 3 — single manual EPG path, saved-source flow (t182)

2026-11-21. Task t182 (developer2), on CI10115 source (after t179). Closes the t180 review findings on F3's refresh paths and the audio-dialog guard regression.

## EPG-MANUAL-01 (high) — one execution per manual request

Before: `onRefreshEpgNow` both raised `shouldRefreshEpg` **and** called `AutoUpdateScheduler.refreshNow(...)`; the 2 s pump in the EPG effect then consumed the flag and called `refreshNow` again → **two downloads, two Toasts, last-update advanced twice**.

Now the settings row only **raises the flag**, and the pump consumes it exactly once:

```kotlin
onRefreshEpgNow = { toast("正在刷新 EPG…"); shouldRefreshEpg.value = true }   // no direct call
// pump:
if (shouldRefreshEpg.value) { shouldRefreshEpg.value = false; AutoUpdateScheduler.refreshNow(...) }
```

`ManualRefreshPolicy.actionFor(flagRaised, alreadyConsumed)` encodes the rule (`CALL_SCHEDULER` only for the first observation, `NONE` otherwise) and is unit-tested.

## EPG-SOURCE-SAVE-01 (high) — saving a source goes through the scheduler

`MainActivity` used `BackgroundSourceUpdater.scheduleRefresh(context, 3000)` → `refreshAll()`, which bypassed the scheduler: EPG success did not clear the failure state and the playlist never wrote `playlist_last_success_at`.

Now saving calls **`AutoUpdateScheduler.onSourceChanged(context)`**, which (on IO, under the scheduler mutex):

1. re-opens the startup gate,
2. refreshes the playlist via `refreshPlaylistUrgent` → `onPlaylistResult` (writes `playlist_last_success_at` / failure counters),
3. refreshes the EPG via `refreshEpg` → `onEpgResult` (clears `epg_fail_count` on success),
4. emits one `源已更新` event for the Toast.

So **all** EPG work now flows through one executor, and both success/failure transitions come from a single source (`AutoUpdateConfig.afterSuccess/afterFailure`).

## STARTUP-RESET-02 (medium) — reset only after a save

`resetStartupFlag` used to run when the settings row was pressed, so merely opening (then cancelling) the config screen triggered a refresh. The row now only opens `MainActivity`; the reset happens inside `onSourceChanged`, i.e. **after the user actually saved**. `ManualRefreshPolicy.shouldRefreshAfterSourceScreen(saved)` documents that contract.

## AUDIO-BACK-02 (high) — already satisfied, re-verified

The audio-dialog guard landed with t179 and is intact in this source: `PlayerActivity.isAudioDialogOpen` + `setAudioDialogOpen` controller, **every** key guard includes `!isAudioDialogOpen` (digits, D-pad/OK/MENU, channel keys, LAST_CHANNEL), **BACK is handled first** and only closes the audio dialog, and opening the picker closes the settings drawer first. Re-checked line by line in this task (`grep isAudioDialogOpen` → 14 guard sites).

## PERF-01 (low) — noted for later

The startup pass uses a fixed `STARTUP_DELAY_MS = 1500`. Replacing it with a real first-frame callback is recorded as a follow-up, not done here (it would need a player-state hook and a new pair for clean attribution).

## Tests — `ManualRefreshPolicyTest` (4, pure JVM)

- manual EPG executes exactly once (`CALL_SCHEDULER` only before consumption);
- only a **saved** source triggers the reset;
- the scheduler is the only refresh path (`usesSingleRefreshPath`);
- success/failure transitions are single-sourced (`afterSuccess` zeroes the failure chain so `backoffMs == 0`).

JVM total **137 → 141**.

## Preserved

F3 behaviour (intervals, manual refresh, last-update line), F4 audio UI, LoadControl **2500/6000/500/2000**, MP2 FFmpeg extension, zero-copy SurfaceView, flat UI (0dp, no blue focus), channel keys, digit OSD, logo cache + switches, recent channels, EPG rendering, SR, brand. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **141 tests 0 failures**; lint **0 errors**/79 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` OK; APK ≤30 MB.
- CI pair receipt (run 44832360654, source `1181aaf1`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **141 tests 0 failures** (incl. ManualRefreshPolicyTest 4); lint 0 errors / 77 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,043,603| `bb314323977c58441a1742cb09dac48a0db1ec0f0eefb9e0f722992f478de3ba` |
| app-debug-androidTest.apk |502,955| `0bc1466f0d90eb0357b2e777b43dcb742d35f8c2c1731a8dbcee26d1cd111b22` |

- Artifacts: pair `14677691798` (`sha256:fd22ea76a83916e532ff8401b1be748a8995c1388cdb0150ad8d5479d1742a08`, zip 10,316,350 B); reports `14677911610` (`sha256:4b9579fd771d5e10a7bb77ab901ea2856d7f7bd7b5246efcc1b6dcf015c890e9`). Signer `d55155cd901c22048ab6282e1a2ef2037ffe3a1aea1899017002ab0a202b778f`.
- Version: `tv.iqtvlite.app.validation / 10116 / 2.2.0-tivimini-1181aaf1-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,043,603 B ≤ 30 MB.

## Device protocol (acceptance, not claimed here)

1. 立即刷新 EPG → **one** network request, **one** Toast, `last_epg_update_time` advances once.
2. Save a new source → one refresh of both, one Toast (`源已更新`), playlist last-success updated.
3. Open then **cancel** the config screen → no refresh at all.
4. Audio picker: BACK closes only the picker; digits/D-pad do not leak to the player.
