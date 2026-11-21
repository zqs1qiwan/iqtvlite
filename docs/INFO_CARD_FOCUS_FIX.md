# 信息卡焦点修复（t16）——F1/F2 缺陷 + F3–F5 清理

> 来源：`docs/REVIEW_SETTINGS_INFO_CARD_V2.md`（t8 复审，verdict=needs_revision）。
> 本文对应 t16：修 **F1（high）/ F2（medium）** 两个焦点缺陷，并同批清理 **F3/F4/F5（low）**。
> 只改代码与 CI，不执行 adb/设备命令；真机复验由 acceptance(t9) 按 §4 执行。

---

## 1. F1（high）：信息卡聚焦态焦点丢失 → BACK 直接退出播放器

### 1.1 根因（旧实现的三个叠加点）

1. `ChannelInfoBanner` 的聚焦只在“获得焦点”时回调 `onFocusedChanged(true)`，**从不回调 false**；
2. `PlayerActivity` 的 `isInfoCardFocused` 镜像由 **state 推导**（`SideEffect { onInfoCardFocusedChanged(infoCardState == FOCUSED) }`），
   而 `ChannelInfoBanner` 在 `FOCUSED && recentCount==0` 时只 `clearFocus` + `wasFocused=false`，**state 仍是 FOCUSED**；
3. `dispatchKeyEvent` 里 `if (isInfoCardFocused && infoCardKey) return super.dispatchKeyEvent(event)`：
   镜像为 true 但 Compose 已无焦点节点 → `super` 返回 false，事件无人消费，`PlayerActivity` 又没覆写
   `onBackPressed` → **BACK 落到系统默认 `finish()` 退出播放器**。

可达路径（无需设备即可推导）：卡片 FOCUSED 期间后台播放列表刷新/换源使“最近观看”解析为空（或窗口焦点被抢）→ 用户按 BACK → 退出。

### 1.2 修复

| 层 | 改动 |
|---|---|
| 纯逻辑 `InfoCardPolicy` | 新增 `focusRecoveryAction(state, recentCount)`：FOCUSED+0 → `EXIT_FOCUS`；FOCUSED+有条 → `REQUEST_FOCUS`；其余 → `NONE`。新增 `aggregateNodeFocus(stripFocusIndex)`（镜像只看“条内是否真有节点持焦点”） |
| UI `ChannelInfoBanner` | 用 `stripFocusIndex` 聚合最近观看条的节点焦点（`onCardFocusChanged(index, isFocused)` 双向回调）；`LaunchedEffect` 执行 `focusRecoveryAction`：**FOCUSED 且 0 条 → `onExitFocus()` 自愈回 IDLE**；FOCUSED 且有条件但节点丢失 → 重新 `requestFocus()`（自愈）；`LaunchedEffect(stripFocusIndex)` 推 `onFocusedChanged(aggregateNodeFocus(...))`；`state==HIDDEN` 时兜底推 `onFocusedChanged(false)` |
| Activity `PlayerActivity` | 聚焦镜像**不再由 state 推导**（删掉 SideEffect 里那半句），改由 banner 的节点焦点回调驱动；守卫改为：先 `super.dispatchKeyEvent(event)`，**若返回 false 且是 BACK → `isInfoCardFocused=false` + `setInfoBannerOpen(false)` + `return true`**（BACK 永不落到系统 `finish()`）；其它方向键/OK 不 return，继续走既有分支（原语义不变） |

**效果**：任何“FOCUSED 但无焦点节点”的状态都不会长期存在；即便短暂出现，镜像立刻为 false，
方向键恢复正常、BACK 只关卡片。

---

## 2. F2（medium）：卡片聚焦中按 MENU 打开设置 → 设置抽屉焦点被清

### 2.1 根因

`ChannelInfoBanner` 旧逻辑：`else if (wasFocused) { focusManager.clearFocus(force = true) }` —— **对所有非聚焦态**
（包括 `FOCUSED→HIDDEN`：MENU 打开设置后覆盖层监听把卡片置 HIDDEN）都执行 `clearFocus(force=true)`。
`LocalFocusManager` 属于整棵 ComposeView（设置抽屉与信息卡同一 owner），于是设置抽屉刚通过
`SettingsDrawer` 的 `LaunchedEffect(visible,page)` 拿到的焦点被清掉，且该 effect 的 keys 未变、不会重跑
→ 抽屉可见但 DOWN 不动。

### 2.2 修复

- 纯逻辑 `InfoCardPolicy.shouldClearFocusOnTransition(old, new)`：**仅 `FOCUSED→IDLE` 为 true**；
  `FOCUSED→HIDDEN` / `IDLE→HIDDEN` / 其它一律 false。
- `ChannelInfoBanner` 用 `lastState` 记录上一状态，只有 `shouldClearFocusOnTransition(lastState, state) && wasFocused`
  才 `clearFocus`；`FOCUSED→HIDDEN` 不清，交给覆盖层持有焦点（隐藏节点的焦点会随组合销毁自然释放）。

---

## 3. F3–F5（low）同批清理

