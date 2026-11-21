# 最近观看 Back / 身份 / 变体 修复复查（t141 / run10102）

任务 t142（attempt 1），designer2，2026-11-21（Asia/Hong_Kong）。**结论：needs_revision。** t141 已修好 t134 的 **BACK-01**（Activity 级 recent guard）、**RECENT-01 新记录身份**（groupKey）、**RECENT-02 recentResolved 选中**，UI-01/02/03、encode、no-regression 也基本到位。但还有两个核心功能缺陷：

1. **`playLastChannel` 绕过了 `RecentChannels.resolve`**：只按 `group.key` 精确匹配、直接传 `previous.variantIndex`。旧版 legacy `url:` 行、刷新后 group key 变化、variant 越界都找不到/不切换；而且 `lastChannelSwitchAt` 已置位、函数返回 true，用户按键却无反应。
2. **400ms 合并不维护内存 pending 列表**：`recordRecentChannel` 每次都从 **Prefs** 读旧列表、只把结果放 `pendingRecentEncoded`；连续 A→B→C 时 B 被丢掉；`recentResolved` 也读 Prefs，且 `recentTick++` 在写盘前，写盘后不再 tick → 最近列表/上一台在写盘后仍可能显示旧数据；pending 在页面销毁时未 flush。

此外 `resolve` 的 variant 选择仍只按 index，忽略 `fallbackUrl`（刷新/重排后可能选错线路），legacy 行与新 group 去重/previous 未迁移；测试未覆盖 clear/debounce/legacy/fallback。

