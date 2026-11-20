# LOGO CACHE & PREFETCH 3 — cancellation, generation races, tiered prefetch (t137)

2026-11-21. Task t137 (developer2), on CI10100 source (after t133). Fixes the t131 review leftovers **and** the t136 CPU finding (opening the drawer prefetched ~57–120 logos → app CPU 86.8 % avg, janky 60.37 % vs 27.3 % baseline).

## CANCEL-01 — cancellation no longer swallowed

`prefetch` previously wrapped each load in `async { runCatching { load(...) } }`, which swallows `CancellationException` so a `collectLatest` cancellation kept the chunk running. Now:

```kotlin
async {
    try { load(...) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
}
```
plus `currentCoroutineContext().ensureActive()` before every batch. Closing the drawer / changing the selection stops the work.

## CACHE-02a — TOCTOU-safe commit

`loadInternal` captures `gen = cacheGeneration` **and** `uGen = urlGeneration[url]` at entry and defines `stale()`. It re-checks:
1. **after decoding from disk** → stale: delete the file, do not publish to memory;
2. **before writing to disk** (existing check, kept);
3. **after writing to disk, before `putCached`** → stale: delete the just-written file and return null (no half-commit).

## CACHE-02b — per-url invalidation

- `cacheGeneration` is now bumped **only** by `clearMemory` / `clearDisk` / `clearAll` (whole-cache).
- `invalidate(url)` bumps a **per-url stamp** (`urlGeneration[url]`); `refreshGroup` invalidates each url, so **other urls' in-flight loads stay valid** (previously one refresh nuked every in-flight logo).
- `loadInternal`'s `stale()` compares both the global generation and the url stamp.

## PREFETCH-02 + CPU budget — tiered prefetch

`PlayerDrawer` (effect keys `visible, selectedGroup, channels, selectedChannelUrl`, `snapshotFlow(visibleItemsInfo)` + `collectLatest` + 200 ms debounce):

| Tier | Set | Behaviour |
|---|---|---|
| **urgent** | visible window ∪ selected±8, capped at **16** logos | requested immediately via `LogoLoader.prefetch` (concurrency **2**, no truncation of this list) |
| **idle filler** | first **60** of the group minus the urgent set | delayed **1 s**, then `LogoLoader.prefetchBackground` → batches of **4**, **220 ms** gap, concurrency 2, and it stops whenever the flow is cancelled (scroll/selection change) |

Concurrency dropped from 3 → **2** for both tiers, so opening the drawer no longer bursts decodes. The filler also yields to interaction because `collectLatest` cancels it on every scroll.

## PERF-02a — throttled prune

`maybePruneDisk` runs at most **once per 60 s** or **every 24 writes** (whichever first) instead of a full `listFiles()` + `sumOf(length)` scan after every write; the actual prune still deletes oldest `.webp` until ≤ **32 MiB**.

## TEST-02 — JVM tests

`LogoGenerationTest` (4 tests, pure JVM):
- fresh load is not stale,
- global bump (clearAll/clearDisk) invalidates every url,
- per-url bump invalidates **only that url** (the CACHE-02b guarantee),
- url generation is stable until bumped.

`LogoLoader` exposes `cacheGenerationForTest/bumpGenerationForTest/urlGenerationForTest/bumpUrlGenerationForTest/isStaleForTest` (`internal`).

## Preserved

Prefetch/cache/settings entry, flat UI (no blue focus), zero-copy base, channel keys, digit OSD, EPG, SR, MP2 audio extension, recent-channels/last-channel, brand & app name. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **52 tests 0 failures** (48 + LogoGenerationTest 4); lint **0 errors**/68 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; APK 16,046,446 B ≤ 30 MB.
- CI pair receipt (run 44797591870, source `b8f9790b`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **52 tests 0 failures** (incl. LogoGenerationTest 4); lint 0 errors / 66 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |12,978,067| `b00e317f5bf570862139608bae76697e79e41f53ceb52f149ed2551be3d764ed` |
| app-debug-androidTest.apk |502,955| `88042ef2128dfae1fbaee43dc273689bbf9ebc3c3e908d30a1b5b0598167be1d` |

- Artifacts: pair `14660730535` (`sha256:cbb47732ca37da18bc2348bd1c11a4f8fafeccd6f2a89d914c01fa41ce29e26f`, zip 10,254,215 B); reports `14659577491` (`sha256:f955f174f085fe96a0aafdd9227a362439d3a68edefb4ad93a9cc6d6834bf0d1`). Signer `6fbbd783d0907e1843c4a2d518a85cedbfe9e6187cbb370319d55421e97337c7`.
- Version: `tv.iqtvlite.app.validation / 10101 / 2.2.0-tivimini-b8f9790b-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 12,978,067 B ≤ 30 MB.

## Expected device result (acceptance, not claimed here)

Visible logos still appear fast (urgent tier ≤16 logos, concurrency 2), while the browsing window should return near the pre-prefetch CPU/jank baseline (~46 % CPU, janky ~27 %, 90th/95th not worse) because the 60-logo filler is deferred 1 s and throttled to 4 per 220 ms. Acceptance should measure **browse-only vs browse+prefetch** CPU separately.
