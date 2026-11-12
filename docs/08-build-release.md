# 08 — 构建、签名与发布

---

## 1. 工程结构

```
iqtvlite/
├── Cargo.toml                  # workspace
├── Cargo.lock                 # 提交
├── crates/
│   ├── iqtvlite-core/         # 纯 Rust 领域层（无 Android 依赖，可在 PC 上跑）
│   │   ├── src/
│   │   │   ├── model/         # Channel / Category / Programme / Playlist / Source
│   │   │   ├── parse/         # m3u / xtream / xmltv
│   │   │   ├── id.rs          # 稳定 ID (xxhash3)
│   │   │   └── error.rs
│   │   └── tests/             # 大量 fixture 测试
│   ├── iqtvlite-store/        # rusqlite 存储层
│   ├── iqtvlite-epg/          # EPG 同步、增量合并、匹配算法
│   ├── iqtvlite-net/          # reqwest 客户端、UA 管理、连接池
│   ├── iqtvlite-player/       # Player trait + 状态机 + fallback 决策
│   ├── iqtvlite-tvfocus/      # 自研 TV 焦点/导航
│   ├── iqtvlite-app/          # Slint UI + 应用状态
│   └── iqtvlite-capi/         # 对 Kotlin 暴露的 C-ABI (cdylib)
├── android/
│   ├── app/                   # Android Gradle 工程
│   │   ├── src/main/
│   │   │   ├── kotlin/iqtvlite/   # 薄壳：Activity / Media3 / SurfaceView
│   │   │   ├── res/                # 主题、图标、banner、leanback 资源
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle.kts
│   ├── gradle/libs.versions.toml
│   └── settings.gradle.kts
├── ui/                        # Slint 源文件 (.slint)
├── docs/
├── scripts/
└── .github/workflows/
```

**关键约束**：`iqtvlite-core` / `store` / `epg` / `net` **不得依赖任何 Android crate**，必须在 PC 上 `cargo test` 全部通过。这是保证核心逻辑可测试、可快速迭代的底线。

---

## 2. 依赖的三层构建

```
┌─ 第 3 层：Android App (Gradle) ────────────────────────────┐
│  打包 .apk / .aab，包含：                                   │
│    ├── lib/<abi>/libiqtvlite.so   ← Rust cdylib            │
│    ├── lib/<abi>/libmpv.so        ← mpv 兜底 (可选 split)  │
│    ├── lib/<abi>/libavcodec.so …  ← FFmpeg (仅 mpv 变体)    │
│    └── classes.dex               ← Kotlin 薄壳              │
└─────────────────────────────────────────────────────────────┘
┌─ 第 2 层：Rust → .so (cargo-ndk) ──────────────────────────┐
│  aarch64-linux-android  (arm64-v8a)  ← 主目标              │
│  armeabi-v7a            (32 位 TV 盒)  ← 次目标            │
│  x86_64                  (模拟器)       ← 仅 debug          │
└─────────────────────────────────────────────────────────────┘
┌─ 第 1 层：libmpv-android AAR (预编译) ─────────────────────┐
│  来源: github.com/jarnedemeulemeester/libmpv-android       │
│  版本: v1.0.0 (mpv 0.42.0 / FFmpeg 9.0.2)                   │
│  许可: GPL-3.0-or-later                                     │
└─────────────────────────────────────────────────────────────┘
```

### 为什么第 1 层用预编译 AAR 而不自己编译

libmpv-android 的构建脚本需要：NDK + meson + ninja + ffmpeg 源码 + libplacebo + libdovi + curl + mbedtls 等，**必须在 Linux 上跑**。Windows 主机上自建成本过高。

**决策**：CI 用 Linux runner 固定版本构建（或直接下载上游 release 的 AAR），把 AAR 作为 artifact 缓存，commit 到 `android/app/libs/` 或走 CI 拉取。

---

## 3. 构建命令

```bash
# 安装工具
rustup target add aarch64-linux-android armeabi-v7a
cargo install cargo-ndk
cargo install cargo-apk2          # 可选：直接打 APK

# 本地开发（需要 Android SDK/NDK 环境变量）
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018

cargo ndk -t arm64-v8a -o android/app/src/main/jniLibs build --release -p iqtvlite-capi

# 核心层测试（PC 上，无需 Android）
cargo test -p iqtvlite-core -p iqtvlite-store -p iqtvlite-epg -p iqtvlite-net
```

---

## 4. APK 体积预算

| 组成 | 大小 (arm64) | 说明 |
|---|---|---|
| Rust core `.so` (release + LTO + strip) | ~3–4 MB | 含 reqwest/rustls/rusqlite/slint |
| Slint + Skia | ~10 MB | **地板**，无法压缩 |
| Kotlin 薄壳 + Media3 | ~4–6 MB | dex + Media3 |
| 资源（图标/字体/banner） | < 1 MB | |
| **小计（无 mpv）** | **~18–21 MB** | |
| libmpv + FFmpeg .so | ~25–35 MB | 仅 mpv 兜底变体 |
| **含 mpv** | **~45–55 MB** | |

