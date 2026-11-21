# REA Static Analysis Report — tivimate.apk

Analysis date: 2026-11-17
Analyst toolchain: REA 6.9.0 / JADX headless MCP 0.7.1 / Python 3.12 zip inspection
Artifact: `C:\Users\Q\Documents\Projects\iqtv\tivimate.apk`
SHA-256: `beaebb25bf818450bf496d4cefb16885b190f156815dea280c31eaae0909ee6c`
Size: 21,122,308 bytes

> Scope note: This report documents **static, read-only reverse-engineering observations**
> produced by REA. The APK was never executed, patched, unpacked at runtime, or
> repackaged. All statements are observations or clearly-labelled inferences;
> runtime behavior was not observed.

---

## 1. Package identity (Evidence: inspect_android_package)

| Field | Value |
|---|---|
| Package | `ar.tvplayer.tv` |
| Version name | `5.3.3` |
| Version code | `1000005332` |
| minSdk / targetSdk | 23 / 34 |
| Manifest components | ~40 (activities, services, receivers, providers) |
| DEX classes visible to JADX | 6545 |

Main launcher activity: `ar.tvplayer.tv.ui.MainActivity`
(categories: LAUNCHER + LEANBACK_LAUNCHER → Android TV / set-top-box app).

Notable declared components:
- `ar.tvplayer.tv.unlock.ui.UnlockPremiumActivity`
- `ar.tvplayer.tv.settings.ui.playlists.wizard.PlaylistWizardActivity`
- `ar.tvplayer.tv.settings.ui.tvguide.*` (EPG source configuration)
- `ar.tvplayer.tv.reminders.ui.ReminderPopupActivity`
- `ar.tvplayer.tv.core.data.repositories.RecordingAlarmReceiver` / `ReminderAlarmReceiver`
- `ar.tvplayer.tv.base.ScreenOnService`, `BootReceiver`
- `com.android.billingclient.api.ProxyBillingActivity(V2)` — Google Play billing

## 2. Permissions (observed)

INTERNET, ACCESS_NETWORK_STATE, REQUEST_INSTALL_PACKAGES,
MANAGE_EXTERNAL_STORAGE, READ/WRITE_EXTERNAL_STORAGE,
READ_MEDIA_IMAGES/VIDEO/AUDIO, FOREGROUND_SERVICE (+ dataSync,
mediaPlayback, specialUse), RECORD_AUDIO, RECEIVE_BOOT_COMPLETED,
SCHEDULE_EXACT_ALARM, SYSTEM_ALERT_WINDOW, `com.android.vending.BILLING`,
WAKE_LOCK, AD_ID, adservices attribution,
`ar.tvplayer.tv.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.

Inference: storage/media permissions + foreground media-playback service +
alarm/receiver components ⇒ features include local media, recording,
reminders, background playback.

## 3. Packing / protection (Evidence: zip inventory + inspect_android_class/method)

Archive membership:
- `classes.dex` 39,416 B · `classes2.dex` 547,612 B · `classes3.dex` 5,617,296 B
- Native libs for arm64-v8a / armeabi-v7a / x86 / x86_64:
  `libdexprotector.so`, `libffmpegJNI.so`, `libavutil.so`, `libsqliteJni.so`,
  `libdatastore_shared_counter.so`

Only three `ar.tvplayer.*` classes are present in the static DEX:
`LibTvPlayerApplication`, `ProtectedTvPlayerApplication`, `R`.
There is **no** `ui.*`, `MainActivity`, `player.*`, etc. in the static DEX.

`ProtectedTvPlayerApplication`:
- `attachBaseContext()`: calls `b()`, then `System.loadLibrary("dexprotector")`.
- `b()`: computes SHA-256 of the signing certificate and compares against
  `cafa2c5e0affcc243b4df23d1a69720a78a92412afc210a051b88ca68fca2c5b`,
  throwing `RuntimeException` on mismatch / multiple signers / missing cert.
- Two methods (`a(Object,String)`, `c()`, `s(String)`) have `body_status: not_available`.
- `LibTvPlayerApplication` is a large obfuscated dispatch class (100+ `a(...)`
  overloads + `mNNNa` bridge methods) — a DexProtector runtime bridge stub.

**Conclusion (high confidence):** the real application bytecode is encrypted and
materialized at runtime by DexProtector. Static DEX/JADX analysis therefore exposes
only the loader stub, resources, manifest, and bundled third-party library classes.
Full business logic is not recoverable from static DEX alone.

## 4. Signature identity (Evidence: inspect_apk_signature)

Signing schemes present: v1 + v2 + v3 (v3.1 absent). All schemes agree on one
signer; self-consistent; strongest scheme v3. Signer certificate SHA-256:
`cafa2c5e0affcc243b4df23d1a69720a78a92412afc210a051b88ca68fca2c5b` —
which exactly matches the hard-coded value checked in `ProtectedTvPlayerApplication.b()`.
This confirms an anti-tamper / certificate-pinning link between the signature and
the loader stub. (Signature verification not performed; authenticity unknown.)

## 5. Playback stack (Evidence: search_android_classes)

Observed Media3 / ExoPlayer API classes in static DEX:
- `androidx.media3.exoplayer.ExoPlayer`, `ExoPlaybackException`, `ExoTimeoutException`, audio output providers
- `androidx.media3.datasource.*` (HTTP/File/Content/Asset data sources, DataSourceException)
- `androidx.media3.ui.AspectRatioFrameLayout`, `DefaultTimeBar`, `SubtitleView`
- `androidx.media3.common.*` (PlaybackException, ParserException, PriorityTaskManager, audio processors)
- Native FFmpeg layer (`libffmpegJNI.so`, `libavutil.so`)

Inference: playback is built on **AndroidX Media3 / ExoPlayer** with an FFmpeg JNI
integration, consistent with an IPTV player handling HLS/DASH and multiple stream
formats. (Exact stream-format handling lives in the packed code and was not observed.)

## 6. Findings ledger

| # | Question | Answer | Evidence / confidence |
|---|---|---|---|
| 1 | App identity | TiviMate 5.3.3, `ar.tvplayer.tv` | inspect_android_package — observed |
| 2 | Target platform | Android TV / set-top box | manifest LAUNCHER+LEANBACK — observed |
| 3 | Feature surface | playlists, EPG, recordings, reminders, premium unlock, billing | manifest components — observed/inferred |
| 4 | Protection | DexProtector packing + certificate pinning | zip + class/method + signature — observed (high) |
| 5 | Playback engine | Media3/ExoPlayer + FFmpeg JNI | class/zip inventory — observed |
| 6 | Full source recoverable statically? | No — real code is runtime-decrypted | DEX class inventory — observed |

## 7. Limitations / unknowns

- Static analysis only; no runtime execution, no unpacking of DexProtector payload.
- Manifest/decompiled text are observations, not proof of runtime policy enforcement.
- Incoming-reference data (not used here) is bounded to JADX using-nodes.
- No network endpoints, test device, or playlist were contacted as part of this analysis.

## 8. Scope boundary (unchanged)

This report is the legitimate reverse-engineering analysis deliverable. It does **not**
and will not include: unpacking the DexProtector payload for code copying, bypassing
the premium/license/certificate checks, or reproducing the app's UI/interaction at
pixel level for derivative development. Those items are outside what I can provide.
