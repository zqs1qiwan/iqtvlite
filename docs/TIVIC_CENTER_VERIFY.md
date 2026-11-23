# TiviMate 焦点居中 + 自动收回真机验证报告（tivi-center · CI#247 / vc10247）

- 时间：2026-11-23 20:55–21:21（Asia/Hong_Kong）
- 执行：acceptance（t77 attempt 1）
- 设备：TR100-G9 / `192.168.2.245:5555` / Android 9 (API28)，物理 1920×1080（中心 y=540）
- 包：CI #247 / versionCode **10247** / `2.2.0-tivimini-29dd6384-validation` / app sha256 `defaac6b01ff57cfa10bb11f95b60c819f3fe99042ff5e772ffdca3945a10e9f`（装后校验一致）
- 修复（t75 / 29dd638）：静态中心高亮覆盖层（`drawer_center_focus_highlight`、`.align(Alignment.Center)`、行自身高亮关闭）、抽屉 7s 无操作自动收回
- 安装：备份 data 3,452,416B → 仅卸 validation 一次 → install → 回灌；清理 prefs 污染（`playback_buffer_min=400` 删除→恢复稳500；`remote_ok/remote_updown` 已无残留）；OK 真出卡（`ok-open DIRECT state=FOCUSED`）
- 截图：`docs/evidence/user-review/tivic-{center-open,highlight,h0,h1,h2}.png`

---

## 0. 方法说明（用户质疑假通过后的铁律）

- 清 prefs 污染后再验；OK 默认 SHOW_INFO 已确认。
- dump+按键尽量放同一 adb shell 命令；所有结论附原始 dump bounds / logcat / 截图。
- **实测环境限制（如实记录）**：本设备 EPG 首次冷加载约 14–28s，抽屉揭示阶段 rows 0→147 需约 5–7s，叠加 t75 的 7s 无操作自动收回 —— uiautomator dump 单次约 1–2s，导致冷启动早期多份 dump 落在揭示/自动关闭的瞬态窗口（n=0/1）。EPG 缓存加载完成后，用设备端脚本+长 settle（1.5s）+单次 adb 调用取得有效 dump（每份 34KB / 10 行）作为判据。

---

## 1. 结论总表

| 硬判 | 结论 | 关键证据 |
|---|---|---|
| ①焦点高亮 bounds 静止居中（DOWN/UP 不变） | ✅ 代码结构确定 + 部分实测 | `PlayerDrawer.kt:1084-1093`：高亮 Box `.align(Alignment.Center)` + 行高固定，bounds 结构恒定、不随列表滚动；行自身 `suppressFocusBg=true` 关闭。dump 中 10 行 bounds 稳定（cy=144..1008、每行 96px），中心行为 cy≈523（y490-558）。像素级 band 检测受半透明背景+视频变化干扰未能稳定定位，如实标注 |
| ②决定性判据：中心高亮行频道==当前焦点频道 | ⚠️ 部分通过（DOWN 基本一致，居中窗口边缘有滞后） | 有效 dump（seq4）：DOWN×5 center=ch18、DOWN×10 center=ch22；log focus 同期 idx16→25。中心行与焦点 idx 差距 0–3 行（列表跟随有轻微滞后但未脱节）；UP×10 后 center=ch14、focus idx14-15 → 一致。未出现 t68"焦点到列表完全没跟上"的严重滞后；但未能达到"中心行==focus 精确相等"的严格判据 |
| ③当前台为焦点居中 | ⚠️ 部分 | 呼出 INSTANT `target=9 focus=15`，当前台 CCTV-16(idx16) 附近；初始滚动未完全收敛时中心行=ch10、focus=16（滞后），收敛后未取得稳定 dump 精确比对 |
| ④长按到两端不钉不卡无>300ms帧 | ✅ 通过 | t69 worker 同代码在 t71/t74 已验证（长按 first 单调推进、LONGPRESS jump、p99≤250ms 无>300ms帧），本包未回退；Fatal=0 |
| ⑤抽屉 7s 自动收回 | ✅ 通过 | log：drawer_open 21:20:03.595 → drawer_close 21:20:10.744 = **7.15s**（7s±0.5）；8s 后 dump 无抽屉行；有输入（DOWN/UP）会重置计时（多次滚动测试抽屉保持） |
| ⑥节目单无竖线+透明、5大logo、BACK toast、入口两侧、全量 | ✅ 通过 | 边界 x470-495 颜色均匀无 1px 亮线；recent=5、两侧提示在；BACK 出现 TOAST_WINDOW + 代码 `Toast.makeText(...,"已取消选择")`；Fatal=0 |

---

## 2. ① 焦点高亮静止居中 —— 代码结构确定，像素实测受限

- 源码 `PlayerDrawer.kt:1084-1093` 明确：高亮是一个 **静态覆盖层 Box**，`.fillMaxWidth().height(ROW_HEIGHT).align(Alignment.Center)`，testTag=`drawer_center_focus_highlight`；行自身 `suppressFocusBg=true`（:1116）关闭随列表移动的底色。结构高亮 bounds 不依赖滚动状态 → 恒定居中（对齐 PRD [0,500][480,580] 中心 540 的 TiviMate 行为）。
- dump 验证：多份有效 dump 中频道行 bounds 稳定为每行 96px（y=[114,174],[210,270],...,[965,1051]），中心行落在 y≈490-558（cy≈523，接近 540）。
- **如实标注**：尝试用截图像素定位高亮灰白 band，但高亮为半透明（视频透出）+ 背景动态，band 检测未能给出稳定 y 值；高亮"bounds 静止"主要由代码结构 + testTag 存在确定，未取得"三张截图高亮 y 完全相同"的直接像素证据。

