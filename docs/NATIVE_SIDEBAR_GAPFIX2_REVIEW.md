# 633dp 叠加列 / 返回保焦点 / 显式左右 独立复查（t70 / run84）

任务 t71（attempt 1），designer2，2026-11-20（Asia/Hong_Kong）。**结论：needs_revision。** t70 已把基座改为固定 633dp、把分组/日期改为覆盖层、加入每列 lastPositions 焦点恢复、显式 LEFT/RIGHT 与 catchup 失败 Toast；但**日期覆盖层用了全屏根 FrameLayout 的 `Gravity.END`，会落到屏幕最右侧、悬在视频上且没有面板背景**（不是 633dp 基座的右缘 513–633dp），GAP-01 的“日期 120dp 覆盖层”未正确落位；另有**分组/日期筛选后 lastPositions 过期导致回焦错位**、**RIGHT from Groups 未显式处理**。修复后复审。

> 本复查只读固定 commit `fdb20978`（工作树 clean）。模型不能读图，布局结论仅基于源码/dp/布局参数。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`，分支 `integration/tivimini-baseline`；HEAD=`15fe434`（docs-only），工作树 clean；`fdb20978..HEAD` 无代码改动。
- 被审查 commit `fdb20978bee7ec625f9bc9f683a8e18473929f14`（t70），2 文件 +50/−30。
- 读取内容身份（git blob @fdb20978）：

| 文件 | blob |
|---|---|
| [NativeSidebarView.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt) | `3602eb3002e3abb0838047768f73a455af3c80e2` |
| [PlayerActivity.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt) | `fc4fb9ad5f2898b8ad270eaa708aab1816cdbe2c` |

- 候选：主 10,208,256B SHA256 `b14b5ac5…2e19`，test 502,955B `058b8dfc…9f87`，签名 `046ebaeb…4ee2`，run 44677560151(run84)，10084。未真机验证。

## 2. GAP-01 列宽/叠加 — 基座正确，**日期覆盖层落位错误**（blocker）

- **基座正确**：[NativeSidebarView:183–185](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L183) `base` 固定 `dp(633)`，内含 channels `dp(300)` + programs `dp(300)`（余 33dp），`#E6161B22` 面板底；`dp()` 密度换算正确。显示/隐藏覆盖层不移动 base。
- **分组覆盖正确**：`groupsColumn` `LayoutParams(dp(151), MATCH_PARENT, Gravity.START)` 加到根 FrameLayout → x=0..151dp，叠在 base 左缘之上（base 背景在其后，可读）。
- **日期覆盖错误**：`datesColumn` `LayoutParams(dp(120), MATCH_PARENT, Gravity.END)` 加到**根 FrameLayout**（NativeSidebarView 本身在 PlayerActivity 以 `MATCH_PARENT/MATCH_PARENT` 加入，[PlayerActivity:152–153](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L152)）。`Gravity.END` 相对**全屏**根，故日期列落在**屏幕最右侧 x=screenW−120dp**，而不是 633dp 基座右缘的 x=513..633dp；且 `column()` 不给列设背景，根透明 → **日期列透明地悬在视频上**，文字/行直接压在视频画面上，既不是“叠加在基座”，也影响可读性。任务要求“日期120为覆盖层、显示隐藏不移动基座/不挤压视频”，当前实现是“覆盖视频右缘”。
- **修复**：把 base+覆盖层放进一个 **633dp 宽的 FrameLayout 容器**（或 `datesColumn` 用 `Gravity.START` + `leftMargin = 633−120 = 513dp`），使其右缘对齐基座；并给覆盖列自身 `setBackgroundColor(0xE6161B22)`（或确保在 633dp 面板底之上）以保证可读。分组列同理建议加背景以防未来基座变化。

## 3. GAP-02 返回保焦点 — 基本实现，但筛选后会错位

- `onRowFocused(column,row,position)` 用 `holder.bindingAdapterPosition` 记录 `lastPositions[column]`（`position>=0` 守卫）；`focusColumn(rv,column)` 用 `lastPositions[column] ?: defaultPosition(column)`，`coerceIn` 防越界，`scrollToPosition + requestFocus`。**未筛选时** LEFT Programs→Channels、RIGHT Channels→Programs 能回到上次浏览行；`defaultPosition(Channels)` 回退 `focusedChannelUrl ?: selectedChannelUrl` 对应索引。符合 GAP-02 主路径。
- **缺陷**：`lastPositions` 在**分组筛选/日期切换后不重置**。选组后 channels 列表变化，`lastPositions[Channels]` 仍指旧索引；LEFT 从分组回频道会聚焦到**新列表的同一序号（可能是别的台）**。选日期后 programs 列表变化，LEFT 从日期回节目同样错位。**修复**：`selectGroup` 后清 `lastPositions[Channels]`（或按 `focusedChannelUrl` 解析）；`selectEpgDate` 后清 `lastPositions[Programs]`（或按当前时间/选中节目解析）；`coerceIn` 只防崩溃不防错位。
- **次要**：`lastPositions` 跨播放列表刷新（后台更新）也可能过期；建议以稳定身份（channel url / program start）为主、index 为辅。

## 4. GAP-03 显式左右 — 主路径已显式，RIGHT from Groups 缺失

