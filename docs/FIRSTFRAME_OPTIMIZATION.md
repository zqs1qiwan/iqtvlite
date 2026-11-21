# FIRST FRAME OPTIMIZATION (t213)

2026-11-21. Task t213 (developer2), on CI10127 source (after t211). **Measurement first.** This delivery ships the fine-grained instrumentation and the risk analysis; the candidate optimizations are documented with an explicit A/B plan and rollback. No performance claim is made here — the authoritative A/B runs on the device (acceptance).

## Context (authoritative numbers from the captain)

| segment | value |
|---|---|
| zap → `prepare()` | 253–373 ms |
| `prepare()` → first frame | **836–1021 ms** |
| squeezing the start buffer | no effect on first frame |

So the cost is inside `prepare → first frame`, which this task now splits into its sub-stages.

## 1. Measurement — first-frame breakdown finished

`IQTV-ZAP` line now carries three extra stages when the decoder events are observed:

```
zap=1300ms prepare=1000ms firstFrame=1300ms fmt=120ms dec=520ms render=660ms
```

| stage | source | meaning |
|---|---|---|
| `fmt` | `onVideoInputFormatChanged` | prepare → first format out of the demuxer (network/RTP join + demux) |
| `dec` | `onVideoDecoderInitialized` | format → MediaCodec **create + configure** |
| `render` | `onRenderedFirstFrame` | first decode + first Surface submit |

All marks are inside `Log.isLoggable("IQTV-ZAP", DEBUG)`; untouched fields when the tag is off (zero cost, no clock reads on the callback path). `ZapTraceTest` (14 → 16) asserts the split and that the line stays compact when the decoder events are absent.

How to read it on device:

```
adb shell setprop log.tag.IQTV-ZAP DEBUG
adb logcat -s IQTV-ZAP
```

- big `dec` → MediaCodec create/configure dominates → **arm A (decoder reuse/pre-warm)**
- big `fmt` → network/RTP join dominates → **arm B (neighbour pre-connect)**
- big `render` → decode+submit dominates → **arm C (earlier first frame / renderer ready)**

## 2. Candidate optimizations — one at a time

### A) Decoder pre-warm / reuse — **not shipped in this task**

`MediaCodec` reuse across zaps only pays when the codec/resolution/profile is unchanged, and a wrong reuse either fails (`IllegalStateException` on reconfigure) or corrupts output. It also needs a per-renderer cache keyed by `(mime, width, height, profile)` with a hard fallback to `create`, plus teardown on surface loss — a multi-file change that cannot be validated without the device A/B. **Rollback:** would be a flag; nothing is in the build today.
Next step (proposed): `DefaultRenderersFactory.setEnableDecoderFallback(true)` is already on; add a `DecoderWarmPool` behind `Prefs.decoderReuseEnabled` (default **off**), keyed as above, and A/B `dec` only.

### B) Neighbour pre-connect — **not shipped in this task** (same rationale as t197 A3)

Needs an injectable HTTP/socket seam (`StreamPlayback` builds `MediaSource`s directly today), a 2 s cap, cancel-on-zap, and a strict "one extra socket at most" budget for the 512 MB box. **Rollback:** flag default off.
Next step: `ZapPreconnect` with `Prefs.zapPreconnectEnabled` (default **off**), open the next channel's host right after the first frame, close on any zap/unbind; A/B `fmt` only.

### C) Earlier first frame — **reviewed, no safe change found**

The renderer path is already minimal: zero-copy `SurfaceView` (`t38`), `setKeepContentOnPlayerReset(true)`, no `TextureView`, no per-frame copy, and the t197 note deliberately **removed** decoder pre-warming at startup because it cost seconds of cold start on this box. Possible micro-wins (e.g. avoiding an initial seek for live) were checked: live `MediaItem`s are prepared at the default position, so there is no redundant seek to remove; forcing `renderer ready` earlier would require touching `MediaCodecVideoRenderer` internals (not `@UnstableApi`-safe).

## 3. Target and decision rule

Target: `firstFrame` p50 ≤ 600 ms (from ~850 ms) **without** more rebuffers or CPU. If an arm does not reach it, report the numbers and stop — do not force a change (per the task).

## Preserved

Default buffer **稳/500** (t205 tiers unchanged), F1 remote habits, F3 auto-update, F4 audio UI, MP2 FFmpeg extension, zero-copy SurfaceView, flat UI, channel keys, digit OSD, logo cache, recent channels, EPG, SR, brand. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **182 tests 0 failures**; lint **0 errors**/87 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` / `check_refresh_paths` / `check_settings_scroll` all OK; APK 16,131,511 B ≤ 30 MB.
- CI pair receipt (run 44856764556, source `bd60d593`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **182 tests 0 failures** (ZapTraceTest 16); lint 0 errors / 85 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,059,991| `f93048b916ffc4791b2142a5d30477b138e5e6455e03d4abdc35a267274d3c69` |
| app-debug-androidTest.apk |502,955| `e59cf4255217044fb0b13a082256f47b43b82e353d71502f80cb3e5bb8a34265` |

- Artifacts: pair `14687418881` (`sha256:95d5d57f333555644b38996506e1682cb028a4e41c783afad4f856fd0046860b`, zip 10,342,491 B); reports `14687687720` (`sha256:b5a82891781a8656a57354c62607278fb323813bc06b2ba3e81d555490efbaf3`). Signer `4505da41596f70d355f2d71dbd10ee1c40b01bc0aea49bf538c6c3b914a1f5cc`.
- Version: `tv.iqtvlite.app.validation / 10128 / 2.2.0-tivimini-bd60d593-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,059,991 B ≤ 30 MB.

## Device protocol (acceptance, not claimed here)

1. `setprop log.tag.IQTV-ZAP DEBUG`; D-pad zap 10× on one channel → 10 lines with `fmt/dec/render`.
2. Report p50/p95 of each stage. Whichever stage dominates selects the arm to A/B next (A: dec, B: fmt, C: render).
3. Confirm rebuffer count and CPU are unchanged by this instrumentation build (it should be: debug off = zero cost).
