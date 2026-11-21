# 遥控习惯设置 独立复查（t186 / run10117）

任务 t190（attempt 1），designer2，2026-11-21（Asia/Hong_Kong）。**结论：needs_revision。** t186 的纯决策层 `RemoteHabits`、Prefs key/非法回退、设置五项+恢复默认、数字“需 OK”等待、遮罩优先、BACK 首判/双按规则、频道键禁用、默认五项方向都基本正确。但源码有两个直接命中验收的缺陷：

1. **设置改动不会“下一次按键即生效”**：`PlayerActivity.remote` 只在 `onCreate`/`onResume` 读取；设置抽屉是同一 Activity 内的 Compose overlay，打开/关闭不会触发 `onResume`。设置行虽然写了 Prefs 和 Compose state，但 Activity 的 `remote` 仍是旧值，直到真正离开并回到 PlayerActivity（或切后台）才更新。验收②–⑦都会失败。
2. **设置抽屉没有滚动容器**：`SettingsDrawer` 的 Column 是 `fillMaxSize()`（无 `verticalScroll`/`LazyColumn`），新增 6 行后，1080p 下“数据与更新/台标与缓存”很可能被推出屏幕，既有设置不可达/焦点丢失（不回归项）。

> 只读固定 commit `09a00e50`；HEAD `62f3957`（docs-only）。工作树有 t187 的 `AudioTrackUi/PlayerActivity/SettingsDrawer` 并发修改（未读取/未评价）。未改代码、未跑 Gradle/CI/ADB、未操作 TV。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`；被审查 commit `09a00e50145d1f75782951b6c76b538f0661de9c`。
- `git show --stat`：5 files +423/−4：`RemoteHabits.kt`、`RemoteHabitsTest.kt`、`Prefs.kt`、`PlayerActivity.kt`、`SettingsDrawer.kt`；docs `REMOTE_HABITS_SETTINGS.md` 62f3957。
- pair：主 `13,059,991B` SHA `bea840a3…e8d2`、test `502,955B` `ac897620…22ad`、run 44834204079（run10117）、10117；设备未验证。
- 关键行：`PlayerActivity` `remote` L113/L124–129/onCreate L137、digits L252–267、OK L279–289、BACK L322–374、UP/DOWN L376–415；`RemoteHabits` 104 行；`Prefs` L189–237；`SettingsDrawer` L146–147（Column fillMaxSize）、L202–241（遥控组）；`SettingsItem` L363–391（padding 14dp）。

## 2. 已通过/正确部分

### 设置项/Prefs — pass
- 新增“遥控器与操作”分组（L202–241），五项 + 恢复默认：上下键、OK键、Back键、数字键、频道键 166/167；行 tag `settings_remote_*`；`SettingsGroupHeader` 仍不可聚焦/0dp/无蓝框；Back 根关闭。
- `Prefs` key：`remote_updown/remote_ok/remote_back/remote_digits/remote_channel_keys`；`getRemoteSettings` 缺失/非法值走 `RemoteHabits.parse*` 默认；`resetRemoteSettings` remove 五 key；默认值 `CHANNEL_STEP/OPEN_DRAWER/CLOSE_OVERLAY/DIRECT/true`。
- `RemoteHabitsTest` 10 项：默认、非法回退、循环、UP/DOWN/OK/BACK 映射、overlay 穷举、digits、频道键独立、label；JVM 141→151 全绿（CI 报告）。

### 键路由 — 大部分 pass
- UP/DOWN：`dispatchKeyEvent` L376–415 按 `RemoteHabits.onUpDown`；`OPEN_DRAWER` 调 `sidebarController.openDrawer()`；`CHANNEL_STEP` 保留 `onChannelStep(∓1)`；166/167/PAGE_UP/DOWN 禁用时 L385/L405 `return true` 吞掉；D-pad 仍走 habit。
- OK：L279–289 默认 `OPEN_DRAWER` → `sidebarController.openDrawer()`，`CONFIRM` → `setInfoBannerOpen(true)`；无 overlay 时。
- BACK：L322–374 **先** audio/recent/quality/drawer/settings/info/catchup，再判断 `RemoteHabits.onBack(exit)`；`EXIT` 走 `super.dispatchKeyEvent(event)`；默认 `CLOSE_OVERLAY` 保留 2s 双按退出；遮罩优先成立。
- 数字：L252–261 按 `onDigit` 决定是否启动 3s 自动提交；`NEEDS_CONFIRM` 不启动 timer；L263–267 在数字 pending 时 BACK 清空、OK/ENTER `submitNumber()`，因此“先显示输入条等 OK”成立。
- 遮罩优先：所有分支先判 `isDrawerOpen||isSettingsDrawerOpen||isQualityDialogOpen||isRecentChannelsOpen||isAudioDialogOpen`；`RemoteHabits.onUpDown/onOk/onBack(overlay)` 也返回 NONE/CLOSE_OVERLAY；测试穷举 upDown×ok。
- 默认方向：UP/DOWN=换台、OK=打开列表、BACK=关抽屉/双按、数字=直接、166/167=启用（与 t190 验收口径一致；但 OK 与 t182 旧行为有差异，见 DEFAULT-01）。

### 信息条/上一台/数字 OSD/频道键 — 保留
- `setInfoBannerOpen`、`setPlayLastChannel`、`onChannelStep`、数字 OSD/3s/6 位、166/167 分支仍在；`LoadControl`/MP2/零拷贝/去圆角/台标/最近观看/EPG/SR/品牌 diff 未动。

### 体积/线程 — pass
- APK 13,059,991B≤30MB；keep210/contract0；无新增 Player/IO/线程；`check_player_threading` 无问题（CI 记录）。

## 3. 阻断/需修复

### REMOTE-INSTANT-01（high）设置改动不会在下一次按键生效

**证据**：
- `PlayerActivity` L113 `private var remote = RemoteHabits.DEFAULT`；L124–129 `onResume() { remote = Prefs.getRemoteSettings(this) }`；onCreate L137 读一次。
- 设置抽屉是 `VideoPlayerScreen` 内的 Compose overlay（同一 Activity），打开/关闭 **不会触发 Activity.onResume**。`SettingsDrawer` 的 `onCycleRemote*`（PlayerActivity L1382–1410）只写 `Prefs` + Compose `remoteHabits` state；Activity 的 `remote` 字段不更新。
- 因此：改“上下键=呼出列表”后下一次按 D-pad 仍走 CHANNEL_STEP；改 OK/Back/数字/频道键同样旧值；验收②–⑦全部不成立，直到 Activity 真正 pause/resume。
- t186 文档 §3 写“onResume 读，下一次按键即生效”与事实不符。

**修复**：
- Activity 增加 `setRemoteSettingsController`/`onRemoteSettingsChanged`，VideoPlayerScreen 每次 `remoteHabits` 变化时回传 `onRemoteSettingsChanged(remoteHabits)`；Activity 直接赋 `remote`（参考 t179 audio controller）。
- 或在 `dispatchKeyEvent` 开头 `remote = Prefs.getRemoteSettings(this)`（SharedPreferences 已缓存，主线程开销极小；但每键读盘不优雅，仍可先 controller）。
- 或 `SettingsDrawer` 关闭/行点击时回调 Activity。
- 测试：设置行点击后不 pause，直接发送 UP/OK/BACK/数字/166，断言新行为。

### UI-01（high/medium）设置抽屉无滚动，新增 6 行后既有分组可能不可达

**证据**：
- `SettingsDrawer` L146–147 `Column(modifier = Modifier.fillMaxSize())`，**没有** `verticalScroll`/`LazyColumn`；外层 300dp 宽、fillMaxHeight。
- 现有分组：基础 5 行 + 遥控 6 行 + 音频 1 行 + 数据 5 行 + 台标 4 行 ≈ 21 行；`SettingsItem` L388 `vertical=14.dp` + 文本，约 44–48dp/行 → 约 924–1008dp，再加 5 个 group header/spacers/标题/24dp padding，1080p 高度已超；数据/台标/缓存行会被推出底部，焦点无法滚动到。
- 这直接违反 t190 不回归“台标/最近观看/EPG 设置可用”和 0dp/焦点要求。

**修复**：
- 给 Column 加 `verticalScroll(rememberScrollState())`（或 LazyColumn），保留 drawable 内容；注意 focus 自动滚动（`bringIntoViewRequester`/LazyColumn 焦点）。
- 或把遥控组折叠/子页；在 1080p + 720p 设备验收能聚焦“台标与缓存/清理缓存/恢复默认”。
- 加 Compose 测试：21 行都能通过 D-pad 到达。

### TEST-01（medium）只有纯 RemoteHabits 测试，没有 Activity/Compose 验收覆盖

**证据**：`RemoteHabitsTest` 10 项只测纯函数；没有：
- `dispatchKeyEvent` 实际 UP/DOWN/OK/BACK/数字/166 路由；
- 设置抽屉修改后**不 pause** 下一次按键即时生效（正是 REMOTE-INSTANT-01）；
- 遮罩下按键不穿透（Activity 级，非纯函数）；
- 数字 NEEDS_CONFIRM 时 OK 提交/BACK 清空；
- 恢复默认后 Activity state 更新；
- 设置滚动/焦点。
JVM 151 全绿不能捕获事实缺陷。

**修复**：`createAndroidComposeRule`/ActivityScenario 或 instrumented 测试；至少用 fake `PlayerActivity`/robotic 测试 dispatchKeyEvent 与 settings 回调；补设置滚动 UI 测试。

### DEFAULT-01（low/medium）默认 OK 与 t182 升级前行为不一致

**证据**：t182 `PlayerActivity` L279 旧代码无条件 `setInfoBannerOpen(true)`；t186 默认 `Ok.OPEN_DRAWER`，OK 改为 `sidebarController.openDrawer()`。t186 文档/验收把“开列表”当默认，但相对 t182 实际源码是行为变化，`RemoteHabitsTest.defaultsKeepTheShippedBehaviour` 命名不准确。
**修复**：产品确认默认应为“打开列表”（当前实现）还是保持旧“信息条”；若保留当前，文档去掉“与 shipped exactly”措辞并列为刻意变更；若保持旧，默认改 `CONFIRM` 并更新测试/验收。

### CATCHUP-01（medium）OK 在回看时不再出信息条，可能打开频道抽屉

**证据**：UP/DOWN 分支 L378–380/L399–401 对 `isCatchupPlayback` 特判 `setInfoBannerOpen(true)`；**OK 分支 L279–289 没有 catchup 特判**，默认 `OPEN_DRAWER` 会在回看时打开频道列表（t182 旧代码 OK 是信息条）。回看中打开列表可能中断回看上下文。
**修复**：OK 分支加 `if (isCatchupPlayback) { setInfoBannerOpen(true); return true }`，或明确产品允许回看开列表并文档化；补 catchup 测试。

### DOC-01（low）文档分组位置与代码不一致
- 文档 §1 写“Placed after 数据与更新, before 台标与缓存”；代码 L202 在 **基础之后、音频与字幕之前**，数据与更新在 L262。请改文档或调整顺序（建议文档跟代码：基础→遥控→音频→数据→台标）。

### REMOTE-BACK-01（low）`EXIT` 走 `super.dispatchKeyEvent`
- L365–366 直接把 BACK 交给 super，绕过 `onBackPressedDispatcher`/predictive back；TV 上通常 finish，但若宿主/父 Activity 有自定义 onBackPressed，行为不确定。建议显式 `finish()`（或 `onBackPressedDispatcher.onBackPressed()`）并消费事件，保证“单按退出”。

## 4. 真机/视觉未验证

- 默认上下换台/OK 开列表/Back 双按退出/数字 3s 跳台/166-167 换台；切上下=列表；切 OK=确认；切 Back=直接退出且遮罩先关；数字=需 OK；禁用频道键后 D-pad 仍换台；恢复默认；设置 21 行可达/滚动——均归 acceptance2。本报告不宣称设备通过。

## 5. 放行判断

- **源码结论：needs_revision。** 纯逻辑/Preference 路由/数字等待/遮罩优先正确；但 **REMOTE-INSTANT-01（设置不即时）** 与 **UI-01（设置无滚动可能不可达）** 是明确缺陷，TEST-01 需补。
- 建议顺序：**REMOTE-INSTANT-01 → UI-01 → TEST-01 → CATCHUP/DEFAULT 产品确认 → DOC/BACK 清理**；重出 r27 pair 后真机验收。

## 6. 只读命令与终态

`git rev-parse HEAD`、`git status --short`、`git show --stat 09a00e50`、`git show 09a00e50`、`read PlayerActivity.kt/RemoteHabits.kt/Prefs.kt/SettingsDrawer.kt/RemoteHabitsTest.kt`、`Get-Content docs/REMOTE_HABITS_SETTINGS.md`。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 工作树 t187 并发修改未读取/未评价；本报告完成后停止读取目标源码。
