# LOGO CACHE & PREFETCH 4 — atomic publish, prefetch scope, real concurrency (t145)

2026-11-21. Task t145 (developer2), on CI10102 source (after t141). Fixes the t138 review findings.

## ATOMIC-01 — the publish is now atomic

`putCached(key, bitmap)` → **`putCachedIfFresh(key, bitmap, gen, urlGen, url)`**:

```kotlin
private fun putCachedIfFresh(key, bitmap, gen, urlGen, url): Boolean = synchronized(cache) {
    if (gen != cacheGeneration.get()) return false
    if (urlGen != synchronized(urlGeneration) { urlGeneration[url] ?: 0 }) return false
    cache[key] = bitmap
    true
}
```
- The **generation check and the put happen under the same `cache` lock**, closing the window between `stale()` and `putCached`.
- `clearMemory()` now does **bump + clear inside the same `cache` lock**; `invalidate(url)` bumps the per-url stamp via `bumpUrlGen`.
- `cacheGeneration` is an **`AtomicInteger`** (`incrementAndGet()` / `get()`), so concurrent clear/load can no longer lose an increment.
- **An old load never deletes another load's file**: deletion after a stale check is guarded by comparing the file's `lastModified()` with the value it had when read (disk-hit path) or with the time this load started writing (network path) — if someone else rewrote the same key, the file is left alone.
- All three publish sites (disk-hit, double-check, network) use the gate.

## PREFETCH-02 — selection first, visible untruncated, filler past 60

`PlayerDrawer`:
- **urgent** = `selectedIndex ± 8` **first**, then the **whole visible window** — the previous `take(16)` (which could starve a far-away selected row) is gone.
- **idle filler** = first 60 **plus** `selectedIndex-60 … selectedIndex+8`, minus the urgent set — so a selection at index > 60 is still filled.
- Filler unchanged otherwise: 1 s delay, batches of 2 with a 220 ms gap, cancelled by `collectLatest` on any scroll/selection change.

## PERF-02 — real concurrency = 2

- Added a dedicated **`prefetchGate = Semaphore(2)`** acquired around each prefetch load; batches are 2 wide. The previous combination (batch 4 × async 4 × load's `Semaphore(3)`) could run 3 at once; now the prefetch path is capped at **2**, independent of the interactive `load` semaphore.

## PRUNE-01 / URLGEN-01

- **PRUNE-01**: `lastPruneAt` is initialised to `System.currentTimeMillis()`, so the **first write does not trigger a full directory scan**; pruning starts after the first interval/write-count threshold.
- **URLGEN-01**: `urlGeneration` is now a **bounded LRU (512 entries)**, so browsing many channels cannot grow the map forever.

## TEST-01

`LogoPublishGateTest` (5 tests): fresh generation publishes; `clearAll` blocks publish; per-url invalidate blocks only that url; `stale()` and the publish gate always agree; the url-generation map stays ≤ 512 after 2000 bumps.

JVM total **54 → 59**.

## Preserved

Prefetch/cache/settings entry, flat UI (no blue focus), zero-copy base, channel keys, digit OSD, EPG, SR, MP2 audio extension, recent-channels/last-channel, brand & app name. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **59 tests 0 failures**; lint **0 errors**/68 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; APK 16,051,734 B ≤ 30 MB.
- CI pair receipt (run 44802783677, source `ebce64df`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **59 tests 0 failures** (incl. LogoPublishGateTest 5); lint 0 errors / 66 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |12,994,451| `34ec22ea7e74ecc90cb8585656cd740cb4b98f0c661f48896394d5bb9721ce76` |
| app-debug-androidTest.apk |502,955| `db089ae1ff431f26ed6ad4ead6047be4b5c476bc752ff76bc34eaae853f2c01f` |

- Artifacts: pair `14660374249` (`sha256:8d4ebc5f99f2fe6916a2b682679c8a7dc729b4a8159c5b288d080de9e55962d3`, zip 10,263,355 B); reports `14660449191` (`sha256:fa06293b7cba7cf964a9b8532a4d54067f9b47a36bb21768bf75c31ef71f336b`). Signer `1e78f777346ebd61a020bae622b4665a2b9d0b0b84b4056580e8c588fefce580`.
- Version: `tv.iqtvlite.app.validation / 10103 / 2.2.0-tivimini-ebce64df-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 12,994,451 B ≤ 30 MB.

## Device expectation (acceptance, not claimed here)

Browse-window CPU should stay near the 46 % baseline with janky ≤ ~27 % and 90th ≤ 69 ms: the urgent set is now selection-first but still small (selected±8 + visible), and the filler is deferred/batched at concurrency 2.
