# 原生侧栏 M2+M3 与 TIV 保真 独立源码复查（t62 / run82）

任务 t63（attempt 1），designer2，2026-11-20（Asia/Hong_Kong）。**结论：needs_revision / 不通过源码门禁。** 原生 RecyclerView 侧栏方向正确（单状态源、无 Compose 抽屉、无硬边框焦点、方角/固定行高/预格式化），但**存在 2 个 TIV 功能回退**（节目列 OK 的回看 catchup 未接、日期列因 `showDates` 永不置真而不可达）以及 **1 个高概率性能回退**（`EpgData` 放进 data class `StateFlow` 导致每次焦点/状态更新做全量 EPG 深比较）。修复后再复审，才可交 acceptance2 真机验证。

> 本复查只读固定 commit `06b79d75`。**工作树当前有未提交 follow-up**（`NativeSidebarView` 增加 `onPlayCatchup`/节目 `startMillis` 等），不属于本 commit、不在本报告范围；若该 follow-up 修掉 NS-01/NS-02，需提交后重新复审。模型不能读图，视觉结论仅基于源码/资源规格。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`，分支 `integration/tivimini-baseline`；HEAD=`79d6255`（docs-only），但**工作树有未提交改动**（见上）。本报告钉在 commit `06b79d759a9d63a69e4c54a8fc898c440c2a3e9c`。
- 被审查 commit `06b79d75`：3 文件 +354/−44（`NativeSidebarView.kt` 新增、`PlayerActivity.kt` 改、`SidebarController.kt` +2）。docs `79d6255`。
- 读取内容身份（git blob @06b79d75）：

| 文件 | blob |
|---|---|
| [NativeSidebarView.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt) | `f51e28d5897f420bffd20c5c73cecdf9126ca5c9` |
| [PlayerActivity.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt) | `22a9ae90e279b0d4fb2590b5b465cc89cd212a79` |
| [SidebarController.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/SidebarController.kt) | `0064e33f1044a6e21eb01b568b340ead67d2aac0` |

- 候选：主 10,191,872B SHA256 `f6d2b71d…cc21`，test 502,955B `a6046a7f…704b`，签名 `55f751af…1213`，run 44673070513(run82)，10082。未真机验证。

## 2. 架构与状态机 — 基本正确

- **层序正确**：`PlayerActivity.onCreate` 依次 add `videoView`(SurfaceView) → `NativeSidebarView`(默认 GONE) → 透明 `ComposeView`，即 video 底、原生侧栏中、Compose 浮层顶（[PlayerActivity @06b79d75](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt)）。灰屏风险未回归（关闭时 GONE，无覆盖）。
- **Compose 抽屉已移除**：`PlayerDrawer(...)` 调用删除，仅保留注释；R8 剔除未引用类（app_class_definitions 9896→9538）。
- **单一状态源**：`SidebarController`（Activity 持有）为唯一状态；`lifecycleScope` 收集 `state` 驱动 `sidebar.visibility/render`；Activity `isDrawerOpen` 是**单向镜像**（`isDrawerOpen = st.drawerOpen`）用于 dispatchKeyEvent 守卫，不是第二状态机。
- **无双键处理**：Activity 的 DPAD/频道键分支仍带 `!isDrawerOpen` 守卫；抽屉打开时落 `super.dispatchKeyEvent`，由原生焦点处理；BACK 用 `sidebarController.value.drawerOpen` → `closeDrawer()`（任意列一次关）。方向正确。
- 遗留死代码（低）：`setDrawerOpen`/`drawerActiveColumn`/`focusToDrawerChannels` 在抽屉移除后基本无消费者；`NativeSidebarView.pendingInitialFocus` 从未置 true（实际由 Activity 的 `requestInitialFocus()` 处理）。不影响运行，但建议清理以免误判。

## 3. TIV 逐项 — 2 项功能回退（blocker）

| TIV | 结论 | 证据 |
|---|---|---|
| 初开定位当前台 | 源码可接受 | Activity collect `if(wasHidden) sidebar.requestInitialFocus()`；`requestInitialFocus` 用 `focusedChannelUrl ?: selectedChannelUrl` 定位并 `scrollToPosition + requestFocus`（NativeSidebarView:234–248） |
| 浏览焦点保留 | 源码可接受 | 结构签名 guard 防 focus-only 重绑（:189–194）；`openDrawer()` 保留有效 `focusedChannelUrl` |
| 上下循环 | 源码可接受（频道键路径） | `SidebarController.stepChannel`（M1）双端循环；抽屉内原生焦点由 RecyclerView 处理 |
| 左组/右节目/右日期 | **部分失败** | 4 列同显；但**日期列默认 GONE 且无任何原生代码置 `showDates=true`**（见 NS-02） |
| 组/日期 OK 仅筛选 | 组 OK 可用；**日期 OK 不可达** | `DrawerColumn.Groups -> selectGroup`、`Dates -> selectEpgDate`；但日期列不可见→无法 OK |
| 频道 OK 换台、抽屉保持 | 源码可接受（焦点可能被重置） | `activateChannel + onPlayChannel` 不关抽屉；但 `selectedChannelUrl` 在结构签名内→`notifyDataSetChanged` 重绑可能丢焦点（NS-06） |
| 单次 Back 关整抽屉 | 源码可接受 | Activity BACK → `sidebarController.closeDrawer()`（任意列一次关） |
| 350ms EPG 防抖 | 源码可接受 | `handler.postDelayed(350){ renderProgramsOnly() }`；`removeCallbacksAndMessages(null)` 清旧任务 |
| 选中/播放标记 + 焦点 | 源码可接受 | 焦点 `#14FFFFFF` 底 + Ink/BOLD；选中 2dp `#4EA8FF` + ▶；NOW 2dp `#F5A524`；无 border |
| 频道键 166/167 仅关闭时 | 源码可接受 | Activity `!isDrawerOpen` 守卫；打开时原生不处理，不误触 |
| 面板内 DPAD 不误触 | 源码可接受 | 抽屉打开时 Activity 不处理 DPAD，落 super→原生焦点 |
| **节目列 OK（回看 catchup）** | **失败（blocker）** | `DrawerColumn.Programs -> Unit`（NativeSidebarView:268）；`SidebarRow` 节目行无 start/end/channel 身份；Compose `onPlayProgram` 的 catchup 路径随抽屉删除而消失 → **TIV-09/15 回看入口丢失** |
| **日期列** | **失败（blocker）** | `datesColumn.visibility = if(state.showDates && …)`（:231）；`showDates` 默认 false，原生从未调用 `setShowDates/toggleDates`，`selectEpgDate` 只在日期列点击时触发→**日期列永不可见**（TIV-08 日期选择/筛选失效） |

