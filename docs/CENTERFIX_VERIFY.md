# 决定性判据终验 v2 报告（centerfix 包 · CI#250 / vc10250）

- 时间：2026-11-23 22:10–22:33（Asia/Hong_Kong）
- 执行：acceptance（t83 attempt 1）
- 设备：TR100-G9 / `192.168.2.245:5555` / Android 9 (API28)，1920×1080
- 包：CI #250 / versionCode **10250** / `2.2.0-tivimini-70f83ff6-validation` / sha256 `a5550146f6a57f383747975bd99923b312e0350fb5780934ff3f82eaf941dd66`（装后校验一致）
- 修复（t81 / 70f83ff）：CLEAR 条件改为 first==target（焦点真正居中才停）、边缘偏离中心继续 ±8 收敛、自动关临时 15s
- 安装：备份 3,483,648B → 仅卸一次 → install → 回灌；prefs 无污染；P0 PlayerActivity、无 VerifyError/FATAL
- 截图：`docs/evidence/user-review/centerfix-{open,down,up}.png`

---

## 0. 结论：**判失败（决定性判据未达差0）**

本次有 15s 自动关窗口，成功取得冷启/DOWN×10/UP×10 三份稳定终态 dump（34KB/10 行）。对照结果：**呼出差0，但 DOWN×10 和 UP×10 均差 1 行**（未达任务铁律"UP/DOWN 都差0才通过"），如实判失败并附证据与根因。

---

## 1. 决定性判据：高亮框所在行频道 vs 焦点频道 对照表

判据口径（依据源码）：
- `channelNumbers[url]=分组index+1`（PlayerActivity.kt:1762-1765），单组内 `row_focus idx=N → 频道#(N+1)`。
- t78/t81：`HALF=(V-1)/2=(11-1)/2=5`，target=`focus-5`，高亮框中心固定在可视区第 5 行（pos5）。
- **收敛判据**：`firstVisible == target == focus-5` → 焦点行落在 pos5 == 高亮框 → 差0行。
- 高亮框 pos5 对应频道由 dump 的 channel 行（x140-430，按 y 排序）取第 6 行；焦点频道在 dump 中的 pos 由其频道#定位。

| 操作 | 高亮框行(pos5)频道 | 焦点 idx→频道 | 焦点频道在dump的pos | **差几行** | 最后 scroll 校正 |
|---|---|---|---|---|---|
| 呼出 | 频道#14 CCTV-14 | idx13→#14 | pos5 | **0 ✅** | target=7 focus=12 first=6（STALE） |
| DOWN×10 | 频道#22 江苏4K | idx22→#23 | pos6 | **1 ❌** | target=16 focus=21 first=14（STALE） |
| DOWN×10(慢步) | 频道#23 东方4K | idx23→#24 | pos6 | **1 ❌** | target=17 focus=22 first=16（STALE） |
| UP×10 | 频道#7 | idx7→#8 | pos6 | **1 ❌** | target=1 focus=6 first=2（STALE） |

**原始证据（dump + log 摘录）：**
```
呼出: 高亮pos5=14.CCTV-14(y608区) ; focus idx=13 ; dump 13.CCTV-13@pos4,14.CCTV-14@pos5 → 焦点#14在pos5 → 差0
DOWN×10: 高亮pos5=22.江苏4K ; focus idx=22 subtitle=true ; dump 22.江苏4K@pos5, 23.东方4K@pos6 → 焦点#23在pos6 → 差1
UP×10: 高亮pos5=频道#7 ; focus idx=7 subtitle=true ; 焦点#8在pos6 → 差1
```

**根因（日志铁证）**：每个场景的最后一条 `drawer_scroll_correct` 的 focus 值都比最终 focus **小 1**（呼出 12 vs 13、DOWN 21 vs 22、慢步 22 vs 23、UP 6 vs 7）——**最终那一次焦点前进（idx+1）没有触发滚动校正**。因此列表停在 `first=focus_old-5`，而焦点已是 `focus_old+1`，焦点行落到 pos6 → 稳定差 1 行。t78 数学对齐本身正确（呼出差0证明），t81 的 CLEAR 条件修的是"没到 target 就停"，但没有修复"最后一步焦点变化未发校正"。

---

## 2. 回归项（全部通过）

- **高亮 bounds 静止居中** ✅：静态 Box `.offset(y=highlightTop)`+testTag 结构恒定；日志 `vr=11`（动态可视行数生效）。
- **长按到端** ✅：长按 DOWN×30 gfxinfo 50th=89/90th=150/95th=150/99th=200ms，**无 >300ms 帧**；t68"钉4-13"未现。
- **抽屉 15s 自动收回（本包临时值）** ✅：实测 `drawer_open 22:31:37.955 → drawer_close 22:31:53.049 = 15.09s`。
- **节目单无竖线+透明** ✅：边界 x470-495 颜色均匀（55-61）无 1px 亮线；代码删竖线 + alpha=0.55f。
- **5大logo/确认菜单** ✅：BACK 出现 TOAST_WINDOW + 代码 `Toast.makeText(...,"已取消选择")`；不计双按/7s/入口两侧/焦点灰白/选中蓝字前轮同代码已验（本轮 info card dump 因 7s 隐藏时序未抓到 5 卡，如实标注 t77 同代码已验 recent=5）。
- **换台不回开头、按LEFT定位当前台、换台抽屉保持、logo像素级、设置187、数字OSD、S1、MP2、上一台、清空、自动更新、台标、遥控** ✅：前轮同代码真机验证无回退。多音轨/睛彩源 → 未验证≠通过。
- **不崩不灰** ✅：Fatal/VerifyError=0。

---

## 3. 收尾

前台 PlayerActivity；实验键 0；buffer 无覆盖（稳500 默认）；Fatal/VerifyError 0；debug tag 复位；/data 2.56G(23%)；align 回滚包保留。

---

## 4. 最终判定与建议

**判失败**：决定性判据 DOWN×10/UP×10 稳定差 1 行（呼出差0）。t78 数学对齐正确、t81 CLEAR 修复部分生效，但存在"最后一次焦点变化未触发滚动校正"的系统性缺陷。

**根治建议**：在焦点变化的收集流里，对**每一次** focus idx 变化（包括最后一步）都 post 一次滚动意图（当前逻辑疑似在 rapid key 下合并/漏掉了末次 intent）；或让中心校正 effect 持续监测 `focus - firstVisible != HALF` 作为稳态条件，一旦偏离就补校正直至相等（而不只在收到新 intent 时校正一次）。这样最后一次焦点前进也会触发补校正到 pos5，达成差0。
