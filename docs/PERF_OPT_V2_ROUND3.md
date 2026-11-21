# 弱机性能第三轮（t27）：抽屉真正懒组合 + 冷启补流 + PSS

> 证据：`docs/T7_PERF_AB_EARLY.md`（t22，包 **10156 / vc10156**）。
> 真机：抽屉 **Janky 100% / 50th 101ms / 90th 350–650ms**；但产帧 32→12–26、静止 0 帧、
> UI 线程中位 41.1→31.1ms；`IQTV-PERF drawer_open ms=314–408`、framestats 出现 **3–4 个 300–385ms 巨帧**
> （UI 346 / Draw 306）；冷启 `rebuffer_begin bufferMs=696 since_first_frame=303ms`、`bytesPerSec=364KB/s`（需 ~1MB/s）；
> PSS 71.8MB（10151 为 60.1MB）。
> **设置滚动已达标**（L1 18.37%/9ms、L2 16.89%/9ms），本轮不动设置。

---

## 1. 重要更正：`rows=147` 不是“组合了 147 行”

第一/二轮我打的日志是 `rows=${channels.size}`——那是**列表总数**，不是“这一帧真正组合的行数”。
所以“147 行同时组合”是**读数误导**。真正的 `LazyColumn` 本来就只组合可见 ~10 行。

**本轮修正埋点为真话**：`drawer_open ms=.. total=<总数> composed=<真正组合过的行数> groups=..`（`composedUrls` 集合只在组合 item 时加入、关闭时清空）。

那 300–385ms 巨帧是什么？按现有代码静态分析，最可能是**打开那一帧把「新出现的抽屉子树」一次性组合+布局+绘制**（冷 JIT/类加载也在这一帧付），叠加**同一帧里 PlayerActivity 因 drawerOpen 变更的整体重组**。可动的杠杆：
1. **D2（本轮的默认）**：让“面板先出、内容后到”，把内容组合移出打开那一帧；
2. **D3**：把行内可省的活省掉（预格式化文本、无同步 IO/解码、行内 `remember`）；
3. **D1**：显式声明 `contentType`、`DRAWER_OVERSCAN_ITEMS=0`（严格可见区，不做额外预组合）；固定 key 已在位。

> 说明：本轮无法在设备上验证巨帧是否由 D2 消除（t28 A/B）。但 D2 是**单变量、可回退**（常量 120↔0），
> `composed` 埋点会给 t28 一个确定的“打开时到底组合了多少行”的数字。

---

## 2. 改动（每项单变量、一行回退）

| # | 改动 | 位置 | 默认 | 回退 |
|---|---|---|---|---|
| **D2** | 打开抽屉后 **120ms** 再组合列表内容（第一帧只画面板/容器） | `PerfPolicy.DRAWER_DEFER_CONTENT_MS` | `120L` | 改 `0L` = 旧行为 |
| **D1** | 频道列表显式 `contentType={"channel"}`、`DRAWER_OVERSCAN_ITEMS=0`（严格可见区）；固定 key 保焦点/滚动；`channelListState` 仍 `remember` 以保滚动位置 | `PlayerDrawer.kt`、`PerfPolicy.kt` | 生效 | 删 `contentType` 即可 |
| **D3** | 行标题预格式化（`remember(index, title)`）；行内无同步 IO/位图解码（`ChannelLogo` 走内存命中 + 异步 load，logo 内存缓存上限 96 条）；无全表 `derivedStateOf` | `PlayerDrawer.kt` | 生效 | — |
| **埋点** | `drawer_open ms=.. total=.. composed=..`（真话） | `PlayerDrawer.kt` | 常驻（DEBUG 门控） | — |

---

## 3. 冷启 rebuffer（真因：初期补流爬升慢）

证据：`bufferMs=696`、`since_first_frame=303ms`、`bytesPerSec=364KB/s`（目标 ~1MB/s）。

- **埋点已在**（t20）：`rebuffer_begin/end ... bytesPerSec/bufferMs`；`IQTV-DIAG bytesRead/firstByteMs/code`。
- **实验 ③（本轮加代码，默认关）**：`ColdStartPolicy.PREBUFFER_BEFORE_PLAY_MS`（默认 `0`）。>0 时
  `prepare` 后先 `playWhenReady=false`，延迟 N ms 再 `play()`（预攒缓冲）。建议 A/B 试 **300 / 500**。
- **实验 ②**：`LoadControlTuning.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS` 2000→3000（一行常量，**默认未改**）。
- **实验 ①**：Loader 预读/并发 —— Media3 `DefaultLoadControl` 无直接并发旋钮；可评估
  `setTargetBufferBytes` / 提高 `MAX_BUFFER_MS`（6000→8000）让 loader 更早更多地预取（需 A/B）。
- **铁律**：必须同时报 TTFF（基准 p50 957ms），TTFF 劣化 >10% 即不采纳；未 A/B 通过前**不改默认**。

