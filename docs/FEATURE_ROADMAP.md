# IQTV Lite 功能路线图（TiviMate 式基础能力 · P0/P1/P2）

> 文档：`docs/FEATURE_ROADMAP.md` · 2026-11-21 · 设计负责人 designer2
> 性质：**只研究与写文档，不改代码、不操作 TV**。产品逻辑只提炼通用能力，不使用/照搬 TiviMate 的图标、截图、文案、布局资产或受版权保护资源。
> 基线：tivimini 原版交互 [TIVIMINI_INTERACTION_SPEC.md](TIVIMINI_INTERACTION_SPEC.md)（TIV-01–24）+ 当前 flat UI v4 + CI10093/CI10096 稳定版。TIV-21/22 已明确：遥控映射、上一台、刷新策略、启动开关、节目点击确认、Guide 直达等属于**可选增量**，不是原版复刻；本路线图就是这些增量的产品化拆解。

---

## 1. 研究结论（先看结论）

**“TiviMate 式基础框架”可归纳为 7 个用户可感知能力**：

1. **遥控器/操作习惯可配置**：上/下、OK、Back、数字键、频道键、上一台键的行为可调，且不与面板内导航冲突。
2. **数字换台 OSD**：输入可见、对比清晰、超时清除、无效反馈，不丢键/不误切。
3. **播放列表 / EPG 自动更新**：开关、周期、启动时机、手动刷新、成功时间与失败退避。
4. **音频/声道/音轨**：显示当前音轨语言/编码/声道，能选择音轨，AAC/AVC 硬解不回退（与 MP2 音频扩展衔接）。
5. **上一台 / 最近观看**：一键回上一台；最近 N 个台可点选，持久化、去重、可清空。
6. **台标缓存管理**：预取策略、缓存命中、刷新台标、清理缓存、显示用量。
7. **设置菜单分组**：TiviMate 式“组 → 子页”结构，值回显、恢复默认、键路径一致。

**当前代码差距（摘要）**：

| 能力 | 当前状态 | 主要缺口 |
|---|---|---|
| 遥控/操作 | `PlayerActivity.dispatchKeyEvent`（L206–328）硬编码 TIV-04/14；`onChannelStep`（L1003）固定换台 | 无设置、无按键捕获、无行为开关 |
| 数字 OSD | `digits`/`submitNumber`（L85–96）、右上 `Text`（L189）黑底 80%、1.2s | 无标签/目标台预览/对比分层/错误内联；无超时配置 |
| 自动更新 | `BackgroundSourceUpdater.scheduleStartupRefresh`（L40–67）仅启动；`MainActivity` L208 保存后 3s；`PlayerActivity` EPG 硬编码 5min/6h/跨天（L759–772） | 无开关/周期/时机设置；无周期调度/状态展示；播放列表仅在启动/错误时刷 |
| 音频/音轨 | `videoFormat` 仅 `p.videoFormat`（L389/549/553）；`ChannelInfoBanner` 用 videoFormat 的 width/height/bitrate/channelCount/mime（L107–132）；`AudioDiagnosticFields`/`StreamPlayback` 只有日志 | 无音频轨选择、无语言/声道/当前轨显示、无偏好持久化 |
| 上一台/最近 | 仅 `Prefs.getLastChannel` 单个 url/title（Prefs L44–56）；`Prefs.setLastChannel` 在换台保存（PlayerActivity L730） | 无上一台键、无 N 条最近列表、无历史 UI |
| 台标缓存 | `LogoLoader` LRU96（L49）、`getInMemory`（L82）、`load`（L177）、`prefetch`（L274）、`ChannelLogo`（L303）；`PlayerActivity` 首屏前 60 预取（L659–664） | 无用量统计、无刷新/清理、无预取策略设置 |
| 设置分组 | `SettingsDrawer` 仅 4 个 `SettingsItem`（L102–132），无子页 | 无分组/子菜单/值回显/恢复默认 |

---

## 2. 优先级总览（P0/P1/P2）

> 原则：**P0 = 本轮交付用户立即感知的基础能力 + 设置骨架**；P1 = 高频但可后置的操作/历史/缓存；P2 = 进阶定制。**所有项都不得破坏 TIV 基线/UI v4/现有性能路径。**

