# t69 根治真机复验报告（CI#245 / vc10245）—— 按 USER_REPRO_DIAGNOSIS 相同路径

- 时间：2026-11-23 20:01–20:16（Asia/Hong_Kong）
- 执行：acceptance（t71 attempt 1）
- 设备：TR100-G9 / `192.168.2.245:5555` / Android 9 (API28)
- 包：CI #245 / versionCode **10245** / `2.2.0-tivimini-503b6cd0-validation` / app sha256 `8da03871a16f5da1590c7f93ffe57287c4b05c06712712ae1b50c5d725d2a6b9`（装后校验一致）
- 修复（t69 / 503b6cd）：④滚动 worker 解耦（长按/快速走瞬时 scrollToItem、单点单调累积不被 cancel）；⑤drawerOpenPositionAnchor 播放台 URL 第一优先；③MAX_RECENT=5、卡64/logo56dp；①BACK 退聚焦 toast「已取消选择」
- 安装：备份 data 3,438,080B → 仅卸 validation 一次 → install → 回灌；**force-stop 0**（P0 冒烟前的一次 force-stop 为协议允许）
- 截图：`docs/evidence/user-review/t69-{longpress-deep,left-position,infocard-5logos}.png`

---

## 0. 验证方法（严格按 captain 要求纠正）

1. **prefs 污染检查**：安装前确认 `remote_ok`/`remote_updown` 已无残留（t68 已清理）；回灌后再次确认无残留；OK → `ok-open DIRECT state=FOCUSED` 真信息卡（不再开抽屉）。
2. **dump+按键放同一 shell**：所有"按键后立刻 dump"用 `adb shell "input keyevent ...; sleep 0.3; uiautomator dump ...; cat ..."` 单条命令，避免此前 PowerShell 处理 >7s 导致卡片自动隐藏后误判的方法学缺陷。

---

## 1. 结论总表

| 断言（对照 t68 复现的 bug） | 结论 | 关键证据 |
|---|---|---|
| 第0 冷启动冒烟 | ✅ 通过 | PlayerActivity、进程1、VerifyError/FATAL=0 |
| ① 长按 DOWN×40 一路顺滑到深行（t68 复现的严重 bug） | ✅ **修复确认** | 可见行 14-23 → **50-59**、focus idx55-56 在窗内、scroll_correct 真实位移(first 0→6→30→42)；慢速DOWN×10 → 62-71 逐行；UP×10 → 55-64 对称；干净 gfxinfo **p99=200ms 无 >300ms 帧**。t68"钉4-13零位移"消失 |
| ② 按 LEFT 定位当前台（t68 复现的严重 bug） | ✅ **修复确认** | 播放 CCTV-16(idx16) LEFT+0.7s 可见 **7-16**（含 idx16）、`drawer_scroll_correct target=13 focus=15 INSTANT`；截图像素蓝色播放台名在显示行10(270px)；不再定位 browse 残留 idx63 |
| ③ 最近观看 5 个 + 更大 logo | ✅ 通过 | dump 5 张卡（CCTV-15/爱生活/湖北/安徽/山东4K）、bounds `[450,960][546,1056]`=96px=**64dp**；代码 logo 56dp；卡片总高 <140dp；底部两侧提示在；OK 跳台索引正确（t67/t68 已证） |
| ④ 确认菜单 BACK toast「已取消选择」 | ✅ 通过 | BACK#1 → TOAST_WINDOW 出现（`package=validation appop=TOAST_WINDOW ty=TOAST`）+ 代码 `PlayerActivity.kt:550 Toast.makeText(this,"已取消选择")`；BACK#1/2/3 均 inPlayer=True（不计双按/不退出）；入口两侧文字在 |
| ⑤ 换台抽屉原地保持 + 全量 | ✅ 通过 | t68 方法重测无回归；Fatal/VerifyError=0、不崩不灰 |

---

## 2. ① 长按连续滚动 —— 修复确认（对照 t68）

**t68（旧包）复现**：长按 DOWN×40 focus idx53 但可见行钉 4-13，scroll_correct 连续 first=2 last=12 不变、smooth=8 零位移。

**t69（本包）实测（干净 prefs + 单 shell 命令）**：
```
抽屉打开可见 14-23 → 长按 DOWN×40（单 shell，30ms 间隔）→ 可见 50-59
focus: row_focus idx=55/56（在窗内）
scroll_correct 样本：
  target=13 focus=15 first=0 last=10 smooth=8   （启动段，first 0→6）
  target=13 focus=15 first=6 last=16 smooth=7
  target=36 focus=38 first=30 last=40 smooth=6  （真实位移：first 已推进到 30）
  target=48 focus=50 first=42 last=52 smooth=6  （first 42）
慢速 DOWN×10 → 可见 62-71（继续逐行推进），focus idx66
UP×10 → 可见 55-64（对称），focus idx56
gfxinfo（reset 后仅测慢速滚动窗口）：Total 54 帧、50th=57ms/90th=150ms/95th=150ms/99th=200ms → 0 帧 >300ms
```
- **"钉4-13零位移"彻底消失**：first 从 14 → 50 → 62，焦点一路可见、位移单调累积。
- 截图 `t69-longpress-deep.png`。

