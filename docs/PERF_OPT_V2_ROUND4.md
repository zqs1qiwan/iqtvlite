# 弱机性能第四轮（t29）：抽屉分帧组合消除巨帧 + 冷启方差结论固化

> 证据：`docs/CI10164_COMBINED_ACCEPT.md`（t28，包 **CI10164 / vc10164**）。
> 本轮只改代码与 CI；设备 A/B 由 acceptance(t30)。

---

## 0. t28 结论摘要（决定本轮方向）

| 项 | t28 实测 |
|---|---|
| 抽屉首帧 | `drawer_open ms=21–29 total=147 **composed=0**`（5/5 次）→ **首帧确实不组合列表**（t27 的 rows 更正确认成立） |
| 抽屉 jank | **Janky 25/25 = 100%**，50th 57–81ms、90th 300–650ms |
| **巨帧** | framestats 18 帧中 **≥300ms 有 5 个（367.8 / 339.3 / 336.5 / 335.7 / 332.5）**，中位 68.9ms |
| 结论 | 巨帧 = **120ms 后那次“内容后到”的同步整块组合**（D2 把长帧从第 0 帧挪到了第 N 帧，没有真正拆开） |
| 冷启 rebuffer | 默认两次：1851ms vs **3914ms**（**运行间方差 ±2s > 任何实验效应**）；`bytesPerSec` 443K–1.14MB/s 波动 |
| PSS | 冷启后 60s 77.5MB / 抽屉开 70.0MB / **关抽屉 63.5MB**（同进程差 15.5MB）→ **口径敏感** |

---

## 1. 抽屉：分帧增量组合（D4）

### 1.1 改动

| 项 | 旧（t27/t28） | 新（t29） |
|---|---|---|
| 何时组合列表 | 打开后 **120ms 一次性**组合全部可见行 | **从第 0 帧起**分帧增量：首帧 **10 行**，之后**每帧 +3 行**直到 total |
| 驱动 | `delay(120)` + `contentReady` | `LaunchedEffect` + `withFrameNanos` + `revealedRows` 状态位 |
| 列表数据 | 全量 `channels` | `channels.subList(0, visibleRows)`（LazyColumn 只看到已揭示的行） |
| 埋点 | `drawer_open ... composed=..` | 追加 `revealed=..`；新增 `drawer_compose frames=.. rows=.. lastFrameRows=..` |
| 焦点 | 15×30ms 重试 | 按帧重试（最多 90 帧≈1.5s），选中行被组合出来后立刻拿到焦点 |
| 滚动 | 打开即 `scrollToItem(选中行)`（首帧只有 10 行时会被钳住） | 补行**完成后**再校正一次滚动 |

关键常量（`PerfPolicy`，单变量可回退）：

```kotlin
const val DRAWER_INCREMENTAL_ROWS = true   // false = 回到 t27 的 120ms 整块组合
const val DRAWER_FIRST_FRAME_ROWS = 10     // 首帧行数（≈一屏可见）
const val DRAWER_ROWS_PER_FRAME = 3        // 每帧追加（2–3）
const val DRAWER_DEFER_CONTENT_MS = 0L     // 去掉 120ms 延迟，从第 0 帧开始
fun nextRevealedRows(current, total)       // 纯函数，JVM 单测锁边界
```

### 1.2 为什么这能消掉巨帧

单帧工作量从「147 行（或一屏 ~11 行 + 高亮/文本测量）一次性组合+布局+绘制」降为「**3 行**」。
每帧只多 3 个 item，单帧远低于 16.7ms 预算；同时首帧就有 10 行可显示 → **没有“先空后填”的突兀感**。

### 1.3 观感/交互不变量

- 首帧即有 10 行可见内容（不是空面板），随后的 3 行/帧在 ~16ms 内完成，肉眼不可见；
- 选中行仍在补行完成后被聚焦并滚动到可视区（`LaunchedEffect` 按帧重试 + 补行后校正滚动）；
- 手动滚动/分组切换不受影响（`channelListState` 始终 remember；`contentType`/固定 key 保留）。

### 1.4 通过线（t30）

`gfxinfo reset` 开合 5 次：**50th ≤16ms、Janky ≤20%、≥300ms 巨帧 0 个**；`drawer_open` 首帧 `composed ≤12`；
另记录 `drawer_compose frames/rows` 与产帧总数。

---

## 2. 冷启 rebuffer：结论 = 源侧/网络方差，App 侧无稳定收益

t28 数据（同包 CI10164，默认参数）：

| 轮次 | 首帧→rebuffer | rebuffer 时长 | bytesPerSec |
|---|---|---|---|
| 默认 #1 | — | **1851ms** | 1.14MB/s |
| 默认 #2 | 303ms | **3914ms** | 0.50MB/s |
| prebuffer 300 | — | 4676ms | — |
| prebuffer 500 | — | 4672ms | — |
| max_buffer 8000 | — | 4304ms | — |
| afterRebuffer 3000 | — | 4199ms | — |

**判定**：同一默认参数两次相差 **2063ms**，而任何实验相对默认最好值都没有优势 →
**差异来自网络/网关瞬时吞吐（443KB/s–1.14MB/s），不是缓冲策略**。

**决定**：

1. **不改 LoadControl 默认**（保持 `2500 / 6000 / 500 / 2000`）；
2. 判定方法改为 **同批 3 次取中位**（单次结果不作为结论）；
3. 保留 `PerfTuning` 的 `Settings.Global` 运行时开关（`iqtv_prebuffer_ms` 等）**供用户/未来自选，推荐默认=关闭**；
4. 不做“首帧后 300ms 内 bufferMs<1000 就提示”的改动：本轮只保留**只读诊断**
   （`rebuffer_begin/end` 已含 `bufferMs`/`bytesPerSec`/`since_first_frame_ms`），不新增 UI 与默认行为。

> 结论一句话：**此项属源/网络侧，App 侧已无可稳定收益；参数实验只在同批 3 次中位下才可判定。**

---

## 3. PSS：固化口径，不作回归判据

- 口径：**冷启后 60s + 抽屉关闭 + 3 次取中位**（t28 实测同进程抽屉开/关差 15.5MB，时点敏感度 > 版本差异）。
- **不再把 PSS 作为回归判据**；只作趋势观察。
- 台标缓存 96→64 的硬降**本轮不做**（等 captain 拍板）。

---

## 4. 门禁与回退

- 新增静态断言（`check_perf_policy.py`）：`DRAWER_INCREMENTAL_ROWS`/`nextRevealedRows`/`revealedRows`/
  `channels.subList(0, visibleRows` 必须在位、`drawer_compose frames=` 埋点存在、首帧 10 / 每帧 3 常量值固定。
- 新增 JVM 测试：`revealedRowsGrowPerFrameAndStopAtTotal`（0→10、10→13、钳到 total、空表/小表）。
- 回退：`DRAWER_INCREMENTAL_ROWS=false`（回 t27 的 120ms 整块）或 `DRAWER_DEFER_CONTENT_MS=120L`。
- 未改播放/零拷贝/台标/设置 IA/信息卡/自动更新；无 Prefs 迁移；体积 ≤30MB。

---

## 5. 交付 receipt

| 项 | 值 |
|---|---|
| 提交 / CI | 见 t29 任务输出（本文件随实现提交，定稿后补 run 号） |
