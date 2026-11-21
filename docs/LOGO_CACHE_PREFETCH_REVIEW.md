# 台标预取 / 缓存 / 设置入口 独立复查（t126 / run10098）

任务 t127（attempt 1），designer2，2026-11-21（Asia/Hong_Kong）。**结论：needs_revision（源码层不通过）。** 预取主体、in-flight 去重、磁盘键、API 命名、设置入口方向正确；但 **`LogoLoader.stats/clear/refreshGroup` 的磁盘 I/O 被放在 Compose 主线程/主调度器**（`logoCacheSummary` 首次 composition 即执行 `stats()`，`clearAll()` 直接主线程删文件，`refreshGroup()` 的 `invalidate` 在 main `scope.launch` 内循环扫盘），与“不阻塞主线程/不增抽屉卡顿/冷启不倒退”的验收直接冲突；另外抽屉预取只取当前组前 60，不是“可视窗口 + 其后 N/当前滚动位置”，大组中当前台/可视行可能不在预取范围。真机时延/卡顿未验证（acceptance2）。

> 只读固定 commit `013ec891`（工作树 clean，HEAD `6fa5005` docs-only）；未改代码、未跑 Gradle/CI/ADB、未操作 TV。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`；HEAD=`6fa5005`（docs-only），`013ec891..HEAD` 无代码改动。
- 被审查 commit `013ec891dbecd49302f0e2e25f719d7a79c545f7`；diff `5 files, +215/−3`：`LogoLoader.kt`、`PlayerActivity.kt`、`PlayerDrawer.kt`、`SettingsDrawer.kt`、`docs/LOGO_CACHE_PREFETCH.md`。
- 关键 blob：`LogoLoader.kt 8ecc3e2`、`PlayerActivity.kt af3fc74`、`PlayerDrawer.kt a9c1775`；`SettingsDrawer.kt` 新增 `onRefreshLogos/onClearLogoCache/logoCacheSummary` 参数（L40–51）。
- pair：主 `12,945,299B` SHA `ef8549e8…158`、test `502,955B` `bc23bd2d…949a`、run 44789570423(run96)、10098；设备时延/卡顿未验证。

## 2. 已有正确部分（源码可接受）

- **预取触发**：`PlayerDrawer` L140–151 `LaunchedEffect(visible, selectedGroup)`，打开/切组后 `delay(180)` 再 `LogoLoader.prefetch(context, urls, 64×40dp)`；不直接改列表状态、不 rebind。
- **并发/去重**：`LogoLoader` `semaphore = Semaphore(3)`（L51）；`inFlight = HashSet` + `tryMarkInFlight/unmarkInFlight`（L111–122）；`load()` L231–252 以 `diskKey` 标记、并发等待内存结果（≤1.5s）；`prefetch` L351–374 委托 `load`。
- **磁盘键稳定**：`diskKey = sha256Hex(url)_WxH`（L103–105），`cacheFile = ...webp`（L107–109）；内存 key `WxH|url`（L99–101）；同 URL 同尺寸二次命中磁盘/内存。
- **API 存在**：`stats/clearMemory/clearDisk/clearAll/invalidate/refreshGroup`（L124–175）；签名与 t126 文档一致。
- **设置入口**：`SettingsDrawer` L141–166 新增“台标与缓存”区块 + 用量文案 + 刷新当前组 + 清理；`PlayerActivity` L1105–1149 接线；默认焦点仍 `源配置`（L64–68），Back/Left 仍关闭设置（L80–87），不破坏 TIV 根路径。
- **无回归 diff**：仅上述 4 个 Kotlin 文件 + docs；零拷贝/SurfaceView、频道键 166/167、LoadControl、flat/去圆角、MP2、EPG、SR、品牌未改；APK 12,945,299B≤30MB。

## 3. 阻断问题（需修复）

### CACHE-01（high）设置用量在 Compose 主线程做磁盘扫描，且启动即执行

**证据**：
- `PlayerActivity.kt` L1100–1104：
  ```kotlin
  val logoCacheSummary = remember(settingsDrawerOpen, logoCacheTick) {
      val s = LogoLoader.stats(context)   // composition / main thread
      ...
  }
  ```
  `remember` 首次 composition 就执行；`SettingsDrawer` 即使 `settingsDrawerOpen=false` 也会被组合，因此**冷启动/首次进入播放器主线程就会 `cacheDir.listFiles()` + `sumOf { length() }`**。
- `LogoLoader.stats` L127–136：`dir.listFiles()?.filter...` + `files.sumOf { it.length() }`，无 `withContext(IO)`。
- 同理 `clearAll` 在 `onClearLogoCache` L1144–1147 **直接主线程**执行 `clearDisk`（L142–147 `listFiles().forEach { delete() }`）；`refreshGroup` L171–175 的 `urls.forEach { invalidate }` 也在 `scope.launch`（默认 Main）内先执行 `listFiles/delete`，再 `prefetch`。

**影响**：与 t126 文档“no extra main-thread work (all decode/network on IO/Default)”相反；冷启动/打开设置/清理/刷新时可能丢帧、ANR 风险、首帧延迟；违反任务 1/3/4 的“不阻塞主线程/设置可用/无卡顿”。

**可执行修复**：
1. 将 `stats` 改为 `suspend fun statsIo(context): LogoStats = withContext(Dispatchers.IO) { ... }`（或保留同步 API 但只允许 IO 调用）。
2. `logoCacheSummary` 改为 `produceState`/`LaunchedEffect(settingsDrawerOpen, logoCacheTick)`，仅在 `settingsDrawerOpen==true` 时 `withContext(Dispatchers.IO) { LogoLoader.stats(...) }` 后写入 `mutableStateOf`；**不可**在 composition 中直接调 `stats`。
3. `onClearLogoCache`：`scope.launch(Dispatchers.IO) { LogoLoader.clearAll(context); withContext(Main){ logoCacheTick++; Toast... } }`；清理期间按钮/文案显示“清理中…”，避免重复触发。
4. `LogoLoader.refreshGroup`：把 `urls.forEach { invalidate(...) }` 包进 `withContext(Dispatchers.IO)`（或把 `invalidate` 改为 suspend/IO）；`PlayerActivity` 用 `scope.launch(Dispatchers.IO)` 调 `refreshGroup`，完成后切 Main 更新 tick/Toast。
5. `stats/clearDisk/invalidate` 如继续公开同步 API，在调用点强制 IO 线程，并在文档标注线程约束。

### PREFETCH-01（medium）预取范围不是“可视窗口 + 其后 N”，大组当前台/可视行漏预取

**证据**：
- `PlayerDrawer.kt` L140–151 取 `channels.asSequence().mapNotNull logoUrl...distinct().take(60)`，即**当前组前 60 个 URL**；没有使用 `LazyListState.layoutInfo.visibleItemsInfo`、`selectedChannelUrl` 或当前 channel index。
- 抽屉 `LazyColumn` 初始滚动到 `selectedChannelUrl`（L341–349 等）；若当前台 index ≥60，打开抽屉时该台及可视行不在前 60，只有 `ChannelLogo` 按需加载，冷缓存仍可能 6–9s。
- `LaunchedEffect` key 只有 `visible, selectedGroup`；`channels` 列表刷新/线路变化但 selectedGroup 不变时不会重新预取。

**影响**：t126 文档写“visible window + rest of group”，实际不是；acceptance 的“冷缓存开抽屉可视行首图 ≤1s / 滚动后台补齐”不保证。

**可执行修复**：
- 将 `selectedChannelUrl`/`channelIndexByUrl`/`LazyListState` 传入并计算预取集合：`visibleItemsInfo.indices` + 当前 selected/focused index ±8 + 前 60，去重后 `take(~80)`。
- `LaunchedEffect(visible, selectedGroup, selectedChannelUrl, channels)`（或使用 `snapshotFlow { channelListState.layoutInfo.visibleItemsInfo }` 做滚动防抖 150–250ms 的增量预取）；避免每滚动一帧启动全量预取。
- 保持 `prefetch` 在 IO；滚动监听仅收集索引/URL，不写列表状态。

### CANCEL-01（medium）`prefetch/loadInternal` 捕获所有 `Exception`，可能吞掉协程取消

**证据**：
- `LogoLoader.prefetch` L369–372：`try { load(...) } catch (_: Exception) {}`；`loadInternal` L305–343 的 `try/catch (e: Exception) { null }`。`CancellationException` 是 `Exception` 子类，会被吞掉。
- 抽屉关闭/`ChannelLogo` 离开组合时 `LaunchedEffect` 取消 `load`，但网络/循环可能继续到下一轮/下一次 `withContext` 才停；不符合文档“recycle/recomposition cancels handled”。

**可执行修复**：
- 所有 `catch (e: Exception)` 前补 `catch (e: CancellationException) { throw e }`（或 `ensureActive()`）；`prefetch` 的 per-URL catch 同样先 rethrow。
- 可选：把 `OkHttp Call` 保存在可取消对象，在 `invokeOnCancellation` 中 `call.cancel()`（若当前网络库允许），减少后台残留请求。

### CACHE-02（low/medium）`refreshGroup/clearAll` 与 in-flight 加载存在“清后又写回”竞态，且磁盘缓存无上限

**证据**：
- `refreshGroup` L171–175 只 `invalidate` 后 `prefetch`；`clearAll` L149–152 只 `clearMemory+clearDisk`，不取消 `inFlight`；正在进行的 `loadInternal` 可能在清理后把旧 bitmap `putCached` / `writeBitmapToDisk` 写回。
- `LogoLoader` 磁盘目录 `logo_cache_v1` 没有大小/条数上限（只有内存 `MAX_ENTRIES=96`），长期使用可能持续增长；`stats` 只能展示，`clear` 手动。

**可执行修复**：
- `clearAll/refreshGroup` 递增 `cacheGeneration` 或维护 `invalidatedKeys`，`loadInternal` 写回前检查 generation；或等待当前 `inFlight` 结束（限时）再清理；至少 `clearAll` 后清空 `inFlight` 并让新加载重新开始。
- 磁盘增加 `MAX_DISK_BYTES`（如 30–50MB）按最后修改时间淘汰；`stats` 显示用量；清理/刷新后更新统计。

## 4. 其他观察（不阻断 t126 本次范围，建议后续）

- **UI-01（low）**：SettingsDrawer 是扁平 section，不是真正分组子页；t126 文档也写“single item can be grouped later”，符合 F7 后续，不阻断。
- **PERF-02（low）**：`prefetch` 是**串行** for 循环（L355–374），`semaphore(3)` 只在可见 `ChannelLogo` 并发时体现；60 个 URL 的预取吞吐可能低于预期。建议 `coroutineScope { urls.chunked(3).map { async { load(...) } }.awaitAll() }`，但必须保留 180ms 延迟/防抖与上限。
- **UI-02（low）**：`logoCacheSummary` 使用 `remember(settingsDrawerOpen, logoCacheTick)` 在设置关闭时也计算；按 CACHE-01 改为可见时才 `LaunchedEffect`。
- **TEST-01（low）**：`LogoStats`/`clear` 无单元测试；建议 JVM 测试 `stats` 计数、`invalidate` 内存/磁盘键、`clear` 后 `load` 重新下载。
- **LOG-01（info）**：`stats` 的 disk 统计包含所有 `.webp`（含未完成 tmp 不计），符合当前设计；可在 UI 文案注明“磁盘缓存”。

## 5. 不回归 / 体积复核

- `git show 013ec891` 只改 `LogoLoader/PlayerActivity/PlayerDrawer/SettingsDrawer/docs`；未改零拷贝 SurfaceView 基座、频道键 166/167、LoadControl `1000/6000/300/1000`、flat UI/0dp、MP2 音频 AAR、EPG、SR、品牌/应用名。
- APK `12,945,299B`（≈12.35 MiB）≤30MB；本地 debug `16,010,355B` 记录在文档，CI pair 为交付真值。
- **R8/keep/contract**：CI `keep 210 identical`、contract missing 0、dex 7 OK；`LogoLoader` 为 Kotlin object/普通类，无新增 native/JNI 类，未引入额外 R8 风险。

## 6. 放行判断

- **源码结论：needs_revision。** 预取/去重/磁盘键/API/设置入口方向正确，但 **CACHE-01 主线程磁盘 I/O** 直接违反“不阻塞主线程/不增抽屉卡顿/冷启不倒退”的验收，必须先修；**PREFETCH-01** 范围与文档/验收不一致，需按可视窗口修正；**CANCEL-01** 取消语义需补。
- **真机/视觉未验证**：冷缓存开抽屉首图时延（目标 ≤1s，基线 6–9s）、滚动补齐、二次打开秒显、清理/刷新、抽屉 jank/CPU 峰值均归 acceptance2；本报告不宣称设备通过。
- **建议顺序**：CACHE-01 → PREFETCH-01 → CANCEL-01 → CACHE-02；修复后重出 r27 pair，再让 acceptance2 用同协议测“冷缓存首图/滚动/二次打开/设置清理/抽屉 jank”。

## 7. 只读命令与终态

`git rev-parse HEAD`、`git status --short`、`git show --stat 013ec891`、`git show 013ec891`、`read LogoLoader.kt`、`read SettingsDrawer.kt`、`grep PlayerActivity/PlayerDrawer`、`Get-Content docs/LOGO_CACHE_PREFETCH.md`。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 本报告完成后停止读取目标源码。