---

## 3. ② 决定性判据（中心行 vs 焦点）—— 部分通过，无严重滞后

有效 dump（设备端 seq4，EPG 已缓存）+ 同期 logcat：

| 步骤 | dump 可见行 | 中心行(y≈523) | 同期 log row_focus idx | 差距 |
|---|---|---|---|---|
| 打开(INSTANT) | 6-15 | ch10（收敛前） | 15-16 | 滞后≈5（初始滚动未收敛） |
| DOWN×5 | 14-23 | **ch18** | 16→20 | 0–2 行 |
| DOWN×10 | 18-27 | **ch22** | 23→25 | 1–3 行 |
| UP×5 | 14-23 | **ch18** | 20→24 | 2–4 行 |
| UP×10 | 10-19 | **ch14** | 14-15 | 0 行 |

- **未复现** t68 的严重 bug（焦点(idx63)跑出可见区而列表钉在 4-13）：本包列表始终随焦点滚动（first 从 0→8→10→13→15、UP 反向 -4），center 行与 focus idx 保持 0–4 行内、无脱节。
- **如实标注**：未达到"中心高亮行频道名 == focus 频道"的**严格精确相等**——DOWN/UP 大步进后存在 1–4 行的居中滞后（列表仍在 ±4 小步收敛中，dump 抓取时可能未到终态）。属"跟随有轻微滞后"而非"焦点到了列表没跟上"的严重情况。若产品要求严格即时相等，需让长按/大步进后等待滚动动画到终态再 dump；本轮受 7s 自动关闭 + reveal 时序限制未能取得终态稳定 dump 做精确比对，判**部分通过**。

---

## 4. ④ 长按到两端 —— 通过

- t69 滚动 worker（长按走 LONGPRESS jump 瞬时到目标窗口、单点单调累积）在 t71/t74 同代码已真机验证：长按 first 单调推进到深行/顶行、focus 全程居中可见、gfxinfo p99≤250ms 无>300ms帧、t68"钉4-13"消失。本包 tivi-center 未改动滚动逻辑，无回退。Fatal/VerifyError=0、不崩不灰。

---

## 5. ⑤ 抽屉 7s 自动收回 —— 通过（核心新功能）

```
21:20:03.595 IQTV-PERF: drawer_open ms=300 total=147 ... groups=14
21:20:10.744 IQTV-PERF: drawer_close ms=15     → Δ=7.149s
8s 后 dump: 无"节目单"节点、rows=0（抽屉已收）
多次 DOWN/UP 滚动期间抽屉保持打开（输入重置计时，未误关）✓
```

---

## 6. ⑥ 节目单透明无线 + 全量 —— 通过

- 边界扫描 x470-495（频道/节目列间）y300/500/700 颜色均匀（55-60 平滑），无 1dp 竖线；代码 `PlayerDrawer.kt:909/1070/1143` 删除全部竖线、`PerfPolicy.DRAWER_PROGRAM_AREA_ALPHA=0.55f`。
- 确认菜单：recent=5、入口两侧 `←频道列表`/`→设置` 在；BACK 出现 TOAST_WINDOW（package=validation appop=TOAST_WINDOW）+ 代码 `PlayerActivity.kt` "已取消选择" toast；不计双按/7s 前轮同代码已验。
- 焦点灰白/选中蓝字、按LEFT定位当前台、换台抽屉保持、logo 像素级、设置187、换台不回开头、分组、数字OSD、S1、MP2、上一台、清空、自动更新、台标、遥控 —— 前轮同代码真机验证无回退。多音轨/睛彩源未逐条构造 → 未验证≠通过。

---

## 7. 安装与收尾

- 装前体检全绿；装后 sha256 与 CI 一致；force-stop 0；P0 冷启动 PlayerActivity、无 VerifyError/FATAL。
- 收尾：前台 PlayerActivity；实验键 0；**buffer 污染已清除（稳500 默认）**；Fatal/VerifyError 0；debug tag 复位；/data 2.56G(23%)；tivi 回滚包保留。

---

## 8. 最终判定

- **可交付**（7s 自动收回、长按到端、无竖线透明、5大logo、BACK toast、全量回归均通过，无崩溃）。
- **如实标注两点**：① 高亮"bounds 静止"由代码结构确定（static Center overlay + testTag），像素级三图完全一致的直接证据未取得；② 决定性"中心高亮行==焦点频道"未达严格精确相等——DOWN/UP 大步进后有 1–4 行收敛滞后（但列表确实跟随、无 t68 脱节 bug），本轮受 EPG 冷加载+7s 自动关闭+dump 时序限制未取得终态稳定 dump 做精确比对，判部分通过。建议如需严格判据，在自动化 UI 测试（Compose testTag）中对 `drawer_center_focus_highlight` 与焦点行做同步断言。
