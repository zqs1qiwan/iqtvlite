# CI10090 回退原版观感快验 — 观感回退、无蓝框、jank 达标

2026-11-20，acceptance2（t99 attempt 1）。对象：`tv.iqtvlite.app.validation` **10090 / 2.2.0-tivimini-165d94f1-validation**（CI10090：回退 tivimini 原版 Compose 抽屉，删原生侧栏、撤销 v2/v3 视觉、原版本就无蓝框；run 44707095454/source 165d94f1）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p，density 240/1.5x）在线。

> 模型不读图：观感按 UI bounds/像素统计/源码描述，截屏留存供人工确认。

## 0. 结论摘要（先给用户）

- **观感回退 PASS（截图见下）**：抽屉恢复原版 Compose 双列（频道+节目）、行 12dp 圆角、原字号/间距/台标、999dp 进度胶囊、18dp 容器（源码 t96）；**焦点无蓝色边框**（像素检测 focused row 蓝色像素=0，全抽屉仅 28 个分散蓝像素 frac 0.0001≈噪声）；频道行显示 `▶ 1. CCTV-1 综合` + `冬去春来第3集 · 播放中`；**不再是方角极简风**。
- **台标显示**：通道行台标区像素 mean 66/55/47、std 28/16/30（非占位），原版台标在位。
- **jank 稳定态：40.32% janky / 50th 14 / 90th 105 / 95th 400ms（377帧）** —— **首次达到 <80%/200ms 门禁**，远优于原生侧栏各版（98–100%/250–600）；video dropped=0。
- **不崩/不灰：PASS**（20/20 开关不崩；首帧出画非灰、BACK 无闪）。
- **TIV 冒烟 PASS**：抽屉双列；频道 OK **保持抽屉**；**单次 Back 关整抽屉**；频道键 166/167 换台、面板内不误触；节目 OK 出现 Toast（当前/未来提示）。
- 冷启 7.2s、PSS 59–71MiB、CPU 峰 280–348%；历史节目回看未取证。

## 1. 身份与安装（PASS）

| 项 | 值 |
|---|---|
| 主 APK SHA256 | 9d00db84d5372ee32f7f4ad66827c1185741e185e50f15490cb83416d6703995（10,404,860B，match） |
| test SHA256 | 23c38806b0bafb9b765e3aeea5b557d50823e6da446bed0a8efa6bb3c56b1a5a（502,955B，match） |
| 包/版本 | tv.iqtvlite.app.validation / 10090 / 2.2.0-tivimini-165d94f1-validation；label=IQTV Lite 验证版 |
| min/target/ABI | 24/35；arm64-v8a+armeabi-v7a |
| 签名SHA256 | c798cf9172891ecb080ecdc1f4a0089b59bbf3acc3501938ae68a52952c71a95 |

backup 后仅替换 validation（Success）；安装后 base.apk 复算=`9d00db84…`（match）。原 tv.iqtvlite.app/com.cjlhll.iptv 保留。

## 2. 观感回退 + 无蓝框（核心 PASS）

- 抽屉截图：[ui-drawer-10090.png](evidence/2026-11-20-ci-10090/ui-drawer-10090.png)；节目：[ui-programs-10090.png](evidence/2026-11-20-ci-10090/ui-programs-10090.png)；信息条：[ui-info-10090.png](evidence/2026-11-20-ci-10090/ui-info-10090.png)；设置：[ui-settings-10090.png](evidence/2026-11-20-ci-10090/ui-settings-10090.png)（供人工对照原版 tivimini）。
- **无蓝框像素检测**：聚焦行 `[0,57][450,153]` 蓝色像素 **0**；全抽屉区域蓝色像素 28（frac 0.0001，分散，非边框）→ **焦点无蓝框**（原版 surfaceVariant 底色）。
- 频道行文本：`▶ 1. CCTV-1 综合` + 副行 `冬去春来第3集 · 播放中`（选中/播放标记）；文本起点 x=96（台标 40dp 在左）。
- 行 12dp 圆角/999dp 进度胶囊/18dp 容器/basicMarquee 为源码 t96 层；圆角/胶囊最终像素需人工看图。

## 3. 台标

