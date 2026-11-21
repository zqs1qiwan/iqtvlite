# NEIGHBOUR PRE-CONNECT (arm B, default OFF) — ZAP_PRECONNECT (t218 B)

2026-11-21. Task t218 part B (developer2), on CI10129 source. **Opt-in experiment, default OFF — the shipped behaviour is unchanged.** Device A/B belongs to acceptance2.

## Why this arm

The t217/t218 A measurement is unambiguous:

| stage | p50 | share of TTFF |
|---|---:|---:|
| `fmt` (prepare → first format = network first packet + demux join) | **1134 ms** | **~94 %** |
| `dec` (MediaCodec create/configure) | 7 ms | ~0.6 % |
| `render` (first decode + Surface submit) | 75 ms | ~6 % |

Decoder work and rendering are already negligible, so the only lever with real headroom is the **network path** of the channel the user is about to zap to. Expected effect: TTFF 1207 ms → 500–700 ms if the connection setup is what the `fmt` window is waiting for.

## What it does

`ZapPreconnect` warms **TCP connect only** for the immediate neighbours of the current channel:

- `PreconnectPolicy.neighbours(size, current)` → next, then previous (max 2, `MAX_TARGETS`);
- one socket per target, `connect(host, port, timeout)`, **closed immediately** — no request, no body, no media prefetch;
- whole pass bounded by `withTimeoutOrNull(2000)`; one pass at a time (`AtomicBoolean`);
- throttled to one pass per 3 s;
- **every failure is swallowed** (a warm must never affect a real zap);
- `ZapPreconnect.cancel()` runs on every real zap (`playGroupAuto`), so a warm can never compete with the connection the user is waiting for;
- only `http(s)` URLs are warmed (RTP/UDP is handled by the player's own transport).

## When it runs

`LaunchedEffect(zapPreconnectEnabled, drawerOpen, firstFrameRendered, currentGroupIndex)`:

- **only when the experiment is enabled**;
- and only while the **channel list is open** (the user is browsing → likely to zap next) or the player is **idle after the first frame**;
- re-runs when the current channel changes, so the neighbours stay correct.

On a 512 MB box that is at most **2 short-lived sockets, only while the user is browsing**, which is the smallest footprint that can still warm the path.

## Setting

**播放与显示 → 预连接相邻频道（实验）：关 / 开** (tag `settings_zap_preconnect`), with the note:

> 开启后会在列表打开或空闲时预热相邻频道的网络连接（每台约 1 个连接、2 秒上限）。默认关闭；如出现卡顿或占用升高请关回。

Pref: `zap_preconnect_enabled` (Boolean, **default false**). Turning it off cancels any running pass immediately.

## A/B protocol (acceptance, not claimed here)

> Single variable: same channel, same buffer tier (稳/500), D-pad 10 zaps each.

| run | 预连接 | TTFF p50 | TTFF p95 | `fmt` p50 | rebuffer | CPU | PSS |
|---|---|---:|---:|---:|---:|---:|---:|
| A | 关（默认） | 1207 ms | _pending_ | 1134 ms | 0 | baseline | baseline |
| B | 开 | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ |

Also watch during run B: `dumpsys meminfo` PSS, `top -n 1` CPU, `logcat -s IQTV-PRECONNECT` (one line per pass: `attempted=… connected=… elapsed=…ms`), and any crash/ANR.

**Decision rule:** keep it opt-in (default off) unless B reaches **p50 ≤ 700 ms with no rebuffer/CPU/PSS regression**. If it regresses, report the numbers and leave the default off — the code stays behind the flag.

## Risks and rollback

| risk | mitigation |
|---|---|
| extra sockets on a 512 MB box | max 2, connect-only, closed immediately, one pass at a time, only while browsing/idle |
| warm competing with the real zap | `cancel()` on every zap; the warm never blocks the player |
| warming a dead/unreachable neighbour | failure is silent, 2 s cap, no retry storm (3 s throttle) |
| DNS cost | one lookup per target, inside the same bounded pass |

**Rollback:** the feature is off by default and self-contained — flip the setting off (or delete the `LaunchedEffect` trigger) and behaviour is exactly the pre-t218 build.

## Preserved

Default buffer **稳/500**, F1 remote habits, F3 auto-update, F4 audio UI, LoadControl tiers, MP2 FFmpeg extension, zero-copy SurfaceView, flat UI, channel keys, digit OSD, logo cache, recent channels, EPG, SR, brand. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **193 tests 0 failures** (`PreconnectPolicyTest` 6, `ZapTraceTest` 21); lint **0 errors**/88 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` / `check_refresh_paths` / `check_settings_scroll` all OK; APK 16,133,469 B ≤ 30 MB.
- CI pair receipt (run 44861163859, source `8fd8d213`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **193 tests 0 failures** (PreconnectPolicyTest 6, ZapTraceTest 21); lint 0 errors / 86 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,076,433| `3999e0daaa0f76dc6d4b04393c423daf5ddb76e62a303d27ecdcea67197835bf` |
| app-debug-androidTest.apk |502,955| `c3e45c6d151c058e00444ab648fd4a1501c2c7da2fd9a3bf0d79107e6ee06b4a` |

- Artifacts: pair `14688954871` (`sha256:091d38c9ea0d17f8059e8992a284bffc15f16ae5c271c0477e63161ccbead81e`, zip 10,349,358 B); reports `14688944950` (`sha256:615942fbdfa1759be9961ee681ecb51e712935718823052bb908ebf69a781189`). Signer `400ceeaeeecf6fb8b82937dd3da99a07e99fbf4b9f430a16af3666aa5f496736`.
- Version: `tv.iqtvlite.app.validation / 10130 / 2.2.0-tivimini-8fd8d213-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,076,433 B ≤ 30 MB.
