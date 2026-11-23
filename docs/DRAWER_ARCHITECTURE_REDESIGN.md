# DRAWER_ARCHITECTURE_REDESIGN — 选台抽屉架构重构设计（首帧直接定位，零滚动 worker）

> 2026-11-23 · t90 · researcher（**只读架构设计，不改代码、不跑设备**）
> 基线：HEAD `5d16707`（t87 live-focus），分支 `integration/tivimini-baseline`。
> 输入：用户明确指令「从底层架构重新设计、严禁补丁式修复、设计错误就推翻重构；TiviMate 呼出即在当前台位置、不滚动过去」；acceptance `docs/DRAWER_ARCHITECTURE_ANALYSIS.md`（补丁链逐层证据）；`docs/GOLDEN_SPEC.md` / `docs/REGRESSION_CHECKLIST.md` / `docs/INTERACTION_REGRESSION_SPEC.md`；`docs/V4_SPEC.md`。
> **本文件取代 `GOLDEN_SPEC.md §3` 关于「平滑滚动」的表述**：新金标准 = **打开即在位、焦点移动瞬时到位、无滚动 worker / 无 animateScrollBy**。以本文件为准。

---

## 0. 为什么必须推翻（当前架构的根因）

### 0.1 错误的设计模型
当前把「呼出抽屉」实现为：`open → 计算 target → LazyColumn 滚（scrollToItem/animateScrollBy）到当前台`。**"打开后再滚过去"本身就是错的模型**——它把定位变成一个与用户输入竞态的异步过程，于是必然产生"差 1–2 行 / 反向滚 / 高亮与列表不同步"。

### 0.2 补丁链（t49→t87，每层修上一层引入的竞态，越叠越乱）
| 版本 | 引入的东西 | 修不了的后果 |
|---|---|---|
| t49 | `drawer_scroll_correct once` | 修正时机受分帧揭示影响 |
| t52 | `animateScrollBy` 平滑滚 | 动画是过程 → 与输入竞态 |
| t56 | focus-6 居中 | 几何写死，真机行数不一 |
| t62 | `INSTANT` 模式 | 与 smooth 模式并存，双路径 |
| t65 | `shouldArmInstantLocate` | 换台/重开边界仍需特判 |
| t69 | `scrollIntentFlow` worker 解耦 | worker key 与快照时序耦合，末次 intent 漏发（t83 差 1） |
| t75 | 静态中心高亮覆盖层 | 高亮固定中心，但列表未必到位 → 指向错台（t77 差 1–4） |
| t78 | `half=(V-1)/2` 几何对齐 | 只修公式，没修"列表真的滚到目标"（呼出差 0，DOWN/UP 差 2） |
| t81 | `CLEAR=first==target` | 末次焦点变化未触发校正（t83 差 1） |
| t84 | 稳态监测 `reconverge` | 用启动时旧 focus 快照，焦点已 23 还按 21 反向滚（t86 铁证 `smooth=-1`） |
| t87 | `liveFocusIndex()` 实时重读 | 未及验证即被用户叫停 |

### 0.3 日志铁证
- t83：`drawer_scroll_correct target=16 focus=21 first=14 last=24`，终态 focus=22 → 差 1。
- t86：`row_focus idx=23` 后紧接 `drawer_steady_correct focus=21 … reconverge` + `drawer_scroll_correct target=16 focus=21 smooth=-1` → 焦点前进、滚动反向。
- 多轮 dump：**呼出（首帧 INSTANT）恒差 0**；任何"打开后再滚"的连续按键（DOWN×10/UP×10）都 1–2 行波动——因为滚动本身就是与输入竞态的过程。
- 结论：唯一稳定的是"首帧就位"，那就把它做成**唯一机制**，删掉所有"事后滚动"。

---

## 1. 首帧直接定位模型（TiviMate 式，核心）

### 1.1 设计
抽屉可见的**首帧组合**时，LazyColumn 的 `LazyListState` 初值就把当前台放到中心高亮框位置，**抽屉出现即到位，全程没有滚动动画过程**。

