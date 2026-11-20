# REMOTE HABITS SETTINGS (t186, F1)

2026-11-21. Task t186 (developer2), on CI10116 source (after t182). Adds the 遥控器与操作 settings group. Defaults reproduce the shipped behaviour exactly, so an untouched install behaves as before.

## 1. New group: 遥控器与操作

Placed after 数据与更新, before 台标与缓存. Flat 0dp rows, `surfaceVariant` focus (no blue frame), BACK still closes only the settings drawer.

| Row | Options (OK cycles) | Default |
|---|---|---|
| 上下键 | 换台 / 呼出频道列表 | **换台** |
| OK键 | 打开频道列表 / 确认 | **打开频道列表** |
| Back键 | 关闭抽屉 / 直接退出 | **关闭抽屉** |
| 数字键 | 直接跳台 / 先显示输入条需OK | **直接跳台** |
| 频道键 166/167 | 启用 / 禁用 | **启用** |
| 恢复默认 | — | clears all `remote_*` keys |

Test tags: `settings_remote_updown`, `settings_remote_ok`, `settings_remote_back`, `settings_remote_digits`, `settings_remote_channel_keys`, `settings_remote_reset`.

## 2. Prefs keys

| Key | Type | Values |
|---|---|---|
| `remote_updown` | String | `CHANNEL_STEP` / `OPEN_DRAWER` |
| `remote_ok` | String | `OPEN_DRAWER` / `CONFIRM` |
| `remote_back` | String | `CLOSE_OVERLAY` / `EXIT` |
| `remote_digits` | String | `DIRECT` / `NEEDS_CONFIRM` |
| `remote_channel_keys` | Boolean | default `true` |

Missing/unknown values fall back to the defaults (`RemoteHabits.parse*`), so an upgrade never changes behaviour.

## 3. Instant switching

- The settings rows write the Prefs value **and** update the Compose state in the same click.
- `PlayerActivity` re-reads `Prefs.getRemoteSettings(this)` in **`onResume`** and once in `onCreate`, so the very next key event uses the new habit — no restart, no player rebuild.
- `RemoteHabits` (pure Kotlin) owns every mapping, so the Activity only asks "what should this key do".

## 4. Key routing (all guards still win)

`dispatchKeyEvent` order is unchanged: the overlay guards (`drawer / settings / quality / recent / audio`) are evaluated **first**; only when the player itself is in front do the habits apply.

| Habit | Effect |
|---|---|
| 上下键 = 换台 | D-pad up/down → `onChannelStep(∓1)` (as before) |
| 上下键 = 呼出频道列表 | D-pad up/down → `sidebarController.openDrawer()` |
| OK键 = 打开频道列表 | OK opens the drawer |
| OK键 = 确认 | OK shows the info banner (the old behaviour) |
| Back键 = 关闭抽屉 | keeps the historical double-press guard ("再按一次退出app") |
| Back键 = 直接退出 | the first BACK is forwarded to `super` → the Activity finishes |
| 数字键 = 直接跳台 | digits auto-submit after the 3 s OSD timeout |
| 数字键 = 需OK | digits stay in the OSD until OK (`submitNumber`) |
| 频道键禁用 | 166/167 (and PAGE_UP/DOWN) are swallowed; **D-pad still zaps** |

`RemoteHabits.onUpDown/onOk/onBack` return `NONE`/`CLOSE_OVERLAY` whenever `overlayOpen` is true, so a habit can never leak under an overlay — covered exhaustively by a test.

## 5. Tests — `RemoteHabitsTest` (10, pure JVM)

- defaults match the shipped behaviour; unknown/missing values fall back;
- cycling wraps through every option for all four habits;
- up/down, OK and BACK map to the right action for each setting;
- **BACK 直接退出 only affects the player foreground** — an open overlay still closes itself;
- **overlays always win** (every up-down × OK combination is neutralised);
- the digits habit decides whether Enter is required;
- disabling 166/167 does not change the D-pad mapping;
- every option has a label.

JVM total **141 → 151**.

## Preserved

Digit OSD, 上一台 (LAST_CHANNEL), channel keys, LoadControl **2500/6000/500/2000**, MP2 FFmpeg extension, zero-copy SurfaceView, flat UI (0dp, no blue focus), logo cache + switches, recent channels, EPG, auto-update settings, audio-track UI, SR, brand. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **151 tests 0 failures**; lint **0 errors**/85 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` OK; APK 16,117,560 B ≤ 30 MB.
- CI pair receipt (run 44834204079, source `09a00e50`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **151 tests 0 failures** (incl. RemoteHabitsTest 10); lint 0 errors / 83 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,059,991| `bea840a3241f77f53bdff92bc42113f71aa231aeeeae5fcce455b913ddbde8d2` |
| app-debug-androidTest.apk |502,955| `ac89762074fa2f3891122fc030469a02bf6314b949dcd044e54a8571d34922ad` |

- Artifacts: pair `14677948897` (`sha256:7896ec12f40a899e4cc52780a02f8b74717919c1c3301b4bb46f80c7637e8530`, zip 10,329,326 B); reports `14678002127` (`sha256:97d831f9a2cb017195f584d70e769a96d3fd31bb0492eb605115ad7b3913d0c9`). Signer `0c9b9b3fc49057817b9da30974bdb1330ccc0cb7760b06ab0faef817a56aef72`.
- Version: `tv.iqtvlite.app.validation / 10117 / 2.2.0-tivimini-09a00e50-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,059,991 B ≤ 30 MB.

## Device protocol (acceptance, not claimed here)

1. Defaults: up/down zaps, OK opens the list, BACK needs two presses to exit, digits zap after ~3 s, 166/167 zap.
2. Switch 上下键 → 呼出频道列表: up/down now opens the drawer immediately; no channel change.
3. Switch OK → 确认: OK shows the info banner instead of the drawer.
4. Switch Back → 直接退出: one BACK leaves the player (overlays still close themselves first).
5. Switch 数字键 → 需OK: typing 12 shows the OSD and waits; OK jumps, BACK cancels.
6. Disable 166/167: the dedicated channel keys do nothing, D-pad still zaps.
7. 恢复默认 returns every row to the default and the behaviour follows.
