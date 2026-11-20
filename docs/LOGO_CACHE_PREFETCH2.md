# LOGO CACHE & PREFETCH 2 — main-thread IO / prefetch scope / cancellation (t130)

2026-11-21. Task t130 (developer2), on CI10098 source. Fixes the t127 review findings on the logo-cache build. Feature set (prefetch, cache management, settings entry) preserved; no other visual change.

## CACHE-01 (high) — no disk IO on the main thread

| Call | Before | After |
|---|---|---|
| `LogoLoader.stats(context)` | plain fun, `cacheDir.listFiles()` + `sumOf(length)` on caller thread | **`suspend`, `withContext(Dispatchers.IO)`** |
| settings summary | `remember(settingsDrawerOpen, tick) { stats() }` — ran in composition on first compose | **`produceState(initialValue="", settingsDrawerOpen, tick)`**, computed only while the settings drawer is open, off the main thread |
| `clearMemory/clearDisk/clearAll/invalidate` | sync, disk scan on caller thread | **suspend + IO** for disk parts (memory clear stays a cheap synchronized map clear) |
| `refreshGroup` | invalidated each url inline | invalidates each url (now IO) then prefetches |
| UI feedback | immediate toast | **“正在清理台标缓存…” / “正在刷新当前组台标…”** shown first, then the completion toast |

## PREFETCH-01 (medium) — correct prefetch scope

`PlayerDrawer` prefetch effect replaced by a `snapshotFlow { channelListState.layoutInfo.visibleItemsInfo.map { it.index } }` + `collectLatest` + `delay(200)` debounce, with effect keys **`visible, selectedGroup, channels, selectedChannelUrl`**. The prefetch set is the **union** of:
- currently visible item indices,
- `selectedIndex ± 8` (selectedIndex = index of `selectedChannelUrl`, falling back to the first visible index),
- the **first 60** channels,

de-duplicated and ordered by index (so the visible rows are requested first). This fixes the earlier "first 60 only" gap when the current channel is at index ≥ 60 or after scrolling.

## CANCEL-01 (medium) — cancellation is not swallowed

- `loadInternal`: `catch (e: CancellationException) { throw e }` added around the network read and the decode block (previously a generic `catch (Exception)` swallowed cancellation).
- `prefetch`: now `coroutineScope { … ensureActive() … }` with `async` chunks, so closing the drawer cancels the whole prefetch instead of running through the list.

## CACHE-02 (low) — clear vs in-flight race, disk cap

- **`cacheGeneration`** counter incremented by `clearMemory/clearDisk/invalidate`; `loadInternal` captures it at entry and **drops the result if it changed** before writing memory/disk → a clear no longer gets repopulated by an in-flight download.
- **`MAX_DISK_BYTES = 32 MiB`**: after each disk write, `pruneDiskIfNeeded` deletes oldest `.webp` files until the cache is under the cap.

## PERF-02 (low) — bounded concurrency

`prefetch` chunks the de-duplicated urls (max 80) into groups of **3** and `awaitAll()`s each chunk inside `coroutineScope`, on top of the existing `Semaphore(3)`; the 200 ms debounce in the drawer remains.

## TEST-01 (low) — JVM tests

`android/app/src/test/java/com/cjlhll/iptv/LogoCacheKeysTest.kt` (5 tests, pure JVM) locks the key scheme that `stats`/`invalidate` depend on:
- memory key = `"WxH|url"` and ends with `|url` (invalidate suffix match),
- memory key differs by target size,
- disk key stable per url+size and starts with `sha256(url)_` (invalidate prefix match), ends `_WxH`,
- disk key differs across urls and sizes,
- sha256 deterministic, lowercase, 64 hex chars.

`LogoLoader` exposes `memoryKeyForTest/diskKeyForTest/sha256ForTest` (`internal`) so the test needs no Android Context.

## Preserved

Prefetch/cache/settings-entry functionality; flat UI (no blue focus), zero-copy base, channel keys, LoadControl, EPG, SR, MP2 audio extension, brand & app name. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **42 tests 0 failures** (37 + 5 new); lint **0 errors**/66 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; APK 16,020,729 B ≤ 30 MB.
- CI pair receipt (run 44792282251, source `036c1fc3`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **42 tests 0 failures** (incl. LogoCacheKeysTest 5); lint 0 errors / 64 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |12,961,683| `c7c3077ee81cd3c399925b61782b1c3c6b9d12222bacd11c259dadf13a9905de` |
| app-debug-androidTest.apk |502,955| `ae6226b6ab9e32953d111199f5f02f77b2e659d23f27a4c5e1eed752b4cc99e7` |

- Artifacts: pair `14658075420` (`sha256:598ce3d4be20baee7958a7ad7bb9c3b6b63588581cc7047abeb4dde035d36274`, zip 10,232,348 B); reports `14656783719` (`sha256:3c0145ca333f0c1c4d13bd1beb0630066f1171ebbfd58b9d3aafffed959a433e`). Signer `53b62623359d1e282aa701c2a94bb9b8ec7c186477c5cb6997084dc3e8f1f11c`.
- Version: `tv.iqtvlite.app.validation / 10099 / 2.2.0-tivimini-036c1fc3-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 12,961,683 B ≤ 30 MB.