- [PlayerActivity:257–267](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L257) drawerOpen 时：
  - LEFT Channels→`setShowGroups(true)+focusGroups`；LEFT Groups→隐藏+`focusChannels`；**LEFT Programs→`focusChannels`（新增）**；LEFT Dates→隐藏+`focusPrograms`。
  - **RIGHT Channels→`focusPrograms`（新增）**；RIGHT Programs→`setShowDates(true)+focusDates`。
  - 均 `return true`；未处理分支落 `super`。主路径 TIV-05/08 已显式。
- **缺口**：**RIGHT from Groups 未显式**（`else` 落 super，依赖隐式焦点搜索右移到 channels）。分组覆盖层在 x0..151，channels 在 x0..300 的 base 上，原生焦点搜索通常能右移，但这是 TIV-05 的分组退出路径，建议显式 `Groups -> focusChannels()`。同理 **RIGHT from Dates** 无目标可去（可保留 else）。
- **GAP-04**：`focusColumn` 用 `postDelayed(30)` 等覆盖层布局后再聚焦；配合显式 LEFT/RIGHT 合理。首次显示分组/日期时若 30ms 内仍未 VISIBLE，可能需第二次按键；建议 post 到 `visibility` 变更后的下一帧或 `doOnLayout`（低风险，真机验证）。
- **GAP-08**：catchup `bound==null` 已 Toast「该节目暂不可回看」，不再静默。符合。

## 5. 保留项 — 未回退

- 视觉 v2：焦点无边框 `#14FFFFFF`+文字 `#F2F5FA`/BOLD、选中 2dp `#4EA8FF`+▶、NOW 2dp `#F5A524`、方角、无 border/shadow/elevation/blur。
- DiffUtil 适配器、EPG 移出 `SidebarState` equality（`epgVersion`）、根透明（仅 base `#E6161B22`）、回看 owner B（id/UA/Referer）、日期分层（位置见 §2 问题）、350ms 防抖。
- 零拷贝基座/灰屏、LoadControl `1000/6000/300/1000`、频道键 166/167/92/93、稳定 key、SR、JNI/R8/UA、硬解 OMX.goke 未改；APK 10,208,256B ≤ 30MB。

## 6. 放行结论与门禁

- **源码结论：needs_revision**。GAP-01 的**日期覆盖层落位/背景**是主要缺陷（视觉与“叠加在基座”语义不符）；GAP-02 筛选后回焦错位、GAP-03 RIGHT from Groups 为次要。修复后复审。
- 修复后 acceptance2 在 exact pair（主 `b14b5ac5…`/test `058b8dfc…`/10084）验证：633dp 基座 + 分组/日期覆盖**不移动基座、不覆盖视频**；LEFT/RIGHT 返回保位置（含分组/日期筛选后）；TIV-05/06/08/09/15；`sidebar_jank_probe.sh` 对照 CI10078；视频 dropped0；冷启/CPU/PSS；品牌。

## 7. 缺陷与可执行修复

| id | 严重度 | 问题 | 修复 |
|---|---|---|---|
| GAP2-01 | **blocker** | 日期覆盖层用全屏根 FrameLayout 的 `Gravity.END` → 落在屏幕最右、悬在视频上且无面板背景；不是 633dp 基座右缘的 513..633dp | 用 633dp FrameLayout 容器承载 base+覆盖层，dates 右缘对齐基座（`START`+`leftMargin=513dp` 或容器内 `END`）；给覆盖列设 `#E6161B22` 背景 |
| GAP2-02 | medium | 分组/日期筛选后 `lastPositions` 未重置，LEFT 回频道/节目聚焦到新列表同序号（错台/错节目） | `selectGroup` 清 `lastPositions[Channels]`（或按 focusedChannelUrl 解析）；`selectEpgDate` 清 `lastPositions[Programs]`（或按当前时间/选中节目解析）；以稳定身份为主 |
| GAP2-03 | medium | RIGHT from Groups 未显式处理，依赖隐式焦点搜索 | 补 `DrawerColumn.Groups -> nativeSidebar?.focusChannels(); return true` |
| GAP2-04 | low | `focusColumn` 固定 `postDelayed(30)`，首次显示覆盖层时若仍未布局完，聚焦可能落空 | 在 `visibility` 变更后的 `doOnLayout`/下一帧再聚焦；设备验证首次 LEFT/RIGHT |
| GAP2-05 | low | 覆盖列自身无背景（groups 靠 base 背景，dates 无） | 覆盖列设 `#E6161B22`，避免未来布局变化后透明 |
| GAP2-06 | low | `pendingInitialFocus` 仍未置真；`requestInitialFocus` fallback 对 `isFocusable=false` 的 RV 无效 | 删除死字段；fallback 前设 `channelsRv.isFocusable=true` |
| GAP2-07 | low | `defaultPosition(Channels)` 每次 `buildChannelRows` O(n) | 仅在无 lastPosition 时调用，可缓存/接受 |

## 8. 只读命令与终态

只读命令：`git rev-parse HEAD`、`git status --short`、`git show --stat fdb20978`、`git show fdb20978`（全 diff）、`git rev-parse fdb20978:<file>`、源码读取（NativeSidebarView init/column/focusColumn/onRowFocused、PlayerActivity dispatchKeyEvent/sidebarView addView）。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 本报告完成后停止读取目标源码；后续提交须新 diff 证据快照。
