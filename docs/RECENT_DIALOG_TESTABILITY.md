# RECENT DIALOG TESTABILITY (t167)

2026-11-21. Task t167 (developer2), on CI10109 source (after t165). Makes the recent-channels flow **mechanically verifiable** (uiautomator / instrumentation / logcat) without changing behaviour, layout or look.

## 1. Stable semantics ids — `RecentDialogTags`

A pure-Kotlin object holds every tag, so the dialog and the tests cannot drift apart:

| Tag | Applied to |
|---|---|
| `recent_dialog` | dialog container (also `contentDescription = "最近观看"`) |
| `recent_list` | the scrolling row list |
| `recent_row_$index` | each row (`recent_row_0`, `recent_row_1`, …) + `contentDescription = "<number>. <title>"` |
| `recent_empty` | the 暂无最近观看 text (present only when the list is empty) |
| `recent_clear` | 清空最近观看 row |
| `recent_close` | 关闭 row (**new**: an explicit tappable close, Back still works) |
| `settings_recent_entry` | the 最近观看 item in the settings drawer |

With these, a device test can `findObject(By.res("recent_clear")).click()` and assert the empty state via `recent_empty` instead of matching Chinese text.

## 2. Settings focus lands on 最近观看

The settings drawer used to focus 源配置 first. It now requests focus on **最近观看**, so one OK press opens the list (and uiautomator finds the entry immediately after the drawer opens). The rest of the column is unchanged; the focus style is still the flat `surfaceVariant` highlight (no blue frame).

## 3. Clear is observable

`onClear` now logs, right after the cache clear:

```
I/IQTV-RECENT: cleared=true count=0
```

`RecentDialogTags.clearedLog(count)` owns that string, so the acceptance grep (`cleared=true`) is stable. The dialog rebuilds from `recentEntries` immediately → the `recent_empty` node appears in the same frame.

## 4. Logo cache clear reports both stores

`LogoLoader.clearDisk` now uses one deleter for the whole directory (`LogoDiskStore.clear` + `clearTemps`), which removes `*.webp` **and** leftover `*.tmp`, and then logs:

```
I/IQTV-LOGO: cache_cleared=memory:0 disk:0
```

So the “内存 0 条 · 磁盘 120 个” report can be settled from logcat: if `disk:` is non-zero after clearing, the deletion failed; if `memory:` is non-zero, the LRU is still holding bitmaps. (`clearAll` = `clearMemory` + `clearDisk`, i.e. both stores.)

## 5. Tests

`RecentDialogTagsTest` (5, pure JVM): all six stable tags, indexed row tags (`recent_row_0` ≠ `recent_row_3`), the exact cleared log line, `isEmptyStateVisible` for 0/1/MAX, and a uniqueness check so a future edit cannot make two ids collide. JVM total **99 → 104**.

## Preserved

Zero-copy SurfaceView, MP2 extension, flat UI (0dp corners, no blue focus), channel keys, digit OSD, logo cache, EPG, SR, brand & app name. Behaviour unchanged apart from the added 关闭 row (Back still closes the list). Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **104 tests 0 failures**; lint **0 errors**/72 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; APK 16,071,693 B ≤ 30 MB.
- CI pair receipt (run 44819829340, source `31e8ee89`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **104 tests 0 failures** (incl. RecentDialogTagsTest 5); lint 0 errors / 70 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,010,835| `743a3bd4925e3f14425bbb6b85848d8ae162d5e7236fc3334b11a2ed9b5a4ec8` |
| app-debug-androidTest.apk |502,955| `1acd657737b702ef44fe89b10046ea86086e5aad0cad5d40e9ac68d608fc652b` |

- Artifacts: pair `14672166357` (`sha256:f4da9ee828da0a89e123855d92106d6aeaf3fa88c121fa4c72686a87783b86a2`, zip 10,283,684 B); reports `14671213342` (`sha256:2ebd5fa1d544ac11499dc01ba9208646a2897b3a2d2b46dff4913dadfb935f52`). Signer `e945f170e35aa9ea2d625aa9f117e73c831c36fee57a62a4d5eb97b79b851ea4`.
- Version: `tv.iqtvlite.app.validation / 10110 / 2.2.0-tivimini-31e8ee89-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,010,835 B ≤ 30 MB.

## Acceptance protocol (device, not claimed here)

1. Open 设置 (focus is already on 最近观看) → OK → `recent_dialog` appears.
2. `findObject(By.res("recent_row_0"))` exists; click → the channel switches and the list closes.
3. Reopen → `findObject(By.res("recent_clear")).click()` → logcat `IQTV-RECENT cleared=true count=0`, `recent_empty` visible, row tags gone.
4. Force-stop → relaunch → open the list → `recent_empty` still visible (the t159 durability fix).
5. 设置 → 清理台标缓存 → logcat `IQTV-LOGO cache_cleared=memory:0 disk:0`.
