# CI10083 原生侧栏快验（jank/回看/日期/视觉）— jank 有改善但未达标

2026-11-20，acceptance2（t69 attempt 1）。对象：`tv.iqtvlite.app.validation` **10083 / 2.2.0-tivimini-b29d5f35-validation**（CI10083：原生 RecyclerView 侧栏缺口修复 NS-01/02/03/04/06/07/08/10；run 44675584770/source b29d5f35）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）在线。

> 模型不读图：视觉按资源/源码/UI hierarchy 描述，截屏留存供人工复核。

## 0. 结论摘要（先给两个决定性结论）

- **① sidebar jank：有改善，但仍未达标。** 官方 probe(启动后8s) **84.44% janky / 50th 36 / 90th 400 / 95th 800ms**；稳定后 **90th 200 / 95th 250ms**（小样本16帧）。对比 **CI10078(80.07%/30/200/500)**、**CI10081(98.55%/125/1100/1350)** → **明显好于 CI10081（90th 400 vs 1100、95th 800 vs 1350）**，但 **probe 仍 84.44%>80%、90th 400>200ms，未达门禁**。视频 **dropped=0/rebuffer=0**（解耦保持）。
- **② 回看/日期：回看可用；日期列头可达，日期行/筛选未验证。** 历史节目 OK → `CatchupHelper.buildCatchupUrl` 生成 `?playseek=20261120012600-20261120015800` 并播放(first_frame 854ms) → **回看 PASS**；`CatchupHelper(owner:Channel)` 以所属频道 B 为 owner（源码层 UA/Referer 归属，日志未直接打印请求头）；日期列 **日期头 [900,0][1065,57] 可达(RIGHT×2)**，分组列 [0,0][225,57] 可达(LEFT)，但**日期行未捕获/date OK 筛选未验证**；回看 Back 回 B 直播未观测到新 prepare（未验证）。
- **不崩：PASS**（反复开关 20/20、0 duplicate-key/FATAL）；**不灰/BACK 无闪 PASS**。
- **冷启：TotalTime 6404/6385/6453ms（较 10081 ~7.1s 改善）**；init CPU 峰 247/353/339%（仍高）；**PSS 58–59MiB（改善）**。
- 频道键 166/167/92/93 **换台 PASS**；面板内 166 prepare=0 不误触。

## 1. 身份与安装（PASS）

| 项 | 值 |
|---|---|
| 主 APK SHA256 | 65f7df729b80cfef6839f64d317a7d8a2e1a5cb5f5c668a26aa746afd2c5122c（10,208,256B，match） |
| test SHA256 | a7a7eaf31fe4439073ea1b92e246cee2b46c1400901ed08b037d1a475179c7ed（502,955B，match） |
| 包/版本 | tv.iqtvlite.app.validation / 10083 / 2.2.0-tivimini-b29d5f35-validation；label=IQTV Lite 验证版 |
| min/target/ABI | 24/35；arm64-v8a+armeabi-v7a |
| 签名SHA256 | c8444481a9fdbbd8f833d856c634e7cf7ee4e56a7514464e2f9aeea5c177c42b |

backup 10081 后仅替换 validation（Success）；安装后 base.apk 复算=`65f7df72…`（match）。原 tv.iqtvlite.app/com.cjlhll.iptv 保留。

## 2. sidebar jank（核心）

`scripts/sidebar_jank_probe.sh` 等价（force-stop→start→sleep8→gfxinfo reset→LEFT→8×DOWN+4×UP→BACK）：

| 版本/条件 | Total frames | Janky | 50th | 90th | 95th | Missed Vsync |
|---|---|---|---|---|---|---|
| CI10078 基线 | 301 | **80.07%** | 30ms | **200ms** | 500ms | 44 |
| CI10081(Compose 抽屉) | 69 | 98.55% | 125ms | 1100ms | 1350ms | 30 |
| **CI10083 probe(8s)** | 45 | **84.44%** | 36ms | **400ms** | 800ms | 8 |
| **CI10083 稳定(~40s, 重前台)** | 16 | 100%(小样本) | 46ms | **200ms** | 250ms | 2 |

- **相对 CI10081 明显改善**（90th 1100→400、95th 1350→800、missed 30→8）；**但仍未达门禁**（probe janky 84.44%>80%、90th 400>200）。稳定样本仅 16 帧（原生侧栏仅焦点变化重绘），90th 200 达边界但样本小。
- **video dropped=0/rebuffer=0**（浏览期间视频不卡，解耦保持）；稳定后两帧 diff 70.4/94.6%（运动视频）。
- 注：等待期间曾出现当贝桌面抢前台导致一次无效采样（0帧），已重前台复测；原生侧栏仅焦点重绘使帧数偏少。

## 3. TIV-08/09/15（日期/回看）

- **TIV-06 分组列**：LEFT 后 `分组 [0,0][225,57]`，含 `全部/央视频道/卫视频道/体育`（可达）。
- **TIV-08 日期列**：RIGHT×2 后 `日期 [900,0][1065,57]` 头可达（列已展开）；但**日期行文本未在 dump 捕获**（早前 logcat 显示 11-20/11-21 行 `visible:false` 宽0），**date OK 仅筛选未验证**。
- **TIV-09/15 回看**：历史节目 OK → `D/CatchupHelper: buildCatchupUrl: modeRaw=append, start=2026-11-19T17:26:00Z…`；`Filled template: ?playseek=20261120012600-20261120015800`；随后 `first_frame session=…s3 ttff_ms=854` → **回看播放 PASS**。
  - **UA/Referer**：源码 `CatchupHelper.kt:16 val owner: Channel? = null`，回看以**所属频道 B 为 owner**（PlayerActivity `onSidebarCatchup` → CatchupPlayRequest）；日志未直接打印请求头（未取到 UA/Referer 明文证据，标“源码层归属正确、请求头明文未取证”）。
  - **回看 Back 回 B 直播**：按 BACK 后未见新 prepare/首帧（未验证；可能已回直播但未重prepare或未触发）。

