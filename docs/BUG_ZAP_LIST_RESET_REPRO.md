# BUG 复现报告：点击台换台后选台列表跳到开头

- 时间：2026-11-22 21:53–22:13（Asia/Hong_Kong）
- 执行：acceptance（t14 attempt 1）
- 设备：TR100-G9 / `192.168.2.245:5555`，装 **vc10213**（最终包，未重装、未 force-stop、未改 prefs）
- 全程只读复现：`setprop log.tag.IQTV-PERF DEBUG`（收尾已复位空）；证据 `setprop` 外无写操作
- 判据：uiautomator dump 正则 `text="N. ` 取可见行 first/last；`logcat -s IQTV-PERF:D` 取 `drawer_open` / `drawer_compose target` / `row_focus idx`
- 截图/日志：`docs/evidence/t7-fullfix/bug-zap-reset-first1.png`、`bug-zap-reset.log`

---

## 0. 结论：**已复现**（用户反馈成立）

复现到的核心症状：**抽屉焦点在一个很深的行（idx 126），但可见行是 1–10（first=1），即列表停在开头、焦点与可见区域脱节**。日志同时显示 `drawer_compose target=147`（目标窗口覆盖到底）、`row_focus idx=126`（焦点在 126），但 dump 可见行始终 `1..10` —— 与用户说的「点击台换台后列表跳到开头」一致。

更重要的是发现一个**稳定 100% 复现**的入口：**每次冷启动 App 后第一次打开抽屉，列表都停在 first=1，而焦点已落在很深的行**（见 §2 P8/R3/最终复现三次一致）。这是「回开头」的高可信根因线索。

- 打开抽屉后用户能触发换台的 6 条路径里，**1 条稳定复现（冷启首开）+ 1 条换台后复现（P8b）**；其余路径（同会话内重开）多数保持位置，说明 bug 与 **抽屉/列表状态的首次初始化 + 换台导致的选中行变化时序**相关，而非简单的「每次打开都回开头」。

---

## 1. 环境与取证方法

- 设备在线、vc10213、前台 PlayerActivity；`log.tag.IQTV-PERF=DEBUG`。
- 每次操作前 `logcat -c`，操作后 `uiautomator dump` 取可见行 + `logcat -d -s IQTV-PERF:D` 取埋点。
- 可见行集合用 `text="N. …"` 正则（频道行标签格式），播放台行带 `▶ N.` 前缀。

---

## 2. 各路径对照表（操作 → 可见行 first/last → target）

| 路径 | 操作 | 换台前可见 | 换台后/重开可见 | 关键日志 | 回开头? |
|---|---|---|---|---|---|
| **P1** 基线开抽屉 | 冷会话内 LEFT 开 | — | first=10 last=17 | target=37 group=全部 | 否（焦点在播放台16附近） |
| **P2** 同组 OK(23) 换台 | 开→DOWN×15→idx28→OK23→重开 | 23–32 | **23–32** playing=28 | row_focus idx=28；drawer_open 无 target 行 | 否 |
| **P3** 同组 ENTER(66) 换台 | 开→DOWN×12→idx40→ENTER66→重开 | 22–31 | **31–39** playing=40 | row_focus idx=40；drawer_open target 未打 | 否 |
| **P4b** 跨组换台 | 播放厦门卫视(卫视组)→浏览切到体育组→DOWN→idx85→OK→重开 | 83–87 | **83–87** playing=85 | target=5 group=体育；row_focus idx=85 | 否 |
| **P5** 同组 TAP 点击换台 | 开→tap -row84→重开 | 83–87 | **83–87** playing=84 | target=5 group=体育；row_focus idx=84 | 否（抽屉保持打开时也不跳） |
| **P6** 频道键 167×3 换台 | 抽屉关→167×3→开 | — | **83–86** playing=87 | target=5 group=体育；row_focus idx=84 | 否 |
| **P7** 全部组深行 TAP + 保持打开 | 全部组→focus idx60→tap row63→(保持开)→重开 | 58–67 | 保持开时 **58–67**；重开 **56–65** | tap 后 drawer_open group=全部；重开 target=90 row_focus idx=63 | 否 |
| **P8** 冷启首开 | monkey 冷启→开抽屉 | — | **first=1 last=10** | target=90；**row_focus idx=63（焦点在63但可见1–10）** | **✅ 焦点/可见脱节** |
| **P8b** 冷启→换台→重开 | 冷启首开(1–10)→DOWN×10→idx73→tap 凤凰中文台→关→重开 | 71–80 | **first=1 last=10**（playing=63 仍未变？） | **target=100**；row_focus 无(idx脱节) | **✅ 回开头** |
| **R2** 会话内换台重开 | 全部组→focus idx102→tap 城市剧场→关→重开 | 99–108 | **58–67** playing=104 | target=131；row_focus idx=104 | 否 |
| **R3** 冷启首开复测 | 冷启→首开(1–10)→DOWN×25→idx129→tap 美人→关→重开 | 121–130 | **58–67** playing=126 | target=147；row_focus idx=126 | 否（重开恢复） |
| **最终复现** | 冷启→首开 | — | **first=1 last=10**，焦点 idx=126 | target=147；**row_focus idx=126**；frames=72 revealed=147 | **✅ 焦点/可见脱节** |

---

## 3. 复现详情（关键证据）

