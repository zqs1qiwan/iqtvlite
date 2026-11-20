# NATIVE SIDEBAR — M1 state extraction (t61)

2026-11-20. Task t61 (developer2). M1 of the native-Views sidebar rewrite: extract the sidebar state machine into an Activity-owned, pure-Kotlin `SidebarController`, read it from Compose via `collectAsState`, and cover the TIV semantics with JVM tests. No visual change, no CI pair (per instruction). M2+M3 (native RecyclerView + wiring) is t62.

## 1. What was extracted

New `android/app/src/tivimini/java/com/cjlhll/iptv/SidebarController.kt`:

- `data class SidebarState` — single source of truth: `channelGroups`, `currentGroupIndex`, `currentVariantIndex`, `selectedChannelUrl`, `focusedChannelUrl`, `drawerOpen`, `activeColumn` (`DrawerColumn?`), `selectedGroup`, `selectedEpgDate`, `epgDates`, `showGroups`, `showDates`, `selectedProgramKey`.
- `class SidebarController` — `MutableStateFlow<SidebarState>` + `state: StateFlow` + `value` snapshot.
  - Channel list/selection: `setChannelGroups` (re-resolves selection by URL, so a playlist refresh keeps the playing channel), `setSelection`, `setSelectedChannelUrl`.
  - Drawer: `setDrawerOpen`, `openDrawer` (first open focuses the **currently playing** channel; later opens keep a **valid browse focus**), `closeDrawer`, `onBack()` (**single Back closes the whole drawer**, returns consumed).
  - Columns/filters (filter only, drawer stays open): `setActiveColumn`, `setShowGroups/Dates`, `toggleGroups/Dates`, `selectGroup`, `selectEpgDate` (sets `showDates`), `setEpgDates`, `selectProgram`.
  - Focus/step: `focusChannel`, `effectiveFocusedChannelUrl` (focus → selected fallback), `stepChannel(delta)` (TIV wrap-around), `activateChannel` (**channel OK switches but keeps the drawer open**), `channelNumber(url)` (variant-aware global numbering), `filteredChannels()` (selected group, global numbering preserved).

`PlayerActivity` now owns `private val sidebarController = SidebarController()` (Activity-level, survives recomposition) and passes it into `VideoPlayerScreen`.

## 2. Compose wiring (collectAsState)

In `VideoPlayerScreen`:

- `val sidebarState by sidebarController.state.collectAsState()`
- `val drawerOpen = sidebarState.drawerOpen`; `val drawerActiveColumn = sidebarState.activeColumn`; `val selectedGroup = sidebarState.selectedGroup` — these were previously `remember { mutableStateOf(...) }` locals.
- All writes now go through the controller: `setDrawerOpenController { sidebarController.setDrawerOpen(it) }`, `onSelectGroup { sidebarController.selectGroup(it) }`, `onClose { sidebarController.closeDrawer() }`, `onActiveColumnChanged { sidebarController.setActiveColumn(it) }`, program-catchup close `sidebarController.closeDrawer()`, focus-to-channels `setActiveColumn(Channels)`.
- The live channel list/selection is mirrored into the controller: `LaunchedEffect(channelGroups) { setChannelGroups(...) }` and `LaunchedEffect(currentGroupIndex, currentVariantIndex) { setSelection(...) }` — so M2's native layer can render from the controller alone.

Behaviour is unchanged for the current Compose drawer (it still renders and drives focus exactly as before). **No `FocusRequester` code was deleted in M1**, so there is no unbound-requester path; the Compose drawer's internal focus/scroll/key machinery stays intact until M2 replaces the rendering and deletes it together (deleting it now while Compose still renders would break TIV navigation).

## 3. JVM tests (`SidebarControllerTest`, 10)

Cover: first open focuses the current channel + Channels column; reopen keeps a valid browse focus; channel step wraps both ends; single Back closes once (second Back no-op); group filter keeps drawer open + global numbering; date filter sets date + keeps drawer; channel OK switches + keeps drawer; `setChannelGroups` re-resolves selection by URL; focus falls back to selected; empty list step/number are no-ops.

## 4. Verification (no pair, per instruction)

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`: **BUILD SUCCESSFUL**.
- JVM **37 tests, 0 failures** (includes new `SidebarControllerTest` 10).
- Lint **0 errors** / 66 warnings / 5 hints.
- No visual changes; no CI pair cut. The visual residuals (18dp→0, logo 10dp→2dp) from t54 are already committed (`b182d1b`).

## 5. Residual / M2 (t62)

- Delete the Compose drawer's own focus/scroll/key state (`PlayerDrawer.kt` `focusedChannelUrl`/`stableFocusedChannelUrl`/`pendingFocusTo*`/`FocusRequester`s) when the native RecyclerView replaces rendering.
- Wire the native `NativeSidebarView` to `SidebarController.state` and its methods; add it to the Activity `rootLayout` between the SurfaceView and the Compose overlay.
- Apply the v2 visual spec (no focus border; `#FFFFFF14` background + text weight; 2dp selection bar + ▶/"播放中"; NOW 2dp amber; loading = 2dp progress line) — must delete the current `Modifier.border(2.dp, 0xFF4EA8FF)` in `PlayerDrawer`.
- Device verification (t64): `scripts/sidebar_jank_probe.sh`, TIV matrix, video dropped 0.

## 6. Risk

Low: controller is additive + pure Kotlin; Compose drawer unchanged. The only behavioural surface is the drawer open/close/group/column writes now going through the flow (covered by tests + compile). The controller's `channelGroups` mirror is updated from the same Compose state, so no divergence in M1.
