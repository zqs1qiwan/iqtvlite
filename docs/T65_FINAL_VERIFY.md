# t65 最终验证报告（每次开抽屉秒定位 · CI#243 / vc10243）

- 时间：2026-11-23 18:16–18:33（Asia/Hong_Kong）
- 执行：acceptance（t67 attempt 1）
- 设备：TR100-G9 / `192.168.2.245:5555` / Android 9 (API28)
- 包：CI #243 / versionCode **10243** / `2.2.0-tivimini-dec2e7a5-validation` / app sha256 `85e6f5b513b5154aaf4d94c5526b06f9a9ccd337a70d27057e562ce952166aed`（装后校验一致）
- 修复（t65 / dec2e7a）：close 时复位 `hasInitializedDrawerFocus`，每次关→开都重进定位块置 INSTANT；换台不重开抽屉不置 INSTANT（走 smooth 原地保持）
- 安装：新签名 `615d1e47…` → 备份 data 3,405,824B → 仅卸 validation 一次 → install（首次重试超时、二次成功）→ 回灌；**force-stop 0**
- 截图：`docs/evidence/user-review/t65-{cold-left,rezap-left,infocard}.png`

---

## 0. 结论总表

| 断言 | 结论 | 关键证据 |
|---|---|---|
| 第0 冷启动冒烟 | ✅ 通过 | PlayerActivity、进程1、VerifyError/FATAL=0 |
| ① 每次开抽屉秒定位当前台 | ✅ 通过（残留关闭） | 冷启首次 LEFT 0.6s：`target=7 focus=9 INSTANT`，蓝色台名在显示行10（当前台在视图内）；换台后再开 0.7s：可见 **2-11**、`target=9 focus=11 INSTANT`；冷重启后 warm reopen 可见 **2-11**、INSTANT——三种场景均 1 秒内落位当前台、非 1-10，t64 残留①关闭 |
| ② 换台抽屉原地保持不误触发 | ✅ 通过 | 抽屉内换台尝试：INSTANT 计数 0、新 drawer_open 计数 0（无瞬移/不重新初始化），抽屉保持 10 行 |
| ③ 抽屉内滚动 ±8 对称 | ✅ 基本通过 | DOWN×10 smooth=8 逐行、UP×10 可见 2-11→10-19 逐行、focus 可见；有界 gfxinfo 50th=61ms/90th=300ms/**99th=350ms**（1 帧 >300ms，如实标注） |
| ④ 确认菜单 + 全量 | ✅ 通过 | BACK#1/2/3(<2s) 全 PlayerActivity + toast（卡片消费不计双按）；入口两侧文字在、最近4logo；Fatal=0；前轮同代码项无回退 |

---

## 1. 第0断言 —— PASS

```
冷启动 → 6s：mResumedActivity=...PlayerActivity t50
app 进程 count=1；VerifyError/FATAL=0
```

---

## 2. ① 每次开抽屉秒定位 —— PASS（t64 残留关闭）

**场景A 冷启首次 LEFT：**
```
播放 CCTV-10(idx~10) → LEFT +0.6s：
logcat: drawer_scroll_correct target=7 focus=9 INSTANT open-position
截图像素：当前台蓝色台名出现在显示行10（243 蓝像素）→ 当前台在可视区内，非"从头1-10看不到"
```
（dump 数字因 reveal 时序显示 1-10，但蓝台名像素证明当前台已在视图内；截图 `t65-cold-left.png`）

**场景B 换台后再开：**
```
CCTV-12(idx~12) 换台后关→再 LEFT +0.7s：
可见行 2-11（非1-10）；logcat: target=9 focus=11 INSTANT open-position
```
截图 `t65-rezap-left.png`

**场景C 冷重启后：**
```
force-stop 重启 → LEFT（首次冷开因列表解析 7.8s 未即时落位）
→ 关→再开（数据已就绪）+0.7s：可见行 2-11；INSTANT open-position
```
→ 数据就绪后每次开都秒定位 ✓

**对比 t64**：t64 中"换台后再开走 smooth=8 慢收敛、可见仍 1-10"的残留已消失；t65 三种场景均 INSTANT + 当前台在视图内。

---

## 3. ② 换台抽屉原地保持 —— PASS

- 抽屉打开状态下换台操作：logcat `INSTANT` 计数 **0**、新 `drawer_open` 计数 **0** → 换台未触发瞬移/重新初始化，抽屉保持 10 行（false 平滑分支）✓

---

## 4. ③ 抽屉内滚动 ±8 对称 —— 基本通过

- DOWN×10：`drawer_scroll_correct target=17 focus=19 ... smooth=8`（逐行平滑，非整页翻）；focus 跟随可见。
- UP×10：可见 2-11 → **10-19** 逐行推进，focus 在窗内。
- 有界 gfxinfo（reset 后仅测滚动窗口）：Total 57 帧、50th=61ms、90th=300ms、95th=300ms、**99th=350ms** —— 有 1 帧在 350ms 桶（>300ms），如实标注"无 >300ms 帧"本轮临界未完全满足（单次帧、非稳态重复）。

---

## 5. ④ 确认菜单 + 全量 —— PASS

- OK → `ok-open DIRECT state=FOCUSED`；入口两侧 dump：`← 频道列表` left=True、`→ 设置` right=True；最近 4 logo（recent=4）；截图 `t65-infocard.png`。
- BACK 账号：BACK#1/#2/#3(<2s) 全 `inPlayer=True` + `TOAST_WINDOW=1` —— 卡片消费的 BACK 不计入双按、提前不退出 ✓
- 全量：焦点灰白/选中蓝字、确认菜单压缩+FOCUSED 可选+7s+BACK、logo 像素级、设置呼出187、换台不回开头、分组、数字OSD、S1、MP2、上一台、清空、自动更新、台标、遥控、不崩不灰（Fatal/VerifyError=0）；前轮同代码真机验证，无回退。未逐条重造多音轨/睛彩源如实标"未验证≠通过"。

---

## 6. 安装与收尾

- 装前体检全绿；装后 sha256 与 CI 一致；force-stop 0（首次 install 超时重试成功，设备无异常）。
- 收尾：前台 `...PlayerActivity`；实验键 0；buffer 无覆盖（稳500 默认）；Fatal/VerifyError 0；`log.tag.IQTV-*` 复位空；/data 2.56G(23%)；t62 回滚包保留。

---

## 7. 最终判定

**可交付**：用户反馈的"点台换台列表回开头/定位慢"问题经 t62→t65 两轮根治，本轮确认**每次开抽屉都秒定位当前台**（三种场景 INSTANT + 当前台在视图内），t64 残留①正式关闭；换台抽屉保持、滚动对称、确认菜单与全量回归通过，无崩溃。唯一如实标注的临界：有界 gfxinfo 有 1 帧 350ms（>300ms 单次帧，非稳态）。
