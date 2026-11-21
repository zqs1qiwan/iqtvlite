# 弱机性能第六轮（t36）：回退式收口（撤全量占位 + 去加速 + 修焦点滚动跟随）

> 证据：`docs/CI_ROUND5_DRAWER_AB.md`（t34，包 **CI10168 / vc10168**）。
> 对照：`docs/CI_ROUND4_DRAWER_AB.md`（t30，**CI10166**，当时最好的抽屉数据）。
> 本轮只改代码与 CI；设备 A/B 由 acceptance(t37)。

---

## 0. t34 结论：第五轮（t33）是**回归**，不合并为默认

| 指标 | t30（10166，第4轮） | t34（10168，第5轮） | 判定 |
|---|---|---|---|
| **≥300ms 巨帧** | 2（352.7/318.0） | **4**（365.8/348.6/323.6/300.3） | ❌ 变差 |
| 帧时中位 | 39.9ms | **103.2ms** | ❌ 变差 |
| UI 线程中位 | 18.1ms | **49.9ms** | ❌ 变差 |
| **Draw 中位** | 2.1ms | **20.7ms（10×）** | ❌ 主因 |
| 50th | 46/38ms | **150/109ms** | ❌ 变差 |
| 90th / 95th | 65/53 · 300/200 | 500/350 · 500/400 | ❌ 变差 |
| 首帧 `revealed/composed` | 10/0 | 6/0 | ✅ 符合 t33 规格 |
| `drawer_close` | — | **9–24ms** | ✅ 关闭极便宜（新埋点的价值） |
| 极端 `drawer_open` 2725ms | 存在 | **已消除**（最大 867ms） | ✅ |

**根因（t34 定位）**：
1. **E3「item 恒为全量 147 + 占位行」** → 每帧都要遍历/测量 147 个 item（即使只是占位 Box），
   **Draw 2.1 → 20.7ms（10 倍）**；
2. **600/1200ms 超时加速**把单帧组合量抬到 6–12 行 → 制造 300ms 级大帧；
3. 附带副作用：占位行不可聚焦，**DOWN×8 后可见行仍 1–6**（焦点下移没有被列表跟随）。

---

## 1. 本轮改动（回退 + 保留 + 修一个新问题）

| # | 动作 | 说明 | 回退 |
|---|---|---|---|
| **R1** | **撤掉 E3**：回到 `channels.subList(0, revealedRows)` | 未揭示的行**根本不存在** → 每帧只测量已揭示项（Draw 回到 t30 量级） | 改回全量 + 占位（不建议） |
| **R2** | **保留 56dp 固定行高** | 固定高度不是问题；全量 item 才是 | `DRAWER_ROW_HEIGHT_DP` 去掉 `.height()` |
| **R3** | **去掉 600/1200ms 加速** | `nextRevealedRows(current,total)` 恒定 **+2 行/帧**，不再有 `DRAWER_REVEAL_FAST/FASTER_MS` | 重新引入加速常量（不建议） |
| **R4** | 保留 `DRAWER_FIRST_FRAME_ROWS = 6` | t34 实测无“先空后填” | 改回 10 |
| **R5** | **保留 `drawer_close ms=..` 埋点** | 已证明关闭 9–24ms，是定位巨帧最有价值的诊断 | — |
| **R6（新）** | **焦点滚动跟随**：`LaunchedEffect(visible, focusedChannelUrl, revealedRows)` —— 焦点移到尚未揭示的行时先把它揭示出来（`revealedRows = idx+1`），再 `scrollToItem(idx-2)` 保证可见 | 直接修 t34 的「DOWN×8 可见行仍 1–6」 | 删该 effect |
| **R7** | 保留 t29 的“补行完成后校正滚动” | 双保险：揭示完成后若选中行不在可视区，再滚一次 | — |

> R1 + R6 一起才成立：subList 让列表长度随揭示增长（成本低），R6 保证焦点/滚动永远跟得上
> （焦点越界时按需提前揭示，而不是靠“加速”整体追赶）。

---

## 2. 预期（t37 验证）

| 项 | t30（10166） | t34（10168） | 本轮（待 t37） | 目标 |
|---|---|---|---|---|
| ≥300ms 巨帧 | 2 | 4 | ？ | **0** |
| 帧时中位 | 39.9ms | 103.2ms | ？ | 越小越好 |
| UI 中位 | 18.1ms | 49.9ms | ？ | ≤18ms 量级 |
| Draw 中位 | 2.1ms | 20.7ms | ？ | 回到 ~2ms |
| 50th | 46/38ms | 150/109ms | ？ | ≤16ms |
| Janky% | 100% | 100% | ？ | ≤20% |
| `drawer_close` | — | 9–24ms | ？ | 保持低价 |
| DOWN/UP 滚动 | 未测 | ❌ 不跟随 | ？ | **跟手** |
| 观感 | 无抖动 | 无抖动但有卡顿感 | ？ | 无抖动/跳 |

---

## 3. 同口径验收（t37）

```bash
adb shell setprop log.tag.IQTV-PERF DEBUG
adb shell dumpsys gfxinfo tv.iqtvlite.app.validation reset
# MENU 开 / BACK 关 ×5（间隔 800ms）；另做一次 DOWN×8 后 dump 可见行
adb shell dumpsys gfxinfo tv.iqtvlite.app.validation > drawer_r6.txt
adb logcat -d -s IQTV-PERF | grep -E 'drawer_open|drawer_close|drawer_compose'
```
关注：`drawer_compose frames=.. ms=.. step=2`（应恒为 2，不再出现加速）、`drawer_open ms=`（首帧）、
`drawer_close ms=`（关闭仍应 <30ms）、以及 DOWN×8 后可见行是否跟随（uiautomator dump 尺寸判据）。

---

## 4. 门禁与回退

- 静态：`check_perf_policy.py` 断言 `channels.subList(0, revealedRows`、`DrawerPlaceholderRow` 已移除、
  `DRAWER_ROW_HEIGHT_DP=56`、`drawer_close ms=`、**`focusedChannelUrl, revealedRows`（焦点跟随 effect）**、
  首帧 6 / 每帧 2。
- JVM：`PerfPolicyTest` 删除加速用例，保留 `revealedRowsGrowPerFrameAndStopAtTotal`（6→8→10→…→147）。
- 回退：R1（改回全量）、R3（恢复加速常量）、R4、R6（删 effect）互不依赖。
- 不改冷启/PSS 口径与默认；未动信息卡/设置/台标/自动更新；无 Prefs 迁移；体积 ≤30MB。

---

## 5. 交付 receipt

| 项 | 值 |
|---|---|
| 提交 / CI | 见 t36 任务输出（本文件随实现提交，定稿后补 run 号） |
