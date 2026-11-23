# FOCUSED 穿透修复真机断言验证报告（CI#228 / vc10228）

- 时间：2026-11-23 10:43–11:02（Asia/Hong_Kong）
- 执行：acceptance（t37 attempt 1）
- 设备：TR100-G9 / `192.168.2.245:5555`
- 包：CI #228 / versionCode **10228** / `2.2.0-tivimini-3ca71b34-validation` / app sha256 `4ca55ecc1f6823f93d1abadc274c8747a4422fa70a40de5260411f43d725c1de`（装后拉取已装 base.apk 校验一致）
- 修复内容（t35 / 3ca71b3）：新增 `FocusedKeyAction` 纯函数，FOCUSED 态按键先交 Compose、未消费则用语义控制器执行并**总是 return true**，删除旧"清焦点后继续 when"穿透路径
- 安装：新签名 `58d329fc…` → 备份 data 3,116,032B → 仅卸 validation 一次 → install → 回灌；**force-stop 0**
- **用户确认截图**：`docs/evidence/user-review/infocard-focused-fix-1.png`（FOCUSED 第1张卡）、`infocard-focused-fix-2.png`（右移到第2张卡高亮）

---

## 0. 结论总表

| 断言 | 结论 | 关键证据 |
|---|---|---|
| 1) 一次 OK 进 FOCUSED + RIGHT×3 每步 move + 卡片不消失 + 绝不 overlay hide | ✅ **通过（核心 bug 修复）** | `ok-open DIRECT focus`；`move index=1/2/3` 逐步；`hide reason=overlay` 计数 **0**；dump 持续 4 卡；两张截图捕获成功 |
| 2) OK 跳台 last_channel 变化 | ✅ 通过 | `switch idx=3 group=21 浙江卫视4K`，last_channel CCTV-5+ → 浙江卫视4K；overlay hide 0 |
| 3) IDLE 态 RIGHT 仍能开设置 | ⚠️ 未验证（非 bug） | 当前处于 V4「全部」分组时 RIGHT 按设计进频道列表（`RemoteBindings.resolveRight` V4 §3 特例），非 OPEN_SETTINGS；未能可靠切到非「全部」组验证 RIGHT→设置；MENU(82) 被本机 launcher 拦截。代码路径 `OPEN_SETTINGS → setSettingsDrawerOpen(true)` 完好未改 |
| 4) BACK 在 FOCUSED 只退聚焦不退出播放器 | ✅ 通过 | OK→FOCUSED 后 BACK×1 仍在 PlayerActivity（IDLE）；BACK×2 卡片消失仍 PlayerActivity，全程未退出 |
| 5) 截图 | ✅ 已交付 | 2 张 1920×1080 截图，差异区域存在（焦点移动） |
| 6) 回归 | ✅ 通过（部分项沿用上轮同代码） | Fatal 0、不崩不灰；信息卡 v4 徽标/7s/EPG 收窄在 t34 同代码路径已验证；换台列表不回开头滚动单真源代码在位 |

---

## 1. 核心断言 1 —— PASS（用户反复反馈的 bug 已修复）

- 清 logcat → 一次 OK：`IQTV-INFOCARD: ok-open DIRECT focus recent=4` —— 一次 OK 出卡即进入 FOCUSED（DIRECT 默认）。
- **连续 RIGHT×3**，每步都有日志：
  - `IQTV-INFOCARD: move index=1`
  - `IQTV-INFOCARD: move index=2`
  - `IQTV-INFOCARD: move index=3`
- **卡片全程不消失**：每步后 dump 均有 `最近观看` 节点 + 4 张卡（`recent_count_in_dump=4`）。
- **绝不出现 `hide reason=overlay`**：整段 logcat 过滤 `hide reason=overlay` 计数 = **0**；任何 hide 日志 = 0。
- 蓝底高亮依次落在小卡 1→2→3（FOCUSED 焦点语义由 `move index` + dump 持续可见佐证；截图 1/2 捕获第1、2张卡高亮状态）。

> 这正是 t35 修复的目标：旧路径在 RIGHT 被 Compose 消费后仍继续 `when` 分支，触发 overlay hide 关卡；新路径「总是 return true」彻底消除穿透。真机实测确认不再关卡。

### 用户确认截图（供 present）
- `docs/evidence/user-review/infocard-focused-fix-1.png` — FOCUSED 第 1 张最近观看卡高亮（确认菜单）。
- `docs/evidence/user-review/infocard-focused-fix-2.png` — RIGHT 后第 2 张卡高亮。
- 两张均 1920×1080，图像差异 bbox 覆盖全屏（焦点高亮移动 + 可能的节目进度变化），非重复帧。

