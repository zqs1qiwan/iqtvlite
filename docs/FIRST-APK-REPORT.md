# 第一版测试 APK — 交付报告

> 日期：2026-11-16
> 目标：按推荐路线（轻量 / 稳定 / 高性能，最终对齐 TiviMate 全功能）交付**可安装、可播放指定播放列表**的第一版测试 APK。

---

## 1. 交付物

| 项 | 值 |
|---|---|
| **GitHub 仓库** | https://github.com/zqs1qiwan/iqtvlite |
| **Release（可直接下载 APK）** | https://github.com/zqs1qiwan/iqtvlite/releases/tag/v0.1.0-test |
| **APK 文件名** | `iqtvlite-0.1.0-test.apk` |
| **体积** | **11.29 MB**（CI 构建）/ 11.67 MB（本地构建） |
| **包名** | `tv.iqtvlite.app` |
| **ABI** | `arm64-v8a` + `armeabi-v7a`（均含 Rust core `.so`） |
| **minSdk / targetSdk** | 26 / 35（Android TV 8.0+） |
| **启动器** | Leanback（Android TV）+ Launcher（手机/平板可装） |

CI 状态（最后一次提交 `aaaed52`）：
- ✅ `Build Test APK` — success（1m54s）
- ✅ `CI`（fmt + clippy + test + 许可审计）— success（2m44s）

---

## 2. 指定的两个播放列表

| # | URL | 实测结果 |
|---|---|---|
| 1 | `https://lbzs.laobaitv.net` | **真实 M3U**（UTF-8），91 个 `#EXTINF`、90 个有效流、1 个被 `#` 注释的失效流；头部 `x-tvg-url="https://epg.laobaitv.net"` |
| 2 | `http://iptv.laobaitv.net` | **HTML 落地页**，App 会自动从页面中正则提取真实 `.m3u8` / playlist API 地址再加载 |

两个源都配置为 App 启动时的默认源，在下拉框中可切换。

### 播放列表解析验证（Rust core）

`crates/iqtvlite-tests/tests/laobaitv.rs` 用真实格式特征做了 5 个回归测试：

| 测试 | 验证内容 |
|---|---|
| `laobaitv_header_epg_url_is_parsed` | `x-tvg-url` 被正确解析为 EPG 源 |
| `laobaitv_channels_are_parsed` | 中文 `tvg-id` / `tvg-name` / `group-title`、Kodi 风格 `\|User-Agent=VLC` 后缀、`http-user-agent` 属性提升 |
| `laobaitv_groups_are_chinese` | 中文分组（央视频道 / 卫视频道 / 老白TV-其他直播源）正确切分 |
| `commented_out_stream_is_skipped` | 被 `#` 注释的失效频道不会出现在列表里 |
| `playback_url_suffix_preserved_for_player` | `\|User-Agent=VLC` 保留给播放器层剥离 |

> 解析过程中**真实修了一个 bug**：播放列表里用 `#http://...` 注释掉失效频道，
> 原来解析器会把对应的 `#EXTINF` 作为孤儿条目输出到列表里；现已修复（文件末尾的 pending 条目若无有效 URL 则丢弃）。

---

## 3. 技术架构（v1 测试 APK 的务实取舍）

```
┌────────────────────────────────────────────────────────────┐
│ Kotlin 原生 Views（UI 层）                                   │
│  - 频道列表 / 分组栏 / 搜索 / 下拉切源                         │
│  - Media3 ExoPlayer 1.11.1（直播主引擎）                      │
│  - MediaSessionService（媒体键 + 音频焦点）                   │
└──────────────────────────┬─────────────────────────────────┘
                           │ JNI
┌──────────────────────────▼─────────────────────────────────┐
│ iqtvlite-capi（C-ABI + JNI 桥，仅 560 KB）                  │
│  - parseM3u / stableId / extractQuery                       │
└──────────────────────────┬─────────────────────────────────┘
                           │
┌──────────────────────────▼─────────────────────────────────┐
│ Rust core（iqtvlite-core / store / epg / net / player）     │
│  - M3U / XMLTV / Xtream 解析                                │
│  - Catch-up 模板引擎（6 套语法）                            │
│  - 稳定 ID / EPG 匹配 / 回退决策状态机                       │
│  - 134 个测试验证                                           │
└────────────────────────────────────────────────────────────┘
```

