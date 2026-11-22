# FULLFIX_SPEC — 全量修复规格（代码定位 / 根因 / 单变量方案 / 用例 / 真机验收）

> 版本 1.0 · 2026-11-22 · researcher（任务 t1，只读调研，**不改代码、不跑设备**）
> 代码基线：`iqtvlite` 分支 `integration/tivimini-baseline`，HEAD `ef8f826`（S1 已提交；工作树仅 `?? docs/REVIEW_HANDOFF.md`）。
> 历史基线：`c82593f`（原始 tivimini Compose 抽屉/播放器）、`fcc7603`（另一处早期快照）、`8c93e1b`（上游源码副本，在 `docs/evidence/tivimini-upstream-8c93e1b/`）。
> 输入：`docs/REVIEW_HANDOFF.md`、`docs/TIVIMATE_REFERENCE_2026_11_22.md`、`docs/INFO_CARD_V3.md` / `_IMPL.md`、`docs/SETTINGS_IA_V2.md`、`docs/REMOTE_HABITS_SETTINGS*.md`、`docs/INFO_CARD_FOCUS_FIX.md`、真机 dump `docs/evidence/2026-11-22-t53-final-verify/08-infocard-v3-dump.xml`。
>
> **本文件分两批**：**第一批（§1–§7）** = 用户 5 项 UI + 重叠的 U1/U5，t3/t4/t5 可直接开工；**第二批（§8–§13）** = S2–S6、U2–U4 与第三批性能项。
>
> **行号口径**：均指上述基线，实现前按 §14 的锚点命令复核。设备为 TR100-G9 / API28 / 1080p / density 240 → 逻辑屏 **1280×720dp**，1dp = 1.5px（dump 坐标 ÷1.5 = dp）。

---

## 0. 总览：问题 → 任务映射与重叠标注

| 用户/交接条目 | 本文 § | 主文件 | 与用户 5 项 UI 重叠 |
|---|---|---|---|
| ① 选台 UI 回退（被精简过度） | §1 | `PlayerDrawer.kt` | 用户问题 1 |
| ② 选台每次回开头 | §2 | `PlayerDrawer.kt` + `SidebarController.kt` | 用户问题 2 |
| ③ 选台背景全色 vs 半透明 | §3 | `PerfPolicy.kt` + `PlayerDrawer.kt` | 用户问题 3 |
| ④ 信息卡排版 | §4 | `ChannelInfoBanner.kt` + `InfoCardPolicy.kt` | 用户问题 4 |
| ⑤ 遥控「功能→按键」全映射 | §7 | `RemoteHabits.kt` / `SettingsIA.kt` / `SettingsDrawer.kt` / `PlayerActivity.kt` | 用户问题 5（设置相关） |
| U1 最近观看焦点 | §5 | `ChannelInfoBanner.kt` + `PlayerActivity.kt` | **= 用户问题 5 的「最近观看不能切换」** |
| U5 抽屉与节目单状态区分 | §6 | `PlayerDrawer.kt` + `PlayerActivity.kt` | **与问题 1（选台 UI 内容）重叠** |
| S1 小分组抽屉崩溃 | 已修（`ef8f826`） | `PerfPolicy.kt` / `PlayerDrawer.kt` | 不重叠；**修复必须保留** |
| S2–S6 / U2–U4 / 第三批 | §8–§13 | 见各节 | U4 与问题 2/5 的焦点语义相邻 |

**全局不变量（任何修复都不得回退）**：
1. S1 安全钳制：`PerfPolicy.drawerSafeOpenTarget / drawerFirstRows / drawerSafeFirstVisibleIndex / drawerFocusIndexInRange`（`PerfPolicy.kt:57-85`）。
2. 性能修复：分帧揭示（`DRAWER_INCREMENTAL_ROWS`、`revealTarget` 泵、`LaunchedEffect` 在 `PlayerDrawer.kt:342-398, 360-382`）；三层 `graphicsLayer`（静态背景 `928-935` / 内容 `937-945` / 列表 `1000-1003`）；去 softWrap（`1310-1346`，`onTextLayout` 窄探测 + `TextSanitize` + `key("sub_$index")` + `clipToBounds`）；滚动跟随（`onRowFocusedScroll` `408-428`）。
3. F1/F2 焦点自愈与 `FOCUSED→HIDDEN` 不清焦点（`InfoCardPolicy.kt:37-52`、`ChannelInfoBanner.kt:104-139`）。
4. 0dp / 无 border / 无 shadow / `surfaceVariant` 表达焦点；零拷贝 SurfaceView 底层不被遮挡。

---

# 第一批（可直接开工）

## 1. 问题① 选台 UI 回退到「一开始的设计」

### 1.1 现状与基线差异（根因）

| 维度 | 原始 tivimini 基线 `c82593f`/`fcc7603` | 当前 HEAD `ef8f826` | 谁改的 |
|---|---|---|---|
| 行高 | 无固定高：`.padding(vertical=3)` + `.padding(horizontal=12, vertical=10)`（`c82593f` DrawerChannelItem）→ 自然约 **64dp/行** | `PerfPolicy.DRAWER_ROW_HEIGHT_DP = 56` 固定 56dp（`PerfPolicy.kt:92`；用在 `PlayerDrawer.kt:1280`） | `cd09f10`（t33 r5）固定行高 |
| 行内容 | **所有行**：logo 64×40 + 序号台名 `titleMedium` + 节目副标题 `bodySmall` + 3dp 进度，无节目时显示「无信息」 | **非聚焦行只有「logo + 序号 + 台名」约 2 节点**；仅聚焦行才组合副标题 + 2dp 进度（`PlayerDrawer.kt:1306-1377`，受 `PerfPolicy.DRAWER_FOCUSED_SUBTITLE=true` / `1310` 的 `focused` 条件门控） | `cab53dc`（t42 r8）精简 → `ee0360a`（t44）只给聚焦行补回 |
| 进度 | `ThinProgressBar`（3dp，`c82593f` 尾部 helper） | 内联 2dp Box（`1348-1365`）；`ThinProgressBar` helper 仍留在 `1381-1402` 但**已无调用点** | `cab53dc`/`ee0360a` |
| 副标题动效 | `FocusMarqueeText`（所有行，`MARQUEE_ITERATIONS` 后受控） | `FocusMarqueeText` helper 仍在（`1411-1433`），但频道行不再调用（只被节目行 `1525` 用） | `cab53dc` |
| 面板列宽 | 分组 150 + 频道 300 + 节目 300+32 + 日期 110（`c82593f` 同） | **完全相同**（`PlayerDrawer.kt:837-843`、`961/997/1064/1077/1158`） | 未变 |
| 台标尺寸 | 64×40（`c82593f`） | **64×40 未变**（`1301-1303`） | 未变 |
| 圆角 | `RoundedCornerShape(12.dp)` 行底 | 0dp（`ad2c6d5`/`fa7f87e` 扁平化，属有意设计） | 不回退 |

**结论**：用户说的「被精简过度、变瘦」= **行内容密度**（两行+进度 → 只剩一行）和 **56dp 行高**，**不是**列宽/台标被改小（列宽和台标其实与基线一致）。因此修复聚焦在 `DrawerChannelItem` 与行高常量，**不动列宽**，避免破坏性能基线。

### 1.2 修复方案（单变量、可回退）

文件 `android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt`、`PerfPolicy.kt`：

1. **行高常量**：`PerfPolicy.DRAWER_ROW_HEIGHT_DP`（`PerfPolicy.kt:92`）`56 → 64`。保留「固定行高」这一性能属性（不回退 t33），只改数值；64dp 正好容纳台名 20 + 副标题 16 + 进度 3 + 内边距。
2. **所有行恢复富内容**：`DrawerChannelItem`（`1247-1379`）把 `1310` 的副标题条件从
   `if (PerfPolicy.DRAWER_FOCUSED_SUBTITLE && focused) TextSanitize.programTitle(nowProgram?.title) else null`
   改为**所有行**计算：`val programTitle = if (PerfPolicy.DRAWER_FOCUSED_SUBTITLE) TextSanitize.programTitle(nowProgram?.title) else null`（focused 只影响颜色/进度强调，不影响是否组合）。无节目时走 `else` 分支渲染 `Text("无信息", bodySmall, α0.45)`（恢复基线）或保留当前「仅台名」分支——二选一，建议恢复「无信息」以贴近原设计。
3. **进度恢复 3dp**：把 `1348-1365` 的内联 2dp Box 替换为对现存 `ThinProgressBar`（`1381-1402`）的调用，`modifier = Modifier.padding(top = 4.dp).fillMaxWidth()`；focused 时 `alpha` 更高由 helper 内部既有逻辑处理。
4. **必须保留**：`key("sub_$index")`（`1314`）、`Box(height(16).fillMaxWidth().clipToBounds())`（`1324`）、`fillMaxWidth + maxLines=1 + Ellipsis`、`onTextLayout` 窄探测（`1336-1342`）、`TextSanitize`（`1309`）、行外层 `clipToBounds()`（`1282`）、`DRAWER_ROWS_PER_FRAME` 分帧泵、三层 `graphicsLayer`、滚动跟随。
5. **可选小步**：`FocusMarqueeText` 仅用于聚焦行的节目行，不给频道行挂跑马灯（`MARQUEE_ITERATIONS=0` 时无动画，成本为零）；若真机发现长副标题仍被 ellipsis 截断，再单变量打开，不默认开。

> 回退：改回 `DRAWER_ROW_HEIGHT_DP=56` 并把 `1310` 恢复 `&& focused` 即可，其他性能开关不动。

### 1.3 可测行为用例（JVM/静态）

- `DrawerRichnessPolicy`（新建纯函数，或直接在 `PerfPolicy` 加常量）：断言 `DRAWER_ROW_HEIGHT_DP == 64`、富行判定 `rowSubtitleVisible(focused=false) == true`（当 `DRAWER_FOCUSED_SUBTITLE`）、`rowSubtitleVisible(focused=true) == true`。
- 静态门禁 `check_perf_policy.py` 增加：`DrawerChannelItem` 内 `TextSanitize.programTitle(` 出现且**不在** `&& focused` 条件内；`ThinProgressBar(` 在频道行被调用；`clipToBounds()` / `key("sub_` / `onTextLayout` / `DRAWER_ROWS_PER_FRAME` / 3 处 `graphicsLayer` / `drawerSafeOpenTarget` 全部仍在（防性能回归）。
- 既有 `PerfPolicyTest` 的 S1 用例、`DrawerKeysTest`、`TextSanitizeTest` 全绿。

### 1.4 真机验收（TR100-G9）

1. 打开选台抽屉，截图/dump：每个频道行都能看到「台标 + `N. 台名` + 节目副标题 + 3dp 进度」，无节目行显示「无信息」；行高约 64dp（dump bounds 高 ≈96px）。
2. 连续 DOWN×15 + 快速连按：无残影单字、无跳到节目列、可见行持续跟随（`row_focus idx=…` 日志）。
3. `gfxinfo framestats` 两轮：无 ≥300ms 巨帧，p50 ≤38ms、Draw ≤2.5ms（对齐 CI10204 口径 `docs/FINAL_PACKAGE.md`）；若劣化 >20%，先回退行高到 56 再评估。
4. 0/1/2 行小分组仍可打开、落焦、Back 退出（S1 不回归）。

