# 复审：设置二级 IA + 信息卡 v2（t8）

> 版本 1.0 · 2026-11-21 · designer（t8，只读复审）· 结论：**needs_revision（不通过）**
> 复审对象：t13 设置二级（`8345078`→`b813457`，CI run #149 / vc 10149）与 t4 信息卡（`49bd541`→`1a7697f`，CI run #145 / vc 10145）
> 复审基线：仓库 `HEAD=70afcfd`（工作树无代码改动；仅 docs 未跟踪）
> 规格：[`SETTINGS_IA_V2.md`](SETTINGS_IA_V2.md)、[`INFO_CARD_V2.md`](INFO_CARD_V2.md)、[`INFO_CARD_V2_WIRING.md`](INFO_CARD_V2_WIRING.md)
> 本文只读复审：未改任何代码、未跑设备。所有引用行号均对当前 `HEAD` 核对过。

---

## 0. 结论摘要

**核心功能全部落地且质量高**：一级 6 入口、二级 ≤6 行、零丢失、Back/Right 层级、信息卡三态/大 logo/大台名/最近观看 ≤6、遮罩优先、静态门禁与 288 个 JVM 用例均成立。

**但发现 2 个必须修复的焦点缺陷（1 高 1 中）**，都集中在信息卡的「聚焦态」与全局焦点清理：

| # | 严重度 | 问题 | 后果 |
|---|---|---|---|
| **F1** | **high** | 卡片 FOCUSED 后焦点丢失无自愈：`state` 仍为 FOCUSED，Activity 守卫继续吞 DPAD/OK/BACK，而 Compose 已无焦点节点 | 方向键/OK 失效；**BACK 可能落到系统默认直接 `finish()` 退播放器** |
| **F2** | **medium** | 卡片聚焦中按 MENU 打开设置时，卡片的 `clearFocus(force = true)` 会清掉设置抽屉刚拿到的焦点 | 设置抽屉可见但无焦点，DOWN 不动（MENU→设置 旧路径回归） |
| F3 | low | 「每页 720dp 首屏全见」表述不严格：播放页保留 4 段长帮助后约 680dp > 672dp | 需滚 1 行；滚动+BIV 正常，功能不受阻；t9 需记录 |
| F4 | low | 死参数/死代码与迁移注释不准确：`onEpgSettingsClick` 已无调用；`InfoCardPolicy.Action`/`onExternalZap` 无生产调用；`LEGACY_ACTION_TAGS` 注释把新增 tag 算作旧 tag | 维护噪音/误导，无功能影响 |
| F5 | low | `check_settings_ia.py` 的 root-tag 泄漏断言空转（先负向 lookahead 排除 `root_`，再断言 `startswith("root_")`） | 门禁覆盖弱化，非产品缺陷 |

修复范围很小：主要在 `ChannelInfoBanner.kt` 的焦点聚合/清理（约 20 行）+ `PlayerActivity.kt` 的 BACK 兜底（约 3 行）；F3–F5 可同批或下轮处理。修复后重跑四门禁 + 单测 + CI，再安排 t9。

---

## 1. 复审方法与证据

| 手段 | 结果 |
|---|---|
| 通读实现（4 个生产文件 + 5 个测试类 + 2 个新门禁 + impl 文档） | 见 §2/§3 |
| **实跑静态门禁**（bundled Python 3.13） | `check_settings_scroll` / `check_settings_ia` / `check_info_card` / `check_logo_webp` / `check_player_threading` / `check_refresh_paths` **6/6 OK（exit 0）** |
| 用例数量核对 | 仓库 `@Test` 共 **288**，与 CI run #149 的 “288 用例全绿” 一致 |
| 源码红线审计（去注释后） | `ChannelInfoBanner.kt` / `SettingsDrawer.kt` / `InfoCardPolicy.kt` / `SettingsIA.kt`：无 `BorderStroke/border/shadow/elevation/blur/graphicsLayer/LazyColumn/LazyRow/animateContentSize`；无 `delay(5000)`；`InfoCardPolicy.kt` 无 Compose/Android import |
| tag / 零丢失 | 24 个旧 tag 全部可映射；`settings_epg_source` 已从 UI 移除；`settings_refresh_epg` 的 `tag =` 恰 1 次；`settings_logo_refresh` 新增；`settings_list` 字面量保留 |
| CI receipt | 仓库文档 `docs/SETTINGS_IA_V2_IMPL.md` §5 记录 run #149（sha `b813457`、vc 10149、288 tests、artifact 10,431,348 B）；t4 的 run #145（sha `1a7697f`）见任务输出。**本轮无法在线复核 CI，也未在本地重跑 Gradle**（本机无 JDK/gradlew），故单测“绿”的依据是 CI receipt + 源码静态核对 |
| 设备 | **未执行**（t9 负责）。F1/F2 是静态推导 + Compose 焦点机制分析，需 t9 按 §6 步骤复验 |

