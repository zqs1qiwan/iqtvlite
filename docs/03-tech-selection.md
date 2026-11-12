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
2. **体积可控**：Skia 是 ~10 MB 地板，但整个 APK 能做到 11–15 MB，且**不含 WebView**。对比 Tauri 方案要带一个 WebView 运行时（Android TV 上 WebView 版本参差，冷启动差异可达数百毫秒）。
3. **无 JS 运行时、无 V8/JSC**，冷启动没有「引擎预热」环节。
4. **Slint 的 focus 是声明式且可控**：`FocusScope` + `focus-changed` 回调，足够构建可靠的 TV 焦点模型。
5. **中文字体**：Android 后端用系统字体（Roboto + 系统 CJK 回退），无需自己打包字体。

**Slint 的三个已知代价（接受）**：
- Skia 是 APK 体积地板（~10 MB）→ 接受。
- **无内置空间导航**（只能"上一个/下一个"）→ 自研焦点层，见 §6。
- Android 后端**固定用 Skia**，不能用 wgpu → 接受。

**为什么不是 Compose TV**：虽然 `androidx.tv` 的焦点/遥控器支持开箱即用最省事，但那会让 UI 层脱离 Rust，与「全 Rust + 未来跨端」的架构目标冲突，且引入完整 Compose 运行时（内存 + 冷启动）。

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
2. **ExoPlayer 在直播起播上明显更快**：内部优化过 HLS/TS 解析与缓冲策略，冷启动到首帧通常 < 1 秒。
3. **mpv 兜底覆盖长尾**：Dolby Vision Profile 5 在非 DV 硬件上 ExoPlayer 解不了，需要 mpv + libplacebo `vo=gpu-next` 做色调映射。
4. **双引擎可按频道手动覆盖**（照搬 OwnTV 的设计）：用户遇到某个频道 ExoPlayer 起不来，可以锁定该频道用 mpv。

**mpv 后端的现实约束（必须写进文档）**：
- libmpv 在 Android TV 上仍可能因 GPU 失败 → 兜底路径**不保证 100% 可用**，UI 必须如实告知。
- mpv 需要 **Render API**（不能 `--wid` 嵌入，Android 上不工作）：用 `MPV_RENDER_PARAM_API_TYPE_OPENGL` + EGL 上下文共享。
- 启用 `MPV_RENDER_PARAM_ADVANCED_CONTROL` 时**必须遵守线程规则**：渲染线程不得等待 mpv core，否则死锁。

**被否决：纯 Rust 自研传输层**。Limpido 用这条路线做到 25 MB Android TV APK，但需要自实现 TS PAT/PMT/PES 解析、PCR 跟踪、HLS 调度、fMP4 解析 —— 工程量以**人年**计，且直接威胁「稳定性」目标。**v2 再评估**，v1 优先把产品做对。

**被否决：纯 libmpv**。Android TV 上不可靠。

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
slint = "1.18"                      # + backend-android-activity-06
slint-build = "1.18"

# Android 桥
android-activity = "0.6"            # Activity 生命周期 + 原生窗口
jni = "0.22"                        # 仅在 Kotlin 桥层用
ndk = "0.8"                         # ANativeWindow 互操作

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

# 播放（mpv 后端）
libmpv2 = { version = "7", features = ["render"] }   # 或 rsmpv
```

**待定（需 PoC 对比）**：`crispy-m3u` / `crispy-xmltv` / `crispy-xtream` 是否直接采用，还是自研。
评估维度：流式解析性能（XMLTV 文件常达 50–200 MB）、API 稳定性、错误处理质量、许可证。

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
| ADR-007 | 协议层自研 vs 采用 `crispy-*` | 🔶 待 PoC |
| ADR-008 | Slint 与视频 Surface 的合成方案 | 🔴 **待 spike（最高风险）** |
| ADR-009 | EPG 增量同步算法 | 🔶 待设计 |
| ADR-010 | mpv AAR 版本固定与源码分发义务 | 🔶 待定 |
