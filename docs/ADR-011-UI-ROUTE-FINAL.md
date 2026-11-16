# ADR-011 — UI 路线最终决策

> 日期：2026-11-17
> 状态：**已定**

## 决策

**接受 Kotlin 原生 Views 作为最终 UI 路线**，不再 pursuit Slint fork。

## 背景

- 项目最初推荐 Slint 纯 Rust UI，但 ADR-008 源码级 spike 确认：
  - Slint Android 窗口硬编码不透明（`set_window_handle(..., false)`）
  - 窗口由 NativeActivity 框架创建，无公开 API 注入自建 SurfaceView
  - JavaHelper 中无 `PixelFormat.TRANSLUCENT` / `setFormat`
  - 视频合成必须 fork `slint` + `android-activity` + 处理 Skia 预编译二进制供应链
- P2 路线（Kotlin 原生 UI + Rust core + Media3）已在真机跑通全部核心功能：
  - 91 频道解析、EPG 网格（495 节目块）、多 EPG 合并
  - CCTV 播放（VLC UA + OMXVDEC 硬解）、CEA-608 字幕切换
  - 收藏/最近观看、MultiView 2×2、DVR 录制（有效 TS 文件）
  - APK 仅 12 MB

## 决策理由

1. **核心目标已达成**：轻量（12 MB）、稳定、高性能（硬解）、TiviMate 主要功能
2. **Rust core 完整保留**：协议解析、EPG、稳定 ID、回退决策、catch-up、录制状态机全部在 Rust（167 测试）
3. **Kotlin 只是平台胶水层**：Activity / Media3 执行 / SurfaceView，符合 Android TV 生态
4. **Slint fork 成本远超收益**：高维护成本 + 供应链风险，与「轻量/稳定」目标冲突

## 影响

- 后续精力投入：**稳定性、性能、TV 观感、功能补全**，不再投入 UI 栈迁移
- 架构文档中所有「Slint 待迁移」的表述以本 ADR 为准
