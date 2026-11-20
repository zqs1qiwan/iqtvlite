# AUDIO MP2 — integration (t117)

2026-11-21. Task t117 (developer2). Wires the Media3 FFmpeg **audio-only** decoder into the app as a **fallback** so MP2 (MPEG-1 Layer II) channels that have no platform decoder still produce sound. Based on the CI10093 stable build; no look/interaction/performance change.

## 1. What was integrated

| Item | Value |
|---|---|
| AAR (committed) | `android/app/libs/lib-decoder-ffmpeg-release.aar` |
| AAR size / sha256 | 1,266,615 B / `e63afb2faedf1da7aa6c3c96b33f8ebee9717dcc133d235d3fd8e88df03acfec` |
| Source AAR (CI build) | run 44771318170, 2,567,827 B, sha256 `d531feb1d534803a09cf149f99435ca6252da3fb907bc9d170566f9a4ba106a7` |
| Bundled ABIs | `arm64-v8a` (`libffmpegJNI.so` 1,341,096 B) + `armeabi-v7a` (`libffmpegJNI.so` 1,155,156 B); x86/x86_64 **stripped** |
| Decoders | mp3 (covers MP2 via Media3's AUDIO_MPEG_L2→"mp3"), aac, ac3, eac3, flac, opus, vorbis, alac, pcm_mulaw, pcm_alaw |
| Gradle | `implementation(files("libs/lib-decoder-ffmpeg-release.aar"))` in `android/app/build.gradle.kts` |

The stripped AAR keeps the original `proguard.txt` consumer rules (`-keep … androidx.media3.decoder.ffmpeg.** { native <methods>; }` and `FfmpegAudioDecoder.growOutputBuffer`), so the JNI entry points survive R8 automatically.

## 2. Renderer strategy — fallback only (no regression)

`PlayerActivity`:
```kotlin
val renderersFactory = DefaultRenderersFactory(context)
    .setEnableDecoderFallback(true)
    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
```
- **`EXTENSION_RENDERER_MODE_ON`** keeps `MediaCodecAudioRenderer` first; FFmpeg is used **only when the platform can't decode the track**. So **AVC/AAC channels keep hardware/platform decode — no A/V-sync or power regression.**
- Deliberately **not** `PREFER` (would force software decode of AAC and risk sync/CPU regressions).

## 3. Licence (LGPL) shipped in the APK

- `android/app/src/main/assets/NOTICE_FFMPEG_LGPL.txt` is bundled and states FFmpeg is LGPL-2.1-or-later, built **without `--enable-gpl`**, dynamically loaded `.so`, with source pointers (ffmpeg.org release/6.0 + androidx/media decoder_ffmpeg) and the LGPL URL.
- Media3 wrapper is Apache-2.0.

## 4. R8 / keep contract

- No manual keep rule was added; the AAR's consumer `proguard.txt` merges into the app build.
- `generate_instrumentation_keep.py` still yields **210 roots, KEEP IDENTICAL**; pair contract missing types/methods/fields = 0; `test_dex_contract.py` 7 OK. No R8/keep regression.

## 5. Size

- Local APK: **15,996,266 B (~15.3 MiB)** — baseline CI10093 was 10,404,860 B; the two `libffmpegJNI.so` add ~2.5 MB (APK stores native libs uncompressed) plus the Rust libs already present. **≪ 30 MB.**
- Confirmed inside the APK: `lib/arm64-v8a/libffmpegJNI.so` (1,341,096 B), `lib/armeabi-v7a/libffmpegJNI.so` (1,155,156 B), `assets/NOTICE_FFMPEG_LGPL.txt`.

## 6. Not changed

Look/interaction (flat UI, no blue focus), channel keys 166/167, LoadControl `1000/6000/300/1000`, EPG, SR, zero-copy base, grey-screen fix, brand & app name. Only the audio renderer factory line, the dependency, the AAR asset and the licence asset were added.

## 7. Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **37 tests 0 failures**; lint **0 errors**/66 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK. APK 15,996,266 B.
- Clo**ud pair receipt: _(filled after CI run completes)_.

## 8. Device audio verification (acceptance, not claimed here)

Install the pair on 192.168.2.245 and verify: (a) an MP2 channel now outputs audio (`AudioSink` active / audible); (b) AAC/AVC channels still use hardware/platform decode and stay in sync; (c) no new crash on channel switch / drawer; (d) APK size and startup unchanged. `FfmpegLibrary.getVersion()` can be logged to confirm the extension loaded.
