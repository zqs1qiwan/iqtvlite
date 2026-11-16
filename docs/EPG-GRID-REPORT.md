# EPG 时间轴网格 — 实现与验证报告

> 日期：2026-11-16
> 目标：实现 TiviMate 的招牌功能（EPG 网格），并验证老白直播 EPG 的真实数据。

---

## 1. 验证结论（真实数据）

### 1.1 EPG 端点

`https://epg.laobaitv.net`（播放列表头部的 `x-tvg-url` 指定）：

| 项 | 实测值 |
|---|---|
| HTTP 状态 | 200 |
| 响应编码 | **gzip**（`Content-Encoding: gzip`，reqwest 自动解压） |
| 解压后 XML | **3,869,417 字符**（约 3.7 MB） |
| 频道数 | 155 |
| `generator-info-name` | `laobaiepg` |

### 1.2 tvg-id 匹配

播放列表的 `tvg-id` 与 EPG 的 `<channel id>` **直接匹配**（实测前 15 个 CCTV 频道 100% 命中）：

```
播放列表 tvg-id: CCTV1, CCTV2, CCTV3, ... CCTV15
EPG channel id : CCTV1, CCTV2, CCTV3, ... CCTV15  ✓
```

### 1.3 真实 EPG 网格（通过 Rust core 生成）

用 `cargo run -p iqtvlite-capi --example epg_smoke` 下载真实 EPG 并生成 6 小时网格：

```
downloaded 403644 bytes (gzip)
xml chars: 3869417
网格统计: GridStats { requested: 4, matched: 3, guide_channels: 3, programmes: 26, skipped_bad_time: 0 }
  [CCTV1] CCTV-1 综合 — 12 个节目
      多喜一家人2/34 (col=0, span=2, progress=82%)
      三餐四季-宣传片 (col=1, span=1, progress=0%)
      非遗里的中国-MV (col=1, span=1, progress=0%)
  [CCTV5] CCTV-5 体育 — 6 个节目
      实况录像-2026年青奥会 比赛 1 (col=0, span=1, progress=100%)
      体育世界 (col=0, span=3, progress=15%)
  [CCTV9] CCTV-9 纪录 — 8 个节目
      寰宇视野2026-323 (col=0, span=2, progress=55%)
      故事中国2026-281 (col=1, span=3, progress=0%)
```

说明：
- 4 个请求频道中 3 个匹配（`HunanTV` 不在 EPG 里 —— 符合预期，EPG 只覆盖部分频道）；
- 节目块有正确的 `col`（起始列）/`span`（跨列数）/`progress`（播出进度）；
- 0 个时间解析失败。

---

## 2. 实现

### 2.1 Rust core（`iqtvlite-epg::grid`）

新增模块 `crates/iqtvlite-epg/src/grid.rs`：

| 设计点 | 说明 |
|---|---|
| 流式解析 | 用 `parse_reader` 逐事件消费 3.7 MB XML，内存只保留窗口内节目 |
| 时间优先索引 | `BTreeMap<i64, Vec<...>>` 一次扫描按时间归集，不 N+1 |
| 严格匹配 | 只用规范化 `tvg-id` 精确匹配；**有 id 的频道绝不按名字匹配** |
| 视口裁剪 | 只处理传入的 `tvg_ids`（UI 视口内），`max_rows` 限制防卡死 |
| 派生字段 | `col`/`span`/`progress` 由 Rust 计算，UI 只负责画格子 |

新增 **7 个单元测试** + **3 个老白 EPG 回归测试** + **1 个 smoke 测试**。

### 2.2 JNI 桥

新增 `iqt_parse_epg_grid(xmltv, tvg_ids_json, now, window_start, window_end, slot_minutes) -> String`，
返回完整网格 JSON。Kotlin 侧 `RustBridge.parseEpgGrid(...)` 调用。

### 2.3 Android UI

- `EpgGrid.kt`：网格数据类，从 Rust JSON 反序列化
- `EpgGridAdapter.kt`：每行 = 频道列 + 节目时间轴，节目块宽度 = `span × 160dp`，当前节目显示进度条
- `MainActivity.kt`：新增 `频道 / EPG` 两个 tab，切到 EPG 时懒加载 EPG 并缓存，点击节目跳转到对应频道播放

---

## 3. 真实播放验证中发现的关键问题（已修复）

### 3.1 供应商要求 VLC UA，不是 TiviMate UA

实测老白直播的 CCTV1/CCTV2 流：

| User-Agent | 结果 |
|---|---|
| `TiviMate/5.1.6 (Android 12)` | **403 Forbidden** |
| `VLC/3.0.20 LibVLC/3.0.20` | **200 OK** ✅ |
| `Mozilla/5.0` | 403 |

播放列表里明确用 `http-user-agent="VLC"` 和 URL 后缀 `|User-Agent=VLC` 标记了这一点。

**第一版原实现**：全局硬编码 TiviMate UA → 这些频道会 403 播放失败。
**已修复**：
1. `RustBridge.parsePlaylist` 解析 `http-user-agent` 属性到 `Channel.userAgent`；
2. `resolveUserAgent()` 把短名 `VLC` 映射成完整 VLC UA；
3. `PlaybackService` 用 OkHttp interceptor **每个流请求动态读取当前 UA**；
4. 换台前调用 `PlaybackService.setStreamUserAgent(ua)`。

另一个流（Migu `cctv3hd`）用默认 UA 就返回 200，说明按频道设置 UA 是正确策略。

---

## 4. 当前状态

| 项 | 状态 |
|---|---|
| Rust EPG 网格 + 11 个测试 | ✅ |
| JNI 桥 | ✅ |
| Android EPG 网格 UI | ✅ |
| 按频道 UA（VLC/TiviMate 映射） | ✅ |
| 真实 EPG 端到端解析 | ✅（见 §1.3） |
| 真机/模拟器播放验证 | 🔄 模拟器安装中（1.56 GB 系统镜像下载） |

代码已推送到 GitHub，CI 验证后更新 release。
