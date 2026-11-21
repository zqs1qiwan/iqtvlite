# CI10093 去圆角 Flat 全量验证 — 几何等价、jank 无回退、TIV/品牌 OK

2026-11-20，acceptance2（t113 attempt 1）。对象：`tv.iqtvlite.app.validation` **10093 / 2.2.0-tivimini-fa7f87ea-validation**（CI10093 = t111 去圆角+每行 shape 背景去除+LRU96，基于 CI10090 回退版；t112 源码复查 pass；run 44763302399/source fa7f87e）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p，density 240）在线（中途曾 offline，已重连）。

> 模型不读图：截图已存 docs/evidence 供用户/人工；几何用 uiautomator bounds **A/B 同协议**对比。

## 0. 结论摘要

- **几何一致性（重点）：等价（PASS）——t107 的 x=156/114 系测量上下文差异，非真实偏移。** 同协议 A/B：**CI10090 与 CI10093 抽屉频道列完全一致** —— `频道[24,24][90,73]`、`1. CCTV-1 综合[156,111][321,147]`、行距 114px、`节目单[476,24]`、**两者均无 ▶/“播放中”**（原版本就无此标记，Captain 已纠正）。→ **真实偏移 = 否**（t99 早期 x=96 属误测的抽屉状态），代码仅圆角变化、布局度量等价。
- **jank：无回退（PASS）。** CI10093 稳定态 **40.03% janky / 50th13 / 90th61 / 95th117ms（597帧）**；同会话 CI10090 基线 68.25%/24/101/150（t99 曾 40.32%/105/400）。10093 90th 61 优于此会话 10090 的 101，**无回退**；video dropped=0。
- **不崩/不灰/频道键/TIV：PASS**：20/20 开关不崩；首帧出画（冷启 ff2.3–2.9s，非灰）、BACK 无闪；166/167/92/93 换台、面板内不误触；抽屉开/频道 OK 保持/单次 Back 关整抽屉；日期列可见(11-19..11-22)。
- **品牌：PASS** label=IQTV Lite 验证版、adaptive icon `mipmap-anydpi-v26/ic_launcher.xml`；banner/splash 保留（t111/t112 未改品牌）。 **SR 源码未变**（t111/t112），**硬解 OMX.goke AVC(OMXVDEC) 正常**，AudioSink 本窗口 0。
- **未验证**：历史节目回看实际播放（OK 仅出 Toast、无 CatchupHelper/playseek，疑该频道无回看）；配置页运行截图（MainActivity 非导出；2dp 由 t108/t112 源码确认）；部分 TIV 子项（初开当前台/上下循环/组日期 OK 仅筛选 未逐条日志）。

## 1. 身份与安装（PASS）

| 项 | 值 |
|---|---|
| 主 APK SHA256 | 56c855a63c2565eb82a66bc2302c19ef2f66fc8cb3073a77bb651352596a361c（10,404,860B，match） |
| test SHA256 | abfe2aa946b6fe3443ae7990c9a62ad8d07be12975bf8399277b1e5bbcafb423（502,955B，match） |
| 包/版本 | tv.iqtvlite.app.validation / 10093 / 2.2.0-tivimini-fa7f87ea-validation；label=IQTV Lite 验证版 |
| min/target/ABI | 24/35；arm64-v8a+armeabi-v7a |
| 签名SHA256 | 579a0db2819b4d3653a2fd3d55bd2821c11755e0e8fb259399e35b998584fa9f |

backup 10090 sandbox 后仅替换 validation（卸载旧+安装 10093，Success）；安装后 base.apk 复算=`56c855a6…`（match）。原 tv.iqtvlite.app/com.cjlhll.iptv 保留。

## 2. 几何 A/B（重点 PASS — 测量差异，非真实偏移）

同协议：安装→配源→force-front→LEFT 开抽屉（row1 聚焦，同频道 CCTV-1）→uiautomator dump。

