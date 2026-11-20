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

> Dispatch note: GitHub `workflow_dispatch` only looks up a workflow that exists on the **default branch**, and this repo's default branch is `main` (which we must not push to). So the build is driven through the existing `apk.yml` with the new boolean input **`build_ffmpeg_audio=true`** (a conditional `build-ffmpeg-audio` job), which is dispatchable on `integration/tivimini-baseline`. `ffmpeg-audio.yml` is kept as the standalone definition for when it can be added to the default branch.

## 3b. Build verification — ACTUAL (green)

The pipeline was executed end-to-end in GitHub Actions on `integration/tivimini-baseline`:

- Run [44771318170](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44771318170) job **Build Media3 FFmpeg audio AAR: success** — `BUILD SUCCESSFUL in 3m 9s` (FFmpeg source cache warm), NDK **r27**, FFmpeg **release/6.0**, media3 **1.11.1**.
- Produced `libraries/decoder_ffmpeg/buildout/outputs/aar/lib-decoder-ffmpeg-release.aar`:
  - **2,567,827 bytes**, sha256 `d531feb1d534803a09cf149f99435ca6252da3fb907bc9d170566f9a4ba106a7`
  - `jni/arm64-v8a/libffmpegJNI.so` 1,341,096 B; `jni/armeabi-v7a/libffmpegJNI.so` 1,155,156 B (plus x86/x86_64 not needed by the app); `classes.jar` 13.5 KB.
  - Artifact `media3-decoder-ffmpeg-aar` id `14648181799` (zip 2,558,951 B).
- First cold run (44769973279) also built the AAR successfully in ~3m; it only failed at my post-build `ls` of the wrong path (`build/` vs media3's `buildout/`), which is fixed. (The APK job in 44771318170 failed only because I passed `isolated_test_package=false`, so the Rust-.so verifier that expects the `.validation` package rejected it — unrelated to the AAR.)

**Size projection:** bundling only arm64-v8a + armeabi-v7a adds **≈2.5 MB** to the APK (10.4 MB → ≈12.9 MB), well under 30 MB (drop the x86/x86_64 ABIs via `abiFilters`).

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

- Delivered: feasibility evidence, a **green CI build of the audio-only AAR** (sha256 `d531feb1…a7`, 2.57 MB), reproducible Linux CI build pipeline, exact integration steps, size/licence/risk analysis. **No app behaviour changed** (extension not enabled until integration), so CI10093 look/interaction/performance are untouched.
- Next (awaiting go-ahead, not half-baked): integrate the AAR + `EXTENSION_RENDERER_MODE_ON` + NOTICE (steps §4), rebuild the pair, and hand to acceptance for MP2 audio verification. Estimated 0.5 day integration + 0.5–1 day device verification.