## 4. 视觉 v2 — 核心项正确，但整体遮罩与列宽偏离

**已符合**：
- 焦点**无边框**：`applyFocus` 仅改背景 `ColorDrawable(0x14FFFFFF)` + 文字 `#F2F5FA` + BOLD（NativeSidebarView:114–118）；无 `border`/`shadow`/`elevation`/`blur`。
- 选中：2dp `#4EA8FF` 左条 + ▶（:64–72, 103–105）；NOW：2dp `#F5A524`（:104）；方角；无阴影。
- 行高：分组 40 / 频道 56 / 节目 44 / 日期 44（:143–146）——与任务“56/44/44/40”集合一致；`setHasFixedSize(true)`。
- 普通文字 `#A9B4C7`，焦点 `#F2F5FA`；颜色与品牌一致。

**偏离/风险**：
- **整屏 90% 不透明底**：`NativeSidebarView` 根 `setBackgroundColor(0xE6161B22)`（:158，≈90% Obsidian）覆盖**全屏**；抽屉打开时视频几乎不可见。TiviMate/规范是“半透明浮层，视频依然透出”，规范 §2.1 模态 scrim 仅 40%（`#00000066`）。→ 建议根透明，仅面板区 `#161B22` 92%，右侧保留视频；若要压暗用 40% scrim。
- **分组列常显 + 4 列 860dp**：`groupsColumn.visibility = VISIBLE`（:199）无条件；分组 150+频道 300+节目 300+日期 110 ≈ 860dp，非原版 633dp 分阶段。任务 TIV 列表接受“左组/右节目/右日期”焦点遍历，但与 TIV-05/06 的“左进组/右回日期”分阶段不同，需确认是否可接受。
- **行内文字重叠风险**：`primary` 的 `marginEnd=86`，`secondary` 为全宽右对齐（:80–92）；长节目名/时间可能压到主文字。建议两行布局或限制 secondary 宽度。

