# CI10073: TV-01 packaging repair candidate

2026-11-19 (Asia/Hong_Kong),task t14. This pair supersedes the runner-blocked10071 pair for acceptance. It is not a claim that device tests have passed. [Repair and root-cause evidence](TV01_RUNTIME_REPAIR.md).

## Exact source and workflow

- Source [82e56d2c96f409edd197d838605ae34178489d55](https://github.com/zqs1qiwan/iqtvlite/commit/82e56d2c96f409edd197d838605ae34178489d55),integration/tivimini-baseline.
- Final [APK run44426383315](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44426383315),run73,isolated_test_package=true: success.
- Intermediate [run44424119875](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44424119875),source330cf13: historical,not final. Its downloaded pair also passes the corrected inherited-member gate, but its original gate had the t16 weakness. Do not substitute it for10073.
- No business Kotlin or Rust source changes. The five independently reviewed business-source hashes remain unchanged. Unchanged Rust reuses [independent Rust CI44374114111](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44374114111):fmt/clippy/licenses and158 tests plus1 ignored smoke.

## Download and identity

[Paired APK artifact14485315735](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44426383315/artifacts/14485315735),[JUnit/lint/R8/contract reports14485325730](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44426383315/artifacts/14485325730).90-day retention,API expiry2027-02-17 00:07:07 UTC. These are Actions artifacts,not a Release.

| Archive member | Bytes | SHA256 |
|---|---:|---|
| debug/app-debug.apk |14,142,183| `956c9366541c1bbf9bba9a9834e8f7bc2d8f892072c0f8ba7c7dc95064da6509` |
| androidTest/debug/app-debug-androidTest.apk |497,747| `b3e9a827c1eb627da6ebc0dd67fde2bc57e0a4bd048508b1d76e5f1da422857a` |

Downloaded copies were not rebuilt,rezipped or resigned. They match the cloud contract report hashes. Both signatures verify and share SHA256 `00b0a57d7195cb432bb7d718afe8aaa4295f9629c75f9c4fb8673c4b5c804b98` (temporary Android Debug). This differs from installed10071 signer73421716…; do not mix old/new tests or assume overwrite compatibility.

Actual main applicationId tv.iqtvlite.app.validation,versionCode10073,versionName2.2.0-tivimini-82e56d2c-validation,label“iqtvlite 验证版”,min24,target35,compile36. JNI namespace remains tv.iqtvlite.app. Actual test package tv.iqtvlite.app.validation.test uses AndroidJUnitRunner targeting the main validation package; AGP test manifest has no separate version fields. Pairing is verified by source/run/signature/target.

## Actual gates

- Fresh runner.temp native target,NDK27.0.12077973,cargo-ndk API24,armv7 and arm64. Downloaded packaged ELF notes independently confirm API24/r27/build12077973. v7a826040B SHA256 cc4cad75141362847af2f0712b79fc4a8540986d9025f9a6fb4d717192c5036e;arm641199856B SHA25656b922d468337d6b8689ee5ea7a9b877c855b6d601b68dc98518574ac3ff2051. Four required JNI dynamic exports per ABI passed.
- Android19 JVM tests:10 SR01/02+4 SR03+5 source-integrity,0 failures/errors/skips. Fresh cloud XML timestamps00:10:40–41 UTC. Lint XML0 errors,47 warnings,5 hints. Both APKs built with R8/resource shrinking enabled.
- Seven Python in-memory contract regressions passed in CI,including test-owned child→main parent missing method/field,restoration,override/interface cases and generator main roots.
- Actual cloud pair and downloaded pair pass the revised real-DEX contract:9987 main/1100 test classes,335 main kotlin definitions/test0,141 external type references and1908 hierarchy method/642 field references;0 missing shared types/methods/fields. Platform/metadata exclusions are explicit and do not skip inherited main members. Actual failed10071 remains a negative control with23 missing types/59 methods/5 fields.
- verify_apk passed both ELF architectures,JNI names,kept JNI/EPG/launcher descriptors and absolute size.14,142,183B is+7.63% over13,140,066B and below14,454,072B by311,889B. No gate exception or feature/artwork removal.

```sh
gh run download 44426383315 -R zqs1qiwan/iqtvlite -n iqtvlite-test-apk -D ci10073-apks
gh run download 44426383315 -R zqs1qiwan/iqtvlite -n iqtvlite-android-reports -D ci10073-reports
```

## Device handoff

Acceptance owns the TV and has the user's testing authorization; no repeat permission request is needed for authorized tests. Developer has not deployed this pair or run the seven instrumentation methods. Follow the reversible validation-only replacement/rollback plan in the repair report:verify backup and old APK pair before replacing differently signed validation app/test. All original tv.iqtvlite.app/com.cjlhll.iptv packages and data remain protected. No main push,tag or public Release.

Acceptance must run normal test-storage initialization and all7 methods on this exact pair,then continue early HTTP/connection errors,SR Activity/Home/Back/refresh paths,TIV/QR/focus,first frame/audio and B/C resource gates. Static linkage cannot prove decoding,platform-hidden/reflection APIs or device behavior. The final receipt commit is documentation-only; APK source remains82e56d2c,not a substituted later HEAD.
