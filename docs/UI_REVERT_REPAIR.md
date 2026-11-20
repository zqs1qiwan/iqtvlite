# UI REVERT REPAIR — restore original tivimini Compose drawer (t96)

2026-11-20. Task t96 (developer2). User requirement: **revert the channel-switcher to the original tivimini look; only remove the blue focus box.** The v2/v3 "minimal" pass had flattened the drawer and made it ugly, and the native RecyclerView sidebar changed the interaction/visual baseline. This reverts to the **c82593f** Compose drawer and undoes the v2/v3 visuals.

## 1. PlayerDrawer restored to c82593f (original look)

- `git checkout c82593f -- PlayerDrawer.kt` → original Compose drawer: two-column 633dp layout, 12dp row corners, `basicMarquee` focus text, original font sizes/spacing/logos, 999dp progress capsule, 18dp drawer container, `surfaceVariant` focus background.
- The **original has no border** — the t50-added `Modifier.border(2.dp, #4EA8FF)` is gone (file restored). Focus highlight is the original `surfaceVariant` fill; no new hard frame.

## 2. PlayerActivity wiring restored

- Re-added the original `PlayerDrawer(...)` call with `groups`, `selectedGroup`, `channels`, `channelNumbers`, `selectedChannelUrl`, `nowProgramByChannelUrl`, `epgData`, `nowMillis`, `onSelectGroup`, `onSelectChannel = playChannel`, `onPlayProgram` (original catchup with channel owner B), `onClose`, `onActiveColumnChanged`, `requestedActiveColumn`.
- Restored the original `darkColorScheme` (`#BDBDBD/#757575/#121212/#1E1E1E/White`) instead of the brand palette.
- Drawer open/close/group/active-column now go through the existing `SidebarController` (open/close/selectGroup/setActiveColumn), read by Compose via `collectAsState` — no second state machine.

## 3. NativeSidebarView removed

- Deleted `NativeSidebarView.kt`; removed the Activity `nativeSidebar` field, its creation/collector, the DPAD overlay branches, and the `onSidebarPlay` / `onSidebarCatchup` fields + `setOnSidebarPlay` / `setOnSidebarCatchup` params/registrations.
- The Compose `PlayerDrawer` is again the **only** sidebar; DPAD/focus behaviour is the original Compose implementation. `SidebarController` remains only as the drawer open/close/selection/active-column holder.

## 4. v2/v3 visuals undone

- SettingsDrawer 18dp, ChannelInfoBanner 18dp, info-banner panel 18dp, QualitySelectorDialog 18dp / rows 12dp, MainActivity 12dp, ChannelSwitchLoading 12dp, LogoLoader 10dp — all restored to the original values.
- No square-row / minimal / font-size / panel redesign remains in the sidebar path.

## 5. Preserved (non-visual fixes)

Zero-copy SurfaceView base + grey-screen fix, channel keys 166/167, LoadControl `1000/6000/300/1000`, SR-01–03, catchup owner B, EPG, audio diagnostics, stable drawer keys, IQTV Lite brand resources/app name. Size ≤30MB.

## 6. Performance expectation

Sidebar performance is back to the **original Compose baseline** (the user prioritises the original look). No claim of being faster than the original; the earlier native-sidebar jank numbers are not applicable. Device verification (t95) checks that the screenshot matches the original, only the blue focus box is gone, TIV/catchup don't regress, no crash/grey screen.

## 7. Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **37 tests 0 failures**; lint **0 errors**/66 warnings; pair gate missing 0; keep 210 roots identical; dex contract 7 OK.
- CI receipt (run90, 44707095454): source commit [165d94f18e49d5482a30f98a1d4c2df0370f80c8](https://github.com/zqs1qiwan/iqtvlite/commit/165d94f18e49d5482a30f98a1d4c2df0370f80c8), [run 44707095454](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44707095454) **successful** (NDK r27, dual ABI/API24, pair gate passed). Cloud JVM 37 tests 0 failures; lint 0 errors / 64 warnings / 5 hints.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |10,404,860| `9d00db84d5372ee32f7f4ad66827c1185741e185e50f15490cb83416d6703995` |
| app-debug-androidTest.apk |502,955| `23c38806b0bafb9b765e3aeea5b557d50823e6da446bed0a8efa6bb3c56b1a5a` |

- Artifacts: pair `14615821971` (`sha256:09c21db4580d3de3121a5df241feb9a99c9bc157e4439428401d32459a457dd9`, 8,896,775B); reports `14615503248` (`sha256:34fa9d8c62de2f2b2cd62d51bf4bff3403342c9f3fc57a595be8feba102750c1`). Both APKs signer `c798cf9172891ecb080ecdc1f4a0089b59bbf3acc3501938ae68a52952c71a95` (fresh debug cert).
- Version: `tv.iqtvlite.app.validation / 10090 / 2.2.0-tivimini-165d94f1-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 roots identical.
- Size 10,404,860B ≤ 30MB. Device verification is acceptance2's (t95) on this exact pair: screenshot should match the original tivimini look with only the blue focus box removed; TIV/catchup no regression; no crash/grey screen. jank is the original Compose baseline (no claim of improvement).
