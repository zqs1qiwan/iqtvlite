# UI RESTORE RICHNESS 3 — LOGO-04/11 + low-risk (t88)

2026-11-20. Task t88 (developer2), fixes t85-review LOGO-04 / LOGO-11 and the low-risk visual items. No architecture change.

## LOGO-04 (medium) — two-line programme time column

- Programme time column (42dp) is now **two lines**: start time **12sp `#C7D0DD`** on top, end time **11sp `#A9B4C7`** below, each `maxLines=1` + `ellipsize=END`. No more clipped `HH:mm-HH:mm` single line.
- Title starts at **86dp** (logo x12 + 20 + 12 = 44, + 42 time column = 86), per v3 §4.2.

## LOGO-11 (medium) — decode at view pixel size

- Channel logos decode at **80×80 px**, programme logos at **40×40 px** — **not** `dp(80)×density` (which was 160×160 px on this density, a 2× oversample). This reduces the LRU-96 bitmap cache footprint (~12MB → ~3MB) and GC/jank pressure on API28 v7a. ImageView stays `CENTER_INSIDE` (square, no crop).

## Low-risk

- **LOGO-12**: channel logo x = **12dp**, channel text starts at **64dp** (v3 values).
- **LOGO-13**: base panel and the groups/date overlays use `clipToOutline = true` with the 4dp rounded background, so the 2dp accent bar cannot poke out of the rounded corner.
- **LOGO-14**: time start/end TextViews both `ellipsize = END`.
- **LOGO-06**: the logo tile shows the **first letter** of the channel/program title (from `#1E242E` placeholder) until the bitmap arrives, then the letter is hidden.
- **LOGO-07**: playing marker is now explicit — `▶` prefix on the channel name **plus** "· 播放中" appended to the current-program subtitle for the playing row.

## Preserved

Real `logoUrl` loading (LOGO-01), square `CENTER_INSIDE` (LOGO-02), 4dp panel radius (LOGO-03), 3-way logo concurrency gate (LOGO-09), no-blue-border focus (`#14FFFFFF` + INK/weight selector, no rebind), row 0dp / logo tile 2dp, 633dp base + overlay columns, DiffUtil, EPG out of `SidebarState` equality, transparent root, catchup owner B, layered dates, zero-copy SurfaceView base / grey-screen fix / channel keys / LoadControl / stable keys / SR / TIV / JNI / R8 / UA. Size ≤30MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **37 tests 0 failures**; lint **0 errors**/72 warnings; pair gate missing 0; keep 210 roots identical; dex contract 7 OK.
- CI receipt (run89, 44693489041): source commit [87023bb79ea29ec3b6ea2e5c4f23241474634b34](https://github.com/zqs1qiwan/iqtvlite/commit/87023bb79ea29ec3b6ea2e5c4f23241474634b34), [run 44693489041](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44693489041) **successful** (NDK r27, dual ABI/API24, pair gate passed). Cloud JVM 37 tests 0 failures; lint 0 errors / 70 warnings / 5 hints.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |10,208,256| `6e457b6f53aa387960a6c27efcf7d59e5bd686a566c70dfdb4dcd4ee837684eb` |
| app-debug-androidTest.apk |502,955| `3b38bb3a4e59f969bb56531d62abc1bc0460fd119207b69cbef644ac6f444a5a` |

- Artifacts: pair `14609812206` (`sha256:ffd8515fa98184cf4ea77cc9aabb129380463ef10406b8fa4c63fac96e952b04`, 8,700,319B); reports `14609726989` (`sha256:f763148bffed2e59eab3b1d2940f183d016786348cc66ecf5dedcc9597979ca7`). Both APKs signer `445d918fd1e56c020156cd79ef2e9456f56d80a7c494610ee58e91311f9a812c` (fresh debug cert).
- Version: `tv.iqtvlite.app.validation / 10089 / 2.2.0-tivimini-87023bb7-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 roots identical.
- Size 10,208,256B ≤ 30MB. Device verification is acceptance2's (t90) on this exact pair; this does not claim the jank target is met.
