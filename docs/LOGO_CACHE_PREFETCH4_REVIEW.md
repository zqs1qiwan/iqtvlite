# 台标缓存原子性 / 预取 / 并发 复查（t145 / run10103）

任务 t146（attempt 1），designer2，2026-11-21（Asia/Hong_Kong）。**结论：needs_revision。** ATOMIC-01 的内存 publish gate 已按 cache 锁内“gen + urlGen 复检 + put”完成；AtomicInteger/URL LRU/prune 首扫/预取并发 2 也已落地；urgent 的 selected±8 + 全 visible 无截断正确。但仍有两条直接命中本次验收：

1. **filler 仍被 `prefetchBackground(limit=60)` 截断**：`PlayerDrawer` 的 filler 是 first60 ∪ (selected−60..selected+8)，但 `LogoLoader.prefetchBackground` 只 `take(60)`；selected>60 时 selected 带几乎全部被丢弃，t145 声称的“filler 覆盖 selected>60”不成立。
2. **stale 旧 load 仍可能删掉别人新写的同 key 文件**：磁盘命中路径在 `putCachedIfFresh` 返回 false 之后才读 `lastModified` 作为 `readAt`（不是读取前），比较必然成立；网络路径用 `lastModified >= writeStartedAt`，若别人在本次写之后写了新文件，也会被删除。与 t145“绝不删他人同 key 新写”相反。

另：TEST-01 只有 5 个 gate/URL LRU 单元测试，**没有真正的并发 stale-put、取消传播、stats 测试**；`staleRuleMatchesPublishGate` 还是用同一规则自证，未覆盖真实 gate。

