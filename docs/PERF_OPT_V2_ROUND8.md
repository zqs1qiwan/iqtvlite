# 弱机性能第八轮（t42，最后一轮）：t37 节奏 + t40 滚动 + 单行成本减法

> 证据：`docs/CI_ROUND7_DRAWER_AB.md`（t40，包 **CI10173 / vc10173**）。
> 三轮曲线：t37（step=2，72 帧）巨帧 1 / 50th 40ms；t34（加速 12 行/帧）巨帧 4 / 50th 150·109ms；
> t40（有界窗口 28 行，2 帧）巨帧 6 / 50th 300·65ms —— **jank 三者都是 100%**。

---

## 0. 关键判断：帧调度已到极限，瓶颈是「单行成本」

t40 的结论最重要：把「72 帧 × ~40ms」换成「2 帧 × 342–455ms」→ **总工作量不变，只是摊在不同帧里**。
反推：**单行组合+布局+绘制 ≈ 12–15ms**。因此：
- 想靠“每帧几行”把 50th 压到 ≤16ms **不可行**（每帧最多 1 行，而 147 行仍有 147 个超预算帧）；
- 唯一出路是**把单行成本本身降下来**，再谈帧调度。

---

## 1. 本轮改动

### A) 恢复 t37 的打开节奏，但只补到「有界开窗目标」（两者取优点）

| 方案 | 帧数 | 单帧 | 结果（真机） |
|---|---|---|---|
| t37：step=2 补满 147 行 | 72 | 2 行 | 巨帧 1、50th 40ms |
| t40：第 2 帧直接 28 行 | 2 | 28 行 | 巨帧 6、50th 300ms |
| **t42（本轮）**：step=2 补到 `openTarget` | **≈13**（3→28） | **2 行** | 待 t43 |

`openTarget = initialScrollIndex + LOOKAHEAD(16) + SCREEN(12)`（上限 total）：
打开时只把“选中行 + 下一屏”准备出来，**之后靠滚动按需增长**（t40 已验证有效）。

### B) 单行成本减法（本轮核心）

| 项 | 旧行（t33–t40） | 新行（t42） |
|---|---|---|
| 内容 | 台标 + 序号/台名 + **EPG 副标题 + 跑马灯 + 进度条 + “无信息”占位** | **台标 + 序号 + 台名** |
| 组合节点 | Row → Column → 2–3 个 Text + Marquee + 2–3 个 Box | Row → Logo + **单个 Text** |
| 文本测量 | 每行 2–3 次（标题/副标题/占位） | **1 次** |
| 动画节点 | `basicMarquee`（即使 iterations=0 也挂节点） | **无** |
| 行高 | 56dp 固定 | 56dp 固定（保留） |

> 取舍：抽屉行不再显示“当前节目”。这是用户可见的信息减法，但换来的是**每行组合成本**的大头
> （副标题文本测量 + 进度条 draw + 跑马灯动画节点）。信息卡（info card）仍完整显示节目/进度，
> 所以信息并未丢失，只是从“列表行”移到“选中后的信息卡”。

---

## 2. 保留项（不得回退）

- `onRowFocusedScroll` + item `onFocusChanged` 驱动的**滚动跟随**（t40 已验收：DOWN×25 跟随、快按跟手、重开位置一致）；
- 滚动驱动按需增长 `snapshotFlow { lastVisibleIndex } → +LOOKAHEAD`；
- `subList(0, revealedRows)` 揭示、56dp 固定行高、`DRAWER_FIRST_FRAME_ROWS=3`；
- 埋点：`drawer_open ms=.. composed=.. revealed=..`、`drawer_compose frames=.. ms=.. step=..`、`drawer_close ms=..`、`drawer_reveal_grow rows=..`。

---

## 3. 目标与待回填表（t43）

放宽口径：**≥300ms 巨帧 0（最多 1）**、**50th ≤50ms**、滚动跟手、无抖动/先空后填；
若单行成本降到 ~4ms，再冲 50th ≤16ms。

| 项 | t37（10170） | t40（10173） | 本轮（待 t43） | 目标 |
|---|---|---|---|---|
| ≥300ms 巨帧 | 1（304.3） | 6（455.8 最大） | ？ | 0–1 |
| 50th | 42/40ms | 300/65ms | ？ | ≤50ms |
| 帧时中位 | 39.0ms | 56.4ms | ？ | 下降 |
| UI 中位 | 16.1ms | 23.6ms | ？ | 下降 |
| `drawer_compose` frames | 72 | 2 | **≈13** | 记录 |
| DOWN×25 跟随 | ❌ | ✅ | ✅（保留） | 保持 |
| 观感 | 无抖动 | 无抖动 | ？ | 保持 |

---

## 4. 单行成本测量方法（t43 可直接用）

```
# 单行成本 ≈ (打开窗口内首帧 UI 时长) / 首帧行数；或对比 step=1/2/4 的帧时中位斜率
adb shell dumpsys gfxinfo tv.iqtvlite.app.validation reset   # 开抽屉一次后立刻 dump
adb logcat -d -s IQTV-PERF | grep -E 'drawer_compose|drawer_open'
```
若 `drawer_compose frames≈13` 且 50th 明显下降 → 说明减法生效；若仍 100+ms/帧，则单行成本仍高，
下一步只能“自绘虚拟列表 / 台标预渲染 Bitmap + drawWithCache”（已超出本轮范围）。

---

## 5. 门禁与回退

- `check_perf_policy.py` 新增：`openTarget`（有界开窗）必须存在；`DrawerChannelItem` 体内**禁止**出现
  `FocusMarqueeText(` / `ThinProgressBar(` / `nowProgram` / `无信息`（防止回退成重行）。
- 回退：把 EPG 副标题/进度条加回行内（不推荐）；`openTarget` 改回 `channels.size` 即回到 t37 的 72 帧节奏。
- 不改冷启/PSS 口径；未动信息卡/设置/台标/自动更新；无 Prefs 迁移；体积 ≤30MB。

---

## 6. 交付 receipt

| 项 | 值 |
|---|---|
| 提交 / CI | 见 t42 任务输出（本文件随实现提交，定稿后补 run 号） |
