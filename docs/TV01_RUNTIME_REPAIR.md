# TV-01 instrumentation shared-runtime repair

Task t14,2026-11-19 (Asia/Hong_Kong), continuing interrupted t12. The failed10071 pair is retained as negative evidence. No TV operation is performed by developer; acceptance owns the device and has the user's authorization for tests through delivery. This report records packaging/static evidence, not a claim that seven tests ran on the TV.

## Observed cause

The actual10071 device output shows AndroidJUnitRunner.onCreate→registerTestStorage→TestDirCalculator.<init>→NoClassDefFoundError kotlin.LazyKt, before any test executes. The failed main/test SHA256 are respectively ab151bb0a005fb840ef38ec8151ce502b87049105f53b087537bfd7104aac065 and bd6e6bff2f891b87a2e870cdece069e7d6507bb2c73aa12116173e7cc4f9bfec.

Actual DEX tables were parsed, distinguishing type references from class definitions. Main has9936 definitions, including320 kotlin stdlib definitions; test has1100 definitions and zero kotlin stdlib definitions. The test contains the method reference kotlin.LazyKt.lazy(Function0):Lazy, while neither APK defines LazyKt. Main R8 usage lists the removed LazyKt facade (including the whole-class entry near line56742); mapping preserves the surviving LazyKt__LazyJVMKt name and contains no renamed LazyKt target. The reported failure is deletion, not obfuscation.

The resolved debugAndroidTestRuntimeClasspath already contains kotlin-stdlib2.2.10, coroutines1.7.3 and OkHttp4.12.0, constrained to the main runtime versions. AGP excludes the shared runtime from the test APK as supplied by the tested application; main R8 had only the application's reachability graph, not these external test callers. Adding the same stdlib compile dependency again cannot restore classes/members removed from the main DEX. Kotlin multifile facade methods inherited from implementation parts also need the declaring members preserved.

The final broad shared-API negative control rejects the actual old pair with23 missing runtime types,59 missing methods and5 missing fields after excluding metadata-only annotations and resolving platform inheritance against the selected SDK stub. The earlier narrower diagnostic counts were superseded by this complete shared-program check. The gaps include collections/text/intrinsics facades, coroutine executors/durations, AndroidX futures and MockWebServer's shared OkHttp WebSocket APIs. Fixing only LazyKt would leave more failures.

REA skill was consulted. Its JADX engine is not configured, so no REA decompilation/Evidence ID is claimed. This investigation uses the actual device stack, Gradle/R8 outputs and ordinary format-aware DEX/classfile parsing; no plugin/profile repair or engine installation was performed.

## Scoped repair and gate

- [Validation keep contract](../android/app/instrumentation-shared-keep.pro) contains154 shared class roots derived from the unchanged instrumentation DEX and known shared ancestors. It keeps exact referenced method/field signatures, including Kotlin multifile declaring families. Test-facing application model/API classes retain their members so external assertions/serialization and virtual equals calls retain the tested API. It does not keep entire Kotlin/AndroidX packages.
- [Generator](../scripts/generate_instrumentation_keep.py) makes the root derivation repeatable after test APIs/dependencies change. The generated contract is reviewed and committed, not silently regenerated in CI.
- [Gradle configuration](../android/app/build.gradle.kts) applies these roots only to isolated validation debug. Main/test dependencies and all seven tests are retained. R8 code shrinking/resource shrinking remain enabled; normal release/non-validation rules, JNI/DTO keeps and business/TIV code are unchanged. Test storage is not disabled.
- [DEX reader](../scripts/dex_contract.py),[SDK classfile reader](../scripts/classfile_contract.py) and [pair gate](../scripts/verify_instrumentation_pair.py) check actual DEX definitions and inherited method/field resolution across the pair, not substring presence. Array.clone is a VM-provided method. Test-owned implementation APIs are not confused with the shared APIs supplied by main; SDK hidden APIs/reflection/dynamic loading and API28 execution remain outside static proof.
- CI runs the pair gate before upload and saves the JSON report in the existing reports artifact. A changed external test API whose main binding is absent blocks delivery. Known metadata-only annotation references are explicitly excluded; executable shared references are not waived.

## Verification

The unchanged19 JVM tests, lint and clean dual-APK Gradle build passed locally. Local lint:0 errors,49 warnings,5 hints. Local test outputs may be restored by Gradle cache because business/test source is unchanged; fresh cloud version/source inputs are verified in the new CI report. Local shared linkage:141 external type references,271 method references and41 field references,0 missing. Local main9987 definitions versus old9936; kotlin definitions335 versus320. Existing verify_apk passes with14,140,311B; this r30/local candidate is diagnostic only, not the cloud deliverable.

New cloud run/source/artifact identities and independent downloaded checks are recorded here after the fresh r27 CI completes. Unchanged Rust code reuses the successful independent Rust run44374114111 (fmt/clippy,158 tests plus1 ignored smoke,licenses). APK CI must be new and must build API24/r27 dual ARM from a fresh runner.temp target.

## Acceptance handoff

Use only the new same-run/same-signature pair; the10071 app/test remain historical failure evidence. Developer does not race acceptance for the TV. Acceptance compares the actual new signature with installed73421716… first. If equal, ordinary validation-only replacement is sufficient. If different, retain both old APKs/hashes and archive the validation sandbox files/shared_prefs/databases with run-as before replacing only tv.iqtvlite.app.validation and its .test package. Verify that backup and rollback materials exist before removing this disposable sandbox; a keep-data uninstall alone does not bypass Android's signature binding. Reinstall/restore the old pair and its sandbox is the rollback path. No command here authorizes removing or clearing tv.iqtvlite.app or com.cjlhll.iptv or their data.

No original production package/data is modified. The new temporary certificate is disclosed; no private key is exported or committed. After acceptance installs the new pair, all7 tests, normal test-storage registration, actual JNI/HTTP/gzip/cancellation/LAN/Media3 plus early errors, the TIV/SR Activity paths and performance gates still require device execution. A static pair check is necessary evidence, not device success.
