# iqtvlite — 项目总览

> 目标：在 Android TV / Google TV 上做一款 **功能对齐 TiviMate**、但 **更丝滑、更轻量、更稳定** 的 IPTV 播放器。
> 技术底座：**全 Rust**（Slint 原生 UI + Rust core），播放层用 **Media3 优先 / mpv 兜底** 的双引擎。

---

## 1. 问题定义

TiviMate 是目前 Android TV 上体验最好的 IPTV 播放器，EPG 网格是它的护城河。但它有明确的问题：

| 问题 | 表现 | iqtvlite 的对策 |
|---|---|---|
| **启动慢** | 启动时同步拉取全量 EPG，冷启动动辄 3–5 秒 | EPG 与播放列表解耦；后台增量同步；本地 SQLite 优先 |
| **体积大 / 内存高** | APK 数十 MB，运行时内存占用高（Compose + ART + WebView 栈） | 纯 Rust + Skia，无 WebView、无 JS 运行时；目标 APK ≤ 15 MB |
| **换台不够快** | 受 ExoPlayer 缓冲策略与供应商单连接限制拖累 | Media3 低延迟调优 + **多引擎预热 + 自动故障切换** + 连接复用 |
| **稳定性差** | 供应商限流、UA 拦截、时移失败导致的报错无指引 | 播放器内建 **故障自愈矩阵**，每类错误给出可执行的下一步 |
| **闭源不可控** | 无法修复供应商侧兼容问题 | 开源，协议层可被社区打补丁 |

---

## 2. 目标与非目标

### 2.1 必须做到（v1.0 对标 TiviMate Premium）

- [ ] **多播放列表**：M3U/M3U+（URL 或本地文件）、Xtream Codes，多源并存、可切换、可合并
- [ ] **EPG 网格**：XMLTV 解析、多日视图、时间轴滚动、节目进度条、节目详情弹层、提醒
- [ ] **频道组织**：分组、收藏（多个收藏夹）、隐藏、重命名、排序、自定义分组
- [ ] **播放**：直播 + 点播 + 剧集（季/集）、断点续播、字幕/音轨选择、倍速
- [ ] **Catch-up / 时移**：跳转回过去节目（供应商支持时）
- [ ] **DVR 录制**：即时录制 + 按节目表排期录制
- [ ] **多画面**：2×2 至 3×3 同时观看
- [ ] **家长控制**：PIN 锁定频道/分组
- [ ] **备份还原**：配置一键导出/导入
- [ ] **User-Agent 伪装**：可切换 TiviMate / VLC / 自定义 UA，应对供应商拦截

### 2.2 明确不做（v1.0）

- ❌ 内置任何频道/订阅/片源（纯播放器，BYO playlist）
- ❌ 账号体系、遥测、广告、云同步（**零遥测**是硬约束）
- ❌ Stalker / Ministra 门户（v2 评估，协议复杂度高、收益低）
- ❌ 转码 / 服务端
- ❌ iOS / 桌面端（架构预留，但不实现）

### 2.3 差异化卖点

1. **Rust 全栈** → 启动快、内存低、无 WebView、崩溃率天然低于 JVM 应用
2. **双引擎 + 自动降级** → ExoPlayer 覆盖 99% 直播起播，mpv 兜底 Dolby Vision 等长尾
3. **故障自愈** → 把「供应商限流 / UA 拦截 / EPG 错配」这些常见坑做成产品功能而非报错
4. **完全离线可用的数据层** → 断网时仍能浏览频道列表与已缓存 EPG

---

## 3. 成功指标

| 指标 | 目标 | 测量方式 |
|---|---|---|
| 冷启动到可浏览 | < 900 ms（中端 Android TV，warm+cold 各测） | `am start -W` + 内部埋点 |
| 直播起播（P50） | < 1.2 s（ExoPlayer，HLS） | 埋点：`engine_start → first_frame` |
| 直播起播（P95） | < 2.5 s | 同上 |
| 换台间隔（冷→热） | < 800 ms | 上一路 `first_frame` → 下一路 `first_frame` |
| APK 体积 | ≤ 15 MB（arm64-only split） | CI 产物统计 |
| 空闲内存 (PSS) | ≤ 60 MB | `dumpsys meminfo` |
| EPG 网格滚动 | 60 fps，不掉帧 | Perfetto / `gfxinfo` |
| 15 万频道列表 | 首屏 < 200 ms，滚动 60 fps | 虚拟列表基准测试 |
| 崩溃率 | < 0.1% (Android vitals) | Play Console / Crashlytics-free 自建上报 |
| 零遥测 | 出站请求仅限用户配置的播放列表/EPG 源 | 抓包审计 |

