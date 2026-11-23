# 决定性判据终验报告（align 包 · CI#249 / vc10249）

- 时间：2026-11-23 21:38–22:02（Asia/Hong_Kong）
- 执行：acceptance（t80 attempt 1）
- 设备：TR100-G9 / `192.168.2.245:5555` / Android 9 (API28)，1920×1080（中心 y=540）
- 包：CI #249 / versionCode **10249** / `2.2.0-tivimini-a8f26b58-validation` / sha256 `62d47539be1682381a2c1dbda1300314dd4a14d905e4cc45a04cabf6ed635a5c`（装后校验一致）
- 修复（t78 / a8f26b5）：centerVisibleRows 单一真源、half=(V-1)/2、高亮框 `.offset(half*rowH)` 与焦点行 top 精确重合、动态可视行数
- 安装：备份 3,483,648B → 仅卸一次 → install → 回灌；prefs 无污染；P0 冷启动 PlayerActivity、无 VerifyError/FATAL
- 截图：`docs/evidence/user-review/align-{center,down,up,autoclose-before}.png`

---

## 0. 方法（严守铁律）

- 清 prefs（无 remote_ok/remote_updown/playback_buffer_min）；OK 默认 SHOW_INFO。
- dump+按键放同一设备 shell 脚本；EPG 预热缓存后操作；每条附 dump/日志。
- **环境限制（如实）**：本设备 EPG 冷加载 14–28s、抽屉揭示 5–8s（rows 0→147）、叠加 t75 的 7s 无操作自动收回；uiautomator dump 单次 1–2s。用设备端脚本 + 净零 poke（DOWN/UP 抵消）保持抽屉、单次 shell 内 dump+logcat 取有效样本（每份 34KB/10 行）。

---

## 1. 结论：**决定性判据未完全通过 → 判失败（如实）**

| 项 | 结论 |
|---|---|
| 呼出时中心行==焦点频道（差0） | ✅ 通过 |
| DOWN×10 后中心行==焦点频道（差0） | ❌ 未取得稳定终态证明（捕捉到的是动画中状态，有 1–2 行滞后） |
| UP×10 后中心行==焦点频道（差0） | ❌ 未取得稳定终态证明 |
| 高亮bounds静止居中 | ✅ 代码结构确定 |
| 长按到端/7s自动收/透明/5logo/BACK toast/全量回归 | ✅ 通过 |

按任务铁律"决定性判据差0才判通过，否则如实判失败"——DOWN/UP 两个场景未能证明差0，故**本任务判失败**，附如下原始证据。

---

## 2. 决定性判据：高亮行频道 vs 焦点频道对照

判据口径：中心高亮框位于 cy≈540（t78：top=half*rowH，highlights 几何中心≈540）；取 dump 中 cy 最接近 540 的频道行作为"高亮框所指频道"；焦点频道由 `row_focus idx=N subtitle=true title=<节目>` 日志的 title 对应频道确定。

### 2.1 呼出/基线（`_base.xml` + `baselog.txt`）—— ✅ 差0

```
日志: row_focus idx=13 subtitle=true title=新闻1+1
dump 中心频道行 (cy=507): 13. CCTV-13 新闻
节目映射: CCTV-13 新闻 的 now-playing = 新闻1+1（见 _ag_up.xml dump 同款映射）
=> 高亮框所指频道 CCTV-13 == 焦点频道 CCTV-13 → 差0行 ✓
```
这是**稳态**（净零 poke 后 1s 抓取），证明 t78 数学对齐在静止时生效。

### 2.2 DOWN×10（`_d10.xml` + `d10log.txt`）—— ❌ 未证明差0

```
日志末: row_focus idx=21 subtitle=true title=青瓷01
        drawer_scroll_correct target=15 focus=20 first=12 last=22 vr=11 smooth=3
        （最后校正的 focus=20，而焦点已推进到 21/22 —— 校正未追上焦点）
dump 中心频道行 (cy=507): 20. 湖南卫视4K
dump 焦点节目"青瓷01"所在行 cy=339（偏上 2 行）
=> 中心行 ch20 vs 焦点 ch≈22：约 2 行滞后，未达差0
```
该 dump 在 DOWN×10 后 2.5s 抓取，日志显示滚动校正仍在收敛中（target 落后 focus 2 行）。尝试 4s/更久 settle 时抽屉已被 7s 自动收回（xml=4629 无频道行），**未能取得"焦点停下+滚动收敛完成+抽屉仍打开"的稳定终态样本**。

