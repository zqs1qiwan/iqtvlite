# REMOTE HABITS 2 — instant apply, catch-up OK, explicit exit (t193)

2026-11-21. Task t193 (developer2), on CI10120 source (after t192). Closes the t190 review findings on F1.

## REMOTE-INSTANT-01 (high) — a settings change applies without any pause

Before: `PlayerActivity` read the habits only in `onCreate`/`onResume`. The settings drawer lives **inside the same Activity**, so changing a row never triggered `onResume` → the next key press still used the old habit until the app was backgrounded.

Now:

- `VideoPlayerScreen` takes `onRemoteSettingsChanged: (RemoteHabits.Settings) -> Unit` and pushes the current value with **`SideEffect { onRemoteSettingsChanged(remoteHabits) }`** on every recomposition.
- The Activity stores it in its `remote` field (`onRemoteSettingsChanged = { settings -> remote = settings }`), which `dispatchKeyEvent` reads on every key.
- The settings rows already write Prefs **and** update the Compose state in the same click, so the value arrives in the same frame — **no pause, no restart, no player rebuild**.

## CATCHUP-01 (medium) — OK keeps the info banner during catch-up

`RemoteHabits.onOk(settings, overlayOpen, catchupPlayback)` now returns **`SHOW_INFO` whenever a catch-up programme is playing**, regardless of the OK habit: opening the channel list would switch channels and drop the replay. Live TV keeps the configured habit, and an open overlay still wins (`NONE`).

`dispatchKeyEvent` passes `catchupPlayback = isCatchupPlayback`.

## REMOTE-BACK-01 (low) — explicit `finish()`

`Back键 = 直接退出` now calls **`finish()` and returns `true`** instead of forwarding the key to `super.dispatchKeyEvent(event)`, so the habit never depends on the system back dispatch being reached. Overlays still close themselves first (the overlay branches run before this point), so one BACK never both closes a dialog and exits.

## DEFAULT-01 / DOC-01 — defaults documented

`RemoteHabits.DEFAULT` carries a doc block listing the shipped defaults:

| Row | Default | Note |
|---|---|---|
| 上下键 | 换台 | — |
| OK键 | 打开频道列表 | **except during catch-up → 信息条** (CATCHUP-01) |
| Back键 | 关闭抽屉 | double press to exit |
| 数字键 | 直接跳台 | auto-submit after the 3 s OSD timeout |
| 频道键 166/167 | 启用 | D-pad unaffected when disabled |

The settings group order in this doc matches the code: **基础 → 遥控器与操作 → 音频与字幕 → 数据与更新 → 台标与缓存**.

## UI-01 / settings scroll (already fixed by t192)

The whole settings list is scrollable, every row brings itself into view on focus, and the remote group is above the fold anyway; its rows keep their test tags (`settings_remote_updown/ok/back/digits/channel_keys/reset`). Verified again by `scripts/check_settings_scroll.py` (OK).

## Tests — `RemoteHabitsTest` (10 → 13)

- **catch-up keeps OK on the banner**: every OK habit yields `SHOW_INFO` while a replay is playing, live TV keeps the habit, and an overlay still wins;
- **a settings change applies to the next key without any pause**: flipping 上下键 → 呼出频道列表 makes the very next `onUpDown` open the drawer, and switching BACK → 直接退出 changes `onBack` immediately (while overlays still close first);
- **digits still submit through the same decision** (DIRECT vs NEEDS_CONFIRM).

JVM total **160 → 163**.

## Preserved

F1 habit semantics, F3 auto-update, F4 audio UI, LoadControl **2500/6000/500/2000**, MP2 FFmpeg extension, zero-copy SurfaceView, flat UI (0dp, no blue focus), channel keys, digit OSD, logo cache + switches, recent channels, EPG, SR, brand. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **163 tests 0 failures**; lint **0 errors**/86 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` / `check_refresh_paths` / `check_settings_scroll` all OK; APK 16,122,562 B ≤ 30 MB.
- CI pair receipt (run 44842867824, source `276a2275`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **163 tests 0 failures** (RemoteHabitsTest 13); lint 0 errors / 84 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,059,991| `0ecefd29b43991bf125f36575da02ddacd1db28bfa3f6b563eb91c211dcee845` |
| app-debug-androidTest.apk |502,955| `64941dea15aa1d6e7ec7b4553d0328e54d05403d5c1ca17e988e2d831ea29285` |

- Artifacts: pair `14681628247` (`sha256:58ebdbf6a4c8ab3f3326e3c85c79fbe6e65f0b658530b63f41d4fd59b8d4b1f4`, zip 10,333,315 B); reports `14681678380` (`sha256:a9af10527ad988d7a53a67eb44e28a2a2275b4b3111e7619d291136f4f6eaea3`). Signer `95ec44615f4044275d04fd90da5d5345417679dc3df84c681aecb7db56ae5d3d`.
- Version: `tv.iqtvlite.app.validation / 10121 / 2.2.0-tivimini-276a2275-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,059,991 B ≤ 30 MB.

## Device protocol (acceptance, not claimed here)

1. Open 遥控器与操作, switch 上下键 → 呼出频道列表, press BACK to close the drawer, then press UP **without leaving the player**: the drawer must open (no restart needed).
2. Switch OK → 确认 → OK shows the info banner immediately.
3. Switch Back → 直接退出 → one BACK leaves the player; an open overlay still closes itself first.
4. Start a catch-up programme → OK shows the info banner and does **not** open the channel list.
5. 恢复默认 restores every row and the behaviour.
6. 数字键 需OK: type digits, the OSD waits; OK jumps, BACK cancels.
