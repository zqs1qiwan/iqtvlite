# NATIVE SIDEBAR — GAPFIX 3 (t73)

2026-11-20. Task t73 (developer2), fixes t71-review GAP2-01/02/03 (+ low-risk) and adds the final sidebar-jank micro-optimisations requested after t69's device data (84.44% janky / 90th 400 / 95th 800 vs CI10081 98.55/1100/1350 — improved but not yet <80%/200ms). No architecture change.

## GAP2-01 — date overlay placement (blocker)

- The base (633dp) and both overlay columns now live in a **633dp-wide `FrameLayout` panel anchored to the left** of the root.
- Base = channels 300dp + programs 300dp (+33dp slack), `#E6161B22` background.
- Groups overlay (151dp) is at the panel's **left edge** (`Gravity.START`); dates overlay (120dp) is at `START + leftMargin 513dp` (= 633 − 120), so its **right edge aligns with the base right edge** — it can no longer float on the far right over the video.
- Both overlays have an **opaque `#E6161B22` background**; the root stays transparent, so nothing is drawn over the video outside the 633dp panel.

## GAP2-02 — focus after filtering

- On any structural render (group change, date change, EPG update, overlay toggle), `lastPositions[Channels]` and `lastPositions[Programs]` are invalidated.
- Channels then restore by `focusedChannelUrl` (`defaultPosition`), programs restart from the top; LEFT back into a column can no longer jump to a stale wrong channel/program index.

## GAP2-03 — explicit right-from-groups

- Activity `dispatchKeyEvent` while the drawer is open: **RIGHT from Groups → `focusChannels()`** (added), alongside the existing RIGHT Channels→Programs / RIGHT Programs→Dates and LEFT chains.

## Low-risk fixes

- `focusColumn` retries once (`postDelayed(30)`) when the target holder is not yet laid out (overlay first-focus timing).
- Dead `pendingInitialFocus` field/block removed.
- Overlay columns have opaque backgrounds (see GAP2-01).

## Jank micro-optimisation (rows are now selector-driven, no rebind on focus)

This targets the remaining hot path — focus moves must not rebind rows:
- **Row background** = `StateListDrawable` (`state_focused` → `#14FFFFFF`, else transparent), set **once** in `onCreateViewHolder`; focus change no longer calls `setBackground`/allocates.
- **Text colour** = `ColorStateList` (`state_focused` → `#F2F5FA`, else `#A9B4C7`) with `isDuplicateParentStateEnabled = true` on both TextViews, so focus recolours without touching the adapter.
- **Selection / NOW accent bar** = `StateListDrawable` (`state_selected` → `#4EA8FF`, `state_activated` → `#F5A524`, else transparent), driven by `isSelected`/`isActivated` at bind time.
- **▶ marker merged into the primary text** (preformatted at bind), removing the separate arrow TextView → each row is now **3 views** (accent, primary, secondary).
- **RecyclerView**: `itemAnimator = null`, `setHasFixedSize(true)`, `setItemViewCacheSize(8)`, fixed row heights; no per-focus `notifyItemChanged`/rebind.
- `onFocusChangeListener` only records the focused position/updates the controller; no view mutation.

## Preserved

Visual v2 (no focus border; selector background + text colour), DiffUtil for structural changes, EPG out of `SidebarState` equality, 633dp base, catchup owner B, layered dates, zero-copy SurfaceView base / grey-screen fix / channel keys / LoadControl / stable keys / SR / TIV / JNI / R8 / UA. Size ≤30MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **37 tests 0 failures**; lint **0 errors**/72 warnings; pair gate missing 0; keep 210 roots identical; dex contract 7 OK.
- CI receipt (run85, 44679621598): source commit [69c614beff79368db704c5107ed4b242fd5e9543](https://github.com/zqs1qiwan/iqtvlite/commit/69c614beff79368db704c5107ed4b242fd5e9543), [run 44679621598](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44679621598) **successful** (NDK r27, dual ABI/API24, pair gate passed). Cloud JVM 37 tests 0 failures; lint 0 errors / 70 warnings / 5 hints.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |10,208,256| `a7e6daa9470dbc9c8a16f65cdfd63ad1223cf5223acd4ec75cda7db89c52c120` |
| app-debug-androidTest.apk |502,955| `5628fd54c79c5e4e58b8009b71c28f93f0620f6884c25b1dd7ad66b1211c19d2` |

- Artifacts: pair `14603109655` (`sha256:20470d763547a245e089b28ad13125f93231260fa22d150f542eb3ec62991c0a`, 8,697,225B); reports `14603224471` (`sha256:f26b51f0008b0ca61805e8e84fd9245872997e4eb216102faa6be86a69721a05`). Both APKs signer `ea0367a50625473517ae33c294cbeea9fb89faacce8c67d17797055a8f1f79ad` (fresh debug cert).
- Version: `tv.iqtvlite.app.validation / 10085 / 2.2.0-tivimini-69c614be-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 roots identical.
- Size 10,208,256B ≤ 30MB. Device verification is acceptance2's (t75) on this exact pair; this does not claim the jank target is met.
