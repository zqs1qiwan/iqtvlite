# 02 — 总体架构

---

## 1. 分层

```
┌───────────────────────────────────────────────────────────────────────┐
│  UI 层 (Rust, Slint)                                                   │
│  ├─ Screens: Home / Live / Guide / Movies / Series / Search / Settings  │
│  ├─ iqtvlite-tvfocus: 自研焦点导航                                      │
│  └─ iqtvlite-app: 应用状态 (AppState) + 事件总线                        │
└───────────────────────────────┬───────────────────────────────────────┘
                                │  (Rust 内部调用, 无序列化)
┌───────────────────────────────▼───────────────────────────────────────┐
│  领域层 (Rust, 纯逻辑, 可在 PC 上完整测试)                               │
│  ├─ iqtvlite-core: 模型 / M3U / Xtream / XMLTV / 稳定 ID                │
│  ├─ iqtvlite-epg:  同步调度 / 增量合并 / 匹配算法 / 网格查询            │
│  ├─ iqtvlite-net:  reqwest 客户端 / UA 管理 / 连接池                   │
│  └─ iqtvlite-player: PlayerEngine trait / 状态机 / fallback 决策        │
└───────────────────────────────┬───────────────────────────────────────┘
                                │
┌───────────────────────────────▼───────────────────────────────────────┐
│  存储层 (Rust)                                                         │
│  └─ iqtvlite-store: rusqlite + FTS5 + WAL + 单写者 + 迁移              │
└───────────────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────────────┐
│  Android 胶水层 (Kotlin, 尽可能薄)                                       │
│  ├─ MainActivity (NativeActivity)                                      │
│  ├─ Media3Bridge: C-ABI → ExoPlayer                                   │
│  ├─ VideoSurface: SurfaceView / TextureView                            │
│  ├─ MediaSessionService + AudioFocus                                   │
│  └─ Mp vSurface: EGL 上下文 (供 mpv Render API 共享)                   │
└───────────────────────────────────────────────────────────────────────┘
```

**规则**：`iqtvlite-core` / `epg` / `net` / `store` / `player` **零 Android 依赖**。
这条规则是整个工程可测试性的根基 —— 它让 90% 的代码能在 PC 上 `cargo test` 跑完。

---

## 2. Crate 依赖图

```
                    iqtvlite-app  (Rust cdylib + bin)
                    iqtvlite-capi (Rust cdylib, C-ABI)
                         │
        ┌────────────────┼────────────────┐
        │                │                │
   iqtvlite-ui     iqtvlite-player   iqtvlite-epg
   (Slint)            │                  │
        │         ┌────┴────┐            │
        │    iqtvlite-net   │            │
        │         │    iqtvlite-core ◄───┘
        │         │            │
        │    iqtvlite-store   │
        │              │       │
        └──────────────┴───────┘
                       │
                 iqtvlite-tvfocus
```

依赖规则：
- `iqtvlite-core` 依赖：无内部 crate（只有 serde/quick-xml/url/time 等外部库）
- `iqtvlite-store` 依赖：`iqtvlite-core`（模型）
- `iqtvlite-net` 依赖：`iqtvlite-core`
- `iqtvlite-epg` 依赖：`core` + `store` + `net`
- `iqtvlite-player` 依赖：`core` + `net`（+ 可选 `libmpv2`）
- `iqtvlite-tvfocus`：无
- `iqtvlite-ui`（Slint 组件）依赖：`core`（只读模型）
- `iqtvlite-app`：全部
- `iqtvlite-capi`：`app` + `player`

---

## 3. 运行时架构

### 3.1 线程模型

```
┌──────────────────────────────────────────────────────────────┐
│ Android Main Thread                                            │
│  ├─ Slint 事件循环 (UI 渲染, 60 fps)                           │
│  ├─ Android 生命周期回调                                      │
│  └─ Kotlin 侧 Media3 回调 → JNI → Rust (极轻量, 只传小结构体) │
└──────────────────────────────────────────────────────────────┘
        │ Slint invoke (同步, 轻)
        ▼
┌──────────────────────────────────────────────────────────────┐
│ Tokio Runtime (multi-thread, 2–4 workers)                     │
│  ├─ EPG / playlist 同步任务                                   │
│  ├─ HTTP 请求 (reqwest)                                       │
│  ├─ XMLTV 流式解析                                            │
│  └─ DVR 录制控制                                              │
└──────────────────────────────────────────────────────────────┘
        │ 单写者通道 (mpsc)
        ▼
┌──────────────────────────────────────────────────────────────┐
│ SQLite Writer Thread (专用, 单线程)                            │
│  └─ 所有写操作, 批量事务 (1000 行/批)                          │
└──────────────────────────────────────────────────────────────┘
        │ 只读连接 (WAL 允许并发读)
        ▼
┌──────────────────────────────────────────────────────────────┐
│ Slint UI Thread (读 SQLite, 渲染)                              │
└──────────────────────────────────────────────────────────────┘

播放器线程 (由 Media3 / libmpv 内部管理, 不占我们的线程)
```

**关键**：
- **SQLite 写操作绝不发生在 UI 线程**。
- **Slint 模型更新走 `slint::invoke_from_event_loop`**（从后台线程安全地回到 UI 线程）。
- 播放引擎在**独立线程/独立对象**里，不阻塞 UI。

