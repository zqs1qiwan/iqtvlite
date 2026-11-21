# CI10074（t19 音频诊断 pair）真机验收记录 — 失败/阻断

2026-11-19，acceptance2（t15 attempt 3）。绑定 CI10074 独立 validation pair。**结论：失败/阻断交付**。身份核验通过并成功替换 validation 沙箱；9 项 instrumentation 实跑 7 通过 2 失败；PCM 音频诊断未真正执行；随后测试机 192.168.2.245 掉线（ping 不可达），未能继续 TIV/冷启/切台/30min 长播。修复下列两个测试缺陷并恢复设备后，需重新交付 pair 并复验全部 9 项。

## 1. 产物身份与安装（PASS）

[CI run 44434416536](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44434416536)，source `77fe7f61`，paired artifact `iqtvlite-test-apk` 14488745834 / reports 14488112239。本地下载并独立 `Get-FileHash`/`aapt2`/`apksigner` 核验：

| 项 | 应用 | androidTest |
|---|---|---|
| 包 | tv.iqtvlite.app.validation | tv.iqtvlite.app.validation.test |
| 版本 | 10074 / 2.2.0-tivimini-77fe7f61-validation | manifest 无自身版本 |
| min/target | 24/35 | 24/35 |
| ABI | arm64-v8a + armeabi-v7a | — |
| SHA256 | 242048fe2e8e26ad409e181d83c57fdb58179b9ef902099da9ba30505658b5a6 | e98f157cf512cc3ed86e7cd181fe922f5253e91bf26df3632d31f33ffa288d44 |
| 字节 | 14,158,563 | 502,075 |
| 签名SHA256 | bcb7ffe365afb255e145a176bcec799c5abe4281609447a4b593a93aa843a882 | 同左 |

两 SHA/字节与 Captain 交付值逐字节一致；签名 `bcb7ffe3…` 与已装 10073 的 `00b0a57d…` 不同（临时 debug，独立 validation 包）。证据：[app-identity](evidence/2026-11-19-ci-10074/app-identity.txt)、[test-identity](evidence/2026-11-19-ci-10074/test-identity.txt)、[app-badging](evidence/2026-11-19-ci-10074/app-badging.txt)、[test-badging](evidence/2026-11-19-ci-10074/test-badging.txt)、[app-signature](evidence/2026-11-19-ci-10074/app-signature.txt)、[test-signature](evidence/2026-11-19-ci-10074/test-signature.txt)、[candidate-10074.json](../../iqtvlite-integration/local/ci-delivery/candidate-10074.json)。

安装：仅在取证/备份后替换独立 validation 沙箱。备份 10073 validation 数据 `validation-10073-backup.tar`（1,726,464B，含 `shared_prefs/iptv_stream.xml`、`iptv_prefs.xml`、playlist/epg 缓存）与 `test-10073-backup.tar`（2,560B）。随后 `adb uninstall` 两个旧 validation 包、`install -r` 两个新包，全部 Success。安装后 `pm path` 拉取 base.apk 重新 hash，应用/测试分别仍为 `242048fe…`/`e98f157c…`，与 CI 文件一致；`dumpsys package` = 10074 / min24 target35 / primaryCpuAbi armeabi-v7a；runner `tv.iqtvlite.app.validation.test/androidx.test.runner.AndroidJUnitRunner`。**原生产包 `tv.iqtvlite.app` 与 `com.cjlhll.iptv` 前后均在，未卸载/未 clear/未改源。**

## 2. 9 项 instrumentation 实际结果（7 通过 / 2 失败）

真实执行整包 runner（无 `-e class` 过滤），`Tests run: 9, Failures: 2`，逐项 start=STATUS_CODE 1 / end。原始输出 [instrumentation-all-10074.txt](evidence/2026-11-19-ci-10074/instrumentation-all-10074.txt)。

