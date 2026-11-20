# AUTO UPDATE SETTINGS — playlist / EPG (t172, F3)

2026-11-21. Task t172 (developer2), on CI10111 source (after t169). Adds the 自动更新播放列表/EPG settings with a 设置分组骨架. Playback, logo pipeline and recent-channels behaviour are untouched.

## 1. Settings skeleton (minimal groups)

| Group header | Items |
|---|---|
| **基础** | 源配置 · EPG 设置 · 清晰度菜单 · 检查更新 · 最近观看（N） |
| **数据与更新** | 自动更新播放列表：<当前值> · 立即刷新播放列表 · 自动更新 EPG：<当前值> · 立即刷新 EPG · 上次更新：播放列表 <…> · EPG <…> |
| **台标与缓存** | 台标预取开关 · 台标显示开关 · 刷新当前组台标 · 清理台标缓存 |

Headers are plain labels (flat, 0dp, never focusable), rows keep the existing `SettingsItem` style (no blue frame), focus lands on 最近观看 on open, and BACK still closes only the settings drawer.

## 2. Options (single choice, OK cycles)

`关闭 → 启动时 → 每 6 小时 → 每 12 小时 → 每 24 小时 → (wrap)`

- **Default is 启动时**, i.e. exactly the pre-F3 behaviour.
- `关闭` means **no automatic pull at all** — not at startup, not periodically (verified by `AutoUpdateConfig.shouldRefresh` returning false for OFF in every state).

## 3. Persistence (`Prefs`)

| Key | Type | Meaning |
|---|---|---|
| `auto_update_playlist` | String | `OFF`/`STARTUP`/`H6`/`H12`/`H24` |
| `auto_update_epg` | String | same enum |
| `playlist_last_success_at` | Long | last successful playlist refresh |
| `playlist_last_failure_at` | Long | last failed attempt |
| `playlist_fail_count` | Int | consecutive failures (drives backoff) |
| `epg_last_failure_at` | Long | last failed EPG attempt |
| `epg_fail_count` | Int | consecutive EPG failures |
| `last_epg_update_time` | Long | pre-existing EPG success timestamp (reused) |

Success clears the failure state (`setPlaylistLastSuccess` / `clearEpgFailure`), so the backoff resets after a good run. Everything is persisted, so a **process restart or device reboot re-evaluates the policy from Prefs**.

## 4. Scheduling — process coroutine, not WorkManager

`AutoUpdateScheduler` (new) owns a `CoroutineScope(SupervisorJob() + Dispatchers.IO)`:

- one startup pass, then a **60 s tick** that calls `AutoUpdateConfig.shouldRefresh(...)` and refreshes only what is due;
- the work itself is delegated to the existing **`BackgroundSourceUpdater.refreshPlaylistUrgent` / `refreshEpg`**, so the download/parse/commit path (mutex + atomic source commit) is unchanged;
- `refreshNow()` powers the two 立即刷新 rows and **bypasses both the interval and the backoff**;
- results are pushed into a `SharedFlow` that `VideoPlayerScreen` turns into a Toast (播放列表已更新 / 更新失败 / EPG已更新 / EPG更新失败).

**Why not WorkManager**: the refreshes only matter while the app is running; the policy is persisted so a restart re-evaluates it; adding WorkManager would ship a new dependency + JobScheduler surface for a ≤30 MB TV app, and the existing `BackgroundSourceUpdater` already owns the IO path. Nothing runs on the main thread (the scheduler and the updater are both IO-scoped).

`IptvApplication.onCreate` now calls `AutoUpdateScheduler.start(this)` instead of the unconditional `scheduleStartupRefresh(...)`, so **关闭 really means no startup pull**.

The EPG coordinator loop in `VideoPlayerScreen` also consults the policy now: automatic EPG refresh happens only when the user's interval says so **and** the backoff allows it; the manual 立即刷新 path sets `shouldRefreshEpg` and bypasses both.

## 5. Failure backoff

`AutoUpdateConfig.backoffMs(failCount)` = 5 min, 10, 20, capped at **30 min**. A failed attempt is retried when its window elapses (`lastFailureAt`), independent of the 6/12/24 h interval — waiting a full day after a transient failure would be wrong. The backoff also applies to the startup pass (a failure recorded just before a restart is not retried immediately).

## 6. Tests — `AutoUpdateConfigTest` (10, pure JVM)

- default is 启动时, unknown strings fall back to it, encode/decode round-trips every option;
- cycle order wraps 关闭 → … → 24h → 关闭;
- **OFF never refreshes automatically** (even with a pending startup pass / stale timestamp);
- 启动时 refreshes once per process only;
- 6h/12h/24h refresh after the period, not before;
- backoff 0/5/10/20/30 min (capped) and the "retry after the window" behaviour;
- backoff also gates the startup pass;
- 上次更新 label formatting (从未更新 / 刚刚 / N 分钟前 / N 小时前 / N 天前);
- `isPeriodic` matches OFF/STARTUP vs the hourly options.

JVM total **109 → 119**.

## Preserved

LoadControl **2500/6000/500/2000**, MP2 extension, zero-copy SurfaceView, flat UI (0dp, no blue focus), channel keys, digit OSD, logo cache + prefetch/load switches, recent channels, EPG rendering, SR, brand & app name. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **119 tests 0 failures**; lint **0 errors**/78 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` OK; APK 16,084,712 B ≤ 30 MB.
- CI pair receipt (run 44824103325, source `094609d7`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **119 tests 0 failures** (incl. AutoUpdateConfigTest 10); lint 0 errors / 76 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,027,219| `4eabf6b30a9581862bf948b50ee49e56d694c9800907b49c17ad353b0e7ac288` |
| app-debug-androidTest.apk |502,955| `54bb7cff458c44a4798a0f2bf5ff8a8b72a043f6fc0c346882b21ce30bdd330e` |

- Artifacts: pair `14674405021` (`sha256:4e40777ae4d0c2c923e9c6031e8fb8710c53d45e7a85b93b8f14cd910d876100`, zip 10,296,501 B); reports `14672619641` (`sha256:944757cebd15078a75ca31b754d341547038afbd31968392c67d3d55f1a3217b`). Signer `ca53410be543536a2cb548af0a317135b78843c084d69fea693a0337367eeecc`.
- Version: `tv.iqtvlite.app.validation / 10112 / 2.2.0-tivimini-094609d7-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,027,219 B ≤ 30 MB.

## Device protocol (acceptance, not claimed here)

1. 设置 shows the three groups; focus starts on 最近观看; BACK closes only the drawer.
2. 自动更新播放列表：关闭 → kill the app, relaunch → no playlist request in logcat (`SourceRefresh` absent); relaunch after switching to 每 6 小时 → one startup request, and none again within 6 h.
3. 立即刷新播放列表 / 立即刷新 EPG → Toast on success/failure and 上次更新 line updates.
4. Force a failure (airplane mode) → 5 min backoff (retry earlier than the interval, but not immediately); manual refresh still works.
5. Settings survive a restart.