| 优先级 | 功能切片 | 为什么先做 | 估算 | 依赖 |
|---|---|---|---|---|
| **P0** | F7a 设置骨架（`SettingsRepository`+根分组占位） | 所有设置项的宿主；先统一存储/回显/Back 路径 | 1–1.5d | 无（Prefs/现有 SettingsDrawer） |
| **P0** | F2 数字换台 OSD（可见/对比/超时/无效反馈） | PRD 明确验收；当前太弱、风险低 | 0.5–1d | F7a 的 `number.timeout_ms`（可先默认） |
| **P0** | F4 音频轨/语言/声道显示 + 选择 | MP2/音频是当前主线；用户明确要求 | 1–1.5d | F7a；`onTracksChanged` 现有 |
| **P0** | F5a 上一台（单条 last-channel） | 基础遥控体验；已有 `Prefs.getLastChannel` 可复用 | 0.5d | F7a 的键设置（可先固定 `KEYCODE_LAST_CHANNEL`） |
| **P0** | F3 自动更新播放列表/EPG（开关/周期/时机 + 立即刷新 + 状态） | 现有硬编码且无用户控制；影响日常可靠性 | 1.5–2d | F7a；`BackgroundSourceUpdater` |
| **P0** | F1a 基本操作行为（上下/OK/Back/数字超时） | PRD 要“遥控器可配置”；先做最常用 4 项 | 1d | F7a；`dispatchKeyEvent` |
| **P1** | F5b 最近观看列表 N 条 | 上一台的扩展；高频但 UI/迁移稍多 | 1d | F5a 的历史存储 |
| **P1** | F1b 完整按键映射/捕获 | 进阶；需键捕获/冲突检测 | 1–1.5d | F1a/F7a |
| **P1** | F6 台标缓存管理（用量/刷新/清理/策略） | 体验维护；依赖 `LogoLoader` 扩展 | 1d | F7a；`LogoLoader` |
| **P1** | F7b 完整设置分组/播放信息页 | 把 P0/P1 的设置收进 TiviMate 式结构 | 1d | F7a，F1/F3/F4/F5/F6 |
| **P2** | 每频道音频覆盖、更新时段/Wi-Fi 限制、台标按源/尺寸策略、信息条“接下来”、Guide/收藏夹直达 | 进阶定制；不与基础能力抢资源 | 2–4d | 对应 P0/P1 稳定后 |

**依赖图（简）**：

```
F7a 设置骨架 ──┬─ F1a 基本行为 ── F1b 键映射
               ├─ F3 自动更新
               ├─ F4 音频
               ├─ F5a 上一台 ── F5b 最近观看
               └─ F6 台标缓存
F7b 分组展示 ← F1/F3/F4/F5/F6 的值与回显
```

---

## 3. 功能规格（逐项）

### F1 遥控器 / 操作习惯设置（P0 基础 / P1 完整键映射）

**目标**：不改变 TIV 默认手感，只把“上/下、OK、Back、数字、频道键、上一台”从硬编码提升为可配置；面板内导航永远不被改写。

**入口**：设置 → `遥控器与操作`（F7a 根项）→ 子页 `按键行为` / `自定义按键`（P1）/ `恢复默认`。

**交互规格**：

| 控件/设置 | 选项与默认 | 键行为/状态 | 生效范围 |
|---|---|---|---|
| 上/下键 | `换台`（默认，TIV-04）/ `显示信息条` / `无操作` | Live 全屏才生效；上=前一台、下=后一台，首尾循环；Catchup 时上下只显示信息（TIV-15） | 仅 Live 无面板 |
| OK/Enter | `显示信息条`（默认，TIV-04）/ `打开频道列表` / `播放/暂停`（P2） | 面板/对话框内不拦截；仅全屏 | Live 全屏 |
| Back | `标准层级`（默认，TIV-14）/ `两秒内双击退出` / `直接退出` | 默认严格 TIV-14：源选择→抽屉→设置→信息条→回看双 Back/直播双 Back；不能把首次 Back 误当退出 | 全屏 |
| 数字键 | `数字跳台`（默认，TIV-23）/ `无操作` | 进入数字缓冲（F2）；面板内数字不触发；最多 6 位 | Live 全屏 |
| 频道键 CH+/CH−（166/167） | `换台`（默认）/ `无操作` | 同上下，按住重复按 250ms 提交，最新请求生效 | Live 全屏 |
| 上一台键 | `上一观看频道`（默认）/ `无操作` | 见 F5a；`KEYCODE_LAST_CHANNEL` 或 `KEYCODE_MEDIA_PREVIOUS`；无历史给非阻断提示 | Live 全屏 |
| 数字超时 | `1200ms`（默认）/ 800/1500/2000ms | 最后一位后计时；OK 立即提交；Back 取消并消费 | F2 |
| 恢复默认 | — | 逐项恢复 TIV 默认；立即持久化 | 全部 |
| 自定义按键（P1） | 动作列表：上一/下一频道、上一观看、频道列表、节目单、设置、信息条 | 进入“按下要使用的按键”捕获；只接受单次 KeyUp；Menu 保留为设置安全入口；拒绝重复/冲突并提示 | Live 全屏 |

