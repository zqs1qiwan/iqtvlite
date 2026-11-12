# 03 — 技术选型报告

> 每个决策包含：**选项 → 评估 → 结论 → 被否决理由**。原则：Android TV 优先，纯 Rust 优先，稳定性优先于功能数量。

---

## 1. UI 框架

### 候选

| 方案 | D-pad | APK 体积 | 中文排版 | 许可 | 成熟度 |
|---|---|---|---|---|---|
| **Slint 1.18** | 需自研焦点 | ~11–15 MB | ✅ 系统字体 | GPL-3.0 / Royalty-free / 商业 | ✅ 生产可用 |
| egui | 需自研焦点 | 最小（~5 MB） | ⚠️ 需手动字体栈 | MIT/Apache | ✅ |
| iced | 需自研焦点 | 中 | ⚠️ | MIT | ✅ |
| Dioxus 0.7 | ⚠️ | 中 | ✅ | MIT/Apache | ⚠️ |
| Tauri 2 + Web | ❌ WebView 焦点差 | 大（含 WebView） | ✅ | MIT/Apache | ✅ |
| Compose TV (Kotlin) | ✅ 开箱即用 | 中 | ✅ | Apache | ✅ |

### 决策：**Slint**

**理由**：
1. **Slint 是唯一有 Android TV 落地实证的纯 Rust GUI**（`rusttv` spike 全部问题得到肯定答案）。
2. **体积可控**：Skia 是 ~10 MB 地板，但整个 APK 能做到 11.5 MB，且**不含 WebView**。对比 Tauri 方案要带一个 WebView 运行时（Android TV 上 WebView 版本参差，冷启动差异可达数百毫秒）。
3. **无 JS 运行时、无 V8/JSC**，冷启动没有「引擎预热」环节。
4. **中文字体**：Android 后端用系统字体（Roboto + 系统 CJK 回退），无需自己打包字体。

**但有六个必须知道的硬事实（全部来自源码/issue 验证）**：

| # | 事实 | 影响 |
|---|---|---|
| 1 | **Android 上只能用 Skia**（`i-slint-backend-android-activity` 无条件依赖 `i-slint-renderer-skia`；FemtoVG 因 fontconfig 问题被显式排除）。`BackendSelector` 会拒绝 `renderer != "skia"` | 没得选，也不用选 |
| 2 | **wgpu-on-Android = Skia-on-wgpu，且只编译 Vulkan**（无 GLES 回退） | **TV 设备上不要依赖 wgpu**。用 `require_opengl_es()` 更稳 |
| 3 | **NDK 必须 r30+**（skia-safe 0.157 用了新 libc++ 符号，NDK < r30 启动即崩，slint#14824） | 构建环境硬约束 |
| 4 | **媒体按键被静默丢弃** —— `MediaPlayPause` / `ChannelUp` / `ChannelDown` / `Info` / `Guide` 在 Slint 的映射表里全是 `None` | **必须自己在 Kotlin 层拦截并转发到 Rust** |
| 5 | **无空间焦点导航** —— 源码确认只有线性 tab 焦点链（`ForwardFocus`/`PreviousFocus` 已被移除） | 必须自研焦点层（见 §6） |
| 6 | **Android 上 `import` 打包字体不生效**（slint#11725，维护者明确不支持） | **中文 UI 必须用 fontique 运行时 API 注册字体**，见下 |

**中文排版的坑（必须处理）**：
- fontique 的 Android 后端读 `/system/etc/fonts.xml` 构建 CJK 回退链。
- 但**部分国产 ROM 的 fonts.xml 有问题** → 中文渲染成豆腐块（slint#11693，OnePlus/ColorOS 实测：「测试」不显示但「字体渲染」正常）。
- → **必须内置一份中文字体**（如 Noto Sans SC / 思源黑体），并通过 fontique 运行时 API 注册：