> 只读固定 commit `5d4c36e0`；HEAD `40f6f38`（docs-only）。工作树有 t145 对 `LogoLoader/PlayerDrawer` 的并发修改（未读取/未评价）。未改代码、未跑 Gradle/CI/ADB、未操作 TV。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`；被审查 commit `5d4c36e07f5fa9ce5be2bb3d77bced7534d665c4`。
- `git show --stat`：4 files +252/−133：`RecentChannels.kt`、`RecentChannelsDialog.kt`、`PlayerActivity.kt`、`RecentChannelsTest.kt`；docs `RECENT_CHANNELS_LAST2.md` 40f6f38。
- pair：主 `12,978,067B` SHA `30095a73…810a`、test `502,955B` `2b021cdf…99de`、run 44800974359（run100）、10102；设备未验证。
- 关键行：`PlayerActivity` `isRecentChannelsOpen` L108、数字/方向/OK/MENU/BACK/上/下 guard L235–360、`recordRecentChannel` L891–911、`playGroupVariant` L914–924、`playLastChannel` L1018–1028、`recentResolved` L1174–1190、dialog callbacks L1257–1285；`RecentChannels` L1–140；`RecentChannelsDialog` L60–205；`RecentChannelsTest` 8 tests。

## 2. 已修复/通过项

### BACK-01 — pass
- Activity 新增 `isRecentChannelsOpen`（L108）+ `setRecentChannelsOpenController/onRecentChannelsOpenChanged`（L178–179），`VideoPlayerScreen` 用 `LaunchedEffect(recentChannelsOpen)` 回传（L407）。
- 数字 L235、OK L260、LEFT L270、RIGHT L279、MENU L291、UP L343、DOWN L353 全部加 `&& !isRecentChannelsOpen`；`KEYCODE_LAST_CHANNEL/MEDIA_PREVIOUS` 先判任一 overlay 打开则 `return false`；**BACK L301–304 首判 recent → setter(false) + return true**，不会再走双 Back 退出。
- 结论：t134 的 BACK-01 已修复（源码层）。

### RECENT-01 新记录身份 — pass（新数据）
- `RecentChannels.Entry` 改 `groupKey`；`push(current, group, number, variantIndex)` 以 `group.key` 去重；quality/line 切换只更新同一条的 `variantIndex/fallbackUrl`（测试 `pushDeduplicatesByGroupKey`）。
- `previous(entries, currentGroupKey)` 按 group key 跳过当前组；新数据线路切换不再算新台。
- `recentResolved` 用 `ChannelGroup`：`title=group.displayTitle`、`number=groupIndex+1`、`logo=group.logoUrl`、`selected=group.key==currentGroupKey`；UI selected 现在渲染 `▶`。

### RECENT-02 当前 variant — 部分 pass
- `playLastChannel` 已改用 `currentGroupIndex.group.key`（不再 defaultChannel），`recentResolved` 也按 group；当前组识别正确。
- **但 `playLastChannel` 不 resolve、variant 越界/fallback 不处理**，见 §3 PLAY-01。

### UI-01/02/03 — pass
- `RecentChannelsDialog` 新增 `clearRequester`；`LaunchedEffect(visible, items.size)` 列表非空聚焦首行、空/清空后聚焦清空行（L70–80）。
- 台标统一 64×40（同 t126 prefetch 缓存键）；`RecentRow` 渲染 `▶ 编号. 名称`；无蓝框，仍 surfaceVariant。
- 清空行 `RecentActionRow` 接收 FocusRequester。

### ENCODE/PERF 部分
- `sanitize` 对 groupKey/title/logo/url 替换分隔符；legacy 5 字段 decode 为 `url:<fallback>`；`push` 防组合 `variantIndex` 越界。
- 400ms 防抖合并写方向正确；`onClear` 先 cancel job + pending=null 再 `Prefs.clearRecentChannels`，修 t140 清空回写。

### 无回归 / 体积 — pass
- diff 仅 Recent/Prefs? 本 commit 只 `RecentChannels/RecentChannelsDialog/PlayerActivity/RecentChannelsTest`；Prefs/SettingsDrawer 未动；未改零拷贝/频道键/LoadControl/0dp flat/MP2/台标缓存/EPG/SR/品牌。
- APK 12,978,067B≤30MB；keep 210 identical / contract missing 0 / dex 7 OK（CI）。

## 3. 仍需修复

### PLAY-01（high）`playLastChannel` 绕过 resolve，legacy/刷新/variant 越界无回退且返回 true 不切换

**证据**：`PlayerActivity.kt` L1018–1028：
```kotlin
val currentGroupKey = channelGroups.getOrNull(currentGroupIndex)?.key
val previous = RecentChannels.previous(entries, currentGroupKey) ?: return false
val targetGroupIndex = channelGroups.indexOfFirst { it.key == previous.groupKey }
if (targetGroupIndex < 0) return false
lastChannelSwitchAt = now
playGroupVariant(targetGroupIndex, previous.variantIndex)
infoBannerOpen = true
return true
```
- 没有调用 `RecentChannels.resolve(entries, channelGroups)`；因此：
  - t133 legacy 行 `groupKey = "url:<fallback>"` 不会匹配任何 `ChannelGroup.key` → `targetGroupIndex<0`，上一台不动作（而 `resolve` 已支持 url fallback，测试 `resolveSkipsMissingGroups...`）。
  - 播放列表刷新后 group key 变化（`resolve` 会走 `fallbackUrl`）→ 同样找不到。
  - `previous.variantIndex` 越界（刷新丢线路）时 `playGroupVariant` L885 `getOrNull` 返回 null，函数直接 return；但 `playLastChannel` 已 `lastChannelSwitchAt=now; infoBannerOpen=true; return true` → **按键被消费但没切台**，800ms 内重按也无效。
- 相比之下 `RecentChannels.resolve` 会把 variant coerce 到 0（但 fallback URL 也忽略，见 VARIANT-01），至少能切；`playLastChannel` 应复用它。

**修复**：
```kotlin
val resolved = RecentChannels.resolve(listOf(previous), channelGroups).firstOrNull() ?: return false
val groupIndex = channelGroups.indexOfFirst { it.key == resolved.second.key }
val variantIndex = resolved.second.variants.indexOfFirst { it.channel.url == resolved.third.url }
if (groupIndex < 0 || variantIndex < 0) return false
lastChannelSwitchAt = now
playGroupVariant(groupIndex, variantIndex)
infoBannerOpen = true
return true
```
并确保 `lastChannelSwitchAt` 只在确定目标存在后设置；或让 `playGroupVariant` 返回 Boolean 供判定。

### DEBOUNCE-01（high）400ms 合并不维护 pending 列表，连续切台丢历史且 UI 读旧 Prefs

**证据**：
- `recordRecentChannel` L891–911：
  ```kotlin
  val updated = RecentChannels.push(
      RecentChannels.decode(Prefs.getRecentChannels(context)), // 读已落盘旧值
      group, number, variantIndex)
  pendingRecentEncoded = RecentChannels.encode(updated)
  recentWriteJob?.cancel()
  recentWriteJob = scope.launch { delay(400); pendingRecentEncoded?.let { Prefs.set... }; pendingRecentEncoded=null }
  recentTick++
  ```
  连续 A→B→C：B 的记录读到的仍是 A（B 未写盘），push C 得到 `[C,A]`，**B 丢失**；应在 pending 存在时 decode `pendingRecentEncoded` 作为 current。
- `recentResolved` L1175 读 `Prefs.getRecentChannels`，**不读 pending**；`recentTick++` 发生在写盘**前**，写盘完成后没有再次 `recentTick++`。所以：
  - 切台后 400ms 内打开最近观看，列表不含新台；
  - 写盘完成后，`remember(recentTick, channelGroups, currentGroupIndex)` 的 key 未变，**列表不会刷新**，仍显示写盘前的旧数据，直到再次切组/清空。
- `playLastChannel` L1022 也直接从 Prefs 读，pending 未落盘时按“上一台”可能回到更早的台（连续切台时）。
- `recentWriteJob` 在 `VideoPlayerScreen` 的 `rememberCoroutineScope` 中，**onStop/dispose 未 flush**；切台后立即退出，最后一次记录可能丢失。

**修复**：
1. 维护内存 `recentEntries` state（或至少 `pendingRecentEncoded`）；`recordRecentChannel` 优先 decode pending，其次 Prefs；`recentResolved`/`playLastChannel` 读同一内存源（pending ?: Prefs）。
2. 写盘完成后 `recentTick++`（或更新内存 entries 后 tick），确保 UI 刷新。
3. `onStop`/`DisposableEffect` `flushRecentIfPending()`（同步 `Prefs.setRecentChannels`，不 delay）；`onClear` 先 cancel+clear pending 再清 Prefs。
4. 可保留 400ms 合并写，但**不能丢失中间频道**；若产品要求“上一台”严格最近，应维护内存 list 而不是只保留最后 encoded。

### VARIANT-01（medium）`resolve` 只按 `variantIndex`，未用 `fallbackUrl` 选线路

**证据**：`RecentChannels.resolve` L?：
```kotlin
val variant = e.variantIndex.coerceIn(0, (group.variants.size - 1).coerceAtLeast(0))
Triple(e, group, group.variants[variant].channel)
```
- `Entry.fallbackUrl` 在 `push` 时保存当前 variant URL，但 resolve 只在 **group 查找**用 `byUrl[e.fallbackUrl]`；**variant 选择完全不用它**。
- 播放列表刷新/线路重排后，stored `variantIndex` 可能指向另一条线路；应优先 `group.variants.indexOfFirst { it.channel.url == e.fallbackUrl }`，找不到再用 index/0。
- 测试 `effectiveVariantFallsBackWhenOutOfRange` 只有单 variant、fallbackUrl 与 index0 相同，未覆盖“多 variant + fallback URL 不同”的场景。

**修复**：resolve variant：
```kotlin
val byFallback = group.variants.indexOfFirst { it.channel.url == e.fallbackUrl }
val variant = if (byFallback >= 0) byFallback else e.variantIndex.coerceIn(...)
```

### LEGACY-01（medium）legacy `url:` 行未参与新 group 去重/previous

**证据**：`decode` legacy 5 字段生成 `groupKey="url:<url>"`（正确用于 resolve fallback），但：
- `push` 只 `filterNot { it.groupKey == entry.groupKey }`；同频道 legacy `url:` 与新 `groupKey` 不会去重，升级后第一次切台会留下重复旧行，直到 cap。
- `previous(entries, currentGroupKey)` 只比较 `groupKey`；legacy `url:` 与当前 groupKey 不同，会把它当“上一台”，可能切回同一频道（resolve 会把 legacy url 映射到当前 group）。
**修复**：push/previous 前把 legacy 行归一化为当前 group（可用 `resolve`/`fallbackUrl` 映射），或 `push` 过滤 `groupKey==key || (startsWith("url:") && fallbackUrl==channel.url)`；`previous` 用 resolve 后的 groupKey 比较。

### TEST-01（low/medium）未覆盖 clear/debounce/legacy/fallback/Activity Back

**证据**：`RecentChannelsTest` 8 项覆盖 group 去重/上限/编解码/legacy decode/缺失/ stored variant/previous/越界；但：
- 无 **clear 后 immediate empty + 重启空**（Prefs/清空回写）；
- 无 **400ms debounce 连续 A→B→C 保留 B**；
- 无 **legacy `url:` 与新 group 的 push/previous 去重**；
- 无 **fallbackUrl 与 variantIndex 不一致**的多线路测试；
- 无 Activity Back/overlay guard（需 Compose/instrumented）。
这些缺口正好覆盖 PLAY-01/DEBOUNCE-01/VARIANT-01/LEGACY-01。

### 其他 low
- **BACK-01 状态回传延迟**：`LaunchedEffect(recentChannelsOpen)` 在组合后执行；若最近列表打开的同帧立即按 Back，理论上 Activity 仍 false；设备几乎不可达，可改用 `SideEffect`/控制器直接回调（低）。
- **`recentWriteJob` 未在 `onClear` 后置空**（仅 `recentWriteJob=null`；okay）。
- **`recentResolved` 的 `selected` 按 group**，variant 切换不刷新 selected（同一 group，selected 仍 true，可接受）。
- **`RecentChannelsDialog` 64×40 台标**现在同缓存键，pass。

## 4. 真机/视觉未验证

- 清空→立即空→重启仍空；抽屉/设置打开时按 229 不切台且不穿透；清晰度切换后 LAST 回另一频道；OK 选最近台关列表回播放；Back 只关列表；焦点无蓝框；台标复用缓存；均归 acceptance2(t143)。本报告不宣称设备通过。

## 5. 放行判断

- **源码结论：needs_revision。** BACK-01/新 group 身份/UI 修复已到位；**PLAY-01 与 DEBOUNCE-01 是核心功能缺陷**，VARIANT-01/LEGACY-01 需补；TEST 需覆盖这些路径。
- 建议顺序：**DEBOUNCE-01 → PLAY-01 → VARIANT-01 → LEGACY-01 → TEST-01**；重出 r27 pair 后再让 t143 真机验证。

## 6. 只读命令与终态

`git rev-parse HEAD`、`git status --short`、`git show --stat 5d4c36e0`、`git show 5d4c36e0`、`read RecentChannels.kt/RecentChannelsDialog.kt/PlayerActivity.kt/RecentChannelsTest.kt`、`Get-Content docs/RECENT_CHANNELS_LAST2.md`。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 工作树 t145 的 `LogoLoader/PlayerDrawer` 并发修改未读取/未评价；本报告完成后停止读取目标源码。
