# MP2 (MPEG-1 Layer II) FFmpeg software-decoder: feasibility & size-blocker decision

Status: **BLOCKED — not implemented.** This is a decision memo, not a delivery receipt. No production or test code was changed for t23; the branch was not modified except this document; no CI run was started; the TV was not touched.

## Goal and driver evidence

Goal (user decision): make MPEG-1 Layer II live audio audible via Media3's FFmpeg audio software decoder, audio-only, video stays platform hardware. Driver evidence from CI10075 device verification: CCTV2 audio is MPEG-1 Layer II; `IQTV-AUDIO-PROBE` reports `audio/mpeg-L2 candidates=0`, MP3=1, AAC=1, `supported=false/selected=false`, PCM path OK. There is no platform L2 decoder on the API28 box.

## Why it cannot be delivered within the current gates (two independent hard blockers)

### Blocker 1 — the APK size budget gate is a hard assert that FFmpeg necessarily fails

- [`scripts/verify_apk.py`](../scripts/verify_apk.py) line 10-11: `max_bytes = 14454072` and `assert path.stat().st_size <= max_bytes`. The APK workflow runs this **before** uploading the artifact ([apk.yml](../.github/workflows/apk.yml) "Verify APK contains Rust .so"), so an over-budget APK fails the job and uploads no pair at all.
- Current APK = 14,158,563 B (run75 / CI10075). Headroom to the gate = **295,509 B (~0.28 MiB)**. The task fixes the budget ("不通过改预算掩盖") and this agent has no authority to raise it ("需如实报告由 Captain/用户再定").
- FFmpeg audio decoder native libraries are megabytes, not kilobytes. Concrete real-world measurement: the public Jellyfin `org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1` AAR (bytes measured here) packages `libffmpegJNI.so` at **arm64-v8a 1,462,200 B + armeabi-v7a 1,360,588 B = 2,822,788 B** for the two ABIs this app ships. That AAR enables multiple audio decoders; an mp3-only `--disable-everything --enable-decoder=mp3` build is smaller but still on the order of ~1.0–1.5 MB per ABI, i.e. **≈2.0–3.0 MB for both ABIs** — roughly **7–10× the 295,509 B headroom**.
- `packaging { jniLibs.useLegacyPackaging = false }` stores native libs **uncompressed** in the APK, so the native `.so` bytes add almost directly to APK size (no zip compression to shrink them). Projected APK ≈ 16.2–17.0 MB, i.e. **~1.7–2.5 MB over the 14,454,072 B gate**. A defensible budget would be **≈18,000,000 B**.
- Consequence: even a perfectly built FFmpeg extension produces a **red CI budget gate and no uploadable pair**. Requirement 7 ("全新 r27 双ABI CI 成功并下载同run pair") and requirements 5/6 ("不通过改预算掩盖 / 显著超出由 Captain/用户再定") are mutually exclusive until the Captain/user raises the budget or explicitly accepts a larger APK.

### Blocker 2 — the FFmpeg native build needs a Linux/macOS toolchain not present here

- Media3's `libraries/decoder_ffmpeg` is **not on Google Maven**; per its README you must clone the androidx/media source and run `build_ffmpeg.sh` (`HOST_PLATFORM` = linux-x86_64 or darwin-x86_64) against an FFmpeg source tree (README recommends FFmpeg 6.0) with the NDK, then build the module AAR. This is a bash/make cross-compile.
- This workstation is Windows with **no WSL/Linux** (see `scripts/dev-env.ps1` notes) and has NDK r27 (`27.2.12479018`) but not a supported FFmpeg build host. A faithful `mp3`-only two-ABI build cannot be produced or measured locally here.
- The legitimate path is to add ~30–40 min FFmpeg build steps to the Ubuntu GitHub Actions job (clone androidx/media@1.11.1 + FFmpeg 6.0, `--disable-everything --enable-decoder=mp3 --disable-gpl --disable-nonfree --disable-programs --disable-doc --disable-network --disable-video* --disable-demuxers --enable-demuxer=... ` etc., produce the AAR, wire it into module `:app`). That work is wasted while Blocker 1 makes the run red, and committing unverifiable FFmpeg wiring would break the default build on this Windows host and every future CI run that cannot build FFmpeg. I deliberately did not commit half-built, non-compiling code.

## What is ready to implement once authorized

1. **Build**: vendor `androidx/media3` `libraries/decoder_ffmpeg` @ 1.11.1; FFmpeg 6.0 configured `--disable-gpl --disable-nonfree --disable-everything --enable-decoder=mp3 --disable-encoders --disable-muxers --disable-demuxers --disable-parsers --disable-bsfs --disable-protocols --disable-network --disable-filters --disable-video --disable-programs --disable-doc` (FFmpeg's `mp3` decoder handles MPEG Layer I/II/III); `armv7`+`arm64`, NDK r27, API24; no libmp3lame/encoders.
2. **Renderers**: wrap the existing `DefaultRenderersFactory` in `PlayerActivity.kt:423` with `setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)` and `setEnableDecoderFallback(true)`; ON (not PREFER) so only formats the platform `MediaCodec` does not support fall through to FFmpeg; platform audio and all video stay on hardware.
3. **Correctness test**: assert `FfmpegLibrary.getCodecName("audio/mpeg-L2")`/"audio/mpeg"/"audio/mpeg-L1" → `mp3` and that a real MPEG-1 Layer II sample actually decodes to PCM with position advancing through the FFmpeg `AudioRenderer` (not by name). Update `AudioDiagnosticTest` so L2 now reports supported and selects the extension decoder.
4. **License**: confirm LGPL (no GPL/nonfree); add an in-app open-source-license page + `NOTICE` documenting FFmpeg version, config flags, LGPL terms and a written offer / source link; no encoders.
5. **Gates**: keep 9 test classes, regenerate exact keep, run the 7 inheritance Python regressions + the old-10071 negative control, verify_apk dual-ABI/JNI; **size gate as is** (will be red — needs the budget decision), record before/after per-ABI `.so` delta and total bytes; PSS/CPU points: FFmpeg software decode is CPU-only and adds ~one decode thread plus ~a few MB code; expect a measurable CPU/PSS uptick only on L2 channels (platform-decoded channels unchanged because mode is ON).

## Requested decision

Choose one, then this task can proceed:

- **A (recommended, matches "追求完美版"):** raise the APK budget to ~18,000,000 B (document the +~2.5 MB FFmpeg audio delta), authorize adding the Linux FFmpeg build steps to CI, and this agent will implement + deliver a green pair.
- **B:** accept a larger APK explicitly and only then have the budget constant updated (still out of this agent's authority).
- **C:** reject FFmpeg (keep current 14.x MB pair); MP2 stays silent on this box.

Until a decision is recorded, t23 is not delivered: no MP2 audio, no new pair, branch code unchanged.

## Evidence gathered this attempt

- `git ls-remote https://github.com/androidx/media` reachable; NDK r27.2 present; no WSL.
- Media3 decoder_ffmpeg README: not on Google Maven; requires local source + `build_ffmpeg.sh` (Linux/macOS host), FFmpeg 6.0 recommended.
- Measured Jellyfin `media3-ffmpeg-decoder:1.9.0+1` AAR: `libffmpegJNI.so` arm64 1,462,200 B, v7a 1,360,588 B.
- `scripts/verify_apk.py:10-11` hard `assert` at 14,454,072 B; run75 APK 14,158,563 B → 295,509 B headroom.