## 5. 性能 — 方向正确，但有一个高概率回退

- **方向正确**：RecyclerView 回收、仅可视项、固定行高、预格式化文本、`setHasFixedSize(true)`、350ms 防抖只重算节目/日期列、结构签名 guard 防 focus-only 重绑。
- **高概率回退（NS-03）**：`SidebarState` 是 `data class`，且新增 `epgData: EpgData?`；`EpgData` 也是 `data class`（含 `programsByChannelId: Map<...>`）。`SidebarController` 用 `MutableStateFlow.update{}`，StateFlow 每次赋值用 `equals` 去重；因此**每次焦点移动/`setActiveColumn`/`focusChannel` 都会对整份 EPG 做深比较**（O(节目数)，主线程）。`render` 的结构签名 `listOf(..., state.epgData, ...)` 也用 `==` 深比较。数千节目时每次焦点移动都可能产生可测卡顿——与“降 jank”目标相反。
  - 修复：不要把 `EpgData` 放进 `SidebarState` 的 equality；用独立 `@Volatile`/单独 StateFlow（identity 去重）或自定义 `equals` 排除 `epgData`；`render` 签名对 `epgData` 用引用比较（`===`）。
- **次要**：`notifyDataSetChanged`（无 DiffUtil）在结构变化时全量重绑；`programsFor` 的 filter/sort 在 350ms 回调主线程执行；`selectedChannelUrl` 在签名内导致频道 OK 后重绑/焦点重置（NS-06）。建议 DiffUtil/`notifyItemChanged`、把排序移到 `Dispatchers.Default`、把选中变化从结构签名分离并原位更新选中行。

## 6. 叠层/焦点/命中/BACK/灰屏

- 层序 video→sidebar→compose 正确；关闭时 GONE，不遮挡视频（灰屏不回归）。**打开时 90% 全屏底**属视觉偏离（§4），非灰屏。
- 焦点：`requestInitialFocus` 在 `channelsRv.post` 中 scroll+requestFocus；ComposeView 在上层可能先持有焦点，`requestFocus` 应能转移（真机验证）。**边界**：fallback `channelsRv.requestFocus()` 无效，因为 `rv.isFocusable=false`（:179）；若 `findViewHolderForAdapterPosition` 返回 null（空列表/未布局），焦点可能未落到原生视图。建议 `channelsRv.isFocusable=true` 或确保 holder 存在后再请求。
- 命中测试：ComposeView 全屏在上；TV 无触摸，DPAD 由 Activity/原生处理；触摸命中不影响 TIV。真机验证。
- BACK/退出：Activity BACK 关抽屉/双击退出未改；退出闪帧机制未变（零拷贝基座）。

## 7. 回归与体积

- 零拷贝基座/灰屏修复：未改 `onCreate` 的 videoView/ComposeView 结构，仅插入 sidebar 层。
- LoadControl `1000/6000/300/1000`、频道键 166/167/92/93、稳定 key（DrawerKeys，未用但测试保留）、SR、JNI/R8/UA、硬解 OMX.goke 均未改。
- 体积 10,191,872B ≤ 30MB；无 ABI/功能删除（catchup 回退属 TIV 功能缺失，见 NS-01）。

## 8. 放行结论与真机门禁