```toml
slint = { version = "=1.18.1", features = ["backend-android-activity-06", "unstable-fontique-011"] }
```
```rust
use slint::fontique_011::fontique;

let bytes = std::fs::read("/data/data/tv.iqtvlite.app/fonts/NotoSansSC-Regular.otf")?;
let blob = fontique::Blob::new(std::sync::Arc::new(bytes));
let mut collection = slint::fontique_011::shared_collection();
let fonts = collection.register_fonts(blob, None);
collection.append_fallbacks(
    fontique::FallbackKey::new(fontique::Script::from_str_unchecked("Hani"), None),
    fonts.iter().map(|x| x.0),
);
```
> 注意 `fontique_0NN` 的后缀**每次 fontique 升级都会变**（1.16/1.17 是 `010`，1.18.1 是 `011`，master 是 `012`）→ **必须精确锁定 Slint 版本**。

**Slint 的三个代价（接受）**：
- Skia 是 APK 体积地板（~10 MB）→ 接受。
- 无空间导航 → 自研焦点层。
- Android 窗口**不透明** → 见 §2b，这是最大的架构约束。

**为什么不是 Compose TV**：虽然 `androidx.tv` 的焦点/遥控器支持开箱即用最省事，但那会让 UI 层脱离 Rust，与「全 Rust + 未来跨端」的架构目标冲突，且引入完整 Compose 运行时（内存 + 冷启动）。**但如果 Slint 的视频合成问题无法解决，Compose TV 是 B 计划**（见 §2b）。

**为什么不是 Tauri**：WebView 在 Android TV 上体验参差（不同厂商 WebView 版本差异大、冷启动慢、焦点导航需要大量 JS 补丁）。

**焦点模型自研方案**（Slint 原生能力不足的补偿）：
```
TvFocusScope (Rust)
├── 逻辑方向（Up/Down/Left/Right）→ 邻接表查找
├── 几何方向 → 按屏幕坐标 + 视口裁剪的候选排序
├── 焦点环 → FocusScope 的 has-focus + 自绘高亮
└── 滚动跟随 → 焦点移动时虚拟列表 scroll_to_item()
```
只实现**规则网格**（EPG 网格、频道列表、分类栏）—— 这类界面的空间导航可以退化为「网格坐标 + 行列步进」，比通用几何算法更可靠。这也是 TiviMate 的实际交互模型。

---

## 2. 播放引擎

### 候选

| 方案 | Android TV 可用性 | 起播速度 | 长尾编码 | 体积 | 复杂度 |
|---|---|---|---|---|---|
| **Media3/ExoPlayer 优先 + mpv 兜底** | ✅ | ⚡ 最快 | ⚠️ 需 mpv 补 | 大（+mpv） | 中 |
| 纯 libmpv | ❌ Mali GPU 翻车 | 慢 | ✅ 最强 | 中 | 低 |
| 纯 Rust 解复用 + 平台硬解 | ✅ | ✅ | ⚠️ | 最小 | **极高** |

### 决策：**双引擎，Media3 优先**

```
启动播放请求
    │
    ├─▶ [主] Media3 / ExoPlayer
    │      ├─ 成功 → 播放
    │      └─ 失败 → 判断错误类型
    │            ├─ CodecNotSupported / 格式不支持 → [兜底] mpv
    │            ├─ 网络超时 / HTTP 4xx/5xx → 重试 → 失败切换备用线路 → 失败报错+指引
    │            └─ 认证/鉴权失败 → 报错 + 提示切换 UA
    │
    └─▶ [兜底] mpv (libmpv-android AAR)
           └─ 仅用于 Dolby Vision P5 等 ExoPlayer 无法解的内容
```

**理由**：
1. **Android TV 上 mpv 不可作为主引擎**（Mali-G31 绿屏/黑屏，见 `01` §3 结论 1）。
2. **ExoPlayer 在直播起播上明显更快**：内部优化过 HLS/TS 解析与缓冲策略。
3. **mpv 兜底覆盖真实长尾**：Media3 的 `media3-exoplayer-rtsp` **不支持 HEVC** —— 若供应商用 RTSP 提供 H.265，只有 mpv/FFmpeg 能播。**这是保留 mpv 兜底最强的理由。**
4. **双引擎可按频道手动覆盖**（照搬 OwnTV 的设计）：用户遇到某个频道 ExoPlayer 起不来，可以锁定该频道用 mpv。

