# 弱机性能第二轮（t20）：抽屉 UI 线程/Draw + 冷启 rebuffer 真因

> 基线对照：改前 **CI10145**（`docs/PERF_BASELINE_V2.md`）；第一轮 **CI10151**（`docs/PERF_OPT_V2.md`）；
> 真机 A/B：**`docs/T7_PERF_AB_EARLY.md`（t18）**。
> 本任务只改代码与 CI；设备侧 A/B 由 acceptance（t22/t9）执行，不抢 TV。

---

## 0. t18 证据（决定本轮方向）

| 观测 | 数值 | 含义 |
|---|---|---|
| 抽屉 jank 比例 | 100% → **84.4–85.7%** | 比例降了，但**没到目标** |
| 抽屉帧 50th | **46–48ms → 81–89ms（更差！）** | 第一轮改动后每帧反而更重 |
| 睛彩（**无视频帧**）下 | **85.29% / 61ms** | **排除“视频层合成”假设**：卡顿来自抽屉自身 |
| `gfxinfo framestats` | UI 线程中位 **41.1ms**；**Draw 118–162ms**；GPU issue 4.1ms、swap 1.6ms | 瓶颈 = **UI 线程组合/布局/绘制录制**，不是 GPU |
| 冷启 rebuffer | 首帧后 **0.95s** 开始（4087 vs 4288ms，属噪声） | rebuffer 早于 EPG 缓存读取（2s）→ **与 t7 的 P5 无关** |

**关键修正**：第一轮把跑马灯从 `Int.MAX_VALUE` 降到 **2 圈**，但只要还有一圈在跑，弱机每帧就要把
整个抽屉层（约 **147 个布局节点**：10 行 × ~15 节点）重新**录制一次 Draw**（118–162ms/帧）。
所以“帧数变少但每帧更重”→ 中位数反而从 46ms 升到 81–89ms。

---

## 1. 本轮改动（每项单变量、可一行回退）

| # | 改动 | 位置 | 默认 | 回退 |
|---|---|---|---|---|
| **E1/E2** | **抽屉关闭时完全不组合列表内容**（`showContent = visible && contentReady`），147 个节点不再被父级重组拖进来；另提供 `PerfPolicy.DRAWER_DEFER_CONTENT_MS`（>0 时打开先画面板、延迟组合列表，供 A/B） | `PlayerDrawer.kt` | 关闭时零组合；defer=0（立即组合） | 去掉 `if (showContent)`；defer 常量改 0 |
| **E3** | 一级/列表的 `remember` 复用（摘要、`rootEntries` 已在 t7 做）；抽屉侧 `channelNumbers`/`filteredChannels`/`indexByUrl` 均 `remember`；行文本仍就地格式化（成本 <1ms） | `PlayerDrawer.kt` / `SettingsDrawer.kt` | 已生效 | — |
| **E4** | 抽屉容器改**不透明** `surface`（不是 `copy(alpha=0.92f)`）→ 去掉每帧整层 alpha 合成；并去掉全屏 `background(Color.Transparent)` 的空 draw op | `PlayerDrawer.kt` + `PerfPolicy.DRAWER_OPAQUE_CONTAINER` | 开 | 常量改 false |
| **M1** | **关掉跑马灯动画**（`MARQUEE_ITERATIONS = 0`）→ 抽屉静止后**不再产生任何帧**（唯一持续 invalidate 源消失） | `PerfPolicy.kt` | 0（无动画） | 改 1..3 恢复“跑几圈就停” |
| **M2** | 埋点：`IQTV-PERF drawer_open ms=.. rows=.. groups=..`（DEBUG 门控）+ atrace section；给出“打开一次组合多少行/多久” | `PlayerDrawer.kt` | 常驻（未开 trace 近零开销） | — |

> 为什么先动 M1/E4：framestats 已证明是 **Draw 录制**贵 + 每帧重录。关掉唯一持续动画后，抽屉静止即无帧；
> E4 去掉整层 alpha；E1/E2 让关闭态不再白白组合 147 个节点。

---

## 2. 冷启 rebuffer（首帧后 0.95s，与 EPG 无关）

**先补可观测**（本轮最重要，可让 t22 直接归因）：

- `rebuffer_begin bytesRead=.. bufferMs=.. since_first_frame_ms=..`
- `rebuffer_end lasted_ms=.. bytes=<期间实读> bytesPerSec=<补流速率> bufferMs=<恢复后水位>`
- `IQTV-DIAG bytesRead=.. firstByteMs=.. code=..`（**真读字节**，来自 `CountingDataSource` 的 `read()` 计数；
  直播永不 `onLoadCompleted`，旧 `bytesLoaded` 恒 0 是误判）

