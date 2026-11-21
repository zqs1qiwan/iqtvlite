# 最终包复验（CI10187 · 抽屉 openTarget 回退 28）

2026-11-22，acceptance（t53 attempt 1）。设备 **TR100-G9 / Android 9 / API28 / armeabi-v7a / 1080p / 240dpi**。
最终包 **10187 / 2.2.0-tivimini-1f206133-validation**（CI run **#187** / app sha256 `bfefb8cfb28204a88c7d228bbd9367eea08a3c4dc8117bb9675ca52ec7b7dabf` / artifact `14724666566`）——**与 t52 交付值一致**。

---

## 0. 结论：**可交付** ✅

| 项 | 结果 | 判定 |
|---|---|---|
| 抽屉 ≥300ms 巨帧 | **1 个**（315.9ms）；t47 为 3 个、t50（openTarget=18）为 4 个 | ✅ **优于历史最佳** |
| 抽屉 50th | **57 / 53 ms**（t47：53/53；t50：89/85） | ✅ 回到 t47 水平 |
| 帧时 / UI / Draw 中位 | **43.6 / 22.1 / 5.0 ms**（t47：50.0 / 22.6 / 4.4） | ✅ 持平或略优 |
| 滚动跟随（DOWN×25 / 快按） | **1–12 → 21–32 → 31–42**，`row_focus via=key` **37 条** | ✅ 稳定 |
| 聚焦行副标题 | `idx=2 subtitle=true title=中国经济大讲堂-2026-44`、`idx=3 … CMG歌会-海南东方篇1` | ✅ 正常 |
| **非聚焦行单字残影** | 单字节点计数 **0**（t47 有 `城/军/武/音`） | ✅ **已修** |
| 睛彩默认 OFF · 健康频道零影响 | CCTV-1 `zap=1202ms`、firstFrame、state=3，**无任何睛彩日志** | ✅ |
| 睛彩默认 OFF · 117 三行齐全 | `IQTV-DIAG` + `buffer` + `diag/timeline` 全在（见 §2.2） | ✅ |
| 回归（信息卡 v3/设置/焦点/MP2/数字/上一台/不崩） | 全部通过（见 §3） | ✅ |
| 交付态 | vc10187、实验键 **0**、`jingcai_experiment=off`、`buffer_min=500`、Fatal=0、dropped=0 | ✅ |

**判定：可交付。** 本次回退 `DRAWER_OPEN_WINDOW_ROWS 18→28` **完全达到预期**：巨帧 4→1（甚至优于 t47 的 3）、50th 89/85→57/53ms、Draw 16.5→5.0ms，同时**残影修复、滚动跟随、聚焦行副标题全部保持**。这是目前最优的抽屉状态。

---

## 1. 安装与健康

| 项 | 值 |
|---|---|
| 安装 | 备份 **2,183,168 B / 338 条目** → **仅卸** `tv.iqtvlite.app.validation`（Success）→ `install -r`（Success）；签名 `7d7e5df4`；vc10187；lastUpdateTime 2026-11-22 05:18:33 |
| 实验键 | 装前 **0** / 装后 **0** / 收尾 **0** |
| 其他包 | `tv.iqtvlite.app` / `.validation.test` / `com.cleanroom.iptv` 全程在位 |
| force-stop | **0 次**（HOME + `am kill`） |
| /data | 2,590,132–2,600,236 KB（22%）；守护进程 2；`DeadObject/LMK/ext4/I-O`=0 |
| 崩溃 | 我方 `Fatal signal` 全程 **0** |
| 冷启 | `first_frame ttff_ms=3312` → `state=3`，rebuffers=0 |

---

## 2. 抽屉与睛彩

### 2.1 抽屉 jank（5 次开合 ×2 轮）

| 轮 | 环境 | Total | Janky | 50th | 90th | 95th | 99th | Missed Vsync | Slow UI |
|---|---|---|---|---|---|---|---|---|---|
| 1 | loadavg 12.48、CPU 33.2% | 80 | 80 (100%) | **57 ms** | 300 | 450 | 700 | 35 | 53 |
| 2 | loadavg 13.29、CPU 35.3% | 83 | 83 (100%) | **53 ms** | 200 | 300 | 400 | 32 | 57 |

framestats（3 次开合，51 帧）：

| 指标 | t47（28） | t50（18） | **t53（28，最终包）** |
|---|---|---|---|
| ≥300ms | 3 | 4 | **1**（315.9） |
| ≥100ms | 8 | 13 | 9 |
| 最大帧 | 341.3 | 451.5 | **315.9** |
| 帧时中位 | 50.0 | 82.3 | **43.6** |
| UI 中位 | 22.6 | 37.8 | **22.1** |
| Draw 中位 | 4.4 | 16.5 | **5.0** |

最慢 6 帧：`315.9, 294.0, 273.1, 260.0, 230.6, 205.1 ms`，其余 ≤115.6ms。

埋点确认回退生效：
```
drawer_open ms=228 total=147 composed=3 revealed=3 first=3 groups=14 group=全部
drawer_compose frames=13 rows=147 target=28 ms=640 lastFrameRows=1 first=3 step=2
drawer_close ms=14
```
→ `target=28` ✅、`frames=13` ✅、`step=2` ✅、`first=3` ✅。

### 2.2 睛彩（仅默认 OFF）

**健康频道零影响**（CCTV-1）：`zap=1202ms prepare=362ms firstFrame=840ms`、firstFrame ✅、state=3 ✅、**无任何 `IQTV-JINGCAI` / `IQTV-DIAG` 日志** ✅。
（切回 CCTV-1 又一次 `zap=622ms`，仍正常。）

