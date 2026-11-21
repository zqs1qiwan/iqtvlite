# CI10073 TV 复验记录（进行中）

任务t15，acceptance，2026-11-19。此文件逐步记录真实执行，不代表全量通过。用户“允许各种测试直到交付”的既有授权覆盖独立validation替换与配置；原tv.iqtvlite.app及com.cjlhll.iptv数据保持。

## 产物与安装

[CI run44426383315](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44426383315)，source82e56d2c96f409edd197d838605ae34178489d55，[成对artifact14485315735](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44426383315/artifacts/14485315735)。主validation10073/2.2.0-tivimini-82e56d2c-validation，min24 target35、v7a；主SHA956c9366541c1bbf9bba9a9834e8f7bc2d8f892072c0f8ba7c7dc95064da6509，test SHAb3e9a827c1eb627da6ebc0dd67fde2bc57e0a4bd048508b1d76e5f1da422857a；两者签名00b0a57d7195cb432bb7d718afe8aaa4295f9629c75f9c4fb8673c4b5c804b98。主14142183B，比13140066B旧universal debug增加7.63%，预算内，并非体积下降。

独立aapt/apksigner/Get-FileHash核验，设备安装后sha256sum再次绑定同文件。新证书不同，已归档旧10071 validation及test沙箱tar并验证tar目录；旧沙箱只有cache/code_cache/dxmaker，无用户源配置。随后仅卸载这两个独立验证包并装新pair，没有卸载/clear生产包。

证据：[主hash](evidence/2026-11-19-ci-10073/validation-hash.txt)、[test hash](evidence/2026-11-19-ci-10073/androidTest-hash.txt)、[主签名](evidence/2026-11-19-ci-10073/validation-signature.txt)、[test签名](evidence/2026-11-19-ci-10073/androidTest-signature.txt)、[安装后hash](evidence/2026-11-19-ci-10073/installed-sha.txt)、[旧备份目录](evidence/2026-11-19-ci-10073/backup-list.txt)、[包](evidence/2026-11-19-ci-10073/installed-package.txt)、[runner](evidence/2026-11-19-ci-10073/installed-runner.txt)。

## 仪器7项

真实执行整类NativeBoundaryTest，3.887s，OK(7 tests)，每项start STATUS1/end STATUS0，最终INSTRUMENTATION_CODE -1。不是仅adb退出码判断；本次runner/test-storage成功初始化，旧TV-01缺类不再复现。[完整原始输出](evidence/2026-11-19-ci-10073/instrumentation-all.txt)。

| 方法 | 实际结果 |
|---|---|
| media3CatchupOwnerStaysBForFirstRetryAndHome | 通过 |
| cancelledWaiterDoesNotPoisonSharedRefresh | 通过 |
| lanServersUseDifferentPortsAndStopCleanly | 通过 |
| media3ErrorCarriesProducingSessionRatherThanLaterCurrentChannel | 通过 |
| media3RequestUsesChannelUaAndReferer | 通过 |
| productionM3uAdapterRetainsNativeFieldsAndIdentity | 通过 |
| encodedAndBareGzipAreDecodedExactlyOnce | 通过 |

fixture通过不代替实际Activity导航、首帧/音频和性能验收。SR真实UI结果后续单列。

## 已执行的公开源与初步绝对样本

首次启动配置，直播字段初焦；LAN http://192.168.2.245:28081/api/ping及/api/push真实200，提交公开源https://iptv.laobaitv.net与https://epg.laobaitv.net进入PlayerActivity。此为本机HTTP表单等价提交，没有手机摄像头真实扫码证据。[配置焦点](evidence/2026-11-19-ci-10073/config-labels.json)、[LAN提交](evidence/2026-11-19-ci-10073/lan-submit.txt)。字段OK/Back操作已执行，IME是否实际显示尚待补证，不从调用代码推断。

信息条CCTV1/CCTV2为1920×1080 AVC，CCTV2 session c7570213555088108263-l2460503528-s2在08:23:57.724真实first_frame，prepare→首帧609ms，随后READY。[信息条](evidence/2026-11-19-ci-10073/channel2-info-labels.json)、[会话](evidence/2026-11-19-ci-10073/metrics-90s.txt)。开启log.tag.IQTV-METRICS=DEBUG后取证，初次CCTV1decoder早于启用；后续controlled fixture同会话日志明确OMX.goke.video.decoder.avc。不能把READY替代首帧。