**为什么第一版不是 Slint UI**：调研确认 Slint 的 Android 窗口**硬编码不透明**（`transparent = false`），SurfaceView 视频层无法穿透显示 —— 真正跑通需要 fork Slint + 改 `android-activity`，风险与工期都远超一个「能跑的测试 APK」。
因此采用 P2 路线（Kotlin 原生 UI + Rust core），**底层架构完全复用**，Slint 是后续 UI 层的替换，不影响数据/播放逻辑。详见 `docs/06-android-tv.md` §1。

---

## 4. 已实现功能（第一版）

| 功能 | 状态 |
|---|---|
| 加载两个默认播放列表（含 HTML 落地页自动提取真实地址） | ✅ |
| 频道列表（中文名 / logo / 分组 / 编号） | ✅ |
| 分组横向 chips 过滤 + 搜索框实时过滤 | ✅ |
| 下拉切换播放列表源 + 下拉刷新 | ✅ |
| Media3 直播播放（HLS / DASH / RTSP / TS） | ✅ |
| TiviMate UA 默认（兼容供应商限制） | ✅ |
| Kodi 风格 `\|User-Agent=VLC` 后缀剥离 | ✅ |
| CH+ / CH- 快速换台 + 方向键选台 | ✅ |
| 播放失败自动切换备用线路 | ✅ |
| 低延迟缓冲参数（targetOffset 3s / 0.97–1.03 倍速追帧） | ✅ |
| 后台播放（MediaSessionService） | ✅ |
| 屏幕常亮（播放时） | ✅ |
| Rust core 全部解析/EPG/决策逻辑 | ✅ |

---

## 5. 当前限制（明确列出，避免误解）

| 限制 | 说明 / 计划 |
|---|---|
| **应用内 EPG 网格未渲染** | 已解析 `x-tvg-url`（EPG 源地址）并存入数据结构，但第一版 UI 只显示频道列表，未做时间轴网格。这是下一个里程碑（M4）的重点。 |
| **DVR / 多画面 / Catch-up UI 未实现** | Rust core 的 catch-up 模板引擎已完成（6 套语法），但 UI 未接入。 |
| **UI 是 Kotlin 原生 Views** | Slint 迁移待 M0 spike（Surface 合成方案 P1/P2 验证）。 |
| 无 mpv 兜底 | 第一版是 `lite` 变体（仅 Media3）。长尾编码（RTSP/HEVC）后续按需加 mpv。 |
| 测试 APK 使用 debug 签名 | 可直接 `adb install`；正式发布需要 release keystore。 |

---

## 6. 验证方法（证据）

```bash
# 1) 本地全量测试 + 静态检查
cargo test --workspace          # 134 passed
cargo clippy --workspace --all-targets -- -D warnings   # 0 warnings
cargo deny check licenses       # ok

# 2) CI（GitHub Actions，均 success）
#    .github/workflows/ci.yml        — fmt/clippy/test/license
#    .github/workflows/apk.yml       — Rust .so 交叉编译 + Gradle 打包

# 3) APK 内容和元数据
aapt dump badging iqtvlite-0.1.0-test.apk
aapt list -v iqtvlite-0.1.0-test.apk | grep '\.so'
# → lib/arm64-v8a/libiqtvlite_capi.so
# → lib/armeabi-v7a/libiqtvlite_capi.so
```

---

## 7. 下一步

1. **EPG 网格**（M4）：时间轴网格 + 节目进度条 + now/next + 提醒。
2. **Slint UI 迁移 spike**（M0）：验证「Slint 渲染进自建 SurfaceView」（P1）vs「保持原生播放屏」（P2）。
3. **Catch-up UI**：把 Rust 的模板引擎接到「长按节目 → 回看」交互。
4. **性能实测**：真机测冷启动 / 换台首帧 / 内存，对齐 `docs/00-overview.md` 的目标指标。
