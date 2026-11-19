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