90秒不切台，实际9区间总93.6185s，app CPU单核100%口径p50 48.38%、p95最近秩76.04%；PSS80999/81331/73629KiB，一Activity，SwapPss0。AT_CLKTCK由/proc/self/auxv key17确认100（getconf命令设备不存在，保留失败，不凭假设给结论）。[原始stat](evidence/2026-11-19-ci-10073/steady-90s-stat.jsonl)、[计算](evidence/2026-11-19-ci-10073/steady-summary.json)、[auxv](evidence/2026-11-19-ci-10073/auxv-values.json)。该窗口无新增rebuffer/drop事件；缺少周期progress，不能仅凭无事件声称全程运动画面/声音正常。后续需足量稳态/长播。

音频限制：audio_flinger有PID17809 notification client，但0 Tracks/standby；STREAM_MUSIC为-inf。这既不证明有声也不能单独证明产品无声，已交developer只读诊断。所取2MiB TS是公开列表index0 CCTV1，而非CCTV2；其PMT AVC0x1b+MPEGaudio0x03不能代替CCTV2音轨事实。[audio](evidence/2026-11-19-ci-10073/steady-audio.txt)、[fixture获取](evidence/2026-11-19-ci-10073/fixture-acquisition.json)、[PMT](evidence/2026-11-19-ci-10073/fixture-stream-types.json)。/system/etc/media_codecs.xml不存在，对应空文件不作能力证据。

## UI取证限制与现有观察

所有操作用adb Dpad/OK/Back/Home/数字，不用触屏替代焦点。XML捕获偶发UiTestAutomationBridge null root，已记录失败并用独立remote文件重取，避免沿用旧XML；早期programs/dates紧邻快照存在时序歧义，不单独据其判断失败。截图无法由当前模型读取，色彩/文字观感、运动画面、实际声音无人工确认不宣称通过。

已观察频道双列、初开CCTV2焦点，浏览CCTV3后节目列更新；频道OK后抽屉和行焦点保持；日期列Back一次关闭；设置根初焦源配置。[初开](evidence/2026-11-19-ci-10073/drawer-initial-labels.json)、[浏览](evidence/2026-11-19-ci-10073/drawer-browse-labels.json)、[OK保持](evidence/2026-11-19-ci-10073/drawer-selected-labels.json)、[日期OK](evidence/2026-11-19-ci-10073/date-ok-labels.json)、[Back关闭](evidence/2026-11-19-ci-10073/date-back-labels.json)、[设置](evidence/2026-11-19-ci-10073/settings-first-labels.json)。

## Controlled SR路径（进行中）

本机127.0.0.1:28888 HTTP fixture，经adb reverse映射到独立validation；A/B/C及Fault503、Refused连接拒绝，独立UA/Referer，动态XMLTV历史/当前/未来节目。服务器记录每个真实HTTP路径/头/时间。静态TS2MiB是短样本，ENDED是样本结束，不代表长播故障。fixture只用作控制错误、归属及UI，不能代替真实直播长播性能。

已真实执行：直播A，抽屉浏览B历史，节目OK启动B archive并关抽屉，503后prepare重试；Home再回；Back返回B-live。server记录b-archive请求UA-B及https://b.example/，无A头污染；Back信息条2.Fixture B且请求/b.ts携带B头。Home快速往返与完整驻留分开记录。[历史焦点](evidence/2026-11-19-ci-10073/fixture-b-history-labels.json)、[请求头次数](evidence/2026-11-19-ci-10073/fixture-request-counts.json)、[实际503](evidence/2026-11-19-ci-10073/catchup-error-logcat.txt)、[Back直播](evidence/2026-11-19-ci-10073/catchup-back-live-labels.json)。

