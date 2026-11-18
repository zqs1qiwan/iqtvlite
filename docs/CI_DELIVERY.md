# GitHub CI candidate delivery

Task t6,2026-11-19 (Asia/Hong_Kong). This report binds a fresh cloud build to its source commit. [Independent SR-03 review](TIVIMINI_SR03_REVIEW.md) released the source gate; this report does not authorize Release publication or TV installation and does not claim device acceptance.

## Source and runs

- Source commit: [7f6e2d76bc8e2007d1b8684d5392777b6525a68e](https://github.com/zqs1qiwan/iqtvlite/commit/7f6e2d76bc8e2007d1b8684d5392777b6525a68e).
- Feature branch: integration/tivimini-baseline. Ordinary push only; main and tags are unchanged.
- [Independent Rust CI](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44374114111), run63, workflow_dispatch: successful. Actual Linux logs show fmt/clippy with warnings denied,158 passing tests and1 pre-existing ignored network smoke, plus cargo-deny licenses ok. Cloud Rust toolchain is1.100.0.
- [APK CI](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44374123717), run71, workflow_dispatch with isolated_test_package=true: successful. Downloaded JUnit XML has19 passes (10+5+4),0 failures/errors/skips; actual cloud lint XML has0 errors,47 warnings,5 hints. Both APKs built and passed cloud verification. No failed run or relaxed budget was needed.

The commit includes the original8c93e1b TV source migration and the independently accepted SR-01/SR-02/SR-03 repairs. The five t11 raw local source hashes were checked unchanged before staging; Git applies the repository's LF normalization. No Kotlin/Rust implementation changed in t6.

Preflight checked110 changed paths for private signing/token material and generated/local artifacts. No keystore,APK,shared library,local.properties,logs,reconstruction tree or live user preferences were committed. Stable-signing repository secrets were queried and were empty; temporary debug signing is therefore explicit. No signing key was copied, generated locally, committed or exported; the runner uses Gradle's normal temporary debug keystore.

## CI execution changes

[apk.yml](../.github/workflows/apk.yml) retains NDKr27 and explicitly builds arm64-v8a plus armeabi-v7a at API24. It sets CARGO_TARGET_DIR to a new runner.temp directory, so restored repository target caches and local r30 outputs cannot substitute for this native build. The job prints NDK source.properties and ELF Android notes, checks the four required dynamic JNI exports on both libraries, and checks the packaged APK with [verify_apk.py](../scripts/verify_apk.py).

The Android job independently runs JVM tests and lint, builds the validation application plus its matching Android test APK, verifies signatures and the instrumentation target, uploads both together as iqtvlite-test-apk, and also uploads JUnit/lint/R8/output-metadata reports as iqtvlite-android-reports. Required APK upload uses if-no-files-found:error. The report upload runs even on failure for diagnosis.

[ci.yml](../.github/workflows/ci.yml) independently checks Rust. Its obsolete libmpv-watch job was removed: the current APK ships Media3 only, and manually validating this branch must not create or push unrelated upstream-update branches.

## Downloaded artifact identity

The cloud artifact was downloaded and independently checked locally, without rebuilding, rezipping or resigning it. Its main APK hash exactly matches the cloud verify_apk log.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| [Application:debug/app-debug.apk](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44374123717/artifacts/14459474944) |14,125,799| `ab151bb0a005fb840ef38ec8151ce502b87049105f53b087537bfd7104aac065` |
| [Test:androidTest/debug/app-debug-androidTest.apk](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44374123717/artifacts/14459474944) |497,747| `bd6e6bff2f891b87a2e870cdece069e7d6507bb2c73aa12116173e7cc4f9bfec` |

- [GitHub paired-APK artifact14459474944](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44374123717/artifacts/14459474944), named iqtvlite-test-apk. [JUnit/lint/R8 reports artifact14461676716](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44374123717/artifacts/14461676716), named iqtvlite-android-reports. Retention90 days; API expiry2027-02-16 18:50:53 UTC. These are Actions artifacts, not a public Release.
- Actual main and test signer SHA256 both `734217160fc11c980403f0df19dd368204a51959472afaa6d7bc71f67c80583e` (Android Debug). This temporary runner certificate differs from local a82e179a… and installed-production646dbf61…. No stable cross-run upgrade guarantee is made; use the matching cloud pair for acceptance.
- Actual main applicationId tv.iqtvlite.app.validation; visible label “iqtvlite 验证版”; versionCode10071; versionName2.2.0-tivimini-7f6e2d76-validation; min24,target35,compile36; launcher/Leanback com.cjlhll.iptv.SplashActivity. JNI/RustBridge namespace remains tv.iqtvlite.app.
- Actual test package tv.iqtvlite.app.validation.test; AndroidJUnitRunner targets tv.iqtvlite.app.validation; min24,target35. AGP-generated instrumentation manifest has no own versionCode/versionName attributes: it is paired by the same run/source/signature and the explicit target, rather than a fabricated matching application version.
- Actual APK14,125,799B is+7.50% over the13,140,066B universal-debug reference, within14,454,072B with328,273B remaining. No feature/resource was removed for CI and no gate exception was applied.

The fresh native step used /home/runner/work/_temp/iqtvlite-android-r27-fresh, with NDK Pkg.Revision27.0.12077973. Downloaded packaged ELF PT_NOTE descriptors were also parsed independently: both explicitly contain API24,r27,build12077973. Dynamic JNI exports version/parseM3u/stableId/parseXmltvFile were checked by llvm-nm for both ABIs in the successful job.

| Packaged library | Actual bytes | SHA256 |
|---|---:|---|
| armeabi-v7a,ELF32 ARM |826,040| `cc4cad75141362847af2f0712b79fc4a8540986d9025f9a6fb4d717192c5036e` |
| arm64-v8a,ELF64 AArch64 |1,199,856| `56b922d468337d6b8689ee5ea7a9b877c855b6d601b68dc98518574ac3ff2051` |

verify_apk passed both ELF classes/machines, required JNI symbol names, R8 bridge/EPG/launcher class descriptors and the absolute file budget. Cloud R8 and output-metadata reports are retained. Local r30 candidates in earlier migration/repair documents remain historical; their hashes are not this cloud artifact.

To reproduce the download with GitHub authentication:

```sh
gh run download 44374123717 -R zqs1qiwan/iqtvlite -n iqtvlite-test-apk -D downloaded-apks
gh run download 44374123717 -R zqs1qiwan/iqtvlite -n iqtvlite-android-reports -D downloaded-reports
```

The final receipt/README update is a separate documentation-only commit after these successful runs. Its branch HEAD is not substituted for the artifact source commit7f6e2d76; APK identity remains bound to the run and source above.

## Handoff and limits

The artifact is an isolated tv.iqtvlite.app.validation build, with namespace/JNI bridge tv.iqtvlite.app. Version is derived from this APK run number and commit: the downloaded manifest confirms10071 /2.2.0-tivimini-7f6e2d76-validation.

CI success proves the reported static/build/JVM gates. Android instrumentation is compiled and delivered for the acceptance stage; it is not run by these workflows. The matching7-method suite must exercise actual JNI,encoded/bare gzip,single-flight cancellation,LAN coexistence,Media3 custom/default headers,B history/retry/Home/Back/missing-row ownership and actual error attribution on the authorized target candidate. Early503,connection failure and immediate-switch old-error delivery need explicit runtime checks as required by source review.

No source review or CI log proves TV focus/IME/QR submission,first frame/audio,hardware decoder behavior,Home sequence or CPU/PSS/zap distribution. These remain the same-APK acceptance gates. Device deployment and public Release need explicit confirmation. Existing production data must remain intact; no certificate-mismatch uninstall/clear-data workaround is authorized by this task.