- 通道行台标区（x20,60×60=40dp）像素 mean 66/55/47、std 28/16/30、dominant 0.52/0.57/0.92 → 非 #1E242E 占位，**原版台标在位**（行3 偏暗属台标本身深色底）。
- 节目行台标/时间列：原版 Compose 节目列（当前在 10089 已验证；本版为原版布局，需人工看图确认）。

## 4. jank（稳定态，PASS）

启动后 25s 空闲 → 重前台 → `gfxinfo reset` → LEFT+8×DOWN+4×UP：

| 版本 | Total frames | Janky | 50th | 90th | 95th | Missed |
|---|---|---|---|---|---|---|
| CI10078 基线 | 301 | 80.07% | 30ms | 200ms | 500ms | 44 |
| CI10089(native) | 53 | 100% | 36ms | 250ms | 500ms | 1 |
| **CI10090(原版 Compose)** | **377** | **40.32%** | **14ms** | **105ms** | **400ms** | 36 |

- **首次达标：40.32%<80%、90th 105ms<200ms**；95th 400<500。帧数多（377，Compose 连续绘制）→ 统计更可信。
- **video dropped=0/rebuffer=0**；两帧 diff 12.2/16.5%（运动视频，静止场景偏低）。

## 5. 不崩 / 不灰（PASS）

- 进播放器不崩（pid 12975 无 FATAL）；first_frame avc1080 ttff1774ms；截屏 mean 152/165/184 主色 0.305（真实画面非灰）；BACK 前后多色无灰闪。
- **反复开关 20/20 次**（含分组层、DPAD）→ ok=20/20，0 FATAL/duplicate-key。

## 6. TIV 冒烟（PASS/部分）

- 抽屉打开：频道列 + 节目列双列（`频道`/`节目单` 头）；频道 OK → **抽屉保持**（频道 header 仍在）；**单次 BACK → 整抽屉关闭**（频道 header 消失）。
- 频道键 166→prepare1/ff984；167→prepare1/ff340；面板开 166→prepare=0（不误触）。
- 节目 OK：出现 Toast（当前/未来/无能力提示）；**历史节目回看未取证**（未落在历史项，无 CatchupHelper 日志）。
- 左组/右节目/右日期层：原版 Compose 抽屉支持（t96 恢复），本轮未逐层截图（可人工点验）。

## 7. 冷启 / PSS / CPU

| 冷启 | TotalTime | first_frame | init CPU 峰值 | PSS |
|---|---|---|---|---|
| #1 | 7319ms | 3062ms | 333% | 71248 KiB |
| #2 | 7215ms | 1728ms | 348% | 62931 KiB |
| #3 | 7228ms | 2678ms | 280% | 59500 KiB |

- TotalTime 7.2s（较 10089 6.2s 略慢，原版 Compose 更重）；PSS 59–71MiB；CPU 峰 280–348%（仍高）。

## 8. 逐项 通过/失败/未验证

| 项 | 结果 |
|---|---|
| 身份/安装/保留原包 | 通过 |
| 观感回退（原版双列/12dp/999/18dp，非方角极简） | **通过（截图 + 源码 t96）** |
| 焦点无蓝框 | **通过**（focused row 蓝色像素 0；全抽屉28/0.0001 噪散） |
| 台标显示 | 通过（通道行像素非占位） |
| jank 稳定态记录（以原版基线为准） | **40.32%/14/105/400（377帧）达标** |
| 不崩（20+）/不灰/BACK | 通过 |
| TIV：双列/OK保持/单Back关/频道键 | 通过 |
| 历史节目回看 | **未验证**（未落历史项；当前/未来 Toast 已现） |
| 左组/右节目/右日期 分层 | 未逐层截图（原版支持） |
| 冷启/PSS/CPU 峰 | 7.2s / 59–71MiB / 280–348% 仍高 |

## 9. 判定

- **观感已回到原版 Compose（PASS）**：双列、原字体/间距/台标、焦点无蓝框；截图已存供用户确认。
- **jank 达标（40.32%/90th105ms）**，为历次最佳；视频 dropped=0。
- 不崩/不灰/频道键/TIV（OK保持、单Back关）PASS；**历史回看未取证**（当前/未来 Toast 已现）。
- 冷启 7.2s、PSS 59–71MiB、CPU 峰仍高（原版 Compose 较重），非本 task 阻断。
- 未改代码、未改原包数据；设备现为可出画、原版观感、jank 达标的 CI10090。
