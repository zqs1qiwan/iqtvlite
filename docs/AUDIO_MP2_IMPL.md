# AUDIO MP2 — feasibility & integration plan (t116)

2026-11-21. Task t116 (developer2). Goal: software-decode **MP2 (MPEG-1 Layer II)** audio so channels whose platform decoder is missing still produce sound, using the Media3 ExoPlayer FFmpeg audio extension. **Feasibility + minimal landing; do not break the stable CI10093 build.**

## 1. Verdict

**Feasible, but the decoder extension cannot be built on this Windows host and must be built in Linux CI** (Media3 explicitly does not support building the module on Windows). This document ships the reproducible build pipeline (`scripts/build_media3_ffmpeg_audio.sh` + `.github/workflows/ffmpeg-audio.yml`) and the exact integration steps; the AAR build is dispatched from CI rather than a half-built local artifact.

## 2. Evidence (what the code actually does)

- Project Media3 version: **1.11.1** (`android/app/build.gradle.kts`).
- Media3 1.11.1 `FfmpegLibrary.getCodecName` maps **`AUDIO_MPEG_L2` → `"mp3"`** (MP2 is decoded by FFmpeg's mp3-family decoder):
  source: `androidx/media@1.11.1` `libraries/decoder_ffmpeg/.../FfmpegLibrary.java` (fetched, HTTP 200). It also maps `AUDIO_MPEG`, `AUDIO_MPEG_L1` → `"mp3"`.
- `MimeTypes` in this project's media3-common-1.11.1 exposes `AUDIO_MPEG_L1` / `AUDIO_MPEG_L2` (verified via `javap`).
- Media3 **does not publish** the decoder extension on Google Maven; it must be built from the `androidx/media` checkout (README fetched, HTTP 200): clone `libraries/decoder_ffmpeg/src/main/jni/ffmpeg` at `release/6.0`, run `build_ffmpeg.sh`, then `./gradlew :lib-decoder-ffmpeg:assembleRelease`.
- CI runner is `ubuntu-latest` with `nttld/setup-ndk@v1` NDK **r27** already used by `apk.yml` → same toolchain requirement is satisfied in CI.

## 3. Build (audio-only, armv7 + arm64)

`scripts/build_media3_ffmpeg_audio.sh <media3-dir> <ndk-path> [android-abi]`:

- media3 tag **1.11.1**, FFmpeg **release/6.0**, NDK **r27**, `ANDROID_ABI=21` (project minSdk 24 ≥ 21).
- `ENABLED_DECODERS=(mp3 aac ac3 eac3 flac opus vorbis alac pcm_mulaw pcm_alaw)` — **audio only** (no h264/hevc) to keep the library small. MP2 is covered by `mp3`.
- Output: `libraries/decoder_ffmpeg/build/outputs/aar/decoder_ffmpeg-release.aar` (arm64-v8a + armeabi-v7a; the stock `build_ffmpeg.sh` loops all four ABIs — trim to the two used by the app by editing its ABI list or rely on Gradle ABI filters).

`.github/workflows/ffmpeg-audio.yml` (manual `workflow_dispatch`) builds it on Ubuntu with NDK r27, caches the FFmpeg source, and uploads the AAR artifact. It is **separate from `apk.yml`** so the stable release path is untouched.

## 4. Integration once the AAR exists

1. Put it at `android/app/libs/media3-decoder-ffmpeg-release.aar` and add to `android/app/build.gradle.kts`:
   ```kotlin
   implementation(files("libs/media3-decoder-ffmpeg-release.aar"))
   ```
2. Enable the renderer as a **fallback only** in `PlayerActivity` (around the existing `DefaultRenderersFactory(context)` at ~L454):
   ```kotlin
   val renderersFactory = DefaultRenderersFactory(context)
       .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
   ```
   **Use `ON`, not `PREFER`.** `ON` keeps `MediaCodecAudioRenderer` (hardware/platform) as the first choice and only falls back to FFmpeg when the platform cannot decode the track — so **AVC/AAC channels keep hardware/platform decode (no audio-sync or power regression)**, while MP2 (unsupported by the platform) falls back to FFmpeg. `PREFER` would force software decode of AAC too and risk sync/CPU regressions.
3. Optionally log `FfmpegLibrary.getVersion()` once at startup to confirm the extension loaded.
4. Add an LGPL notice (see §6).

## 5. Size / budget

- Audio-only FFmpeg `.so` + JNI wrapper, stripped, is ~2–4 MB **per ABI**; two ABIs ≈ 4–8 MB in the APK.
- Current CI10093 APK is 10,404,860 B; expected final ≈ **15–18 MB**, still **≤ 30 MB**. If tight, drop `ac3/eac3/dca/truehd` (MVP only needs `mp3` for MP2 + keep `aac`), saving ~1–2 MB.

## 6. Licensing

FFmpeg is **LGPL-2.1-or-later** when built without `--enable-gpl` (the default build_ffmpeg.sh config). The Media3 wrapper is Apache-2.0. Shipping the `.so` requires: keep FFmpeg as dynamically-loaded `.so` (as the extension does), include the LGPL text + FFmpeg source offer in `NOTICE`/about screen. Do **not** enable `--enable-gpl`.

## 7. Risks / open items

1. **NDK r27 vs README-tested r26b** for `build_ffmpeg.sh`. If r27's clang rejects the FFmpeg 6.0 configure step, pin **r26b for this extension build only** (the app/Rust NDK stays r27; the AAR is a prebuilt artifact).
2. **MP2 MIME**: Media3 maps `audio/mpeg-L2`→`mp3`; the TS extractor usually reports `audio/mpeg-L2` or `audio/mpeg`, both mapped. Device verification must confirm a real MP2 channel now produces `AudioSink` output (the CI t113/t114 runs showed `AudioSink` 0 because the test channels were AAC).
3. **Audio/video sync**: FFmpeg audio renderer outputs PCM to the same `AudioSink`; validate A/V sync on an MP2 channel.
4. **CI build time**: first run ~30–60 min (FFmpeg configure+build per ABI). Cache the FFmpeg source; consider committing the built AAR to a Release asset (not `main`) after the first successful build to avoid rebuilding per app run.

## 8. Effort estimate

- Build the AAR in CI + fix toolchain flags: **0.5–1 day**.
- Integrate AAR + renderer mode + NOTICE + size check: **0.5 day**.
- Device audio verification (MP2 channel sound, AAC/AVC no regression, sync, no crash): **0.5–1 day** (acceptance).

## 9. Status of this task

- Delivered: feasibility evidence, reproducible Linux CI build pipeline, exact integration steps, size/licence/risk analysis. **No app behaviour changed** (extension not enabled until the AAR is built), so CI10093 look/interaction/performance are untouched.
- Not delivered: the compiled AAR and an MP2-enabled pair. Per the task's fallback clause ("若 CI 内不可行 → 给可复现的构建日志/证据 + 推荐路径与工作量评估"), the CI build is dispatched separately; once the AAR artifact is green, integration is a small, gated change (steps §4) — no half-baked code merged.
