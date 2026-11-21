# 重审 R2：信息卡焦点修复（F1/F2） + F3–F5 清理（t17）

> 版本 1.0 · 2026-11-21 · designer（t17，只读复审）· **verdict = pass（附 3 条低优先建议，不阻断）**
> 复审对象：t16 `9dd333b`（CI run #153 / vc 10153）/ receipt `ae3c754`；上一轮：`docs/REVIEW_SETTINGS_INFO_CARD_V2.md`（t8，needs_revision）
> 规格：[`SETTINGS_IA_V2.md`](SETTINGS_IA_V2.md)、[`INFO_CARD_V2.md`](INFO_CARD_V2.md)、[`INFO_CARD_V2_WIRING.md`](INFO_CARD_V2_WIRING.md)
> 只读复审：未改代码、未跑设备；本机无 JDK/gradlew，单测/CI 以 receipt + 源码静态核对为准（门禁已本地实跑）。

---

## 0. 结论

**F1（high）与 F2（medium）已实质修复；F3 已按“文档修正（方案 A）”裁定落实于主规格；F4a/F4b/F4c、F5 均落实（F5 已用“植入泄漏”独立复验会报红）。** 七门禁全绿，`@Test` 303 个（≥288+7），CI run #153 与仓库 sha 一致，APK 10,434,614 B ≤30MB，未发现阻塞性回归。

t9 需按 §6 补 3 个真机判据（源刷新→BACK 只关卡不退出、HOME→再进 BACK 不退出、MENU→设置 DOWN 可动），并记录 F3 的“playback 页是否滚 1 行”。另有 3 条低优先建议（§7），不影响本轮 pass。

---

## 1. 复审证据

| 项 | 值 |
|---|---|
| 提交 | `9dd333b`（fix(info-card)…）→ `ae3c754`（receipt） |
| 变更 | `InfoCardPolicy.kt`(+32)、`ChannelInfoBanner.kt`(+60/−?)、`PlayerActivity.kt`(+23)、`InfoCardFocusTest.kt`(新 7 例)、`SettingsDrawer.kt`(−2)、`SettingsIA.kt`(注释)、两个门禁、`docs/INFO_CARD_FOCUS_FIX.md`、`docs/SETTINGS_IA_V2.md` |
| CI（receipt） | run **#153** success，`BUILD SUCCESSFUL 3m26s`，`:app:testDebugUnitTest` + `lintDebug` + 双包 assemble；`tv.iqtvlite.app.validation` vc **10153**，`2.2.0-tivimini-9dd333b1-validation`；artifact `iqtvlite-test-apk` id 14706781173，**10,434,614 B** |
| 门禁（本地实跑，bundled Python） | `check_settings_scroll / check_settings_ia / check_info_card / check_logo_webp / check_perf_policy / check_player_threading / check_refresh_paths` = **7/7 OK（exit 0）** |
| 用例 | 仓库 `@Test` 共 **303**（t8 时 288 → +7 `InfoCardFocusTest` + t7 `PerfPolicyTest`），与 receipt 的“含新增 7 例”一致 |
| 工作树 | 复审基线为 `9dd333b`（receipt `ae3c754`）；当前工作树另有 **Jingcai 实验（t12/t18）未提交改动**（`Jingcai*`/`StreamPlayback`），不属 t16 范围、未参与本次判定。t16 涉及的 7 个文件与 HEAD 一致 |

---

## 2. F1（high）复核：FOCUSED 无焦点节点时 BACK 不退出播放器

### 2.1 三层实现（均已核对源码）

| 层 | 实现 | 位置 |
|---|---|---|
| 纯策略 | `focusRecoveryAction(state, recentCount)`：FOCUSED+0 → `EXIT_FOCUS`；FOCUSED+有条 → `REQUEST_FOCUS`；其余 `NONE` | `InfoCardPolicy.kt:26-41` |
| 节点焦点聚合 | `aggregateNodeFocus(stripFocusIndex)`：镜像只看“条内是否真的有节点持焦点”（`>=0`） | `InfoCardPolicy.kt:43-44` |
| Banner 自愈 | `stripFocusIndex` 由 `onCardFocusChanged(index, isFocused)` **双向**更新；`LaunchedEffect` 执行：FOCUSED+0 → `onExitFocus()`（回 IDLE）；FOCUSED 且 `stripFocusIndex<0` → 重新 `requestFocus()`；`LaunchedEffect(stripFocusIndex)` 推 `onFocusedChanged(aggregateNodeFocus(...))`；`state==HIDDEN` 兜底推 false | `ChannelInfoBanner.kt:94-137`、`RecentStrip` `onFocusChanged` 双向回调（`361-364`） |
| Activity 镜像 | **不再由 state 推导**（删掉 SideEffect 的 `onInfoCardFocusedChanged(infoCardState == FOCUSED)`），只由 banner 节点焦点回调驱动 | `PlayerActivity.kt:913-918` |
| Activity 守卫 | `isInfoCardFocused && infoCardKey` → 先 `super.dispatchKeyEvent`；未被消费则镜像置 false；若 BACK → `setInfoBannerOpen(false)` + `return true`；其它键继续走既有分支 | `PlayerActivity.kt:312-323` |

