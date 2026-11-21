# 抽屉第六轮真机 A/B（t36 回退式收口）· CI10170

2026-11-22，acceptance（t37 attempt 1）。设备 **TR100-G9 / Android 9 / API28 / armeabi-v7a / 1080p / 240dpi**。
包 **10170 / 2.2.0-tivimini-5b77d726-validation**（CI run **#170** / app sha256 `adb9e78c1ff80b255eb600d06dc1a065725d79642f491c376be247a595aa696b` / artifact `14715151557`）。

> 列名说明：captain 要求的四列中，**t30** 与 **t34** 是实测轮次；**t33/t36** 是改动轮（t33 的改动由 t34 实测、t36 的改动由本轮 t37 实测）。

---

## 0. 结论：本轮为**四轮以来最佳**，但三项达标条件仍未同时满足

| 指标 | t30（10166，此前最佳） | t33→**t34**（10168） | **t36→t37（10170）** | 目标 |
|---|---|---|---|---|
| **≥300ms 巨帧** | 2 | 4 | **1**（304.3 ms） | **0** ❌ |
| ≥100ms 帧 | 7 | 13 | **4** | — |
| **帧时中位** | 39.9 ms | 103.2 ms | **39.0 ms** | — |
| **UI 线程中位** | 18.1 ms | 49.9 ms | **16.1 ms**（四轮最佳） | — |
| **Draw 中位** | 2.1 ms | 20.7 ms | **2.5 ms**（已回落） | — |
| 最大帧 | 352.7 ms | 365.8 ms | **304.3 ms** | — |
| **50th** | 46 / 38 ms | 150 / 109 ms | **42 / 40 ms** | ≤16 ❌ |
| 90th / 95th | 65/53 · 300/200 | 500/350 · 500/400 | **61 · 150 / 105** | — |
| **Janky** | 100% | 100% | **100%**（375/375 帧 ×2 轮） | ≤20% ❌ |
| 首帧 revealed | 10 | 6 | **6**（first=6） | ≤12 ✅ |
| 每帧步进 | 3 | 2（加速 6/12） | **2（无加速）** | — ✅ |
| `drawer_open ms` | 685–757 | 242–248 | **243–316 / 688** | — |
| `drawer_close ms` | — | 9–24 | **17 / 19** | — ✅ |
| **滚动跟随（DOWN×8/UP×8/DOWN×25）** | — | 不跟随 | **仍不跟随** | 应跟随 ❌ |
| 观感（先空后填/抖动） | 无 | 无 | **无**（0.9s 内容完整、无重排） | — ✅ |

**达标判定（巨帧 0 且 50th≤16ms 且 jank≤20%）：未达标。**
- 巨帧 4 → **1**、Draw 20.7 → **2.5ms**、UI 49.9 → **16.1ms**、中位 103.2 → **39ms** —— **回退式收口（R1 撤全量占位 + R3 去加速）被实测证明是对的**，各项回到甚至优于 t30。
- 但 **50th 仍 40–42ms（目标 16ms）、jank 仍 100%**：说明**剩下的成本不在"组合多少行"，而在"抽屉这一层的固定开销"**（打开/关闭动画 + 147 行容器的测量与绘制录制）。
- **R6 的滚动跟随修复无效**（详见 §3），这是本轮最重要的负面结论。

---

## 1. 安装与健康

| 项 | 值 |
|---|---|
| 安装 | 备份 **2,030,080 B / 325 条目** → **仅卸** `tv.iqtvlite.app.validation`（Success）→ `install -r`（Success）；签名 `3c35eb31`；vc10170；lastUpdateTime 2026-11-22 00:32:28 |
| 实验键 | 装前 **0** / 装后 **0** / 收尾 **0** |
| 其他包 | `tv.iqtvlite.app` / `.validation.test` / `com.cleanroom.iptv` 全程在位 |
| force-stop | **0 次**（HOME + `am kill`） |
| /data | 2,593,428–2,603,524 KB（22%）；守护进程 2；`DeadObject/LMK/ext4/I-O`=0 |
| 崩溃 | 我方 `Fatal signal` 全程 **0** |
| `buffer_min` | **500**（`bufferMin=500`；一级摘要 `稳(500ms)`），全程未变 ✅ |