**Android TV / Leanback 建议**：
- Compose for TV（现有 `androidx.tv.material3`）自绘 `SettingsChoiceDialog`/`SettingsSection`，不引入 Leanback `PreferenceFragment`（与本项目 Compose 抽屉不一致）。
- 键捕获在 Activity/Settings 的 `onPreviewKeyEvent` 完成；过滤 `KEYCODE_MENU`/组合键；捕获后写入 `Prefs`；面板/IME 打开期间不捕获。
- `dispatchKeyEvent` 只做“查偏好→分派”，保留 TIV-04/14 默认；避免双处理。

**数据存储（Prefs）**：

| key | 类型 | 默认 |
|---|---|---|
| `remote.updown` | String | `switch`（换台） |
| `remote.ok` | String | `info` |
| `remote.back` | String | `standard` |
| `remote.digit` | String | `number_jump` |
| `remote.channel` | String | `switch` |
| `remote.last` | String | `previous_channel` |
| `number.timeout_ms` | Int | `1200` |
| `remote.custom_map_json` | String(JSON) | `{}`（P1） |

**现有代码落点**：
- `PlayerActivity.dispatchKeyEvent` L206–328（数字/上/下/OK/Back/频道键/菜单/回看），`onChannelStep` L1003，`onNumberJump` L995；`submitNumber` L87。
- `SettingsDrawer` L39–170（根项）；`Prefs`（新增 key）。
- **不要**改 `PlayerDrawer` 内部 DPAD/焦点；面板内仍由原版 Compose 处理。

**验收**：
- 每项改后杀进程重启仍保留；默认值与 TIV-04/14/23 一致；上/下/OK/Back/数字在 Live 全屏按设置生效；面板/设置/回看/信息条不被误改；恢复默认全量回退；无重复/漏键。

---

### F2 数字换台 OSD（P0）

**目标**：让用户“看得见输入、知道会切到哪、出错有解释”，不改变 TIV-23 的 1.2s/全局编号语义。

**入口**：Live 全屏按 0–9 自动显示；无设置也可用。

**交互规格**：

| 项 | 规格 |
|---|---|
| 位置 | 右上安全区（距屏幕右/上 48dp；1080p） |
| 容器 | 0dp 方角（UI v4 flat），`#121212` 85% 不透明；内边距 20–24dp；无蓝框/阴影 |
| 主数字 | 64–72sp、White/原版 `onSurface`；等宽数字；完整保留（如 `112`） |
| 辅助行 | 14–16sp `#C7D0DD`：`输入频道号 · 1.2s 自动切换`；有效号码预览 `→ 3. CCTV-1`（20sp） |
| 无效 | 2.5s 显示 `没有频道 N（1..总数）`，保持当前频道；不产生播放请求 |
| 超时 | 最后一位后 **1200ms**（可配 F1）；OK/Enter 立即提交；Back 取消并消费本次 Back；方向/Menu 先取消数字再执行自身 |
| 边界 | 最多 6 位（且不超过总频道数位数）；重复 KeyDown 不追加同一位；前导 0 允许、按十进制；超过上限保留原值并提示 |
| 状态 | 面板/设置/源选择/回看中不显示数字 OSD（或提示不可换台）；输入期间不改变视频/抽屉 |
| 清除 | 提交成功显示信息条（TIV-13）；取消/超时/无效后自动隐藏；不在屏幕残留 |

**Android TV / Leanback**：Compose 全屏 `Box` 浮层；`LaunchedEffect(numberVersion)` 计时；`PlayerActivity` 状态提升 `numberInput`/`numberError`/`numberPreview`；不要每帧重绘；OSD 不抢焦点（不 focusable）。

**数据**：`number.timeout_ms`（与 F1 共用）；无历史持久化。

**现有代码落点**：
- `PlayerActivity` `digits` L85、`submitNumber` L87–94、`onStop` L95–97、OSD L189、数字键 L209–221、`onNumberJump` L995–1001、`player` 回调 L974–981。
- 建议新增 `NumberJumpOverlay.kt`（Composable）并把 L189 的 `Text` 替换为它；`ChannelInfoBanner` 不变。

