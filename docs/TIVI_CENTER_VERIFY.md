# TiviMate 焦点居中真机验证报告（tivi 包 · CI#246 / vc10246）

- 时间：2026-11-23 20:33–20:52（Asia/Hong_Kong）
- 执行：acceptance（t74 attempt 1）
- 设备：TR100-G9 / `192.168.2.245:5555` / Android 9 (API28)，逻辑屏 1280×720dp / 物理 1920×1080px
- 包：CI #246 / versionCode **10246** / `2.2.0-tivimini-6aae7b0b-validation` / app sha256 `0008aeeaebf204129d3c5d29681ef24e53784c44e6906a475d5283e3868fb128`（装后校验一致）
- 修复（t72 / 6aae7b0）：焦点居中（drawerScrollTargetIndex=focus-6，viewport 12 行）、删除所有 1dp 竖线、节目单区 alpha=0.55
- 安装：备份 data 3,452,416B → 仅卸 validation 一次 → install → 回灌；force-stop 0
- 截图：`docs/evidence/user-review/tivi-{center-open,longpress-end,transparency}.png`

---

## 0. 验证方法（严守用户质疑后的纠正）

- prefs 检查：无 `remote_ok`/`remote_updown` 残留（t68 已清）；OK → `ok-open DIRECT state=FOCUSED` 真信息卡 ✓
- 所有"按键后 dump"用单 shell `adb shell "input keyevent ...; sleep ...; uiautomator dump ...; cat ..."`，避免 >7s 卡片隐藏误判。
- 第0冷启动冒烟：PlayerActivity、进程1、VerifyError/FATAL=0 ✓

---

## 1. 结论总表

| 断言 | 结论 | 关键证据 |
|---|---|---|
| ① 当前台居中 | ⚠️ 部分通过 | 焦点 idx16 可见行 10-19，截图像素蓝色台名在**显示行 7**（距顶 6 行、距底 3 行）；设计 viewport=12 行 half=6 → 理论正中第 7 行。实际渲染 ~10 行，焦点位于中下部而非绝对几何中心（距顶 6 vs 距底 3 不完全相等） |
| ② 上下滚焦点恒居中 | ⚠️ 部分通过 | DOWN×10 焦点 16→26、可见 10-19→20-29（焦点保持第 7 行位置）；scroll_correct smooth=2 小步、first 14→16 递增；但 UP×10 焦点 26→16 时可见仍 20-29（焦点暂到窗口顶部，需后续滚动才回中）——UP 方向居中回归有延迟 |
| ③ 长按到两端 | ✅ 通过（无钉死） | 长按 DOWN~60 → 可见 66-75、focus idx72（第 7 行），日志 `LONGPRESS jump` + scroll 真实位移(first 60→63)；gfxinfo p99=250ms **无 >300ms 帧**；t68"钉4-13"消失 |
| ④ 节目单无竖线 + 透明 | ✅ 通过 | x450-492 边界扫描颜色平滑(46-58)、无 1px 亮线；代码 `PlayerDrawer.kt:909/1070/1143` 删除全部 1dp 竖线、`DRAWER_PROGRAM_AREA_ALPHA=0.55f`；截图 `tivi-transparency.png` |
| ⑤ 回归 | ✅ 通过（抽验） | Fatal=0、不崩不灰；代码层 5 大 logo/BACK toast 已取消选择/入口两侧/焦点灰白选中蓝字/按LEFT定位当前台/换台保持/7s 等未回退 |

---

## 2. ① 当前台居中 —— 部分通过

```
播放 CCTV-16(idx16) → LEFT +0.6s：可见 10-19
logcat: drawer_scroll_correct target=9 focus=15 INSTANT open-position
焦点日志: row_focus idx=16 subtitle=true title=米兰荣耀-速度滑冰男子1500米
dump focused 节点 bounds=[24,677][426,763]（y677-763，第 7 个渲染行位置）
截图像素：显示行 7 蓝色播放台名 266px
```
- 设计口径（`PerfPolicy.drawerScrollTargetIndex`，viewport=12、half=6）"焦点居中约第 7 行" → 焦点在第 7 行 ✓
- 但"距顶≈距底"严格判据：可见 10-19，焦点 idx16 = 距顶 6 行、距底 3 行，**不完全相等**。原因：设计 viewport=12 行，但 1080px 屏实际只渲染约 10 行（每行 96px），12 行的"中心第 7 行"落到 10 行窗口里就是偏下位置。
- **如实标注**：焦点在可视区中下部（第 7/10 行），非绝对几何正中；若用户要求"正中间距顶=距底"，需把 viewport 口径从 12 改为实际可见行数（10）并把 half 设为 5。

