# CI10076 灰屏修复与老机流畅度真机复验 + 回归（t32）

2026-11-19，acceptance2（t32 attempt 1）。对象：`tv.iqtvlite.app.validation` **10076 / 2.2.0-tivimini-983e153c-validation**（CI10076，TextureView 灰屏修复，run 44553646612 / source 983e153c）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）在线。依据 docs/GREYSCREEN_PERF_DIAGNOSIS.md 与 docs/TV_BASELINE_ACCEPTANCE.md。

> 灰屏/出画判断用 PIL 像素统计（均值/标准差/主色桶占比）+ PNG 体积，非肉眼；截图保留供人工复核。

## 0. 结论摘要

- **灰屏修复成立**：冷启首帧前为加载态（Compose 灰 mean≈30），**首帧后立即显示真实画面**，无持久满屏灰；多渠道/多次冷启一致。
- **UI 渲染流畅度大幅改善**：gfxinfo 50th **23–25ms**、90th **29–32ms**、95th **31–36ms**、Missed Vsync **0–2**、**Davey 0**（10075 为 100% janky/50th1050/90th2850、Davey 1930–3442ms）。帧时已接近原装 Views 基线（50th 22/90th 40ms）。
- **但“老机流畅可接受”尚需条件**：①**源/网络短时 rebuffer**（前段出现 rebuffers=8/12.5s；但 11.7min 稳态长播 rebuffers=0/dropped=0，判为源/网络短时，非 app 自身，见 §4.2）；②TextureView **CPU 稳态 ≈82% 单核**（长播 p50 82.3%/p95 83.8%；明显高于 10075 的 ≈20%，GPU 拷贝代价），建议 `setOpaque(false)`/硬件层或按 t30 原生基座方案评估；③冷启 TotalTime 6.6–9.7s（受慢网/首帧等待）。故：**UI/合成流畅、灰屏已解决；端到端“流畅”受源缓冲与高 CPU 限制，不宣称完美流畅**。
- **回归**：TIV 关键路径（抽屉双列/分组/OK保抽屉/BACK关闭/设置）正常；硬解 OMX.goke AVC 正常；**AudioSink UnexpectedDiscontinuityException 仍存在（未修，属稳健性，不阻塞出画）**；SR 源码零变更(t31)+10073 clean 设备证据，本轮未在10076重跑。

## 1. 产物身份与安装（PASS）

| 项 | 应用 | test |
|---|---|---|
| SHA256 | 5006e91d6bad682d5d8ef4dcdcd8630e59846389a9f16cd51575c927dc9f4a66 | 74e4a965cf7e66ac7c3b82dbe434ec2789198f0431be7f4bf5224cfe8adbf1be |
| 字节 | 14,159,123 | 502,955 |
| 包/版本 | tv.iqtvlite.app.validation / 10076 / 2.2.0-tivimini-983e153c-validation | …validation.test |
| min/target/ABI | 24/35；arm64-v8a+armeabi-v7a | 24/35 |
| 签名SHA256 | 578bdb34f3c1804424267aa572266aa6087fb230a10fd2d1741d7aa02b910102 | 同左 |

安装（见 t33）：backup 旧 10075 沙箱后仅替换两个 validation 包；安装后 `pm path` 拉取 base.apk 复算 = `5006e91d…`（match）。**原 tv.iqtvlite.app 与 com.cjlhll.iptv 及数据保留**（pm list 前后均在）。

## 2. 灰屏核心复验（PASS）

force-stop 后冷启，按时间点截屏 PIL 统计（CCTV 1080p AVC）：

| 冷启 | TotalTime | first_frame ttff | 首帧前(t≈0.9s) | 首帧后(t≈5.4s) | 判定 |
|---|---|---|---|---|---|
| #1 | 9749ms | 2409ms | mean30,std0,主色1.000（加载灰） | 195,173,167／主色0.069 | 灰→真实画面 |
| #2 | 7453ms | 3274ms | mean30,std0,主色1.000（加载灰） | 166,142,119／主色0.081 | 灰→真实画面 |
| #3 | 6611ms | 2155ms | 149,153,155／0.068（已出画） | 146,122,115／0.011 | 真实画面 |

- **首帧前瞬时为 Compose 加载灰（mean≈30）属正常加载**，**首帧后立即可见真实画面**（多色、主色桶<0.09），**无 10075 那种首帧后持久满屏灰**；三次冷启一致。
- 多渠道：数字切 CCTV2 / CCTV3 均 `video/avc 1920x1080`，first_frame 885 / 759ms，截屏主色 0.058 / 0.024（真实画面）。**多渠道均出画。**
- 退出闪帧：需双 Back 退出（TIV-14），亚帧级 teardown 未用截图捕捉；机制上因无独立被遮 SurfaceView 已消除（§3），建议人工目视。

