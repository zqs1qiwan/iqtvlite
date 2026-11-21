# CI10079 性能第二阶段快验（sidebar jank / 冷启 / CPU / PSS）— 目标未达成

2026-11-20，acceptance2（t45 attempt 1）。对象：`tv.iqtvlite.app.validation` **10079 / 2.2.0-tivimini-62eedde9-validation**（CI10079，t42 性能第二阶段：移除对焦行每帧 marquee / 列表稳定 key / 后台拉取延后 / Logo 预取限 60 / LRU96；run 44660869560/source 62eedde9）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）在线。

## 0. 结论摘要

- **不崩/不灰/BACK 无闪：PASS**（进入播放器不崩、首帧即出画非灰、BACK 未见灰闪）。
- **sidebar jank（重点）：未改善、反而更差** —— 浏览期 gfxinfo **96–100% janky、90th 250–950ms、95th 300–1200ms**，对比 CI10078(80%/200/500) 明显变差；渲染帧数显著少(72–102 vs 301)。**视频仍 dropped=0/rebuffer=0（解耦维持），但 sidebar UI 仍明显 jank。**
- **冷启：无改善** —— TotalTime **7009/6818/6854ms**（CI10078 6.9–7.8s，基本持平/略快）；first_frame 1.7–3.2s（本次较快）；**启动/EPG 窗口 CPU 尖峰 336–397%，未低于 86–270% 目标（反而更高，含 t42 延后到 8/12s 的后台拉取落在采样窗内）**。
- **PSS：改善** —— 冷启后 ~62–73MiB（CI10078 ~71–85MiB），LRU/Logo 限流有效。
- **频道键 166/167 换台 PASS**；TIV 冒烟（双列抽屉/信息条）PASS；AudioSink 本轮窗口 0（AAC 未见）。
- **判定：sidebar jank 与冷启/CPU 尖峰两项核心目标未达成（按任务“sidebar 仍明显 jank/冷启无改善”需给最小复现）；PSS 改善、无回归。** 不宣称性能第二阶段达标。

## 1. 产物身份与安装（PASS）

| 项 | 值 |
|---|---|
| 主 APK SHA256 | 60efd72f3e085003a15a5bd977d0695e143fcd926016a670a4c08e684b40a89d（14,109,967B，match） |
| test SHA256 | 2262b13a41883dd934f586acedba65bc7e342de6d2d3020d158c324ee3c78df4（502,955B，match） |
| 包/版本 | tv.iqtvlite.app.validation / 10079 / 2.2.0-tivimini-62eedde9-validation |
| min/target/ABI | 24/35；arm64-v8a+armeabi-v7a |
| 签名SHA256 | 7bb63183bac20a9570b91938be024b84fc42674e325c5a5c63b18fd00156a60f（两包同） |

backup 10078 沙箱后仅替换两个 validation 包（Success）；安装后 base.apk 复算 = `60efd72f…`（match），versionCode 10079。**原 tv.iqtvlite.app/com.cjlhll.iptv 及数据保留。**

## 2. 不崩/不灰/BACK（PASS）

配源后进入 PlayerActivity：pid 14228 存活、**无 FATAL/Force finishing**；`first_frame video/avc 1920x1080 ttff 1663ms`；截屏 mean 58/53/54、主色桶 0.222（真实画面，**非灰**）；BACK 前后截屏均多色内容（无灰闪；单次 BACK 未销毁，符合双 Back 逻辑）。与 CI10078 同架构（rootLayout 黑底→首帧即画面）。

## 3. sidebar jank（重点，未改善/变差）

`reset gfxinfo` 后 LEFT 开抽屉 + 8×DPAD_DOWN + 4×DPAD_UP：

| 条件 | Total frames | Janky | 50th | 90th | 95th | Missed Vsync |
|---|---|---|---|---|---|---|
| CI10078（t40 基线） | 301 | **80.07%** | 30ms | **200ms** | 500ms | 44 |
| CI10079 早段(启动后~3s, 后台延后拉取窗内) | 72 | **100%** | 117ms | **950ms** | 1200ms | 30 |
| CI10079 稳定(启动后~31s) | 102 | **96.08%** | 53ms | **250ms** | 300ms | 36 |

- **两轮 CI10079 均比 CI10078 差**（janky 96–100% vs 80%；90th 250–950 vs 200；帧数 72–102 vs 301）。视频 **dropped=0/rebuffer=0**（解耦维持，视频不卡），但 **sidebar UI 自身渲染更慢/更 janky**。
- 注：设备后台有 `com.shafa.market:xg_service_v2` 反复崩溃重启等干扰，会加剧 UI jank；但两轮一致偏差提示 t42 的 sidebar 改动未带来改善，需开发本地复现确认。

