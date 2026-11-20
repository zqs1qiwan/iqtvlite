# NATIVE SIDEBAR — GAPFIX 2 (t70)

2026-11-20. Task t70 (developer2), fixes the t67-review GAP-01/02/03 (+ GAP-04/08) in the native RecyclerView sidebar. No architecture change.

## GAP-01 — fixed 633dp base + overlay group/date columns

- Root `NativeSidebarView` is a `FrameLayout`; the **base** is a horizontal `LinearLayout` of width **633dp** containing **channels 300dp + programs 300dp (+33dp slack)**, with the `#E6161B22` panel background.
- **Groups (151dp)** and **dates (120dp)** are now **overlay columns** added directly to the root `FrameLayout` with `Gravity.START` / `Gravity.END` respectively; they are `GONE` by default and appear on demand.
- Showing/hiding the overlays **does not shift the base columns or change the main-view width**; the video area is not squeezed. All sizes are density-converted (`dp()`).

## GAP-02 — returning keeps the browsing position

- Each column records `lastPositions[column]` from the native focus callback (position passed via `holder.bindingAdapterPosition`).
- `focusColumn(rv, column)` restores that position (channels fall back to the focused/selected channel's index via `defaultPosition`), instead of `findFirstCompletelyVisibleItemPosition()` which always jumped back to the first item.
- LEFT from programs → channels and RIGHT from channels → programs therefore return to the last browsed row (TIV-05/08).

## GAP-03 — explicit left/right navigation

Activity `dispatchKeyEvent` while the drawer is open now handles the horizontal moves explicitly (no reliance on implicit focus search):
- **RIGHT from Channels → `focusPrograms()`**
- **LEFT from Programs → `focusChannels()`**
- LEFT from Channels → show groups overlay + `focusGroups()`; LEFT from Groups → hide overlay + `focusChannels()`
- RIGHT from Programs → show dates overlay + `focusDates()`; LEFT from Dates → hide overlay + `focusPrograms()`

## GAP-04 / GAP-08

- GAP-04: `focusColumn` defers focus by one frame (`postDelayed(30)`) so an overlay column is laid out/visible before focus moves.
- GAP-08: when the catchup binding returns `null`, the Compose bridge now shows **Toast "该节目暂不可回看"** instead of failing silently (the native view already toasts for "仅历史节目可回看" / "该频道不支持回看").

## Preserved

Visual v2 (no focus border, `#14FFFFFF` background + text weight, 2dp blue/amber accents), DiffUtil adapters, EPG out of `SidebarState` equality (`epgVersion`), transparent root, catchup owner-B (id/UA/Referer), layered dates, zero-copy SurfaceView base / grey-screen fix / channel keys / LoadControl / stable keys / SR / TIV / JNI / R8 / UA. Size ≤30MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **37 tests 0 failures**; lint **0 errors**/69 warnings; pair gate missing 0; keep 210 roots identical; dex contract 7 OK.
- CI receipt (run84, 44677560151): source commit [fdb20978bee7ec625f9bc9f683a8e18473929f14](https://github.com/zqs1qiwan/iqtvlite/commit/fdb20978bee7ec625f9bc9f683a8e18473929f14), [run 44677560151](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44677560151) **successful** (NDK r27, dual ABI/API24, pair gate passed). Cloud JVM 37 tests 0 failures; lint 0 errors / 67 warnings / 5 hints.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |10,208,256| `b14b5ac52de26fe273aa2aaf1206db7462e91f893be3ce83e8c34c0449dc2e19` |
| app-debug-androidTest.apk |502,955| `058b8dfc2d2f7f79de25fee67151b3fb231635b22950581a8d274e2c3cb89f87` |

- Artifacts: pair `14603850269` (`sha256:303295c2a5715bd233337b172900f0aa413a12deccef154d5756d751e2ff971c`, 8,697,497B); reports `14603945071` (`sha256:893aa81201ef5945d86a4061e73f9970c1607b505fd201a63007da1837c4e820`). Both APKs signer `046ebaebeef9e183aa82204b90fc6f4126d74a8146d7ca5f6dffcb27bc104ee2` (fresh debug cert).
- Version: `tv.iqtvlite.app.validation / 10084 / 2.2.0-tivimini-fdb20978-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 roots identical.
- Size 10,208,256B ≤ 30MB. Device verification is acceptance2's (t72) on this exact pair; this does not claim the jank target is met.
