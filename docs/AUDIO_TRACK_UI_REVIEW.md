# 音轨 / 声道 / 语言显示与选择 独立复查（t175 / run10113）

任务 t177（attempt 1），designer2，2026-11-21（Asia/Hong_Kong）。**结论：needs_revision。** 音轨标签格式、纯逻辑测试、TrackSelectionOverride（不重建 player）、设置分组/0dp/焦点方向基本正确。但以下源码缺陷直接影响验收：

1. **音频对话框没有 Activity 级状态**：`audioDialogOpen` 是 Compose 局部；打开时 `SettingsDrawer` 仍开着。Back 在 Activity `dispatchKeyEvent` 先被处理：第一次 Back 关闭设置抽屉而不是音频对话框；设置关掉后，音频对话框仍在，数字/D-pad/BACK 又可穿透到播放器（再次 Back 可能走双退）。与“Back 只关对话框”相反。
2. **`onTracksChanged` 无条件把 override 重置为自动**：设置 `trackSelectionParameters` 会触发 `onTracksChanged`，手动选择后 UI/state/信息条又回自动；实际 ExoPlayer override 可能仍生效，UI 与播放不一致，当前 ▶ 也丢失。
3. **无音轨/单音轨降级未真正接线**：`AudioTrackUi.buildItems(emptyList())` 仍返回“自动”行，`AudioTrackDialog` 的 `items.isEmpty()` 分支永远不可达，`audio_empty` testTag 不会出现；`canSelect=false` 只存在于纯逻辑，设置行仍可点开单音轨对话框。
4. **音轨列表未区分 renderer/selected**：`onTracksChanged` 把所有 audio groups 扁平化（可能同时含 MediaCodec 与 FFmpeg 扩展组）；用户可为 AAC 选择 FFmpeg 组，违反“AAC 仍 MediaCodec”。`summary` 自动态取 `raws.first()` 而非当前 selected track，信息条可能显示错轨。

