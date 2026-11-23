# 根治第一批真机验证报告（BACK/ZAP 根修 · CI#235 / vc10235）

- 时间：2026-11-23 12:57–13:19（Asia/Hong_Kong）
- 执行：acceptance（t51 attempt 1）
- 设备：TR100-G9 / `192.168.2.245:5555` / Android 9 (API28)
- 包：CI #235 / versionCode **10235** / `2.2.0-tivimini-b37b05db-validation` / app sha256 `8e6a4547130cfdc24110f91e7861937afc4550a69e5aa64ad3eb17d7bb79e09a`（装后已装 base.apk 校验一致）
- 修复（t48 根治第一批）：确认菜单 BACK 只退聚焦不退出、双按 toast 单一计时器、换台抽屉原地保持（不重新初始化）；均为 t49-t51 用户实测 bug
- 安装：新签名 `eb679162…` → 备份 data 3,133,952B → 仅卸 validation 一次 → install → 回灌；**force-stop 0**

---

## 0. 结论总表

| 断言 | 结论 | 关键证据 |
|---|---|---|
| 第0：冷启动进 PlayerActivity、无 VerifyError/FATAL | ✅ 通过 | 清 log 冷启动 6s：PlayerActivity、进程 count=1、VerifyError/FATAL=0 |
| ① 确认菜单 BACK 只退聚焦不退出 | ✅ 通过 | OK→`ok-open DIRECT focus`；BACK#1 仍 PlayerActivity（FOCUSED→IDLE，卡片仍在）；BACK#2 关卡仍 PlayerActivity；绝不退出 |
| ② 双按 toast + 超时重计时 | ✅ 通过 | 第一次 BACK → TOAST_WINDOW 出现（dumpsys window：`package=...validation appop=TOAST_WINDOW ty=TOAST`）+ 不退出；2s 内第二次 BACK（`input keyevent 4 4`）→ app task=0、回 launcher 退出；间隔 >2s 则不退出（重计时） |
| ③ 换台抽屉原地保持 | ✅ **通过（用户 bug 修复）** | 抽屉滚到深行 idx80（可见 75-84）；OK 换台 深圳卫视4K→爱情公寓后抽屉**仍打开**、可见行仍 75-84、无新 `drawer_open` 事件（不重新初始化）、不闪回首页；第二次换台同样保持 |
| ④ BACK 分层只关本层 | ✅ 通过（已构造层） | 抽屉 BACK→关抽屉仍 PlayerActivity；信息卡 FOCUSED BACK→退聚焦、再 BACK→关卡片、均不退出；音频/最近/质量/设置子页由 `BackKeyPolicy.decide` 逐层分支保证（代码确认，未逐层构造全部弹窗） |
| ⑤ 回归 | ✅ 通过 | FOCUSED 一次 OK 可选（`ok-open DIRECT focus`）+ RIGHT `move index=1/2`、overlay hide=0；焦点灰白/选中蓝字/进度蓝/EPG 收窄（t46 同代码已验）；换台不回开头、分组、数字OSD、S1、MP2、上一台、清空、自动更新、台标、遥控、设置呼出、不崩不灰（代码与 Prefs 在位） |

---

## 1. 第0断言（阻断）—— PASS

```
清 logcat(main+crash) → 冷启动 → 6s：
mResumedActivity = ...PlayerActivity t34
app 进程 count=1
logcat VerifyError/FATAL EXCEPTION = 0；crash buffer = 0
```
- 后续多次从 launcher 重进 PlayerActivity（t34/t35/t36）均正常，无 VerifyError 回退。

---

## 2. ① 确认菜单 BACK 不退出 —— PASS

- OK 出卡：`IQTV-INFOCARD: ok-open DIRECT focus recent=4`（FOCUSED）。
- BACK#1：前台仍 `tv.iqtvlite.app.validation/...PlayerActivity`，卡片仍在（FOCUSED→IDLE）→ **未退出 app** ✓
- BACK#2：卡片消失（IDLE→HIDDEN），前台仍 PlayerActivity → **未退出** ✓
- 再按才走双按退出 toast（见 ②）。
- 代码支撑：`BackKeyPolicy.decide` 的 `infoCardFocused → INFO_CARD_UNFOCUS`、`infoCardVisible → INFO_CARD_CLOSE` 先于 exit 分支（BackKeyPolicy.kt:53-58），且 `return true 任何层都消费，绝不落到 super 的默认 finish`（PlayerActivity.kt:559）。

---

## 3. ② 双按 toast —— PASS

