# TV-AUDIO-01 / TV-UA-01 instrumentation-fixture repair and CI pair

2026-11-19. Fixes the two test/fixture defects that [CI10074 device verification](../../iqtv/docs/CI_10074_DEVICE_VERIFICATION.md) exposed (7/9 instrument methods passed; the two failures are test-code defects, not a proven product regression). This delivery only changes `androidTest` sources plus the generated validation keep contract. No business source (TIV/SR/JNI/R8/app code/audio decode/dependencies) is modified. Device execution remains acceptance2's work; the device was offline, so the developer did not install, run or wait on it.

## TV-AUDIO-01 — PCM url resolved on the main looper

- Symptom (CI10074): `pcmWaveReachesAudioTrackWithoutClaimingAudibility` threw `NetworkOnMainThreadException`; the PCM/AudioTrack path never executed.
- Root cause: `AudioDiagnosticTest.kt` built the channel inside `instrumentation.runOnMainSync { ... Channel("PCM diagnostic", server.url("/silence.wav")...) }`. `MockWebServer.url()` performs a reverse-DNS lookup, which StrictMode blocks on the main thread. Test defect, not product.
- Fix: resolve `val silenceUrl = server.url("/silence.wav").toString()` **before** `runOnMainSync`, then pass `silenceUrl` into `StreamPlayback.prepare`. Digital silence and the existing `initialized` / `advanced` / `AUDIO_RAW` / no-sink-error assertions are unchanged; no global volume is touched.
- Effect: the AudioTrack pipeline is now actually entered, so the assertions exercise real `onAudioTrackInitialized`, `onAudioPositionAdvancing`, `onAudioInputFormatChanged(AUDIO_RAW)` and `onAudioSinkError` callbacks instead of failing before playback.

## TV-UA-01 — non-deterministic request assertion

- Symptom (CI10074): `media3RequestUsesChannelUaAndReferer` failed `expected:</default.ts> but was:</live.ts>`; the first channel's `User-Agent=Channel-UA/1` / `Referer=https://ref.example/` assertions had already passed, proving channel-level UA/Referer injection works.
- Root cause: the test's `server.takeRequest()` was not path-filtered. After the first channel's `503`, ExoPlayer retried `/live.ts`; the second `prepare` on the same player left that stale retry in flight, so the next `takeRequest()` could return `/live.ts` instead of the new `/default.ts`. A fixture race (possibly surfaced by the new audio-diagnostic listener timing), not a UA/Referer regression.
- Fix: added `takeRequestForPath(server, path, …)` which drains non-matching in-flight requests until the matching path arrives. First channel waits on `/live.ts` and still asserts its UA/Referer; second channel waits on `/default.ts` and still asserts the default UA `VLC/3.0.20 LibVLC/3.0.20` and `Referer == null`. A batch of responses is enqueued so a stale retry cannot stall the new request. The filter never weakens the UA/Referer assertions; it strengthens coverage by asserting that any observed `/live.ts` retry still carries `Channel-UA/1` / `https://ref.example/`.
- Not done: assertions were not relaxed to accept `live.ts`; the exact default-UA / null-referer checks remain.

## Keep contract

The new test lambda adds a `kotlin.jvm.functions.Function1.invoke(Object)` reference from the test DEX to main-owned Kotlin stdlib, so the exact validation keep was regenerated with `scripts/generate_instrumentation_keep.py` (210 shared roots). The regenerated file is idempotent and now includes `-keepclassmembers class kotlin.jvm.functions.Function1 { java.lang.Object invoke(java.lang.Object); }` (4 added lines). This is a validation-only ABI keep, not a business change.

## Local pre-merge verification (not the CI delivery)

- `:app:testDebugUnitTest` 21 tests, 0 failures; `:app:lintDebug` 0 errors / 49 warnings / 5 hints (local warning count differs from cloud 47 due to local toolchain/caches); `:app:assembleDebug` + `:app:assembleDebugAndroidTest` BUILD SUCCESSFUL.
- `scripts/verify_apk.py` passes for both local ABIs; `python -m unittest discover -s scripts -p test_dex_contract.py` 7/7 OK; `verify_instrumentation_pair.py` on the new local pair: `missing_types/methods/fields = 0`, "Shared class/method/field runtime contract passed".
- Old broken CI10071 pair still rejected by the gate (missing SuspendFunction, CallbackToFutureAdapter/ResolvableFuture/Trace, PlaybackIntent.getActive/updatePlaylist$default, etc.), so the gate keeps its negative control. Local native is the r30 build and is not the delivery.

