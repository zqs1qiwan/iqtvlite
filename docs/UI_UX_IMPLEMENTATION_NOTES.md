# Android TV IPTV UI/UX 重构实施说明（t4）

日期：2026-11-18。实施者：developer。范围：主 Compose 工程 `:app`，按 [UI_UX_DESIGN.md](UI_UX_DESIGN.md) 与 [UI_UX_ACCEPTANCE.md](UI_UX_ACCEPTANCE.md) 的 A+B 切片实施。

本文件记录实施改动、对既有核心能力的理由与仍未验证项。它是实施记录，不是验收通过证明；设备/遥控器/首帧/视觉证据由独立验收任务补充。

## 1. 改动清单

新增：
- `TvTokens.kt`：TV 颜色/间距/字号 token，`computePanelWidth = min(360dp, W/3)`。
- `AppSettings.kt`：A09 基础设置持久化（启动恢复、节目点击、列表/EPG 刷新策略、遥控器映射及冲突/禁用键规则）。
- `ChannelPanel.kt`：单容器左侧分层导航（Channels/Groups/Programs/Dates），逐层 Back、稳定焦点锚点。
- `NumberInputOverlay.kt`：数字缓冲右上角反馈控制器与浮层。
- `PlaybackFailurePanel.kt`：恢复耗尽后的可操作失败终态与关闭后状态条。

修改：
- `ChannelGrouper.kt`：全局展示编号（`numberedDisplayChannels`/`globalNumberByUrl`/分组过滤保留编号）。
- `EpgModels.kt`：`nextProgram`、`hasProgramsFor` 支持信息条“接下来”和 EPG 缺数据判定。
- `ChannelInfoBanner.kt`：重写为“正在播放/接下来”三行信息条、全局编号、EPG 三态占位、回看进度。
- `LogoLoader.kt`：成功台标取消圆角裁剪（`ContentScale.Fit`），无台标用中性占位。
- `PlayerActivity.kt`：接入新面板/设置/数字浮层/失败终态；统一按键与 Back 归属；恢复计数与 12 秒超时；上一频道；按设置路由按键。
- `SettingsDrawer.kt`：重写为独立右侧分层设置页（根 + 节目选择/刷新策略/遥控器/源选择/播放信息），OK 保存即返回、Back 取消。
- `MainActivity.kt`：配置页改为状态机（编辑/加载/错误/空），验证成功后才替换源/缓存，错误卡片含重试/修改地址，扫码推送复用同一状态机。
- `SplashActivity.kt`：按“恢复上次频道”开关路由；有缓存无历史也可进入播放器。
- 删除 `PlayerDrawer.kt`：由 `ChannelPanel.kt` 取代其单容器职责，避免两套导航并存。

保持了既有实现：M3U 解析（UA/重定向/gzip 处理在 `M3uParser`/`EpgRepository`/`NetUtil`，未改动）、ChannelGrouper 合并与多线路、Playlist/EPG 缓存、CatchupHelper 回看 URL 构造、Media3 ExoPlayer 播放路径。

## 2. 会改变既有核心能力的地方与理由

1. **配置保存时机**：原实现先写 Prefs 再下载，失败仅 Toast。改为下载+解析成功后才写 Prefs/缓存（A01/A11：失败必须保留旧源与输入）。理由：避免坏地址覆盖可用配置。
2. **抽屉形态**：原 `PlayerDrawer` 为多列可扩宽（常态约 633dp）。改为单容器分层替换（`ChannelPanel`），宽度 min(360dp, W/3)。理由：设计/勘察 P0“侧栏≤约1/3屏宽、视频始终可见”。
3. **频道编号**：统一为合并后全局编号；分组过滤、信息条、数字跳台共用。原抽屉按过滤后本地 index 重编号，已修正。
4. **返回归属**：面板/设置/失败层内部用 Compose BackHandler 逐层返回；Activity 仅在没有浮层时处理信息条/回看/退出双 Back。理由：避免一次按键触发多层。
5. **播放错误处理**：改为最多 3 次恢复预算、单次准备 12 秒超时、800ms 阶段退避，耗尽进入可操作失败面板（重试/换台/源配置）。理由：原实现失败后只隐藏 loading。
6. **遥控器映射**：默认映射沿用设计第 3 节；Menu 固定为设置安全入口；禁止 Back/Home/Power/音量/数字/OK/Menu 分配，冲突拒绝并保留原映射；仅在 Live 无面板生效。

## 3. 未验证 / 局限（诚实标注）

- 未在真机/模拟器运行：当前工作区无可启动 AVD，adb 目标离线。首帧、遥控器焦点、扫码真实提交、断网缓存、回看 seek、720p/1080p 观看距离均**未做设备验收**。
- 未做视觉测量：当前会话无图像读取能力，未目视核对截图；颜色/尺寸依据设计 token 与源码。
- 遥控器按键捕获的某些厂商扩展键、Media3 `UnstableApi`、`java.time` 在 minSdk24 的 lint 提示为既有工程模式，构建不阻断。
- 回看失败终态复用通用失败面板（重试/换台/源配置），未单独提供“返回直播”按钮（可从设置“返回直播”）。
- 分组/日期边界滚动、长列表性能与完整焦点回归需设备复核。
