# 弱机性能第五轮（t33）：抽屉巨帧定位 + 首帧 4–6 行 + 每帧 2 行 + 固定行高

> 证据：`docs/CI_ROUND4_DRAWER_AB.md`（t30，包 **CI10166 / vc10166**）。
> 本轮只改代码与 CI；设备 A/B 由 acceptance(t34)。

---

## 0. t30 现状与判定

| 项 | t30（CI10166） |
|---|---|
| 分帧是否生效 | ✅ `drawer_compose frames=47 rows=147 lastFrameRows=2 first=10`、`drawer_open revealed=10 composed=0` → 10+3×45+2=147，与设计一致 |
| 首帧 | ✅ `revealed=10`（≤12），无“先空后填” |
| UI 线程 | ✅ 中位 **18.1ms**（t28 为 31.1–41.1），Draw 2.1ms |
| 帧时中位 | ✅ 68.9 → **39.9ms**；90th 300–650 → **65/53ms** |
| **巨帧** | ❌ **≥300ms 仍有 2 个（352.7 / 318.0）**；≥100ms 7 个 |
| **50th / Janky** | ❌ 50th 46/38ms、Janky 100%（目标 16ms / ≤20%） |
| 风险 | 一次 `drawer_open ms=2725`（其余 685–757） |
| 观感 | ✅ 无滚动跳/抖动/闪烁（t+0.9s 首屏完整、t+2.6s 不变、DOWN×2 仅 +29B） |

**结论**：瓶颈已从“整块组合”降到“每帧 2–3 行的重组 + 少量长帧”。要再往下压必须：
① 把**剩余巨帧归因**（打开首帧 vs 关闭反向重绘）；
② 降低首帧与单帧成本；③ 行高固定，去掉逐行测量。

---

## 1. 本轮改动（每项单变量、可回退）

| # | 改动 | 常量/位置 | 默认 | 回退 |
|---|---|---|---|---|
| **E1** | **巨帧归因埋点**：新增 `drawer_close ms=..`（关闭后等两帧再计时）；`drawer_open ms=..` 保留 | `PlayerDrawer.kt` | 常驻（DEBUG 门控） | — |
| **E2** | 首帧行数 **10 → 6** | `PerfPolicy.DRAWER_FIRST_FRAME_ROWS` | `6` | 改回 10 |
| **E3** | 每帧行数 **3 → 2** | `PerfPolicy.DRAWER_ROWS_PER_FRAME` | `2` | 改回 3 |
| **E4** | **超时加速**：揭示已耗时 ≥600ms → 6 行/帧、≥1200ms → 12 行/帧（消掉 `drawer_open 2725ms` 的极端情况） | `DRAWER_REVEAL_FAST_MS/FASTER_MS`、`nextRevealedRows(current,total,elapsedMs)` | 开 | 传 `elapsedMs=0` |
| **E5** | **未揭示行 = 固定高度占位**：item 数量恒为全量（滚动/焦点/`scrollToItem` 不受揭示进度影响），未揭示的只画 `DrawerPlaceholderRow()`（无文本测量/无 logo） | `PlayerDrawer.kt` | 开 | 改回 `subList` 截断 |
| **E6** | **行固定高度 56dp** + 内边距 10→6，长标题仍单行 ellipsis；无 `IntrinsicSize` 等昂贵测量 | `PerfPolicy.DRAWER_ROW_HEIGHT_DP`、`DrawerChannelItem` | `56` | 去掉 `.height()` |
| **E7** | `revealedRows` **每轮迭代只 `withFrameNanos` 一次、只递增一次**（不叠加重组） | `PlayerDrawer.kt` | — | — |

> E5 同时修掉一个隐患：t29 的 `subList(0, visibleRows)` 会改变列表长度，导致首帧 `scrollToItem(选中行)` 被钳住、
> 需要“补行完成后再校正滚动”。改成“全量 item + 占位”后列表长度恒定，滚动/焦点语义与揭示进度解耦。

---

## 2. 巨帧如何归因（t34 用）

