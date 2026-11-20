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
- CI pair receipt: _(filled after run completes)_

## Device check (acceptance, not claimed here)

Press digits on the remote → the panel must be clearly readable (ink digits on dark), auto-clear after ~3 s, Back cancels, valid number switches channel, invalid shows the toast.
