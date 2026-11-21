# FIRST-FRAME SEGMENTATION FIX (t216)

2026-11-21. Task t216 (developer2), on CI10128 source (after t213). Two one-line-class instrumentation defects found by t215, both fixed here. **No behaviour/perf change** — only when `IQTV-ZAP` debug is on.

## 1. `onZap()` did not reset the stage marks (ZAP-SEG-01)

**Defect:** `onZap()` cleared `zapAt/prepareAt/firstFrameAt/lastZapMs` but **not `formatAt/decoderAt`**. From the second zap on, the stale `formatAt`/`decoderAt` were smaller than the new `prepareAt`, so `formatDelayMs()`/`decoderDelayMs()` returned **-1**, the `summaryLine()` condition failed and only the short base line was printed — the fmt/dec/render split silently disappeared after the first channel.

**Fix:** `onZap()` now also does `formatAt = 0L; decoderAt = 0L` (every stage field starts clean on each zap).

## 2. First autoplay produced no ZAP line at all (ZAP-AUTOPLAY-01)

**Defect:** `summaryLineOnce()` needs a `zapKey`, and only the **user entries** (`playChannel`, `playGroupAuto`, `playGroupVariant`) armed it. Cold-start autoplay never goes through those, so the very first playback logged **nothing**.

**Fix:** `ZapTrace.armIfUnarmed(key, now)` + wiring in `StreamPlayback` right before `onPrepare`:

```kotlin
if (Log.isLoggable("IQTV-ZAP", DEBUG)) {
    ZapTrace.armIfUnarmed(ZapTrace.zapKey(channelId, channel.url), now)   // only when nothing armed
}
ZapTrace.onPrepare(now)
```

A user entry always arms first, so `armIfUnarmed` is a **no-op** for normal zapping and can never move the user-visible origin. Cold start now logs its own line (with `prepare` ≈ 0 by definition, since the app-triggered prepare *is* the origin there).

## `summaryLine()` condition re-checked

```kotlin
return if (fmt >= 0 && dec >= 0 && render >= 0) "$base fmt=…ms dec=…ms render=…ms" else base
```

Unchanged and correct: all three stages must be present, otherwise the compact line is printed (no `-1` leaking into the log). With the two fixes above, a normal zap now satisfies the condition on **every** channel.

## Tests — `ZapTraceTest` (16 → 18)

- **`theSecondZapStillReportsTheThreeStages`**: two consecutive zaps (different groups) both print `zap=1500ms prepare=200ms firstFrame=1300ms fmt=120ms dec=580ms render=600ms`; after the second one the three delays are 120/580/600 (not -1).
- **`theFirstAutoplayAlsoReportsALineWithoutAnyKeyPress`**: `armIfUnarmed` on an unarmed clock produces the full line for the cold-start autoplay, and a later `armIfUnarmed` **does not** move an origin that a user entry already set (400 ms stays 400 ms).
- the earlier "compact line without decoder events" test still passes (graceful omission).

JVM total **182 → 184**.

## Preserved

Default buffer **稳/500**, F1 remote habits, F3 auto-update, F4 audio UI, LoadControl tiers, MP2 FFmpeg extension, zero-copy SurfaceView, flat UI, channel keys, digit OSD, logo cache, recent channels, EPG, SR, brand. Size ≤30 MB. Debug-off cost unchanged (both new calls are inside `Log.isLoggable`).

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **184 tests 0 failures**; lint **0 errors**/87 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` / `check_refresh_paths` / `check_settings_scroll` all OK; APK 16,131,835 B ≤ 30 MB.
- CI pair receipt (run 44858617836, source `84885824`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **184 tests 0 failures** (ZapTraceTest 18); lint 0 errors / 85 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,059,987| `8924ee8f67cc8cb72c9a57dd9ade0d7aedf221d512eaf964b99d5af8a312b0c1` |
| app-debug-androidTest.apk |502,955| `71281227d7902ac1685e82e8ce8b0d7a498509fa688b04044abb00d1685b186a` |

- Artifacts: pair `14689290545` (`sha256:891bead895833ee898ff495eb97fc37b2888ed03080754dfd547e711636955a8`, zip 10,342,317 B); reports `14689485349` (`sha256:fd96fcda8daab1c8e942f86bfdbbb1b80bf32545c027f6338bcaba88f6d9f99b`). Signer `eb726088ddb0986f302c4beefcec60a782abb2a1bb74ec58fc410d0f93a5c776`.
- Version: `tv.iqtvlite.app.validation / 10129 / 2.2.0-tivimini-84885824-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,059,987 B ≤ 30 MB.

## Device protocol (acceptance, not claimed here)

1. `setprop log.tag.IQTV-ZAP DEBUG`, cold start without touching the remote → **one ZAP line with `fmt/dec/render`** (previously none).
2. D-pad zap 10 times → **10 lines, each with `fmt/dec/render`** (previously only the first one had them).
3. Report p50/p95 per stage; with the ~757 ms `firstFrame` and ~239 ms `prepare` split known, the stage numbers now say whether the cost is **network fill (`fmt`)**, **MediaCodec create/configure (`dec`)** or **first decode+present (`render`)** — that decides the next arm (A/B/C in `FIRSTFRAME_OPTIMIZATION.md`).
4. Re-entering the same channel prints no second line.
