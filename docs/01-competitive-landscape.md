# 01 — 竞品与开源生态调研

> 调研时间：2026-11。目的：确认「Rust 重写 TiviMate 类软件」这条路是否已被走过、走成了什么样、我们该抄什么、该避开什么。

---

## 1. 结论先行

**这条赛道有人走，但没有人走到终点。**

- 所有现存的 Rust IPTV 播放器**几乎都是桌面端**（Windows/macOS/Linux），用的是 **Tauri + Web 前端**。
- **Android TV 上的 Rust IPTV 播放器几乎是空白**：只有 `Limpido`（闭源商业）和 `nyx-player`（Tauri 技术栈，Android 部分）。
- **纯 Rust UI 的 IPTV 播放器只有一个**：`Cathode`（Dioxus + WASM，4⭐，刚起步）。
- **没有一个人做到「TiviMate 功能对齐」**：最接近的是 `ynoTV`（455⭐，AGPL-3.0，Windows 优先）。

→ **我们的定位很清晰**：Android TV + 纯 Rust UI + TiviMate 功能对齐。这个组合目前无人占据。

---

## 2. 现有项目详细对比

### 2.1 Rust 技术栈项目

| 项目 | 星空 | UI | 引擎 | 平台 | 许可 | 评价 |
|---|---|---|---|---|---|---|
| **ynoTV** | 455 | Tauri v2 + React | mpv (嵌入) | Windows 为主 | AGPL-3.0 | **功能最全**，但是 WebView UI 且偏桌面 |
| **better-iptv** | 60 | Tauri + React | mpv | Win/mac/Linux | GPL-2.0 | 15 万频道性能好；家长控制、Profile 做得好 |
| **dopeIPTV** | — | 原生(闭源) | mpv | 三平台 | 商业 | 9 宫格多画面、时移、录制、Chromecast；27 语言 |
| **Cathode** | 4 | **Dioxus 0.7 (Rust→WASM)** | mpv Render API | 三平台 | GPL-3.0 | **架构最接近我们的目标**，但项目很年轻 |
| **Cinette** | 8 | Tauri v2 + React | libmpv (嵌入) | macOS/Linux | GPL-3.0 | Rust core `mvp-core` 跨端共享，iOS/Android 规划中 |
| **nyx-player** | — | Tauri 2 + React | mpv / 原生 libmpv | Win/Android/AndroidTV | GPL-3.0 | 一套代码三端，Android TV 已可用 |
| **Open TV** | — | Tauri | — | 桌面 | — | 搜索优先、刻意砍掉 EPG 的极简派 |
| **Limpido** | — | Svelte | 自研 Rust 解复用 + 平台硬解 | 全平台含 AndroidTV | 商业 | **架构最有参考价值**（见 §4） |
| **proton-stream** | — | Compose (Android) | libmpv | Android | AGPL | Rust core + UniFFI → Kotlin 的分层参考 |

### 2.2 非 Rust 但值得对照的

| 项目 | 关系 | 借鉴点 |
|---|---|---|
| **OwnTV** | Kotlin + Compose TV，双引擎（mpv + ExoPlayer） | **双引擎自动降级**的具体做法 |
| **VortX** | Swift/Kotlin 原生 | Apple/Android 双端原生播放栈 |
| **dopeIPTV** | 商业 | 多画面、时移、录制的功能设计 |

---

## 3. 从调研中提炼的六条硬结论

### 结论 1 — mpv 的 OpenGL 管线在 Android TV 上不可靠

多份独立报告指向同一结论：

> Amlogic 盒子 + **Mali-G31** GPU 上，mpv 的 OpenGL 管线灾难性失败：
> `mediacodec-copy` 返回四分之一分辨率帧 → 绿屏（mpv-android#1088）；
> 零拷贝 MediaCodec 尝试要么启动失败要么直接重启设备；
> mpv 的 GL 管线什么都不渲染，只有 `gpu-dumb-mode` 能出图（mpv-android#292）。

