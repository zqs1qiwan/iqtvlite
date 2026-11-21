# 抽屉第五轮真机 A/B（t33 固定行高 + 全量占位 + 加速）· CI10168

2026-11-22，acceptance（t34 attempt 1）。设备 **TR100-G9 / Android 9 / API28 / armeabi-v7a / 1080p / 240dpi**。
包 **10168 / 2.2.0-tivimini-cd09f10f-validation**（CI run **#168** / app sha256 `b46c2e7504672a23f66f50af7b66e188e261cf7e6421a98b71aa7296ca2168dd` / artifact `14713223884`）。
对照：**t30 / CI10166**（第四轮，subList + 10/3 + 无占位）。

---

## 0. 结论：**本轮为回归，不建议合并为默认；建议回退 E3（全量占位）**

| 指标 | t30（10166） | **t34（10168）** | 变化 | 目标 |
|---|---|---|---|---|
| 5 次开合帧数 | 250 | 35 / 43 | ↓ | — |
| Janky | 100% | **100%** | = | ≤20% ❌ |
| **50th** | 46 / 38 ms | **150 / 109 ms** | **恶化 2–4×** | ≤16 ❌ |
| **90th** | 65 / 53 ms | **500 / 350 ms** | **恶化 6–8×** | — |
| **95th** | 300 / 200 ms | **500 / 400 ms** | 恶化 | — |
| **≥300ms 巨帧** | 2 | **4**（365.8 / 348.6 / 323.6 / 300.3） | **恶化** | 0 ❌ |
| ≥100ms 帧 | 7 | **13** | 恶化 | — |
| 帧时中位 | 39.9 ms | **103.2 ms** | **恶化 2.6×** | — |
| **UI 线程中位** | 18.1 ms | **49.9 ms** | **恶化 2.8×** | — |
| **Draw 中位** | 2.1 ms | **20.7 ms** | **恶化 10×** | — |
| `drawer_open` | 685–757（一次 2725） | **242–248**（一次 867） | ✅ 改善（不再 2.7s） | — |
| `drawer_compose ms` | 1755（13 帧） | **936–960（28–31 帧）** | ✅ 更快 | — |
| 首帧 revealed | 10 | **6** | 符合 t33 规格（4–6） | ≤12 ✅ |
| `step` | 3 | **2** | 符合 t33 规格 | — |
| `drawer_close ms` | — | **9 / 19 / 20 / 22 / 24** | 关闭极便宜 | — |

**判定：①≥300ms 巨帧**未达标且**从 2 增到 4**；**②50th 未达标且恶化到 109–150ms**（目标 16ms）；**③巨帧归因已明确——不在关闭路径**（`drawer_close ms=9–24`），而在**打开路径的"加速批量组合"帧**；**④`drawer_compose step=` 加速确实生效**（28–31 帧完成 147 行、耗时 936–960ms，比 t30 的 1755ms 快），但**加速本身把单帧组合量抬到 6–12 行，是本次恶化的主因之一**。

---

## 1. 安装与健康

| 项 | 值 |
|---|---|
| 安装 | 备份 **2,057,728 B / 328 条目** → **仅卸** `tv.iqtvlite.app.validation`（Success）→ `install -r`（Success）；签名 `8bc51fa0`；vc10168；lastUpdateTime 2026-11-22 00:00:07 |
| 实验键 | 装前 **0** / 装后 **0** / 收尾 **0** |
| 其他包 | `tv.iqtvlite.app` / `.validation.test` / `com.cleanroom.iptv` 全程在位 |
| force-stop | **0 次**（HOME + `am kill`） |
| /data | 2,593,544–2,603,604 KB（22%）；守护进程 2；`DeadObject/LMK/ext4/I-O`=0 |
| 崩溃 | 我方 `Fatal signal` 全程 **0** |
| `buffer_min` | **500**（`IQTV-ZAP: bufferMin=500`；一级摘要 `起播缓冲 稳(500ms)`），全程未被误改 ✅ |

---

## 2. 埋点：t33 的规格全部落地

```
drawer_open ms=248 total=147 composed=0 revealed=6 first=6 groups=14 group=全部
drawer_compose frames=1  rows=147 ms=250 lastFrameRows=6  first=6 step=2
drawer_compose frames=30 rows=147 ms=960 lastFrameRows=1  first=6 step=2
drawer_close ms=19
drawer_open ms=247 ... revealed=6 first=6 ...
drawer_compose frames=31 rows=147 ms=936 lastFrameRows=3  first=6 step=2
drawer_close ms=9
```

| 检查 | 期望 | 实测 | 结论 |
|---|---|---|---|
| 首帧揭示行数 | 4–6 | **revealed=6 first=6** | ✅ |
| 每帧步进 | 2 | **step=2** | ✅ |
| 总行数 | 147 | **rows=147** | ✅ |
| 加速 | ≥600ms→6、≥1200ms→12 | 28–31 帧完成 147 行（无加速则需 1+71 帧）→ **加速生效** | ✅ |
| 极端 `drawer_open` | 不再 2.7s | 最大 **867ms**（t30 有 2725ms） | ✅ **已消除** |
| `drawer_close` | — | **9–24ms** | ✅ 关闭路径不是巨帧来源 |

---