### 3.2 状态流

```
AppState (Rust, 单线程持有)
  ├── sources: Vec<SourceSummary>
  ├── active_source: SourceId
  ├── channels: ChannelListModel      ← Slint Model，虚拟化
  ├── guide: GuideModel                ← EPG 网格模型
  ├── search: SearchModel
  ├── settings: Settings
  └── player: PlayerUiState            ← 由 Media3 回调驱动
```

事件源：
| 来源 | 事件 | 目标 |
|---|---|---|
| UI 交互 | `ChannelSelected` | → `player.play(channel)` |
| UI 交互 | `RequestRefresh` | → tokio: 同步任务 |
| Media3 回调 | `FirstFrame` | → `player.state = Playing` |
| Media3 回调 | `Error` | → `player.fallback.decide()` |
| 同步任务完成 | `ChannelsUpdated(n)` | → 增量刷新 `ChannelListModel` |
| EPG 同步完成 | `EpgUpdated` | → 刷新 `GuideModel` |

---

## 4. 关键数据流

### 4.1 冷启动（目标 < 900 ms）

```
t=0ms     进程启动, ART 初始化, Rust 动态库加载
t=50ms    Rust 初始化: tracing, 打开 SQLite (WAL)
t=120ms   读取本地数据: sources, categories, channels(首批 500), favourites
t=200ms   Slint 首帧渲染 → 界面可交互  ← 用户看到内容
          ─────────────────────────────────
t=210ms   后台: tokio 启动 playlist/EPG 同步任务 (不阻塞)
t=220ms   Kotlin: 创建 warm ExoPlayer + MediaSession
t=300ms   Kotlin: 预取 master playlist (若有上次播放的频道)
```

**注意 t=200ms 处**：界面立即可交互，EPG 网格可能还是空的 → 显示「正在更新节目单…」的轻量提示，不阻塞。

### 4.2 换台

```
用户按下「频道 B」
t=0ms     UI: 高亮 B, 停止旧播放 (同时释放连接)
t=10ms    Rust: 查 channel_streams → 选线路 (优先健康的、优先 HLS)
t=15ms    Rust: warm player.load(url)
t=20ms    Kotlin: ExoPlayer 内部: HTTP 连接 (复用 keep-alive) + 拉 playlist
t=250ms   ExoPlayer: MediaSource ready
t=400ms   ExoPlayer: 首帧解码完成 → onVideoSizeChanged / 首帧回调
t=420ms   Kotlin → Rust → UI: 显示画面 + 更新 OSD
```

---

## 5. 错误处理原则

```rust
#[derive(Debug, thiserror::Error)]
pub enum AppError {
    #[error("网络错误: {0}")]
    Network(#[from] reqwest::Error),
    #[error("播放失败: {0}")]
    Playback(#[from] PlaybackError),
    #[error("存储错误: {0}")]
    Storage(#[from] rusqlite::Error),
    #[error("解析错误: {0}")]
    Parse(String),
    #[error("配置错误: {0}")]
    Config(String),
}
```

**面向用户的错误**必须携带**可执行动作**，而不是原始错误串：

```rust
pub struct UserFacingError {
    pub title: String,             // 「供应商拒绝了新连接」
    pub detail: Option<String>,    // 技术细节（折叠显示）
    pub actions: Vec<RecoveryAction>,
}
```

---

## 6. 可观测性（零遥测）

- `tracing` 结构化日志，本地写 logcat（`adb logcat`）。
- **不向任何服务器发送数据**。崩溃报告也**不上传**（或仅在用户明确选择时，通过 GitHub issue 模板手动提交）。
- 应用内「诊断」页：可导出日志包（用户主动触发）。
- 性能埋点：本地 SQLite `metrics` 表，用于应用内显示「上次换台耗时」。

---

## 7. 依赖管理策略

| 类别 | 政策 |
|---|---|
| 核心库（serde / rusqlite / reqwest / quick-xml） | 跟随上游，锁定 minor |
| UI 库（slint） | **精确锁定版本**（`=1.18.1`）—— Slint 内部 crate 有 breaking change in patch release 的历史 |
| 播放库（libmpv2） | 锁定 minor；定期评估 `rsmpv` |
| 实验性 | 放在 `iqtvlite-core` 的 feature flag 后面，默认关闭 |

**CI 依赖审计**：`cargo deny`（license + advisory 检查）。**注意 `cargo deny` 的 license allowlist 必须排除 CC-BY-NC-SA 类协议**（防止误引入 `crispy-*`）。

---

## 8. 未来扩展点

| 方向 | 预留 |
|---|---|
| 桌面端 | `iqtvlite-core`/`epg`/`store`/`net` 全部可复用；新增 `iqtvlite-ui-desktop`（Slint 同 UI 描述可复用大部分） |
| iOS | 同上；播放层新增 AVFoundation 后端 |
| Stalker/Ministra | `iqtvlite-core::parse::stalker`（协议层独立） |
| 自研传输层 | `iqtvlite-player::transport` trait（当前只有 Media3 实现） |
| 移动端 | Slint 支持 iOS/Android；触摸交互需要额外的焦点→触摸映射 |
