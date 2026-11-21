# 设置二级信息架构 v2（SETTINGS_IA_V2）

> 版本 1.0 · 2026-11-21 · designer（任务 t2）· 代码基线 `b949a41`
> 适用：t3（设置二级菜单实现）、t8（复审）、t9（真机验收）
> 目标机：TR100-G9 / API28 / 1080p / density 240 → **逻辑屏 1280×720dp**（1dp = 1.5px）。本文所有 dp/sp 均以该逻辑屏为准。

---

## 0. 一句话

**一级只留 6 个入口（每行「标题 + 当前值摘要」），具体设置项全部收进 6 个二级页；滚动 / 焦点 bringIntoView / 0dp 无蓝框 / Back 逐级返回全部沿用现有实现，现有 24 个设置行一项不丢（仅合并 1 组完全重复的动作行）。**

### 0.1 本轮决策（评审先看）

| 编号 | 决策 | 理由 / 回退 |
|---|---|---|
| **D-S1** | 抽屉宽度保持 **300dp** 不变；一级行采用**两行式**（标题 + 摘要），不做「右侧值列」 | 300dp 下 `遥控器与操作` + 右侧值放不下（剩余内容宽 252dp）；两行式与现有「行 + 说明小字」的视觉语言一致。若要加宽到 360dp 属另一轮视觉决策，本文不授权 |
| **D-S2** | 「更新EPG」（旧 `settings_epg_source`）与「立即刷新 EPG」（旧 `settings_refresh_epg`）**合并为一行「立即更新 EPG」**，保留 tag `settings_refresh_epg`；`settings_epg_source` 退役 | 两者是同一动作：`PlayerActivity.kt:1520-1524` 与 `1698-1702` 都只做 `requestManualEpg()`；唯一差异是旧「更新EPG」会先关抽屉。合并后采用「不关抽屉」（与旧 `立即刷新 EPG` 一致，用户能就地看到「上次更新」刷新）。见 §4 |
| **D-S3** | 一级默认焦点 = **第 1 行「播放与画面」**（旧版默认焦点是「最近观看」） | 「最近观看」迁到「数据与更新 → 观看记录」，一级不再有该行。验收/自动化路径见 §9.3（按键步数已给全）。**captain 2026-11-21 已批准**（决定记录见 §12 cap·R2；captain 批复中把它记作 D-C1，编号对调说明见 §12） |
| **D-S4** | 「关于」页只承载：应用名 / 版本名 / 版本号 / **检查更新** | 检查更新原本属于「基础」组，按本轮一级入口定义归入「关于」。不新增「日志」「许可」等未要求内容 |
| **D-S5** | 不新增「字幕」设置行 | 现版本没有任何字幕设置行（只有 `SubtitleManager` 内部逻辑）。本任务要求「不丢失」，不是「发明」；`音频与字幕` 页当前只有音轨选择行 |
| **D-S6** | 二级页内打开对话/配置页时，抽屉先关闭（沿用 t179「一次只有一个覆盖层」），Back 由对话自行消费，**不**回到设置页 | 保持现有已验证行为（`PlayerActivity.kt:1654-1669`、`1554-1557`、`1525-1530`）；本轮不改覆盖层层级，避免回归 t179 的按键守卫 |

### 0.2 不可回归项（红线）

1. 抽屉：300dp、右对齐、`surface` 0.95、0dp 圆角、24dp padding、160/140ms 滑入滑出。
2. 焦点：`surfaceVariant` 底色表达焦点，**无边框 / 无蓝框 / 无缩放 / 无阴影**。
3. 每个可聚焦行在焦点进入时 `bringIntoView()`；列表 `verticalScroll`（不引入 LazyColumn）。
4. `settings_list`、`settings_audio_track`、`settings_refresh_epg`、`settings_auto_epg`、`settings_logo_clear`、`settings_remote_updown` 等既有 tag 保留（`scripts/check_settings_scroll.py` 仍须通过）。
5. 遥控习惯即时生效（`onRemoteSettingsChanged`，不等 pause/resume）；自动更新/EPG 单次刷新；台标缓存清理；音轨单轨门控（不可选时不开空框）；最近观看计数与清空。
6. 所有设置持久化在 `Prefs`，进程重启保留；本轮**不新增任何设置项**。

---

## 1. 现状（证据）与问题

`android/app/src/tivimini/java/com/cjlhll/iptv/SettingsDrawer.kt:175-430`：单页 24 个可聚焦行 + 6 个分组标题，全部堆在一个 300dp 列里。

| 现状问题 | 证据 |
|---|---|
| 单页过长，超出 720dp 视口，只能靠滚动 | `SettingsDrawer.kt:170-173`（t192 加的 `verticalScroll`）、`docs/SETTINGS_SCROLL_FIX.md`、`docs/CI_SETTINGS_SCROLL_F3_VERIFICATION.md:27-34` |
| 分组语义错位：「基础」组只包住「最近观看」，源配置/更新EPG/检查更新 反而在「数据与更新」之前且无 header | `SettingsDrawer.kt:182-228`；历史评审 `docs/AUTO_UPDATE_SETTINGS_REVIEW.md:68-70` |
| 实验项 4 段长说明文字与设置行交错，一屏读不完 | `SettingsDrawer.kt:241-282` |
| 一级与具体项混排：翻找「音轨」要滚动 8+ 行 | `SettingsDrawer.kt:326-343` |
| 重复动作：「更新EPG」与「立即刷新 EPG」 | `PlayerActivity.kt:1520-1524` / `1698-1702`（都只调 `requestManualEpg()`） |

**结论**：需要「一级入口 + 二级页」，不是再排序。

---

## 2. 目标 IA（树）