---

## 2. 验收矩阵

### 2.1 设置二级（t13）

| 验收项 | 结果 | 证据 |
|---|---|---|
| 一级恰好 6 入口、顺序固定 | **通过** | `SettingsIA.kt:36-43`；`SettingsIaTest.rootHasExactlySixEntriesInTheApprovedOrder` |
| 一级两行式（标题 + 摘要 + ›） | **通过** | `SettingsDrawer.kt:453-473`；摘要 `SettingsSummary`（≤22 全角，`SettingsSummaryTest`） |
| 页脚提示「关闭后按 ← 打开频道列表」不可聚焦 | **通过** | `SettingsDrawer.kt:408-413`（`HelpText` 无 focusable） |
| 6 个二级页、每页 ≤6 可聚焦行 | **通过** | 5/6/1/6/4/1（`SettingsIA.kt:46-82`；`SettingsIaTest.everyPageStaysWithinTheFirstScreenBudget`） |
| 720dp 首屏全见 | **部分** | 一级 568dp ✔；二级最长播放页 ≈680dp（含 4 段完整帮助）→ 需滚动 1 行（F3）。滚动+BIV 保留，可达性不受影响 |
| 继续 `verticalScroll` + 每行 `BringIntoViewRequester`（无 LazyColumn） | **通过** | `SettingsDrawer.kt:382-386,427-443,891-918`；`check_settings_scroll.py` OK |
| 二级 BACK/LEFT → 一级（焦点回入口行） | **通过** | `SettingsDrawer.kt:181-193,216-229`；`PlayerActivity.kt:391-396`；`SettingsBackStackTest` 11 例 |
| 二级 RIGHT 吞掉（不穿透） | **通过** | `SettingsIA.kt:202-205`；`SettingsBackStackTest.pageRightIsSwallowed` |
| 一级 BACK/LEFT → 关抽屉（只退一层） | **通过** | `PlayerActivity.kt:391-396`；`SettingsNavPolicy` Root→Closed |
| 24 行零丢失、唯一合并 EPG、tag 兼容 | **通过** | `SettingsDrawer.kt:680-731`；`SettingsIA.kt:94-150`；`SettingsZeroLossTest`（读源码逐 tag） |
| 最近观看对话框/清空/计数保留 | **通过** | `SettingsDrawer.kt:724-731`；`PlayerActivity.kt` 对话框逻辑未改 |
| 遥控习惯即时生效 | **通过** | `SettingsDrawer.kt` 回调仍走既有 `onCycleRemote*`；摘要实时取自 labels |
| 与信息卡不抢键 | **通过**（有 F1/F2 边界） | 抽屉打开即置卡片 HIDDEN（`PlayerActivity.kt:919-926`）；`check_settings_ia.py` 第 8 条 |
| 焦点/滚动会话内记忆 | **通过** | `SettingsDrawer.kt:137-150,169-173,181-193`；每页 ScrollState 提升到抽屉级 |

### 2.2 信息卡（t4）