---

## 2. 问题② 选台每次点击回到开头（按上次位置恢复）

### 2.1 根因（为什么每次都回 0）

关键事实：**Activity 侧确实保存了浏览焦点，但 Drawer 从不读它**。

- `SidebarController.kt:79-91` `openDrawer()` 已把 `focusedChannelUrl = existingFocus ?: selectedChannelUrl` 写进状态，且 `focusChannel(url)`（`:121`）、`effectiveFocusedChannelUrl()`（`:123`）都在——但 `PlayerActivity.kt:1535-1567` 调用 `PlayerDrawer(...)` 时**只传了 `selectedChannelUrl`（`:1545` = 当前播放台）**，没有传 `focusedChannelUrl / effectiveFocusedChannelUrl`。
- `PlayerDrawer` 内部另起一份局部状态 `focusedChannelUrl`（`PlayerDrawer.kt:177`，初值 null）。每次打开的落点完全由这几处决定：
  - `initialScrollIndex`（`328-331`）= `channelIndexByUrl[selectedChannelUrl] ?: 0` —— **只看播放台**，与上次浏览焦点无关；
  - `channelListState = rememberLazyListState(initialFirstVisibleItemIndex = initialScrollIndex)`（`333-335`）；
  - 打开效果 `LaunchedEffect(visible, channels.size, initialScrollIndex)`（`342-358`）用 `drawerSafeOpenTarget(initialScrollIndex, …)` 定窗口，另一次（`432-440`）`scrollToItem(idx-2)`；
  - `LaunchedEffect(visible)`（`518-533`）在 `hasInitializedDrawerFocus` 首次为真时把局部 `focusedChannelUrl` 设成 `selectedChannelUrl ?: first.url`，并滚到该行；
  - **切组**：`LaunchedEffect(selectedGroup)`（`561-570`）显式 `focusedChannelUrl = null` + `channelListState.scrollToItem(0)`；`focusTargetIndex`（`299-320`）在 `groupChangedFlag && focusedChannelUrl 为空` 时返回 **0**（`307-309`）。
- 因此「每次点击（LEFT 打开抽屉 / 切组）都回开头」= 目标永远由 `selectedChannelUrl` 或常量 0 推导，用户上次停在的位置被丢弃；切组更是硬编码回 0。

### 2.2 修复方案（单变量链路）

**数据模型**：在 `SidebarController` 增加 `lastFocusedByGroup: Map<String,String>`（`SidebarState` 内，会话内记忆，不持久化），`focusChannel(url)` 同时按当前 `selectedGroup` 记一份；`selectGroup(name)` 不清该 map。新增 `fun lastFocusedUrlFor(group: String): String?`。

**接线**（`PlayerActivity.kt:1535-1567`）：`PlayerDrawer(...)` 新增参数
`initialFocusUrl: String? = sidebarController.value.lastFocusedUrlFor(selectedGroup)`；同时把 `onFocusedChannelChanged: (String) -> Unit = { sidebarController.focusChannel(it) }` 传入。

**Drawer 消费**（`PlayerDrawer.kt`）：
1. 新参数 `initialFocusUrl: String? = null`；`lastFocusedUrl` 记忆变量：`var lastFocusedUrl by remember { mutableStateOf<String?>(null) }`。
2. `initialScrollIndex`（`328-331`）：改为**优先** `initialFocusUrl`，其次 `selectedChannelUrl`：
   ```kotlin
   val initialScrollIndex = remember(channelIndexByUrl, selectedChannelUrl, initialFocusUrl) {
       val url = initialFocusUrl ?: selectedChannelUrl
       if (!url.isNullOrBlank()) channelIndexByUrl[url] ?: 0 else 0
   }
   ```
   —— 这一行是「回到上次位置」的核心单变量；S1 的 `drawerSafeOpenTarget` 调用点不用改。
3. `LaunchedEffect(visible)` 的首次初始化（`518-533`）：`val targetUrl = initialFocusUrl ?: selectedChannelUrl ?: channels.firstOrNull()?.url`；首次进入时把 `lastFocusedUrl` 设为该值。
4. 行 `onFocused`（`1020-1030`）：在 `!isFocusRestoring` 分支里同时 `lastFocusedUrl = ch.url; onFocusedChannelChanged(ch.url)`（回调新增）。UP/DOWN 分支（`891`）改 `focusedChannelUrl` 时同样写 `lastFocusedUrl`。
5. 关闭抽屉（`504-515`，`!visible` 分支）：**不要清空** `lastFocusedUrl`（当前不清 `focusedChannelUrl`，保持）。
6. **切组**（`561-570`）：把 `focusedChannelUrl = null` + `scrollToItem(0)` 改为：
   ```kotlin
   val remembered = initialFocusUrlForNewGroup   // 由 Activity 在该组切换时传入，或用 SidebarController.lastFocusedUrlFor(selectedGroup)
   focusedChannelUrl = remembered?.takeIf { channelIndexByUrl.containsKey(it) }
   val idx = remembered?.let { channelIndexByUrl[it] } ?: 0
   runCatching { channelListState.scrollToItem(PerfPolicy.drawerSafeFirstVisibleIndex((idx-2).coerceAtLeast(0), channels.size)) }
   ```
   若暂不扩参数，最低成本的等价实现是：切组时**不清空** `focusedChannelUrl`（删 `565`），让 `focusTargetIndex` 用新组里的同 url（找不到才回 0），并把 `scrollToItem(0)`（`568`）改为按 `focusTargetIndex` 计算——但「按组记忆」仍需 `lastFocusedByGroup`，建议一次做完。
7. `focusTargetIndex`（`299-320`）在 `groupChangedFlag && focusedChannelUrl 空` 的回退分支（`307-309`）改为先看 `lastFocusedUrl` 在新组中的索引，再回 0。
8. 保留 S1：`drawerSafeOpenTarget` / `drawerFirstRows` / `drawerSafeFirstVisibleIndex` 全部原样。

### 2.3 可测行为用例

- `SidebarControllerTest` 新增：`openDrawer → focusChannel(U3) → closeDrawer → openDrawer` 后 `effectiveFocusedChannelUrl()==U3`；`selectGroup("G2") → focusChannel(V5) → selectGroup("G1") → selectGroup("G2")` 后 `lastFocusedUrlFor("G2")==V5`。
- `PerfPolicyTest` 新增：`drawerSafeOpenTarget` 用「上次焦点 index=40」而不是播放台 index=0 时，openTarget 与首帧窗口覆盖 40（原公式同样安全，只是入参变了）。
- 若把「选初始索引」抽成纯函数 `DrawerFocusPolicy.initialIndex(lastFocusedUrl, selectedUrl, indexByUrl)`：覆盖 上次有/无、上次不在当前组、播放台为 null、空组 → 0 等 6 例。
- 静态门禁：禁止 `initialScrollIndex` 仅由 `selectedChannelUrl` 推导（字符串断言：`remember(channelIndexByUrl, selectedChannelUrl, initialFocusUrl)` 必须存在）。

### 2.4 真机验收

1. 播放 A（第 1 台），LEFT 打开抽屉，DOWN×12 停在第 13 台，BACK 关抽屉；再 LEFT 打开 → 焦点与首行仍在第 13 台（dump `focused="true"` 节点与 `row_focus idx=13`），**不是**第 1 台。
2. 在分组列选 G2，浏览到第 20 行，BACK 关，重开 → 仍停 G2 的第 20 行；切回 G1 再回 G2 → 恢复 G2 第 20 行。
3. 0/1/2 行小分组、大组切小组（S1）不崩，落焦有效。
4. 连续 20 次开关无焦点跳动/空焦点；`gfxinfo` 无新增巨帧。

---

## 3. 问题③ 选台背景全色 vs 半透明

### 3.1 根因

`PerfPolicy.DRAWER_OPAQUE_CONTAINER = true`（`PerfPolicy.kt:28-31`，t20/E4），`PlayerDrawer.kt:141-142` 据此把 `container` 设为**不透明** `surface`；`scrim = Color.Transparent`（`:143`）。基线 `c82593f`/`fcc7603` 是 `surface.copy(alpha=0.92f)`（上游副本 `docs/evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt:136` 同）。设置抽屉是 `surface @0.95`（`SETTINGS_IA_V2.md §6.1`），信息卡 `surface @0.92`（`ChannelInfoBanner.kt:81`）。所以选台抽屉是全屏唯一「全色」面板，与其余菜单不一致。

### 3.2 修复方案（注意性能）

**推荐（保留缓存、只改一处开关 + 一处 layer）**：
1. `PerfPolicy.kt` 新增 `const val DRAWER_CONTAINER_ALPHA = 0.92f`（与信息卡/基线一致），并把 `DRAWER_OPAQUE_CONTAINER` 语义改为「是否用不透明绘制」，`false` = 走半透明。
2. `PlayerDrawer.kt:141-142` 保持 `container = surface`（**不透明 paint**），不要改回 `surface.copy(alpha=...)`。
3. 在**已存在的静态背景层**（`928-935`，t58 三层之一）上加 alpha：
   ```kotlin
   Box(modifier = Modifier
       .fillMaxHeight().width(drawerWidth).offset(x = drawerOffsetX)
       .graphicsLayer { alpha = if (PerfPolicy.DRAWER_OPAQUE_CONTAINER) 1f else PerfPolicy.DRAWER_CONTAINER_ALPHA }
       .background(container))
   ```
   这样视频仍从面板透出 8%，但 alpha 只作用在**一张录制好的 layer** 上（GPU 一次 layer blend），不是每个 draw command 都参与整层合成；内容层/列表层（`:937-945`、`:1000-1003`）不受影响。`graphicsLayer()` 缓存与 3 层结构不变，`check_perf_policy.py` 的 3 处 `graphicsLayer` 断言仍成立。
4. `scrim` 仍 `Color.Transparent`，不加全屏遮罩。
5. 单变量回退：`DRAWER_OPAQUE_CONTAINER=true` → alpha=1f，完全回到当前状态；`PerfPolicyTest` / `check_perf_policy.py` 增加 alpha 常量与背景层 `graphicsLayer { alpha = … }` 的断言。

> 备选（更省事但性能风险高）：直接 `DRAWER_OPAQUE_CONTAINER=false` 让 `141-142` 走 `copy(alpha=0.92f)`。**不推荐**——这正是 t20 实测 Draw 118–162ms 的成因。若用此备选，必须真机 A/B 对比 framestats 后再决定。

### 3.3 可测行为用例

- `PerfPolicyTest`：`DRAWER_CONTAINER_ALPHA == 0.92f`；`drawerContainerAlpha(opaque=true)==1f`、`(opaque=false)==0.92f`（若抽纯函数）。
- 静态门禁：`PlayerDrawer.kt` 背景层含 `graphicsLayer { alpha =`；`container` 不能再出现 `surface.copy(alpha`；3 处 `graphicsLayer`、`if (showContent)`、`DRAWER_OPAQUE_CONTAINER` 引用仍在。
- A/B：真机同机同源，开抽屉 20 轮的 p50/p95、Draw ms 与巨帧数对比。

