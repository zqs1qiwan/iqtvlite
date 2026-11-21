# DIGIT OSD TIMEOUT FIX (t223)

2026-11-21. Task t223 (developer2), on CI10131 source. Fixes a device-found defect (CI10130) in the 「数字键 = 先显示输入条需OK」 habit.

## Symptom

With 数字键 set to **先显示输入条需OK**, typing `1` showed the OSD `请输入频道号 1_` and it **stayed on screen indefinitely** (still visible after 7 s). The interaction matrix requires the OSD to disappear after **3 s**; BACK cancel worked.

## Root cause

Only the DIRECT branch armed the timer:

```kotlin
if (RemoteHabits.onDigit(remote) == DigitAction.DIRECT) {
    numberJob = launch { delay(3000); submitNumber() }
}
// NEEDS_CONFIRM: numberJob was cancelled just above and never re-armed → OSD stayed forever
```

So in NEEDS_CONFIRM the job was cancelled on every key press and nothing restarted it.

## Fix

One timer for **both** modes; only the action taken on expiry differs (pure policy in **`DigitOsdPolicy`**):

| event | DIRECT | NEEDS_CONFIRM |
|---|---|---|
| digit typed (incl. append) | restart 3 s timer | **restart 3 s timer** (new) |
| timer fires | submit the number (jump) | **clear the OSD, no jump** (new) |
| OK | submit + clear | submit + clear |
| BACK | cancel + clear | cancel + clear |
| overlay opens (drawer/settings/quality/recent/audio) | hide + clear | hide + clear (new central guard) |

```kotlin
numberJob?.cancel()                       // every input restarts the shared timer
val digitMode = remote.digits
numberJob = lifecycleScope.launch {
    delay(DigitOsdPolicy.TIMEOUT_MS)       // 3 000 ms, same constant as before
    when (DigitOsdPolicy.timeoutAction(digitMode)) {
        SUBMIT -> submitNumber()
        CLEAR  -> digits = ""              // needs-OK: hide only, the user committed nothing
    }
}
```

The timeout value is unchanged (**3 000 ms**) and now lives in `DigitOsdPolicy.TIMEOUT_MS`; the input cap (6 digits) moved to `DigitOsdPolicy.append(...)` so it is testable. Behaviour in DIRECT mode is byte-for-byte the same as before.

An open overlay also clears the OSD centrally at the top of `dispatchKeyEvent` (non-digit key while an overlay is open → cancel timer + clear digits), so the OSD can never sit on top of the drawer/settings/dialogs.

## Tests — `DigitOsdPolicyTest` (5)

- both modes share the **3 000 ms** timeout and every input restarts it;
- the timeout **only jumps in DIRECT** — NEEDS_CONFIRM only clears;
- OK → SUBMIT, BACK → CLEAR (both modes);
- appending stops at the 6-digit cap;
- an open overlay hides the OSD.

JVM total **198 → 203**.

## Preserved

F1 remote habits (defaults unchanged), F3 auto-update, F4 audio UI, default buffer **稳/500**, LoadControl tiers, MP2 FFmpeg extension, zero-copy SurfaceView, flat UI, channel keys, logo cache, recent channels, EPG, SR, brand. Size ≤30 MB.

## Verification

- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **203 tests 0 failures** (`DigitOsdPolicyTest` 5); lint **0 errors**/89 warnings; pair gate missing 0; keep 210 identical; dex contract 7 OK; `check_player_threading` / `check_refresh_paths` / `check_settings_scroll` all OK; APK 16,084,713 B ≤ 30 MB.
- CI pair receipt (run 44868605399, source `ef3786ae`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **203 tests 0 failures** (`DigitOsdPolicyTest` 5); lint 0 errors / 87 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,092,817| `bffa1bb0f266cb1f7e786dd04c48a40ee0b48fc38b9c5e8835b0aab5981e5015` |
| app-debug-androidTest.apk |502,955| `bb4d71e2d7a735a72cd55da5cf60809448a985967f1339407d61264f1f9d57be` |

- Artifacts: pair `14692804122` (`sha256:a5f53b9b52f991131ac9b0e921b1c65b3282104f3deca7f2e5a286697484f74a`, zip 10,360,965 B); reports `14692854081` (`sha256:603a9d5d9e57e28d3eb8916955c6b79d65434eee019760b404402aed20fca5c1`). Signer `ce81713f0947664928b483f6efe326f4d5b7061405d75d9aae5bdbcebea78705`.
- Version: `tv.iqtvlite.app.validation / 10132 / 2.2.0-tivimini-ef3786ae-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,092,817 B ≤ 30 MB.

## Device protocol (acceptance, not claimed here)

1. 遥控器与操作 → 数字键 = 先显示输入条需OK.
2. Type `1` → OSD `请输入频道号 1_` appears; **within ~3 s it disappears and no channel change happens** (previously it stayed forever).
3. Type `1`, wait 2 s, type `2` → the OSD shows `12_` and the timer restarts (still ~3 s from the *last* key).
4. Type `1` then press **OK** → jumps immediately and clears.
5. Type `1` then press **BACK** → clears immediately, no jump.
6. Type `1` then open the drawer / settings → OSD is gone, never left on top.
7. DIRECT mode unchanged: type `1`, wait 3 s → jumps.
