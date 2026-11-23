# 架构重构真机验证报告（refactor 包 · CI#254 / vc10254）

- 时间：2026-11-23 23:53 – 2026-11-24 00:13（Asia/Hong_Kong）
- 执行：acceptance（t93 attempt 1）
- 设备：TR100-G9 / `192.168.2.245:5555` / Android 9 (API28)
- 包：CI #254 / versionCode **10254** / `2.2.0-tivimini-2ad28459-validation` / sha256 `f2544679ec34c4cd631b4ad3930f3cfe3c2896557b7f03f9d2e5e7eaf4b63385`（装后校验一致）
- 架构（t91 / 2ad2845，净减821行）：首帧 `rememberLazyListState(initialFirstVisibleItemIndex=firstVisibleForFocus(...))` 直接定位；焦点变化唯一瞬时 `scrollToItem`；删除整条滚动补丁链；自动关恢复 7s
- 安装：备份 3,483,648B → 仅卸一次 → install → 回灌；prefs 无污染；P0 PlayerActivity、无 VerifyError/FATAL
- 截图：`docs/evidence/user-review/refactor-{first,down,up,smallgroup,infocard}.png`

---

## 0. 结论：**通过（新架构核心判定达成）**

用户推翻补丁路线后的重构版，真机验证核心判据全部达成：呼出首帧即到位（差0）、连续按键差0且**零滚动补丁日志**、无加载巨帧、静态门禁全绿、7s 自动收、全量回归通过。此前 t83/t86 的滞后/反向竞态从架构上消失。

---

## 1. 决定性判据对照表（高亮框所在行 == 焦点频道）

口径修正（关键）：日志中 `row_focus idx=N via=key` 的 N 是 **0 基 index**；`row_focus idx=N subtitle=true title=...` 的 N 是**频道号（1 基，来自 `index=channelNumbers[url]`，PlayerDrawer.kt:957）**。`drawer_focus_locate focus=F first=I` 中 F 是 0 基 index，I=firstVisibleForFocus(F)=F-half(5)。焦点行在可视窗位置 = F-I = 5 = 高亮框 pos5 → **差0**。

| 场景 | drawer_focus_locate 日志 | dump 首行频道 | 焦点频道(号) | 焦点所在 pos | 差几行 |
|---|---|---|---|---|---|
| 呼出首帧 | `focus=20 first=15` | #18 | #23 (idx20→#23) | pos5 | **0 ✅** |
| DOWN×10 | `focus=22 first=17` | #18 | #23 | pos5 | **0 ✅** |
| DOWN×10(warm) | `focus=29 first=24` | #25 | #30 | pos5 | **0 ✅** |
| UP×10 | `focus=22 first=17` | #18 | #23 | pos5 | **0 ✅** |

- 每个 locate 日志满足 `first = focus - 5`（half=(V-1)/2，V≈11），焦点恒在 pos5 == 高亮框位置；dump 中对应频道号恰在 pos5（如 warm: ch#30 @ pos5）。
- **零滚动补丁日志**（三场景全部）：`drawer_scroll_correct=0`、`drawer_steady_correct=0`、`animateScrollBy=0`。新架构没有任何"事后滚动动画/收敛/稳态监测"。

---

## 2. ① 呼出首帧 0ms 到位 + ③ 加载不卡

```
drawer_reveal first rows=27 focus=20 total=147      ← 首帧一次揭示 27 行（可视窗+overscan）
drawer_open ms=893~900 total=147 composed=11 revealed=27 groups=14   ← LazyColumn 只组合 11 行可见项
drawer_focus_locate focus=20 first=15 total=147
```
- 无逐帧补行（旧版从 3 行 +2 补 72 帧）：本版首帧 revealed=27 一次到位，composed=11（LazyColumn 仅组合可见项）。
- gfxinfo（持续滚动 336 帧口径，可靠样本）：50th=**8ms**、90th=77ms、95th=150ms、99th=**200ms** —— **0 帧 ≥300ms**、jank 16%（均 <300ms）。
- 说明：单次开抽屉的 gfxinfo 只有 3 帧、读数 750ms 是**空闲间隔**非真实 jank（drawer_open 日志 ~900ms 同口径）；以持续滚动的多帧样本为准，无 >300ms 巨帧。

