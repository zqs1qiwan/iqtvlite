# 台标缓存 IO / 预取 / 取消 修复复查（t130 / run10099）

任务 t131（attempt 1），designer2，2026-11-21（Asia/Hong_Kong）。**结论：needs_revision。** t130 已把 `stats` 改为 suspend+IO、设置摘要改为仅可见时 `produceState`、`clear*/invalidate/refreshGroup` 的磁盘部分切到 IO，并把抽屉预取改为 `snapshotFlow` 的“可视窗口 ∪ 选中 index±8 ∪ 前60”，主线程磁盘 IO 问题基本消除。但 **CANCEL-01 在 `prefetch` 里仍被 `runCatching` 吞掉**，**CACHE-02 的 generation 只检查“写盘前”且磁盘命中路径不检查**，以及 **`prefetch` 的 80 上限可能截掉高 index 的可视/选中项**；需要再修后重出 pair。真机主线程/时延/卡顿未验证（acceptance2）。

> 只读固定 commit `036c1fc3`；HEAD `a0fc664`（docs-only）。工作树另有 t133 的 `Prefs.kt` 修改和未跟踪 `RecentChannels.kt`（并发任务），本复查**不读取、不评价**，全部结论基于 `036c1fc3` 固定 blob。未改代码、未跑 Gradle/CI/ADB、未操作 TV。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`；被审查 commit `036c1fc3f2e8ef3a6c9b39198c48fa1a47f9f93e`（t130）。
- `git show --stat`：4 files +165/−54：`LogoLoader.kt`、`PlayerActivity.kt`、`PlayerDrawer.kt`、`LogoCacheKeysTest.kt`；`docs/LOGO_CACHE_PREFETCH2.md` 为 a0fc664 docs。
- pair：主 `12,961,683B` SHA `c7c3077e…905de`、test `502,955B` `ae6226b6…c99e7`、run 44792282251（run97）、10099；设备未验证。
- 固定 blob 关键行：`LogoLoader.kt` `stats` L135–144、`clearMemory/clearDisk/clearAll` L146–162、`invalidate` L165–178、`refreshGroup` L184–188、`loadInternal` L272–374、`prefetch` L380–392、`pruneDiskIfNeeded` L394–407；`PlayerDrawer.kt` L268–284；`PlayerActivity.kt` L1100–1157；`LogoCacheKeysTest.kt` 5 tests。

## 2. 已修复/通过项（源码可接受）

### CACHE-01 — 主线程磁盘 IO（pass）
- `stats` **L135** `suspend fun ... = withContext(Dispatchers.IO)`；`PlayerActivity` **L1100–1110** 改为 `produceState(initialValue="", settingsDrawerOpen, logoCacheTick)`，`settingsDrawerOpen` 为 false 时直接 `""`，**首次组合不再扫盘**；打开设置/清理后 tick 才在 IO 统计。
- `clearDisk` L151 `withContext(IO)`；`invalidate` 内存删除后 L172 `withContext(IO)` 删盘；`refreshGroup` L184 调用的 `invalidate` 已 suspend+IO；`clearAll` L159 先 `clearMemory`（仅内存）再 `clearDisk`（IO）。
- `onRefreshLogos` L1141 先 Toast“正在刷新…”再 `scope.launch { refreshGroup }`；`onClearLogoCache` L1149 先 Toast“正在清理…”再 `scope.launch { clearAll }`。**主线程不再直接做 `listFiles/sumOf/delete`**（内存 map 清理仍为同步，成本低）。
- 结论：t127 的 **CACHE-01 主线程扫盘问题已修复**；`stats` 只在设置可见时 IO 计算，符合要求。

### PREFETCH-01 — 范围/防抖/key（基本通过）
- `PlayerDrawer` **L268–284**：`LaunchedEffect(visible, selectedGroup, channels, selectedChannelUrl)`；`snapshotFlow { channelListState.layoutInfo.visibleItemsInfo.map { it.index } }` + `collectLatest` + `delay(200)`；集合为可见 index ∪ `selectedIdx ± 8`（selectedIdx 找不到时回退 firstVisible）∪ `0 until min(60, size)`，按 index 排序去重。当前台 index≥60、滚动后可覆盖；key 含 channels/selectedChannelUrl。
- `LogoLoader.prefetch` **L380–392** 去重后 `.take(80)`、`chunked(3)` + `async/awaitAll`，`ensureActive` 在层间；`Semaphore(3)` 保留。
- 结论：范围和 snapshotFlow 防抖方向正确；细节截断见 §3 PREFETCH-02。

### PERF-02 / 磁盘上限（基本通过）
- `prefetch` 分块 3 并发（L384–391），保留 80 上限/去重；`MAX_DISK_BYTES = 32 MiB`（L55 附近），`pruneDiskIfNeeded` L394–407 在写盘后按 `lastModified` 最旧优先删除。
- `cacheGeneration` 为 `@Volatile`（L57 附近），`clearMemory/clearDisk/invalidate` 递增；网络下载结果写盘前检查 `gen != cacheGeneration`（L358）。
- `LogoCacheKeysTest` 5 项覆盖内存/磁盘键格式、尺寸区分、sha256 稳定性；JVM 37→42 全绿（依赖报告）。

### 无回归 / 体积（pass）
- diff 仅 `LogoLoader/PlayerActivity/PlayerDrawer/新增测试`；未改零拷贝 SurfaceView 基座、频道键 166/167、LoadControl、flat UI/去圆角、MP2 AAR、EPG、SR、品牌/app name。
- APK 12,961,683B（≈12.36 MiB）≤30MB；keep 210 identical / contract missing 0 / dex 7 OK（CI）；无新增 native/ABI。

## 3. 仍需修复（needs_revision）

### CANCEL-01（medium）`prefetch` 仍用 `runCatching` 吞掉 CancellationException

**证据**：`LogoLoader.kt` L389：
```kotlin
chunk.map { url -> async { runCatching { load(context, url, targetWidthPx, targetHeightPx) } } }.awaitAll()
```
`runCatching` 捕获 `Throwable`，包括 `CancellationException`；`loadInternal` 虽已在网络/解码块 rethrow（L332–336/L350–354），但 `prefetch` 的 `runCatching` 会把它转成 `Result.failure`，`awaitAll` 正常返回，层间 `ensureActive` 只在 chunk 之间执行。`PlayerDrawer` 的 `collectLatest` 取消时，若已进入当前 chunk，**不能保证取消被传播/停止后续 URL**；与 t130 doc“closing the drawer cancels the whole prefetch”不一致。

**修复**：
```kotlin
async {
    try { load(context, url, w, h) }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }
}
```
并在每个 chunk/await 前后 `currentCoroutineContext().ensureActive()`；`runCatching` 不能用于会取消的 suspend 调用。

### CACHE-02a（medium）generation 检查只在“网络写盘前”，磁盘命中路径仍可返回/写回 stale

**证据**：
- `loadInternal` 磁盘命中 L289–300（首查）和 L309–320（permit 内二查）直接 `putCached(memoryKey, imageBitmap)` 返回，**没有比较 `gen`**；清理/刷新发生在 decode 与 `putCached` 之间时，会把旧磁盘图写回内存。
- 网络路径 L358 只在 `withContext(IO){ writeBitmapToDisk; prune }` **之前**检查；`clearAll` 若在 L358 之后、`writeBitmapToDisk` 完成前执行，旧图仍会写盘，随后 L366 `putCached` 写回内存（检查-写入 TOCTOU）。
- `refreshGroup` 对每个 URL 调用 `invalidate`（global `cacheGeneration++`），所有 in-flight 下载的结果都可能被丢弃；这是“安全但过宽”的副作用，可见行加载可能被无差别取消/不缓存。

**修复**：
- 磁盘命中 decode 后、`putCached` 前检查 `gen == cacheGeneration`；不匹配则丢弃 bitmap（不要缓存）。
- 写盘完成后再次检查 generation；不匹配则删除刚写文件并跳过 `putCached`；更稳妥把“写盘+检查+putCached”放进带锁/原子提交段。
- 将 `cacheGeneration` 改为按 URL key 的 generation（或 `invalidatedKeys` 集合），`invalidate(url)` 只影响该 url；`clearAll/clearDisk` 才全量递增；`refreshGroup` 不应因一个 URL 让全部其他 URL 的 in-flight 失效。

### PREFETCH-02（low/medium）`prefetch` 的 `.take(80)` 可能截掉高 index 的可视/选中项

**证据**：`PlayerDrawer` 集合顺序是按 index 排序（L281）；`LogoLoader.prefetch` L385 再 `.take(80)`。当 first60 + 可见窗口（约10）+ selected±8（17）超过 80，且选中/可视位于高 index 时，排序后的尾部（高 index 的可见/选中）会被截断。例：first60 + 可见 100–110 + selected 190±8 ≈ 88 项，`take(80)` 只能覆盖到 selected 的低端；若可见/选中都在高 index，可能部分漏预取。文档写“union”，但实际有上限。
**修复**：把 `visible + selected±8` 放在 `first60` 之前（或分两档：优先集合全量、first60 补齐到 80/100）；`prefetch` 接收已排序的优先 URL 列表，不要在尾部盲目 `take`；或把上限提高到 100–120 并保留优先顺序。

### CACHE-02b / PERF-02a（low）`pruneDiskIfNeeded` 每次写盘都全量扫描/排序

**证据**：`writeBitmapToDisk` 后 L360–363 每个 URL 调 `pruneDiskIfNeeded`；`pruneDiskIfNeeded` L394–407 对 32MB 目录 `listFiles` + `sumOf` + `sortedBy`。预取 60 个新 logo 会产生约 60 次全目录扫描排序（O(n²) IO/CPU），可能拖慢后台预取、增加 IO 抖动；虽在 IO 线程，仍与“低成本预取”目标冲突。
**修复**：批量写盘后裁剪一次，或节流（如 2–5s 内最多一次/按写入计数）；维护累计大小，超阈值才扫描；`prune` 在 `Dispatchers.IO` 单例锁下串行。

### PERF-02b / TEST-02（low）
- `prefetch` 3 并发是 **chunk 串行**（wait 3 完再下一组），符合上限但不是“持续 3 并发”；可接受，若目标吞吐可改 `Semaphore`+`async` 全量（仍限3）。
- `LogoCacheKeysTest` 只锁 key 格式；建议补 `cacheGeneration`/clear 后 in-flight 丢弃、`prefetch` 取消、`stats` 计数的纯 JVM/可注入测试（不依赖 Android Context 的部分）。

## 4. 功能保留 / 设置入口

- `SettingsDrawer` 的“台标与缓存”区块（用量/刷新当前组/清理）仍在；`logoCacheSummary` 由 `produceState` 提供，只有设置可见时非空；默认焦点仍“源配置”、Back/Left 仍关闭设置，未破坏 TIV-11 根路径。
- `PlayerDrawer` 预取仍不修改列表状态，不 rebind；`ChannelLogo` 组件和 LRU96/磁盘键保持不变；设置入口可用（真机反馈未验）。
- **UI-01（low）**：设置仍是扁平方块而非子页分组，属 F7 后续；t130 不阻断。

## 5. 放行判断

- **源码结论：needs_revision。** CACHE-01/PREFETCH-01 主问题已修，但 **CANCEL-01 仍被 `runCatching` 吞取消**、**CACHE-02 generation 对磁盘命中/写盘 TOCTOU 不完整**、**prefetch 80 截断可能漏高 index 可视/选中**是明确的源码缺陷；修复后再请 acceptance2 验证。
- **真机未验证**：主线程磁盘 IO（StrictMode/Perfetto）、冷缓存可视行首图 ≤1s、滚动增量、设置清理/刷新反馈、抽屉 jank/CPU、频道键/数字 OSD/MP2/零拷贝回归均归 t132/acceptance2；本报告不宣称设备通过。
- **建议顺序**：CANCEL-01 → CACHE-02a/b → PREFETCH-02 → 可选 prune 节流；重出 r27 pair 后再测。

## 6. 只读命令与终态

`git rev-parse HEAD`、`git status --short`、`git show --stat 036c1fc3`、`git show 036c1fc3`、`read LogoLoader.kt`、`read PlayerDrawer.kt`、`read PlayerActivity.kt`、`read LogoCacheKeysTest.kt`、`Get-Content docs/LOGO_CACHE_PREFETCH2.md`。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 工作树中 t133 的 `Prefs.kt`/`RecentChannels.kt` 未读取/未评价；本报告完成后停止读取目标源码。