---

## 2. 埋点：R1/R3/R4/R5 全部按预期落地

```
drawer_open ms=243 total=147 composed=1 revealed=6 first=6 groups=14 group=全部
drawer_compose frames=72 rows=147 ms=2104 lastFrameRows=1 first=6 step=2
drawer_close ms=19
drawer_open ms=316 total=147 composed=12 revealed=6 first=6 groups=14 group=全部
drawer_compose frames=72 rows=147 ms=2724 lastFrameRows=1 first=6 step=2
```

| 检查 | 期望（t36） | 实测 | 结论 |
|---|---|---|---|
| 回到 subList 揭示 | `composed` = 已揭示项数 | **composed=1 / 12** | ✅（不再是 147） |
| 每帧 2 行、无加速 | step=2，恒定 | **step=2；72 帧 = 6 + 2×71 −1** | ✅ 无加速 |
| 首帧行数 | 6 | **revealed=6 first=6** | ✅ |
| `drawer_close` | 仍 9–24ms | **17 / 19ms** | ✅ |
| 揭示总耗时 | 无加速会更长 | **2104 / 2724 ms**（t34 加速版 936–960ms） | 符合预期（代价） |

> 注：无加速后"揭示全部 147 行"要 2.1–2.7s，但因为**首屏 6 行即刻可用**，这段组合发生在用户看不见的区域；代价是这 2–3s 内有持续的组合帧（`frame=72`）。

---

## 3. 滚动跟随（t34 遗留，R6 声称已修）——**实测仍未修复** ❌

同一抽屉会话内连续按键，可见行**完全没有变化**：

| 操作 | 可见行（uiautomator text） |
|---|---|
| 开抽屉（初始） | 1 → 12（`1. CCTV-1 综合 … 12. CCTV-12 社会与法`） |
| **DOWN×8** | **1 → 12（完全不变）** |
| **UP×8** | **1 → 12（完全不变）** |
| **DOWN×25** | **1 → 12（完全不变）** |
| 关抽屉 → 重开 | **15 → 26**（一下跳到焦点区） |
| 二次确认：DOWN×3 后等 2s | 15→19 保持**不变**（before==after） |

**判断**：焦点确实在移动（重开后列表跳到 15–26，说明焦点已到 ~15+ 区），但**按键过程中 LazyColumn 不滚动**；R6 的 `LaunchedEffect(visible, focusedChannelUrl, revealedRows, channels.size)`（`revealedRows=idx+1` + `scrollToItem(idx-2)`）没有生效。可能原因（供下一轮排查）：
- `subList(0, revealedRows)` 使列表**实际只有 revealedRows 项**，`scrollToItem(idx-2)` 被钳在列表末尾 → 必须**先扩 revealedRows 再滚动**，且两步要在**同一帧内**完成才会跟着走；
- 或者 `focusedChannelUrl` 在按键时未变化（焦点在 Compose 内部，未回写状态）→ LaunchedEffect 不触发；
- 也可能 `channelListState` 被 re-`remember` 掉（key 变化）导致每次都是新 state。

> 这条若在真机上不修，用户按 ↑↓ 时列表不动、只有重开抽屉才跳位，属于**可感知的交互缺陷**，建议优先于 jank 数值。

---

## 4. 观感

| 时刻 | dump 尺寸 | 说明 |
|---|---|---|
| t+0.35s | 5,230 B | 抽屉刚滑入（内容未就绪） |
| t+0.90s | **36,710 B** | **内容完整可读**（列表 12 行 + 节目名） |
| t+2.60s（实测约 4.6s，含 dump 延迟） | 6,705 B | 该次采样时抽屉已不在前台（采样时机偏差，不作为结论） |

- ✅ **无"先空后填"**：首帧即有 6 行，0.9s 全屏可读。
- ✅ **无抖动/闪烁**：0.9s 时内容已稳定（与 t30/t34 表现一致）。
- ⚠ 打开瞬间 `drawer_open ms=243–316/688` 比 t34 的 242–248 略慢（因为不再有加速），但与 t30 的 685–757 相当。