### 3.4 真机验收

1. 播放亮色画面时打开抽屉：面板后方画面可见（半透明），与设置抽屉观感一致；面板外视频不被遮挡。
2. 连续 DOWN×20 + 开关 10 次：`gfxinfo` 无 ≥300ms 巨帧、p50 不劣于 38ms、Draw 不劣于 2.5ms（CI10204 口径）。若明显劣化，回退到 `DRAWER_OPAQUE_CONTAINER=true`。
3. 无灰屏/花屏，SurfaceView 视频正常（t30 红线性）。

---

## 4. 问题④ 信息卡排版（台标下只留「频道 N / 分组」；右上补全徽标；减空白）

### 4.1 根因与现状证据

INFO_CARD_V3（`docs/INFO_CARD_V3.md`）已实现（`INFO_CARD_V3_IMPL.md`），但仍有三处与用户要求不符：

1. **台标下仍带编码**：`ChannelInfoBanner.kt:160` `smallMetaLine(channelNumber, groupLabel, audioCodec)`；`InfoCardPolicy.smallMetaLine`（`InfoCardPolicy.kt:251-257`）把 `audioCodec` 拼成 `频道 N · 分组 · MPEG-L2`。真机 dump `info_card_number` bounds [336,803][559,827]（223px ≈149dp）明显含多段。
2. **右上徽标实际只显示 1 个**：dump `info_card_badges` bounds [1821,668][1860,689] = 39×21px ≈ **26×14dp**，只有「SD」档；`InfoCardPolicy.badgeCandidates`（`149-157`）取 `bitrate/frameRate/channelCount` 全部来自 **video** `Format`（`ChannelInfoBanner.kt:142-147` 的 `vf = videoFormat`）。Media3 的 video Format 通常 `bitrate=-1`、`frameRate` 缺失；而**声道数属于音频 Format**，取 `vf?.channelCount`（`:145` 末参）语义错误 → 码率/声道/帧率永远隐藏（策略层“未知即隐藏”本身没错，是数据源错了）。
3. **中间空白/总高 300dp**：dump `info_card` bounds [0,630][1920,1080] = **450px = 300dp**（正踩上限）。按 INFO_CARD_V3 §7 高账 A 档 286dp 计算，真机因字体度量/双 padding 被撑到 300dp；另外底部最近观看区有**双重 40dp 内边距**：父 `Column`（`:191` `padding(start=40,end=40)`）+ 最近条 `Row`（`:314`、`:401` 又 `padding(start=40,end=40)`）→ 实际左右 80dp，可用宽缩小、长标题更易被 ellipsis（“显示不全”）。

### 4.2 修复方案（精确 DP 布局）

**① 台标下去掉编码**：`InfoCardPolicy.smallMetaLine` 签名改为 `(number: Int, group: String?)`，删除 `audioCodec` 分支；`ChannelInfoBanner.kt:160` 调用改为 `smallMetaLine(channelNumber, groupLabel)`。同步更新 `InfoCardBadgeTest` 的 `smallMetaLine(12,"央视频道","MPEG-L2")` 断言（新期望 `"频道 12 · 央视频道"`）与 `docs/INFO_CARD_V3.md §9.5/§10.1`（标注 v3.1 修订）。tag `info_card_number`/`info_card_group` 保留。

**② 右上徽标补全数据源**：
- `ChannelInfoBanner` 新增 `audioChannelCount: Int = -1`、`videoBitrateFallback: Int = -1`、`frameRateFallback: Float = -1f`；`badgeCandidates` 调用（`143-147`）改为
  `vf?.width ?: 0, vf?.height ?: 0, takePositive(vf?.frameRate, frameRateFallback), takePositive(vf?.bitrate, videoBitrateFallback), audioChannelCount`。
- `AudioTrackUi` 增加 `fun selectedChannelCount(raws: List<Raw>, overrideGroup: Int, overrideTrack: Int): Int`，从**实际播放音轨 Format** 取 `channelCount`（`Raw` 已持有 format 信息，见 `AudioTrackUi.kt:21-24, 113-123`）；`PlayerActivity.kt:2010` 附近把它传给 banner。
- 码率/帧率回退：从 `Player.currentTracks` 的**已选视频 Format**（`onTracksChanged`，`PlayerActivity.kt:839-840` 已在更新 `videoFormat`）取；仍 ≤0 时按 INFO_CARD_V3 §3 隐藏（不显示 `—`），并保留 `IQTV-INFOCARD` DEBUG 日志打出原始值供真机判读。**不伪造数值**。
- 徽标渲染、优先级（分辨率>帧率>码率>声道）、`planBadges` 溢出策略、`BADGE_MAX_WIDTH_DP=260`、单行 ellipsis 全部不动。

**③ 减少空白（精确 DP 表）**：保留 A 档 logo 160×96 / 主行 96dp / 台名 34sp；只收紧 padding/gap（`ChannelInfoBanner.kt`）：

| 区域 | 当前 | 改为 | 位置 |
|---|---:|---:|---|
| 容器 top/bottom padding | 20 / 20 | **16 / 16** | `:191` |
| 徽标行高 | 24 | 24（不变，保 tag 与门禁） | `:194-200` |
| 徽标→主行 gap | 6 | **4** | `:212` |
| 主行高 | 96 | 96（不变，logo 撑高） | `:215-220` |
| 主行→技术行 gap | 4 | **2** | `:291` |
| 技术行高 | 16 | 16 | `:293-306` |
| 技术→最近标签 gap | 12 | **6** | 紧接 `:306` 的 Spacer（当前无显式 Spacer，改为 `Spacer(6.dp)`，替代隐式留白） |
| 最近标签/提示行高 | 16 | 16 | `:309-324` |
| 标签→小卡 gap | 8（`:314` bottom=8） | **6** | `:314` |
| 小卡行高 | 64 | 64（不变） | `RecentStrip :408` |
| **合计（有最近条）** | 300 | **266dp** | 目标 ≤300，留 34dp 余量 |
| 无最近条 | ~214 | **~186dp** | 更矮，不保留空占位 |

- **去掉双重 padding**：删除 `RecentStrip` 内 `:401` 的 `.padding(start=40.dp, end=40.dp)`，以及 `:314` 那行的 start/end（父 Column `:191` 已有 40dp）；只保留 `bottom` 用于间距。这样内容宽回到 1200dp，长台名/节目少被截断。
- 全部文字仍 `maxLines=1 + Ellipsis`，主行/徽标行/技术行固定高；禁 `wrapContentHeight/basicMarquee/LazyRow/animateContentSize`（`check_info_card.py` 既有断言不动）。

### 4.3 可测行为用例

- `InfoCardBadgeTest`：`smallMetaLine(12,"央视频道") == "频道 12 · 央视频道"`；`smallMetaLine(12,"央视频道","MPEG-L2")` 旧签名删除后不再编译（防止回退）。
- 新增 `InfoCardBadgeSourceTest`（纯函数）：`takePositive(frameRate, fallback)` / `takePositive(bitrate, fallback)` 的三例（主值有效取主值、主值 ≤0 取回退、两者无效返回 null）。
- `scripts/check_info_card.py` 保留 v3 全部断言，新增：banner 签名含 `audioChannelCount`、徽标候选使用回退值、主行高 96、容器 padding `16.dp`、无双重 40dp（统计 `padding(start = 40.dp` 出现次数 ≤1）。
- 高度账纯函数（可选）：`infoCardHeight(hasRecent=true) == 266`，`false == 186`，断言 ≤300。

### 4.4 真机验收

1. 直播 CCTV-1：大 logo 160×96 + 34sp 台名同行；台标下只有 `频道 1 · 央视频道`（**无 MPEG-L2**）；右上出现 `FHD · 50fps · 8.3Mbps · 2ch` 中实际可得的项（至少 2 项，缺失项不留空位/不显示 `—`）；技术行仍有 `H.264 · MPEG-L2 · SDR`。
2. dump `uiautomator dump` 量 `info_card` 高度 ≤400px（≈266dp），`info_card_badges` 宽度 >60px（至少 2 个徽标），`info_card_number` 文本不含 `MPEG-L2`。
3. 回看/快进浮层：同一节目行显示起止+进度、最近条隐藏；高度与直播一致（不跳动）。
4. 4K/HD/未知字段台各一：档位切换正确，未知项隐藏不留占位；`IQTV-INFOCARD` 日志能解释每个徽标为何显示/隐藏。
5. F1/F2 焦点用例（MENU 进设置不被清焦点、BACK 只关卡片）全回归。

---

## 5. U1 最近观看不能切换（**= 用户问题 5 的「最近观看」部分**）

### 5.1 根因（三处不一致：高亮 / 真实焦点 / OK 目标）

文件 `ChannelInfoBanner.kt` 与 `PlayerActivity.kt`：

- **高亮由 `focusIndex` 决定**：Activity 的 `onMoveFocus`（`PlayerActivity.kt:2018-2025`）只做 `infoCardFocusIndex = InfoCardPolicy.nextFocusIndex(...)`。
- **真实焦点不跟随**：`RecentStrip` 每张卡自带 `onPreviewKeyEvent`（`ChannelInfoBanner.kt:416-425`），LEFT/RIGHT 调 `onMoveFocus(±1)` 后 `return true` **消费了按键**，Compose 默认焦点移动被阻止；而重聚焦逻辑 `LaunchedEffect(state, focusIndex, recentCount, stripFocusIndex)`（`:105-130`）在 `focusRecoveryAction == REQUEST_FOCUS` 时有守卫 `if (stripFocusIndex < 0)`（`:114`）——真实焦点已经在卡 0（`stripFocusIndex=0`），所以**永远不会**按新的 `focusIndex` 去请求卡 1 的焦点。
- **OK 目标是事件来源卡片**：`onPreviewKeyEvent` 里 `Key.DirectionCenter/Enter → onSwitchRecent(index)`（`:421`）传的是**当前持有焦点的卡片 index**（卡 0），不是 `focusIndex`（卡 1）。于是 `OK→OK→右→OK` 实际切到第一张卡对应的台。
- 这三处叠加 = 用户反馈的「不能切换」。之前 `InfoCardRecentTest` 只测数据规则（`cardRecentItems` / `nextFocusIndex`），没测「高亮=焦点=OK 目标」这条不变量，所以漏掉。
- **布局**:`:191` 与 `:314`/`:401` 双重 40dp padding（见 §4.2③），以及 6 张卡时末尾卡片可能贴边/文字被 ellipsis；`RecentStrip` 无 `clipToBounds`、无显式 `Arrangement`。

### 5.2 修复方案（单一真源 = `focusIndex`）

