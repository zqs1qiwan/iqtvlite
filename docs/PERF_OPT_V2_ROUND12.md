# 弱机性能第十二轮（t58）：回退常量 + 层缓存攻 Draw + 残影彻底修

> t57 证据（`docs/CI_ROUND10_DRAWER_AB.md`）：1 行/帧把**巨帧打到 0**，但 50th 61/53ms 未降、
> Draw 5.0→**10.6ms**、UI 22.1→25.9ms、帧数 13→18。结论：单帧成本 = **固定开销（抽屉层每帧重录 +
> Measure）** + 行数×12–15ms；「每帧几行」这条轴（2 / 1 / 批量）已全部试遍，到顶。

---

## 1. 回退（回到 vc10187 最佳）

| 常量 | t56（1 行/帧） | **t58（本轮）** |
|---|---|---|
| `DRAWER_ROWS_PER_FRAME` | 1 | **2** |
| `DRAWER_FIRST_FRAME_ROWS` | 2 | **3** |
| `DRAWER_LOOKAHEAD_ROWS` | 12 | **16** |
| `DRAWER_OPEN_WINDOW_ROWS` | 20 | **28** |

vc10187 实测：巨帧 1 个（315.9ms，仍在 0–1 目标内）、50th 57/53ms、Draw 5.0ms。

---

## 2. 攻固定开销：把抽屉拆成三层独立 RenderNode（本轮核心）

原来只有一个 Box：`offset + background + 全部内容` 在同一层里 —— 列表每帧变化都会让**整层**
（含背景、边框、面板）重新录制，这是 Draw 5→10.6ms 的来源。

本轮改成三层：

| 层 | 内容 | 是否每帧重录 |
|---|---|---|
| **L1 静态背景层** | 抽屉底色 `container`（不透明，t20 的 E4 仍生效） | **否**（只录制一次；`graphicsLayer()` 缓存 RenderNode） |
| **L2 内容层** | 分组/频道/节目三列容器、边框 | 仅在内容变化时 |
| **L3 列表层** | 频道 `LazyColumn` | **是**（只有它需要每帧重录） |

实现：`graphicsLayer()` × 3（背景 Box / 内容 Box / 频道 LazyColumn），背景从内容 Box 上剥离成**兄弟节点**。
单变量、可回退：删掉三处 `graphicsLayer()` 即回到旧行为。

**目标**：Draw 中位 **≤5ms**（对照 5.0 / 10.6），并观察 50th 是否随之下降。

---

## 3. 残影彻底修（t57 在 50–61 区间复现孤立 `北`）

| 手段 | 说明 |
|---|---|
| **独立子节点** | 聚焦行的“台名+副标题+进度”放进 `key("sub_$index") { … }` —— 不再与普通行**复用同一个 Text slot**（残影根源是 slot 复用导致的旧内容残留） |
| **失焦整块移除** | `programTitle` 仍由 `if (DRAWER_FOCUSED_SUBTITLE && focused)` 门控；非聚焦行走 `else` 分支，副标题子树**根本不存在** |
| **显式裁剪** | 行级 `clipToBounds()` + 副标题 `Box(16dp).clipToBounds()` |
| **不换行** | 副标题 `softWrap = false` + `maxLines = 1` + `TextOverflow.Ellipsis` |

门禁新增：`key("sub_$index")`、`softWrap = false`、`graphicsLayer()` ≥ 3 处必须存在。

---

## 4. 保留（不得回退）

`subList(0, revealedRows)` 揭示、56dp 固定行高、单项 `revealTarget` 分帧泵、UP/DOWN 由频道列接管、
滚动跟随（`onRowFocusedScroll` + 按需增长）、聚焦行副标题 + 2dp 进度、四个抽屉埋点、
睛彩 A–H 全默认关（含 t56 的 Probe `cc_v/cc_a/cc_other` 修正）。

---

## 5. 判定与回退

- **若 Draw ≤5ms 且 50th 无改善** → 记录「固定开销无法再降，Compose 方案到此为止」，**不再迭代行/帧轴**；
- **若 50th 明显下降** → 继续沿层缓存方向做（如给节目/分组列也加层）。
- 回退：删三处 `graphicsLayer()`；四个常量改回 1/2/12/20（不推荐，见 t57）；`DRAWER_FOCUSED_SUBTITLE=false` 关副标题。

不改冷启/PSS/信息卡/设置/台标；无 Prefs 迁移；体积 ≤30MB。

---

## 6. 交付 receipt

| 项 | 值 |
|---|---|
| 提交 | `47e5160`（r12：回退 2/3/16/28 + 三层 graphicsLayer + keyed 副标题 softWrap=false） |
| CI run（绿） | **#200**，sha `47e5160`，push，conclusion `success` |
| 构建 | `BUILD SUCCESSFUL in 3m 26s`；`:app:testDebugUnitTest` + `:app:lintDebug` + 双包 assemble |
| 门禁 | 八门禁全 OK（perf_policy：常量 2/3/16/28 + `graphicsLayer()` ≥3 + `key("sub_$index")` + `softWrap = false`）；pair gate `{0,0,0}` |
| 包 | `tv.iqtvlite.app.validation`，versionCode **10200** |
| app sha256 | `c4bb280b48d12a1140855948cc053d709193d9785d50852fde1319b8f65a5598` |
| test sha256 | `7b368b533e10b64f9671f9e99da716af13b208bd5c56e0d37b8084c209860e1c` |
| 产物 | `iqtvlite-test-app`（artifact id 14731266406，10,476,985 B ≪30MB） |
| 待 t59 判定 | Draw 中位是否 ≤5ms、50th 是否下降、50–61 区间孤立 `北` 是否消失；若 Draw≤5 但 50th 不动 → 按 §5 记录“Compose 方案到此为止” |