#### mpv 后端的六条现实约束（必须写进文档）

| # | 约束 | 影响 |
|---|---|---|
| 1 | **不能用 `--wid` 嵌入** | 必须用 **Render API**（`MPV_RENDER_PARAM_API_TYPE_OPENGL` + EGL 上下文共享） |
| 2 | **`gpu-next` 在 Render API 下不可用**（mpv#10810） | **失去 libplacebo 的 DV RPU 渲染、HDR 色调映射、高级缩放** —— mpv 兜底路径的最大短板 |
| 3 | **`hwdec=mediacodec-copy` 是错误选项** | copy 路径正是 Mali 绿屏的根源（mpv-android#1088，至今未修）。正确：`hwdec=mediacodec`（HW+，零拷贝） |
| 4 | **HW+ 无色调映射** | HDR→SDR 显示错误。`hwdec=mediacodec` + `vo=mediacodec_embed` 最稳但失去 OSD/字幕/GL 合成 |
| 5 | **Android 上无原生 HDR** | mpv-android#874：需 Vulkan，而标准 APK 不含 Vulkan |
| 6 | **mpv-android 已被 Play 以 Android TV 理由下架** | 捆绑 libmpv 会**增加我们被 Play 拒审的风险**（Jellyfin 也因此从 TV 构建移除了 mpv） |

#### 产品策略调整：双变体

| 变体 | 内容 | 定位 |
|---|---|---|
| **`iqtvlite-lite`** | 仅 Media3，**不含 libmpv** | 默认推荐。无 GPL 负担、无 Play 拒审风险、体积最小 |
| **`iqtvlite`** | Media3 + libmpv 兜底 | 面向愿意 sideload 的高级用户，处理 RTSP/HEVC 等长尾 |

**并且**：Dolby Vision Profile 5 的支持**不依赖 libmpv** —— 用 Rust 侧 `dolby_vision` crate（libdovi 的 Rust 实现）把 P5 转成 P8.1（带 HDR10 base layer），ExoPlayer 就能以 HDR10 播放。**纯 Rust，无 GPL 传染，绕开所有硬件限制。**

**被否决：纯 Rust 自研传输层**。Limpido 用这条路线做到 25 MB Android TV APK，但需要自实现 TS PAT/PMT/PES 解析、PCR 跟踪、HLS 调度、fMP4 解析 —— 工程量以**人年**计，且直接威胁「稳定性」目标。**v2 再评估**，v1 优先把产品做对。

**被否决：纯 libmpv**。Android TV 上不可靠。

---

## 2b. 🔴 视频合成架构：Slint 的硬约束（**最重要的未决项**）

> 完整分析见 `06-android-tv.md` §1。这里只给决策摘要。

**问题**：Slint 的 Android 后端硬编码 `transparent = false`，Surface 从不设为 `PixelFormat.TRANSLUCENT`，且**没有任何 API 能把 `ANativeWindow`/`Surface` 交给 Slint**。
→ **SurfaceView 视频层无法穿透 Slint 窗口。** 这不是 Z-order 问题，是 **alpha** 问题。

**四条路径**：

| 路径 | 做法 | HDR | DRM | 工作量 | 风险 |
|---|---|---|---|---|---|
| **P1** patch Slint 透明标志 + 让 Slint 渲染进我们自建的 SurfaceView | fork `slint`（~2 行）+ 可能要动 `android-activity` 的窗口获取 | ✅ | ✅ | 高 | 🟠 供应链（Skia 预编译二进制）、上游冲突 |
| **P2** Slint 只做浏览屏 + 原生播放屏 | 浏览全 Slint；播放 = SurfaceView + ExoPlayer + 约 200 行 Kotlin OSD | ✅ | ✅ | 中 | 🟠 OSD 非 Rust；两 Activity |
| **P3** GL underlay | ExoPlayer → SurfaceTexture → OES 纹理 → `set_rendering_notifier(BeforeRendering)` | ❌ | ❌ | 中高 | 🔴 HDR/DRM 受损，Slint 无文档支持 |
| **P4** 换 Compose for TV | Kotlin UI + Rust core | ✅ | ✅ | 低 | 🔴 丢掉 Rust UI 的轻量优势 |

