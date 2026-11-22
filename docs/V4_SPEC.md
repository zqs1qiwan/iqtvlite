# V4_SPEC — 信息卡 v4 + 选台交互 + 数字 OSD + logo 资源 定位规格（t18）

> 2026-11-22 · researcher（只读调研，**不改代码、不跑设备**）
> 基线：`iqtvlite` HEAD `f3a3332`（分支 `integration/tivimini-baseline`，与 origin 同步；工作树仅 3 个 untracked 文档/证据）。
> 输入：`docs/INFO_CARD_V4_CONFIRM.md`（用户确认图 + 追加的分组交互/焦点蓝规则）、`docs/BUG_ZAP_LIST_RESET_REPRO.md`（t14 复现）、t15 滚动校正提交 `f3a3332` 与 **CI #216 failure**（[run 45032187632](https://github.com/zqs1qiwan/iqtvlite/actions/runs/45032187632)）。
> 目标机：TR100-G9 / API28 / 1080p / 240dpi → 逻辑屏 1280×720dp，1dp=1.5px。
> 每项给「根因 → 文件/函数/行号 → 单变量方案 → 可测用例 → 真机步骤」。行号以 HEAD 为准，实现前按 §10 复核。

---

## 0. 总览与全局约束

| § | 条目 | 主文件 | 优先级 |
|---|---|---|---|
| 1 | 打开闪到开头 + 打开即高亮 | `PlayerDrawer.kt` | P0 |
| 2 | 分组点击/聚焦即展示 + → 进入收起 + 缓存复用 | `PlayerDrawer.kt` / `SidebarController.kt` / `PlayerActivity.kt` | P0 |
| 3 | 「全部频道」RIGHT 进不去选台（与遥控全映射冲突） | `PlayerActivity.kt` / `RemoteBindings.kt` | P0（需产品裁定） |
| 4 | 列表跳开头 / t15 未过 CI 的 145/146 | `PerfPolicy.kt` / `PerfPolicyTest.kt` / `PlayerDrawer.kt` | P0 |
| 5 | 播放台标识 ▶ → 变色 | `DrawerUiPolicy.kt` / `PlayerDrawer.kt` | P1 |
| 6 | 信息卡 v4 重排 | `ChannelInfoBanner.kt` / `InfoCardPolicy.kt` | P1 |
| 7 | 数字 OSD 移到右上角 | `PlayerActivity.kt` / `DigitOsdPolicy.kt` | P1 |
| 8 | logo 资源替换映射 | `src/tivimini/res`（**不是 src/main/res**） | P1 |
| 9 | 全局焦点/确认态统一品牌蓝 `#2F6BFF` | 全 UI 文件 + 主题色 | P0（贯穿 1–7） |

**不可回退的既有性能/稳定性修复**：分帧揭示泵（`revealedRows/revealTarget`,`DRAWER_ROWS_PER_FRAME=2`）、三层 `graphicsLayer`（背景/内容/列表）、去 softWrap（`key("sub_$index")+clipToBounds+onTextLayout+TextSanitize`）、滚动跟随（`onRowFocusedScroll`）、t15 的 `pendingScrollIndex` 门控校正、S1 安全钳制、半透明 alpha 在缓存 layer、U1 单一焦点真源、遥控通用 fallback（t10）、B2 播放台/信息卡修复。

---

## 1. 打开抽屉「闪到开头又回到当前台」+ 打开即高亮

### 1.1 根因（两个独立缺陷叠加）

**缺陷 A — 首帧滚动钳制闪烁（t14 已复现，t15 已部分修）**
- t14 证据：`BUG_ZAP_LIST_RESET_REPRO.md §3.1` 冷启首开日志 `drawer_compose target=147 first=3`、`drawer_open … revealed=3`、`row_focus idx=126`，但 dump 可见行 `1..10`。根因：`PlayerDrawer.kt:551-566` 的 `hasInitializedDrawerFocus` 一次性块把 `pendingScrollIndex = targetScroll`（`:563`）登记；t15 的校正 effect `LaunchedEffect(visible, revealedRows, pendingScrollIndex)`（`:400` 附近）等 `drawerScrollCorrected(revealedRows, target)` 为真才 `scrollToItem`。
- **仍存在的闪烁路径**：`initialScrollIndex`（`:341-344`）= `drawerInitialIndex(openFocusUrl, selectedChannelUrl, channelIndexByUrl)` 作为 `rememberLazyListState(initialFirstVisibleItemIndex=…)`（`:346`）的初值。冷启 `openFocusUrl=null` → 取播放台深行 idx126 → LazyColumn 首帧只有 `revealedRows=3` 行，`initialFirstVisibleItemIndex=124` 被 LazyColumn **钳到 0**（只有 3 项时最大索引 2）→ 用户看到「先闪到开头」。随后分帧补行 + t15 校正 effect 在 `revealed>=125` 时 `scrollToItem(124)` → 「又回到当前台」。t15 只修了「补行后不再校正」，**没修首帧 initialFirstVisibleItemIndex 被钳制造成的闪烁**。
- 另一个首帧 scroll：`LaunchedEffect(visible, channels.size, initialScrollIndex)`（`:357-373` 开窗）不改 scroll；但 `:432-440` 的确认 effect 仍有一次 `scrollToItem((idx-2))`（需复核当前是否还在，§10 命令）——它也可能在 revealed=3 时被钳。

