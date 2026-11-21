# CI10127 冷启启动 rebuffer 诊断 + 睛彩系列复测

2026-11-21，acceptance2（t214 attempt 1）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）。对象 CI10127 / 2.2.0-tivimini-8d23b51d-validation（设备现装，未重装）。测试源 `https://iptv.laobaitv.net`。证据目录 `docs/evidence/2026-11-21-ci-10127-diag/`。

## 0. 结论
- **冷启 ~3.3–3.6s 停顿主因**：**首帧渲染后约 1s 出现的播放欠缓冲（Loader 补流 ~3.77s）**，**不是 EPG 解析**（EPG 缓存 18.6s 才加载，远晚于首帧），**不是渲染**（首帧 2.8s 正常出）。与起播缓冲档位无关（三方各档相同），因 minBuffer=2500ms 固定、弱机/弱网首秒填充率低，需约 3.7s 填满。
- **睛彩系列（117/118/119）异常**：三台均可解析格式（avc 1920×1080）+ 音频解码器初始化（MP2 ffmpegLavc60.3.100-mp3），但**长时间卡在 `state=2`（缓冲），无 `first_frame`、无 `audio_advancing`、无 `state=3`**，无崩溃。疑似 **源不可达/多播 join 失败/拉流卡住**。**视频首帧未确认（疑似黑屏/无画面）**，需人工目视 + 源侧确认。

## 1. A) 冷启启动 rebuffer 时间线（全冷）
设置：`log.tag.IQTV-METRICS/ZAP=DEBUG`、`log.tag.ExoPlayer=VERBOSE`；force-stop → 冷启动（pid 24735，bufferMin=500）。

| 相对时刻 | 事件 |
|---|---|
| 10:28:22.393 | `bufferMin=500`（播放器初始化） |
| 10:28:23.030 | `prepare ... at=8254129` |
| 10:28:23.080 | `state=2`（起播缓冲）rebuffers=0 |
| 10:28:24.001 | `audio_session id=153` |
| 10:28:25.330 | `decoder OMX.goke.video.decoder.avc init_ms=81` |
| 10:28:25.345 | `audio_decoder OMX.google.aac.decoder init_ms=37` |
| 10:28:25.845 | **`first_frame ttff_ms=2817`**（首帧出，rendered_at=8256930） |
| 10:28:25.976 | `state=3`（READY）rebuffers=0 |
| 10:28:26.006 | `audio_advancing since_prepare_ms=2977` |
| 10:28:26.923 | **`state=2` rebuffers=1**（首帧后 ~1.0s 欠缓冲） |
| 10:28:30.697 | **`state=3 rebuffers=1 rebuffer_ms=3774`**（恢复，**~3.77s 停顿**） |
| 10:28:30.754 | `audio_advancing since_prepare_ms=7726` |
| 10:28:44.464 | `IQTV-ZAP: epgCache=18600ms firstFrame=true`（EPG 缓存 18.6s 才完成） |

CPU（top 快照，4 核）：`110% user / 52% sys / 208% idle`，`loadavg 14.72`（系统整体高，但播放进程非独占）。
**判读**：`epgCache=18.6s` 晚于首帧 16s，排除“EPG 解析抢首帧”；`first_frame=2.8s` 正常，排除渲染问题；**停顿=首帧后立即欠缓冲，恢复需 3.77s 补流**（Loader 在弱网首秒填充率低 + minBuffer 2500ms）。与起播缓冲档（300/400/500）无关（t212 三档均 ~3.3–3.6s）。
**最小复现**：冷启动 → 首帧 ~2.8s → ~1s 后进入 rebuffer → 约 3.7s 恢复。
**优化方向建议**：冷启“先填到 minBuffer 再 start”或首秒预连接/提高起播预缓冲，而非调 EPG/渲染。

## 2. B) 睛彩系列复测（app 编号 117/118/119）
App 频道号与播放列表索引偏移 9（列表 #126/#127/#128 → app 117/118/119）。逐台跳转观察 60s：

| 频道 | app号 | 源 URL | 视频 | 音频 | first_frame | 状态 | crash |
|---|---|---|---|---|---|---|---|
| 睛彩竞技 | 117 | rtp 239.3.1.125:8001 | avc 1920×1080 | MP2 ffmpegLavc60.3.100-mp3 init=7–9 | **无（15s 内未出）** | 卡 `state=2`，无 `state=3`，无 `audio_advancing` | 0 |
| 睛彩篮球 | 118 | udp 239.3.1.126:8001 | avc 1920×1080 | MP2 init=4 | **无** | 卡 `state=2` | 0 |
| 睛彩青少 | 119 | rtp 239.3.1.127:8001 | avc 1920×1080 | MP2 init=5 | **无** | 卡 `state=2` | 0 |

- 三台标题切换正确（睛彩竞技/篮球/青少），`format session` 均为 `video/avc 1920×1080`，`audio_decoder=ffmpegLavc60.3.100-mp3` 初始化成功，**但无 `first_frame`、无 `state=3`、无 `audio_advancing`** → **卡在缓冲、疑似无画面/无声音**。
- 截图（睛彩青少）非纯黑（full mean≈99.6，dark 28%），但可能是 OSD/背景，**视频帧未由日志确认**；需人工目视。
- 无崩溃、无 MediaCodec 硬错（仅 CodecLooper/hal_hwgraphics 的 permissive avc denied，属系统宽免策略）。
- **异常说明**：117/119 用 `rtp://239.3.1.125/127:8001`，118 用 `udp://239.3.1.126:8001`（协议不一致），均经 `http://192.168.2.1:4022/` 网关。疑似**多播组不可达/join 失败/网关拉流卡住**。
- **最小复现**：设置数字键=需OK → 输入 117（1,1,7）+OK → 标题变“睛彩竞技”，但长时间 `state=2`、无 first_frame、无声音推进。120/121 同理。
- **待开发/源侧确认**：请核对该三路多播是否可达、协议（rtp vs udp）是否一致、网关 4022 是否正确转发。

## 3. C) 不回归抽验
- **普通频道**（CCTV-1，数字 2）：`state=3` 就绪 → 1268ms 一次 rebuffer → 恢复，正常出画面（截图 mean 160）。
- **MP2**：睛彩台音频解码器 `ffmpegLavc60.3.100-mp3` 初始化正常；普通台 `audio/mpeg-L2` 正常。
- **频道键/数字跳台**：D-pad 167 与“数字=需OK 输入+OK”跳台均生效；上一台 229 正常（t212 已验）。
- **crash=0**（全程）。

## 4. 结论
1. **冷启停顿（~3.3–3.6s）主因 = 首帧后欠缓冲/Loader 补流（~3.77s），非 EPG、非渲染**；优化应在“起播预缓冲/首秒吞吐”，与起播缓冲档无关。
2. **睛彩竞技/篮球/青少（117/118/119）卡缓冲、无首帧、无音频推进**，疑似源不可达/多播 join 失败，**视频未确认（疑似黑屏/无声）**；给出 URL 与最小复现，建议源侧/多播核查。
3. 普通频道/MP2/换台/无崩溃 正常。
- 未改代码、未改原包；设备 CI10127（PlayerActivity 前台）。