```
设置（抽屉，300dp，右滑入）
├─ 1  播放与画面           摘要：起播缓冲 稳(500ms)[ · 实验开]
│     └ 二级页 playback
│        ├ 起播缓冲：稳(500ms)                     [settings_playback_buffer]
│        ├ 线路选择（当前频道）                     [settings_quality_menu]（多线路才出现）
│        ├ 预连接相邻频道（实验）：关               [settings_zap_preconnect]
│        ├ 组播预热（实验）：关                     [settings_multicast_prewarm]
│        └ 睛彩诊断实验：关闭                       [settings_jingcai_experiment]
├─ 2  遥控器与操作         摘要：换台 · 显示信息卡
│     └ 二级页 remote
│        ├ 上下键：换台                            [settings_remote_updown]
│        ├ OK键：显示信息卡                         [settings_remote_ok]
│        ├ Back键：关闭抽屉                         [settings_remote_back]
│        ├ 数字键：直接跳台                         [settings_remote_digits]
│        ├ 频道键 166/167：启用                     [settings_remote_channel_keys]
│        └ 恢复默认                                [settings_remote_reset]
├─ 3  音频与字幕           摘要：音轨 mpeg-L2 · 2ch
│     └ 二级页 audio
│        └ 音轨选择（当前频道）                     [settings_audio_track]（+ 当前：… 小字）
├─ 4  数据与更新           摘要：列表 启动时 · EPG 启动时
│     └ 二级页 data
│        ├ §列表   源配置                          [settings_source_config]
│        │         自动更新播放列表：启动时         [settings_auto_playlist]
│        │         立即刷新播放列表                 [settings_refresh_playlist]
│        ├ §EPG    自动更新 EPG：启动时             [settings_auto_epg]
│        │         立即更新 EPG                    [settings_refresh_epg]（合并旧两行）
│        │         上次更新：…（静态小字，不可聚焦）
│        └ §观看记录 最近观看（N）                  [settings_recent_entry] → 现有对话框
├─ 5  台标与缓存           摘要：96 条 / 120 个
│     └ 二级页 logo
│        ├ 内存 96 条 · 磁盘 120 个 / 326 KB（静态小字）
│        ├ 台标预取：开                            [settings_logo_prefetch]
│        ├ 台标显示：开                            [settings_logo_load]
│        ├ 刷新当前组台标                          [settings_logo_refresh]（新增 tag，行为不变）
│        └ 清理台标缓存                            [settings_logo_clear]
└─ 6  关于                 摘要：版本 10137
      └ 二级页 about
         ├ 应用名 / VERSION_NAME / VERSION_CODE（静态文本）
         └ 检查更新                                [settings_check_update]
```

一级 **正好 6 行**；二级每页 **≤ 6 个可聚焦行**（`playback` 5、`remote` 6、`audio` 1、`data` 6、`logo` 4、`about` 1）。
720dp 高屏上各页通常无需滚动即可全见；**例外（t16/F3 实测口径修正）**：`playback` 页保留 4 段完整帮助后约 **680dp > 672dp 可用高，末尾可能需滚动 1 行** —— 功能与可达性不受影响（`verticalScroll` + `BringIntoViewRequester` 保留）。滚动能力亦保留给更小屏/更长文案/未来扩展。

---

## 3. 页面明细（可直接实现）

### 3.1 一级（root）

| # | 入口 | 摘要（第 2 行，12sp） | 摘要生成（纯函数） | tag |
|---|---|---|---|---|
| 1 | 播放与画面 | `起播缓冲 稳(500ms)` 或 `… · 实验开` | `"起播缓冲 " + LoadControlTuning.tierLabel(buf)`；任一实验开（zapPreconnect / multicastPrewarm / jingcai!=OFF）追加 `" · 实验开"` | `settings_root_playback` |
| 2 | 遥控器与操作 | `换台 · 显示信息卡` | `"${upDown.label} · ${ok.label}"` | `settings_root_remote` |
| 3 | 音频与字幕 | `音轨 mpeg-L2 · 2ch` / `—` | `audioSummary?.let { "音轨 $it" } ?: "—"` | `settings_root_audio` |
| 4 | 数据与更新 | `列表 启动时 · EPG 启动时` | `"列表 ${playlist.label} · EPG ${epg.label}"` | `settings_root_data` |
| 5 | 台标与缓存 | `96 条 / 120 个` | `"${mem} 条 / ${disk} 个"`（来自 `LogoLoader.stats`；抽屉关闭时为空→`—`） | `settings_root_logo` |
| 6 | 关于 | `版本 10137` | `"版本 " + BuildConfig.VERSION_CODE` | `settings_root_about` |

规则：
- 摘要单行、`maxLines=1`、`TextOverflow.Ellipsis`；**长度 ≤ 22 全角字符**（252dp 内容宽 / 12sp ≈ 21 字）。超出由格式化函数截断（见 §8.3 测试）。
- 摘要**不允许做磁盘 IO**：台标统计沿用 `PlayerActivity.kt:1503-1510` 的 `produceState`（仅在抽屉打开时计算，IO 在 `Dispatchers.IO`）；值为空时显示 `—`。
- 一级行不显示分组标题（“设置”标题即上下文）。
- **一级页脚提示**（captain 要求：OK 默认改为“显示信息卡”后，频道列表入口必须在设置抽屉里可见）：第 6 行下方 12dp 处一行 `关闭后按 ← 打开频道列表`，`bodySmall` 12sp/16、`onSurfaceVariant` α0.5、start 16dp，**不可聚焦**、不参与焦点顺序、不改变任何行行为（文案与理由见 §12 cap·R1）。

### 3.2 播放与画面（`playback`）

| 行 | 文案（含值） | OK 行为 | 抽屉 | 帮助小字（保留原文，不删减） |
|---|---|---|---|---|
| 起播缓冲 | `起播缓冲：稳(500ms)` | 循环 快→均衡→稳，Toast「下次切台生效」，焦点留在本行 | 保持 | `SettingsDrawer.kt:242-246` 原文 |
| 线路选择 | `线路选择（当前频道）` | 打开现有 `QualitySelectorDialog`（`qualityMenuEnabled` 才显示该行） | **关闭** | — （无） |
| 预连接相邻频道 | `预连接相邻频道（实验）：关/开` | 切换 + Toast（现逻辑） | 保持 | `SettingsDrawer.kt:253-258` 原文 |
| 组播预热 | `组播预热（实验）：关/开` | 切换 + Toast + `MulticastPrewarm.cancel()`（现逻辑） | 保持 | `SettingsDrawer.kt:277-282` 原文 |
| 睛彩诊断实验 | `睛彩诊断实验：关闭/E1…/E3…` | 循环 + Toast「重新选台后生效」（现逻辑） | 保持 | `SettingsDrawer.kt:271-276` 原文 |

- 「线路选择」在不适用时**整行不出现**（不是置灰），与现状 `qualityMenuEnabled` 一致；页面首行仍是「起播缓冲」，不丢焦点。
- 5 行 + 3 段帮助 ≈ 300dp，720dp 内全见。
  - **例外（t16/F3 裁定，方案 A）**：`playback`（播放与画面）页保留 4 段完整帮助后约 **680dp > 672dp（720−48）**，**允许向下滚 1 行**；不缩行高、不删帮助文案。t9 若记录到该页需滚 1 行属预期，不判失败。

### 3.3 遥控器与操作（`remote`）