```kotlin
// 在 PlayerDrawer 里，visible 翻转到 true 的那一刻同步算出（不是 LaunchedEffect 里事后算）：
val viewportRows = PerfPolicy.drawerVisibleRowsFromPx(viewportHeightPx, rowHeightPx) // ~10
val half = PerfPolicy.drawerCenteredHalf(viewportRows)                                 // (V-1)/2
val focusIndex = /* focusedChannelUrl → channelIndexByUrl，同步查表 */
val targetFirst = PerfPolicy.firstVisibleForFocus(focusIndex, channels.size, viewportRows)
val channelListState = rememberLazyListState(initialFirstVisibleItemIndex = targetFirst,
                                             initialFirstVisibleItemScrollOffset = 0)
```

- **关键**：`targetFirst` 直接进 `rememberLazyListState` 的构造参数；组合出来的第一帧，LazyColumn 的 firstVisible 就是 targetFirst。没有 `scrollToItem`、没有 `animateScrollBy`、没有 worker。
- `focusIndex` 由 `focusedChannelUrl → channelIndexByUrl` **同步计算**（现有 `focusTargetIndex`/`liveFocusIndex` 逻辑简化为一个 remember，见 §3）。
- 抽屉**关闭期间**用 `key(visible/drawerSession)` 让 `rememberLazyListState` 在每次关→开重新创建，从而重算初值（对应 t65「每次呼出重新定位当前台」）；抽屉**打开期间**换台不改 `selectedChannelUrl` 驱动的初值 → UI 原地保持（§5）。

### 1.2 与 openFocusUrl 冻结的关系
- 现有 `openAnchor = remember(visible){ initialFocusUrl ?: selectedChannelUrl }`（`PlayerDrawer.kt:359`）的冻结语义**保留但用途改变**：它不再是"事后定位的意图来源"，而是**首帧初值的 anchor 输入**。
- 首帧 anchor 优先级沿用 t69⑤ `drawerOpenPositionAnchor(playingUrl=selectedChannelUrl, browseAnchor=openAnchor, …)`：从播放器按 LEFT 进入 → 当前播放台；切组内恢复 → 该组记忆焦点。算出 URL 后同步查 `channelIndexByUrl` 得 focusIndex → targetFirst。
- 打开期间 `selectedChannelUrl` / `focusedChannelUrl` 的变化**不再回写初值**；焦点移动走 §3 的瞬时 scrollToItem。

---

## 2. 揭示策略配合（去掉逐帧补行卡顿）

### 2.1 设计
打开时**首帧就揭示"当前台 ± 一屏"的完整可视窗口**，LazyColumn 只组合可见项（+ 少量 overscan），不会巨帧；不再从 3 行逐帧 +2 补行（那是"加载卡顿"的观感来源）。

```kotlin
// 首帧 revealedRows 直接 = 覆盖可视窗口的最小值（不再从 3 开始）：
val initialReveal = PerfPolicy.initialRevealRows(focusIndex, channels.size, viewportRows)
// = minOf(channelCount, maxOf(viewportRows, targetFirst + viewportRows))，S1 安全钳制
var revealedRows by remember { mutableIntStateOf(initialReveal) }  // visible 翻转时重置
```

- 一次性组合 ~viewportRows + overscan（真机 ~10–14 行 × 64dp），**不是全量 147 行**——LazyColumn 本来就只组合可见项，揭示窗口 = 可视窗口 + 缓存，首帧成本与旧「3 行 + 72 帧补满」相比是**更少的总帧数**（1 帧 vs 72 帧），单帧多行但仅是可见区组合。
- 需要真机 A/B gfxinfo 验证：首帧 draw/compose 时长与 ≥300ms 巨帧数不劣于旧基线（CI10204：0 巨帧、50th 32–38ms）。若单帧过高，退路 = 首帧揭示 `viewportRows`（只可见区，无 overscan），仍远大于旧的 3 行，且不逐帧 +2。

