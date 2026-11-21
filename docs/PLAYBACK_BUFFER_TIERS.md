# PLAYBACK BUFFER TIERS (t205)

2026-11-21. Task t205 (developer2), on CI10124 source (after t204). Turns the one-shot `playbackBufferMin = 300` A/B arm into a **user-selectable start-up buffer tier** with a safe default. Device A/B of the three tiers belongs to acceptance2.

## Why

t197 changed `playbackBufferMin` 500 → 300 to shave TTFF. The device showed the trade-off: 300 ms starts faster but produced a **5.8 s rebuffer on cold start** on this v7a box. A single hard-coded value cannot serve both profiles, so it becomes a three-way choice with the **safe tier as default**.

## Tiers

| Row value | Label | start-up buffer (`playbackBufferMin` ) | Notes |
|---|---|---:|---|
| 300 | 快(300ms) | 300 | fastest start, most exposed to a weak segment |
| 400 | 均衡(400ms) | 400 | middle ground |
| 500 | **稳(500ms)** | **500** | **default** — matches the pre-t197 behaviour that never showed the 5.8 s cold-start rebuffer |

`minBufferMs = 2500` and `bufferForPlaybackAfterRebufferMs = 2000` are **unchanged** for every tier, so the USAB2-01/CI10077 invariant (`minBuffer >= afterRebuffer`, `playbackBufferMin <= bufferForPlayback = 500`) holds for all three — asserted by `LoadControlTuning.isValidFor(tier)` and exercised through the **real `DefaultLoadControl.Builder`** in `LoadControlConfigTest`.

## Setting

New group **播放与显示** with one row:

```
起播缓冲：稳(500ms)     ← OK cycles 快(300) → 均衡(400) → 稳(500) → 快…
快=起播更快但弱网更易卡顿；稳=更抗抖动。切换后下次切台生效。
```

- Tag: `settings_playback_buffer` (inside the scrollable list, so it is reachable on 1080p).
- The row writes `Prefs` **and** a Toast: `起播缓冲已设为稳(500ms)，下次切台生效`.

## Pref

| key | type | values | default |
|---|---|---|---|
| `playback_buffer_min` | Int | 300 / 400 / 500 | **500** |

`Prefs.getPlaybackBufferMin()` runs the value through `LoadControlTuning.sanitize()`, so a corrupted/legacy value (e.g. `0`, `-1`, `123`) can never reach the builder — it falls back to 500.

## When it takes effect

`LoadControl` is constructed in `initializePlayer()`, i.e. **per player creation**. Changing the tier does **not** rebuild the running player (that would interrupt playback); it applies on the **next channel switch / player (re)creation**. The setting row says so explicitly and the docs repeat it.

## Debug line

Gated by `Log.isLoggable("IQTV-ZAP", DEBUG)`:

```
IQTV-ZAP bufferMin=500
```

printed once per player creation, so an A/B can prove which tier actually took effect (`adb shell setprop log.tag.IQTV-ZAP DEBUG`). Off by default → zero logging cost.

## Tests

`LoadControlConfigTest` (**5**): production durations accepted through the real builder; **every tier (300/400/500) builds and keeps `min >= afterRebuffer`**; **the default is 稳/500** (`playbackBufferMinMs(0)`/`(999)` both → 500); unknown values fall back and the cycle wraps (300→400→500→300, `tierLabel(999) == 稳(500ms)`); the invalid 800/…/1000 set still throws (CI10077 regression).

JVM total **174 → 176**.

## Preserved

F1 remote habits, F3 auto-update, F4 audio UI, TTFF instrumentation (`ZapTrace`), MP2 FFmpeg extension, zero-copy SurfaceView, flat UI, channel keys, digit OSD, logo cache, recent channels, EPG, SR, brand. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **176 tests 0 failures**; lint **0 errors**/87 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` / `check_refresh_paths` / `check_settings_scroll` all OK; APK 16,130,385 B ≤ 30 MB.
- CI pair receipt (run 44850762787, source `52701870`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **176 tests 0 failures**; lint 0 errors / 85 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,059,987| `e724395771f7b3e67fe1369b7829a86b2bc97fe5d7eddbe6649b7dde6470bbba` |
| app-debug-androidTest.apk |502,955| `6054900914a8ceb887acbeb286b7f7ec5dd7adf0633c779cc5bdbed2a1bdf3d1` |

- Artifacts: pair `14685691305` (`sha256:8910ee0c5a849e8a2a2ed310597f6c62c86766870bf9f58a0db38eae4d47981e`, zip 10,339,449 B); reports `14684968780` (`sha256:a78358e00e60272f69ea597b4112618611e42b91c9a9bd82f9f06bd528dd7bd6`). Signer `5cd891d94d79e180e5f9b25c92ddbc9996031323664d85c045f7a2afaad9b083`.
- Version: `tv.iqtvlite.app.validation / 10125 / 2.2.0-tivimini-52701870-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,059,987 B ≤ 30 MB.

## Device protocol (acceptance, not claimed here)

> Same package, three runs, only the row changes. Record TTFF p50/p95 (10 zaps) **and** rebuffers per tier; keep the tier that wins on the combination, defaulting to 稳 if 快/均衡 regress.

1. 设置 → 播放与显示 → 起播缓冲：确认默认 **稳(500ms)**。
2. 切到 快(300) → Toast「下次切台生效」 → zap 一次 → `IQTV-ZAP bufferMin=300` in logcat, 10-zap p50/p95 + rebuffer count.
3. Repeat for 均衡(400) and 稳(500) (`bufferMin=400` / `bufferMin=500`).
4. Confirm `minBuffer`/`afterRebuffer` behaviour unchanged (no start-up crash on any tier).
5. Reboot / cold start with 稳 selected → no 5.8 s rebuffer.
