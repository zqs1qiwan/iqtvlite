# S1 只读复审结论：小分组抽屉崩溃修复

- 复审对象：t1 [s1-impl] 的 4 个改动文件（基线 `e5111af`）
- 复审方式：只读（读源码 + `git diff` + 静态门禁 + Python 公式镜像）；**未改代码、未跑设备**
- 复审结论：**PASS（通过）**，可进入后续环节
- 复审日期：2026-11-22

## 改动范围（diff 核对）

| 文件 | 改动 | 评价 |
|---|---|---|
| `PerfPolicy.kt` | 新增 4 个纯逻辑 helper：`drawerFirstRows` / `drawerSafeOpenTarget` / `drawerSafeFirstVisibleIndex` / `drawerFocusIndexInRange` | 无 Android/Compose 依赖，可 JVM 断言；无副作用 |
| `PlayerDrawer.kt` | 仅 2 处替换：line 351 `openTarget`+`revealedRows`、line 882 `base` 钳制 | 手术式改动，未触碰其它分支 |
| `PerfPolicyTest.kt` | 新增 6 个 S1 用例 | 覆盖 0/1/2/大组/越界/切组 |
| `check_perf_policy.py` | 新增 S1 静态断言（含禁止模式反查 + Back 分支保留） | 门禁非空调 |

未发现越界改动；S1 之外的 drawer 交互代码在 diff 中完全未出现。

## ① 崩溃路径是否彻底消除（原 line 347 / line 875）

**已消除。**

- 原 line 347（打开窗口）：`(initialScrollIndex + OPEN_WINDOW).coerceIn(FIRST_FRAME_ROWS=3, channels.size)`。
  当 `channels.size < 3`（0/1/2 行）时 `lo=3 > hi` → `IllegalArgumentException`，打开抽屉即崩。
  现改为 `PerfPolicy.drawerSafeOpenTarget(initialScrollIndex, channels.size)` + `revealedRows = drawerFirstRows(channels.size)`。
  - 公式：`first = minOf(3, n)`（n≤0 时 0）；`target = max(first, min(start+28, n))`，恒有 `first ≤ target ≤ n`，不存在 lo>hi。
  - 0 行 → first=0/target=0；1/2 行 → first=1/2、target 钳到行数内。
  - Python 镜像对 `n=0..200 × idx=-5..200` 全量扫描：**0 处越界、0 处异常**。

- 原 line 875（UP/DOWN 落焦 base）：`firstVisibleItemIndex.coerceIn(0, channelCount-1)`，`count=0` 时 `lo=0 > hi=-1` 抛异常。
  现改为 `PerfPolicy.drawerSafeFirstVisibleIndex(...)`（`count≤0 → 0` 占位，`count>0 → coerceIn(0, count-1)` 语义不变）。
  注：该分支外层已由 `channelCount > 0` 守卫（line 871），此修复属正确的防御加固。

- 残留扫描：对**去注释后**的生产代码检索禁止模式 `coerceIn(FIRST_FRAME_ROWS, channels.size)` 与
  `coerceIn(0, channelCount-1)`：**均不存在**（唯一字面命中是 S1 说明性注释，已被门禁 `strip_comments` 剔除）。
- 相邻风险点：`val next = (base+1) % channelCount`（line 886）的取模在同一 `channelCount > 0` 守卫分支内，不会除零；
  1 行时 `next==base` 走 `return true`，不崩、仅无移动。安全。

## ② 0/1/2 行与大组切小组覆盖是否真实有效

**覆盖有效，且非空调。**

- 6 个新增 JVM 用例逐项核对：首帧行数钳制、openTarget 小组边界、大组窗口语义保持、
  `n=0..200 × idx=-5..200` 全量不变式扫描、大组切小组、firstVisible 钳制。
- Python 独立镜像复算用例中声明的 **21 个点值断言全部一致（0 失败）**，全量扫描 0 违规。
- **反向证明非空调**：用旧公式对 0/1/2 行求值，`coerceIn(3,0/1/2)` 均抛出 `IllegalArgumentException` 等价异常；
  这些用例在旧实现下必然失败，确能触发原问题。

