# CI10096 MP2 软解 资源分场景量化 + 音频完整验证

2026-11-21，acceptance2（t121 attempt 1；t119 已并入本任务）。对象：`tv.iqtvlite.app.validation` **10096 / 2.2.0-tivimini-ae531f7f-validation**（MP2 软解版；pair 主 `1e518f49…dad6` 12,928,915B / test `0ff2f7ee…0641` / 签名 `773bf65a…`）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）在线。测试源 `https://iptv.laobaitv.net`（CCTV 走组播 RTP 192.168.2.1）。

> 模型不读图；音频以 dumpsys audio / audio_flinger / IQTV-METRICS 计数据判定。

## 0. 结论摘要（关键：软解代价只在瞬态，稳态可忽略）

- **分场景稳态 appCPU/PSS（各静置 30s 取中位，同机同源）**：

| 场景 | appCPU 中位 | PSS | 备注 |
|---|---|---|---|
| ①空闲（MainActivity 配置页，无播放） | **0%**（样本 0,0,0.2,0） | **62,416 KiB(~61MiB)** | 无解码 |
| ③MP2 播放（CCTV-1 `audio/mpeg-L2`，FFmpeg 软解） | **3.2%**（3,3.5,3.2,5.2） | **58,329 KiB(~57MiB)** | audio_advancing 正常 |
| ②其他频道播放（同法静置） | **2%**（1.4,2,2.3,2.1） | **58,596 KiB(~57MiB)** | 与 MP2 基本一致 |

- **结论：高 CPU/PSS 只出现在切台/抽屉等过渡期（t120 记录的 88%、82MiB 属瞬态切台负载，非稳态）。稳态 MP2 软解 appCPU 仅 ~3.2%、PSS ~57MiB，与空闲(0%/61MiB)、其他频道(2%/57MiB)相当 → MP2 软解代价在稳态可忽略，不是全局/常驻回退。**（PSS 三场景均 57–62MiB 平稳，无 MP2 专属 +10MiB 跳变。）
- **AAC 场景 CPU/PSS 本会话未能独立隔离**（切台抓取 `audio_format` 不可靠；但 t120 已证 AAC `audio/mp4a-latm` 有声且 audio_advancing，且“其他频道”稳态 2%/57MiB 与 MP2 相同）。
- **音频完整**：MP2 有声 PASS（audio/mpeg-L2 + AudioTrack state:started + audio_advancing + 1 active track + focus GAIN）；AAC 有声 PASS（t120）；AVC 正常；MP2↔AAC 10 次切换 dead=0 不崩；60s 无 AudioSink UnexpectedDiscontinuity/error；本 app 无崩溃。
- **.so 加载时机**：`/proc/<pid>/maps` 被系统权限拒绝（shell 与 run-as 均读到空）→ **映射时机无法直接观测**；代理证据：三场景 PSS 平稳（57–62MiB）无 MP2 专属跳变 → 无明显“仅 MP2 常驻 +10MiB”；libffmpegJNI.so 随 APK。
- **`FfmpegLibrary.getVersion()` 无法经 adb 直取**（无日志/钩子）；但 MP2（audio/mpeg-L2，平台无解码器）能出声即**功能性地证明 FFmpeg 扩展已被加载且 FfmpegAudioRenderer 成功实例化 → AUDIO-01（反射构造器 keep）实际已解**。

## 1. 身份/安装（PASS）
- 已装 versionCode=10096 / 2.2.0-tivimini-ae531f7f-validation（沿用 t120 安装；base SHA=`1e518f49…` match）。APK 含 libffmpegJNI.so(arm64/armeabi)+NOTICE；原包 tv.iqtvlite.app/com.cjlhll.iptv 保留。

## 2. 分场景资源（原始）

- **空闲**：`mResumedActivity=MainActivity`（配置页无播放）；appCPU 中位 0%，PSS TOTAL 62416。
- **MP2 播放**：`audio_format mime=audio/mpeg-L2 rate=48000 ch=2 s13`；`audio_advancing since_prepare_ms=1310`；appCPU 中位 3.2%，PSS TOTAL 58329。
- **其他频道播放**（25 次切台后落定）：appCPU 中位 2.0%，PSS TOTAL 58596。
- 早前 t120 的“稳态 appCPU p50~88%、PSS~82MiB”实为**切台后紧接采样**（含过渡/EOB 重建），本会话充分静置 30s 后 MP2 仅 3.2% → 修正为瞬态。

