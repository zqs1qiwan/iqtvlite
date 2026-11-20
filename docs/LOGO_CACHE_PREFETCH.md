# LOGO CACHE & PREFETCH (t126)

2026-11-21. Task t126 (developer2), on CI10097 source. Problem: cold-cache drawer logos took ~6–9 s to appear; suspected repeat loads. Fix = background prefetch + in-flight de-dup + cache management API + a minimal settings entry. No look/interaction change beyond the new settings item.

## 1. Prefetch

- **Drawer open / group change** (`PlayerDrawer`, `LaunchedEffect(visible, selectedGroup)`): when the drawer becomes visible, collects the **first ~60** channel logo urls of the current group (visible window + rest of group), waits 180 ms (so the open/scroll first frame isn't blocked), then `LogoLoader.prefetch(...)` runs on IO with the existing **3-way semaphore**.
- **Channel switch** (`playChannel`): warms that channel's logo (`prefetch(listOf(logoUrl))`).
- Target px match the `ChannelLogo` 64×40 dp view size (`with(density){ (64.dp).roundToPx() }` / 40 dp), so prefetched bitmaps hit the same memory keys the drawer requests → **repeat opens are instant**.

## 2. Cache / de-dup

- Memory **LRU 96** (`ImageBitmap`) + disk cache (`cacheDir/logo_cache_v1/<sha256(url)>_<w>x<h>.webp`), disk key is stable on `logoUrl`.
- **In-flight de-duplication**: `load()` now marks `(diskKey)` in `inFlight`; a concurrent request for the same logo does not start a second network/decode — it polls memory for up to **1.5 s** and returns the first result. `prefetch` no longer does its own marking (delegates to `load`), so visible rows and prefetch never double-fetch.
- Recycle/recomposition cancels are handled by the existing Compose `LaunchedEffect` + coroutine cancellation in `ChannelLogo`.

## 3. LogoLoader API (new)

```kotlin
data class LogoStats(val memoryEntries: Int, val diskFiles: Int, val diskBytes: Long)
fun stats(context): LogoStats        // memory entries + disk file count/bytes
fun clearMemory()                    // drop in-memory LRU
fun clearDisk(context)               // delete disk cache files
fun clearAll(context)                // memory + disk
fun invalidate(context, url)         // drop one url (all sizes) from memory + disk
suspend fun refreshGroup(context, channels, w, h)  // invalidate each group logo then prefetch
```
`prefetch` and `load` remain the load path; the semaphore bounds concurrency to 3.

## 4. Settings entry “台标与缓存”

`SettingsDrawer` gained a minimal section (single item can be grouped later):
- summary line: `内存 N 条 · 磁盘 M 个 / K KB` (from `LogoLoader.stats`),
- **刷新当前组台标** → `refreshGroup(filteredChannels)` + Toast,
- **清理台标缓存** → `clearAll(context)` + Toast.
Wired in `VideoPlayerScreen` where `filteredChannels`, `context`, `density`, `scope` already exist; no new Activity plumbing.

## 5. Expected behaviour (not device-verified here)

- Cold cache, drawer open: visible rows should decode from prefetch within ~1 s (prefetch starts before the row bind and 3-way concurrency covers the visible window first since urls are ordered), the rest of the group fills in the background; **no extra main-thread work** (all decode/network on IO/Default).
- Second open: all hit memory/disk → immediate.
- Prefetch is delayed 180 ms and capped at 3 concurrent → no drawer-open burst/CPU spike intended.

## 6. Not changed

Flat UI / no blue focus, zero-copy SurfaceView base, channel keys 166/167, LoadControl, EPG, SR, MP2 audio extension, brand & app name. Only the logo pipeline + the new settings item.

## 7. Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **37 tests 0 failures**; lint **0 errors**/66 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; APK 16,010,355 B ≤ 30 MB.
- CI pair receipt: _(filled after run completes)_

## 8. Device check (acceptance, not claimed here)

Cold-cache open drawer → measure time-to-first-logo on visible rows (target ≤1 s, was ~6–9 s); scroll to confirm background fill; reopen → instant; Settings → 台标与缓存 show usage, refresh/clear work; confirm no drawer jank/CPU spike.
