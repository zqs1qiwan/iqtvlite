# 弱机性能第七轮（t39）：修滚动跟随 + 最后 1 个巨帧 + 50th

> 证据：`docs/CI_ROUND6_DRAWER_AB.md`（t37，包 **CI10170 / vc10170**）。
> 对照：t30（10166）、t34（10168）。本轮只改代码与 CI；设备 A/B 由 acceptance(t40)。

---

## 0. t37 现状

| 指标 | t30 | t34 | **t37（10170）** | 目标 |
|---|---|---|---|---|
| ≥300ms 巨帧 | 2 | 4 | **1**（304.3ms） | 0 |
| 帧时中位 | 39.9 | 103.2 | **39.0ms** | — |
| UI 中位 | 18.1 | 49.9 | **16.1ms** | — |
| Draw 中位 | 2.1 | 20.7 | **2.5ms** | — |
| 50th | 46/38 | 150/109 | **42/40ms** | ≤16 |
| Janky | 100% | 100% | **100%** | ≤20% |
| **DOWN/UP 滚动** | — | ❌ | **❌ 不跟随（可见行恒 1–12）** | 跟手 |

**两个问题**：①（功能缺陷）焦点移动但 LazyColumn 不滚动；②（性能）50th 40ms、残留 1 个 304ms 巨帧。

---

## 1. A) 滚动跟随：根因定位与修法（三假设逐一处理）

| 假设 | 判定 | 处理 |
|---|---|---|
| H1 `subList(0, revealedRows)` 让列表只有 revealedRows 项 → `scrollToItem` 被钳在末尾 | **成立**（原来滚动时机在状态生效前，同一帧执行） | 先写 `revealedRows`，**`withFrameNanos` 等一帧**让列表真正长出这些项，**再** `scrollToItem` |
| H2 `focusedChannelUrl` 按键时不回写 → `LaunchedEffect` 不触发 | **最可能的主因**（滚动依赖外部焦点状态，回头写受 `isFocusRestoring` 影响） | **改由 item 的 `onFocusChanged` 直接驱动**：`onRowFocusedScroll(index)`，不再依赖 `focusedChannelUrl` 回写 |
| H3 `channelListState` 被 re-`remember` | 否（`rememberLazyListState` 无 key，稳定） | 保持 |

新增 `onRowFocusedScroll(index)`（`PlayerDrawer.kt`）：
```
if (idx >= revealedRows) revealedRows = (idx + DRAWER_LOOKAHEAD_ROWS).coerceIn(0, size)  // ①先扩
drawerScope.launch {
    withFrameNanos { }                                                                    // ②等一帧
    if (idx 不在可见窗口) channelListState.scrollToItem((idx - 2).coerceAtLeast(0))        // ③再滚
}
```
并在 `DrawerChannelItem` 的 `onFocused` 回调里调用（焦点一落行就滚，与状态回写解耦）。

**验收判据（t40）**：开抽屉 → **DOWN×8 可见行应移动**；UP×8 回顶；DOWN×25 到底；快按跟手；关开重开位置一致；焦点行始终可见。

---

## 2. B) 最后 1 个 304ms 巨帧：首帧 6 → 3 行

`PerfPolicy.DRAWER_FIRST_FRAME_ROWS = 3`（3 行仍能“有内容”，不是空面板）。

---

## 3. C) 50th 40 → 16ms：把“逐帧补满 147 行”改成**有界工作窗口**

t37 的 `drawer_compose frames=72` 说明：打开后要用 **72 个帧**逐帧补到 147 行，**每一帧都重组列表** →
这就是 50th≈40ms 的来源（Draw 只有 2.5ms，UI 每帧 ~16ms × 72 帧）。

**本轮改法（关键）**：不再“补满全量”，而是维护**有界工作窗口**：
1. 第 1 帧：揭示 `DRAWER_FIRST_FRAME_ROWS=3` 行（极轻，消 304ms 巨帧）；
2. 第 2 帧：一次性扩到 `initialScrollIndex + LOOKAHEAD(16) + SCREEN(12)`（覆盖选中行与其下一屏）；
3. 之后**随滚动按需增长**：`snapshotFlow { lastVisibleIndex }` 收集，当最后可见项逼近窗口末尾时再 +16 行。