**缺陷 B — 焦点高亮打开时不出现，按键后才出现**
- `LaunchedEffect(visible)`（`:537-592`）在首次初始化后进入 `while (attempts < 90)` 循环请求 `selectedChannelRequester.requestFocus()`（`:579`）。但 `selectedChannelRequester` 只挂在 `focusTargetUrl == ch.url` 的那一行（`PlayerDrawer.kt:1064` 附近的 `focusRequester = if (ch.url == focusTargetUrl) selectedChannelRequester else null`）。
- 分帧组合下深行 idx126 在首帧根本不存在（`revealedRows=3`），`requestFocus()` 失败 → 循环 `withFrameNanos{}` 等到该行被揭示（约 60 帧）才成功。所以用户看到「打开时没有任何高亮，按 UP/DOWN 后（显式改 `focusedChannelUrl` + pendingFocusToChannels）才出现」。
- 另外 `focusedIndex/focusTargetIndex` 的 memo 虽算出 126，但 `DrawerChannelItem` 的 `focused` 是**本地 `onFocusChanged` 状态**（`:1336` 附近 `var focused by remember`），没有真正的 Compose 焦点就不会置 true → 高亮依赖真实焦点，而非 `focusedChannelUrl` 状态。

### 1.2 修复方案（单变量、分工明确）

1. **消除首帧钳制闪烁（核心）**：把 `channelListState` 的初值改为 **0**，让恢复位置完全交给 t15 的 `pendingScrollIndex` 门控 effect：
   ```kotlin
   val channelListState = rememberLazyListState(initialFirstVisibleItemIndex = 0)
   ```
   并在首次初始化块（`:558-564`）**无条件登记** `pendingScrollIndex = drawerScrollTargetIndex(targetIndex, channels.size)`（不要只在 `targetIndex !in visibleIndices` 时）。这样首帧列表在 0 是「确定的起点」，等揭示覆盖目标后一次跳到位——没有「先按 124 被钳到 0、再跳回 124」的中间态，dump 上首帧就是 1..10 直到校正完成（但这属于揭示过程的正常推进，不是「闪回」）。
   > 更优（可选，需真机 A/B）：让 `openFocusUrl` 非空且目标很深时，首帧 `revealedRows` 直接给到覆盖 target 的行数（`PerfPolicy.drawerFirstRowsFor(target)`），让 `initialFirstVisibleItemIndex` 一开始就不被钳。但会增加首帧组合成本（t20/t29 的巨帧风险），**建议优先用「初值 0 + pending 门控」**。
2. **打开即高亮（不依赖真实焦点）**：给 `DrawerChannelItem` 的 `bg` 与文字色增加「逻辑焦点」来源——新增参数 `logicalFocused: Boolean`（由 `ch.url == focusTargetUrl` 在调用点传入），`val showFocused = focused || logicalFocused`，用它决定 `surfaceVariant` 底色与加粗文字。这样行一被组合出来（即使 `requestFocus` 还没成功）就有蓝色焦点底（§9）。`onFocusChanged` 的回调与 `onRowFocusedScroll` 保持不变，真实焦点到位后平滑接管。
3. 保留 `requestFocus()` 循环（使遥控器方向键有真实焦点锚点），但不再作为「高亮出现」的前置条件。
4. 复核并移除/门控 `:432-440` 的额外 `scrollToItem`，避免与 t15 pending 校正双滚动打架；统一为「所有定位都走 pendingScrollIndex」。

### 1.3 可测用例

- `PerfPolicyTest`：新增 `drawerInitialIndex` 冷启退化用例（openFocusUrl=null → 播放台 index）；`drawerScrollCorrected` 边界（已由 t15 用例覆盖）。
- 静态门禁 `check_perf_policy.py`：禁止 `rememberLazyListState(initialFirstVisibleItemIndex = initialScrollIndex)`（首帧钳制根因）；要求调用点传 `logicalFocused`；要求所有 `scrollToItem` 定位集中在 pending 校正 effect（可数出现次数）。
- 真机（用户路径）：冷启首开 → dump 连续采样首 5 帧可见行，不应出现「先 1..10 → 突然 120..130」的两次跳变；焦点底色在抽屉打开的**第一帧**（目标行被揭示后）就出现，无需按键。

---

## 2. 分组：聚焦即展示 / → 进入并收起 / 缓存复用 / 点组从头

### 2.1 根因（当前交互与用户要求差三处）

- `DrawerGroupItem.onFocused`（`PlayerDrawer.kt:1028-1031`）只做 `activeColumn = Groups; showDates = false`，**不切分组**；`onSelectGroup(g)` 只在 `onClick`（`:1032`，OK/点击）触发 → 用户「上下移动焦点右侧不变、点 OK 才变」。
- `LaunchedEffect(selectedGroup)`（`:594-606`）有 `if (!showGroups) return@LaunchedEffect`（`:596`）守卫：分组列收起后（`showGroups=false`）切组不会做任何滚动/focus 更新；而 t15 已把切组的 `scrollToItem(0)` 改成 `pendingScrollIndex = drawerScrollTargetIndex(idx, …)`，其中 `idx = remembered?.let { channelIndexByUrl[it] } ?: 0`（`:603`）——「进入分组后从第一台开始」需要 `remembered=null`（该组无记忆）时 idx=0，当前逻辑恰好满足，但要保证 `showGroups=false` 后也能执行（去掉 `:596` 的早退，或由交互流程在切组时保持 showGroups=true 到效果执行完）。
- `onPreviewKeyEvent` 的 RIGHT（`:950-952`，activeColumn==Groups）只设 `pendingFocusToChannels=true`，**不收起分组列** → 分组列一直占 150dp，频道列不满宽，用户感觉「只是翻页/进不去」。
- `filteredChannels`（`PlayerActivity.kt:1280-1288`）已用 `remember(channelGroups, selectedGroup)`，这是**缓存复用的正确基础**，不要改成每次重组重算；`onSelectGroup = { sidebarController.selectGroup(it) }`（`:1654`）是唯一写点。

