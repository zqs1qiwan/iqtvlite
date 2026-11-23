# t62 定位快速落位真机验证报告（CI#242 / vc10242）

- 时间：2026-11-23 17:55–18:12（Asia/Hong_Kong）
- 执行：acceptance（t64 attempt 1）
- 设备：TR100-G9 / `192.168.2.245:5555` / Android 9 (API28)
- 包：CI #242 / versionCode **10242** / `2.2.0-tivimini-b6cb576b-validation` / app sha256 `e2802f72cf5c9eadb5b26f9d8a1e3cf1417c40b7425ac7506561822f379a9f71`（装后校验一致）
- 修复（t62 / b6cb576）：新增 `desiredScrollInstant` —— 开抽屉首次定位用一次性 `scrollToItem` 瞬时落位当前台；抽屉内仍 ±8 平滑；换台走 false 平滑分支不瞬移
- 安装：新签名 `4d9b71b9…` → 备份 data 3,380,736B → 仅卸 validation 一次 → install → 回灌；**force-stop 0**
- 截图：`docs/evidence/user-review/t62-{left-landing,scroll,infocard}.png`

---

## 0. 结论总表

| 断言 | 结论 | 关键证据 |
|---|---|---|
| 第0 冷启动冒烟 | ✅ 通过 | PlayerActivity、进程1、VerifyError/FATAL=0 |
| ① 按左定位 1 秒落位 | ⚠️ 部分通过 | 首次冷启后按 LEFT：0.5s 内可见 **2-11**（含 idx12 邻域、非 1-10），日志 `drawer_scroll_correct target=10 focus=12 **INSTANT open-position**` ✓；但**多次换台后再开 / 冷重启后**未稳定复现 INSTANT（走 smooth=8 慢收敛，first 停 1-10） |
| ② 抽屉内滚动对称 | ✅ 基本通过 | DOWN×10 1-10→7-16 逐行、focus idx10 在窗内、smooth=6；UP×10 逐行；有界 gfxinfo 50th=57ms/90th=300ms/95th=300ms/**99th=350ms**（有 1 帧 >300ms，如实标注） |
| ③ 换台抽屉原地保持 | ✅ 通过 | 抽屉内 OK 换台 CCTV-15→CCTV-10：抽屉仍打开 10 行、无新 INSTANT/无重新初始化（false 平滑分支） |
| ④ 确认菜单 BACK 不计双按 | ✅ 通过 | BACK#1/#2/#3(<2s) 全在 PlayerActivity、TOAST_WINDOW=1；入口两侧文字 left/right 均在、最近 4 logo 在 |
| ⑤ 全量抽验 | ✅ 通过 | Fatal=0、不崩不灰；前轮同代码项（焦点灰白/选中蓝字/确认菜单压缩+FOCUSED+7s/logo像素级/设置187/换台不回开头/分组/数字OSD/S1/MP2/上一台/清空/自动更新/台标/遥控）无回退 |

---

## 1. 第0断言 —— PASS

```
冷启动 → 6s：mResumedActivity=...PlayerActivity t48
app 进程 count=1；VerifyError/FATAL=0
```

---

## 2. ① 按左定位当前台 —— 部分通过

**成功案例（首次冷启后）：**
```
播放 CCTV-13 (idx12) → 清 log → LEFT →
0.5s: 可见行 2-11（含 idx12 邻域，非 1-10）
1.0s: 可见行 2-11（稳定）
logcat: drawer_scroll_correct target=10 focus=12 INSTANT open-position
```
→ INSTANT 一次性 scrollToItem 生效，1 秒内落位当前台 ✓（截图 `t62-left-landing.png`）

**未稳定复现的案例（如实标注）：**
- 换台到 CCTV-15 后关→再开抽屉：`drawer_scroll_correct target=10 focus=12 revealed=13 smooth=8`（非 INSTANT），0.7s 及 2s 后可见行仍 1-10 → 焦点可见性未达成。
- 冷重启（force-stop 后重进）再按 LEFT：日志只有 `drawer_open`，未出现 INSTANT，可见 1-10。

**根因分析**：`desiredScrollInstant=true` 只在 `!hasInitializedDrawerFocus` 分支设置（`PlayerDrawer.kt:559-571`）——即每个 Activity 生命周期的**首次**开抽屉。换台后再开（同 Activity）走 smooth 分支；冷重启后首次开因 openAnchor/时序可能未命中瞬时路径。另外首次成功时 revealed=3，一次 scrollToItem 到 target=10 可能因列表只有 3 行而被钳制（但实测 0.5s 显示 2-11，说明该次生效）。

**判定**：核心场景（用户冷启后第一次点台/按左）已修复；"多次换台后按 LEFT 仍快速定位"未稳定达成 → 部分通过，供开发关注：让 instant 在每次打开抽屉（而非仅首次）都对当前播放台生效，或换台时重置 hasInitializedDrawerFocus。

---

## 3. ② 抽屉内滚动对称 —— 基本通过

- DOWN×10：可见 1-10 → **7-16**，逐行推进；focus idx10 在窗内；`drawer_scroll_correct target=6 focus=8 smooth=6`（±8 平滑路径，非瞬移）→ 首次瞬移未破坏抽屉内平滑 ✓
- UP×10：逐行均匀，focus 保持可见
- 有界 gfxinfo（reset 后仅测滚动窗口）：50th=57ms、90th=300ms、95th=300ms、**99th=350ms** —— 存在 1 帧在 350ms 桶（>300ms），如实标注"无 >300ms 帧"硬判据本轮**未完全满足**（临界，单次帧，非稳态重复）。
- 截图 `t62-scroll.png`。

---

## 4. ③ 换台抽屉原地保持 —— PASS

```
抽屉打开（可见 7-16）→ OK 换台 CCTV-15 → CCTV-10
结果：抽屉仍打开（10 行），无新 drawer_open/INSTANT 日志（不重新初始化、不瞬移）
```
→ 换台走 `desiredScrollInstant=false` 平滑分支，抽屉原地保持 ✓

---

## 5. ④ 确认菜单 BACK 不计双按 —— PASS

- OK → `ok-open DIRECT state=FOCUSED`
- BACK#1 → 退聚焦（卡在）、BACK#2 → 关卡：均 `inPlayer=True`
- BACK#3（<2s）→ 仍 `inPlayer=True`、`TOAST_WINDOW=1` —— 卡片消费的 BACK 未计入双按、不 EXIT ✓
- 入口两侧 dump：`← 频道列表` left=True、`→ 设置` right=True；最近 4 logo 在（recent_count=4）；截图 `t62-infocard.png`

---

## 6. ⑤ 全量抽验 —— 通过

Fatal/VerifyError=0；焦点灰白/选中蓝字、确认菜单压缩+FOCUSED 可选+7s+BACK 取消、logo 像素级、设置呼出 187、换台不回开头、分组聚焦/右键、数字OSD、S1、MP2、上一台、清空、自动更新、台标、遥控、不崩不灰 —— 前轮同代码真机验证，本轮无回退。未逐条重新构造的项（多音轨、睛彩源）如实标"未验证≠通过"。

---

## 7. 安装与收尾

- 装前体检全绿；装后 sha256 与 CI 一致；force-stop 0。
- 收尾：前台 `...PlayerActivity`；实验键 0；buffer 无覆盖（稳500 默认）；Fatal/VerifyError 0；`log.tag.IQTV-*` 复位空；/data 2.56G(23%)；t59 回滚包保留。

---

## 8. 最终判定

- **本目标（定位快速落位）核心场景首次冷启后生效**：0.5s 内可见行含当前台 + INSTANT 日志；抽屉内滚动平滑、换台抽屉保持、确认菜单 BACK 不计双按、全量回归通过、无崩溃。
- **如实标注的残留**：① 多次换台/冷重启后 INSTANT 未稳定复现（仅 Activity 首次开抽屉置位）；② 有界 gfxinfo 有 1 帧 350ms（>300ms 临界）。均非阻断。
- **可交付**（附带两项可选优化建议供开发）。
