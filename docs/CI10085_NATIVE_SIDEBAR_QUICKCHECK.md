# CI10085 原生侧栏快验（selector 焦点 jank / GAP2 / 回看）— jank 未达门禁、日期落位修正

2026-11-20，acceptance2（t76 attempt 1）。对象：`tv.iqtvlite.app.validation` **10085 / 2.2.0-tivimini-69c614be-validation**（CI10085：焦点/选中改 StateListDrawable+ColorStateList selector、行内 3 View、itemAnimator=null/hasFixedSize、日期覆盖层修正到 633dp 基座右缘、筛选后焦点重置；run 44679621598/source 69c614be）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p，density 240/1.5x）在线。

> 模型不读图：视觉按源码/资源/UI bounds 描述，截屏留存供人工复核。

## 0. 结论摘要（两个决定性结论）

- **① sidebar jank：未达门禁。** probe(启动后8s) **100% janky / 50th 200 / 90th 600 / 95th 700ms(28帧)**；稳定(40s，重前台)**86.84% / 50th 36 / 90th 150 / 95th 200ms(38帧)**。对照 **CI10083(84.44/36/400/800)**、**CI10078(80.07/30/200/500)**：
  - **稳定态 90th 150ms<200、95th 200<500（首次在稳定态达 90th/95th 门禁）**；
  - **但 janky 仍 86.84%>80%，且 probe(8s) 仍 100%/90th600（未达标）**。→ **未稳定 <80%/200ms**（8s 采样点失败；janky% 未 <80%）。视频 dropped=0/rebuffer=0。
- **② GAP2 日期落位：修正 PASS。** 日期列头 **`[769,0][949,57]`**，右缘 949px = **633dp×1.5=949.5px = 基座右缘**（频道列 0–450、节目列 450–900、基座 0–949），**不再落到屏幕最右(1920)悬在视频上**；日期列可聚焦(焦点 [769,57][949,123])。**日期行文本未捕获、date OK 仅筛选未验证**；RIGHT from Groups→Channels 通过；LEFT 回列焦点部分未验证。
- **回看：本轮未取证（unverified）** —— 节目 OK 未落历史项，无 CatchupHelper 日志；Back 回 B 直播未验证。
- **不崩：PASS**（20/20，0 duplicate-key/FATAL）；**不灰/BACK 无闪 PASS**。
- **冷启 6.24–6.31s（改善）、PSS 52–62MiB（改善）、CPU 峰 309/334/177%（仍高）**；频道键 166/167 PASS、面板内不误触。

## 1. 身份与安装（PASS）

| 项 | 值 |
|---|---|
| 主 APK SHA256 | a7e6daa9470dbc9c8a16f65cdfd63ad1223cf5223acd4ec75cda7db89c52c120（10,208,256B，match） |
| test SHA256 | 5628fd54c79c5e4e58b8009b71c28f93f0620f6884c25b1dd7ad66b1211c19d2（502,955B，match） |
| 包/版本 | tv.iqtvlite.app.validation / 10085 / 2.2.0-tivimini-69c614be-validation；label=IQTV Lite 验证版 |
| min/target/ABI | 24/35；arm64-v8a+armeabi-v7a |
| 签名SHA256 | ea0367a50625473517ae33c294cbeea9fb89faacce8c67d17797055a8f1f79ad |

backup 10083 后仅替换 validation（Success）；安装后 base.apk 复算=`a7e6daa9…`（match）。原 tv.iqtvlite.app/com.cjlhll.iptv 保留。

## 2. sidebar jank（核心，未达门禁）

`scripts/sidebar_jank_probe.sh` 等价（force-stop→start→sleep8→gfxinfo reset→LEFT→8×DOWN+4×UP→BACK）：

| 版本/条件 | Total frames | Janky | 50th | 90th | 95th | Missed Vsync |
|---|---|---|---|---|---|---|
| CI10078 基线 | 301 | 80.07% | 30ms | 200ms | 500ms | 44 |
| CI10083 probe | 45 | 84.44% | 36ms | 400ms | 800ms | 8 |
| **CI10085 probe(8s)** | 28 | **100%** | 200ms | **600ms** | 700ms | 3 |
| **CI10085 稳定(40s)** | 38 | **86.84%** | 36ms | **150ms** | **200ms** | 0 |

- **稳定态 90th 150ms、95th 200ms（首次稳定态达 90th<200/95th<500）**；**但 janky 86.84%>80%、probe(8s) 100%/90th600 未达标**。样本 28–38 帧（原生侧栏仅焦点重绘，帧数少、janky% 噪声大）。
- **video dropped=0/rebuffer=0**（浏览期间视频不卡）；稳定后两帧 diff 26.0/60.9%（运动视频）。
- 8s probe 与 40s 稳定差异大：8s 点仍与启动后台（EPG/logo）竞争，导致 50th 200ms/90th600。

## 3. GAP2（633dp 基座 + 覆盖层落位）

density=240(1.5x)，633dp→949.5px：

- **基座列**：频道 `[0,57][450,123]`（0–450=300dp×1.5）、节目 `[450,57][900,123]`（450–900）、基座右缘 949（633dp×1.5）。
- **日期覆盖层**：头 **`日期 [769,0][949,57]`** → 左 769（=513dp×1.5，leftMargin=633-120）、右 949=基座右缘 → **日期列右缘对齐基座、不再悬屏幕最右**（GAP2-01 **PASS**）；日期列可聚焦（focus [769,57][949,123]）。
- 分组覆盖层：`分组 [0,0][225,57]`（151dp×1.5≈226）。
- **RIGHT from Groups→Channels**：焦点回到频道行 `[0,57][450,141]` → PASS。
- **日期行文本/date OK 仅筛选**：本轮 dump 未捕获日期行文本（列头可见、行未取到），**未验证**。
- **LEFT 回列（含筛选后）不跳错**：本轮 LEFT 后焦点在频道行，未完全验证（需更细 dump）。