## 3. 巨帧归因（t33 埋点的核心用途）

- `drawer_close ms=9/19/20/22/24` —— **关闭路径极便宜**，不可能产生 300ms+ 巨帧。
- 巨帧必然落在**打开路径**：`drawer_open ms=242–248`（首帧）+ 随后 28–31 帧的"加速批量组合"（每帧 6→12 行）。
- framestats 的最慢 4 帧（365.8 / 348.6 / 323.6 / 300.3 ms）与 `draw 中位 20.7ms`（t30 仅 2.1ms）一致指向**单帧内组合/绘制了大量真实行 + 占位行**。

→ **t30 的"2 个巨帧"在 t34 变成 4 个，且中位从 39.9 涨到 103.2ms**：t33 把"少数大块"改成"更少但每块更大"（加速到 6–12 行/帧），同时列表 item 恒为 147（全量占位）使**每帧都要遍历/测量 147 个 item**（Draw 中位 2.1→20.7ms 的直接解释）。

---

## 4. 观感

| 时刻 | dump | 结论 |
|---|---|---|
| t+0.35s | 36,580 B，**列表已完整可读** | ✅ 无"先空后填"（比 t30 的 0.9s 更早） |
| t+0.90s | 36,580 B（与 0.35s 完全一致） | ✅ 无二次重排 |
| t+2.60s | 36,580 B | ✅ 无抖动/闪烁 |
| DOWN×2 | 36,613 B（+33） | 焦点移动正常 |
| DOWN×8 | 36,768 B（+155），但可见行仍显示 1–6 | ⚠ **存疑**：疑似焦点下移但列表未跟随滚动（占位行方案下 `scrollToItem` 与揭示解耦后的副作用），建议下一轮单独验证 |

开合跟手性：`drawer_open 242–248ms`（t30 685–757ms）→ **主观"打开更快"**；但帧内单帧成本高（109–150ms 中位），观感上会有**明显卡顿感**（虽然不闪不跳）。

---

## 5. 回归（全部通过）

| 项 | 结果 |
|---|---|
| 信息卡 v3 | ✅ `info_card_logo [60,705][300,849]`（240×144px = **160×96dp**）、`title 'CCTV-12 社会与法' [336,728][751,803]` 同行、`badges 'FHD' [1821,668]`、`tech 'H.264 · MPEG-L2 · SDR'`、长节目名 `回看：2026社会与法电视剧精选-我是刑警-29/38` 单行不换行、`hint`、`recent_row` 均在 |
| 设置二级 | ✅ 一级 6 入口 + `起播缓冲 稳(500ms)` + `关于 版本 10168` |
| 焦点 F1/F2 | ✅ BACK#1 / BACK#2 均仍为 `PlayerActivity` |
| MP2 | ✅ `audio/mpeg-L2 48k/2ch` + `audio_advancing` |
| 数字 OSD | ✅ `zap=1360ms`（1,2 直接跳台） |
| 上一台 229 | ✅ `ZAP 增量=1` |
| 3s 隐藏 | ✅ 4s 后 dump 4,629B |
| 不崩不灰 | ✅ `Fatal signal=0`；`grey/gray/black` 我方 0；`dropped=0` |
| `buffer_min` | ✅ 500（日志 `bufferMin=500`） |

---

## 6. 结论与第六轮建议

**结论：t33 达成"首帧 6 行 / step=2 / 消除 2.7s 极端值 / drawer_close 可观测"四个目标，但整体 jank 明显回归（巨帧 2→4、中位 39.9→103.2、Draw 2.1→20.7、50th 38–46→109–150），不应作为默认。**

第六轮建议（按预期收益，均为单变量可回退）：
1. **撤掉"列表 item 恒为全量（147）+ 占位行"**，回到 t29/t30 的 `subList(0, revealed)`，但**保留 56dp 固定行高**（固定高度本身不是问题，全量 item 才是 Draw 20.7ms 的来源）。
2. **保留 `drawer_close` 埋点**（本轮最有价值的诊断产物：已证明关闭路径 9–24ms）。
3. **降速而非加速**：`DRAWER_ROWS_PER_FRAME` 加速阈值（600/1200ms）在弱机上反而制造大帧，建议**去掉加速**或把加速上限压到 4 行/帧。
4. `DRAWER_FIRST_FRAME_ROWS=6` 可保留（首帧 6 行实测无"先空后填"）。
5. DOWN×8 后列表是否跟随滚动的疑点，建议下一轮用 3 次 dump（滚动前后 + 焦点可见性）单独确认。

## 7. 交付物

- 本报告：`docs/CI_ROUND5_DRAWER_AB.md`
- 原始数据：`docs/evidence/2026-11-22-t34-drawer-r5/`
  - `01-drawer-round1.txt`、`01-drawer-round2.txt`（含直方图）
  - `02-framestats.txt`、`02-framestats-top.txt`（24 帧，≥300ms=4）
  - `03-perception.txt`、`03b-perception-scroll.txt`、`03c-perception-scroll8.txt`
  - `04-regression.txt`、`04b-regression-supplement.txt`、`05-infocard-v3-dump.xml`
- 复跑脚本：`.acceptance/perf-measure.ps1`、`.acceptance/adb-helpers.ps1`
