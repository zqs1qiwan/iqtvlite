# ZAP & COLD-START 5 — arm once per selection (t211)

2026-11-21. Task t211 (developer2), on CI10126 source (after t208). Fixes ZAP-NESTED-01. **No performance claim** — the formal A/B is acceptance's.

## ZAP-NESTED-01 (high) — one arm per user zap, even for multi-variant channels

**Defect:** `playGroupAuto` armed with the **first variant's** key and `playGroupVariant` armed with the **actual channel's** key.

- `selectedIndex == 0` (first source reachable): the keys happened to match → idempotent, OK.
- `selectedIndex > 0` (first source unreachable, multi-source channel): the two keys **differed** → `playGroupVariant` re-armed, so the probe time spent trying the earlier variants was **excluded** from the measurement and `prepareDelay` collapsed to ≈0.
- `playChannel → playGroupAuto → playGroupVariant` re-armed several times for the same user action.

**Fix — arm exactly once, at the outermost entry, with a selection-scoped key:**

| function | arms? | key |
|---|---|---|
| `playChannel` | yes (drawer / return-to-live) | `zapKey(channel.id, url)` |
| `playGroupAuto(groupIndex, armZap = true)` | yes when `armZap` — it is the D-pad / number-jump entry | **`groupKey(groupIndex)`** |
| `playGroupVariant(..., armZap = false)` | **only** when called directly with `armZap = true` (last-channel, quality-variant pick) | `groupKey(groupIndex)` |
| `playChannel` → `playGroupAuto` | passes `armZap = false` | — |
| `playGroupAuto` → `playGroupVariant` | default `armZap = false` | — |

`groupKey(groupIndex) = "g<index>"` is shared by every variant probed inside one selection, and `onZapRequested` is idempotent per key, so the probe of `selectedIndex` 0/1/2 can never restart the clock. The arm is still wrapped in `Log.isLoggable(ZAP_TAG, DEBUG)` → **release builds zero key construction**.

## PLAY-TARGET-01 (low)

`playChannel` still resolves the owning group and delegates to `playGroupAuto`; passing a target `variantIndex` through would change which source is played first, so it is **recorded, not changed** here (no behaviour change in this task).

## PERF-01 / DOC-01

- Both arms are inside `Log.isLoggable(ZAP_TAG, DEBUG)`; `onPrepare`/`onFirstFrame` only touch `ZapTrace` (a few volatile longs) and the first-frame log line is built only inside the same `isLoggable` branch → zero cost when tracing is off.
- Documented nested-no-reset condition: **all variants of one selection share `groupKey(groupIndex)`; a nested `onZapRequested` with the same key is a no-op.**

## Tests — `ZapTraceTest` (12 → 14)

- **`aMultiVariantSelectionArmsOnceForAnyChosenVariant`**: for `selectedIndex` 0/1/2, arm once with the group key, then prepare after 40/80/120 ms of probing → `prepareDelay` is exactly 40/80/120 (**> 0 in every case**) and a line is produced;
- **`nestedVariantSwitchesDoNotRestartTheMeasurement`**: a second `onZapRequested` with the same group key does not move the origin;
- the existing 12 (split, arm-idempotency, channel-stable key, prepare fallback, log-once, tiers, EPG gate/fallback) still pass.

JVM total **178 → 180**.

## Preserved

F1 remote habits, F3 auto-update, F4 audio UI, LoadControl tiers 2500/6000/{300|400|500}/2000, MP2 FFmpeg extension, zero-copy SurfaceView, flat UI, channel keys, digit OSD, logo cache, recent channels, EPG, SR, brand. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **180 tests 0 failures**; lint **0 errors**/87 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` / `check_refresh_paths` / `check_settings_scroll` all OK; APK 16,130,654 B ≤ 30 MB.
- CI pair receipt (run 44853632483, source `8d23b51d`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **180 tests 0 failures** (ZapTraceTest 14); lint 0 errors / 85 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,059,991| `7c25f14f50654db133995c4bad4a13b3f2828808aae14bdca5c5eca54d5a33ad` |
| app-debug-androidTest.apk |502,955| `1533e87c0c95b190e26a1a9950b33b4876340f3d7b96b10fbcd8ff60e1fa83c3` |

- Artifacts: pair `14685979124` (`sha256:49ba772df252dcc4041aa341718fdb57e0d7bd51e00570fff16aa0e1ad20b728`, zip 10,339,972 B); reports `14685939270` (`sha256:397b75efabeaebd7f1506bf11e9fa817365225f229120c6b7820e2a347122207`). Signer `62b2493a9b68db601d848609a1957f84ab0519e90aa06239ba524457911081d8`.
- Version: `tv.iqtvlite.app.validation / 10127 / 2.2.0-tivimini-8d23b51d-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,059,991 B ≤ 30 MB.

## Device protocol (acceptance, not claimed here)

1. On a **multi-source** channel whose first source is unreachable (`selectedIndex > 0`): zap once → one `zap=` line with **`prepare` > 0** (previously ≈0).
2. Single-source channels: unchanged behaviour, one line, `prepare` > 0.
3. D-pad / number / last-channel / quality variant: one line each.
4. Re-entering the same channel or opening settings: no second line.
5. Three buffer tiers 300/400/500 with `IQTV-ZAP bufferMin=<v>` still visible, rebuffer counts unchanged.
