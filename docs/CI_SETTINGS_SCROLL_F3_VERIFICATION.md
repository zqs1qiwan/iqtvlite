# 设置可滚动 + F3 收口 真机验证（CI10120）

2026-11-21，acceptance2（t195 attempt 1）。对象：`tv.iqtvlite.app.validation` **10120 / 2.2.0-tivimini-245f56ba-validation**（t192 设置列表可滚动 + t189 F3 收口；run 44840965336）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）。测试源 `https://iptv.laobaitv.net`。只替换 validation；原包保留。

## 0. 摘要

| 项 | 结果 |
|---|---|
| 身份/安装 | **通过**（app `dcba0f67…1fbd` 13,059,987B / test `3d996265…c7a0`；签名 `22e83d39…`；10120） |
| **设置可滚动（关键 blocker 修复）** | **通过**：D-pad 连续 DOWN 后列表整体上滚，`音频与字幕`、`音轨选择（仅 1 条）（不可选）`、`数据与更新`、`自动更新播放列表`、`立即刷新播放列表`、`自动更新 EPG`、`立即刷新 EPG`、`上次更新：播放列表 1 分钟前 · EPG 1 分钟前`、`台标与缓存`、`内存 96 条 · 磁盘 120 个/326KB`、`台标预取` **全部依次滚入视口可达** |
| 焦点自动带入视野 | 通过（观察：随 DOWN 推进，后续项逐个出现在 dump 中，无“隐形焦点”） |
| swipe 可滚 | **未验证**（本轮仅验证 D-pad 滚动） |
| **「立即刷新 EPG」单次** | **通过**：`epg refreshed. programs=155` **恰好 1 次**；`last_epg_update_time` 只前进一次（1795220081089 → 1795220348317） |
| Toast（EPG 刷新） | **未直接证实**（logcat 无 Toast 文本；SurfaceFlinger Toast 层计数为 0） |
| **保存源单路径** | **部分通过**：LAN push（等价保存）后 `SourceRefresh: playlist refreshed bytes=25626` + `epg refreshed programs=155` 各 1 次 → 一次刷新两项 |
| 取消配置不刷新 | 未在本版复测（10116 已验证 count=0） |
| 失败恢复（离线→在线 fail_count 归零） | **未验证**（未做断网实验） |
| **音轨行（单音轨）不可选** | **通过**：设置行显示 `音轨选择（仅 1 条）（不可选）`；点击后 **audio_dialog 未出现**（settingsStill=True），SurfaceFlinger 出现 Toast 层（文本未捕获） |
| 音频框 Back 只关自身 | **不适用/未验证**（单音轨不可选 → 框打不开） |
| **Back 只关设置** | **通过**：设置页 BACK 后 `settingsGone=True`，`mResumedActivity` 仍为 PlayerActivity |
| 不回归 | 167 换台 / MP2 / 无崩溃 **通过**；数字 OSD 因场景变化不可判定；清空/遥控习惯默认未测 |

## 1. 身份/安装（PASS）
- app `dcba0f673595bfc9da76bfeadc01bb839fb22df5c83533f7b538a0915a4e1fbd`（13,059,987B，match）/ test `3d9962656b92204b28cc1606c16ef5e7c144182945d40da5211c8e3cc34dc7a0`（502,955B，match）；签名 `22e83d39…`；10120/2.2.0-tivimini-245f56ba-validation；backup 10118（1,464,320B）后仅替换；原包保留。
- **设备故障（已恢复）**：安装时再次出现 `DeadObjectException`（storaged/installd binder 死亡），`pm install` 全部失败；**reboot 后恢复**，随后安装成功（与 t188 同类，建议开发/运维关注该测试机稳定性）。

## 2. 设置可滚动（关键，PASS）
设置页打开后逐次 DOWN 并 dump，观察到的可见项序列（节选）：
- step0–5：`设置|基础|源配置|更新EPG|检查更新|最近观看|遥控器与操作|上下键：换台|OK键：打开频道列表|Back键：关闭抽屉|数字键：直接跳台|频道键 166/167：启用`
- step6：新增 `恢复默认`
- step8：新增 `音频与字幕`、`音轨选择（仅 1 条）（不可选）`、`当前：mpeg-L2 · 2ch`、`数据与更新`、`自动更新播放列表：启动时`
- step11：新增 `立即刷新播放列表`、`自动更新 EPG：启动时`、`立即刷新 EPG`
- step12–14：新增 `上次更新：播放列表 1 分钟前 · EPG 1 分钟前`、`台标与缓存`、`内存 96 条 · 磁盘 120 个 / 326 KB`、`台标预取`
→ **全部关键项可达**（此前 10118 完全不可达的 blocker 已修复）。

