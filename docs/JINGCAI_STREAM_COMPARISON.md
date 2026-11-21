# 睛彩 vs CCTV-1 码流结构对比（2026-11-21）

样本：睛彩竞技 `http://192.168.2.1:4022/rtp/239.3.1.125:8001`（25s / 26.5MB）、CCTV-1 `http://192.168.2.1:4022/rtp/239.3.1.64:8124`（8s / 8.7MB），均从本机直连网关 4022 抓取。

## 1. 相同点（都正常）

| 项目 | 睛彩 | CCTV-1 |
|---|---|---|
| 视频 stream_type | 0x1B H.264 | 0x1B H.264 |
| 音频 stream_type | 0x03 MPEG-1 audio | 0x03 MPEG-1 audio |
| IDR / SPS / PPS | 有（17/17/17） | 有（8/8/8） |
| PES 声明长度 vs 实际 | 差值恒为 8 或 13（正常头开销） | 同样正常 |
| 带宽 | ≈8.4 Mbps | ≈8.7 Mbps |
| 设备 curl（含我们的 UA） | HTTP 200 / 8.4MB / TTFB 0.1s | HTTP 200 |

## 2. 关键差异

| 项目 | 睛彩竞技 | CCTV-1 | 影响 |
|---|---|---|---|
| **AUD（NAL type 9）** | **完全没有（0 次）** | 每帧都有（392 次） | 部分老 OMX 硬件解码器依赖 AUD 做访问单元边界 |
| NAL type 0（未指定） | 197 | 196 | 睛彩把部分数据标为 0 而非 AUD |
| NAL type 12（filler） | 57 | 41 | 填充数据多 |
| PCR PID | **104（独立于视频）**，7s 内仅 263 个 | 4113（=视频 PID） | 独立 PCR 较稀疏 |
| PMT 描述符 | 无（es=0） | 有（0x52 stream identifier） | 一般无影响 |
| 视频 SPS | Main@4.0 / 8bit / 4:2:0 / progressive / 1920×1088(裁剪到1080) | 同类标准档位 | 硬件均支持 |
| PES flags | 0x80(PTS) 与 0xC0(PTS+DTS) 混合 | 仅 0x80(PTS) | 有 B 帧重排（PTS 非单调，-80~+160ms） |

## 3. 设备侧观测（CI10136）

- `format=video/avc/1920x1080` **已产生**（extractor 正常）；
- **视频解码器从未创建**：`OMX/ACodec/OMXVDEC/MediaCodec` 日志计数全为 0，无 `decoder session`；
- 无 `init/configure/start failed`、无丢帧；
- **音频正常起播**（`audio/mpeg-L2` + FFmpeg init + AudioTrack started）；
- 对照 CCTV-1：`format` → `ACodec/OMXVDEC 创建 OMX.goke.video.decoder.avc` → `first_frame ttff=1251ms` → `state=3`。

## 4. 结论

- **排除**：源/网关/组播/带宽/UA/数据源（curl 同 UA 正常）、extractor（format 已出）、解码器能力（CCTV 同机正常）、渲染丢帧。
- **最可能**：该流**缺少 AUD 且带独立稀疏 PCR**，触发本机 OMX 硬件解码器路径不创建解码器（MediaCodec 侧静默不初始化）。
- **可行修法（按优先级）**：
  1. **看门狗升级为"整机播放器重建"**：当前重试是同一 player 上 `seekToDefaultPosition+prepare`，不重建 MediaCodec；改为第二次尝试**重建 ExoPlayer 实例**（新渲染器/新解码器），这是"解码器未创建"类问题的经典修法，风险低、可回退。
  2. 若重建仍无效 → 该频道走 **FFmpeg 视频软解兜底**（1080p 8Mbps 在 armv7 弱机可能跟不上，作为可选开关）。
  3. 记录该频道为"需源侧修复 AUD"（长期）。