**验收**：1080p 可见/对比（文字≥7:1）；输入 `112` 显示完整并在 1.2s/OK 后切到全局 112；无效号 2.5s 提示且不切；Back 取消不退出；方向/Menu 先取消；键盘重复不重复追加；面板内不误触。

---

### F3 自动更新播放列表 / EPG（P0）

**目标**：把当前“启动一次 + EPG 硬编码 6h/跨天”变成用户可控、可观察、可重试的后台更新；失败不清缓存、不打断播放。

**入口**：设置 → `数据与更新`（F7a）→ 子页；根保留原 `更新EPG` 立即动作（TIV-11 兼容）。

**交互规格**：

| 项 | 选项/默认 | 行为 |
|---|---|---|
| 启动时更新 | 开（默认）/关 | App 启动/进入播放器后，**首帧后约 3s** 后台刷新；不阻塞冷启；EPG 与播放列表错峰 1.5s |
| 自动更新播放列表 | `启动+每6小时`（默认）/`仅启动`/`启动+每12小时`/`启动+每24小时`/`仅手动` | 按最近成功时间计算周期；失败至少 5 分钟退避；成功更新 `last_playlist_update_time` |
| 自动更新 EPG | `启动+每6小时`（默认）/`每天首次启动`/`仅手动` | 保留现有 5 分钟最小尝试间隔；失败不更新时间；成功后发 `epgUpdated` |
| 仅 Wi-Fi/以太网（P2） | 开/关（默认关/或开） | 移动网络不自动刷新（TV 通常以太网；可选） |
| 立即刷新 | `立即刷新播放列表`/`立即刷新EPG` | 用户主动不受退避；同类请求进行中禁用并显示“更新中”；不中断播放 |
| 状态 | 最近成功时间/结果/错误 | 失败可见但不弹全屏；不覆盖旧缓存；源变更重新计算 |
| 时机 | 缓存先服务首屏；后台更新 | 播放器建立后再刷新；抽屉/焦点不被强制打断 |

**Android TV / Leanback 建议**：
- 优先 `WorkManager` `PeriodicWorkRequest`（最小 15min）承载 6/12/24h 周期，`ExistingPeriodicWorkPolicy.UPDATE`，网络约束；`IptvApplication` 启动时 enqueue + enqueueUnique；`PlayerActivity` 前台协程做首帧后首次刷新（避免冷启争抢）。
- 若暂不引入 WorkManager，可用 `BackgroundSourceUpdater` 的进程内协程 ticker（仅 App 存活时有效），但需在设置里说明“仅应用运行时”。

**数据存储（Prefs）**：

| key | 类型 | 默认 |
|---|---|---|
| `auto.update_on_startup` | Bool | `true` |
| `auto.playlist.mode` | String | `startup_6h` |
| `auto.epg.mode` | String | `startup_6h` |
| `auto.update_wifi_only` | Bool | `false`（P2） |
| `update.last_playlist_time` | Long | `0` |
| `update.last_epg_time` | Long | 现有 `last_epg_update_time` |
| `update.last_result` | String(JSON) | `{}` |

**现有代码落点**：
- `BackgroundSourceUpdater.kt` L40–67（启动刷新）、L69–75（`scheduleRefresh`）、L83–148（`refreshAll/refreshPlaylist/refreshEpg`）。
- `PlayerActivity.kt` EPG 循环 L736–792（5min/6h/跨天硬编码→改为 Prefs）；`MainActivity.kt` L208（保存源后 3s 刷新）；`IptvApplication.kt` L9（启动刷新）。
- `Prefs`、`SourceStore`/`PlaylistCache`、`EpgRepository`、`SettingsDrawer`（新增数据与更新子页）。

**验收**：设置持久化；启动/周期/手动均能在日志/状态中看到；失败不清缓存且 5min 退避；播放不断流；EPG/播放列表最近成功时间更新；无冷启明显回退；重复刷新合并。

---

### F4 音频 / 声道 / 音轨显示与选择（P0）

**目标**：信息条能看到“当前音轨语言/编码/声道”，设置里能选择音轨；AAC/AVC 仍走平台/硬解，MP2 靠已有 FFmpeg 扩展兜底（t117）。

**入口**：
- 信息条右侧增加当前音频摘要（语言/编码/声道）。
- 设置 → `音频与字幕` → `音轨选择`（P0 子页）；可选：`KEYCODE_MEDIA_AUDIO_TRACK` 或长按 OK 打开（P2，避免抢默认 OK）。