---

## 4. PSS（71.8MB，目标 ≤65MB）——静态审计结论

| 检查 | 结论 |
|---|---|
| 台标内存缓存 | `LogoLoader.cache` 是 `LinkedHashMap`，`removeEldestEntry` 上限 **96 条**；`urlGeneration` 上限 512 → **有界，非泄漏源** |
| 播放器/会话 | `sessions` 是 `WeakHashMap<ExoPlayer, Metrics>`；`release()` 里显式 `sessions.remove(player)`、`watchdogs.remove`、`watchdogJobs.remove` 并 cancel 定时器 → 正常路径不残留 |
| 监听器 | `Metrics` 在 release 时 `player.removeAnalyticsListener`；`audioSummaryJob` 也会 cancel |
| 本轮新增 | `composedUrls` 只在打开期间存在、关闭即 `clear()`（不持有频道对象，仅 url 字符串） |
| 结论 | **静态未发现新增泄漏**；10151→10156 的 +12MB 更可能是「测量时点/被测状态不同」（例如在 147 节点抽屉可见时采样）或 `dumpsys` 噪声 |
| t28 建议 | 统一时点：冷启后 **60s 稳态**采样；再在**关抽屉**状态采一次；并对 `sessions.size`/`LogoLoader` 缓存条数做一次 `am dumpheap` 对照。若确需硬降，下一步可评估台标缓存上限 96→64（视觉/流量取舍，需拍板） |

---

## 5. 验收线（t28 同口径）

```bash
adb shell setprop log.tag.IQTV-PERF DEBUG
adb shell dumpsys gfxinfo tv.iqtvlite.app.validation reset
# MENU 开 / BACK 关 ×5（间隔 800ms）
adb shell dumpsys gfxinfo tv.iqtvlite.app.validation > drawer_r3.txt
adb logcat -d -s IQTV-PERF | grep drawer_open   # 看 composed=<真正组合行数>
```
**通过线**：50th ≤16ms、Janky ≤20%、**无 ≥300ms 巨帧**；报告需含「产帧数 / 巨帧数 / composed 行数」。
D2 A/B：`DRAWER_DEFER_CONTENT_MS=120` vs `0`，其余不变。

---

## 6. 改前/改后表（t28 回填）

| 项 | 10156（t22） | 本轮 10160+（待测） | 目标 |
|---|---|---|---|
| 抽屉 Janky% | 100% | ？ | ≤20% |
| 抽屉 50th | 101ms | ？ | ≤16ms |
| 巨帧（≥300ms）数 | 3–4 | ？（D2 后应显著减少） | 0 |
| 产帧数 | 12–26 | ？ | 记录 |
| composed 行数 | （旧埋点误报 147） | ？ | ≤12 |
| 冷启 rebuffer | 1 次 / ~4s（364KB/s） | ？ | 明显下降 |
| TTFF p50 | 957ms | ？ | 不劣化 >10% |
| PSS | 71.8MB | ？ | ≤65MB |

---

## 7. 回退 / 门禁

- 回退：D2 常量→`0L`；D1 删 `contentType`/overscan 常量；冷启实验常量→`0L`；均互不依赖。
- 门禁：`check_perf_policy`（含 `if (showContent)`、`DRAWER_OPAQUE_CONTAINER`、`CountingDataSource(`）+ 四守卫 + `check_player_threading`/`check_refresh_paths`；JVM/lint/pair gate/keep；体积 ≤30MB。
- 未改已验收功能/观感；无 Prefs 迁移。`docs/PERF_OPT_V2_ROUND3.md` 为本文件。

---

## 8. 交付 receipt

| 项 | 值 |
|---|---|
| 提交 | `58524d8`（perf(drawer) r3: defer composition 120ms + contentType/overscan=0 + truthful composed-rows probe; cold-start prebuffer experiment + PSS audit） |
| CI run（绿） | **#162**，sha `58524d8`，push，conclusion `success` |
| 构建 | `BUILD SUCCESSFUL in 4m 39s`；`:app:testDebugUnitTest` + `:app:lintDebug` + `assembleDebug` + `assembleDebugAndroidTest` |
| 包 | `tv.iqtvlite.app.validation`，versionCode **10162**，versionName `2.2.0-tivimini-58524d81-validation` |
| app sha256 | `285f117b6371f7c9630df6abbd08177f4de24e55964dace8530fc2167c5e2307` |
| test sha256 | `f6551ef7512c81ec57c98197cb386fc3645ed1216423c51d93c271f6df53b115` |
| 门禁 | logo / info_card / settings_ia / settings_scroll / perf_policy / player_threading / refresh_paths 全 OK；pair gate `{0,0,0}` |
| 产物 | `iqtvlite-test-apk`（artifact id 14710415201，10,447,948 B ≪30MB） |