1. **统一 OK 目标**：`PlayerActivity.kt:2027-2035` 的 `onSwitchRecent` 忽略传入 index，改用权威 `infoCardFocusIndex`：
   ```kotlin
   onSwitchRecent = { _ ->
       cardRecentItems.getOrNull(infoCardFocusIndex)?.let { item -> ...switchToRecent(item)... }
   }
   ```
   或把 banner 的 OK 处理改成 `onSwitchRecent(focusIndex)`。二选一，推荐前者（Activity 端单点改）。
2. **真实焦点跟随高亮**：`ChannelInfoBanner.kt:112-118` 去掉 `if (stripFocusIndex < 0)` 守卫，改为**每次 `focusIndex` 变化都请求**：
   ```kotlin
   val target = requesters.getOrNull(focusIndex.coerceIn(0, recentCount - 1))
   runCatching { target?.requestFocus() }
   ```
   并把 `stripFocusIndex = focusIndex` 同步（先于请求，避免 `aggregateNodeFocus` 短暂为 false）。`LaunchedEffect` 的 key 已含 `focusIndex`（`:105`），无需改 key。
3. **移除卡片自身的 OK 处理**：`:421` 删掉 `Key.DirectionCenter/Enter/NumPadEnter` 分支（避免事件源 index 再次成为目标）；在 banner 根 `onPreviewKeyEvent`（`:176-186`）补 `Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onSwitchRecent(focusIndex); true }`，且仅在 `focused` 时生效（`:178` 守卫保留）。LEFT/RIGHT 仍可由卡片处理（调用 `onMoveFocus`），也可上移到根部；推荐上移，卡片只保留 `onFocusChanged` 上报，减少两套按键路径。
4. **F1/F2 不回归**：`stripFocusIndex` 仍由 `onCardFocusChanged`（`:333-336`）维护；`focusRecoveryAction` 三态、`shouldClearFocusOnTransition`、`onFocusedChanged` 镜像、`HIDDEN` 兜底全部不动；`FOCUSED + 0 条 → EXIT_FOCUS` 仍生效。
5. **布局修正**（与 §4.2③ 同一改动）：删除 `:314` 与 `:401` 的内层 40dp padding；`RecentStrip` 的 `Row` 加 `.height(64.dp).clipToBounds()` 与 `horizontalArrangement = Arrangement.Start`；卡片 `Text` 保持 `maxLines=1 + Ellipsis`；`MAX_RECENT=6` 与 `104×64/12sp/12dp` 不变（规格原样保留）。
6. **焦点切换后行为**：`onSwitchRecent` 成功后 `infoCardFocusIndex = 0`（`:2032`）保持；下一帧 `LaunchedEffect(focusIndex)` 会把焦点落在新的第 0 张卡（刚离开的台），符合 `INFO_CARD_V2 §5.2` A↔B 语义。

### 5.3 可测行为用例

- `InfoCardFocusTest`（纯逻辑）新增不变量：`focusIndex` 变化后 `focusRecoveryAction(FOCUSED, n>0) == REQUEST_FOCUS` 且目标索引 == `focusIndex`（把「目标索引」抽成纯函数 `focusTargetIndex(focusIndex, count)` 便于断言）；`onSwitchRecent` 目标解析函数 `resolveRecentTarget(items, focusIndex)` 返回 items[focusIndex]。
- `InfoCardRecentTest` 追加：`switchingBackAndForthAlternatesBetweenTwoChannels` 的「OK 目标 = 高亮」断言（用 `resolveRecentTarget`）。
- 静态门禁 `check_info_card.py`：banner 中不得出现 `onSwitchRecent(index)`（只许 `onSwitchRecent(focusIndex)` 或 Activity 端忽略参数）；`REQUEST_FOCUS` 分支不得含 `stripFocusIndex < 0`；根部 `info_card` 的 `onPreviewKeyEvent` 必须含 `Key.DirectionCenter`；F1/F2 既有断言全保留。
- 若引入 Compose UI 测试（可选）：`setContent { ChannelInfoBanner(state=FOCUSED, recentCards=3, focusIndex=1) }` → 断言 `info_card_recent_1` `focused=true`，按 RIGHT 后 `_2` focused，按 OK 触发 `onSwitchRecent(2)`。

### 5.4 真机验收（用户明确要求）

1. 至少 5 张最近观看卡时：`OK`（进聚焦，卡 0 亮且真焦点在卡 0）→ `OK`（切到卡 0 台）→ `OK` → `右` → `OK`：必须切到**第二张卡**对应台（`logcat IQTV-INFOCARD: switch idx=1 group=…`）。
2. `右/左` 连续移动：dump 中 `focused="true"` 的 `info_card_recent_N` 与 `focusIndex` 一致；走到首/尾 clamp 不环绕。
3. 6 张卡全部可见可聚焦，末尾卡片不被截断；卡片文字不换行。
4. 回看/快进浮层：最近条不渲染、不可聚焦；`OK` 不误切台。
5. 卡片聚焦中 MENU→设置、后台刷新使最近观看变空、强杀重进：BACK 只关卡片不退出播放器（F1/F2 回归）。

---

## 6. U5 抽屉与节目单状态区分（与选台 UI 内容重叠）

### 6.1 根因

- **播放态标记丢失**：`DrawerChannelItem` 签名有 `selected: Boolean`（`PlayerDrawer.kt:1251`），调用点 `:1017` 传了 `ch.url == selectedChannelUrl`，但**函数体从未使用 `selected`**（`1257-1377` 无引用）→ 用户看不出「哪台在播、哪台只是浏览焦点」。基线 `UI_RESTORE_RICHNESS3.md` LOGO-07 曾用 `▶` 前缀 + `· 播放中`，Compose 版本丢掉了。
- **可回看态不可见**：`DrawerProgramItem` 只有 `timeState`（Past/Now/Future，`1090-1094`）驱动颜色；点击时才知道能不能回看（`1107-1141`：`state==Past && ch.catchupMode!=null`）。没有事前标记。
- **空态不区分**：`:1061-1073` 只有「加载中」/「暂无节目单」两种，无法区分「未配置 EPG 源」「加载失败」「配置了该频道但无节目」。
- **信息卡被浏览分组污染**：`PlayerActivity.kt:1186-1196` 构造 `nowProgramByUrl` 时按 `selectedGroup` 过滤 groups；抽屉里切到别的组后，正在播放的 A 台的 `nowProgramByUrl[url]` 会消失 → 信息卡节目变空（`1972/1978/1992`）。这是「切换浏览分组不影响 A 台的信息卡节目」验收失败的直接原因。

### 6.2 修复方案

1. **播放标记**：在 `DrawerChannelItem`（`1257` 附近）用 `selected` 生成 `val nameLabel = remember(selected, index, channel.title) { "${if (selected) "▶ " else ""}$index. ${channel.title}" }`（替代 `:1259` 的 `label`）；聚焦行副标题末尾追加 ` · 播放中`（仅 selected 时）。`▶` 与 `· 播放中` 用纯文本前缀，无新节点/无动画。
2. **浏览焦点标记**：保持 `focused → surfaceVariant` 底色（`:1269-1272`），与 `selected` 的文本标记形成两个正交维度。A 台播放 + 焦点在 B 台时：A 有 `▶`，B 有 `surfaceVariant` 底，可同时辨认。
3. **可回看标记**：给 `DrawerProgramItem` 新增 `catchupAvailable: Boolean` 参数；在 `:1095-1103` 计算 `state == Past && epgDataChannel?.catchupMode != null` 并传入；item 内 `Text` 标题后追加 ` · 回看`（或前缀 `⟲`），颜色 α0.6；不可用历史节目不给标记。
4. **空态三分类**：`PlayerDrawer` 新增 `epgSourceConfigured: Boolean`、`epgLoadFailed: Boolean` 参数；`:1069` 文案改为
   `if (isEpgLoading) "加载中" else if (!epgSourceConfigured) "未配置 EPG 源" else if (epgLoadFailed) "EPG 加载失败" else "暂无节目单"`。Activity 从 `Prefs.getEpgSource(context).isNotBlank()` 与 `epgData == null && 已尝试加载` 传入（`PlayerActivity.kt:1106-1163` 的 EPG 加载链路已有 timeout/fallback，可加一个 `epgLoadFailed` 状态位）。
5. **信息卡与实际播放台绑定**：`PlayerActivity.kt:1186-1196` 的 `nowProgramByUrl` 构建**改为始终遍历 `channelGroups`（全部组）**，删除按 `selectedGroup` 过滤的分支（`1188-1194`）；`LaunchedEffect` key 去掉 `selectedGroup`。这样抽屉浏览分组变化不影响 A 台信息卡。对照 S5 的「节目快照与频道绑定」（§11）一起做更稳。
6. **perf 保护**：以上只在已有节点上加文本/参数，不新增 LazyColumn、不重录 layer；`▶` 用 `remember` 预格式化避免每帧拼接。

### 6.3 可测行为用例

- 静态门禁：`DrawerChannelItem` 函数体必须出现 `selected`；`PlayerDrawer` 签名含 `epgSourceConfigured/epgLoadFailed`；`nowProgramByUrl` 的 `LaunchedEffect` key 不含 `selectedGroup`。
- 纯函数（可选）`DrawerUiPolicy.channelLabel(selected, index, title)` / `programEmptyText(configured, failed, loading, empty)` 表驱动测试（4 组合）。
- `InfoCardFocusTest` / `InfoCardPolicyTest` 不回归；`SettingsIaTest` 不回归。

### 6.4 真机验收

1. A 台播放、抽屉焦点在 B 台：A 行有 `▶`，B 行有聚焦底色，两者可辨；dump 同时见 `text` 含 `▶` 的节点与 `focused="true"` 的节点。
2. 抽屉切到别的分组后，信息卡节目仍是 A 台的当前节目（`info_card_program` 文本不空、不跳变）。
3. 历史节目行显示 `· 回看`，未来/当前节目不显示；OK 仅历史且支持回看的节目能进入回看。
4. EPG 未配置 / 网络失败 / 已配置无节目：节目单区分别显示三种文案；网络失败时旧缓存仍可见（S4 语义，§10）。

---

## 7. 问题⑤ 遥控设置改 TiviMate 式「功能→按键」全映射

### 7.1 根因与现状

`docs/TIVIMATE_REFERENCE_2026_11_22.md §1` 明确目标：功能列表 → 每个功能绑定任意键（短按/长按），默认保持现有行为、老偏好迁移。当前实现是 5 个固定二选一开关：

- `RemoteHabits.kt:13-41`：5 个 enum（`UpDown/Ok/Back/Digits/channelKeysEnabled`）+ `Settings` data class；`DEFAULT` 在 `:53`；纯决策函数 `onUpDown/onOk/onBack/onDigit`（`:73-118`）。
- `SettingsDrawer.kt:563-637` `SettingsRemotePage`：6 行（5 开关 + 恢复默认），`OK` 循环切换（`onClick = onCycleXxx`），标签来自 `RemoteHabits.*.label`。
- `PlayerActivity.dispatchKeyEvent`（`:264-486`）：按 `event.keyCode` 分派；数字 OSD（`:265-301`）、信息卡聚焦镜像（`:302-323`）、OK（`:335-351`）、LEFT/RIGHT（`:353-375` 回看时 seek）、MENU（`:377`）、BACK（`:384-441`）、UP/DOWN + 166/167（`:443-482`）。`calculateSeekStep(repeatCount)`（`:253-260`）已经区分长短按的"步幅"，但只用于回看 seek。
- `Prefs.kt:27-32, 232-272`：5 个 `remote_*` key 与 `resetRemoteSettings()`。
- `PlayerActivity.kt:582-593`：`remoteHabits` Compose state + `SideEffect { onRemoteSettingsChanged(remoteHabits) }` 即时生效；`:1723-1757` 五个 cycle lambda 写 Prefs + 更新 state。

