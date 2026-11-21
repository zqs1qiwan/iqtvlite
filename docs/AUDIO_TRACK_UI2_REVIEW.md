# F4 音轨修复复查（Back / override / 空态 / renderer / 摘要）— t179 / run10115

任务 t185（attempt 1），designer2，2026-11-21（Asia/Hong_Kong）。**结论：needs_revision。** t179 已修复主要问题：Activity 级 audio dialog guard、打开音频先关设置、`onTracksChanged` 不再 reset override、空态 `audio_empty`、`isTrackSupported` 过滤 + 去重、selected 摘要、banner 抑制旧 audioText；pure 测试 11 项通过。但仍有两个源码缺陷/缺口：

1. **`onMediaItemTransition` 只清 UI override state，没有清 `player.trackSelectionParameters` 的实际 override**。换台后 UI 显示自动、旧 TrackSelectionOverride 仍留在 player 参数里（可能被新 TrackGroup 忽略，但参数状态不干净，后续同 group/回退时可复现）。
2. **`canSelect` 仍未接入 SettingsDrawer**：单音轨/无音轨行仍可点开；t179 只把 entryLabel 改成去重列表，未禁用/隐藏入口。且任务要求“有测试”的 Back 仍只有 pure 测试，没有 Activity/Compose 测试。