| 验收项 | 结果 | 证据 |
|---|---|---|
| 96×60 大台标 + `headlineLarge` 台名 | **通过** | `ChannelInfoBanner.kt:160-191` |
| 中部 `titleLarge` 节目 + 3dp 进度 + 单行技术行 | **通过** | `ChannelInfoBanner.kt:193-222,455-477`；`InfoCardPolicy.techLine`（空项省略） |
| 最近观看 ≤6、排除当前台、最新在前 | **通过** | `InfoCardPolicy.cardRecentItems`；`PlayerActivity.kt:1588-1590`；`InfoCardRecentTest` |
| 小卡 40×40/104×64/12sp/间距 12dp、左右聚焦、OK 切台 | **通过** | `ChannelInfoBanner.kt:303-367`；`InfoCardPolicy.nextFocusIndex` |
| 切台后保持聚焦、index→0、A↔B 快跳 | **通过** | `PlayerActivity.kt:1989-1997`；`InfoCardRecentTest` 推演 |
| 入口提示「← 频道列表 · → 设置」不可聚焦 | **通过** | `ChannelInfoBanner.kt:286-296`；`InfoCardPolicy.ENTRY_HINT` |
| 3s 自动隐藏、聚焦不隐藏 | **通过** | `InfoCardPolicy.AUTO_HIDE_MS=3000`；`PlayerActivity.kt:908-917`（`autoHideMs` 驱动） |
| 聚焦态 BACK 先退聚焦、再按才关卡片 | **通过**（正常路径） | `ChannelInfoBanner.kt:128-138`；`PlayerActivity.kt:312` 守卫；`InfoCardPolicyTest` |
| 遮罩优先（覆盖层打开时 OK 绝不弹卡） | **通过** | `PlayerActivity.kt:324-331` 分支在所有覆盖层守卫内；`RemoteHabits.onOk(overlayOpen=true)=NONE` 未改 |
| 回看/快进：条隐藏、不可聚焦、进度行保留 | **通过** | `InfoCardPolicy.showRecentRow/canFocusRecent`；`ChannelInfoBanner.kt:141-147,226-253` |
| 0dp/无边框/无阴影/焦点仅 `surfaceVariant` | **通过** | 去注释审计 0 命中；`ChannelInfoBanner.kt:342-344` |
| 未聚焦不拦按键（LEFT/RIGHT/UP/DOWN 原语义） | **通过** | `ChannelInfoBanner.kt:130`（`!focused` 直接 false）；Activity 守卫仅在 `isInfoCardFocused` 时生效 |
| tag / 无障碍 | **通过** | `info_card*` 全在；`testTagsAsResourceId`；`contentDescription` |
| 与设置抽屉互不叠层 | **通过** | `PlayerActivity.kt` 覆盖层监听置 HIDDEN |
| **聚焦态鲁棒性（焦点丢失/BACK 兜底）** | **不通过** | **F1** |
| **MENU 聚焦态打开设置后的焦点** | **不通过** | **F2** |

### 2.3 门禁与测试

| 项 | 结果 |
|---|---|
| `check_settings_ia.py` | OK（但 §7 第 4 条断言空转，见 F5） |
| `check_settings_scroll.py` | OK（未改文件，兼容） |
| `check_info_card.py` | OK |
| 4 个设置测试类 / 2 个信息卡测试类 / RemoteHabitsTest 更新 | 结构合理、非空断言；288 用例与 CI 一致 |
| CI 接线 | 四门禁均在 `.github/workflows/apk.yml:144-149` 构建校验步骤，失败即红 |
| 体积 | t13 产物 10,431,348 B（≤30MB）；t4 产物见任务输出（同一量级） |

---

## 3. captain 指定的 3 个易错点复核

| 易错点 | 实现 | 结论 |
|---|---|---|
| ① 信息卡镜像**不算** seek 浮层 | `PlayerActivity.kt:902-906`：`onInfoBannerOpenChanged(infoCardState != HIDDEN)`，seek 不参与；旧 BACK 语义保留 | **通过** |
| ② 聚焦时 BACK 由 Compose 消费 | `PlayerActivity.kt:305-313` 在数字键分支后插入守卫 → `return super.dispatchKeyEvent`；`ChannelInfoBanner.kt:135` 卡片消费 `Key.Back` → `onExitFocus` | **通过（正常路径）**；焦点丢失时无兜底 → F1 |
| ③ 第二段 OK 仅 `CONFIRM` | `PlayerActivity.kt:324-331`：`isInfoBannerOpen && remote.ok == Ok.CONFIRM` 才调用 `setInfoCardOk`，否则走原习惯 | **通过** |

---

## 4. 阻断/缺陷清单（必须修复项）

### F1（high）信息卡聚焦态焦点丢失无自愈，BACK 可能直接退出播放器

**现象**
- `ChannelInfoBanner.kt:98-107`：`state==FOCUSED` 且 `recentCount==0` 时进入 else 分支 → `clearFocus(force=true)`、`wasFocused=false`，**但 `state` 仍保持 FOCUSED**。
- `ChannelInfoBanner.kt:327-330`：`onFocusChanged` 只在获得焦点时回调 `onFocusedChanged(true)`，**从不回调 false**；Activity 的 `isInfoCardFocused` 镜像（`PlayerActivity.kt:128,219,312`）因此可能长期为 true。
- `PlayerActivity.kt:312` 对 DPAD/OK/BACK 直接 `return super.dispatchKeyEvent(event)`；此时 Compose 内已无焦点节点，按键无人消费。`PlayerActivity` 未覆写 `onBackPressed`/无 `BackHandler`（全仓 grep 无）→ **BACK 走系统默认 `finish()`**。

