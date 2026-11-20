# SURFACE_BASE / zero-copy rendering architecture + perf repair

2026-11-20. Task t38 (developer2). User decision: **APK size unlimited up to 30 MB; performance/run-speed is the top requirement.** This implements the zero-copy SurfaceView rendering base (planned in t30 §4 / t34 §4), raises the size budget, and fixes the USAB2-01 LoadControl crash that invalidated CI10077. Device verification is acceptance2's job; this does not claim the box is smooth.

## 1. Architecture: zero-copy SurfaceView base + transparent Compose overlay

`PlayerActivity` no longer hosts the video inside the Compose tree. The content view is a **native `FrameLayout`**:
- **Bottom layer**: `androidx.media3.ui.PlayerView` (default **SurfaceView**, zero-copy) — the video is on its own compositor layer, so it is *not* recomposited by the Compose UI and there is **no per-frame GPU copy** (vs the t30 TextureView).
- **Top layer**: a **transparent `ComposeView`** that renders all existing Compose UI (drawers, settings, banner, quality dialog, channel-switch spinner, digit entry). Root Compose `Box` is transparent — it draws only overlays, never an opaque full-screen layer.
- **Player owned by the Activity**: `videoView.player = newPlayer` when created, `= null` on release; the same `ExoPlayer` + same SurfaceView are **reused across every zap/catch-up** (`setMediaSource + prepare`, release only on `ON_STOP`). Compose receives `playerView` only to attach/detach (no `AndroidView`/`PlayerView` inside Compose anymore).

Why it fixes the two P0s: video no longer shares the app's RenderThread/GPU with Compose, so (a) single-core CPU drops (TextureView copy removed) and (b) opening/browsing the sidebar no longer drags the video into stutter — the video keeps its own layer.

## 2. Anti-grey-screen guard (learned from t30)

The t30 grey screen was a `SurfaceView` occluded by an opaque Compose window. Guards applied:
- The SurfaceView is a **direct child of the content `FrameLayout`** (index 0); the ComposeView is index 1 above it and has `setBackgroundColor(TRANSPARENT)`.
- The Compose root is a **transparent** `Box` (removed the opaque Material3 `Surface` wrapper). Overlays (drawer/settings/banner) carry their own opaque/translucent backgrounds only where they draw.
- `rootLayout` background is BLACK (so pre-first-frame is black, never grey).
- SurfaceView settings kept: `SHOW_BUFFERING_NEVER`, `keepContentOnPlayerReset(true)`, transparent shutter, `keepScreenOn`, non-focusable / `FOCUS_BLOCK_DESCENDANTS`.
- Regression checks for acceptance: SurfaceFlinger video layer visible (not occluded, not 1×1); BACK/exit no flash; drawer/settings/banner focus & hit-testing; TIV-01–18/23; SR-01/02/03.

## 3. Fix USAB2-01 (LoadControl crash) — CI10077 was invalid

CI10077 entered `PlayerActivity` and immediately `FATAL`: `setBufferDurationsMs(800, 6000, 300, 1000)` violates Media3's constraint `minBufferMs >= bufferForPlaybackAfterRebufferMs` (800 < 1000). Fixed to **`1000 / 6000 / 300 / 1000`** (min 1000 ≥ after-rebuffer 1000, ≥ bufferForPlayback 300) — still faster than the original 1500/6000/500/1000, without violating constraints. `setBackBuffer(0,false)` + `setPrioritizeTimeOverSizeThresholds(true)` kept.

**JVM guard test added**: `android/app/src/test/java/com/cjlhll/iptv/LoadControlConfigTest.kt` builds the real `DefaultLoadControl.Builder().setBufferDurationsMs(1000,6000,300,1000).build()` and asserts it does not throw, and asserts the old `800/6000/300/1000` set **does** throw `IllegalArgumentException`. This fails in JVM before device for this class of runtime-parameter bug (compile/JVM gates previously missed it). Device "enter player & can start playback" smoke remains acceptance's first check.

## 4. Key codes (correction)

The channel-key mapping in `dispatchKeyEvent` uses the constant names, which resolve to: **`KEYCODE_CHANNEL_UP`=166, `KEYCODE_CHANNEL_DOWN`=167, `KEYCODE_PAGE_UP`=92, `KEYCODE_PAGE_DOWN`=93** (merged with DPAD_UP/DOWN → prev/next channel when panels closed; panels keep DPAD navigation). Note: earlier ad-hoc text "adb keyevent 91/92" was **wrong** — `91` is `KEYCODE_MUTE`, `92` is `PAGE_UP`. Correct device test:
```
adb shell input keyevent 166   # CHANNEL_UP -> previous channel
adb shell input keyevent 167   # CHANNEL_DOWN -> next channel
adb shell input keyevent 92    # PAGE_UP -> previous
adb shell input keyevent 93    # PAGE_DOWN -> next
```

## 5. Retained from t34

Channel keys; LoadControl faster-start params (now legal); removed experimental decoder pre-warm; sidebar has **no per-frame animation** (instant width/offset, fade-only content). Hardware decode unchanged (`OMX.goke.*` AVC, `setEnableDecoderFallback(true)`); TIV/SR/JNI/R8/UA unchanged; MP2 not mixed in.

## 6. Budget / size / risk

