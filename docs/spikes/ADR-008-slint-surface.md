# ADR-008 Spike — Slint 与视频 Surface 合成

> 日期：2026-11-16
> 方法：**源码级 spike**（Slint v1.18.1 tag 的实际源码，非推测）
> 问题：Slint 能否渲染进我们自建的 SurfaceView，让 Media3 视频层在其下方显示？

---

## 1. 结论

**否 —— 用公开 API 无法做到。** P1 路线（patch Slint 透明标志 + 注入自建 SurfaceView）需要 fork Slint 并改动内部实现，
风险和工期都远超一个测试 APK 的范畴。**维持 P2 路线**（Kotlin 原生 UI + Rust core + Media3）是当前正确选择。

---

## 2. 证据（Slint v1.18.1 源码）

### 2.1 透明度硬编码为 false

`internal/backends/android-activity/androidwindowadapter.rs` 第 224–230 行：

```rust
self.renderer.set_window_handle(
    Arc::new(w),
    Arc::new(DummyDisplayHandle),
    size,
    self.requested_graphics_api.borrow().clone(),
    false,          // ← transparent，硬编码
)?;
```

### 2.2 窗口来自框架，不是我们能注入的

同一文件第 212 行与 500 行：

```rust
if let Some(w) = self.app.native_window() { ... }   // InitWindow
let Some(win) = self.app.native_window() else { ... };  // resize
```

`self.app` 是 `slint::android::AndroidApp`（即 `android-activity` 的 `AndroidApp`），
其 `native_window()` 由 **NativeActivity 框架**创建 —— Slint 没有公开 API 让我们传入自己的 `ANativeWindow` / `Surface`。

### 2.3 Java 侧也没有任何 Surface 配置

`internal/backends/android-activity/java/SlintAndroidJavaHelper.java`（732 行）中搜索：

| 关键词 | 命中 |
|---|---|
| `PixelFormat` | 0 |
| `TRANSLUCENT` | 0 |
| `setFormat` | 0 |
| `Surface` / `SurfaceView` | 0（除 IME 相关注释） |

→ Slint 从不把 Surface 设为透明格式，即使改了 Rust 侧的 `transparent` 标志，
底层的 `ANativeWindow` 仍是不透明的。

### 2.4 理论上的 P1 改法（需要 fork）

1. `androidwindowadapter.rs` 第 229 行 `false` → `true`
2. JavaHelper 里对 Activity 的 Surface 设置 `PixelFormat.TRANSLUCENT`
3. 让 `AndroidApp::native_window()` 返回**我们创建的** SurfaceView 的 `ANativeWindow`
   —— 这一步需要改 `android-activity` 或绕过框架的 `InitWindow` 流程

第 3 步没有现成实现，是主要风险点。

---

## 3. 决策

| 方案 | 可行性 | 决策 |
|---|---|---|
| P1: patch Slint + 自建 SurfaceView | 理论可行，需 fork 两个上游 crate + Skia 构建链 | ❌ 不采用（风险/工期过高） |
| **P2: Kotlin 原生 UI + Rust core + Media3** | 已真机验证（91 频道 / 550 EPG 节目 / 播放） | ✅ **采用** |
| P3: GL underlay（视频帧进 Slint 纹理） | 丢 HDR、Slint 无文档支持 | ❌ 不采用 |
| P4: Compose for TV | 可行但 UI 非 Rust，且比当前 Kotlin Views 更重 | ❌ 不采用 |

---

## 4. 对最终目标的影响（已由 ADR-011 定案）

> **2026-11-17 更新**：用户已明确决定**接受 Kotlin 原生 UI 作为最终路线**（见 ADR-011）。
> 本 ADR 的结论被采纳 —— 不 fork Slint，专注功能与性能。

「UI 全 Rust」这个目标不再 pursue —— 但**底层架构完全兼容**：
- Rust core 承担全部协议解析、EPG、稳定 ID、回退决策、catch-up 模板
- Kotlin 只是 Android 平台胶水（Activity / Media3 执行）
- 若未来投入 fork Slint 的成本，UI 层可以直接替换，底层无需重写

这与「轻量、稳定、高性能」的核心目标不冲突 —— 真机验证的 APK 为 11.73 MB。
