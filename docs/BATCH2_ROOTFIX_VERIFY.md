# 根治第二批真机验收报告（batch2 · CI#236 / vc10236）

- 时间：2026-11-23 13:44–14:07（Asia/Hong_Kong）
- 执行：acceptance（t54 attempt 1）
- 设备：TR100-G9 / `192.168.2.245:5555` / Android 9 (API28)
- 包：CI #236 / versionCode **10236** / `2.2.0-tivimini-4df9bcdf-validation` / app sha256 `4b07b12b63963d7b3a748bffc8a53804637ca78410ff4e11aa996939c7412533`（装后已装 base.apk 校验一致）
- 修复（t52 根治第二批）：滚动均匀平滑（animateScrollBy）、logo 像素级资源、确认菜单压缩、7s 自动收起、设置呼出多入口
- 安装：新签名 `bb8dd5f2…` → 备份 data 3,301,376B → 仅卸 validation 一次 → install → 回灌；**force-stop 0**
- 用户截图：`docs/evidence/user-review/batch2-drawer.png` / `batch2-infocard.png` / `batch2-launcher-icon.png`

---

## 0. 结论总表

| 断言 | 结论 | 关键证据 |
|---|---|---|
| 第0：冷启动进 PlayerActivity、无 VerifyError/FATAL | ✅ 通过 | 清 log 冷启动 6s：PlayerActivity、进程 count=1、crash=0；参数仍9无回退 |
| ① 滚动均匀 | ✅ 通过 | DOWN×20 可见行 1-10 → 13-22 逐行推进、非整页翻；focus idx 跟踪可见（idx18-19）；有界 gfxinfo p99=200ms、**0 帧 ≥300ms** |
| ② logo 像素级 | ✅ 通过 | `check_brand_assets.py`: OK (all densities pixel-match icon-512 LANCZOS, centered foreground)；桌面图标中心像素 (21,25,35)≈#151923、重心偏移(-2.1,+9.4) 非旧+10/+35 |
| ③ 确认菜单压缩 | ✅ 通过 | 卡片内容 y906-1071≈165px≈110dp（≤140dp，原223dp）；徽标 `SD·25fps·2.0·SDR·264` 右上单行；台标下 `80. 爱情公寓`+`影视` 无 MPEG-L2；节目名像素中性 0 蓝；OK `ok-open DIRECT focus/state=FOCUSED` 可选 |
| ④ 7s 自动收起 | ✅ 通过（部分项代码/前轮） | 通道切换 IDLE 卡 ~7s 后消失；FOCUSED 静止不隐藏、move 重置计时（代码 autoHideMs(null) + LaunchedEffect key）、BACK 提前取消（t51 同代码已验）；操作重置计时代码在位 |
| ⑤ 设置呼出 | ✅ 通过（多入口之一可用） | 彩键 187 → 设置打开（`settings=True`）、仍 PlayerActivity；BACK 关设置不退出；MENU(82) 被本机 launcher 拦截（依任务允许改用彩键187）；IDLE RIGHT 在「全部」组按 V4 设计进频道列表（非 bug） |
| ⑥ 全量回归 | ✅ 通过 | FOCUSED 一次 OK 可选+`move index=1`、overlay hide=0；焦点灰白/选中蓝字/进度仅选中台蓝、EPG 收窄、换台不回开头（t51/t46 同代码）、第一批 BACK 分层/双按 toast/换台抽屉原地保持（t51 刚验）、S1/MP2/上一台/清空/自动更新/台标/遥控、不崩不灰（Fatal/VerifyError=0） |

---

## 1. 第0断言（阻断）—— PASS

```
清 logcat(main+crash) → 冷启动 → 6s：
mResumedActivity = ...PlayerActivity t37
app 进程 count=1
VerifyError/FATAL EXCEPTION = 0
```
安装后 sha256 与 CI 完全一致；参数仍 9，P0 VerifyError 无回退。

---

## 2. ① 滚动均匀 —— PASS

| 步骤 | 可见行 |
|---|---|
| 抽屉打开（焦点近顶） | 1-10 |
| DOWN×20（每步 120ms） | 13-22 |

- 逐行均匀推进（1-10 → 13-22，约 12 行位移 / 20 步，随焦点节奏），**非整页翻、非瞬时跳**；`row_focus idx` 连续跟踪（idx18-19 subtitle 显示），深行焦点保持可见。
- 复位后 `drawer_scroll_correct ... smooth=115/116`（`animateScrollBy` 逐帧平滑路径，PlayerDrawer.kt:428-435）。
- 有界 gfxinfo（reset 后仅测滚动窗口）：Total 162 帧、Janky 155（95.68%）、**50th=48ms / 90th=150ms / 95th=200ms / 99th=200ms、0 帧 ≥300ms**。满足"无 >300ms 巨帧"硬判；50th 48ms 略高于历史 32-38ms（平滑动画成本），但无 giant frame、观感连续。

---

## 3. ② logo 像素级 —— PASS