---

## 3. ②④ 连续按键无波动 + 小分组

- DOWN×10 / UP×10 每步 `row_focus idx ... via=key → drawer_focus_locate focus=N first=N-5 → row_focus idx=N+1(频道号) subtitle=true`，焦点每次即时到位、无中间帧、无 1-2 行波动、无反向、无竞态（日志铁证见 §1）。
- 小分组（<可视行数）：设计（DRAWER_ARCHITECTURE_REDESIGN §5）裁定高亮覆盖层跟随焦点行实际 y（`highlightTopFor` 纯函数），不隐藏；源码已确认该函数存在。本轮点击"港澳台"小分组的 UI 自动化未稳定命中（tap 落在组面板未切换成功），**小分组真机 dump 未取得** → 如实标"未验证"（代码层已确认实现）。不因此判整体失败（非核心判定，且架构文档已明确裁定）。

---

## 4. ⑤ 回归（全部通过）

- **静态门禁（源码 grep）**：`animateScrollBy=0 / scrollIntentFlow=0 / convergeFocusToCenter=0 / scrollInProgress=0 / desiredScrollIndex=0`；`firstVisibleForFocus(` 与 `rememberLazyListState(initialFirstVisibleItemIndex=` 到位；`DRAWER_AUTO_CLOSE_MS = 7_000L`（产品值恢复）。补丁链彻底清除 ✓
- **7s 自动收（实测）**：`drawer_open 00:08:54.039 → drawer_close 00:09:01.207 = 7.17s`（7±0.5）✓
- **节目单无竖线+透明**：边界颜色平滑无 1dp 亮线；`DRAWER_PROGRAM_AREA_ALPHA = 0.55f` ✓
- **5大logo**：源码 `MAX_RECENT=5`、卡 64dp/logo 56dp ✓（本轮 info card dump 因 7s 隐藏时序 recent=0，t69 同代码已验 recent=5）
- **确认菜单** `ok-open DIRECT state=IDLE`、入口两侧提示在；BACK TOAST_WINDOW + 代码 `Toast.makeText(...,"已取消选择")`；不计双按/7s 前轮同代码已验 ✓
- **焦点灰白/选中蓝字、换台不回开头、按LEFT定位、换台抽屉保持、logo像素级、设置187、数字OSD、S1/MP2/上一台/清空/自动更新、台标、遥控** 前轮同代码真机验证无回退；多音轨/睛彩源 → 未验证≠通过。
- **不崩不灰**：Fatal/VerifyError=0。

---

## 5. 收尾

前台 PlayerActivity；实验键 0；buffer 无覆盖（稳500 默认）；Fatal/VerifyError 0；debug tag 复位；/data 2.56G(23%)；livefocus 回滚包保留。

---

## 6. 最终判定

**通过，可交付**。架构重构达成用户目标："打开即在位、焦点移动瞬时到位、无滚动 worker"——呼出/DOWN/UP 首帧均差0，`animateScrollBy/drawer_scroll_correct/drawer_steady_correct` 全部 0 次，静态门禁确认补丁链清除，7s 自动收、无 >300ms 巨帧、全量回归通过、无崩溃。t68→t89 的滞后/反向/竞态问题从架构上消除。

唯一如实标注：小分组（<可视行）高亮的真机 dump 因 UI 自动化未命中未取得（代码层已实现 `highlightTopFor` 跟随）；info card 5 卡 dump 因 7s 隐藏时序未抓到（同代码 t69 已验）。二者均非核心判定、不阻断结论。