> 只读固定 commit `ebce64df`；HEAD `4d4f054`（docs-only）。工作树有 t152/其他并发修改未读取/未评价。未改代码、未跑 Gradle/CI/ADB、未操作 TV。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`；被审查 commit `ebce64df35a50f3b9d1cd2add7c979f9b61dcaf0`。
- `git show --stat`：3 files +138/−42：`LogoLoader.kt`、`PlayerDrawer.kt`、新增 `LogoPublishGateTest.kt`；docs `LOGO_CACHE_PREFETCH4.md` 4d4f054。
- pair：主 `12,994,451B` SHA `34ec22ea…1ce76`、test `502,955B` `db089ae1…2c01f`、run 44802783677（run101）、10103；设备未验证。
- 关键行：`LogoLoader` `putCachedIfFresh` L?、`clearMemory` L167–173、`invalidate` L199–205、`loadInternal` L321–430、`prefetchBackground` L?、`prefetchGate` L?、`maybePruneDisk` L?；`PlayerDrawer` L268–300；`LogoPublishGateTest` 5 tests。

## 2. 已修复/通过项

### ATOMIC-01 内存 gate — pass
- `putCachedIfFresh(key, bitmap, gen, urlGen, url)` 在 `synchronized(cache)` 内比较 `cacheGeneration.get()` 与 `urlGenOf(url)` 后再 put；`clearMemory` 在同一 cache 锁内 `incrementAndGet()+clear`；`cacheGeneration` 改 `AtomicInteger`；`invalidate` 用 `bumpUrlGen` + cache remove；内存 publish 的检查-写入原子化。方向正确。
- **磁盘删除路径仍有问题**，见 §3 ATOMIC-02。

### PREFETCH-02 urgent — pass
- `PlayerDrawer` 先加 `selectedIdx±8`，再加整个 visible window；**无 `take(16)`**；urgent `prefetch` limit=Int.MAX；selected 优先。方向符合“selected±8 优先、visible 全量”。

### PERF-02 — pass
- 新增独立 `prefetchGate = Semaphore(2)`，`prefetchChunked` 每个 `load` 包 `prefetchGate.withPermit`；filler `batchSize=4` 但 gate 限制实际并发 2；与交互 load 的 `Semaphore(3)` 隔离。文档/任务“并发2”成立。

### PRUNE-01 / URLGEN-01 — pass
- `lastPruneAt = System.currentTimeMillis()` 初始化，首次写不再全扫；每 24 写/60s 节流。
- `urlGeneration` 改 accessOrder `LinkedHashMap`、>512 淘汰；`LogoPublishGateTest` 2000 次 bump ≤512。

### TEST/无回归 — 部分
- `LogoPublishGateTest` 5 项：fresh/global clear/per-url 只阻断/规则一致/URL map 上限；JVM 54→59 全绿（CI 报告）。
- diff 仅 `LogoLoader/PlayerDrawer/新增测试`；未改零拷贝/频道键/LoadControl/0dp flat/MP2/台标设置/EPG/SR/品牌；APK 12,994,451B≤30MB；keep 210/contract 0。

## 3. 仍需修复

### ATOMIC-02（high/medium）stale 旧 load 仍可能删掉别人新写的同 key 文件

**证据**：
- 磁盘命中 L345–354：
  ```kotlin
  if (bitmap != null) {
      val imageBitmap = bitmap.asImageBitmap()
      if (putCachedIfFresh(...)) return imageBitmap
      val readAt = diskFile.lastModified()   // ← 在 publish 失败之后才读
      if (diskFile.lastModified() == readAt) runCatching { diskFile.delete() }
  }
  ```
  `readAt` 不是“读取/解码时”的 mtime，而是失败后的当前 mtime；比较恒真，**任何**当时存在的同 key 文件（包括别人刚写的新文件）都会被删除。t145 说“按读取时/写开始时刻判断”，实现只做到“失败后自比”。
- 网络写盘 L412–426：
  ```kotlin
  val writeStartedAt = System.currentTimeMillis()
  withContext(IO) { writeBitmapToDisk(diskFile, bitmap); maybePruneDisk(...) }
  if (!putCachedIfFresh(...)) {
      if (diskFile.lastModified() >= writeStartedAt) runCatching { diskFile.delete() }
      return null
  }
  ```
  若另一个协程/进程在本次 `writeStartedAt` 之后写了新文件，新文件 `lastModified >= writeStartedAt`，也会被旧 load 删除；`writeBitmapToDisk` 用固定 `.tmp` + rename，同 key 并发写本身也没有唯一 tmp 名，存在互相覆盖。
- `putCachedIfFresh` 只保护内存；磁盘文件不属于原子 gate 的同一临界区。

**修复（可执行）**：
1. **不要在 stale publish 失败时删共享磁盘文件**：让 `clearDisk/invalidate/prune` 负责删除；旧 load 只丢弃 bitmap。这样绝不会删别人新写。
2. 若必须删，捕获**解码前** `diskFile.lastModified()`/size，并在删除前再比较；网络写用唯一 tmp（`File.createTempFile`）并记录写入文件 inode/mtime/size，只有确认文件仍是本次写的才删。
3. `writeBitmapToDisk` 的 tmp 改为 per-write unique（include UUID/thread id），避免同 key 并发互相覆盖。
4. 加并发测试：两个 load 同 URL，A 在 gate 前被 invalidate，B 新写；断言 A 不删 B 文件、内存只留 B。

### PREFETCH-02b（medium/high）filler 被 limit=60 截断，selected>60 仍未覆盖

**证据**：
- `PlayerDrawer` L287–296：`filler = first60 ∪ (selectedIdx−60..selectedIdx+8) − urgent`；`filterNot urgentUrls`；调用 `LogoLoader.prefetchBackground(context, fillerUrls, ...)`。
- `LogoLoader.prefetchBackground` L?：
  ```kotlin
  prefetchChunked(..., limit = 60, batchSize = batchSize, ...)
  ```
  `unique = ...take(limit=60)`；filler 的插入顺序是 first60 先、selected 带后，`take(60)` 只保留前 60 个 channels（0..59），**selected>60 的带全部被截掉**。与 t145 文档“selected>60 也补齐”不符。
- urgent 本身已全量；缺的是 filler 的 selected 带。

**修复**：
- `prefetchBackground` 的 limit 改为 `Int.MAX_VALUE`（或至少 `max(120, filler.size)`），或让 `PlayerDrawer` 把 selected 带放在 first60 之前；更稳妥：filler 分两次传（selected 带优先 + first60 补齐），不要让 `take` 尾部截断。
- 测试 selectedIdx=200、组 300，确认 192..208 的 URL 进入 filler 请求；接受设备测选中行首图。

### TEST-01（medium）缺少真实并发 stale-put、取消、stats 测试

**证据**：
- `LogoPublishGateTest` L1–60：
  - 只测 `shouldPublishForTest`（纯函数镜像），没有真正进入 `putCachedIfFresh` 的 `synchronized(cache)` 临界区；
  - `staleRuleMatchesPublishGate` 用 `isStaleForTest`（内部委托 `shouldPublishForTest`）自证，无法发现 ATOMIC-02 磁盘删除；
  - 无 `prefetch` 取消传播、无 `stats` 内存/磁盘计数、无并发 clear/invalidate 与 publish 交错。
- `LogoGenerationTest` 4 项也只测 generation 规则；总计 59 全绿但漏掉本次两个 high/medium。

**修复**：
- 用可注入 `CacheStore`/`LogoLoader` 内部测试钩子，起两个协程/线程 `load` + `clear/invalidate`，断言 stale 不发布、不删新文件；
- 补 `prefetch` cancellation（collectLatest/async）与 `stats` 计数/clear 后重下；
- 避免 `isStaleForTest` 自证，测试真实 `putCachedIfFresh` 返回值/缓存内容。

### TEST-02 / 文档（low）
- `putCachedIfFresh` 的 `urlGenOf` 在 cache 锁内再锁 urlGeneration；当前 `invalidate` 先 bumpUrlGen（释放）再 cache，锁顺序不反转，暂无死锁；建议文档写清锁顺序，避免以后在 urlGeneration 锁内再取 cache 锁。
- `urlGeneration` 512 LRU 会淘汰仍 in-flight 的 url 的 stamp；淘汰后旧 load 的 uGen 可能变 0 → 被 gate 拒绝（安全但可能丢一次结果），可接受。
- `LogoPublishGateTest` 的 `staleRuleMatchesPublishGate` 是重复表达，建议删除或改为真实 gate 测试。

## 4. 真机/视觉未验证

- 浏览窗口 CPU ≈46%、janky ≤~27%、90th ≤69ms；可视首图仍快；设置清理/刷新正常；并发清空/刷新无错图；频道键/数字 OSD/MP2/零拷贝不回归均归 acceptance2(t147)。本报告不宣称设备通过。

## 5. 放行判断

- **源码结论：needs_revision。** ATOMIC-01 内存 gate/PERF-02/PRUNE/URLGEN 已修，但 **ATOMIC-02 磁盘删除仍会删别人新写**、**PREFETCH-02b filler 仍截断 selected>60**，且 TEST-01 未覆盖真实并发/取消/stats。
- 建议顺序：**ATOMIC-02 → PREFETCH-02b → TEST-01**；重出 r27 pair 后再做 t147 真机 CPU/jank。

## 6. 只读命令与终态

`git rev-parse HEAD`、`git status --short`、`git show --stat ebce64df`、`git show ebce64df`、`read LogoLoader.kt`、`read PlayerDrawer.kt`、`read LogoPublishGateTest.kt`、`Get-Content docs/LOGO_CACHE_PREFETCH4.md`。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 工作树并发修改未读取/未评价；本报告完成后停止读取目标源码。
