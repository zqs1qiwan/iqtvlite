# SETTINGS SCROLL FIX (t192)

2026-11-21. Task t192 (developer2), on CI10119 source (after t189). Fixes the **availability blocker** found on the real device (t188): the settings drawer could not scroll, so the EPG rows were unreachable.

## Symptom (device, 1080p / 240 dpi)

Visible rows stopped at 「立即刷新播放列表」 (y≈945). 「立即刷新 EPG」 / 「自动更新 EPG」 / 「上次更新」 sat below y≈981, and **neither DOWN×6/DOWN×8 nor a swipe scrolled** the list — the EPG settings were simply unreachable with the remote.

## Cause

The drawer was a plain `Column(Modifier.fillMaxSize())`: content taller than the panel was clipped, focus could move to an off-screen row (invisible), and there was no scroll container for the D-pad or a touch swipe to act on.

## Fix

1. **The list scrolls**: `Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("settings_list"))`. `verticalScroll` (not `LazyColumn`) is deliberate here — the settings list is ~30 cheap rows, so composing them all keeps focus order stable and avoids item-recycling complexity inside a focus-driven TV drawer; there is no per-frame work and therefore no new jank.
2. **Focus follows the viewport**: every `SettingsItem` now owns a `BringIntoViewRequester` and, on `onFocusChanged { isFocused }`, calls `bringIntoView()` in a coroutine → D-pad DOWN to a row outside the viewport scrolls it into view automatically. No call site had to change, so the 30+ rows are all covered.
3. **Stable test tags** for automation: `settings_list` on the container, and `settings_source_config`, `settings_epg_source`, `settings_quality_menu`, `settings_check_update`, `settings_auto_playlist`, `settings_refresh_playlist`, `settings_auto_epg`, `settings_refresh_epg`, `settings_audio_track`, `settings_logo_prefetch`, `settings_logo_load`, `settings_logo_clear`, `settings_remote_updown/ok/back/digits/channel_keys/reset`, plus the existing `recent_entry`.
4. Group headers stay plain `Text` labels (no `focusable`/`clickable`), so they never steal focus while scrolling; the BACK layer is untouched (it still closes only the settings drawer, one level at a time).

## Appearance

Unchanged: 0 dp corners, `surfaceVariant` focus fill (no blue frame), same fonts, same 16/14 dp paddings, same group titles. Only the container gained a scroll modifier.

## Static gate

`scripts/check_settings_scroll.py` asserts against the real source that the list scrolls, the tags exist, rows request bring-into-view, and the group header is not focusable. Verified: `check_settings_scroll: OK`.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **160 tests 0 failures**; lint **0 errors**/86 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` OK; `check_refresh_paths` OK; `check_settings_scroll` OK; APK 16,122,444 B ≤ 30 MB.
- CI pair receipt (run 44840965336, source `245f56ba`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **160 tests 0 failures**; lint 0 errors / 84 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,059,987| `dcba0f673595bfc9da76bfeadc01bb839fb22df5c83533f7b538a0915a4e1fbd` |
| app-debug-androidTest.apk |502,955| `3d9962656b92204b28cc1606c16ef5e7c144182945d40da5211c8e3cc34dc7a0` |

- Artifacts: pair `14681207092` (`sha256:02f810b20d7df826e4965cfc8324415cbbd28c357adf5225eeeb7bf53e8f07cb`, zip 10,332,954 B); reports `14681281755` (`sha256:fa798854c0eb65554a6a6e3f03aea201bd002786cb11cf04d9499561fc809423`). Signer `22e83d396f5cbd51c8bf1f7df8f6f85fc033f380d770458301545768c195bed0`.
- Version: `tv.iqtvlite.app.validation / 10120 / 2.2.0-tivimini-245f56ba-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,059,987 B ≤ 30 MB.

## Device protocol (acceptance, not claimed here)

1. Open 设置 → DOWN to the bottom: every group is reachable, in particular 「立即刷新 EPG」「自动更新 EPG」「上次更新」「清理台标缓存」.
2. Focus a row that starts below the fold → the list scrolls it into view (no invisible focus).
3. Swipe/touch on the list scrolls it as well.
4. BACK closes only the settings drawer (one level), overlay layering unchanged.
5. 「立即刷新 EPG」 still issues exactly one refresh (t189 behaviour preserved).
6. No visual change: 0 dp corners, no blue focus frame, same fonts/spacing.
