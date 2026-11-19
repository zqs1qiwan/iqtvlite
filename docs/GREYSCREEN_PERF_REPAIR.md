# GREYSCREEN / old-box performance repair

2026-11-19. Task t30 (developer2). Priority P0: user reported `tv.iqtvlite.app.validation` launched to a grey screen, only flashed one frame on exit, and was severely laggy on the old armeabi-v7a box. This document records the root cause from acceptance2's CI10075/`2026-11-19-greyscreen` evidence, the rendering fix, the main-thread/perf audit, residual risk, and the CI receipt. **This document does not claim device acceptance** — acceptance2 verifies the delivered pair (cold-start first frame / exit / 24-TIV / performance).

## 1. Grey screen root cause (confirmed) and fix

Evidence (acceptance2 `docs/evidence/2026-11-19-greyscreen`, parsed by `parse_sf.py` + `analyze_screen.py`):

- Screenshot is a single uniform colour `#2E2E2E` (1920x1080, per-channel std 0, dominant-bucket fraction 1.000) — no video, no stable UI.
- SurfaceFlinger layer stack for our activity: the video **`SurfaceView` is a separate layer at `z=-2`, `isOpaque=1`, with `activeBuffer=[1x1:16,RGBx_8888]`** — i.e. a 1920x1080 SurfaceView sub-surface with no real video frame; it sits **below** the app window `BufferLayer (z=0)`.
- `gfxinfo`: 11 frames total, 100% janky, 50th 550 ms / 95th 3300 ms, "Slow UI thread" 11/11.

This is the classic **SurfaceView-inside-Compose** defect: `PlayerView` defaults to a `SurfaceView`, which renders in its own compositor layer *behind* the app window. Compose draws an opaque full-window background (`MaterialTheme.colorScheme.surface` = `0x1E1E1E`), so the SurfaceView sub-surface is hidden the whole time and the video frame only becomes visible for an instant when the Activity window is torn down on exit ("退出前闪一次画面"). The 1×1 active buffer shows the surface was never given a usable frame while covered.

**Fix**: host the video in a **`TextureView`** instead of a `SurfaceView`. A `TextureView` is drawn inside the normal view hierarchy (it does not create a separate below-window layer), so it composites correctly under the Compose UI overlays and is always visible.

- New layout [android/app/src/tivimini/res/layout/player_texture_view.xml](../android/app/src/tivimini/res/layout/player_texture_view.xml): `<androidx.media3.ui.PlayerView ... app:surface_type="texture_view" app:use_controller="false" app:show_buffering="never" />`.
- [PlayerActivity.kt](../android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt) `AndroidView(factory = …)` now inflates that layout instead of `PlayerView(it)`; all existing settings (transparent shutter, keepContentOnPlayerReset, keepScreenOn, non-focusable) are retained.
- Kept (not changed): UA/Referer, SR/TIV/JNI/R8, audio diagnostics, API24 compatibility. No TIV navigation behavior changed.

Expected effect: cold start shows black/video (never the opaque grey) before first frame; first frame becomes visible as soon as it is produced; BACK/exit has no flash (video is in the window, not a latched sub-layer). Trade-off: TextureView adds one GPU composition copy vs SurfaceView — acceptance2 must measure gfxinfo/latency (see §4).

## 2. Jank — main-thread / composition audit

Observed: acceptance gfxinfo 100% janky (50th 1050 ms / 95th 3400 ms, missed vsync 9, slow-UI-thread 11), Davey 1930–3442 ms, cold start 3–16 s; original Views app 50th ≈ 22 ms.

Audit result — heavy work is already OFF the main thread:
- M3U/playlist parse: `parsePlaylist` = `withContext(Dispatchers.Default)`.
- EPG load/refresh: `EpgRepository.load/loadStructured` on `Dispatchers.IO`; `nowProgramByUrl` map built in `withContext(Dispatchers.Default)`.
- Logo load/prefetch: `LogoLoader` decodes/scales in `withContext(Dispatchers.Default)` and reads disk/net in `Dispatchers.IO`.
- Latency probe: `SourceLatencyProber.probe` = `withContext(Dispatchers.IO)`.
- Per-frame state writes: none in our code. `nowMillis` ticks every 30 s; catch-up position polls every 500 ms only during catch-up; `videoFormat` updates only on video-size/tracks-changed.

So the residual jank is **not** data work on the main thread; it is **Compose composition/measure/draw + first MediaCodec/GPU init on the weak v7a SoC** (gfxinfo "Slow UI thread" / "Slow issue draw commands"). The broken 1×1 SurfaceView also likely forced ExoPlayer to drop/retry frames, adding avoidable playback-thread + system load — the TextureView fix should reduce that; acceptance2 must confirm on device.

