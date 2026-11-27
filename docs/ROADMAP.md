# IQTV Lite 开发路线与竞品借鉴分析（对比 OwnTV）

> 基于对 [`ahXN00/OwnTV`](https://github.com/ahXN00/OwnTV)（Kotlin + Compose for TV + Media3 ExoPlayer / libmpv，v5.2.2）的架构与功能调研，结合 **IQTV Lite「轻量、极速换台、兼容 Android 7.0+ 与入门级电视盒子、专注家庭直播电视」** 的定位整理。

---

## 一、定位与核心差异对比

| 维度 | IQTV Lite (`v0.3.1`) | OwnTV (`v5.2.2`) |
| --- | --- | --- |
| **产品定位** | 轻量纯粹的家庭 **直播电视（Live TV）** 播放器，开机即播、秒切台 | 全能型影视频道中心（Live TV + VOD 电影/剧集 + 体育赛事 + 离线下载） |
| **系统与性能包袱** | **Android 7.0+ (`minSdk 24`)**；~17.8 MB 单包；Rust 核心 (`iqtvlite-core`) + SQLite EPG + 原生 `RecyclerView` 抽屉，面向 512MB–1GB 老旧运营商盒子依然跟手 | **Android 8.0+ (`minSdk 26`)**；内嵌 `libmpv` + Room + Paging + Coil + Koin + TMDB/OpenSubtitles，体积与常驻内存门槛明显更高 |
| **播放内核** | **Media3 ExoPlayer** 为核心 + FFmpeg 软音（`lib-decoder-ffmpeg`）+ **逐级 HW→SW 视频解码回退** + **原生直连 `rtp://` / `udp://` 异步 4MB 组播引擎** | **双引擎切换**（ExoPlayer 负责快切预览 + `libmpv` 负责广泛编码/图像字幕兼容），逐频道可覆盖引擎偏好 |
| **直播源支持** | M3U / TXT、复杂防盗链 HTTP Header 优先级解析、局域网 WebUI（免密直连、多源管理、配置 JSON 备份/恢复） | Xtream / M3U / Stalker (MAC)、QR + PIN 扫码配源、DRM（Widevine / ClearKey）、自定义 DNS / DoH |
| **画质 / 音频 / 多画面** | 4K HEVC 硬件优先与防花屏重建、应用内双画面画中画 | Auto Frame Rate (AFR) 自动帧率匹配、HDR/SDR 偏好、按音频输出设备单独调延迟（HDMI / 蓝牙 / Soundbar）与 150% 音量增益、4 路多画面 Multiview、录制与长时回看 |
| **启动与稳定性** | 无复杂首页加载，首帧前 0 阻塞（EPG 延后加载），静态 CI 门禁 + 端到端实机契约校验 | 引入 AndroidX `BaselineProfile` 优化 Compose 启动与滚动编译耗时 |

---

## 二、明确不做（Non-Goals —— 保持「Lite」护城河）

以下功能与 IQTV Lite「家庭直播电视轻量工具」定位相悖，**不纳入主线计划**：

1. ❌ **TMDB 电影/剧集海报墙、演职员表与体育实时比分聚合**：引入外部 API、复杂大列表与推荐流，显著增加复杂度与内存占用。
2. ❌ **多用户 Profile / 儿童锁 / 分类 PIN 锁 / 设置锁**：家庭日常打开即看场景中属于高频打扰流程（注：WebUI 已明确坚持家庭局域网免密直连）。
3. ❌ **Stalker (MAC) Portal 门户与 P2P/VOD 离线下载队列**：与国内家庭组播/单播直播需求重叠度低。

---

## 三、值得借鉴与落地的开发路径（按优先级排序）

### P0 — 近期落地（小开销、立竿见影提升直播观影体验）

1. ✅ **[已于 v0.3.2 落地] 自动帧率匹配（AFR / Auto Frame Rate）与电视显示模式切换**
   - 根据视频流实测帧率在 1,200 ms 防抖后优先选择整数倍或 1:1 刷新率（如 `25fps -> 50Hz` / `59.94fps`），在「设置 → 播放与解码」提供开关（默认关闭），避免换台黑屏闪烁。
2. ✅ **[已于 v0.3.2 落地] 输出设备音频延迟微调（Audio Delay / Lip-Sync）与 150% 音量增益**
   - 通过零依赖 Media3 `AudioSyncBoostProcessor` 为本机扬声器 / 蓝牙音频 / HDMI 独立记忆 `±500ms` 延迟及 `100%–150%` 响度增益（未调整时完全旁路零开销）。
3. ✅ **[已于 v0.3.2 落地] 实时码流信息浮层 & WebUI 一键诊断日志导出**
   - 在「设置 → 播放与解码 → 实时码流诊断」提供只读诊断页（编解码、分辨率/帧率、AFR、音频路由、DoH 命中/回退、RTP 丢包率），并在局域网 WebUI 提供 `GET /api/diag` 诊断 JSON 下载。
4. ✅ **[已于 v0.3.2 落地] 单频道「软解/硬解」记忆覆盖**
   - 在「设置 → 播放与解码 → 当前频道解码覆盖」支持为当前频道单独记住「强制硬解 / 强制软解」，不影响其余频道硬件优先策略。

### P1 — 中期演进（架构性能与兼容性增强）

5. **引入 AndroidX Baseline Profile（启动与列表滚动编译优化）**
   - 借助 OwnTV 实践成熟的 `baselineprofile` 模块为 Compose 设置抽屉、信息栏与 Media3 核心热路径预生成 AOT 编译配置，进一步压低 `TR100_G9` 等低端 Cortex-A53/A9 盒子的冷启动 `Choreographer` 掉帧。
6. ✅ **[已于 v0.3.2 落地] 自定义 DNS / DoH（DNS-over-HTTPS）**
   - 内置零外部依赖的 RFC 8484 Wire Format DoH 解析器（系统默认 / 阿里 DoH / Cloudflare DoH），带 256 条 TTL 缓存、局域网/IP 直连跳过及系统 DNS 自动回退，与 IPv6/IPv4 优先级栈完全协同。
7. ✅ **[已于 v0.3.2 落地] EPG 时区偏移（Guide Time Offset）**
   - 支持 `-12h .. +12h` 快速 EPG 时区纠偏，SQL 查询层实时计算无需重写数据库（后续仍可扩展单频道手动 XMLTV 关联）。
8. ✅ **[已于 v0.3.2 落地] 纯音频模式（Audio-Only 熄画听音）**
   - 在底部信息卡快捷栏新增「仅听音频」，一键关闭视频轨保留音频与电台提示卡（后续仍可扩展更多画面缩放热键）。

### P2 — 长期探索（视体积预算与用户诉求推进）

9. **轻量 Xtream Codes 直播接入 & 本地目录导入 M3U**
   - 仅解析 Xtream Live 频道列表与 EPG（不引入 VOD 电影海报墙），并增强机顶盒本地 U 盘/存储文件选择器导入 M3U。
10. **ABI 分包前提下评估可选 `libmpv` 兼容扩展**
    - 保持主包（< 20MB）仅含 ExoPlayer + FFmpeg 音频；仅当未来遇到 ExoPlayer + `ExperimentalFfmpegVideoRenderer` 仍无法兼容的高码率非标格式（如 AVS2 / 特殊 DVB 字幕封装）时，评估通过按 ABI 分包（`arm64-v8a` / `armeabi-v7a`）或可选插件化形态引入。
11. **按需扩展多画面（2→4 路）与直播录制（TS 直存）**
    - 在现有双画面画中画（PiP）基础上，在高性能设备可选开放 3/4 格多画面同看（自动限制高码率 4K 并发防解码器耗尽）及一键直存原生 MPEG-TS 录屏。