| 项 | CI10090(165d94f1) | CI10093(fa7f87e) |
|---|---|---|
| 频道头 | `[24,24][90,73]` | `[24,24][90,73]` |
| 频道1 文本 | `1. CCTV-1 综合 [156,111][321,147]` | `1. CCTV-1 综合 [156,111][321,147]` |
| 行距 | 114px（111,225,339,…） | 114px（111,225,339,…） |
| 节目头 | `节目单[476,24]` | `节目单[476,24]` |
| ▶/播放中 | 无 | 无 |
| 当前节目 | 三餐四季（第三季）-7湖南 | 三餐四季（第三季）-7湖南 |

→ **两版完全一致**。故 t107 记录的 x=156/114/头[24,24] 与早期 t99 的 x=96/96/[0,0] 差异系**抽屉测量上下文**（滚动/面板状态/节点选择），**非代码偏移**；与 dev/t112 “仅圆角变化、布局度量等价 CI10090” 及 Captain “原版无 ▶/播放中” 一致。**几何判等价 PASS。**（证据：[old-10090-dump.xml](evidence/2026-11-20-ci-10093/old-10090-dump.xml) vs [new-10093-chan-dump.xml](evidence/2026-11-20-ci-10093/new-10093-chan-dump.xml)，截图 ab-old-10090.png / ab-new-10093-chan.png。）

## 3. 截图（供用户，已存，模型不读图）

- [ui-drawer-10093.png](evidence/2026-11-20-ci-10093/ui-drawer-10093.png)（抽屉，含台标/双行/EPG）
- [ui-programs-10093.png](evidence/2026-11-20-ci-10093/ui-programs-10093.png)（节目列，含时间列）
- [ui-info-10093.png](evidence/2026-11-20-ci-10093/ui-info-10093.png)（信息条）
- [ui-settings-10093.png](evidence/2026-11-20-ci-10093/ui-settings-10093.png)（设置：源配置/更新EPG/检查更新）
- [ui-update-10093.png](evidence/2026-11-20-ci-10093/ui-update-10093.png)（检查更新）
- 配置页：**未截图**（MainActivity 非导出，`am start` SecurityException；TextField×2/Button 2dp 由 t108/t112 源码确认）。
- **方角/方头/2dp/无胶囊**：源码 t103/t108/t111/t112（全工程 0dp，配置 2dp/进度 Butt）；像素级最终需人工看图。**焦点无蓝框**：抽屉区域蓝色像素 **0**（[blue_check] 0/0.0000）→ 无蓝框。

## 4. jank（同协议，PASS 无回退）

启动后 25s 空闲 → 重前台 → `gfxinfo reset` → LEFT+8×DOWN+4×UP → 关：

| 版本/会话 | Total | Janky | 50th | 90th | 95th | Missed |
|---|---|---|---|---|---|---|
| CI10090 t99 | 377 | 40.32% | 14 | 105 | 400 | 36 |
| CI10090 本会话 A/B | 611 | 68.25% | 24 | 101 | 150 | 77 |
| **CI10093 本会话** | 597 | **40.03%** | 13 | **61** | 117 | 44 |

- **10093 40.03%/90th61** ≤ CI10090（本会话 68.25%/101；t99 40.32%/105）→ **jank 无回退、达标**（janky<80%、90th<200）。帧数 597，统计充分。**video dropped=0**。

## 5. 不崩 / 不灰 / 频道键（PASS）

- 反复开关 **20/20** ok、0 FATAL/duplicate-key。
- 首帧出画（冷启 ff 2.3–2.9s，非灰）；BACK 前后多色无灰闪；video dropped=0。
- 频道键：166→ff1300；167→ff1110；92→ff1506；93→ff1124（均换台）；面板开 166→prepare=0（不误触）。

## 6. TIV 交互