| 行 | 文案 | OK 行为 | 抽屉 | tag |
|---|---|---|---|---|
| 上下键 | `上下键：{换台/呼出频道列表}` | 循环 → `Prefs.setRemoteUpDown` + `remoteHabits.copy` + `onRemoteSettingsChanged` | 保持 | `settings_remote_updown` |
| OK键 | `OK键：{显示信息卡/打开频道列表}` | 循环（标签由 `RemoteHabits.Ok.label` 决定；默认值变更见 `INFO_CARD_V2.md` D-C1） | 保持 | `settings_remote_ok` |
| Back键 | `Back键：{关闭抽屉/直接退出}` | 循环 | 保持 | `settings_remote_back` |
| 数字键 | `数字键：{直接跳台/先显示输入条需OK}` | 循环 | 保持 | `settings_remote_digits` |
| 频道键 166/167 | `频道键 166/167：启用/禁用` | 切换 | 保持 | `settings_remote_channel_keys` |
| 恢复默认 | `恢复默认` | `Prefs.resetRemoteSettings` + Toast「已恢复默认遥控设置」 | 保持 | `settings_remote_reset` |

页尾静态小字（bodySmall，onSurfaceVariant 0.72）两行，均**不可聚焦**：
1. `默认：换台 / 显示信息卡 / 关闭抽屉 / 直接跳台 / 频道键启用`
2. `频道列表：← 　设置：→`（captain 要求：OK 改为出卡后，明确频道列表与设置的入口键）

### 3.4 音频与字幕（`audio`）

| 行 | 文案 | OK 行为 | 抽屉 | tag |
|---|---|---|---|---|
| 音轨选择 | `音轨选择`；不可选时 `音轨选择（不可选）`（与现状 `AudioTrackUi.entryLabel` 一致） | 可选：关抽屉 → 打开音频框；不可选：Toast（`没有音轨` / `只有 1 条音轨`），**不开空框** | 可选时关闭 | `settings_audio_track` |

`当前：{audioSummary}` 小字保留（`SettingsDrawer.kt:335-342`）。本页无其他行（**不新增字幕项**，见 D-S5）。

### 3.5 数据与更新（`data`）

| 分区 | 行 | 文案 | OK 行为 | 抽屉 | tag |
|---|---|---|---|---|---|
| 列表 | 源配置 | `源配置` | `startActivity(MainActivity)`（不 reset、不刷新，保持 `PlayerActivity.kt:1513-1519` 注释语义） | **保持** | `settings_source_config` |
| 列表 | 自动更新播放列表 | `自动更新播放列表：启动时` | 循环 OFF/启动时/6h/12h/24h + Toast | 保持 | `settings_auto_playlist` |
| 列表 | 立即刷新播放列表 | `立即刷新播放列表` | Toast「正在刷新播放列表…」+ `AutoUpdateScheduler.refreshNow(playlist=true, epg=false)` | 保持 | `settings_refresh_playlist` |
| EPG | 自动更新 EPG | `自动更新 EPG：启动时` | 循环 + Toast | 保持 | `settings_auto_epg` |
| EPG | 立即更新 EPG | `立即更新 EPG` | Toast「正在更新 EPG…」+ `requestManualEpg()`（单路径，t189 保证只发一次） | 保持 | `settings_refresh_epg` |
| EPG | 上次更新 | `上次更新：播放列表 1 分钟前 · EPG 1 分钟前` | 不可聚焦静态行 | — | — |
| 观看记录 | 最近观看（N） | `最近观看（3）` / 无则 `最近观看` | 关抽屉 → 打开**现有** `RecentChannelsDialog`（列表/清空/关闭全保留） | **关闭** | `settings_recent_entry` |

- 「立即更新 EPG」不关抽屉：与旧「立即刷新 EPG」一致；用户可就地看到「上次更新」变化（`autoUpdateTick` 驱动重组）。
- 源配置返回后抽屉仍在（现状）；这是唯一一个「二级页 → 系统 Activity」的路径，Back 从配置页回播放器时抽屉保持打开，属既有行为。

### 3.6 台标与缓存（`logo`）

| 行 | 文案 | OK 行为 | 抽屉 | tag |
|---|---|---|---|---|
| 统计 | `内存 96 条 · 磁盘 120 个 / 326 KB` | 静态行 | — | — |
| 台标预取 | `台标预取：开/关` | 切换 + `Prefs.setLogoPrefetchEnabled` + Toast | 保持 | `settings_logo_prefetch` |
| 台标显示 | `台标显示：开/关` | 切换 + `Prefs.setLogoLoadEnabled` + 关时 `LogoLoader.clearMemory()` + Toast | 保持 | `settings_logo_load` |
| 刷新当前组台标 | `刷新当前组台标` | Toast「正在刷新当前组台标…」→ 异步刷新 → `logoCacheTick++`（保持 64×40 缓存键） | 保持 | `settings_logo_refresh`（**新增**，旧行无 tag） |
| 清理台标缓存 | `清理台标缓存` | Toast → `LogoLoader.clearAll` → `logoCacheTick++` | 保持 | `settings_logo_clear` |

帮助小字保留：`SettingsDrawer.kt:399-404`、`412-417`。

### 3.7 关于（`about`）

| 类型 | 内容 | tag |
|---|---|---|
| 静态 | 应用名 `IQTV Lite`（titleMedium） | — |
| 静态 | `版本 {BuildConfig.VERSION_NAME}`（bodySmall，可折行 2 行） | — |
| 静态 | `版本号 {BuildConfig.VERSION_CODE} · 包名 {packageName}`（bodySmall） | — |
| 行 | `检查更新` → 关抽屉 + Toast「正在检查更新…」+ `UpdateChecker`（现逻辑 `PlayerActivity.kt:1531-1543`） | `settings_check_update` |

### 3.8 静态文本/帮助的归属（不允许丢失）

| 现存静态文本 | 新位置 |
|---|---|
| `logoCacheSummary`（内存/磁盘统计） | 台标与缓存页首行 + 一级摘要 |
| `lastUpdateSummary`（上次更新） | 数据与更新页 EPG 分区末尾 + 一级摘要（摘要只放列表/EPG 周期，避免过长） |
| `audioSummary`（当前音轨） | 音频与字幕页 + 一级摘要 |
| 起播缓冲说明（`242-246`） | 播放与画面页，紧随该行 |
| 预连接说明（`253-258`） | 同上 |
| 睛彩实验说明（`271-276`） | 同上 |
| 组播预热说明（`277-282`） | 同上 |
| 台标预取/显示说明（`399-404`、`412-417`） | 台标与缓存页 |

> 帮助小字**原文保留**（含「仅供 117/118/119 定位用」等），不做删减改写；它们不可聚焦、不参与焦点顺序。

---

## 4. 迁移矩阵（零丢失清单）