### 体积策略

1. **发布两个变体**：
   - `iqtvlite-lite` — **不含 mpv**，只有 Media3。体积最小，覆盖 99% 场景。
   - `iqtvlite` — 含 mpv 兜底。
   → 这直接呼应「lite」的产品定位，也是一个自然的商业模式分层。
2. **单 ABI split**（`arm64-v8a` 独立 APK），Google Play 用 App Bundle 自动分发。
3. `release` profile：`lto = "fat"`、`codegen-units = 1`、`panic = "abort"`、`strip = true`、`opt-level = "z"`（或 `"s"`）。

```toml
# Cargo.toml
[profile.release]
opt-level = "z"
lto = "fat"
codegen-units = 1
panic = "abort"
strip = true
incremental = false
```

> ⚠️ `opt-level = "z"` 会牺牲一些运行速度。**启动性能和体积需要权衡** —— 建议先测 `"s"` 与 `"z"` 的差异（见 `07-roadmap.md` 的性能验证项）。

---

## 5. CI (GitHub Actions)

```yaml
# .github/workflows/ci.yml 概念结构
jobs:
  core-test:            # ubuntu — 最快反馈
    - cargo test --workspace --exclude iqtvlite-capi
    - cargo clippy -- -D warnings
    - cargo fmt --check

  android-build:        # ubuntu
    - cargo ndk -t arm64-v8a,armeabi-v7a build --release -p iqtvlite-capi
    - ./gradlew :app:assembleRelease
    - 上传 artifact + 体积报告（对比基线，超标即失败）

  apk-size-gate:        # 体积预算守门
    - 检查 APK 各组成大小，超预算则 fail

  nightly-libmpv:       # 每周 — 探测 libmpv-android 上游新版本
    - 读取上游 releases，检测到新版本则开 PR 更新版本号
```

**体积门禁（size gate）**：CI 里比对 APK 各部分体积与基线，超出阈值直接失败。轻量是产品承诺，必须有自动化守门。

---

## 6. 签名与发布

- **Debug keystore**：本地开发用，提交到 `.gitignore`。
- **Release keystore**：CI secret `ANDROID_KEYSTORE_BASE64` + `ANDROID_KEY_ALIAS` + `ANDROID_KEY_PASSWORD`。
- **Play App Signing**：用 Play App Signing (PAB)，本地 keystore 只用于上传密钥。
- **分阶段发布**：内部测试 → 公开测试 → 10% → 50% → 100%。
- **版本号**：`versionName` 用语义化版本；`versionCode` 用 CI 递增。

---

## 7. 许可与第三方声明

发布前必须生成 `THIRD_PARTY_NOTICES`，列出：

| 组件 | 版本 | 许可证 | 源码 |
|---|---|---|---|
| mpv | 0.42.0 | GPL-2.0-or-later | github.com/mpv-player/mpv |
| FFmpeg | 9.0.2 | GPL-3.0-or-later | github.com/FFmpeg/FFmpeg |
| libplacebo | 7.387.0 | LGPL-2.1-or-later | code.videolan.org/videolan/libplacebo |
| libdovi | 3.3.2 | MIT | github.com/quietvoid/dovi_tool |
| Media3 | — | Apache-2.0 | github.com/androidx/media |
| Slint | 1.18.1 | Royalty-free 2.0 / GPL-3.0 | github.com/slint-ui/slint |
| Rust crates | 见 `docs/03` §7 | MIT / Apache-2.0 | — |

**GPL §6 义务**：随 APK 分发的 libmpv/FFmpeg 需要提供**完整对应源码 + 构建脚本**。做法：
- 在 GitHub Release 里附 `libmpv-source.tar.gz`（上游已提供 tarball）。
- 应用内「开源许可」页链接到该 release。
- 固定并记录构建版本（`buildscripts/include/depinfo.sh`）。

---

## 8. 本地调试技巧

```bash
# 看 Rust 侧日志（logcat）
adb logcat -s iqtvlite:V RustStdout:V

# 性能
adb shell dumpsys gfxinfo tv.iqtvlite.iqtvlite

# 内存
adb shell dumpsys meminfo tv.iqtvlite.iqtvlite

# 冷启动耗时
adb shell am start -W -n tv.iqtvlite.iqtvlite/.MainActivity

# Tracer for Android TV：可直接看 Surface 合成层，验证视频层与 UI 层的叠放
```

**调试 Surface 合成问题**时，Android Studio 的 **Layout Inspector** 或 **`dumpsys SurfaceFlinger --list`** 能看到 layer 层级 —— 这是验证 ADR-008（Slint + SurfaceView 合成）的关键工具。
