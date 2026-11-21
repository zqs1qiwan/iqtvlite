# CI10081 综合版快验（sidebar jank / 品牌 / 不崩）— sidebar 未改善

2026-11-20，acceptance2（t53 attempt 1）。对象：`tv.iqtvlite.app.validation` **10081 / 2.2.0-tivimini-8e2e064e-validation**（CI10081 综合版：sidebar 方角+焦点框+撤回 t42/t46 延后 + TiviMate 视觉核心项 + IQTV Lite 品牌接入；run 44665902878/source 8e2e064e）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）在线。

> 模型**不能读图**：视觉部分按可验证的资源/清单/manifest 与 UI hierarchy 描述；截屏仅保存供人工复核。

## 0. 结论摘要

- **sidebar jank（重点）：未改善，反而更差** —— 官方 probe(启动后8s 浏览) **98.55% janky、50th 125 / 90th 1100 / 95th 1350ms**；稳定(启动后~40s) **97.09%、50th 48 / 90th 250 / 95th 300ms**。对比 CI10078(80%/30/200/500)、CI10079(96%/250/300) → **未改善（8s 点明显更差）**。视频 **dropped=0/rebuffer=0 仍解耦（视频不卡）**。
- **不崩：PASS** —— 进入播放器不崩、首帧即出画(非灰)、BACK 无灰闪；真实源 **反复开关抽屉 20/20 次不崩、0 duplicate-key/FATAL**。
- **品牌/视觉：PASS（资源层面）** —— label=IQTV Lite 验证版；manifest icon+roundIcon+banner(tv_banner)；adaptive icon(mipmap-anydpi-v26/ic_launcher.xml+round)；splash_mark/splash_background；品牌色/视觉改动在包内。截屏供人工看方角/焦点框。
- **冷启：TotalTime 无改善**（7158/7076/9592ms）；first_frame 1.6–2.3s；**init CPU 峰值 331/326/332%（仍高，未降）**；**PSS 改善 59–69MiB**。
- 频道键 166/167 PASS；TIV 冒烟 PASS；AudioSink 本窗口 0；OMX.goke AVC 硬解正常。
- **判定：sidebar jank 未改善（关键目标失败，且较 CI10078 更差）；品牌/不崩/不灰/PSS 达标；冷启与 CPU 峰未达预期。**

## 1. 产物身份与安装（PASS）

| 项 | 值 |
|---|---|
| 主 APK SHA256 | 5b0fdce6c9134ef5da7410351504c41c3fba2fd94c77e07de8c42ef0c59e089f（10,404,860B，≤30MB，match） |
| test SHA256 | 5e8674dae7034ae5e13a447ae76cce7c830b9742f86f76732b15377ed5c4482e（502,955B，match） |
| 包/版本 | tv.iqtvlite.app.validation / 10081 / 2.2.0-tivimini-8e2e064e-validation；**label=IQTV Lite 验证版** |
| min/target/ABI | 24/35；arm64-v8a+armeabi-v7a |
| 签名SHA256 | 34a031e525ee6f970dbe7048e23d87aede792b1183383f36603d55681e5df90a（两包同） |

backup 10079 沙箱后仅替换 validation 两包（Success）；安装后 base.apk 复算=`5b0fdce6…`（match），versionCode 10081。**原 tv.iqtvlite.app/com.cjlhll.iptv 及数据保留。**

## 2. 品牌/视觉（资源层面 PASS）

- **label**：aapt `application-label:'IQTV Lite 验证版'`（全语言一致）。
- **manifest**：`icon=@mipmap/ic_launcher`、`roundIcon=@mipmap/ic_launcher_round`、`banner=@drawable/tv_banner`、`theme=@style/...`。
- **adaptive icon**：`mipmap-anydpi-v26/ic_launcher.xml`、`ic_launcher_round.xml`；各密度 `ic_launcher.png`/`ic_launcher_round.png`/`ic_launcher_foreground.png`。
- **splash**：`drawable/splash_background`(XML)、`drawable-nodpi/splash_mark.png`。
- **tv_banner**：`drawable-nodpi/tv_banner.png`（manifest banner 已指向）。
- 截屏已存：[ui-player.png](evidence/2026-11-20-ci-10081/ui-player.png)、[ui-drawer.png](evidence/2026-11-20-ci-10081/ui-drawer.png)（供人工看方角/焦点框/信息条；模型不读图）。
- **UI hierarchy**（抽屉打开 dump）：98 nodes / 33,672 bytes（存在 focusable 行；具体圆角/边框像素需人工看图）。
- 注：视觉“方角/2dp 焦点框/去阴影”代码层已按 t50 落地（包内含 brand_colors/资源），**最终视觉合规需人工看截屏确认**（模型不判图）。

## 3. 不崩 / 不灰 / BACK（PASS）

- 配源进入 PlayerActivity：pid 7423 存活、**无 FATAL/Force finishing/duplicate key**；`first_frame video/avc 1920x1080 ttff 2364ms`；截屏 mean 49/41/38、主色桶 0.242（真实画面非灰）。
- BACK：前后截屏均多色内容（55/47/41 → 64/49/38），无灰闪。
- **崩溃压力**：真实源 20 次开/关抽屉（含 group 层切换、DPAD 上下）→ **ok=20/20，进程始终存活，0 FATAL/duplicate-key**（t46 key 加固有效）。

## 4. sidebar jank（重点，未改善/更差）