旧行号均指 `SettingsDrawer.kt`（基线 `b949a41`）。**旧 24 个可操作行 → 新 23 个可操作行 + 2 行静态文本**；唯一合并为 D-S2。

| # | 旧分组 | 旧文案 | 旧 tag | 新页 / 分区 | 新文案 | 行为是否保留 |
|---:|---|---|---|---|---|---|
| 1 | （无 header，`184`） | 源配置 | `settings_source_config` | 数据与更新 / 列表 | 源配置 | ✅ 同 |
| 2 | 基础 | 更新EPG | `settings_epg_source` | 数据与更新 / EPG | **立即更新 EPG** | ✅ 同动作（`requestManualEpg`），**tag 退役**，抽屉不再关闭（D-S2） |
| 3 | 基础 | 源选择 | `settings_quality_menu` | 播放与画面 / 线路 | 线路选择（当前频道） | ✅ 同（多线路才出现） |
| 4 | 基础 | 检查更新 | `settings_check_update` | 关于 | 检查更新 | ✅ 同 |
| 5 | 基础 | 最近观看（N） | `settings_recent_entry` | 数据与更新 / 观看记录 | 最近观看（N） | ✅ 同（对话框、清空、计数全保留） |
| 6 | 播放与显示 | 起播缓冲：稳(500ms) | `settings_playback_buffer` | 播放与画面 | 起播缓冲：稳(500ms) | ✅ 同 |
| 7 | 播放与显示 | 预连接相邻频道（实验）：关 | `settings_zap_preconnect` | 播放与画面 / 实验 | 同 | ✅ 同 |
| 8 | 播放与显示 | 组播预热（实验）：关 | `settings_multicast_prewarm` | 播放与画面 / 实验 | 同 | ✅ 同 |
| 9 | 播放与显示 | 睛彩诊断实验：关闭 | `settings_jingcai_experiment` | 播放与画面 / 实验 | 同 | ✅ 同 |
| 10 | 遥控器与操作 | 上下键：换台 | `settings_remote_updown` | 遥控器与操作 | 同 | ✅ 同 |
| 11 | 遥控器与操作 | OK键：… | `settings_remote_ok` | 遥控器与操作 | 同 | ✅ 同（默认值变更见 INFO 卡 D-C1） |
| 12 | 遥控器与操作 | Back键：… | `settings_remote_back` | 遥控器与操作 | 同 | ✅ 同 |
| 13 | 遥控器与操作 | 数字键：… | `settings_remote_digits` | 遥控器与操作 | 同 | ✅ 同 |
| 14 | 遥控器与操作 | 频道键 166/167：… | `settings_remote_channel_keys` | 遥控器与操作 | 同 | ✅ 同 |
| 15 | 遥控器与操作 | 恢复默认 | `settings_remote_reset` | 遥控器与操作 | 同 | ✅ 同 |
| 16 | 音频与字幕 | 音轨选择 | `settings_audio_track` | 音频与字幕 | 音轨选择 | ✅ 同（单轨门控保留） |
| 17 | 数据与更新 | 自动更新播放列表：… | `settings_auto_playlist` | 数据与更新 / 列表 | 同 | ✅ 同 |
| 18 | 数据与更新 | 立即刷新播放列表 | `settings_refresh_playlist` | 数据与更新 / 列表 | 同 | ✅ 同 |
| 19 | 数据与更新 | 自动更新 EPG：… | `settings_auto_epg` | 数据与更新 / EPG | 同 | ✅ 同 |
| 20 | 数据与更新 | 立即刷新 EPG | `settings_refresh_epg` | 数据与更新 / EPG | 立即更新 EPG | ✅ 同（合并 #2 于此） |
| 21 | 台标与缓存 | 台标预取：开 | `settings_logo_prefetch` | 台标与缓存 | 同 | ✅ 同 |
| 22 | 台标与缓存 | 台标显示：开 | `settings_logo_load` | 台标与缓存 | 同 | ✅ 同 |
| 23 | 台标与缓存 | 刷新当前组台标 | （无） | 台标与缓存 | 同 | ✅ 同；**新增 tag** `settings_logo_refresh` |
| 24 | 台标与缓存 | 清理台标缓存 | `settings_logo_clear` | 台标与缓存 | 同 | ✅ 同 |
| — | 全局 | 设置标题「设置」 | — | 一级 + 每页上下文 | 一级「设置」headlineSmall；二级页标题 24sp | ✅ |

**保留但不在设置页的既有能力（不得删）**
- `RecentChannelsDialog`（`recent_dialog` / `recent_list` / `recent_empty` / `recent_clear` / `recent_close`）由「数据与更新 → 最近观看」打开，行为、tag、testing 全保留。
- 遥控习惯持久化键名（`RemoteHabits` 枚举名）不变；`Prefs` 不迁移。
- 自动更新调度、EPG 单路径（t189）、台标缓存、音轨 override：不触碰。
- `scripts/check_settings_scroll.py` 的全部 REQUIRED_TAGS 仍存在（其中 `settings_epg_source` 本来就不在门禁列表内）。

**明确不做（防“顺手加功能”）**：不新增字幕行、不新增 DVR/收藏、不新增“恢复所有设置默认”、不新增页面动画、不改抽屉宽度、不改颜色体系。

---

## 5. 交互与焦点

### 5.1 层级与 Back 栈

状态：`CLOSED` → `ROOT`（一级）→ `PAGE(pageId)`。

| 当前 | BACK | LEFT | RIGHT | OK |
|---|---|---|---|---|
| `CLOSED`（播放器） | 既有双 Back 退出习惯（不改） | 打开频道抽屉（不改） | 打开设置（→ `ROOT`） | 按遥控习惯（信息卡/频道列表，见 INFO 卡） |
| `ROOT` | **关抽屉 → `CLOSED`** | 关抽屉（与现状一致，`SettingsDrawer.kt:150-155`） | 进入焦点行页面 → `PAGE` | 进入焦点行页面 → `PAGE` |
| `PAGE` | **回 `ROOT`，焦点回到“进来的那一行”** | 同 BACK（回 `ROOT`，同样恢复焦点） | 吞掉（no-op），不得穿透到播放器的“右开设置” | 执行行动作（见 §3），除标注「关闭抽屉」的行外焦点留在本行 |

硬性要求：
1. 二级 → 一级 **只退一层**；一级 → 关抽屉 **只退一层**；任何一次按键都不能从二级直接关抽屉或退出播放器。
2. `PAGE` 的 RIGHT 必须被消费（否则会触发 `PlayerActivity` 的 RIGHT 再开设置，出现“页面被重置”的错觉）。
3. 一级 BACK/LEFT 关闭的是设置抽屉，不是 `PlayerActivity`（现状：`PlayerActivity.kt:364-367` 先判 `settingsDrawerOpen` 再判信息条/退出）。
4. 菜单键（MENU）与 RIGHT 等价（现状 `PlayerActivity.kt:339-344`）。

