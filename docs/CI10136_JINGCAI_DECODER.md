# CI10136 睛彩解码层定位（decoder 组件 / init 错误 / CCTV 对照）

2026-11-21，acceptance2（t233 attempt 1）。设备 `192.168.2.245:5555`（TR100-G9 / Android 9 / API28 / armeabi-v7a / 1080p）。验证包 **10136 / 2.2.0-tivimini-8c1f3ebb-validation**（**未重装、未卸载**，只读诊断）。证据：`docs/evidence/2026-11-21-ci-10136-decode/`（原始 logcat 已存）。

## 0. 结论（明确回答）

| 问题 | 答案 |
|---|---|
| a) 是否创建了 video decoder？组件名？ | **没有创建**。睛彩 117 窗口内 `OMX=0 / ACodec=0 / OMXVDEC=0 / MediaCodec=0`，且**无 `decoder session=` 行**（`IQTV-METRICS: decoder` 是视频解码器初始化事件）。对照 CCTV-1 同窗口：`OMX=7 / ACodec=6 / OMXVDEC=6 / MediaCodec=5`，组件名 **`OMX.goke.video.decoder.avc`**（硬件 AVC）。 |
| b) 是否 init/configure/start failed？错误码？ | **没有任何失败**：`init failed=0`、`configure failed=0`、`start failed=0`。**不是解码器初始化失败，而是根本没走到“创建解码器”这一步。** |
| c) 有 format 但无 onVideoSizeChanged/onRenderedFirstFrame？ | **是**：`format session=… mime=video/avc 1920x1080` 已出现（extractor 出流），但 `first_frame=0`、`onVideoSizeChanged=0`、`onRenderedFirstFrame=0`。 |
| d) 是否一直 Dropping/skipping？ | **否**：`Dropping=0`、`skipping=0`。不是丢帧，是**根本没有帧到达渲染器**。 |
| e) audio 是否先起播而视频卡住？ | **是（关键）**：`audio_track_initialized encoding=2 rate=48000` 正常、`audio_track … state=started`（AudioTrack 起播）、MP2 解码器 `ffmpegLavc60.3.100-mp3 init_ms=4` 正常；**视频侧无解码器、无首帧**。 |

**分层结论：故障位于「视频渲染器（MediaCodecVideoRenderer）从未被驱动到创建解码器」这一步** —— 即 `format` 已产生，但视频样本没有送到渲染器/渲染器没有收到可解码的样本。**不是解码器不支持、不是 init 失败、不是渲染卡帧、不是网络/源（t232 已用 curl 8.4Mbps 证伪）、不是 extractor（format 已出）**。

## 1. 原始日志摘录

### 1.1 睛彩 117（`logcat-117e-full.txt`，15:25:23 起）
```
15:25:23.879  IQTV-METRICS: audio_track session=c4783027072129292115-…-s4 group=1 track=0 supported=true selected=true renderer_format_support=4 mime=audio/mpeg-L2 …
15:25:23.887  IQTV-METRICS: format session=c4783027072129292115-…-s4 mime=video/avc width=1920 height=1080 fps=-1.0
15:25:23.891  IQTV-METRICS: audio_decoder session=…-s4 name=ffmpegLavc60.3.100-mp3 init_ms=4
15:25:23.893  IQTV-METRICS: audio_format session=…-s4 mime=audio/mpeg-L2 rate=48000 channels=2
15:25:24.020  IQTV-METRICS: audio_track_initialized session=…-s4 encoding=2 rate=48000 channelConfig=12 bufferBytes=96000
15:25:31.488  IQTV-WATCHDOG: firstFrameTimeout retry=1 channel=4783027072129292115
15:25:39.507  IQTV-JINGCAI: mode=off bytes=0 format=video/avc/1920x1080@-1.0 firstFrame=false state=2
```
**缺失**：无 `IQTV-METRICS: decoder session=… name=OMX.*.video.decoder.avc`（视频解码器事件），无 `first_frame`。

### 1.2 对照 CCTV-1（`logcat-cctv1-ctrl.txt`，15:22:16 起，同 tag 集）
```
15:22:16.510  IQTV-METRICS: format session=…-s2 mime=video/avc width=1920 height=1080
15:22:16.527  IQTV-METRICS: audio_decoder … ffmpegLavc60.3.100-mp3 init_ms=9
15:22:16.669  OMXVDEC : get_config_index_config_common_output_crop: w:1920 h:1080
15:22:16.670  ACodec  : [OMX.goke.video.decoder.avc] Get scale mode(0x0)
15:22:16.670  OMXVDEC : omx_convert_codecaspects_to_coloraspects …
15:22:16.717  IQTV-METRICS: first_frame session=…-s2 ttff_ms=1251 rendered_at=3923422
15:22:16.876  IQTV-METRICS: state=3 rebuffers=0 dropped=0
15:22:16.956  IQTV-METRICS: audio_advancing … since_prepare_ms=1490
```
→ 视频解码器 `OMX.goke.video.decoder.avc` 被创建（`ACodec` + `OMXVDEC` 日志），随后出首帧。

## 2. 组件/模式统计对照表