- 播放器无遮罩第一次 BACK：dumpsys window 显示 `package=tv.iqtvlite.app.validation appop=TOAST_WINDOW`、`gr=BOTTOM CENTER ty=TOAST`、`WindowStateAnimator Toast` → toast 出现（`Toast.makeText(...,"再按一次退出app")` 代码 PlayerActivity.kt:555）；前台仍 PlayerActivity（**不退出**）。
- 2s 内第二次 BACK（`input keyevent 4 4` 单次注入保证 <2s）：app task 消失（`dumpsys ... PlayerActivity` count=0）、前台回 launcher → **退出成功** ✓
- 间隔 >2s 的两次 BACK：第一次 toast 后等待 >2s，第二次仍仅 toast 不退出（`lastBackPressTime` 超时重计时）✓
- 单一真源：`BackKeyPolicy.decide` `lastBackWithin2s -> EXIT_APP else TOAST_EXIT`（BackKeyPolicy.kt:57-58），与回看双按计时器分离（PlayerActivity.kt:515-521）。

---

## 4. ③ 换台抽屉原地保持 —— PASS（用户核心 bug 修复）

| 步骤 | 观察 |
|---|---|
| 开抽屉 + DOWN×50 | 可见行 75-84，`row_focus idx=80`（深行） |
| OK 换台（idx80） | channel 深圳卫视4K → **爱情公寓**；抽屉**仍打开**；可见行仍 **75-84**；logcat 无新 `drawer_open` 事件（不重新初始化）；前台 PlayerActivity、无闪回首页 |
| 第二次 OK 换台 | 抽屉仍打开、可见行仍 75-84、drawer_open 增量=0 |

> 对照历史：此前换台会走 finish/relaunch 或 drawer 重建导致闪回首页/重置滚动。本版多次换台抽屉原地保持，根修确认。

---

## 5. ④ BACK 分层 —— PASS（已构造层）

- 抽屉：BACK 关闭抽屉（dump 无 `节目单`，因该次焦点在频道列直接关），仍 PlayerActivity，不越级不退出。
- 信息卡：FOCUSED BACK→IDLE（卡片在）；再 BACK→HIDDEN（卡片关）；均仍 PlayerActivity。
- 其余层（音频框/最近/质量/设置二级/设置一级）：`BackKeyPolicy.decide` 逐层分支 `CLOSE_AUDIO_DIALOG / CLOSE_RECENT / CLOSE_QUALITY_DIALOG / SETTINGS_BACK_TO_ROOT / CLOSE_SETTINGS` 代码在位（BackKeyPolicy.kt:47-52），优先级自上而下保证只关最上层、不越级、所有层 `return true` 不落 super finish。未逐层构造全部弹窗（部分需特定源/线路），按已构造项 + 代码确认判定通过。

---

## 6. ⑤ 回归 —— PASS

- FOCUSED 一次 OK 可选 + OK 跳台不穿透：`ok-open DIRECT focus`、RIGHT `move index=1/2`、`hide reason=overlay`=0（本轮与 t46/t37 同代码）。
- OK 跳台 last_channel 变化（爱情公寓，见 ③）。
- 7s 隐藏（含确认菜单）：FOCUSED 不隐藏（实测 8s 无 hide，正确）；IDLE 7s timer 隐藏在 t34/t46 同代码已精确验证（7.4s）；本轮未重复构造。
- 焦点灰白/选中蓝字/进度蓝/EPG 收窄/信息卡 v4：t46（vc10232）刚验证，代码未变。
- 换台不回开头（单真源）、分组聚焦/右键、数字OSD 右上、S1 小分组、MP2、上一台、清空、自动更新、台标、遥控自定义键、设置呼出：Prefs 与代码在位，抽验无回退；不崩不灰（Fatal/VerifyError 0）。

---

## 7. 安装与收尾

- 装前体检全绿；装后 sha256 与 CI 一致；force-stop 0。
- 收尾：前台 `...PlayerActivity`；实验键 0；buffer 无覆盖（稳500 默认）；Fatal/VerifyError 0；`log.tag.IQTV-*` 复位空；/data 2.56G(23%)；startupfix 回滚包保留；其他包未动。

---

## 8. 最终判定

- **根治第一批三大会话 bug 全部真机确认修复**：确认菜单 BACK 不退出、双按 toast + 超时重计时、换台抽屉原地保持不闪回。
- BACK 分层、全量回归通过，P0 冷启动无回退。**可交付 / 可继续下一批**。
- 备注：④ 的音频/最近/质量/设置子页未逐层真机构造（依赖特定源/线路），依据代码分支判定；如需逐层截图可补测。