**根因**：Mali 驱动缺失 mpv 所需的 OpenGL 扩展；而 Android 的 MediaCodec API 本身有分辨率上限和不稳定的零拷贝行为。

**对策**：
1. Android TV 主引擎必须是 **Media3/ExoPlayer**，解码到 **SurfaceView**。
2. mpv 仅作**兜底**（Dolby Vision Profile 5 等 ExoPlayer 解不了的），并接受其在此平台上的局限。
3. 放弃「mpv Vulkan + wgpu」路线——Android 上 Vulkan 支持覆盖率不够，是 not-a-starter。

### 结论 2 — UI 层必须处理 SurfaceFlinger 透明区

**问题**：SurfaceView 在全屏时，`SurfaceFlinger` 会把该区域视为「空」，直接丢掉覆盖其上的透明 UI 层 → **播放器控制条消失**。

**对策**：覆写 `View.gatherTransparentRegion()`，强制 SurfaceFlinger 认可覆盖层。

> 但这是 Android **View** 体系的概念。Slint 走 `android-activity` 的原生窗口路径，需要另一套方案 —— 这是本项目 **最大的技术风险点**，详见 `06-android-tv.md`。

### 结论 3 — Rust + Slint 在 Android TV 上已被验证可行

`damienmiheev/rusttv` 项目做了完整的 Phase 1b spike（2026-10-23），实测结论：

| 问题 | 结论 |
|---|---|
| Slint 能否在 Android TV 渲染 | ✅ 可以，Android 后端固定用 Skia |
| D-pad 能否到达 Slint | ✅ 方向键映射为 `Key.*Arrow`，OK 为 `Key.Return`；BACK 需自行消费 |
| 同 APK 内能否共存 Kotlin 桥 | ✅ 用 Kotlin `NativeActivity` 子类，通过 Activity 的 classloader 加载应用类 |
| 空闲内存 / 启动 | 模拟器粗测：Idle PSS **43.9 MB** vs 原生 60.9 MB；冷启动中位 **189 ms** vs 318 ms |
| APK 体积 | 调优后 **11.5 MB**（native lib 10.4 MB，**Skia 是 ~10 MB 的地板**） |

> 注意：模拟器数据不可对外发布，需在真机上重测。**但 Skia 是体积地板**这一结论是结构性的。

### 结论 4 — Rust 生态的 IPTV 组件已经相当完整

crates.io 实测（2026-11）：

| 需求 | 现成 crate | 版本 / 下载量 | 评价 |
|---|---|---|---|
| mpv 绑定 | `libmpv2` | v7.0.1 / 12 万 | 维护最活跃，有 `render` feature |
| mpv 绑定（新） | `rsmpv` | v0.2.1 / 2.5k | ISC 授权，含 `render` + `stream-cb` |
| mpv 绑定（老） | `libmpv-sirno` | v2.0.2 / 3.3 万 | 事实标准但 2022 年后未更新 |
| HLS 播放列表 | `quick-m3u8` | v0.8.0 / 2.3 万 | RFC 8216 解析 |
| XMLTV | `xmltv` | v2.1.1 / 1.4 万 | serde 支持 |
| M3U 解析 | `crispy-m3u` | v0.1.1 / 522 | 高性能，配套 `crispy-*` 全家桶 |
| Xtream 客户端 | `crispy-xtream` | v0.1.1 / 39 | 异步 API 客户端 |
| 纯 Rust fMP4 | `oxideav-mp4` | v0.0.11 / 4.3k | OxideAV 项目，Rust 音视频栈 |
| 纯 Rust MPEG-TS | `rff-format-ts` | v0.4.6 / 3.0k | Remade-With-Rust |
| 任意容器互转 | `transmux` | v0.30 / 2.6 万 | 但它是**打包器**，不是播放器 |
| SQLite | `rusqlite` | v0.42 / 15 万 | bundled 特性可静态链接 |
| 哈希 | `xxhash-rust` | v0.8.19 | 用于稳定 ID |
| GUI | `slint` | **v1.18.1** / 248 万 | 当前最新 |

