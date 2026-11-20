# RECENT CHANNELS & LAST CHANNEL 2 — group identity, guards, clear (t141)

2026-11-21. Task t141 (developer2), on CI10101 source (after t137). Fixes the t134 review findings plus two device bugs found on t140.

## BACK-01 (high) — the recent list is a first-class overlay

- `PlayerActivity` gained **`isRecentChannelsOpen`**, fed by `onRecentChannelsOpenChanged` from `VideoPlayerScreen` (a `LaunchedEffect(recentChannelsOpen)` mirrors the Compose state).
- **All key guards now include it**: digit keys, D-pad/OK/MENU branches and the channel keys all require `!isRecentChannelsOpen` (same expression as drawer/settings/quality).
- **BACK is handled first**: if the recent list is open, BACK closes only the list (via `setRecentChannelsOpenController(false)`) and consumes the event; it never falls through to "close drawer"/"exit player".
- **LAST_CHANNEL / MEDIA_PREVIOUS (229 / 88)**: the branch now returns **early** when drawer/settings/quality/**recent** is open — the key is *not* consumed and can never switch a channel underneath an overlay (device bug: 229 previously zapped while the drawer was open; note `KEYCODE_LAST_CHANNEL` is **229**, 171 is `KEYCODE_WINDOW`).

## RECENT-01 (high) — identity is the channel GROUP

`RecentChannels.Entry` now stores **`groupKey`** (from `ChannelGroup.key`) plus the **`variantIndex`** that was actually playing:

- `push(current, group, number, variantIndex)` de-duplicates by group key → switching line/quality inside one channel **updates the row**, it never creates a second "channel" and `previous()` never returns another line of the same channel.
- `decode` keeps the legacy 5-field format working (old row → `groupKey = "url:<url>"`, resolved through the channel url).
- `resolve(entries, groups)` looks up the group by key first, then by `url:` fallback, then by the stored fallback url; **missing groups are skipped** (playlist refresh safe). The stored `variantIndex` is clamped to the group's current variant count (a line that disappeared falls back to variant 0).

## RECENT-02 (medium) — current variant, not default

- `playLastChannel()` compares the **current group key** (`channelGroups[currentGroupIndex].key`) and switches with `playGroupVariant(targetGroupIndex, previous.variantIndex)` — the actually-playing variant, not `defaultChannel`.
- The recent list is built from groups (`resolve(entries, channelGroups)`), shows `group.displayTitle` / `group.logoUrl`, marks the row whose **group** matches the current one, and selecting a row plays the group at its stored variant.

## UI

- **UI-01**: the dialog has a `FocusRequester` for the first row **and** for the 清空 row; the `LaunchedEffect(visible, items.size)` focuses the first row when non-empty, otherwise the clear row — deterministic focus after clearing.
- **UI-02**: row logo is now **64×40 dp**, the same key the drawer/prefetch uses, so it hits the logo cache instead of re-downloading at 40×28.
- **UI-03**: the currently playing row renders a **`▶ ` prefix** (the `selected` flag was computed but never shown).

## Persistence (PERF-01, ENCODE-01)

- **PERF-01**: rapid zapping no longer writes prefs per press — `recordRecentChannel` updates the in-memory list, stores the encoded value in a pending field and restarts a **400 ms** coroutine; only the last state is written.
- **ENCODE-01**: `encode` sanitizes `groupKey`/`title`/`logoUrl`/`fallbackUrl` (separator characters replaced) and trims, so a weird channel name can never corrupt the blob.
- **Clear bug (t140)**: `onClear` now **cancels the pending debounced write and nulls it before** `Prefs.clearRecentChannels` — previously a pending zap could re-create the list right after clearing (so it survived a restart).

## Tests

`RecentChannelsTest` rewritten for the group model (8 tests): group-key de-dup (line switch updates one row), cap at MAX, encode/decode round-trip + separator sanitizing, garbage/empty/legacy decode, resolve skipping missing groups + url fallback, stored variant selection, previous skipping the current group, out-of-range variant fallback. JVM total **52 → 54**.

## Preserved

Zero-copy base, channel keys 166/167, digit OSD, flat UI, MP2 audio extension, logo cache/prefetch, EPG, SR, brand & app name. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **54 tests 0 failures**; lint **0 errors**/68 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; APK 16,049,817 B ≤ 30 MB.
- CI pair receipt (run 44800974359, source `5d4c36e0`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **54 tests 0 failures**; lint 0 errors / 66 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |12,978,067| `30095a73755dadcdba2a700c8b6ad37d3fc6e86387cb1ec2b817a31721c7810a` |
| app-debug-androidTest.apk |502,955| `2b021cdff071bc5e5d21f1c9f02a656604189799f9f942fa94e5eed2394799de` |

- Artifacts: pair `14661122834` (`sha256:adddb59ab0ab0b79405076d3a9d665cb9dc242925431b743d61733d90da84565`, zip 10,262,193 B); reports `14660693628` (`sha256:daa91456bbd6250c4e8a3a774961024e866ccb76a71363e2916e7a995d531c81`). Signer `bb320493e471f1a7c3a9814d4164abf594251fdfc706355e385b8b7d5c96e0f5`.
- Version: `tv.iqtvlite.app.validation / 10102 / 2.2.0-tivimini-5d4c36e0-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 12,978,067 B ≤ 30 MB.

## Device checklist (acceptance, not claimed here)

Clear → list empty immediately **and after restart**; drawer open + key 229 → drawer stays, no zap; quality switch → LAST_CHANNEL returns to the other channel, not another line of the same; OK on a recent row → list closes and playback returns; BACK with list open → only the list closes.