**字节计数通道**：`CountingDataSource` 包一层上游 DataSource，`read()` 后 `JingcaiDiag.onBytes(n)`；
零改写、零新增线程。`Metrics.diagnosticLine` 的 bytes 已改为 `max(loaderBytes, 实读)`。

**三个实验（都不改默认；t22 按需 A/B，必须同时报 TTFF）**：

| 实验 | 做法 | 预期 |
|---|---|---|
| ① 起播缓冲更小（首帧前少等） | **已有用户开关**：设置→播放与画面→起播缓冲 = **快(300ms)**（t205 已实现，无需改码） | 起播更快但更易 rebuffer；观察 `rebuffer_begin/end` |
| ② afterRebuffer 2000→3000 | `LoadControlTuning.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS` 一行改 3000（**未改默认**） | 每次 rebuffer 后等更久才恢复 → 只用于观察是否“反复抖动” |
| ③ Loader 重试/补流 | 观察 `bytesPerSec` 是否远低于码率（~8.3Mbps≈1.0MB/s）；若是则调 OkHttp/连接复用（需单独立项） | 判定是“网络补不上”还是“缓冲策略” |

> 本轮**不改任何 LoadControl 默认值**：没有真机数据前改缓冲会直接拉长 TTFF，违反约束。
> 先让 t22 用上面的日志拿到 `bytesPerSec`/`bufferMs` 曲线，再决定。

---

## 3. 同口径 A/B 方法（acceptance / t22）

```bash
adb shell setprop log.tag.IQTV-PERF DEBUG
adb shell dumpsys gfxinfo tv.iqtvlite.app.validation reset
# 开合 5 次（MENU 开 / BACK 关，间隔 800ms）
adb shell input keyevent KEYCODE_MENU; sleep 0.8; adb shell input keyevent KEYCODE_BACK; sleep 0.8
# ...重复 5 次
adb shell dumpsys gfxinfo tv.iqtvlite.app.validation > drawer_r2.txt
adb logcat -d -s IQTV-PERF | grep drawer_open
```
看 `Janky frames: N (P%)`、`50th/90th/95th`、HISTOGRAM 是否有 ≤16ms 桶。
**通过线**：50th ≤16ms 且 Janky ≤20%（与 `PerfPolicy.meetsJankTarget` 口径一致）。

冷启：`am force-stop` → `am start` → 抓 `IQTV-ZAP` 的 `first_frame/rebuffer_begin/rebuffer_end` 与
`IQTV-DIAG bytesRead/firstByteMs`；同时记录 TTFF。

---

## 4. 改前/改后表（t22 回填）

| 项 | 10145 基线 | 10151（第一轮） | 本轮（待测） | 目标 |
|---|---|---|---|---|
| 抽屉 Janky% | 100% | 84.4–85.7% | ？ | ≤20% |
| 抽屉 50th | 46–48ms | 81–89ms | ？ | ≤16ms |
| 抽屉 Draw（framestats） | — | 118–162ms | ？ | 大幅下降 |
| 设置滚动 Janky% | 82.78%（旧 24 行） | 需重取 | ？ | ≤20% |
| 冷启 rebuffer | 1 次 / 4288ms | 1 次 / 4087ms | ？ | 明显下降且 TTFF 不劣化 |
| TTFF p50 | 1160ms | 同量级 | ？ | 不劣化 |
| PSS 播放稳态 | 79.3MB | 未变差 | ？ | 不高于基线 |

---

## 5. 门禁与回退

- JVM：`PerfPolicyTest` 更新（`MARQUEE_ITERATIONS=0`、`DRAWER_OPAQUE_CONTAINER=true`、`DRAWER_DEFER_CONTENT_MS=0`）。
- 静态 `scripts/check_perf_policy.py` 增加：`if (showContent)` 门控、`DRAWER_OPAQUE_CONTAINER`、`CountingDataSource(` 安装。
- 四守卫 + `check_perf_policy` + `check_player_threading` + `check_refresh_paths` 全绿；pair gate / keep / 体积 ≤30MB。
- **逐项回退**：M1（`MARQUEE_ITERATIONS`）、E4（`DRAWER_OPAQUE_CONTAINER=false`）、E1（去掉 `showContent` 门控 / `DRAWER_DEFER_CONTENT_MS=0`）、冷启实验（改常量）互不依赖。

---

## 6. 交付 receipt

| 项 | 值 |
|---|---|
| 提交 / CI | 见 t20 任务输出（本文件随实现提交，文档定稿补 run 号） |
| 未改 | 播放器/零拷贝/台标/自动更新/音轨/信息卡/设置 IA 结构；无 Prefs 迁移 |