### 2.2 换台 / 焦点越界的揭示增长
- 焦点移动出当前揭示窗口时，**瞬时把 revealedRows 抬到覆盖新焦点的窗口**（一次性，不用分帧泵）：`revealedRows = maxOf(revealedRows, initialRevealRows(newFocus, …))`。
- 删除 t46 分帧泵（`snapshotFlow{revealTarget}` + `withFrameNanos` 每帧 +2）、`revealTarget`、`reveal_grow`、`DRAWER_LOOKAHEAD_ROWS` 增长逻辑——首帧一次到位 + 焦点越界时一次性抬高，无需"渐进揭示"。
- 保留 LazyColumn 的 `contentType/key` 与三层 `graphicsLayer` 缓存（列表层滚动/重录不影响背景/内容层）。

---

## 3. 焦点与列表关系简化（纯 state index + 瞬时 scrollToItem，无 worker）

### 3.1 单一真源
```
focusedChannelUrl (mutableState)  ──查表──>  focusIndex
                                              │
                    ┌─────────────────────────┼──────────────────────────┐
                    │ 首帧：initFirstVisible=firstVisibleForFocus(...)    │
                    │ 之后焦点变化：channelListState.scrollToItem(        │
                    │     firstVisibleForFocus(focusIndex,count,V))      │  ← 唯一滚动调用
                    └─────────────────────────────────────────────────────┘
```
- 只保留**一个**滚动调用点：焦点变化时 `LaunchedEffect(focusIndex) { channelListState.scrollToItem(firstVisibleForFocus(focusIndex, count, V)) }`（**瞬时、无动画**）。
- 因首帧已把初值设成同一公式结果，打开瞬间这个 effect 的 scrollToItem 是**幂等的 no-op**（first 已等于 target），不会产生可见滚动；它只在焦点**后续变化**时才真正瞬时重定位。
- 长按/连续 UP/DOWN：每次焦点 state 更新 → effect 瞬时 scrollToItem 到新 target，逐行即时响应（"丝滑 = 即时不卡"，不是慢动画）；无 worker、无 animate、无 converge、无稳态监测、无 scrollInProgress 守卫。
- 中心高亮覆盖层（t75/t78，**保留**）：bounds 固定在中心 `highlightTop = half*rowH`，列表瞬时 scrollToItem 后焦点行 top 恰等于 highlightTop → 两者差 0，无需几何 "对齐补丁"，公式本来就是同一个 `firstVisibleForFocus`。

### 3.2 为什么没有竞态
- 滚动不再是"过程"（animateScrollBy 消除了），`scrollToItem` 是同步一次性提交；输入（UP/DOWN）只改 focusIndex 一个 state，effect 跟随它做确定性映射。**没有"滚动中焦点前进"的窗口**，t83/t86 那类竞态从架构上不可能发生。
- 高亮与列表共用同一个 `firstVisibleForFocus` 公式（首帧初值 + 焦点 scrollToItem + 高亮 top 都从它派生），不存在"高亮指向 A、列表停在 B"。

### 3.3 长按不卡的优化
- 瞬时 `scrollToItem` 天然无逐帧动画 → 长按连续按键不会积累未完成的动画；每次焦点变化立即到位，长按到顶/底也顺滑（无 ±8 step 限幅、无动画取消问题）。

---

## 4. 删掉的东西清单（必须全部移除）

