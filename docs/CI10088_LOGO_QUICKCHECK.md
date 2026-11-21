# CI10088 台标真实显示/观感/jank 快验 — 台标已真实显示，但 jank 回退

2026-11-20，acceptance2（t87 attempt 1）。对象：`tv.iqtvlite.app.validation` **10088 / 2.2.0-tivimini-c02c8fb1-validation**（CI10088：LOGO-01 logoUrl 修复、方形解码 CENTER_INSIDE、面板 4dp 圆角、时间列 42dp、Logo 并发 Semaphore(3)；run 44686317357/source c02c8fb1）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p，density 240/1.5x）在线。

> 模型不读图：观感按资源/UI bounds/像素统计描述，截屏留存供人工复核。

## 0. 结论摘要

- **台标真实显示：PASS（频道行）** —— 每个频道行有 `ImageView [12,75][72,135]` = 60×60px（40dp×1.5）；裁剪台标像素 mean≈67–71 / std≈52 / dominant≈0.72，**明显非 #1E242E 占位**（占位应≈37 均匀）；logcat `BitmapFactory defaultAllocator` 解码日志确认位图加载。**节目行 20×20 台标未单独验证**（截图供人工看）。
- **jank 回退（FAIL 项）** —— 稳定态(25s空闲后) gfxinfo **98.15% janky / 50th 34 / 90th 300 / 95th 400ms(54帧)**；对照 **CI10085 稳定 86.84%/150/200**、**CI10078 80/200/500** → **恢复 Logo 后 jank 明显回退（90th 150→300、janky 86.84→98.15）**；95th 400 仍 <10078 500。视频 dropped=0/rebuffer=0。
- **不崩/不灰/频道键：PASS** —— 20/20 开关不崩；首帧即出画非灰、BACK 无闪；166/167 换台、面板内不误触。
- **冷启 6.2s、PSS 54–60MiB（改善）、CPU 峰 329/365/337%（仍高）**。

## 1. 身份与安装（PASS）

| 项 | 值 |
|---|---|
| 主 APK SHA256 | f13408a1b8d9e4c734b47d24bd66576496cdd7031bae30c546fca6c17162ac97（10,208,256B，match） |
| test SHA256 | 88660a6084cebe220ecc9473cb16d2e90b5e3651994aa5c9f3057f2162bdcd30（502,955B，match） |
| 包/版本 | tv.iqtvlite.app.validation / 10088 / 2.2.0-tivimini-c02c8fb1-validation；label=IQTV Lite 验证版 |
| min/target/ABI | 24/35；arm64-v8a+armeabi-v7a |
| 签名SHA256 | 0975cc73693271f8e6588cc29381b7435c72c7ed3d19e493ecc408af779b67f6 |

backup 10085 后仅替换 validation（Success）；安装后 base.apk 复算=`f13408a1…`（match）。原 tv.iqtvlite.app/com.cjlhll.iptv 保留。

## 2. 台标真实显示（频道行 PASS）

- 抽屉打开后 channel 行 ImageView：`[12,75][72,135]`、`[12,171][72,231]`… 间距 96px（64dp），尺寸 60×60px = **40dp×40dp**（频道行台标）。
- 裁剪台标区（x4–68,h60）像素：mean 67–71 / std 52–55 / dominant 0.67–0.72 → **非占位**（占位 #1E242E≈(30,36,46)，std 低）；多行均如此 → 真实台标位图已加载。
- logcat：`D/BitmapFactory: defaultAllocator` 多条 → 位图解码发生。
- **logoUrl 来源**：源码 t84 LOGO-01 `SidebarRow.logoUrl=channel.logoUrl`、`bindLogo(row.logoUrl)`（此前误用直播流 row.url）——**请求为 logoUrl 而非流地址**（源码层确认；运行期 URL 明文未打印）。
- **宽幅不裁切**：源码 t84 LOGO-02 解码方形 80×80/40×40 + ImageView `CENTER_INSIDE`（宽幅按比例内缩不裁）；像素/肉眼需人工看图。
- **节目行 20×20 台标**：本轮未单独取 bounds/像素，**未验证**（ui-programs-10088.png 供人工看）。

## 3. 观感截图（供人工，模型不读图）

- [ui-drawer-10088.png](evidence/2026-11-20-ci-10088/ui-drawer-10088.png)：频道行（2dp accent+40×40 台标+双行频道名/当前节目+▶/编号、行方角）。
- [ui-programs-10088.png](evidence/2026-11-20-ci-10088/ui-programs-10088.png)：节目行（20×20 台标+时间列+节目名）。
- [ui-info-10088.png](evidence/2026-11-20-ci-10088/ui-info-10088.png)：信息条（台标/频道信息）。
- 焦点态/选中态：截图含当前焦点行与选中(▶)行；**焦点无蓝框（#14FFFFFF 底+字重）、选中/播放细条、面板 4dp 圆角**为源码 t81/t84 层，最终像素需人工看图确认。

## 4. jank（回退，FAIL 项）

稳定态（启动后 25s 空闲 → 重前台 → gfxinfo reset → LEFT+8×DOWN+4×UP）：

