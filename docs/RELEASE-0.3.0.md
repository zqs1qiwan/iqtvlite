## iqtvlite 0.3.0 — 收藏 / 最近观看 / Catch-up 回看

第三版测试 APK，新增三个 TiviMate 常用功能，并完成 ADR-008 Slint 合成方案源码级 spike。

### 新增功能

| 功能 | 说明 |
|---|---|
| **★收藏** | 频道列表长按收藏/取消收藏；用 Rust 稳定 ID 存储，播放列表重同步后不丢 |
| **最近观看** | 播放频道自动记录（最多 30 条），按最近顺序；同一稳定 ID 机制 |
| **Catch-up 回看** | EPG 长按已播出节目 → Rust 生成归档 URL 并播放。支持 Shift/Append/Default/Timeshift/Flussonic 全部模式 |

> 关于回看：用户指定的两个播放列表（lbzs.laobaitv.net / epg.laobaitv.net）实测**都不含 catch-up 元数据**，
> 因此这些源会正确提示「不支持回看」。功能本身用合成 fixture 完整验证（3 个新单测），
> 可用于任何带 catchup 标记的播放列表。

### 真机验证（TR100-G9 Android TV 盒子）

```
I iqtvlite: WATCHED 7528061454182504437 CCTV-1 综合 recents=1
I iqtvlite: playing CCTV-1 综合 ua=VLC/3.0.20 LibVLC/3.0.20
```
UI 分组栏实测出现 `★收藏` / `最近观看` / `全部` / 各分组。

### ADR-008 Spike 结论

对 Slint v1.18.1 源码级验证确认：Slint Android 窗口**硬编码不透明**（`set_window_handle(..., false)`），
JavaHelper 里没有任何 `PixelFormat.TRANSLUCENT` / `setFormat` 调用，且窗口由 NativeActivity 框架创建、无法注入自建 SurfaceView。
→ **P1 路线需要 fork 两个上游 crate，风险过高；维持 P2 路线**（Kotlin 原生 UI + Rust core + Media3）。
详见 `docs/spikes/ADR-008-slint-surface.md`。

### 验证状态

- 148 个 Rust 测试通过，clippy -D warnings 零告警
- 两个 GitHub Actions 工作流均 success
- APK 12.07 MB，arm64-v8a + armeabi-v7a 双 ABI

### 仍待实现

DVR 录制、多画面（MultiView）、system-wide EPG 发布、mpv 兜底变体。