### 4.1 PlayerDrawer.kt
| 删除项 | 当前行号 | 说明 |
|---|---|---|
| `import animateScrollBy` | 14 | 不再用平滑滚动 |
| `desiredScrollIndex` / `desiredScrollInstant` | 249/252 | 意图真源 → 由 focusIndex 直接派生 |
| `scrollIntentFlow` / `ScrollIntent` data class / `postScrollIntent()` | 255 / 120 / 526 | 整个 worker 消息链删除 |
| `lastFocusEventAt` / rapid 判定 | 257 / 554-557 | 长按/单点分支合并为统一瞬时 |
| `centerVisibleRowsState` 稳态快照 | 376/377-384 | 由首帧 viewportRows 派生，不再 snapshotFlow 监测 |
| `scrollInProgress` 守卫 | 437 | 无动画过程 → 无需防重入 |
| `liveFocusIndex()` | 442-445 | 由单一 focusIndex remember 取代 |
| `convergeFocusToCenter()` (24 轮) | 447-479 | 整个收敛循环删除 |
| 意图 worker `LaunchedEffect` + `scrollIntentFlow.collect` | 483-499 | 删除 |
| 稳态监测 `LaunchedEffect` + `snapshotFlow{…}` + `needsSteadyCorrection` 调用 | 505-523 | 删除 |
| 意图写入口调用（open/换台/焦点兜底） | 571/656/751 等 `postScrollIntent` | 改为首帧初值 + 焦点 effect |
| `onRowFocusedScroll` 里的 lookahead/意图投递 | 550-559 | 改为只 `revealedRows=maxOf(...)` + focusIndex 更新 |
| 分帧泵 + `revealTarget` + `reveal_grow` | 246/391-433/534-546 | 一次性揭示取代 |

### 4.2 PerfPolicy.kt（删除/作废，保留被 §1/§2/§3 复用的）
- 删除：`drawerScrollDecision` + `enum ScrollDecision`、`drawerMonotonicStep`、`needsSteadyCorrection`、`isEdgeFocus`（若仅稳态用）、`isRapidFocus` / `DRAWER_FOCUS_CADENCE_MS`、`drawerScrollStep`、`drawerScrollDelta`、`DRAWER_SCROLL_MAX_STEP`、`DRAWER_SCROLL_EDGE_ROWS`、`drawerScrollTargetIndex`（由 `firstVisibleForFocus` 取代）、`drawerScrollCorrected`（若仅旧门控用）、`drawerFocusNeedsRevealCorrection`。
- **保留并复用**：`drawerCenteredHalf`（§1 计算 half）、`drawerVisibleRowsFromPx`（viewport）、`drawerSafeOpenTarget` / `drawerFirstRows` / `drawerSafeFirstVisibleIndex`（S1 安全钳制）、`drawerInitialIndex` / `drawerOpenPositionAnchor`（首帧 anchor）、`DRAWER_CENTER_HIGHLIGHT` + `drawerHighlightTopPx`（高亮覆盖层）、`DRAWER_ROW_HEIGHT_DP=64`、`DRAWER_PROGRAM_AREA_ALPHA`、`DRAWER_CONTAINER_ALPHA`、`DRAWER_OPAQUE_CONTAINER`、`DRAWER_INCREMENTAL_ROWS`(新语义：首帧一次揭示，常量可改名 `DRAWER_INITIAL_REVEAL_VISIBLE`)。
- 新增纯函数 `firstVisibleForFocus(focus, count, viewport)`（§6），并让 `drawerHighlightTopPx` 与它共用 `drawerCenteredHalf`，保证几何一致。

### 4.3 必须保留（与滚动无关的正确改动）
- t72 透明节目单区（`DRAWER_PROGRAM_AREA_ALPHA`，无竖线分割）；
- t69 近期 5/4 大 logo、信息卡 v4、BACK 分层状态机、数字 OSD 右上、焦点灰白/选中蓝字；
- t75 固定中心高亮覆盖层（配合新公式，天然对齐，不再是补丁）；
- 抽屉自动关：t81 把 `DRAWER_AUTO_CLOSE_MS` 从 7000 临时放宽到 **15000**（`PerfPolicy.kt:166`，纯验收值）→ **重构后确认恢复产品值 7000ms**，并单独真机验证（不再依赖"滚动收敛后才取稳定 dump"）。

