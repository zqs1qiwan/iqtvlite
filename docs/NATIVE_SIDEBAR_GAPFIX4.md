# NATIVE SIDEBAR — GAPFIX 4 (t77)

2026-11-20. Task t77 (developer2), fixes the t74-review SIDEBAR3 items in the native RecyclerView sidebar. Small changes, no architecture change.

## SIDEBAR3-01 (medium) — hide overlay before focusing channels

- **RIGHT from Groups** now does `setShowGroups(false)` **then** `focusChannels()` (previously only focused channels, so the 0..151dp groups overlay kept covering the focused channel row → TIV-05 focus invisible).
- LEFT-side symmetric paths reviewed: LEFT from Dates hides dates first (`setShowDates(false)`) then `focusPrograms()`; LEFT from Channels shows groups then focuses; LEFT from Groups hides groups then focuses channels. Covered by the SIDEBAR3-07 clearing too.

## SIDEBAR3-02 (low) — focus weight without rebind

- Added `FocusWeightTextView` (extends `AppCompatTextView`): on `drawableStateChanged()` it switches between `sans-serif-medium` (normal) and `sans-serif` bold (focused) based on `state_focused` — the row's `isDuplicateParentStateEnabled` propagates focus. No adapter rebind; only the focused row re-measures its text.

## SIDEBAR3-03 (low) — secondary colour distinct

- Primary keeps the `ColorStateList` (focused `#F2F5FA` / normal `#A9B4C7`); **secondary stays `#A9B4C7`** on focus (no longer shares the primary's focused colour).

## SIDEBAR3-04 (low) — NOW refresh

- Channel-row NOW text and program NOW flags are recomputed on **channel switch** (existing channel-row rebuild) and on **EPG update / structural render**, plus a **60s visibility tick** (`nowTick`) while the drawer is visible, so a long-open drawer doesn't show stale NOW.
- Debounce refresh uses a dedicated `programRefresh` runnable (so the 60s tick isn't cancelled by focus).

## SIDEBAR3-07 (low) — overlay toggle clears stale position

- Added `NativeSidebarView.clearPosition(column)`; Activity calls it on every overlay toggle (show/hide groups, show/hide dates) so a re-entered overlay column never reuses a stale index.

## Preserved

633dp base + overlay columns, selector-based focus (no blue border), visual v2, DiffUtil, EPG out of `SidebarState` equality, transparent root, catchup owner B, layered dates, zero-copy SurfaceView base / grey-screen fix / channel keys / LoadControl / stable keys / SR / TIV / JNI / R8 / UA. Size ≤30MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **37 tests 0 failures**; lint **0 errors**/72 warnings; pair gate missing 0; keep 210 roots identical; dex contract 7 OK.
- CI receipt (run86, 44682527913): source commit [780d6fa8f313e601e9f8cb03eb9b198e5e9ee18f](https://github.com/zqs1qiwan/iqtvlite/commit/780d6fa8f313e601e9f8cb03eb9b198e5e9ee18f), [run 44682527913](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44682527913) **successful** (NDK r27, dual ABI/API24, pair gate passed). Cloud JVM 37 tests 0 failures; lint 0 errors / 70 warnings / 5 hints.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |10,208,256| `d5611430609a0c0fc97b9de8edd5f8a2b5ee3c2cb5e95906680a76912b1d67ac` |
| app-debug-androidTest.apk |502,955| `b4ec5b3bb9d2cf3b2d88366080847034920ffdf22df602dd752dd1d635e55e7b` |

- Artifacts: pair `14605052672` (`sha256:9d9358ff503dc96084631ff9f9d8fcd926daefa7107da2357de7ca26be9a821b`, 8,698,661B); reports `14605272412` (`sha256:2fa10f0556d67802ea77a86674596e71474bcac539e05105e8388e744d3fdc65`). Both APKs signer `1f9e78554b83ca9f5ba5919641f1edd32727df8424203f4b628a9a7309517a1a` (fresh debug cert).
- Version: `tv.iqtvlite.app.validation / 10086 / 2.2.0-tivimini-780d6fa8-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 roots identical.
- Size 10,208,256B ≤ 30MB. Device verification is acceptance2's (t79) on this exact pair; this does not claim the jank target is met.
