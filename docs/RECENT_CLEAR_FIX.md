# RECENT CHANNELS — durable clear, BACK, OK (t159)

2026-11-21. Task t159 (developer2), on CI10106 source (after t157). Fixes the **clear-not-persistent blocker** found on device (CI10104: after 清空 + force-stop the list still showed 131/134/135/133), plus the BACK double-exit and the OK-selection path.

## Why the clear did not stick (three independent causes)

1. **`apply()` instead of `commit()`** — `Prefs.clearRecentChannels` used `apply()`, which only schedules the write. A force-stop right after clearing loses it. Every recent-channels write now uses **`commit()`** (the value is tiny).
2. **Stale debounced write** — `flushRecentChannels()` wrote `pendingRecentEncoded` unconditionally. If a channel had been started in the 400 ms before clearing, the pending value still held the old list, and **ON_STOP/ON_PAUSE/onDispose flushed it back** — resurrecting the list after the user cleared it.
3. **Re-record on re-prepare** — clearing did not stop the *currently playing* channel from being recorded again by a playlist commit / recovery / catch-up restart, which re-created a row immediately.

## The fix — `RecentChannelsCache` (pure Kotlin, unit-tested)

One authoritative in-memory state with a `Storage` seam (`readEncoded`/`writeEncoded`/`clearEncoded`/`isCleared`):

| Rule | Effect |
|---|---|
| `record()` bumps **generation** | a debounced flush carries the generation it was scheduled for |
| `flush(gen)` drops a **stale generation** | an older scheduled write can never overwrite a newer list |
| `flush()` returns false when **cleared && empty** | ON_STOP/ON_PAUSE/onDispose can no longer write the list back |
| `clearAll(currentGroupKey)` clears list + pending + storage and sets a **persistent `recent_channels_cleared` marker** in the same `commit()` | the clear is durable across force-stop |
| `record()` ignores the group that was playing at clear time while `cleared` | a re-prepare of the same channel does not rebuild a row; a **different** channel starts a new list and lifts the marker |

`Prefs`:
- `setRecentChannels` writes the list **and** clears the marker in one `commit()`;
- `clearRecentChannels` removes the list **and** sets the marker in one `commit()`;
- `isRecentChannelsCleared` exposes the marker.

Startup never writes: `RecentChannelsCache` only *reads* on construction (no `onResume`/`onStart` write path exists).

## BACK double-exit

The Activity mirrors the recent-list open state, but the mirror was published by a `LaunchedEffect` — there was a window where BACK still took the drawer/finish path. The Compose side now publishes **synchronously** with the state change (`setRecentOpen` → `onRecentChannelsOpenChanged`), so `dispatchKeyEvent` sees `isRecentChannelsOpen == true` on the very next key and consumes BACK (closing only the list).

## OK selection

`onSelect` resolves the row to `(group, channel)`, finds the group index and the variant index **by url**, and calls `playGroupVariant(groupIndex, variantIndex)` — i.e. it plays the exact line stored in the row, then closes the list and the drawer. (Unchanged logic, now covered by the resolve tests.)

## Tests — `RecentChannelsCacheTest` (10, pure JVM, production class)

- record → flush persists and lifts the marker;
- **clear drops list + pending + storage**;
- **flush after clear writes nothing** (the blocker regression, both with a stale generation and without);
- **reload after clear is empty** (fresh process, force-stop simulation);
- stale-generation flush is dropped while the newest wins;
- recording after clear builds a new list;
- an empty list is never written back when the marker is set;
- **re-preparing the cleared channel does not rebuild the list**;
- **switching to a different channel after clear starts a new list**;
- clear is idempotent.

JVM total **79 → 89**.

## Preserved

Zero-copy base, channel keys 166/167, digit OSD, flat UI, MP2 audio extension, logo cache/prefetch, EPG, SR, brand & app name. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **89 tests 0 failures**; lint **0 errors**/70 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; APK 16,058,069 B ≤ 30 MB.
- CI pair receipt (run 44813428778, source `5c04b1c6`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **89 tests 0 failures** (incl. RecentChannelsCacheTest 10); lint 0 errors / 68 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |12,994,455| `3f22a3bad243b070179ebd2577b5d8f2fda40c5e47229ae0d37d0c4a3682f367` |
| app-debug-androidTest.apk |502,955| `b9e71cc8a507f36db0b3931f49634e7c0f812e417c0b5a28bcd042b3117caf16` |

- Artifacts: pair `14668648205` (`sha256:288ca752b240690729a7fdf988958e425342b7f66465b9bd2c02ad05d13f7549`, zip 10,267,166 B); reports `14668633129` (`sha256:7488091ca5382cd7363afd8a66ebccb771b79dcf76d98068eb72f88fa22dfdb5`). Signer `1b7c4169e2cddbd85b93f2ddf7a1197a812709e01c271491c40292ed449a4afe`.
- Version: `tv.iqtvlite.app.validation / 10107 / 2.2.0-tivimini-5c04b1c6-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 12,994,455 B ≤ 30 MB.

## Device checklist (acceptance, not claimed here)

设置 → 最近观看 → 清空 → list empty immediately → **force-stop → relaunch → still empty**; then zap to a channel → the list starts again from that channel; BACK with the list open closes only the list (the player stays); OK on a non-current row switches to that channel and closes the list.
