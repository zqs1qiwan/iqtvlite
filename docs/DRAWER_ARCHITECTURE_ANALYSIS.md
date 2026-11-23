# 选台抽屉架构问题分析（供重构参考）

- 整理：acceptance
- 背景：t68→t89 连续多轮真机复现/验证，用户在 t88 后明确推翻当前架构路线："从 t72→t87 一直在滚动收敛 worker + 稳态监测 + 实时焦点 + 防竞态上打补丁，越叠越复杂还不断引入新竞态——这正是禁止的补丁式修复。TiviMate 是呼出菜单首帧就在当前台位置、不滚动过去；'打开后滚动到当前台'根本是错误设计。"
- 本文档只做问题归纳与根因，不含代码修改。**t89(livefocus)验证已按 captain 指令停止**，结论不重要。

---

## 1. 根本设计错误（用户核心结论）

当前实现：`open drawer → 计算 target → 用 LazyColumn 滚动(scrollToItem/animateScrollBy/animatedScrollBy 循环)到当前台所在位置`。
- 这条"打开后再滚过去"的路线从 t49 的 `drawer_scroll_correct once` 起，经 t52(animateScrollBy)、t56(focus-6居中)、t62(INSTANT)、t65(arm instant)、t69(worker解耦)、t75(静态高亮覆盖层)、t78(数学对齐)、t81(CLEAR)、t84(稳态监测 reconverge)、t87(实时焦点重读) —— 逐层叠加，每层修一个上层引入的竞态，越叠越复杂，仍不断产生新问题（t83 末次 intent 漏发→差1行、t86 稳态监测读旧 focus→反向滚动）。
- **TiviMate 正确模型**：抽屉第一次组合（首帧）就把 LazyColumn 的初始滚动位置直接放在当前台，即 ` LazyColumn(initialFirstVisibleItemIndex = ...)` / `rememberLazyListState(initialFirstVisibleItemIndex = 当前台-half)`，首帧即到位，**没有"滚动动画"这个过程**，因此不存在收敛/竞态/高亮与列表不同步。

---

## 2. 各轮引入的新竞态/缺陷（说明补丁路线的死胡同）

| 版本 | 试图解决 | 实际引入的新问题 |
|---|---|---|
| t69 滚动 worker 解耦 | 长按钉4-13零位移 | worker key 与快照时序耦合，焦点快速前进时校正 target 落后（t83 日志 last correction focus 总比终 focus 小1） |
| t75 静态高亮覆盖层 | 高亮随列表移动 | 高亮框固定中心，但列表未必滚到对应行 → 高亮指向错误台（t77 的 1-4 行滞后） |
| t78 数学对齐 half=(V-1)/2 | 高亮中心与焦点行中心重合 | 只修几何公式，未修"列表是否真的滚到目标"（t80 呼出差0但 DOWN/UP 差2） |
| t81 CLEAR=first==target | 未到 target 就停 | 修"没到就停"但没修"最后一步焦点变化未发校正"（t83 末次 intent 漏发差1） |
| t84 稳态监测 reconverge | 末次漏校正 | reconverge 用了 collect 启动时的旧 focus 快照，焦点已到23仍按21反向滚 smooth=-1（t86 铁证） |
| t87 实时焦点重读 | t86 旧 focus 反向滚 | 未及验证即被用户叫停 |

---

## 3. 关键日志铁证（说明为何补丁路线不可持续）

- t83：`drawer_scroll_correct target=16 focus=21 first=14 last=24`，而最终 focus=22 —— 最后焦点前进未触发校正 → 差1行。
- t86：`row_focus idx=23 (22:54:57.022)` 后紧接 `drawer_steady_correct focus=21 first=17 -> reconverge (22:54:57.441)` 与 `drawer_scroll_correct target=16 focus=21 smooth=-1` —— 焦点已前进到23，滚动却反向（smooth=-1），把焦点推得更偏。
- 多轮 dump 对照：呼出（首帧 INSTANT 场景）稳定差0，但任何"打开后再滚"的连续按键场景（DOWN×10/UP×10）都出现 1-2 行波动 —— 因为滚动是一个会与输入竞态的过程。

---

## 4. 重构建议（对齐 TiviMate 首帧定位）

1. **首帧定位，取消滚动过程**：抽屉可见时，用 `rememberLazyListState(initialFirstVisibleItemIndex = 当前台index - half)`（half=(可见行数-1)/2）直接构造；当前台切换只在**抽屉关闭状态**更新该初值，打开期间不再因焦点变化 scrollToItem/animateScrollBy。
2. **焦点=列表位置的单一真源**：焦点行就是"当前台 index"，高亮框固定中心；UP/DOWN 只改变"当前台 index"这一状态，LazyColumn 位置由初值 + 用户滚动自然驱动，不再有独立滚动校正 effect。
3. **删除整条补丁链**：`desiredScrollIndex` / `desiredScrollInstant` / `scrollIntentFlow` / `scrollJob` / `drawerScrollTargetIndex` / `drawerScrollDecision` / `drawerScrollCorrected` / `drawerMonotonicStep` / `drawer_steady_correct` / `convergeFocusToCenter` / `drawer_center_focus_highlight` 几何对齐等全部回滚；保留 t72 的透明节目单区（无竖线）与 t69 的近期 5 大 logo 等与滚动无关的正确改动。
4. **边缘钳制**：首帧定位时 list 无法把 idx<HALF 的台放正中（顶部无行），这是物理必然；需与用户对齐"首尾台贴边"的验收口径，不应再为此加补丁。
5. **自动关 15s**：t81 起把自动关临时改成 15s 便于验收；重构后应确认是否恢复产品值，并单独验证。

---

## 5. 设备与现场状态

- t89 验证按 captain 指令**已停止**（livefocus 包 vc10252 已安装在 192.168.2.245:5555，P0 冒烟通过，无崩溃）；已复位 debug tag、清理临时 dump/脚本、PlayerActivity 前台、Fatal=0、buffer 稳500。
- 回滚包保留：steady / centerfix / align / tivi-center 等均在 delivery-apks。
- 详见各轮报告：`FOCUSED_FIX_VERIFY.md`、`BACK_ZAP_ROOTFIX_VERIFY.md`、`BATCH2_ROOTFIX_VERIFY.md`、`FINAL_REGRESSION_VERIFY.md`、`USER_REPRO_DIAGNOSIS.md`、`T59_ROOTFIX_VERIFY.md`、`T62_LANDING_VERIFY.md`、`T65_FINAL_VERIFY.md`、`TIVI_CENTER_VERIFY.md`、`TIVIC_CENTER_VERIFY.md`、`ALIGN_FINAL_VERIFY.md`、`CENTERFIX_VERIFY.md`、`STEADY_CENTER_VERIFY.md`。
