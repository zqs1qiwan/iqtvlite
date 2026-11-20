# AUDIO COUNTERS THREAD FIX (t169)

2026-11-21. Task t169 (developer2), on CI10110 source (after t167). Fixes the **crash blocker** introduced with the debug audio counters.

## The crash

```
FATAL EXCEPTION: DefaultDispatcher-worker-2
java.lang.IllegalStateException: Player is accessed on the wrong thread
    at androidx.media3.exoplayer.ExoPlayer.verifyApplicationThread(ExoPlayer.java)
    at androidx.media3.exoplayer.ExoPlayer.getCurrentMediaItem(ExoPlayer.java)
    at com.cjlhll.iptv.StreamPlayback$Metrics$begin$1.invokeSuspend(StreamPlayback.kt:96)
```

Reproduced twice on CCTV-1 with CI10109 (and CI10110, which shares the code). Cause: the t165 debug summary job ran on `CoroutineScope(SupervisorJob() + Dispatchers.Default)` and, after its `delay(60_000)`, read `player.currentMediaItem?.mediaId` to check the session was still live. ExoPlayer only allows accessors on `applicationLooper`, so the read threw on the worker thread and killed the process (before any `IQTV-AUDIO` line could be printed).

## The fix — a main-thread snapshot, no Player access off-looper

New **`PlaybackSession`** (pure Kotlin, `@Volatile`):

```kotlin
PlaybackSession.begin(prepared.mediaId)      // called in prepare(), i.e. main thread
PlaybackSession.isCurrent(session)           // safe from any thread
PlaybackSession.clear(mediaId)               // on release
```

- `Metrics.begin()` now snapshots the media id into `PlaybackSession` **on the main thread** (where `prepare()` already runs) and the 60 s job checks `PlaybackSession.isCurrent(session)` — **no `player.` call at all** from the background coroutine.
- `Metrics.matches()` (used by every analytics hook, including `onAudioUnderrun`, `onAudioDisabled`, `onAudioTrackInitialized`, `onTracksChanged`) now uses `PlaybackSession.isCurrent(session)` instead of `player.currentMediaItem`, so it is thread-safe **by construction** — the counters cannot throw regardless of which thread delivers a callback.
- `release()` clears the snapshot, so a job that outlives the player logs nothing.
- Remaining `player.` reads in `Metrics` (`audioContext()`: `volume`/`playbackSuppressionReason`/`playWhenReady`/`audioSessionId`) are only reached from `AnalyticsListener` callbacks, which Media3 delivers **on the application looper**, and only when the debug flag is on. They are unchanged.

**With the counters disabled (the default, and every release build) the whole path is inert**: `AudioCostCounters.enabled == false` short-circuits every hook and no summary job is started — but the snapshot is still taken, so the thread rule holds either way.

## Static guard — `scripts/check_player_threading.py`

A text scan over `android/app/src/tivimini/java/com/cjlhll/iptv` that fails on:

1. a `Dispatchers.Default { … }` / `Dispatchers.IO { … }` block containing a player accessor;
2. a `launch { … }` / `async { … }` block containing a player accessor, unless the block uses `Dispatchers.Main` / `applicationLooper` / `Handler` / `runOnUiThread`.

Verified both ways: clean tree → `OK`; a synthetic `s.launch { val id = player.currentMediaItem }` → `FAILED` at the exact line. Run it before any change that touches playback code:

```bash
python scripts/check_player_threading.py
```

## Tests

`PlaybackSessionTest` (5, pure JVM): `begin` marks the session current, another session is not current, `clear()` resets, `clear(staleId)` does **not** drop the live session, nothing is current before the first prepare. JVM total **104 → 109**.

## Preserved

LoadControl **2500/6000/500/2000** (untouched), MP2 extension, zero-copy SurfaceView, flat UI (0dp, no blue focus), channel keys, digit OSD, logo cache/prefetch switches, recent channels, EPG, SR, brand & app name. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **109 tests 0 failures**; lint **0 errors**/72 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` OK; APK 16,071,984 B ≤ 30 MB.
- CI pair receipt (run 44821763419, source `661b2b0f`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **109 tests 0 failures** (incl. PlaybackSessionTest 5); lint 0 errors / 70 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,010,835| `92ab44c53c0efe3124ad85295d3d882204c482ce0493a75be17c02b61de77544` |
| app-debug-androidTest.apk |502,955| `12b28c5bcc6028f183420d00584d9a50f69796219340efd48cc438b800c5c8de` |

- Artifacts: pair `14673020249` (`sha256:ebeefe2fa7982e6eac246eaba60fa075394168dc92ba79976f7b539dc4be904e`, zip 10,283,701 B); reports `14673125184` (`sha256:9d45fc6c9b15a380f7730b9f95cc412a46bb2bf03112857a314ddf5bb0dc7d8a`). Signer `0c4fd16374c0f7f5f5d44662b600bcde7f65d8ff10f11097a2996e6be5e050fe`.
- Version: `tv.iqtvlite.app.validation / 10111 / 2.2.0-tivimini-661b2b0f-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,010,835 B ≤ 30 MB.

## Device protocol (acceptance, not claimed here)

1. **Crash check first**: play CCTV-1 (MP2) for ≥2 minutes with the counters **off** → no FATAL, no `wrong thread` in logcat.
2. Then `adb shell setprop log.tag.IQTV-AUDIO DEBUG`, replay CCTV-1 for 60 s → exactly one `I/IQTV-AUDIO: active_ms=… underrun=… decoder=ffmpeg …` line, **no crash**.
3. Zap between channels several times while armed → no crash (the snapshot is replaced per prepare).
4. Re-run the t167 clear flow (CI10110 acceptance script) on this pair.