- 大组切小组链路复核：
  - `LaunchedEffect(selectedGroup)`（line 561-570）在切组时置 `groupChangedFlag=true`、`focusedChannelUrl=null`；
  - `focusTargetIndex` memo（line 299-320）在 focused 为空、`groupChangedFlag && channelIndexByUrl` 非空时返回 **0**（首个频道）；
  - `drawerFirstRows(2)=2 > 0`，`revealedRows=2` 使第 0 行被组合并挂上 `selectedChannelRequester`，落焦可达。
  - 用例 `s1LargeGroupToSmallGroupTargetAndFocusStayValid` 覆盖：大组 openTarget=128、切到 2 行小组 openTarget=2、
    落焦索引 0 有效、首帧覆盖落焦行、越界索引无效。逻辑与生产路径一致。

## ③ 非空小组落焦与 Back 退出语义是否保留

**均保留。**

- 落焦：`LaunchedEffect(visible)`（line 518-559）逻辑未改；`if (channels.isEmpty() || activeColumn != Channels) return`
  （line 535）保证空组不进入聚焦重试、非空组照常 `requestFocus()`（最多 90 次重试）。
- Back：`onPreviewKeyEvent` 中 `Key.Back` 分支（line 864-869）保持不变——非频道列时置 `pendingFocusToChannels=true`，
  然后 `onClose()` 关闭抽屉。门禁明确断言 `it.key == Key.Back` 与 `onClose()` 均存在，防回归。

## ④ 其他抽屉交互是否被破坏

**未被破坏。**

- diff 仅 4 文件、改动集中；分帧组合、滚动跟随、聚焦副标题、三层 layer、去 softWrap 等 token 全部保留
  （去注释代码中逐一确认存在）：`DRAWER_INCREMENTAL_ROWS`、`revealedRows`、`subList(0, revealedRows`、
  `onRowFocusedScroll`、`revealTarget` + `snapshotFlow { revealTarget }`、`drawer_reveal_grow`、`drawer_compose frames=`、
  `FOCUSED_SUBTITLE && focused`、`key("sub_$index")`、`sub_narrow`、`clipToBounds`、`TextSanitize.programTitle(`、
  `DRAWER_OPAQUE_CONTAINER`、`showContent` 门控；`graphicsLayer()` 恰为 3 处（背景/内容/列表）；
  `softWrap = false` 已移除。
- 完整静态门禁 `python scripts/check_perf_policy.py` 运行 **EXIT=0（OK）**。

## ⑤ 门禁/测试是否真能触发原问题

**能触发，非空转。**

- 门禁两条禁止模式正则已用旧代码字符串验证：均能匹配旧写法（non-vacuous），且当前去注释代码中均不命中。
- 门禁还强制要求三个安全 helper 在 `PerfPolicy` 中存在、在 `PlayerDrawer` 中被调用，并要求 Back 分支保留。
- JVM 用例对旧实现会失败（旧式 0/1/2 行抛异常），对边界做了穷举扫描。

## 观察项（非阻断）

- `drawerFocusIndexInRange`  helper 目前只被单测引用，生产 `PlayerDrawer` 未直接调用；落焦语义实际由未被 S1 改动的
  `focusTargetIndex` memo（line 299-320）实现。测试以该 helper 作为等价模型（`(index in 0 until count)`）是可接受的代理，
  但更贴近生产路径的断言可考虑直接围绕 memo 行为。此项不影响 S1 修复正确性与本次 PASS 结论。

## 环境限制

- 本机无 JDK / Android SDK 平台，未执行 Gradle 单测与真机验收（与 t1 报告一致）。
- 本次验证依赖：静态门禁（bundled Python，EXIT=0）+ Python 公式镜像（21 点断言 + 全量扫描 + 旧式崩溃复现）。
  JVM 单测与设备交互的**实证执行**仍需在具备环境的环节补做；复审确认的是用例/门禁的正确性与可触发性，而非其已被运行。
