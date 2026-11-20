# UI FLAT CLEAN 2 — Material3 defaults flattened (t108)

2026-11-20. Task t108 (developer2). Minimal follow-up to t103: t105 review found the **Material3 default shapes** were still rounded on the config page / update dialog (everything with an explicit shape was already 0dp).

## FLAT-01 — config page TextFields

- Both `TextField`s (live source / EPG address) now set `shape = RoundedCornerShape(2.dp)` (was the Material3 default rounded field).

## FLAT-02 — “保存并播放” button

- The button sets a flat 2dp shape. This is the **androidx.tv.material3.Button**, whose `shape` parameter is a `ButtonShape`, so it is built with `ButtonDefaults.shape(shape=2.dp, focusedShape=2.dp, pressedShape=2.dp, disabledShape=2.dp, focusedDisabledShape=2.dp)` — visually 2dp in every state, no capsule.

## FLAT-03 — UpdateDialog

- `AlertDialog` `shape = RoundedCornerShape(2.dp)` (was the Material3 28dp default).
- Confirm (“更新”) and dismiss (“取消”) TV buttons → flat 2dp `ButtonShape`.
- `LinearProgressIndicator` → `strokeCap = StrokeCap.Butt` (square ends; no capsule), trackColor unchanged.

## Low-risk

- **FLAT-04**: removed the now-unused `SidebarController.setDrawerOpen` (no callers/tests); `focusToChannels` was already gone.
- **FLAT-05**: `LogoLoader` LRU stays **128** (PSS allows).
- **FLAT-06**: Web config HTML shadow untouched (not a TV surface).
- **FLAT-07**: `CircularProgressIndicator` left as-is (round by design).

## Preserved

Everything else stays flat 0dp / square: PlayerDrawer rows/container/progress, info banner, settings rows, quality dialog, channel-switch loading, logo tiles. Original architecture intact (logo 64×40, channel names, EPG 96dp, 633dp two columns, row heights/fonts/spacing/colors, `basicMarquee`, interactions), no blue focus box, zero-copy base/grey-screen/channel keys/LoadControl/SR/catchup owner B/EPG/audio diagnostics/stable keys/brand & app name. Size ≤30MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **37 tests 0 failures**; lint **0 errors**/66 warnings; pair gate missing 0; keep 210 roots identical; dex contract 7 OK.
- CI receipt (run92, 44713464928): source commit [4d1b6d45f7927054c84a7869b94bc2968b695aec](https://github.com/zqs1qiwan/iqtvlite/commit/4d1b6d45f7927054c84a7869b94bc2968b695aec), [run 44713464928](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44713464928) **successful** (NDK r27, dual ABI/API24, pair gate passed). Cloud JVM 37 tests 0 failures; lint 0 errors / 64 warnings / 5 hints.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |10,404,864| `264c594a868ff4579dab59ea4013b86d4bda2a2ee3567d76238db6e665b2eb9f` |
| app-debug-androidTest.apk |502,955| `21024bb85d83a0bceadc8f0dd14ebe06a5f68133480ecbe99a297c41a785e297` |

- Artifacts: pair `14618446728` (`sha256:9b2e7c5deead7b5b669196e775ad1bae567fa15137464c8cbee997fb7d9212e6`, 8,896,918B); reports `14618706530` (`sha256:d021ceca1c5795255ad879b4f5e072f7476ba211beebf0945f49e493157685ac`). Both APKs signer `4af55fef3a3c8e597fc6a34aea12c8357b8b63ba2b92e39f77cd8bd09a3ec26b` (fresh debug cert).
- Version: `tv.iqtvlite.app.validation / 10092 / 2.2.0-tivimini-4d1b6d45-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 roots identical.
- Size 10,404,864B ≤ 30MB. Device verification is acceptance2's (t110) on this exact pair. Geometry/jank follow-up is tracked in t111 (next), not claimed here.
