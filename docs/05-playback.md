# 05 — 播放层：双引擎与故障自愈

> 这是「**更丝滑**」和「**更稳定**」两个目标的落地处。

---

## 1. 架构

```
┌─────────────────────────────────────────────────────────┐
│  iqtvlite-player (Rust)                                  │
│                                                          │
│   PlaybackCoordinator                                     │
│   ├── 选择引擎 (EnginePolicy)                             │
│   ├── 状态机 (PlaybackState)                              │
│   ├── 故障分类 & 降级决策 (FallbackPolicy)                │
│   ├── 预热池 (PreheatPool)                                │
│   └── 线路选择 (StreamSelector)                           │
│                                                          │
│   trait PlayerEngine { prepare / play / pause / seek /    │
│                        tracks / stop / events }          │
│   ├── Media3Engine   ← C-ABI → Kotlin → ExoPlayer        │
│   └── MpvEngine      ← libmpv2 (Rust 直调)               │
└─────────────────────────────────────────────────────────┘
        │ C-ABI                     │ Rust FFI
        ▼                           ▼
   Kotlin Bridge                libmpv.so
   (Media3 / SurfaceView)       (Render API → EGL)
```

### 1.1 Rust/Kotlin 边界的具体形态

**不让 Rust 直接用 `jni` crate 手搓 Media3 调用**。Rust 侧定义 C-ABI：

```rust
// iqtvlite-capi/src/media3.rs
#[repr(C)]
pub struct PlaybackRequest {
    pub url: *const c_char,
    pub user_agent: *const c_char,
    pub start_position_ms: i64,
    pub options: PlaybackOptions,   // 扁平结构体
}

#[repr(C)]
pub struct PlaybackOptions {
    pub buffer_ms: i32,
    pub min_buffer_ms: i32,
    pub rebuffer_ms: i32,
    pub enable_low_latency: bool,
    pub enable_audio_focus: bool,
    pub start_volume: f32,
    pub muted: bool,
}

#[repr(C)]
pub struct Media3Host {
    pub on_state_changed: Option<extern "C" fn(*mut c_void, i32)>,
    pub on_error:         Option<extern "C" fn(*mut c_void, i32, *const c_char)>,
    pub on_tracks:        Option<extern "C" fn(*mut c_void, *const TrackInfo)>,
    pub on_video_size:    Option<extern "C" fn(*mut c_void, i32, i32)>,
    pub user_data: *mut c_void,
}
```

Kotlin 侧实现 `Media3Host` 的函数指针（用 `external fun` + `Function` 包装），并在 Rust 传入的 `user_data` 上持有状态。

**收益**：
- Rust 侧的 fallback 决策逻辑**可以用纯单测覆盖**（不需要 Android 设备）。
- 未来换掉 Media3（换 ExoPlayer 版本 / 换 AVPlayer）不影响上层。

### 1.2 状态机

```rust
pub enum PlaybackState {
    Idle,
    Preparing { engine: EngineKind, attempt: u32 },
    Connecting,
    Buffering { pct: u8 },
    Playing { engine: EngineKind, live_edge: bool },
    Paused,
    Seeking,
    Recovering { reason: RecoveryReason, attempt: u32 },
    Failed { error: PlaybackError, actions: Vec<RecoveryAction> },
}
```

**状态迁移图**

```
                 ┌─────────┐
   Idle ────────▶│Preparing│
                 └────┬────┘
                      │ engine 选好
                      ▼
   ┌────────────▶┌─────────┐
   │             │Connecting│
   │             └────┬────┘
   │                  │
   │      ┌───────────┼───────────┐
   │      ▼           ▼           ▼
   │ ┌─────────┐ ┌──────────┐ ┌────────┐
   │ │Buffering│ │  Playing │ │ Failed │
   │ └────┬────┘ └────┬─────┘ └───┬────┘
   │      │           │           │
   └──────┴───────────┘           │
                                  ▼
                            ┌───────────┐
                            │ Recovering│
                            └─────┬─────┘
                       ┌──────────┼──────────┐
                       ▼          ▼          ▼
                   重试(同引擎)  换线路    换引擎(mpv)
```

