# DIGIT CHANNEL-SWITCH OSD — visibility / contrast fix (t124)

2026-11-21. Task t124 (developer2), on CI10096. User feedback: the digit channel-number entry was **invisible / black on dark**. Minimal fix to the existing OSD; no other visual/interaction change.

## Root cause

`PlayerActivity`'s Compose overlay rendered the raw digits as a single `Text(digits)` at `TopEnd` with `MaterialTheme.typography.headlineLarge` and no explicit text colour. On the dark surface it was low-contrast/black and easy to miss; it also lacked a label and timed out after only 1.2 s.

## Fix (minimal, same overlay)

Replaced the single `Text` with a small **channel-number OSD** at `TopCenter` (safe 36dp top margin):

- Dark panel `#E610141A` (≈88% opaque) with 28dp horizontal / 14dp vertical padding — flat (no new corners/shadow, matches the flat-UI direction).
- Two lines:
  - label `请输入频道号` in `#A9B4C7` (`titleMedium`),
  - the entered number + caret `12_` in brand **ink `#F2F5FA`** (`headlineLarge`).
- Explicit colours passed to `Text` (no longer inherits a dark/ambiguous content colour) → high contrast on any frame.
- **Timeout raised to 3 s** before auto-submit/clear (`delay(3000)`).

## Behaviour retained / verified by code

- Digit keys `0..9` append (max 6 digits) only when no drawer/settings/quality dialog is open; `ACTION_UP` of digits is swallowed so nothing else reacts.
- With digits pending: **BACK** cancels and clears; **DPAD_CENTER / ENTER / NUMPAD_ENTER** submits immediately; any other key clears the buffer.
- Invalid number → existing `Toast("没有频道 $digits")`; valid → `onNumberJump(number)` → `playGroupAutoState.value(number-1)` (channel-number jump, unchanged).
- Nothing else changed: zero-copy SurfaceView base, channel keys 166/167, flat UI, MP2 audio extension all untouched.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **37 tests 0 failures**; lint **0 errors**/66 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; APK 15,997,133 B (with MP2 AAR) ≤ 30 MB.
- CI pair receipt (run 44787285293, source `8cfc03d0`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM 37 tests 0 failures; lint 0 errors / 64 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |12,928,911| `f267cc7e28aadd76f300df84292eb86093007a11db08984d42080f90bdd2b900` |
| app-debug-androidTest.apk |502,955| `9116dd08cdd6b9e6be7ce6fe9c9b885abd8c0399419a5eebd4facefdbd89e832` |

- Artifacts: pair `14654039491` (`sha256:32a196066347dd72f76ff0569c5e718e6497b975f5c577cc12004944d772413a`, zip 10,204,737 B); reports `14655461895` (`sha256:48865f85adb926857025a68d640f557afe134e3ce4b019f74f43b0cc68a59bc8`). Signer `196a0e5920dcad2270d30cd7f72d142d2519da1d21db64906411a284f3b0cc4d`.
- Version: `tv.iqtvlite.app.validation / 10097 / 2.2.0-tivimini-8cfc03d0-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 12,928,911 B ≤ 30 MB.

## Device check (acceptance, not claimed here)

Press digits on the remote → the panel must be clearly readable (ink digits on dark), auto-clear after ~3 s, Back cancels, valid number switches channel, invalid shows the toast.
