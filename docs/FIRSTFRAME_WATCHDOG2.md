# FIRST-FRAME WATCHDOG 2 — review repairs (t228)

2026-11-21. Task t228 (developer2), on CI10134 source (after t225). Fixes the three defects found by the t226 review plus the arming race. **No behaviour change for healthy channels** (first frame ≪ 8 s).

## WATCHDOG-RETRY-01 (high) — the retry's own first frame was swallowed

**Defect:** `Metrics.onRenderedFirstFrame` did `if (!matches(eventTime) || firstFrame) return` **before** `onFirstFrameForWatchdog(player)`. A retry does **not** call `Metrics.begin()`, so `firstFrame` stayed `true`: the retry's successful frame was dropped, the watchdog was never cancelled, and the second timer always fired the bogus 「该频道暂不可播」 toast.

**Fix:** notify the watchdog **first**, then keep the once-only early return:

```kotlin
override fun onRenderedFirstFrame(...) {
    onFirstFrameForWatchdog(player)   // t228: before the once-only early return
    if (!matches(eventTime) || firstFrame) return
    ...
}
```

Test: `aFirstFrameAfterARetryCancelsTheSecondTimer` — arm → timeout → RETRY → `onFirstFrame()` → the next `onTimeout(gen)` returns **IGNORE** (no toast).

## WATCHDOG-RELEASE-01 (high) — use-after-release

**Defect:** `release()` only removed + cancelled the *policy*; the coroutine that had already been scheduled still ran, read `player.playWhenReady` on a **released** player (IllegalStateException risk) and could reach the old Activity's `Context` for the toast.

**Fix:**
- `watchdogJobs: WeakHashMap<ExoPlayer, MutableList<Job>>` keeps every timer (initial + retry);
- `release()` cancels those jobs **before** touching policy/player;
- every timer body now starts with `if (!watchdog.isCurrent(gen)) return@launch`, i.e. **ownership is checked before any player access**.

## WATCHDOG-PAUSE-01 (medium) — old generation killed the new channel

**Defect:** a stale timer of channel A could wake after channel B was armed, see `!player.playWhenReady` (B paused), and call `cancel()` — killing **B's** watchdog.

**Fix:** `cancel()` advances the generation and the timer checks `isCurrent(gen)` first, so a stale generation can never cancel a newer one. Test: `aPausedNewChannelIsNotKilledByTheOldTimer`.

## WATCHDOG-ARM-01 (low) — fast first frame could arrive un-armed

**Defect:** the watchdog was armed *after* `prepare()/play()`, so an immediate first frame could land before arming and leave an un-cancelled timer → spurious retry 8 s later.

**Fix:** `armWatchdog(...)` is now called **before** `prepare()` (it only needs the channel id), so a first frame always cancels a live watchdog. Test: `fastFirstFrameBeforeArmingIsCovered`.

## Tests — `FirstFrameWatchdogPolicyTest` (6 → 11)

new: `aFirstFrameAfterARetryCancelsTheSecondTimer`, `aSecondFrameForTheSameChannelIsHarmless`, `aPausedNewChannelIsNotKilledByTheOldTimer`, `releaseInvalidatesEveryTimer`, `fastFirstFrameBeforeArmingIsCovered`; previous 6 still pass. JVM **209 → 214**.

## Unchanged guarantees

One retry only, 8 s timeout (`TIMEOUT_MS`), toast once, same media item (no new MediaSource, no zap re-arm), DEBUG-gated `IQTV-WATCHDOG firstFrameTimeout retry=1 channel=…`, rebuffer ≠ pause (`playWhenReady` stays true). F1/F3/F4, buffer 稳/500, LoadControl, MP2, zero-copy, flat UI, channel keys, digit OSD, logo cache, recent, EPG, SR, brand untouched. Size ≤30 MB.

## Verification

- Local `--no-daemon`: **BUILD SUCCESSFUL**; JVM **214 tests 0 failures**; lint **0 errors**/89 warnings; pair gate missing 0; keep 210 identical; dex 7 OK; `check_player_threading` / `check_refresh_paths` / `check_settings_scroll` all OK; APK 16,088,421 B ≤ 30 MB.
- CI pair receipt (run 44875183411, source `4230a7f0`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **214 tests 0 failures** (`FirstFrameWatchdogPolicyTest` 11); lint 0 errors / 87 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,092,817| `2a59b9f0b4952265a17af0b035e07ff99576724e892e6c5286f7255dfb697646` |
| app-debug-androidTest.apk |502,955| `a09dfb19ae2b4711a52b329f4c74135a901006a9b30adb8d3782d48356baf28b` |

- Artifacts: pair `14695617782` (`sha256:19cd0b1c51ac5fd953933aa81c49a8391133a07d748647ae1479387aff68ee45`, zip 10,364,810 B); reports `14695832238` (`sha256:69a4678816e0a5dd6b575889a6af3c48e8b3ca7d8ca4d6af49d55dcc78b4ce7e`). Signer `89d0cced86323a41b8a9143f2fcf0323d54f4a59849998d40aef5805f069f4f0`.
- Version: `tv.iqtvlite.app.validation / 10135 / 2.2.0-tivimini-4230a7f0-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,092,817 B ≤ 30 MB.

## Device protocol (acceptance, after the read-only health check)

1. 睛彩 117/118/119 → 8 s: exactly one `retry=1`; if the retry succeeds → **no toast** (this was the bug: it always toasted).
2. Zap away within the window → no retry/toast for the abandoned channel.
3. Pause during the window → no retry/toast; resume → still no stale timer.
4. Exit the player during the window → no crash (release cancels the timers).
5. Healthy channel → no watchdog log at all; no duplicate `IQTV-ZAP` line from a retry.
