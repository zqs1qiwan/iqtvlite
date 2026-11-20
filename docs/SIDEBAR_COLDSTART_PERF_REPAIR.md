# SIDEBAR / COLD-START / EPG-SPIKE / PSS repair (performance phase 2)

2026-11-20. Task t42 (developer2), on top of the CI10078 zero-copy SurfaceView base. Driver = CI10078 real-device data (iqtv/docs/CI_SURFACE_BASE_PERF_VERIFICATION.md): steady CPU good (1080p AVC median ≈49.5%, video dropped 0), but **sidebar UI itself still janky (80% janky, 90th 200 ms, 95th 500 ms)**, **cold start 6.9–7.8 s** (first frame 3.2–9 s), **startup/EPG CPU spikes 86–270%**, **PSS ~71–85 MiB**. This document records root cause + fixes + expected deltas + size/risk + CI receipt. Device verification is acceptance2's; this does not claim the box is perfectly smooth.

## 1. Sidebar UI jank — root cause & fixes

Profiled sources in the Compose path (no data work is on the main thread — EPG/parse/logo are already on Dispatchers.Default/IO; `epgChannelUiCache` is LRU(32)):
- **`FocusMarqueeText` used `Modifier.basicMarquee(iterations = Int.MAX_VALUE)` on the focused row** → an infinite marquee animation that recomposed/re-measured the focused channel/program/date text **every frame** while browsing. This is the dominant sidebar jank driver. **Removed** — single-line `TextOverflow.Ellipsis` (no marquee). Browsing focus moves no longer restart a per-frame animation.
- **LazyColumn keys contained the item index** (`"$index:…"`) for groups / programs / dates → any list change (date switch, EPG refresh, filter) shifted indices and re-created items. **Changed to stable keys**: groups key = group name, programs key = `channelId:startMillis:endMillis`, dates key = date string. (Channels keep `index:url` key to stay safe against duplicate URLs — reordering a channel list is not the jank path; will revisit if needed.)
- Retained: EPG selection 350 ms debounce; per-row EPG comes from the LRU `epgChannelUiCache`; `nowProgramByUrl` computed in `Dispatchers.Default`; row visuals already simple (small rounded backgrounds; no shadows).

Expected: sidebar browse **janky% and 90/95th drop materially** (the per-frame marquee cost removed; stable keys avoid needless item re-creation). Final numbers from acceptance2 gfxinfo.

## 2. Cold start — root causes & fixes

Init serial/contending points found:
- `IptvApplication.onCreate → BackgroundSourceUpdater.scheduleStartupRefresh()` did an **immediate network playlist download + `M3uParser.parse`** at process start, and an EPG network refresh after only **2.5 s** — both contend with cold start / first video frame (the 86–270% startup spike). **Fixed**: defer startup **playlist** refresh by **8 s** and startup **EPG** refresh by **12 s**. Foreground continues to read the on-disk cached playlist/EPG immediately, so the first screen is not delayed by this change. **Correction (t46):** this was implemented in a single coroutine, so the 8 s playlist delay ran *before* the 12 s EPG delay and the EPG refresh actually fired ≥20 s after start; t46 splits them into independent coroutines so the EPG refresh fires ~12 s after start.
- `PlayerActivity` also ran its **own** initial EPG network refresh as soon as `lastEpgUpdate` looked stale (first launch → immediate). **Fixed**: `delay(8 s)` before the EPG refresh loop; the cached EPG still loads immediately for the first screen.

Expected: startup CPU spike reduced and cold-start/first-frame contention reduced. (Cold start also depends on source/network; the app-side serial contention above is what we can remove.)

## 3. CPU spikes — EPG/prefetch throttling

- **Logo prefetch** (`LogoLoader.prefetch`) iterated **every** display channel's logo at two sizes right after startup → large network+decode spike. **Fixed**: prefetch bounded to the **first 60** display channels (both passes); the rest load lazily when scrolled into view (per-row `ChannelLogo` already loads on demand).
- **Deferred network playlist/EPG refresh** (§2) removes the biggest parse spikes from the cold-start window.
- Concurrency: playlist refresh is mutex-serialized; EPG refresh is single-flight (existing). No unbounded parallel decode introduced.

Expected: init/EPG spikes (86–270%) materially reduced; steady behavior unchanged.

## 4. PSS

- `LogoLoader` in-memory `ImageBitmap` LRU **128 → 96** entries (disk cache still serves misses) — small, bounded bitmap memory reduction.
- `epgChannelUiCache` already LRU(32); `nowProgramByUrl` is a per-variant small map. EPG `EpgData.programsByChannelId` is the main contributor and is kept (needed), bounded by the source's programme count.
- Expected: PSS same order of magnitude (~70–85 MiB) with lower peaks; not a guaranteed drop to 73 MiB. PSS breakdown to be reported by acceptance (dumpsys meminfo: Java/native/Graphics/Code).

