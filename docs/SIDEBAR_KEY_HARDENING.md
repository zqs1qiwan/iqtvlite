# SIDEBAR STABLE-KEY HARDENING (real-source duplicate-key crash guard)

2026-11-20. Task t46 (developer2), minimal change on top of CI10079. Fixes the t43 review finding: the t42 stable-key change could produce **duplicate LazyColumn keys** on a real source, which makes Compose throw `IllegalArgumentException` when the drawer list is built. This document records the fix, the regression test and the CI receipt. Device verification is acceptance2's; this does not claim smoothness.

## 1. Root cause (potential crash on real sources)

- `PlayerDrawer` groups list = `ChannelGrouper.sortedDrawerGroups(...)` = `listOf("全部") + realGroups`. t42 used `key = g`. **If the real source contains a group literally named "全部"**, the synthesised "全部" and the real "全部" produce the same key → duplicate key → Compose throws when opening the drawer.
- programmes t42 key = `channelId:startMillis:endMillis`; **if the EPG contains two programmes with the same channelId+start+end** (bad/synthetic EPG), duplicate key again.
- dates key = raw date string; duplicates (rare but possible from bad EPG) would also collide.

## 2. Fix — collision-free, still stable keys

New [DrawerKeys.kt](../android/app/src/tivimini/java/com/cjlhll/iptv/DrawerKeys.kt) (`internal object`), used by [PlayerDrawer.kt](../android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt):

| list | key | why unique |
|---|---|---|
| groups | index 0 → `@all`; real groups (index ≥1) → `G:<name>#<index>` | synthetic "全部" gets a dedicated prefix so a real "全部" (index≥1) can never collide; index makes duplicate names unique |
| programmes | `P:<channelId>:<startMillis>:<endMillis>#<index>` | index tie-breaker makes duplicate start/end unique |
| dates | `D:<date>#<index>` | index tie-breaker makes duplicate dates unique |

Keys remain **stable across ordinary refreshes** because list order is preserved, so the same `(value,index)` yields the same key (no needless item recreation). `channels` keeps the existing `index:url` key (unchanged, per task).

## 3. Regression test

New [DrawerKeysTest.kt](../android/app/src/test/java/com/cjlhll/iptv/DrawerKeysTest.kt) (JVM, 4 tests):
- `groupKeysAreUniqueWhenRealGroupIsNamedAll`: list `["全部"(synthetic), "全部","全部","央视频道","卫视"]` → index0 key `@all`, real "全部" ≠ `@all`, all keys distinct.
- `programKeysAreUniqueForDuplicateStartEnd`: same channel + identical start/end twice → distinct keys.
- `dateKeysAreUniqueForDuplicateDates`: duplicate dates → distinct keys.
- `keysAreStableAcrossCalls`: deterministic across calls.