## 4. 冷启（无改善）+ CPU 尖峰 + PSS

冷启 ×3（force-stop + am start -W；启动后 0.5s 起每 2s 采样 CPU）：

| 冷启 | TotalTime | first_frame ttff | 启动窗 CPU 峰值(单核=100) | PSS(TOTAL KiB) |
|---|---|---|---|---|
| #1 | 7009ms | 2906ms | **343%**（343,305,317,311,266,124） | 63650 |
| #2 | 6818ms | 1682ms | **336%**（334,336,334,334,314,248） | 66518 |
| #3 | 6854ms | 3198ms | **397%**（311,397,280,289,251,118） | 72986 |

- **TotalTime 6.8–7.0s（≈ CI10078 6.9–7.8s，无改善）**；first_frame 1.7–3.2s（本次较快）。
- **启动/初始化窗 CPU 峰值 336–397%（多核并发）**，未低于 t42 所述目标 86–270%，反而更高；部分因 t42 把 playlist 拉取延后到 8s、EPG 延后到 12s，正好落在我 0–14s 采样窗内（网络/解析）。→ **“CPU 尖峰降低”未达成**。
- **PSS 62–73MiB（较 CI10078 71–85MiB 改善）** —— Logo LRU96/预取限 60 有效。

## 5. 频道键 / TIV / 音频

- **频道键 PASS**：166 CHANNEL_UP→ff1052ms 换台；167 CHANNEL_DOWN→ff1524ms 换台。
- **TIV 冒烟 PASS**：双列抽屉（CCTV-1..5 + 节目）；信息条“1. CCTV-1 综合 / 谷雨第26集 / 1920x1080 / avc”。
- **AudioSink**：本轮 AVC/mpeg-L2 窗口 `UnexpectedDiscontinuity` 计数 0（未在 AAC 频道触发）；硬解 OMX.goke AVC 正常。

## 6. 逐项 通过/失败/未验证

| 项 | 结果 |
|---|---|
| 身份/安装/保留原包 | 通过 |
| 不崩 / 首帧即出画非灰 / BACK 无闪 | **通过** |
| sidebar jank vs CI10078(80%/200/500) | **失败：未改善、更差（96–100% janky，90th 250–950ms）**；视频 dropped=0 仍解耦 |
| 冷启 TotalTime vs 6.9–7.8s | **未改善（6.8–7.0s）** |
| 启动/EPG CPU 尖峰 vs 86–270% | **未达成（336–397%）** |
| PSS vs 71–85MiB | **改善（62–73MiB，通过）** |
| 频道键 166/167 | 通过 |
| TIV 冒烟（双列/信息条） | 通过 |
| 24TIV/SR/长播 | 未验证（t44 全量） |

## 7. 最小复现（sidebar jank / 冷启未改善）

```
adb -s 192.168.2.245:5555 shell am force-stop tv.iqtvlite.app.validation
adb -s 192.168.2.245:5555 shell am start -n tv.iqtvlite.app.validation/com.cjlhll.iptv.SplashActivity
# 等 ~30s（让后台拉取稳定）后：
adb ... shell dumpsys gfxinfo tv.iqtvlite.app.validation reset
adb ... shell input keyevent 21            # 开 sidebar
# 8× DPAD_DOWN(20) + 4× DPAD_UP(19) 浏览
adb ... shell dumpsys gfxinfo tv.iqtvlite.app.validation   # 实测 96% janky / 90th 250ms（CI10078 为 80%/200）
```
冷启：`am force-stop` 后 `am start -W` TotalTime≈6.8–7.0s（未改善），启动 0–14s CPU 峰值 336–397%。

## 8. 结论

- **目标未达成**：sidebar 浏览 UI jank 未改善（反而更差）、冷启 TotalTime 无改善、初始化 CPU 尖峰未降低（336–397%）。
- **达成/正向**：PSS 改善（62–73MiB）；不崩、不灰、首帧即出画、BACK 无闪；视频与 sidebar 解耦维持（dropped=0）；频道键/TIV 正常。
- 判定：**性能第二阶段（sidebar jank/冷启/CPU）真机未达预期**，需开发据最小复现继续定位（或回退/重做 sidebar 相关改动；建议加严格 sidebar 浏览 gfxinfo 门禁）。设备侧背景干扰(shafa xg_service)与测量方法（延后后台拉取落入启动采样窗）已注明。
- 未改代码、未改原包数据；设备现为 CI10079（可出画，但 sidebar 仍 jank）。
