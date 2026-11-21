# ZAP STAGE PRECISION — ZAP_PRECHECK (t218 A)

2026-11-21. Task t218 part A (developer2), on CI10129 source (after t216). One-line-class fix to the first-frame stage semantics; **no behaviour or performance change**.

## Symptom

The device trace showed:

```
onVideoDecoderInitialized   47.864 s
onInputVideoFormatChanged   47.869 s     ← 5 ms LATER
```

The decoder mark therefore came **before** the format mark, so `decoderDelayMs()` (format → decoder) was **negative**. The old `summaryLine()` required `fmt >= 0 && dec >= 0 && render >= 0`, so a single out-of-order event dropped **all three** stages and only the base line was printed.

## Fix (definition of each stage)

| stage | definition now | when the mark is missing / out of order |
|---|---|---|
| `fmt` | `prepare → first format` (network first packet + demux) — **the dominant stage: ~94 % of TTFF, p50 1134 ms** | no format event → `-1` → suffix omitted (nothing measurable) |
| `dec` | `format → decoder initialised`, **clamped to 0** | missing or negative → **0** (reused codec, or the event arrived first) |
| `render` | `first frame − latest mark` (`max(format, decoder, prepare)`) | negative → **0**; no first frame → `-1` |

`summaryLine()` now appends the suffix **as soon as `fmt` is known**, and `dec`/`render` can never poison the line with a negative number:

```kotlin
val fmt = formatDelayMs()
if (fmt < 0L) return base                       // no format → compact line
return "$base fmt=${fmt}ms dec=${decoderDelayMs()}ms render=${renderDelayMs()}ms"
```

## Tests — `ZapTraceTest` (18 → 21)

- **`decoderBeforeFormatDoesNotDropTheStages`** — decoder mark first, then format: `fmt=200ms dec=0ms render=700ms` are all printed.
- **`aMissingDecoderEventStillReportsFormatAndRender`** — a reused codec that never emits `onVideoDecoderInitialized`: `fmt=800ms dec=0ms render=…`.
- **`aNegativeWindowIsClampedToZero`** — first frame before the last mark → `render=0`, never `render=-`.
- the "no format event → compact line" test was aligned with the new (clamped) helpers.

JVM total **184 → 193** together with part B (CI run 44861163859 / 10130, cloud JVM 193 0 失败).

## Device protocol (acceptance)

1. `setprop log.tag.IQTV-ZAP DEBUG`, D-pad zap 10× → every line must now carry `fmt=… dec=… render=…` (previously only the first line had them).
2. `fmt` is expected to dominate (~1.1 s); `dec` ≈ 0–7 ms, `render` ≈ 75 ms. That confirms arm B (network) as the only lever with headroom.