### 2.2 修复方案（含用户追加的三点）

1. **聚焦即展示（带去重）**：`SidebarController.selectGroup(name)`（`:113`）改为**幂等**——只有 `name != _state.value.selectedGroup` 才 `_state.update`（返回 Changed 信息或保持 Unit 但内部去重）。`DrawerGroupItem.onFocusChanged`（`:1287-1290`）在 `it.isFocused` 时调用 `onSelectGroup(g)`（新增：与 `onFocused` 并列或合并）。因为 `selectGroup` 去重，`focused` 事件重复触发不会引发重算；`filteredChannels` 的 `remember(channelGroups, selectedGroup)` 在组名不变时直接复用同一 List 实例 → 不重解析。
   - 若担心焦点移动瞬间 `channels` 还是旧组导致闪烁：在 `PlayerDrawer` 里把 `onFocused` 的 `onSelectGroup(g)` 用 `if (g != selectedGroup)` 再包一层（双保险）。
2. **→ 进入频道列并收起分组**：`onPreviewKeyEvent` 的 `Key.DirectionRight && activeColumn == Groups`（`:950-952`）改为：
   ```kotlin
   showGroups = false                 // 收起分组列
   activeColumn = DrawerColumn.Channels
   pendingFocusToChannels = true
   // 确保右侧立即显示聚焦组的第一台（若尚未展示）
   val firstUrl = channels.firstOrNull()?.url
   if (firstUrl != null) { focusedChannelUrl = firstUrl; onFocusedChannelChanged(firstUrl) }
   true
   ```
   `targetDrawerWidth`（`:886-890`）因 `showGroups=false` 立即变为 300dp 频道列 + 节目列，`drawerOffsetX/graphicsLayer` 缓存结构不变（无动画，t20 无每帧重排）。
3. **切组从第一台开始**：用户明确「从分组进来选台应从头开始」。`LaunchedEffect(selectedGroup)`（`:594-606`）在 **由分组交互触发**的切组时，忽略 `lastFocusedByGroup` 记忆、用 `idx=0`（第一台）：可由 `SidebarController` 增加 `selectGroup(name, resetFocus: Boolean)`，分组列聚焦/点击调用时 `resetFocus=true`，`selectGroup` 同时 `focusChannel(null)` 清该组记忆；常规程序性切组（如 source 刷新）保持记忆行为。这样 `pendingScrollIndex = drawerScrollTargetIndex(0, size)=0`，配合 §1 的初值 0 无闪烁。
4. **缓存复用声明（防止退化）**：
   - `filteredChannels` 保持 `remember(channelGroups, selectedGroup)`，不引入 `derivedStateOf` 每次重组重算；`selectGroup` 真变化才改 state。
   - EPG：`epgChannelUiCache`（`PlayerDrawer.kt:187-193`，LinkedHashMap 32 条）与 `nowProgramByUrl`（Activity 全组映射，`1294+`）已按 URL 缓存，切组不重复解析；不要在这些 key 里加 `showGroups`。
   - logo：`LogoLoader.getInMemory(url,w,h)` 内存 LRU 命中不走解码；切组后新行复用同一 64×40 内存位图（DrawerChannelItem 的 ChannelLogo 已用 LogoLoader）。
   - 组合：分帧揭示的 `revealedRows` 切组后从 `drawerFirstRows(n)` 起步，已组合行由 `key("${index}:${ch.url}")` 复用；切回原组若 List 实例相同，Compose key 一致可复用组合。

### 2.3 可测用例

- `SidebarControllerTest`：`selectGroup("A")` 两次 state 引用不变（去重）；`selectGroup("B", resetFocus=true)` 清 B 组记忆且 `focusedChannelUrl=null`。
- 静态门禁：分组行 `onFocusChanged` 必须调用 `onSelectGroup`；RIGHT(Groups) 分支必须含 `showGroups = false`；`filteredChannels` 的 `remember(channelGroups, selectedGroup)` 不得删除。
- 真机（用户路径）：冷启→LEFT 开抽屉→分组列 UP/DOWN：右侧频道列随焦点即时变化（不按 OK）；按 → 分组列消失、频道列满宽、焦点在第一台；再切回组 B 按 → 从 B 第一台开始；重复切换 10 次无卡顿（logcat 无重复 `M3uParser`/`XmlTvParser` 日志、logo 内存命中）。

---

## 3. 「全部频道」按 → 进不去选台（RIGHT 语义冲突）

### 3.1 根因（遥控全映射引入的默认值变化）