## 4. 不崩 / 不灰 / BACK（PASS）

- 进播放器不崩（pid 23265 无 FATAL）；first_frame avc1080 ttff1842ms；截屏 mean 115/110/116 主色 0.018（真实画面非灰）；BACK 前后多色无灰闪。
- **反复开关 20/20 次**（含分组/节目层切换、DPAD 上下）→ ok=20/20，进程存活，**0 FATAL/duplicate-key/Key already used**。

## 5. 视觉（源码/资源层，模型不读图）

- 原生侧栏 v2：焦点=背景 #14FFFFFF 提亮+文字加粗、无 border/shadow/elevation；选中=2dp #4EA8FF 左条+▶；方角；行高 56/44/44/40；仅可视项回收（源码 t62/t66）。截屏 [ui-drawer-dates.png](evidence/2026-11-20-ci-10083/ui-drawer-dates.png) 留存供人工看焦点无边框/选中细条/根透明（不再 90% 遮视频）。
- 焦点/选中/播放标记、NOW 等具体像素需人工看图确认（模型不判图）。

## 6. 冷启 / CPU / PSS

| 冷启 | TotalTime | first_frame | init CPU 峰值(单核=100) | PSS(TOTAL KiB) |
|---|---|---|---|---|
| #1 | 6404ms | 2850ms | **247%**（247,213,149,110,90,76） | 60428 |
| #2 | 6385ms | 2277ms | **353%**（353,317,192,151,131,74） | 59488 |
| #3 | 6453ms | 2755ms | **339%**（339,269,212,159,105,68） | 59187 |

- TotalTime **6.4s**（较 10081 7.1–9.6s、10079 ~6.9s 改善）；first_frame 2.3–2.9s。
- init CPU 峰 **247/353/339%**（仍高，未稳定 <270%；#1 247 达标，#2/#3 >340）。
- PSS **58–59MiB**（较 10081 59–69、10078 71–85 改善）。

## 7. 频道键

- 干净态（抽屉关）：166→prepare1/ff1058；167→ff1324；92→ff1324；93→ff958；DPAD_DOWN→prepare1。**换台 PASS**。
- 面板内：开抽屉后 166→**prepare=0**（不误触换台）。PASS。
- 注：首轮 166/167 曾 prepare=0，系当时抽屉开/状态所致，干净重测通过。

## 8. 逐项 通过/失败/未验证

| 项 | 结果 |
|---|---|
| 身份/安装/保留原包 | 通过 |
| sidebar jank 是否真正达标(<80%/200ms) | **未达（probe 84.44%/90th400）；较 CI10081 明显改善**；视频 dropped=0 |
| TIV-06 分组列 | 通过（可达） |
| TIV-08 日期列 | 列头可达；**日期行/date OK 筛选未验证** |
| TIV-09/15 回看 | **通过**（playseek+播放）；UA/Referer 源码归属 B（明文未取证）；Back 回 B 直播未验证 |
| 不崩（20+ 开关/层） | 通过（20/20，0 crash/dup） |
| 不灰/BACK 无闪 | 通过 |
| 视觉（焦点无边框/选中细条/根透明） | 源码层符合；**像素需人工看图** |
| 冷启 TotalTime / CPU 峰 / PSS | 6.4s 改善 / 峰 247–353% 仍高 / 58–59MiB 改善 |
| 频道键 166/167/92/93 | 通过（干净态换台）；面板内不误触 |

## 9. 最小复现（sidebar jank 未达标）

```
adb -s 192.168.2.245:5555 shell am force-stop tv.iqtvlite.app.validation
adb -s 192.168.2.245:5555 shell am start -W -n tv.iqtvlite.app.validation/com.cjlhll.iptv.SplashActivity
sleep 8
adb ... shell dumpsys gfxinfo tv.iqtvlite.app.validation reset
adb ... shell input keyevent 21            # 开抽屉
# 8× DPAD_DOWN(20) + 4× DPAD_UP(19)
adb ... shell dumpsys gfxinfo tv.iqtvlite.app.validation framestats  # 84.44% janky / 90th 400ms
```
回看/日期：开抽屉→RIGHT×2 到日期列头可达；节目列历史节目 OK→CatchupHelper playseek 回看。

## 10. 判定

- **sidebar jank：较 CI10081 明显改善（90th 1100→400ms、95th 1350→800），但仍未达 <80%/200ms 门禁** → 判“未达标、需继续（原生侧栏方向正确，剩绘制/测量热点需 Perfetto 逐帧定位）”。
- **回看可用（PASS）**；**日期列头可达但日期行/筛选未验证**；**不崩/不灰/BACK/频道键 PASS**；**冷启 6.4s 与 PSS 58–59MiB 改善，CPU 峰仍高**。
- 未改代码、未改原包数据；设备现为可出画、侧栏明显变好但 jank 仍未达门禁的 CI10083。