**判断**：`crispy-*` 系列（`crispy-iptv-types` / `crispy-m3u` / `crispy-xmltv` / `crispy-xtream`）正好覆盖我们要的协议层，值得先做 **PoC 对比**再决定自研还是采用。

### 结论 5 — 供应商侧的现实约束

- **单连接限制**：不少供应商按「线路数」计费，把最近打开的流算作未释放约 30 秒。快速换台会撞上 → 报「provider refused the stream」。应对：换台节流 + 明确的错误指引 + 可切换 UA（TiviMate/VLC/自定义）。
- **UA 拦截**：部分供应商只放行已知播放器 UA。**必须内置 UA 伪装**，这甚至是一个「照着 TiviMate 配置」的功能点。
- **稳定 ID**：收藏/历史必须用 `xxhash3(source_id + provider_stream_id)`，否则播放列表重同步后顺序变化会导致收藏全乱。`cathode` 就是这么做的。

### 结论 6 — TiviMate 的体验债正是我们的机会

从 TiviMate 用户指南里能直接读出的痛点：

> 「默认值会在**应用启动时重新下载 EPG**，这就是启动慢的原因。把 `Update on app start` 关掉。」

这条被写进官方第三方指南 —— 说明它广为人知且招人烦。我们的默认行为应该反过来：

- **启动时永不阻塞等待 EPG**（后台增量 + 本地优先）
- EPG 网格先渲染本地缓存，缺失部分显示占位
- 播放列表与 EPG **独立同步周期**（建议 24h / 6h）

---

## 4. Limpido 的架构为什么值得抄

Limpido（商业，闭源）公开的技术要点，是目前最接近「纯 Rust IPTV 播放器」的实践：

| 平台 | 谁读流 | 谁解码 | 体积 |
|---|---|---|---|
| iPhone/iPad | **Limpido (Rust)** | Apple 硬件解码器，直通 | **17 MB** |
| Android | Limpido 的传输层 | Media3 | 55 MB |
| Android TV / Fire TV | **Limpido (Rust)，同一 core** | 面板硬解，经 Media3 | **25 MB** |
| Windows | mpv | GPU 经 mpv | 71 MB |

关键设计：

1. **MPEG-TS 与 Matroska 在 Rust 里自己解复用**，不捆绑 FFmpeg。
2. 解复用后的**压缩样本直接喂硬件解码器**（Apple 用 `AVSampleBufferDisplayLayer`，Android 用 Media3）。
3. Android TV **不需要 WebView**，所以能压到 25 MB。

**对我们的启示**：
- 「Rust 负责传输与解复用，平台负责解码」是让 Android TV 端做到 25MB 级别的唯一路径。
- 但这条路工程量巨大（要自己实现 TS PAT/PMT/PES 解析、PCR 跟踪、HLS 调度、fMP4 解析）。**v1 不宜冒进**。
- **折中方案**：v1 用 Media3 全权负责传输+解码（等于把这两层交给 Google），把 Rust 的价值放在 UI/EPG/数据层；v2 再评估自研传输层。

---

## 5. 我们与现有项目的差异化定位

```
                  纯 Rust UI
                      ▲
                      │
    iqtvlite ●────────┼─────────►  ← 空白地带
   (Android TV)       │
                      │
                      ▼
              Tauri + Web 前端
                      ▲
                      │
    ynoTV ●           │           ● better-iptv
   (桌面)             │          (桌面)
                      │
                      ▼
                   桌面端
```

**四个轴上的取舍**：

| 轴 | 桌面 Rust 项目 | iqtvlite |
|---|---|---|
| 平台 | 桌面 | **Android TV**（真实 TV 遥控器 + 客厅场景） |
| UI | WebView（重、启动慢、焦点差） | **Slint 原生**（轻、焦点可控） |
| 功能 | 参差不齐 | **对齐 TiviMate Premium 全功能** |
| 引擎 | 单一 mpv | **Media3 优先 + mpv 兜底** |

---

## 6. 可直接借鉴的具体设计（来自各项目源码）

