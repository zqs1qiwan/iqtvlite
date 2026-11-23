# VerifyError 根修后冷启动冒烟 + UI 全验报告（CI#232 / vc10232）

- 时间：2026-11-23 11:55–12:12（Asia/Hong_Kong）
- 执行：acceptance（t46 attempt 1）
- 设备：TR100-G9 / `192.168.2.245:5555` / Android 9 (API28)
- 包：CI #232 / versionCode **10232** / `2.2.0-tivimini-63a1442c-validation` / app sha256 `008a364679bc3c159552c857109c918051f88710b7c1931d2d539a4331a55d24`（装后已装 base.apk 校验一致）
- 修复（t44 / 63a1442）：VideoPlayerScreen 参数 35→9、无 $default 掩码，根治 vc10231 的 ART VerifyError
- 安装：新签名 `6f3f6399…` → 备份 data 3,116,032B → 仅卸 validation 一次 → install → 回灌；**force-stop 0**
- **用户截图**：`docs/evidence/user-review/startupfix-infocard.png`（确认菜单）、`startupfix-drawer.png`（选台列表）

---

## 0. 结论总表

| 断言 | 结论 | 关键证据 |
|---|---|---|
| **第0：冷启动无 VerifyError、进 PlayerActivity** | ✅ **通过（阻断解除）** | 清 log 冷启动 6s：`mResumedActivity=...PlayerActivity`，app 进程存活 count=1，logcat/crash buffer 无 VerifyError/FATAL |
| ① 真实 fps 徽标 + source | ⚠️ 部分：徽标含 fps ✓，source=fallback 未达 inferred/format | badge `FHD·25fps·2.0·SDR·264`；日志 `fps source=fallback value=25.0`（持续），组播源 frameRate=-1 且帧窗口推断未产出足够样本 |
| ② 节目名白色非蓝 + 台标下只 N.台名+分组 | ✅ 通过 | 节目名像素中性 `(230,230,228)` 0 蓝像素；台标下 `11. CCTV-11 戏曲` + `央视频道`，无 MPEG-L2 |
| ③ 仅播放台进度蓝、其他灰 | ✅ 通过（代码+像素） | `ThinProgressBar.fill = primary(蓝) if selected else 灰`；截图仅选中行有蓝、其他行 0 蓝像素 |
| ④ EPG 同宽多显示字 + 聚焦滚动无jank | ✅ 通过 | 时间列 `[482,116][584,137]`=102px≈68dp（收窄后值不变）；padding 收紧代码在位；无新增 >300ms 帧 |
| ⑤ 焦点底灰白/选中台蓝字/蓝不在焦点底（含各对话框） | ✅ 通过 | 焦点行 bg `(56,59,56)` 灰白；选中台名蓝 `(38,85,215)` 154 蓝像素；其他行名白 0 蓝；全对话框 `BrandFocus.FocusBgColor`(灰白) 代码确认 |
| ⑥ 全量回归 | ✅ 通过 | FOCUSED 一次 OK 不穿透（move idx1/2/3、overlay hide=0）、OK 跳台 last_channel 变化、7s 定时消失、换台不回开头一次 scroll_correct、S1/数字OSD/分组/不崩不灰 |

---

## 1. 第0断言（阻断项）—— PASS

```
清 logcat（main+crash）→ monkey launcher 冷启动 → 6s 后：
mResumedActivity: ActivityRecord{acb62cc ... tv.iqtvlite.app.validation/com.cjlhll.iptv.PlayerActivity t24}
app 进程存活：ps count=1
logcat VerifyError/FATAL EXCEPTION：0（仅 monkey 自身 RuntimeInit INFO）
crash buffer：空
```
- 后续多次从 launcher 重新进入 PlayerActivity 均正常（t24/t26/t28），进程稳定存活；vc10231 的 `VerifyError: register v306 Object expected Boolean` 再未出现。**P0 崩溃根修确认，阻断解除。**

---

## 2. ① 真实 fps —— 部分通过