> 只读固定 commit `d85c0a3a`；HEAD `89bf442`（docs-only）。工作树有 t182 的 AutoUpdateScheduler/MainActivity/PlayerActivity 并发修改（未读取/未评价）。未改代码、未跑 Gradle/CI/ADB、未操作 TV。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`；被审查 commit `d85c0a3a4e24ef7c37fac44a57169fac19d53118`。
- `git show --stat`：6 files +185/−55：`TracksFlattening.kt`、`AudioTrackUi.kt`、`AudioTrackDialog.kt`、`ChannelInfoBanner.kt`、`PlayerActivity.kt`、`AudioTrackUiTest.kt`；docs `AUDIO_TRACK_UI2.md` 89bf442。
- pair：主 `13,043,603B` SHA `3968f6d9…1c15`、test `502,955B` `8303c83f…f2ee`、run 44830742229（run10115）、10115；设备未验证。
- 关键行：`PlayerActivity` Activity audio state L89/L113/L185、guards L242–364、`onTracksChanged` L626–633、`onMediaItemTransition` L635–639、`applyAudioOverride` L941–958；`TracksFlattening` 40 行；`AudioTrackUi` source/selected/dedupe；`AudioTrackDialog` hasAudio/audio_empty；`SettingsDrawer` audio row unchanged。

## 2. 已通过/正确部分

### AUDIO-BACK-01 — pass（源码级）
- Activity 新增 `isAudioDialogOpen`（L113）+ `setAudioDialogOpenController`/`onAudioDialogOpenChanged`（L185）；VideoPlayerScreen 用 `LaunchedEffect(audioDialogOpen)` 回传（L450）。
- 数字 L242、LAST/MEDIA_PREVIOUS L260、OK L267、LEFT L274、RIGHT L286、MENU L298、UP L354、DOWN L364 全部加 `!isAudioDialogOpen`；**BACK L305 首判 audio → setter(false)+return true**。
- `onAudioTrackClick` L1337–1342 **先 settingsDrawerOpen=false** 再 audioDialogOpen=true；不再叠层；Back 不会先关设置。
- `AudioTrackDialog` 根 testTag/`testTagsAsResourceId`；`onPreviewKeyEvent` Back 作为兜底。

### AUDIO-OVERRIDE-01（UI）— pass
- `onTracksChanged` 只 `audioTracks = TracksFlattening.audioTracks(tracks)`；不再 reset override（L626–633）。
- `onMediaItemTransition` 才把 `audioOverrideGroup/Track=-1`（L635–639）；选后 ▶/摘要不再因 track selection 回自动。
- `applyAudioOverride` 设置 `TrackSelectionOverride`（L954），不重建 player。

### AUDIO-EMPTY-01 — pass
- `AudioTrackUi.buildItems(emptyList())` 返回空（测试 `emptyListDegradesGracefully`）；`AudioTrackDialog` 新增 `hasAudio`，`!hasAudio || items.isEmpty()` 渲染 `audio_empty` 文本 `TestTag("audio_empty")`；无音轨不再显示自动行；`LaunchedEffect` 仅在 hasAudio&&items 非空请求焦点（不抢焦点）。
- `AudioTrackUi.entryLabel(empty)` “音轨选择（无音轨）”。

### AUDIO-RENDERER-01 / SUMMARY — 基本 pass
- `TracksFlattening.audioTracks` 只保留 `group.isTrackSupported(trackIndex)`；`Raw.selected=group.isTrackSelected(trackIndex)`；`AudioTrackUi.dedupe` 按 `(language,codec,channels)` 去重、selected 优先；`applyAudioOverride` 二次校验 `isTrackSupported`。
- `AudioTrackUi.currentTrack` = override → selected → first；`summary` 基于 currentTrack；banner 在 audioLabel 非空时抑制旧 `audioText`，不再重复声道。
- 测试新增 summary 跟随 selected、dedupe 合并重复保留 selected、不同轨道保留；JVM 134→137 全绿（CI 报告）。

### 信息条 / 无回归 / 体积 — pass
- `ChannelInfoBanner` 旧 audioText 仅在 audioLabel 空时显示；audioLabel 非空整行 `音轨 …`；null 省略。
- diff 仅 6 文件；未改 LoadControl 2500、MP2 AAR、零拷贝/0dp flat、频道键、数字 OSD、台标、最近观看、EPG、SR、品牌。
- APK 13,043,603B≤30MB；keep210/contract0；`check_player_threading` 无新增后台 Player。

## 3. 仍需修复

### AUDIO-OVERRIDE-02（medium/high）换台只清 UI state，不清实际 `trackSelectionParameters`

**证据**：
- `PlayerActivity` L635–639：
  ```kotlin
  override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
      audioOverrideGroup = -1
      audioOverrideTrack = -1
  }
  ```
  只改 Compose state；`applyAudioOverride` 之前设置的 `p.trackSelectionParameters` 里仍有 `TrackSelectionOverride(group.mediaTrackGroup, trackIndex)`。
- 新频道的 `Tracks` group 对象变化时 Media3 可能忽略旧 override，但**参数没有清除**；同 group/轨道复用、返回旧源或后续 track selection 重新评估时，旧 override 仍可能生效；UI 显示自动与实际参数不一致。
- 验收“换台后回到自动”应是**UI + 实际参数**都回自动。

**修复**：
- `onMediaItemTransition` 内（主线程）：
  ```kotlin
  val cleared = p.trackSelectionParameters.buildUpon()
      .clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
  p.trackSelectionParameters = cleared
  audioOverrideGroup = -1; audioOverrideTrack = -1
  ```
  或 `playGroupVariant`/`prepare` 前清一次；注意不要在 `onTracksChanged` 清（会重演 AUDIO-OVERRIDE-01）。
- 测试：选第 2 轨 → 新 media item → assert `p.trackSelectionParameters.overrides` 不含 audio override，UI auto。

### AUDIO-SINGLE-02（medium）`canSelect` 仍未接入 SettingsDrawer，单音轨/无音轨行仍可点开

**证据**：`SettingsDrawer` 音频行（t175 增加）没有 `enabled`/`canSelect` 参数；`onAudioTrackClick` 无条件 `audioDialogOpen=true`。`AudioTrackUi.canSelect(raws)` 只在 pure test 使用；t179 摘要“SINGLE-01/UI-01：canSelect/entryLabel 用去重列表”只做到 entryLabel 文本，未禁用/隐藏入口。
- 单音轨频道点开仍显示“自动 + 1 行”，选择无意义；无音轨点开显示 empty（可接受），但单音轨应 disabled/提示无需选择。

**修复**：`SettingsDrawer` 音频项增加 `enabled: Boolean`/`onAudioTrackClick` 受 `canSelect` 门控；单音轨显示“仅 1 条（无需选择）”并禁用；无音轨可只读提示或打开 empty dialog。补 Compose 测试。

### TEST-01（medium）Back/override/empty/renderer 仍无 Activity/Compose/真实 Tracks 测试

**证据**：`AudioTrackUiTest` 11 项只测 pure formatter/items/dedupe/summary/canSelect；**没有**：
- Activity `isAudioDialogOpen` guard/Back 只关音频（任务点 1 “有测试”未满足）；
- `onMediaItemTransition` 清实际 override；
- `AudioTrackDialog` `audio_empty`/testTag/Back；
- `TracksFlattening.audioTracks` 真实 `Tracks` 多 group/selected/supported；
- Settings 单音轨禁用。
JVM 137 全绿不能捕获 AUDIO-OVERRIDE-02/AUDIO-SINGLE-02。

**修复**：`createAndroidComposeRule`/Activity Scenario 测 Back/数字/D-pad；用 Media3 `Tracks` 构造器/Group 测 flatten；instrumented 测 override clear。若无法单测，至少设备脚本断言。

### AUDIO-DEDUPE-01（low/medium）去重键 `(language,codec,channels)` 会折叠真正不同的同参数轨

**证据**：`AudioTrackUi.dedupe` 用 `formatLanguage|formatCodec|channels` 作为唯一键；它本意折叠 MediaCodec/FFmpeg 重复，但**同一频道的两条不同音轨**（例如两条中文评论/主备音轨）参数完全相同也会被折叠，用户无法选择第二条。
**修复**：dedupe 仅针对“跨 renderer 的同一 stream”（可比较 `Format.id`/`label`/`language`+`codec`+`channels`+`trackGroup.id` 中的 renderer 身份），或保留相同参数但不同 `trackIndex` 的第二条；无可靠 stream id 时宁可多显示一行，不要丢真实轨道。

### BACK-03（low）`LaunchedEffect(audioDialogOpen)` 回传 Activity 的理论同帧窗口
- 打开 audioDialog 后同一帧立即 Back，Activity `isAudioDialogOpen` 仍 false；设备几乎不可达，但可像 recent 一样用 `SideEffect` 或 controller 直接回调；当前源码主路径 pass。

### 其他
- `TracksFlattening` 无直接 JVM 测试；`AudioTrackUi.dedupe` 的 selected 顺序 pass；`applyAudioOverride` 二次校验 pass。
- `AudioTrackDialog` empty 文本 `audio_empty` testTag 存在，无焦点；pass。

## 4. 真机/视觉未验证

- 选第 2 轨后 ▶ 保持、banner 同步、不回自动；换台后自动；Back 只关音频、数字/方向不穿透；无音轨显示 `audio_empty`；重复上报只一行；MP2/AAC 与 LoadControl 不回归——均归 acceptance2。本报告不宣称设备通过。

## 5. 放行判断

- **源码结论：needs_revision。** Back/UI override/empty/renderer/summary 主问题已修；但 **AUDIO-OVERRIDE-02 实际参数未清**、**SINGLE-02 未接线**、**TEST-01 缺 Back/override/真实 Tracks 测试** 需补。
- 建议顺序：**AUDIO-OVERRIDE-02 → AUDIO-SINGLE-02 → TEST-01 → DEDUPE/BACK 加固**；重出 r27 pair 后真机验收。

## 6. 只读命令与终态

`git rev-parse HEAD`、`git status --short`、`git show --stat d85c0a3a`、`git show d85c0a3a`、`read PlayerActivity.kt/AudioTrackUi.kt/TracksFlattening.kt/AudioTrackDialog.kt/ChannelInfoBanner.kt/SettingsDrawer.kt/AudioTrackUiTest.kt`。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 工作树 t182 并发修改未读取/未评价；本报告完成后停止读取目标源码。