**建议**：**v1 走 P2，M0 spike 并行验证 P1**。
- P2 零 patch、零上游耦合，1–2 周可确定跑通，能保证交付。
- P1 是「Slint 一统」的必经之路，但必须先验证「Slint 能否渲染进我们自建的 SurfaceView」—— 这是 M0 的第一号任务。

**降级链**：P2 → P4（Compose TV）→ P3。

---

## 3. 语言分层：Rust 90% + Kotlin 薄壳

### 决策

| 层 | 语言 | 理由 |
|---|---|---|
| UI | **Rust** (Slint) | 目标 |
| 领域逻辑 / 解析 / 缓存 / 调度 | **Rust** | 目标 |
| 播放控制 (Media3) | **Kotlin** | Media3 只有 Java/Kotlin API |
| 视频 Surface | **Kotlin** | SurfaceView 必须在 View 体系 |
| Activity / Service 装配 | **Kotlin** | Android 生命周期 |

### Rust ↔ Kotlin 边界设计

**不让 Rust 直接用 `jni` crate 手搓 Media3 调用** —— 会写出一堆脆弱的 JNI 样板。改为：

```
Rust (iqtvlite-player)
    │  C-ABI (extern "C", #[repr(C)])  ← 稳定、无 GC、跨语言友好
    ▼
Kotlin (iqtvlite-android)
    │  JNI
    ▼
Media3 ExoPlayer
```

C-ABI 设计要点：
- 所有字符串用 `*const c_char`（UTF-8），由 Kotlin 侧负责转换。
- 回调用**函数指针 + userdata**，Kotlin 侧注册，Rust 侧触发。
- 事件用**扁平的结构体**（固定字段，非字符串 map），避免跨语言序列化开销 —— 这是「丝滑」的一部分。
- 播放状态机在 **Rust 侧**（决定何时重试、何时降级、何时提示），Kotlin 只做**执行**。

这样做的收益：Rust 侧可以写**纯单测**覆盖 fallback 决策逻辑，不需要 Android 设备。

---

## 4. 数据层：SQLite (rusqlite) + FTS5

### 决策：**rusqlite**（bundled 特性，静态链接 SQLite）

**理由**：
- 无外部依赖，单文件，无服务进程 —— 符合「轻量」。
- **FTS5** 提供频道 + 节目名的全文搜索，且能对中文做 `unicode61` 分词（配合 `trigram` 或自定义 tokenizer）。
- `rusqlite` v0.42 支持 Android NDK 交叉编译（bundled 特性会用 NDK clang 编 C）。
- 对比 `sqlx`（异步、需连接池、编译期校验宏在 Android 上有坑）→ 选 rusqlite。
- 对比 `redb`（纯 Rust KV）→ 我们的查询是关系型的（EPG join、排序、分组），SQL 更合适。

**写入策略**：单写者线程 + WAL 模式，批量事务提交（1000 条一批），避免 EPG 导入时卡顿。

---

## 5. 网络层：reqwest + rustls

- `reqwest` 0.13 + `rustls`（不用 OpenSSL，Android 上省掉一个 .so，也避免 NDK 交叉编译地狱）。
- `rustls` v0.24 目前是 `0.24.0-dev.2`，需要在 CI 锁定版本。
- **HTTP 客户端池按 host 复用** —— 这是换台速度的关键之一：TLS 握手复用可以省掉 200–500 ms。
- 直播流本身**不走 reqwest**，交给 Media3 自己的 HttpDataSource（它有自己更激进的连接管理）。

---

## 6. 焦点与导航（自研层）

Slint 没有内置空间导航。我们自研 `iqtvlite-tvfocus`：