| 版本/条件 | Total frames | Janky | 50th | 90th | 95th | Missed Vsync |
|---|---|---|---|---|---|---|
| CI10078 基线 | 301 | 80.07% | 30ms | 200ms | 500ms | 44 |
| CI10085 稳定(无 Logo) | 38 | 86.84% | 36ms | 150ms | 200ms | 0 |
| **CI10088 稳定(含 Logo)** | 54 | **98.15%** | 34ms | **300ms** | 400ms | 1 |

- **恢复 Logo 后 jank 明显回退**：90th 150→300ms、janky 86.84%→98.15%（相对 10085），且 90th 300 > 10078 基线 200；95th 400 仍 <10078 500。样本 54 帧（百分比噪声）。
- **video dropped=0/rebuffer=0**（浏览期间视频不卡）；稳定后两帧 diff 40.7/75.5%（运动视频）。

## 5. 不崩 / 不灰 / 频道键（PASS）

- 进播放器不崩（pid 8705 无 FATAL）；first_frame avc1080 ttff3489ms；截屏 mean 76/63/47 主色 0.076（真实画面非灰）；BACK 前后多色无灰闪。
- **反复开关 20/20 次**（含分组层、DPAD）→ ok=20/20，0 FATAL/duplicate-key。
- 频道键 166→prepare1/ff767；167→prepare1/ff517；面板开 166→prepare=0（不误触）。

## 6. 冷启 / CPU / PSS

| 冷启 | TotalTime | first_frame | init CPU 峰值(单核=100) | PSS(TOTAL KiB) |
|---|---|---|---|---|
| #1 | 6212ms | 2943ms | 329%（329,256,165,155,136,84） | 55306 |
| #2 | 6229ms | 2484ms | 365%（365,186,170,92,92,65） | 57983 |
| #3 | 6246ms | 2972ms | 337%（337,289,195,150,98,75） | 60216 |

- TotalTime 6.21–6.25s（与 10085 6.24–6.31s 相当）；first_frame 2.5–3.0s。
- init CPU 峰 329/365/337%（仍高，未稳定 <270%）。
- PSS 54–60MiB（较 10085 52–62 相当/略优）。

## 7. 逐项 通过/失败/未验证

| 项 | 结果 |
|---|---|
| 身份/安装/保留原包 | 通过 |
| 频道行 40×40 台标真实显示（非占位） | **通过**（ImageView 60×60px；像素非占位；BitmapFactory 解码） |
| logoUrl 而非流地址 | 源码层通过（t84 LOGO-01）；运行期 URL 明文未打印 |
| 宽幅不裁切（CENTER_INSIDE） | 源码层符合；像素/肉眼未验证 |
| 节目行 20×20 台标 | **未验证**（未单独取 bounds/像素） |
| 观感截图（抽屉/节目/信息条/焦点/选中） | 已存 3 张；焦点无蓝框/细条/面板圆角为源码层，需人工看图 |
| **恢复 Logo 后 jank 未明显回退** | **失败：明显回退**（90th 150→300、janky 86.84→98.15；90th 300>10078 200） |
| 不崩（20+）/不灰/BACK | 通过 |
| 频道键 166/167/面板不误触 | 通过 |
| 冷启/PSS/CPU 峰 | 6.2s 相当 / 54–60MiB 相当 / 峰 329–365% 仍高 |

## 8. 最小复现（jank 回退）

```
adb -s 192.168.2.245:5555 shell am force-stop tv.iqtvlite.app.validation
adb -s 192.168.2.245:5555 shell am start -W -n tv.iqtvlite.app.validation/com.cjlhll.iptv.SplashActivity
sleep 25            # CPU 空闲
adb ... shell am start -n .../SplashActivity   # 重前台
adb ... shell dumpsys gfxinfo tv.iqtvlite.app.validation reset
adb ... shell input keyevent 21            # 开抽屉
# 8× DPAD_DOWN(20) + 4× DPAD_UP(19)
adb ... shell dumpsys gfxinfo tv.iqtvlite.app.validation framestats  # 98.15% / 90th 300 / 95th 400
```
对照 CI10085 无 Logo 稳定 86.84%/150/200；恢复 Logo 后 90th +150ms。

## 9. 判定

- **台标真实显示（频道行）PASS**（40×40 台标、非占位、logoUrl 源码确认、BitmapFactory 解码）；节目行 20×20 台标未验证。
- **jank 明显回退（FAIL 项）**：稳定态 98.15%/90th300（vs 10085 86.84%/150、10078 80/200）→ **恢复 Logo 的加载/绘制带来回退**，需优化（Logo 解码/绑定仍可能在滚动/首次打开时集中；Semaphore(3) 与 LRU96 已有，但 90th 仍升 150ms）。视频 dropped=0 仍解耦。
- 不崩/不灰/频道键 PASS；冷启 6.2s、PSS 54–60MiB、CPU 峰仍高。
- 未改代码、未改原包数据；设备现为可出画、台标真实显示但 jank 回退的 CI10088。