---

## 2. 引擎选择策略

```rust
pub enum EngineKind { Media3, Mpv }

pub struct EnginePolicy {
    /// 默认直播用哪个引擎
    pub live_primary: EngineKind,
    /// 默认 VOD 用哪个引擎
    pub vod_primary: EngineKind,
    /// 是否允许自动降级到另一引擎
    pub allow_fallback: bool,
    /// 用户对特定频道的强制覆盖
    pub per_channel_override: HashMap<ChannelId, EngineKind>,
}
```

### 2.1 默认

| 内容 | 主引擎 | 兜底 |
|---|---|---|
| 直播 HLS/TS | **Media3** | mpv |
| 直播 RTMP/RTSP | **Media3**（Media3 有 RTSP 模块） | mpv |
| VOD / 剧集 | **Media3** | mpv |
| Dolby Vision P5 | mpv（ExoPlayer 解不了） | — |

### 2.2 自动降级触发条件

| 错误 | 分类 | 动作 |
|---|---|---|
| `DecoderInitializationException` / codec 不支持 | `Unsupported` | → **切 mpv** |
| `MediaCodecVideoRenderer` 异常 | `Unsupported` | → **切 mpv** |
| `HttpDataSource` 401/403 | `Auth` | 提示换 UA / 检查凭据（**不重试**） |
| HTTP 404 | `NotFound` | 换线路 → 仍失败则报错 |
| HTTP 5xx / SocketTimeout | `Transient` | 重试（指数退避）→ 换线路 → 报错 |
| `Source error` / 解析失败 | `Format` | 换线路 → 切 mpv → 报错 |
| `BehindLiveWindow` | `LiveEdge` | 等待并重试（最多 3 次） |
| 供应商限流（403 + 特定 body） | `RateLimited` | **节流等待** + 提示 |
| 解码器崩溃 / 设备丢失 | `DeviceLost` | 重建引擎 → 重试 |

---

## 3. 故障自愈矩阵（产品级差异化）

**核心思想**：把「供应商限流 / UA 拦截 / EPG 错配」这些行业常见坑，从「报错」变成「可执行的操作」。

| 症状 | 用户看到 | 提供的一键操作 |
|---|---|---|
| 供应商限流（上一路未释放） | 「供应商拒绝了新连接，请稍候」 | 「等待 15 秒重试」按钮 + 自动倒计时重试 |
| UA 被拦截 | 「服务器拒绝访问（可能需要特定客户端标识）」 | 「切换 User-Agent → TiviMate / VLC / 自定义」+ 立即重试 |
| 该线路失效 | 「线路不可用」 | 「换一条线路」（共 N 条） |
| 编码不支持 | 「此内容需要兼容播放器」 | 「使用兼容模式播放」（→ mpv）+ 说明 mpv 变体的额外下载 |
| EPG 匹配率低 | 设置页角标「EPG 匹配率 23%」 | 「重新匹配 EPG」（按 tvg-name 模糊匹配） |
| 鉴权失败 | 「账号或密码错误」 | 「编辑凭据」 |
| 直播落后 | 「正在追上直播点…」 | 显示追帧进度 |

### 3.1 限流退避

```rust
const BACKOFF: &[Duration] = &[1, 2, 4, 8, 15];  // 秒
// 重试时向用户显示：「供应商限制同时连接，8 秒后重试（第 3/5 次）」
```

**重要**：退避期间**释放上一路连接**（`stop()`），否则永远解不开。

---

## 4. 换台速度优化（核心指标）

目标：换台 P50 < 800 ms。拆解时间预算：

| 环节 | 预算 | 优化手段 |
|---|---|---|
| 旧连接释放 | ~50 ms | 提前 `stop()`，不等 `onPlaybackStateChanged(IDLE)` |
| DNS + TLS 握手 | 0–300 ms | **连接池复用**（同 host 复用 keep-alive）→ OkHttp `ConnectionPool` |
| 播放列表拉取 | 50–200 ms | ExoPlayer 的 HLS 解析器；预取 master playlist |
| 首帧解码 | 100–300 ms | 硬件解码；`hwdec` 优先 |
| **合计** | **~500–800 ms** | |