### 7.2 数据模型（新增，与旧模型并存以便迁移）

新建 `RemoteBindings.kt`（纯 Kotlin，Android-free，放 `src/tivimini/java/com/cjlhll/iptv/`）：

```kotlin
enum class RemoteAction(val label: String) {
    CHANNEL_UP("上一个频道"), CHANNEL_DOWN("下一个频道"),
    OPEN_DRAWER("打开频道列表"), OPEN_SETTINGS("打开设置"),
    SHOW_INFO("显示信息卡"), RECENT_CHANNELS("最近观看"),
    LAST_CHANNEL("上一台"), RETURN_LIVE("返回直播"),
    SEEK_BACK("快退"), SEEK_FORWARD("快进"),
    PLAY_PAUSE("播放/暂停"), VOLUME_UP("音量+"), VOLUME_DOWN("音量-"),
    EXIT_APP("退出应用"), NONE("未绑定"),
}
data class KeyBinding(
    val action: RemoteAction,
    val shortPressKeycodes: Set<Int> = emptySet(),
    val longPressKeycodes: Set<Int> = emptySet(),
)
object RemoteBindings {
    const val PREFS_KEY = "remote_action_bindings_v1"   // 新 Prefs，序列化 JSON/字符串
    val DEFAULT: List<KeyBinding> = listOf(
        KeyBinding(RemoteAction.CHANNEL_UP,    setOf(KEYCODE_DPAD_UP, 166/*CHANNEL_UP*/, 92/*PAGE_UP*/)),
        KeyBinding(RemoteAction.CHANNEL_DOWN,  setOf(KEYCODE_DPAD_DOWN, 167, 93)),
        KeyBinding(RemoteAction.OPEN_DRAWER,   setOf(KEYCODE_DPAD_LEFT)),
        KeyBinding(RemoteAction.OPEN_SETTINGS, setOf(KEYCODE_DPAD_RIGHT, KEYCODE_MENU)),
        KeyBinding(RemoteAction.SHOW_INFO,     setOf(KEYCODE_DPAD_CENTER, KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER)),
        KeyBinding(RemoteAction.RECENT_CHANNELS, setOf(KEYCODE_LAST_CHANNEL)), // 例：可重绑
        KeyBinding(RemoteAction.RETURN_LIVE,   longPressKeycodes = setOf(KEYCODE_BACK)),
        KeyBinding(RemoteAction.EXIT_APP,      longPressKeycodes = setOf(KEYCODE_BACK)), // 双按仍保留
        KeyBinding(RemoteAction.SEEK_BACK,     setOf(KEYCODE_DPAD_LEFT)),   // 回看上下文
        KeyBinding(RemoteAction.SEEK_FORWARD,  setOf(KEYCODE_DPAD_RIGHT)),  // 回看上下文
    )
    fun resolve(keycode: Int, longPress: Boolean, context: UiContext): RemoteAction?
    fun serialize(bindings: List<KeyBinding>): String
    fun parse(raw: String?): List<KeyBinding>              // 未知/损坏 → DEFAULT
    fun migrate(legacy: RemoteHabits.Settings): List<KeyBinding> // 老偏好 → 新绑定
}
enum class UiContext { PLAYER_ONLY, EPG_VISIBLE, MENU_OPEN, CATCHUP }
```

- **默认行为完全等价**：UP/DOWN=换台、LEFT=频道列表、RIGHT/MENU=设置、OK=信息卡、166/167/PAGE=换台、BACK 双按退出、回看时 LEFT/RIGHT=seek、回看 OK=信息卡（t193 CATCHUP-01）。
- **上下文敏感**：`resolve(keycode, longPress, context)` 先按 context 过滤（PLAYER_ONLY 才换台；CATCHUP 把 LEFT/RIGHT 解析为 SEEK_*；MENU_OPEN 由覆盖层自行处理，返回 NONE），再查 short/long 集合。与原「覆盖层先赢」守卫一致（TiviMate ref §1 与 `REMOTE_HABITS_SETTINGS.md §4`）。
- **老偏好迁移**：`Prefs.getRemoteSettings()` 读到的旧 `Settings` 若曾显式写过任一 `remote_*` key，用 `migrate()` 生成新绑定并写一次 `remote_action_bindings_v1`；未写过则用 `DEFAULT`。枚举名保留，`RemoteHabits` 可暂不删除（兼容 `RemoteHabitsTest`），但 `dispatchKeyEvent` 改读新模型。
- **长短按判定**：`event.isLongPress` / `event.repeatCount > 0`；回看 seek 继续用 `calculateSeekStep(repeatCount)`（`:253-260`），只把「是否 seek」交给 `resolve`，不重复造步幅逻辑。

### 7.3 UI（功能→按键，每个功能一行，点击后捕获）

`SettingsDrawer.kt:563-637` `SettingsRemotePage` 改为**动态 inflate**（不硬编码 6 行）：

- 行 = `RemoteAction.label`（如「下一个频道」）+ 当前绑定摘要（如 `↑ / 166 / 92`，短按/长按分两行小字）；tag `settings_remote_action_<action.name>`；保留「恢复默认」行 `settings_remote_reset`。
- 点击某行 → 进入**捕获态**：行内显示「请按任意键（短按）…」，`PlayerActivity` 收到下一个非导航冲突键时写回该 action 的 `shortPressKeycodes`；长按捕获同理（行内两个子按钮：`settings_remote_bind_short_<action>` / `_long_<action>`，OK 选中开始捕获）。捕获期间 `dispatchKeyEvent` 优先消费一次键并退出捕获态，且**不执行该键原本动作**（用 `captureRequest` 状态位，类似 digit OSD 的守卫 `:265-301`）。
- 冲突处理：同一键被绑定到多个 action 时，按上下文解析；同上下文冲突提示「该键已绑定到 X，是否覆盖」，确认后从旧 action 移除。
- 页脚帮助保留 `频道列表：← 　设置：→`、`默认：…`（`SettingsDrawer.kt:634-635`）；一级摘要 `SettingsSummary.remote(upDown, ok)`（`SettingsIA.kt`）改为读新绑定的摘要（保持 ≤22 全角字符）。
- `SettingsIA.kt` 的 `LEGACY_TAG_PAGE` 保留旧 6 个 tag 映射（`SettingsZeroLossTest` 不回归），新增 `RemoteAction` 页面 tag 列表；`check_settings_ia.py` 的 24 旧 tag 断言仍通过（旧 tag 作为兼容别名存在或保留退役说明）。

### 7.4 可测行为用例

- `RemoteBindingsTest`（新，纯 JVM）：
  - `DEFAULT.resolve(DPAD_UP,false,PLAYER_ONLY)==CHANNEL_UP`；`resolve(DPAD_LEFT,false,CATCHUP)==SEEK_BACK`；`resolve(BACK,true,PLAYER_ONLY)==RETURN_LIVE`；`resolve(any,*,MENU_OPEN)==null`（覆盖层赢）；
  - `migrate(legacy)`：5 种旧组合（默认/上下键=呼出/OK=打开/Back=退出/禁用频道键）映射后与原行为一致；
  - `parse` 未知/空/损坏字符串 → DEFAULT；`serialize`→`parse` 往返一致；冲突解析稳定。
- `RemoteHabitsTest` 既有 13 例不回归（旧模型保留）；`SettingsIaTest`/`SettingsZeroLossTest`/`SettingsBackStackTest` 不回归。
- `check_settings_ia.py` / `check_settings_scroll.py`：新 tag 在 `SettingsDrawer.kt` 出现，页面仍 ≤6 可聚焦行或显式改为动态列表（若超过 6 行需允许滚动，`SETTINGS_IA_V2 §7` 的 `verticalScroll+BringIntoView` 已具备）。
- 静态门禁：`dispatchKeyEvent` 中不得再直接 `when (settings.upDown)` 等旧分支（改为 `RemoteBindings.resolve`）；旧 `onUpDown/onOk/onBack` 保留但标 `@Deprecated`。

### 7.5 真机验收

1. 默认状态复现既有 7 步 device protocol（`REMOTE_HABITS_SETTINGS.md §Device protocol` + `REMOTE_HABITS_SETTINGS2.md §6`）：换台/OK 出卡/BACK 双按/数字键/166-167/恢复默认全部一致。
2. 设置 → 遥控器与操作 → 「下一个频道」→ 点「短按绑定」→ 按 `KEYCODE_BUTTON_1`（彩键）：摘要更新；回播放器按该彩键换台，原 UP 不再换台（除非仍绑定）。
3. 「显示信息卡」长按绑定 `KEYCODE_MENU`：短按 MENU 仍开设置，长按出信息卡；`getRepeatCount` 不误触两次。
4. 回看中 LEFT/RIGHT 仍 seek（步幅 10/30/60/300s），OK 只出卡不开列表；退出回看后同键恢复直播语义。
5. 任一覆盖层打开时按已重绑键：覆盖层行为不变（不穿透切台）。
6. 强制停止重启 + 老版本偏好升级：绑定保持；「恢复默认」一次性清回 TiviMate 式默认。

---

# 第二批 · 稳定性 / 遥控反馈 / 性能

> 按 HANDOFF「交给 agent 的方式」：**S2 的播放状态契约先定（§8），U4 对接它（§12）；涉及同一文件的任务顺序交接或独立工作树**。
> `PlayerActivity.kt`、`PlayerDrawer.kt`、`ChannelInfoBanner.kt`、`SettingsDrawer.kt` 被多条任务触碰，建议按下表分批：
> 第一批 UI（§1–§7）→ S2 契约（§8）→ U4（§12）→ S5（§10）→ 其余；同文件不要并行写。

## 8. S2 · 播放恢复：统一重试预算 / 退避 / 代际校验（**先定契约**）

### 8.1 根因（三条独立路径各管一段，缺统一预算与终态）

`PlayerActivity.kt` 的直播失败恢复在 `1028-1069` 的 `for (failure in playbackErrorEvents)`：

