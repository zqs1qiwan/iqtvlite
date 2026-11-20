# AUDIO TRACK UI — label + picker (t175, F4)

2026-11-21. Task t175 (developer2), on CI10112 source (after t172). Shows the current audio track and lets the user pick one. Playback/decoder paths are untouched.

## 1. Info banner

`ChannelInfoBanner` gained an optional `audioLabel`; when non-null it renders one extra `TechSpecText` line:

```
音轨 zh · mpeg-L2 · 2ch
```

- Omitted **entirely** when there is nothing to show (no empty placeholder, no layout shift).
- Source: `player.currentTracks` audio groups flattened in `onTracksChanged` (delivered on the application looper) → `AudioTrackUi.Raw(language, label, mime, codecs, channels)`.
- The pre-existing `videoFormat.channelCount` line is unchanged, so the banner keeps its old look for channels whose video format already reported channels.

## 2. Settings → 音频与字幕 → 音轨选择

New group **音频与字幕** (between 基础 and 数据与更新, using the shared `SettingsGroupHeader`), containing:

| Row | Behaviour |
|---|---|
| 音轨选择（N 条 / 仅 1 条 / 无音轨） | opens `AudioTrackDialog`; the label already tells the user what to expect |
| 当前：zh · aac · 2ch | summary line, hidden when unknown |

Dialog (`audio_dialog`, flat 0dp, focus = `surfaceVariant`, no blue frame, BACK closes only the dialog):

```
▶ 自动（跟随片源）
  音轨 1 · zh · mpeg-L2 · 2ch
  音轨 2 · en · aac · 2ch
```

- **OK** applies immediately and closes the dialog (the info banner is re-shown).
- **自动** clears the override.
- Test tags for acceptance: `audio_dialog`, `audio_row_auto`, `audio_row_<group>_<track>`, `audio_empty`,
  and the settings entry `settings_audio_track`.

## 3. Selection mechanism — no player rebuild

`applyAudioOverride(groupIndex, trackIndex)` only touches the playback parameters:

```kotlin
val builder = player.trackSelectionParameters.buildUpon()
builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))  // or clearOverridesOfType(AUDIO)
player.trackSelectionParameters = builder.build()
```

So **decoders are never re-created**: AAC stays on MediaCodec, MP2 stays on the FFmpeg extension, `LoadControl` (2500/6000/500/2000), the SurfaceView zero-copy path and the renderer factory are untouched. No background thread and no IO is added — the listener and the apply call both run on the application looper.

A new media item resets the override to 自动 (the tracks change, so a stale `TrackSelectionOverride` would be meaningless).

## 4. Degradation

| Situation | Behaviour |
|---|---|
| multi-track | picker lists every track, 自动 first, current marked with ▶ |
| single track | entry shows 音轨选择（仅 1 条）; dialog still opens with only 自动 + that track (no misleading choice) |
| no audio at all | banner shows nothing extra; entry shows 音轨选择（无音轨）; dialog shows 当前频道没有可选音轨 (`audio_empty`) |

## 5. Tests — `AudioTrackUiTest` (8, pure JVM)

- codec shortening (`audio/mpeg-L2` → `mpeg-L2`, `mp4a.40.2` → `aac`, `ac-3`, `eac3`, null/blank → null);
- channels (`2ch`, `6ch`, 0 → null) and language (`und` → null);
- label composition drops unknown fields and never prints `null`;
- **auto is selected by default and listed first**;
- **manual selection marks only the chosen group/track** and unselects 自动;
- summary returns the current track, mentions the count only for multi-track, and is **null for no audio**;
- single track → `canSelect == false`, entry 仅 1 条; multiple → 可切换; empty → 无音轨;
- empty list builds only the 自动 row (graceful degrade).

JVM total **119 → 127**.

## Preserved

LoadControl **2500/6000/500/2000**, MP2 FFmpeg extension, AAC MediaCodec, zero-copy SurfaceView, flat UI (0dp, no blue focus), channel keys, digit OSD, logo cache + switches, recent channels, EPG, auto-update settings, SR, brand & app name. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **127 tests 0 failures**; lint **0 errors**/78 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` OK; APK 16,103,447 B ≤ 30 MB.
- CI pair receipt (run 44826190207, source `a7f9d498`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **127 tests 0 failures** (incl. AudioTrackUiTest 8); lint 0 errors / 76 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,043,607| `4d45dcc1039b34807100eb2154a1b17170db741ece1acbd3bd8c027f61388d02` |
| app-debug-androidTest.apk |502,955| `218e7358ebb0289cb08ddc2033fecb919575a24af782bb851ead6111eb8fd1d1` |

- Artifacts: pair `14674861275` (`sha256:8da72099308d2c40a5942c147659c4d6d7ce4ed14c29aafeb6cac8e16e40c086`, zip 10,315,215 B); reports `14675215547` (`sha256:29403db5b1ca7234784b5f8e59e8160fd7cfcea74389031bdff8cff81cfcb09d`). Signer `626f355c6de75bbe2628b3119a6519b70fe1af9941a0d9d67a7c51b30c5df8f0`.
- Version: `tv.iqtvlite.app.validation / 10113 / 2.2.0-tivimini-a7f9d498-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,043,607 B ≤ 30 MB.

## Device protocol (acceptance, not claimed here)

1. MP2 channel: info banner shows `音轨 … mpeg-L2 … 2ch`; settings entry shows the track count.
2. Multi-audio channel: 音频与字幕 → 音轨选择 → pick the second track → audible switch immediately, no rebuffer of the video, banner line updates.
3. 自动 → back to the source default.
4. BACK in the dialog closes only the dialog (settings drawer state unchanged).
5. MP2 and AAC channels still play (decoder untouched), LoadControl unchanged, no new jank.
