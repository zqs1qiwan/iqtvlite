# UI / BRAND / SIDEBAR-PERF repair (t50)

2026-11-20. Task t50 (developer2), on top of t46 (stable-key hardening). Combines three things: (A) sidebar-jank root-cause work (performance first), (B) TiviMate/Android-TV visual cleanup per `docs/UI_VISUAL_SPEC_TIVIMATE.md`, (C) IQTV Lite brand integration per `docs/BRAND_GUIDELINES.md` / `docs/brand/`. Device verification is acceptance2's; this does not claim perfect smoothness.

Context: t45 real-device quick-check showed CI10079 sidebar **worse** (96% janky) and startup CPU spikes higher because the t42/t46 8s/12s deferral moved refreshes into the interaction window — so t50 **reverts** that deferral. The stable-key hardening from t46 stays.

## A. Sidebar jank — root-cause & changes (code-analysis based; no device Perfetto run by developer)

Code-level hotspots targeted (all in the Compose drawer/list path):
1. **Per-row `RoundedCornerShape(12.dp)` clip** on every group/channel/date/program row → a rounded clip/saveLayer per row on the weak v7a GPU. **Removed** (square rows, brand spec = 0dp list rows).
2. **Variable row heights** (text-driven measure) → continuous re-measure while scrolling. **Added `heightIn(min = 44.dp)`** and reduced vertical padding (stabilises row measure; channel rows stay taller due to the 40dp logo).
3. **999dp progress capsule** (drawer + info banner) and **18dp drawer container** → large rounded clips. **Changed** to 2dp capsule and 0dp container.
4. **`ChannelSwitchLoading` 12dp capsule** → **2dp** (brand spec).
5. **Infinite focus marquee** was already removed in t42 (a per-frame animation).
6. **Focus styling**: added a **2dp `#4EA8FF` border only on the focused row** (single item; cheap) instead of relying on the background fill alone. (Selected/playing 3dp bar + NOW amber bar are *not* yet added — see deferrals.)
7. **Reverted the harmful deferral**: `BackgroundSourceUpdater` startup refresh now runs **immediately on IO** (`PLAYLIST_STARTUP_DELAY_MS=0`, independent coroutines; EPG staggered only 1.5s so the two parses don't collide); `PlayerActivity`'s own first EPG network refresh deferral (8s) removed. Rationale: t45 showed deferring moved the CPU spike into the user-browsing window and the sidebar got worse.
8. **Self-test / gate tool**: added [scripts/sidebar_jank_probe.sh](../scripts/sidebar_jank_probe.sh) — adb gfxinfo framestats around "open drawer + 8×DOWN/4×UP/close"; prints janky% / 50/90/95th. Gate target: janky% clearly below CI10078 80.07%, 90th < 200 ms, 95th < 500 ms. (CI cannot run a device; acceptance runs this.)

Expected: fewer skips/binds per row (no per-row rounded clip), stable row measure, single-item focus border → lower janky% and 90/95th during browsing. Actual numbers from acceptance2.

Not done (honest deferral): leaf-level `derivedStateOf` focus reads / limiting visible count / full Perfetto systrace profiling / selected-playing 3dp bar and NOW amber bar — the remaining items need real per-frame traces (t49/t50-follow-up) to target precisely; adding them blind risks regressions.

## B. Visual (TiviMate / Android TV spec, partial-but-real)

- **Radii**: drawer container 18dp→**0**; all drawer list rows 12dp→**0**; drawer + info-banner progress capsule 999dp→**2dp**; ChannelSwitchLoading 12dp→**2dp**; QualitySelectorDialog 18dp→**4dp**, its rows 12dp→**2dp**; SettingsDrawer rows 12dp→**2dp**; MainActivity accent 12dp→**2dp**. No `shadow(` usages remain.
- **Focus**: 2dp `#4EA8FF` border on focused drawer rows.
- **Row heights**: `heightIn(min = 44.dp)` on group/channel/date/program rows (channel rows effectively ~60dp with 40dp logo).
- **Colors**: `PlayerActivity` `darkColorScheme` now uses the brand palette — primary `#4EA8FF` (focus/selected), background `#0E1116` (Obsidian), surface `#161B22`, onSurface `#F2F5FA` (Ink), secondary `#A9B4C7` (Muted), tertiary `#F5A524` (Amber).
- Preserved TIV interaction semantics (drawer columns/focus order/back behaviour) — only drawing/radii/size changed.
- Deferred: exact 64/56/44/40 per-page heights (currently min-44 stabilisation), 3dp selected/playing bar + ▶ marker, NOW amber text (program Now uses the older light-blue), settings/quality/info pages full spec pass.

## C. Brand integration (IQTV Lite; applicationId/JNI unchanged)

- **Icons**: brand adaptive icons + density PNGs copied into `android/app/src/tivimini/res/mipmap-*` (old `.webp` launcher icons removed). `mipmap-anydpi-v26/ic_launcher(.round).xml` = brand adaptive (Obsidian background + brand foreground).
- **TV banner**: `drawable-nodpi/tv_banner.png` (320x180); manifest `android:banner` → `@drawable/tv_banner`.
- **Splash**: `drawable-nodpi/splash_mark.png` + `drawable/splash_background.xml` (Obsidian + centred mark); `Theme.TiviMini` `android:windowBackground` → `@drawable/splash_background` (light+night), so the splash/cold-start window shows the brand mark instead of a grey background.
- **Colors**: `values/brand_colors.xml` (obsidian/surface/surface_elevated/divider/blue/amber/ink/muted) added.
- **Display name**: `app_name` resValue → **"IQTV Lite"** (validation build "IQTV Lite 验证版"). `applicationId` (`tv.iqtvlite.app` / `.validation`) and all JNI/RustBridge names unchanged.
- **Leanback 512**: brand `app_icon_leanback.png` copied to `drawable-nodpi` (store/leanback asset; note it is not a manifest attribute and resource-shrink may drop it if unreferenced).
- **SplashActivity** still launches immediately; the branded window background is what shows during cold start.

## D. Preserved / size / risk

- Preserved: zero-copy SurfaceView base + grey-screen fix; channel keys 166/167/92/93; LoadControl `1000/6000/300/1000`; stable drawer keys (t46); SR-01/02/03, TIV-01–18/23, JNI/R8/UA; hardware `OMX.goke` AVC. MP2 not touched.
- Size: local debug APK **13,025,831 B** (resource shrink dropped now-unused legacy banner assets after the banner switch); well under 30 MB. Real CI bytes in §E.
- Risk: visual-only changes + resource swaps; key/behaviour untouched. Resource-shrink removing unreferenced legacy assets is expected. Device must confirm icons/banner/splash render and no drawer crash.

## E. What acceptance2 must verify on the delivered pair

- Repeatedly open/close + browse the drawer on a **real source incl. a group named "全部" and duplicate-start/end EPG** → no crash (t46) and no visual break.
- `scripts/sidebar_jank_probe.sh` (or manual) gfxinfo: janky% / 90/95th vs CI10078 baseline 80.07% / 200 / 500 — expect clear improvement.
- Cold start + startup CPU spike (no deferral now), video dropped 0, channel keys, TIV/SR, hardware decode.
- New icon/banner/splash/display name "IQTV Lite"/"IQTV Lite 验证版" visible.

## F. CI receipt (run81, 44665902878)

- Source commit [8e2e064eac5eec7bdbc12457ce40a18b7cd02b18](https://github.com/zqs1qiwan/iqtvlite/commit/8e2e064eac5eec7bdbc12457ce40a18b7cd02b18), branch integration/tivimini-baseline. Ordinary push only; main/tags untouched; this doc is a docs-only follow-up commit.
- [APK CI run 44665902878](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44665902878), run81, workflow_dispatch with isolated_test_package=true: **successful**. Fresh CARGO_TARGET_DIR, NDK r27 27.0.12077973, cargo-ndk arm64-v8a+armeabi-v7a at API24; both-ABI JNI + "Shared class/method/field runtime contract passed". Cloud JVM **27 tests 0 failures** (incl DrawerKeysTest 4); lint 0 errors / 64 warnings / 5 hints.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| [Application debug/app-debug.apk](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44665902878/artifacts/14597069105) |10,404,860| `5b0fdce6c9134ef5da7410351504c41c3fba2fd94c77e07de8c42ef0c59e089f` |
| [Test androidTest/debug/app-debug-androidTest.apk](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44665902878/artifacts/14597069105) |502,955| `5e8674dae7034ae5e13a447ae76cce7c830b9742f86f76732b15377ed5c4482e` |

- [Paired artifact 14597069105](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44665902878/artifacts/14597069105) named iqtvlite-test-apk, zip digest `sha256:bfe8562761c8d252a6e6f18a9ef3a79e115da43209c72487dd958a1806ca351e`,8,886,054B, retention90 days, expiry2027-02-18 03:10:30 UTC. [Reports artifact 14597753107](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44665902878/artifacts/14597753107) named iqtvlite-android-reports, `sha256:230e31afb5acb1bccf43b190d047b6caa2155077bd1c4407f44dcf007ae8c79a`,3,248,253B. Actions artifacts, not a public Release.
- Both APKs signer SHA256 `34a031e525ee6f970dbe7048e23d87aede792b1183383f36603d55681e5df90a` (Android Debug, fresh runner cert; differs from prior runs; no stable cross-run upgrade guarantee).
- Main applicationId tv.iqtvlite.app.validation; **application-label "IQTV Lite 验证版"**; versionCode10081, versionName2.2.0-tivimini-8e2e064e-validation; min24,target35,compile36; native-code 'arm64-v8a' 'armeabi-v7a'; icon resolves to `res/mipmap-anydpi-v26/ic_launcher.xml`; `res/drawable-nodpi/tv_banner.png` + `res/drawable-nodpi/splash_mark.png` + `res/drawable/splash_background.xml` packaged.
- instrumentation-contract.json: app_sha256/test_sha256 equal the table hashes; missing_types/methods/fields all empty; keep regenerated = 210 roots identical.
- Size: main APK **10,404,860 B** — far under the 30,000,000 B budget. The drop vs CI10080 is resource-shrink dropping the now-unreferenced legacy banner/logo bitmaps after switching `android:banner` to the brand `tv_banner`; no ABI/feature was removed.
- **Device verification is acceptance2's work on this exact pair**: repeated drawer open/close + browse on a real source (incl. a group named "全部" / duplicate EPG start+end) must not crash; `scripts/sidebar_jank_probe.sh` gfxinfo vs CI10078 baseline; cold start/CPU/video-dropped-0/channel keys/TIV/SR; new icon/banner/splash/name. This receipt does not claim smoothness.

To reproduce:

```sh
gh run download 44665902878 -R zqs1qiwan/iqtvlite -n iqtvlite-test-apk -D downloaded-apks
gh run download 44665902878 -R zqs1qiwan/iqtvlite -n iqtvlite-android-reports -D downloaded-reports
```