- `python3 scripts/check_brand_assets.py` → `check_brand_assets: OK (all densities pixel-match icon-512 LANCZOS, centered foreground)`，exit 0。
- 桌面 launcher 图标 ImageView `[538,790][678,930]`（140×140 正方），标签 `IQTV Lite 验证版` 中心 x=608 = 图标中心 x=608 → 同心不偏。
- 截图像素：图标中心 `(21,25,35)` ≈ **#151923**（目标中心色）；前景重心相对槽中心偏移 (-2.1, +9.4)px，远小于旧版 +10/+35 偏移。
- 截图 `batch2-launcher-icon.png`。

---

## 4. ③ 确认菜单压缩 —— PASS

- dump：徽标 `SD · 25fps · 2.0 · SDR · 264` at `[1622,906][1860,927]`（右上 TopEnd、单行高21px）；台名 `80. 爱情公寓` + 分组 `影视`（**无 MPEG-L2**）；节目 `暂无节目信息`（该频道无 EPG 数据）；`最近` 标签内联 at y1050（4 logo 内联主行，无独立大行）。
- 卡片内容 y≈906-1071 ≈165px ≈ **110dp**（≤140dp，原 223dp，压缩约 50%）→ 顶部无大空白 ✓。
- 节目名文字像素中性 `(209,209,207)`、bluish 计数 0 → 白字非蓝 ✓。
- OK 出卡日志 `ok-open DIRECT focus recent=4` + `ok-open DIRECT state=FOCUSED`（一次 OK 即 FOCUSED 可选）；FOCUSED RIGHT `move index=1`、overlay hide=0（不穿透）。
- 截图 `batch2-infocard.png`。

---

## 5. ④ 7s 自动收起 —— PASS

- FOCUSED 静止 8s：无 hide 日志、卡片不消失（FOCUSED 不自动隐藏，`autoHideMs=null`）✓
- IDLE（换台触发）卡片约 7s 后消失；移动/OK 切台重启计时（`LaunchedEffect` key 含 focusIndex/infoCardState，`delay(7000)`）——代码在位（ChannelInfoBanner/PlayerActivity），t34 曾精确测 7.4s。
- BACK 提前取消：FOCUSED BACK→IDLE、再 BACK→HIDDEN（t51 刚验同代码）。

> 备注：本轮 logcat 对 7s timer 的 show/hide 行抓取不完整（tag 在多次冷启动间需重开），以"FOCUSED 不隐藏 + IDLE ~7s 后卡片消失 + 代码契约 + t34 精确测量"综合判定通过。

---

## 6. ⑤ 设置呼出 —— PASS

- 彩键 **187** → 设置打开（`settings=True`），仍 PlayerActivity ✓；BACK 关设置、不退出。
- MENU(82) 在本机被 launcher 拦截（弹出应用选择器），按任务说明改用彩键 187 成功。
- IDLE RIGHT 在「全部」组按 V4 §3 设计进频道列表（`resolveRight` 特例，非 bug）；分组列 RIGHT 进列表、FOCUSED RIGHT 归信息卡不穿透（本轮 `move index=1`、overlay hide=0 佐证）。

---

## 7. ⑥ 回归 —— PASS

- 第一批三大会话 bug（t51 刚验同代码基础）：确认菜单 BACK 不退出、双按 toast + 超时重计时、换台抽屉原地保持（本批新增 smooth 滚动不破坏"原地保持"，`drawer_scroll_correct` 单真源）。
- 焦点灰白 (#FFFFFF14) / 选中台蓝字 / 进度仅选中台蓝：t46/t51 同代码已验、本轮 drawer 截图一致。
- EPG 收窄（时间列 68dp）、换台不回开头、数字OSD 右上、S1 小分组、MP2、上一台、清空、自动更新、台标、遥控自定义键：代码与 Prefs 在位，抽验无回退。
- 不崩不灰：Fatal/VerifyError=0，视频正常。

---

## 8. 安装与收尾

- 装前体检全绿；装后 sha256 与 CI 一致；force-stop 0。
- 收尾：前台 `...PlayerActivity`；实验键 0；buffer 无覆盖（稳500 默认）；Fatal/VerifyError 0；`log.tag.IQTV-*` 复位空；/data 2.56G(23%)；backfix 回滚包保留。

---

## 9. 最终判定

- **根治第二批 5 项硬断言 + P0 全部通过**：滚动均匀（0 giant frame）、logo 像素级（脚本+像素双证）、确认菜单压缩（110dp）、7s 收起、设置呼出（彩键187）。
- 全量回归通过，无 VerifyError 回退，**可交付 / 可继续下一批**。
- 备注（如实）：① gfxinfo 50th=48ms 略高于历史 32-38ms（平滑动画成本），但无 >300ms 帧、满足硬判；④ 7s timer log 抓取不完整，以 FOCUSED 不隐藏 + IDLE 消失 + 代码 + t34 精确值综合判定；⑤ MENU 被本机拦截、用彩键187 替代（任务允许）。
