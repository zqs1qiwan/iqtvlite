# 决定性判据终验 v3 报告（steady 包 · CI#251 / vc10251）

- 时间：2026-11-23 22:39–23:02（Asia/Hong_Kong）
- 执行：acceptance（t86 attempt 1）
- 设备：TR100-G9 / `192.168.2.245:5555` / Android 9 (API28)，1920×1080
- 包：CI #251 / versionCode **10251** / `2.2.0-tivimini-dc9b30ab-validation` / sha256 `e28ed891d9a911f16f7ba0f74c44d923b3d87009c32768058ea1ebbbfa553603`（装后校验一致）
- 修复（t84 / dc9b30a）：抽取共享 convergeFocusToCenter、snapshotFlow 持续监测 `first!=target` 且无动画中主动补校正直到 `first==target`、scrollInProgress 守卫防抖
- 安装：备份 3,483,648B → 仅卸一次 → install → 回灌；prefs 无污染；P0 PlayerActivity、无 VerifyError/FATAL
- 截图：`docs/evidence/user-review/steady-{open,down,up}.png`

---

## 0. 结论：**判失败（决定性判据三场景未全部差0）**

15s 窗口内取得多份稳定 dump。结果：**呼出稳定差0、DOWN×10 部分样本差0、但存在多处差1–2行样本，UP×10 稳定差1行/顶边差2行**。按铁律"三场景全差0才通过"，判失败并附证据。t84 稳态监测有部分改善（DOWN 多数场景已比 t83 的恒定差1更好），但未稳定达标。

---

## 1. 决定性判据：高亮行频道 vs 焦点频道 对照表

口径：`channelNumbers[url]=组index+1`（单组 idx N→频道#N+1）；`V≈11, HALF=(V-1)/2=5`；高亮框固定在 pos5；收敛时 `first==target==focus-5` → 焦点在 pos5 → 差0。

| 场景/样本 | 高亮框(pos5)频道 | 焦点 idx→频道 | 焦点在dump pos | 差几行 |
|---|---|---|---|---|
| 呼出（st_open） | #14 CCTV-14 | idx13→#14 | 5 | **0 ✅** |
| DOWN×10（st_down，早期） | #24 | idx23→#24 | 5 | **0 ✅** |
| DOWN×10（g_down，6s稳态） | #22 | idx23→#24 | 7 | **2 ❌** |
| DOWN×10（f_dn，final） | #23 | idx23→#24 | 6 | **1 ❌** |
| UP×10（st_up，到顶边） | #6 | idx3→#4 | 3 | **-2 ❌（顶边钳制）** |
| UP×10慢步（st_upslow，深处） | #22 | idx22→#23 | 6 | **1 ❌** |

**原始日志证据（g_down，1 条关键片段）：**
```
22:54:57.022 row_focus idx=23 subtitle=true title=下一站   ← 焦点已到23
22:54:57.438 drawer_scroll_correct target=17 focus=22 first=15 last=25 smooth=2
22:54:57.441 drawer_steady_correct focus=21 first=17 vr=12 -> reconverge   ← 稳态监测用了过期focus=21
22:54:57.762 drawer_scroll_correct target=16 focus=21 first=17 last=27 smooth=-1  ← 反向滚，焦点23更偏
```
→ 稳态监测读到**过期 focus（21）**，在焦点已到 23 时反向滚动，反而把真实焦点推离中心，导致差2行。这是"快照焦点与当前焦点不同步"的竞态，t84 的持续监测未解决反而引入新抖动路径。

**说明**：dump 与日志存在 1–2s 时序错位（uiautomator dump 耗时），部分样本差1可能含动画未完成因素；但 g_down 日志铁证显示稳态监测确实基于过期 focus 反向滚动，**这是真实缺陷**，不是纯采样误差。

---

## 2. 回归项（全部通过）

- **高亮 bounds 静止居中** ✅：静态 Box `.offset(y=highlightTop)` + testTag，结构恒定；日志 vr=11/12 动态可视行数生效。
- **长按到端不钉不引抖动** ✅：长按 DOWN×30 gfxinfo 50th=73/90th=129/95th=150/99th=200ms，**无 >300ms 帧**；稳态监测未引入可见重复滚动卡顿；t68"钉4-13"未现。
- **抽屉 15s 自动收回** ✅：`drawer_open 23:00:41.128 → drawer_close 23:00:56.311 = 15.18s`（本包临时值）。
- **节目单无竖线+透明** ✅：边界 x470-495 颜色均匀（44-62）无 1px 亮线；代码删竖线 + alpha=0.55f。
- **5大logo/确认菜单** ✅：recent=5、两侧提示在；BACK TOAST_WINDOW + 代码 `Toast.makeText(...,"已取消选择")`；不计双按/7s 前轮同代码已验。
- **焦点灰白/选中蓝字、换台不回开头、按LEFT定位当前台、换台抽屉保持、logo像素级、设置187、数字OSD、S1/MP2/上一台/清空/自动更新/台标、遥控** ✅：前轮同代码真机验证无回退。多音轨/睛彩源 → 未验证≠通过。
- **不崩不灰** ✅：Fatal/VerifyError=0。

---

## 3. 收尾

前台 PlayerActivity；实验键 0；buffer 无覆盖（稳500 默认）；Fatal/VerifyError 0；debug tag 复位；/data 2.56G(23%)；centerfix 回滚包保留。

---

## 4. 最终判定与建议

**判失败**：三场景未全部差0（呼出0、DOWN 0~2不稳定、UP稳定差1~2）。

**根因**：t84 的 `drawer_steady_correct` 快照了过期 focus 值（reconverge 时 focus=21 vs 实际 23），在焦点已更新后仍基于旧值反向滚动，引入新的偏离而非收敛。

**根治建议**：
1. 稳态监测的 `first!=target` 判定里的 `focus`/`target` 必须与当前 `focusedChannelUrl → index` **实时同步取值**（用 `rememberUpdatedState` 的最新值，而非 collect 启动时的快照）；避免 reconverge 使用旧 focus。
2. 收敛循环里对 `scrollInProgress` 为 false 时再读一次最新 focus 重算 target；若期间 focus 变化则重新进入校正。
3. 边缘钳制场景（idx<HALF）：焦点无法居中是物理必然（列表顶部无行），需明确产品口径——可接受"顶部/底部焦点贴边"不判失败，但需与用户对齐该基准。
