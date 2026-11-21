# CI10078 零拷贝 SurfaceView 基座 真机全量验证（t40）

2026-11-20，acceptance2（t40 attempt 1）。对象：`tv.iqtvlite.app.validation` **10078 / 2.2.0-tivimini-c82593f5-validation**（CI10078 零拷贝 SurfaceView 基座 + 透明 Compose 浮层；USAB2-01 LoadControl 崩溃修复；run 44651507384/source c82593f5；t39 源码复查 pass）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）在线。体积上限 30MB。

> 灰屏/出画= PIL 像素统计 + 两帧差分（运动量），非肉眼。

## 0. 结论摘要

- **灰屏：无回归（PASS）** —— 冷启首帧前为 **黑底(0,0,0)非灰**，首帧即显示**运动视频**；SurfaceFlinger 视频层可见（见 §2 分层说明）。
- **USAB2-01 崩溃：已修复** —— 进 PlayerActivity 不崩、能起播。
- **稳态 CPU 明显下降**：1080p AVC 稳态 **中位≈49.5%（46.7–54.3%），0/11 >82%**；CI10076≈82% → 显著降低。**但存在瞬时尖峰**（冷启初始化/EPG 刷新/缓冲时短暂 86%–270%）。
- **sidebar：视频不再跟随卡（PASS）** —— 打开/浏览期间 **video dropped=0/rebuffer=0**；但 **sidebar 的 Compose UI 自身仍 jank（80% janky/90th 200ms/95th 500ms）** 为 UI 侧残余。
- **频道键 PASS**：166/167/92/93 均换台；面板内不误触发。
- **zap30 TTFF p50 923/p95 1453ms，0 dropped**；TIV 关键通过；硬解 OMX.goke AVC 正常；**AudioSink 时间戳不连续本轮 AVC/mpeg-L2 窗口未复现(0)**（此前 10076 AAC 信道 64 条；源码未改，可能仍在）。
- **“老机流畅”：明显改善、但未完美** —— 稳态播放流畅、CPU 降；残余 sidebar UI jank、冷启/初始化 CPU 尖峰与冷启偏慢。不称完美流畅。

## 1. 产物身份与安装（PASS）

| 项 | 值 |
|---|---|
| 主 APK SHA256 | ed165e6e06dd9b7a9e86d74294b2db0aaf45016c098364d12f1e3fd94eab7285（14,126,355B，≤30MB，match） |
| test SHA256 | 5564f5a21df3d1968d6b21a3eae7cf1ad6324eba5188c692c95fba085725ac71（502,955B，match） |
| 包/版本 | tv.iqtvlite.app.validation / 10078 / 2.2.0-tivimini-c82593f5-validation |
| min/target/ABI | 24/35；arm64-v8a+armeabi-v7a |
| 签名SHA256 | bc52f452bb0677a726fb8c96f53a8846dffdebe8fee5fa70a8dd8135f05a641c（两包同） |

本 pair 已由 t41 装入并保持；本次复验安装后 base.apk 复算 = `ed165e6e…`（match），versionCode 10078 / primaryCpuAbi armeabi-v7a。**原 tv.iqtvlite.app/com.cjlhll.iptv 及数据保留。**

## 2. 灰屏回归 + SurfaceFlinger（PASS）

冷启 3 次（force-stop + am start -W）：

| 冷启 | TotalTime | first_frame ttff | 首帧前(t≈0.8s) | 首帧后(t≈5s) | 运动差分 |
|---|---|---|---|---|---|
| #1 | 6943ms | 7685ms | **0,0,0 主色0.999=黑底** | 77/78/76 主色0.107 | mean_abs_diff 26.3/57%（运动） |
| #2 | 6881ms | 4387ms | **0,0,0=黑底** | 65/69/69 主色0.114 | （PIL OOM，post 颜色=视频） |
| #3 | 7759ms | 9015ms | **0,0,0=黑底** | 61/64/66 | mean_abs_diff 22.0/45%（运动） |

