# 台标缓存取消 / 生成代 / 预取 复查（t137 / run10101）

任务 t138（attempt 1），designer2，2026-11-21（Asia/Hong_Kong）。**结论：needs_revision。** 取消吞没（CANCEL-01）已修；按 URL 生成代（CACHE-02b）已实现；磁盘命中/写盘后的 stale 检查已补。但仍有三处与 t137 目标/任务验收不一致：

1. **CACHE-02a 的 commit 不是原子的**：`loadInternal` 在 stale 检查之后、`putCached()` 之前仍有窗口；`putCached` 不校验 generation。清理/失效与 publish 竞态时，旧图仍可写回内存（甚至删掉别人新写的同 key 文件）。
2. **PREFETCH-02 的 urgent 被 `PlayerDrawer.take(16)` 截断**：visible + selected±8 可以 >16；当前选中行不保证在 urgent 里，且 `take(16)` 的顺序是 visible 先、selected 后，选中邻域可能被截掉。与“visible+selected 优先且全量”不一致。
3. **PERF-02 的“并发 2”未落实到 filler**：`prefetchBackground(batchSize=4)` 一次 `async` 4 个，而全局 `Semaphore(3)` 仍允许 3 个并发；实际可出现 3 个网络/解码并发，不是 2。

此外 JVM 测试只新增了 generation 4 项，**取消/stats 测试没有补**（任务 5 明确要求）；prune 节流方向正确但首写仍会扫目录（low）。