SR03实际Home驻留当贝（XML/Activity STOPPED取证），仅修改validation所指缓存为missingB有效M3U，返回后catchup仍为B，Back后显示Fixture B无错误A编号/节目且厂商AVC首帧重新出现。此为实际Activity缓存重新装载路径，不冒称网络后台刷新路径已验证。[Home窗口](evidence/2026-11-19-ci-10073/sr03-home-launcher-window.txt)、[缺B直播](evidence/2026-11-19-ci-10073/sr03-missing-b-live-labels.json)、[会话](evidence/2026-11-19-ci-10073/sr03-metrics.txt)。B重新入表测试第一次host把Invoke-WebRequest byte[]误转数字字符串，已保留invalid fixture并纠正UTF8读取；随后数字3在missingB表实际选中Fault，污染了corrected系列前提，故invalid/corrected系列均不计回表结论。最终从确定A直播/B历史与有效M3U重新执行clean全链，结果见下。

### Clean真实路径结果

| 场景 | 实际结果与证据 |
|---|---|
| 缺B Back返回直播 | 显示Fixture B，无A号码或A节目，[clean-unlisted](evidence/2026-11-19-ci-10073/sr03-clean-unlisted-labels.json) |
| Home后B同稳定ID以新/b-new.ts回表 | 显示2.Fixture B及B Current，保持B，[clean-relisted](evidence/2026-11-19-ci-10073/sr03-clean-relisted-labels.json)；cache注入路径，不冒充后台HTTP事件 |
| 显式选择C | 3.Fixture C与C Current及视频格式，[clean-explicit-c](evidence/2026-11-19-ci-10073/sr03-clean-explicit-c-labels.json) |
| B prepare前503 | 真实InvalidResponseCodeException503后SourceRefresh成功重新取1493B列表，界面2.Fixture B；随后显式C保持3.Fixture C |
| prepare前连接拒绝 | 真实ConnectException到127.0.0.1:28889，SourceRefresh成功；界面5.Fixture Refused，随后C保持C。不是已有timeline解析坏媒体的替代样本 |

[early503与连接拒绝](evidence/2026-11-19-ci-10073/sr01-error-summary.txt)、[会话时间与ID](evidence/2026-11-19-ci-10073/sr01-session-summary.txt)、[B错误](evidence/2026-11-19-ci-10073/sr01-b-503-labels.json)、[错误后C](evidence/2026-11-19-ci-10073/sr01-c-after-b-error-labels.json)、[连接拒绝](evidence/2026-11-19-ci-10073/sr01-connection-refused-labels.json)、[连接拒绝后C](evidence/2026-11-19-ci-10073/sr01-c-after-refused-labels.json)。这些证明实际失败到刷新/后续选择路径，旧A排队事件的具体可控延迟交付仍仅由7项中真实Media3 fixture覆盖，不声称UI已逐条强制调度旧事件。

IME补证：从源配置字段OK进入时，[input_method](evidence/2026-11-19-ci-10073/ime-runtime.txt)明确mShowRequested=true、mShowExplicitlyRequested=true、mInputShown=true，具有Compose输入连接。Back后继续LAN推源成功。源字段仍可编辑，没有清理原用户配置。

## CI10074（t19 音频诊断 pair）复验 — 失败/阻断（新增）

2026-11-19，t15 attempt 3。按 backup 回退方案（validation-10073-backup.tar + test-10073-backup.tar，含 shared_prefs）仅替换独立 validation 沙箱到 CI10074（source 77fe7f61，run 44434416536，app SHA `242048fe…` 14,158,563B / test SHA `e98f157c…` 502,075B，签名 bcb7ffe3…，min24 target35 双ABI；安装后 base.apk 复算一致，runner 正常）。**原 tv.iqtvlite.app 与 com.cjlhll.iptv 及数据全程保留，未卸载/未 clear。**

真实整包 runner 实跑 9 项：**7 通过 / 2 失败**（原始 [instrumentation-all-10074](evidence/2026-11-19-ci-10074/instrumentation-all-10074.txt)）。
- 失败1 `AudioDiagnosticTest.pcmWaveReachesAudioTrackWithoutClaimingAudibility`：`NetworkOnMainThreadException`，`AudioDiagnosticTest.kt:96` 在 runOnMainSync 内调用 `server.url()` 触发主线程反向 DNS —— 测试缺陷，PCM/AudioTrack 未真正执行。
- 失败2 `NativeBoundaryTest.media3RequestUsesChannelUaAndReferer`：`expected</[default].ts> but was</[live].ts>`（NativeBoundaryTest.kt:218），10073 曾通过；首请求 UA/Referer 注入断言已过，失败为同 player 二次 prepare 时首通道 `/live.ts` 重试被 takeRequest 误取 —— 夹具非确定性/待修确定化。
- `api28AudioMimeCapabilities` 通过（AAC 正对照>0），但 `IQTV-AUDIO-PROBE`（含 audio/mpeg-L2 候选）logcat 在设备掉线前未取回；音频分支仍**未判定**。