- **首帧前=rootLayout 黑底（非灰）**；**首帧后=运动真实视频**（两帧差分变化像素 45–57%）。**无 10075 持久灰屏回归。**
- SurfaceFlinger（[sf-full-t40.txt](evidence/2026-11-20-ci-10078-t40/sf-full-t40.txt)）：`SurfaceView - ...PlayerActivity#0` **z=-2, isOpaque=1**（SurfaceView 固有分层，位于窗口之下）；`Background for -SurfaceView` z=-3；App 窗口层 z=0/1。
- **可见性判定**：零拷贝基座中 SurfaceView 为 FrameLayout 直子层、ComposeView/root 透明 → 窗口在视频区打孔透出视频；**截屏像素+运动差分证明“用户可见运动视频、不被遮挡”**（功能判据 PASS）。注：SurfaceView 固为 z=-2 属正常（非 10075“不透明 Compose 覆盖致灰”等价）；`activeBuffer` 转储字段不代表实际合成画面。

## 3. 性能（对比 CI10076/10077）

### 3.1 稳态 CPU（关键，含波动性说明）
- **1080p AVC（OMX.goke.video.decoder.avc）60s 连续采样 12 点**：min 46.7 / **median 49.5** / max 54.3 / mean 49.7%，**0/11 >82%** → **稳态约 50% 单核**，较 CI10076≈82% **显著降低**（零拷贝：视频经 SurfaceView/HWC，UI 线程不参与）。
- **瞬时尖峰（如实）**：冷启后初始化/EPG 刷新/缓冲期间观测到短时 86%–177%–270%（短暂），随后回落 ~50%。故 CPU 非线性恒定，存在初始化/EPG 尖峰。
- 纯看视频时 gfxinfo **Total frames=0、Missed Vsync=0、Slow UI=0**（UI 线程空闲无 jank）；Davey 稳态 0。
- **PSS**：约 71–85 MiB（多窗口 71204/81039/85259/89833 KiB 不等）；较 CI10076≈72.8MiB 相当或略高。

### 3.2 sidebar 打开/浏览视频是否跟随卡（用户重点）
`reset gfxinfo` 后 LEFT 开抽屉 + 8×DPAD_DOWN + 4×DPAD_UP + 关闭（CCTV1）：

| 指标 | 数值 | 解读 |
|---|---|---|
| gfxinfo（浏览期） | 301 帧、Janky **80.07%**、50th 30ms、90th **200ms**、95th **500ms**、Missed Vsync 44、Slow UI 92 | **sidebar Compose UI 自身仍 jank** |
| IQTV-METRICS 视频 | **dropped=0 / rebuffers=0** | **视频未被 UI 卡顿拖累（解耦成功）** |
| 浏览后运动 | diff 35.8/67% | 视频仍在播放 |

**判定**：用户所报“开左侧 sidebar 视频跟随卡”——**视频不再跟随卡（dropped0/rebuffer0，PASS）**；**sidebar UI 本身浏览仍明显 jank（80%/90th200ms）**，为 UI 侧残余（EPG/抽屉重组），非视频。

### 3.3 冷启 / 切台 / 长播
- 冷启 TotalTime 6881–7759ms；first_frame 3.2–9.0s（本窗口偏慢，含慢源/网络与首帧等待）。
- **zap 30 次（DPAD_DOWN）**：fgOurs 30/30，prepare 30，first_frame 30（0 错台），**TTFF min348/p50 923/p95 1453/max1583ms，maxDropped 0，maxRebuffers 1**。
- 长播（CPU/PSS/rebuffer/dropped 长时）本轮未单独跑完整30min——t41/t32 已示稳态与零 rebuffer；本任务重点已覆盖稳态 CPU/侧栏。标“长播30min未在本轮完整重跑(可t40追加)”。

## 4. 频道键（PASS）

面板关闭实测：166 CHANNEL_UP→ff1163ms 换台；167 CHANNEL_DOWN→ff1165ms；92 PAGE_UP→ff1176ms；93 PAGE_DOWN→ff610ms（均新 prepare+first_frame）。**面板打开时 166→prepare=0、DPAD_DOWN→prepare=0（不误触发换台，仅 UI 导航）。PASS。**

## 5. 回归

