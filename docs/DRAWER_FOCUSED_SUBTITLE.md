# 抽屉行：仅聚焦行显示节目副标题（t44，信息不减法的折中）

> 背景：t42 为降单行成本把抽屉行的 EPG 副标题/进度条全部删掉（行只剩台标+序号+台名），
> 属于**信息减法**；用户在意信息展示。本任务把信息找回来，但**只给聚焦行**，保持非聚焦行零成本。

---

## 1. 实现

| 项 | 内容 |
|---|---|
| 开关 | `PerfPolicy.DRAWER_FOCUSED_SUBTITLE = true`（改 `false` 即回到 t42 的精简行） |
| 触发 | `val programTitle = if (PerfPolicy.DRAWER_FOCUSED_SUBTITLE && focused) nowProgram?.title else null` —— `focused` 由 item 自己的 `onFocusChanged` 驱动 |
| 聚焦行结构 | `Row(56dp)` → Logo + Spacer + `Column(weight 1)` → 台名（titleMedium）+ **副标题 11sp/14sp 单行 ellipsis** + 可选 **2dp 进度**（两个 Box，底 + `fillMaxWidth(fraction)`） |
| 非聚焦行结构 | `Row(56dp)` → Logo + Spacer + **单个 Text**（与 t42 完全一致，零副文本成本） |
| 行高 | 仍 `DRAWER_ROW_HEIGHT_DP = 56dp`，副标题压在台名下、字号 11sp，**不改变行高** |
| 禁用 | 不引入 `SubcomposeLayout` / `IntrinsicSize`；**不用** `FocusMarqueeText`（动画节点）/`ThinProgressBar`（额外 draw） |

聚焦切换时旧行立刻回到 `else` 分支（副标题节点被移除、立即释放）。

---

## 2. 成本模型（为什么可接受）

| 场景 | 每帧成本 |
|---|---|
| 非聚焦行（147 行里绝大多数） | 与 t42 相同：2 个节点、1 次文本测量 |
| **聚焦行**（任意时刻最多 1 行） | 约 12ms（多 1 次文本测量 + 2 个 Box）——**每帧最多 1 行**，不会随行数放大 |
| 打开/滚动揭示 | 每帧 2 行（step=2），其中最多 1 行是聚焦行 |

因此理论预期：**巨帧 0–1、50th 与 t42 同级（≤50ms）**，而信息完整度恢复到 t37 水平（聚焦行有节目名+进度）。

---

## 3. 门禁

`check_perf_policy.py`：
- 要求 `PerfPolicy.DRAWER_FOCUSED_SUBTITLE` 存在且默认 `true`；
- 要求行内副标题**必须**被 `if (PerfPolicy.DRAWER_FOCUSED_SUBTITLE && focused)` 门控；
- `DrawerChannelItem` 体内仍**禁止** `FocusMarqueeText(` / `ThinProgressBar(` / `无信息` / `SubcomposeLayout`
  （即：允许副标题，但只允许“轻量副标题”，不允许回退成重组件）。

---

## 4. 回退

- 只想关副标题：`DRAWER_FOCUSED_SUBTITLE = false`（一行，回到 t42）。
- 若 t43 实测 **50th > 60ms 或巨帧 > 1**：按 captain 指示回退 t44（同上一条），并在最终报告说明取舍
  （“抽屉行不含节目信息，信息由信息卡承担”）。

---

## 5. 交付 receipt

| 项 | 值 |
|---|---|
| 提交 / CI | 见 t44 任务输出（本文件随实现提交，定稿后补 run 号） |
