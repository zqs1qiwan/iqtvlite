# 弱机性能第十三轮（t60，终版）：残影按“窄测量”根治 + 冻结 50th

> t59 证据：残影已降为**间歇**（6 轮命中 1 次 `北`，位于 `60.卡酷少儿` / `61.北京IPTV` 附近），
> **命中时只有 1 个字**；`subtitle=true` 共 109 条正常；**Draw 10.6 → 5.0/5.7ms（三层 RenderNode 有效）**；
> 50th 48–57ms 不再降 → **Compose 行方案上限，冻结**。

---

## 1. 残影根治（按线索，不再堆 clip/key）

| 手段 | 说明 |
|---|---|
| **去掉 `softWrap = false`** | 该参数会把文字测量成**极窄宽**（正是“只有 1 个字”的来源）；改为 `maxLines = 1` + `TextOverflow.Ellipsis` + `Modifier.fillMaxWidth()`（父 16dp Box 有确定宽度） |
| **清洗节目名不可见字符** | 新增纯函数 `TextSanitize.programTitle()`：`\u00A0`(NBSP)/`\u3000`(全角空格) → 普通空格；`\u200B..\u200D`、`\uFEFF` 零宽字符**直接删除**；连续空白压一个空格并 trim；空结果 → `null`（不渲染副标题） |
| **窄测量探测** | `onTextLayout` 里 `size.width < 8.dp` 或 `lineCount > 1` → `IQTV-PERF sub_narrow idx=.. w=.. lines=..`（DEBUG），验收可据此断言“一次都不应出现” |
| 门禁 | ①`softWrap = false` **不得再出现**；②副标题必须经 `TextSanitize.programTitle(`；③`sub_narrow` 探测必须在位 |

保留（不动）：`key("sub_$index")` 独立子节点、副标题 `Box(16dp)+clipToBounds()`、行级 `clipToBounds()`。

---

## 2. 冻结项（不再迭代）

| 项 | 值/做法 |
|---|---|
| 帧调度 | `ROWS_PER_FRAME=2`、`FIRST_FRAME_ROWS=3`、`LOOKAHEAD=16`、`OPEN_WINDOW=28` |
| 层缓存 | **三层 RenderNode**（L1 静态背景 / L2 内容 / L3 列表）—— Draw 已 ≤5.7ms |
| 揭示 | `subList(0, revealedRows)` + 单项 `revealTarget` 分帧泵（禁一次性赋值） |
| 行 | 56dp 固定行高、`clipToBounds()`、聚焦行副标题 + 2dp 进度 |
| 交互 | UP/DOWN 由频道列接管（`via=key`）、滚动跟随（`onRowFocusedScroll` + 按需增长） |
| 埋点 | `drawer_open` / `drawer_compose` / `drawer_close` / `drawer_reveal_grow` |
| 睛彩 | A–H 全部默认关；Probe `cc_v/cc_a/cc_other` |

---

## 3. 最终指标（t59 实测，t61 复验）

| 指标 | 值 | 判定 |
|---|---|---|
| **Draw 中位** | **5.0 / 5.7ms** | ✅ ≤6ms（三层 RenderNode 生效） |
| UI 中位 | ~22ms | Compose 行方案下限 |
| **50th** | **48–57ms** | ⚠️ **Compose 上限，冻结**（不再改行/帧轴） |
| ≥300ms 巨帧 | 1–3 | 目标 0–1，实测 1 次为主 |
| 滚动跟随 | DOWN×25 / 快按×10 全过 | ✅ |
| 残影 | 6 轮 1 次（`北`） | 本轮根治，t61 需 **0 次（≥6 轮扫 60/61 区间）** |

---

## 4. 回退

| 想回退 | 操作 |
|---|---|
| 残影修法 | 恢复 `softWrap = false`（不推荐，残影会回来） |
| 层缓存 | 删三处 `graphicsLayer()`（Draw 会回到 10.6ms） |
| 帧调度 | 改成 1/2/12/20（t57 已证伪：巨帧 0 但 50th/ Draw 更差） |
| 关副标题 | `DRAWER_FOCUSED_SUBTITLE = false` |

不改冷启/PSS/信息卡/设置/台标；无 Prefs 迁移；体积 ≤30MB。

---

## 5. 交付 receipt

| 项 | 值 |
|---|---|
| 提交 | `170ea89`（r13 主体：清洗 + 去 softWrap + sub_narrow + 文档）→ `64ab74f`（用 Text 的 onTextLayout 参数）→ `d4ddb38`（阈值内联，修编译） |
| CI run（绿） | **#204**，sha `d4ddb38`，push，conclusion `success` |
| 构建 | `BUILD SUCCESSFUL in 3m 27s`；`:app:testDebugUnitTest`（含新增 `TextSanitizeTest` 3 例）+ `:app:lintDebug` + 双包 assemble |
| 门禁 | 八门禁全 OK（perf_policy：**禁止 `softWrap = false`**、要求 `TextSanitize.programTitle(`、要求 `sub_narrow`、`graphicsLayer()` ≥3、常量 2/3/16/28）；pair gate `{0,0,0}` |
| 包 | `tv.iqtvlite.app.validation`，versionCode **10204** |
| app sha256 | `564792e9a80286cfd3df95a986bfbca02702a05069b2e956b6d4ba07572e12ef` |
| test sha256 | `0017a076bb418a41164dbd86aea65c97b50ede15e88cef887ef26f676589af9e` |
| 产物 | `iqtvlite-test-apk`（artifact id 14732018900，10,478,713 B ≪30MB） |
| 待 t61 终验 | 残影 **0 次**（≥6 轮扫 60/61 区间）+ `sub_narrow` 日志应为 0 条 + 回归全项 + 可交付确认 |
