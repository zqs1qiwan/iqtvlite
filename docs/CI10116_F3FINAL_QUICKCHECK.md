# CI10116 真机快验（F3 收口版）

2026-11-21，acceptance2（t188 attempt 1）。对象：`tv.iqtvlite.app.validation` **10116 / 2.2.0-tivimini-1181aaf1-validation**（t182：EPG 手动单路径 / 保存源统一 / STARTUP-RESET；run 44832360654）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）。测试源 `https://iptv.laobaitv.net`。只替换 validation；原包保留。

> 模型不读图；以 logcat / prefs（run-as 直读）/ uiautomator 节点为证据。

## 0. 摘要

| 项 | 结果 |
|---|---|
| 身份/安装 | 通过（app `bb314323…e3ba` 13,043,603B / test `0bc1466f…1b22`；签名 `d55155cd…`；10116） |
| 取消不触发（源配置页 Back） | **通过**：无 `refreshAll`/`onSourceChanged`/`SourceRefresh` 日志（count=0） |
| 保存源单路径 | **部分**：LAN push（等价保存）→ `SourceRefresh: playlist refreshed. bytes=25626` 单次、`playlist_last_success_at` 更新、Toast 出现；**未走“保存并播放”按钮**（配置页 D-pad/tap 无法到达按钮） |
| **手动 EPG 单次** | **未验证**：「立即刷新 EPG」项在设置列表**屏幕外**且滚动/焦点无法到达（dump 始终止于 `立即刷新播放列表 @[1530,945]`）；`last_epg_update_time` 未变化 |
| 立即刷新播放列表 | 通过：单次 `playlist refreshed bytes=25626` + Toast |
| 设置分组顺序 | **通过**：基础 → 音频与字幕 → 数据与更新 →（台标与缓存，屏幕外） |
| 音频框 testTag | **通过**：`audio_dialog` / `audio_row_auto` / `audio_row_1_0` 均可见（testTagsAsResourceId 生效） |
| 音频框 Back 只关自身 | **未验证**（对话框打开状态不稳定，BACK 后 dump 多次为空） |
| 不回归 | MP2 正常、无崩溃；OSD/台标/灰屏未测 |

## 1. 身份/安装（PASS）
- app `bb314323977c58441a1742cb09dac48a0db1ec0f0eefb9e0f722992f478de3ba`（13,043,603B，match）/ test `0bc1466f0d90eb0357b2e777b43dcb742d35f8c2c1731a8dbcee26d1cd111b22`（502,955B，match）；签名 `d55155cd…`；10116/2.2.0-tivimini-1181aaf1-validation；安装后 base SHA match；backup 10111（1,033,728B）后仅替换；原包保留。
- **设备故障与恢复（需知悉）**：安装期间设备 `storaged`/`installd` binder 死亡（`DeadObjectException` / `IInstalld.isQuotaSupported ... null`），所有 `pm install*` 失败；`adb root` + 重启 storaged 无效，**最终 reboot 设备后恢复**（installd 复活），再卸载残留 validation 并成功安装 10116。设备现已正常。

## 2. 取消不触发（PASS）
- 打开源配置页（MainActivity）→ 直接 BACK → `logcat` 过滤 `SourceRefresh|refreshAll|onSourceChanged` **count=0**；应用退回桌面，无刷新行为。

## 3. 保存源（部分通过）
- 由于配置页输入框占用焦点（D-pad 不移动、tap 按钮无响应、IME 遮挡），**未能通过「保存并播放」按钮**触发。
- 改用 LAN 推送（`POST /api/push`，等价“保存源”路径）→ 应用自动保存并进入播放器：
  - `I/SourceRefresh: playlist refreshed. bytes=25626`（**单次**）
  - prefs：`playlist_last_success_at=1795216412489`、`playlist_last_failure_at=0`、`live_source`/`epg_source` 已写
  - Toast 出现（SurfaceFlinger Toast 层）
- 未观测到重复 `refreshAll`（count=0）；但**无法区分**是否“一次刷新两项”，因 EPG 项不可达（见 §4）。

## 4. 手动 EPG 单次（未验证）
- 设置列表实际可见项（dump）：
  `设置 | 基础 | 源配置 | 更新EPG | 检查更新 | 最近观看 | 音频与字幕 | 音轨选择（仅 1 条） | 当前：mpeg-L2 · 2ch | 数据与更新 | 自动更新播放列表：启动时 | 立即刷新播放列表 @[1530,945][1723,981]`
- **`立即刷新 EPG` / `自动更新 EPG` / `上次更新` 位于 y>981（屏幕外）**；多次 DOWN（含 6/8 次）与 swipe(1600,900→300) 均未滚动列表（dump 不变）。
- 因此：**无法点击「立即刷新 EPG」**，`last_epg_update_time` 保持 `1795216130198` 未变 → **未验证**（非失败）。
- 对照：「立即刷新播放列表」tap 成功 → 单次 `playlist refreshed` + Toast（说明按钮本身可用）。

## 5. 音频框（部分）
- 点击「音轨选择（仅 1 条）」→ dialog dump 显示：
  `audio_dialog [570,435][1350,646]`、`audio_row_auto`（`▶ 自动（跟随片源）`）、`audio_row_1_0`（`音轨 1 · mpeg-L2 · 2ch`）→ **testTag 生效**。
- **Back 只关自身未验证**：dialog 打开状态不稳定（tap 命中依赖滚动位置），BACK 后多次 dump 为空（len=0），无法判定“设置仍在/再 Back 才关设置”。

## 6. 不回归
- MP2 正常：`audio_decoder ffmpegLavc60.3.100-mp3`、`audio_format mime=audio/mpeg-L2 48k/2ch`；无 `wrong thread` FATAL（crash=0）。
- 设置分组顺序符合预期（基础 → 音频与字幕 → 数据与更新）。
- 数字 OSD / 台标 / 灰屏 / AAC 未测。

## 7. 逐项
| 项 | 结果 |
|---|---|
| 身份/安装 | 通过（含设备 installd 故障后 reboot 恢复） |
| 取消不触发 | 通过 |
| 保存源单路径（一次刷新+Toast+lastSuccess） | 部分（LAN push 路径验证；按钮路径不可达） |
| 手动 EPG 单次（一次请求/一 Toast/last update 一次） | **未验证**（EPG 项屏幕外不可达） |
| 立即刷新播放列表 | 通过（单次 + Toast） |
| 分组顺序 | 通过 |
| 音频框 testTag | 通过 |
| 音频框 Back 只关自身 | 未验证 |
| 不回归 | MP2/无崩溃 通过；其余未测 |

## 8. 判定与建议
- **通过**：取消不触发、保存源（push 路径）单次刷新 + lastSuccess + Toast、播放列表立即刷新单次、设置分组顺序、音频框 testTag 生效、MP2 无回归无崩溃。
- **未验证**（工具/布局限制，非产品判定）：手动 EPG 单次（设置列表 EPG 项在屏幕外，D-pad/swipe 均无法滚动到）；音频框 Back 只关自身（dialog dump 不稳定）。
- **建议**：①设置列表需可 D-pad 滚动到底部（当前焦点/滚动在 10116 上疑似被截断，影响 EPG 项可用性——**这是可复现的可用性问题，建议开发确认是否布局高度导致**）；②音频 dialog 根节点已 testTagsAsResourceId，但打开后 dump 偶发空，建议补 contentDescription/稳定节点。
- 未改代码、未改原包数据；设备现为 CI10116（PlayerActivity 前台，MP2 正常）。