```rust
pub enum FocusAxis { Up, Down, Left, Right }

pub struct FocusGrid {
    /// 每个可聚焦项的逻辑位置
    items: Vec<FocusItem>,
    /// 行列索引 → item，用于网格快速跳转
    rowcol: HashMap<(u16, u16), FocusIndex>,
}

pub trait FocusScope {
    /// 返回在给定方向上最合适的候选；None = 交给父级
    fn candidate(&self, axis: FocusAxis, from: FocusIndex) -> Option<FocusIndex>;
}
```

实现策略：
- **规则网格**：EPG 网格（行=频道，列=时间片）、频道列表（单列）、分类栏（横排）。这三类覆盖 95% 的导航需求。
- **焦点移动触发的滚动**：焦点改变时调用虚拟列表的 `scroll_to_item(index, Align::Center)`。
- **焦点环**：Slint `FocusScope` 的 `has-focus` 驱动边框绘制，用 `BorderRadius` + 品牌色。

---

## 7. 关键 crate 清单

```toml
# UI
slint = { version = "=1.18.1",           # ← 必须精确锁定（内部 crate 有 patch 级 breaking change）
          features = ["backend-android-activity-06", "unstable-fontique-011"] }
slint-build = "=1.18.1"
# Android 后端只用 Skia（不可选）；wgpu-on-Android 仅 Vulkan，不建议用于 TV
# 构建要求: NDK r30+（skia-safe 0.157）, rustup target add aarch64-linux-android

# Android 桥
android-activity = "0.6"            # Activity 生命周期 + 原生窗口；Slint 重导出为 slint::android::android_activity
jni = "0.22"                        # 仅在 Kotlin 桥层用
ndk = "0.9"                         # ANativeWindow 互操作 (rwh_06)
android-build = "0.1"               # javac + d8 驱动（Slint 的 build.rs 也用）

# 领域
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"
quick-xml = "0.42"                  # 流式 XMLTV 解析
roxmltree = "0.21"                  # 备选（DOM 风格）
url = "2.5"
time = { version = "0.3", features = ["serde", "local-offset"] }
xxhash-rust = { version = "0.8", features = ["xxh3"] }
uuid = { version = "1", features = ["v4", "serde"] }
percent-encoding = "2"

# 存储
rusqlite = { version = "0.42", features = ["bundled", "backup"] }

# 网络
reqwest = { version = "0.13", default-features = false,
            features = ["rustls-tls", "gzip", "brotli", "stream"] }
tokio = { version = "1", features = ["rt-multi-thread", "macros", "fs", "sync", "time"] }
urlencoding = "2"

# 并发
rayon = "1.12"
parking_lot = "0.13"

# 可观测
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
tracing-android = "0.3"             # 输出到 logcat

# 播放（mpv 后端，仅完整版变体）
rsmpv = { version = "0.2", features = ["render"] }   # clean-room FFI, 无 bindgen/libclang
                                       # ← 优于 libmpv2：后者用 bindgen，NDK 交叉编译需要 libclang
libmpv2 = { version = "7", optional = true }          # 备选

# Dolby Vision（纯 Rust 处理 DV P5 → P8.1，绕开 libmpv）
dolby_vision = "0.6"                                   # dovi_tool 的 Rust 库
```

**待定（需 PoC 对比）**：`crispy-m3u` / `crispy-xmltv` / `crispy-xtream` 是否直接采用，还是自研。
评估维度：流式解析性能（XMLTV 文件常达 50–200 MB）、API 稳定性、错误处理质量、许可证。