- **源码结论：needs_revision（不通过）**。必须先修 NS-01（节目回看）、NS-02（日期列不可达）、NS-03（EpgData 深比较），再复审；NS-04~NS-10 可同批修或明确记录。
- 修复并新 CI 后，acceptance2 在 exact pair 验证：反复开关不崩、TIV-05/06/08/09/15 全路径（含日期选择、节目回看）、频道 OK 抽屉保持且焦点不丢、`sidebar_jank_probe.sh` 对照 CI10078（janky<80%/90th<200/95th<500）、视频 dropped0、冷启/CPU/PSS、图标/banner/splash/名称。

## 9. 缺陷与可执行修复

| id | 严重度 | 问题 | 修复 |
|---|---|---|---|
| NS-01 | **blocker** | 节目列 OK 无动作（`DrawerColumn.Programs -> Unit`），节目行无 start/end/channel 身份；Compose 抽屉的 catchup 入口被删 → TIV-09/15 回看丢失 | `SidebarRow` 带 start/end/channel；`Programs` 点击构造 `CatchupPlayRequest` 并走 `PlaybackIntent.catchup → startPlayback`（与删除的 Compose `onPlayProgram` 等价），按原版语义关抽屉/保留；补 JVM/设备回归 |
| NS-02 | **blocker** | 日期列 `visibility` 依赖 `showDates`，但原生从不置真 → 日期列永不可见，TIV-08 日期选择失效 | 在节目列 RIGHT/聚焦时 `controller.setShowDates(true)`（或初始即显示日期列）；提供 DPAD 右进日期/左返节目；`selectEpgDate` 后保持；补测试 |
| NS-03 | **high** | `EpgData`（data class，含大 Map）放入 `data class SidebarState` 的 `StateFlow`；每次焦点/状态更新触发全量 EPG `equals` 深比较（主线程），与降 jank 相反 | 将 `epgData` 移出 StateFlow equality（独立 holder/单独 flow/自定义 equals 排除）；`render` 签名用 `===` 引用比较；验证 focus 移动无深比较 |
| NS-04 | medium | 侧栏根背景 `0xE6161B22`（≈90%）覆盖全屏，抽屉打开时视频几乎不可见 | 根透明；仅面板区 `#161B22` 92%；如需压暗用 `#00000066`（40%）scrim |
| NS-05 | medium | 分组列常显、4 列 860dp，非原版 633dp 分阶段 | 按 TIV-05/06 用 `showGroups/showDates` 分阶段显示；或明确接受偏差并在验收记录 |
| NS-06 | medium | 频道 OK 后 `selectedChannelUrl` 进入结构签名→`notifyDataSetChanged` 重绑，可能丢焦点 | 选中变化从结构签名分离，原位更新选中行；或重绑后恢复焦点 |
| NS-07 | medium | 无 DiffUtil，结构变化 `notifyDataSetChanged` 全量重绑 | 用 DiffUtil/ListAdapter 或 `notifyItemChanged`；排序移出主线程 |
| NS-08 | medium/low | 行内 primary `marginEnd=86` 与全宽右对齐 secondary 可能文字重叠 | 两行布局或限制 secondary 宽度；真机截图复核 |
| NS-09 | low | 死代码：`pendingInitialFocus` 从未置真；`setDrawerOpen`/`drawerActiveColumn`/`focusToDrawerChannels` 无消费者；`PlayerDrawer.kt` 未引用仍含旧 border | 清理死代码/删除或明确保留；避免误判 |
| NS-10 | low | `requestInitialFocus` fallback `channelsRv.requestFocus()` 对 `isFocusable=false` 的 RecyclerView 无效；空列表/未布局时焦点可能不落原生 | 让 RecyclerView 可聚焦或确保 holder 存在后再 requestFocus |

## 10. 只读命令与终态

只读命令：`git rev-parse HEAD`、`git status --short`（发现工作树未提交 follow-up，未纳入）、`git show --stat 06b79d75`、`git show 06b79d75 -- <files>`、`git show 06b79d75:<file>`（提取 committed `NativeSidebarView.kt` 全文 285/317 行核对）、`git rev-parse 06b79d75:<file>`、源码 grep（EpgData/data class、showDates、catchup、dead callbacks）。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 本报告完成后停止读取目标源码；后续提交须新 diff 证据快照，不自动纳入本次结论。