These pin the exact crash precondition (duplicate keys); if the key generator regressed to raw values, the tests fail in JVM before device. (A full Compose drawer-open UI test was not added — the JVM test covers key uniqueness structurally; device drawer-open on a real source remains acceptance's.)

## 4. EPG startup-delay doc correction (t42 → t46)

t42 changed the startup refresh to a **single coroutine**: `delay(8 s)` playlist refresh **then** `delay(12 s)` EPG refresh, so the EPG refresh actually fired **≥20 s** after start (not 12 s). t46 splits them into **independent coroutines** ([BackgroundSourceUpdater.kt](../android/app/src/tivimini/java/com/cjlhll/iptv/BackgroundSourceUpdater.kt)) so the EPG refresh fires ~12 s after start. The t42 doc (`SIDEBAR_COLDSTART_PERF_REPAIR.md` §2) now carries this correction.

> Context: t45 real-device quick-check found CI10079 sidebar **worse** (96% janky) and startup CPU spikes higher because the 8 s/12 s deferral moved the refresh into the interaction/sampling window. Per captain, reverting that deferral and adding real Perfetto/gfxinfo framestats profiling is scheduled as a separate task (t49); **t46 deliberately does not change the delay behavior** — it only hardens keys and corrects the doc.

## 5. Preserved / size / risk

- Preserved: zero-copy SurfaceView base + grey-screen fix; channel keys 166/167/92/93; LoadControl 1000/6000/300/1000; t42 sidebar optimizations (marquee removed, background deferral, logo prefetch ≤60, LRU96); SR-01/02/03, TIV-01–18/23, JNI/R8/UA; hardware decode `OMX.goke` AVC. Size ≤30 MB.
- Risk: very low — key strings only; `DrawerKeys` is used by main `PlayerDrawer` (retained by R8). It is referenced only by `src/test`, so the instrumentation keep contract is unchanged (verified: 210 roots identical).
- Not done: no UI/instrumentation drawer-open test; the t49 deep-profiling scope is untouched.

## 6. CI receipt (run80, 44663063713)

- Source commit [983f66d74884b3a4d13f0cc29e83e8223598fd0b](https://github.com/zqs1qiwan/iqtvlite/commit/983f66d74884b3a4d13f0cc29e83e8223598fd0b), branch integration/tivimini-baseline. Ordinary push only; main/tags untouched; this doc is a docs-only follow-up commit.
- [APK CI run 44663063713](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44663063713), run80, workflow_dispatch with isolated_test_package=true: **successful**. Fresh CARGO_TARGET_DIR, NDK r27 27.0.12077973, cargo-ndk arm64-v8a+armeabi-v7a at API24, BUILD SUCCESSFUL; both-ABI JNI + "Shared class/method/field runtime contract passed". Cloud JVM **27 tests 0 failures** incl. new `DrawerKeysTest` (4); lint 0 errors / 47 warnings / 5 hints.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| [Application debug/app-debug.apk](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44663063713/artifacts/14596676372) |14,126,355| `72f558a266b580b30a1d908aba2d39ed69233c24d1610be7d430cf9ca047a8ae` |
| [Test androidTest/debug/app-debug-androidTest.apk](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44663063713/artifacts/14596676372) |502,955| `9d5b8b87754bd2c97d8e2b92985e6344c2d9f6310f44faab26fa775dfe7e08a7` |

- [Paired artifact 14596676372](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44663063713/artifacts/14596676372) named iqtvlite-test-apk, zip digest `sha256:8abfa6cfed0c09e458b05a1ae94379e1f84a446d2cd9356bc56566b5c22ce9f9`,12,615,062B, retention90 days, expiry2027-02-18 02:45:39 UTC. [Reports artifact 14596721305](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44663063713/artifacts/14596721305) named iqtvlite-android-reports, `sha256:6e353bcc3befe125ad1109c99d4fd465704174f19721a750aaf449d4d74286e1`,3,243,840B. Actions artifacts, not a public Release.
- Both APKs signer SHA256 `8d62fc4d8af76ef26f87c0c8beb043e35573237ded3e4628f2937ebac16482fb` (Android Debug, fresh runner cert; differs from prior runs; no stable cross-run upgrade guarantee).
- Main applicationId tv.iqtvlite.app.validation; label "iqtvlite 验证版"; versionCode10080, versionName2.2.0-tivimini-983f66d7-validation; min24,target35,compile36; native-code 'arm64-v8a' 'armeabi-v7a'. Test package tv.iqtvlite.app.validation.test; target tv.iqtvlite.app.validation; runner AndroidJUnitRunner.
- instrumentation-contract.json: app_sha256/test_sha256 equal the table hashes above; missing_types/methods/fields all empty; keep regenerated = 210 roots identical.
- Size: main APK **14,126,355 B** — well under the 30,000,000 B budget.
- **Device verification is acceptance2's work on this exact pair**: open the sidebar on a real source (including one with a group named "全部" or duplicate-start/end EPG programmes) must not crash; the sidebar-jank/cold-start deep fix is a separate follow-up (captain's t50/t49). This receipt does not claim smoothness.

To reproduce:

```sh
gh run download 44663063713 -R zqs1qiwan/iqtvlite -n iqtvlite-test-apk -D downloaded-apks
gh run download 44663063713 -R zqs1qiwan/iqtvlite -n iqtvlite-android-reports -D downloaded-reports
```