> 只读固定 commit `a7f9d498`；HEAD `7887c9b`（docs-only）。工作树有 t176 的 `PlayerActivity/SettingsDrawer` 并发修改（未读取/未评价）。未改代码、未跑 Gradle/CI/ADB、未操作 TV。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`；被审查 commit `a7f9d498d4359efa526b7cc40aa1f596ab84889c`。
- `git show --stat`：6 files +491：`AudioTrackUi.kt`、`AudioTrackDialog.kt`、`AudioTrackUiTest.kt`、`ChannelInfoBanner.kt`、`PlayerActivity.kt`、`SettingsDrawer.kt`；docs `AUDIO_TRACK_UI.md` 7887c9b。
- pair：主 `13,043,607B` SHA `4d45dcc1…88d02`、test `502,955B` `218e7358…8d1d1`、run 44826190207（run10113）、10113；设备未验证。
- 关键行：`PlayerActivity` `audioDialogOpen` L438、`onTracksChanged` L614–638、`applyAudioOverride` L982–1000、Dialog L1430–1438、Banner L1554；`AudioTrackUi` entire 130；`AudioTrackDialog` 153；`SettingsDrawer` audio group L167–187；`Activity` `dispatchKeyEvent` no audio guard L238–360。

## 2. 已通过/正确部分

- **标签格式**：`AudioTrackUi.formatCodec` 覆盖 mpeg-L2/aac/ac-3/eac3/opus/flac/mp3，`formatChannels` 2ch/6ch，`formatLanguage` 去 und/null；`TrackLabel` 不含 null；单测 8 项通过（JVM 127）。
- **对话框模型**：自动行第一 `AUTO_TEXT`；手动 override 只标记所选；`audio_dialog/audio_row_auto/audio_row_<g>_<t>` testTag；0dp、surfaceVariant 焦点、无蓝框；`AudioRow` 选中 `▶`；OK 回调、Back 有 `onPreviewKeyEvent`。
- **选择机制**：`applyAudioOverride` 只改 `player.trackSelectionParameters`（`setOverrideForType(TrackSelectionOverride)`/`clearOverridesOfType`），**不重建 player**；AAC/MP2/LoadControl 默认路径未动；无新线程/IO；check_player_threading 无新增后台 Player。
- **设置入口**：新增“音频与字幕”`SettingsGroupHeader`（不可聚焦、0dp、无蓝框）+ 音轨选择行 + 当前摘要；默认焦点仍最近观看；Back 根关闭；`SettingsItem` 有 `settings_audio_track` tag。
- **信息条**：`ChannelInfoBanner` 新增可选 `audioLabel`；null/blank 整行省略；非空渲染 `音轨 …`；无数据不占位。
- **无回归**：diff 仅上述 6 文件；未改零拷贝/LoadControl/MP2 AAR/0dp flat/频道键/数字 OSD/台标/最近观看/EPG/SR/品牌；APK 13,043,607B≤30MB；keep210/contract0。

## 3. 阻断/需修复

### AUDIO-BACK-01（high）音频对话框没有 Activity 状态；Back 关错层、按键可穿透

**证据**：
- `PlayerActivity.kt` L438 `var audioDialogOpen by remember { mutableStateOf(false) }`，只传给 `AudioTrackDialog`；Activity **没有** `isAudioDialogOpen`/controller（与 t141 修 recent 的模式不一致）。
- `SettingsDrawer` 的 `onAudioTrackClick` L1374 只 `audioDialogOpen=true`，**没有关闭 settings**；此时 Activity `isSettingsDrawerOpen=true`。
- Activity `dispatchKeyEvent` BACK L300–323 先判 recent/quality/drawer/**settings**；第一次 Back 会 `setSettingsDrawerOpen(false)`，**音频对话框仍在**；第二次 Back 再走信息条/双退。若设置先被关，数字/D-pad/OK/MENU 的 guard 也不含 audioDialogOpen，会穿透到播放器。
- `AudioTrackDialog` 的 `onPreviewKeyEvent` Back L86–91 在 Activity 之后，**收不到**。

**修复**：
1. Activity 增加 `isAudioDialogOpen` + `setAudioDialogOpenController`/`onAudioDialogOpenChanged`；`VideoPlayerScreen` 在 `audioDialogOpen` 变化时回传（`SideEffect`/controller，避免 LaunchedEffect 同帧窗口）。
2. 所有数字/OK/方向/MENU/频道键 guard 加 `&& !isAudioDialogOpen`；BACK **最前** `if (isAudioDialogOpen) { setAudioDialogOpen(false); return true }`。
3. 打开音频对话框时先 `settingsDrawerOpen=false`（或明确保留设置但 Activity 优先 audio）；Back 只关对话框。
4. 建议 Compose 测试：settings→audio→Back 后 `audio_dialog` 不存在、播放器未 finish；数字不触发。

### AUDIO-OVERRIDE-01（high/medium）`onTracksChanged` 无条件重置 override，手动选择立即被 UI 回自动

**证据**：`PlayerActivity.kt` L637–638：
```kotlin
audioTracks = flattened
// a new item invalidates the manual override
audioOverrideGroup = -1
audioOverrideTrack = -1
```
但 `applyAudioOverride` 设置 `p.trackSelectionParameters` 会触发 `onTracksChanged`；此时并非换台，仍把 override 置 -1：
- 关闭对话框后 `AudioTrackUi.summary(..., -1, -1)` 取 `raws.first()`，信息条/设置摘要显示错误轨；
- 再次打开对话框 `buildItems` 自动行 selected，手动当前 ▶ 丢失；实际 ExoPlayer 的 override 可能仍选中，UI/播放不一致；
- 用户无法判断当前音轨，也无法从列表确认选择。

**修复**：
- 只在**媒体项/频道真正变化**时重置：记录 `lastAudioMediaId`（`p.currentMediaItem?.mediaId`，主线程）或在 `onMediaItemTransition`/`playGroupVariant` 里 reset；`onTracksChanged` 仅刷新 `audioTracks`。
- 或从 `p.trackSelectionParameters.overrides`/`Tracks` selected group 推导 override，不维护独立 state；这样 `onTracksChanged` 自动同步 UI。
- 加测试：设置 override → onTracksChanged 后 selected 仍指向该轨；换台后回自动。

### AUDIO-EMPTY-01（medium）无音轨 `audio_empty` 不可达，显示“自动”而非“没有可选音轨”

**证据**：
- `AudioTrackUi.buildItems(emptyList())` 返回 `[自动]`；`AudioTrackDialog` L129 `if (items.isEmpty())` 永远 false；`audio_empty` L? 永不渲染。
- 任务要求“无音轨显示「无音轨」且对话框给「当前频道没有可选音轨」”；当前设置行 `entryLabel(empty)="音轨选择（无音轨）"` 正确，但对话框只有自动行。
- `AudioTrackUiTest.emptyListDegradesGracefully` 断言只有自动行，与需求/对话框空状态冲突。

**修复**：`AudioTrackDialog` 增加 `hasAudio: Boolean`（或 `raws`/`empty` 参数），无音轨时显示 `audio_empty` 文本且不显示自动行；或让 `buildItems(empty)` 返回空并更新测试；有音轨才加自动行。单音轨 `canSelect=false` 也应隐藏/禁用入口，或对话框只显示“仅1条，无需选择”。

### AUDIO-SINGLE-01（low/medium）`canSelect=false` 未接线，单音轨仍可打开无意义对话框

**证据**：`AudioTrackUi.canSelect(raws)` 只被单测使用；`SettingsDrawer` 的 `audioEntryLabel`/`onAudioTrackClick` 没有 `canSelect`/disabled 参数，单音轨行仍可点开；`AudioTrackDialog` 会显示自动+单轨，选择无效果。
**修复**：设置项增加 `enabled`/`canSelect`；单音轨禁用并显示“仅 1 条（无需选择）”；无音轨可显示只读提示或打开 empty dialog。

### AUDIO-RENDERER-01（medium/high）音轨列表未过滤 renderer/selected，AAC 可选到 FFmpeg 组

**证据**：`onTracksChanged` L619–634 遍历 **所有** `group.type == TRACK_TYPE_AUDIO`，不检查 `group.isSelected`/`isTrackSupported`/renderer；`AudioTrackUi.Raw` 也不带 `selected`。项目以 `EXTENSION_RENDERER_MODE_ON` 同时存在 MediaCodec 与 FFmpeg 音频 renderer：
- `player.currentTracks` 可能同时列出 MediaCodec 与 FFmpeg 的 AAC group；用户选 FFmpeg group 会走软件 AAC，违反“AAC 仍 MediaCodec”；
- 列表可能出现重复同名轨，`summary` 取 `raws.first()`，可能显示未选中的 group/轨。
**修复**：
- 只把**当前 selected audio group（或 active renderer）**的 tracks 作为可选项；若需列出多个 renderer，必须标注 renderer 并禁止 AAC 选 FFmpeg（或明确产品允许）。
- `Raw` 增加 `selected`/`renderer` 字段；`summary` 取 `group.isSelected && group.isTrackSelected && selectedIndex` 的轨，而不是 first。
- `applyAudioOverride` 再校验 group 仍属于当前 selected/active audio renderer。
- 加真实 `Tracks` 构造测试（多 group selected/unsupported）与设备 AAC/MP2 验。

### AUDIO-SUMMARY-01（medium）自动摘要取 first raw，非当前 selected

**证据**：`AudioTrackUi.summary` L?：
```kotlin
val current = if (overrideGroup >= 0) raws.firstOrNull {...} else raws.firstOrNull()
```
`Raw` 无 `selected`/`isTrackSelected`；多 group/多轨时自动态显示第一条，可能是未选中轨；信息条/设置“当前”可能错。
**修复**：同 AUDIO-RENDERER-01：Raw 带 `selected`；自动摘要使用 selected group/track（或 `player.currentTracks` 的 selected track）；manual 用 override；找不到回退到第一但标注。

### TEST-01（medium）只有纯 AudioTrackUi 8 项，没有对话框/Activity/选择集成测试

**证据**：`AudioTrackUiTest` 只测格式化/items/summary/canSelect；无：
- Compose `audio_dialog/audio_empty/audio_row_*` testTag、Back 只关对话框；
- Activity recent/settings/audio 多 overlay Back/数字 guard；
- 真实 `Tracks` 多 group/selected、TrackSelectionOverride 应用；
- 单音轨/无音轨降级接线。
JVM 127 全绿无法捕获 AUDIO-BACK-01/OVERRIDE-01/EMPTY-01/RENDERER-01。

**修复**：加 Compose/Activity 测试（`createAndroidComposeRule`/`testTag`）与 Player 单元/仪器测试；至少纯逻辑加 `selected`/renderer 测试；acceptance 真机验证多音轨/AAC/MP2。

### UI-01（low）信息条额外音轨行高度/重复 `2ch`
- `ChannelInfoBanner` 已有 `audioText`（videoFormat.channelCount），新增 `音轨 …` 行可能显示两个 `2ch`，并增加信息条高度；1080p 需确认不挤压节目名。建议把旧 `audioText` 合并进 audioLabel，audioLabel 存在时不重复 channels；或使用同一容器行。

## 4. 真机/视觉未验证

- MP2 信息条“音轨 … mpeg-L2 … 2ch”；多音轨切第二立即生效/不重建/信息条同步；自动恢复；对话框 Back 只关；AAC/MP2/LoadControl 不回归——均归 acceptance2。本报告不宣称设备通过。

## 5. 放行判断

- **源码结论：needs_revision。** 标签/纯逻辑/不重建 player 方向正确，但 **AUDIO-BACK-01**、**AUDIO-OVERRIDE-01**、**AUDIO-EMPTY-01**、**AUDIO-RENDERER-01/AUDIO-SUMMARY-01** 需要修；TEST-01 需补。
- 建议顺序：**AUDIO-BACK-01 → AUDIO-OVERRIDE-01 → AUDIO-EMPTY/SINGLE → AUDIO-RENDERER/SUMMARY → TEST**；重出 r27 pair 后真机验收。

## 6. 只读命令与终态

`git rev-parse HEAD`、`git status --short`、`git show --stat a7f9d498`、`git show a7f9d498`、`read AudioTrackUi.kt/AudioTrackDialog.kt/PlayerActivity.kt/SettingsDrawer.kt/ChannelInfoBanner.kt`、`read AudioTrackUiTest.kt`、`Get-Content docs/AUDIO_TRACK_UI.md`。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 工作树 t176 并发修改未读取/未评价；本报告完成后停止读取目标源码。