| 关键项 | **睛彩 117** | **CCTV-1（对照）** |
|---|---|---|
| `OMX`（OMX 组件日志） | **0** | **7** |
| `ACodec`（ACodec 层） | **0** | **6** |
| `OMXVDEC`（厂商解码器） | **0** | **6** |
| `MediaCodec` | **0** | **5** |
| `decoder session`（我方视频解码器事件） | **0** | **1** |
| 组件名 | —（未创建） | `OMX.goke.video.decoder.avc` |
| `init failed / configure failed / start failed` | 0 / 0 / 0 | 0 / 0 / 0 |
| `format session`（extractor 出流） | ✅ avc 1920×1080 | ✅ avc 1920×1080 |
| `first_frame` | **0（无）** | ✅ `ttff=1251ms` |
| `state=3` | **无（停在 2）** | ✅ |
| `audio_track_initialized` / AudioTrack started | ✅（MP2 正常） | ✅ |
| `audio_advancing` | **无** | ✅ |
| `Dropping` / `skipping` | 0 / 0 | 0 / 0 |
| `IQTV-WATCHDOG` | 1 条 `retry=1`（8s） | 无 |

## 3. 设备侧解码器能力
- `dumpsys media.codec` → **`Can't find service: media.codec`**（该 Android 9 盒子未注册该服务名；无法用此命令枚举）。
- `dumpsys media.player` → 仅 `0 registered handlers / No media recorder client`（无有用信息）。
- **但 CCTV-1 已证明硬件 AVC 解码器 `OMX.goke.video.decoder.avc` 存在且可用**（1920×1080 avc 正常出帧）。**故“盒子不支持 AVC”被排除。**

## 4. 结论与根因指向

**明确结论：解码器“未被创建”，而非“创建失败”。**

时序对比：
- CCTV-1：`format(avc)` → **`ACodec/OMXVDEC`（创建解码器）** → `first_frame` → `state=3`
- 睛彩 117：`format(avc)` → **（无解码器创建）** → 卡在 `state=2` → 8s 看门狗重试 → 16s 放弃

`format` 已经由 extractor 解析出来（`video/avc 1920×1080`），说明**解复用成功**；但 **`MediaCodecVideoRenderer` 没有收到可解码的样本流**（否则一定会创建 `ACodec` 并打出 `OMXVDEC`）。结合：
- t232 已证 curl 同 UA 可拉 8.4Mbps（**数据源正常**）；
- 本任务证 **extractor 出 format**（**解复用正常**）；
- 本任务证 **无 `Dropping`、无 init 失败**（**不是解码器/渲染错误**）；

→ **最可能：视频样本在送入渲染器之前被丢弃/未产出**，即 **TS 里视频 PES 未被 extractor 正确产出（或产出后被 `MediaCodecVideoRenderer` 判为不可用而静默等待）**。典型成因：
1. **视频 PID 未被识别/PMT 不完整**：`format` 可能来自流内探测（`setTsExtractorTimestampSearchBytes`），但**实际视频 PES 载荷未被送出**（例如 PID 过滤/连续计数/加扰/`tsSearch` 未覆盖到真正的首个 PES）。
2. **时间戳不可用**：PES 时间戳异常导致样本被判为“无有效时间戳”而不进渲染器（ExoPlayer 会等更多数据）。
3. **样本被 `SampleQueue` 跳过**（无 `Dropping` 日志说明不是渲染器丢帧，而是上游没给样本）。

**建议下一步（App 侧，一步可定位）**：
- 在 `StreamPlayback` 的 `AnalyticsListener.onLoadCompleted/onDownstreamFormatChanged` 之外，**加 `onVideoInputFormatChanged` + `onVideoDecoderInitialized` + `onDroppedVideoFrames` + `onVideoSizeChanged` 的显式日志**（当前只有我方 `decoder`/`format` 事件，缺“渲染器是否收到样本”的直接证据）；
- 或在 `MediaCodecVideoRenderer` 层打印 `onInputFormatChanged` 与首个 `onQueueInputBuffer`（需 hook 或改用 `setVideoEffects` 无法做到 → 更实际的是**换 `DefaultTsPayloadReaderFactory` 显式指定 PID/或关闭 `setTsExtractorTimestampSearchBytes` 走默认**做单变量对照）；
- **最省事的一步实验**：新增 **E4：`DefaultTsPayloadReaderFactory(FLAG_ALLOW_NON_IDR_KEYFRAMES | FLAG_IGNORE_AAC_STREAM …)`** 或**禁用 `experimentalParseSubtitlesDuringExtraction`**（E3 已做，无效）→ 试 **E4：`setTsExtractorTimestampSearchBytes` 回默认 0/1MB 之外，再试“不注入 CEA608 + 不设置 tsSearch”组合**；
- 若仍无 decoder 创建，需 **旁路 TS 解析**（读 PES/PMT/PID）确认视频 PES 是否真的存在且时间戳合理（此前 t231 已诚实标注未做）。

## 5. 健康与协议
- 全程**只读**：未重装、未卸载、未改包。
- 每步后 `/data` 可用 **2,640,080 KB**、`sys.boot_completed=1`、`storaged/installd/I/O error/DeadObject` 全 0、3 包在位。
- 仅 `force-stop` 本应用 1 次（为让 `setprop log.tag.*` 生效），未整机重启。
- 设备当前停在 **睛彩竞技**（117），CI10136，`jingcai_experiment=off`。

## 6. 交付物
- 本报告：`docs/CI10136_JINGCAI_DECODER.md`
- 原始日志：`docs/evidence/2026-11-21-ci-10136-decode/`
  - `logcat-117-full.txt`（1813 行）
  - `logcat-117b-full.txt` / `logcat-117c-full.txt` / `logcat-117d-full.txt`
  - **`logcat-117e-full.txt`（最终 117 窗口，含 codec 计数）**
  - **`logcat-cctv1-ctrl.txt`（CCTV-1 对照窗口）**