If jank persists after the TextureView pair, the reliable next step is a **larger UI-architecture change** (reported to Captain for go-ahead, not done blindly here):
- Host video in a native `FrameLayout` root: `PlayerView` (SurfaceView, cheapest) as the base layer + a **transparent `ComposeView` overlay** for all UI (drawers/banners). Native SurfaceView punch-through works correctly when it is a direct content child (no Compose window over it), and Compose overlays stay interactive.
- Lighten Compose on the old box: avoid per-frame animation of large lists; keep drawer LazyColumns lazily composed (already the case); consider native channel list if needed.
- Cap video selection to ≤1080p and avoid 4K/HEVC decode on v7a (via `DefaultTrackSelector` max video size / decoder constraints) — must be device-validated to avoid disabling video if only higher variants exist.
- Cost estimate: ~1–2 dev-days, touches PlayerActivity render/player wiring, higher regression risk to TIV/SR; requires acceptance re-run of the full 24-TIV + performance matrix.

Robustness note: logcat shows `AudioSink$UnexpectedDiscontinuityException` (live timeline discontinuity) — a media robustness item, independent of drawing, not addressed here.

## 3. Change list / size / risk

- Changed: `PlayerActivity.kt` (AndroidView factory → TextureView layout), new `res/layout/player_texture_view.xml`.
- Size: main APK 14,158,563 B → 14,159,123 B = **+560 B** only (layout XML + ResourceTable entry). Well within the existing 14,454,072 B budget; no budget change needed. Video/audio decode config unchanged.
- Risk: TextureView adds a GPU copy; on a very weak GPU could lower video smoothness. Mitigation/measurement in §4. No video/audio decoding config, TIV, SR or JNI changed.

## 4. What acceptance2 must measure on the delivered pair

- Cold start (after force-stop): first ~2 s must not be uniform grey; first frame appears as soon as decoded.
- BACK / exit: no single-frame flash / no grey latch.
- gfxinfo framestats + SurfaceFlinger: confirm the video layer is no longer an occluded z=-2 SurfaceView; capture 50th/95th, janky %, missed vsync; compare with the original Views app.
- 24-TIV interaction + basic smoothness; logcat for `AudioSink$UnexpectedDiscontinuityException`.

## 5. CI receipt (run76, 44553646612)

- Source commit [983e153c790375c4862b6c3f1bda4e06901f4945](https://github.com/zqs1qiwan/iqtvlite/commit/983e153c790375c4862b6c3f1bda4e06901f4945), branch integration/tivimini-baseline. Ordinary push only; main/tags untouched; this doc is a docs-only follow-up commit.
- [APK CI run 44553646612](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44553646612), run76, workflow_dispatch with isolated_test_package=true: **successful**. Fresh CARGO_TARGET_DIR, NDK r27 27.0.12077973, cargo-ndk arm64-v8a+armeabi-v7a at API24, BUILD SUCCESSFUL; both-ABI JNI exports + "Shared class/method/field runtime contract passed". Cloud JVM 21 tests 0 failures; lint 0 errors / 48 warnings / 5 hints.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| [Application debug/app-debug.apk](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44553646612/artifacts/14544313564) |14,159,123| `5006e91d6bad682d5d8ef4dcdcd8630e59846389a9f16cd51575c927dc9f4a66` |
| [Test androidTest/debug/app-debug-androidTest.apk](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44553646612/artifacts/14544313564) |502,955| `74e4a965cf7e66ac7c3b82dbe434ec2789198f0431be7f4bf5224cfe8adbf1be` |

- [Paired artifact 14544313564](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44553646612/artifacts/14544313564) named iqtvlite-test-apk, zip digest `sha256:affaf433f0d1338e7a68df3d4cf11c11a5f99a0d5a25c6d1393e2fccbbad3fde`,12,647,806B, retention90 days, expiry2027-02-17 15:20:20 UTC. [Reports artifact 14543784291](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44553646612/artifacts/14543784291) named iqtvlite-android-reports, `sha256:f39bc393005a97c718930ba4da6aa5574a5f6fde19c4b249c97e4b0a8ac2c18d`,3,259,908B. Actions artifacts, not a public Release.
- Both APKs signer SHA256 `578bdb34f3c1804424267aa572266aa6087fb230a10fd2d1741d7aa02b910102` (Android Debug, fresh runner cert; differs from prior runs; no stable cross-run upgrade guarantee).
- Main applicationId tv.iqtvlite.app.validation; label "iqtvlite 验证版"; versionCode10076, versionName2.2.0-tivimini-983e153c-validation; min24,target35,compile36; native-code 'arm64-v8a' 'armeabi-v7a'. Test package tv.iqtvlite.app.validation.test; target tv.iqtvlite.app.validation; runner AndroidJUnitRunner.
- instrumentation-contract.json: app_sha256/test_sha256 equal the table hashes above; missing_types/methods/fields all empty.
- **Device verification is acceptance2's work on this exact pair** (cold-start first frame, BACK/exit no flash, SurfaceFlinger/gfxinfo before-after, 24-TIV, smoothness). Not verified by the developer; this receipt does not claim the box is now smooth.

To reproduce:

```sh
gh run download 44553646612 -R zqs1qiwan/iqtvlite -n iqtvlite-test-apk -D downloaded-apks
gh run download 44553646612 -R zqs1qiwan/iqtvlite -n iqtvlite-android-reports -D downloaded-reports
```