### 2.2 为什么“BACK 不会落到 finish()”成立（两条独立保险）

1. **镜像正确**：`isInfoCardFocused` 来自“条内真有节点持焦点”。一旦节点消失/被移除，Compose 的 `onFocusChanged(false)` 会把 `stripFocusIndex` 置 -1 → 镜像 false。于是守卫不成立，BACK 走 **既有 BACK 分支** `if (isInfoBannerOpen) { setInfoBannerOpen(false); return true }`（`PlayerActivity.kt:408-411`）——只关卡片并消费按键；且 DOWN 被 `when` 分支消费时 Activity 的 `onKeyDown` 未被调用、不建立 tracking，UP 不会触发 `onBackPressed`。
2. **纯策略自愈**：FOCUSED 但最近观看为 0（后台刷新/换源的典型情形）→ 状态强退回 IDLE，镜像随之 false；重复出现也不会长期停留 FOCUSED。

`InfoCardFocusTest` 7 例覆盖：FOCUSED+0→EXIT_FOCUS、FOCUSED+有条→REQUEST_FOCUS、非 FOCUSED 不动、镜像只看节点焦点、后台刷新清空时 BACK 不被吞、FOCUSED→IDLE 才清焦点、FOCUSED→HIDDEN 绝不清。

**判定：F1 关闭（实质修复）。** 详见 §7-A1 的一条低优先加固建议（关于“兜底分支”与系统 BACK-UP 的关系）。

---

## 3. F2（medium）复核：MENU → 设置后 DOWN 可动

- 纯策略 `shouldClearFocusOnTransition(old,new)` **仅 FOCUSED→IDLE 为 true**；`FOCUSED→HIDDEN / IDLE→HIDDEN / HIDDEN→*` 全为 false（`InfoCardPolicy.kt:46-52`）。
- Banner 用 `lastState` 记录上一状态，仅 `shouldClearFocusOnTransition(lastState, state) && wasFocused` 才 `focusManager.clearFocus(force=true)`（`ChannelInfoBanner.kt:119-127`）。
- MENU（`PlayerActivity.kt:377-382`）打开设置 → 覆盖层监听把卡片置 HIDDEN（`PlayerActivity.kt:932-938`）→ 走 `NONE` 分支、**不清焦点**；设置抽屉由 `SettingsDrawer.LaunchedEffect(visible,page)` 正常取得焦点。
- `InfoCardFocusTest.openingAnOverlayMustNotClearTheDrawerFocus` 覆盖 FOCUSED→HIDDEN/IDLE→HIDDEN/HIDDEN→IDLE 等 6 组组合。

**判定：F2 关闭（实质修复）。** 真机步骤见 §6-③（t9）。

---

## 4. F3 裁定：文档修正（方案 A），保留完整帮助

**明确裁定：采用方案 A（文档修正），不缩行高。** 理由：4 段实验/缓冲说明是排障必需的既有信息，为 1 行滚动去删减会造成信息丢失；`verticalScroll + BringIntoViewRequester` 已保证可达。

已落实：
- `docs/SETTINGS_IA_V2.md:96` 已明确写出：**`playback` 页保留 4 段完整帮助后约 680dp > 672dp，末尾可能需滚动 1 行；功能与可达性不受影响**，不再笼统宣称“全见”。

**仍残留两处旧措辞（低优先，需在最终文档清理，不阻断 t9）**：
- `docs/SETTINGS_IA_V2.md:130`：「5 行 + 3 段帮助 ≈ 300dp，720dp 内全见」——实际是 4 段帮助且约 680dp，与本文件 §2（第 96 行）自相矛盾；
- `docs/SETTINGS_IA_V2_IMPL.md:144`（t13 receipt 的 t9 指引）：「二级 ≤6 行首屏全见」——需改为“通常全见；playback 页允许滚 1 行”。

**判定：F3 关闭（裁定已落于主规格）；两处残留文案属 A2 文档清理项。**

---

## 5. F4 / F5 复核

| 项 | 结论 | 证据 |
|---|---|---|
| **F4a** 死参数 `onEpgSettingsClick` | **已删除** | `SettingsDrawer.kt` 参数列表不再有该参数；`PlayerActivity.kt:1661-1672` 调用点只剩说明注释；全仓无 `onEpgSettingsClick` 调用（仅该注释提及） |
| **F4b** `InfoCardPolicy.onExternalZap` | **保留 + 注释说明“给 InfoCardPolicyTest 校验不变量，勿删”** | `InfoCardPolicy.kt:82-88` |
| **F4c** `LEGACY_ACTION_TAGS` 注释 | **已改为「23 个旧 tag + 1 个新增 `settings_logo_refresh`，共 24」** | `SettingsIA.kt:124-131`。残留：`SETTINGS_IA_V2_IMPL.md:55` 仍写“基线 24 个可操作 tag”，属 A2 同类文案（低） |
| **F5** `check_settings_ia.py` 第 4 条 | **已由空转变为真断言**：`SettingsRootPage` 必须带 `ROOT_TITLE_TAG` 且用 `SettingsRootEntry.tag` 绑行；6 个 `Settings*Page` 体内不得出现 `ROOT_TITLE_TAG`/`rootTag(` | `scripts/check_settings_ia.py:123-146` |
| **F5 独立复验（植入泄漏）** | **通过** | 把源树复制到临时目录：未改动副本 `check_settings_ia: OK (exit 0)`；在 `SettingsPlaybackPage` 体内植入 `ROOT_TITLE_TAG` 后 → `check_settings_ia: FAIL SettingsDrawer.kt: root tag leaked into SettingsPlaybackPage (exit 1)`。**证明门禁不再空转**（临时目录在系统 TEMP，未改仓库源码） |