### 4.4 测试/门禁同步
- 删除旧门禁里对 `drawerScrollDecision` / `drawerMonotonicStep` / `convergeFocusToCenter` / `scrollInProgress` 等的断言（`scripts/check_perf_policy.py`）；新增「无 animateScrollBy / 无 scrollIntentFlow / 首帧初值用 firstVisibleForFocus / 唯一 scrollToItem 调用点」的断言（防止补丁回流）。
- 删除对应旧 JVM 用例（t83/t84/t86 的收敛/稳态用例）；新增 `firstVisibleForFocus` 用例（§6）。

---

## 5. 边界与约束（逐条给确定行为，不再加补丁）

| 场景 | 行为（由 `firstVisibleForFocus` 统一裁决） |
|---|---|
| `count == 0` | 返回 0；抽屉空列表不组合频道行，高亮覆盖层可隐藏 |
| `count < viewport`（小分组） | 返回 0（列表不能滚动，全部可见）。**已裁定（captain 2026-11-23）：中心高亮覆盖层跟随焦点行的实际 y，不隐藏**——小分组行数少于可视区、焦点无法几何居中，隐藏会让用户不知道聚焦哪个台，跟随实际行符合直觉。这是全设计唯一例外，用纯函数 `highlightTopFor(focus,count,V,rowH)` 实现（非硬编码补丁），已写入 `GOLDEN_SPEC.md §3` |
| 焦点在顶部（focus<half） | first=0（贴顶，物理无法居中）；与用户对齐"首尾贴边"口径（acceptance §4.4），**接受，不加补丁** |
| 焦点在底部（focus≥count-half） | first=count-viewport（贴底）；同上 |
| 分组切换 | 组名真变化时，focusIndex 重置为该组第一台（0）→ 首帧 targetFirst=0；抽屉保持打开时用瞬时 scrollToItem(0) 到位；复用 `selectGroup` 幂等（V4 §2） |
| 换台时抽屉已打开 | **UI 原地保持**：播放台变化只更新"选中台蓝字"，不改 focusIndex、不重算首帧初值、不 scrollToItem（换台与浏览焦点解耦，GOLDEN §3） |
| 切组记忆 | 按组记忆的 `lastFocusedByGroup`（V4 §2）作为切回该组时的首帧 anchor；但用户"从分组进入从头"语义优先 → 组切换直接进入用 focus=0（见上"分组切换"行） |
| 首帧初值 vs 打开期间 | 每次关→开用 `key(visible)` 重建 LazyListState（重算首帧初值）；打开期间只通过 §3 的焦点 effect 瞬时移动，不再重建 state |

---

## 6. 可测性

### 6.1 纯函数 + JVM 用例
```kotlin
// PerfPolicy.kt（新）
fun firstVisibleForFocus(focusIndex: Int, channelCount: Int, viewportRows: Int): Int {
    if (channelCount <= 0 || viewportRows <= 0 || focusIndex < 0 || focusIndex >= channelCount) return 0
    if (channelCount <= viewportRows) return 0
    val half = drawerCenteredHalf(viewportRows)
    val hi = channelCount - viewportRows
    return (focusIndex - half).coerceIn(0, hi)
}
```
JVM 用例（新建 `DrawerFirstVisibleTest`）：
- 居中：V=11,half=5,count=147,focus=30 → first=25；
- 顶边：focus=2 → first=0；底边：focus=145 → first=136（147-11）；
- 小列表：count=5,V=11 → first=0；count=0 → 0；focus 越界 → 0；
- 高亮一致性：`drawerHighlightTopPx(V,rowH) == drawerCenteredHalf(V)*rowH`，且 `firstVisibleForFocus(focus)+half == focus`（非边缘时），即焦点行恰在高亮框。

