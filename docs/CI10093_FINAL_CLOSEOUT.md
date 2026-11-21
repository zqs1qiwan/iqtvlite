# CI10093 最终收尾验证（回看专项/配置页/TIV/品牌/jank）

2026-11-20~21，acceptance2（t114 attempt 1）。对象：`tv.iqtvlite.app.validation` **10093 / 2.2.0-tivimini-fa7f87ea-validation**（pair 主 `56c855a63c2565eb82a66bc2302c19ef2f66fc8cb3073a77bb651352596a361c` 10,404,860B / test `abfe2aa946b6fe3443ae7990c9a62ad8d07be12975bf8399277b1e5bbcafb423` 502,955B；签名 `579a0db2…`）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p，density 240）在线。

> 模型不读图：截图存 docs/evidence 供人工；配置/TIV 用 uiautomator 文本+bounds。

## 0. 结论摘要

- **回看：源不支持**（用户已确认搁置）。测试源真实 M3U（`https://iptv.laobaitv.net/?m3u=true`）**158 条 #EXTINF，catchup/playseek/tvod 字段=0**；样例：`#EXTINF:-1 tvg-id="CCTV1" tvg-name="CCTV-1 综合" tvg-logo="https://logo.laobaitv.net/CCTV1" group-title="央视频道",CCTV-1 综合`（无 catchup 属性）。→ 记录“**源无 catchup 能力，回看代码路径保留(owner B)以源码为准**”，不再强测。
- **配置页运行截图 PASS**：MainActivity（经 设置→源配置 打开）含“源配置 / 直播源地址 / EPG地址 / 保存并播放”；截图 [ui-config-10093.png](evidence/2026-11-20-ci-10093/ui-config-10093.png)；2dp 方角为源码 t108/t112（像素需人工看图）。
- **剩余 TIV：多 PASS**（见 §4）：初开定位当前台、浏览焦点保留、左组/右节目/右日期、组 OK 仅筛选保留列、频道 OK 保持抽屉、单次 Back 关整抽屉 **PASS**；DPAD 到顶环绕与 date-OK 仅筛选未单测（部分）。
- **jank 复测：无回退、更好（PASS）**：**30.02% janky / 50th12 / 90th40 / 95th77ms（1046帧）** < 前次 40.03%/61；video dropped=0。
- **品牌 PASS**：label=IQTV Lite 验证版；adaptive icon `mipmap-anydpi-v26/ic_launcher.xml`；**banner=`res/drawable-nodpi-v4/tv_banner.png`**（aapt 确认）；splash/launcher 截图已存。

## 1. 身份与安装（PASS）

- 已装 10093 / 2.2.0-tivimini-fa7f87ea-validation，versionCode 10093（t113 装入沿用）；base.apk 由 t113 复算 = `56c855a6…`（match）。原 tv.iqtvlite.app/com.cjlhll.iptv 保留。

## 2. 回看能力专项（源不支持，记录）

- `GET https://iptv.laobaitv.net/?m3u=true` → 真实 M3U 25,626B，**#EXTINF=158，catchup=0，playseek=0，tvod=0**（证据 [playlist-found.m3u](evidence/2026-11-20-ci-10093/playlist-found.m3u)、[playlist-raw.txt](evidence/2026-11-20-ci-10093/playlist-raw.txt) 为首页 HTML 源站）。
- 样例 EXTINF 仅含 tvg-id/tvg-name/tvg-logo/group-title，无任何 catchup 字段 → **全源无回看能力**。
- 结论：**无法真机验证回看起播/UA/Referer/Back 回直播**；回看代码路径（CatchupHelper.buildCatchupUrl owner=频道B）保留，以源码为准（t111/t112 未改）。符合用户“回看专项搁置”。

## 3. 配置页运行截图（PASS）

- 路径：播放器 → RIGHT(设置) → 「源配置」OK → MainActivity 配置页。
- 配置页节点：`源配置`、`http://192.168.2.245:28081/`、`https://iptv.laobaitv.net`(直播源地址)、`https://epg.laobaitv.net`(EPG地址)、`保存并播放`。
- 截图：[ui-config-10093.png](evidence/2026-11-20-ci-10093/ui-config-10093.png)（直播源/EPG 输入框 + 保存并播放按钮）。2dp 方角/非胶囊=源码 t108/t112（像素需人工看图）。

## 4. 剩余 TIV 逐项

