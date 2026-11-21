# 回退原版观感 + 仅去蓝框焦点 独立复查（t96 / run90）

任务 t97（attempt 1），designer2，2026-11-20（Asia/Hong_Kong）。**结论：needs_revision。** 侧栏主体已**逐字节恢复 c82593f 原版 Compose PlayerDrawer**（双列 633dp、12dp 行圆角、basicMarquee、原字号/间距/台标、18dp 容器、原版无 border、焦点 `surfaceVariant`）；NativeSidebarView 已删除、无残留引用；回看/日期/分组/数字跳台/频道键均保留。但 v2/v3 的方角化**还有两处残留**：**信息条进度条仍是 2dp（原版 999dp 胶囊）**、**设置行仍是 2dp 圆角（原版 12dp）**。修复这两处即可完全回到 c82593f 观感。

> 本复查只读固定 commit `165d94f1`（工作树 clean）。模型不能读图，视觉结论仅基于源码/尺寸与 c82593f 逐文件 diff。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`，分支 `integration/tivimini-baseline`；HEAD=`7f18f1e`（docs-only），工作树 clean；`165d94f1..HEAD` 无代码改动。
- 被审查 commit `165d94f18e49d5482a30f98a1d4c2df0370f80c8`（t96），9 文件 +66/−765（删除 NativeSidebarView 653 行）。
- 基线 commit `c82593f`（tivimini Compose 原版观感）。
- 关键文件 blob @165d94f1：

| 文件 | blob |
|---|---|
| [PlayerDrawer.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt) | `64894c617565f67a6f39a58c34903d4415e431df` |
| [ChannelInfoBanner.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/ChannelInfoBanner.kt) | `a17765238fc1c6704c02aa61e82d1791017983f5` |
| [SettingsDrawer.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/SettingsDrawer.kt) | `c17707ebef04ffffef1f0f385023e827cd825219` |
| [PlayerActivity.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt) | `514cdf1865b97434a880475988933207a9ce113b` |

- 候选：主 10,404,860B SHA256 `9d00db84…3995`，test 502,955B `23c38806…b1a5`，签名 `c798cf91…7a95`，run 44707095454(run90)，10090。未真机验证。

## 2. PlayerDrawer 原版恢复 — 完全一致（pass）

- `git diff c82593f:PlayerDrawer.kt 165d94f1:PlayerDrawer.kt` **为空**，文件逐字节恢复。
- 原版值确认：双列 **633dp**（channels 300 + divider 1 + programs 300+32；groups 150+1、dates 110+10 按需展开）、行 **12dp 圆角**、行内 padding 12/10 与 3dp 行间距、`basicMarquee` 聚焦文本、`ChannelLogo 64×40`、`titleMedium/bodySmall` 原字号、`surfaceVariant` 焦点底、`RoundedCornerShape(topEnd=18dp,bottomEnd=18dp)` 容器、`surface` alpha 0.92。
- **无蓝色焦点边框**：`PlayerDrawer` 无 `border`/`BorderStroke`/`4EA8FF`；焦点是原版 `surfaceVariant` 底色。符合“只去蓝框”的要求（原版本身无 border，t50 添加的 2dp 蓝框随文件恢复消失）。

## 3. 信息条 — 容器已恢复，**进度条仍是 2dp（残留）**

- `ChannelInfoBanner` 容器 `RoundedCornerShape(topStart=18dp, topEnd=18dp)`（L41）、tal 版 80×50 台标、32dp 边距、原字号/颜色均已恢复。
- **残留（REVERT-01 medium）**：`BannerProgressBar` 仍是 `RoundedCornerShape(2.dp)`（[L256/L262](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/ChannelInfoBanner.kt#L256)），而 c82593f 原版是 **`RoundedCornerShape(999.dp)` 胶囊**。v2/v3 的“方角化”未完全撤销。
- 修复：把 `BannerProgressBar` 轨道/填充恢复为 `999.dp`（CatchupProgressRow 的 2dp 是原版 L202/211，保持）。

## 4. 设置抽屉 — 容器已恢复，**设置行仍是 2dp（残留）**

- `SettingsDrawer` 容器 `RoundedCornerShape(topStart=18dp, bottomStart=18dp)`（L52）、300dp 宽、24dp padding、`surface` 0.95 已恢复。
- **残留（REVERT-02 medium）**：`SettingsItem` 行仍是 `RoundedCornerShape(2.dp)`（[L155](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/SettingsDrawer.kt#L155)），原版 c82593f 是 **12dp**。v2/v3 方角化未完全撤销。
- 修复：设置行恢复 `RoundedCornerShape(12.dp)`（与 PlayerDrawer 行一致）。

## 5. NativeSidebarView 删除 / 无双状态机 — pass（含 low 清理项）

- `NativeSidebarView.kt` **已删除**（`git show --stat` 653 行删除；`Test-Path` false）；全工程 `NativeSidebar|nativeSidebar|setOnSidebar` **无残留引用**。
- `PlayerActivity` 恢复调用原版 `PlayerDrawer(...)`（visible/groups/selectedGroup/channels/channelNumbers/selectedChannelUrl/nowProgramByChannelUrl/epgData/nowMillis/onSelectGroup/onSelectChannel/onPlayProgram/onClose/onActiveColumnChanged/requestedActiveColumn）；`onSelectChannel = playChannel`；`onPlayProgram` 仍走 `PlaybackIntent.catchup`（owner B）；`onClose`/`onActiveColumnChanged`/`requestedActiveColumn` 经 `SidebarController`，`onDrawerOpenChanged` 同步 `isDrawerOpen`（PlayerActivity L572）。
- 无双状态机：PlayerDrawer 内部 `showGroups/showDates/activeColumn/followedChannel` 为原版展示状态；`SidebarController` 只承载 Activity 级 drawerOpen/activeColumn/selectedGroup，二者不重复写同一渲染状态。
- **low（REVERT-04）**：`SidebarController` 实际仍保留完整 `SidebarState`（channelGroups/epgData/showGroups/showDates/selectEpgDate/stepChannel/activateChannel 等），其中部分已无消费者；t96 docs 称“仅保留 open/close/selectGroup/setActiveColumn”不准确。无行为冲突，但建议清理/注释，避免未来误用为第二状态源。
- **low（REVERT-05）**：Activity 仍保留死字段 `setDrawerOpen`/`drawerActiveColumn`/`focusToDrawerChannels`（仅 `isDrawerOpen` 被 `onDrawerOpenChanged` 使用；`setDrawerOpenController` 注册但无调用）。建议清理。

## 6. 原版 DPAD/focus 行为 — 恢复（pass）

- `PlayerDrawer` 原版 `onPreviewKeyEvent`/`FocusRequester`/`basicMarquee` 全部随文件恢复；无 native 侧栏 DPAD 分支。
- Activity `dispatchKeyEvent`：DPAD_LEFT 仅在 `!isDrawerOpen` 时 `sidebarController.openDrawer()`；抽屉打开时 LEFT 落 Compose；BACK 用 `sidebarController.value.drawerOpen` 一次关整抽屉；数字/频道键守卫 `!isDrawerOpen`；频道键 166/167 走 `KEYCODE_CHANNEL_UP/DOWN`（L306/L316）。符合原版。
- `isDrawerOpen` 由 `LaunchedEffect(drawerOpen){ onDrawerOpenChanged(drawerOpen) }` 同步（L572），与 controller 同源。

## 7. 保留项 / 功能 / 体积 — pass

- 零拷贝 SurfaceView 基座/灰屏修复、频道键 166/167、LoadControl `1000/6000/300/1000`（L446–449）、SR-01–03、回看 owner B、EPG、音频诊断（`AudioDiagnosticFields`/`StreamPlayback`）、稳定 key（`DrawerKeys`）、品牌资源与应用名（`resValue` = `IQTV Lite` / validation `IQTV Lite 验证版`，`PlayerActivity` 原版 colorScheme `#BDBDBD/#757575/#121212/#1E1E1E/White`）均保留。
- 功能：PlayerDrawer 原版恢复 → 回看（onPlayProgram owner B）、日期列、分组筛选、数字跳台（digits/numberJump）、频道键均无丢失；`onSelectChannel=playChannel`。
- 体积 10,404,860B ≤ 30MB。
- **性能取舍（REVERT-06 low）**：回到原版 Compose `basicMarquee`/抽屉渲染，jank 以原版基线为准，不宣称优于原版；这是用户明确接受的取舍。