### 4.0 Media3 的 LoadControl 调参（最关键）

Media3 1.11 默认缓冲是**为点播设计的**，对 IPTV 直播过大：

```java
DEFAULT_MIN_BUFFER_MS                        = 50_000;  // 50 秒
DEFAULT_MAX_BUFFER_MS                        = 50_000;  // 50 秒
DEFAULT_BUFFER_FOR_PLAYBACK_MS               =  1_000;
DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS=  2_000;
```

**IPTV 直播推荐值**：

```kotlin
val loadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMsForStreaming(
        /* minBufferMs = */                     1_500,
        /* maxBufferMs = */                     6_000,
        /* bufferForPlaybackMs = */               500,
        /* bufferForPlaybackAfterRebufferMs = */ 1_000
    )
    .setBackBuffer(10_000, /* retainFromKeyframe = */ true)
    .build()

val player = ExoPlayer.Builder(context)
    .setLoadControl(loadControl)
    .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(okHttpFactory))
    .build()
```

**单一最高杠杆开关**：`MediaItem.LiveConfiguration.setTargetOffsetMs(3000)` ——
`DefaultLoadControl.shouldStartPlayback()` 里有一行：

```java
if (parameters.targetLiveOffsetUs != C.TIME_UNSET) {
    minBufferDurationUs = Math.min(parameters.targetLiveOffsetUs / 2, minBufferDurationUs);
}
```
→ **把所需起始缓冲直接减半**。

### 4.1 预热池（PreheatPool）

TiviMate 的「快速换台」体感来自提前准备好播放管线。

```
用户在看 Channel A
  ├─ 后台预取 Channel B 的 master playlist（HTTP HEAD/GET 前 2 KB）
  ├─ 后台预创建 ExoPlayer 实例（复用，不销毁）
  └─ 用户按「B」时：直接 load 到已就绪的 player
```

**注意**：预创建 `ExoPlayer` 实例是**有内存代价**的（每个实例持有 codec）。策略：
- 维护 **1 个** 备用实例（"warm player"），只在用户有明显的「即将换台」意图时创建（比如焦点移到另一个频道列表项）。
- 绝不做多实例预热。

### 4.2 连接复用

- `reqwest` 的 `Client` 全局单例，**按 host 复用连接池**。
- ExoPlayer 侧使用 `DefaultHttpDataSource` + `DefaultDataSource.Factory`，**不要**每次新建 Factory（会丢连接池）。
- HLS：ExoPlayer 的 `HlsMediaSource` 会自己缓存 playlist。

### 4.3 首帧埋点

```rust
pub enum PlaybackMetric {
    RequestSent,        // t0: 发起播放请求
    PlayerPrepared,     // t1: ExoPlayer prepare() 返回
    SourceReady,        // t2: MediaSource ready
    FirstFrame,         // t3: 首帧渲染
    Playing,            // t4: 状态 = PLAYING
}
```

`首帧延迟 = t3 - t0` 是**唯一真正对用户有意义的指标**（不要用 `prepare` 完成来衡量）。UI 上可以开启调试浮层显示。

**参考基线**：ExoPlayer 官方在 issue#8703 的回复是「光纤网络下换台约 **1–1.5 秒**」，并给出了成本分解：*"Loading manifest (≥1 RTT) + Loading DRM keys (1 RTT) + Buffering sufficient media"*。TiviMate 的反编译结果显示它用自定义 LoadControl（1000/2500/500/1000）+ 保温连接池做到亚秒级。

> **⚠️ 未找到任何可信的「ExoPlayer 在 Android TV 硬件上冷启动」的公开基准数据**（Fire TV / Shield / Chromecast with Google TV，HLS vs MPEG-TS 分列）。上述数字只能作为参考量级 —— **必须自测**。

### 4.4 MPEG-TS 的两个经典坑（IPTV 特有）

ExoPlayer 官方 troubleshooting 页明确写道：

> *"Some MPEG-TS files do not contain access unit delimiters (AUDs). By default, ExoPlayer relies on AUDs to cheaply detect frame boundaries. Similarly, some MPEG-TS files do not contain IDR keyframes."*
> → **ExoPlayer 会看起来卡在 buffering 状态。**