随后测试机 192.168.2.245 掉线（`adb offline`，本机 ping Destination host unreachable/超时，重连失败），导致 10074 上 TIV-01–18/23/24、冷启10/切台30/30min 长播、音频同会话对照**均未执行**。详细失败/复现/修复要求见 [CI_10074_DEVICE_VERIFICATION.md](CI_10074_DEVICE_VERIFICATION.md)。结论：CI10074 未通过，待 developer 修 2 项测试缺陷并重新交付 pair、现场恢复设备后复验全部 9 项与全量交互/性能。

## CI10075 复验 — 设备在线，9/9 通过（t22，2026-11-19）

**产物**：CI10075（source b4455ac4，run 44515714680）主 app SHA `6446130c…7bee` 14,158,563B / test `a1c475ce…768d` 502,955B，签名 `4ae761a0…`，tv.iqtvlite.app.validation 10075 / 2.2.0-tivimini-b4455ac4-validation，min24 target35 双ABI。本地+安装后 base.apk hash 均 match；backup 10074 沙箱后仅替换 validation 两包，原 tv.iqtvlite.app/com.cjlhll.iptv 数据保留。

**9 项 instrument 全部通过** `OK (9 tests)`：含原 TV-AUDIO-01 `pcmWaveReachesAudioTrack…`（PCM 真执行、AudioTrack advancing）与 TV-UA-01 `media3RequestUsesChannelUaAndReferer`（/default.ts + 默认UA VLC + Referer=null，未放宽）。原始 [instrumentation-all-10075](evidence/2026-11-19-ci-10075/instrumentation-all-10075.txt)。

**音频关键证据**：IQTV-AUDIO-PROBE `audio/mpeg-L2 candidates=0`、`audio/mpeg(MP3)`=1、`audio/mp4a-latm(AAC)`=1。CCTV2 同会话 `audio_track mime=audio/mpeg-L2 supported=false selected=false renderer_format_support=1 48k/2ch`、groups=1 selected 无、**0 audio_decoder/track_initialized**；焦点 GAIN；HDMI STREAM_MUSIC `400(hdmi):0`；AudioFlinger standby 0 Tracks。**判定：CCTV2 为 MPEG‑1 Layer II，平台无 audio/mpeg-L2 解码器（未打包 FFmpeg/MP2）→ unsupported 未选、无解码/无 AudioTrack → 无声；非焦点/静音/sink/无音轨缺陷，PCM 通路已证可用。不宣称有声。** 详见 [CI_10075_DEVICE_VERIFICATION.md](CI_10075_DEVICE_VERIFICATION.md)。

**TIV（10075 实测）**：双列抽屉、分组列(LEFT)、频道OK保抽屉、BACK一次关闭、日期列(11-18..22)、信息条(2.CCTV-2/节目/1920x1080/avc)、DPAD上下换台 均通过；TIV-23 数字112 未清晰验证(部分)。其余 TIV 本轮未在10075逐条重跑(10073有证据，业务码同10074/10075)。

**性能(同机 C 绝对，无匹配 B 不出提升)**：冷启10 TotalTime p50 2292/p95 2320ms(真冷)；切台30 foregroundOurs29/30 prepare30 first_frame30，TTFF p50 1079/p95 1491/max1695ms；30min 长播 1817.9s(30样本,fgOurs30/30) app CPU p50 0.89%/p95 1.31%、media.codec p50 0.15%、PSS p50 72816KiB 稳定、rebuffers=0/dropped=0（与10073 90s旧测量方法不同不可比）。

设备侧：期间 com.baiguotv.tv.debug 曾抢前台致一次无效采集，改用导出 SplashActivity 复位+逐轮前台校验后有效；PlayerActivity 未导出属测试脚本约束非产品缺陷。