> 只读固定 commit `b8f9790b`（t137）；HEAD `4fdb634`（docs-only）。工作树无本任务相关改动（t141 并发修改未读取/未评价）。未改代码、未跑 Gradle/CI/ADB、未操作 TV。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`；被审查 commit `b8f9790b030bc21033a1401d79a0dc47692b5916`。
- `git show --stat`：3 files +167/−23：`LogoLoader.kt`、`PlayerDrawer.kt`、新增 `LogoGenerationTest.kt`；`docs/LOGO_CACHE_PREFETCH3.md` 为 4fdb634 docs。
- pair：主 `12,978,067B` SHA `b00e317f…764ed`、test `502,955B` `88042ef2…be1d`、run 44797591870（run99）、10101；设备未验证。
- 关键行：`LogoLoader` `cacheGeneration/urlGeneration` L62–67、`putCached` L103–105、`clearMemory` L154–157、`invalidate` L174–189、`loadInternal` L292–404、`prefetchChunked` L433–466、`maybePruneDisk` L472–478；`PlayerDrawer` L268–296；`LogoGenerationTest` 4 tests。

## 2. 已修复/通过项

### CANCEL-01 — pass
- `prefetchChunked` L451–462：`async { try { load(...) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } }`，每个 batch 前 `ensureActive()`；`awaitAll` 传播取消；`PlayerDrawer` `collectLatest` 取消时不再走 `runCatching` 吞掉。符合要求。

### CACHE-02b — pass（行为）
- `cacheGeneration` 只在 `clearMemory`（L155）和 `clearDisk`（L162）递增；`invalidate(url)` L178 只 bump `urlGeneration[url]`；`refreshGroup` L197 逐 URL invalidate，不再影响其他 URL 的 in-flight；`loadInternal` L295–297 同时比较 global + url stamp。
- `LogoGenerationTest` 3 个相关测试覆盖 global/per-url/稳定性；方向正确。

### CACHE-02a — 部分通过
- 磁盘命中 decode 后 L320/L341 检查 `stale()`，stale 时删盘不 put；网络写盘后 L390–394 再次检查并删刚写文件；比 t130 完整。但**不是原子 commit**，见 §3 ATOMIC-01。

### PREFETCH/PRUNE — 部分通过
- urgent 走 `prefetch`（limit=Int.MAX_VALUE，无 80 截断，async 2）；filler 走 `prefetchBackground`（limit=60，delay1s，batch4/220ms），`PlayerDrawer` 用 snapshotFlow/collectLatest/200ms。
- `maybePruneDisk` L472–478 每 24 写或 ≥60s 才 `pruneDiskIfNeeded`；32MiB 上限/最旧优先保留。
- `LogoGenerationTest` 4 项新增，JVM 48→52；CI 全绿。
- **但 urgent 调用方 take(16)、filler 实际并发3、测试缺取消/stats**，见 §3。

### 无回归 / 体积 — pass
- diff 仅 `LogoLoader/PlayerDrawer/新增测试`；未改零拷贝 SurfaceView、频道键 166/167、数字 OSD、flat 0dp、MP2、台标设置入口、EPG、SR、品牌/应用名。
- APK 12,978,067B（≈12.38 MiB）≤30MB；keep 210 identical / contract missing 0 / dex 7 OK（CI）。

## 3. 阻断/需修复

### ATOMIC-01（medium）`putCached` 不校验 generation，stale 检查与 publish 之间有窗口

**证据**：
- `putCached` L103–105 仅 `synchronized(cache) { cache[key] = bitmap }`，无 gen/uGen 参数。
- `loadInternal` 磁盘命中 L320–323：`if (bitmap != null && !stale()) { putCached(...); return }`；网络 L390–397：`if (stale()) { delete; return }` 然后 `putCached`。
- `clearMemory` L154–157 先 `cacheGeneration++` 再 `synchronized(cache){clear()}`；`invalidate` L178 先 bump urlGeneration 再清内存。若 `loadInternal` 在 `stale()` 返回 false 之后、`putCached` 获得锁之前发生 clear/invalidate，`putCached` 仍会把旧 bitmap 写回内存；磁盘路径也可能在 `diskFile.delete()` 前被别人重写同 key 文件，旧 load 再删新文件。
- 文档写“commit atomically”，实际不是；t131 的 TOCTOU 只封了检查点，没封检查→publish。

**修复**：
- `putCached(key, bitmap, globalGen, url, urlGen): Boolean` 在 `synchronized(cache)` 内检查 `globalGen == cacheGeneration && urlGen == urlGenOf(url)`；不匹配不写并返回 false。
- 磁盘命中/网络 paths 把 gen/uGen 传入；返回 false 时删除 `diskFile`（只删本 generation 写出的文件；若无法区分，至少跳过内存 publish，避免 UI 读到旧图）。
- 若要严格原子，用 `synchronized(cache)` 同时保护“generation 检查 + cache 写入”；`clearMemory/invalidate` 在同一个锁内 bump + clear（当前先 bump 后锁，仍有窗口）。`cacheGeneration` 可用 `AtomicInteger` 避免并发 ++ 丢失。
- 加测试：模拟 clear 发生在 stale check 后/put 前，断言缓存不出现旧 key。

### PREFETCH-02（medium）urgent `take(16)` 截断 visible+selected

**证据**：`PlayerDrawer.kt` L278–284：
```kotlin
val urgent = LinkedHashSet<Int>()
visibleIdx.forEach { ... }
for (i in (selectedIdx - 8)..(selectedIdx + 8)) ... add(i)
val urgentUrls = urgent.take(16)...
```
visible 窗口（电视常见 8–12 行）+ selected±8（17 个 index，含重叠）经常 >16；`take(16)` 先保留全部 visible，再取 selected 的低 8 个。例如 visible 10 行、selected 200：urgent 集合 = visible 10 + selected 192..208（17），take16 只保留 visible + 192..197，**当前选中 200 和 198..208 被截掉**；filler 只覆盖 first60，selected>60 时也不会补。文档称“no truncation of this list”，与调用方矛盾。

**修复**：
- 不要 `take(16)`；urgent 全量传入 `LogoLoader.prefetch`（其 `limit=Int.MAX_VALUE` 已支持）。
- 若必须限 16，优先保留 **selected±8**，再从 visible 补到 16；并按 index 去重；不要把 selected 尾部截掉。filler 仍只补 first60，且排除 urgent。
- 验收时用 selected index≥60 的场景（当前台在组后段）确认选中行预取。

### PERF-02（medium）filler 实际并发 3，不是 2

**证据**：
- `prefetchBackground` L422–430：`batchSize=4`、`concurrency=2`；`prefetchChunked` L449 `unique.chunked(batchSize)` → 每批 4 个 `async`（L451–462），而全局 `semaphore = Semaphore(3)`（L57）；`load` 内部 `semaphore.withPermit`。所以 filler 可有 **3** 个并发网络/解码，不是文档/任务要求的 2；urgent `prefetch` 因 `batchSize=2` 是 2，但 filler 与可见 `ChannelLogo` 仍可到 3。
- `concurrency` 参数只作为 `batchSize` 默认值，`prefetchBackground` 显式传 `batchSize=4` 时完全没限制 async 数。

**修复**：
- `prefetchBackground` 改用独立 `Semaphore(2)`（或把 `async` 数限制为 2、每批 4 个分两个 micro-batch）；保留 4/220ms 的批次节奏但并发 ≤2。
- 或在 `prefetchChunked` 中传 `concurrency` 并 `chunked` 后限制 `async` 数量；不要只依赖全局 Semaphore3。
- 验收测“仅浏览 vs 浏览+预取” CPU，确认 filler 不把并发拉回 3。

### TEST-01（medium/low）只补了 generation，缺取消/stats 测试

**证据**：`LogoGenerationTest` L1–49 仅 4 个生成代测试；任务明确“补 generation/取消/stats JVM 测试”。当前没有：
- `prefetch` 取消传播（collectLatest/后台关闭）测试；
- `stats` 内存/磁盘计数、clear 后重下测试；
- `putCached` stale 不发布测试（ATOMIC-01）。
现有 52 全绿无法覆盖 §3 ATOMIC-01 与 urgent 截断。

**修复**：新增纯 JVM/可注入测试：`isStale` 双代、clear 后 `putCached` 拒绝、取消时 async 传播、stats 文件计数/字节；或在 `LogoLoader` 抽 `CacheStore` 接口测。

### PRUNE-01（low）首写必扫目录，且两次 prune 间可短暂超 32MiB

**证据**：`lastPruneAt=0L`，`maybePruneDisk` L472–478 首次写入 `now - 0 > 60s` 必然 `pruneDiskIfNeeded`；之后每 24 写或 60s。首写仍全目录扫；两次 prune 之间最多累积 23 个文件，磁盘可能短暂超过 32MiB。
**修复**：初始化 `lastPruneAt=now` 或首写不扫；写后按 `writeCount` 每 24 次（或 60s）批量；若严格要求 ≤32MiB，可在写前预估/超阈值立即 prune。

### CACHE-02b 附加 low
- `urlGeneration` 是无界 HashMap（每个 invalidate URL 留一条）；长期刷新大量 URL 会累积；可在 clearAll/clearDisk 时清空，或改用有界 LRU。无功能风险但内存卫生。

## 4. 设备/视觉未验证

- 真机 **浏览-only vs 浏览+预取** CPU/jank（目标浏览 ~46%、janky ~27%、90th/95th 不回退）、可视首图≤1s、滚动增量、设置清理/刷新即时反馈、频道键/数字 OSD/MP2/零拷贝不回归，均归 acceptance2(t139)；本报告不宣称设备通过。

## 5. 放行判断

- **源码结论：needs_revision。** CANCEL-01/CACHE-02b 已修，CACHE-02a 的 stale 检查有但**非原子 commit**；PREFETCH-02 urgent 被截断；PERF-02 filler 并发 3；TEST 缺取消/stats。修复后再重出 r27 pair 并做 t139 的真机 CPU/jank。
- 建议顺序：**ATOMIC-01 → PREFETCH-02 → PERF-02 → TEST-01 → PRUNE-01**。

## 6. 只读命令与终态

`git rev-parse HEAD`、`git status --short`、`git show --stat b8f9790b`、`git show b8f9790b`、`read LogoLoader.kt`、`read PlayerDrawer.kt`、`read LogoGenerationTest.kt`、`Get-Content docs/LOGO_CACHE_PREFETCH3.md`。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 工作树中 t141 并发修改未读取/未评价；本报告完成后停止读取目标源码。
