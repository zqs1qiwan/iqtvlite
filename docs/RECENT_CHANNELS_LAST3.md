# RECENT CHANNELS & LAST CHANNEL 3 — resolve-first, memory list, variant matching (t153)

2026-11-21. Task t153 (developer2), on CI10103 source (after t152 audit). Fixes the t142 review findings.

## PLAY-01 (high) — 上一台 only switches when the target is fully valid

Before: `playLastChannel()` looked the group up with `indexOfFirst { key }` and passed `previous.variantIndex` straight to `playGroupVariant`, but **set `lastChannelSwitchAt`, `infoBannerOpen` and returned `true` regardless** — with a legacy `url:` key, a refreshed group key or an out-of-range variant the player did nothing while the app believed it had switched (and the 800 ms debounce then blocked the next press).

Now:
```kotlin
val target = RecentChannels.previousResolved(recentEntries, channelGroups, currentGroupKey) ?: return false
if (target.groupIndex !in channelGroups.indices) return false
if (target.variantIndex !in target.group.variants.indices) return false
lastChannelSwitchAt = now            // only after everything resolved
playGroupVariant(target.groupIndex, target.variantIndex)
infoBannerOpen = true
return true
```
`previousResolved` = newest entry that **resolves** and whose **group differs** from the current one; unresolved rows and other lines of the same channel are skipped.

## DEBOUNCE-01 (high) — the list lives in memory

- New **`recentEntries: List<RecentChannels.Entry>`** state (initialised once from Prefs) is the single source of truth for the dialog **and** for 上一台.
- `recordRecentChannel` pushes onto **`recentEntries`** (never onto a fresh `Prefs` snapshot), so **A→B→C keeps B**; the encoded value goes to `pendingRecentEncoded` and a 400 ms coroutine writes it.
- `flushRecentChannels()` writes the pending value immediately; a **`LifecycleEventObserver` flushes on `ON_STOP`/`ON_PAUSE`** and `onDispose` flushes again, so the last zap survives backgrounding/exit.
- `recentResolved` is built from `recentEntries` (`resolvedDistinct`), so the dialog updates as soon as the channel changes; `onClear` clears the memory list **and** the pending value **and** cancels the write before persisting an empty list.

## VARIANT-01 — the line, not the index

`RecentChannels.resolveOne`:
1. group by `key` → `url:` prefix → `fallbackUrl`;
2. variant by **`fallbackUrl` match first** (a channel whose line order changed still plays the same line);
3. otherwise the stored index **only if it still exists**, else the group's default line (0) — never an arbitrary clamped index.

`Resolved(entry, group, groupIndex, variantIndex, channel)` guarantees every index is valid, which is what PLAY-01 checks.

## LEGACY-01 — legacy `url:` rows

`resolvedDistinct()` collapses a legacy `url:<stream>` row and a modern group-key row that resolve to the same group into **one** row (first/newest wins), capped at 10 — so an upgraded install neither duplicates a channel nor treats another line of the same channel as "上一台".

## BACK-02 (low)

The recent-list open state is now mirrored by a `LaunchedEffect(recentChannelsOpen)` writing through the existing controller (`onRecentChannelsOpenChanged`), i.e. the Activity sees one authoritative value; the BACK guard reads that value first (already in place from t141) — no extra state machine was added.

## TEST-01

New `RecentChannelsResolveTest` (9 tests, pure JVM, the same functions the Activity calls):
- variant matched by fallback url before index;
- out-of-range index → default line (0), not clamped;
- `previousResolved` skips same-group rows, unresolved rows, empty lists, empty playlist;
- legacy `url:` row deduped with the group row; legacy row alone still resolves;
- `resolvedDistinct` newest-first + cap;
- **debounce regression**: push A→B→C on the in-memory list keeps all three and survives encode/decode;
- clear produces an empty, decodable state.

JVM total **59 → 68**.

## Preserved

Zero-copy base, channel keys 166/167, digit OSD, flat UI, MP2 audio extension, logo cache/prefetch, EPG, SR, brand & app name. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **68 tests 0 failures**; lint **0 errors**/68 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; APK 16,053,647 B ≤ 30 MB.
- CI pair receipt (run 44806007427, source `8e6ac3ea`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **68 tests 0 failures** (incl. RecentChannelsResolveTest 9); lint 0 errors / 66 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |12,994,451| `b8634e865a3afea220b58e8ca90f57ff3b52f55dde44a0304a12e239940659d4` |
| app-debug-androidTest.apk |502,955| `743531cb54e099815be5198d8d55ff7340c885207777063741645661c1806197` |

- Artifacts: pair `14664345256` (`sha256:a99371d270f1c79fe7de5c3cb6d85f6870f990ef05dec7feca9ef20547a3c574`, zip 10,266,166 B); reports `14663476653` (`sha256:a4b8aec05687dd54fdc711c074dd947d0d61f32b5b0b515daae8b259c54937ae`). Signer `c375f785c104f54fe3b9f5438d835c5446b1673717e539e253e31ab896c202d1`.
- Version: `tv.iqtvlite.app.validation / 10104 / 2.2.0-tivimini-8e6ac3ea-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 12,994,451 B ≤ 30 MB.

## Device checklist (acceptance, not claimed here)

Zap A→B→C quickly → 上一台 returns to B (not C or A); 上一台 after a quality switch returns to the other channel; legacy install (upgraded from CI10100) shows no duplicate row; clear → empty now and after restart; BACK with the list open closes only the list.
