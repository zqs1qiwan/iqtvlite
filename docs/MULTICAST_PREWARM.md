# MULTICAST PRE-WARM (t220) — opt-in experiment, **default OFF**

2026-11-21. Task t220 (developer2), on CI10130 source (after t218). Adds a second, transport-level pre-warm arm. **Shipped behaviour is unchanged** (both experiments default off). Device A/B belongs to acceptance2.

## Why this arm (and why not TCP)

| arm | what it warms | device result (t219) |
|---|---|---|
| t218 TCP pre-connect | TCP handshake to the neighbour's host | **no effect** — the source is multicast, no TCP path to warm |
| **t220 multicast pre-warm** | the **actual transport**: IGMP join + route + first TS packets of the neighbour's RTP/UDP group | this task |

The remaining `fmt` cost (~560 ms) is UDP group join + first TS packet + waiting for the first IDR, so the only network work that can help is joining the group early.

## What it does

`MulticastPrewarm` (Android layer, IO coroutine) + `MulticastPrewarmPolicy` (pure, tested):

1. Only for **rtp:// / udp://** URLs whose host is in `224–239.x.x.x` (real multicast); unicast http is rejected by `parse()`.
2. Warms **exactly one** neighbour channel (`MAX_TARGETS = 1`, next first, else previous).
3. Opens its **own** `MulticastSocket`, `joinGroup(...)`, reads packets into a throw-away 48 KB buffer until:
   - `MAX_BYTES = 3 MiB` was read, or
   - `TIMEOUT_MS = 2 500 ms` elapsed, or
   - it is cancelled (real zap / switch off).
4. `leaveGroup(...)` + `close()` in `finally`.
5. Sniffs for an H.264 IDR (NAL type 5) while reading, purely as a diagnostic (`idr=true/false` in the log).
6. **Every failure is silent** — a broken group, an unreachable route or a permission problem can never affect real playback.
7. Runs only while the **channel list is open** or the player is **idle after the first frame**, at most once per 5 s (`MIN_INTERVAL_MS`).
8. `MulticastPrewarm.cancel()` runs on every real zap, so the warm can never compete with the connection the user is waiting for.

### Honest limitation (documented, deliberate)

This build **does not inject** the buffered packets into ExoPlayer: the player still opens its own socket and its own join. So the delivered arm is **"join + first-packet probe"** — it removes the *membership/route setup* cost and proves the group is live, but it cannot remove the "wait for the next IDR" part. Packet injection would need a custom `DataSource` feeding a live TS pipeline, which risks A/V desync and is not something to ship unmeasured on a 512 MB box.

Expected ceiling of this arm: it can only help the join/route part of the ~560 ms `fmt` window. **If the A/B shows no gain, the honest conclusion is that the cost is the IDR wait itself**, and the next arm must be decoder-side (pre-IDR buffering) rather than network-side.

## Setting

**播放与显示 → 组播预热（实验）：关 / 开** (tag `settings_multicast_prewarm`), with the note:

> 仅对 rtp/udp 组播源生效：预加入相邻频道的组播组并读取少量首个数据包（上限 3MB / 2.5 秒），用于提前完成组播加入。默认关闭；如内存/稳定性变差请关回。

Pref: `multicast_prewarm_enabled` (Boolean, **default false**). Turning it off cancels any running warm. It is independent of the TCP experiment (`zap_preconnect_enabled`), so the two can be A/B'd separately.

## Budgets / rollback

| risk | mitigation |
|---|---|
| memory on a 512 MB box | 48 KB receive buffer, ≤3 MiB read, dropped immediately; nothing retained |
| extra socket / IGMP membership | one target, one socket, ≤2.5 s, then leave+close; 5 s throttle |
| warm competing with the real zap | `cancel()` on every zap |
| dead/unreachable group | silent failure, hard timeout |
| PSS growth | **acceptance gate: >10 MB PSS or any crash/rebuffer increase → keep default OFF** |

**Rollback:** off by default; disable the row (or drop the `LaunchedEffect` branch) and behaviour is exactly the CI10130 build.

## A/B protocol (acceptance, not claimed here)

> Same channel, same buffer tier (稳/500), D-pad 10 zaps each, single variable.

| run | 组播预热 | TTFF p50 | TTFF p95 | `fmt` p50 | rebuffer | PSS | CPU | crash |
|---|---|---:|---:|---:|---:|---:|---:|---|
| A | 关（默认） | _pending_ | _pending_ | 560 ms (t219) | _pending_ | baseline | baseline | 0 |
| B | 开 | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ |

Watch in run B: `logcat -s IQTV-PREWARM` (one line per pass: `joined bytes=… elapsed=…ms idr=…`), `dumpsys meminfo` PSS, `top` CPU.

**Target:** TTFF p50 ≤ 700 ms with no PSS/crash/rebuffer regression. If not reached, report the data and **keep the default off**.

## Preserved

Default buffer **稳/500**, F1 remote habits, F3 auto-update, F4 audio UI, LoadControl tiers, MP2 FFmpeg extension, zero-copy SurfaceView, flat UI, channel keys, digit OSD, logo cache, recent channels, EPG, SR, brand. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **198 tests 0 failures** (`MulticastPrewarmPolicyTest` 5); lint **0 errors**/89 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` / `check_refresh_paths` / `check_settings_scroll` all OK; APK 16,083,919 B ≤ 30 MB.
- CI pair receipt: _(filled after run completes)_