```kotlin
val extractorsFactory = DefaultExtractorsFactory()
    .setEnableTsInsertionPackets(false)
    .setTsDurationReader(DefaultTsDurationReader(1_000_000))

// 无 AUD 时开：
DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
// 无 IDR 时开：
DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
```

**这是「更稳定」的一个具体落点**：M3U 里的 TS 流经常缺 AUD，默认配置必然卡死。探测到 TS 时主动开这两个 flag。

---

## 5. mpv 兜底后端

### 5.1 为什么需要

Dolby Vision Profile 5 在非 DV 硬件上，ExoPlayer 无法正确解码（色彩错误/绿紫）。需要 mpv + libplacebo `vo=gpu-next` + `libdovi` 做 RPU reshaping + 色调映射。

### 5.2 Rust 侧集成

```rust
// iqtvlite-player/src/mpv/engine.rs
pub struct MpvEngine {
    ctx: *mut mpv_handle,
    render_ctx: Option<*mut mpv_render_context>,
    // EGL 上下文由 Kotlin 侧创建并共享
}
```

**crate 选择：`rsmpv` 0.2.1**（clean-room 手写 FFI，**无 bindgen、无需 libclang**，这对 `cargo-ndk` 交叉编译是关键优势）。
（`libmpv2` 7.x 用 bindgen，在 NDK 构建里需要 libclang → 痛点。）

**选项**：

```rust
ctx.set_option("vo", "gpu")?;              // ⚠️ 不能用 gpu-next（Render API 不支持，见 5.3）
ctx.set_option("hwdec", "mediacodec")?;    // ⚠️ 不能用 mediacodec-copy（Mali 绿屏根源）
ctx.set_option("gpu-api", "opengl")?;
ctx.set_option("vd-lavc-dr", "no")?;        // 关闭直接渲染（Android 上会崩）
ctx.set_option("video-sync", "display-resample")?;  // 帧率匹配（Auto Frame Rate）
```

### 5.3 ⚠️ Android 上的六条硬性限制

| # | 限制 | 详情 |
|---|---|---|
| 1 | **不能用 `--wid` 嵌入** | 必须用 **Render API**（`MPV_RENDER_PARAM_API_TYPE_OPENGL` + EGL 上下文共享） |
| 2 | **`gpu-next` 不可用** | mpv#10810（仍开放）：*"Features only available in gpu-next are currently not available when using the rendering API."* → **失去 DV RPU 渲染、HDR 色调映射、高级缩放** |
| 3 | **`hwdec=mediacodec-copy` 是错误选项** | copy 路径正是 Mali-G31 绿屏的根源（mpv-android#1088，**至今未修**）。应使用 `hwdec=mediacodec`（HW+，零拷贝） |
| 4 | **HW+ 无色调映射** | mpv-android#853 维护者原话：*"It works with 'HW+' though, but it's kind of meaningless for HDR content cause there's no tone mapping in this case."* |
| 5 | **Android 上无原生 HDR** | mpv-android#874：需要 Vulkan，而标准 APK 不含 Vulkan |
| 6 | **Play 商店已下架 mpv-android for Android TV** | *"this mpv-android has been depublished from the Play Store for Android TV"* |

### 5.4 Render API 的线程规则（违反 = 真死锁）

mpv `render.h` 原文：

> *"there must be no lock or wait dependency from the render thread to a thread using other libmpv functions... If you ignore this requirement, deadlocks can happen, which are made non-fatal with timeouts; then playback quality will be degraded... **If you set `MPV_RENDER_PARAM_ADVANCED_CONTROL`, you promise that this won't happen, and must absolutely guarantee it, or a real deadlock will freeze the mpv core thread forever.**"*