### 5.2 焦点与确定性

| 场景 | 焦点落点 |
|---|---|
| 打开设置（首次进程） | 一级第 1 行「播放与画面」 |
| 打开设置（同进程再次） | 上次停留的一级行（会话内记忆，不持久化） |
| 进入某页 | 该页**上次停留的行**；首次进入 = 第 1 个可聚焦行（页面内记忆，会话内） |
| 从页返回一级 | 打开该页的那一行（用该行 `FocusRequester.requestFocus()`） |
| 页内滚动 | 焦点进入行 → 该行 `BringIntoViewRequester.bringIntoView()`（现有 `SettingsDrawer.kt:474-478` 模式） |
| 条件行消失 | 若「线路选择」在进入页面时不可用，则该行不存在，焦点落「起播缓冲」；不允许“焦点掉到不可见节点” |
| 返回页面滚动位置 | 每页 `ScrollState` 会话内缓存（`mapOf(pageId to ScrollState)`），返回时恢复 |

实现约定：
- 请求焦点一律在**组合完成后**（`LaunchedEffect(page)` + `requestFocus()`），不写 `delay(80)` 之类模拟延迟（沿用 `docs/UI_UX_DESIGN.md:159` 的既有约定）。
- 一级/二级切换**不做转场动画**（层内直接替换内容），理由是弱机 jank 与焦点确定性；抽屉本身的 160/140ms 滑入滑出不变。
- 页面切换不重建抽屉容器：同一个 `Box` 内 `when (page) { null -> RootList(); PLAYBACK -> PlaybackPage(); … }`。

### 5.3 覆盖层与返回路径（不改）

- 打开 音轨/最近观看/线路 对话框前，抽屉**先关闭**（一次只有一个覆盖层，t179）；BACK 关对话框后回到播放器，不回到设置页（D-S6）。
- 源配置：`startActivity(MainActivity)`，抽屉保持打开；从配置页返回后焦点仍在「源配置」行。
- 检查更新：抽屉关闭；结果走既有 `UpdateDialog`/Toast。

---

## 6. 视觉规格（数值可直接落地）

### 6.1 抽屉容器（不变）

| 项 | 值 | 来源 |
|---|---|---|
| 宽度 | **300dp**，`fillMaxHeight`，`Alignment.CenterEnd` | `SettingsDrawer.kt:158-165` |
| 背景 | `MaterialTheme.colorScheme.surface` **alpha 0.95**，`RoundedCornerShape(0.dp)` | `SettingsDrawer.kt:107-109` |
| 内边距 | 24dp（四边） | 同上 |
| 滑入 / 滑出 | `slideInHorizontally(tween(160), { it })` / `slideOutHorizontally(tween(140), { it })` | `SettingsDrawer.kt:141-142` |
| 遮罩 | `Color.Transparent`（不加半透明黑幕） | `SettingsDrawer.kt:108` |
| 标题 | 「设置」`headlineSmall`（24sp/32），`onSurface`，下间距 20dp | `SettingsDrawer.kt:175-180` |

### 6.2 一级行（新增样式）

| 项 | 值 |
|---|---|
| 行结构 | 两行：标题 + 摘要 |
| 标题 | `titleMedium`（16sp/24），`onSurface`，单行 |
| 摘要 | `bodySmall`（12sp/16），`onSurfaceVariant` **alpha 0.72**，单行 ellipsis |
| 行内边距 | `horizontal = 16dp, vertical = 10dp`（内容高 ≈ 24+16+20 = 60dp） |
| 行间距 | `Spacer(Modifier.padding(8.dp))` = 视觉 16dp（与现有分组行间距一致） |
| 行背景 | 未聚焦 `surface` alpha 0.01；聚焦 `surfaceVariant`（0dp，无边框） |
| 右侧指示 | 行尾 `Text("›", bodyMedium, onSurfaceVariant alpha 0.5)`；不参与焦点、不占宽度约束 |
| 焦点态 | 仅底色变化；**文字颜色不变、行高不变、无缩放、无描边** |
| 页脚提示 | `关闭后按 ← 打开频道列表`，`bodySmall` 12sp/16，`onSurfaceVariant` α0.5，start 16dp，距第 6 行 12dp，**不可聚焦** | captain 要求的入口补偿（§12 cap·R1） |
| 总高 | 6×60 + 5×16 + 标题 52 + 页脚 (12+16=28) + 上下 padding 48 = **568dp** < 672dp（720−48），首屏全见 |

### 6.3 二级页

| 项 | 值 |
|---|---|
| 页头 | 上下文小字「设置」`bodySmall` 12sp `onSurfaceVariant` alpha 0.6，下间距 2dp；页标题 `headlineSmall` 24sp `onSurface`，下间距 16dp |
| 分区标题 | `SettingsGroupHeader` 复用：`titleMedium` 16sp，`onSurface`，`start=16dp, top=12dp, bottom=4dp`，**不可聚焦** |
| 行 | 复用现有 `SettingsItem`：`titleMedium` 16sp、`padding(horizontal=16dp, vertical=14dp)`（≈52dp/行）、行间距 16dp、焦点 `surfaceVariant` 0dp |
| 帮助小字 | `bodySmall` 12sp/16，`onSurfaceVariant` alpha 0.72，`start=16dp, top=2dp, bottom=6dp`，不可聚焦 |
| 页尾 | `Spacer(8dp)`（与现状一致） |
| 滚动 | `Column.fillMaxSize().verticalScroll(scrollState)`，`testTag("settings_list")`；每行 `bringIntoViewRequester` |
| 总高（最长页 remote） | 6×52 + 5×16 + 页头 (18+2+32+16=68) + 尾 8 + padding 48 ≈ **472dp** < 672dp |

### 6.4 颜色 / 字体 / 间距 token 表