## 5. Preserved / not done

- Preserved: grey-screen fix + zero-copy SurfaceView base + transparent Compose overlay; channel keys `166/167/92/93`; LoadControl `1000/6000/300/1000` (USAB2-01 fixed); decoder pre-warm removed; SR-01/02/03, TIV-01–18/23, JNI/R8/UA; hardware decode `OMX.goke` AVC. Size well under 30 MB.
- Not done (deliberately, risk/scope): 4K/HEVC cap via `DefaultTrackSelector` max-video-size — risky (could disable video if a stream only offers >1080p variants) and would jeopardise "出画"; left for a device-validated follow-up. Deeper drawer visual simplification not needed if removing the marquee already moves the needle.

## 6. What acceptance2 must measure (same pair)

- Sidebar open + 8×DPAD_DOWN/4×DPAD_UP/close on a channel with EPG: gfxinfo janky%, 50/90/95th, missed vsync, slow UI — expect materially below 80%/90th 200 ms/95th 500 ms.
- Cold start ×3 (force-stop): TotalTime + first-frame; CPU spike during init/EPG refresh (target: below 86–270%).
- PSS (dumpsys meminfo breakdown) steady; confirm no growth.
- Video dropped/rebuffer during sidebar = 0 (must stay); no grey-screen; channel keys; 24-TIV + SR regression.

## 7. CI receipt (run79, 44660869560)

- Source commit [62eedde92bf68b137c733740b46265a551e5d162](https://github.com/zqs1qiwan/iqtvlite/commit/62eedde92bf68b137c733740b46265a551e5d162), branch integration/tivimini-baseline. Ordinary push only; main/tags untouched; this doc is a docs-only follow-up commit.
- [APK CI run 44660869560](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44660869560), run79, workflow_dispatch with isolated_test_package=true: **successful**. Fresh CARGO_TARGET_DIR, NDK r27 27.0.12077973, cargo-ndk arm64-v8a+armeabi-v7a at API24, BUILD SUCCESSFUL; both-ABI JNI + "Shared class/method/field runtime contract passed". Cloud JVM 23 tests 0 failures; lint 0 errors / 47 warnings / 5 hints.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| [Application debug/app-debug.apk](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44660869560/artifacts/14596181295) |14,109,967| `60efd72f3e085003a15a5bd977d0695e143fcd926016a670a4c08e684b40a89d` |
| [Test androidTest/debug/app-debug-androidTest.apk](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44660869560/artifacts/14596181295) |502,955| `2262b13a41883dd934f586acedba65bc7e342de6d2d3020d158c324ee3c78df4` |

- [Paired artifact 14596181295](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44660869560/artifacts/14596181295) named iqtvlite-test-apk, zip digest `sha256:e47ccde85ab67f280dcca4c4f206ddb94f8bb6d5bbe774b26b03d5c192dfd977`,12,614,230B, retention90 days, expiry2027-02-18 02:26:59 UTC. [Reports artifact 14595547337](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44660869560/artifacts/14595547337) named iqtvlite-android-reports, `sha256:faaf493ef5a828b7fa5e06da686a2c21c3e0941e0ab0763ebb4169acc456e34d`,3,242,100B. Actions artifacts, not a public Release.
- Both APKs signer SHA256 `7bb63183bac20a9570b91938be024b84fc42674e325c5a5c63b18fd00156a60f` (Android Debug, fresh runner cert; differs from prior runs; no stable cross-run upgrade guarantee).
- Main applicationId tv.iqtvlite.app.validation; label "iqtvlite 验证版"; versionCode10079, versionName2.2.0-tivimini-62eedde9-validation; min24,target35,compile36; native-code 'arm64-v8a' 'armeabi-v7a'. Test package tv.iqtvlite.app.validation.test; target tv.iqtvlite.app.validation; runner AndroidJUnitRunner.
- instrumentation-contract.json: app_sha256/test_sha256 equal the table hashes above; missing_types/methods/fields all empty.
- Size: main APK **14,109,967 B** — well under the 30,000,000 B budget (slightly below the t38 pair; source-only changes).
- **Device verification is acceptance2's work on this exact pair** (see §6). Not verified by the developer; this does not claim the sidebar/cold-start are now perfect.

To reproduce:

```sh
gh run download 44660869560 -R zqs1qiwan/iqtvlite -n iqtvlite-test-apk -D downloaded-apks
gh run download 44660869560 -R zqs1qiwan/iqtvlite -n iqtvlite-android-reports -D downloaded-reports
```
