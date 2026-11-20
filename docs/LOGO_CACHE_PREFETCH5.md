# LOGO CACHE 5 — atomic disk store, identity-guarded deletes, prefetch cap (t156)

2026-11-21. Task t156 (developer2), on CI10104 source (after t153). Fixes the t146 review findings on the **disk** half of the logo cache.

## ATOMIC-02 (high) — the disk layer is now atomic and single-owner

New pure-JVM component **`LogoDiskStore`** (real temp-dir testable, no Android deps) owns every disk mutation:

| Concern | Before | Now |
|---|---|---|
| Temp name | fixed `name.tmp` → two writers overwrote each other's bytes | **unique** `name.<seq>.<uuid>.tmp` |
| Publish | `delete(target)` then `rename` → a reader could see **no file** | `rename` replaces in one step (POSIX); JVM/Windows fallback via `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`; delete+rename only as last resort |
| Same-key writes | unsynchronized | **serialized per key** (64-stripe lock table, bounded) |
| Stale-load cleanup | deleted the shared file by `lastModified >= writeStartedAt` / a `readAt` read *after* the gate → always true, so an old load deleted a **newer** file | **never deletes a file it did not read**: the file identity `(lastModified, length)` is captured **before decoding**, and `deleteIfIdentity` removes the file only if it is still byte-identical to what this load decoded; a stale network result leaves the file alone (clearDisk/prune own removal) |
| Orphans | leftover `.tmp` accumulated | `clearTemps()` removes them on every prune |

`writeBitmapToDisk` now delegates to `LogoDiskStore.writeAtomically(file) { bitmap.compress(...) }`.

### LOCK-01 — documented lock order
1. the in-memory `cache` lock is held only for map operations — **no disk I/O inside it**;
2. a disk write takes exactly **one** key lock (never two);
3. therefore `cache`-lock → disk-lock nesting cannot occur and there is no lock cycle.

## PREFETCH-02b (medium) — a selection past index 60 is no longer truncated

- `prefetchBackground` no longer passes `limit = 60` (it truncated the tail of the caller's already-prioritised list).
- `PlayerDrawer` builds the filler with the **selection band first** (`selected-60 … selected+8`), then the head of the group — so a channel at index > 60 is never pushed out by the first 60.

## TEST-01 — real disk, real concurrency

New `LogoDiskStoreTest` (10 tests) runs against a **real temporary directory** (not a mirror of the logic):

- atomic replace keeps the new length (no append, no leftovers);
- temp names are unique per write;
- a failed write leaves the previously published file intact;
- **8 concurrent writers on the same key** → the surviving file is exactly one writer's payload, byte-uniform, no mix, no temp files left;
- a concurrent **reader never sees a partial/truncated file** while 30 rewrites happen;
- `scan`/`prune` enforce the byte cap and keep the newest files;
- `clear(extension)` removes only the matching files;
- `clearTemps` removes killed-process leftovers but keeps the published file;
- **`deleteIfIdentity` refuses to delete a newer file** (the ATOMIC-02 regression) and deletes its own bytes;
- identity of a missing file is null.

JVM total **68 → 78**. Each test creates/destroys its own directory (TEST-02: no shared global state, no flakiness between runs).

## Preserved

Prefetch/cache/settings entry, flat UI, zero-copy base, channel keys, digit OSD, EPG, SR, MP2 audio extension, recent-channels/last-channel, brand & app name. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **78 tests 0 failures**; lint **0 errors**/68 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; APK 16,057,181 B ≤ 30 MB.
- `Files.move` is reached **reflectively**, so no API-26 symbol is referenced (minSdk 24 stays clean — lint `NewApi` would otherwise fail).
- CI pair receipt (run 44809183573, source `976ac9c0`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **78 tests 0 failures** (incl. LogoDiskStoreTest 10); lint 0 errors / 66 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |12,994,451| `5cd0a544248f1b51c58ad6b85ee779b3cfbe3573bdf992b32e4b023824880a99` |
| app-debug-androidTest.apk |502,955| `f634d8ae6e6595a2fd681c131e58a278957c48e3315e8a9061421adb10694ae6` |

- Artifacts: pair `14666240297` (`sha256:3e7334d98ec8b2ef29a14097c98430684728d8e96d65ec2b13d013166173f00d`, zip 10,267,004 B); reports `14666150285` (`sha256:8bfb3a5a76e6e0f8b0eff7428c64d86bd3a41d5a5825fb061c99f36e038b1a2c`). Signer `8443977dc33219679b985d118f4b7869314b808c5d3d2214958a5a8ed312f1e6`.
- Version: `tv.iqtvlite.app.validation / 10105 / 2.2.0-tivimini-976ac9c0-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 12,994,451 B ≤ 30 MB.

## Device expectation (acceptance, not claimed here)

Rapid zapping while the drawer prefetches must never show a corrupt/blank logo that persists (previously possible when two loads wrote the same key); settings → 清理台标缓存 still empties the directory; a channel past index 60 gets its logo filled after the 1 s idle delay.