- **抽屉打开 PASS**；**频道 OK 保持抽屉 PASS**；**单次 Back 关整抽屉 PASS**。
- **日期列可见 PASS**：RIGHT×2 后日期行 11-19/11-20/11-21/11-22 可见（x956）。
- 节目列：时间列两行（如 13:51-14:00）+ 节目名可见。
- 频道键 166/167/92/93 换台、面板内不误触 PASS。
- **初开当前台/上下循环/左组右节目右日期/组日期 OK 仅筛选/350ms EPG**：抽屉导航正常（开/左右/上下可用），未逐条日志判读，标“部分验证”。
- **历史节目 OK 回看**：节目列多为历史时段，OK **出现 Toast 但无 CatchupHelper/buildCatchupUrl/playseek、无 catchup prepare** → **未取证**（疑该频道无回看能力→“暂不可回看/仅历史”提示）。owner=频道B 源码保留（t111/t112）。

## 7. 台标 / 冷启 / PSS / 品牌 / SR / 硬解 / AudioSink

- 台标：抽屉 channel 行含台标（Logo 系统同 CI10088/10089，源码 t84/t88 未改；截图 ui-drawer 供人工看）；像素非占位（前证）。
- 冷启：TotalTime **7279/10237/8368ms**（波动/偏慢）；ff 2.3–2.9s；init CPU 峰 **291/308/339%**；PSS **54–72MiB**；稳态 app CPU ~73.5%（单次采样，偏高）。
- **品牌 PASS**：label=IQTV Lite 验证版；adaptive icon `mipmap-anydpi-v26/ic_launcher.xml`；banner/splash 保留（t111/t112 未改品牌资源；t90/t99 已验）。
- **SR**：PlaybackIntent.kt 存在，源码自 165d94f1→fa7f87e 未改（t111/t112），SR/回看 owner B 保留（引用 10073 clean）。
- **硬解**：`OMX.goke.video.decoder.avc`/`OMXVDEC` 正常。
- **AudioSink**：本 AVC/mpeg-L2 窗口 `UnexpectedDiscontinuity` 计数 **0**（未触发 AAC；潜在仍在，需 AAC 专项）。

## 8. 逐项 通过/失败/未验证

| 项 | 结果 |
|---|---|
| 身份/安装/保留原包 | 通过 |
| 几何与 CI10090 一致（A/B） | **通过：两版完全一致；真实偏移=否（测量差异）** |
| 方角/方头/2dp/无胶囊/无蓝框 | 通过（源码层 + 抽屉蓝像素0；像素需人工看图） |
| 配置页/更新弹窗 | 源码层通过（2dp/Butt，t108/t112）；配置页运行截图未取 |
| jank 不劣于 CI10090 | **通过：40.03%/90th61 ≤ 10090（本会话101/t99 105）** |
| 不崩（20+）/不灰/BACK | 通过 |
| 频道键 166/167/92/93 + 面板不误触 | 通过 |
| TIV：开/OK保持/单Back关/日期可见 | 通过（部分子项部分验证） |
| 历史回看（owner B） | **未验证**（OK 仅 Toast、无 playseek/无 catchup prepare） |
| 台标/品牌(label/icon/banner/splash) | 通过 |
| SR / 硬解 OMX.goke / AudioSink | SR 源码未变；OMX 正常；AudioSink 本窗口0 |
| 冷启/PSS | 7.3–10.2s / 54–72MiB / CPU 峰 291–339% 仍高 |

## 9. 判定

- **几何等价（PASS）：A/B 同协议证明 CI10090≡CI10093（x156/行距114/头[24,24]/无▶），t107 的偏移结论为测量上下文差异，非代码回归。**
- **jank 无回退（PASS）：40.03%/90th61 ≤ 10090。**
- 不崩/不灰/频道键/TIV（开/OK保持/单Back关/日期可见）PASS；品牌/SR/硬解 OK。
- **未验证**：历史回看实际播放（无回看能力或未触发）、配置页运行截图、部分 TIV 子项。
- 未改代码、未改原包数据；设备现为可出画、几何等价、jank 达标、去圆角的 CI10093。