| Token | 值 | 用途 |
|---|---|---|
| `color.panel` | `colorScheme.surface` @0.95 | 抽屉底 |
| `color.text` | `colorScheme.onSurface` (#FFFFFF) | 标题、一级行标题、页标题 |
| `color.text2` | `colorScheme.onSurfaceVariant` | 摘要/帮助/分区小字（必要时 alpha 0.72） |
| `color.focus` | `colorScheme.surfaceVariant` | 焦点底（**不是** `#4EA8FF`，不加 border） |
| `type.rootTitle` | `headlineSmall` 24/32 | 「设置」/页标题 |
| `type.rootRow` | `titleMedium` 16/24 | 一级入口标题 |
| `type.rootSummary` | `bodySmall` 12/16 | 一级摘要、帮助小字 |
| `type.section` | `titleMedium` 16/24 | 分区标题 |
| `radius` | **0dp**（允许 2dp 仅弹窗） | 全部容器与行 |
| `space` | 4 / 8 / 12 / 16 / 24 | 见上表 |
| `motion.panel` | 160ms in / 140ms out | 抽屉 |
| `motion.page` | **无** | 一级↔二级内容替换 |

### 6.5 状态图（示意，非像素稿）

```
① 一级（ROOT）                     ② 二级（PAGE）
┌───────────────────────────┐      ┌───────────────────────────┐
│ 设置                       │      │ 设置                       │
│ ┌───────────────────────┐ │      │ 播放与画面                 │
│ │播放与画面              │ │      │ ┌───────────────────────┐ │
│ │起播缓冲 稳(500ms)    › │ │      │ │起播缓冲：稳(500ms)     │ │ ← 焦点(surfaceVariant)
│ └───────────────────────┘ │      │ └───────────────────────┘ │
│  遥控器与操作              │      │  快=起播更快但弱网…        │
│  换台 · 显示信息卡        ›│      │  线路选择（当前频道）      │
│  音频与字幕                │      │  预连接相邻频道（实验）：关│
│  音轨 mpeg-L2 · 2ch      › │      │  组播预热（实验）：关      │
│  数据与更新                │      │  睛彩诊断实验：关闭        │
│  列表 启动时 · EPG 启动时 ›│      │  关闭=E1/E2/E3 逐一切换…   │
│  台标与缓存                │      └───────────────────────────┘
│  96 条 / 120 个          › │        BACK/LEFT → 回一级，焦点回「播放与画面」
│  关于                      │        RIGHT → 吞掉（不穿透）
│  版本 10137              › │        OK    → 执行本行动作
└───────────────────────────┘
  OK/RIGHT → 进页            BACK/LEFT → 关抽屉

键盘焦点：仅「行」可聚焦；标题 / 分区 / 小字 / 「›」一律 focusable=false。
```

---

## 7. 滚动与性能

1. **复用而非重写**：页面仍是 `verticalScroll` + 每行 `BringIntoViewRequester`（`SettingsDrawer.kt:170-173`、`459-479`）。不引入 `LazyColumn`：每页 ≤6 行且整页只有约 10 个 Compose 节点，固定组合比回收更稳定、无 item 复用抖动（沿用 `docs/SETTINGS_SCROLL_FIX.md:15` 的结论）。
2. 一级/二级**只组合当前页**（`when` 分支），另一页不进入树 → 比现状（24 行全组合）更少节点，弱机 jank 只会更好。
3. 抽屉关闭时不做任何统计/IO：台标统计保持 `produceState(settingsDrawerOpen)`；「上次更新」文案只在抽屉打开时由现有 `autoUpdateTick` 生成。
4. 页面切换无动画、无 `AnimatedContent`、无 `graphicsLayer`/`shadow`/`clip` 新增。
5. 行背景用纯色 `background(color)`，不再传 `RoundedCornerShape(0.dp)`（避免每帧构造 outline，`docs/UI_FLAT_CLEAN3.md:27` 的做法），视觉相同。

性能验收口径（与 t6/t7 对齐）：设置菜单开/关与 8 次 DOWN 的 `gfxinfo` jank 不高于改造前（干净窗口），不得引入新的 overdraw 层。

---

## 8. 可测试契约（t3 必须交付）

### 8.1 纯逻辑模型（新文件，Android-free）

`android/app/src/main/.../` 不参与编译；放在 `tivimini/java/com/cjlhll/iptv/SettingsIA.kt`（**无 Compose import**，可 JVM 单测）：

```kotlin
enum class SettingsPage(val id: String, val title: String) {
    PLAYBACK("playback", "播放与画面"),
    REMOTE("remote", "遥控器与操作"),
    AUDIO("audio", "音频与字幕"),
    DATA("data", "数据与更新"),
    LOGO("logo", "台标与缓存"),
    ABOUT("about", "关于"),
}

data class SettingsRootEntry(
    val page: SettingsPage,
    val tag: String,          // settings_root_<id>
    val summary: String,      // 生成后的摘要（由 Formatter 传入）
)

object SettingsIA {
    const val LIST_TAG = "settings_list"
    val ROOT_ORDER: List<SettingsPage> = listOf(PLAYBACK, REMOTE, AUDIO, DATA, LOGO, ABOUT)
    /** 每个页面的行 tag，顺序即展示顺序。 */
    fun itemTags(page: SettingsPage): List<String>
    /** 旧 tag → 新页面（供零丢失测试/静态门禁）。 */
    val LEGACY_TAG_PAGE: Map<String, SettingsPage>
    /** 已合并退役的 tag。 */
    val RETIRED_TAGS: Set<String> = setOf("settings_epg_source")
}

object SettingsNavPolicy {
    sealed interface State {
        data object Closed : State
        data class Root(val focus: Int) : State
        data class Page(val page: SettingsPage, val focusTag: String) : State
    }
    enum class Key { UP, DOWN, LEFT, RIGHT, OK, BACK }
    fun onKey(state: State, key: Key, pageTags: List<String>): Pair<State, Boolean> // 新状态 + 是否消费
}

object SettingsSummary {
    fun playback(bufferLabel: String, anyExperimentOn: Boolean): String
    fun remote(upDown: String, ok: String): String
    fun audio(summary: String?): String
    fun data(playlist: String, epg: String): String
    fun logo(memoryEntries: Int, diskFiles: Int): String   // ≤22 全角字符，null/0 用 — 
    fun about(versionCode: Int): String
}
```

### 8.2 testTag 表

**保留（语义不变）**：`settings_list`、`settings_source_config`、`settings_quality_menu`、`settings_check_update`、`settings_recent_entry`、`settings_playback_buffer`、`settings_zap_preconnect`、`settings_multicast_prewarm`、`settings_jingcai_experiment`、`settings_remote_updown/ok/back/digits/channel_keys/reset`、`settings_audio_track`、`settings_auto_playlist`、`settings_refresh_playlist`、`settings_auto_epg`、`settings_refresh_epg`、`settings_logo_prefetch`、`settings_logo_load`、`settings_logo_clear`。

**新增**：`settings_root`（抽屉外容器）、`settings_root_title`、`settings_root_playback/remote/audio/data/logo/about`、`settings_page_playback/remote/audio/data/logo/about`（挂在页标题 Text 上，用于断言当前页）、`settings_logo_refresh`。

**退役**：`settings_epg_source`（D-S2，同一动作已由 `settings_refresh_epg` 承载；仓库内脚本/门禁未引用该 tag，仅 `docs/SETTINGS_SCROLL_FIX.md` 历史记录提及）。

### 8.3 JVM 测试清单（t3 必交）

| 测试类 | 断言 |
|---|---|
| `SettingsIaTest` | 一级恰好 6 项且顺序 = 播放与画面/遥控器与操作/音频与字幕/数据与更新/台标与缓存/关于；每页 `itemTags` 非空且**无重复**；全部现存 tag 均在 `LEGACY_TAG_PAGE`（除 `RETIRED_TAGS`）；一级 tag 不出现在页面 `itemTags` 中 |
| `SettingsZeroLossTest` | 24 个旧可操作 tag（含 `settings_epg_source`）逐个可映射；退役集合只允许 `settings_epg_source`；断言合并后动作仍唯一（无第二个 EPG 手动刷新 tag） |
| `SettingsBackStackTest` | `Root + BACK → Closed`；`Root + LEFT → Closed`；`Page + BACK → Root(打开它的那行)`；`Page + LEFT → Root(同)`；`Page + RIGHT → 状态不变且 consumed=true`；`Root + OK/RIGHT → Page(首行)`；连续 BACK 不会一次跳两级 |
| `SettingsSummaryTest` | 6 个摘要格式化：样例值输出与非空；`logo(96,120) == "96 条 / 120 个"`；`about(10137) == "版本 10137"`；空/负值不崩且 ≤22 全角字符；`audio(null) == "—"` |
| 既有测试不回归 | `RemoteHabitsTest`（默认值变更由 t4 负责，见 INFO D-C1）、`RecentDialogTagsTest`、`DrawerKeysTest`、`AutoUpdateConfigTest` 全绿 |

### 8.4 静态门禁

- `scripts/check_settings_scroll.py`：**不改也必须通过**（`settings_list` / `bringIntoViewRequester` / `bringIntoView()` / `SettingsGroupHeader` 不可聚焦 / REQUIRED_TAGS 全在）。
  → 因此：`SettingsGroupHeader` 函数名保留（用作分区标题）、`SettingsDrawer.kt` 仍是抽屉 composable 的唯一文件（纯模型放 `SettingsIA.kt`）。
- 新增 `scripts/check_settings_ia.py`（t3 交付）：
  1. `SettingsIA.kt` 中 `ROOT_ORDER` 恰 6 项且顺序固定；
  2. 每个 `SettingsPage` 在 `SettingsDrawer.kt` 有对应 page composable（命名 `Settings*Page`）；
  3. 24 个旧 tag 中除 `settings_epg_source` 外全部出现（在 `SettingsDrawer.kt` 或 `SettingsIA.kt`）；
  4. 一级行 tag（`settings_root_*`）不出现在页面行列表中；
  5. `settings_list` 在 `SettingsDrawer.kt` 出现且只在滚动容器上；
  6. 无 `LazyColumn`（防止实现漂移）。

---

## 9. 验收清单

### 9.1 源码/静态（t3 自证）

- [ ] 一级恰好 6 行、顺序正确、每行标题 + 摘要；无第 7 个入口、无分组标题。
- [ ] 6 个二级页存在；24 个旧行 → 23 行映射完成（§4 逐行可勾）。
- [ ] `settings_epg_source` 退役、`settings_refresh_epg` 承载合并行；`settings_logo_refresh` 新增。
- [ ] 回退层级：二级 BACK/LEFT → 一级（焦点回入口行）→ BACK/LEFT → 关抽屉；RIGHT 在二级被吞。
- [ ] `verticalScroll` + 每行 `bringIntoViewRequester` 均在；`SettingsGroupHeader` 不可聚焦。
- [ ] 抽屉 300dp / surface 0.95 / 0dp / 24dp padding / 160-140ms 动画 / 焦点 `surfaceVariant` 无边框：数值与基线一致。
- [ ] 无 IO：抽屉关闭时无 `LogoLoader.stats`；摘要为空显示 `—`。
- [ ] `check_settings_scroll.py` 通过；新增 `check_settings_ia.py` 通过；JVM 测试（§8.3）全绿。
- [ ] 一级页脚提示 `关闭后按 ← 打开频道列表` 存在且不可聚焦；遥控器与操作页含 `频道列表：← 　设置：→` 帮助行（§12 cap·R1）。

### 9.2 真机（t9 用）

| # | 步骤 | 期望 |
|---|---|---|
| 1 | 播放器按 RIGHT | 设置抽屉滑入；焦点在「播放与画面」 |
| 2 | 连续 DOWN ×5 / UP ×5 | 焦点在 6 行内移动，无越界、无隐藏焦点；每行摘要正确（起播缓冲/遥控/音轨/更新周期/缓存/版本） |
| 3 | 焦点在「数据与更新」按 OK | 进入二级页；页标题「数据与更新」；焦点在「源配置」 |
| 4 | 页内 DOWN 到底（6 行） | 「最近观看（N）」可达且可见；「上次更新」静态小字不抢焦点 |
| 5 | BACK | 回一级且焦点在「数据与更新」；再 BACK 关抽屉；播放器仍前台 |
| 6 | 二级页按 RIGHT | 无任何变化（不退出、不重开设置、不穿透） |
| 7 | 二级页按 LEFT | 与 BACK 等价（回一级、焦点回入口行） |
| 8 | 播放与画面 → 睛彩实验 | 循环 + Toast「重新选台后生效」；帮助原文仍在 |
| 9 | 遥控器与操作 → 上下键改「呼出频道列表」 | 立即生效（无需重启），摘要同步为「呼出频道列表 · …」 |
| 10 | 数据与更新 → 立即更新 EPG | 抽屉保持；`last_epg_update_time` 只前进 1 次（沿用 `CI_SETTINGS_SCROLL_F3_VERIFICATION` 断言） |
| 11 | 数据与更新 → 最近观看 → 清空 | 对话框显示「暂无最近观看」；一级/二级摘要计数同帧变 0；force-stop 重启仍空 |
| 12 | 台标与缓存 → 清理 | 统计变 0；「立即刷新播放列表」路径不受影响 |
| 13 | 关于 | 版本号与 `dumpsys package` 一致；检查更新可走通 |
| 14 | 反复开关 20 次 + 二级进出 20 次 | 无崩溃、无灰屏、无 ANR；jank 不劣于基线（t6 数据对比） |
| 15 | 一级页脚 | 可见 `关闭后按 ← 打开频道列表`；焦点无法落到该文本（连续 DOWN 停在「关于」） |
| 16 | 「关于」页按 DOWN | 焦点停在「检查更新」，不越界到静态文本 |

### 9.3 自动化路径（给 t9 / uiautomator）

| 目标 | 按键路径 |
|---|---|
| 打开设置 | `RIGHT`（或 `MENU`） |
| 一级 n（1..6） | `DOWN ×(n-1)` |
| 进入页面 | `OK` |
| 最近观看 | `RIGHT` → `DOWN ×3`（数据与更新）→ `OK` → `DOWN ×5` → `OK` |
| 音轨选择 | `RIGHT` → `DOWN ×2` → `OK` → `OK` |
| 检查更新 | `RIGHT` → `DOWN ×5` → `OK` → `OK` |
| Back 层级断言 | 二级 `BACK` → dump 仍见 `settings_root_*` 与页标题消失；再 `BACK` → 设置抽屉消失，`mResumedActivity` 仍 `PlayerActivity` |

---

## 10. 风险与回退

| 风险 | 影响 | 缓解 / 回退 |
|---|---|---|
| 旧自动化脚本按旧顺序点「更新EPG/最近观看」 | 脚本失败，非功能回归 | 路径表（§9.3）随本 spec 交付；`settings_epg_source` 退役写入变更说明 |
| 焦点恢复抖动（页面切换 requestFocus 过早） | 焦点落到错误行 | 组合完成后 `LaunchedEffect` 请求；不写 delay hack；`SettingsBackStackTest` 覆盖状态机 |
| 摘要过长（台标统计/版本名） | 布局挤压、ellipsis | 统一格式化函数 + ≤22 全角字符断言；版本名放页面内，不放摘要 |
| 一级从 24 行变 6 行后用户找不到项 | 可用性 | 一级摘要即“当前值”，二级页 ≤6 行；真机验收 §9.2 |
| OK 键默认值变更（INFO D-C1） | 遥控习惯观感变化 | 属信息卡任务，设置页只显示 label；如需保留旧默认，`RemoteHabits.DEFAULT.ok = OPEN_DRAWER` 一行回退，二级页不变 |

回退方式：整个改造集中在 `SettingsDrawer.kt` + 新增 `SettingsIA.kt` + `PlayerActivity` 调用点；不涉及数据/Preference 迁移，回退 = revert 这三个文件。

---

## 11. 版本关系与非目标

- 视觉基线：[`UI_VISUAL_SPEC_TIVIMATE.md`](UI_VISUAL_SPEC_TIVIMATE.md)（0dp/无边框/`surfaceVariant` 焦点/字号层级）。本文只改变**信息架构**，不改颜色体系、台标尺寸（抽屉 64×40 不变）、行字号（页面行仍 `titleMedium`）。
- 滚动基线：[`SETTINGS_SCROLL_FIX.md`](SETTINGS_SCROLL_FIX.md)、[`CI_SETTINGS_SCROLL_F3_VERIFICATION.md`](CI_SETTINGS_SCROLL_F3_VERIFICATION.md)。
- 层级/焦点思想参考：[`UI_UX_DESIGN.md`](UI_UX_DESIGN.md) §“设置根与所有子页”（Back/左回父层、进入根默认焦点上次使用项）；但**不采用**其品牌色/描边焦点（已被 v4 Flat 取代）。
- 非目标：抽屉宽度/颜色改版、字幕设置、设置项搜索、设置项迁移向导、页面转场动画、把对话框改为二级页（线路/音轨/最近观看仍用对话框）。

---

## 12. 决定记录（captain 批准 · 2026-11-21）

> **编号说明（先读）**：提交评审时我用的是「D-S3 = 一级默认焦点」「D-C1 = OK 键默认」；captain 批复把两者编号对调（D-S3 = OK 键、D-C1 = 一级焦点）。**批准内容一致**。为避免 t3/t4 按错编号，以下按内容定名 **cap·R1 / cap·R2**，并列出两个编号别名。两条记录与 `INFO_CARD_V2.md` §12 同源。

### cap·R1（OK 键默认 = 显示信息卡 · 本文件原 D-C1，captain 记作 D-S3）

- **结论：批准。**
- **生效范围**：仅当**从未写入** `remote_ok` 偏好时采用新默认（新装 / 清数据 / 从未改过该项）；已存 `CONFIRM` / `OPEN_DRAWER` 原样保留，**不静默改老用户显式选择**。
- 枚举名 `CONFIRM` 保留（偏好兼容），仅 label `确认` → `显示信息卡`；`RemoteHabits.DEFAULT.ok = Ok.CONFIRM`。
- 入口：频道列表 = **LEFT**（现状不变），设置 = **RIGHT**（现状不变）。**入口提示两处都要写**：
  1. 信息卡：`← 频道列表　·　→ 设置`（`INFO_CARD_V2.md` §2.1 / §12）；
  2. 设置抽屉：一级页脚 `关闭后按 ← 打开频道列表`（§3.1 / §6.2）+「遥控器与操作」页帮助 `频道列表：← 　设置：→`（§3.3）。
- **遮罩优先不变**：任一覆盖层（抽屉/设置/线路/音轨/最近观看）打开时，OK 仍由覆盖层处理（`RemoteHabits.onOk(overlayOpen = true) = NONE`），习惯不参与；实现**不得**把 `SHOW_INFO` 提到守卫之前。
- 同步更新：`RemoteHabitsTest` 的默认值/标签断言（`Open` 默认由 `OPEN_DRAWER` → `CONFIRM`、label 断言）；`docs/REMOTE_HABITS_SETTINGS*.md` 属历史记录不改写，以本节为准。
- **一行回退**：`RemoteHabits.DEFAULT.ok = Ok.OPEN_DRAWER`（枚举名不变，Prefs 无需迁移；信息卡仍会在换台后自动出现）。

### cap·R2（一级默认焦点 = 播放与画面 · 本文件原 D-S3，captain 记作 D-C1）

- **结论：批准。**
- 一级首次打开焦点 = 第 1 行「播放与画面」；会话内记忆上次停留的一级行（**不持久化**）。
- 「观看记录（最近观看）」迁至「数据与更新 → 观看记录」；设置侧入口、对话框、清空、计数全部保留。
- **信息卡底部「最近观看」快跳必须保留**（用户明确要求）：见 `INFO_CARD_V2.md` §5（≤6、排除当前台、40dp 台标、左右聚焦、OK 切台、连按 A↔B）。
- **一行回退**：一级初始焦点改回按 tag 定位即可；若要同时恢复旧默认焦点「最近观看」，需在数据页复制入口（不建议，属回退之外的产品变更）。
