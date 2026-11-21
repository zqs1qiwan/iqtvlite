# 原生侧栏缺口修复（回看/列宽/DiffUtil）独立复查（t66 / run83）

任务 t67（attempt 1），designer2，2026-11-20（Asia/Hong_Kong）。**结论：needs_revision。** t63 的两个功能 blocker（节目回看丢失、日期列不可达）与 EPG 深比较性能 blocker 均已修复；但**列布局未达任务要求的 633dp 分层叠加语义**（实际 600/150/110 且为并排增宽），以及**列间焦点回退未保留浏览位置**、右进节目/左回频道依赖隐式焦点搜索。这些是视觉/交互保真问题，不是崩溃；修复后可复审并交 acceptance2。

> 本复查只读固定 commit `b29d5f35`（工作树 clean）。模型不能读图，视觉/布局结论仅基于源码与 dp 规格。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`，分支 `integration/tivimini-baseline`；HEAD=`cdc5fb0`（docs-only），工作树 clean；`b29d5f35..HEAD` 无代码改动。
- 被审查 commit `b29d5f35276fe73e970b62c28802f400f7c0c766`（t66），3 文件 +160/−32。
- 读取内容身份（git blob @b29d5f35）：

| 文件 | blob |
|---|---|
| [NativeSidebarView.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt) | `c34c8e0d62f2fce53c9fb3443648174d5dbaf8e7` |
| [PlayerActivity.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt) | `5dbde1f0f82ac4a9161d44321224a878da626f44` |
| [SidebarController.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/SidebarController.kt) | `6f38cb9787f002356db9744ef0ff47d09efae2a6` |

- 候选：主 10,208,256B SHA256 `65f7df72…122c`，test 502,955B `a7a7eaf3…c7ed`，签名 `c8444481…c42b`，run 44675584770(run83)，10083。未真机验证。

## 2. 节目列 OK 回看（TIV-09/15）— 源码接受

- `SidebarRow` 新增 `startMillis/endMillis/programTitle`；`programsFor` 填充（NativeSidebarView）。
- `playCatchup(row)`：`url = focusedChannelUrl ?: selectedChannelUrl` → 从 `channelGroups` 解析**所属频道 B**；`row.endMillis > now` → Toast「仅历史节目可回看」；`CatchupHelper.buildCatchupUrl(channel.url, channel.catchupMode, channel.catchupSource, start, end)`（签名核对一致，mode/source 取自 owner B）→ 失败 Toast「该频道不支持回看」；成功构造 `CatchupPlayRequest(catchupUrl, liveUrl=B.url, title, start, end, owner=B)`。
- Activity `setOnSidebarCatchup` → `PlaybackIntent.catchup(allChannels, req, playbackIntent.request)` → 非空则 `sourceSelectGeneration++ / cancel job / beginSelection / catchupRequest=req / startPlayback(bound) / sidebarController.closeDrawer()`。**与删除的 Compose `onPlayProgram` 路径等价；owner B 的 id/UA/Referer 随 `owner` 不可变；未改 currentGroupIndex/直播索引。**
- 当前/未来仅提示不转直播——与任务要求一致；无能力明确不可用。**源码接受。**

## 3. 列布局 — 未达任务要求（needs_revision 主因）

任务要求：**633dp 分层语义**（频道 300 + 节目 300 主视图；分组 151 / 日期 120 **叠加**；不要四列并排）。

实际（NativeSidebarView:165–168, 190）：
- 分组 **150dp**、频道 **300dp**、节目 **300dp**、日期 **110dp**（应为 151/300/300/120）。
- 主视图 300+300=**600dp**（应为 633dp）。
- 四个列是水平 `LinearLayout` 的子 View，`GONE` 时不占位、`VISIBLE` 时**增宽并右移频道/节目**（并排），不是“叠加”在 633dp 基座上。分组出现时总宽 750dp、日期出现时 710dp，频道/节目会横向跳动。

→ 双列主视图、按需显示、density 换算（`dp()`）正确；但**633/151/120 与叠加语义未达成**（GAP-01）。建议：基座固定 633dp（300+300+33dp 余量/内边距），分组 151dp 以 FrameLayout **覆盖**在左侧、日期 120dp 覆盖在右侧，显示/隐藏不改变频道/节目位置。

## 4. 分层交互 — 基本可用，焦点回退有缺口

- `openDrawer` 重置 `showGroups/showDates=false`（主视图=频道+节目）。`groupsColumn.visibility` 由 `showGroups` 控制；`datesColumn` 由 `showDates` 控制（t63 的“日期列不可达”已修复）。
- Activity `dispatchKeyEvent`（drawerOpen）：
  - LEFT Channels→`setShowGroups(true)+focusGroups`；LEFT Groups→隐藏+`focusChannels`；LEFT Dates→隐藏+`focusPrograms`；RIGHT Programs→`setShowDates(true)+focusDates`。
  - **RIGHT from Channels（进节目）未显式处理**，依赖原生焦点搜索横向移动；**LEFT from Programs（回频道）同样依赖隐式搜索**。原生 RecyclerView 横向焦点搜索通常可行，但这是 TIV-05 的关键路径，建议显式 `Channels -> focusPrograms()`、`Programs -> focusChannels()`，避免设备差异（GAP-03）。
- **焦点回退未保留浏览位置**：`focusColumn` 用 `findFirstCompletelyVisibleItemPosition` 定位并聚焦**首个可见项**，不是该列上次聚焦项。因此 LEFT 从分组回频道、LEFT 从日期回节目时，焦点可能跳到列表首项，丢失浏览位置（TIV-05/08 的“浏览焦点保留”）。建议每列记住 lastFocusedPosition（或保存 `focusedChannelUrl` 对应 index），`focusColumn` 优先聚焦它（GAP-02）。
- 组/日期 OK 仅筛选：`selectGroup`/`selectEpgDate` 不改 drawerOpen；日期 OK 保持 `showDates=true`。符合。
- 频道 OK 换台、抽屉保持：`activateChannel + onPlayChannel`，并仅 `channelsAdapter.submit(buildChannelRows)` 用 DiffUtil 更新选中标记；`render` 签名排除 `selectedChannelUrl` → 不整列重绑，焦点保留（NS-06 已修）。
- 单 Back 关整抽屉：Activity BACK → `closeDrawer()`（任意列）。符合。

## 5. DiffUtil / EPG 防抖 — 已修

- `RowAdapter.submit`：`old == newRows` 早退；否则 `DiffUtil.calculateDiff`（identity=text+url/groupName/date/startMillis；contents=全等），`dispatchUpdatesTo`；**无 `notifyDataSetChanged`**。focus-only 变化不进 rows → 不重绑。符合。
- 350ms 防抖：`onRowFocused(Channels)` → `focusChannel` + `handler.postDelayed(350){ renderProgramsOnly() }`；`renderProgramsOnly` 只提交节目/日期。符合。
- **EPG 移出 StateFlow equality**：`SidebarState.epgVersion:Int`；controller `@Volatile epgDataRef` + `epgData()`；`setEpgData` 只 bump version；`render` 签名用 `epgVersion`。焦点更新不再深比较 EPG。符合（NS-03 已修）。
- 残留（低）：节目/日期排序仍在 350ms 主线程回调内（列表小，可接受；建议后续后台化）；DiffUtil identity 对节目用 `text+startMillis`，同标题同 start 的重复项会碰撞（建议加 endMillis 或 index tiebreaker，GAP-05）。

## 6. 视觉 v2 / 清理 / 回归

- **视觉 v2**：焦点仅 `ColorDrawable(0x14FFFFFF)` + 文字 `#F2F5FA`/BOLD（普通 `#A9B4C7`/NORMAL），无 border/shadow/elevation/blur；选中 2dp `#4EA8FF` 左条 + ▶；NOW 2dp `#F5A524`；方角；行高 40/56/44/44。符合。
- **根透明**：`NativeSidebarView` 根 `TRANSPARENT`，仅 `row` 容器 `0xE6161B22`（90%），不再全屏遮视频。符合（NS-04 已修）。
- **文本布局**：primary marginEnd=96dp、secondary 固定 90dp 右对齐，重叠风险已消除（NS-08 已修）。
- **focusColumn**：先 `rv.isFocusable=true` 再 requestFocus（NS-10 已修）；但回退到首可见项（GAP-02）。
- **PlayerDrawer.kt 清理安全**：仍被 `DrawerColumn`（定义于 PlayerDrawer.kt:79）与 helper 引用，故保留合理；未使用的 composable（含旧 2dp border）不被组合、R8 剔除；无未绑定 `FocusRequester` 运行风险（composable 不执行）。**安全**，但建议后续把 `DrawerColumn` 抽到独立文件后删除旧抽屉源码（GAP-06）。
- **回归**：零拷贝基座/灰屏、LoadControl `1000/6000/300/1000`、频道键 166/167/92/93、稳定 key（DrawerKeys）、SR、JNI/R8/UA、硬解 OMX.goke 均未改；APK 10,208,256B ≤ 30MB。
- **死代码（低）**：`pendingInitialFocus` 仍未置真；`requestInitialFocus` fallback `channelsRv.requestFocus()` 对 `isFocusable=false` 无效（GAP-07）。