## 8. 放行结论与门禁

- **源码结论：needs_revision**。主体已逐字节恢复 c82593f，无蓝框、NativeSidebar 删除、功能保留；只剩**信息条进度胶囊 999dp** 与**设置行 12dp** 两处 v2/v3 方角残留，修复后即完全回到原版观感。
- acceptance2 在 exact pair（主 `9d00db84…`/test `23c38806…`/10090）验证：截图与 c82593f 原版一致（仅无蓝框）、双列 633dp/12dp 行圆角/18dp 容器、信息条 999dp 进度胶囊、设置行 12dp、台标 64×40/80×50、字号/间距/颜色一致；TIV/回看/日期/分组/数字跳台不回归；反复开关不崩/不灰屏；jank 以原版 Compose 基线为准。

## 9. 缺陷与可执行修复

| id | 严重度 | 问题 | 修复 |
|---|---|---|---|
| REVERT-01 | **medium** | 信息条 `BannerProgressBar` 仍是 2dp 方角，原版 c82593f 是 999dp 胶囊 | `ChannelInfoBanner` L256/L262 恢复 `RoundedCornerShape(999.dp)`（CatchupProgressRow 2dp 保持） |
| REVERT-02 | **medium** | 设置行 `SettingsItem` 仍是 2dp 圆角，原版是 12dp | `SettingsDrawer` L155 恢复 `RoundedCornerShape(12.dp)` |
| REVERT-03 | low | `LogoLoader.MAX_ENTRIES` 96 vs c82593f 128（t42 PSS cap） | 非视觉；保留 96 可省 PSS，或按“完全原版”恢复 128 并记录 |
| REVERT-04 | low | `SidebarController` 仍保留完整 state/部分无消费者；docs 称仅保留 4 方法不准确 | 清理未用 state/setter 或注释为“兼容保留”；确保唯一渲染源 |
| REVERT-05 | low | Activity 死字段 `setDrawerOpen`/`drawerActiveColumn`/`focusToDrawerChannels` | 删除或注释；保留 `isDrawerOpen` 同步 |
| REVERT-06 | low | 原版 `basicMarquee`/Compose 抽屉 jank 可能高于 v3/native | 用户已接受原版观感；jank 以原版基线为准，不宣称优化 |
| REVERT-07 | low | `PlayerActivity` 的 t42 `take(60)`/t50 EPG 注释等性能改动仍在 | 非视觉，保留并记录；如严格“完全原版”可另行评估 |

## 10. 只读命令与终态

只读命令：`git rev-parse HEAD`、`git status --short`、`git show --stat 165d94f1`、`git diff c82593f:... 165d94f1:...`（PlayerDrawer/ChannelInfoBanner/SettingsDrawer/MainActivity/ChannelSwitchLoading/QualitySelectorDialog）、`git show 165d94f1:...PlayerDrawer.kt | grep border`、源码 grep（NativeSidebar/nativeSidebar、isDrawerOpen/onDrawerOpenChanged、channel keys、LoadControl、AudioDiagnosticFields、DrawerKeys、resValue）。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 本报告完成后停止读取目标源码；后续提交须新 diff 证据快照。