- FULLFIX §7 之前：`dispatchKeyEvent` 的 `KEYCODE_DPAD_RIGHT`（旧 `PlayerActivity.kt:365-375`）直接 `setSettingsDrawerOpen?.invoke(true)` → 打开设置。
- FULLFIX `f762bf4` 之后（当前 `PlayerActivity.kt:426-438`）：RIGHT 走 `resolvePlayerAction(event)`，`RemoteBindings.DEFAULT`（`RemoteBindings.kt:81-86`）把 `KEY_DPAD_RIGHT` 绑定到 `RemoteAction.OPEN_SETTINGS`（`:85`），分支 `RemoteAction.OPEN_SETTINGS -> setSettingsDrawerOpen(true)`（`:434`）；而 `OPEN_DRAWER` 默认只在 `KEY_DPAD_LEFT`（`:84`）。结果：**用户按 → 期望进频道列表，实际总开设置**（在「全部频道」上下文尤甚，因为该上下文没有特殊分支）。LEFT 才进频道列表。
- 这不是回归 bug，而是 v1 全映射默认与用户心智模型的冲突：用户把「→」理解为「进入右侧/下一级（频道列表）」。

### 3.2 兼容方案（三选一，需产品裁定；推荐 A）

- **方案 A（推荐，最小且不动全局默认）**：引入「全部频道上下文」特例——在 `dispatchKeyEvent` 的 RIGHT 分支，当 `selectedGroup == "全部"`（或更通用：当播放器处于 PLAYER_ONLY 且用户最近一次入口是频道列表/最近观看）且 RIGHT 的默认绑定仍是 `OPEN_SETTINGS` 时，**优先执行 `sidebarController.openDrawer()`**。实现为纯函数 `RightKeyPolicy.resolve(openDrawerDefault, settingsDefault, selectedGroup, customBound)`：仅当 RIGHT 是**默认绑定**（用户未自定义）且 selectedGroup=全部时改为 OPEN_DRAWER；用户显式重绑过 RIGHT 时尊重用户绑定。这样默认用户一键进列表、高级用户全映射不被破坏。
  - 兼容 LEFT：保持 LEFT=OPEN_DRAWER 不变（双入口），避免老用户困惑；信息卡入口提示 `← 频道列表 · → 设置` 需按裁定更新文案（见 §6）。
- **方案 B（用户确认改默认）**：把 `RemoteBindings.DEFAULT` 的 RIGHT 改为 `OPEN_DRAWER`、LEFT 改为 `OPEN_SETTINGS`（或 LEFT 保持 DRAWER、RIGHT 也 DRAWER 并保留 MENU=SETTINGS）。这是产品决策，需同步 `RemoteHabitsTest`/`RemoteBindingsTest.defaultResolveMatchesShippedBehaviour` 的断言与升级迁移（老用户已存 `remote_action_bindings_v1` 时**不静默改**，与 t193 cap·R1 同理）。
- **方案 C（仅提示）**：不改行为，只在设置页/信息卡加明显入口提示「← 频道列表 · → 设置」，并让用户确认 RIGHT 语义。**不推荐**（用户已明确表达不满）。

> **需要 captain/用户裁定**：用户原话「从『全部频道』按 → 进入选台：只在翻页，进不去」更像是期望 RIGHT 进列表。若确认，走 A（默认特例）或 B（永久换默认）。本章给出 A 的完整实现点，B 只需改 DEFAULT 常量与测试。

### 3.3 可测用例 / 真机

- `RightKeyPolicyTest`：默认绑定 + 全部组 → OPEN_DRAWER；默认 + 非全部组 → OPEN_SETTINGS；用户自定义 RIGHT → 始终尊重自定义；CATCHUP 上下文 → SEEK_FORWARD 不变。
- 真机（用户路径）：默认安装、播放任意台、selectedGroup=全部 → 按 RIGHT：频道抽屉打开（不是设置）；设置里把 RIGHT 重绑为 OPEN_SETTINGS → 行为随绑定；回看中 RIGHT 仍快进。

---

## 4. 列表跳开头 / t15 未过 CI 的 145/146（必须修）

### 4.1 根因（单测与实现不一致，明确可修）

- `PerfPolicy.drawerScrollTargetIndex(focusIndex, channelCount)`（`PerfPolicy.kt:115-116`）= `drawerSafeFirstVisibleIndex((focusIndex-2).coerceAtLeast(0), channelCount)`；`drawerSafeFirstVisibleIndex(idx,n)` 把越界钳到 `n-1`。所以 `drawerScrollTargetIndex(999,147)` = clamp(997) = **146**。
- 但测试 `PerfPolicyTest.kt:257`（`t15ScrollTargetUsesSafeFirstVisibleClamp`）断言 `assertEquals(145, …(999,147))`。**测试期望 145，实现返回 146** → CI #216（run 45032187632, head `f3a3332`）`testDebugUnitTest` 失败，t15 滚动校正修复**从未出包**。
- 为什么期望可能是 145：`scrollToItem(index)` 是「首可见项索引」，147 行时最大首可见索引应为 `147 - 一屏行数(12) = 135` 才不会「滚到底只剩空白」；145/146 都接近末尾，属于测试作者随手写的边界值。**关键点**：不管取 145 还是 146，只要单测期望与实现一致、CI 绿即可；语义上「越界焦点 → 滚到列表末尾附近」两者都可接受。
- 因此两种修法任选其一：①改实现为 `(channelCount-2).coerceAtLeast(0)`（999→145，与测试一致，且末尾留 2 行上下文）；②改测试期望为 146。**推荐 ①**：既与测试对齐，又让「末尾目标」与「焦点留 2 行前置」的注释语义一致（`:113`）。

