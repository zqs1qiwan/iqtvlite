# SR-01 / SR-02 implementation repair

2026-11-19 (Asia/Hong_Kong), task t8. This is the implementer's repair record, **awaiting independent review**. It does not change the failed/needs_revision verdict in the [original t5 review](../../iqtv/docs/TIVIMINI_SOURCE_REVIEW.md), and it does not claim target-device or new GitHub CI acceptance.

Worktree: integration/tivimini-baseline in the isolated iqtvlite-integration clone, based on90ae4cd. t4 was not committed; its source/candidate record remains historical and the t8 paths below identify this repair. No main push, tag, APK installation, uninstall, clear-data or TV key input was performed.

## SR-01: bind recovery to the producing session

[PlaybackIntent](../android/app/src/tivimini/java/com/cjlhll/iptv/PlaybackIntent.kt) is a main-thread intent/session guard. It owns only the immutable prepared request and its media binding; it is not a second decoder or navigation state machine. Existing live group/variant indexes remain the UI's state.

- Beginning a channel selection invalidates the previous intent **before** asynchronous source probing. A pending selection rejects playlist application; completing a new prepare creates a new immutable PreparedPlayback with its own revision and actual mediaId. Home/stop invalidates active recoveries and cancels pending source selection, retaining the request for resume.
- [StreamPlayback](../android/app/src/tivimini/java/com/cjlhll/iptv/StreamPlayback.kt) stamps PlaybackFailure from AnalyticsListener.onPlayerError's EventTime. It verifies the event timeline's mediaId against the prepared binding and current MediaItem, then sends that binding. Errors without a provable matching timeline are not assigned a guessed owner. There is no Unit event and no consumer-time restamping of A as B.
- [PlayerActivity](../android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt)'s long-lived collectors call liveSelection/selectedLiveNow at execution time. These functions read the current delegated channelGroups/index state, not the initial composition's ordinary currentChannel val.
- The same production updatePlaylist pipeline guards before loading, after loading/before parsing, after parsing, and immediately before a synchronous commit. Row resolution prefers the selected channel's stable identity and URL, then stable identity; a URL reused by another known owner cannot silently select A. It scans existing groups without materializing a second flattened channel table. It reads the selection again at commit. Any newer user intent rejects the old result, including the interval when C has been chosen but its reachability probe has not yet prepared C. The pipeline discards stale work rather than rebasing an old recovery onto a new channel.
- The failed session is checked before an urgent download, after it completes, and during the common parse/apply pipeline. Source and actual player/media identity must still match. A valid B recovery prepares B/the resolved B line, including when A remains in the new list; an obsolete A error cannot download or restart B.
- Initial cache application is keyed by player lifecycle as well as source identity; Home invalidation cannot leave a canceled initial cache load permanently without a replacement. Deferred logo prefetch checks that its list is still current.

The revision is recovery/probe ownership metadata. It does not replace the original drawer/back/focus model. The existing sourceSelectGeneration still cancels source probe jobs; it is not used to fabricate a media failure identity.

## SR-02: keep catch-up owner and address together

[CatchupPlayRequest](../android/app/src/tivimini/java/com/cjlhll/iptv/CatchupHelper.kt) now has an optional immutable owner Channel. [PlayerDrawer](../android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt) fills it from the actual EPG row B when a historical programme is selected. This payload addition does not change date/group/focus/OK/Back layout or behavior.

PlaybackIntent.catchup verifies the owner against liveUrl and binds **B Channel + B catchupUrl** as one MediaPlaybackRequest. It retains B's stable ID, UA, Referer and live URL. An explicit owner distinguishes B even when another row has the same live URL; legacy callers without an owner resolve by liveUrl. An unknown owner never falls back to the unrelated currently playing A.

First playback, delayed catch-up retries and Home resume all pass that immutable request to StreamPlayback.prepare. Retry uses the exact failed session's request, and only if that session remains current after the existing800ms delay. Resume retains B even if a refreshed live list no longer contains B. The common httpHeaders helper is consumed by the real Media3 source factory; there is no mutable global per-channel UA.

The history callback does **not** force currentGroupIndex/currentVariantIndex to B. Live A and browsed B stay separate as in the original. On the original ReturnToLive path, B's retained owner/stable identity resolves the B live group; if B is no longer listed, its retained B live request is used. Existing source order probing and actual explicit Back-to-live index changes are retained.

Original limitations are deliberately preserved: programme/banner naming may still use the live group, catch-up progress is prepared from the beginning after Home, and retry count remains per player instance. This repair does not broaden the UI/product scope to fix those limitations.

## Regression evidence and execution boundary

[PlaybackIntentRegressionTest](../android/app/src/test/java/com/cjlhll/iptv/PlaybackIntentRegressionTest.kt) invokes the actual production helper with Channel/ChannelGroup objects, injected asynchronous loads/parses and controlled HTTP. It does not only compare generation integers.

| Regression | Observable checked |
|---|---|
| A→B then reordered background list | Applied group/row remains B with B ID/headers, although A still exists |
| A failure queued, then B selected/prepared | Immutable queued event remains A; no old-A download, parse or commit occurs |
| B recovery parsing delayed, user chooses C | Old B application is rejected even before C has prepared; C choice remains intact |
| Background cache read delayed, user switches C | Old content is rejected before parsing |
| Current B error and fresh list still contains A/B | Recovery re-prepares B rather than A |
| A live→B history, retry and Home/list missing B | Request remains B+archive URL, ID/UA/Referer and later B-live address |
| Same live URL, different A/B owner identities | Explicit B catch-up owner wins; missing owner cannot inherit unrelated A |
| A reuses B URL during a playlist refresh | The applied row remains B by stable ID, both for unchanged B URL and replaced B URL |
| Actual localhost HTTP via production header contract | A request followed by B archive first/retry/Home requests sends the respective real User-Agent and Referer; B media owner stays202 |

