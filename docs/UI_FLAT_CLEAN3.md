# UI FLAT CLEAN 3 — geometry A/B + jank convergence (t111)

2026-11-20. Task t111 (developer2), on top of t108 (CI10092). Addresses the t107 report: "flat version shifted geometry (channel text x=156 vs 96, row pitch 114 vs 96 px, header [24,24] vs [0,0], missing ▶ + '· 播放中') and jank regressed (CI10091 61.06%/90th 200 vs CI10090 40.32%/105)".

## 1. Geometry — code-level A/B (no layout code was changed by the flatten)

`git diff 165d94f1..HEAD` for the three files named in the task:

| File | Diff vs CI10090 |
|---|---|
| `PlayerDrawer.kt` | **only** `RoundedCornerShape(18/12/999.dp)` → `0.dp` (container, 4 row backgrounds, progress track/fill), 6 hunks |
| `ChannelInfoBanner.kt` | **only** shape 18dp/2dp → 0dp (5 lines) |
| `SettingsDrawer.kt` | **only** shape 18dp/2dp → 0dp (2 lines) |

No padding / rowHeight / spacer / logoSize / font-size / width change exists in the diff, so the flat build's Compose layout metrics are identical to CI10090 by construction.

**Play marker:** `▶` and `"· 播放中"` are **not present in either CI10090's PlayerDrawer or the tivimini-port baseline** (`git show 165d94f1:...PlayerDrawer.kt` and `git show c82593f:...PlayerDrawer.kt` both have zero matches for `播放`/`▶`). Those markers existed only in the **native `NativeSidebarView`** (CI10086–CI10088, removed by t96). So "missing marker" is not a t103 regression; it is a difference between the native-sidebar builds and the reverted Compose drawer.

**Conclusion (pending device confirmation):** x=156 / row 114px / header [24,24] / markers are a **measurement-context difference** (different APK generation, drawer scroll/focus state, or the native-sidebar build), not a code regression in the flat version. Do not move layout code that the diff proves unchanged.

**A/B protocol for acceptance (t113):** `scripts/sidebar_geometry_ab.sh <ci10090.apk> <new.apk> [serial]` — installs both, opens the drawer with the same LEFT key, captures screenshots and `gfxinfo` under the same open/8×DOWN/4×UP/close protocol. Compare the same channel row: channel text left edge (expect 96dp), row pitch, header top-left, and selected-row marker. This is the only apples-to-apples way to settle the report.

## 2. Jank convergence

CI10090 40.32% / 90th 105 ms vs CI10091 61.06% / 90th 200 ms. Changes in this build target the two plausible mechanical causes raised in the task:

- **Per-row shape background removed**: `.background(bg, RoundedCornerShape(0.dp))` → `.background(bg)` for channel/program/group/date rows; `.background(container, shape)` → `.background(container)`; progress track/fill use plain `.background(track)`/`.background(fill)`. A 0dp `RoundedCornerShape` still builds an outline/path; plain colour draw avoids that per draw/per row. Visual result is identical (square).
- **Logo cache back to 96 entries** (from 128) to keep PSS/GC pressure at the CI10090 level on API28 v7a.
- No clipping layer was added or removed: the drawer used `.background(container, shape)` in both builds (background does not clip), so the flat change cannot add an overdraw/clip layer.

If the device probe still shows a regression vs CI10090, the remaining differences are the corner radius only (0dp vs 18/12dp) — i.e. no per-frame work — which would point to run-to-run variance; the A/B script measures both APKs back-to-back on the same device state.

## 3. Preserved

t108 config page / update dialog 2dp + square progress; everything else 0dp flat; original architecture (logo 64×40, channel names, EPG 96dp, 633dp two columns, row heights/fonts/spacing/colors, `basicMarquee`, interactions), no blue focus box, zero-copy base/grey-screen/channel keys/LoadControl/SR/catchup owner B/EPG/audio diagnostics/stable keys/brand & app name. Size ≤30MB.

## 4. Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: _(filled after build)_
- CI receipt: _(filled after run completes)_