- **TIV 关键（10078 实测，PASS）**：TIV-04/05 双列抽屉（频道+节目单，聚焦）；TIV-06 分组列（全部/央视频道/卫视/…）；TIV-07 频道 OK 抽屉保持；TIV-10 BACK 一次关闭回视频（tiv-back ~1.1MB 真实画面）；TIV-11 设置（源配置/更新EPG/检查更新）；TIV-13 信息条（“6. CCTV-6 电影/解放·终局营救/1920x1080/avc”）。
  - 其余 TIV（01/02/03/08/09/12/14/15/16/17/18/23）本轮未逐条重跑；业务逻辑与 10073 一致（t39：PlaybackIntent/PlayerDrawer 自7f6e2d7零变更、PlayerActivity 仅渲染基座重构），10073 有证据；标“未在本轮 10078 逐条重跑”。
- **SR01/02/03**：t39 确认源码自 7f6e2d7 零变更（PlaybackIntent 未动、JNI/R8/UA 未改），SR 逻辑与 10073 相同；10073 clean 设备证据。**本轮未在 10078 重跑**（受时间/源限制），标“依赖源码不变+10073证据，未在10078重验”。
- **硬解**：logcat `OMX.goke.video.decoder.avc`/`OMXVDEC` 存在 → AVC 硬件解码正常。
- **AudioSink 时间戳不连续**：本轮 AVC/mpeg-L2 频道窗口 `UnexpectedDiscontinuity` 计数 **0**（当前无 AAC 音轨则无该事件）；此前 CI10076 在 AAC 信道见 64 条；t39 确认未改音频代码 → **未在本次窗口复现，但潜在仍在**（需 AAC/4K 信道专项长测确认）。

## 6. 逐项 通过/失败/未验证

| 项 | 结果 |
|---|---|
| 身份/安装/尺寸≤30MB/保留原包 | 通过（14,126,355B） |
| 灰屏回归（首帧前黑非灰/首帧即运动画面/退出无灰） | **通过**（冷启×3） |
| SurfaceFlinger 视频层可见不被遮挡 | 通过（功能判据：截屏+运动差分；SurfaceView 固 z=-2） |
| 稳态 CPU vs 10076≈82% | **通过：1080p AVC 稳态 median≈49.5%，0/11>82%**（注：冷启/EPG 瞬时尖峰 86–270%） |
| PSS | ~71–85MiB（相当/略高于 10076） |
| sidebar 视频是否跟随卡 | **视频不卡(dropped0/rebuffer0) 通过**；**sidebar UI 自身仍 jank(80%/90th200ms)—残余** |
| 冷启 TotalTime / TTFF | 6.9–7.8s / 3.2–9.0s（偏慢、慢源） |
| zap≥30 TTFF | 通过（p50 923/p95 1453，0 dropped） |
| 频道键 166/167/92/93 + 面板不误触 | **通过** |
| TIV01–18/23 关键 | 部分在10078实测(04/05/06/07/10/11/13通过)，其余依赖10073 |
| SR01/02/03 | 未在10078重跑（源码零变更t39+10073 clean） |
| 硬解 OMX.goke AVC | 通过 |
| AudioSink 异常 | 本窗口0(无AAC)；潜在仍在，需AAC信道专项 |
| 长播30min | 未在本轮完整重跑 |

## 7. “老机流畅”判断

- **明显改善**：稳态 1080p AVC CPU median≈49.5%（<82%）、UI 线程空闲无 jank、视频经 SurfaceView/HWC 流畅、sidebar 不再拖累视频、zap p50 923ms；灰屏与崩溃均已解决。
- **未完美（残余）**：①sidebar 的 Compose UI 浏览仍 jank（80%/90th200ms）；②冷启初始化/EPG 刷新 CPU 瞬时尖峰（86–270%）与冷启偏慢（6.9–7.8s，含慢源）；③PSS 略高；④4K/AAC 信道专项未测、长播30min未重跑。
- **判定：达标“可用且明显更流畅”，未达“完美流畅”**。不判失败（无灰屏回归、无崩溃、稳态 CPU 显著降、视频解耦），但 sidebar UI jank 与初始化尖峰需后续优化方可达“老机完全流畅”。
- 未改代码、未改原包数据；设备现为可用 CI10078。
