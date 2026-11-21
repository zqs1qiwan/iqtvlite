# 灰屏与卡顿真机诊断（CI10075）

2026-11-19，acceptance2（t29，只读取证，未改代码）。对象：`tv.iqtvlite.app.validation` 版本 **10075 / 2.2.0-tivimini-b4455ac4-validation**（已装 CI10075，source b4455ac4）。测试机 `192.168.2.245:5555`（TR100-G9 API28 armeabi-v7a 1080p，在线）。本报告所用“灰屏”判断来自**像素统计（PIL）非肉眼**；截图已保留供人复核。

## 1. 结论（先给判定）

用户所述“启动即灰屏、退出前才闪一次画面、操作严重卡顿”**已客观复现**，可归因为两类问题叠加：

- **主因（遮挡/合成）：视频 `SurfaceView` 被不透明的 Compose 窗口层遮住**。PlayerView 安装在 Compose `AndroidView` 内且为 **默认 SurfaceView**（独立 SurfaceFlinger 图层 `z=-2`、位于 App 窗口层 `z=0` 之后）；Compose `MaterialTheme Surface/Box` 以不透明背景（`0xFF1E1E1E`）铺满窗口，SurfaceView 未获得透明“打孔”，导致视频帧虽在解码/排队却**始终不可见**，屏幕呈纯灰。窗口销毁（退出）时 SurfaceView 才短暂露出 → “退出前闪一次画面”。工作副本里 developer 已有未提交的 **TextureView 修复**（`player_texture_view.xml`，注释即写明“SurfaceView inside Compose … hidden (grey) until exit, so use surface_type=texture_view”），但**该修复不在 CI10075 内**。
- **次因（卡顿/冷启）：UI 线程严重卡顿 + 冷启慢**。gfxinfo **100% janky**、50th 高达 1050ms、90th 2850ms、95th 3400ms、missed vsync 9、帧超时 11；OpenGLRenderer 多次 `Davey! duration=1930/2877/3442ms`（主线程单帧卡 2–3.4s）；冷启 TotalTime 3.0–16.0s。另有 `AudioSink$UnexpectedDiscontinuityException`（音轨时间戳不连续）与 4K HEVC（3840×2160）软/硬解压力。

## 2. 设备与包身份

- 设备：`192.168.2.245:5555` 在线（注意同网另有 `192.168.2.132`，本诊断始终 `-s 192.168.2.245:5555`）。
- 已装 validation：`versionCode=10075 versionName=2.2.0-tivimini-b4455ac4-validation min24 target35 primaryCpuAbi=armeabi-v7a`（即 CI10075）。
- 原装对照包 `tv.iqtvlite.app`（0.1.0-test）与 `com.cjlhll.iptv` 均在；未改其数据。
- 视频宿主：可执行 `dumpsys SurfaceFlinger --list` 见 `SurfaceView - ...PlayerActivity#0`（**是 SurfaceView，不是 TextureView**）。

## 3. 现象与可复现步骤（冷/热）

复现（多次一致）：
```
adb -s 192.168.2.245:5555 shell am force-stop tv.iqtvlite.app.validation
adb -s 192.168.2.245:5555 shell am start -W -n tv.iqtvlite.app.validation/com.cjlhll.iptv.SplashActivity
# 观察 5-15s：整屏纯灰；日志 IQTV-METRICS 有 decoder/first_frame；退出瞬间偶见画面
```
- 冷启 TotalTime 多次：3314 / 16047 / 3539 / 3077 ms（波动大；一次 16s）。
- 首个频道 `video/hevc 3840x2160` + AAC，`first_frame ttff_ms=5245`（后续切台 789–2130ms）；**解码确有 first_frame，但画面不显示**。
- DPAD 上下连切 3 个频道（均 4K HEVC）、数字跳转 CCTV1（`video/avc 1920x1080`，first_frame 789ms）——**所有频道、含 1080p H.264，屏幕始终纯灰**，说明与 4K 无关。

## 4. 灰屏的客观像素证据（PIL，非目视）

脚本 [analyze_screen.py](evidence/2026-11-19-greyscreen/analyze_screen.py) 对 `screencap` PNG 求均值/标准差/主色桶占比：

| 场景 | 截图 | mean RGB | std RGB | 主色桶占比 | 结论 |
|---|---|---|---|---|---|
| 冷启 ~7s | launch-1-screen.png | 46,46,46 | 0,0,0 | 1.000 | 纯灰 |
| 首帧后 ~12s | launch-1-screen-late.png | 46,46,46 | 0,0,0 | 1.000 | 仍纯灰（首帧后仍无画面） |
| 切 CCTV1(1080p AVC) | chan1.png | 41,41,41 | 0 | 1.000 | 纯灰 |
| 热恢复(切前台) | warm-resume.png | 30,30,30 | 1.000 | 纯灰=Compose Surface 0x1E1E1E |
| 打开抽屉 | drawer-open.png | 49.7 | 0.926 | 抽屉 Compose UI 可见覆盖在灰底上 |
| **原装 tv.iqtvlite.app** | orig-app.png | **156.5,75.9,72.7** | 28/43/42 | **0.125** | 有真实画面内容（非灰） |

