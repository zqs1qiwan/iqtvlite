# NATIVE SIDEBAR — GAPFIX (t66)

2026-11-20. Task t66 (developer2), fixes the t62/t63-review gaps in the native RecyclerView sidebar. No architecture change (still Activity `SidebarController` + native `NativeSidebarView` above the zero-copy SurfaceView). Device verification is acceptance2's (t68).

## NS-01 — program column OK = catchup (TIV-09/15)

- Program rows now carry `startMillis/endMillis/programTitle`; clicking a program calls `NativeSidebarView.playCatchup`:
  - `endMillis > now` → toast **"仅历史节目可回看"** (no live switch).
  - no `CatchupHelper.buildCatchupUrl` (mode NONE / empty template) → toast **"该频道不支持回看"**.
  - historical + capable → builds `CatchupPlayRequest(catchupUrl, liveUrl, programTitle, start, end, owner = channel)` with the **owning channel's immutable id/UA/Referer**, bridged to `VideoPlayerScreen` (`setOnSidebarCatchup`) which runs the original `PlaybackIntent.catchup(...)` + `startPlayback(bound)` + `closeDrawer()`.
- The live channel index is not force-changed by the sidebar; the existing catchup path owns it.

## NS-02 — layered columns (633dp semantics)

- `openDrawer()` resets `showGroups=false`, `showDates=false` → main view = **channels 300dp + programs 300dp**.
- Activity `dispatchKeyEvent` while the drawer is open:
  - LEFT from Channels → `setShowGroups(true)` + focus groups; LEFT from Groups → `setShowGroups(false)` + focus channels.
  - RIGHT from Programs → `setShowDates(true)` + focus dates; LEFT from Dates → `setShowDates(false)` + focus programs.
  - RIGHT from Channels falls through to native focus → programs; LEFT from Programs → native → channels.
- Group/date OK only filters (`selectGroup` / `selectEpgDate` keeps `showDates` true); single BACK closes the whole drawer.

## NS-03 — EPG out of StateFlow equality (performance)

- `SidebarState` no longer contains `EpgData`; it has `epgVersion: Int`.
- `SidebarController` keeps `@Volatile epgDataRef: EpgData?` + `epgData()`; `setEpgData` only bumps the version.
- `NativeSidebarView` reads `controller.epgData()`; render signature uses `epgVersion` — **focus/active-column updates no longer deep-compare the EPG maps on the main thread**.

## NS-04 / NS-08 / NS-10 — visual/layout fixes

- Root `NativeSidebarView` background is **transparent**; only the columns container has `#E6161B22`, so the full screen is not dimmed (~90% overlay removed).
- Row text no longer overlaps: primary has `marginEnd=96dp`, secondary is a fixed **90dp right-aligned** view.
- `focusColumn` sets `RecyclerView.isFocusable=true` before the empty/fallback `requestFocus()` (a non-focusable RecyclerView previously swallowed it).

## NS-06 / NS-07 — DiffUtil and focus preservation

- All four adapters use `DiffUtil.calculateDiff` (identity = text+url/group/date/start; contents = full row equality) instead of `notifyDataSetChanged`.
- Render signature **excludes `selectedChannelUrl`**: channel OK updates only the channel rows via DiffUtil (focus is not recreated), program/date columns refresh on the 350ms debounce.

## NS-09 — PlayerDrawer.kt retained (reason)

`PlayerDrawer.kt` is no longer rendered (the call was removed in t62), but it still defines `DrawerColumn` (used by `SidebarController`/Activity key handling) and internal EPG helper types. Deleting the file wholesale would require moving those first; its unused composables are already stripped by R8. **Retained deliberately** for this task; a source-only cleanup can move `DrawerColumn` out and delete the rest in a follow-up.

## Residual

- Program/date sorting still happens on the main thread during the debounce (small per-channel lists); a background sort can be added if device traces show it.
- PlayerDrawer source cleanup (see NS-09).

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **37 tests 0 failures**; lint **0 errors**/69 warnings; pair gate missing 0; keep 210 roots identical; dex contract 7 OK.
- CI receipt (run83, 44675584770): source commit [b29d5f35276fe73e970b62c28802f400f7c0c766](https://github.com/zqs1qiwan/iqtvlite/commit/b29d5f35276fe73e970b62c28802f400f7c0c766), [run 44675584770](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44675584770) **successful** (NDK r27, dual ABI/API24, pair gate passed). Cloud JVM 37 tests 0 failures; lint 0 errors / 67 warnings / 5 hints.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |10,208,256| `65f7df729b80cfef6839f64d317a7d8a2e1a5cb5f5c668a26aa746afd2c5122c` |
| app-debug-androidTest.apk |502,955| `a7a7eaf31fe4439073ea1b92e246cee2b46c1400901ed08b037d1a475179c7ed` |

- Artifacts: pair `14601768383` (`sha256:42af6d8266f02a1bc7d671b412dd67f51de056fce68c5caa42065856b369d75a`, 8,696,883B); reports `14602118065` (`sha256:6d6d117b46377a387edff8f01b26f1d2ec8e5b32153620bbde097b107482f766`). Both APKs signer `c8444481a9fdbbd8f833d856c634e7cf7ee4e56a7514464e2f9aeea5c177c42b` (fresh debug cert).
- Version: `tv.iqtvlite.app.validation / 10083 / 2.2.0-tivimini-b29d5f35-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 roots identical.
- Size 10,208,256B ≤ 30MB. Device verification is acceptance2's (t68) on this exact pair; this does not claim the jank target is met.
