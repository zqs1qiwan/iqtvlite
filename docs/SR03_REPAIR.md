# SR-03: retain the actual live owner outside the refreshed list

Task t10,2026-11-19 (Asia/Hong_Kong). Implementation response to [t9 SR-03](../../iqtv/docs/TIVIMINI_SOURCE_REREVIEW.md), awaiting independent re-verification. The accepted SR-01/SR-02 main paths and original TIV navigation are not reopened. The t9 needs_revision verdict is not changed by this implementation report.

## Production change

[PlaybackIntent](../android/app/src/tivimini/java/com/cjlhll/iptv/PlaybackIntent.kt) now represents the missing-row return explicitly with a retained immutable B-live request. The transition is made by returnToLive **on Back from history**, not when entering B history. It still leaves the original browse/live indexes separate while history is playing.

[PlayerActivity](../android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt) uses the same production functions as the regression fixture:

- returnToLive resolves the B owner/stable ID and returns the live request plus an optional listed group. A listed B follows the original source-order probing route. An absent B sets retained ownership, cancels the old source probe, clears history, and prepares the B-live request without forcing an A/B browse index.
- liveOwner is the shared resolution for selectedLiveNow, initialization/Home, background selection and live metadata. A retained B takes precedence over leftover A indexes. Matching uses B identity rather than a different known owner reusing B's URL.
- updatePlaylist does not run title/index fallback when this retained B is absent. It can accept new A/C rows while explicitly keeping the B owner. commitPlaylist is the common production commit policy: null indexes preserve browsing context, no A request is prepared, and B continues.
- When the same stable B returns, the commit policy clears retained ownership, relocates the actual B index and refreshes B's prepared request (including changed B URL). That new session invalidates still-pending old missing-row updates. Rejoin while stopped updates indexes and invalidates obsolete work without preparing a released player; the next initialization resolves B.
- A user-initiated channel/line selection begins a new intent and clears retention. Explicit C therefore returns to normal selection semantics. Home alone does not clear retention.
- If urgent recovery cannot obtain a usable list while B is retained, the legacy listed-alternate fallback is not allowed to impersonate B using leftover A state.

Metadata follows the shared LiveOwnerView. Missing B has groupIndex=-1 and the already-supported unknown number0 rather than A's number. The banner uses B's title/logo/identity; it does not take a programme/progress entry from an indexed A row or from a reused URL. No A row is falsely marked as playing. The drawer layout, focus routing, date/group controls, Back priority and double-Back transition remain unchanged. No new navigation layer, error menu or programme feature is added.

Only the two production files above change in t10. The existing immutable Media3 header/source factory and Analytics error binding are unchanged. Rust sources and native libraries are unchanged.

## Regression boundary

[UnlistedLiveOwnerTest](../android/app/src/test/java/com/cjlhll/iptv/UnlistedLiveOwnerTest.kt) drives the actual returnToLive/liveOwner/updatePlaylist/commitPlaylist production functions, with the Activity's state-application wiring and an injected prepare sink. It does not manually invent a B request as a substitute for Back, and it deliberately leaves the browse index at A for the failing branch.

Four added regressions cover:

1. A-live→B-history→new table A/C→Back→Home→another missing-B refresh→Home: B identity, live address, UA/Referer, prepared-media owner and metadata stay B; unknown number is0, and a missing-row update does not prepare A.
2. B returns at a different index with the same stable ID and a changed live URL: the real B row is selected and prepared; explicit C after a separate missing-B transition ends retention and stays C after refresh/Home.
3. Updates while the player is stopped: missing B stays retained; B can rejoin without preparing the released player, and resume prepares B.
4. A known A reuses B's URL: A does not end B retention or become B's metadata.

The existing Media3 catch-up fixture in [NativeBoundaryTest](../android/app/src/androidTest/java/com/cjlhll/iptv/NativeBoundaryTest.kt) now calls the production missing-B Back transition, live resolution and refresh commit policy. It then checks B-live HTTP requests after Home, B mediaId, and persisted B UA/Referer. The Android suite remains7 methods and is compiled only; no device execution occurs in this task. JVM injection proves the shared policy/state application, not Android Activity focus, decoded frames or Media3 runtime success.

## Final local verification and candidate

Final frozen clean verification passed:

```sh
android/gradlew.bat -p android :app:clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest -PISOLATED_TEST_PACKAGE=true --no-daemon
python scripts/verify_apk.py android/app/build/outputs/apk/debug/app-debug.apk
```

- JVM:19 tests passed (4 new SR-03 +10 SR-01/02 +5 source-integrity),0 failures/errors. The new4-method XML was checked directly. No already-accepted regression was removed.
- Lint:0 errors,49 warnings,5 hints. Clean application APK and matching Android test APK built. All7 instrumentation methods remain compiled but unexecuted.
- [New SR-03 local APK](../local/candidates/iqtvlite-sr03-validation.apk):14,123,927B, SHA256 `e6f8b67d1cdeda464a9e9c44c145818f30962b63268bf71f41ca9e4ed47d5b19`.
- [Matching test APK](../local/candidates/iqtvlite-sr03-validation-androidTest.apk):497,747B, SHA256 `37a33e31cda95237060c88eb62f33ecbfd063fcd8df7783a482463adf2eaf1dc`.
- Actual signer SHA256: `a82e179afe278f1b69638db3fda1523d605f205289c42065846fdac4639249f8`, existing local Android Debug certificate. It still differs from the installed production-package certificate; no overwrite upgrade is claimed.
- Still the separate tv.iqtvlite.app.validation application,10001/2.2.0-tivimini-local-validation, visible validation label, debuggable code/resource-shrunk test variant. Android test manifest was parsed: tv.iqtvlite.app.validation.test targets tv.iqtvlite.app.validation through AndroidJUnitRunner.
- Actual file size is7.49% above the13,140,066B old universal-debug reference and below the14,454,072B1.10 bound. No image/resource changes occurred in t10.
- verify_apk passed both packaged ARM ELF architectures, required JNI symbol names, kept bridge/EPG/launcher DEX classes and budget. v7a library824,172B; arm64 library1,197,152B. Additional final-archive assertions confirm LiveOwnerView, ReturnToLive and PlaylistCommit DEX classes. Static presence is not a JNI loading or Media3 runtime result.
- git diff --check passed; only the two production files, the new JVM fixture, the extended existing Android fixture and this report are t10 changes. No source commit or push was made.

The suitable gates are Android JVM/lint/clean application+test APK and verify_apk. As permitted by t10, unchanged Rust reuses t8's158 tests plus1 ignored network smoke, fmt,clippy and license passes; these are not presented as new t10 executions. Local packaged native code remains the existing API24/r30 dual-ARM build. It cannot replace the future r27 CI rebuild.

The pre-t10 artifacts remain archived under their original names/hashes. No installation, uninstall, data clearing, force-stop, remote key injection, main push or tag was performed. An independent SR-03 source recheck is required before CI. The new CI artifact must subsequently run all7 instrumentation methods, early503/connection-error attribution and the original TIV/device/performance gates under the acceptance task's authorization.
