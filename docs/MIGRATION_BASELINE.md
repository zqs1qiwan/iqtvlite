# tivimini integration baseline and migration record

Task t1 audit / t4 implementation, 2026-11-19 (Asia/Hong_Kong). This document records source evidence and implementation decisions; it is not a device acceptance or performance pass.

## Preserved repositories

- `../iqtv`: origin `https://github.com/cjlhll/tivimini.git`, HEAD `8c93e1b1d6601f882a51022cfafd59e684f6ac26`. At audit time 33 tracked paths differed (2559 additions / 3013 deletions), with substantial untracked Kotlin, tests, docs and artifacts. No reset, checkout, stash, remote edit or commit was performed there. The dirty UI is not the upstream interaction reference.
- `../iqtvlite`: old clean local checkout `7a5b6432811d76f15edfea0d0541984bd4d6d0f6`, no named remote; its main has nine crates including DVR. It was left untouched and was not used as the integration base.
- This independent clone: `https://github.com/zqs1qiwan/iqtvlite.git`, branch `integration/tivimini-baseline`, based on verified remote main `90ae4cdd0ba7f0acbeb25955868dc5d4b1d0bac9`. Neither original origin nor either main was overwritten.

## Reproducible upstream evidence

The upstream Android sources/resources were exported from the fixed tivimini commit with `git archive HEAD app/src/main`, excluding the dirty worktree. Only application source/resources were imported under `android/app/src/tivimini`; no tracked keystore, local.properties, decompiled reconstruction tree, evidence logs or private configuration were copied.

The implementation follows the source-confirmed TIV-01–18/23/24 specification from the collaboration workspace `../iqtv/docs/TIVIMINI_INTERACTION_SPEC.md`. Its owner traced eight snapshots to the fixed upstream commit. Key requirements are the 633dp channel/programme drawer, additional 151dp groups / 120dp dates, channel OK retaining the drawer, group/date OK retaining focus, and Back closing the entire drawer. Historical programmes alone enter catch-up. Settings remain the original independent 300dp panel and original actions.

Earlier PRD wording that mandates W/3 single panels, progressive Back, channel OK closing the drawer, or current-programme OK returning live is superseded for this replication. Number input and test source prefills are retained migration additions, not upstream features. Global merged-channel numbering is an explicit correctness fix. Original Kotlin programme/catch-up presentation is retained; no TiviMate decompilation is included or claimed.

## Audited target at 90ae4cd

| Item | Verified baseline |
|---|---|
| Rust workspace | 8 crates: core/store/epg/net/tvfocus/player/capi/tests; no DVR crate |
| Android | Kotlin Views, Media3 1.11.1, OkHttp 4.12.0, Coil 2.6.0 |
| Build | AGP 8.7.3, Kotlin 2.2.10, Gradle 8.11.1, JDK17 |
| Package | tv.iqtvlite.app; min26 / target35 / compile36 |
| Internal version | code1 / 0.1.0-test, despite v2.1-brand tag |
| Native | cargo-ndk, arm64-v8a + armeabi-v7a, CI NDKr27 |
| Rust core CI | run 44217568682, fmt / clippy / workspace tests / license audit all success |
| APK CI | run 44218052566, head90ae4cd, success |
| Release asset | v2.1-brand app-debug.apk, 13,140,066 bytes |
| Asset SHA256 | 26da3876b5fdc6886e40f888c6e6ad2ff3cd8e85a506db5277c45fd4972b475e |

Links: https://github.com/zqs1qiwan/iqtvlite/actions/runs/44217568682 and https://github.com/zqs1qiwan/iqtvlite/actions/runs/44218052566 . Baseline release is https://github.com/zqs1qiwan/iqtvlite/releases/tag/v2.1-brand .

The old APK job built both ABIs but asserted only the arm64 library. It did not run Android lint/unit tests, did not update the manifest version, and published an automatically debug-signed APK. README and older ADR/build documents contain obsolete Slint/NDKr30/libmpv/DVR claims. Actual code and the fixed CI artifact determine the baseline.

## Actual production ownership after integration

