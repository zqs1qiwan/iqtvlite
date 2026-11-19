# Audio diagnostics candidate (t19)

This candidate adds DEBUG observation and explicit device tests. It does not add MPEG audio support or establish audible playback. No FFmpeg, codec/MIME alias, track-selection policy, renderer, video, TIV or SR behavior is changed. The developer does not operate the TV or interrupt acceptance's CI10073 cold/zap/long-play measurements.

## Evidence motivating the diagnostic

The saved08:41 CCTV2 s43 window has validation GAIN/loss none/notified true. Although STREAM_MUSIC says Muted:false, its active HDMI0x400 index is0/15; speaker/default7 does not apply to HDMI. AudioPolicy and AudioFlinger agree with very low/-inf gain,standby/0Tracks. Zero volume does not by itself explain no Track. Vendor XML declares MP3 audio/mpeg,not audio/mpeg-L2; this is a candidate capability gap,not a runtime API/format-selection proof. The existing TS fixture is CCTV1 MPEG-1 Layer II48kHz/stereo/256kbit/s,not CCTV2. s43 video940ms+452ms rebuffer is separate from s2 video609ms/earlier steady window. These facts do not establish an acoustic result or justify blindly adding a decoder.

## Production observation

[StreamPlayback](../android/app/src/tivimini/java/com/cjlhll/iptv/StreamPlayback.kt) retains the existing BuildConfig.DEBUG + Log.isLoggable(IQTV-METRICS,DEBUG) guard and existing EventTime timeline/current-mediaId ownership check. New observation returns before track traversal or format rendering when the guard is off. No per-sample/per-frame logging is added.

With the tag enabled,matched media events report:

- audio_track:group/track index,mime,codecs,rate,channels,language,bitrate,supported,selected,and renderer_format_support (Media3 C.FormatSupport numeric value,4=HANDLED,3=EXCEEDS_CAPABILITIES,lower values unsupported).
- audio_tracks:audio group count and current player context (volume,suppression,playWhenReady,audioSessionId).
- audio_enabled/disabled,decoder initialized/released,input format,AudioTrack initialized/released (encoding/rate/channel mask/buffer/tunneling/offload),AudioSink/codec error class,audio position advancing and sessionId changes.
- volume/playWhenReady/suppression events. Player volume is distinct from system STREAM_MUSIC HDMI volume,so continue to capture dumpsys audio.

Each line uses the existing stable/hash/sequence mediaId. Stale/empty-timeline events are rejected rather than attributed to a newer channel. Listener removal at final player release and ownership filtering mean missing close callbacks cannot be interpreted as proof that audio was never enabled. Existing business error dispatch and video callbacks remain unchanged.

[AudioDiagnosticFields](../android/app/src/tivimini/java/com/cjlhll/iptv/AudioDiagnosticFields.kt) bounds and validates typed format tokens. No Format.id/label/URI/HTTP headers/exception message/stack trace is printed. [JVM regression](../android/app/src/test/java/com/cjlhll/iptv/AudioDiagnosticFieldsTest.kt) checks useful legitimate tokens and rejection of credential URLs,control characters and oversized values.

## Explicit API28-compatible device tests

[AudioDiagnosticTest](../android/app/src/androidTest/java/com/cjlhll/iptv/AudioDiagnosticTest.kt) is separate from the unchanged7 NativeBoundaryTest methods:

1. api28AudioMimeCapabilities enumerates regular non-encoder codecs for audio/mpeg-L2,audio/mpeg,and AAC. It records MIME candidate counts and per-codec48kHz/stereo format support/rate/channel capabilities or exception class. Platform calls exist by API21; no API29 hardware/software flags. AAC candidate presence is the enumeration positive control. L2 zero candidates is reported,not converted into a test skip or a claim that MP3 can decode it.
2. pcmWaveReachesAudioTrackWithoutClaimingAudibility uses a local MockWebServer and two seconds of digital-silence PCM WAV. It observes real AudioTrack initialization,audio position advancing,AUDIO_RAW input and no sink error. It neither changes global volume nor emits a test tone,and cannot validate MPEG decoding or audible output. Test preferences use their own audio_diagnostic_ prefix. It releases its player/server on completion.

Acceptance can explicitly select the new class after scheduling deployment; running only the old NativeBoundaryTest class does not run these two methods. Production diagnostics require the tag; the explicitly invoked probe uses IQTV-AUDIO-PROBE to expose its results even if the production tag is off.

```text
adb -s 192.168.2.245:5555 shell setprop log.tag.IQTV-METRICS DEBUG
adb -s 192.168.2.245:5555 shell am instrument -w -r -e class com.cjlhll.iptv.NativeBoundaryTest,com.cjlhll.iptv.AudioDiagnosticTest tv.iqtvlite.app.validation.test/androidx.test.runner.AndroidJUnitRunner
```

These commands are handoff instructions,not developer-executed TV operations. Preserve the existing tag value if changing it,and restore afterward. Preserve the original system volume/route; an eventual positive-volume hearing check is a separate controlled action by acceptance. Pair signing/sandbox replacement must follow the exact new receipt,not interrupt the current10073 measurements.

## Verification and receipt

