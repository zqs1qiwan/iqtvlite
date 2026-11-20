# PERF ISOLATION SWITCHES — logo prefetch / load + counters (t154)

2026-11-21. Task t154 (developer2), on CI10107 source (after t159). Adds two persisted switches and one counter log line so the logo pipeline's cost can be isolated on the device (and so a weak box can turn the heavy part off).

## 1. Two switches (default **on**)

| Pref key | UI | Default | Effect when off |
|---|---|---|---|
| `logo_prefetch_enabled` | 设置 → 台标与缓存 → **台标预取：开/关** | `true` | no prefetch at all (drawer urgent + filler + startup prefetch); logos load only for visible rows on demand |
| `logo_load_enabled` | 设置 → 台标与缓存 → **台标显示：开/关** | `true` | `ChannelLogo` never reads memory or calls `load()`; only the placeholder letter is drawn (isolates decode/draw cost) |

- Both are `Prefs` booleans (`isLogoPrefetchEnabled/setLogoPrefetchEnabled`, `isLogoLoadEnabled/setLogoLoadEnabled`).
- The prefetch guard is **one line at the single funnel** (`prefetchChunked`), so every entry point (drawer urgent tier, idle filler, startup 60-logo prefetch, `playChannel` warm-up, `refreshGroup`) is covered without touching each caller.
- Turning the load switch off also calls `LogoLoader.clearMemory()` so the bitmaps are released immediately.
- UI: two `SettingsItem` rows in the existing 台标与缓存 block, flat 0dp, `surfaceVariant` focus (no blue frame), each with a one-line explanation (关闭后只加载可视台标，可降低低配设备负载 / 关闭后不加载解码台标，只显示占位). BACK still closes only the settings drawer (unchanged hierarchy).

## 2. Counters — `IQTV-LOGO prefetch=req:N dec:M hit:K`

`LogoPerfCounters` (pure Kotlin, three atomics, **no allocation per logo and no background job**):

- `req` — unique urls handed to a prefetch run (counted once per `prefetchChunked`, after de-duplication);
- `dec` — logos decoded from the network (one per successful decode);
- `hit` — memory/disk cache hits served without decoding.

`PlayerDrawer` logs exactly one line when the drawer **closes**:
```
LaunchedEffect(visible) { if (!visible) LogoLoader.logPerfCounters(drawerOpen = false) … }
→ Log.i("IQTV-LOGO", "prefetch=req=12 dec=4 hit=8")
```
and then resets the counters, so two consecutive sessions can be compared directly (`adb logcat -s IQTV-LOGO`).

## 3. Prefetch parameters (single place for future tuning)

| Constant | Value | Where |
|---|---|---|
| urgent set | selected±8 **then** the whole visible window (no cap) | PlayerDrawer |
| idle filler | selection band (`selected-60 … selected+8`) **then** first 60 | PlayerDrawer |
| filler delay | 1000 ms after the urgent pass | PlayerDrawer |
| filler batch | 4 per batch, 220 ms gap | `prefetchBackground` defaults |
| prefetch concurrency | 2 (dedicated `Semaphore(2)`, independent of the interactive `Semaphore(3)`) | `LogoLoader.prefetchGate` |
| disk cap / prune | 32 MiB, pruned at most every 60 s or 24 writes | `LogoLoader` |

## 4. Tests

`LogoPerfCountersTest` (5 tests): counters report the exact log format (`prefetch=req=12 dec=5 hit=7`), `sampleAndReset` clears them, non-positive request counts are ignored, counts accumulate, and the format is stable. JVM total **89 → 94**.

## Preserved

Zero-copy base, channel keys 166/167, digit OSD, flat UI, MP2 audio extension, logo cache/prefetch pipeline (default behaviour unchanged), recent channels, EPG, SR, brand & app name. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **94 tests 0 failures**; lint **0 errors**/72 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; APK 16,066,296 B ≤ 30 MB.
- CI pair receipt (run 44815601732, source `86ae6068`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **94 tests 0 failures** (incl. LogoPerfCountersTest 5); lint 0 errors / 70 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |12,994,451| `64fac814390e61d785421112992b2fa10c9ee055429d3ce98f11e30be4eec652` |
| app-debug-androidTest.apk |502,955| `edf3f7ed6331c3d3d95c5d34d0a4104ae0e1a910ffeb994b43ce56859a9c312b` |

- Artifacts: pair `14669537600` (`sha256:79068bbae3839ace6f6d69522c075e8286499530c01439cdbc4017fecc8b0063`, zip 10,277,173 B); reports `14669497813` (`sha256:68271e961508b6bb461957c611b6494d5cfd7b20fe0070973471c0fdb42927a7`). Signer `631dc99b326dd1df2a04699d548539faaf9419f9cf7189551ccd9cb749a635ee`.
- Version: `tv.iqtvlite.app.validation / 10108 / 2.2.0-tivimini-86ae6068-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 12,994,451 B ≤ 30 MB.

## Device protocol (acceptance, not claimed here)

1. **Baseline** (both switches on): open the drawer, scroll one screen, close → `adb logcat -s IQTV-LOGO` shows one `prefetch=req=N dec=M hit=K` line; note app CPU during browsing.
2. **Prefetch off** (`台标预取：关`): same gesture → the line shows `req=0`; visible rows still load (dec small), CPU should drop noticeably.
3. **Load off** (`台标显示：关`): placeholders only, `dec=0 hit=0`, lowest CPU — this is the floor for the logo pipeline.
4. Confirm each toggle survives a restart, that BACK in settings still closes only the drawer, and that channels/EPG/MP2 playback are unaffected.