## 3. .so 加载时机
- 直接法失败：`cat /proc/<pid>/maps | grep ffmpeg` 与 `grep -c '.so'` 经 shell 与 run-as 均返回 0 → 该设备拒绝非自身进程 /proc/<pid>/maps 读取（权限），**不能据此判定 .so 未加载**。
- 代理法：三场景 PSS 57–62MiB 平稳，MP2 相对空闲未出现 +10MiB 级跃升 → 无“MP2 专属常驻”证据；.so 随 APK，按需由 Media3 extension 加载（EXTENSION_RENDERER_MODE_ON，仅 MP2 触达）。
- 结论：**无法直接量化映射时机（权限限制）**；间接表明 MP2 未带来显著常驻内存增量。

## 4. 音频完整（合并 t120 + 本会话）

| 项 | 结果 | 证据 |
|---|---|---|
| MP2 有声 | PASS | `audio_format mime=audio/mpeg-L2`+`audio_advancing`+`AudioTrack(…)::start`+`dumpsys audio ID:503 u/pid 10092/24649 state:started`+audio_flinger `1 Tracks of which 1 are active`+focus GAIN |
| MP2 60s 连续性 | 通过（无断续/阻塞） | 60s 窗口 `UnexpectedDiscontinuity/AudioSink error/dropped=0`；pid 存活；（注：audio_advancing 仅在会话起始记录，60s 内不再重复打印，故“0 行”≠静音） |
| AAC 有声 | PASS | t120：`audio_format mime=audio/mp4a-latm`+audio_advancing |
| AVC 视频 | 正常 | 1080p 出画（截图 mean 132/138/132） |
| MP2↔AAC 切换 10+ | PASS | t120：10 次交替 mp4a-latm/mpeg-L2 dead=0；累计 20+ 切台 pid 稳定 |
| 崩溃 | 无本 app 崩溃 | am_proc_died 仅 com.shafa.market（无关） |
| 音画同步（粗测） | 近似同步（未量化毫秒） | 同一 prepare 周期 first_frame 与 audio playout 均随即推进；无帧级工具，未做严格口型/事件时间差 |

## 5. 观感/品牌 + 冷启
- 品牌/观感与 CI10093 同源一致（label IQTV Lite 验证版，同品牌资源）；截图 scenario-mp2.png / mp2-60s.png / mp2-final-audio.png。
- **冷启×3**：TotalTime **7533/7351/7337ms，中位 7351ms**；PSS 72865/56823/65541 KiB（~55–71MiB）。与 t120(~7.3–7.6s) 一致。

## 6. 逐项 通过/失败/未验证

| 项 | 结果 |
|---|---|
| 三场景 appCPU/PSS（空闲/AAC/MP2） | 通过：空闲0%/61MiB、MP2 3.2%/57MiB、其他2%/57MiB；**AAC 场景未独立隔离**（t120证有声） |
| .so 加载时机（是否仅MP2常驻） | 未验证（/proc/maps 权限拒绝）；代理：PSS 平稳无MP2专属增量 |
| MP2 有声/60s无断续/AAC有声/AVC正常/切换10+ | 通过 |
| 音画同步（毫秒级） | 未验证（粗测近似） |
| FfmpegLibrary.getVersion() 有值 | 未直接取证（无日志钩子）；功能证明扩展已加载/AUDIO-01 已解 |
| 观感/品牌/冷启 | 通过（同源一致；冷启中位 7351ms） |

## 7. 判定

- **核心结论（回答“仅MP2 or 全局”）：稳态下 MP2 软解代价可忽略（appCPU ~3.2% vs 空闲 0%、PSS ~57 vs ~61MiB），不构成全局/常驻回退；t120 的高值属切台瞬态。**
- 音频功能完整：MP2 有声、AAC 有声、AVC 正常、MP2↔AAC 切换不崩、60s 无 AudioSink 阻塞、本 app 无崩溃。
- 未验证/受限：AAC 场景 CPU/PSS 未独立隔离；.so 映射时机（权限）；严格毫秒音画同步；getVersion 直取（功能已证扩展加载）。
- 未改代码、未改原包数据；设备现为 CI10096（MP2 有声、稳态低耗）。
