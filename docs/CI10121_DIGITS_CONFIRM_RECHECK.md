# CI10121 数字键「需 OK」补测（提交/取消/超时）+ 未测项

2026-11-21，acceptance2（t198 attempt 1）。设备已装 **CI10121 / 2.2.0-tivimini-276a2275-validation**（未重装、未改原包）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）。测试源 `https://iptv.laobaitv.net`。

## 0. 摘要

| 项 | 结果 |
|---|---|
| 设置数字键=需OK | **通过**：设置行「数字键：先显示输入条需OK」，prefs `remote_digits=NEEDS_CONFIRM` |
| **输入存在的单数字 5 → 不立即跳 → OK 跳转** | **通过**：输入 5 后无新 session；按 OK → 新 session `c8397975…`、`last_channel_title=CCTV-5 体育`（**频道号匹配**） |
| **输入 → 立即 BACK 取消** | **通过**：输入 8 后立即 BACK → 无新 session，标题仍 `CCTV-5 体育`（未跳台） |
| **超时 3s+ 不跳** | **通过**：输入 9 后等待 5s → 无新 session，标题不变 |
| 上一台 229 | **通过**：CCTV-5 → `东南卫视`（session `c7601674…`，即跳转前频道） |
| 音频行「不可选」 | **通过**：`音轨选择（仅 1 条）（不可选）` |
| 最近观看清空→重启仍空 | **通过**：清空后 `recent_channels_cleared=true`、`recent_channels_v1` 删除；force-stop 重启后仍 `cleared=true`/无 key |
| MP2 有声 | **通过**：`ffmpegLavc60.3.100-mp3 init=33`、`audio/mpeg-L2 48k/2ch`、`AudioTrack ID:223 state:started` |
| crash | **通过**：`wrong thread/FATAL` = 0 |

## 1. 设置（原始）
- 设置 →「遥控器与操作」→ 点击「数字键：直接跳台」→ 行变「数字键：先显示输入条需OK」；prefs：`remote_digits=NEEDS_CONFIRM`。

## 2. 提交（PASS）
- 基线：`session=c7601674595851735663`、`last_channel_title=东南卫视`。
- 输入 `5`（KEYCODE_5）→ 1s 后：无新 `format session`（等待确认，未跳）。
- 按 **OK** → 6s 后：
  - `session=c8397975057439523021`（**变化**）
  - `first_frame ttff=513ms`
  - prefs `last_channel_title=CCTV-5 体育` → **跳转到频道 5 且频道号匹配 PASS**。

## 3. 取消（PASS）
- 基线：`session=c8397975057439523021`、`title=CCTV-5 体育`。
- 输入 `8` → **立即 BACK** → 5s 后：无新 `format session`（性 `NONE`）、`title` 仍 `CCTV-5 体育` → **取消成功、未跳台 PASS**。

## 4. 超时（PASS）
- 输入 `9` → 等待 5s（>3s 超时）→ 无新 `format session`、`title` 不变（`CCTV-5 体育`）→ **超时不跳 PASS**。

## 5. 上一台（PASS）
- 按 `229`（LAST_CHANNEL）→ `session=c7601674595851735663`（跳转前的“东南卫视”）、`title=东南卫视`、`first_frame ttff=1214ms` → **回上一台 PASS**。

## 6. 顺带项
- **音频行**：设置显示 `音轨选择（仅 1 条）（不可选）`（单音轨门控文案）→ PASS。
- **最近观看清空→重启仍空**：清空（tap `[618,805][745,817]`）→ `recent_channels_cleared=true`、`recent_channels_v1` 删除；force-stop → 重启后仍 `cleared=true`、无 key → **PASS**。
- **MP2 有声**：`audio_decoder ffmpegLavc60.3.100-mp3 init_ms=33`、`audio_format mime=audio/mpeg-L2 48k/2ch`、`AudioTrack ID:223 state:started` → PASS。
- **crash**：`wrong thread|FATAL` 计数 **0**，pid 24987 稳定。

## 7. 逐项
| 项 | 结果 |
|---|---|
| 数字=需OK 设置生效 | 通过 |
| 输入 5 + OK → 跳 CCTV-5 | 通过 |
| 输入 8 + BACK → 取消不跳 | 通过 |
| 输入 9 + 5s → 超时不跳 | 通过 |
| 229 回上一台 | 通过 |
| 音频行不可选 | 通过 |
| 最近观看清空→重启仍空 | 通过 |
| MP2 有声 | 通过 |
| crash=0 | 通过 |

## 8. 判定
- **数字键“需 OK”的提交、取消、超时三项行为全部真机通过**（t196 遗留项补齐）。
- 顺带项（229/清空持久/音频不可选/MP2/crash）全部通过。
- 设备保持 CI10121；未重装、未改原包。