**交互规格**：

| 项 | 规格 |
|---|---|
| 当前音频显示 | `语言（zh/en/原声） · 编码（AAC/MP2/AC3/EAC3） · 声道（2.0/5.1）`；无音频轨显示 `—`，不伪造 0 |
| 音轨列表 | 每行：语言 / 编码 / 声道 / 是否默认；当前轨勾选；`OK` 应用并回父页；`Back` 取消 |
| 选择策略 | `自动（首选语言）`（默认）/`手动：记住上次选择`；自动时按 `preferred_audio_language`（默认系统语言，TV 中文则 zh）匹配，否则第一轨 |
| 频道切换 | 自动策略重新按语言匹配；手动策略若该频道有同语言轨则沿用，否则回自动；不把“音轨序号”当稳定 id |
| 不回归 | 选择/切换不改 `videoFormat`/硬解；AAC/AVC 仍由 MediaCodec；MP2 仍扩展兜底；音画同步保持 |
| 状态 | 无音轨/不可选禁用并解释；加载中显示“解析音轨…”；失败保留原轨并提示 |

**Android TV / Leanback**：
- Media3：监听 `player.currentTracks`，过滤 `C.TRACK_TYPE_AUDIO` 的 `Tracks.Group`；用 `TrackSelectionParameters.Builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))` 选择；`setPreferredAudioLanguage` 实现自动。
- 新 `AudioTrackDialog.kt` 复用 `QualitySelectorDialog` 的聚焦列表/Back/勾选模式；不要新造全屏 Modal。
- 信息条新增 `audioFormat: Format?`/`audioTracks` 参数，沿用 `TechSpecText` 的字号/颜色层。

**数据存储（Prefs）**：

| key | 类型 | 默认 |
|---|---|---|
| `audio.policy` | String | `auto` |
| `audio.preferred_language` | String | `auto`（系统→zh） |
| `audio.last_track_by_channel_json` | String(JSON) | `{}`（P2 每频道覆盖） |

**现有代码落点**：
- `PlayerActivity` `videoFormat` L389/549/553、`onTracksChanged` L552–553（目前只存 videoFormat）；`ChannelInfoBanner` 参数 L26–32、音频摘要 L107–132；`AudioDiagnosticFields`/`StreamPlayback`（日志/格式字段可复用）；`QualitySelectorDialog`（对话框范式）；`Prefs`；`SettingsDrawer`。

**验收**：MP2 频道显示 `MP2/2ch` 且有声（与 t119 协同）；AAC/AVC 仍平台/硬解、音画同步；选择音轨立即生效并持久；换台后按策略正确；无音轨不显示 0；无崩溃；信息条不挤压节目名（右侧 tech 列）。

---

### F5 快速返回上一台 / 最近观看（P0 上一台 / P1 最近列表）

#### F5a 上一台（P0）

| 项 | 规格 |
|---|---|
| 入口 | `KEYCODE_LAST_CHANNEL`（优先）/`KEYCODE_MEDIA_PREVIOUS`（备用）；F1 可改；设置内也可点“上一台” |
| 行为 | 直播下在“当前频道 ↔ 上一个频道”切换；只在**成功开始播放后**更新历史；换线路/源刷新/回看不算新频道 |
| 无历史 | 非阻断提示“暂无上一台”，不改变当前 |
| 显示 | 切换后显示信息条 5s；不打开抽屉 |
| 持久化 | 重启后上一台仍可用（`history.previous_url` + title） |

**数据**：`history.previous_url`, `history.previous_title`；复用 `Prefs.getLastChannel`/`setLastChannel` 的扩展或新 `RecentChannelStore`。

**代码**：`PlayerActivity` `Prefs.setLastChannel` L730、`playGroupAuto`/`setOnChannelStep` L971–1003、`dispatchKeyEvent` L206–328；`Prefs`。

#### F5b 最近观看列表（P1）

| 项 | 规格 |
|---|---|
| 入口 | 设置 → `最近观看`；可选 `KEYCODE_LAST_CHANNEL` 长按（P2） |
| 列表 | 最近 N 个频道（默认 10，可选 5/10/20）；行：台标/全局编号/频道名/最近时间；当前播放标记；去重（同 URL 只保留最近） |
| 键 | 上下移动；OK 切台并关闭；Back 关闭；列表为空显示“暂无观看记录” |
| 缺失频道 | 源刷新后不存在的条目灰显/提示，不强行换台；可清理 |
| 清空 | `清空最近观看` 二次确认（0–2dp 方角、无蓝框） |
| 持久化 | 重启保留；最多 20 条；不记录回看为直播频道 |

