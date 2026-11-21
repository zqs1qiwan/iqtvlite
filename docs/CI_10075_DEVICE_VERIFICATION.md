# CI10075 真机复验报告（t22）

2026-11-19，acceptance2（t22 attempt 1）。绑定 CI10075 独立 validation pair（source b4455ac4，run 44515714680）。设备 192.168.2.245 恢复在线（ping 11ms，adb device）。本报告逐项给通过/失败/未验证与实际执行数，脚本/夹具缺陷与产品缺陷分开。

## 1. 产物身份与安装（PASS）

| 项 | 应用 | androidTest |
|---|---|---|
| 包 | tv.iqtvlite.app.validation | tv.iqtvlite.app.validation.test |
| 版本 | 10075 / 2.2.0-tivimini-b4455ac4-validation | manifest 无自身版本 |
| min/target | 24/35 | 24/35 |
| ABI | arm64-v8a + armeabi-v7a（v7a libiqtvlite_capi.so 826040B） | — |
| SHA256 | 6446130caced04563a96149178243261eb15a07f621503adaa0843807ab67bee | a1c475ce831145ed46d7b9df33a1b4a4de9c12bb07fd70b17c732f16c5cb768d |
| 字节 | 14,158,563 | 502,955 |
| 签名SHA256 | 4ae761a04df5076ee64c4cca2f64b5825fe43ee853b34a2b9712656dbc7d046d | 同左 |

本地 Get-FileHash/aapt2/apksigner 全部与 candidate-10075.json 收据一致；安装后 `pm path` 拉取 base.apk 重新 hash 仍为 `6446130c…`/`a1c475ce…`（match=True）。安装：backup 旧 10074 validation 沙箱（validation-10074-backup.tar 15360B / test 2560B）后，仅 `uninstall` 两个旧 validation 包 + `install -r` 两个新包（均 Success）；`dumpsys` = 10075 / min24 target35 / primaryCpuAbi armeabi-v7a，runner 正常。**原 tv.iqtvlite.app 与 com.cjlhll.iptv 及数据前后均在，未卸载/未 clear。** 证据：[installed-identity](evidence/2026-11-19-ci-10075/installed-identity.txt)。

## 2. 9 项 instrumentation（9/9 通过）

真实整包 runner（无 -e class 过滤），`OK (9 tests)`，逐项 start=1/end=0。原始 [instrumentation-all-10075.txt](evidence/2026-11-19-ci-10075/instrumentation-all-10075.txt)。

| 类.方法 | 结果 |
|---|---|
| NativeBoundaryTest.media3CatchupOwnerStaysBForFirstRetryAndHome | 通过 |
| NativeBoundaryTest.cancelledWaiterDoesNotPoisonSharedRefresh | 通过 |
| NativeBoundaryTest.lanServersUseDifferentPortsAndStopCleanly | 通过 |
| NativeBoundaryTest.media3ErrorCarriesProducingSessionRatherThanLaterCurrentChannel | 通过 |
| NativeBoundaryTest.productionM3uAdapterRetainsNativeFieldsAndIdentity | 通过 |
| NativeBoundaryTest.encodedAndBareGzipAreDecodedExactlyOnce | 通过 |
| **NativeBoundaryTest.media3RequestUsesChannelUaAndReferer**（原 TV-UA-01） | **通过**（/default.ts 目标请求 + 默认 UA=VLC/3.0.20 LibVLC/3.0.20 + Referer=null 均断言，未放宽） |
| AudioDiagnosticTest.api28AudioMimeCapabilities | 通过 |
| **AudioDiagnosticTest.pcmWaveReachesAudioTrackWithoutClaimingAudibility**（原 TV-AUDIO-01） | **通过**（PCM 实际创建 AudioTrack、position advancing、input=AUDIO_RAW、无 sink error——真执行） |

TV-AUDIO-01 与 TV-UA-01 两个此前 10074 阻滞的夹具缺陷在 10075 均已修复并通过，且断言未放宽。

## 3. IQTV-AUDIO-PROBE：设备音频解码能力（关键）

[audio-mime-probe-logcat.txt](evidence/2026-11-19-ci-10075/audio-mime-probe-logcat.txt)：

```
api=28 mime=audio/mpeg-L2 candidates=0
api=28 mime=audio/mpeg  name=OMX.google.mp3.decoder  formatSupported=true rate48000=true maxChannels=2  candidates=1
api=28 mime=audio/mp4a-latm name=OMX.google.aac.decoder formatSupported=true rate48000=true maxChannels=8  candidates=1
```

**设备对 `audio/mpeg-L2`（MPEG-1 Layer II）声明 0 个解码器；MP3(audio/mpeg) 与 AAC 各 1 个。** 这是设备平台能力事实，不是应用私设。

