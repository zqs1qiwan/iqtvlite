# 上一台 / 最近观看 独立复查（t133 / run10100）

任务 t134（attempt 1），designer2，2026-11-21（Asia/Hong_Kong）。**结论：needs_revision。** 持久列表、N=10、去重、按 id 解析、缺失跳过、清空、设置入口、TV 列表布局基本正确；但两个核心行为在源码层不成立：

1. **最近观看打开时 Activity 没有 `recentChannelsOpen` 状态**：`dispatchKeyEvent` 的数字/D-pad/OK/MENU/BACK 仍按“无面板”处理，**Back 会走播放器双 Back 退出逻辑**，`RecentChannelsDialog` 的 Compose Back 处理根本接不到；数字/方向键还会在列表背后触发换台/开抽屉/设置。与“Back 只关列表”直接冲突。
2. **`stableKey` 使用每个 variant 的 `channel.id`/url**；`ChannelGrouper` 的 variants 是同一频道的不同 M3U 行，各自有唯一 id；`playGroupVariant` 又把当前 variant 记录为最近项，因此**线路/清晰度切换会被当成新台**，`playLastChannel` 还以 `defaultChannel` 而非 `currentVariantIndex` 判断当前台，违反 t133 的“线路/清晰度切换不算新台”。

> 只读固定 commit `65d33068`（t133）；HEAD `8668be3`（docs-only）。工作树有 t137 对 `LogoLoader/PlayerDrawer` 的并发修改（不属于本任务），未读取/未评价；本报告全部基于固定 commit。未改代码、未跑 Gradle/CI/ADB、未操作 TV。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`；被审查 commit `65d330682c86dee6354a13759eca77e8e429ad13`。
- `git show --stat`：7 files +544：`RecentChannels.kt`、`RecentChannelsDialog.kt`、`PlayerActivity.kt`、`Prefs.kt`、`SettingsDrawer.kt`、`RecentChannelsTest.kt`、`docs/RECENT_CHANNELS_LAST.md`。
- pair：主 `12,978,067B` SHA `73a814fc…41072`、test `502,955B` `8f185867…bf2e9`、run 44795717239（run98）、10100；设备未验证。
- 关键源码：`PlayerActivity` dispatch L231–354、`playLastChannel` L988–1005、`playGroupVariant` L882–898、`recentResolved` L1141–1160、`RecentChannelsDialog` L60–137；`RecentChannels` L1–104；`Prefs` 新增 L78–91；`SettingsDrawer` 最近入口 L141 附近。

## 2. 已通过部分（源码可接受）

### 上一台（部分）
- `dispatchKeyEvent` L248–251 处理 `KEYCODE_LAST_CHANNEL` / `KEYCODE_MEDIA_PREVIOUS`，仅 `ACTION_DOWN`、`repeatCount==0`；`playLastChannel()` L988 内检查 `drawerOpen/settingsDrawerOpen/qualityDialogOpen/recentChannelsOpen`；800ms 防抖；无上一台返回 false，不消费。
- **无上一台不动作**、**重复按键防抖**、**抽屉/设置/清晰度打开时不触发**（对 last 键本身）方向正确。

### 最近观看（部分）
- `RecentChannels.MAX=10`；`push` 最新在前、按 `stableKey` 去重、`take(MAX)`；`encode/decode` 单字符串、字段/条目分隔、非法输入安全；`resolve` 先 id 后 url、缺失静默跳过；`Prefs` `recent_channels_v1` + `clearRecentChannels`。
- 设置项“最近观看（N）”存在；`RecentChannelsDialog` 0dp 容器、`ChannelLogo`、`surfaceVariant` 焦点（无蓝框）、OK 回调、`Back` `onPreviewKeyEvent`、底部清空行。
- `recentResolved` 在 `recentTick`/`channelGroups`/`currentGroupIndex` 变化时解析当前列表；`recentCount` 回填设置项；清空后 `recentTick++` 刷新。
- **测试**：`RecentChannelsTest` 6 项覆盖最新/去重、cap、编码解码（含分隔符）、垃圾输入、缺失 id/url 回退、previous 选择；JVM 48 全绿（CI 报告）。

### 无回归 / 体积（pass）
- diff 仅上述 Recent/Prefs/PlayerActivity/SettingsDrawer/tests/docs；未改零拷贝 SurfaceView、频道键 166/167、数字 OSD、flat 0dp、MP2、台标缓存、EPG、SR、品牌/应用名。
- APK `12,978,067B`（≈12.38 MiB）≤30MB；`keep 210 identical` / contract missing 0 / dex 7 OK（CI）。

## 3. 阻断问题（needs_revision）

### BACK-01（high）最近观看没有 Activity 级状态，Back/数字/D-pad 仍作用于播放器

**证据**：
- `PlayerActivity` 只有 `isDrawerOpen`、`isSettingsDrawerOpen`、`isQualityDialogOpen`（L103–109），**没有 recent 状态**；`VideoPlayerScreen` 的 `recentChannelsOpen` 只是 Compose 局部 `remember`（L394），没有回传 Activity。
- `dispatchKeyEvent`：
  - 数字键 L234 仅检查 `!isDrawerOpen && !isSettingsDrawerOpen && !isQualityDialogOpen`；最近列表打开时仍进入数字缓冲（3s 后可能切台）。
  - D-pad L260/272/332/342、OK L253、MENU L284 同样没有 recent；列表背后会打开抽屉/设置/信息条/换台。
  - **BACK L291–330**：recent 不在判断内；若信息条/抽屉/设置/质量都关，直接走“再按一次退出app”→第二次 `finish()`；`RecentChannelsDialog` 的 `onPreviewKeyEvent` L86–91 在 Activity 之后，**收不到 Back**。因此“Back 只关列表”源码层不成立。
- `playLastChannel` 本身会检查 `recentChannelsOpen`，但 Activity 不知道 recent，last 键返回 false 后仍可能落空；数字/BACK 的破坏更直接。

**修复（可执行）**：
1. Activity 增加 `private var isRecentChannelsOpen = false` 与 `setRecentChannelsOpenController`/`onRecentChannelsOpenChanged` 回调。
2. `VideoPlayerScreen` 在 `recentChannelsOpen` 变化时（`LaunchedEffect`/`SideEffect`）调用 `onRecentChannelsOpenChanged`，把状态提升到 Activity；`RecentChannelsDialog` 的 `onClose`/`onSelect`/清空路径同步。
3. `dispatchKeyEvent` 所有 `!isDrawerOpen && !isSettingsDrawerOpen && !isQualityDialogOpen` 增加 `&& !isRecentChannelsOpen`；BACK 分支最前加 `if (isRecentChannelsOpen) { setRecentChannelsOpen?.invoke(false); return true }`。
4. 或把 RecentChannelsDialog 改成真正的 `Dialog`/`Popup` 窗口，由窗口自己消费 Back；但当前 Compose overlay 必须补 Activity 状态。

### RECENT-01（high）stableKey 按 variant 的 id/url，线路/清晰度切换被当成新台

**证据**：
- `ChannelGrouper.group` L64–66：`variants = members.mapIndexed { index, channel -> ChannelVariant(channel, ...) }`；`members` 是同一显示名的多个 M3U 行，每个 `Channel` 保留自己的 `id`（`M3uParser` L25 `id = row.getLong("id")`；Rust `m3u_provider_key` 优先 `tvg-id`/URL 哈希，`IdUniquifier` 保证同源重复项不同 id）。
- `RecentChannels.stableKey`：`id:${channel.id}` 或 `url:${channel.url}`；**不同 variant 的 id/url 不同**。
- `playGroupVariant` L896–897 每次切 variant 都 `recordRecentChannel(channel, groupIndex+1)`，即线路/清晰度切换会插入另一个 `id:` 条目。
- `RecentChannels.previous` 只比较 `entry.key != currentKey`；`playLastChannel` L990 用 `channelGroups[currentGroupIndex].defaultChannel` 作为当前 key——**非默认 variant 时当前 key 也错**；线路切换后按 last 可能回到同频道的另一条线路，而非“上一台”。

**修复**：
- 以**频道组**为最近观看身份：`RecentChannels.Entry` 增加 `groupKey`（或把 `key` 设为 `ChannelGroup.key`/稳定组 key）；`recordRecentChannel(group, channel, number)` 写入 group key；`previous` 用当前 group key 跳过；`resolve` 把 entry 映射到当前 `ChannelGroup`（按 group key，再按 id/url 回退）。
- 如果必须保留 channel id 解析，则新增 `groupKey` 字段与 `channelId/url` 并存；去重/previous 只看 groupKey，解析时再用 id/url 找 variant。
- `playLastChannel` 用 `channelGroups[currentGroupIndex].key`（以及 `currentVariantIndex` 的 channel）作为 current，不要用 `defaultChannel`；最近列表 `selected` 也按 group key。

### RECENT-02（medium）当前 variant 使用 defaultChannel，选中标记/上一台目标不一致

**证据**：`playLastChannel` L990 `current = channelGroups.getOrNull(currentGroupIndex)?.defaultChannel`；`recentResolved` L1158 `currentUrl = channelGroups.getOrNull(currentGroupIndex)?.defaultChannel?.url`。当用户通过 `QualitySelectorDialog` 选择非默认线路（`currentVariantIndex != defaultVariantIndex`）时：
- `previous` 可能把当前 variant 当作“上一台”（因为 current key 用 default）；
- 最近列表 `selected` 不标当前行；
- 回上一台可能回到同频道默认线路而非另一频道。
**修复**：用 `currentGroupIndex/currentVariantIndex` 取实际当前 `Channel`/`ChannelGroup`；`playChannel` 若需保持线路，明确 group+variant；不要在逻辑里用 `defaultChannel` 代替当前。

### UI-01（low）清空/空列表的焦点与清空行

**证据**：`RecentChannelsDialog` L72–74 只在 `visible && items.isNotEmpty()` 请求第一行焦点；`RecentActionRow`（清空）没有 `FocusRequester`。清空后 `items` 变空、第一行被移除，焦点落点不确定；空状态只有文本，清空行虽 `focusable` 但没有显式初始焦点。
**修复**：给清空行独立 `FocusRequester`；`items` 为空时请求清空行；清空后保持/恢复清空行焦点；Back 仍只关闭。

### UI-02（low）列表台标尺寸 40×28 与预取 64×40 不同键，不能直接命中 drawer 预取

**证据**：`RecentChannelsDialog` 的 `ChannelLogo(width=40.dp,height=28.dp)`（L161–166）会产生 `40x28|url` 内存/磁盘 key；t126 prefetch 只预取 `64x40`，最近列表首次打开仍需各自加载 10 张。`ChannelLogo` 确实复用 `LogoLoader`，但不是同一内存命中。
**修复**：最近列表改用 64×40 的 `ChannelLogo`（视觉可缩放到 40×28 容器/保持比例），或增加 40×28 的预取；至少在文档中不要宣称“直接命中 drawer 预取”。

### TEST-01（low）测试未覆盖清空持久化、variant/线路、Activity Back

**证据**：`RecentChannelsTest` 6 项覆盖 push/cap/codec/resolve/previous；**没有** `Prefs.clearRecentChannels` 后重启/再次读取、同一组不同 variant 的 previous、Activity 最近列表 Back 行为。因此 CI 全绿也漏掉上述两个 high。
**修复**：补 `RecentChannels` 组 key/变体测试；`Prefs` clear 内存测试（Robolectric 或纯存储抽象）；加 Compose/Activity 测试或至少手动验收脚本，断言 recent open 时 Back 只关列表、数字不触发。

## 4. 其他观察

- **`recentResolved` selected 字段未被 `RecentRow` 使用**：当前播放行没有可见标记；若设计要求“当前/播放标记”，需渲染。
- **`recentResolved` 只在 `recentTick/channelGroups/currentGroupIndex` 变化时重算**；`currentVariantIndex` 变化不重算 selected（与 RECENT-02 同源）。
- **`recordRecentChannel` 在 `playGroupVariant` 里每次实际切台都会 Prefs 读+写+`recentTick++`**；O(10)、SharedPreferences 内存缓存，不在主线程做磁盘 I/O，可接受；但频道快速切换时写放大，可合并/防抖。
- **`RecentChannels.encode` 只净化 title/logo/url，未净化 `key`**：URL/id 若含 `\u0001`/`\u0002` 会破坏解码；实际 URL 极少含控制符，low。
- **`RecentChannelsDialog` 清空无二次确认**：t133 说明是直接清空；如产品要求确认，另列。
- **设备未验证**：LAST/MEDIA_PREVIOUS 键值、抽屉/设置打开时行为、清晰度切换后 last、列表 OK/Back/清空、焦点/台标时延均归 acceptance2(t135)。

## 5. 放行判断

- **源码结论：needs_revision**。BACK-01 与 RECENT-01 是核心行为缺陷，必须修；RECENT-02 同源；UI/TEST 为加固。
- 修复顺序建议：**BACK-01 → RECENT-01/02 → UI-01/02 → TEST-01**；重出 r27 pair 后，acceptance2 验：
  - 最近列表打开时数字/D-pad/OK/MENU 不触发播放器；**Back 只关列表**（播放继续）；
  - 线路/清晰度切换后按 LAST/MEDIA_PREVIOUS 回到另一台；不把同频道 variant 记为上一台；
  - 列表 ≤10、台标、OK 切台并关抽屉、清空后重启仍空；
  - 零拷贝/频道键 166/167/数字 OSD/去圆角/MP2/台标缓存/EPG/SR/品牌无回退。
- **真机/视觉未验证**；本报告不宣称设备通过。

## 6. 只读命令与终态

`git rev-parse HEAD`、`git status --short`、`git show --stat 65d33068`、`git show 65d33068`、`read RecentChannels.kt/RecentChannelsDialog.kt/PlayerActivity.kt/Prefs.kt/SettingsDrawer.kt/RecentChannelsTest.kt`、`read docs/RECENT_CHANNELS_LAST.md`、`git show 65d33068:...`。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 工作树中 t137 的 `LogoLoader.kt`/`PlayerDrawer.kt` 并发修改未读取/未评价；本报告完成后停止读取目标源码。