| 来源 | 设计 | 我们的采纳 |
|---|---|---|
| `cathode` | 稳定 ID = `xxhash3(source + provider_id)` | ✅ 采纳 |
| `cathode` | mpv **Render API** 嵌 GL surface，不 spawn 进程、不用 `--wid` | ✅ 采纳（mpv 后端） |
| `cathode` | 同一套 `Stream`/`Category`/`Programme` 类型贯穿前后端，无序列化边界 | ✅ 采纳（Rust core 直供 Slint） |
| `better-iptv` | 只渲染可见行的虚拟化网格 | ✅ 采纳（15 万频道） |
| `better-iptv` | 每 Provider 一个 Profile，各带收藏 | ✅ 采纳 |
| `ynoTV` | 自动流故障切换 + 重复频道聚成 failover 组 + Always Play Primary | ✅ 采纳 |
| `ynoTV` | 直播性能徽章（分辨率/FPS/码率）+ 一键禁用死频道 | ✅ 采纳 |
| `owntv` | 双引擎（mpv 兼容 + ExoPlayer 快速直播）+ 自动回退 + 每频道手动覆盖 | ✅ **完全采纳** |
| `dopeiptv` | 9 宫格多画面、每窗口独立时移与字幕 | 🔲 v2 |
| `limpido` | Rust 自研解复用 → 平台硬解 | 🔲 v2（架构预留） |
| `rusttv` | Slint + Kotlin NativeActivity 桥接方案 | ✅ 采纳 |
| `rusttv` | 焦点自持（D-pad 映射确认，Slint 无内置空间导航） | ✅ 采纳 |

---

## 7. 风险清单（调研发现）

| 风险 | 等级 | 缓解 |
|---|---|---|
| Slint UI 与 SurfaceView 视频层的**合成**问题 | 🔴 高 | 早期 spike 验证；备选：把 Rust UI 作为 View 嵌入（`rust-mobile/android-view`）、或改用 TextureView |
| libmpv AAR 在 Android TV 上的实际表现 | 🟠 中高 | 仅作兜底；主路径不依赖它；准备 `hwdec=mediacodec-copy` / `hwdec=no` 两套配置 |
| libmpv AAR **GPL 分发义务**（需附源码） | 🟠 中 | 优先用上游预编译 AAR；CI 中固定版本号并生成 `THIRD_PARTY_NOTICES` 与源码链接 |
| Slint **焦点导航**需自研，TV 遥控器手感难做对 | 🟠 中 | 参考 `rusttv` 的 FocusScope 方案；大量手工真机测试 |
| EPG 数据量（10 万节目级）下的滚动性能 | 🟡 中低 | FTS5 + 分页 + 虚拟列表 |
| 供应商限制导致的「起播失败」无法根治 | 🟡 中低 | 做成产品功能：UA 切换、连接节流、错误指引、故障切换组 |
| Slint Skia 是 APK 体积地板（~10 MB） | 🟢 低 | 接受；arm64 单 ABI 分包 |

---

## 8. 参考来源

- github.com/tbeezy/ynotv
- github.com/mewset/better-iptv · better-iptv.vercel.app
- github.com/kaiserbh/cathode
- github.com/MaxMB15/Cinette · github.com/MaxMB15/MaxVideoPlayer
- github.com/kunai974/nyx-player
- github.com/ahXN00/OwnTV
- github.com/VortXTV/VortX
- github.com/moabualruz/crispy-{iptv-types,m3u,xmltv,xtream}
- github.com/jarnedemeulemeester/libmpv-android
- github.com/damienmiheev/rusttv（RESEARCH.md / BENCHMARK_PLAN.md）
- github.com/OxideAV/oxideav-mp4 · github.com/Remade-With-Rust/remade_ffmpeg_rs
- crates.io: `slint` `libmpv2` `rsmpv` `rusqlite` `quick-m3u8` `xmltv` `transmux`
- rusttv 转述的 mpv-android issue #1088 / #292
- dev.to/serbyte — 「Integrating MPV Video Player into Rust-Based Cinebox GUI…」（Android TV Mali-G31 复盘）
- iptv.dope.rs · limpid.app