| Production operation | Owner and path |
|---|---|
| Activity/IME/DPAD/focus/drawers/info/quality modal | Original Compose UI in src/tivimini; Android remains responsible for focus |
| Main video | One Activity-owned Media3 ExoPlayer + PlayerView/SurfaceView; release on stop/dispose; legacy Views/PlaybackService are excluded from source sets |
| M3U syntax and stable IDs | M3uParser adapter → tv.iqtvlite.app.RustBridge.parseM3u / stableId → capi → core |
| Channel mapping | Adapter retains title/tvg-name/group/logo/tvg-id/alternatives/response-time/UA/Referer/catch-up. ChannelGrouper maintains upstream grouping and global numbering |
| Playlist source commit | SourceRepository shared version check + AtomicFile cache, rejects stale A→B→A results; invalid content does not replace the last cache |
| HTTP | Android OkHttp shared pool and IPv4-first DNS; actual media source receives immutable channel UA/Referer, not a mutable global UA |
| XMLTV/gzip/time parsing | EpgRepository stages a file → parseXmltvFile JNI → Rust streaming quick-xml/flate2. No complete XML string crosses JNI or resides on the Java heap |
| EPG presentation | Compact native JSON rows become one cached EpgData snapshot, then upstream Kotlin lookup/date/window presentation; no XML reparse per focus event |
| EPG cache/refresh | Source-scoped single-flight load, unique candidate, atomic parsed cache, cache fallback distinguished from network success; stale source updates are gated |
| Scan/push | Original QR + LAN server lifecycle and automatic import; test URLs are prefills, not a source restriction |
| Playback verification | Opt-in Media3 AnalyticsListener with session / stable channel / opaque line identity, actual rendered-first-frame, decoder/format/drop/rebuffer events; no full URL in added metrics |

The existing net/player/tvfocus crates remain tested library modules but are not newly connected to Android JNI. Existing grid/merge/catch-up URL JNI entry points remain available for compatibility; the replication screen uses the new file XMLTV endpoint, parseM3u and stableId. Do not claim that an unused Rust fallback state machine is governing Media3. Decode, buffering, surface and lifecycle are not reimplemented in Rust.

The file bridge still creates compact JSON and Kotlin programme objects once per successful import. This is a remaining peak-memory cost, not zero-copy storage. XML is limited to 32MiB expanded, 100,000 programmes and 10,000 guide channels; downloads have a 30-second request budget and 32MiB response cap. Over-budget/malformed imports retain a same-source cache. A cancelled screen stops awaiting a shared refresh; the bounded shared job may finish caching, and old screen work cannot publish into a new source. Rust parsing itself is synchronous and not interruptible mid-token. Native readers/decoders and HTTP/file streams have scoped disposal.

OkHttp transparently decompresses HTTP Content-Encoding:gzip. Rust detects gzip magic on the resulting file, so a plain decoded XML response is parsed once and a bare .gz body is decompressed once. The standalone gzip file boundary has native tests; both HTTP variants also need device/instrumented request-level confirmation.

## Dependency and API decisions

The source app used Compose/Kotlin2.0.21/AGP8.13.2/Media3 1.9.2, com.cjlhll.iptv min24/target36/compile36. The integration keeps target Kotlin2.2.10/Gradle8.11.1/JDK17/Media3 1.11.1. A full build exposed extensive D8 Kotlin2.2 metadata errors under AGP8.7.3, so AGP is raised to8.10.1 (the Kotlin2.2-compatible line). It adds the matching Kotlin Compose plugin and actual original Compose BOM2025.01.00/TV material1.0.0/foundation alpha12/activity-compose1.10.0 dependencies. Coil, RecyclerView, swipe refresh and MediaSession dependencies are not needed by the compiled replication UI. Original resources/themes require material1.12.0. Core-library desugaring supports java.time at API24.

Application identity and generated namespace remain tv.iqtvlite.app; original UI classes retain com.cjlhll.iptv source names. `tv.iqtvlite.app.RustBridge` remains unchanged because native symbol names are Java_tv_iqtvlite_app_RustBridge_*. The launcher is SplashActivity; a target MainActivity alias supports prior explicit launch commands. minSdk24 and cargo-ndk platform24 are explicit; actual device is API28/armeabi-v7a. Compile/link checks are not a claim of runtime API24 validation.

The target's existing low-latency LoadControl values are retained:1.5s minimum/6s maximum/0.5s playback/1s rebuffer, no back buffer, replacing tivimini's120s maximum and30s back buffer. Hardware decoder fallback, MediaCodec prewarming and target TS extractor/CEA-608 settings remain enabled; Android audio-focus attributes are set. This is a configured resource bound, not measured improvement; B/C playback tests must check rebuffer tradeoffs.

## Version, signing and non-destructive installation

APP_VERSION_CODE / APP_VERSION_NAME Gradle properties override defaults. CI defaults derive code from GITHUB_RUN_NUMBER (10000 + run number) and name from GITHUB_SHA; local defaults are code10001 and a local-labelled name. A tag alone no longer leaves every candidate at code1. For a deliberately separate test install use `-PISOLATED_TEST_PACKAGE=true`; this changes applicationId to tv.iqtvlite.app.validation while keeping the JNI namespace/class intact.

Read-only device extraction established baseline installed certificate SHA256:
`646dbf613bfa1995e895c7cd16676c7bb9cba075d5cb7498eaf2b1abfc5ead42` (Android Debug).
`gh secret list -R zqs1qiwan/iqtvlite` returned no configured names at audit time. There was no stable CI signing support at baseline. No private key was copied/generated/committed by this integration; neither the source tracked keystore nor its hard-coded password is imported.