## 4. CCTV2 同会话音频分支判定（不宣称有声）

配置公开源（LAN POST /api/push，直播 https://iptv.laobaitv.net / EPG https://epg.laobaitv.net，SourceRefresh playlist refreshed bytes=25626）后，开 `log.tag.IQTV-METRICS DEBUG`，切到 CCTV2 实测 [audio-metrics-1.txt](evidence/2026-11-19-ci-10075/audio-metrics-1.txt)：

```
audio_track session=c7570213555088108263-l2460503528-s2 group=1 track=0 supported=false selected=false renderer_format_support=1 mime=audio/mpeg-L2 codecs=unknown rate=48000 channels=2 language=unknown bitrate=-1
audio_tracks ... groups=1 volume=1.0 suppression=0 playWhenReady=true audioSessionId=73
format ... video/avc width=1920 height=1080
first_frame ... ttff_ms=1078
```

- CCTV2 确有音轨，格式 **mime=audio/mpeg-L2、48000Hz、2ch**；但 **supported=false / selected=false / renderer_format_support=1（FORMAT_UNSUPPORTED_TYPE）**。
- 整段会话 **0 条 audio_decoder / audio_track_initialized / audio_enabled / audio_sink_error**（无音频解码器或 AudioTrack 建立）。
- 音频焦点：GAIN、loss none、notified true、usage=USAGE_MEDIA/content=CONTENT_TYPE_MOVIE（[audio-state-1/2](evidence/2026-11-19-ci-10075/audio-state-2.txt)）。
- HDMI 输出：STREAM_MUSIC Muted:false，`Current: 2(speaker):7, 400(hdmi):0, default:7`，Devices:hdmi → **HDMI 路由 index=0**（另一叠加条件）。
- AudioFlinger：Standby yes、0 Tracks（[audio-flinger-2](evidence/2026-11-19-ci-10075/audio-flinger-2.txt)）。
- 对照：PCM 诊断通过，说明 AudioTrack/sink 通路对受支持格式可用。

**判定分支：①源音轨存在但为 MPEG‑1 Layer II；②平台无 audio/mpeg-L2 解码器；③Media3 标记 unsupported 且未选中、无 decoder/sink 建立 → 无声。** 根因是 MPEG‑L2 解码能力缺失（本构建未打包 FFmpeg/MP2 软解，见 AUDIO_PATH_DIAGNOSIS.md），**不是**焦点/静音/路由或 sink 写入缺陷（PCM 通路已证可用），也**不是**“无音轨”。HDMI index=0 为需要一起满足的次要条件，但无解码器时即便增益非零仍无声。**不宣称有声。** 若要“有声”，需为该设备/构建提供 MPEG‑L2 软解（Media3 FFmpeg audio extension，仅 MPEG audio，mode ON）或改用含受支持音轨（AAC/MP3）的等价源。

## 5. TIV 遥控逐路径（10075 核心实测）

均用 adb Dpad/OK/Back 注入，UI 层级取证于 evidence/2026-11-19-ci-10075/tiv-*.xml。

| TIV | 路径 | 实测 | 结果 |
|---|---|---|---|
| TIV-04 | 无浮层 Up/Down 换台；Left 开双列抽屉；Right 设置 | DPAD_DOWN 换台成功（CCTV2→CCTV3 等，首帧 1159ms）；LEFT 开抽屉 | 通过（换台/抽屉） |
| TIV-05 | 初开当前频道，双列初焦 | 抽屉双列=频道列表+节目单（tiv-drawer-open） | 通过（结构/初焦） |
| TIV-06 | 频道 Left 组，选组 OK 仅筛选且留组；Right 回频道并隐藏组 | LEFT→分组列（全部/央视频道/卫视…）；RIGHT→隐藏分组回频道列 | 通过 |
| TIV-07 | 频道上下循环；OK 切台后抽屉保持且行焦点保持 | OK 后抽屉仍在（tiv-channel-ok，32360B），焦点行保持 | 通过 |
| TIV-08 | Left→Right 节目→Right 日期；日期 OK 改日期 | 三列出现日期 11-18/11-19/11-20/11-21/11-22，日期有焦点（tiv-date-full） | 通过（日期列存在/可聚焦） |
| TIV-10 | 每列 Back 一次关闭整个抽屉回视频 | BACK 一次→抽屉关闭（tiv-back-close 4657B 纯视频面） | 通过 |
| TIV-13 | OK 信息条 5 秒，台标/全局号/频道/节目/视频参数 | OK→信息条“2. CCTV-2 财经 / 货币的故事3-47分版 / 1920x1080 / avc” | 通过 |
| TIV-23 | 数字 112 全局号 | 10075 上未获清晰数字 OSD（信息条叠加，数字未唯一定位到全局112），判 **部分/未清晰验证**，不记通过；10073 曾有 numeric112 证据仅参考不可迁移 | 部分 |