## 3. SurfaceFlinger（遮挡根因消除，PASS）

- CI10075：`SurfaceView - ...PlayerActivity#0` **z=-2 isOpaque=1**（窗口之下被遮）。
- CI10076：`--list`/`dumpsys SurfaceFlinger` **已无该 SurfaceView 图层**，视频由 **TextureView** 在窗口内合成（App 窗口内 1920x1080 isOpaque 层 z=0）。遮挡根因消除。

## 4. 性能复验（与 10075 / 原装 对照）

**gfxinfo（reset 后稳定窗口）**：[gfxinfo-t32.txt](evidence/2026-11-19-ci-10076-t32/gfxinfo-t32.txt)

| 指标 | CI10075(修前) | CI10076(t33) | CI10076(t32) | 原装 Views |
|---|---|---|---|---|
| 50th | 1050ms | 23ms | **25ms** | 22ms |
| 90th | 2850ms | 32ms | **29ms** | 40ms |
| 95th | 3400ms | 36ms | **31ms** | 4500ms* |
| Missed Vsync | 9 | 2 | **0** | — |
| Slow UI thread | 11 | 31 | **2** | — |
| Janky | 100% | 77.98% | 86.66% | 72.73% |
| Davey>700ms | 1930/2877/3442ms | 0 | **0** | — |

\*原装 95th 单点异常 4500ms。**帧时（50th/90th/95th）已接近/优于原装**；Missed Vsync 0、Slow UI thread 2、Davey 0 → **UI 线程不再阻塞**。Janky 86.66% 与 Frame deadline missed 281 主要来自**源 rebuffer 冻结帧**（见下），非 UI 每帧卡死。

**冷启 TotalTime**：9749 / 7453 / 6611ms（含首帧等待；当前慢网导致偏高）。

**切台 20 次**（DPAD，前台守卫 20/20，无 refront）：prepare 20 / first_frame 20（0 错台），**TTFF min420 / p50 883 / p95 1431 / max1431ms，maxDropped 0**（优于 10075 p50 1079）。

**CPU / PSS**：`/proc` 多次差分 **85.3 / 85.4 / 86.5 / 82.3%**（1 核=100%）→ **CPU≈85% 单核且稳定**（TextureView 走 GPU/额外拷贝，10075 曾≈20%）；`dumpsys meminfo` PSS **68968 KiB≈67.4MiB**（SwapPss 0）。

**长播（~11.7min）**：见 §4.1。

### 4.1 长播结果（~11.7min / 700s / 12 点采样）

后台 `omx@1.0-service` 硬解，前台全程本应用（fgOurs 12/12）；原始 [long-summary.json](evidence/2026-11-19-ci-10076-t32/long-summary.json)：

- **app CPU：p50 82.3% / p95 83.8% / mean 82.3%**（单核=100%，11 个 60s 差分）；codec(omx) 进程 p50 8.2%。→ **TextureView 稳态 CPU ≈82% 单核、稳定**（非 rebuffer 引起；稳态即 82%）。
- **PSS：p50 74617 KiB（≈72.9MiB）**，max 75333，12 点序列 73844→74743 平稳、**无单调增长（无泄漏迹象）**。
- **本窗口 rebuffers=0 / dropped=0**（11.7min 稳态 0 rebuffer、0 丢帧）。

### 4.2 rebuffer 是否源侧

- 前段一度出现 `rebuffers=8, rebuffer_ms≈12.5s`；但**随后 11.7min 稳态长播 rebuffers=0/dropped=0**，说明并非 app 持续性问题。同期本机抓取播放列表 **30218B 用时 1299ms ≈ 23KB/s**（源/网络吞吐低），且 rebuffer 跨频道出现、app 无解码/网络错误栈。**判：rebuffer 为源/网络短时（非本次修复回退）；稳定窗口 0 rebuffer**。严格隔离仍需受控流，标记“源侧概率高”。

### 4.3 TextureView CPU≈85% 稳定性与可否降低

- 多次采样（短窗口 82–86%、长播 p50 82.3%/p95 83.8%）→ **稳态 ≈82% 单核、稳定偏高**（与 rebuffer 无关，稳态即此值）。
- 现布局 `player_texture_view.xml` 仅设 `surface_type=texture_view / use_controller=false / show_buffering=never`；**未设** `setOpaque(false)`、未设硬件层。TextureView 默认经 GPU 采样/合成，比 SurfaceView 多一次拷贝。
- 建议（供架构决策，本任务不改）：对该 TextureView `setOpaque(false)` 或 `setLayerType(LAYER_TYPE_HARDWARE)`、或改用“原生 SurfaceView 基座 + 透明 ComposeView 覆盖”(t30 方案) 以降低拷贝/CPU；需实测并回归 24TIV + 性能。