### 3.1 稳定复现：冷启首开 first=1 + 焦点在深行（P8 / 最终复现，3 次一致）
```
# 冷启动后第一次打开抽屉（playing=美人,  idx126；playing=凤凰中文台 idx63 时也一致）
IQTV-PERF: drawer_compose target=147 first=3 step=2 rows=147
IQTV-PERF: drawer_open ms=99 total=147 composed=3 revealed=3 first=3 groups=14 group=全部
IQTV-PERF: row_focus idx=126 subtitle=false title=
IQTV-PERF: drawer_compose frames=72 rows=147 target=147 ms=1797 lastFrameRows=2 first=3 step=2
```
- uiautomator dump 可见行：`1. CCTV-1 综合 … 10. CCTV-10 科教`（**first=1**）
- 但焦点埋点 `row_focus idx=126`，target=147 —— **焦点在 126，列表却在 1–10**。
- 截图 `bug-zap-reset-first1.png`、日志 `bug-zap-reset.log`。

### 3.2 换台后回开头（P8b）
```
# 冷启首开(1-10) → DOWN×10 到 idx73 → tap 凤凰中文台 → 关 → 重开
IQTV-PERF: drawer_compose target=100 first=3 step=2 rows=147
IQTV-PERF: drawer_open ms=136 total=147 composed=11 revealed=88 first=3 groups=14 group=全部
# dump: first=1 last=10（playing=63 凤凰中文台）
```
- 换台前列表已滚到 71–80，换台重开后回到 **1–10**，`target=100` 未生效到可见区。

### 3.3 对照：多数会话内路径正常（P2/P3/P5/P7/R2/R3）
- 同组 OK(23)/ENTER(66)/TAP、跨组换台、频道键换台后重开，可见行基本保持在焦点附近（如 P7 重开 first=56 焦点63、R2 first=58 焦点104、R3 first=58 焦点126）。
- 说明抽屉 `channelListState` 在会话内会记住滚动位置；问题集中在**首次初始化**与**换台后某些时序**。

---

## 4. 代码定位（供开发参考，未改代码）

关键逻辑在 `PlayerDrawer.kt`：

1. `PlayerDrawer.kt:341` — `openFocusUrl = remember(visible) { if (visible) initialFocusUrl else null }`：开合翻转时冻结恢复焦点，来源 `PlayerActivity.kt:1673` `sidebarController.lastFocusedUrlFor(selectedGroup)`。冷启时 `lastFocusedByGroup` 为空（会话内存、不持久化）→ `openFocusUrl=null`。

2. `PlayerDrawer.kt:519-549` — `LaunchedEffect(visible)` 中的首次聚焦块受 `if (!hasInitializedDrawerFocus)` **一次性**保护：
   - `targetUrl = openFocusUrl ?: selectedChannelUrl ?: first`（冷启时退化为播放台，深 idx）
   - `val visibleIndices = channelListState.layoutInfo.visibleItemsInfo.map{it.index}.toSet()`
   - `if (targetIndex !in visibleIndices) channelListState.scrollToItem(targetScroll)` —— **第 545 行**。

3. **疑似根因**：这次 scrollToItem 发生在抽屉打开的**首帧**，`revealedRows` 还是 `drawerFirstRows=3`（日志 `revealed=3 first=3`），LazyColumn 此刻只有 3 行可滚动；`scrollToItem(124)` 落到 3 行的列表上**无法到达目标（被钳到 0）**。之后分帧泵才把行数补到 147（`frames=72 revealed=147`），但**没有再次按 targetScroll 校正滚动**，于是列表停在首行 first=1，而焦点请求仍在 idx126 → 焦点与可见区脱节。

4. `PlayerDrawer.kt:357-373` 的 `LaunchedEffect(visible, channels.size, initialScrollIndex)` 只更新 `revealedRows/revealTarget`（开窗），**不对 `channelListState` 做实际 scrollToItem**；真正的一次性 scroll 只在上面 545 行。分帧揭示完成后缺一次"按最终 target 重新定位滚动"的补偿。

**建议修复方向**（供开发，不在本任务实施）：分帧揭示推进到覆盖 `targetScroll` 后，补一次 `channelListState.scrollToItem(targetScroll)`（或 `animateScrollToItem`），把"滚动定位"与"行数揭示"解耦；不要在只有 3 行时就消费掉唯一的 scrollToItem。另需覆盖 `hasInitializedDrawerFocus` 已为 true 但换台导致 `selectedChannelUrl` 变为深行的场景（P8b）。

---

## 5. 无法复现 / 需注意

- **「每次换台都回开头」未复现**：会话内绝大多数换台重开都保持位置（P2/P3/P5/P6/P7/R2/R3 共 8 条）。
- **跨组换台**按用户描述的严格路径（切组→焦点中间→点台→重开）试过（P4b），未复现回开头。
- 复现条件更像是：**冷启动后首开**（稳定）与**冷启首开之后紧接一次换台**（P8b，1 次复现，R3 同序列未复现，存在时序偶发性）。若用户是在"杀进程/冷启后第一次点台"触发，与本报告一致。
- 全程只读：未装包、未 force-stop、未改 prefs；仅 `setprop log.tag.IQTV-PERF DEBUG` 且收尾已复位为空。

---

## 6. 交付判定

- 用户反馈的「列表跳到开头」**已复现**（冷启首开 first=1 + 焦点深行脱节；P8b 换台后 first=1）。
- 已给出精确代码定位（`PlayerDrawer.kt:545` 一次性 scroll 在 3 行时消费 + `:357-373` 缺揭示后补偿滚动）与修复方向。
- 证据：截图 1 张 + logcat 1 份 + 本报告 12 条路径对照。