1. 先 `!playbackIntent.isCurrent(failed) || p.currentMediaItem?.mediaId != failed.mediaId` 代际/identity 校验（`:1032`）——这部分是对的；
2. `playbackIntent.recover(...)`（`:1044-1050`）每次失败都会拉 M3U（`BackgroundSourceUpdater.refreshPlaylistUrgent`）并可能重播；**没有总重试预算/退避**，坏流会反复拉列表 + 重播；
3. 之后还手动轮备用线路（`:1053-1067`），又是另一条重播路径；
4. **看门狗**另起一套：`StreamPlayback.armWatchdog`（`179-247`）用 `FirstFrameWatchdogPolicy.Watchdog`（8s / 最多 1 次）→ `rebuildHandler { rebuildPlayer(soft) }`（`PlayerActivity.kt:1334-1337`），代际 `isCurrent(gen)` 在 `:189` 检查但 timeout 触发后先 `cancel()`（`:198`）再 rebuild——这是一条与 error 事件**互不知情**的重建路径；
5. **首帧事件**：`StreamPlayback.Metrics` 在首帧时 `onFirstFrameForWatchdog(player)`（`StreamPlayback.kt:250-252, 452`）并 `ZapTrace.onFirstFrame(now)`（`PlayerActivity.kt:832`），但首帧事件未按 `mediaId`/代际回传给 U4 的加载 UI；`ChannelSwitchController.hide()`（`ChannelSwitchLoading.kt:45-47`）由任意 `STATE_READY/ENDED/onPlayerError`（`:69-80`）或 12s 定时器（`:87-92`）触发，三种终态混在一起。

「选 A 后立即选 B，A 的迟到事件影响 B」的残余窗口：`playbackErrorEvents` 是 `Channel.BUFFERED` 的 `remember Channel`（`:616`），消费在 `LaunchedEffect(Unit)`；虽然 `:1032` 有 identity 校验，但 `recover()` 的刷新是 `suspend` 且中间有多处 guard，若 B 的 `beginSelection()` 尚未发生（同帧 A→B）仍可能启动一次无谓网络拉；看门狗虽然 `isCurrent(gen)`，但 `armWatchdog` 的 rebuild 只在 `handler != null` 时回调 Activity 的 `rebuildPlayer`，与 `playbackIntent.revision` 无直接耦合。

### 8.2 播放状态契约（供 U4 对接，纯 Kotlin 新文件 `PlaybackRecoveryPolicy.kt`）

**单一状态机**，所有"选台 → 终态"路径都写入它：

```kotlin
enum class PlaybackPhase { IDLE, SELECTING, PREPARING, BUFFERING, READY, FAILED, TERMINAL }
data class PlaybackSessionState(
    val generation: Int,          // 每次用户选台 +1（与 PlaybackIntent.ticket / Watchdog.gen 对齐）
    val mediaId: String?,
    val channelKey: String,
    val phase: PlaybackPhase,
    val attempts: Int,            // 本代内已尝试次数
    val triedUrls: Set<String>,
    val nextBackoffMs: Long,
    val error: String?,
)
object PlaybackRecoveryPolicy {
    const val MAX_ATTEMPTS = 3              // 总预算：首播 + 2 次恢复
    const val BASE_BACKOFF_MS = 800L
    fun backoff(attempt: Int): Long          // 800 / 1600 / 3200，cap 3200
    fun onFailure(state, failedGen, failedMediaId): Decision  // IGNORE/RETRY_LINE/REFRESH_LIST/GIVE_UP
    fun onFirstFrame(state, gen, mediaId): PlaybackSessionState // → READY（终态）
    fun onTimeout(state, gen): Decision      // 与 onFailure 共用预算
    fun isStale(state, gen, mediaId): Boolean
}
```

**契约要点**：
- **一次选台 = 一个 generation + 一个 `mediaId`**；任何迟到事件带旧 `gen` 或不同 `mediaId` → `IGNORE`（不取消/不重建/不覆盖 B）。
- 所有重试（error、watchdog timeout、备用线路）共用 `attempts` 预算和 `triedUrls`，不再各管各的；到预算 → `TERMINAL`，UI 显示「重试 / 选其他线路」入口。
- `REFRESH_LIST`（拉 M3U）最多每代 1 次，且必须与 `SourceRepository` 的版本守卫（§9）配合。
- 首帧事件把 phase 推到 `READY` 并**唯一地**通知 `ChannelSwitchController.hide()`（U4）；错误/超时最终态推 `FAILED/TERMINAL`，由 UI 决定显示什么。

### 8.3 修复接线

- 新建 `PlaybackRecoveryPolicy.kt`（纯 JVM 单测）；`PlayerActivity.kt` 持有 `var playbackState by mutableStateOf(PlaybackSessionState(...))`（`:616` 附近），`channelSwitch.show()` 时 `onSelection(channel)` 建新代。
- `:1028-1069` 的 error collector 改为 `when (PlaybackRecoveryPolicy.onFailure(playbackState, failed.revision, failed.mediaId)) { IGNORE -> continue; RETRY_LINE -> prepareRequest(next variant); REFRESH_LIST -> playbackIntent.recover(...); GIVE_UP -> playbackState = ...TERMINAL }`；`attempts/backoff` 由纯函数给出，`delay(Policy.backoff(attempt))`。
- `StreamPlayback.armWatchdog`（`179-247`）的 timeout 改为调用 `PlaybackRecoveryPolicy.onTimeout` 并把结果交给同一个 handler；`FirstFrameWatchdogPolicy` 的 8s/1 次重试保留作为"无首帧"分支的**输入**，但不再单独 rebuild。
- 首帧回调（`PlayerActivity.kt:832` 附近、`StreamPlayback.kt:452`）除 `ZapTrace` 外调用 `onFirstFrame` 推 READY。
- 代际统一：`PlaybackIntent.ticket()`（`PlaybackIntent.kt:30`）与 `Watchdog.arm()` 返回的 gen 由 Activity 对齐（同一个 `selectionGeneration`），避免两套计数。
- `check_player_threading.py` / 静态门禁：error collector 必须调用 `PlaybackRecoveryPolicy.onFailure`；不得裸调 `playbackIntent.recover` 超过 1 次/代。

### 8.4 可测行为用例

- `PlaybackRecoveryPolicyTest`：连续 3 次 FAILED → attempts=3、第 3 次 GIVE_UP、`REFRESH_LIST` 只出现 1 次；退避序列 800/1600/3200；旧 gen / 不同 mediaId 的事件全部 `IGNORE`（含 A→B 交错）；首帧到达后任何旧 error 都 IGNORE；timeout 与 error 共用预算。
- 静态门禁：`PlayerActivity.kt:1028-1069` 原有裸 `recover` 分支被 Policy 调用替换；`FirstFrameWatchdogPolicy` 既有测试不回归。

### 8.5 真机验收

1. 断网/坏流台连按 OK：重试 2 次后停在终态（Toast「该频道暂不可播」），日志 `attempts=3`、`giveup=1`，**不再**反复拉 M3U（grep `playlist download` 次数 ≤1）。
2. A 台坏流立即选 B 台：B 正常出画面；A 的迟到 error / watchdog 事件日志 `IGNORE stale`，B 不被取消/重建/覆盖。
3. 首帧到达后加载提示消失一次；12s 定时器不再误触发隐藏（U4）。

---

## 9. S3 · 源保存竞态：手动代际 vs 已提交快照

### 9.1 根因

`SourceRepository.kt:42-56`：`beginChange()` 先 `setVersion(next)`（`:44-45`）作为**手动请求代际**，`commitSource(live,epg,content,expectedVersion)` 校验版本相等后写缓存、再 `setVersion(+1)` 并提交 Prefs（`:49-55`）。问题：`beginChange` 已经把版本号推进了一格，后台刷新 `commitBackground(expectedVersion=SourceStore.currentVersion())`（`BackgroundSourceUpdater.kt:47,63`）在**用户验证新源期间**读到的是「手动请求代际」而不是「已提交源快照版本」：
- 用户 `beginChange()` → v=N+1（未提交）；后台读 `expectedVersion=N+1`、拿旧源 A 的内容，若用户尚未 `commitSource`，`commitBackground` 会通过版本校验并 `setVersion(N+2)`；随后用户 `commitSource(B, expected=N+1)` 因版本已变返回 false → **B 验证成功却提交不了**；A→B→A 的来回也会让旧结果覆盖新指针。`SourceIntegrityTest` 覆盖了「beginChange 后第一次 commit 成功、第二次失败」的顺序场景，但没覆盖「beginChange 与后台刷新交错」这一真实竞态。

### 9.2 修复方案（单变量）

把「手动请求代际」和「已提交源快照版本」**拆成两个字段**：

- `SourcePrefsStore` 新增 `getCommittedVersion()/setCommittedVersion(v)`（Prefs 新 key `source_committed_version`），`getVersion()/setVersion()` 继续作为**请求代际**。
- `beginChange()`（`:43-47`）只递增请求代际，**不动** committedVersion。
- `commitSource()`（`:49-56`）在 expected 校验通过后：`writeAtomic` → `setCommittedVersion(newVersion)` → `setVersion(newVersion)`（一次 Editor，与 `Prefs.setSourceConfig` 同事务）→ `commitSource(...)`；失败的写不推进任一版本。
- `commitBackground(expectedVersion, ...)`（`:59-67`）校验 `expectedVersion == prefs.getCommittedVersion()`（**不是** getVersion）且 `getLiveSource()==live`，提交后同时推进两个版本。
- `BackgroundSourceUpdater.kt:47` 取 `SourceStore.currentCommittedVersion(context)`（新 facade 方法）；`MainActivity.kt:170/191` 的手动流程不动（`beginChange` 仍返回请求代际）。
- 老用户升级：`Prefs.getCommittedVersion()` 缺省时回退为 `getVersion()` 一次并写入（幂等）。

### 9.3 可测行为用例（`SourceIntegrityTest` 扩充）

- `beginChange(B)` 未提交 → 后台 `commitBackground(oldCommitted, A)` 成功 → 用户 `commitSource(B, expected=requestGen)` 仍成功（B 生效，A 旧结果不覆盖）。
- 手动 `commitSource` 失败（写盘失败）→ 两个版本都不推进；后台仍可提交旧源快照。
- A→B→A 交错：最后一次显式提交的源胜出；旧 `expectedVersion` 的后台提交一律 false。
- Prefs 迁移：无 committedVersion 时首次读取 = 当前 version。
- 静态门禁：`SourceRepository.kt` 中 `commitBackground` 必须出现 `getCommittedVersion()`；`beginChange()` 不得调用 `setCommittedVersion`。

### 9.4 真机验收

1. 设置页验证新源 B（等待下载/解析），期间触发自动刷新（可手动"立即刷新播放列表"制造交错）：B 最终保存并播放；旧 A 内容不覆盖 B 指针。
2. 故意断网让 B 下载失败：配置保持 A，无「已从手机推送更新」假成功。
3. 连续 A→B→A 快速切换 + 后台刷新：最终播放列表与 Prefs liveSource 一致；force-stop 重启后仍是最终源。

---

## 10. S4 · EPG 假成功：网络成功 / 缓存回退 / 失败三态

### 10.1 根因