**可达路径（无需设备即可推导）**
1. 卡片 FOCUSED 时后台播放列表刷新/换源，`recentResolved` 只剩当前台 → `cardRecentItems` 变空 → 最近观看条整条移除（`ChannelInfoBanner.kt:227`）；
2. 或窗口/系统临时抢走 Compose 焦点（恢复后不会重新 `requestFocus`）；
3. 用户按 BACK：Activity 守卫吞掉 → Compose 无节点 → 系统退出播放器。

**影响**：弱机后台自动更新（启动时/6h/12h）期间概率性出现「方向键全失效、BACK 直接退出」，属用户可感知的稳定性回归。

**requiredFix（约 20 行）**
1. `RecentStrip`/banner 聚合“任一小卡是否聚焦”（计数或 `focusedIndex`），在焦点丢失时回调 `onFocusedChanged(false)`；Activity 镜像随之为 false（不再吞键）。
2. `LaunchedEffect(state, recentCount)`：`state==FOCUSED && recentCount==0` → `onExitFocus()`（回 IDLE），不允许 FOCUSED 无条状态存在。
3. Activity 守卫加兜底：`if (isInfoCardFocused && infoCardKey) { val handled = super.dispatchKeyEvent(event); if (!handled && event.keyCode == KEYCODE_BACK) { setInfoBannerOpen?.invoke(false); return true }; return handled }`（BACK 永不落到系统 `finish()`）。
4. 可选：`infoCardState` 从 FOCUSED 变 HIDDEN/IDLE 时主动 `onFocusedChanged(false)`。

**验收**：t9 新增/复用步骤——聚焦卡片 → 触发源刷新（LAN push 或等自动更新）或切到只剩当前台 → 方向键仍换台、BACK 只关卡片不退出；单测可覆盖策略层 `onExternalZap`/`canFocusRecent` 组合。

> 代码：`ChannelInfoBanner.kt:85-107,226-253,327-330`；`PlayerActivity.kt:126-128,305-313,324-331`。

### F2（medium）卡片聚焦中按 MENU 打开设置，会把设置抽屉的焦点清掉

**现象**
- MENU 由 Activity 处理（`PlayerActivity.kt:366-371`）→ 打开设置；下一帧覆盖层监听把卡片置 HIDDEN。
- 同一流程中 `ChannelInfoBanner.kt:98-107` 因 `wasFocused=true` 执行 `focusManager.clearFocus(force = true)`。`LocalFocusManager` 属于整棵 ComposeView（设置抽屉与信息卡同一 owner），设置抽屉刚通过 `SettingsDrawer.kt:181-193` 请求到的焦点会被清空；该 `LaunchedEffect(visible,page)` 不会再次触发（keys 未变）。
- 结果：设置抽屉可见但无焦点，DOWN 无反应；用户只能 BACK 退出（BACK 分支可关抽屉），属旧「MENU→设置」路径回归。

**requiredFix**
- 焦点清理只在**用户主动退聚焦**（FOCUSED→IDLE）时执行；`state→HIDDEN`（覆盖层/计时器）时不要 `clearFocus`，交给新覆盖层持有焦点（或仅当条内仍持焦点时清理）。
- 与 F1 的焦点聚合修复一起做：只有“条内确实持焦点”才清，且清完把 `onFocusedChanged(false)` 推给 Activity。

**验收**：t9 步骤——卡片聚焦 → MENU → 设置抽屉可见且焦点在「播放与画面」→ DOWN 可移动 → BACK 回一级/关抽屉。

> 代码：`ChannelInfoBanner.kt:104`；`SettingsDrawer.kt:181-193`；`PlayerActivity.kt:366-371`。

---

## 5. 低优先级（不阻断，可批次处理）

### F3（low）“720dp 首屏全见”表述与播放页实际高度

`SettingsPlaybackPage`（`SettingsDrawer.kt:480-548`）保留 4 段完整帮助（509/528/537/546）。按 252dp 内容宽、12sp 估算约 680dp（>672dp 可用高），「睛彩诊断实验」帮助末尾可能需要滚动 1 行。
- 影响：滚动+BIV 正常，可达性不受影响；仅实现文档/规格验收语的严谨性问题。
- requiredFix：impl 文档改为“≤6 行；带长帮助的页允许 1 行滚动”，或把长帮助折叠为 2 行 ellipsis（视觉取舍）。
- 建议：t9 记录该页是否需滚动，不以此判失败。