The Gradle build supports a stable externally provisioned keystore through ANDROID_SIGNING_KEYSTORE_FILE / ANDROID_SIGNING_STORE_PASSWORD / ANDROID_SIGNING_KEY_ALIAS / ANDROID_SIGNING_KEY_PASSWORD. Partial configuration fails. The workflow can decode ANDROID_SIGNING_KEYSTORE_BASE64 from a repository secret into the runner temporary directory and supplies the other three secret values as environment variables. A maintainer must provision all four secrets with an authorized existing/new signing identity; no secret values are printed. Without configuration, CI still uses its ephemeral debug key and must be labelled accordingly. Stable future signing cannot retroactively reproduce the installed historical debug certificate.

The validation variant has the visible launcher label 'iqtvlite 验证版', a '-validation' version suffix and a separate LAN port range28081–28084. Normal package ports retain upstream18080/18081/19090/28080/8080 with bind-error fallback and cleanup. This avoids the known original/legacy preferred ports when apps coexist; actual QR address and concurrent binding still need device confirmation.

Before installation compare APK package/version/ABI/certificate with the installed package. Different certificates prohibit an in-place upgrade. Do not uninstall, clear data or attempt `-r` hoping to bypass signatures. The safe verification alternative is a separately built validation applicationId, retaining the original installed app and its rollback APK. Legacy pushed source preferences are copied only within the same application sandbox; all old preferences/cache files remain untouched. Old Views favorites and last-channel ID stay preserved for rollback but are not silently claimed as migrated to new UI semantics.

## Validation route

Use actual workspace CI commands: cargo fmt --all -- --check; cargo clippy --workspace --all-targets -- -D warnings; cargo test --workspace --locked; cargo deny check licenses. APK workflow uses cargo ndk -t arm64-v8a -t armeabi-v7a -P 24 -o android/app/src/main/jniLibs build --release --locked -p iqtvlite-capi, then android/gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug. scripts/verify_apk.py verifies both actual ELF architectures and required JNI names in the packaged libraries; dynamic symbol checks are also run in CI.

The delivered debug variant is an optimized test build: R8 minification and resource shrinking are enabled while debuggability/opt-in metrics remain. It is not equivalent to the old unminified debug variant. JNI bridge names, native methods and serialized EPG DTOs have explicit keep rules. Runtime JNI/EPG/headers tests are supplied as an instrumentation APK; compiling it is not a claim of execution. Original ComponentActivity.dispatchKeyEvent is intentionally retained for TV event ownership with a method-local RestrictedApi lint suppression and explanatory comment; this is a documented lint exception, not a replacement input mechanism.

On this Windows host default MSVC rustc could not find link.exe. The existing stable-x86_64-pc-windows-gnu toolchain was selected per command, without changing global defaults. It passed workspace tests. The SDK had NDK30.0.16248370. A single sdkmanager attempt to install27.2.12479018 failed with 'Error on ZipFile unknown archive'; no SDK directory was removed or download retried. Local Android .so verification therefore uses r30; the workflow remains pinned to r27 and must independently pass in CI, rebuilding its own libraries. Local artifacts do not substitute for CI evidence.

See IMPLEMENTATION_VALIDATION.md for final candidate hashes, command outcomes, artifact size analysis, certificate comparison and remaining device/CI checks. Tests cover source commit races/write failure, global numbering, native Chinese/long-title/empty-group/duplicate URL/UA/Referer/catch-up mapping, gzip/UTC offset and expanded-input limits. Device JNI runtime/load and request-level header tests remain explicit next-stage gates.

## Device and performance limitations

The acceptance dependency recorded API28/armeabi-v7a/1920x1080/240dpi (~1280x720dp), task-restore270ms and foreground104768KiB with three Activities. These are not cold-start or steady playback performance. The installed APK matches the old release hash. An older document reported3.6s cold playback/85.5MB PSS, but its conditions are not comparable. No percentage improvement is asserted.

Final acceptance must bind the new CI APK SHA/certificate/commit to install identity and TIV-01–24 paths. Metrics are enabled only for debug builds when `adb shell setprop log.tag.IQTV-METRICS DEBUG` is set; set the tag to WARN to disable. `prepare` and `first_frame` carry the same session identifier; old timeline/mediaItem callbacks are ignored. READY/loading-hidden is never recorded as a first frame. Decoder/format/dropped/rebuffer events are state changes, not per-frame logs. Source URLs/credentials are not present in these new metrics.

Compare B/C on the same device, channel, line, codec, source hash and background load; gather cold start, actual TTFF, at least30 zaps, steady CPU/PSS and long playback. APK byte budget uses the13,140,066-byte universal debug reference and a1.10 bound, with dependency/native/resource deltas reported honestly. Source-level UI traceability and successful compilation do not replace TV focus/playback/scan-push validation. No installation, uninstall, clear-data, force-stop or injected remote keys were performed during this implementation task.