`EpgOutcome.kt:3-38` 已经定义了 `EpgLoadResult.Success/CacheFallback/Failure` 和 `EpgOutcome.classify`（纯逻辑），`EpgRepository.loadStructured`（`EpgRepository.kt:27-86`）也返回结构化结果（`:81 Success`、`:84 CacheFallback/Failure`）。**但调度器没消费三态**：`AutoUpdateScheduler.onEpgResult(context, ok: Boolean)`（`AutoUpdateScheduler.kt:214-225`）只拿到 `data != null`（`:99/134-135/197-198`）——`BackgroundSourceUpdater.refreshEpg` 在 `:79-87` 把 `result.dataOrNull` 返回，`CacheFallback` 也非 null → `ok=true` → `onEpgResult` 推进 `lastEpgUpdateTime` 并 `afterSuccess()` 清退避。这就是「网络失败回退旧缓存被记为成功」；手动反馈同样只看 `data != null`，用户不知道在用旧数据。

### 10.2 修复方案

1. `BackgroundSourceUpdater.refreshEpg` 返回类型改为 `EpgLoadResult`（保留一个 `refreshEpgData` 包装给不需要三态的旧调用点），不再把三态压成 `EpgData?`。
2. `AutoUpdateScheduler` 的三处调用（`onSourceChanged :98-100`、`refreshNow :133-135`、`runDue :197-198`）改用结果：
   ```kotlin
   onEpgResult(context, result)   // 新签名
   ```
   `onEpgResult`（`:214-225`）：`Success` → `setLastEpgUpdateTime(now)` + `afterSuccess()`；`CacheFallback` → **不推进成功时间**、`afterFailure()`（退避生效，用旧数据），`_events.tryEmit("EPG 网络失败，正在使用旧数据")`；`Failure` → `afterFailure()` + `setEpgFailureState` + `"EPG更新失败"`。
3. 手动反馈（`refreshNow :136`）：三态分别 emit `EPG已更新 / EPG使用旧数据 / EPG更新失败`。
4. EPG 数据仍交给 UI（`_epgUpdated.tryEmit(data)` 只在 `Success` 或 `CacheFallback` 且有数据时发，`Failure` 不发）；已有缓存仍可看旧节目（红线性）。
5. `EpgOutcome.classify` 保持纯函数；`AutoUpdateConfig.afterSuccess/afterFailure` 语义不变。

### 10.3 可测行为用例

- `AutoUpdateConfigTest` 扩充 / 新 `EpgOutcomeSchedulingTest`：`Success → lastSuccess 前进 + failCount=0`；`CacheFallback → lastSuccess 不变 + failCount+1 + 事件含"旧数据"`；`Failure → 无数据 + 退避`。
- `BackgroundSourceUpdater` 的 `refreshEpg` 签名变更处全部编译通过（grep 调用点 4 处）。
- `check_refresh_paths.py` 增加：`AutoUpdateScheduler.onEpgResult` 参数类型不是 Boolean；`refreshEpg` 返回 `EpgLoadResult`。

### 10.4 真机验收

1. 断开 EPG URL（改失效地址）但保留缓存：自动/手动刷新后旧节目仍显示；「上次更新」时间**不前进**；Toast «EPG 网络失败，正在使用旧数据»；连续失败按退避间隔刷新（日志可查）。
2. 恢复有效 URL 手动刷新：徽标/节目更新，`lastEpgUpdateTime` 前进一次，事件 «EPG已更新»。
3. 无缓存且网络失败：Toast «EPG更新失败»，节目单空态显示「EPG 加载失败」（U5 §6）。

---

## 11. S5 · 回看身份与进度：节目快照绑定频道、进度恢复

### 11.1 根因

- `CatchupPlayRequest`（`CatchupHelper.kt:10-17`）已带 `owner: Channel?`（`:16`）和 liveUrl/start/end；`PlaybackIntent.catchup()`（`PlaybackIntent.kt:91-98`）也用 `owner` 校验 `declared.url != catchup.liveUrl → null`（`:93`）。**但**：`PlayerDrawer.kt:1121-1129` 构造 request 时 `owner = ch`，`ch = epgDataChannel`（`PlayerDrawer.kt:642-645`）= `stableFocusedChannelUrl ?: selectedChannelUrl` 解析出的频道——这是**浏览焦点**，不是「当前点击那一行节目所属的频道」；若 EPG 数据是异步的（`produceState :647-690`），焦点从 A 快速移到 B 时 `epgDataChannel` 可能仍是 A → 用 A 的节目时段 + B 的地址生成回看 URL。`CatchupHelper.buildCatchupUrl` 的 `liveUrl` 来自 `ch.url`（`:1113`），确实混用。
- **进度丢失**：`PlayerActivity.kt:961-973` 的 `LaunchedEffect(catchupRequest, player)` 只**读** `p.currentPosition` 到 `catchupPositionMs`（`:970`），从不写回；`setOnCatchupSeek`（`:1439-1450`）更新内存值但无持久化；切 HOME 再返回时 `catchupRequest` 作为 Compose remember 状态留在进程内（Activity 未重建时进度还在，但如果系统回收/重建 Activity，`catchupRequest=null` → 回看身份和进度全丢）。`START_POSITION_MS` 之类的 Media3 参数也没在 `prepareRequest` 中使用。

### 11.2 修复方案

1. **节目快照绑定频道**：`PlayerDrawer.kt:1105-1130` 的点击处理改为用**当前行的频道**而非 `epgDataChannel`：`val ch = focusedChannel ?: epgDataChannel`（`focusedChannel` 在 `:637-640` 由 `focusedChannelUrl` 解析，与点击行一致）；更稳的做法是把 `DrawerProgramItem` 的 onClick lambda 直接捕获 `epgDataChannel.url` 并断言 `p.channelId` 与该行 `p.channelId` 匹配（EpgProgram 有 channelId），不匹配就不生成 URL。
2. `CatchupPlayRequest` 增加 `val fingerprint: String` = `"${owner?.id}|${liveUrl}|${startMillis}|${endMillis}"`；`PlaybackIntent.catchup()`（`:91-98`）校验 `retained` 的 fingerprint 一致才能复用，否则新建 request。
3. **进度恢复**：`CatchupPlayRequest` 增加 `resumePositionMs: Long = 0`；`PlayerActivity` 在发起回看时记录 `recentCatchupResume: Map<fingerprint, Long>`（会话内，`remember`）；`prepareRequest` 里对 `request.isCatchup && resumePositionMs>0` 设置 `MediaItem.Builder().setStartPositionMs(resume)`；`setOnCatchupSeek`（`:1447-1448`）写回 map；`onReturnToLive`（`:1451-1463`）清除该 fingerprint 的进度。HOME→返回（Activity 未重建）直接保留；若重建，从 `Prefs`/`savedState` 按 fingerprint 恢复（可选持久化键 `last_catchup_resume`）。
4. **只恢复同一请求**：恢复前校验当前 `catchupRequest.fingerprint == saved.fingerprint`，不一致（换台/换节目）丢弃旧位置，避免把 A 的进度套到 B。

### 11.3 可测行为用例

- `PlaybackIntentRegressionTest` 扩充：`catchup(channels, reqA, retainedB)` 不复用 B 的 MediaItem；fingerprint 相同才复用；`owner.url != liveUrl` 返回 null。
- 新 `CatchupIdentityTest`（纯 JVM）：`fingerprint` 对 A/B 不同；`resumeFor(fingerprint, map)` 一致才返回值；`onReturnToLive` 清除。
- 静态门禁：`PlayerDrawer.kt` 的 `onPlayProgram` lambda 不得直接用 `epgDataChannel` 作为 `owner` 而不校验 program.channelId；`prepareRequest` 必须含 `setStartPositionMs`（或其封装）。

### 11.4 真机验收

1. 快速浏览 A→B（两频道都有 EPG 历史节目），在 B 的历史节目按 OK：回看 URL 与 `logcat CatchupHelper Filled template` 显示的是 B 的直播地址和时间，**不是** A 的时段。
2. 可 seek 的回看节目拖到 30min，按 HOME 再回播放器：进度继续在 30min（`catchupPositionMs` 与画面一致）。
3. 回看 A → 返回直播 → 立即回看 B：不沿用 A 进度；B 从头播。
4. 回看中信息卡显示 B 的节目名/起止/进度（§4/§6），不显示直播 EPG 的节目。

---

## 12. U4 · 换台终态（对接 §8 契约）

### 12.1 根因

`ChannelSwitchController`（`ChannelSwitchLoading.kt:33-54`）：`show()` 增 sessionId 并置 visible，`hide()` 置 false。`BindChannelSwitchLoading`（`:61-93`）：`STATE_READY / STATE_ENDED`（`:71-73`）、`onPlayerError`（`:76-80`）、12s 定时（`:87-92`）**三者都调 hide()**，且没有 generation/mediaId 校验；错误后没有「重试 / 选其他线路」入口；`ChannelSwitchOverlay` 只有固定 `message = "加载中..."`（`:99`），不显示目标台名或首帧/失败态。这就是「READY、错误、超时都可能清掉同一加载提示」「坏流重试耗尽后可重试或选其他线路」两个验收失败点。

### 12.2 修复方案（对接 S2 契约）

1. `ChannelSwitchController.show(channelTitle, generation, mediaId)`（`:40-43`）记录目标台名/代际，`hide(reason)` 只接受来自 `PlaybackRecoveryPolicy` 的终态事件；旧 generation 的 hide 被忽略。
2. `BindChannelSwitchLoading` 改为消费 `PlaybackSessionState`（§8）而不是自己监听 Player：`phase==READY → hide("firstFrame")`；`phase==TERMINAL → hide("giveUp")` 并让 overlay 显示失败态；**12s 兜底改为 `onTimeout` 通知 Policy**（不是直接 hide）。保留一个更长的硬看门狗（如 20s）防 Policy 卡死，但带 generation 校验。
3. `ChannelSwitchOverlay`（`:95-130`）新增 `state: PlaybackSessionState?`：加载中显示 `正在播放 {channelTitle}` + spinner；失败终态显示 `{channelTitle} 暂不可播` + 两个可聚焦按钮/提示「OK 重试 · ← 选择其他线路」（复用 `playGroupVariant` 与 `QualitySelectorDialog`，按键在 `dispatchKeyEvent` 的 error 态分支消费）。
4. 目标台名从 `PlaybackIntent.request.channel.title`（`PlaybackIntent.kt:26`）取，不再从浏览焦点取（与 U5 一致）。
5. 继续浏览时按键可用：终态 UI 不占用全局守卫（`isDrawerOpen/...` 不变），LEFT 打开频道列表、OK 重试，不吞掉方向键。

### 12.3 可测行为用例

- `ChannelSwitchControllerTest`（新，纯 JVM）：旧 generation 的 hide 被忽略；READY/ERROR/TIMEOUT 分别映射为不同 reason；12s/20s 兜底不误清新代。
- 静态门禁：`BindChannelSwitchLoading` 不得含裸 `controller.hide()`（须带 reason + generation）；`ChannelSwitchOverlay` 必须含失败态文案。

### 12.4 真机验收

1. 正常换台：加载提示显示目标台名，真实首帧后消失（一次）。
2. 坏流：重试 2 次后显示「暂不可播」+ 重试/选线路入口；按 OK 重试、按 LEFT 选台可用；连续 DOWN 浏览不卡键。
3. A→B 快切：A 的 READY/ERROR/超时事件不隐藏 B 的加载提示（generation 校验）。