### 4.2 修复方案

```kotlin
fun drawerScrollTargetIndex(focusIndex: Int, channelCount: Int): Int =
    if (channelCount <= 0) 0
    else (focusIndex - 2).coerceIn(0, channelCount - 2)
```
- 147 行：focus=999 → 145（测试绿）；focus=126 → 124（t15 另一用例不变）；focus=1 → 0；focus=0 → 0；空组 → 0。
- `drawerScrollCorrected(revealedRows,target)` 不需改：`revealed >= target+1`（146 覆盖 145）。
- 保留 S1 `drawerSafeFirstVisibleIndex` 原样（其他调用点如 UP/DOWN 仍用），只在 scroll target 这层用更保守的上限。
- 静态门禁 `check_perf_policy.py` 增加：`drawerScrollTargetIndex` 使用 `coerceIn(0, channelCount - 2)`（或任何能过 `999→145` 的形式），并保留 t15 的 pending 门控断言。

### 4.3 可测用例 / 真机

- JVM：`t15ScrollTargetUsesSafeFirstVisibleClamp` 全绿（重点 145），另补 `channelCount=1` → 0、`channelCount=2, focus=999` → 0。
- CI：新 run 必须 success（旧 #216 failure 作对照）；确认 `testDebugUnitTest` 通过。
- 真机（用户路径）：冷启首开深行 idx126、换台后重开、点台换台后列表保持焦点附近（t14 §2 的 P8/P8b/R3 全路径），无 first=1 与焦点脱节。

---

## 5. 播放台标识从「▶ … 播放中」改为变色

### 5.1 根因与现状

`DrawerUiPolicy.channelLabel(selected, index, title)`（`DrawerUiPolicy.kt:18-19`）= `(if (selected) "$PLAYING_MARK " else "") + "$index. $title"`，`PLAYING_MARK="▶"`（`:12`）；当前 `PlayerDrawer.kt:1319` 注释与调用点用该文本标识播放台。用户要求改为**变色**（与全局焦点蓝 §9 统一）。

### 5.2 修复方案

- `DrawerUiPolicy`：删除 `PLAYING_MARK` 与 `channelLabel` 的 `▶` 拼接，改为返回纯 `"$index. $title"`；新增 `playingLabelColor(selected: Boolean): ColorToken`（纯逻辑可用枚举 `PLAYING / NORMAL`，由 UI 层映射到品牌蓝 `#2F6BFF`）。或更简单：`channelLabel` 保留纯文本，播放器行颜色在 `DrawerChannelItem` 用 `selected` 决定。
- `DrawerChannelItem`（`:1309+`）：`val isPlaying = selected`；台名 `Text.color` 用 `if (isPlaying) BrandFocus else contentColor`，`fontWeight = if (isPlaying || focused) Bold else Medium`（需 `Text` 支持 `fontWeight`）。播放台**不额外挂 ▶ 文本**；焦点行与播放台同为蓝色系时，用「蓝底（焦点）+ 蓝字（播放）」区分（见 §9）。
- 与 §1 的 `logicalFocused` 合流：`showFocused = focused || logicalFocused` 决定底色；`selected` 决定文字变色；两者正交。

### 5.3 可测用例 / 真机

- `DrawerUiPolicyTest`：`channelLabel(true, 2, "CCTV")` == `"2. CCTV"`（无 ▶）；新增 `playingColor(true)==BRAND_BLUE`。
- 静态门禁：`PlayerDrawer.kt` 不得含 `PLAYING_MARK`/`▶`/`播放中`。
- 真机：播放台台名为品牌蓝；焦点行蓝底；A 播放、B 聚焦时两者可辨；dump 文本不含 `▶`。

---

## 6. 信息卡 v4 重排（按 INFO_CARD_V4_CONFIRM §1）

### 6.1 现状 vs 确认图

| 区域 | 确认图（v4） | 当前 v3.1（`ChannelInfoBanner.kt`） |
|---|---|---|
| 右上徽标 | 三项紧凑：`HD · 帧数 · 5.1` | `InfoCardPolicy.badgeCandidates` 四项（分辨率/帧率/码率/声道），`joinToString("  ·  ")`，行高 24dp |
| 台标下 | `序号. 台名`（台名在序号同列）+ `本台分组小字` | 台名 34sp 单独行 + `smallMetaLine(number,group)` 小字（`频道 N · 分组`） |
| 右侧节目 | 节目名 + 起止/剩余 + 3dp 进度 | 已有（`info_card_program/_time/_progress`） |
| 底部 | 最近观看 **4 个 logo**，无 logo 显示文字，LEFT/RIGHT/OK | `MAX_RECENT=6`，`104×64`，40dp logo + 12sp 文字双行 |
| 高度 | ≤240dp | 当前 ~160–240dp（t7 dump 240px≈160dp），有余量 |

### 6.2 改造点（精确）

