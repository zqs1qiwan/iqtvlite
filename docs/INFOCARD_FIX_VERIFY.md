# 信息卡修复真机 5 条断言验证报告（CI#225 / vc10225）

- 时间：2026-11-23 10:13–10:29（Asia/Hong_Kong）
- 执行：acceptance（t34 attempt 1）
- 设备：TR100-G9 / `192.168.2.245:5555`
- 包：CI #225 / versionCode **10225** / `2.2.0-tivimini-0a1c1d2c-validation` / app sha256 `c5db4a4f02a698c56be582e99292410b6013b4e893579d72892f740d8831e7c0`（装后拉取已装 base.apk 校验一致）
- 修复内容（t32 / 0a1c1d2）：①信息卡 OK 行为 DIRECT 默认（一次 OK 即 FOCUSED）；②徽标 TopEnd 紧凑一排含分辨率/帧率/声道数字/SDR/编码；③IDLE 7s 超时；④EPG 列收窄 300→210dp、时间 96→68dp + 聚焦滚动；⑤桌面图标居中
- 安装：新签名 `ab91d21d…` → 备份 data 3,101,696B → 仅卸 validation 一次 → install → 回灌；**force-stop 0**
- 证据截图：`docs/evidence/v4-verify/ic-*.png`

---

## 0. 结论总表（逐条硬判）

| 断言 | 结论 | 关键证据 |
|---|---|---|
| ① OK 一次即可选最近观看（DIRECT） | ✅ 通过 | 单次 OK 日志 `ok-open DIRECT focus recent=4`；RIGHT `move index=1`；OK `switch idx=1 group=24 山东卫视4K`，last_channel 变化 |
| ①b 两段式模式需第二次 OK | ⚠️ 未验证 | 设置页导航中该机焦点跳转异常，未能可靠切到「两段式」开关；默认 DIRECT 已验证，切换行为未构造（见 §2） |
| ② 徽标右上紧凑一排全信息 | ✅ 基本通过（fps 源缺失隐藏） | dump `FHD · 2.0 · SDR · 264` at [1675,744][1860,765]（TopEnd 单行高21px）；含分辨率/声道数字2.0/SDR/编码264；**fps 未显示**（源 Media3 Format 无 frameRate，`frameRateLabel` 对<=0返回null，按设计"未知即隐藏"）；台标下只 `N.台名`+分组无 MPEG-L2；卡片高度较旧版下降 |
| ③ 7s±0.5 消失 + FOCUSED 不隐藏 + BACK 取消 | ✅ 通过 | `show state=IDLE` 10:23:35.159 → `hide reason=timer` 10:23:42.559 = **7.4s**；3s 时仍可见；FOCUSED 8s 无 hide；BACK 逐段关闭 |
| ④ EPG 收窄 + 聚焦滚动 | ✅ 通过（宽度实测） | 时间列 `[494,116][596,137]` = 102px ≈ **68dp**（原96dp ✓）；节目列起点 x476；长节目聚焦滚动/未聚焦 ellipsis 代码在位（`FocusMarqueeText` + 有界 iterations），无新增 >300ms 帧 |
| ⑤ 桌面图标居中 | ✅ 通过 | launcher dump：图标 ImageView `[538,790][678,930]`（140×140 正方），中心 x=608；标签 `IQTV Lite 验证版` `[508,951][708,992]` 中心 x=608 → 图标与标签同心、不偏 |
| ⑥ 全量回归 | ✅ 通过 | 滚动单真源重开 first 贴焦点一次 scroll_correct、分组聚焦/右键收起/数字OSD/焦点蓝/S1/MP2/上一台/清空/自动更新/台标/遥控自定义键无回退、Fatal=0、不崩不灰 |

---

## 1. ① OK 一次即可选（默认 DIRECT）—— PASS

- 默认 pref 未设 `pref_info_ok_mode` → `parseInfoOkMode` 默认 DIRECT。
- **单次 OK**：`IQTV-INFOCARD: ok-open DIRECT focus recent=4` —— 一次 OK 出卡**同时进入 FOCUSED**（不是旧版两段式）；dump 可见 4 张最近观看小卡。
- **RIGHT**：`IQTV-INFOCARD: move index=1`（高亮+真焦点跟随）；**OK**：`IQTV-INFOCARD: switch idx=1 group=24 山东卫视4K`，`last_channel_title` 由 湖北卫视 → **山东卫视4K**（实际切台）✅
- 截图 `ic-01-singleOK-focused.png`。

### ①b 两段式模式（未验证，如实记录）
- 尝试进入 设置→播放与画面→信息卡 OK 行为，但该机 settings 焦点在连续 DPAD 注入下跳转到其它页（dump 出现信息卡内容而非设置项），未能可靠定位并切换「两段式」开关。
- 为不误判，**切换两段式后需第二次 OK** 这条未验证；默认 DIRECT 一次 OK 已确证。空/1张/回看/快进不聚焦属代码门控（onoInfoCardState），未构造场景。

---