| 类.方法 | 结果 |
|---|---|
| NativeBoundaryTest.media3CatchupOwnerStaysBForFirstRetryAndHome | 通过 |
| NativeBoundaryTest.cancelledWaiterDoesNotPoisonSharedRefresh | 通过 |
| NativeBoundaryTest.lanServersUseDifferentPortsAndStopCleanly | 通过 |
| NativeBoundaryTest.media3ErrorCarriesProducingSessionRatherThanLaterCurrentChannel | 通过 |
| NativeBoundaryTest.productionM3uAdapterRetainsNativeFieldsAndIdentity | 通过 |
| NativeBoundaryTest.encodedAndBareGzipAreDecodedExactlyOnce | 通过 |
| NativeBoundaryTest.media3RequestUsesChannelUaAndReferer | **失败**（见 2.2） |
| AudioDiagnosticTest.api28AudioMimeCapabilities | 通过 |
| AudioDiagnosticTest.pcmWaveReachesAudioTrackWithoutClaimingAudibility | **失败**（见 2.1） |

### 2.1 失败 TV-AUDIO-01：PCM 音频诊断被自身测试缺陷阻断

- 复现：`adb -s 192.168.2.245:5555 shell am instrument -w -r tv.iqtvlite.app.validation.test/androidx.test.runner.AndroidJUnitRunner` → `pcmWaveReachesAudioTrackWithoutClaimingAudibility` 抛 `android.os.NetworkOnMainThreadException`。
- 栈：`StrictMode.onNetwork → InetAddress.getCanonicalHostName → okhttp3.mockwebserver.MockWebServer.getHostName → MockWebServer.url(MockWebServer.kt:234) → AudioDiagnosticTest.kt:96`，运行在 `Instrumentation.runOnMainSync` 主线程。
- 根因：`AudioDiagnosticTest.kt:96` 在 `instrumentation.runOnMainSync { ... Channel("PCM diagnostic", server.url("/silence.wav").toString(), ...) }` 内部调用 `server.url()`；MockWebServer.url() 触发反向 DNS（网络），主线程被 StrictMode 拦截。**这是测试代码缺陷，不是产品缺陷**，结果是 PCM/AudioTrack 管线根本没被执行。
- 修复要求：在进入 `runOnMainSync` 前先 `val url = server.url("/silence.wav").toString()`（与 NativeBoundaryTest 其它用例一致，那里 url 在 mainSync 外计算），或把 `.url()` 移到后台线程；随后重跑该用例确认 `initialized/advanced/AUDIO_RAW` 断言真正执行。

### 2.2 失败 TV-UA-01：UA/Referer 用例在 10074 上出现非确定性路径断言

- 复现：同上整包 runner → `media3RequestUsesChannelUaAndReferer` 抛 `org.junit.ComparisonFailure: expected:</[default].ts> but was:</[live].ts>`（NativeBoundaryTest.kt:218）。
- 事实：该用例在 10073（82e56d2）为通过，在 10074（77fe7f61）失败。第一条通道请求的断言 `User-Agent=Channel-UA/1`、`Referer=https://ref.example/`（NativeBoundaryTest.kt:210-211）**已通过**，说明通道级 UA/Referer 注入本身在起作用；失败在第二次 `server.takeRequest()` 取到的是首通道 `/live.ts` 的一次重试请求，而非新 `默认UA` 通道的 `/default.ts`。
- 根因（源码分析）：用例第一段 `enqueue(503)` 触发 ExoPlayer 对 `/live.ts` 的自动重试；随后在**同一 ExoPlayer** 上再 prepare 第二通道并 `enqueue(503)`，`takeRequest()` 未过滤路径，可能先返回 `/live.ts` 重试（被第二个 503 消费），从而命中 `assertEquals("/default.ts", next.path)` 失败。10074 新增的 StreamPlayback 音频分析监听（+63 行，`observe()` 关闭时提前返回）可能改变时序而暴露该非确定性；也可能本身即为既有竞态。**不能据此断定产品 UA/Referer 合同回归**——首请求注入已证通过。
- 修复要求：让用例确定性——`takeRequest` 按 `path=="/default.ts"` 过滤（忽略 `/live.ts` 重试），或第二通道使用新的 ExoPlayer 实例，或首通道改用不会触发重试的响应；同时可补断言 `/live.ts` 重试仍携带 `Channel-UA/1`（这更有价值）。修复后重跑确认。

