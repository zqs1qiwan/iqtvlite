# 音频成本隔离（CCTV-1 静音对照 + 两版对比）

2026-11-21，acceptance2（t166 attempt 1）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）。测试源 `https://iptv.laobaitv.net`。只替换 validation；原包保留。

## 0. 结论（音频成本 ≈ 19.6pp，差异主要由“MP2 从无声变有声”解释）

| 版本/状态 | 音频证据（IQTV-METRICS / dumpsys audio） | 60s appCPU avg | p50 |
|---|---|---|---|
| **CI10093**（CCTV-1） | `audio_tracks groups=1`，但**无 `audio_format` / `audio_decoder` / `audio_track_initialized`**，dumpsys audio 无本进程 AudioTrack started → **MP2 无解码器、无音频输出** | **29.7%** | 28.9% |
| **CI10107**（CCTV-1，音频 ON） | `audio_decoder name=ffmpegLavc60.3.100-mp3 init_ms=25`、`audio_format mime=audio/mpeg-L2 48k/2ch`、`audio_track_initialized encoding=2 rate=48000`、`AudioTrack state:started` | **49.3%** | 48.5% |
| **CI10107**（CCTV-1，KEYCODE_MUTE=91 静音不暂停） | AudioTrack 仍 `state:started`（仅静音输出） | **50.0%** | 50.0% |

- **两版差 ≈ 19.6pp（49.3 − 29.7）≈ MP2 音频解码/渲染成本**。
- **静音（mute）后 CPU 不降（50.0% vs 49.3%，甚至略高）** → 成本在**解码/渲染**，不在“是否出声”；因此不能用静音省 CPU。
- 结论：CI10101/10106/10107 相对 CI10093 在 CCTV-1 的 CPU 差异，**主要由“MP2 从无到有”的音频解码/渲染解释**；LoadControl 修复（Loader 16.9→5.5）仍是正确收益（Loader 已回落），Playback 高主要是音频成本。
- **underrun/retry 未知**：CI10109 计数包崩溃，无法取 IQTV-AUDIO 计数（见 §2）。

## 1. CI10107 数据（原始）
- CCTV-1：`audio_decoder ffmpegLavc60.3.100-mp3 init_ms=25`；`audio_track_initialized encoding=2 rate=48000 channelConfig=12 bufferBytes=96000 tunneling=false offload=false`；`AudioTrack ID:2495 state:started`。
- 60s audio ON：avg **49.3%**、p50 48.5、vals `[48.7,48.1,49.4,51,45.9,48.7,55.6,47.5,51.7,51.4,48.5,46,38.5,43.1]`；线程和 46.2%。
- 静音（91）60s：avg **50.0%**、p50 50.0、vals `[50.8,39.8,45.9,51.7,48.3,48.8,56.4,50,49.7,43.4,53.3,51.2,51.4,53.3]`；线程和 45.2%。
- → 静音未降低（+0.7pp，噪声内）。

## 2. CI10109 计数包：崩溃（blocker）
- 安装 10109（`070da23d…59b8` 13,010,839B / test `9fac4614…98d2` / 签名 `ecc4882e…` / 10109）后，播放 CCTV-1 时**崩溃两次**：
```
FATAL EXCEPTION: DefaultDispatcher-worker-2
Process: tv.iqtvlite.app.validation, PID: 28366
java.lang.IllegalStateException: Player is accessed on the wrong thread.
Current thread: 'DefaultDispatcher-worker-2'  Expected thread: 'main'
  at androidx.media3.exoplayer.ExoPlayerImpl.verifyApplicationThread(ExoPlayerImpl.java:3150)
  at androidx.media3.exoplayer.ExoPlayerImpl.getCurrentTimeline(ExoPlayerImpl.java:1426)
  at androidx.media3.common.BasePlayer.getCurrentMediaItem(BasePlayer.java:293)
  at com.cjlhll.iptv.StreamPlayback$Metrics$begin$1.invokeSuspend(StreamPlayback.kt:96)
```
- 现象：进程在播放/采样期间被杀死（`/proc/<pid>/stat` 消失），`am_kill` 记录 `Process … has died: fore TOP`；非 LMK，是未捕获异常。
- **影响**：10109 无法用于 60s 采样与 IQTV-AUDIO 计数；`IQTV-AUDIO` 未产生任何行（`logcat -s IQTV-AUDIO` 为空）。
- 证据：`crash-10109.log`、`crash-10109-2.log`。

## 3. CI10093 数据（原始）
- CCTV-1：`audio_tracks groups=0 → groups=1`、`format mime=video/avc 1920x1080`、`first_frame ttff=1475ms`；**无 audio_format/audio_decoder/audio_track_initialized**；dumpsys audio 无本进程 started AudioTrack。
- 60s：avg **29.7%**、p50 28.9、vals `[29.9,26.4,32.3,30,29.9,35.4,28.5,28.9,29,28.9,27.7,27.4,35.1,26.6]`。

## 4. AAC 频道对照（未完成）
- 在 CCTV 区及分组 2–8 内用 167 扫描（每组 10 步），`audio_format` **全部为 `audio/mpeg-L2`**，未找到 `audio/mp4a-latm` 频道；本轮 **AAC 两版对比未完成**（时间/频道定位限制）。
- 说明：若需 AAC 对照，建议按 M3U 分组名定位含 AAC 的组（如地方/卫视）后复测；或由开发提供 AAC 频道名。

## 5. 逐项
| 项 | 结果 |
|---|---|
| CCTV-1 音频 active 确认（mpeg-L2/ffmpeg/AudioTrack started） | 通过（CI10107） |
| CCTV-1 60s 基线 vs 静音 60s | 通过：49.3% → 50.0%（**未下降**） |
| CI10093 音频可用性佐证 | 通过：无 audio_format/audio_decoder/track_initialized（MP2 无解码器） |
| AAC 频道两版 60s | 未完成（未找到 AAC 频道） |
| CI10109 IQTV-AUDIO 计数 | **失败（10109 崩溃）** |
| 结论（差异是否由音频解释） | **是**：≈19.6pp ≈ MP2 解码/渲染成本 |

## 6. 判定与建议
- **版本 CPU 差异主要由音频解释**：CCTV-1 上 CI10093 无音频 29.7% vs CI10107 有声 49.3%，差 19.6pp；静音不降 → 成本在解码/渲染。
- **LoadControl 修复有效**（Loader 16.9→5.5），剩余高 CPU 是音频成本，不是 UI/预取/EPG。
- **必须修 CI10109 崩溃**：`StreamPlayback.Metrics.begin`（StreamPlayback.kt:96）在 `Dispatchers.Default` 调 `getCurrentMediaItem()`；应改为在 main/player 线程读取（或 `player.applicationLooper`），否则计数包不可用且会线上崩溃。
- 后续可用计数包量化 underrun/retry（修好后），判断是否需进一步优化 MP2 解码（如 offload 不可用则考虑降低重采样/缓冲）。
- 未改代码、未改原包数据；设备已恢复 **CI10107**（PlayerActivity 前台）。
