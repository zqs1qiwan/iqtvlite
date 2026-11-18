# Integration implementation and local validation

Task t4, 2026-11-19 (Asia/Hong_Kong). The implementation is in the isolated `integration/tivimini-baseline` branch based on90ae4cd. This is a local candidate report, not a new GitHub CI result or TV acceptance pass.

## Changes and provenance

The compiled UI comes from tivimini8c93e1b, not the dirty prior single-panel redesign. The original SettingsDrawer, QualitySelectorDialog, ChannelSwitchLoading and SplashActivity match the fixed source snapshots ignoring line endings. ChannelInfoBanner differs only by an explicit Media3 opt-in annotation. PlayerDrawer keeps the upstream columns, dimensions, focus and Back behavior; it adds the documented global number mapping and removes a full catch-up URL from a log. PlayerActivity retains that navigation while adding buffered digits, the target low-latency Media3 configuration, immutable per-source headers, session metrics and lifecycle/state fixes.

Production M3U parse and stable IDs call Rust JNI. EPG parsing uses a new file JNI endpoint with native gzip detection, streaming XMLTV, retained UTC offsets and bounded input; Android owns download/cache/UI. The same source result is not parsed again on focus movement. Source commit generation and version checks reject old manual/background requests; phone pushes cancel the previous OkHttp call, and only the latest request can navigate or persist. Lifecycle resume and catch-up retries resolve the current selected channel at callback time rather than retaining a stale composition value.

The old target Views activity/service files remain in Git for rollback but are excluded from current source sets. There is one active main ExoPlayer; no Rust decode, secondary playback controller or cloned navigation state machine was added. Rust net/player/tvfocus libraries are not claimed as production JNI integrations.

## Verification ledger

| Check | Local result and limits |
|---|---|
| cargo fmt --all -- --check | Passed using the existing GNU host toolchain |
| cargo test --workspace --locked |158 passed;1 network smoke test intentionally ignored by its existing annotation |
| cargo clippy --workspace --all-targets -- -D warnings | Passed |
| cargo deny check licenses | Passed |
| cargo ndk -t arm64-v8a -t armeabi-v7a -P24 ... build --release --locked -p iqtvlite-capi | Both native libraries built locally with NDK30.0.16248370 |
| Native dynamic symbols | Required RustBridge version/parseM3u/parseXmltvFile/stableId exports present on v7a; packaged checks cover both ABIs |
| Native ABI/API link | v7a ELF32 ARM and arm64 ELF64 AArch64; Android ident API24; shared dependencies libc.so/libdl.so, no extra C++ runtime to copy |
| :app:testDebugUnitTest |5 tests: stale background A→B→A; cache write failure; newer manual request; global filtered numbering; suffix headers/default isolation |
| :app:lintDebug | Passed:0 errors,49 warnings,5 hints after explicit Media3 opt-ins and the narrow TV dispatch exception; not presented as zero-warning lint |
| :app:assembleDebug | Passed for isolated optimized test/debug candidate |
| :app:assembleDebugAndroidTest | Test APK compiled; the5 instrumentation methods were not executed in t4 |
| scripts/verify_apk.py | Checks actual file budget, both ELF architectures, required JNI symbol names and kept bridge/EPG/launcher DEX descriptors |
| New GitHub CI | Pending t5; previous90ae4cd CI is baseline evidence only |
| Target device runtime | Pending explicitly authorized candidate installation and t6; no install/uninstall/clear-data/force-stop/key injection in t4 |

The clean final Gradle invocation is:

```sh
./gradlew :app:clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest -PISOLATED_TEST_PACKAGE=true --no-daemon
```

On this host it is run with android/gradlew.bat -p android and SDK environment variables. A clean build is used for the delivered APK: an earlier incremental package retained unused ZIP data (20,035,844B despite roughly14.1MB of live entries), which the size gate correctly rejected. Rebuilding through Gradle clean removes that packaging slack. The APK is not manually rezipped or resigned to hide the issue.

## Candidate identity

The frozen lifecycle fix passed the final clean build. The delivered local APK is [iqtvlite-validation.apk](../local/candidates/iqtvlite-validation.apk),14,107,543B (7.36% larger than the old universal debug reference, within the1.10 bound). SHA256:
`d28db1f7a9ed20869baf0455f665e892750956ffceb53071f786f7ec85be8581`.

The matching compiled test APK is [iqtvlite-validation-androidTest.apk](../local/candidates/iqtvlite-validation-androidTest.apk),490,875B, SHA256:
`f4cdf32ba2443f308fc8a1e57c4dcbc49f56c95abacd201413d92a5878239a07`.
Its parsed manifest targets tv.iqtvlite.app.validation and uses androidx.test.runner.AndroidJUnitRunner. These files are deliberately ignored local build artifacts, not binaries committed to Git.

- Package: tv.iqtvlite.app.validation; generated namespace and JNI bridge remain tv.iqtvlite.app.
- Version: code10001 /2.2.0-tivimini-local-validation.
- Visible label: “iqtvlite 验证版”.
- minSdk24 / target35 / compile36; universal arm64-v8a + armeabi-v7a.
- Launcher: com.cjlhll.iptv.SplashActivity.
- Native library sizes: v7a824,172B; arm64 1,197,152B.
- Variant: debuggable test build with R8 code shrinking and resource shrinking. AGP explicitly disables release-level code optimization/obfuscation for debuggable variants; this is not a release APK.