| 项 | 结果 | 证据 |
|---|---|---|
| 初开定位当前台 | **通过**（开抽屉焦点 row≈1=CCTV-1 当前台） | r-open.xml focus [24,96][426,200] |
| 浏览后焦点保留 | **通过**（DOWN×3 到 row4→关→重开仍在 row4） | r-down3/r-reopen row≈4 |
| 左进分组 | **通过**（分组 header + 焦点在 group 列） | d-group.xml 分组@[24,96][201,168] |
| 右进节目 | **通过**（焦点 x476 节目列） | r-program.xml focus [476,304][926,375] |
| 右进日期 | **通过**（焦点 x941 日期区；日期行 11-19..11-22 可见） | r-date.xml + dates-10093.xml |
| 组 OK 仅筛选且保留列 | **通过**（group OK 后 分组+频道+节目单 头仍在） | d-groupok.xml |
| 日期 OK 仅筛选 | **部分/未单测**（日期列可达、行可见；未单独按 OK 验筛选） | — |
| 频道 OK 换台抽屉保持 | **通过**（OK 后抽屉仍开、焦点 row≈2） | r-chanok.xml headers[频道,节目单] |
| 单次 Back 关整抽屉 | **通过**（Back 后无 频道/节目单 头） | r-backclose.xml headers=[] |
| 频道上下循环 | **部分**：DPAD 到顶未见环绕；频道循环按源码 stepChannel(Math.floorMod)（t61 JVM 覆盖） | r-upwrap row≈1 |
| 350ms EPG 去抖 | 源码（未上机可测），t61/t66 | — |

## 5. jank 复测（同协议，PASS 更好）

启动 25s 空闲→重前台→gfxinfo reset→LEFT+8×DOWN+4×UP：

| 会话 | Total | Janky | 50th | 90th | 95th | Missed |
|---|---|---|---|---|---|---|
| t113 CI10093 | 597 | 40.03% | 13 | 61 | 117 | 44 |
| **t114 CI10093 复测** | 1046 | **30.02%** | 12 | **40** | 77 | 57 |

- **30.02%/90th40** 优于前次 40.03%/61，**无回退**（janky<80%、90th<200）。**video dropped=0**。

## 6. 冷启 / CPU / PSS

| 冷启 | TotalTime | first_frame | init CPU 峰 | PSS |
|---|---|---|---|---|
| #1 | **368510ms（异常/设备停顿，忽略）** | 2517ms | 307% | 64752KiB |
| #2 | 7319ms | 1587ms | 335% | 56636KiB |
| #3 | 7181ms | 1747ms | 307% | 66708KiB |

- 代表值 TotalTime **~7.2s**（#1 为异常停顿 368s，非产品问题，已标注）；CPU 峰 307–335%（仍高）；PSS 55–65MiB。

## 7. 品牌（PASS）

- aapt：`application-label:'IQTV Lite 验证版'`；`application: icon='res/mipmap-anydpi-v26/ic_launcher.xml' banner='res/drawable-nodpi-v4/tv_banner.png'`（adaptive icon + TV banner 均在）。
- 截图：[ui-splash-10093.png](evidence/2026-11-20-ci-10093/ui-splash-10093.png)（启动 Splash 标）、[ui-launcher-10093.png](evidence/2026-11-20-ci-10093/ui-launcher-10093.png)（桌面）、[ui-config-10093.png](evidence/2026-11-20-ci-10093/ui-config-10093.png)。应用名 IQTV Lite 验证版（aapt）。

## 8. 逐项 通过/失败/未验证

| 项 | 结果 |
|---|---|
| 回看专项 | 源不支持（记录+证据；用户确认搁置）；回看路径源码为准 |
| 配置页运行截图（输入框/保存按钮 2dp） | 通过（截图已存；2dp 源码 t108/t112） |
| TIV 初开定位/焦点保留/左组/右节目/右日期/组OK仅筛选/频道OK保持/单次Back关 | 通过 |
| TIV DPAD 环绕 / 日期OK仅筛选 / 350ms EPG | 部分/源码（未全上机测） |
| jank 复测 vs 40.03%/90th61 | **通过：30.02%/40，无回退** |
| 冷启×3/CPU/PSS | 记录（~7.2s / 峰307–335% / 55–65MiB；#1异常标注） |
| 品牌 label/icon/banner/splash | 通过（aapt + 截图） |

## 9. 判定

- **收尾通过**：回看按源能力记录（源无 catchup，代码路径保留）；配置页截图 PASS；TIV 核心路径 PASS；jank 无回退（30.02%/90th40）；品牌 label/adaptive icon/banner/splash PASS。
- 未验证/部分：回看实际播放（源限制，按用户搁置）、DPAD 环绕/日期OK筛选/350ms EPG（源码/未全测）、冷启#1 异常停顿。
- 未改代码、未改原包数据；设备现为 CI10093（可出画、去圆角、几何等价、jank 达标）。
