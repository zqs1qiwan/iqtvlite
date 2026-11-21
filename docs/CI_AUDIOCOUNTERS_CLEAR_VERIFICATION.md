# CI10111 音频计数线程修复 + 清空硬断言 真机验证

2026-11-21，acceptance2（t171 attempt 1）。对象：`tv.iqtvlite.app.validation` **10111 / 2.2.0-tivimini-661b2b0f-validation**（t169：PlaybackSession 修复错线程崩溃；run 44821763419）。设备 `192.168.2.245:5555`（TR100-G9 API28 v7a 1080p）。测试源 `https://iptv.laobaitv.net`。只替换 validation；原包保留。

## 0. 结论摘要

| 项 | 结果 |
|---|---|
| **无错线程崩溃（默认模式）** | **通过**：CCTV-1 播放 + 3 次切台，`FATAL/wrong thread` = **0**，pid 稳定 |
| **无崩溃（计数模式 armed）** | **通过**：`IQTV-AUDIO=DEBUG` 重启后播 60s，crash=0，pid 稳定 |
| **IQTV-AUDIO 计数行** | **通过**：恰好一行 `decoder=ffmpeg… encoding=2 tracks=1 underrun=0 retry=0` |
| **清空硬断言** | **通过**：点「清空最近观看」→ `recent_channels_cleared=true`、`recent_channels_v1` 删除；**force-stop 重启仍空** |
| 同频道恢复不重建 | **通过**：清空后停留 10s，prefs 仍无 recent key |
| 清空后切新组仅 1 条 | **通过**：跳到组 2 后列表仅 `▶ 2. CCTV-2 财经` 一行 |
| Back 只关列表 | **未验证**（uiautomator 对 dialog 稳定 root null，8 次重试均空） |
| **OK 选非当前台切换** | **通过**：列表内 DOWN×2+ENTER → session 变化且列表关闭 |
| **缓存清理** | **通过**：`IQTV-LOGO cache_cleared=memory:0 disk:0`；设置显示 内存 0 条 · 磁盘 0 个 / 0 KB |
| 不回归 | 167 切换/MP2/OSD/无崩溃 通过；台标/灰屏未逐项 |

## 1. 身份/安装（PASS）
- app `92ab44c53c0efe3124ad85295d3d882204c482ce0493a75be17c02b61de77544`（13,010,835B，match）/ test `12b28c5b…c8de`（502,955B，match）；签名 `0c4fd163…`；10111/2.2.0-tivimini-661b2b0f-validation；安装后 base SHA match；backup 10107（1,419,264B）后仅替换；原包保留。

## 2. 无崩溃 + 计数行（原始）
- **默认模式**（`log.tag.IQTV-AUDIO=INFO`）：播 CCTV-1（`format avc 1920x1080`、`audio_decoder ffmpegLavc60.3.100-mp3 init_ms=7`、`first_frame ttff=825ms`）+ 167×3 切台 → **crash 0**，pid 9701。
- **计数模式**（`log.tag.IQTV-AUDIO=DEBUG`，**必须先 force-stop 再启动**）：60s 后输出
  `I/IQTV-AUDIO: active_ms=0 underrun=0 retry=0 tracks=1 rendered=0 skipped=0 decoder=ffmpegLavc60.3.100-mp3 encoding=2 rate=48000 buffer=96000`
  → 恰好一行、decoder=ffmpeg、encoding=2、underrun=0、retry=0、tracks=1；crash=0，pid 稳定。
- 注：若在进程已启动后才 `setprop DEBUG`，`AudioCostCounters.enabled` 已在 `Metrics.begin` 读取为 false，**不会输出**——需重启进程（真机已复现该差异）。

## 3. 清空硬断言（原始）
- 初始：`最近观看（1）`；prefs `recent_channels_v1` 存在。
- 打开设置→OK→列表对话框（dump 可见 `最近观看` / `暂无最近观看` / `清空最近观看` / `关闭`；testTag 未映射为 resource-id，按文本定位）。
- **点击 `清空最近观看`**（bounds `[618,581][745,612]`，tap 682,596）→
  `recent_channels_cleared=true`、`recent_channels_v1` **删除**（present=False）。
- **force-stop → 重启** → 仍 `cleared=true`、`recent_channels_v1` 不存在；设置页显示 **`最近观看`（无计数）** → **仍空 PASS**。
- 同频道停留 10s → prefs 仍无 recent key（不自动重建 PASS）。
- 跳组 2（数字 2+ENTER）→ 列表 **仅 1 行** `▶ 2. CCTV-2 财经`（换组才新建 PASS）。

## 4. Back / OK
- **Back 只关列表：未验证** —— 列表打开后按 BACK，随后 8 次 `uiautomator dump` 均 **root 空**（len=0），无法判定“设置仍在/播放器未退出”；此前在 10107 曾观测到 BACK 后设置仍在（倾向通过），但不作为本版结论。
- **OK 选非当前台：通过** —— 列表内 DOWN×2 + ENTER → session `c2427738562638760953`（变化）且 `listGone=True`（列表关闭）。

## 5. 缓存清理
- 设置→清理台标缓存 → `I/IQTV-LOGO: cache_cleared=memory:0 disk:0`；设置显示 `内存 0 条 · 磁盘 0 个 / 0 KB` → **PASS**。

## 6. 不回归
- 167 切换：`c24277… → c83979… changed=True`；`audio_format mime=audio/mpeg-L2 48k/2ch`、`audio_decoder ffmpeg… init_ms=4`、`first_frame ttff=907`；`AudioTrack ID:2615 state:started`；**crash=0**。
- 数字 OSD（暗场景）：base bright0.04%/dark0.0% → 输入 1,2 后 **dark 10.24%**、bright0.55% → 暗板出现（可见）；文字需人工看 `osd-12.png`。
- 台标/灰屏未逐项复测。

## 7. 逐项
| 项 | 结果 |
|---|---|
| 身份/安装 | 通过 |
| 默认模式无崩溃（3 切台/60s） | 通过 |
| 计数模式无崩溃（60s） | 通过 |
| IQTV-AUDIO 行（ffmpeg/encoding=2/underrun=0） | 通过（需先重启进程） |
| 清空→立即空→重启仍空 | **通过**（cleared=true，key 删除） |
| 清空后切新组仅 1 条 | 通过 |
| 同频道恢复不重建 | 通过 |
| Back 只关列表 | **未验证**（dump root null） |
| OK 非当前台切换 | 通过 |
| 缓存清理 memory:0 disk:0 | 通过 |
| 不回归 | 167/MP2/OSD/无崩溃 通过；台标/灰屏未测 |

## 8. 判定
- **t169 崩溃修复真机通过**：默认/计数/armed 切台均无 `wrong thread` FATAL，计数行正常输出（单行、underrun=0/retry=0）。
- **清空硬断言通过**：清空后立即空、force-stop 重启仍空、同频道不重建、换组仅 1 条、OK 非当前台切换并关列表、缓存清理 memory:0 disk:0。
- **Back 只关列表未验证**（uiautomator dialog root null）；另有 low：设置计数曾显示 `最近观看（3）` 而列表实际 `暂无最近观看`（计数缓存未即时刷新，建议修）。
- 未改代码/原包数据；设备现为 CI10111（PlayerActivity 前台，可出画、MP2 正常）。