## CI pair receipt (run75, 44515714680)

- Source commit [b4455ac49417656cdb07cdc5d618bf9f2dc63ea6](https://github.com/zqs1qiwan/iqtvlite/commit/b4455ac49417656cdb07cdc5d618bf9f2dc63ea6), branch integration/tivimini-baseline. Ordinary push only; main and tags unchanged.
- [APK CI run 44515714680](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44515714680), run75, workflow_dispatch with isolated_test_package=true: successful. Fresh CARGO_TARGET_DIR, NDK Pkg.Revision27.0.12077973, cargo-ndk arm64-v8a + armeabi-v7a at API24. Job log shows BUILD SUCCESSFUL, both signer certs, both-ABI JNI exports, `test_dex_contract` 7 tests, "Shared class/method/field runtime contract passed". Cloud JVM 21 tests 0 failures; lint 0 errors / 47 warnings / 5 hints.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| [Application debug/app-debug.apk](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44515714680/artifacts/14525752764) |14,158,563| `6446130caced04563a96149178243261eb15a07f621503adaa0843807ab67bee` |
| [Test androidTest/debug/app-debug-androidTest.apk](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44515714680/artifacts/14525752764) |502,955| `a1c475ce831145ed46d7b9df33a1b4a4de9c12bb07fd70b17c732f16c5cb768d` |

- [Paired artifact 14525752764](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44515714680/artifacts/14525752764) named iqtvlite-test-apk, download zip digest `sha256:3f3beeb1972b1498e39d356dbb08a4456420df25d4521fd979b723030b3ed684`,12,647,494B, retention90 days, expiry2027-02-17 11:32:22 UTC. [Reports artifact 14525273400](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44515714680/artifacts/14525273400) named iqtvlite-android-reports, `sha256:dbd3077a17c6b079bc5feebeac338a5548944439b01f93758e91e3ca40c957fb`,3,258,858B. Actions artifacts, not a public Release.
- Both APKs signer SHA256 `4ae761a04df5076ee64c4cca2f64b5825fe43ee853b34a2b9712656dbc7d046d` (Android Debug). Fresh runner certificate, different from the 10074 `bcb7ffe3…` and all earlier temporary debug certs; no stable cross-run upgrade guarantee.
- Main applicationId tv.iqtvlite.app.validation; label "iqtvlite 验证版"; versionCode10075, versionName2.2.0-tivimini-b4455ac4-validation; min24,target35,compile36; native-code 'arm64-v8a' 'armeabi-v7a'; JNI/RustBridge namespace tv.iqtvlite.app. Test package tv.iqtvlite.app.validation.test; targetPackage tv.iqtvlite.app.validation; runner androidx.test.runner.AndroidJUnitRunner.
- instrumentation-contract.json: `app_sha256`/`test_sha256` equal the table hashes above, `missing_types/methods/fields` all empty; shared150 external types/1923 methods/649 fields.
- Main APK14,158,563B is within the14,454,072B budget with295,509B remaining; no feature was stripped. Packaged native libs are byte-identical to the prior r27 pair (Rust/native unchanged): armeabi-v7a 826,040B `cc4cad75141362847af2f0712b79fc4a8540986d9025f9a6fb4d717192c5036e`; arm64-v8a 1,199,856B `56b922d468337d6b8689ee5ea7a9b877c855b6d601b68dc98518574ac3ff2051`.

To reproduce:

```sh
gh run download 44515714680 -R zqs1qiwan/iqtvlite -n iqtvlite-test-apk -D downloaded-apks
gh run download 44515714680 -R zqs1qiwan/iqtvlite -n iqtvlite-android-reports -D downloaded-reports
```

## Limits / handoff

Not verified by this receipt: any device installation or execution of the 9 instrument methods, the actual PCM/AudioTrack observation on API28, MIME enumeration output, MPEG-L2 decode support, audible output, or TV volume/route. These remain acceptance2's explicit on-device work on the recovered 192.168.2.245 device. Expected next device check: full runner `Tests run: 9` with the two repaired methods `pcmWaveReachesAudioTrackWithoutClaimingAudibility` and `media3RequestUsesChannelUaAndReferer` passing, plus the rest of TIV/perf items. No main push, tag, or public Release. This receipt does not claim any audio/performance improvement.
