# RECENT CHANNELS & LAST CHANNEL (t133)

2026-11-21. Task t133 (developer2), on the logo-cache-fixed source (CI10099). Adds **上一台** (return to previous channel) and a persistent **最近观看** list. No change to the rest of the UI.

## 1. 上一台 (last channel)

- `PlayerActivity.dispatchKeyEvent` handles **`KEYCODE_LAST_CHANNEL`** and **`KEYCODE_MEDIA_PREVIOUS`** on `ACTION_DOWN`, `repeatCount == 0`.
- Guarded: does nothing while the drawer / settings / quality dialog / recent list is open (`playLastChannel()` returns false → the event falls through).
- **Debounce 800 ms** so key repeat or a double press cannot thrash the player.
- **Variant/quality switches do not count as a new channel**: `RecentChannels.previous()` skips the entry whose stable key equals the currently playing channel, so pressing "last channel" after a quality switch returns to the genuinely different channel. Recording happens per channel (not per variant).
- No previous entry → returns false, no action.

## 2. 最近观看 (recent channels)

`RecentChannels` (pure Kotlin, unit-tested):
- `stableKey(channel)` = `id:<channel.id>` when the parser produced an id, else `url:<url>` — survives playlist re-parse.
- `push(current, channel, number)` → newest first, **de-duplicated by stable key**, capped at **N = 10** (`RecentChannels.MAX`).
- `resolve(entries, channels)` maps entries onto the current playlist by id first, then url; **missing channels are silently skipped** (never throws).
- `encode/decode` = one compact string, fields `\u0001`-separated, entries `\u0002`-separated; separators inside titles/urls are sanitized; malformed input decodes to an empty list.
- `previous(entries, currentKey)` = first entry whose key differs from the current channel.

Persistence: `Prefs` key **`recent_channels_v1`** (single string), plus `getRecentChannels/setRecentChannels/clearRecentChannels`. Recorded in `playGroupVariant` (i.e. on every actual channel start), so the list is O(N) to update and tiny on disk; reads happen on the composition thread but only decode ≤10 short entries (no disk IO — SharedPreferences is memory-cached after first load).

## 3. Entries (TV-friendly)

- **Settings → 最近观看（N）** opens `RecentChannelsDialog` (settings closes first).
- The dialog: flat 0dp container, title 最近观看, rows show **台标 (ChannelLogo, reuses the logo cache) + “number. title”**, focus highlight = `surfaceVariant` (no blue border), a **清空最近观看** row at the bottom.
- **OK** on a row → closes the dialog + drawer and plays that channel (falls back to the current group's channel object resolved from the playlist).
- **Back** → closes only the list; playback continues.
- Empty state shows 暂无最近观看.

## 4. Not changed

Zero-copy SurfaceView base, channel keys 166/167, digit OSD, flat UI (no blue focus), MP2 audio extension, logo cache/prefetch, EPG, SR, brand & app name. Size ≤30 MB.

## 5. Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **48 tests 0 failures** (42 + RecentChannelsTest 6); lint **0 errors**/68 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; APK 16,044,036 B ≤ 30 MB.
- `RecentChannelsTest` covers: newest-first + stable-id de-dup, cap at MAX, encode/decode round-trip incl. separator sanitizing, garbage/empty decode, resolve skipping missing channels + url fallback, `previous` selection (other channel / none / no current).
- CI pair receipt (run 44795717239, source `65d33068`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **48 tests 0 failures** (incl. RecentChannelsTest 6); lint 0 errors / 66 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |12,978,067| `73a814fce5b4f7429c32e9a592f4dcd837cdc6957606b59530379c63cf341072` |
| app-debug-androidTest.apk |502,955| `8f18586756fb4bf70ac091c9267cd47f611230da5fb750a4733bcd21001bf2e9` |

- Artifacts: pair `14659725625` (`sha256:a562ba1b1a8ea27ec7d7c5c71bce91f8ed70db62e9649c144d10617f8c4ce710`, zip 10,250,728 B); reports `14659850291` (`sha256:86b91bb398eebe9b695196c6e636366f222a44baab8d608c2eb5acc3b4ccf6c7`). Signer `840e687e1f7daa45aa3c2852f8c6a01edf8571a740246417403c3a64606e98cd`.
- Version: `tv.iqtvlite.app.validation / 10100 / 2.2.0-tivimini-65d33068-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 12,978,067 B ≤ 30 MB.

## 6. Device check (acceptance, not claimed here)

Press LAST_CHANNEL/MEDIA_PREVIOUS on the remote → returns to the previously watched channel; with drawer/settings open it must not fire; quality switch then LAST_CHANNEL returns to the other channel; Settings → 最近观看 shows ≤10 rows with logos, OK switches, Back only closes, 清空 works and survives an app restart.