官方 `scripts/sidebar_jank_probe.sh` 等价步骤（force-stop→start→sleep 8→gfxinfo reset→LEFT→8×DOWN+4×UP→BACK）：

| 版本/条件 | Total frames | Janky | 50th | 90th | 95th | Missed Vsync |
|---|---|---|---|---|---|---|
| CI10078 基线(t40) | 301 | **80.07%** | 30ms | **200ms** | 500ms | 44 |
| CI10079 稳定(t45) | 102 | 96.08% | 53ms | 250ms | 300ms | 36 |
| **CI10081 probe(8s)** | 69 | **98.55%** | 125ms | **1100ms** | 1350ms | 30 |
| **CI10081 稳定(~40s)** | 103 | **97.09%** | 48ms | **250ms** | 300ms | 28 |

- **8s probe 点显著更差（98.55%/90th 1100）**；稳定点 97.09%/250 与 CI10079 相当、仍差于 CI10078 80%/200。**→ sidebar jank 未改善（较 CI10078 基线更差）。**
- **视频 dropped=0/rebuffer=0**（两轮均），视频与 UI 解耦维持（视频不卡）。
- 原因线索：t50 撤回延后→playlist 0s/EPG 1.5s 即时后台拉取，与 8s 早期浏览重叠竞争（probe 更差）；稳定后仍 97% 说明 Compose 抽屉本身渲染重（裁剪/文本/焦点派生未下沉），t50 方角/边框简化未降 jank。设备后台 `com.shafa.market:xg_service_v2` 反复崩溃有干扰（已注明）。

## 5. 冷启 / CPU 峰 / PSS

| 冷启 | TotalTime | first_frame | init CPU 峰值(单核=100) | PSS(TOTAL KiB) |
|---|---|---|---|---|
| #1 | 7158ms | 2150ms | **331%**（317,306,331,206,192,108） | 69224 |
| #2 | 7076ms | 2321ms | **326%**（326,298,280,171,196,86） | 60514 |
| #3 | 9592ms | 1556ms | **332%**（332,326,328,323,203,206） | 60151 |

- **TotalTime 7.1–9.6s（未改善 vs 10078 6.9–7.8s）**；first_frame 1.6–2.3s（较快）。
- **init CPU 峰 326–332%（未降，仍 >270% 目标）** —— 即时后台拉取(0/1.5s)与初始化同窗竞争。
- **PSS 59–69MiB（较 10078 71–85MiB 改善）**。

## 6. 频道键 / TIV / 音频

- **频道键 PASS**：166 CHANNEL_UP→ff1200ms 换台；167 CHANNEL_DOWN→ff596ms 换台。
- **TIV 冒烟 PASS**：双列抽屉（CCTV-1..5+节目）；信息条“1. CCTV-1 综合 / 谷雨第27集 / 1920x1080 / avc”。
- AudioSink 本窗口 0（AVC/mpeg-L2 未触发 AAC）；硬解 OMX.goke AVC 正常。

## 7. 逐项 通过/失败/未验证

| 项 | 结果 |
|---|---|
| 身份/安装/体积/保留原包 | 通过（10,404,860B，label IQTV Lite 验证版） |
| 品牌/manifest/adaptive icon/banner/splash | **通过（资源层面；视觉需人工看图）** |
| 不崩 / 首帧即出画非灰 / BACK 无闪 | 通过 |
| 反复开关抽屉 20+ 次不崩（key 加固） | **通过（20/20，0 duplicate-key）** |
| sidebar jank vs CI10078(80/200/500) | **失败：未改善、更差（probe 98.55%/90th1100；稳定 97.09%/250）**；视频 dropped=0 |
| 冷启 TotalTime vs 6.9–7.8s | 未改善（7.1–9.6s） |
| init/EPG CPU 尖峰 | **未降（326–332%）** |
| PSS vs 71–85MiB | **改善（59–69MiB，通过）** |
| 频道键 166/167 | 通过 |
| TIV 冒烟 | 通过 |
| 24TIV/SR/长播 | 未验证（t52 全量） |

## 8. 最小复现（sidebar jank）

```
adb -s 192.168.2.245:5555 shell am force-stop tv.iqtvlite.app.validation
adb -s 192.168.2.245:5555 shell am start -W -n tv.iqtvlite.app.validation/com.cjlhll.iptv.SplashActivity
sleep 8
adb ... shell dumpsys gfxinfo tv.iqtvlite.app.validation reset
adb ... shell input keyevent 21            # 开抽屉
# 8× DPAD_DOWN(20) + 4× DPAD_UP(19)
adb ... shell dumpsys gfxinfo tv.iqtvlite.app.validation framestats  # 98.55% janky / 90th 1100ms
# 等 ~40s 稳定后重复 → 97.09% / 90th 250ms
```

## 9. 判定与建议

- **sidebar jank 关键目标未达成（且较 CI10078 基线更差）**；冷启 TotalTime、init CPU 峰未改善；**品牌接入、不崩(key 加固)、不灰、PSS 改善达标**。
- 建议：t50 的方角/焦点框/撤回延后未解决 jank；需更深层 root cause（Perfetto framestats 逐帧、叶子 derivedState 焦点下沉、限制可视项复用、文本测量缓存、后台拉取错峰/限流避免 8s 竞争）。加严格 sidebar 浏览 gfxinfo 门禁（janky<80%、90th<200ms）。设备侧 shafa 干扰与测量点(8s vs 稳定)已注明。
- 未改代码、未改原包数据；设备现为可出画但 sidebar 仍 jank 的 CI10081；原包保留。
