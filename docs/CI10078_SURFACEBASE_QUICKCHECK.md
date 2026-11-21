# CI10078 零拷贝 SurfaceView 基座真机快验（t41）

2026-11-20，acceptance2（t41 attempt 1）。对象：`tv.iqtvlite.app.validation` **10078 / 2.2.0-tivimini-c82593f5-validation**（CI10078，零拷贝 SurfaceView 基座 + 透明 Compose 浮层 + LoadControl 崩溃修复，run 44651507384 / source c82593f5）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）在线。

> 出画/画面判断用 PIL 像素统计 + 两帧差分（运动量），非肉眼；截图保留供人工复核。

## 0. 结论摘要（先给用户四问）

- **USAB2-01 崩溃已修复**：进入 PlayerActivity **不崩**（pid 存活、无 FATAL/Force finishing），**能起播、有真实画面**（非灰，两帧差分证明是运动视频）。
- **频道键 166/167 生效（换台成功）**；PAGE_UP/DOWN(92/93) 亦换台；**面板内 DPAD/频道键不误触发换台**。
- **整体 CPU 显著下降**：稳态单核 **p50 ≈45%（27–50%）vs CI10076 ≈82%**；纯看视频时 UI 线程 0 帧（零拷贝，视频走 SurfaceView/HWC，UI 不参与）。
- **开/浏览左侧 sidebar 时视频不再“跟随卡”**：浏览期间 **video dropped=0 / rebuffer=0**（视频与 UI 解耦）。**但 sidebar 的 Compose UI 本身浏览时仍 janky**（69.7% janky、90th 350ms/95th 600ms）——UI 侧残余，视频不受影响。
- 灰屏不回归：首帧前为 **rootLayout 黑底**（非灰），首帧后立即运动画面。
- 备注：PSS **89833KiB（≈87.7MiB）较 10076≈72.8MiB 偏高**，需留意。

## 1. 产物身份与安装（PASS）

| 项 | 值 |
|---|---|
| 主 APK SHA256 | ed165e6e06dd9b7a9e86d74294b2db0aaf45016c098364d12f1e3fd94eab7285（14,126,355B，match） |
| test SHA256 | 5564f5a21df3d1968d6b21a3eae7cf1ad6324eba5188c692c95fba085725ac71（502,955B，match） |
| 包/版本 | tv.iqtvlite.app.validation / 10078 / 2.2.0-tivimini-c82593f5-validation |
| min/target/ABI | 24/35；arm64-v8a+armeabi-v7a |
| 签名SHA256 | bc52f452bb0677a726fb8c96f53a8846dffdebe8fee5fa70a8dd8135f05a641c（两包同） |

安装：backup 10076 沙箱后仅替换两个 validation 包（Success）；安装后 base.apk 复算 = `ed165e6e…`（match），versionCode 10078 / primaryCpuAbi armeabi-v7a。**原 tv.iqtvlite.app/com.cjlhll.iptv 及数据保留。**

## 2. 冒烟：不崩 / 起播 / 真实画面（PASS，最高优先）

- 冷启（2 次）：`PlayerActivity` 前台、进程存活（pid 1716/5755/6419）、**无 FATAL / Force finishing**；此前 CI10077 的 `IllegalArgumentException minBufferMs...` 不再出现（LoadControl 已改 1000/6000/300/1000）。
- 起播：`first_frame video/avc 1920x1080 ttff 1380–2969ms`。
- 画面：截屏 PIL mean 65–99 / 78–133 / 70–196、主色桶 0.03–0.24（非纯色）；**两帧差分 mean_abs_diff=41–86、变化像素 65–99% → 确认是运动中的真实视频（非灰/非静帧）**。
- 首帧前：mean 0,0,0（rootLayout 黑底），**非灰屏**。

## 3. SurfaceFlinger 视频层（可见性 PASS，注意与字面 z/1x1 判据的差异）

`dumpsys SurfaceFlinger`（[sf-full-10078.txt](evidence/2026-11-20-ci-10078/sf-full-10078.txt)）validation 图层：
- `SurfaceView - ...PlayerActivity#0`：**z=-2, isOpaque=1, activeBuffer=[1x1,RGBx_8888]**；`Background for -SurfaceView` z=-3；App 窗口层 z=0/1。

**说明**：零拷贝 SurfaceView 基座里 SurfaceView 固有地处于窗口之下(z=-2)，这是 SurfaceView 的正常分层（并非 10075 被遮问题的等价）。**关键差异**：10078 的 ComposeView/root Box 透明、SurfaceView 为 FrameLayout 直子层 → 窗口在视频区透明(打孔)→ 视频通过孔可见。**功能判据“视频可见不被遮挡”已由 §2 的截屏像素+运动差分证明（PASS）**；`activeBuffer=1x1` 是 SF 转储字段不代表实际合成画面（实际截屏含运动视频）。故不按任务字面“非z=-2/非1x1”判，而以“用户可见运动视频”判为可见/不遮挡。

## 4. 频道键（PASS）