> 说明：两处均为测试/夹具缺陷，而非已证明的产品回归；但自动门禁本次为红（9 中 2 失败），按规则不得判为通过。

## 3. 音频诊断状态（未结论，不得宣称有声/无声）

- `api28AudioMimeCapabilities`：**通过**（`MediaCodecList(REGULAR_CODECS)` 枚举正常，AAC 正对照候选>0）。该用例会向 logcat `IQTV-AUDIO-PROBE` 打印每种 MIME（含 `audio/mpeg-L2`/`audio/mpeg`/`audio/mp4a-latm`）的候选数与 `isFormatSupported/rate48000/maxChannels`；**但设备在取回该 logcat 前掉线，L2 具体候选数本轮未取到**，不能编造。
- `pcmWaveReachesAudioTrack…`：**未执行**（见 2.1），PCM→AudioTrack 管线仍无运行证据。
- 10074 生产侧新增音频可观测性（StreamPlayback +63：`audio_track/audio_tracks/audio_enabled/audio_format/audio_decoder/audio_track_initialized/audio_volume/audio_sink_error/audio_session` 等，受 `IQTV-METRICS` DEBUG 门控）已随包安装，但**尚未在真实 CCTV2 会话上实际采集**（设备掉线）。
- 因此：CCTV2 到底“无音轨/不支持 L2/未选/sink 无写入/音量路由”哪一分支，本轮仍**未判定**；HDMI 媒体音量索引 0 的既有线索见 [AUDIO_PATH_DIAGNOSIS.md](AUDIO_PATH_DIAGNOSIS.md)，需在设备恢复后按同一会话补齐并做正音量（保存/可回退）听音对照。

## 4. 测试机掉线（ENV-01 阻断）

在第二次以错误写法 `am instrument ... -e class com.cjlhll.iptv.AudioDiagnosticTest#api28AudioMimeCapabilities` 触发（`#` 被设备 shell 当作注释，命令挂起）后，`adb devices` 变为 `192.168.2.245:5555 offline`；随后本机 `ping 192.168.2.245`（源 192.168.2.193）持续 `Destination host unreachable / Request timed out`，多次 `adb disconnect/kill-server/connect` 均失败，盒子已不在网。**这不是我可从本机修复的问题**，需现场恢复 192.168.2.245 网络/adbd。

## 5. 10073 旧证据（仅参考，不得静默迁移到 10074）

同机 10073 曾测得：冷启 10 次 TotalTime p50 6536 / p95 8325ms；切台 30 次 prepare-TTFF p50 1008.5 / p95 1544ms（0 失败）；90s 稳态 CPU 单核口径 p50 48.38% / p95 76.04%，PSS≈73–81MiB；SR01/02/03 clean 通过。以上属 10073，不构成 10074 结论；最终验收须在“被接受的最终 pair”上重测。

## 6. 本轮实际执行数量

- 10074 身份核验：完成（PASS）；安装替换：完成（PASS）。
- instrumentation：实跑 9，7 通过 / 2 失败。
- 10074 上 TIV-01–18/23/24：**0 项**（设备掉线）；冷启/切台/30min 长播：**未执行**（设备掉线）。
- 音频真实会话采集：**未完成**（设备掉线）。

## 7. 结论与下一步

- 10074 pair 未通过设备验收：2 项 instrument 失败（含此前 10073 通过的 UA/Referer 用例）+ PCM 音频诊断空转。
- 需 developer：(a) 修 `AudioDiagnosticTest.kt:96` server.url 主线程缺陷；(b) 修 `NativeBoundaryTest.kt:216-218` 请求过滤确定性；(c) 重新 CI 交付新 pair（新 SHA/签名），交回本角色在设备恢复后复验全部 9 项。
- 需现场恢复 192.168.2.245 网络后，再完成 TIV/冷启/切台/30min 长播/音频同会话对照。
- 不宣称任何有声或性能提升结论。