### 6.2 真机判定（GOLDEN 配套）
| 判定 | 方法 |
|---|---|
| 呼出 0ms 到位 | 清 log 冷启 → 从播放器按 LEFT → **首帧 dump** 可见行 first == `firstVisibleForFocus(当前台)`，焦点行在高亮框中心；无 `drawer_scroll_correct` / `drawer_steady_correct` / `animateScrollBy` 日志（=0 次） |
| 焦点瞬时到位 | 打开后 DOWN/UP×10：每次 `row_focus idx` 后紧接着 dump，first 立即 == 公式值，**无中间帧**、无 1–2 行波动 |
| 长按不卡 | 长按 DOWN 到列表底：逐行即时到位、无动画残留、无 ≥300ms 巨帧 |
| 换台保持 | 抽屉开→换台：可见行不变、无 scrollToItem 日志、仅选中台蓝字切换 |
| 无加载卡顿 | gfxinfo：开抽屉 0 巨帧、50th 不劣于 32–38ms（CI10204 口径） |
| 自动关 | 空闲 7s（恢复产品值后）抽屉自动收起 |

### 6.3 静态门禁断言（替代旧补丁断言）
- `PlayerDrawer.kt` 不得出现：`animateScrollBy` / `scrollIntentFlow` / `convergeFocusToCenter` / `scrollInProgress` / `drawerScrollDecision` / `drawerMonotonicStep` / `needsSteadyCorrection` / `desiredScrollIndex`。
- 必须出现：`firstVisibleForFocus(`（用于 initialFirstVisibleItemIndex）、`channelListState.scrollToItem(` **恰好一处**（焦点 effect）、`rememberLazyListState(initialFirstVisibleItemIndex =`。
- `PerfPolicy.kt` 必须有 `fun firstVisibleForFocus(`；`DRAWER_AUTO_CLOSE_MS == 7000`。

---

## 7. 迁移步骤（给 developer，顺序执行、每步可回退）

1. **加纯函数**：`PerfPolicy.firstVisibleForFocus` + `initialRevealRows` + JVM 用例（§6.1），先绿。
2. **首帧定位**：在 `PlayerDrawer` 用 anchor 同步算 focusIndex → `rememberLazyListState(initialFirstVisibleItemIndex = firstVisibleForFocus(...))`，并以 `key(visible)` 每次打开重建 state；同时 `revealedRows` 首帧设为 `initialRevealRows(...)`。
3. **加焦点 effect**：唯一 `LaunchedEffect(focusIndex){ channelListState.scrollToItem(firstVisibleForFocus(...)) }`；首帧它是 no-op。
4. **删除补丁链**：按 §4.1 逐块删 PlayerDrawer、§4.2 删 PerfPolicy 函数、§4.4 更新门禁与测试。
5. **自动关恢复 7000ms**，单独验证。
6. **保留项复核**：t72 透明节目区、t69 logo、t75 高亮覆盖层、分组/右键、换台保持不回退。
7. **真机全判定**（§6.2）+ 全量回归（GOLDEN/REGRESSION_CHECKLIST），CI 出包后按"呼出 0ms 到位 + animateScrollBy=0 次"硬判。

---

## 8. 冲突裁定（以本文件为准）

- **覆盖 `GOLDEN_SPEC.md §3`「滚动均匀平滑 animateScrollBy」**：新金标准改为"焦点移动瞬时 scrollToItem、无平滑动画、无 worker"。GOLDEN §3 的"均匀不整页翻"由"瞬时到位"天然满足（没有过程就不会"一会滚一会翻"）。
- **覆盖 `GOLDEN_SPEC.md §3`「按组记忆焦点」**：保留记忆，但"从分组进入从头开始"优先级更高（用户确认），首帧 anchor 用组首台。
- **覆盖 `GOLDEN_SPEC.md §6` 自动关时长**：t81 的 15s 是临时验收值，重构后恢复产品 7s。
- 小分组高亮例外（§5）**已由 captain 裁定**：count<可视行数时高亮跟随焦点行实际 y、不隐藏，已写入 `GOLDEN_SPEC.md §3`；定稿，无需再加补丁。

> 目标达成判据（用户原话）：**"打开即在位、焦点移动瞬时到位、无滚动 worker"**——代码里搜不到 animateScrollBy/scrollIntentFlow/convergeFocusToCenter，呼出首帧 dump 即差 0，真机无加载卡顿。