1. **徽标三项**：`InfoCardPolicy.badgeCandidates`（`:161-166`）删掉 `bitrate` 候选项优先级（保留函数 `bitrateLabel` 供诊断，但不进默认候选）；`planBadges` 的 `maxWidthDp` 可降到 180（3 项只需约 130dp）。v4 顺序固定 `分辨率 > 帧率 > 声道`，未知即隐藏，不补 `—`。真机源无帧率时显示 `HD · 5.1` 也符合「未知隐藏」。
2. **台标下小字**：`smallMetaLine(number, group)`（`:265`）当前产出 `频道 N · 分组`；v4 要求「序号. 台名 + 本台分组小字」。台名展示改为 `Text("$number. ${title}")` 与分组小字同列（`info_card_title` 与 `info_card_group` 复用 tag），`smallMetaLine` 改为只返回 `group`（或新增 `groupLine(group)`），去掉「频道」前缀。
3. **底部 4 logo**：`InfoCardPolicy.MAX_RECENT=6` → **4**；`ChannelInfoBanner.RecentStrip`（`:409+`）卡片从 `104×64`（logo 40dp + 文字）改为**仅 logo 的方块**（建议 `56×56dp`，logo `48dp`，无文字行），卡片高从 64 → 56；无 logo（`item.logoUrl` 空白）时显示 `item.number` 或 `item.title.take(1)` 文字回退（`Text` 单行、居中、品牌蓝焦点时白色加粗）。`onMoveFocus/onSwitchRecent` 逻辑不动（U1 单一真源保持）。高度账：主行 96 + 徽标 24 + 技术行 16 + 最近 56 + gaps/padding ≈ 220dp < 240dp。
4. **焦点色**：底部 4 卡聚焦底色改 `#2F6BFF22`（§9）。
5. 保留：`info_card_*` 全部 tag、`InfoCardPolicy` 焦点/三态/遮罩纯逻辑、F1/F2 自愈、节目行三态、回看并入节目行。

### 6.3 可测用例 / 真机

- `InfoCardBadgeTest`：徽标候选最多 3 项且顺序 HD/fps/channel；`MAX_RECENT==4`；`groupLine("央视频道")=="央视频道"`；无 logo 回退文字非空。
- `check_info_card.py`：徽标 3 项、小卡高 56dp、无 `basicMarquee/LazyRow`、品牌蓝 token 出现。
- 真机：直播台信息卡按确认图布局；右上无码率项；底部恰好 4 个 logo（无 logo 有文字）；左右键+OK 切台（U1 回归）；高度 ≤240dp；无硬边框。

---

## 7. 数字 OSD 移到右上角

### 7.1 根因

`PlayerActivity.kt:239-259`：数字 OSD 的 Compose `Column` 用 `.align(Alignment.TopCenter).padding(top=36.dp)` —— **顶部居中**。`DigitOsdPolicy`（`TIMEOUT_MS=3000`、`append`、`timeoutAction`、`visibleWhen`）纯逻辑正确，无需改；移位只影响布局。

### 7.2 修复方案

- `:243` 改 `.align(Alignment.TopEnd)`；把 `padding(top=36.dp)` 改为 `padding(top=24.dp, end=40.dp)`（避开 TV 安全区/状态栏；40dp 与信息卡左右 padding 对齐）。`Column.horizontalAlignment = Alignment.End`。
- 背景 `.background(ComposeColor(0xE610141A))` 保留（轻量、非全屏）；如需与品牌统一，可用 `BrandFocus.copy(alpha=…)` 的深色变体，但**不得**用亮蓝大面积遮挡画面（用户要「轻量 OSD，不遮挡画面」）。
- 确认态「数字 OSD 确认态」（§9）：输入中数字用白色；OK/超时触发跳转瞬间（提交前的 1 帧或 Toast）数字变品牌蓝 `#2F6BFF` 加粗。实现：`digits` 旁加 `digitConfirmed: Boolean`，`submitNumber()`（`:94-104`）前置置 true 并在清空时复位；Text color 按此切换。3s 超时（DIRECT 自动提交、NEEDS_CONFIRM 清空）、遮罩守卫（`DigitOsdPolicy.visibleWhen`）、BACK 取消、数字 OSD 优先于遥控 fallback（`:556` `digits.isEmpty()` 守卫）全部不动。
- tag：给 OSD 容器加 `Modifier.testTag("digit_osd")` 便于 dump 验证右上角位置。

### 7.3 可测用例 / 真机

- `DigitOsdPolicyTest`：既有 3s/两种模式/追加/遮罩测试不回归。
- 静态门禁：`PlayerActivity.kt` 数字 OSD `Modifier` 必须含 `Alignment.TopEnd`，不得再是 `TopCenter`。
- 真机：按 `1`→`3`（显示 `13_`），dump `digit_osd` bounds 在右上角（x 接近 1080dp 宽右缘、y < 100dp）；停止输入约 3s 自动跳台；需OK 模式下 3s 只清 OSD；输入中打开抽屉 OSD 立即隐藏。

---

## 8. Logo 资源替换映射（**注意实际 sourceSet**）

### 8.1 关键事实（避免改错目录）

`android/app/build.gradle.kts:82-92` 把 main 的 `res` 指向 **`src/tivimini/res`**、`java/kotlin` 指向 `src/tivimini/java` + `src/bridge/kotlin`。`src/main/res` 下的 PNG/vector **不参与编译**（是历史遗留）。任务描述里的 "android/app/src/main/res" 应理解为「实际构建 res = src/tivimini/res」。`AndroidManifest.xml:17-20` 引用 `@mipmap/ic_launcher`、`@mipmap/ic_launcher_round`、`@drawable/tv_banner`。

### 8.2 映射表（源 `C:\Users\zqs1q\Documents\Projects\iqtv\iqtvlite-logo-assets`）