---

## 5. 回归（全部通过）

| 项 | 结果 |
|---|---|
| 信息卡 v3 | ✅ 实测（抓取时机敏感，3 次重试后成功）：`info_card` @ `[0,630][1920,1080]`（450px=**300dp**）；`info_card_logo` @ `[60,705][300,849]`（240×144px=**160×96dp**）；`title='CCTV-1 综合'` @ `[336,728][620,803]`（**与台标同行**）；`number='频道 1 · 央视频道 · MPEG-L2'`；`badges='FHD'` @ `[1821,668]`；`program='生活早参考-特别节目（生活圈）2026-320'` @ `[1030,732][1645,781]`（单行未换行）；`program_time='00:14–01:00 · 剩余 6 分钟'`；`tech='H.264 · MPEG-L2 · SDR'`；`hint`、`recent_row` 均在；聚焦日志 `focus index=0` |
| 设置二级 | ✅ 一级 6 入口 + `起播缓冲 稳(500ms)` |
| 焦点 F1/F2 | ✅ BACK#1 / BACK#2 均仍为 `PlayerActivity` |
| MP2 | ✅ `audio/mpeg-L2` 2 条 |
| 数字 OSD | ✅ `zap=1308ms` |
| 上一台 229 | ✅ `ZAP 增量=1` |
| 3s 隐藏 | ✅ 4s 后 dump 4,629B |
| 不崩不灰 | ✅ `Fatal signal=0`；`grey/gray/black` 我方 0；`dropped=0` |
| 实验键 / buffer_min | ✅ keys=0；`bufferMin=500` |

---

## 6. 结论与第七轮建议

**结论：R1（撤全量占位）+ R3（去加速）是正确的回退，把 t34 的全面恶化拉回到四轮最佳（巨帧 1、Draw 2.5ms、UI 16.1ms、中位 39ms）；但 50th 仍 40–42ms、jank 仍 100%、巨帧还有 1 个，三项达标条件未同时满足。另发现 R6 滚动跟随修复无效。**

第七轮建议（按优先级）：
1. **先修滚动跟随**（交互缺陷 > 数值）：确认 `focusedChannelUrl` 是否在按键时更新；把"扩 revealedRows"与"scrollToItem"合并到一次 `snapshotFlow` 里，或直接 `channelListState.scrollToItem(idx)` 前先 `revealedRows = max(revealedRows, idx + 1)` 并用 `LaunchedEffect(focusedChannelUrl)` 单点触发。
2. **干掉最后 1 个 304ms 巨帧**：它出现在打开首帧或首屏 6 行绘制录制上。建议 `DRAWER_FIRST_FRAME_ROWS` 6→**3**（首屏仍不见空，但首帧录制量再减半），或首帧只画容器+3 行、第 2 帧补齐 6 行。
3. **把 50th 从 40 压到 16**：既然 Draw 已 2.5ms、UI 16.1ms，剩余 40ms 中位说明**同一帧里 UI 与 Draw 串行排满**。下一步应查"抽屉这一层是否仍每帧整层重录"（t20 的 E4 不透明容器是否被 t33/t36 改动影响），必要时给列表加 `graphicsLayer` 缓存或 `drawWithCache`。
4. 保留：subList 揭示、56dp 固定行高、first=6、step=2、`drawer_close` 埋点。
5. 冷启/PSS 维持 t29 结论（不测不判）。

## 7. 交付物

- 本报告：`docs/CI_ROUND6_DRAWER_AB.md`
- 原始数据：`docs/evidence/2026-11-22-t37-drawer-r6/`
  - `01-drawer-round1.txt`、`01-drawer-round2.txt`（含直方图）
  - `02-framestats.txt`、`02-framestats-top.txt`（120 帧，≥300ms=1）
  - `03-scroll-follow.txt`、`03b-scroll-recheck.txt`（滚动跟随证据）
  - `04-perception.txt`、`05b-infocard-dump2.xml`（信息卡 v3 完整节点）、`06-regression.txt`
- 复跑脚本：`.acceptance/perf-measure.ps1`、`.acceptance/adb-helpers.ps1`