MockWebServer4.12.0 is added only to testImplementation (it already exists at the same version for Android tests). An initial JDK HttpServer fixture could not compile against the Android unit-test boot classpath, so it was replaced by this pinned test-only dependency. No product network dependency/version was changed.

[NativeBoundaryTest](../android/app/src/androidTest/java/com/cjlhll/iptv/NativeBoundaryTest.kt) additionally provides actual Media3 fixtures for first B catch-up/retry/Home/Back requests with distinct A/B headers and real returned mediaIds, and for an actual Media3 parser error retaining its producing A binding after switching B. These Android tests are compiled into the matching test APK; **they are not executed in t8**, because this task forbids installing a candidate. The executed localhost JVM transport fixture checks the shared production header contract over OkHttp, not Android/Media3 decoding or TV focus.

## Final local verification and new candidate identity

The final frozen production repair passed these commands (GNU host selection is per command and does not change the global toolchain):

```sh
cargo +stable-x86_64-pc-windows-gnu fmt --all -- --check
cargo +stable-x86_64-pc-windows-gnu test --workspace --locked
cargo +stable-x86_64-pc-windows-gnu clippy --workspace --all-targets -- -D warnings
cargo +stable-x86_64-pc-windows-gnu deny check licenses
android/gradlew.bat -p android :app:clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest -PISOLATED_TEST_PACKAGE=true --no-daemon
python scripts/verify_apk.py android/app/build/outputs/apk/debug/app-debug.apk
```

Rust:158 passed,1 existing network smoke test ignored. Android JVM:15 passed (10 new production-pipeline/localhost regressions plus5 source-integrity tests),0 failures/errors. Final lint:0 errors,49 warnings,5 hints. Both application and instrumentation APK builds passed. The existing narrow dispatchKeyEvent lint exception and release-level optimization limits of debuggable R8 remain disclosed; this is a code/resource-shrunk **debug test variant**, not a release APK.

- [Repaired local APK](../local/candidates/iqtvlite-sr-repair-validation.apk):14,107,543B, SHA256 `61af7ac08a07dfc4ae56485f059fb45e13cb360e8163f5a0ef043f01961f8ac3`.
- [Matching compiled Android test APK](../local/candidates/iqtvlite-sr-repair-validation-androidTest.apk):495,551B, SHA256 `da99499fd0581e165f4fb04c25464886a80667d88bfd80134a17fd3540e06ca7`.
- Actual signer SHA256: `a82e179afe278f1b69638db3fda1523d605f205289c42065846fdac4639249f8` (existing local Android Debug key). It differs from the existing production-package certificate646dbf61…, so no production overwrite upgrade is claimed.
- Application remains tv.iqtvlite.app.validation, versionCode10001/versionName2.2.0-tivimini-local-validation, visibly labelled “iqtvlite 验证版”. JNI namespace stays tv.iqtvlite.app. Test manifest was parsed: tv.iqtvlite.app.validation.test targets tv.iqtvlite.app.validation through androidx.test.runner.AndroidJUnitRunner.
- Universal APK is still7.36% above the13,140,066B old universal debug reference and within the14,454,072B bound. No artwork/navigation was removed, and PNG resources were not changed in t8. Equal aligned APK byte size does not imply equal code; the new SHA binds this repair.
- Packaged v7a ELF32 ARM native library824,172B and arm64 ELF64 AArch64 library1,197,152B passed verify_apk. Required JNI exports, RustBridge/EPG/launcher DEX descriptors remain. Additional archive assertions confirm PlaybackIntent, MediaPlaybackRequest, PreparedPlayback and PlaybackFailure descriptors in the delivered DEX.

The pre-repair t4 candidate remains archived separately under its original d28db1f7… hash. Neither local candidate nor the matching test APK is committed to Git. After removing redundant constant assertions from JVM-only fixture source, the15 JVM tests were also rerun against that final source. No production/test-APK behavior or artifact bytes changed.

No Android instrumentation method was executed and no TV install/data/process/key operation occurred. Host localhost HTTP verifies the actual shared header contract and intent ownership; actual Media3 error EventTime attribution/transport, JNI runtime, TV Home/focus and rendering still require the final authorized CI APK. The root t5 needs_revision verdict remains until a separate reviewer accepts this repair.

## Required downstream gates

Fresh independent source review must evaluate these files and the new candidate identity. A fresh GitHub CI run must rebuild native libraries under the existing r27 workflow and regenerate both APKs; local native libraries remain the t4 API24 builds made with existing NDKr30, not r27 evidence. The final CI APK's own SHA/certificate/manifest/ABI must be checked before authorized installation.

Execute all7 Android instrumentation methods against that same authorized candidate, including actual Media3 error timeline attribution and B catch-up requests. Then run the original TIV paths and the SR-01/SR-02 cases on API28 armeabi-v7a. The t4 documented XMLTV no-zone behavior, dispatchKeyEvent lint exception, optional signing limits and unmeasured runtime performance remain unchanged. No source/static/host-test result in this report is a TV acceptance or performance improvement claim.
