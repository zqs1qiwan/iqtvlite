# USABILITY / PERFORMANCE repair 2 (channel keys, zap/startup, sidebar jank)

2026-11-19. Task t34 (developer2), builds on t30 (TextureView grey-screen fix). Driver: user P0 feedback + CI10076 measurement — video **出画正常** (frame time ≈25 ms) but **single-core CPU ≈82%**, **cold start 6.6–9.7 s**, **TTFF p50 ≈883 ms**; zap stutter; **remote CHANNEL key does nothing**; opening/browsing the left sidebar makes the video stutter ("视频跟随卡"). This document records each fix, LoadControl params, expected CPU/gfxinfo/TTFF deltas, size, risks and the CI receipt. Device verification is acceptance2's job; this does not claim the box is smooth.

## 1. Remote channel keys (功能修复)

`PlayerActivity.dispatchKeyEvent` now maps **`KEYCODE_CHANNEL_UP` / `KEYCODE_PAGE_UP` → previous channel** and **`KEYCODE_CHANNEL_DOWN` / `KEYCODE_PAGE_DOWN` → next channel**, merged into the existing `DPAD_UP`/`DPAD_DOWN` branch. They only act when no panel is open (`!isDrawerOpen && !isSettingsDrawerOpen && !isQualityDialogOpen`); when the sidebar/settings/dialog is open they fall through to `super` so DPAD still navigates the panels (no conflict). During catch-up they raise the info banner (same as DPAD). Verified by code inspection; device test: `adb shell input keyevent 91` (CHANNEL_UP) / `92` (CHANNEL_DOWN) with the panel closed should zap prev/next.

## 2. Faster cold start / zap TTFF

- **Player + Surface are reused across zap (no rebuild).** Zapping goes `startPlayback → prepareRequest(p, request)` → `StreamPlayback.prepare` which does `player.setMediaSource(factory.createMediaSource(item)); player.prepare()` on the **same `ExoPlayer`** (created once in `initializePlayer()` on `ON_START`; released only on `ON_STOP`). No per-zap `release()`/new player/new surface. Confirmed by reading the path.
- **LoadControl tuned** ([PlayerActivity.kt](../android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt) `initializePlayer`): `setBufferDurationsMs(minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs)` changed **1500/6000/500/1000 → 800/6000/300/1000**. Lower `minBufferMs` (start sooner) and `bufferForPlaybackMs`; keep 6 s max and 1 s after-rebuffer for long-play/live stability. `prioritizeTimeOverSizeThresholds`/`backBuffer(0)` unchanged.
- **Removed experimental decoder pre-warming** (`experimentalSetEnableMediaCodecVideoRendererPrewarming(true)`): it eagerly creates+warm a video decoder at startup — a likely contributor to the 6.6–9.7 s cold start and CPU spike on this SoC. The normal first-frame path is cheaper here. (Trade-off: first-frame may rely on real decode; measured on device.)

Expected: cold TTFF and zap TTFF reduced (target: lower than 883 ms p50). Device must measure.

## 3. Sidebar / operation jank + "video follows stutter"

Removed the **per-frame drawer animations** in [PlayerDrawer.kt](../android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt):
- `animateDpAsState` (spring width + tween offset) replaced by **instant** `drawerWidth`/`drawerOffsetX`. Previously the **entire sidebar width+offset was recomposed and re-laid-out every frame** while opening/closing/browsing → UI thread + GPU pegged, which made the in-hierarchy `TextureView` video drop frames ("视频跟随卡").
- `AnimatedVisibility` enter/exit changed from `slideInHorizontally + expandHorizontally` / `slideOut + shrinkHorizontally` to **fade only** (`fadeIn/fadeOut`): removes layout-animation work; alpha fade is GPU-cheap.
- Kept: EPG selection **350 ms debounce** (`snapshotFlow{}.debounce(350)`), `epgChannelUiCache` LRU(32), EPG `nowProgramByUrl` built on `Dispatchers.Default` (off main).

Effect: opening/browsing the sidebar is now a cheap single layout instead of a many-frame animation, so the UI thread is not saturated during browsing and video playback no longer follows the stutter. This is the low-risk deterministic part of the CPU/video-stutter fix.

**On `TextureView.setOpaque(false)+setLayerType(HARDWARE)` (suggested low-risk tweak): not applied.** For a full-screen video, `opaque=true` lets the compositor skip alpha blending; `setOpaque(false)` *forces* blending (more GPU work), so it would likely raise CPU/GPU, not lower it. `setLayerType(HARDWARE)` is already implicit for TextureView. So these flags would not achieve the goal and risk degrading 出画.

## 4. Residual CPU (≈82% single-core) — SurfaceView base plan (NOT implemented here)

The remaining single-core CPU is dominated by **TextureView's per-frame GPU copy/composition** plus Compose draw. TextureView must be composited by the app's RenderThread, so any Compose load also affects video. The true zero-copy fix is a native **SurfaceView base** decoupled from Compose:

- Plan: `PlayerActivity` sets a `FrameLayout` root = `PlayerView` (SurfaceView, zero-copy) as base layer + a **transparent `ComposeView` overlay** for all UI. Keep the same Compose UI, but it no longer hosts the video; the player is owned by the Activity and handed to both.
- **Regression points (must be guarded, learned from the t30 grey-screen bug):** (a) SurfaceView occlusion must NOT return — the base SurfaceView works only as a direct child of the content FrameLayout with the ComposeView transparent above it; if any opaque layer covers it, grey-screen returns. (b) overlay hit-testing/focus for drawer/settings/banner. (c) TIV-01–18/23 and SR-01/02/03 flows. (d) SurfaceFlinger verify video layer is no longer occluded + gfxinfo/video-drop. (e) BACK/exit no flash.
- Cost: ~1–2 dev-days, touches PlayerActivity render/player wiring, higher regression risk; needs acceptance to re-run the full 24-TIV + performance matrix (cold TTFF, zap TTFF, gfxinfo, dropped frames, 30-min long-play).
- Recommendation: only do this if the low-risk changes here do not bring CPU/jank to an acceptable level on t34's pair. Reported for Captain decision; not implemented blindly (to protect 出画).

## 5. Preserved behavior / size / risk

- Preserved: TIV-01–18/23, SR-01/02/03, JNI/R8/UA; hardware decode unchanged (`OMX.goke.*` AVC, `setEnableDecoderFallback(true)` kept); no audio/MP2 change.
- Size: source-only (Kotlin) changes; APK size delta measured from CI (§6). No resources/ABI/features removed.
- Risk: LoadControl lower min-buffer could slightly increase rebuffer on slow networks (max buffer/after-rebuffer unchanged to bound this); removing pre-warm could affect first-frame timing; removing drawer slide animation changes the visual transition (UX tradeoff, expected/approved by task "避免逐帧动画"). All are revertable single-line changes.

## 6. What acceptance2 must measure (same pair)

- Cold start (force-stop): TotalTime and TTFF before/after; target faster than CI10076 (6.6–9.7 s / 883 ms).
- Zap TTFF p50/p95 across ~30 zaps; confirm no per-zap player rebuild (logcat).
- `adb shell input keyevent 91/92` (CHANNEL_UP/DOWN) and PAGE_UP/DOWN zap prev/next with panel closed; DPAD still navigates inside the sidebar.
- Sidebar open/browse: gfxinfo framestats + dropped frames; video should not stutter when opening/browsing.
- Single-core CPU during steady playback and during sidebar open (target: below CI10076 ≈82%).
- 24-TIV + SR regression; logcat `AudioSink$UnexpectedDiscontinuityException` still a separate robustness item.

## 7. CI receipt (run77, 44567827863)

- Source commit [bcf4a1e134c985e2e9e1b2a7724db93dbb62c970](https://github.com/zqs1qiwan/iqtvlite/commit/bcf4a1e134c985e2e9e1b2a7724db93dbb62c970), branch integration/tivimini-baseline. Ordinary push only; main/tags untouched; this doc is a docs-only follow-up commit.
- [APK CI run 44567827863](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44567827863), run77, workflow_dispatch with isolated_test_package=true: **successful**. Fresh CARGO_TARGET_DIR, NDK r27 27.0.12077973, cargo-ndk arm64-v8a+armeabi-v7a at API24, BUILD SUCCESSFUL; both-ABI JNI + "Shared class/method/field runtime contract passed". Cloud JVM 21 tests 0 failures; lint 0 errors / 47 warnings / 5 hints.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| [Application debug/app-debug.apk](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44567827863/artifacts/14554655182) |14,159,123| `1ed8b730d1c50c5e6874ed48c73749a1fadd1f277477c80d480885827ea37993` |
| [Test androidTest/debug/app-debug-androidTest.apk](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44567827863/artifacts/14554655182) |502,955| `5b06fa0146d451f977e22bb7c1405b236e4ac81bdfd3e515ffd65440f85848af` |

- [Paired artifact 14554655182](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44567827863/artifacts/14554655182) named iqtvlite-test-apk, zip digest `sha256:2fcd53f1d5fc7b35af9e47081b92cddb131cfbabacd0142bb01fc0abe3e7f797`,12,647,281B, retention90 days, expiry2027-02-17 16:35:36 UTC. [Reports artifact 14553206383](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44567827863/artifacts/14553206383) named iqtvlite-android-reports, `sha256:8c2e4d6717b33b2f20bbe41d43432f3b0b28788fa8aa491a4e9563f5b2d229a9`,3,258,544B. Actions artifacts, not a public Release.
- Both APKs signer SHA256 `bdab6664326a9697b770af70198278a13a90d6fd788012e67db05b513bdb3449` (Android Debug, fresh runner cert; differs from prior runs; no stable cross-run upgrade guarantee).
- Main applicationId tv.iqtvlite.app.validation; label "iqtvlite 验证版"; versionCode10077, versionName2.2.0-tivimini-bcf4a1e1-validation; min24,target35,compile36; native-code 'arm64-v8a' 'armeabi-v7a'. Test package tv.iqtvlite.app.validation.test; target tv.iqtvlite.app.validation; runner AndroidJUnitRunner.
- instrumentation-contract.json: app_sha256/test_sha256 equal the table hashes above; missing_types/methods/fields all empty.
- Size: main APK 14,159,123 B — **unchanged vs the t30 pair** (code-only Kotlin changes, no resource/ABI delta; well within the 14,454,072 B budget, no budget change).
- **Device verification is acceptance2's work on this exact pair** (see §6). Not verified by the developer; this receipt does not claim the box is now smooth and does not claim CHANNEL-key/zap/CPU results.

To reproduce:

```sh
gh run download 44567827863 -R zqs1qiwan/iqtvlite -n iqtvlite-test-apk -D downloaded-apks
gh run download 44567827863 -R zqs1qiwan/iqtvlite -n iqtvlite-android-reports -D downloaded-reports
```
