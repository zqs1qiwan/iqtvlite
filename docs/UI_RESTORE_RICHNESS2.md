# UI RESTORE RICHNESS 2 — LOGO fixes (t84)

2026-11-20. Task t84 (developer2), fixes the t82-review LOGO defects in the native sidebar. No architecture change.

## LOGO-01 (blocker) — logos actually load from the channel logo URL

- `SidebarRow` gained `logoUrl`; `buildChannelRows` sets `logoUrl = channel.logoUrl`; `programsFor` sets `logoUrl = channel.logoUrl` for the focused channel's programmes.
- `bindLogo` now uses **`row.logoUrl`** (previously it used `row.url`, the live stream URL — so every channel requested/decoded the stream address and failed, and programme rows always showed the placeholder). `row.url` remains identity/catchup only.

## LOGO-02 — square decode + no cropping

- Decode targets are now **square 80×80 (channel) / 40×40 (program)** and the ImageView uses **`CENTER_INSIDE`** (was `CENTER_CROP`), so wide logos are not cropped left/right.

## LOGO-03 — real panel radius

- The 633dp base and the groups/date overlays now use a **`GradientDrawable` with 4dp corner radius** (`#E6161B22`) instead of a flat colour — matches the docs claim of 2–4dp panel rounding.

## LOGO-04 — programme time column

- Programme time column is **42dp** and the title starts at **86dp** (v3 values; was 78/118, which squeezed the title).

## LOGO-09 — logo concurrency bound

- Added a **`Semaphore(3)`** around `LogoLoader.load`; opening the drawer can no longer burst 20–30 concurrent network/decode loads. Cached hits bypass the gate (instant).

## Low-risk

- Group rows **48dp** (was 40).
- Placeholder is `#1E242E` (dark surface tile, 2dp) — the first-letter overlay is not drawn (documented); the tile keeps row geometry stable.
- Playing marker: `▶` prefixed in the channel row's primary text (preformatted).
- Adapter coroutine scope is **cancelled on detach** (`dispose()` called from `NativeSidebarView.onDetachedFromWindow`), so no leaked logo jobs.

## Preserved

No-blue-border focus (`#14FFFFFF` + INK/weight selector, no rebind), row 0dp / logo tile 2dp, 633dp base + overlay columns, DiffUtil, EPG out of `SidebarState` equality, transparent root, catchup owner B, layered dates, zero-copy SurfaceView base / grey-screen fix / channel keys / LoadControl / stable keys / SR / TIV / JNI / R8 / UA. Size ≤30MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **37 tests 0 failures**; lint **0 errors**/72 warnings; pair gate missing 0; keep 210 roots identical; dex contract 7 OK.
- CI receipt (run88, 44686317357): source commit [c02c8fb11682c0f4ef13138ceee85af87f4a4db5](https://github.com/zqs1qiwan/iqtvlite/commit/c02c8fb11682c0f4ef13138ceee85af87f4a4db5), [run 44686317357](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44686317357) **successful** (NDK r27, dual ABI/API24, pair gate passed). Cloud JVM 37 tests 0 failures; lint 0 errors / 70 warnings / 5 hints.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |10,208,256| `f13408a1b8d9e4c734b47d24bd66576496cdd7031bae30c546fca6c17162ac97` |
| app-debug-androidTest.apk |502,955| `88660a6084cebe220ecc9473cb16d2e90b5e3651994aa5c9f3057f2162bdcd30` |

- Artifacts: pair `14606548510` (`sha256:4c35b992a8df070d003c0be8eb83c8df473ab7ee6f9d571a6d03fcb1e8071e74`, 8,700,005B); reports `14607290799` (`sha256:25a5f6eef215cc68a21bc80519a0632ff0730c2b98446e213b8e6c4f43d0aa86`). Both APKs signer `0975cc73693271f8e6588cc29381b7435c72c7ed3d19e493ecc408af779b67f6` (fresh debug cert).
- Version: `tv.iqtvlite.app.validation / 10088 / 2.2.0-tivimini-c02c8fb1-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 roots identical.
- Size 10,208,256B ≤ 30MB. Device verification is acceptance2's (t86) on this exact pair; this does not claim the jank target is met.