## 3. EPG 单次（PASS）
- 点击 `立即刷新 EPG`（bounds `[1530,987][1678,1023]`，tap 1604,1005）后 20s：
  - logcat：`I/SourceRefresh: epg refreshed. programs=155` → **计数 1**
  - prefs：`last_epg_update_time` 1795220081089 → **1795220348317**（仅 +1 次）
  - Toast：logcat 无文本、SurfaceFlinger `changing Toast` 计数 0 → **Toast 未直接证实**（可能被系统去重/未记日志）。

## 4. 保存源（部分）
- `POST /api/push`（等价“保存源”）→ `Am start` 后进入 PlayerActivity，日志：
  `I/SourceRefresh: playlist refreshed. bytes=25626`、`I/SourceRefresh: epg refreshed. programs=155`（各 1 次）→ 一次刷新两项。
- 「保存并播放」按钮路径本轮未走通（配置页输入框占焦点，历史已知），故仅以 push 路径佐证。
- 取消配置不刷新：未在本版复测（10116 曾验证 count=0）。

## 5. 音轨行门控（PASS）
- 设置行显示 `音轨选择（仅 1 条）（不可选）`（t187 的 audioSelectable 文案生效）。
- 点击后：`audio_dialog` **未出现**、`settingsStill=True`；SurfaceFlinger 记录 `Toast#0 destroyed`（说明有 Toast 弹出）→ 符合“单音轨 Toast 且不开框”。Toast 文本未捕获。
- 音频框 Back：因框不可打开，**本版不适用**（需多音轨源才能验证）。

## 6. Back 层级（PASS）
- 设置页按 BACK：`settingsGone=True`；`mResumedActivity` 仍 `PlayerActivity` → **只关设置，不退出播放器**。

## 7. 不回归
- 167 换台：`NONE → c75702… changed=True`；`audio_decoder ffmpegLavc60.3.100-mp3 init=8`、`audio/mpeg-L2 48k/2ch`、`AudioTrack ID:39 state:started`、`first_frame ttff=777ms`。
- `crash=0`，pid 4062 稳定。
- 数字 OSD：base bright33.0%/dark5.64% → 输入 1,2 后 bright0.16%/dark8.35%（**场景变化**，不可判定）；截图存证。

## 8. 逐项
| 项 | 结果 |
|---|---|
| 设置可滚动、全项可达 | **通过** |
| 焦点自动 bringIntoView | 通过（观察） |
| swipe 可滚 | 未验证 |
| 立即刷新 EPG 单次（1 请求/1 Toast/1 次 last update） | 通过×2（请求/时间）；Toast 未证实 |
| 保存源一次刷新两项 | 部分通过（push 路径） |
| 取消不刷新 | 未复测（10116 PASS） |
| 失败恢复 fail_count 归零 | 未验证 |
| 音轨行「不可选」+ OK Toast 不开框 | 通过（Toast 层出现） |
| 音频框 Back 只关自身 | 不适用（单音轨） |
| Back 只关设置 | **通过** |
| 遥控默认/OSD/频道键/MP2/清空/不崩 | 167/MP2/不崩 通过；OSD 不可判定；其余未测 |

## 9. 判定
- **t192 的“设置列表不可滚动”blocker 真机修复确认（PASS）**；所有分组项（含此前不可达的 `音频与字幕/音轨选择`、`数据与更新/立即刷新 EPG/自动更新 EPG/上次更新`、`台标与缓存`）均可滚动到达。
- **F3 收口**：EPG 手动单次（1 次请求 + last update 一次）通过；保存源经 push 路径一次刷新两项通过；音轨单轨门控与 Back 层级通过。
- 未验证：swipe 滚动、Toast 文本、离线失败恢复、音频框 Back（需多音轨源）、清空/遥控默认回归。
- 未改代码、未改原包数据；设备现为 CI10120（PlayerActivity 前台，MP2 正常）。