**数据**：`history.recent_json`：`[{url,title,channelNumber,lastWatchedMs}]`；`RecentChannelStore`（Prefs JSON，20 条；>20 可换 Room，非必须）。

**代码**：新增 `RecentChannelsDialog.kt`（复用 `QualitySelectorDialog` 的列表/焦点模式）；`PlayerActivity.playChannel/playGroupAuto` 更新；`SettingsDrawer` 入口；`Prefs`。

**验收**：上一台可在任意两个不同台间翻转；换线路不算；最近列表顺序/去重/清空/重启保留；OK/Back 正常；缺失频道不崩溃；不改变 TIV 开关/面板路径。

---

### F6 台标缓存管理（P1）

**目标**：让用户能看/清/刷新台标缓存，减少“换源后旧台标/缓存膨胀”；不改变现有 LRU96/磁盘策略与透明台标渲染。

**入口**：设置 → `台标与缓存`。

**交互规格**：

| 项 | 选项/显示 | 行为 |
|---|---|---|
| 预取策略 | `当前组前60`（默认）/`全部频道`/`仅可视` | 在播放列表加载/切换组时执行；`仅可视` 依赖 RecyclerView/Lazy 可视项（现有抽屉 Compose） |
| 自动预取 | 开（默认）/关 | 开时启动/换组后台预取；关时不主动下载 |
| 缓存用量 | 内存 N 项 / 磁盘 X MB（LruCache+磁盘目录统计） | 只读展示；无阻塞 |
| 刷新当前组台标 | 按钮 | 失效当前组 URL 的内存/磁盘条目并重新预取；不重启播放 |
| 清理缓存 | 按钮（二次确认） | 清空内存 LRU + `logo_cache_v1` 磁盘；不影响频道/EPG/播放 |
| 最近刷新 | 时间戳 | 成功/失败显示 |

**Android TV**：设置页只做数据/文件操作，后台 `Dispatchers.IO`；进度“清理中…”禁重复；不影响 UI 帧；`LogoLoader` 扩展 `clearMemory()`, `clearDisk(context)`, `stats(context)`, `invalidate(url)`, `prefetch(...)`。

**数据**：`logo.prefetch_policy`（默认 `current60`）、`logo.auto_prefetch`（true）、`logo.last_refresh`；磁盘 `cacheDir/logo_cache_v1`（现有 `LogoLoader.cacheDir` L91–95）；LRU `MAX_ENTRIES=96`（L49）。

**代码**：`LogoLoader.kt` L49/82/177/274/303；`PlayerActivity` 预取 L659–664、`buildPrefetchLogoUrls` L1223–1245（当前 `maxUrls=220`/`head=120`/`take(60)`）；`SettingsDrawer`。

**验收**：显示用量准确（±1 文件）；刷新后新台标可见；清理后内存/磁盘归零且再访问可重下；不崩/不卡；透明台标不裁；预取策略生效。

---

### F7 设置菜单分组 / TiviMate 式结构（P0 骨架 / P1 完整）

**目标**：把现有 4 项设置扩展为“组 → 子页”的 TiviMate 式结构，值回显、恢复默认、Back/左层级一致；**默认焦点仍为“源配置”**，TIV-11 原有四项保留可达。

**根结构（建议）**：

| 分组 | 子项 |
|---|---|
| 基础（保留 TIV-11） | 源配置、更新EPG、源选择（多线路时）、检查更新 |
| 播放与显示 | 启动恢复上次频道（开/关，默认开）、信息条时长（5s 默认）、节目点击行为（直接回看/确认后回看）、播放信息（只读） |
| 遥控器与操作 | F1 的按键行为/自定义（P1）/恢复默认 |
| 数据与更新 | F3 的启动/周期/立即刷新/最近成功 |
| 音频与字幕 | F4 的音轨策略/语言/当前音轨 |
| 台标与缓存 | F6 的预取/用量/刷新/清理 |

**交互规格**：
- 根页上下移动；OK/右进入子页；左/Back 回父页；根左/Back 关闭；子页值行右侧显示当前值/勾选；选择项 OK 保存并回父页，Back 取消未提交选择（参照 `UI_UX_DESIGN` §5）。
- 每次打开默认焦点：首次“源配置”，之后上次使用的根项；`settings.last_group` 持久化（若允许恢复）。
- 选择弹窗/确认弹窗：0–2dp 方角、无蓝框/阴影；沿用 v4 flat。
- 所有偏好落 `SettingsRepository`（Prefs 类型化封装）；缺省/迁移/恢复默认集中管理。