| 源文件 | 目标（**src/tivimini/res**） | 说明 |
|---|---|---|
| `icon/ic_launcher-48.png` | `mipmap-mdpi/ic_launcher.png` | mdpi |
| `icon/ic_launcher-72.png` | `mipmap-hdpi/ic_launcher.png` | hdpi |
| `icon/ic_launcher-96.png` | `mipmap-xhdpi/ic_launcher.png` | xhdpi |
| `icon/ic_launcher-144.png` | `mipmap-xxhdpi/ic_launcher.png` | xxhdpi |
| `icon/ic_launcher-192.png` | `mipmap-xxxhdpi/ic_launcher.png` | xxxhdpi |
| `icon/icon-512.png` | `ic_launcher-playstore.png`（src/tivimini 根，Play 商店 512） | 可选，CI 不校验 |
| `icon/ic_launcher_foreground-512.png` | `mipmap-xxxhdpi/ic_launcher_foreground.png` | 自适应前景（108dp 安全区由源保证） |
| `icon/ic_launcher_background-512.png` | 新文件 `drawable/ic_launcher_background_graphite.png`（或保留 vector） | 若用 PNG 背景需同步 `values/ic_launcher_background.xml` → `#FF151923`、`mipmap-anydpi-v26/ic_launcher.xml` 指向新 drawable；更简单：只改颜色值 `#FF151923` 并保留 vector 背景 |
| round icon | 源无独立 round PNG；用 `mipmap-anydpi-v26/ic_launcher_round.xml`（新建 adaptive round）或继续复用 ic_launcher（现有各密度 `ic_launcher_round.png` 暂保留） | 避免与应用圆形图标不一致；推荐新建 anydpi-v26 round 指向同一 foreground/background |
| `tv/tv-banner-320x180.png` | `drawable-nodpi/tv_banner.png`（覆盖现有） | manifest `@drawable/tv_banner` 对应；现有 `drawable/banner.xml`（vector）与 `drawable/banner_bitmap.png` 可保留但不再引用 |
| `logo/iqtvlite-logo-graphite.svg` | `docs/brand/svg/`（文档资产，不入 res）；如需在 app 内显示转 `drawable/iqtvlite_wordmark.xml`（vector） | 字标用于设置/关于页（可选） |
| `logo/logo-transparent.png` / `logo-white-transparent.png` / `logo-vertical-transparent.png` | `docs/brand/png/`（文档资产）；白色版可放 `drawable-nodpi/iqtvlite_wordmark_white.png` 供深底用 | 不替换频道 logo（那是用户源里的台标） |
| `overview-logo.png` / `overview-icon.png` | `docs/brand/png/`（可选） | 仅文档 |

- **不修改** `applicationId`（`build.gradle.kts:11`，validation 包由 `-PISOLATED_TEST_PACKAGE=true` 决定）；不动任何网络/播放逻辑。
- 颜色 token：`src/tivimini/res/values/brand_colors.xml` 与 `values/ic_launcher_background.xml` 的强调色从 `#40C4FF` 改 `#2F6BFF`（见 §9）；`values/colors.xml` 的 `tm_focus/tm_accent/tm_progress` 同样改。
- 验证：`aapt2`/`assembleDebug` 通过；CI 出包后真机launcher/TV banner 截图确认新 logo、无变形/模糊；包名与签名不变。

---

## 9. 全局焦点/确认态统一品牌蓝 `#2F6BFF`（用户追加，贯穿全局）

### 9.1 现状（现有焦点色定义）

所有焦点态目前都是 `MaterialTheme.colorScheme.surfaceVariant`（主题未显式定义，TV Material3 默认偏灰白 `#FFFFFF14` 类浅底），文字多为 `onSurface`（白）不加重：

| 文件 | 行号 | 现状 |
|---|---|---|
| `PlayerDrawer.kt` | `:1276-1278`（分组/日期等 bg）、`:1336`（频道行 focused → surfaceVariant）、`:1495/1496`、`:1538/1539` | `focused -> surfaceVariant`，否则 `surface.copy(alpha=0.01f)` |
| `SettingsDrawer.kt` | `:453`、`:972` | `if (focused) surfaceVariant else surface.copy(alpha=0.01f)` |
| `ChannelInfoBanner.kt` | `:440`（最近卡 bg）、`:458`（文字色） | `if (isFocused) surfaceVariant else Transparent` |
| `RecentChannelsDialog.kt` | `:182/183`、`:226/227`、`:212`、`:244` | 同上 |
| `QualitySelectorDialog.kt` | `:153/154` | 同上 |
| `MainActivity.kt` | `:354-355` | `focusedContainerColor=Color(0xFF303030)`（TV Material 组件） |
| 主题（PlayerActivity `:185-196`、MainActivity `:81-92`） | `primary=0xFFBDBDBD`、无 `surfaceVariant` 定义 | 系统默认灰蓝浅底 |

### 9.2 修复方案（集中定义 + 逐点替换，保持「无硬边框」）

