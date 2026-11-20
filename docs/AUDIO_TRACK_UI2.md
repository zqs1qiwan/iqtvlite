# AUDIO TRACK UI 2 — BACK guard, override lifecycle, renderer filtering (t179)

2026-11-21. Task t179 (developer2), on CI10114 source (after t176). Fixes the t177 review findings on the F4 audio-track feature.

## AUDIO-BACK-01 (high) — one overlay, one BACK

- `PlayerActivity` gained **`isAudioDialogOpen`** + `setAudioDialogOpen` controller; `VideoPlayerScreen` mirrors it (`LaunchedEffect(audioDialogOpen)`).
- **Every key guard includes it**: digits, D-pad/OK/MENU, channel keys and the LAST_CHANNEL branch now require `!isAudioDialogOpen` (same expression as drawer/settings/quality/recent).
- **BACK is handled first**: if the audio dialog is open, the Activity closes *only* it (`return true`) — never the settings drawer or the player.
- Opening the picker **closes the settings drawer first** (`onAudioTrackClick`), so the two overlays can never stack (previously BACK closed settings and left the audio dialog unguarded).

## AUDIO-OVERRIDE-01 (high) — the manual choice survives

`onTracksChanged` used to reset the override unconditionally, so applying a track immediately fell back to 自动 and the ▶ marker vanished. Now:

- `onTracksChanged` **only refreshes the list** (`audioTracks = TracksFlattening.audioTracks(tracks)`).
- A new `onMediaItemTransition` listener resets the override — i.e. **only a channel/media change** invalidates a manual selection.

## AUDIO-EMPTY-01 (medium) — a real empty state

`AudioTrackUi.buildItems(emptyList())` now returns **no rows** (it used to fabricate a 自动 row), and `AudioTrackDialog` takes `hasAudio` so the `audio_empty` text ("当前频道没有可选音轨") is actually reachable. The picker only auto-focuses when there is something to focus.

## AUDIO-RENDERER-01 (medium) — the picker only offers real, single tracks

New **`TracksFlattening.audioTracks(tracks)`**:

- keeps a track only when `group.isTrackSupported(trackIndex)` — an AAC track that the platform renderer rejects is never offered through the extension;
- collapses duplicates (Media3 reports the same stream once per candidate renderer) via `AudioTrackUi.dedupe`, which keys on `(language, codec, channels)` and **prefers the selected track**, so the surviving row is the one the player actually uses;
- `applyAudioOverride` **re-validates** `group.isTrackSupported(trackIndex)` before applying, so a stale row can never route audio to a rejected renderer.

## AUDIO-SUMMARY-01 (medium) — the summary follows the playing track

`Raw` gained `selected`; `AudioTrackUi.currentTrack()` resolves **override → selected → first**, and `summary()` is built from it. A multi-track channel therefore shows the track that is playing, not merely the first row.

## SINGLE-01 / UI-01

- `canSelect(raws)` / `entryLabel(raws)` are wired to the deduped list, so a single-track channel reads 仅 1 条.
- The banner's legacy `videoFormat`-derived `audioText` ("2ch") is **suppressed when the real track line is present**, so the channel count is no longer printed twice.

## Tests — `AudioTrackUiTest` (11, pure JVM)

Existing 8 plus:
- **empty list yields no rows** and `currentTrack == null` (AUDIO-EMPTY-01);
- **summary follows `selected`, not the first row**, while an explicit override still wins (AUDIO-SUMMARY-01);
- **dedupe collapses the MediaCodec/FFmpeg duplicate and keeps the selected group** (AUDIO-RENDERER-01);
- dedupe keeps genuinely different tracks (different language / channel count).

JVM total **134 → 137**.

## Preserved

F4 behaviour (banner line, picker, 自动), LoadControl **2500/6000/500/2000**, MP2 FFmpeg extension, AAC MediaCodec, zero-copy SurfaceView, flat UI (0dp, no blue focus), channel keys, digit OSD, logo cache + switches, recent channels, EPG, auto-update settings, SR, brand. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **137 tests 0 failures**; lint **0 errors**/79 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` OK; APK 16,103,434 B ≤ 30 MB.
- CI pair receipt (run 44830742229, source `d85c0a3a`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **137 tests 0 failures** (incl. AudioTrackUiTest 11); lint 0 errors / 77 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,043,603| `3968f6d9df83d0868fa1c2def314902a6688b73a3cba59b86b80981b9a1f1c15` |
| app-debug-androidTest.apk |502,955| `8303c83f746f1be237e0244630eead5d11a0fbe18b76a550cd71d68ea29ff2ee` |

- Artifacts: pair `14675699928` (`sha256:cf212b1a6fb4d99957d2de060c99d867767431e3666a1d313dd9721550284d44`, zip 10,315,763 B); reports `14676580422` (`sha256:877f792a99789d8970ddfa43c508f147843be1250fc7b01b1f95d336d68d32aa`). Signer `c2893fd1cc3b9737fb6ed795602df46b1f700adc245f24353b248690ab4c48ad`.
- Version: `tv.iqtvlite.app.validation / 10115 / 2.2.0-tivimini-d85c0a3a-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,043,603 B ≤ 30 MB.

## Device protocol (acceptance, not claimed here)

1. Open 音频与字幕 → 音轨选择 → pick track 2 → the ▶ marker **stays** on track 2 and the banner keeps showing it (no flip back to 自动).
2. Zap to another channel → the picker is back to 自动 for the new channel.
3. BACK with the picker open → only the picker closes (settings drawer, player untouched); digits/D-pad do not leak through.
4. A channel with no audio → the picker shows 当前频道没有可选音轨 (`audio_empty`), never a 自动 row.
5. A channel reported twice (MediaCodec + extension) shows **one** row, not two.
6. MP2/AAC playback and LoadControl unchanged; the banner prints the channel count once.
