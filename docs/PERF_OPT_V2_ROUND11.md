# 弱机性能第十一轮（t56）：1 行/帧 + 短 lookahead（低成本冲 50th）+ Probe cc_err 修正

> 基线：最终包 **vc10187**（t52）实测 —— **1 个 ≥300ms 巨帧（315.9ms）**、50th **57/53ms**、
> UI 22.1ms、Draw 5.0ms、帧时 43.6ms。单行组合成本实测 **12–15ms**。

---

## 1. 推理

当前 `step=2 行/帧` + `lookahead=16` + `openWindow=28` → 单帧 ≈ 2×13ms + 开销 ≈ **40–57ms**，
与实测 50th 53–57ms 吻合。**改成 1 行/帧** 后单帧应 ≈ **15–20ms**，且“一次组合过多”的巨帧来源也消失。

代价：揭示变慢（20 行 ≈ 20 帧 ≈ 0.33s）。但首帧已有 2 行、可视区 ~12 行，用户在前 0.2s 内看到的是
连续增长的行，不需要等满窗口。

---

## 2. 常量表（单变量、一行回退）

| 常量 | 旧（vc10187） | 新（t56） | 作用 |
|---|---|---|---|
| `DRAWER_ROWS_PER_FRAME` | 2 | **1** | 每帧只组合 1 行 → 单帧成本减半 |
| `DRAWER_FIRST_FRAME_ROWS` | 3 | **2** | 首帧更轻 |
| `DRAWER_LOOKAHEAD_ROWS` | 16 | **12** | 焦点/滚动预取更短，减少一次性补行量 |
| `DRAWER_OPEN_WINDOW_ROWS` | 28 | **20** | 打开目标窗口更小，泵更早停 |

预期：**50th ≤35ms、巨帧 0–1**。

---

## 3. 风险与验收重点（t57）

**主要风险：滚动跟手**。快按 DOWN×10 时，1 行/帧必须跟得上，不能出现“焦点已到但行还没长出来”的空白。

代码侧已有的兜底（本轮不改）：
- `onRowFocusedScroll(idx)`：焦点落到某行 → `revealTarget = idx + 12`，然后**最多等 30 帧**让目标行出现再滚；
- `snapshotFlow { lastVisibleIndex }`：最后可见项逼近窗口末尾就再抬 `revealTarget`（+12）；
- 单项泵 `snapshotFlow { revealTarget }` 每帧 +1 行推进。

因此最坏情况：快速连按 10 次（<200ms）→ 需要 10 行 → 约 10 帧（0.17s）补齐；
若 t57 观测到卡顿/空白行，**把 4 个常量改回 2/3/16/28** 并在本文记录「1 行/帧不可取」。

---

## 4. Probe `cc_err` 修正（t54 的遗留问题）

| 项 | 旧 | 新 |
|---|---|---|
| null PID 0x1FFF 的 CC | **参与**校验（导致 cc_err 虚高：null 包 CC 通常不连续） | **跳过** |
| 统计粒度 | 单一 `cc_err` | `cc_err`（总数）+ **`cc_v`**（视频）+ **`cc_a`**（音频）+ **`cc_other`**（其余 PID） |
| 日志 | `cc_err=..` | `cc_err=.. cc_v=.. cc_a=.. cc_other=..` |

JVM 测试 `probeSplitsCcErrorsAndIgnoresNullPid`：视频/音频/其它各 1 次跳变 + null PID 跳变
→ `cc_v=1 cc_a=1 cc_other=1 cc_err=3`（null 不计入）。

---

## 5. 冻结项（不得回退）

`subList(0, revealedRows)` 揭示、56dp 固定行高、单项 `revealTarget` 分帧泵、UP/DOWN 由频道列接管、
残影三重保险（keyed 子树 + `clipToBounds` + 16dp Box）、聚焦行副标题 + 2dp 进度、滚动跟随
（`onRowFocusedScroll` + 按需增长）、四个抽屉埋点、睛彩 A–H（全部默认关）。

---

## 6. 回退

| 想回退 | 操作 |
|---|---|
| 回到 vc10187 的帧调度 | 4 个常量改回 `2 / 3 / 16 / 28` |
| cc_err 回到旧口径 | 恢复 null PID 参与 CC 校验（不推荐，虚高） |
| 关副标题 | `DRAWER_FOCUSED_SUBTITLE = false` |

不改冷启/PSS/信息卡/设置/台标；无 Prefs 迁移；体积 ≤30MB。

---

## 7. 交付 receipt

| 项 | 值 |
|---|---|
| 提交 | `ac86304`（r11：1 行/帧 + 短 lookahead/open 窗口；Probe cc_err 拆分 + null PID 忽略） |
| CI run（绿） | **#198**，sha `ac86304`，push，conclusion `success` |
| 构建 | `BUILD SUCCESSFUL in 2m 57s`；`:app:testDebugUnitTest`（含新增 `probeSplitsCcErrorsAndIgnoresNullPid`）+ `:app:lintDebug` + 双包 assemble |
| 门禁 | 八门禁全 OK（`check_perf_policy` 断言已改为 1/2/12/20）；pair gate `{0,0,0}` |
| 包 | `tv.iqtvlite.app.validation`，versionCode **10198** |
| app sha256 | `dccd55bcdaa518e2603dac5a36016aac6aae6b4869488adc05b031994e2137f0` |
| test sha256 | `cac6fe03edec8dba9daeb66504bbdb7f952153d86329d4151b2ccf3ba0c6a719` |
| 产物 | `iqtvlite-test-apk`（artifact id 14729881015，10,476,949 B ≪30MB） |
| 待 t57 判定 | 50th 是否 ≤35ms、巨帧是否 0–1、**快按 DOWN×10 是否跟手/无空白行**；若不达标即按 §6 回退并记录“1 行/帧不可取” |