因为 LazyColumn 本来就只组合可见项，所以**用户感知与“全量揭示”完全一致**（可以一路滚到底），
但打开时只有 **2 个轻帧**而不是 72 个重帧 → 50th 才有机会回到 ≤16ms。

配套：`DRAWER_LOOKAHEAD_ROWS=16`、`DRAWER_SCREEN_ROWS=12`；新埋点 `drawer_reveal_grow rows=.. lastVisible=..`。

> E4 不透明容器检查：`DRAWER_OPAQUE_CONTAINER=true` 仍在、scrim 仍为 Transparent（未被 t33/t36 破坏），
> 所以 50th 不是“每帧整层重录”，而是“帧数太多”。这轮直接把帧数从 72 降到 2+按需。

---

## 4. D) 保留项

subList 揭示、56dp 固定行高、`step=2`（`nextRevealedRows` 仍用于纯函数测试与增长单位）、
`drawer_close ms=` 埋点、`drawer_open`/`drawer_compose` 埋点全部保留。

---

## 5. 通过线与待回填表

开合 5 次：50th ≤16ms、Janky ≤20%、≥300ms 巨帧 0、首帧 `composed ≤12`、无抖动/先空后填；
**DOWN×8 / UP×8 / DOWN×25 可见行必须变化**。

| 项 | t37（10170） | 本轮（待 t40） | 目标 |
|---|---|---|---|
| ≥300ms 巨帧 | 1（304.3） | ？ | **0** |
| 50th | 42/40ms | ？ | ≤16ms |
| 帧时中位 | 39.0ms | ？ | 下降 |
| `drawer_compose` frames | 72 | ？ | **≈2**（按需增长） |
| DOWN×8 可见行 | 恒 1–12 ❌ | ？ | **变化（如 9–20）** |
| 观感 | 无抖动 | ？ | 保持 |

---

## 6. 门禁与回退

- `check_perf_policy.py`：要求 `PerfPolicy.DRAWER_LOOKAHEAD_ROWS`、`DRAWER_SCREEN_ROWS=12`、`onRowFocusedScroll`、
  `drawer_reveal_grow`、`channels.subList(0, revealedRows`、`DRAWER_ROW_HEIGHT_DP=56`、`drawer_close ms=`、首帧 3。
- `PerfPolicyTest`：首帧 3、递增序列、lookahead=16、screen=12。
- 回退：`DRAWER_FIRST_FRAME_ROWS` 3↔6；窗口增长改回“逐帧补满”；`onRowFocusedScroll` 删除即回旧行为。互不依赖。
- 不改冷启/PSS 口径；未动信息卡/设置/台标/自动更新；无 Prefs 迁移；体积 ≤30MB。

---

## 7. 交付 receipt

| 项 | 值 |
|---|---|
| 提交 | `189c109`（r7 主体）→ `ac12b26`（把窗口揭示 effect 移到 `channelListState` 之后、替换旧的全量揭示滚动 effect，修编译） |
| CI run（绿） | **#173**，sha `ac12b26`，push，conclusion `success` |
| 构建 | `BUILD SUCCESSFUL in 3m 20s`；`:app:testDebugUnitTest` + `:app:lintDebug` + `assembleDebug` + `assembleDebugAndroidTest` |
| 包 | `tv.iqtvlite.app.validation`，versionCode **10173**，versionName `2.2.0-tivimini-ac12b266-validation` |
| app sha256 | `453d26eb206a4956c808e0138b02afbf17d2912c9bbf570d92633ae8771aad8b` |
| test sha256 | `d351e4fac0231fff5c510d6fc8a599fb47afdba92fb1a3453e99dca18c73daf4` |
| 门禁 | logo / info_card / settings_ia / settings_scroll / **perf_policy（t39 断言）** / player_threading / refresh_paths 全 OK；pair gate `{0,0,0}` |
| 产物 | `iqtvlite-test-apk`（artifact id 14716414176，10,454,333 B ≪30MB） |
| 红→绿 | `#172` 编译错（窗口揭示 effect 引用了后面才声明的 `initialScrollIndex`/`channelListState`）→ 移到声明之后 + 替换旧 effect → `#173` 全绿 |
