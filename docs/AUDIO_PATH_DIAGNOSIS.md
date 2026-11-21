# CI10073 直播音轨与 MPEG audio 解码链诊断

2026-11-19，Asia/Hong_Kong；developer/t18。仅分析现有源码、已保存设备证据和固定版本 Media3 源码，未操作 TV、未调音量/焦点、未修改应用代码/依赖、未跑构建或 CI。acceptance 继续独占设备。本结论不能替代现场听音。

**目前不能断言 CCTV2 有声或无声，也不能把 MP2 不支持写成已经证实的根因。** 已确认视频指标与音频输出证据不同；已确认 CCTV1 样本含 MPEG-1 Layer II，但 CCTV2 的实际音轨格式、supported/selected、解码器和 AudioTrack 写入尚缺完整同会话证据。优先补音轨与设备 MIME 能力证据，同时核实快照中媒体音量为 `-inf` 的独立线索。

## 已有证据及边界

| 证据 | 可以证明 | 不能证明 |
|---|---|---|
| [metrics-90s](evidence/2026-11-19-ci-10073/metrics-90s.txt#L4)，PID17809，CCTV2 session `c7570213555088108263-l2460503528-s2` | 08:23:57 prepare，1920×1080 AVC，真实视频 first_frame609ms，随后 STATE_READY | 没有 audio format/selection/decoder/sink 回调；READY 和视频首帧不等于音频已输出 |
| [约93.62秒采样汇总](evidence/2026-11-19-ci-10073/steady-summary.json)及上述指标 | acceptance 的该窗口有 CPU/PSS 与无已记录 rebuffer/drop 指标 | 不构成同长度的音频输出或声学测量 |
| [AudioFlinger 快照](evidence/2026-11-19-ci-10073/steady-audio.txt#L76) | PID17809 仅列在 Notification Clients；主 HDMI mixer standby=yes、0 Tracks、无 session refs，snapshot 中该 output frames/writes 为0 | Notification Client 只是注册了音频系统通知，不是已建立 PCM Track；单次快照不能代表整个94秒，也不能定位没建 Track 的原因 |
| [快照音量/路由](evidence/2026-11-19-ci-10073/steady-audio.txt#L100) | output device HDMI；line123 的 stream3（STREAM_MUSIC）增益 `-inf`，提示该 mixer 对媒体流增益为零 | 不能单独认定具体用户静音键/音量索引，也不能证明本节目已创建/选中了音轨。静音通常仍可建立 AudioTrack，因此它本身不解释所有0 Tracks现象 |
| [启动日志](evidence/2026-11-19-ci-10073/after-lan-logcat.txt#L2512) | 08:23:02.519，UID10058/PID17809 为 validation 包发起 requestAudioFocus；日志含 Media3/1.11.1 与 OMX.goke.video.decoder.avc | 请求发生在 s2 之前，未给出返回值与完整焦点变化；不能证明 s2 持续获焦点。未见 audio 初始化行也不能单独证明未解码，现有自定义 Metrics 根本不记录该事件 |
| [fixture 获取身份](evidence/2026-11-19-ci-10073/fixture-acquisition.json)与[PMT](evidence/2026-11-19-ci-10073/fixture-stream-types.json) | playlist_index0，即 CCTV1，2MiB；PMT video0x1b/PID4113 + MPEG audio0x03/PID4352 | 不是 CCTV2，不能借此认定 s2 采用相同音轨；仅 PMT0x03 也不足以区分 MPEG audio Layer I/II/III 或证明 ES 可解码 |
| [device-media-codecs](evidence/2026-11-19-ci-10073/device-media-codecs.xml) | 文件为空；acceptance 明确说明读取 `/system/etc/media_codecs.xml` 失败、路径不存在 | 不代表设备没音频 codec，更不是有效能力表；后续应由 acceptance 从实际 vendor/include 路径或 API 留证 |

对既有 [CCTV1 TS 样本](evidence/2026-11-19-ci-10073/fixture.ts)进行了只读内存分析：SHA256 `395fcc991be8576fcecdf16a38dbef51364ab348550d4eade44da7fe8bc6d77a`；按188字节 TS、PID4352、adaptation/PUSI/PES header 提取，400个含音频 payload 的包；找到79个后继 frame header 校验一致的 MPEG-1 Layer II header。首个 `0xfffdc404`：48kHz、双声道、256kbit/s、每帧768字节/1152 samples。这比 PMT 更具体，仍仅识别 CCTV1 比特流，未解码 PCM、未验证全部 CRC/PTS，也未证明设备支持或可听。未把这个样本冒充 CCTV2。

## 当前生产源码链

[PlayerActivity 初始化](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L423)：`DefaultRenderersFactory` + decoder fallback + **视频**预热；没有自定义 AudioRenderer、MediaCodecSelector 或 AudioSink，没有启用 extension renderer。ExoPlayer 使用默认轨道选择；检索现有生产源码未见 TRACK_TYPE_AUDIO disabled/override、player.volume=0 或手动静音设置。`setAudioAttributes(USAGE_MEDIA, CONTENT_TYPE_MOVIE, true)` 启用 Media3 焦点处理；它不是保证系统音量非零或焦点获准的开关。

[StreamPlayback](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/StreamPlayback.kt#L48)只给 TS 提取器补 CEA608 和 timestamp search，未过滤 MPEG audio，也未把音频改由 Rust 解码。[Metrics](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/StreamPlayback.kt#L97)只实现 video decoder/input format/first frame/drop/state；[onTracksChanged](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L492)仅刷新 videoFormat。因而现在日志缺 audio 行是明确的可观测性缺口。

[依赖](../../iqtvlite-integration/android/app/build.gradle.kts#L72)是 Media3 1.11.1 的 core ExoPlayer/TS相关模块，没有 decoder_ffmpeg、第三方 MP2 解码库或 libmpv。`setEnableDecoderFallback(true)` 只在现有 MediaCodec 候选初始化失败时尝试低优先级 codec；不会自动下载/附带软解库，也不把 video 预热扩展为音频解码能力。

Media3 **固定1.11.1** 源码核对结果：

- [DefaultRenderersFactory](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultRenderersFactory.java)：默认 extension mode OFF；正常创建 MediaCodecAudioRenderer + DefaultAudioSink，只有显式开启且实际打包扩展时才加入 FFmpeg renderer。OFF不代表关闭音频。
- [DefaultTsPayloadReaderFactory](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/ts/DefaultTsPayloadReaderFactory.java)：0x03/0x04 创建 MpegAudioReader。容器提取支持不等于设备解码支持。
- [MpegAudioUtil](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/MpegAudioUtil.java)：按帧 header 区分 layer，Layer II 对应 `audio/mpeg-L2`，Layer III 才是通常的 `audio/mpeg`。不能仅查 MP3 codec 就声称 MP2 已支持。
- [MediaCodecUtil](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/mediacodec/MediaCodecUtil.java)：按设备声明 MIME 查询候选，alternative MIME 分支没有自动把 `audio/mpeg-L2` 改为 `audio/mpeg`。某 vendor 的 MP3 实现或许能解 Layer II，须以实际 format/codec probe/解码验证；不能全局伪造 MIME 绕过选择器。

## 与两个上游基线的差异

1. 原目标 `90ae4cdd` 的 [PlaybackService 历史路径](../../iqtvlite-integration/android/app/src/main/kotlin/tv/iqtvlite/app/PlaybackService.kt)（需用 `git show 90ae4cdd:android/app/src/main/kotlin/tv/iqtvlite/app/PlaybackService.kt` 读取，当前 main sourceSet 不使用此树）本来就是 Media3 1.11.1、DefaultRenderersFactory、fallback、视频预热和相同 media/movie/focus=true。该 commit 的 Gradle 无 FFmpeg/MP2 extension。原来的“passthrough”偏好只记录日志/AudioAttributes，未建立通用 MP2 解码器。不存在已有 MPEG-L2 软件支持被本次删掉的源码证据；原包有声仍需独立实际验证，不能从这些配置代入。
2. 目标原来的 MediaSessionService 被现版本 Activity 生命周期单播放器代替，减少后台重复实例；这是焦点/后台行为差异，值得结合焦点日志检查，但不改变 MPEG-L2 decoder 的存在性。当前已观察到焦点请求，不宜断言没有焦点机制。
3. tivimini 固定8c93e1b的 [PlayerActivity 快照](evidence/tivimini-upstream-8c93e1b/PlayerActivity.kt#L387)也是默认 renderer + fallback，无 FFmpeg；它使用较大 buffer、无显式 media/movie/focus=true 配置。迁移保留了目标低缓冲/视频预热和显式焦点设置；没有从 tivimini 搬入特殊 MPEG audio 解码器。UI复刻不意味着音频 codec 被替换。

## 条件判别与下一轮复现

| 同一媒体 session 需要的证据 | 判别/下一步 |
|---|---|
| currentTracks 没有 audio，且同流 PMT/PES 没有有效音频 | 考察源本身/变体/取样时段；不先加 decoder |
| 有 MPEG-L2 Format，但 supported=NO_UNSUPPORTED_TYPE/SUBTYPE、未选中，设备 Media3候选为空 | 才能把平台 MPEG-L2 能力缺口作为有证据的解释；视频可正常独立播放 |
| 有受支持音轨，但未选中 | 查实际 TrackSelectionParameters/override/语言/禁用状态，不能靠 forced unsupported selection 修复 codec 缺失 |
| selected=true、audio decoder已初始化，但无sink/config/PCM进展或有AudioSink/AudioTrack错误 | 检查输出format/rate/channel/HDMI能力与sink错误、audio position advancing、underrun，不直接换全播放器 |
| AudioTrack持续写入，STREAM_MUSIC muted/volume0/焦点抑制或路由不符 | 定位播放增益/焦点/HDMI/显示设备静音；音频数据路径与实际听音仍需分别确认 |
| 仅无 audio log 或一个0Tracks快照 | 保留“窗口/日志缺失”解释，不给 root-cause 定论 |

执行归 acceptance；我已向其发消息，不打断当前 controlled SR03 测试。其后回公开 CCTV2 时，可在一个有PID、媒体session、频道身份、主机/设备时钟的60秒窗口，先保存完整 logcat，再用下列只读命令在开始/中间/结束采样（在 PowerShell 中执行，日志名应加时间，避免覆盖旧证据）：

```powershell
adb -s 192.168.2.245:5555 shell pidof tv.iqtvlite.app.validation
adb -s 192.168.2.245:5555 shell dumpsys audio > audio-focus-volume-window.txt
adb -s 192.168.2.245:5555 shell dumpsys media.audio_flinger > audioflinger-window.txt
adb -s 192.168.2.245:5555 logcat -d -v threadtime > full-audio-window-logcat.txt
adb -s 192.168.2.245:5555 shell ls /vendor/etc/media_codecs*.xml /odm/etc/media_codecs*.xml /system/etc/media_codecs*.xml
```

不要自动清 logcat/调音量/清包数据；保留发生变化前的状态。当前日志标签不保证包含 audio callback；上述 shell 输出不能凭空提供 currentTracks。若现有日志不足，下一项经过团队分工的最小诊断改动应在 debug/session guard 下记录：所有audio group的Format(sampleMimeType/codecs/language/rate/channels/bitrate)、supported/selected、renderer支持状态，audioDecoderInitialized/inputFormatChanged/audioEnabled/disabled、AudioSink/AudioTrack error、audioPositionAdvancing、volume、playWhenReady/playbackSuppressionReason、audioSessionId。只有建议，本任务没有实现或重新打包。

设备能力最终应以该设备的 `MediaCodecList(REGULAR_CODECS)`/Media3 `getDecoderInfos("audio/mpeg-L2", false, false)` 与实际48kHz双声道Format支持为准；在同一probe也查 `audio/mpeg` 和一个 AAC 正对照，记录名字、MIME、format支持/异常，且实际解码一段已识别 ES。API28没有API29才加入的硬件/软件分类方法，不能直接调用新API。vendor XML及其 Include可作佐证，不替代API枚举或真实解码。当前我未编译/部署此probe。

为 CCTV2 另取同一实际播放URL/授权header的短样本并保留身份/hash/PTS/ES信息；不复用CCTV1的索引0身份、不将源URL写进公共报告。若对照源有AAC且合法可用，可与同设备/同音量/同输出路由做正对照；不要未经选择更换用户节目。

## 若证明 MPEG-L2 无可用解码器，最佳最小增强建议

优先保持 Media3/单视频硬解和现有请求、时钟、UA、播放器归属不变：如同节目本就有受支持AAC音轨/合法等价流，使用可用轨道或用户可理解的源选择即可，APK/本地解码库增量为零。不存在时，再考虑**同版本官方 Media3 FFmpeg audio extension，仅编译 MPEG audio 所需功能，mode ON用于核心renderer不支持的格式，不用PREFER抢走已有可用Codec**。ON并不保证已选中的平台codec发生运行期错误后会自动跨renderer改用FFmpeg；若出现的是平台宣称支持但解码失败，应另按该codec/format证据设计有限兼容策略。不引入另一个视频播放器、FFmpeg视频解码/网络/demux、全协议栈或Rust自写MP2解码。

关键实现约束（仅建议，未安装/构建）：[decoder_ffmpeg说明](https://github.com/androidx/media/blob/1.11.1/libraries/decoder_ffmpeg/README.md)指明该模块非 Google Maven现成依赖，需要匹配模块源码与native构建；[FfmpegLibrary映射](https://github.com/androidx/media/blob/1.11.1/libraries/decoder_ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegLibrary.java)把 MPEG/L1/L2 **都映射到decoder名称`mp3`**。因此只编译名为`mp2`的decoder并不能满足现有wrapper探测；必须验证`supportsFormat(audio/mpeg-L2)`、native decoder查询及真实Layer-II样本输出，不能按名称猜测。Media3的 `decoder_mpegh` 是另一种 MPEG-H格式，不解决这里的MPEG-1 Layer II。

| 方案代价 | 必须怎样评估 |
|---|---|
| 体积 | 当前universal APK14,142,183B，上限14,454,072B，只有311,889B余量，需容纳armv7+arm64 native/JNI/dex及对齐。不能承诺完整FFmpeg在余量内，最小decoder构建也须实际测量。仅启用所需 decoder/依赖、关闭不需要的encoder/demuxer/protocol/filter并strip；不偷偷放宽预算、删ABI或破坏原UI资产。本任务没有测出可交付增量 |
| 许可 | Media3 wrapper是Apache2.0，FFmpeg另有许可证，不能整体标成Apache。MPEG解码配置应审查LGPL相应版本及分发/源码/重新链接义务，不需要编码器libmp3lame，不应混入不需要的GPL/nonfree选项；以实际构建flags/组件及license输出为准 |
| CPU/内存/时延 | MPEG audio软件解码增加音频CPU、native代码和缓冲，但无需软件AVC；具体CPU/PSS/功耗/首声/同步必须在该v7a盒子测。48kHz双声道16bit PCM理论192000B/s只是PCM流量，不是解码CPU或总内存估算；不能从视频609ms或当前CPU采样推算增量 |
| 稳定性/交付 | 要补双ABI/API24、native加载/keep规则、真实Layer-II/AAC对照、mute/focus、A/V同步/切台/回看/Home长播回归及同源性能测量，再交新CI pair。只设extension mode而不打包库无效；只改MIME别名也不能证明解码兼容 |

目前最小行动是补齐同一 CCTV2 会话的轨道/能力/音量焦点链证据，而不是先加FFmpeg。若上述证据后来到齐，应在本报告补采样身份和结论，不把本次未知项自动改为通过。

## 08:41 后补：同 CCTV2 会话焦点、HDMI 音量与真实 vendor 声明

本节是 t18 完成后由 acceptance 新提供的追加证据，不改变上文的采样时间/边界，也没有执行额外设备操作。

- [恢复公开源指标](evidence/2026-11-19-ci-10073/metrics-public-restored.txt#L167)记录 PID17809、CCTV2同稳定ID的新 session `c7570213555088108263-l2460503528-s43`，08:40:57.782 prepare，视频940ms首帧，08:40:59.332–59.783有一次452ms rebuffer。此为新 session，不能混成早先s2的609ms/无已记录rebuffer窗口，也没有新增音轨日志。
- [08:41:02焦点栈](evidence/2026-11-19-ci-10073/cctv2-audio-state.txt#L2)顶层是 validation/UID10058，GAIN、loss none、notified true、media/movie。在这个快照点，“尚未获焦点”解释得到明显削弱；不能扩大成全时段焦点始终未变化。
- **`Muted:false` 不等于有可听音量。** 同文件 [STREAM_MUSIC](evidence/2026-11-19-ci-10073/cctv2-audio-state.txt#L89)明确当前 Devices:hdmi，speaker/default索引7，但 **HDMI(0x400)索引0/15**；不能拿speaker/default的7当当前输出音量。[AudioPolicy](evidence/2026-11-19-ci-10073/cctv2-audio-policy.txt#L575)也显示output13 devices0x400、stream3衰减-758、refCount0，line606确认HDMI索引0。这把早先`-inf`从线索提升为有系统状态交叉佐证的当前路由零音量条件。即便后续解码成功，也不能在此设置下直接验收可听；本轮没有代用户改音量。
- [CCTV2开始快照](evidence/2026-11-19-ci-10073/cctv2-audio-flinger.txt#L90)与[中间快照](evidence/2026-11-19-ci-10073/cctv2-audio-flinger-middle.txt#L90)仍然standby、0Tracks、frames/writes0、媒体增益-inf、PID17809只为notification client。多个点强化“未观察到活动PCM输出”的证据，**HDMI零音量仍不能独自解释为何没有Track**；轨道未提取/未支持/未选等解码链分支尚未排除。
- 真正的 [vendor主配置](evidence/2026-11-19-ci-10073/vendor-media-codecs.xml#L237)包含google audio/video配置；[google音频声明](evidence/2026-11-19-ci-10073/vendor-google-audio.xml#L19)列`OMX.google.mp3.decoder`、MIME **audio/mpeg**、最多2ch及48kHz范围，另列AAC等。已取得这两个XML没有声明`audio/mpeg-L2`，也没有vendor音频decoder条目。这支持“系统声明层可能没有L2候选”的假设，仍不替代实际MediaCodecList枚举/Media3支持状态/真实解码，尤其不能证明CCTV2与CCTV1样本同格式。

更新后的判断是两条独立路径并存：①该快照已获焦点，但当前HDMI媒体音量为0，当前不满足正音量听音验收前提；②没有活动PCM Track的原因依然待CCTV2真实Format、supported/selected、codec/sink事件确认，vendor XML让MPEG-L2声明缺口成为更有根据的候选，尚不是定案。不要因Muted:false忽略索引0，也不要因索引0便停止检查Track/codec。后续如要做正音量听音对照，必须由acceptance按其测试安排保存原值并可回退，不能自动替用户调整；我只读补充本报告。
