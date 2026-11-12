# iqtvlite

> 一款 **功能对齐 TiviMate**、**更丝滑 · 更轻量 · 更稳定** 的 Android TV / Google TV IPTV 播放器。
> 全 Rust 实现（Slint 原生 UI + Rust core），播放层 Media3 优先 / mpv 兜底。
> **零遥测 · 无内置片源 · BYO playlist。**

---

## 目标

| 维度 | TiviMate | iqtvlite 目标 |
|---|---|---|
| 冷启动 | 3–5 s（同步拉 EPG） | **< 900 ms** |
| APK | 数十 MB | **≤ 15 MB**（lite 变体） |
| 空闲内存 | 数百 MB | **≤ 60 MB PSS** |
| 直播起播 P50 | ~2 s | **< 1.2 s** |
| 换台 | ~2–3 s | **< 800 ms** |
| 遥测 | 有 | **零** |
| 源码 | 闭源 | **GPL-3.0-or-later** |

---

## 状态

🚧 **开发中** —— 当前处于 M0（技术可行性 Spike）阶段。

| 模块 | 状态 |
|---|---|
| Slint + Android TV 链路 | ⬜ |
| 视频层 Surface 合成（ADR-008，最高风险） | ⬜ |
| 双引擎（Media3 + mpv） | ⬜ |
| 协议层（M3U / Xtream / XMLTV） | ⬜ |
| SQLite 存储 + FTS5 | ⬜ |
| EPG 网格 | ⬜ |
| DVR / 多画面 / Catch-up | ⬜ |

---

## 文档

| 文档 | 内容 |
|---|---|
| [00-overview.md](docs/00-overview.md) | 项目总览、目标、指标、三个决定成败的技术事实 |
| [01-competitive-landscape.md](docs/01-competitive-landscape.md) | Rust/商业 IPTV 播放器生态调研 |
| [02-architecture.md](docs/02-architecture.md) | 总体架构、模块边界、线程模型 |
| [03-tech-selection.md](docs/03-tech-selection.md) | 逐项技术选型与被否决方案 + ADR 索引 |
| [04-data-model.md](docs/04-data-model.md) | SQLite schema 与数据模型 |
| [05-playback.md](docs/05-playback.md) | 双引擎与故障自愈 |
| [06-android-tv.md](docs/06-android-tv.md) | **Surface 合成方案（最大风险）** + 平台适配 |
| [07-roadmap.md](docs/07-roadmap.md) | 里程碑与验收标准 |
| [08-build-release.md](docs/08-build-release.md) | 构建、签名、体积预算、许可合规 |
| [09-protocols.md](docs/09-protocols.md) | 协议层实现指南（M3U / Xtream / XMLTV / Catch-up） |
| [research/](docs/research/) | 调研原始记录（含完整引用与置信度标注） |

---

## 许可

**GPL-3.0-or-later**。随包分发的 libmpv / FFmpeg 为 GPL-3.0-or-later，需附源码（见 [08](docs/08-build-release.md) §7）。
Slint 采用 Royalty-free 许可，归属标注见应用内「开源许可」页。

---

## 免责声明

iqtvlite **是播放器，不是片源**。不提供、不托管、不分发任何频道、订阅或流媒体内容。
用户自行提供播放列表，并自行确保符合当地法律与供应商条款。