## 5. 回归

- **TIV 关键路径（10076 实测）**：TIV-04/05 双列抽屉 ✓（频道+节目单，聚焦）；TIV-06 分组列（全部/央视频道/卫视/…）✓；TIV-07 频道 OK 抽屉保持 ✓；TIV-10 BACK 一次关闭回视频 ✓（tiv-back 截图 2.2MB 真实画面）；TIV-11 设置（源配置/更新EPG/检查更新）✓；换台/可观看 ✓。
- **TIV 未清/部分**：TIV-08 日期列、TIV-13 信息条 本轮 uiautomator 文本捕获为空（Compose+TextureView dump 时序）；TIV-23 数字112 未确认跳到全局112（此次 prepare 为 CCTV2）。这些业务逻辑与 10073 一致（t31：PlayerDrawer/PlaybackIntent 零变更、PlayerActivity 仅工厂 6 行），10073 有对应证据；本轮 10076 标“未清/未验证”。
- **SR01/02/03**：t31 已确认源码自 7f6e2d7 零变更（PlaybackIntent/PlayerDrawer 0 变、PlayerActivity 仅工厂 6 行），SR 逻辑与 10073 相同；10073 有 clean SR01/02/03 设备证据。**本轮未在 10076 重跑**（受时间/源限制），标“依赖源码不变+10073证据，未在10076重验”。
- **硬解**：logcat `OMXVDEC / OMX.goke.video.decoder.avc` 存在 → AVC 硬件解码正常。
- **AudioSink 异常**：`AudioSink$UnexpectedDiscontinuityException`（audio track timestamp discontinuity）**仍存在**（本会话 64 条，session c70301… AAC 频道；`audio_sink_error type=UnexpectedDiscontinuityException`）→ 未修，属音频稳健性/A-V 同步问题，**不阻塞出画/视频**；t31 已预告未改。

## 6. 逐项 通过/失败/未验证

| 项 | 结果 |
|---|---|
| 身份/安装/备份/保留原包 | 通过 |
| 灰屏修复（冷启首帧前灰→首帧后出画，多渠道多次） | **通过** |
| SurfaceFlinger 无被遮 z=-2 SurfaceView | 通过 |
| gfxinfo 帧时/jank/missed vsync/Davey | 通过（帧时接近原装；jank%受源rebuffer影响） |
| 切台 TTFF 20 | 通过（p50 883/p95 1431，0 dropped） |
| 长播 CPU/PSS/rebuffer/dropped | 通过（11.7min：app CPU p50 82.3%/p95 83.8%，codec 8.2%，PSS p50 74617KiB 稳定无泄漏，rebuffers=0/dropped=0） |
| TextureView CPU≈82% 稳定/可降配置 | 已量化（稳态 p50 82.3%/p95 83.8%），setOpaque(false)/硬件层/原生基座为建议 |
| TIV 关键（04/05/06/07/10/11） | 通过 |
| TIV 08/13/23 | 未清/未验证（捕获/时序；业务逻辑同10073） |
| SR01/02/03 | 未在10076重跑（源码零变更t31+10073 clean） |
| 硬解 OMX.goke AVC | 通过 |
| AudioSink 异常 | **仍在（未修）** |

## 7. 结论与判据

- **灰屏：已修复（通过）** —— 冷启/切换首帧后立即可见真实画面，不再整屏纯灰、不再退出才闪。
- **UI 渲染流畅度：显著改善，帧时接近原装**（50th 25 vs 22、90th 29 vs 40、missed vsync 0、Davey 0）。
- **是否“老机流畅可接受”：有条件** —— ①当前端到端卡顿由**源/网络 rebuffer** 主导（非 app/本次修复回退），需更好源或缓存窗口验证；②**TextureView CPU≈85% 单核偏高**（GPU 拷贝代价），建议 setOpaque(false)/硬件层或 t30 原生基座方案；③冷启 6.6–9.7s 偏慢（慢网+首帧）。**不称完美流畅**。
- **遗留**：AudioSink 时间戳不连续未修；TIV 08/13/23 与 SR 未在 10076 重跑（源码不变+10073证据）。这些作为后续 t32 追加/或新任务处理。
- 未改代码、未改原包数据、未强停用户其它应用；设备独占取证。