**Android TV**：Compose TV `SettingsDrawer` 内部维护 `SettingsPage` 栈（Root/Subpage），`FocusRequester` 随页面切换；不要用 Leanback `PreferenceFragment`（项目无该栈且样式不一致）。用 `Dialog`/`AnimatedVisibility`；避免每帧重组。

**存储**：`SettingsRepository` over `SharedPreferences`（现有 `Prefs`），新增 `settings.version`、`settings.last_group` 与各功能 key；列表用 JSON；不需要 Room（除 P2 量大）。

**代码**：`SettingsDrawer.kt` L39–170（扩展根/子页/`SettingsRow`）；`PlayerActivity` 的 `SettingsDrawer(...)` 调用与回调；`Prefs`/新增 `SettingsRepository.kt`。保留 TIV-11 的 `源配置/更新EPG/源选择/检查更新` 标签/顺序；新增项可作为分组，若严格基线要求，新增内容放入“高级设置”分组不影响原四项。

**验收**：分组导航/Back/左符合预期；默认焦点“源配置”；值回显与修改立即生效；恢复默认全量；原有四项均可达；无横向溢出/无蓝框；重启保持。

---

## 4. 设置键位/存储总表（开发可直接照做）

| key | 类型 | 默认 | 归属 | 读写落点 |
|---|---|---|---|---|
| `settings.version` | Int | 1 | F7 | `SettingsRepository` |
| `settings.last_group` | String | `source` | F7 | `SettingsRepository` |
| `remote.updown` | String | `switch` | F1 | `PlayerActivity.dispatchKeyEvent` |
| `remote.ok` | String | `info` | F1 | 同上 |
| `remote.back` | String | `standard` | F1 | 同上 |
| `remote.digit` | String | `number_jump` | F1/F2 | 同上 |
| `remote.channel` | String | `switch` | F1 | 同上 |
| `remote.last` | String | `previous_channel` | F1/F5a | 同上 |
| `number.timeout_ms` | Int | `1200` | F2 | `PlayerActivity.numberJob` / `submitNumber` |
| `auto.update_on_startup` | Bool | `true` | F3 | `BackgroundSourceUpdater`/`IptvApplication` |
| `auto.playlist.mode` | String | `startup_6h` | F3 | `BackgroundSourceUpdater` |
| `auto.epg.mode` | String | `startup_6h` | F3 | `BackgroundSourceUpdater`/PlayerActivity EPG loop |
| `auto.update_wifi_only` | Bool | `false` | F3(P2) | 调度约束 |
| `update.last_playlist_time` | Long | `0` | F3 | `BackgroundSourceUpdater` |
| `update.last_epg_time` | Long | 现有 `last_epg_update_time` | F3 | 现有 Prefs |
| `audio.policy` | String | `auto` | F4 | `PlayerActivity`/`AudioTrackDialog` |
| `audio.preferred_language` | String | `auto` | F4 | 同上 |
| `audio.last_track_by_channel_json` | String | `{}` | F4(P2) | 同上 |
| `history.previous_url` | String | `""` | F5a | `PlayerActivity`/`RecentChannelStore` |
| `history.recent_json` | String | `[]` | F5b | `RecentChannelStore` |
| `logo.prefetch_policy` | String | `current60` | F6 | `PlayerActivity`/`LogoLoader` |
| `logo.auto_prefetch` | Bool | `true` | F6 | 同上 |
| `logo.last_refresh` | Long | `0` | F6 | 同上 |

> 仍用现有 `Prefs`（`iptv_prefs`）以免迁移风险；JSON 只存小列表（≤20）。P2 若最近观看/每频道音轨规模变大再评估 Room/DataStore；本期不引入数据库依赖。

---

## 5. 分批落地建议

### P0（建议 4–6 个工作日）
1. **F7a** `SettingsRepository` + `SettingsDrawer` 根分组骨架 + 值行/选择弹窗 + 恢复默认。
2. **F2** `NumberJumpOverlay`（替换 PlayerActivity L189）+ `number.timeout_ms` + 无效内联。
3. **F4** `onTracksChanged` 收集 `audioTracks` + `ChannelInfoBanner` 音频摘要 + `AudioTrackDialog` + `Prefs` 策略。
4. **F5a** 上一台键 + `history.previous_*` + 成功换台更新。
5. **F3** `BackgroundSourceUpdater` 周期/启动/退避 + `PlayerActivity` EPG 循环改 Prefs + `Settings` 数据与更新页。
6. **F1a** 上/下/OK/Back/数字超时基本开关（不含自定义捕获）。