文件大小旁证：所有纯灰 PNG≈11.5–11.7KB；`drawer-open.png`=297KB（有 UI）、`orig-app.png`=904KB（有画面）——纯灰帧几乎不可压缩。

## 5. Surface/图层证据（遮挡判定）

`dumpsys SurfaceFlinger`（[sf-full.txt](evidence/2026-11-19-greyscreen/sf-full.txt)，解析 [layers-z-opaque.txt](evidence/2026-11-19-greyscreen/layers-z-opaque.txt)）validation 相关图层：

| 图层 | z | isOpaque | size |
|---|---|---|---|
| `6146f07 tv.iqtvlite.app.validation/...PlayerActivity#0`（App 窗口层） | 0 | 0 | 1920x1080 |
| `SurfaceView - ...PlayerActivity#0`（视频） | **-2** | **1** | 1920x1080 |
| `Background for -SurfaceView - ...PlayerActivity#0` | -3 | 0 | — |

**视频 SurfaceView 在 App 窗口层之后（z=-2<0）**。SurfaceView 要可见必须由窗口在其区域打透明孔；此处窗口被 Compose 不透明 Surface 填充，(打孔未生效) → 视频被遮挡。

**热恢复 vs 冷启的 frame-latency（`dumpsys SurfaceFlinger --latency 'SurfaceView …'`）**：
- 热恢复：返回**真实帧时间戳**（如 `4874056120526 4874085252621 …`）→ SurfaceView 确实在产出帧；但屏幕 mean RGB=**30**（=Compose Surface 色）→ **有帧但被不透明层盖住 = 遮挡实锤**。
- 冷启部分采样：latency 全 0（`0 0 0`）→ 冷启阶段 SurfaceView 一度未产出（surface attach/生命周期问题）；但无论 0 或非 0，屏幕均纯灰。

## 6. 卡顿证据（gfxinfo / Davey）

`dumpsys gfxinfo tv.iqtvlite.app.validation`（[gfxinfo-validation.txt](evidence/2026-11-19-greyscreen/gfxinfo-validation.txt)）：
```
Total frames rendered: 11
Janky frames: 11 (100.00%)
50th percentile: 1050ms   90th: 2850ms   95th: 3400ms   99th: 3400ms
Number Missed Vsync: 9    Number Slow UI thread: 11    Number Frame deadline missed: 11
```
多次 reset 复测仍 100% janky（50th 69–1050ms，90th 150–2900ms）。OpenGLRenderer（[logcat-errors.txt](evidence/2026-11-19-greyscreen/logcat-errors.txt)）多帧 `Davey! duration=1930ms / 2877ms / 3442ms` —— 主线程多次单帧卡 2–3.4 秒，直接对应“操作严重卡顿”。

## 7. CPU / PSS

- `/proc/<pid>/stat` 前后 1s 差分：utime 8836→8853、stime 788→791，合计 ~20 ticks/s = **约 0.20 核 ≈ 20% 单核**（app 进程；[cpu-procstat.txt](evidence/2026-11-19-greyscreen/cpu-procstat.txt)）。`top -n1` 瞬时 app≈2%、RES≈140M。
- `dumpsys meminfo`：**TOTAL PSS 59747 KiB（≈58.3 MiB）**，SwapPSS 0（[meminfo-validation.txt](evidence/2026-11-19-greyscreen/meminfo-validation.txt)）。

## 8. logcat 关键错误

- `E/MediaCodecAudioRenderer: Audio sink error` → `AudioSink$UnexpectedDiscontinuityException: Unexpected audio track timestamp discontinuity: expected 1000021418666, got 1000021696000`（音轨时间戳不连续，A/V 同步异常；见 [logcat-errors.txt](evidence/2026-11-19-greyscreen/logcat-errors.txt)）。
- 解码正常链：`OMXVDEC / OMX.goke.video.decoder.avc`、`MediaCodec ... setting surface generation`、`SurfaceUtils connecting to surface ... set up nativeWindow ... for 1920x1080` → 硬解在做，surface 已连，但被遮挡。
- `E/HWComposer: getColorModes ... Unsupported(8)`（次要）。

## 9. 同机流畅对照基线（原装包）