### F4（low）死参数/死代码/注释不准确

| 项 | 证据 | 建议 |
|---|---|---|
| `SettingsDrawer.onEpgSettingsClick` 无任何使用 | `SettingsDrawer.kt:73`（仅签名）；`PlayerActivity.kt:1650` 传入的 lambda（Toast「开始更新EPG…」+ requestManualEpg）**永不执行** | 删除参数与 lambda，或注释「仅为兼容保留」；合并行已由 `onRefreshEpgNow` 承载同动作 |
| `InfoCardPolicy.Action` / `onExternalZap` 无生产调用 | `InfoCardPolicy.kt:21-24,54-58`（仅测试使用） | 删除或标 `@VisibleForTesting` 语义注释 |
| `LEGACY_ACTION_TAGS` 注释“旧版 24 个可操作 tag” | `SettingsIA.kt:124-150`：含新增 `settings_logo_refresh`、含退役 `settings_epg_source`，实际是“23 个旧 tag + 1 个新增” | 改注释/命名（如 `BASELINE_24_ROWS`），避免误导迁移审计 |

### F5（low）门禁断言空转

`scripts/check_settings_ia.py:124-126`：
```python
page_items = re.findall(r'"settings_(?!root_)([a-z_]+)"', drawer_code)
if any(item.startswith("root_") for item in page_items):
```
负向 lookahead 已排除 `root_`，断言永不成立（空转）。建议改为：断言每个页面 composable 内不出现 `"settings_root_` 字面量，或 root tag 恰好 6 个且只出现在 `SettingsRootPage`。

---

## 6. 修复后复测清单（给 developer / t9）

**developer（修复 F1/F2 后）**
- [ ] 新增/扩展 JVM 测试：策略层覆盖「FOCUSED + 0 条 → IDLE」「focus 丢失 → onFocusedChanged(false)」（若抽纯逻辑可测；Compose 层用 t9 验证）。
- [ ] `check_info_card.py` 增加断言：banner 中存在 `onFocusedChanged(false)` 路径 / `recentCount == 0` 的降级分支（防回归）。
- [ ] 本地/CI：6 门禁 + 288+ 单测 + lint + assemble 全绿；APK ≤30MB。
- [ ] 更新 `docs/INFO_CARD_V2.md` 备注（焦点丢失自愈规则）或新增一行变更说明。

**t9 真机（至少覆盖）**
1. 卡片聚焦 → MENU → 设置抽屉焦点在「播放与画面」，DOWN/OK 正常；BACK 逐级。
2. 卡片聚焦 → 等后台自动更新/触发源刷新（或删源重刷）→ 方向键仍换台；BACK 只关卡片，不退出播放器。
3. 卡片聚焦中强杀焦点场景：回到播放器（HOME→再进）后按 BACK，不应退出。
4. 其余按 `SETTINGS_IA_V2.md` §9.2 16 步 + `INFO_CARD_V2.md` §8.2 17 步照旧（含二级 RIGHT 吞掉、最近观看清空、EPG 单次、遮罩优先）。

---

## 7. 未验证 / 局限

- **无设备**：所有焦点/按键结论来自静态分析；F1/F2 需 t9 真机确认修复。
- **未本地跑 Gradle**：本机无 JDK/gradlew（`java` 不在 PATH、仓库无 wrapper），单测“288 全绿”依据 CI receipt；四门禁已用 bundled Python 实跑通过。
- **CI 远程复核**：run #149/#145 的 sha/versionCode 与仓库提交一致，但未拉取 CI 日志逐行核对。
- **t4 无 impl 文档**：t4 的 CI receipt 仅在任务输出中；建议补 `docs/INFO_CARD_V2_IMPL.md`（低优先，便于 t9 追溯）。

---

## 8. 复审判定

- **verdict = needs_revision**：F1（high）属稳定性回归风险且修复量小；F2（medium）是 MENU 路径焦点回归；其余功能/门禁/测试均达标。
- 修复 F1/F2 后**无需改 IA 或数据结构**，不涉及 Prefs 迁移；F3–F5 可与修复同批提交或留待后续。
- t9 应在 F1/F2 修复版（新 CI）上执行，避免把已知风险带入最终验收。
