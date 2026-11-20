# NATIVE SIDEBAR REWRITE — feasibility, blocker and milestone plan (t54)

2026-11-20. Task t54 (developer2). Target: replace the Compose drawer lists with a native View (RecyclerView) sidebar layer above the zero-copy SurfaceView base, because Compose tuning has not fixed the jank (CI10081 still ~97–98% janky). This document states what was delivered, the **blocking finding**, and a concrete milestone plan. It does **not** claim the native sidebar is delivered.

## 1. Delivered in this attempt (safe, requested visual residuals)

- `SettingsDrawer` panel top/bottom 18dp corner → **0dp**.
- `ChannelInfoBanner` top 18dp corner → **0dp**.
- `PlayerActivity` (info-banner panel) top 18dp corner → **0dp**.
- `LogoLoader` channel-logo clip 10dp → **2dp** (brand: square-ish logo, no large rounding).
- Local build + JVM/lint verified (see §4). These are visual-only; no behaviour/TIV change.

## 2. Blocking finding — the drawer is a Compose state machine, not just a render layer

A native View layer above the SurfaceView needs the sidebar's **data and selection** outside Compose, but in the current code they live **inside `VideoPlayerScreen` Compose state**:

- `PlayerActivity.kt:339 fun VideoPlayerScreen(...)`; inside it: `PlayerActivity.kt:367 var drawerOpen`, `:384 var channelGroups`, `:385/386 currentGroupIndex/currentVariantIndex`, `:838 fun playGroupVariant(...)`, `:913/:1113` selection calls, `:1022 selectedChannelUrl`, `:1027 onSelectChannel`.
- `Activity` itself has **no** `channelGroups`/EPG/selection; it only owns the native `PlayerView` + `ComposeView` and `startPlayback`.
- The drawer's DPAD handling is **manual Compose** (`PlayerDrawer.kt` `onPreviewKeyEvent` with `Key.DirectionLeft/Right/Up/Down`, `FocusRequester`s, `pendingFocusTo*`, `focusTargetIndex`) plus ~350ms EPG debounce and scroll effects.

Consequence: a native sidebar cannot "just render the same data". It requires either
(a) extracting `channelGroups` + selection + EPG/date state into an **Activity-owned controller** (`SidebarController`/`PlaybackController`) that both the native sidebar and the Compose overlay read, then deleting the Compose drawer and its key/focus machinery; or
(b) a Compose↔native bridge for data + callbacks, while removing the Compose `onPreviewKeyEvent` DPAD handling and every now-unbound `FocusRequester` effect (unbound `requestFocus()` throws).

Both are **multi-file, multi-hundred-line refactors**. Doing (b) partially would leave **two state machines / two key handlers** — exactly what the task says to avoid — and would likely regress TIV navigation (left→groups, right→programs→dates, channel wrap, single-Back-close). Without device access in this attempt, shipping that half-wired layer is a higher risk than leaving the current Compose drawer in place.

**Therefore t54 cannot honestly be marked as delivering a "usable" native sidebar in this attempt.** The primary deliverable is blocked on the state-extraction refactor, not on the visual assets.

## 3. Milestone plan (recommended split)

### M1 — Extract drawer state to an Activity-owned controller (prerequisite)
- New `SidebarController` (plain class or `ViewModel`) holding: `channelGroups`, `currentGroupIndex/VariantIndex`, selected/focused URL, `drawerOpen`, `activeColumn`, `epgData`, `epgDates/selectedDate`, `showGroups/showDates`.
- Move `playGroupVariant`/selection/channel-step out of `VideoPlayerScreen` into it; Compose reads/writes the controller (collectAsState); EPG load stays IO.
- Delete the Compose drawer's manual `onPreviewKeyEvent` DPAD + `FocusRequester`/`pendingFocusTo*`/scroll-effect machinery once the native sidebar owns focus.
- Test: JVM tests for selection/step/wrap/filter logic (no Android).
- Risk: medium-high (touches channel switching); mitigations: keep `startPlayback`/`LoadControl`/`MediaPlaybackRequest` unchanged.

### M2 — Native `NativeSidebarView` (RecyclerView)
- `FrameLayout`/`LinearLayout` with 4 `RecyclerView`s (groups 150dp / channels 300dp / programs + dates).
- Fixed row heights (channel 56, program/date 44, group 40), square 0dp, 2dp `#4EA8FF` focus selector (`drawable/native_row_focus.xml`), 3dp blue selection bar (+▶) or "播放中", amber NOW.
- Row view holders with pre-formatted strings (no per-frame measure); `notifyItemRangeChanged` / DiffUtil only for visible window; `scrollToPosition` + `requestFocus` on open.
- Add to the activity `rootLayout` **between** `videoView` and `composeOverlay`; `visibility=GONE` when closed; `isFocusableInTouchMode`; remove from focus path when hidden.
- Test: JVM adapter/data tests; instrumentation smoke for focus order / no crash.

### M3 — Wire + remove Compose drawer
- LEFT opens native sidebar (before Compose); BACK closes; channel 166/167/DPAD ZAP only when closed; drawer keeps open on channel OK; group/date OK filters only.
- Remove `PlayerDrawer(...)` call and the Compose drawer key handling; Compose overlay hides while native drawer focused.

### M4 — Device verification (acceptance2)
- `scripts/sidebar_jank_probe.sh` gate: janky < 80%, 90th < 200 ms (target clearly better than CI10078 80/200/500); video dropped 0; repeated open/close no crash; TIV matrix + SR.

## 4. What this attempt verified

- Local `--no-daemon` build: BUILD SUCCESSFUL; JVM 27 tests 0 failures; lint 0 errors. (Visual-only diff: 3×18dp→0, logo 10dp→2dp.)
- No new native component/state changes were committed for t54 (deliberately, to avoid a dual-state-machine partial).

## 5. Risk if a partial native layer were shipped

- Two key handlers (Activity + Compose) and two selection states → wrong channel on ZAP, drawer not closing, stale program list, focus dead-ends. Acceptance would have to bisect. The current Compose drawer, while janky, is functionally correct; an unverified native layer is not.

## 6. CI receipt

_(not applicable: no shippable pair cut for t54 because the primary deliverable is blocked; the visual-residual fixes above are ready to be packaged in a follow-up pair on request.)_