---

## 6. 无回归 / 门禁 / 测试 / 体积

| 项 | 结果 |
|---|---|
| 七门禁 | **7/7 OK**（见 §1） |
| 单测 | `InfoCardFocusTest` 7 例为纯策略断言，覆盖 F1/F2 决策表；既有 288+ 用例未改语义；总数 303 |
| CI / 产物 | run #153 success；vc 10153；APK 10,434,614 B ≤30MB；pair gate `{0,0,0}`（receipt） |
| 设置二级 | 未改动（仅删死参数 + 注释）；`SettingsDrawer` 行/tag/Back 行为与 t13 一致；`check_settings_*` 绿 |
| 信息卡其余行为 | 视觉/三态/最近观看/技术行/遮罩优先/3s 不变；改动集中在焦点聚合与清理；`InfoCardPolicy` 既有 API 未改语义 |
| t7 性能改动 | 同版本包含 t7（EPG 延迟/跑马灯/预取），属 t9 性能验收范围，非 t16 引入 |

---

## 7. 低优先建议（不阻断，记录在案）

- **A1（加固，可选）**：`PlayerActivity` 现有“BACK 兜底”分支对 **BACK** 实际不可达——`super.dispatchKeyEvent(BACK DOWN)` 会走 `Activity.onKeyDown`（targetSdk≥5 时对 BACK 恒 `startTracking()` 并返回 true），所以不会进入 `if (super…)` 的 false 分支；BACK 不退出实际依赖 §2.2-①（节点焦点镜像 + 既有 `isInfoBannerOpen` 分支）+ §2.2-②（FOCUSED+0 自愈）。建议后续补一刀确定性：覆写 `onBackPressed()`（或同时吞 BACK 的 ACTION_UP），让“任何情况下 BACK 都必须先经过卡片/覆盖层守卫”成为不依赖镜像时序的硬保证。**不阻断本轮**，t9 的 HOME→再进用例会实测。
- **A2（文档，t9 前清理）**：`SETTINGS_IA_V2.md:130` 与 `SETTINGS_IA_V2_IMPL.md:144` 的两处“全见”旧措辞、以及 `SETTINGS_IA_V2_IMPL.md:55` 的“24 个可操作 tag”措辞，统一为“≤6 行；playback 页允许滚 1 行 / 23 旧 + 1 新 tag”。
- **A3（测试口径）**：`InfoCardFocusTest` 只断言纯策略；`check_info_card.py` 的 BACK 断言是文本匹配，无法覆盖“Activity.onKeyDown 对 BACK 的 tracking/UP finish”语义。t9 必须执行真机 BACK 用例（§6）；未来可加 instrumentation 级测试。

---

## 8. t9 真机必测（本轮已与 captain 对齐）

1. 卡片聚焦 → **源刷新/等自动更新使最近观看变空** → 方向键仍换台；**BACK 只关卡片，不退出播放器**。
2. 卡片聚焦 → **HOME → 再进**播放器 → BACK 不退出（且方向键正常）。
3. 卡片聚焦 → **MENU** → 设置抽屉可见、焦点在「播放与画面」；DOWN/OK 正常；BACK 逐级（二级→一级→关）。
4. 全量回归按 `SETTINGS_IA_V2.md §9.2` 16 步 + `INFO_CARD_V2.md §8.2` 17 步；并记录 **playback 页是否需要滚 1 行**（F3 裁定：允许，不判失败）。

---

## 9. 局限

- 未执行设备命令；F1/F2 的用户可见行为由 t9 复验（本机无 JDK/gradle，未重跑单测；以 CI receipt + 源码静态核对 + 本地门禁为准）。
- CI run #153 的远程日志未逐行核对；sha/versionCode/artifact 与仓库提交、receipt 一致。

---

## 10. 判定

**verdict = pass。** F1/F2 实质修复（镜像来自真实节点焦点 + FOCUSED+0 自愈 + 覆盖层不再清焦点），F3 按“文档修正、保留完整帮助”裁定落实于主规格，F4a/F4b/F4c/F5 均完成且 F5 经植入泄漏复验；七门禁全绿、303 用例、CI #153、体积达标，无阻塞性回归。A1–A3 为低优先加固/文档/测试口径建议，交由 t9 或后续小批处理。