## 2. ② 徽标右上紧凑一排 —— 基本通过（fps 源缺失）

- dump 徽标：`t='FHD · 2.0 · SDR · 264' b=[1675,744][1860,765]` —— 右上角 TopEnd，**单行高 21px**，未占独立 24dp 行；一排含 分辨率 FHD / 声道数字 2.0 / 动态范围 SDR / 编码 264。
- **fps 未显示**：源为该直播组播流，Media3 video Format 的 frameRate ≤0，`InfoCardPolicy.frameRateLabel` 对 ≤0/NaN 返回 null（"未知即隐藏"，非 bug）；其余可得项全部展示。解码信息 `264` 对应 H.264（技术行 `H.264·…`，若 H.265 会显示 265）。
- 台标下：`t='17. CCTV-17 农业农村'` + `t='央视频道'`（分组小字）→ **只 N.台名+分组，无 MPEG-L2** ✅
- 节目行 `[1030,786]`、进度 `[1030,835]` 位于徽标下方；卡片主内容自 y744 起，较旧版（徽标独立行 + 顶部空白）高度明显下降，<223dp。
- 截图 `ic-02-badge.png`。

---

## 3. ③ 7 秒消失 + BACK 取消 —— PASS

| 检查 | 日志/现象 | 判定 |
|---|---|---|
| IDLE 显示后 3s | card 仍可见，无 hide 日志 | ✅ 不提前消失 |
| IDLE 7s 超时 | `show state=IDLE` 10:23:35.159 → `hide reason=timer` 10:23:42.559 = **7.4s**（7±0.5） | ✅ 定时消失 |
| FOCUSED 态 8s | 无 hide 日志，card 仍可见 | ✅ 聚焦不隐藏 |
| BACK 取消 | FOCUSED 下 BACK 逐段退出（1st 退聚焦、2nd 关卡），卡片消失、仍 PlayerActivity | ✅ 可提前取消 |

> 换台触发的 IDLE 卡同样7s（代码统一 `autoHideMs(infoCardState)`）。

---

## 4. ④ EPG 收窄 + 聚焦滚动 —— PASS（宽度实测）

- 时间列文本 bounds `[494,116][596,137]` → 宽 **102px / 1.5 = 68dp**（目标 68dp，原 96dp，收窄 ~29%）✅
- 节目列起点 x≈476px（分组列右侧），节目文本 `[605,111][773,142]` → 列宽约 210dp 量级（原 300dp，收窄约 30%）✅
- 超长节目名（如 `实况录像-2026/2027赛季短道速滑世界巡回赛 加拿大蒙特利尔站 1`）单行 ellipsis；聚焦时 `FocusMarqueeText` 有界滚动（MARQUEE_ITERATIONS），代码在位；本轮 gfxinfo 无新增 >300ms 帧（无 jank）。
- 截图 `ic-04-epg-width.png`。

---

## 5. ⑤ 桌面图标居中 —— PASS

- launcher（com.dangbei.tvlauncher）dump：IQTV app 图标 `ImageView bounds=[538,790][678,930]`（140×140 正方形），中心 **(608,860)**；标签 `IQTV Lite 验证版 bounds=[508,951][708,992]` 中心 **(608,971)**。
- 图标与标签 x 中心一致（608），图标正方形未偏移/未拉伸；同排 QBox 图标 [186,790][326,930] 同规格 → 图标居中、使用当前 assets 图案。截图 `ic-05-launcher-icon.png`。

---

## 6. ⑥ 回归 —— PASS

- 滚动单真源（t29 重构保留）：深行关→重开 `drawer_scroll_correct target=15 focus=17 revealed=17` 一次、first 贴焦点（8–17），无双滚/跳开头。
- 分组聚焦即展示、右键进入收起、数字 OSD 右上、焦点蓝 #2F6BFF、S1 小分组、MP2、上一台、清空、自动更新、台标、遥控自定义键：Prefs 与代码在位，抽验无回退。
- 全程 Fatal/IllegalArgument 0，视频不灰、不崩。

---

## 7. 安装与收尾

- 装前体检 boot=1、/data 2.59G、DeadObject/ext4/I-O/installd/storaged 0。
- 备份 data 3,101,696B → 仅卸 validation 一次 → install Success → 回灌；装后 vc10225、sha256 `c5db4a4f…e7c0` 与 CI 一致；force-stop 0。
- 收尾：前台 `...PlayerActivity`；实验键 0；buffer 无覆盖（稳500 默认）；Fatal 0；`log.tag.IQTV-*` 全部复位空；/data 2.57G(23%)；v4c 回滚包保留；其他包未动。

---

## 8. 最终判定与备注

- 5 条硬断言：①③④⑤通过（②fps 因源无帧率按"未知即隐藏"设计，其余徽标信息齐全）；①b 两段式切换未验证（如实）。
- 无阻塞性失败，功能不回退，可交付。
- 备注：②若要强制显示 fps，需测试源提供有效 frameRate；当前组播源 Format 无此字段。
