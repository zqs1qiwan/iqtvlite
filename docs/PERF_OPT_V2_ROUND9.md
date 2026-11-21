# 弱机性能第九轮（t46，最后一刀）：增长也分帧 + 副标题渲染确认 + 滚动落点

> 证据：`docs/CI_ROUND7_DRAWER_AB.md`（t45/CI10177 同批次结论见 captain 摘要）。
> t45（CI10177）：50th **46/36ms** ✅、帧时中位 **33.7ms**（历史最佳）、UI 19.1ms、Draw 2.5ms、
> `frames=14 first=3 target=28` —— 但 **≥300ms 巨帧 5 个（301–393ms）**，且滚动跟随不稳定
> （一次 DOWN×25 跟随，另一次快按后 DOWN×15 仍 1–12）。

---

## 0. 巨帧归因（结论）

巨帧**不是副标题**造成的（t40 无副标题也有 6 个），而是**批量组合帧**：

| 触发 | 旧行为 | 一次组合行数 |
|---|---|---|
| 打开 | `revealedRows = openTarget`（28） | 3 → 28（**+25 行一次性**） |
| 滚动增长 | `revealedRows = lastVisible + 16` | **+16 行一次性** |

单行 ≈12–15ms → 一次 +16/+28 行 = **200–420ms 巨帧**，与实测 301–393ms 完全吻合。

---

## 1. 本轮改动

### A) 唯一增长入口 + 分帧泵（核心）

```
revealTarget（任何触发只抬高它）
        │
        ▼
LaunchedEffect { snapshotFlow { revealTarget }.collect { target ->
    while (revealedRows < target) { withFrameNanos {}; revealedRows = min(revealedRows + 2, target) }
} }
```
- 打开：`revealTarget = openTarget`（有界窗口，仍 first=3 / lookahead=16 / screen=12）；
- 滚动：`revealTarget = lastVisible + LOOKAHEAD`；
- 焦点：`revealTarget = idx + LOOKAHEAD`；
- **任何路径都不再一次性赋值 `revealedRows`**（门禁禁止 `revealedRows = next`）。

效果：单帧恒为 **+2 行**（≈24–30ms），批量巨帧从机制上消失。

### B) 副标题渲染确认

- 副标题 Text 加 `Modifier.testTag("drawer_row_subtitle")`（t45 语义树里没观测到它，需要一个可断言的锚点）；
- 聚焦行打印 `IQTV-PERF row_focus idx=<n> subtitle=<bool> title=<节目名>` —— 直接确认“聚焦行是否真的显示节目”。

### C) 滚动落点：UP/DOWN 由频道列自己处理

t45 的不稳定来自**默认焦点搜索**：下一行还没被揭示时，焦点会跳到右侧节目单面板。本轮在
`onPreviewKeyEvent` 里接管 `DirectionUp/DirectionDown`（仅当 `activeColumn == Channels`）：
```
next = down ? (base + 1) % count : (base - 1 < 0 ? count - 1 : base - 1)
focusedChannelUrl = channels[next].url      // → focusTargetUrl 变化 → 该行挂 requester → 现有 effect 聚焦
pendingFocusToChannels = true
PerfTrace.d { "row_focus idx=$next via=key down=$down" }
```
- 列表内**循环**（首行 UP → 末行、末行 DOWN → 首行），不会跑到节目单面板；
- 想进节目单仍按 **RIGHT**（原逻辑不变）。

---

## 2. 保留项（不得回退）

`subList(0, revealedRows)`、56dp 固定行高、`DRAWER_FIRST_FRAME_ROWS=3`、`DRAWER_ROWS_PER_FRAME=2`、
四个埋点（`drawer_open` / `drawer_compose` / `drawer_close` / `drawer_reveal_grow`）、
`onRowFocusedScroll`（先抬高 target、再等行出现、最后滚动）、`DRAWER_FOCUSED_SUBTITLE`（仅聚焦行副标题）。

---

## 3. 通过线（放宽）与待回填表

**巨帧 0–1、50th ≤50ms、DOWN×25/快按稳定跟随、聚焦行副标题可见、无抖动。**

| 项 | t45（10177） | 本轮（待 t47） | 目标 |
|---|---|---|---|
| ≥300ms 巨帧 | 5（301–393） | ？ | **0–1** |
| 50th | 46/36ms | ？ | ≤50ms |
| 帧时中位 | 33.7ms | ？ | 不劣化 |
| `drawer_compose frames` | 14 | ？（应更多但更轻，≈14–16） | 记录 |
| DOWN×25 / 快按 | 不稳定 | ？ | 稳定跟随 |
| 聚焦行副标题 | 语义树未观测 | ？ | `drawer_row_subtitle` 可见 |
| 观感 | 无抖动 | ？ | 保持 |

---

## 4. 门禁与回退

- `check_perf_policy.py` 新增：`revealTarget` / `snapshotFlow { revealTarget }` / `drawer_row_subtitle` /
  `row_focus idx=` / `via=key` 必须在位；**禁止** `revealedRows = next`（一次性增长回潮）。
- 回退：把泵的步长改成更大值（会回到巨帧，不推荐）；`DRAWER_FOCUSED_SUBTITLE=false` 关副标题。
- 不改冷启/PSS/信息卡/设置/台标；无 Prefs 迁移；体积 ≤30MB。

---

## 5. 交付 receipt

| 项 | 值 |
|---|---|
| 提交 / CI | 见 t46 任务输出（本文件随实现提交，定稿后补 run 号） |
