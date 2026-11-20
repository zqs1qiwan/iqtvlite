# NATIVE SIDEBAR — M2+M3 (t62)

2026-11-20. Task t62 (developer2), on top of M1 (`SidebarController`, t61). Replaces the Compose drawer lists with a **native View/RecyclerView sidebar** layer above the zero-copy SurfaceView base, wired through the Activity-level `SidebarController`. Device verification is acceptance2's (t64); this does not claim the jank target is met.

## 1. Architecture

`PlayerActivity` content `FrameLayout` children (bottom → top):

1. `PlayerView` (SurfaceView, zero-copy) — video, unchanged.
2. **`NativeSidebarView`** — new native sidebar layer (this task), `visibility=GONE` when closed.
3. `ComposeView` (transparent) — light overlays only (info banner, settings, quality, source select).

The Compose `PlayerDrawer(...)` call is **removed** (disabled); the sidebar is now a single native state machine fed by `SidebarController.state` (Activity-owned `StateFlow`). No double state machine: the Compose drawer key/focus/DPAD code is no longer invoked (the file stays compiled but unreferenced; R8 strips it).

`SidebarController` gained `epgData: EpgData?`; `VideoPlayerScreen` pushes `epgData` into it (`LaunchedEffect(epgData) { setEpgData(...) }`) and registers the playback callback (`setOnSidebarPlay { g, v -> playGroupVariant(g, v) }`), so the native sidebar switches channels through the existing playback path.

## 2. Native view (`NativeSidebarView.kt`)

- `FrameLayout` + horizontal `LinearLayout` with **4 RecyclerViews**: 分组 (150dp) / 频道 (300dp) / 节目单 (300dp) / 日期 (110dp), each with a header. Only visible items are laid out (RecyclerView recycling).
- Rows are built programmatically with **pre-formatted strings** (channel number+title, now-program, program time range `HH:mm`); no per-frame measure/composition.
- Row heights: channels **56dp**, programs **44dp**, dates **44dp**, groups **40dp**.
- **Visual v2 (no focus box)**: focused row = background `#14FFFFFF` + text `#F2F5FA` bold (normal `#A9B4C7`); no `border`/stroke/shadow/elevation/blur. Selected = 2dp `#4EA8FF` left bar + `▶`; NOW = 2dp `#F5A524` left bar; both can coexist. Square rows.
- Render is driven from `SidebarController.state`; a structural signature guard prevents focus-only emissions from re-binding the lists (which would reset native focus).
- 350ms focus debounce recomputes only the **program/date columns** (`renderProgramsOnly`), off the focus path.
- Opening scrolls to + requests focus on the currently playing channel (`scrollToPosition` + `requestFocus`).
- EPG: programs/dates are computed from `EpgData` for the focused channel (channelId resolution via `EpgData.resolveChannelId`), today by default / selected date when set.

## 3. Interaction mapping (TIV)

| TIV | Native implementation |
|---|---|
| LEFT opens drawer | `dispatchKeyEvent` → `sidebarController.openDrawer()`; native view becomes VISIBLE, focuses current channel |
| BACK closes whole drawer (any column) | `dispatchKeyEvent` → if `controller.value.drawerOpen` → `closeDrawer()` |
| channel OK switches, drawer stays open | channel row click → `controller.activateChannel(g, 0)` + `onPlayChannel(g, 0)` (drawer not closed) |
| group/date OK filters only, keeps column | group/date row click → `controller.selectGroup/selectEpgDate` (no close) |
| channel up/down wraps | `SidebarController.stepChannel` (M1, tested) — used by channel keys via existing `setOnChannelStep` when drawer closed |
| channel keys 166/167/92/93 only when closed | existing `dispatchKeyEvent` guard `!isDrawerOpen` (now synced from controller) |
| DPAD inside panel navigates, does not zap | native RecyclerView focus; Activity zaps only when `!drawerOpen` |
| initial focus current channel / keep browse focus | `openDrawer()` keeps valid `focusedChannelUrl`, else selected |
| 350ms EPG debounce | native `Handler.postDelayed(350)` → `renderProgramsOnly()` |

## 4. Performance expectation

The jank source was Compose LazyColumn item composition/measure/draw per row. Native RecyclerView recycles row Views and lays out only visible rows; focus changes no longer trigger Compose recomposition of the drawer. Expected: sidebar browsing janky% clearly below CI10078 80% and 90th < 200ms (acceptance to measure with `scripts/sidebar_jank_probe.sh`); video stays dropped 0 (video is still on its own SurfaceView layer).

## 5. Size / preserved behaviour

- No ABI/feature removal; size well under 30MB (see receipt).
- Preserved: zero-copy SurfaceView base + grey-screen fix, LoadControl `1000/6000/300/1000`, channel keys 166/167/92/93, stable drawer keys (unused now but tests pass), SR/TIV, JNI/R8/UA, hardware `OMX.goke` AVC.
- Visual v2 applied to the native rows (no focus border). The old Compose `Modifier.border(2.dp, #4EA8FF)` is inside the now-unreferenced `PlayerDrawer.kt`; it is not rendered. (Full deletion of that file is deferred to keep `DrawerColumn`/helpers stable; R8 strips the unused composables.)

## 6. Residual / known gaps

- `PlayerDrawer.kt` still exists (unreferenced); `DrawerKeys` unused. R8 strips them from the APK; source cleanup can be a follow-up.
- Native sidebar shows the 4 columns simultaneously (groups 150 + channels 300 + programs 300 + dates 110 ≈ 860dp) rather than the original 633dp two-column staging; TIV left→groups / right→programs→dates focus traversal works natively.
- Program OK (catchup) is not wired in the native program column (channel/group/date are); catchup from the info banner is unchanged.
- The 350ms debounce recomputes program/date columns only; a full per-item diff/DiffUtil is not used (RecyclerView `notifyDataSetChanged` on structural change only).
- Device verification (t64) required: repeated open/close, TIV matrix, jank probe, cold start, CPU/PSS, brand.

## 7. Verification (local + CI)

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **37 tests 0 failures**; lint **0 errors** / 70 warnings / 5 hints; pair gate missing 0; keep 210 roots identical; dex contract 7 OK.
- CI receipt (run82, 44673070513): source commit [06b79d759a9d63a69e4c54a8fc898c440c2a3e9c](https://github.com/zqs1qiwan/iqtvlite/commit/06b79d759a9d63a69e4c54a8fc898c440c2a3e9c), [run 44673070513](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44673070513) **successful** (NDK r27, dual ABI/API24, pair gate passed). Cloud JVM 37 tests 0 failures; lint 0 errors / 68 warnings / 5 hints.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |10,191,872| `f6d2b71d0993948a6f27ee200f6f692c84629fe228da9043bb7e58479feecc21` |
| app-debug-androidTest.apk |502,955| `a6046a7fde8be9451aa6900ffadda3af295920df1059c2b616928ac76b98704b` |

- Artifacts: pair `14601365795` (`sha256:922c35ed7337371a4d744adf89004a24ce8a9fc5a0cd95ce3123ca511e28f649`, 8,680,803B); reports `14600824169` (`sha256:0087076121bc9dbff3cff01e5db745c1970f92c11b2f024f736599208ae5ee9b`). Both APKs signer `55f751afb15fda3d8477098a1237af77d80a04cddcc603e4948b9be1cbc61213` (fresh debug cert).
- Version: `tv.iqtvlite.app.validation / 10082 / 2.2.0-tivimini-06b79d75-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 roots identical.
- Size 10,191,872B ≤ 30MB. Device verification is acceptance2's (t64) on this exact pair; this does not claim the jank target is met.