| # | 清理 | 位置 |
|---|---|---|
| F3 | 「720dp 首屏全见」表述修正：注明 `playback` 页含 4 段完整帮助 ≈680dp，末尾允许滚动 1 行（不改功能） | `docs/SETTINGS_IA_V2.md` |
| F4a | 删除死参数 `SettingsDrawer.onEpgSettingsClick` 及其在 `PlayerActivity` 的 lambda（Toast+requestManualEpg，永不执行；合并行由 `onRefreshEpgNow` 承载同一 `requestManualEpg`） | `SettingsDrawer.kt`、`PlayerActivity.kt` |
| F4b | `InfoCardPolicy.onExternalZap` 保留但注释明确“给 `InfoCardPolicyTest` 校验不变量，勿删” | `InfoCardPolicy.kt` |
| F4c | `LEGACY_ACTION_TAGS` 注释修正为「23 个旧 tag + 1 个新增 `settings_logo_refresh`，共 24」 | `SettingsIA.kt` |
| F5 | `check_settings_ia.py` 第 4 条空转断言改为真断言：`SettingsRootPage` 必须带 `ROOT_TITLE_TAG` 且用 `SettingsRootEntry.tag` 绑定行；六个 `Settings*Page` 体内不得出现 `ROOT_TITLE_TAG`/`rootTag(`（已用“植入泄漏”自测确认能红） | `scripts/check_settings_ia.py` |

---

## 4. 测试与门禁

- **JVM 回归**：新增 `InfoCardFocusTest`（7 例）——FOCUSED+0 自愈 EXIT_FOCUS、FOCUSED+有条 REQUEST_FOCUS、
  非 FOCUSED 不动、镜像只看节点焦点、**后台刷新清空最近观看时 BACK 不会被吞**、FOCUSED→IDLE 才清焦点、
  **FOCUSED→HIDDEN（开覆盖层）绝不清焦点**。
- **静态门禁 `check_info_card.py` 新增第 6 组断言**（防回归）：banner 必须走
  `InfoCardPolicy.focusRecoveryAction` / `aggregateNodeFocus` / `shouldClearFocusOnTransition`、必须存在
  `onFocusedChanged(false)` 路径；Activity 守卫必须“先交 Compose、再 BACK 兜底关卡片”，且镜像不得再由
  `infoCardState == FOCUSED` 推导。
- **既有四守卫**：`check_logo_webp` / `check_settings_ia` / `check_settings_scroll` / `check_perf_policy` 继续通过。
- JVM/lint/pair gate/keep 见 §5 receipt。

### 4.1 acceptance(t9) 真机复验（至少）

1. 卡片聚焦 → **MENU** → 设置抽屉可见、焦点在「播放与画面」，DOWN/OK 正常；BACK 逐级（二级→一级→关）。
2. 卡片聚焦 → 触发源刷新/等自动更新（或删源重刷）使最近观看变空 → 方向键仍换台；**BACK 只关卡片，不退出播放器**。
3. 卡片聚焦中被强杀焦点（HOME→再进）后按 BACK，不应退出。
4. 其余按 `SETTINGS_IA_V2.md` §9.2 16 步 + `INFO_CARD_V2.md` §8.2 17 步照旧（含二级 RIGHT 吞掉、最近观看清空、EPG 单次、遮罩优先）。
5. `playback` 设置页记录是否需要滚动 1 行（F3，不判失败）。

---

## 5. 交付 receipt

| 项 | 值 |
|---|---|
| 提交 | `9dd333b`（fix(info-card): heal focus loss … + drop dead onEpgSettingsClick + tighten gates (t16)） |
| CI run（绿） | **#153**，sha `9dd333b`，event `push`，conclusion `success` |
| 构建 | `BUILD SUCCESSFUL in 3m 26s`；`:app:testDebugUnitTest`（含新增 `InfoCardFocusTest` 7 例）+ `:app:lintDebug` + `assembleDebug` + `assembleDebugAndroidTest` |
| 包 | `tv.iqtvlite.app.validation`，versionCode **10153**，versionName `2.2.0-tivimini-9dd333b1-validation` |
| `app-debug.apk` sha256 | `66c2832ba5180917a29a6ba6670dcd5fac38984afd66ee6faaa11e03fe4eb8ca` |
| `app-debug-androidTest.apk` sha256 | `af9dc08efddcbfd9277a0908b19eaf20ace5a068a7903bb28e3b17aa4f98d1fb` |
| 门禁 | `check_logo_webp` / `check_info_card` / `check_settings_ia` / `check_settings_scroll` / **`check_perf_policy`** 全 OK；pair gate `{"missing_types":0,"missing_methods":0,"missing_fields":0}` |
| 产物 | `iqtvlite-test-apk`（artifact id 14706781173，10,434,614 B）；`iqtvlite-android-reports`（id 14706930825） |
| 回退 | `git revert 9dd333b` 即回到 t8 复审状态；无 Prefs 迁移、无数据结构变化 |

> 不改已验收功能：信息卡外观/三态/最近观看/技术行、设置二级 IA、播放/零拷贝/台标/自动更新均未改动。
> 只新增纯逻辑 + 焦点聚合/回调 + BACK 兜底 + 死代码清理（约 30 行量级）。
