# ZAP & COLD-START 3 — correct timing origin and stable key (t204)

2026-11-21. Task t204 (developer2), on CI10123 source (after t201). Fixes the two measurement-accuracy issues found in the t202 review. **No performance claim** — acceptance runs the formal A/B on this pair.

## ZAP-KEY-01 (high) — the key is the channel, not the per-prepare MediaItem id

**Defect:** `StreamPlayback` armed the stopwatch from `id = "c$channelId-l…-s${sequence.incrementAndGet()}"`. The `s<sequence>` part changes on **every** prepare, so a re-prepare of the same channel looked like a brand-new channel and the running measurement was wiped — the "one zap = one measurement / same channel keeps" contract was broken. The old unit test used a fixed `"chan-1"`, so it never exercised the production id shape.

**Fix:** a **channel-stable key**, used by both the arm and the (removed) production arm site:

```kotlin
fun zapKey(channelId: Long, url: String): String = "c$channelId-l${url.hashCode().toUInt()}"
```

No sequence number anywhere. `theKeyIsTheChannelNotThePerPrepareMediaId` asserts: same channel → same key (re-prepare keeps the measurement); a different stream of the same channel → different key.

## ZAP-TIMING-01 (medium) — the clock starts when the user zaps

**Defect:** `onZap` sat right next to `onPrepare` inside `StreamPlayback`, so `prepareDelay ≈ 0` and the measured "TTFF" was really *prepare → first frame*, i.e. systematically **smaller** than what the user experiences.

**Fix:**

- `PlayerActivity.playChannel(channel)` — **the user zap entry** (key press / click / number jump / group play all funnel here) — now arms the stopwatch:
  `ZapTrace.onZapRequested(ZapTrace.zapKey(channel.id, channel.url), elapsedRealtime())`.
- `StreamPlayback` only records `onPrepare`/`onFirstFrame`.
- `onPrepare` **self-arms if nothing armed it** (initial autoplay has no user zap): the origin becomes prepare, so a line is still produced and is explicitly documented as a fallback (`prepareDelay = 0` in that case).

The three numbers now mean: **zap** = key press → first frame (what the user perceives), **prepare** = key press → `prepare()`, **firstFrame** = `prepare()` → first present.

## EPG-CACHE-MAIN-01 — the cache read is out of the main thread

`EpgRepository.load(context, epgSource, forceRefresh = false)` (disk + XMLTV parse) now runs inside `withContext(Dispatchers.IO)`; a slow or failing cache — and the 10 s fallback path — can no longer block the UI.

## DOC-01 / PERF-01

- `ColdStartPolicy.mayLoadEpgCache(...)` had **no production caller** (the effect uses the 10 s timeout) → removed; the surviving rule is the `EPG_AFTER_FIRST_FRAME` constant, asserted by a test.
- Debug output stays behind `Log.isLoggable(ZAP_TAG, DEBUG)`: **zero writes, zero string building when the tag is off** (no `Log.d` call executes at all — the `summaryLine()` is only built inside the `isLoggable` branch).

## Tests — `ZapTraceTest` (8 → 10)

- zap/prepare/first-frame split + summary line; incomplete measurement → null; a new zap resets the old numbers;
- **armed once per real zap (user entry)**: a second press for the same channel keeps both `zap` and `prepare` numbers; a different channel re-arms;
- **the key is channel-stable** (`zapKey`), including "same channel, different stream → different key";
- **prepare self-arms** when there was no user entry (autoplay fallback, `prepareDelay = 0`);
- the LoadControl invariant with the 300 arm; the EPG gate constant; the bounded fallback window.

`LoadControlConfigTest` (4) unchanged from t201 (reads the production tuning, pins 300).

JVM total **172 → 174**.

## Preserved

F1 remote habits, F3 auto-update, F4 audio UI, LoadControl **2 500/6 000/300/2 000**, MP2 FFmpeg extension, zero-copy SurfaceView, flat UI, channel keys, digit OSD, logo cache, recent channels, EPG, SR, brand. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **174 tests 0 failures**; lint **0 errors**/86 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` / `check_refresh_paths` / `check_settings_scroll` all OK; APK 16,126,841 B ≤ 30 MB.
- CI pair receipt (run 44849027943, source `24ae116e`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **174 tests 0 failures** (ZapTraceTest 10); lint 0 errors / 84 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,059,987| `4bf33d93cf4b9bd7064669926f6dd7959f2d780278f56301b822558438d82490` |
| app-debug-androidTest.apk |502,955| `407b78f1bd9bf4dc929606b539209d7484416a0aa764663c95db69e637ef2c8d` |

- Artifacts: pair `14684926598` (`sha256:5f3a35c48da7b0f484e694c258bfac918b7d15a498127a0d02bae1c1e9fe5480`, zip 10,333,569 B); reports `14683878773` (`sha256:776ec861b815a5a3d65a962ae2aba9c635808d90a8dc5189a8dfacbc8f2da5df`). Signer `041e6093de17f3ad6e9c3fcf3811d4182ae4f44035dac6f706018cad040945b7`.
- Version: `tv.iqtvlite.app.validation / 10124 / 2.2.0-tivimini-24ae116e-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,059,987 B ≤ 30 MB.

## Device protocol (acceptance, not claimed here)

1. `setprop log.tag.IQTV-ZAP DEBUG`: 10 zaps → 10 `zap=… prepare=… firstFrame=…` lines; **prepare** must now be a real gap (key → prepare), not 0.
2. Re-entering the **same** channel (or a re-prepare caused by a settings overlay) must **not** print a second line nor restart the measurement.
3. Formal A/B on this pair: p50/p95 vs CI10121 baseline (996 / 1 317 ms). CI10122/CI10123 numbers were collected with the buggy origin and are **reference only**.
4. Airplane mode: EPG cache still loads within 10 s off the main thread (`epgCache=… firstFrame=false`), UI stays responsive, 「立即刷新 EPG」 still one attempt.
5. Rebuffer count must not increase; if it does, set `PLAYBACK_BUFFER_MIN_MS` back to 500 and re-run.
