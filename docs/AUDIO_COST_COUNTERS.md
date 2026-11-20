# AUDIO COST COUNTERS — debug-only (t165)

2026-11-21. Task t165 (developer2), on CI10108 source (after t154). Quantifies the audio side of the playback CPU so the MP2 cost can be separated from a real regression.

**Nothing in the normal path changed**: `LoadControl` stays **2500/6000/500/2000**, the renderer factory, decoder fallback, UI, zero-copy SurfaceView, channel keys, digit OSD, logo cache, recent channels, EPG, SR and brand are untouched.

## Enabling (default OFF)

```bash
adb shell setprop log.tag.IQTV-AUDIO DEBUG      # arm the counters (debuggable builds only)
adb logcat -s IQTV-AUDIO                        # read the summary lines
```
The flag is evaluated per session as `BuildConfig.DEBUG && Log.isLoggable("IQTV-AUDIO", Log.DEBUG)`, so a **release/user build can never emit it**, and an armed session costs only a handful of atomics.

## What is counted (`AudioCostCounters`, pure Kotlin)

| Field | Source | Meaning |
|---|---|---|
| `active_ms` | `onActiveChanged` / `onActiveMs` | how long the audio renderer was enabled during the session |
| `underrun` | `AnalyticsListener.onAudioUnderrun` (new hook) | sink starved — the CPU-relevant failure mode for MP2 |
| `retry` | `onAudioSinkError` + `onAudioCodecError` | sink/codec errors that force re-initialisation |
| `tracks` | `onAudioEnabled` | AudioTrack activations (1 = normal, >1 = churn) |
| `rendered` / `skipped` | `onAudioDisabled(decoderCounters)` | output buffers rendered vs dropped |
| `decoder` | `onAudioDecoderInitialized` | `c2.…` (platform MediaCodec) vs `ffmpeg` (MP2 extension) |
| `encoding` / `rate` / `buffer` | `onAudioTrackInitialized(AudioTrackConfig)` | AudioTrack parameters |

Counters are **inert when the flag is off** (`if (!enabled) return` at every hook) and `reset()` runs at every `prepare()`.

## The one line

60 s after a session starts (guarded by the media-id, so a zap cancels it; cancelled on release too):

```
I/IQTV-AUDIO: active_ms=60000 underrun=0 retry=0 tracks=1 rendered=2804 skipped=0 decoder=ffmpeg encoding=2 rate=48000 buffer=16384
```

- `decoder=ffmpeg encoding=2` → **MP2 (mpeg-L2) is being decoded by the FFmpeg extension and fed to a real AudioTrack** (`encoding=2` is `ENCODING_PCM_16BIT`), i.e. audio is genuinely active.
- `decoder=c2.android.aac.decoder` on an AAC channel → platform path, for the CI10093-style comparison.

## How to use it for the MP2-vs-AAC question

1. Install this pair, `setprop log.tag.IQTV-AUDIO DEBUG`.
2. Play **CCTV-1 (MP2)** for 60 s → capture the line (expect `decoder=ffmpeg`, `underrun≈0`, `tracks=1`).
3. Play an **AAC** channel for 60 s → capture the line (`decoder=c2.android.aac.decoder`).
4. Compare app CPU for the two; the difference is the audio cost of the MP2 path, **not** a LoadControl regression (`Loader` is already back to ~5.5 %).
5. If `underrun`/`retry` are high on MP2, that is a genuine audio problem (buffer too small / decoder starved) — fix would be an audio-sink buffer bump, which would be a **separate** pair for clean attribution.

## Tests

`AudioCostCountersTest` (5, pure JVM): inert when disabled (no counter moves, no decoder recorded), the exact summary line shape, `decoder=none`/`encoding=none` placeholders for an empty session, `reset()` clears everything, and `active_ms` accumulates while ignoring non-positive input. JVM total **94 → 99**.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **99 tests 0 failures**; lint **0 errors**/72 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; APK 16,069,060 B ≤ 30 MB.
- CI pair receipt (run 44817653551, source `4cbfc2e5`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **99 tests 0 failures** (incl. AudioCostCountersTest 5); lint 0 errors / 70 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,010,839| `070da23db904b45475263752a3998b15cff2082fd011cbd321e74ff1864c59b8` |
| app-debug-androidTest.apk |502,955| `9fac4614499fb89a850330c11afe32c3bcdecb1d6730c8fc004d44d5a09198d2` |

- Artifacts: pair `14671605358` (`sha256:c8257cd7e1fd1914821c8754ed9be4f2c29123e69537b7c279e8994ccd57dc71`, zip 10,280,669 B); reports `14671405565` (`sha256:17fa011da8202a19b3202ae406b6a0f4c154e8874d607221014c9ba88e606351`). Signer `ecc4882ef40e476e272126395cbadcfdf96e2d041f7b012e8cd94309c291be04`.
- Version: `tv.iqtvlite.app.validation / 10109 / 2.2.0-tivimini-4cbfc2e5-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,010,839 B ≤ 30 MB.
