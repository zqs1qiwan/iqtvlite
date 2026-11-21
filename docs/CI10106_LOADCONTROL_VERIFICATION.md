# CI10106 LoadControl 修复复验（同频道 60s 协议）

2026-11-21，acceptance2（t160 attempt 1）。对象：`tv.iqtvlite.app.validation` **10106 / 2.2.0-tivimini-b8850420-validation**（t157：LoadControl 1000/6000/300/1000 → **2500/6000/500/2000**；run 44810654405）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）在线。**只测不改；原包保留。**

## 0. 结论（未达标；Loader 已修，Playback 反升）

| 指标（同频道 CCTV-1，t155 协议） | CI10093 | CI10101 (旧 LoadControl) | **CI10106 (2500/6000/500/2000)** | 目标 |
|---|---|---|---|---|
| 60s appCPU 累计 avg | 31.2% | 44.2% | **43.6%**（未静置首轮 50.2%） | ~31–35% ❌ |
| 60s per-4s p50 | — | — | **44.4%** | — |
| **ExoPlayer:Loader** | 5.0% | 16.9% | **5.5%** | 个位数 ✅ |
| **ExoPlayer:Playback** | 11.6% | 18.1% | **21.5%** | ~11–13% ❌（反升） |
| CodecLooper / MediaCodec | 4.7 / 2.7 | 4.4 / 2.8 | 4.8 / 3.1 | ≈ 不变 |
| 其它 | — | — | ExoPlayer:Simpl 4.0%、HwBinder 1.1%、Okio 0.9% | — |
| rebuffers / dropped | 1(636ms)/0 | 0/0 | **1(1519ms 启动期)** / 0 | 0 ❌ 首次出现 |
| gfxinfo 帧数（60s） | 2 | 2 | **3** | ≈0 ✅ |
| 首帧 ttff | — | — | **685ms** | 不得明显变慢 ✅ |
| MP2 | — | — | `audio/mpeg-L2` + `AudioTrack ID:1519 state:started` | 正常 ✅ |

- **Loader 已按预期回落**（16.9%→**5.5%**，≈CI10093 5.0%），说明“minBuffer 1s 偏紧→Loader 频繁唤醒”的判断被验证。
- **但总 CPU 未回落**（43.6–44.4% vs 目标 31–35%），因为 **Playback 线程从 18.1% 升至 21.5%**（比 CI10093 11.6% 高 1.85×），抵消了 Loader 的下降。
- **启动期出现 1 次 1519ms rebuffer**（CI10101 为 0）——首帧仍 685ms 正常，但启动加载阶段有可见重缓冲（afterRebuffer=2000 下会停顿约 1.5s）。
- **判定：本轮 LoadControl 单变量未达标**：Loader 目标达成，但 Playback 反升 + 启动 rebuffer，总 CPU 仍在 ~44%。

## 1. 身份/安装（PASS）
- app `8130bea6bef357d6d0750f1562df36a9f4d00f71846e1b3696912bac14297054`（12,994,451B，match）/ test `6635b8ef…a490`（502,955B，match）；签名 `96d6594f…`；10106/2.2.0-tivimini-b8850420-validation；安装后 base SHA match；backup 10104（1,530,880B）后仅替换；原包保留。

## 2. 同频道证据（与 t155 完全一致）
- tvg-id **CCTV1** /「CCTV-1 综合」/ 央视频道；URL `http://192.168.2.1:4022/rtp/239.3.1.129:8008`（组播 RTP 渐进 TS）。
- 选择：开抽屉 → UP×20 到顶 → OK 第一行；`format mime=video/avc 1920x1080`、`audio_format mime=audio/mpeg-L2 48000/2ch`、`first_frame ttff=685ms`。

## 3. 60s 采样（原始）
- 未静置（t155 同协议）：累计 avg **50.2%**；per-4s p50 **44.2%**；vals `[84.7,62.1,55.9,39.2,39.3,50.5,44.1,41.3,48.4,40.5,44.2,35.6,45.9,45]`（首 3 个样本 62–85% 为启动/加载突发）。
- **静置 30s 后 60s**：累计 avg **43.6%**；per-4s p50 **44.4%**；vals `[37.6,44.9,42.1,42.7,44.4,45.2,36.8,42.3,47,45.2,38.9,44.6,46.3,49]`（稳态 ~36–49%）。
- gfxinfo（静置后 60s）：Total frames **3**、90th 44ms；无 UI 重绘。
- `dumpsys cpuinfo`：本进程 0%（瞬时）/ TOTAL 46%。
- IQTV-METRICS：`state=3 rebuffers=0`（静置窗口）；启动窗口出现 `state=2 rebuffers=1` → `state=3 rebuffers=1 rebuffer_ms=1519`。

## 4. 线程拆分（静置后 10s）
`ExoPlayer:Playback 21.5%`、`ExoPlayer:Loader 5.5%`、`CodecLooper 4.8%`、`ExoPlayer:Simple* 4.0%`、`MediaCodec_loop 3.1%`、`HwBinder 1.1%`、`Okio 0.9%`，合计 **40.9%**（与 60s avg 43.6% 基本一致）。
- 对比 CI10101：Loader 16.9→5.5（-11.4pp），Playback 18.1→21.5（+3.4pp）→ 净减少约 -8pp 应使总 CPU 明显下降，但实测总仅 44.2→43.6（≈持平）；说明**Playback/其它线程在 60s 窗口内存在与 Loader 无关的额外开销**（可能与 30s EPG tick/推理/渲染或采样窗口差异有关，需 Perfetto 进一步定位）。

## 5. 回归
- 首帧 **685ms**（未明显变慢，OK）。
- MP2 声道正常（`AudioTrack ID:1519 state:started`）。
- 1080p 出画（截图 cctv1-10106*.png），gfxinfo UI 帧 2–3（无重绘）。
- **启动期 1 次 1519ms rebuffer**（新出现）；afterRebuffer=2000 下会停顿约 1.5s → 体感可能变差，需观察。
- 无崩溃（进程稳定）。

## 6. 逐项
| 项 | 结果 |
|---|---|
| 身份/安装 | 通过 |
| 同频道 60s 协议复现 | 通过（CCTV-1 RTP TS） |
| appCPU 回到 31–35% | **未通过**（43.6–44.4%） |
| Loader 个位数 | **通过**（5.5%，≈CI10093 5.0） |
| Playback ~11–13% | **未通过**（21.5%，反升） |
| rebuffers 0 | **未通过**（启动 1 次 1519ms） |
| UI 帧≈0 | 通过（2–3 帧） |
| 首帧不慢 | 通过（685ms） |
| MP2 / 1080p 流畅 | MP2 通过；1080p 出画（流畅度主观未逐帧量化） |

## 7. 结论与下一轮建议
- **本轮单变量 LoadControl(2500/6000/500/2000) 未达标**：Loader 目标达成（16.9→5.5%），但 **Playback 反升（18.1→21.5%）**、总 CPU 仍 ~44%、并出现 **启动 1519ms rebuffer**。
- 建议下一轮（开发每次只改一组）：
  1) **2000/6000/500/1500**（降低 minBuffer 与 afterRebuffer，可能减少启动停顿但需再次确认 Loader 不回升）；
  2) **3000/8000/500/2000**（更大 maxBuffer，观察 Loader/总 CPU 与内存）；
  3) 同时用 Perfetto/线程栈定位 **Playback 21.5%** 的组成（是否包含音频渲染/EPG tick 等非 LoadControl 因素），否则仅调 LoadControl 可能无法回到 31–35%。
- 未改代码、未改原包数据；设备现为 CI10106（可出画、MP2 正常）。