1. **集中 token**：新建 `FocusTheme.kt`（`src/tivimini/java/com/cjlhll/iptv/`，纯 Compose 常量）：
   ```kotlin
   object BrandFocus {
       const val BLUE = 0xFF2F6BFF.toInt()
       const val BG = 0x222F6BFF.toInt()      // #2F6BFF22 浅底
       const val BAR = BLUE                    // 可选：3dp 蓝色高亮条
   }
   ```
   或在两个 `darkColorScheme`（`PlayerActivity.kt:185`、`MainActivity.kt:81`）显式设 `surfaceVariant = Color(BrandFocus.BG)`、`primary = Color(BrandFocus.BLUE)`、`onSurfaceVariant = Color.White`——**推荐两者都做**（token 单点定义 + 主题接入），使所有 `MaterialTheme.colorScheme.surfaceVariant` 自动变蓝底，减少逐点改动。注意：把 `surfaceVariant` 改蓝会影响所有「非焦点但用 surfaceVariant 的小字」颜色（如 `ChannelInfoBanner:225/266` 的 `onSurfaceVariant` 文字色）——`onSurfaceVariant` 设为白色可保持可读。
2. **逐点加粗/变色（重点行）**：
   - `PlayerDrawer.DrawerChannelItem`（`:1336`）：focus bg 用 `Color(BrandFocus.BG)`；台名/节目文字 `fontWeight = if (focused||logicalFocused) FontWeight.Bold else Medium`，色 `Color.White`；播放台文字用 `Color(BrandFocus.BLUE)`（§5）。可选：行首 3dp 蓝色高亮条（`Box(Modifier.width(3.dp).fillMaxHeight().background(BrandFocus.BAR))`），**不加四周边框**。
   - `SettingsDrawer`（`:453/972`）、`RecentChannelsDialog`（`:182/226`）、`QualitySelectorDialog`（`:153`）、`ChannelInfoBanner.RecentStrip`（`:440/458`）：focus bg 统一 `Color(BrandFocus.BG)`，聚焦文字 `FontWeight.Bold + Color.White`。
   - 数字 OSD 确认态（§7）：数字变蓝加粗。
   - `MainActivity.kt:354-355` 的 `focusedContainerColor` 改 `Color(BrandFocus.BG)`。
3. **保持「无蓝色大方框」**：只改背景色/文字色/可选 3dp 侧条；现有 `RoundedCornerShape(0.dp)`、`focus_border.xml` 等描边资源不得被启用；`check_perf_policy.py`/`check_info_card.py` 的「0dp/no border/shadow」断言继续通过。
4. `values/colors.xml`（tivimini res）：`tm_focus/tm_accent/tm_progress` → `#FF2F6BFF`、`tm_focus_bg` → `#222F6BFF`、`tm_panel` 保持 `#E81E1E1E`；`ic_launcher_background` → `#FF151923`（graphite）。

### 9.3 可测用例 / 真机

- 静态门禁（新增 `check_brand_focus.py` 或并入现有）：所有 UI 文件不得再出现 `focused -> MaterialTheme.colorScheme.surfaceVariant`（应走 BrandFocus/主题）；`BrandFocus.BLUE` 常量存在；`0.dp`/无 `border` 断言仍成立。
- 真机：选台焦点行、最近观看 4 logo 聚焦、设置项焦点、数字 OSD 确认均呈 `#2F6BFF22` 浅底 + 白色加粗文字（或蓝条）；**无蓝色大方框**；播放台蓝色台名与焦点蓝底可区分；截图逐项留证。

---

## 10. 实现前锚点复核命令（PowerShell）

```powershell
$s='iqtvlite/android/app/src/tivimini/java/com/cjlhll/iptv/'
Select-String -Path "${s}PlayerDrawer.kt" -Pattern 'initialScrollIndex|hasInitializedDrawerFocus|pendingScrollIndex|onSelectGroup|showGroups = false|logicalFocused|focused -> MaterialTheme|DirectionRight && activeColumn == DrawerColumn.Groups'
Select-String -Path "${s}PlayerActivity.kt" -Pattern 'KEYCODE_DPAD_RIGHT|RIGHT_KEY_POLICY|Alignment.TopCenter|digit_osd|filteredChannels|surfaceVariant'
Select-String -Path "${s}SidebarController.kt" -Pattern 'fun selectGroup|lastFocusedByGroup|fun focusChannel'
Select-String -Path "${s}PerfPolicy.kt","iqtvlite/android/app/src/test/java/com/cjlhll/iptv/PerfPolicyTest.kt" -Pattern 'drawerScrollTargetIndex|145|146|drawerScrollCorrected'
Select-String -Path "${s}DrawerUiPolicy.kt" -Pattern 'PLAYING_MARK|channelLabel'
git -C iqtvlite log --oneline -6
```

---

## 11. 排期与依赖建议

1. **P0 先做 §4（t15 145/146）**：CI 必须绿才能出包；这是所有后续真机验证的前提。
2. **§1 + §2 + §3 同属选台交互**，由同一实现者顺序完成（`PlayerDrawer.kt`/`PlayerActivity.kt` 同文件）；§3 需 captain/用户先裁定 A/B 方案。
3. **§9 焦点蓝**贯穿全部 UI 文件，建议在 §1/2/5/6/7 的改色阶段统一接入（先定义 token + 主题，再逐点替换），避免多处返工。
4. **§5/6/7/8 相对独立**（DrawerUiPolicy/InfoCardPolicy/数字 OSD 布局/res 资源），可并行于不同文件；§8 注意实际 sourceSet 是 `src/tivimini/res`。
5. 真机验证必须按 `INFO_CARD_V4_CONFIRM.md §6` 的**用户实际操作路径**逐条：冷启首开、从当前频道打开、点分组、全部频道按 →、点台换台、数字右上角、播放台变色、最近观看 4 logo 左右+OK；每条留 dump/日志/截图，不通过如实判失败。
