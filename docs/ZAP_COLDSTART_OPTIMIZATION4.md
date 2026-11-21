# ZAP & COLD-START 4 — every zap entry armed, one line per channel (t208)

2026-11-21. Task t208 (developer2), on CI10125 source (after t205). Fixes the entry coverage the t207 review found. **No performance claim** — t210 runs the formal A/B on this pair.

## ZAP-ENTRY-01 (high) — all channel-change paths arm the stopwatch

**Defect:** `playChannel` was only reached from the drawer (`onSelectChannel`) and `onReturnToLive`. The **main** paths went elsewhere:

| path | lands in |
|---|---|
| D-pad up/down (`onChannelStep`) | `playGroupAuto` |
| number jump (`onNumberJump`) | `playGroupAuto` |
| last channel (`playLastChannel`) | `playGroupVariant` |
| quality variant pick | `playGroupVariant` |

None of them armed `ZapTrace`, so `onPrepare`'s fallback origin made `prepareDelay = 0` and the D-pad/number TTFF was systematically **under**-measured.

**Fix:** arm at **both funnels** — every channel change ends in `playGroupAuto` or `playGroupVariant`:

```kotlin
fun playGroupAuto(groupIndex: Int) { … ZapTrace.onZapRequested(zapKey(c.id, c.url), now) … }   // first variant
fun playGroupVariant(groupIndex: Int, variantIndex: Int, …) { … ZapTrace.onZapRequested(zapKey(channel.id, channel.url), now) … }
```

`onZapRequested` is **idempotent for the same channel key**, so the nested call (auto → variant) does not restart the measurement, and `playChannel` keeps its own arm for the drawer path. Result: D-pad / number / last-channel / quality all have a real key→prepare gap.

## ZAP-REPEAT-01 (medium) — one line per channel, not per first frame

**Defect:** `onRenderedFirstFrame` fires again when the same channel is re-entered (or after an audio/video reconfigure). The old code called `summaryLine()` unconditionally, printing a second line with a much larger, bogus `zap`.

**Fix:** `ZapTrace.summaryLineOnce()` keeps a `loggedKey` and returns the line **at most once per key**; a different channel prints again. The first-frame hook now calls `summaryLineOnce()`.

## PERF-01 — zero cost when the tag is off

Both arms are wrapped in `Log.isLoggable(ZAP_TAG, DEBUG)`, so in release **no key string is built, no clock is read, no map/field is touched** for the tracing feature. `summaryLine()` is likewise only built inside the `isLoggable` branch.

## DOC-01

Documented coverage (matches the code now): `playChannel` (drawer / return-to-live) **+** `playGroupAuto` (D-pad, number jump) **+** `playGroupVariant` (last channel, quality variant). The previous doc implied `playChannel` covered everything — corrected here.

## Tests — `ZapTraceTest` (10 → 12)

- **`theLineIsPrintedOncePerChannelEvenIfTheFrameRepeats`**: a repeated first frame returns null from `summaryLineOnce()`; a different channel prints again;
- **`aRealZapEntryLeavesAPositivePrepareGap`**: arming at the entry then preparing 320 ms later yields `prepareDelay == 320` (this is exactly what D-pad/number produce now, instead of the old 0);
- the existing zap/prepare/first-frame split, arm-idempotency, channel-stable key, prepare fallback, tier invariant and EPG gate/fallback tests all still pass.

JVM total **176 → 178**.

## Preserved

F1 remote habits, F3 auto-update, F4 audio UI, LoadControl **tiers 2500/6000/{300|400|500}/2000**, MP2 FFmpeg extension, zero-copy SurfaceView, flat UI, channel keys, digit OSD, logo cache, recent channels, EPG, SR, brand. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **178 tests 0 failures**; lint **0 errors**/87 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` / `check_refresh_paths` / `check_settings_scroll` all OK; APK 16,130,025 B ≤ 30 MB.
- CI pair receipt (run 44852052430, source `bd54e254`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **178 tests 0 failures** (ZapTraceTest 12); lint 0 errors / 85 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,059,991| `2011476c0175234f35e207cebfaefaf073251312cc8cfea817e807ac5f171e9c` |
| app-debug-androidTest.apk |502,955| `6fdd63508274f5c5411aa0d25c0581f06db29d620311da3b2a36b70b36f265bb` |

- Artifacts: pair `14686072315` (`sha256:75b6a678d600f88f710be4a8f68e6cf3aef393e8d3fdbdfc6a9a2a9065f1c329`, zip 10,339,440 B); reports `14685907772` (`sha256:0d48626532501ce11523072d6e190b554c9241197f90af27b45b3c258eccb9a8`). Signer `529bc25dfdeb87b9d92d23f1a4056795a3a27fb579b0bc1887adec9909015feb`.
- Version: `tv.iqtvlite.app.validation / 10126 / 2.2.0-tivimini-bd54e254-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,059,991 B ≤ 30 MB.

## Device protocol (acceptance/t210, not claimed here)

> Formal A/B on **this** pair (previous 10122/10123/10124 numbers were measured with an incomplete entry set and are reference only).

1. `setprop log.tag.IQTV-ZAP DEBUG`; zap **with the D-pad** 10 times → 10 lines, each with **`prepare` > 0** (real key→prepare gap).
2. Zap with **digits**, **last-channel (上一台)** and a **quality variant** each → one line each, `prepare` > 0.
3. Re-enter the **same** channel (or open/close settings) → **no second line** for that channel.
4. Record p50/p95 per entry type; compare the three buffer tiers (300/400/500) with `IQTV-ZAP bufferMin=<v>` proving the tier in effect, plus rebuffer counts and whether the 5.8 s cold-start rebuffer returns.