---

## 4. 技术栈总览（决策摘要）

```
┌──────────────────────────────────────────────────────────┐
│  iqtvlite (Android TV / Google TV)                       │
├──────────────────────────────────────────────────────────┤
│  UI 层        Slint 1.18 (纯 Rust, Skia 后端)             │
│                └─ 自研 TV Focus 模型（空间导航 + 焦点环）   │
├──────────────────────────────────────────────────────────┤
│  应用层       iqtvlite-app    (Rust, Android Activity 桥) │
│                └─ Kotlin 薄壳 (仅 Activity/Service 装配)    │
├──────────────────────────────────────────────────────────┤
│  领域层       iqtvlite-core   (纯 Rust, 无 Android 依赖)  │
│                M3U / Xtream / XMLTV 解析 · 数据模型 · EPG   │
├──────────────────────────────────────────────────────────┤
│  存储层       iqtvlite-store  (rusqlite + FTS5)            │
├──────────────────────────────────────────────────────────┤
│  播放层       iqtvlite-player (Rust trait + 2 个后端)      │
│                ├─ Media3 后端 (Kotlin 桥, 直播优先)         │
│                └─ mpv 后端   (libmpv-android AAR, 兜底)   │
├──────────────────────────────────────────────────────────┤
│  传输层       reqwest (rustls) · 自研 TS/fMP4 嗅探          │
└──────────────────────────────────────────────────────────┘
```

**关键决策一句话版**：Rust 承担 90% 逻辑与全部 UI；Kotlin 仅作为 Android 平台胶水层（Activity 生命周期、Media3、SurfaceView）；mpv 以 AAR 形式随包分发仅作长尾兜底。

---

## 5. 文档索引

| 文档 | 内容 |
|---|---|
| `01-competitive-landscape.md` | 现有 Rust / 商业 IPTV 播放器调研与对标结论 |
| `02-architecture.md` | 总体架构、模块边界、数据流、线程模型 |
| `03-tech-selection.md` | 逐项技术选型对比与决策依据（含被否决方案）+ ADR 索引 |
| `04-data-model.md` | SQLite schema、EPG 索引策略、稳定 ID 方案 |
| `05-playback.md` | 双引擎设计、故障自愈矩阵、性能调优参数 |
| `06-android-tv.md` | Android TV 平台适配（**Surface 合成方案 = 最大风险**） |
| `07-roadmap.md` | 里程碑拆分、验收标准、风险清单 |
| `08-build-release.md` | 构建、签名、CI、体积预算、许可合规 |
| `09-protocols.md` | 协议层实现指南（M3U / Xtream / XMLTV / Catch-up / 供应商怪癖） |
| `research/domain-protocols-reference.md` | 协议调研原始记录（78 KB，含完整引用与置信度标注） |

---

## 5b. 三个决定项目成败的技术事实

> 这三条都是调研中**推翻了初始假设**的发现，直接决定了架构形态。

### 事实 1 — Slint 的 Android 窗口是**不透明**的

Slint 后端硬编码 `transparent = false`，`Surface` 从不设为 `PixelFormat.TRANSLUCENT`，且**没有任何 API 能把 `ANativeWindow` 交给 Slint**。

→ **SurfaceView 视频层无法穿透 Slint 窗口显示。** 这是 **alpha** 问题，不是 Z-order 问题。

四条出路：**P1** patch Slint（+让 Slint 渲染进自建 SurfaceView）/ **P2** Slint 只做浏览屏 + 原生播放屏 / **P3** GL underlay（丢 HDR/DRM）/ **P4** 换 Compose for TV。
→ 建议 **v1 走 P2，M0 spike 并行验证 P1**。详见 `06-android-tv.md` §1。

### 事实 2 — Xtream 面板的怪癖比想象中深

