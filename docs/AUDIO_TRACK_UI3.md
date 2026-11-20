# AUDIO TRACK UI 3 — real override clearing, single-track gating (t187)

2026-11-21. Task t187 (developer2), on CI10117 source (after t186). Closes the t185 review findings on F4.

## AUDIO-OVERRIDE-02 (medium) — the override is really cleared

`onMediaItemTransition` used to reset only the Compose state (`audioOverrideGroup/Track = -1`), so after a channel change the UI showed 自动 while the **old `TrackSelectionOverride` was still applied** to the player.

Now the same callback (Media3 delivers it on the application looper) calls:

```kotlin
private fun clearAudioOverride(player: Player) {
    val params = player.trackSelectionParameters
    if (params.overrides.isEmpty()) return          // nothing to write back
    player.trackSelectionParameters = params.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        .build()
}
```

- Runs **on the main thread only** (application looper) — no player access from a background coroutine, `check_player_threading` still passes.
- Skips the assignment when there is no override, so a zap does not churn `trackSelectionParameters`.
- The player is **not** rebuilt; AAC stays on MediaCodec, MP2 on the FFmpeg extension.

## AUDIO-SINGLE-02 (medium) — the row is inert when there is nothing to pick

- `SettingsDrawer` gained `audioSelectable`; the row reads `音轨选择（仅 1 条）（不可选）` / `音轨选择（无音轨）（不可选）`.
- `onAudioTrackClick` re-checks `AudioTrackUi.canSelect(audioTracks)`: for a single-track channel it shows a Toast (`当前频道只有 1 条音轨`) and **does not open** a dialog that could only ever offer 自动; for a channel without audio it says `当前频道没有音轨`.
- Multi-track channels behave exactly as before.

## AUDIO-DEDUPE-01 (low) — only cross-renderer copies collapse

The old key `(language, codec, channels)` merged two **genuine** tracks of the same group that happened to share those attributes. Now:

```kotlin
val identity = "$groupIndex#$trackIndex#$language|$codec|$channels"   // same group → distinct tracks
val alreadyKeptForAnotherGroup = result.any { it.groupIndex != raw.groupIndex && identityOf(it) == sameIdentity }
```

i.e. duplicates are collapsed **only across groups** (Media3's per-renderer copies), and a copy is dropped only when an equivalent row from a *different* group already exists. Selected rows are visited first, so the surviving row is the one in use.

## BACK-03 (low) — direct state push

`LaunchedEffect(audioDialogOpen)` → **`SideEffect { onAudioDialogOpenChanged(audioDialogOpen) }`**, so the Activity's guard state follows every recomposition instead of only a value change.

## TRACKS-01 (low) — testable selection logic

Media3's `TrackGroup` calls `android.text.TextUtils` in its constructor, which is not mocked in plain JVM tests, so `TracksFlattening` was split:

- `select(List<Candidate>)` — **all** the logic (support filter → map → dedupe), pure and unit-tested;
- `audioTracks(Tracks)` — a thin adapter mapping `Tracks.Group` fields onto `Candidate`.

## Tests

`AudioTrackUiTest` **11 → 14**: dedupe never merges two tracks of one group; dedupe collapses only cross-renderer copies (and keeps a genuinely different stream in another group); single-track row is not selectable and the label says 仅 1 条.

`TracksFlatteningTest` **new, 6**: only supported tracks listed + selected flagged; unsupported-only → no rows; renderer duplicates collapse to one row (the selected one); two real tracks in one group survive; a different stream in another group is kept; single-track channel reports `canSelect == false`.

JVM total **151 → 160**.

## Preserved

F4 behaviour, F1 remote habits, F3 auto-update, LoadControl **2500/6000/500/2000**, MP2 FFmpeg extension, zero-copy SurfaceView, flat UI (0dp, no blue focus), channel keys, digit OSD, logo cache + switches, recent channels, EPG, SR, brand. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **160 tests 0 failures**; lint **0 errors**/85 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` OK; APK 16,119,401 B ≤ 30 MB.
- CI pair receipt (run 44836837180, source `b1da5ff4`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **160 tests 0 failures** (AudioTrackUiTest 14, TracksFlatteningTest 6); lint 0 errors / 83 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,059,987| `32ce84f39f1f08a417021b01713a305439eaaec4ddaf42e0916d4527022c4c6c` |
| app-debug-androidTest.apk |502,955| `add768ae8ed77662fc4fac8aaa26bb1272008a50ceb4473549aba787a66ceeeb` |

- Artifacts: pair `14679790528` (`sha256:2321e00775aba9c7bcdd0fd8d6cea0ff5bb7b9c76fdf5b33104f38bec4b4c875`, zip 10,332,263 B); reports `14679885201` (`sha256:d5e3440d132d97a377e365df8998b7770d6f78105c24cc2e0b5a441dadf4bed3`). Signer `61832b871d1aa77abf8503196a1bf2bb3125daa361c0b7b690c8b747543abb26`.
- Version: `tv.iqtvlite.app.validation / 10118 / 2.2.0-tivimini-b1da5ff4-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,059,987 B ≤ 30 MB.

## Device protocol (acceptance, not claimed here)

1. Multi-audio channel → pick track 2 → PLAY, then zap to another channel and back: the new channel plays 自动 (no stale selection), the picker shows 自动 selected.
2. Single-track channel → 音频与字幕 row shows 不可选; pressing OK shows `当前频道只有 1 条音轨` and opens nothing.
3. No-audio channel → `当前频道没有音轨`, no dialog.
4. A channel reported twice (MediaCodec + extension) still shows one row.
5. MP2/AAC playback, LoadControl and the remote habits unchanged.
