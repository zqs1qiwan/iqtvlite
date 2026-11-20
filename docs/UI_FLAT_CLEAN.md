# UI FLAT CLEAN — 去圆角化干净扁平 UI (t103)

2026-11-20. Task t103 (developer2), on top of the CI10090 revert baseline (original tivimini architecture). **Only corner flattening + cleanup** — structure, logos, channel names, EPG presentation, 633dp two-column layout, font sizes/spacing/colors, row heights and the info-banner structure are untouched.

## Flattened corners (all → 0dp)

| Area | Before | After |
|---|---|---|
| PlayerDrawer row backgrounds (channel/program/group/date) | 12dp | **0dp** |
| PlayerDrawer drawer container | 18dp | **0dp** |
| PlayerDrawer program progress track/fill | 999dp capsule | **0dp square** |
| Info banner container | 18dp | **0dp** |
| Info banner progress track/fill | 999dp capsule | **0dp square** |
| Catchup progress row fills | 2dp | **0dp square** |
| SettingsDrawer container | 18dp | **0dp** |
| Settings row | 12dp | **0dp** |
| QualitySelectorDialog container | 18dp | **0dp** |
| Quality selector row | 12dp | **0dp** |
| MainActivity panel | 12dp | **0dp** |
| ChannelSwitchLoading panel | 12dp | **0dp** |
| LogoLoader logo tile | 10dp | **0dp** |
| PlayerActivity info-banner panel / progress | 18dp / 2dp | **0dp / 0dp** |

No hairline was added (the task allows an optional faint divider; omitted to keep the surface clean). **No border is used for focus** — the original no-blue-box focus stays (original light `surfaceVariant` fill), no new hard frame.

## Preserved

- PlayerDrawer structure: 633dp two-column, original row heights/alignment, `basicMarquee`, logos, channel names, EPG columns, channel/program/date/group interactions, number jump, channel keys 166/167.
- Catchup owner B, zero-copy SurfaceView base + grey-screen fix, LoadControl `1000/6000/300/1000`, SR-01–03, EPG, audio diagnostics, stable keys, IQTV Lite brand resources/app name. Size ≤30MB.
- No v2/v3 "minimal" content removal/structure/font-size changes were reintroduced; the 999dp capsule and 12dp values from t100 were **not** restored (t101 cancelled).

## Also included (low-risk cleanup)

- `LogoLoader` memory LRU is 128 entries (REVERT-03, PSS allows).
- Two dead Activity fields (`setDrawerOpen`, `focusToDrawerChannels`) and their unused controller params/registrations were removed (REVERT-05); `drawerActiveColumn` is still used by `requestedActiveColumn`.

## Performance expectation

Jank baseline remains the **original Compose baseline**; corner flattening does not add work per frame (static shapes only), so no expected regression.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **37 tests 0 failures**; lint **0 errors**/66 warnings; pair gate missing 0; keep 210 roots identical; dex contract 7 OK.
- CI receipt (run91, 44710264438): source commit [ad2c6d57e7a227b5d5b73cac2a9ddc64568527a2](https://github.com/zqs1qiwan/iqtvlite/commit/ad2c6d57e7a227b5d5b73cac2a9ddc64568527a2), [run 44710264438](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44710264438) **successful** (NDK r27, dual ABI/API24, pair gate passed). Cloud JVM 37 tests 0 failures; lint 0 errors / 64 warnings / 5 hints.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |10,404,864| `06da0b504a6c3ba6b5af3739583bb480a38cdb8f42d274cb71d209bc2b872da9` |
| app-debug-androidTest.apk |502,955| `63939d39eae472bc6358c214b0356d38d5c865c36774533d274a6b5e6888c2af` |

- Artifacts: pair `14616478851` (`sha256:66f83f600131bcc0693d58c6007725ca90f3f32e329353a62d172d4351fe0aeb`, 8,896,659B); reports `14617731068` (`sha256:02f669f48e2deaf9c7c7dbf7648788f16d214627ff4a93231b56d806df9210a1`). Both APKs signer `7ce572927571b05d91ea3b21dd0d1b856785c8f13baa98d571c9458eed513cc8` (fresh debug cert).
- Version: `tv.iqtvlite.app.validation / 10091 / 2.2.0-tivimini-ad2c6d57-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 roots identical.
- Size 10,404,864B ≤ 30MB. Device verification is acceptance2's (t106) on this exact pair: flat square UI with intact structure/logos/EPG; only corners changed; no blue focus box; jank is the original Compose baseline (no claim of improvement).