面板关闭（播放态）实测 `input keyevent`：
| 键 | 结果（IQTV-METRICS 新 prepare+first_frame） |
|---|---|
| 166 CHANNEL_UP | prepare c5203… first_frame 986ms，换台成功 |
| 167 CHANNEL_DOWN | prepare c7583… first_frame 1274ms，换台成功 |
| 92 PAGE_UP | prepare c5203… first_frame 1338ms，换台成功 |
| 93 PAGE_DOWN | prepare c7583… first_frame 834ms，换台成功 |

面板打开（LEFT 抽屉）时：`166` → **prepare=0（未换台/不误触发）**；`DPAD_DOWN` → **prepare=0**（仅 UI 导航，不换台）。**面板内 DPAD/频道键不误触发换台（PASS）。**

## 5. 性能（对比 CI10076）

- **稳态 CPU（单核=100%，/proc 多次差分）**：27.4 / 49.7 / 44.7 / 49.6% → **p50≈44.7%（区间27–50%）**；**CI10076 ≈82% → 显著下降**（约降一半）。原因：零拷贝 SurfaceView 视频经 HWC 合成，UI 线程不参与渲染。
- **稳态 gfxinfo（纯看视频 12s）**：Total frames **0**（UI 不绘制）、Janky 0、Missed Vsync 0、Slow UI 0 → UI 线程空闲无卡顿。
- **PSS**：89833KiB（≈87.7MiB，SwapPss0）——**高于 CI10076≈72.8MiB**，需留意（SurfaceView+ComposeView 双宿主/图形缓冲）。
- **zap 20 次（DPAD_DOWN）**：prepare 20 / first_frame 20（0 错台），**TTFF min409 / p50 899 / p95 1567 / max1567ms，maxDropped 0**（与 CI10076 p50 883/p95 1431 相当）。
- **冷启 TotalTime**：7070 / 6894ms（含首帧等待，与 10076 6.6–9.7s 同量级；受慢源影响）。

### 5.1 sidebar 打开/浏览时视频是否跟随卡

`reset gfxinfo` 后 LEFT 开抽屉 + 8×DPAD_DOWN + 4×DPAD_UP（浏览）再关闭：

| 指标 | 数值 | 解读 |
|---|---|---|
| gfxinfo（浏览期） | 165 帧、Janky 115(**69.7%**)、50th 29ms、90th **350ms**、95th **600ms**、Missed Vsync 30、Slow UI 65 | **sidebar 的 Compose UI 自身仍 janky** |
| IQTV-METRICS 视频 | **dropped=0 / rebuffers=0** | **视频未被 UI 卡顿拖累（解耦成功）** |
| 浏览后视频运动 | 两帧 diff 36.6、42% 像素变化 | 视频仍在播放（未卡死） |

**判定**：用户所报“开左侧 sidebar 视频跟随卡”——**视频不再跟随卡（dropped=0/rebuffer=0，PASS）**；但 **sidebar UI 本身浏览仍明显 jank（90th 350ms/95th 600ms）**，属 UI 侧残余（EPG 节目列/抽屉重组），非视频问题。若要求侧栏滚动也顺滑，需后续优化 Compose 抽屉/EPG。

## 6. 逐项 通过/失败/未验证

| 项 | 结果 |
|---|---|
| 身份/安装/备份/保留原包 | 通过 |
| 进 PlayerActivity 不崩 / 起播 / 真实画面(非灰) | **通过**（USAB2-01 修复确认；运动视频证明） |
| SurfaceFlinger 视频可见不被遮挡 | **通过（功能判据：截屏+运动差分）**；注：SurfaceView 固为 z=-2，但窗口透明可见 |
| 频道键 166/167(+92/93) 换台 | **通过**（4 键均触发换台） |
| 面板内 DPAD/频道键不误触发 | 通过（166/DPAD 下 prepare=0） |
| 稳态 CPU vs 10076≈82% | **通过：p50≈45%（显著降）**；PSS 偏高(87.7MiB)需留意 |
| sidebar 打开/浏览视频跟随卡 | **视频不卡(dropped0/rebuffer0) 通过**；**sidebar UI 自身仍 jank（69.7%/90th350ms）—残余** |
| 冷启 TotalTime/TTFF | 通过（6.9–7.1s / ff 1.4–3.0s） |
| zap ≥20 TTFF | 通过（p50 899 / p95 1567，0 dropped） |
| 灰屏不回归 | 通过（首帧前黑、首帧后运动画面） |
| 24TIV/SR/长播/AudioSink | 未验证（留 t40 全量） |

## 7. 结论

- **可用性：通过** —— USAB2-01 崩溃已修复，能起播、可正常观看运动画面。
- **四问：** ①CHANNEL 键 166/167 换台 ✓；②起播/换台快（zap p50 899ms）✓；③整体更顺、CPU 显著降至 ≈45%（vs 82%）✓；④开/浏览 sidebar 视频不再跟随卡（dropped0）✓ **但 sidebar UI 自身仍有 jank（残余）**。
- **残余/建议**：sidebar 的 Compose UI 浏览卡顿、PSS 偏高（87.7MiB）需后续优化；TIV/SR/长播/音频等完整回归归 t40（待 t39 复审后）。不称“完美流畅”。
- 未改代码、未改原包数据；设备现为可用 CI10078。