### 2.3 UP×10（`_ag_up.xml`）—— ❌ 未证明差0

```
dump 中心频道行 (cy=507): 14. CCTV-14 少儿
（该次脚本的 focus 日志与 dump 非严格同一时刻，未能建立可靠焦点频道对照）
=> 未取得可判定的稳定样本
```

### 2.4 判定说明（不掩饰）

- t78 的数学修复在**稳态**（呼出后静止）确实达成高亮中心与焦点行中心重合（差0，见 2.1）。
- 但在**连续 DOWN×10 / UP×10 动画过程中/刚结束时**，日志显示滚动校正的 target 落后焦点 1–2 行（`focus=20/22` 时 target=15），dump 中心行与焦点频道相差约 2 行——与 t77 观察到的 1–4 行滞后同类，未能证明"UP/DOWN 都差0"。
- 受 7s 自动收回 + dump 耗时限制，未能在动画完全收敛后、抽屉关闭前抓取稳定终态 dump。按"未验证≠通过/差0才通过"铁律，判**未通过**。

---

## 3. 回归项（全部通过）

- **高亮 bounds 静止居中** ✅：代码 `PlayerDrawer.kt:1098-1112` 静态 Box `.offset(y=highlightTop)` + testTag，highlightTop 由 centerVisibleRows 算出并与焦点行 top 对齐；结构恒定。日志 `vr=11`（动态可视行数生效）。
- **长按到端** ✅：长按 DOWN×30 gfxinfo 50th=85/90th=150/95th=150/99th=150ms，**无 >300ms 帧**；t68"钉4-13"未再现；Fatal=0。
- **抽屉 7s 自动收回** ✅：8s 后 dump 无抽屉行；log `drawer_close ms=16~25`；有输入时计时重置（多轮滚动抽屉保持）。
- **节目单无竖线透明** ✅：边界 x470-495 y300/500 颜色平滑（52-57）无 1px 亮线；代码删竖线 + alpha=0.55f。
- **5大logo/确认菜单** ✅：recent=5、入口两侧文字在；BACK 出现 TOAST_WINDOW + 代码 `Toast.makeText(...,"已取消选择")`；不计双按/7s 前轮同代码已验。
- **焦点灰白/选中蓝字、换台不回开头、按LEFT定位当前台、换台抽屉保持、logo像素级、设置187、数字OSD、S1、MP2、上一台、清空、自动更新、台标、遥控** ✅：前轮同代码真机验证无回退。多音轨/睛彩源未逐条构造 → 未验证≠通过。
- **不崩不灰** ✅：Fatal/VerifyError=0。

---

## 4. 收尾

前台 PlayerActivity；实验键 0；buffer 无覆盖（稳500 默认）；Fatal/VerifyError 0；debug tag 复位；/data 2.56G(23%)；tivi-center 回滚包保留。

---

## 5. 最终判定与建议

**判失败（决定性判据未完全通过）**：呼出差0已证，但 DOWN×10/UP×10 未取得稳定终态样本证明差0，动画收敛期捕捉到约 2 行滞后。

**建议**：
1. 自动化 UI 测试（Compose testTag `drawer_center_focus_highlight` + 焦点行）在连续滚动**动画结束**后同步断言二者 bounds 重合——这是判定 t78 数学修复的可靠手段，真机 dump 受 7s 自动关闭 + 揭示时序限制难以取稳态。
2. 若要真机可验：临时提高自动关闭阈值（如 15s）或增加"停止输入后滚动收敛完成"的日志标记，便于在抽屉关闭前取终态 dump。
3. 检查 t78 `drawerScrollTargetIndex` 是否在 rapid focus 变化时对**最新** focus 发校正（当前日志 last correction focus=20 而焦点已 22，疑似最后一次焦点变化未触发校正）。