```
adb shell setprop log.tag.IQTV-PERF DEBUG
adb shell dumpsys gfxinfo tv.iqtvlite.app.validation reset
# 记录 logcat 时间戳与 gfxinfo 一起，开合 5 次
adb logcat -v time -s IQTV-PERF | grep -E 'drawer_open ms=|drawer_close ms=|drawer_compose'
```
判定：
- 若 `drawer_open ms=` 之后立刻出现 300ms 帧 → 属**打开首帧**（继续压首帧行数/行内成本）；
- 若 300ms 帧紧跟 `drawer_close ms=`（或其后一帧）→ 属**关闭反向重绘**（下一步做“关闭时复用已录制层/不做反向重绘”）；
- `drawer_compose ms=` 反映补行总耗时；`step=2` 为正常，若出现加速（≥600ms）说明本机单帧成本仍偏高。

---

## 3. 通过线与待回填表

开合 5 次：**50th ≤16ms、Janky ≤20%、≥300ms 巨帧 0 个**、首帧 `composed ≤12`、无可见抖动/滚动跳。
若 50th 仍 >16ms 但巨帧为 0，如实报数据并给出下一步（候选：行内 subcomposition + 固定高度占位、文本预测量缓存、`derivedStateOf` 合并）。

| 项 | t30（10166） | 本轮（待 t34） | 目标 |
|---|---|---|---|
| ≥300ms 巨帧 | 2（352.7/318.0） | ？ | **0** |
| ≥100ms 帧 | 7 | ？ | 尽量 0 |
| 50th | 46/38ms | ？ | ≤16ms |
| Janky% | 100% | ？ | ≤20% |
| 首帧 revealed / composed | 10 / 0 | ？ | ≤12 |
| `drawer_compose` frames/ms | 47 / — | ？ | 记录（含是否触发加速） |
| 观感 | 无抖动 | ？ | 不变 |

---

## 4. 门禁与回退

- `check_perf_policy.py` 新增/更新断言：`DrawerPlaceholderRow()`、`PerfPolicy.DRAWER_ROW_HEIGHT_DP`、`drawer_close ms=`、
  `DRAWER_FIRST_FRAME_ROWS = 6`、`DRAWER_ROWS_PER_FRAME = 2`、`DRAWER_ROW_HEIGHT_DP = 56`。
- JVM：`PerfPolicyTest` 更新首帧/每帧常量与递增序列，新增 `revealAcceleratesToAvoidThe2_7sWorstCase`。
- 回退：E2/E3/E4 三个常量；E5 改回 `subList`；E6 去掉 `.height()`；互不依赖。
- 不改冷启/播放/PSS 口径与默认值；未动信息卡/设置/台标/自动更新；无 Prefs 迁移；体积 ≤30MB。

---

## 5. 交付 receipt

| 项 | 值 |
|---|---|
| 提交 | `cd09f10`（perf(drawer) r5: first-frame 6 + 2 rows/frame + 加速 + 固定 56dp 行 + 占位 + drawer_close 埋点） |
| CI run（绿） | **#168**，sha `cd09f10`，push，conclusion `success` |
| 构建 | `BUILD SUCCESSFUL in 4m 21s`；`:app:testDebugUnitTest`（含更新后的 PerfPolicyTest / 新增加速用例）+ `:app:lintDebug` + `assembleDebug` + `assembleDebugAndroidTest` |
| 包 | `tv.iqtvlite.app.validation`，versionCode **10168**，versionName `2.2.0-tivimini-cd09f10f-validation` |
| app sha256 | `b46c2e7504672a23f66f50af7b66e188e261cf7e6421a98b71aa7296ca2168dd` |
| test sha256 | `38928043d26b944f808f3f33359231fdd35404d7b27c320633e672170bb92742` |
| 门禁 | logo / info_card / settings_ia / settings_scroll / **perf_policy（含 t33 新断言）** / player_threading / refresh_paths 全 OK；pair gate `{0,0,0}` |
| 产物 | `iqtvlite-test-apk`（artifact id 14713223884，10,449,965 B ≪30MB） |