---

## 2. 断言 2 —— PASS（OK 跳台）

- FOCUSED 右移到 idx3 后按 OK：`IQTV-INFOCARD: switch idx=3 group=21 浙江卫视4K`。
- `last_channel_title` 由 `CCTV-5+ 体育赛事` → **`浙江卫视4K`**（实际切台）✅
- 该次 OK 后 `hide reason=overlay` 计数 = **0** —— 卡片消失是换台语义所致，非 overlay 误触发 ✅

---

## 3. 断言 3 —— 未验证（非 bug，如实记录）

- 在「全部」分组（默认 selectedGroup=全部）下，IDLE 态按 RIGHT 打开的是**频道抽屉**而非设置。这不是回归：V4 §3 引入 `RemoteBindings.resolveRight()`，当 RIGHT 是默认绑定且 `selectedGroup=="全部"`（或焦点在分组列）时特例映射为 `OPEN_DRAWER`，即用户此前确认的「全部频道按 → 能进列表」功能（`RemoteBindings.kt:245-258`）。
- 尝试切换到具体分组（央视频道）后再验 RIGHT→设置，但真机连续 DPAD 注入下焦点跳转异常，未能可靠切换；MENU(82) 键被本机 launcher 拦截（弹出应用选择器）。
- 代码路径核对：`PlayerActivity.kt:487` `OPEN_SETTINGS → setSettingsDrawerOpen(true)` 完好，RIGHT 默认绑定 `22|82 → OPEN_SETTINGS` 未改（`remote_action_bindings_v1` Prefs 实测确认），故 **IDLE→设置能力未被本次 FOCUSED 修复破坏**，但真机未构造出非「全部」组的干净场景，按用户要求如实标未验证。

---

## 4. 断言 4 —— PASS（BACK 不退出播放器）

- 干净重启 App → 一次 OK 进 FOCUSED（`ok-open DIRECT focus recent=4`）。
- BACK×1：前台仍 `tv.iqtvlite.app.validation/...PlayerActivity`（退出 FOCUSED 到 IDLE，卡片仍在）。
- BACK×2：卡片消失，前台仍 PlayerActivity，全程未退出播放器 ✅
- 截图/证据：dump + foreground 查询均确认仍在 PlayerActivity。

---

## 5. 断言 6 回归

| 项 | 证据 |
|---|---|
| 不崩不灰 | 全程 Fatal/IllegalArgument 0，视频正常 |
| 信息卡 v4 徽标右上/7s/EPG 收窄 | t34（vc10225）同代码路径已逐条验证：`FHD·2.0·SDR·264` TopEnd 单行、IDLE 7.4s、时间列 68dp；本版仅改 FOCUSED 按键语义，未触及这些，无回退迹象 |
| 换台列表不回开头 | t31（滚动单真源重构）已在同分支验证；本版 `drawer_scroll_correct` 代码在位 |
| 分组聚焦/右键收起/数字OSD/S1/焦点蓝 | 代码与 Prefs 在位，抽验无异常 |
| 遥控自定义键 | `remote_action_bindings_v1` 新模型在位，fallback 派发代码保留 |

> 备注：本机 Prefs 中残留 `remote_ok=OPEN_DRAWER`（早期轮测试遗留，随 data 备份回灌），本轮后段偶发 OK 开抽屉干扰了部分补测；不影响核心断言 1/2/4（在干净重启后完成）。如需完全干净的状态，建议后续测试从空 Prefs 起。

---

## 6. 安装与收尾

- 装前体检 boot=1、/data 2.59G、DeadObject/ext4/I-O/installd/storaged 0。
- 备份 data 3,116,032B → 仅卸 validation 一次 → install Success → 回灌；装后 vc10228、sha256 `4ca55ecc…c1de` 与 CI 一致；force-stop 0。
- 收尾：前台 `...PlayerActivity`；实验键 0；buffer 无覆盖（稳500 默认）；Fatal 0；`log.tag.IQTV-*` 全部复位空；/data 2.57G(23%)；ci-infocard 回滚包保留；其他包未动。

---

## 7. 最终判定

- **用户反复反馈的 FOCUSED 穿透 bug 已彻底修复**：一次 OK 进 FOCUSED、RIGHT×3 每步 move、卡片不消失、零 overlay hide、OK 跳台生效、BACK 不退出。
- 核心 1/2/4 + 截图 + 回归通过；断言 3（IDLE RIGHT→设置）因 V4「全部」组特例 + 本机 launcher 拦截未能构造干净场景，如实标未验证（代码路径完好，非 bug）。
- 两张用户确认截图已交付，路径供 present。可交付。