## Size and unchanged visual assets

Reference old universal debug APK:13,140,066B. The allowed1.10 bound is14,454,072B after flooring. The initial unshrunk Compose candidate was25,208,802B; retaining that artifact would not meet the budget. Code/resource shrinking and removal of uncompiled legacy UI dependencies reduce the candidate without replacing original interactions.

Original visual assets were retained at their exact dimensions and colour metadata. scripts/optimize_png.py recompresses only concatenated IDAT data and copies every other PNG chunk verbatim. Decompressed filtered pixel bytes are asserted identical. No resize, quantization or quality conversion occurs.

| Asset | Original | Lossless result | Dimensions |
|---|---:|---:|---|
| banner_bitmap.png |3,782,564B |3,439,444B |2730×1535 RGBA |
| logo.png |1,050,333B |1,024,682B |1024×1024 RGB |

Combined saving:368,771B with unchanged pixels/metadata. A diagnostic shrunk package before this recompression had compressed categories DEX5,676,851B / native2,038,672B / resources6,518,194B, versus the reference DEX9,199,209B / native1,855,192B / resources1,832,502B. These explain the change: the original artwork dominates resources, while shrinking reduces DEX. Final clean artifact bytes and exact category totals are the release-decision values; different variants are never called a measured runtime improvement.

## Signing and safe rollout

Installed old package certificate SHA256:
646dbf613bfa1995e895c7cd16676c7bb9cba075d5cb7498eaf2b1abfc5ead42.
Local candidate debug certificate SHA256:
a82e179afe278f1b69638db3fda1523d605f205289c42065846fdac4639249f8.

They differ. The local candidate cannot update the existing tv.iqtvlite.app installation. The validation applicationId is separate, visibly labelled, and uses28081–28084 LAN ports instead of the normal upstream preferred ports. Existing apps/data remain installed. Android's local debug keystore already existed on2026-11-04; t4 did not generate or copy a private key. The source tracked keystore/password were not imported.

The workflow now supports optional stable signing from four repository secrets. No secrets currently exist in the target repository, so a fresh CI run remains explicitly temporary-debug signed until an authorized maintainer provisions them. Partial signing configuration fails. A stable new key does not recover the historical installed debug key. Do not uninstall, clear data or use `-r` to work around a mismatch.

## Runtime checks prepared, not claimed passed

NativeBoundaryTest exercises actual native M3U mapping with Chinese/long title/empty group/duplicate URL/UA/Referer/catch-up and cross-source IDs; actual XMLTV JNI through both Content-Encoding:gzip and bare gzip HTTP bodies; UTC offset/hour duration; shared-refresh cancellation; two concurrent LAN binds/cleanup; Media3 requests using a custom channel UA/Referer followed by a default channel without header pollution. Tests use their own preference/files wrappers and localhost sources. They must run against the final authorized candidate, not the old installed app.

For the isolated package, install both matching candidate/test APKs only after authorization, then run:

```sh
adb -s 192.168.2.245:5555 shell am instrument -w tv.iqtvlite.app.validation.test/androidx.test.runner.AndroidJUnitRunner
```

The delivered test APK manifest target was checked as tv.iqtvlite.app.validation; recheck it for the fresh CI artifact. A passing compile/static DEX symbol check is not a substitute for this execution, serialization round-trip, loaded native library, source requests or TV launcher/resource rendering.

TIV remote paths, QR phone submission, actual first frame/audio, Home/resume, error recovery, cold-start/TTFF/zap distributions, sustained playback and CPU/PSS remain t6 gates. The app can emit opt-in elapsedRealtime session metrics for the rendered first frame, decoder/format and dropped/rebuffer events. No visual/video success is inferred from READY, a black SurfaceView screenshot or missing model image support. No performance improvement percentage is asserted.

## Build compatibility and documented exceptions

- AGP8.7.3 produced extensive D8 Kotlin2.2 metadata rewriting warnings. AGP8.10.1 with Gradle8.11.1/Kotlin2.2.10 removes that mismatch; Media3 remains1.11.1.
- Media3 unstable/experimental APIs are explicitly opted into at the relevant screen/helper/banner, including the target's prewarming option.
- ComponentActivity.dispatchKeyEvent retains a method-local RestrictedApi suppression with a TV event-ownership reason. This is a disclosed lint exception, not a claim that annotations repaired event behavior.
- Local MSVC linking failed because link.exe is absent; per-command GNU host selection passed the tests without changing global defaults.
- One SDK r27c installation failed with 'Error on ZipFile unknown archive'. No retry/deletion of user SDK data was performed. Local r30 native results do not replace the independently rebuilt r27 CI artifact.
- Cargo.lock only removes obsolete DVR references already absent from the eight-crate target workspace; no dependency versions were upgraded by that cleanup.
- The existing target Rust parser's XMLTV no-zone default remains UTC, following XMLTV. Explicit provider offsets are preserved for UI dates/catch-up; this differs from tivimini's permissive local-time fallback for a timestamp without a zone and is an explicit data-correctness choice, not hidden UI parity.

Fresh CI should run on the integration branch with isolated_test_package=true, rebuild both native libraries under r27, run core and Android gates, and record that CI APK's own SHA/signature/ABI before any target-device acceptance. No main push or tag/Release was performed in t4.