实践要点：
- 渲染线程**只**调 `mpv_render_*` 函数，**绝不**调会阻塞的 libmpv API（如 `mpv_wait_event`）。
- `mpv_render_context_create()` 必须在**拥有 GL 上下文的那个线程**上调用，且所有 `mpv_render_*` 调用来自同一线程/上下文。
- `MPV_RENDER_PARAM_FLIP_Y = 1`（Android GL 原点在左下）。
- mpv **不保存/恢复 GL 状态** → 自己重置。
- `mpv_render_context_free()` 必须用**同一个** context 指针。
- API 版本 1.105+（mpv 0.30.0）修复了 ADVANCED_CONTROL 的历史死锁问题 → mpv 0.41/0.42 安全。

### 5.5 现实预期

**我们不承诺 mpv 兜底在所有 Android TV 设备上可用。**

产品策略：
- `iqtvlite`（含 mpv）与 `iqtvlite-lite`（不含）两个变体。
- lite 变体遇到不支持的编码时，明确提示「此内容需要兼容模式，可下载完整版」。
- 完整版在目标 GPU 上 mpv 失败时，提示「当前设备不支持该内容的兼容播放」。

**关键**：**Dolby Vision 不依赖 mpv**（见 §9），这是我们相对 mpv 路线的核心优势。

---

## 6. DVR 录制

```
录制任务
  ├─ 走独立的 ExoPlayer 实例（不能复用播放实例）
  ├─ 写到 app 私有目录或用户选定的外部目录
  ├─ 容器选择：
  │   ├─ TS 流 → 直接存 .ts（不转码，零 CPU）
  │   └─ HLS → 存原始分片 + 索引，或用 fMP4 重封装
  └─ 断电/被杀后：扫描未完成的录制文件，用已有分片重建（可恢复）
```

**关键**：**不转码**。录制 = 把字节落盘。转码会烧 CPU、发热、掉帧。

**断点续录**：`Recording` 表记录 `start_ts / duration_s / file_path / status`；App 启动时扫描 `status='recording'` 的记录，检查文件尾部是否可解析 → 标记为 `failed` 或标记为可续。

---

## 7. 多画面（MultiView）

- 2×2 / 3×3 → 每个窗口一个独立 ExoPlayer 实例。
- **性能现实**：4 路 1080p 解码在中端 Android TV 芯片上是可行的，但要注意：
  - 每路用 `DefaultRenderersFactory` 的 `setEnableDecoderFallback(true)`
  - 限制每路的缓冲（`setBufferForMsAfterRebuffer` 调小）
  - 总输出分辨率不超过面板原生分辨率
- 声音：默认只有一路出声，点击某窗口切换音频焦点。
- 每窗口独立时移（复用 catchup 机制）。

---

## 8. 字幕与音轨

| 来源 | 处理 |
|---|---|
| 内嵌字幕轨（TS/HLS 内） | ExoPlayer 直接支持；选择轨道 |
| 外部字幕（.srt/.ass） | `Media3` 的 `SubtitleConfiguration` + `MediaItem.SubtitleConfiguration` |
| OpenSubtitles / SubSource | v2：搜索 + 下载 + 挂载 |

**字幕样式**：ExoPlayer 的 `CaptionStyleCompat`（背景、边缘、字号）。ASS 字幕 libass 支持有限 → 走 `SubtitleView` 的基础渲染，样式简化。

**音轨**：按 `language` 偏好排序，字幕/音轨偏好设置存 `kv`。

---

## 9. 回退矩阵（决策伪码）

```rust
fn on_error(&self, err: PlaybackError, ctx: &PlayContext) -> RecoveryAction {
    match err.classify() {
        Unsupported => {
            if ctx.policy.allow_fallback && ctx.engine == EngineKind::Media3 {
                RecoveryAction::SwitchEngine(EngineKind::Mpv)
            } else {
                RecoveryAction::Fail { actions: vec![
                    Action::UseExternalPlayer,
                    Action::ReportError,
                ]}
            }
        }
        Auth => RecoveryAction::Fail { actions: vec![
            Action::ChangeUserAgent,
            Action::EditCredentials,
        ]},
        RateLimited => {
            self.release_current();               // 先释放连接
            RecoveryAction::RetryAfter(backoff(ctx.attempt))
        }
        Transient => {
            if ctx.attempt < 3 {
                RecoveryAction::RetryAfter(backoff(ctx.attempt))
            } else if ctx.has_alternative_stream() {
                RecoveryAction::SwitchStream      // 换线路
            } else {
                RecoveryAction::Fail { actions: vec![Action::Retry, Action::ChangeUserAgent] }
            }
        }
        NotFound | Format => {
            if ctx.has_alternative_stream() {
                RecoveryAction::SwitchStream
            } else if ctx.engine == EngineKind::Media3 && ctx.policy.allow_fallback {
                RecoveryAction::SwitchEngine(EngineKind::Mpv)
            } else {
                RecoveryAction::Fail { actions: vec![Action::SwitchStream, Action::ReportError] }
            }
        }
        DeviceLost => RecoveryAction::RecreateEngine,
    }
}
```