## 4. 不崩 / 不灰 / BACK（PASS）

- 进播放器不崩（pid 15019 无 FATAL）；first_frame avc1080 ttff2310ms；截屏 mean 82/74/61 主色 0.176（真实画面非灰）；BACK 前后多色无灰闪。
- **反复开关 20/20 次**（含分组层、DPAD 上下）→ ok=20/20，进程存活，**0 FATAL/duplicate-key/Key already used**。

## 5. 回看（未取证）

- 节目列导航到历史节目后 OK：本轮 **无 CatchupHelper/buildCatchupUrl 日志、无 catchup prepare** → **未触发回看（unverified）**（可能未落到历史项/该频道无 catchup 能力→应 Toast，但未捕获）。
- **B 的 UA/Referer 明文请求头未取证**；**Back 回 B 直播未验证**。

## 6. 视觉（源码/资源层，模型不读图）

- t73：行背景 StateListDrawable(state_focused→#14FFFFFF)、文字 ColorStateList(state_focused→INK, duplicateParentState)、accent StateListDrawable(state_selected 蓝/state_activated 琥珀)；▶ 合入 primary 文本→每行 3 View；itemAnimator=null/hasFixedSize/cacheSize8；焦点移动不 rebind。
- 截屏留存：[ui-drawer-10085.png](evidence/2026-11-20-ci-10085/ui-drawer-10085.png)、[ui-programs-10085.png](evidence/2026-11-20-ci-10085/ui-programs-10085.png)、[ui-dates-10085.png](evidence/2026-11-20-ci-10085/ui-dates-10085.png)（供人工看焦点无边框/选中细条/日期落位）。

## 7. 冷启 / CPU / PSS

| 冷启 | TotalTime | first_frame | init CPU 峰值(单核=100) | PSS(TOTAL KiB) |
|---|---|---|---|---|
| #1 | 6312ms | 1636ms | **309%**（309,296,212,179,148,150） | 62188 |
| #2 | 6289ms | 1447ms | **334%**（334,255,203,154,71,76） | 61171 |
| #3 | 6241ms | 2843ms | **177%**（154,172,177,53,87,80） | 53428 |

- TotalTime **6.24–6.31s（较 10083 6.4–6.45、10081 7.1 改善）**；first_frame 1.4–2.8s。
- init CPU 峰 **309/334/177%**（#3 177<270，但 #1/#2 >300，未稳定 <270%）。
- PSS **52–62MiB（改善，较 10083 58–59、10078 71–85）**。

## 8. 频道键

- 干净态 166→prepare1/ff757；167→prepare1/ff693。**换台 PASS**。
- 面板内 166→prepare=0（不误触）。PASS。

## 9. 逐项 通过/失败/未验证

| 项 | 结果 |
|---|---|
| 身份/安装/保留原包 | 通过 |
| sidebar jank 是否 <80%/200ms | **未达（probe 100%/90th600；稳定 86.84%/150ms）**；稳定态 90th/95th 首次达门禁，janky 仍 >80% |
| GAP2 日期落位（基座右缘/不悬视频/不透明） | **通过**（日期头 [769,0][949,57]=基座右缘949） |
| 日期行可见/date OK 仅筛选 | **未验证**（列头可见，行文本未捕获） |
| RIGHT from Groups→Channels | 通过 |
| LEFT 回列（含筛选后）不跳错 | 部分未验证 |
| 回看（历史 OK/UA/Back） | **未验证**（本轮未触发 catchup） |
| 不崩（20+）/不灰/BACK | 通过 |
| 视觉（selector 焦点无边框/选中细条） | 源码符合；像素需人工看图 |
| 冷启/CPU 峰/PSS | 6.3s 改善/峰 309–334% 仍高/PSS 52–62MiB 改善 |
| 频道键 166/167/面板不误触 | 通过 |

## 10. 最小复现（jank 未达标）

```
adb -s 192.168.2.245:5555 shell am force-stop tv.iqtvlite.app.validation
adb -s 192.168.2.245:5555 shell am start -W -n tv.iqtvlite.app.validation/com.cjlhll.iptv.SplashActivity
sleep 8
adb ... shell dumpsys gfxinfo tv.iqtvlite.app.validation reset
adb ... shell input keyevent 21            # 开抽屉
# 8× DPAD_DOWN(20) + 4× DPAD_UP(19)
adb ... shell dumpsys gfxinfo tv.iqtvlite.app.validation framestats  # probe: 100% / 50th200 / 90th600
# 等 40s 再重复（重前台）→ 86.84% / 36 / 150 / 200
```
日期：开抽屉→RIGHT×2→日期头 [769,0][949,57]（基座右缘）；日期行/OK 筛选需补验证。

## 11. 判定

- **sidebar jank 仍未稳定 <80%/200ms**（8s probe 100%/600 失败；稳定 90th 150 达 90th 门禁但 janky 86.84%>80%）→ **未达门禁**；但相较 10083（84.44/400）稳定态 90th/95th 有改善（150/200），selector 方向正确。
- **GAP2 日期落位修正 PASS（基座右缘 949px，不悬视频）**。
- **回看本轮未取证（unverified）**；日期行/筛选、LEFT 回列未完全验证。
- 不崩/不灰/BACK/频道键 PASS；冷启 6.3s 与 PSS 52–62MiB 改善、CPU 峰仍高。
- 未改代码、未改原包数据；设备现为可出画、日期落位已修正但 jank 仍未达门禁的 CI10085。