- `scripts/verify_apk.py` `max_bytes` raised **14,454,072 → 30,000,000** with an in-file note recording the user authorization (2026-11-20 "最高30MB、性能最高优先"); dual-ABI/JNI/keep checks and true-byte reporting kept; no ABI/feature removed.
- Local APK ≈ 14,157,251 B — far under 30 MB (this refactor is roughly size-neutral vs t34; the SurfaceView base removes the TextureView, no new native lib).
- Risk: SurfaceView occlusion regression (mitigated + acceptance verifies SurfaceFlinger), focus/hit-testing of the ComposeView overlay, and the general Compose-over-video interaction — all covered by acceptance's 24-TIV/SR + gfxinfo pass. Revert is localized to `PlayerActivity.onCreate`/`VideoPlayerScreen` if needed.

## 7. Performance expectation & what acceptance2 must measure

Expected: steady-state single-core CPU **well below** the t34 TextureView ≈82% (zero-copy, no GPU copy); **open/browse sidebar no longer drops video frames**; cold start/TTFF and zap TTFF reduced (legal 1s min-buffer + no pre-warm). Measure on the delivered pair:
- gfxinfo framestats: 50th/95th, janky %, missed vsync, dropped frames — steady playback and **while opening/browsing sidebar**.
- Single-core CPU + PSS steady, and during sidebar open/browse (target: materially below 82%).
- Cold start TotalTime + TTFF; 30× zap TTFF p50/p95.
- SurfaceFlinger: video layer visible/not occluded (not z=-2 hidden, not 1×1); BACK/exit no flash.
- **First: "enter PlayerActivity, no crash, video starts" smoke** (the USAB2-01 class), then 24-TIV + SR-01/02/03.

## 8. CI receipt (run78, 44651507384)

- Source commit [c82593f5a5edcd9e30dc9001844bdd5f0923bad9](https://github.com/zqs1qiwan/iqtvlite/commit/c82593f5a5edcd9e30dc9001844bdd5f0923bad9), branch integration/tivimini-baseline. Ordinary push only; main/tags untouched; this doc is a docs-only follow-up commit.
- [APK CI run 44651507384](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44651507384), run78, workflow_dispatch with isolated_test_package=true: **successful**. Fresh CARGO_TARGET_DIR, NDK r27 27.0.12077973, cargo-ndk arm64-v8a+armeabi-v7a at API24, BUILD SUCCESSFUL; both-ABI JNI + "Shared class/method/field runtime contract passed". Cloud JVM **23 tests 0 failures** incl. the new `LoadControlConfigTest` (2, guards USAB2-01); lint 0 errors / 47 warnings / 5 hints.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| [Application debug/app-debug.apk](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44651507384/artifacts/14591332740) |14,126,355| `ed165e6e06dd9b7a9e86d74294b2db0aaf45016c098364d12f1e3fd94eab7285` |
| [Test androidTest/debug/app-debug-androidTest.apk](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44651507384/artifacts/14591332740) |502,955| `5564f5a21df3d1968d6b21a3eae7cf1ad6324eba5188c692c95fba085725ac71` |

- [Paired artifact 14591332740](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44651507384/artifacts/14591332740) named iqtvlite-test-apk, zip digest `sha256:7807fca77a5e1c348f14cac2a76baa5a853cbd89d4c68a8e890ac5c127c65d1a`,12,617,971B, retention90 days, expiry2027-02-18 01:09:40 UTC. [Reports artifact 14591247973](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44651507384/artifacts/14591247973) named iqtvlite-android-reports, `sha256:8d8ec353c020d43df280c5354bffeea2547385a52b182d13ada8ce7028cba614`,3,245,557B. Actions artifacts, not a public Release.
- Both APKs signer SHA256 `bc52f452bb0677a726fb8c96f53a8846dffdebe8fee5fa70a8dd8135f05a641c` (Android Debug, fresh runner cert; differs from prior runs; no stable cross-run upgrade guarantee).
- Main applicationId tv.iqtvlite.app.validation; label "iqtvlite 验证版"; versionCode10078, versionName2.2.0-tivimini-c82593f5-validation; min24,target35,compile36; native-code 'arm64-v8a' 'armeabi-v7a'. Test package tv.iqtvlite.app.validation.test; target tv.iqtvlite.app.validation; runner AndroidJUnitRunner.
- instrumentation-contract.json: app_sha256/test_sha256 equal the table hashes above; missing_types/methods/fields all empty.
- Size: main APK **14,126,355 B** — well under the raised 30,000,000 B budget, and slightly smaller than t34 (the TextureView path/artifacts removed; no new native lib, no feature/ABI removed).
- **Device verification is acceptance2's work on this exact pair.** First smoke: enter PlayerActivity → no crash (USAB2-01 must not recur) → video starts. Then SurfaceFlinger video layer visible/not occluded, gfxinfo (steady + sidebar open/browse), single-core CPU/PSS, cold start/TTFF, 30× zap TTFF, 24-TIV + SR-01/02/03. Not verified by the developer; this does not claim the box is now smooth.

To reproduce:

```sh
gh run download 44651507384 -R zqs1qiwan/iqtvlite -n iqtvlite-test-apk -D downloaded-apks
gh run download 44651507384 -R zqs1qiwan/iqtvlite -n iqtvlite-android-reports -D downloaded-reports
```