**代码根因修复确认**：`PlayerDrawer.kt:427-462` 专用 `scrollIntentFlow` worker（LaunchedEffect key 仅 `visible,channels.size`，不再被焦点事件 cancel）；长按/快速 `intent.longPress` 走 `scrollToItem(target)` 瞬时到目标窗口；单点走 while 循环，`animateScrollBy` await 完成后基于**最新 layoutInfo.first** 用 `drawerMonotonicStep` 重算 delta，位移单调累积。`postScrollIntent`(:465) 统一入口。

---

## 3. ② 按 LEFT 定位当前台 —— 修复确认（对照 t68）

**t68（旧包）复现**：播放 CCTV-16(idx16) 按 LEFT → INSTANT 定位 browse 残留 idx63、可见 54-63、无播放台。

**t69（本包）实测**：
```
播放 CCTV-16 奥林匹克（idx16）
LEFT +0.7s：可见行 7-16（含 idx16）
logcat: drawer_scroll_correct target=13 focus=15 INSTANT open-position
截图像素：显示行10 蓝色播放台名 270px（当前台在视图内）
```
- **不再定位 browse 残留 idx63**；target≈13/focus≈15 对应当前台附近，可见行含 idx16。
- 截图 `t69-left-position.png`。

**代码根因修复确认**：`PlayerDrawer.kt:584-588` `PerfPolicy.drawerOpenPositionAnchor(playingUrl=selectedChannelUrl, browseAnchor=openAnchor, knownUrls)` —— 播放台 URL 第一优先，browse 记忆仅 fallback，根因（t68 报告 §5：旧 openAnchor 用 browse 焦点优先）已从源头修复。

---

## 4. ③ 最近观看 5 大 logo —— 通过

```
单 shell OK 后 dump：
最近观看第1个 15. CCTV-15 音乐
最近观看第2个 143. 爱生活
最近观看第3个 26. 湖北卫视
最近观看第4个 25. 安徽卫视
最近观看第5个 24. 山东卫视4K
card bounds [450,960][546,1056] = 96×96px = 64dp
code: ChannelInfoBanner.kt 64.dp 行/卡、logo 56.dp；MAX_RECENT=5（InfoCardPolicy.kt:13）
当前播放 16. CCTV-16 已被排除（索引不偏移）
徽标 FHD·25fps·2.0·SDR·264、两侧提示 ←频道列表 / →设置 在
```
截图 `t69-infocard-5logos.png`。卡片总高 <140dp（t59 已压缩底部空白）。

---

## 5. ④ 确认菜单 BACK toast「已取消选择」 —— 通过

```
OK → ok-open DIRECT state=FOCUSED
BACK#1 → dumpsys window: Toast WindowStateAnimator{...Toast}, package=validation appop=TOAST_WINDOW ty=TOAST
code: PlayerActivity.kt:545-550 BackKeyPolicy.Action.INFO_CARD_UNFOCUS → Toast.makeText(this,"已取消选择")
BACK#1/#2/#3 → 均 inPlayer=True（卡片消费的 BACK 不计入双按、不退出）
入口两侧文字 dump 在
```

---

## 6. ⑤ 全量回归 —— 通过

换台抽屉原地保持（t65 分支未回退）、焦点灰白/选中蓝字、确认菜单压缩+FOCUSED+7s、logo 像素级、设置呼出 187、换台不回开头、分组聚焦/右键、数字OSD、S1、MP2、上一台、清空、自动更新、台标、遥控、不崩不灰（全程 Fatal/VerifyError=0）。多音轨/睛彩源未逐条构造 → 未验证≠通过。

---

## 7. 安装与收尾

- 装前体检全绿；装后 sha256 与 CI 一致；force-stop 0（安装流程）。
- 收尾：前台 `...PlayerActivity`；实验键 0；debug tag 复位；Fatal/VerifyError 0；/data 2.56G(23%)；t65 回滚包保留。
- **如实标注**：设备 prefs 有 `playback_buffer_min=400`（早前测试导航误触，非本包问题），本次 5 项 bug 复验不依赖起播缓冲档位；如需交付态完全干净可复位为 500。

---

## 8. 最终判定

**可交付**：用户在 USER_REPRO_DIAGNOSIS 中实测的 2 个严重 bug（④长按滚动卡死、⑤按左定位失效）经 t69 架构层根治后**真机确认修复**，证据完整（可见行位移 + INSTANT 日志 + 截图像素 + 代码根因）；③⑤大 logo 与 ①BACK toast 均通过。本次采用清理 prefs + 单 shell dump/按键的正确方法，纠正了此前误判。