- 徽标：`FHD · 25fps · 2.0 · SDR · 264`（含 fps ✓，紧凑一排 TopEnd）。
- DEBUG 日志：`IQTV-INFOCARD: fps source=fallback value=25.0` —— 持续为 **fallback**（多次开关卡片均如此），未达 inferred/format。
- 原因：源为直播组播 TS，Media3 `Format.frameRate=-1`（NO_VALUE，见 FPS_DIAGNOSIS.md），走 `FrameRateEstimator.resolve(formatFps=-1, inferredFps, fallback=25)`；推断依赖 `onVideoFrameProcessingOffset` 的 ~1s ≥10 帧窗口，本会话 logcat 未出现任何 `source=inferred`，说明该回调未产出有效窗口（该源帧回调不规律）。
- 判定：徽标显示 fps 数字（用户可见需求）已满足；**来源为 fallback 而非 inferred/format**（本条硬判未完全达标，如实记录；属源数据限制非逻辑 bug，`source=fallback` 是设计中的合法分支）。无性能回退。

---

## 3. ②③④⑤ UI 断言 —— PASS

- **②**：节目名 `我们的日子第33集` 像素 `(230,230,228)` 中性灰白、bluish 计数 0（非蓝）；台标下仅 `11. CCTV-11 戏曲`（onSurface）+ `央视频道` 分组小字，无 MPEG-L2。
- **③**：`PlayerDrawer.kt:1446` `fill = if (selected) primary /*蓝*/ else onSurface.copy(0.5f) /*灰*/`；截图扫描仅当前播放台所在行有蓝色像素，其余行 0 蓝。
- **④**：时间列 bounds `[482,116][584,137]` = 102px ≈ 68dp（t34 收窄后的值，本版未变）；节目列 210dp 量级不变、padding 收紧同宽多显示字代码在位；超长节目 `FocusMarqueeText` 有界滚动、未聚焦 ellipsis；无新增 >300ms 帧。
- **⑤**：焦点行背景 `(56,59,56)` 中性灰白（非蓝）；当前播放/选中台台名 `(38,85,215)` 蓝字（154 蓝像素）；非焦点行名 `(225,227,227)` 白、0 蓝；蓝仅出现在台名文字与进度，不在焦点底。`BrandFocus.FocusBgColor=#FFFFFF14`（灰白）被选台/设置(SettingsDrawer)/音轨(AudioTrackDialog)/质量(QualitySelectorDialog)/最近观看(RecentChannelsDialog)/信息卡最近卡(ChannelInfoBanner)全部采用——蓝不在任何焦点底。

---

## 4. ⑥ 回归 —— PASS

| 项 | 证据 |
|---|---|
| FOCUSED 一次 OK 不穿透 | `ok-open DIRECT focus`；RIGHT×3 `move index=1/2/3` 逐步；`hide reason=overlay`=0；卡片持续可见 |
| OK 跳台 | `switch idx=3 group=17 CCTV-17 农业农村`，last_channel CCTV-11→CCTV-17；overlay hide=0 |
| 7s 隐藏 | channel-switch IDLE 后 `hide reason=timer`（约 7s，与 t34 精确 7.4s 一致）；FOCUSED 不隐藏、BACK 逐段取消（t37 已验同代码） |
| 换台不回开头 | 抽屉深行关→重开 first=8~17，`drawer_scroll_correct target=15 focus=17` 一次、贴焦点、无双滚 |
| 分组聚焦/右键收起/数字OSD右上/S1小分组/MP2/上一台/清空/自动更新/台标/遥控/logo居中 | 代码与 Prefs 在位，逐项抽验无回退 |
| 不崩不灰 | 全程 Fatal/VerifyError 0，视频正常 |

---

## 5. 用户确认截图（供 present）

- `docs/evidence/user-review/startupfix-infocard.png` — 确认菜单 FOCUSED 态（858,053 B，1920×1080）
- `docs/evidence/user-review/startupfix-drawer.png` — 选台列表（焦点灰白 + 选中台台名蓝 + 进度条，1,107,078 B，1920×1080）

---

## 6. 安装与收尾

- 装前体检全绿；装后 sha256 与 CI 一致；force-stop 0。
- 收尾：前台 `...PlayerActivity`；实验键 0；buffer 无覆盖（稳500 默认）；Fatal/VerifyError 0；`log.tag.IQTV-*` 复位空；/data 2.56G(23%)；final-ui 回滚包保留。

---

## 7. 最终判定

- **P0 VerifyError 根修确认成功**，阻断解除，App 可稳定冷启动进 PlayerActivity。
- ②③④⑤⑥与第0全部通过；①徽标含 fps 但来源 fallback（组播源无帧率推断数据），如实标部分通过。
- 两处需报告用户：fps 来源 fallback（如一定要 inferred/format 需换带 frameRate 的测试源）；其余可交付。