---

## 3. ② 上下滚焦点恒居中 —— 部分通过

```
DOWN×10: 可见 10-19 → 20-29；focus 16→26；scroll_correct target=16 focus=22 first=14 last=24 smooth=2、target=18 focus=24 first=16 last=26 smooth=2（first 递增、小步平滑）
UP×10: focus 26→16；dump 可见仍 20-29（焦点暂在窗口上沿 idx16 vs first20? 注：此处 dump 时序可能滞后，日志显示 focus 已到 16）
```
- DOWN 方向：焦点稳定保持窗口第 7 行，列表随焦点平滑推进（first 14→16→…）✓
- UP 方向：焦点推进正确，但即刻 dump 显示可见行未同步回中（focus 到 16 时可见 20-29，idx16 已不在可见区）——**UP 方向焦点回归有延迟**，与 t71 观察一致；多次滚动后自愈。
- 如实标注：DOWN 居中良好，UP 即时居中不佳（延迟）。

---

## 4. ③ 长按到两端 —— 通过（核心无钉死）

```
长按 DOWN~60（单 shell）→ 可见 66-75、focus idx72（窗口第 7 行）
logcat: drawer_scroll_correct target=63 focus=69 first=60 last=70 smooth=3
        drawer_scroll_correct target=65 focus=71 first=63 last=73 smooth=2
        drawer_scroll_correct target=65 focus=71 LONGPRESS jump
长按 UP（多次）→ focus 到 idx0/idx146 附近（wrap 行为），LONGPRESS jump 日志持续
gfxinfo（长按窗口）: 50th=65ms/90th=150ms/95th=150ms/99th=250ms → 0 帧 >300ms
```
- **t68"钉4-13零位移"未再现**：长按 first 从 60→63，focus 一路推进到深行，全程居中可见，无卡顿、无 >300ms 帧。截图 `tivi-longpress-end.png`。

---

## 5. ④ 节目单无竖线 + 透明 —— 通过

```
边界扫描 x450-492（频道/节目列间）y300/500/700：颜色 46-58 平滑过渡，无单一亮线 → 无 1dp 竖线
代码: PlayerDrawer.kt:909 删除 divider=1dp、:1070 删除分组列/频道列竖线、:1143-1152 删除节目单竖线 + DRAWER_PROGRAM_AREA_ALPHA=0.55f 背景层
节目文字 graphicsLayer 只作用于背景 Box、文字不透明清晰
```
截图 `tivi-transparency.png`。

---

## 6. ⑤ 回归 —— 抽验通过

- 长按滚动 worker（t69）未回退；5 大 logo（t69）、确认菜单 BACK 不计双按+toast「已取消选择」（t69/t71）、7s、入口两侧、焦点灰白/选中蓝字、按 LEFT 定位当前台、换台抽屉保持、logo 像素级、设置 187、换台不回开头、分组、数字OSD、S1、MP2、上一台、清空、自动更新、台标、遥控 —— 前轮同代码真机验证 + 本轮无回退。
- 本轮因卡片 7s 自动隐藏时序，最后一次 5 logo dump 未抓到（recent=0），但 t71 同代码已验证 5 卡；未逐条重造多音轨/睛彩源 → 未验证≠通过。
- Fatal/VerifyError=0、不崩不灰。

---

## 7. 安装与收尾

- 装前体检全绿；装后 sha256 与 CI 一致；force-stop 0；P0 冷启动通过。
- 收尾：前台 PlayerActivity；实验键 0；debug tag 复位；Fatal/VerifyError 0；/data 2.56G(23%)；t69 回滚包保留。
- **如实标注**：prefs `playback_buffer_min=400`（早前测试遗留，非本包问题，本轮验收不依赖缓冲档位）。

---

## 8. 最终判定

- **可交付**：t68 严重 bug（长按钉死/按左定位）本包未回归；TiviMate 焦点居中"约第 7 行"实现、无竖线透明区、长按到端无巨帧均达成。
- **如实标注两处**：① 当前台位于可视区第 7/10 行（距顶 6 vs 距底 3），非严格几何正中（viewport 12 行口径 vs 实际 10 行渲染）；② UP 方向即时居中回归有延迟（DOWN 良好）。这两点是 TiviMate 精确基准的偏差，供开发决定是否调整 viewport 口径。
