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

✅ **已交付** —— Kotlin 原生 UI + Rust core，真机 TR100-G9 端到端验证通过。最新 Release v1.8.0。

| 模块 | 状态 |
|---|---|
| 协议层 `iqtvlite-core`（M3U/XMLTV/Xtream/Catch-up） | ✅ 71 测试 |
| 存储层 `iqtvlite-store`（SQLite + FTS5） | ✅ 3 测试 |
| EPG 层 `iqtvlite-epg`（匹配算法） | ✅ 10 测试 |
| 网络层 `iqtvlite-net`（UA / 连接池） | ✅ 4 测试 |
| 播放层 `iqtvlite-player`（回退状态机） | ✅ 13 测试 |
| 焦点层 `iqtvlite-tvfocus`（空间导航） | ✅ 7 测试 |
| 集成测试（真实形态 fixture） | ✅ 19 测试 |
| Android TV UI（满屏播放 + 浮层 + 海报墙） | ✅ 真机验证 |
| EPG 时间轴网格 + 时间刻度 | ✅ |
| 手机扫码推源 / 首次引导 / PiP / 多画面 | ✅ |
| DVR 录制 | ❌ 明确不做（已全链路删除） |

**CI 全绿：cargo fmt / clippy -D warnings / cargo test / APK 构建全部通过。**

> 开发准入与功能清单见 [docs/PRD.md](docs/PRD.md)（唯一真相源）。

### 本地构建

```powershell
. .\scripts\dev-env.ps1     # 设置 PATH（cargo + MinGW-w64）
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
```

> 本机没有 Visual Studio（缺 `link.exe`），因此使用 MinGW-w64 + `x86_64-pc-windows-gnu` 工具链。
> Android 交叉编译还需安装 JDK 17 + Android SDK + **NDK r30+**（Skia 0.157 的硬要求，见 `docs/06-android-tv.md`）。

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