Local clean Android/JVM/lint/dual APK gates passed;9 instrumentation methods compile (original7+2 diagnostics),not executed by developer. The first pair gate caught the new annotation class reference UnstableApi absent from main; the generated keep contract was refreshed to210 precise roots and a final clean rebuild passed with0 missing types/methods/fields (150 external types,1920 hierarchy methods,649 fields). Local diagnostic main14,156,695B remains under14,454,072B; local r30 native is not the CI delivery. Seven inherited-contract Python regressions pass,and the actual broken10071 pair is still rejected. Fresh r27/API24 dual-ARM CI identities are appended after the new run completes. Updating instrumentation APIs requires the exact generated shared-class/member keep contract and corrected test→main inheritance gate; the old broken10071 pair remains the negative control. Rust/dependency code is unchanged and can reuse the prior independent Rust run. No main push,tag,or public Release.

## CI receipt: fresh r27 dual-ABI pair (run74, 44434416536)

Cloud receipt appended after the diagnostic source commit. Documentation-only update; it does not rebuild, re-zip or re-sign the artifact and does not claim device or audible results. Acceptance owns device execution and the TV volume/route.

- Source commit [77fe7f613e612a1831f39aa5e61676b05f5b5e4e](https://github.com/zqs1qiwan/iqtvlite/commit/77fe7f613e612a1831f39aa5e61676b05f5b5e4e), branch integration/tivimini-baseline. Ordinary push only; main and tags unchanged.
- [APK CI run 44434416536](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44434416536), run74, workflow_dispatch with isolated_test_package=true: successful (5m8s). Fresh CARGO_TARGET_DIR, NDK Pkg.Revision27.0.12077973; cargo-ndk builds arm64-v8a plus armeabi-v7a at API24. Job log confirms BUILD SUCCESSFUL, package/native-code/targetSdk dump, both signer certs, the four JNI exports (version/parseM3u/stableId/parseXmltvFile) on both ABIs, test_dex_contract7 tests, and "Shared class/method/field runtime contract passed".
- Cloud JVM testDebugUnitTest21 tests,0 failures (AudioDiagnosticFields2,PlaybackIntentRegression10,SourceIntegrity5,UnlistedLiveOwner4); lint0 errors/47 warnings/5 hints. Rust/dependency code is unchanged versus the earlier independent Rust CI, so that pure-Rust workflow was not re-run; packaged .so hashes equal the prior r27 pair below.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| [Application debug/app-debug.apk](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44434416536/artifacts/14488745834) |14,158,563| `242048fe2e8e26ad409e181d83c57fdb58179b9ef902099da9ba30505658b5a6` |
| [Test androidTest/debug/app-debug-androidTest.apk](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44434416536/artifacts/14488745834) |502,075| `e98f157cf512cc3ed86e7cd181fe922f5253e91bf26df3632d31f33ffa288d44` |

- [Paired artifact 14488745834](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44434416536/artifacts/14488745834) named iqtvlite-test-apk, download zip digest `sha256:74dc26b9c35772118cf50bfada3870565914dee2789241ee35f2e802ec157428`,12,646,972B, retention90 days, expiry2027-02-17 01:07:04 UTC. [Reports artifact 14488112239](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44434416536/artifacts/14488112239) named iqtvlite-android-reports, `sha256:db9942be71b7e2fe75f67359d66ca1b770ae49f629648e5d6440749ff7874375`,3,258,853B. These are Actions artifacts, not a public Release.
- Both APKs signer SHA256 `bcb7ffe365afb255e145a176bcec799c5abe4281609447a4b593a93aa843a882` (Android Debug). This runner certificate differs from prior runs and from local a82e179a… / installed-production646dbf61…; no stable cross-run upgrade guarantee is made. Use this matching cloud pair for acceptance.
- Main applicationId tv.iqtvlite.app.validation; visible label "iqtvlite 验证版"; versionCode10074, versionName2.2.0-tivimini-77fe7f61-validation; min24,target35,compile36; native-code 'arm64-v8a' 'armeabi-v7a'; JNI/RustBridge namespace tv.iqtvlite.app. Test package tv.iqtvlite.app.validation.test; instrumentation targetPackage tv.iqtvlite.app.validation; runner androidx.test.runner.AndroidJUnitRunner; the AGP manifest carries versionCode0/empty versionName and is paired by run+source+signature+target rather than a fabricated application version.
- instrumentation-contract.json: app_sha256/test_sha256 equal the table hashes above, missing_types/methods/fields all empty; shared150 external types/1920 methods/649 fields. The test sources contain9 @Test methods (NativeBoundaryTest7 + AudioDiagnosticTest2) and were compiled only, not executed by the developer.
- Main APK14,158,563B is within the14,454,072B absolute budget with295,509B remaining. No gate exception was applied.

| Packaged library | Actual bytes | SHA256 |
|---|---:|---|
| armeabi-v7a,ELF32 ARM |826,040| `cc4cad75141362847af2f0712b79fc4a8540986d9025f9a6fb4d717192c5036e` |
| arm64-v8a,ELF64 AArch64 |1,199,856| `56b922d468337d6b8689ee5ea7a9b877c855b6d601b68dc98518574ac3ff2051` |

Not verified by this receipt: any device installation, API28 execution of the9 methods, MIME candidate enumeration output, PCM AudioTrack observation, MPEG-L2 decode support, audible output, or TV volume/route state. These remain acceptance's explicit on-device work. No main push, tag, or public Release.

For the CI10074 device follow-up that repairs the two diagnostic test fixtures (TV-AUDIO-01/TV-UA-01) and re-delivers, see [TV_AUDIO_TEST_REPAIR.md](TV_AUDIO_TEST_REPAIR.md).