## 7. 放行结论与门禁

- **源码结论：needs_revision**。t63 的 blocker 已修（回看/日期/EPG 深比较）；剩余为**布局保真（633/151/120 叠加）**、**列间焦点回退保留**、**左右列遍历显式化**，以及若干低风险加固。修复后复审。
- 修复后 acceptance2 在 exact pair（主 `65f7df72…`/test `a7a7eaf3…`/10083）验证：TIV-05/06/08/09/15（左组/右节目/右日期、日期筛选、历史回看、当前/未来提示、无能力提示）、频道 OK 抽屉保持且焦点不丢、反复开关不崩、`sidebar_jank_probe.sh` 对照 CI10078、视频 dropped0、冷启/CPU/PSS、品牌。

## 8. 缺陷与可执行修复

| id | 严重度 | 问题 | 修复 |
|---|---|---|---|
| GAP-01 | **medium** | 列宽 150/300/300/110、主视图 600dp，且分组/日期为并排增宽而非 633dp 基座上的叠加；与任务 300+300+151/120 叠加不符 | 基座固定 633dp（300+300+33 余量）；分组 151dp、日期 120dp 用 FrameLayout 覆盖左右；显示/隐藏不移动频道/节目 |
| GAP-02 | medium | `focusColumn` 聚焦首个可见项，未保留该列上次浏览焦点；LEFT 回频道/节目会丢位置（TIV-05/08） | 每列记录 lastFocusedPosition/或按 focusedChannelUrl 定位；`focusColumn` 优先恢复 |
| GAP-03 | medium | RIGHT from Channels（进节目）/ LEFT from Programs（回频道）未显式处理，依赖隐式焦点搜索 | Activity drawerOpen 分支补 `Channels -> focusPrograms()`、`Programs -> focusChannels()`；设备验证 |
| GAP-04 | low | `setShowGroups/ShowDates` 后立即 `focus*`，若可见性尚未更新（GONE）焦点可能落空 | 先 render/设可见再 post 聚焦，或 post 两帧；设备验证首次 LEFT/RIGHT |
| GAP-05 | low | DiffUtil identity 节目用 text+startMillis，重复项可能碰撞 | identity 加 endMillis 或 index tiebreaker |
| GAP-06 | low | PlayerDrawer.kt 保留（DrawerColumn 在其中）；旧 border 仍在未引用 composable | 安全；建议抽出 DrawerColumn 后删除旧抽屉源码 |
| GAP-07 | low | `pendingInitialFocus` 死字段；`requestInitialFocus` fallback 对非焦点 RecyclerView 无效 | 删除死字段；fallback 前设 `channelsRv.isFocusable=true` |
| GAP-08 | low | `setOnSidebarCatchup` 的 `bound == null` 时静默不动作 | 加 Toast/日志（如“无法回看该节目”） |

## 9. 只读命令与终态

只读命令：`git rev-parse HEAD`、`git status --short`、`git show --stat b29d5f35`、`git show b29d5f35`（全 diff）、`git rev-parse b29d5f35:<file>`、源码 grep/读取（CatchupHelper 签名、CatchupPlayRequest.owner、DrawerColumn 定义、PlayerDrawer 调用、focusColumn、列宽）。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 本报告完成后停止读取目标源码；后续提交须新 diff 证据快照。