**117 三行齐全**（本轮第 1 次尝试即拿到完整 giveup 链路）：
```
IQTV-JINGCAI: giveup=1 channel=4783027072129292115 giveup=1 stage=SOFT_TRIED
IQTV-JINGCAI: diag mode=off bytesRead=8422400 firstByteMs=55092357 format=video/avc/1920x1080@-1.0 firstFrame=false state=2 code=open
IQTV-JINGCAI: IQTV-DIAG bytesRead=8422400 firstByteMs=55092357 code=open
IQTV-JINGCAI: buffer bufferMs=0 currentPosition=0 windowCount=1 state=2 rebuffers=0 rebuffer_ms=0 bytesRead=8422400 firstByteMs=55092357
```
→ 与 t50/t51 一致：**数据读到了（8.4MB）、首字节早已到达、但 `bufferMs=0 / currentPosition=0`，解不出可播放时间轴**。
（注：本轮 117 第一次尝试**播放成功**（firstFrame/state=3），第二次才失败 —— **再次印证间歇性**。）

---

## 3. 回归（全部通过）

| 项 | 实测 |
|---|---|
| **信息卡 v3** | `info_card [0,630][1920,1080]` = **300dp**；`logo [60,705][300,849]` = 240×144px = **160×96dp**；`title 'CCTV-1 综合' [336,728][620,803]`（**与台标同行**）；`number '频道 1 · 央视频道 · MPEG-L2'`；`badges 'FHD' [1821,668]`；`program '寻古中国-海岛寻源壳丘头2'`；`program_time '05:27–06:00 · 剩余 18 分钟'`；`progress [1030,811][1860,822]`；`tech 'H.264 · MPEG-L2 · SDR'`；`hint '← 频道列表 · → 设置'`；`recent_row [120,918][1800,1014]` |
| 设置一级 | ✅ **6 入口齐全** + `起播缓冲 稳(500ms)` + `台标与缓存 96 条 / 316 个` + `关于 版本 10187` + 页脚提示 |
| 焦点 F1/F2 | ✅ BACK#1 / BACK#2 均仍为 `PlayerActivity` |
| MP2 | ✅ `audio/mpeg-L2` 8 条 |
| 数字 OSD | ✅ `zap=898ms` |
| 上一台 229 | ✅ `ZAP 增量=1` |
| 不崩不灰 | ✅ `Fatal signal=0`；`grey/gray/black` 我方 0；`dropped=0` |
| 收尾 | ✅ keys=0；`jingcai_experiment=off`；`buffer_min=500` |

> 抓取备注：本包上 **KEYCODE_DPAD_CENTER(23) 有时不触发信息卡，而 KEYCODE_ENTER(66) 可以** —— 建议后续验收脚本对信息卡统一用 66 或两种都试（本次即靠 66 + 立即 dump 抓到完整节点）。

---

## 4. 最终结论

**可交付。**

1. **抽屉**：回退 `openTarget=28` 后达到历史最佳 —— **≥300ms 巨帧 1 个**（优于 t47 的 3 个）、**50th 53–57ms**、Draw 中位 5.0ms；同时**残影已修**、**滚动跟随稳定（via=key 37 条）**、**聚焦行副标题正常**。
2. **睛彩**：默认 OFF 对健康频道**零影响**，117 失败时三行诊断齐全（`bytesRead=8.4MB / bufferMs=0 / currentPosition=0`）；**间歇性**特征在最终包上再次复现（本轮第 1 次播放成功、第 2 次失败），与 t50/t51 结论一致 —— **不建议在最终包内继续修，按已知限制归档**。
3. **回归**：信息卡 v3（300dp/160×96dp/FHD/节目+时间+进度）、设置二级 6 入口、焦点 F1/F2、MP2、数字 OSD、上一台、无崩溃无灰屏全部通过。
4. **交付态干净**：vc10187、实验键 0、`jingcai_experiment=off`、`buffer_min=500`、`Fatal=0`、`dropped=0`。

**已知限制（与 t52 `docs/FINAL_PACKAGE.md` 一致）**：
- 抽屉仍有 1 个 ≥300ms 巨帧、50th ~53ms —— 受单行 12–15ms 组合成本限制，要真正达标需换渲染方案（自绘虚拟列表/预渲染行位图），属新立项；
- 冷启 rebuffer 由源侧/网络方差主导，App 侧无稳定收益；
- PSS 口径敏感（同进程 62–79MB 随状态变化），不作回归判据；
- 睛彩间歇性故障未证明可修，诊断链路已可交付。

---

## 5. 交付物

- 本报告：`docs/CI_FINAL_PACKAGE_VERIFY.md`
- 原始数据：`docs/evidence/2026-11-22-t53-final-verify/`
  - `01-coldstart.txt`
  - `02-drawer-round1.txt`、`02-drawer-round2.txt`（含直方图）
  - `03-framestats.txt`、`03-framestats-top.txt`（51 帧，≥300ms=1）
  - `04-drawer-semantics.xml`（残影检查）、`05-scroll.txt`（滚动跟随）
  - `06-cctv1-off.txt`（健康频道零影响）
  - `07-jingcai-117-off.txt`、`07b-jingcai-117-try1.txt`（117 三行齐全）
  - `08-infocard-v3-dump.xml`（信息卡完整节点）
  - `09-regression.txt`
- 复跑脚本：`.acceptance/perf-measure.ps1`、`.acceptance/adb-helpers.ps1`