---

## 13. U2 · 线路测速抢焦点；U3 · 回看提示；第三批性能项

### 13.1 U2 · 线路测速抢焦点

- **根因**：`QualitySelectorDialog.kt:66-71` 的焦点初始化 `LaunchedEffect(visible, variants)` 把整个 `variants` 列表作为 key；而列表内容由 `PlayerActivity.kt:1572-1585` 从 `measuredLatencyByUrl/probingUrls` 派生，测速结果返回会生成**新的 `variants` 实例** → effect 重跑 → `itemRequesters[selectedIndex].requestFocus()`（`:69`）把用户刚移到备用行的焦点抢回选中行。
- **修复**：把 key 改为 `LaunchedEffect(visible)`（只在打开时初始化一次），或引入独立布尔 `focusInitialized`；测速状态更新只改 `SourceOptionItem` 的 `latencyText`（`:125-129`），不得重跑焦点请求。三态文案已在 `:125-129`（测速中/超时/ms），保留。
- **用例**：`QualitySelectorTest`（若引入 Compose 测试）：打开后 DOWN 到备选行 → 注入 variants 更新 → `focused="true"` 仍在备选行；OK 切该线路。
- **真机**：打开线路选择、DOWN 到备用行等待测速结果返回 → 焦点不跳；OK 切到备用线路播放。

### 13.2 U3 · 回看提示与长按 Back

- **根因**：`ChannelInfoBanner.EntryHint()`（`:371-381`）固定显示 `InfoCardPolicy.ENTRY_HINT = "← 频道列表 · → 设置"`（`InfoCardPolicy.kt:260`），回看时 LEFT/RIGHT 实际是 seek（`PlayerActivity.kt:355-369`）→ 提示与实际按键不符；无明确「返回直播」入口提示。`dispatchKeyEvent` 的 BACK 双按分支（`:413-425`）用 `System.currentTimeMillis() - lastBackPressTimeCatchup > 2000` 判定，只按时间窗口，**未检查 `event.repeatCount == 0`** → 长按一次 Back 的 repeat 事件在 2s 内可再触发「返回直播」分支（等价于双按）。
- **修复**：① `EntryHint` 接受 `catchup: Boolean`，回看时显示 `← 快退 · → 快进 · BACK×2 返回直播`；`InfoCardPolicy.entryHint(catchup)` 纯函数 + 测试。② `PlayerActivity.kt:413` 分支加 `event.repeatCount == 0` 守卫（与换台/数字键 `:277/332` 一致）；长按只记一次时间。
- **用例**：`InfoCardPolicyTest.entryHint(true/false)`；静态门禁检查 catchup BACK 分支含 `repeatCount == 0`。
- **真机**：回看中提示文案正确；长按一次 BACK 只出「再按一次返回直播」Toast，不直接返回；松开后再按一次才返回直播；LEFT/RIGHT 仍按 10/30/60/300s seek。

### 13.3 第三批 · 性能与信息层级（优先级低于上述项）

按 HANDOFF §28–34，逐项给出定位与单变量方案（不做实现，待排期）：

| 项 | 文件/函数/行号 | 根因 | 单变量方案 | 可测用例 / 真机 |
|---|---|---|---|---|
| 换台首帧 | `PlayerActivity.playGroupAuto :1339-1400`、`SourceLatencyProber.probe :67-84` | 常规路径串行探测每个 variant（`:1362-1387`）；`probe` 的 2s 超时不支持 cancellation 传到 OkHttp call | 用选定/最近成功线路直接试播（`PrefixPolicy` 纯函数：lastSuccessUrl → selectedIndex）；串行探测移到失败后，共用 S2 总预算；`probe(url, cancelSignal)` 把取消传到 `Call.cancel()` | 测 p50/p95/失败率；真机 zap≥30 对比 TTFF（基线 p50 923ms） |
| 列表与冷启 | `PlayerActivity.parsePlaylist :975-977`、`applyPlaylistContent :1003-1008`、`BackgroundSourceUpdater.refreshPlaylist :44-74` | 同内容重复解析/写盘/发布；M3U 下载无体积上限 | `PlaylistCache` 记录 contentHash，内容相同跳过写盘与 `commitBackground`；下载 `body.contentLength()` 上限 10MB（EPG 已有 32MB 上限 `EpgRepository.kt:58`） | 同 URL 二次刷新磁盘 mtime 不变；真机 A/B 冷启重缓冲次数 |
| 抽屉派生数据 | `PlayerActivity :1172-1226`、`PlayerDrawer epgChannelUiCache :187-193` | 分组索引/展示行随每次 EPG 更新全量重建；`nowProgramByUrl` 每 30s 全量重算 | `remember(epgData, channelGroups)` 一次构建索引；EPG 状态更新只变 affected channel key（配合 §6 的全组改法） | 真机开抽屉/连续滚动 p95（CI10204 150ms 基线）不劣化 |
| 信息卡/设置/首次配置 | `ChannelInfoBanner :188-308`、`SettingsIA.kt`、`MainActivity.kt:223-251` | 自动换台展示完整卡片（信息层级过密）；高级诊断混在播放页；扫码缺同网指引/可重试错误 | 自动换台用简洁条（隐藏徽标/技术行，仅 logo+台名+节目）、主动 OK 出完整卡；实验项收纳独立页；扫码错误态带重试按钮（`SourceConfigLanServer` 返回真实结果，见 §9/S6） | 真机自动换台卡片简洁、OK 展开完整；扫码失败有可见错误与重试 |
| 「全部」分组 | `ChannelGrouper.kt:173`（HANDOFF :34） | 内置的「全部」入口与真实同名分组混淆 | 用 separate sentinel key（如 `__ALL__`）区分；`displayGroupName` 保留展示文案 | `ChannelGrouperTest`：真实名为「全部」的组与内置入口同时存在时行为不同 |
| 起播档位提示 | `PlayerActivity :1797-1805`、`LoadControlTuning` | Toast 说「下次切台生效」但实际生效点可能不同 | 把提示文案与实际生效点统一（或直接立即 `setPlaybackParameters`/重建）；单变量 | 真机切换档位后立即/下次生效与提示一致 |
| 组播预热 | `MulticastPrewarm.kt:55`、`PlayerActivity :1087-1095` | socket 超时/取消不能真正结束任务 | 先让 `warmNeighbour` 的 socket 关闭 + 取消生效，再测收益；默认保持关闭 | 真机开启后取消立即停止（线程/日志），无残留连接 |

### 13.4 S6 · 扫码推源（配对 / 校验 / 真实反馈）

- **根因**：`SourceConfigLanServer.kt:89-96` 收到 `/api/push` 后立即 `onPush(live,epg)` 并回 `{ok:true}`；`MainActivity.kt:223-235` 的 `onPush` 只改 Compose 状态、Toast「已从手机推送更新」、启动 `loadAndPlay`，**响应早于实际提交**；无会话校验（任何同网设备可 POST）、无 URL 合法性检查（`:91-92` 直接 `optString`），失败时 `loadAndPlay` 的 Toast 是异步的、与 HTTP 响应脱节。
- **修复**：① `SourceConfigLanServer.start()` 生成 6 位短时配对码（如 5min 有效）并显示在二维码页，`/api/push` 必须带 `code` 且未过期（服务端 `InternalServer :73-100` 校验，失败回 401/403）；② `parsePayload`（`:102-117`）校验 URL scheme=http/https、长度上限，拒空白；③ `onPush` 改为 `suspend (live,epg) -> PushResult`，HTTP handler **等待** `loadAndPlay` 的真正结果（成功/下载失败/保存失败）后再回 `{ok:true|false, reason}`；④ 前端 `SourceConfigWebPage` 展示真实反馈与重试按钮；⑤ 复用 S3 的 `SourceRepository`/版本守卫（§9），不绕过事务。
- **用例**：`SourceConfigLanServerTest`（可注入 fake onPush）：无效/过期/缺失 code → 401/403 且不调用 onPush；非法 URL → 400；fake onPush 延迟返回失败 → HTTP 体含 `ok:false` 与 reason。
- **真机**：同网手机扫码→输入配对码推送有效源→配置页显示「已保存并开始播放」；错误码推送不改变配置；断网/坏源推送显示清楚错误并可重试；过期码被拒绝。

---

## 14. 实现前通用锚点复核命令（Windows PowerShell）

```powershell
# 关键符号是否存在、行号是否漂移
Select-String -Path android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt -Pattern `
  'initialScrollIndex|focusTargetIndex|focusedChannelUrl|DRAWER_ROW_HEIGHT_DP|graphicsLayer|drawerSafeOpenTarget|ThinProgressBar|selected: Boolean|onPlayProgram|epgDataChannel'
Select-String -Path android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt -Pattern `
  'dispatchKeyEvent|playbackErrorEvents|playbackIntent.recover|nowProgramByUrl|ChannelInfoBanner\(|onSwitchRecent|repeatCount|calculateSeekStep|onPlayProgram'
Select-String -Path android/app/src/tivimini/java/com/cjlhll/iptv/ChannelInfoBanner.kt -Pattern `
  'smallMetaLine|badgeCandidates|stripFocusIndex < 0|onSwitchRecent\(index\)|padding\(start = 40.dp|RecentStrip|ENTRY_HINT'
Select-String -Path android/app/src/tivimini/java/com/cjlhll/iptv/RemoteHabits.kt,android/app/src/tivimini/java/com/cjlhll/iptv/SettingsDrawer.kt -Pattern `
  'enum class UpDown|settings_remote_updown|SettingsRemotePage|onCycleRemote'
git -C iqtvlite log --oneline -6   # 确认 HEAD 与基线
```

---

## 15. 排期与依赖建议（给 captain）

1. **先做第一批**（§1–§7），t3/t4/t5 可并行但注意：`PlayerDrawer.kt` 被 §1/§2/§3/§6 同时触碰 → 由**一个实现者顺序完成**或独立工作树；`ChannelInfoBanner.kt` 被 §4/§5 触碰 → 同一个实现者；`RemoteHabits/SettingsIA/SettingsDrawer/PlayerActivity` 被 §7 触碰 → 一个实现者。
2. **S2 契约（§8）必须先定**，U4（§12）对接；两者都碰 `PlayerActivity.kt`，顺序交接。
3. S3（§9）/ S4（§10）/ S6（§13.4）相对独立（SourceRepository / AutoUpdateScheduler / LanServer），可并行。
4. S5（§11）碰 `PlayerDrawer.kt` + `PlayerActivity.kt`，须在 §1/§2/§6 完成后再做。
5. 每项交付：改动的精确文件+函数、JVM 用例、静态门禁命令与结果；**真机步骤由 acceptance 统一执行**，逐项贴 dump / 日志 / gfxinfo / Toast 证据。
6. 用户明确要求「全部修复 + 真机测试」：S1 只静态验证不够，所有 §条目必须走一遍对应真机验收（至少 §1/§2/§3/§5/§7 + S2/U4 的关键路径）。