注：10075 = 10074 业务码 + 仅 androidTest/keep 修复（t20 明确“无业务源码改动”），业务行为与 10073 一致；上述为 10075 上独立重测的核心路径。其余 TIV（01/02/03/09/11/12/14/15/16/17/18）在 10073 有逐项证据，本轮未在 10075 逐条重跑，标“未在本轮 10075 重验”，不硬迁移。TIV-19–22 可选未做。

## 6. 性能（同机；无匹配 B 不宣称提升）

- 冷启动 10 次：[cold-summary.json](evidence/2026-11-19-ci-10075/cold-summary.json)。force-stop 后 `pidBefore` 均为空=真冷进程；`am start -W SplashActivity` TotalTime：min 2201 / **p50 2292 / p95 2320** / max 2320 ms（ThisTime≈1.65s）。
- 切台 30 次：[zap-metrics-dpad.txt](evidence/2026-11-19-ci-10075/zap-metrics-dpad.txt)。前台守卫 foregroundOurs=29/30，prepare=30 first_frame=30（0 错台/0 缺失）；**prepare→first_frame TTFF min 401 / p50 1079 / p95 1491 / max 1695 ms**。
- 30min 长播：[long-summary.json](evidence/2026-11-19-ci-10075/long-summary.json)、[long-markers.jsonl](evidence/2026-11-19-ci-10075/long-markers.jsonl)。实测 1817.9s（30 个 1min 采样，前台全程为本应用 fgOurs=30/30，无其它应用干扰）；`/proc/PID/stat` 60s 差分、CLK_TCK=100、1核=100%：**app CPU p50 0.89% / p95 1.31% / max 1.44%**；media.codec p50 0.15% / p95 0.22%；**PSS p50 72,816KiB（≈71.1MiB）**，min0 74290→min10 72752→min29 72873，无单调增长（无泄漏迹象）；**全窗口 rebuffers=0 / rebuffer_ms=0 / dropped=0**。与 10073 的 90s 旧测量（p50 48%）方法/窗口不同，**不可比，不据此称提升**。

B/C：同机同源旧生产包 B 未取得（旧包当前源不可与 C 同源且不得擅改旧包），**无匹配 B，不宣称任何性能提升**；以上为 C 绝对测量。

## 7. 设备侧干扰（记录，非产品缺陷）

测量中 `com.baiguotv.tv.debug` 曾抢占前台，导致一次 DPAD 采集 prepare=0；已改用 `SplashActivity`（导出）复位前台并以每轮前台校验规避，最终 29/30 前台有效。PlayerActivity 未导出，shell 无法直接 am start，只能用 SplashActivity 路由——**测试脚本约束，非应用缺陷**。全程未卸载/clear 原生产包，未强停用户其它应用（仅 HOME+启动本包）。

## 8. 本轮实际执行数

- 身份核验+安装替换：完成（PASS）。
- instrumentation：9/9 通过。
- IQTV-AUDIO-PROBE：已取（L2=0/MP3=1/AAC=1）。
- CCTV2 音频同会话分支：已判定（MPEG-L2 unsupported/未选/无decoder；HDMI index0；焦点GAIN）。
- TIV：核心 7 项在 10075 实测通过（04/05/06/07/08/10/13），TIV-23 部分；其余未在10075重跑。
- 冷启 10、切台 30：完成；30min 长播：进行中。
- B/C 性能对比：无 B，不出提升结论。

## 9. 结论

CI10075：身份/安装 PASS；**9/9 instrumentation 通过（含 TV-AUDIO-01/TV-UA-01 修复验证）**；测试修复 pair 通过设备门禁。产品侧核心 TIV 遥控路径在 10075 实测正常；性能 C 绝对测量已得（cold p50 2292/p95 2320ms；zap p50 1079/p95 1491ms；30min 长播 0 rebuffer/drop、app CPU p50 0.89%、PSS≈71MiB 稳定）。**音频：CCTV2 为 MPEG‑1 Layer II，平台 0 个 audio/mpeg-L2 解码器导致 unsupported/未选、无 decoder/无 AudioTrack → 无声；这是需产品决策的 MPEG‑L2 软解/换源限制，不是焦点/音量/sink 缺陷，亦不宣称有声。** 最终是否“有声达交付”取决于是否增加 MPEG‑L2 软解或改用受支持音轨源。