---

## 9. Dolby Vision：走纯 Rust，不走 libmpv

### 9.1 问题

| Profile | Base layer | 非 DV 屏上的表现 | ExoPlayer | mpv + libdovi |
|---|---|---|---|---|
| **5 (`dvhe.05`)** | **无**（单层 IPTPQc2） | **无回退**，灰/紫/绿 | ❌ 需要 DV 解码器，MediaCodec 支持依厂商 | ✅ 需 libdovi |
| **7 (MEL/FEL)** | 双层 | MEL≈8.1 | ⚠️ | ✅ 需 libdovi |
| **8.1 (`dvhe.08.01`)** | HDR10 | ✅ 当作 HDR10 播 | ✅ | ✅ |

> IINA#6483：*"Profile 5 has no fallback HDR10 layer. **Without libdovi**, the underlying mpv cannot convert ICtCp colorspace to standard PQ BT.2020, which leads to the green tint."*

### 9.2 方案

**用 Rust 侧的 `dolby_vision` crate（`quietvoid/dovi_tool` 的库部分）在本地做 RPU 转换，把 Profile 5 转成 Profile 8.1，再交给 ExoPlayer。**

`dolby_vision` 提供：
- `dovi_rpu_get_header()` → 探测 profile
- 转换模式：
  - mode 0：原样复制 RPU
  - mode 1：MEL 兼容
  - **mode 3：Profile 5 → 8.1** ← 我们要的
  - mode 2：Profile 8.1 兼容（去掉 P7 FEL 的亮度/色度映射）
  - mode 4：Profile 8.4
  - mode 5：保留亮度/色度映射的 8.1

**优点**：
1. **纯 Rust，MIT/Apache 许可** → 无 GPL 传染、无 Play 拒审风险。
2. **绕开 `gpu-next` 不可用的限制** → 不需要 libplacebo 的色调映射。
3. **不依赖 GPU** → 在弱 SoC 上也能工作。
4. **ExoPlayer 原生支持 HDR10** → TV 盒子普遍支持。

### 9.3 流程

```
检测到 DV 流
  ├─ Profile 8.1 / 10  → 直接交给 ExoPlayer（原生 DV 或 DV10）
  ├─ Profile 5 / 7    → Rust dolby_vision crate: P5 → 8.1 转换
  │                      → 重封装（fMP4）→ ExoPlayer 以 HDR10 播放
  │                      → 无 HDR10 屏则做色调映射到 SDR
  └─ 其他/未知        → 走标准 Media3 轨道选择（优先非 DV 轨道）
```

**注意**：这一步需要 MP4 容器重写（把 DV 的 `dvhe` 轨道换成转换后的 `hvc1`/`hvcC`），属于 `iqtvlite-core` 的可选模块。**v2 实现，v1 先做「检测 + 提示」。**

---

## 10. 验收指标

| 指标 | 目标 |
|---|---|
| 换台首帧 P50 | < 800 ms |
| 换台首帧 P95 | < 2.0 s |
| 冷启动首帧 P50 | < 1.2 s |
| 连续换台 50 次 | 无 OOM、无连接泄漏 |
| 断网 → 恢复 | 自动重连成功 |
| 供应商限流 | 自动退避重试，用户无需手动干预 |
| ExoPlayer 失败 → mpv 兜底 | 自动切换，用户无感（除首帧延迟增加） |
| 8 小时长跑 | 内存增长 < 5%，无音画不同步累积 |