- `get_short_episodes` / `action=get_episodes` **不存在**（用 `get_series_info`）
- EPG 的 `title`/`description` 是 **base64**（XMLTV 才是明文）
- 时移起点是**供应商本地墙钟时间**，必须用 `server_info.timezone` 换算
- 归档比直播**滞后 30 秒 ~ 2 分钟** → 请求时长要 **+5 分钟**
- 很多播放列表用**未公开的私有语法**（JioTV 的 `{{H}}`、中国移动的 `${(b)yyyyMMddHHmmss}`）→ 必须防御性实现

→ 详见 `09-protocols.md`。

### 事实 3 — `crispy-*` 全家桶是 **CC-BY-NC-SA 4.0（非商业）**

技术质量很高（TypeScript 实战库 `@iptv/playlist` / `ektotv/xtream-api` 的 Rust 移植），但**与 GPL-3.0 不兼容**，会让产品永久锁定为非商业。

→ **决策：作为参考研究，不作为依赖引入。** 用 `cargo deny` 在 CI 里做许可门禁，防止误引入。

---

## 6. 环境前提（开发机）

本机（Windows）当前状态：

| 依赖 | 状态 | 备注 |
|---|---|---|
| Git | ✅ 2.56.0 | |
| Node.js | ✅ v24.22.1 | 仅用于 UI 构建工具链（若采用） |
| Rust (rustup) | ❌ **缺失** | 需安装：`rustup target add aarch64-linux-android` |
| JDK 17+ | ❌ **缺失** | Android Gradle Plugin 需要 |
| Android SDK / NDK | ❌ **缺失** | NDK r27+（cargo-ndk 要求） |
| cargo-ndk | ❌ **缺失** | `cargo install cargo-ndk` |

> **Windows 主机注意**：构建 Android NDK 产物本身不需要 WSL（NDK 自带 clang），但 **libmpv-android 的 AAR 重新编译**（需要 meson/ninja + ffmpeg 构建链）历史上主要在 Linux 上做 —— 建议直接下载上游预编译 AAR，或用 GitHub Actions 的 Linux runner 构建。

---

## 7. 许可证策略

| 组件 | 许可证 | 影响 |
|---|---|---|
| 我们的代码 | **GPL-3.0-or-later** | 必须 |
| libmpv + FFmpeg (AAR) | GPL-3.0-or-later | 与 GPL 应用兼容 |
| Slint | GPL-3.0 / Royalty-free / 商业三选一 | **选 Royalty-free 2.0**：需在 About 页显示 Slint 归属 |
| Media3 / ExoPlayer | Apache-2.0 | 兼容 |
| Rust crates | MIT / Apache-2.0 | 兼容 |

**结论**：应用整体发布为 **GPL-3.0-or-later**，随包分发 libmpv 时需附完整源码与构建脚本链接（GPL §6）。Slint 走 royalty-free 路线避免强制 GPL 传染到 UI 代码（若选 GPL 版则 UI 也必须 GPL——实际上我们本来就是 GPL，所以选 GPL 版更省事；**但 royalty-free + About 页归属标注更利于未来闭源商业化**）。

---

## 8. 实现进度

> 领域层（纯 Rust，零 Android 依赖）**已实现并通过 127 个测试**。
> 详见 [`crates/README.md`](../crates/README.md)。

| 层 | Crate | 状态 |
|---|---|---|
| 协议层 | `iqtvlite-core`（M3U / XMLTV / Xtream / Catch-up 模板 / 稳定 ID / 时间） | ✅ 71 测试 |
| 存储层 | `iqtvlite-store`（SQLite + FTS5 + 迁移） | ✅ 3 测试 |
| EPG 层 | `iqtvlite-epg`（频道↔指南匹配算法） | ✅ 10 测试 |
| 网络层 | `iqtvlite-net`（UA 管理 / 连接池保温） | ✅ 4 测试 |
| 播放层 | `iqtvlite-player`（回退状态机 / 故障自愈） | ✅ 13 测试 |
| 焦点层 | `iqtvlite-tvfocus`（自研空间导航） | ✅ 7 测试 |
| 集成测试 | `iqtvlite-tests`（真实形态 fixture） | ✅ 19 测试 |
| **UI 层** | `iqtvlite-app`（Slint） | ⬜ 待 M0 spike |
| **Android 胶水** | `iqtvlite-capi` + Kotlin | ⬜ 待 M0 spike |

**下一步（`docs/07-roadmap.md` 的 M0）**：Surface 合成方案验证、中文字体、媒体按键拦截、NDK 构建链路。
