# CI10093 vs CI10101 同频道播放 CPU A/B

2026-11-21，acceptance2（t155 attempt 1）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p，4 核）在线。**同机、同源、同频道、同协议**对比两版播放 CPU 与线程拆分。只替换 validation；原 tv.iqtvlite.app / com.cjlhll.iptv 数据保留。

## 0. 结论（决定性）

- **同频道 CCTV-1：CI10101 = 44.2%（60s 稳态）vs CI10093 = 31.2% → CI10101 高 1.42×，是真回退，不是频道差异。**
- 短协议（6×4s p50）同频道：CI10101 **54.7%** vs CI10093 **28.8%**（1.9×），与 60s 方向一致（短窗口含采样波动/瞬时峰）。
- **线程结构差异**（同频道 10s 差分）：CI10101 Playback 18.1% + Loader 16.9%；CI10093 Playback 11.6% + Loader 5.0% → **Loader ≈3.4×、Playback ≈1.6×**。
- **不是 HLS 分段问题**：该频道是**组播 RTP 渐进式 TS**（`http://192.168.2.1:4022/rtp/239.3.1.129:8008`），两版 60s 内 **rebuffers=0**（CI10093 一次 rebuffer 636ms 属偶发）→ Loader 高不是“HLS 2s 分段正常开销”。
- **不是 UI/EPG 重绘**：两版 60s 空闲 gfxinfo 均 **Total frames = 2**（无 UI 重绘），与 t151 一致。

## 1. 同频道证据（完全相同的频道）

| 项 | 值 |
|---|---|
| tvg-id / 名称 | **CCTV1** / `CCTV-1 综合`（group 央视频道） |
| URL | `http://192.168.2.1:4022/rtp/239.3.1.129:8008`（组播 RTP → 渐进式 TS） |
| 视频 | `mime=video/avc 1920x1080 fps=-1.0`（两版相同） |
| 音频 | CI10101 CCTV-1 实际 `audio/mpeg-L2 48000/2ch`；CI10093 CCTV-1 `mpeg-L2`（同源同流） |
| 选择方式 | 开抽屉 → UP×20 到顶 → OK 播第 1 行（`1. CCTV-1 综合`）→ 两版同一操作 |

## 2. 对比表

### 2a. 60s 稳态（t152 要求的同 60s 协议）
| 指标 | CI10093 (fa7f87e) | CI10101 (b8f9790b) | 比值 |
|---|---|---|---|
| **appCPU avg（60s，jiffies）** | **31.2%** | **44.2%** | **1.42×** |
| dumpsys cpuinfo（本进程行） | 0%（瞬时，非采样点） | 0%（瞬时） | — |
| dumpsys cpuinfo TOTAL | 50%（34% user+15% kernel） | 50% | — |
| gfxinfo Total frames（60s） | **2** | **2** | 无 UI 重绘 |
| gfxinfo 90th | 97ms | 113ms | — |
| rebuffers / dropped | 1（636ms，偶发）/ 0 | **0 / 0** | 均无频繁重缓冲 |

### 2b. 短协议（t115 同法：静置 30s + 6×4s 取 p50）
| 指标 | CI10093 | CI10101 | 比值 |
|---|---|---|---|
| appCPU p50 | **28.8%**（28.8,28.4,26.9,30,29.6） | **54.7%**（56.9,33.7,33.6,58.5,54.7） | 1.9× |
| 进程线程合计 | 27% | 51.4% | 1.9× |

### 2c. 线程拆分（同频道，10s /proc/<pid>/task/*/stat 差分）
| 线程 | CI10093 | CI10101 | 比值 |
|---|---|---|---|
| **ExoPlayer:Playback** | 11.6% | **18.1%** | 1.56× |
| **ExoPlayer:Loader** | 5.0% | **16.9%** | **3.38×** |
| CodecLooper | 4.7% | 4.4% | ≈ |
| MediaCodec_loop | 2.7% | 2.8% | ≈ |
| HwBinder | 1.0% | 0.9% | ≈ |
| Okio Watchdog | 1.0% | 2.5% | 2.5× |
| main（进程主线程） | 0.4% | （0.5% 于 t151） | ≈ |
| 合计 | 27% | 51.4% | 1.9× |

## 3. 补充：AAC 频道对照（非同一频道，仅供参照）
- CI10101 AAC 频道（`audio/mp4a-latm`，session c5648776…s19）：appCPU p50 **87.2%**，Loader **42.6%**、Playback 20.2%（t150 附录 A + t151）。
- 同一 AAC 频道在 CI10093 **未测**（时间/设备窗口限制）；因此**该 AAC 频道的 87% 与 46.6% 的历史对比仍属跨频道/跨时点，不能单独作为结论**。CCTV-1 的同频道 A/B（§2）才是决定性证据。

## 4. 结论（真回退 or 频道差异）
- **真回退**：同一 CCTV-1、同一 URL/分辨率、同 60s 协议，CI10101 44.2% vs CI10093 31.2%（+13.0 个百分点，1.42×）；短协议 54.7% vs 28.8%（1.9×）。差异集中在 **ExoPlayer:Loader（3.4×）与 Playback（1.6×）**，codec 线程基本不变。
- **不是频道/码率差异**（同频道同流）；**不是 HLS 分段**（RTP 渐进式 TS、rebuffers=0）；**不是 UI/EPG 重绘**（60s 仅 2 帧）。
- 与 t152 只读审计最可疑项一致：**LoadControl 1000/6000/300/1000 对短缓冲直播偏紧**（minBuffer 1s → 缓冲反复跌破、Loader 频繁唤醒）→ 建议候选 **2500/6000/500/2000** 验证（未改，待开发）。
- 另注：CI10101 相对 CI10093 增加了 MP2/FFmpeg 扩展、台标预取/缓存、最近观看、EPG 等；Loader 3.4× 也可能与 EPG/playlist 后台刷新或 MP2 相关的分片读取交互有关，建议改 LoadControl 后再测同频道 60s 复验。

## 5. 逐项
| 项 | 结果 |
|---|---|
| 记录同频道 tvg-id/URL/分辨率/码率 | 通过：CCTV1 / rtp 239.3.1.129:8008 / avc 1920x1080（码率源未标注 bitrate=-1） |
| CI10101 同频道 p50 + 线程 | 通过：54.7%；Playback18.1/Loader16.9 |
| CI10093 同频道 p50 + 线程 | 通过：28.8%；Playback11.6/Loader5.0 |
| 装回 CI10101 恢复 | 通过：b8f9790b，PlayerActivity 前台，原包保留 |
| 对比表 + 真回退/频道差异结论 | 通过：**真回退 1.42×（60s）/1.9×（短）** |
| 低码率频道两版各测 | **未做**（时间/设备窗口；CCTV-1 已足证回退，低码率另测可后续） |
| 60s 协议 cpuinfo/top-H/gfxinfo + rebuffers/流类型 | 通过：44.2% vs 31.2%；2 帧；rebuffers=0；RTP 渐进 TS（非 HLS） |

## 6. 设备恢复状态
- 当前装 **CI10101 / 2.2.0-tivimini-b8f9790b-validation**（versionName 已核）；PlayerActivity 前台，`first_frame ttff=1746ms`，`audio/mpeg-L2` 正常出画；`tv.iqtvlite.app` 与 `com.cjlhll.iptv` 保留；backup `validation-10101-backup.tar`(1,420,800B) 在证据目录。
- 未改代码、未改原包数据。