> ### ⚠️ ADR-007 最终结论：**自研，不采用 `crispy-*`**
>
> `crispy-*` 全家桶（`crispy-iptv-types` / `crispy-m3u` / `crispy-xmltv` / `crispy-xtream` / `crispy-stalker` / `crispy-catchup` / `crispy-stream-checker` / `crispy-iptv-tools` / `crispy-media-probe`）由 Mo Abualruz 维护，是 TypeScript 实战库（`@iptv/playlist`、`ektotv/xtream-api`）的 Rust 移植，**技术质量很高**。
>
> **但它们的许可证是 `CC-BY-NC-SA-4.0`（含 NonCommercial 条款）**，与 GPL-3.0-or-later **不兼容**（FSF 立场：CC BY-NC-SA 4.0 不是 GPL-compatible license），且：
> - 会让整个产品**永久锁定为非商业**；
> - 与 `iqtvlite-lite` 付费变体的商业化路径直接冲突；
> - ShareAlike 条款要求衍生作品同样以 CC-BY-NC-SA 分发。
>
> **处置**：
> 1. **不作为依赖引入**（`Cargo.toml` 里不出现 `crispy-*`）。
> 2. **作为协议实现的高质量参考**研究：M3U 属性全集、Xtream 端点与字段名、catchup 模板语义、稳定 ID 策略、`StreamFormat`/`StreamProtocol` 分类逻辑。
> 3. 我们按 **GPL-3.0-or-later** 独立实现同等能力的 `iqtvlite-core`。
>
> **注意**：协议本身（M3U 属性名、Xtream HTTP 端点、XMLTV 标签）是**行业规范与事实**，不受版权保护；受保护的是其代码表达。我们独立实现，不复制代码。


---

## 8. 被否决方案汇总

| 方案 | 否决理由 |
|---|---|
| **Tauri 2 + Web 前端** | WebView 冷启动慢、焦点导航差、Android TV 上 WebView 版本碎片化；与「更轻量」目标冲突 |
| **Compose for TV (Kotlin) UI** | UI 层脱离 Rust，违反架构目标；引入 Compose 运行时 |
| **纯 libmpv 引擎** | Android TV + Mali GPU 上不可靠 |
| **纯 Rust 自研传输栈** | 工程量以人年计，威胁稳定性目标 → 推迟到 v2 |
| **Electron** | 荒谬 |
| **Web 技术栈 (React Native / Flutter)** | 运行时开销与「轻量」目标冲突；Flutter 在 Android TV 上无焦点/遥控器生态 |
| **纯 egui** | 焦点模型与文本栈在 TV 上要造的东西太多；Slint 有实证 |
| **Dioxus/WASM** | WASM 运行时 + 无实证；且 Dioxus 的 TV 焦点支持未知 |
| **自研 SQLite 封装** | 无必要，`rusqlite` 成熟 |
| **Stalker/Ministra 协议（v1）** | 协议复杂度高、供应商少、收益低 → v2 |

---

## 9. 决策记录（ADR 索引）

| # | 决策 | 状态 |
|---|---|---|
| ADR-001 | 目标平台 = Android TV / Google TV | ✅ 已定 |
| ADR-002 | UI = Slint 纯 Rust | ✅ 已定 |
| ADR-003 | 双引擎：Media3 优先 + mpv 兜底 | ✅ 已定 |
| ADR-004 | Rust/Kotlin 边界 = C-ABI | ✅ 已定 |
| ADR-005 | 存储 = rusqlite + FTS5 | ✅ 已定 |
| ADR-006 | 自研 TV 焦点层 | ✅ 已定 |
| ADR-007 | 协议层**自研**（不采用 `crispy-*`） | ✅ 已定 —— 见下 |
| ADR-008 | Slint 与视频 Surface 的合成方案 | ✅ **已定：Option D2**（Kotlin 持有 Activity，FrameLayout 内 SurfaceView + 原生 UI View 为兄弟节点，靠 Z-order 而非 gatherTransparentRegion） |
| ADR-009 | EPG 增量同步算法 | 🔶 待设计 |
| ADR-010 | mpv AAR 版本固定与源码分发义务 | 🔶 待定（版本实际为 mpv 0.41.0 + FFmpeg 8.1，非 0.42/9.0） |
| ADR-011 | **Dolby Vision 走 Rust `dolby_vision` crate 而非 libmpv** | ✅ 已定 —— 绕开 GPL、Play 拒审风险与 gpu-next 不可用三重限制 |
| ADR-012 | **不发布系统 EPG**（`TvContract.Programs`），改用 Home Screen `PreviewChannel`/`PreviewProgram` | ✅ 已定 —— TIF 仅 OEM 可用（API 21–25） |
| ADR-013 | **不使用 Android 系统 PiP** | ✅ 已定 —— TV 上媒体类应用被 `com.google.android.tv.pip.category` 明确禁止；改用应用内 mini-player |