### P1（建议 3–5 个工作日）
1. **F5b** `RecentChannelStore` + `RecentChannelsDialog` + 设置入口。
2. **F1b** 自定义按键捕获/冲突检测/恢复默认。
3. **F6** `LogoLoader` clear/stats/invalidate + 预取策略 + 设置页。
4. **F7b** 完整分组/播放信息页/上次分组恢复。

### P2（按需）
- F4 每频道音轨覆盖；F3 更新时段/Wi-Fi 限制；F6 按源/尺寸策略；TIV-13 信息条“接下来”行；Guide/收藏夹直达；启动播放开关等。

---

## 6. 横切约束与验收

### 6.1 不回归红线（所有功能）
- 不改 TIV-01–24 默认键路径/焦点/面板层级；新设置只改“可配置的默认行为”，默认值必须等于 TIV 原版。
- UI 保持 v4 flat：行/面板 0dp、进度方头、无蓝框焦点、无硬框/阴影/模糊；设置面板 300dp、右侧展开。
- 不改零拷贝基座、灰屏修复、LoadControl 1000/6000/300/1000、频道键 166/167、SR、回看 owner B、EPG、音频诊断、稳定 key、品牌/app name。
- 不引入 TiviMate 资源/商标/截图/文案；仅通用交互逻辑；图标用现有品牌/系统图标。
- 所有偏好默认值可回退；升级旧版本无迁移崩溃（新 key 缺省读取）。
- 更新/预取/清理均在后台线程，不阻塞主线程/不 rebind 列表/不逐帧写状态；不新增每帧工作。

### 6.2 验收矩阵（摘要）

| 功能 | 必测项 |
|---|---|
| F1 | 6 项行为默认=原版；改值重启保留；面板内键不被吞；恢复默认；无重复触发 |
| F2 | 1080p 可见/对比；`112` 完整显示；1.2s/OK/Back/方向/Menu；无效提示不切台；不误触面板 |
| F3 | 启动/6h/12h/24h/手动；失败 5min 退避；缓存不清；播放不断；最近成功时间；无冷启回退 |
| F4 | 当前音轨语言/编码/声道显示；多音轨选择立即生效；持久；MP2 有声/AAC 硬解/音画同步；无音轨显示 — |
| F5 | 上一台双台翻转；线路/刷新不算；最近 N 顺序/去重/清空/重启；缺失频道不崩 |
| F6 | 用量显示；刷新/清理生效；不崩不卡；透明台标不裁；预取策略生效 |
| F7 | 组/子页/Back/左；默认焦点源配置；值回显；恢复默认；原四项可达；无蓝框/横向溢出 |

### 6.3 测试建议
- JVM 单测：`SettingsRepository` 默认/迁移/恢复默认；`RecentChannelStore` 去重/上限/缺失；`NumberInputState` 位上限/前导0/超时；`AudioTrackPicker` 语言匹配；`UpdateSchedule` 最近成功+退避。
- Compose UI 测试：Settings 页面导航/Back/焦点；Number OSD 状态；AudioTrackDialog；RecentDialog。
- 真机（192.168.2.245 API28 1080p）：键矩阵/数字 112/MP2 音轨/AAC 硬解/自动更新开关/台标清理/重启持久；`gfxinfo`/PSS/冷启对照 CI10093/CI10096；不崩/不灰。

---

## 7. 文件清单（本次交付）

| 文件 | 状态 | 说明 |
|---|---|---|
| `docs/FEATURE_ROADMAP.md` | **新增** | 本路线图（功能规格/优先级/存储/落点/验收） |
| `docs/PRD.md` / `docs/TIVIMINI_INTERACTION_SPEC.md` / `docs/UI_UX_DESIGN.md` | 只读参考 | 需求/原版交互/既有交互设计来源 |
| `android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt` 等 | 未改 | 仅作为落点引用；**本任务不改代码** |

**边界**：未改任何代码、未运行 Gradle/CI/ADB、未操作 TV。后续实现时按 P0→P1→P2 切片，每片保持 TIV 默认、UI v4 flat、性能零回退，并由 acceptance2 真机验证。