- 原装 `tv.iqtvlite.app`：启动 TotalTime 5217ms；截图 mean RGB **156/76/73、主色桶 0.125**（有真实画面）；gfxinfo `Total 11 / Janky 8 (72.73%) / 50th 22ms / 90th 40ms / 95th 4500ms`（[gfxinfo-original-app.txt](evidence/2026-11-19-greyscreen/gfxinfo-original-app.txt)）。原包在**同一台机器**能显示画面且 UI 帧时（50th 22ms）显著优于 validation（50th 1050ms、100% janky）→ 说明盒子具备显示能力，问题在 validation 的 UI/合成与负载。
- 参考 `com.cjlhll.iptv` 为另一下载的 IPTV 应用，本次未强行置前台对照（避免干扰），保留作后续可选。

## 10. 初步归因

1. **遮挡/合成（主因，解释灰屏与“退出才闪”）**：PlayerView=默认 **SurfaceView**，位于 Compose 窗口层之后（z=-2）；Compose `MaterialTheme Surface`/`Box` 首帧即用不透明 `0xFF1E1E1E` 铺满窗口，SurfaceView 区未透明化 → 视频被盖。热恢复时 SurfaceView 已有帧（latency 非 0）但屏幕仍为 Compose 色(30)，直接证明“帧在、被遮挡”。窗口销毁时才露出 → 退出闪一次。**非** 4K/解码/无帧问题（1080p AVC 同样灰，且解码 first_frame 正常）。
2. **lifecycle/attach（次因）**：冷启阶段 SurfaceView 一度 latency=0（未产出）；且冷启 TotalTime 3–16s 抖动，存在 SurfaceView/EGL/decoder 初始化与 attach 时序问题。
3. **Compose 过重/主线程卡顿（次因，解释卡顿）**：100% janky、Davey 单帧 2–3.4s、50th>1s；疑似重排/重绘或解码/EGL 初始化的主线程占用，v7a 老机尤甚。
4. **音频**：AudioSink 时间戳不连续异常（A/V 同步）；叠加前述音轨格式问题（MPEG-L2 无解码器），但与本灰屏无直接关系。

## 11. 待开发修复建议（只建议，未改）

1. **改用 TextureView**：PlayerView 使用 `surface_type=texture_view`（工作副本已有 `res/layout/player_texture_view.xml` 及工厂 inflate，注释即点明此 bug）。改为 TextureView 后视频作为普通 View 参与窗口合成，不再被 Compose 不透明层遮挡。构建新 CI pair 后在冷/热启动验证“首帧即可见、无退出闪帧”。
   - 备选：`SurfaceView.setZOrderOnTop(true)`/`setZOrderMediaOverlay(true)` + 窗口透明；但 Compose 下 TextureView 更稳。
2. **透明化叠加**：确保视频区域上方无实心 Compose 背景；把 `AndroidView(PlayerView)` 放到不透明 `Surface` 之外，或该区域背景 alpha=0；避免整屏 `0xFF1E1E1E` 覆盖视频层。
3. **降卡顿**：把解码器/EGL 初始化、EPG 解析、列表构建移出主线程；减少 PlayerDrawer/EPG/ChannelSwitchOverlay 的重组与重绘；开启硬件合成/减少 overdraw；对老 v7a 避免默认 4K HEVC（优先 1080p AVC 或降分辨率）。
4. **音频**：修 AudioSink 时间戳不连续（音轨选择/AudioSink buffer 配置）；结合 MPEG-L2 无解码器问题决定是否加 FFmpeg MP2 或换 AAC 源。
5. **回归门禁**：新 pair 必须真机冷启（force-stop 后）确认“首帧前不出灰屏/首帧后立即可见”，并附 gfxinfo jank、SurfaceView/TextureView latency、退出无闪帧证据；不能仅以 READY/first_frame 日志当可见。

## 12. 证据清单与限制

- 原始截图：launch-1-screen(.late).png、cold-rep1/2.png、chan1.png、chan-step1..3.png、warm-resume.png、drawer-open.png、exitf-1..10.png、**orig-app.png**（全在 evidence/2026-11-19-greyscreen/）。
- 原始 dump：sf-full.txt、layers-z-opaque.txt、gfxinfo-validation.txt、gfxinfo-original-app.txt、meminfo-validation.txt、cpu-procstat.txt、logcat-errors.txt、gfxinfo-framestats.txt、launch-1-start.txt。
- **模型不能看图**：灰屏/画面判断用 PIL 像素统计（均值/标准差/主色桶）与 PNG 体积旁证，截图保留人工复核；未以肉眼断言色彩/运动。
- 未改任何代码、未改原包数据、未强停用户其它应用（仅 HOME+启动本包）；测试机独占取证。
- 工作副本中 developer 的 TextureView 修复属其进行中改动，本报告仅作对照提及，不计为已交付/已验证；CI10075 内仍为 SurfaceView。
