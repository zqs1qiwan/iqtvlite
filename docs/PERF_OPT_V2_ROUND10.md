# 弱机性能第十轮（t48）：抽屉收尾 —— 修副标题残影 + openTarget 28→18

> 证据：`docs/CI_ROUND9_DRAWER_FINAL.md`（t47，包 **CI10179 / vc10179**）。
> **抽屉已冻结**：巨帧 3 / 50th 53ms 接受为单行 12–15ms 组合成本的物理上限，不再调帧调度。
> 本轮只做两项低风险收尾，其余全部冻结。

---

## 1. 修非聚焦行单字残影（`城/军/武/音`）

**现象（t47）**：语义树里非聚焦行偶尔出现单个汉字（城/军/武/音），疑为副标题失焦后的裁剪/复用残留。

**修法（三重保险，全部低成本）**：

| 手段 | 说明 |
|---|---|
| `key("row_focused_$index")` | 聚焦行的「台名+副标题+进度」子树**按 key 隔离**：失焦时整棵子树销毁，重新进入非聚焦分支 → 不会复用旧布局/旧 draw 命令 |
| `clipToBounds()`（行级 + 副标题盒级） | 行内容严格裁剪在 56dp 行内；副标题固定 `Box(height 16dp, fillMaxWidth, clipToBounds)`，不给文字任何溢出机会 |
| `focused` 严格门控（t44 已有） | `programTitle = if (DRAWER_FOCUSED_SUBTITLE && focused) nowProgram?.title else null` —— 非聚焦行根本不组合副标题节点 |

门禁新增：`clipToBounds()`、`key("row_focused_$index")` 必须在位（防止回退成“复用旧子树”）。

---

## 2. `openTarget` 28 → 18

| 项 | 旧（t46/t47） | 新（t48） |
|---|---|---|
| 首屏窗口 | `initialScrollIndex + LOOKAHEAD(16) + SCREEN(12)` = **28** | `initialScrollIndex + DRAWER_OPEN_WINDOW_ROWS(18)` = **18** |
| 分帧帧数 | 13 | **≈9**（仍 step=2，泵不变） |
| 预期 | 巨帧 3、50th 53ms | **巨帧 3→2**、50th 略降 |

滚动增长仍用 `LOOKAHEAD(16)`（未改），所以向下滚动的“提前量”不受影响。
若 t50 实测反而变差（巨帧 >3 或 50th >60ms）→ 改回 28 并在本节记录。

---

## 3. 冻结项（不得回退）

`channels.subList(0, revealedRows)`、`DRAWER_ROW_HEIGHT_DP=56`、`DRAWER_FIRST_FRAME_ROWS=3`、
`DRAWER_ROWS_PER_FRAME=2`、**`revealTarget` 单泵分帧**（禁止一次性 `revealedRows = next`）、
**UP/DOWN 由频道列接管**（`via=key`）、四个埋点（`drawer_open`/`drawer_compose`/`drawer_close`/`drawer_reveal_grow`）、
`onRowFocusedScroll` 滚动跟随、`DRAWER_FOCUSED_SUBTITLE`（仅聚焦行副标题 + 2dp 进度）。

---

## 4. 回退

| 想回退 | 操作 |
|---|---|
| openTarget 回到 28 | `DRAWER_OPEN_WINDOW_ROWS` 改 28（或恢复 `LOOKAHEAD+SCREEN` 表达式） |
| 关副标题 | `DRAWER_FOCUSED_SUBTITLE = false` |
| 残影修复 | 删 `key(...)` 与 `clipToBounds()`（不推荐，会复发） |

不改冷启/PSS/信息卡/设置/台标；无 Prefs 迁移；体积 ≤30MB。

---

## 5. 交付 receipt

| 项 | 值 |
|---|---|
| 提交 | `696105c`（fix(drawer): keyed subtitle subtree + clipToBounds，open window 28→18） |
| CI run（绿） | **#181**，sha `696105c`，push，conclusion `success` |
| 构建 | `BUILD SUCCESSFUL in 3m 46s`；`:app:testDebugUnitTest` + `:app:lintDebug` + `assembleDebug` + `assembleDebugAndroidTest` |
| 包 | `tv.iqtvlite.app.validation`，versionCode **10181**，versionName `2.2.0-tivimini-696105cc-validation` |
| app sha256 | `0b1fa060c56c84212f1eb3ddab17e171c066375132692c57b8aac53c03643dac` |
| test sha256 | `fef67df88ccb5ddd07fcd2dfb6c55f97e7c5dceafb5477b51a701603bb70914e` |
| 门禁 | logo / info_card / settings_ia / settings_scroll / **perf_policy（t48：clipToBounds / keyed 副标题子树 / OPEN_WINDOW=18）** / player_threading / refresh_paths 全 OK；pair gate `{0,0,0}` |
| 产物 | `iqtvlite-test-apk`（artifact id 14720848888，10,464,337 B ≪30MB） |
