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

1. **自动帧率匹配（AFR / Auto Frame Rate）与电视显示模式切换**
   - **现状缺口**：国内直播大量存在 `25fps`（1080i/50i 隔行/央视高清）与 `50fps`（4K HDR）混排，默认 60Hz 输出易产生 `3:2 pull-down` 微卡顿。
   - **落地方案**：在 `Display.Mode`（API 23+）上根据实测 `IQTV-FPS`（`25 / 50 / 29.97 / 60`）优先选择整数倍刷新率（如 `25fps -> 50Hz`），在「设置 → 播放解码」提供开关（默认关闭，避开老盒子切换黑屏闪烁），可选切台确认气泡。
2. **输出设备音频延迟微调（Audio Delay / Lip-Sync）与 150% 音量增益**
   - **现状缺口**：部分蓝牙音箱 / HDMI ARC Soundbar 与电视画面存在固定音画延迟；部分卫视频道声音偏小。
   - **落地方案**：利用 ExoPlayer / AudioProcessor 为当前音频路由（本机扬声器 / HDMI / 蓝牙）记忆 `±500ms` 偏移及 `0%–150%` 响度增益。
3. **实时码流信息浮层 & WebUI 一键诊断日志导出**
   - **现状缺口**：已有 `IQTV-FPS`、`IQTV-RTP`（丢包率 / 环缓溢出）、`JingcaiDiag` 等优质埋点，但仅在 `logcat` 可见，普通用户排查 4K 丢包/软硬解需连 `adb`。
   - **落地方案**：在信息卡 / 菜单增加轻量「流诊断详情」（编码格式、分辨率/帧率、实时码率、当前解码器、组播丢包率），并在局域网 WebUI 提供「下载诊断日志」接口（`/api/diag`），排查问题无需 `adb`。
4. **单频道「软解/硬解 + 码流兼容模式」记忆覆盖**
   - **现状缺口**：目前仅支持切台超时触发当次软解降级（切台重置），若用户确认某个特殊频道必须软解或兼容模式，下次切回需重走 8s~16s 等待。
   - **落地方案**：在 `OK` 菜单或长按菜单允许「仅对该频道记住解码偏好」，其余所有频道仍保持默认硬件优先。

### P1 — 中期演进（架构性能与兼容性增强）

5. **引入 AndroidX Baseline Profile（启动与列表滚动编译优化）**
   - 借助 OwnTV 实践成熟的 `baselineprofile` 模块为 Compose 设置抽屉、信息栏与 Media3 核心热路径预生成 AOT 编译配置，进一步压低 `TR100_G9` 等低端 Cortex-A53/A9 盒子的冷启动 `Choreographer` 掉帧。
6. **自定义 DNS / DoH（DNS-over-HTTPS）与内置可选代理/网络诊断**
   - 为 `NetUtil.okHttpClient` 增加可配置的公共 DNS / DoH（AliDNS / DNSPod / Cloudflare）选项，解决部分地区宽带 DNS 污染导致域名直播源、台标或 EPG 拉取失败问题（沿用当前 IPv6/IPv4 双栈有序解析架构）。
7. **EPG 时区偏移（Guide Time Offset）与手动匹配/纠偏**
   - 支持单源 `±12h` 时区微调，以及在频道抽屉内为未自动匹配成功的频道手动关联 XMLTV 频道 ID。
8. **纯音频模式（Audio-Only / Radio Screen Saver）与 4:3 / 16:9 / 拉伸快捷画面比例**
   - 针对广播频道或仅听电视新闻场景关闭视频解码器省电防烧屏；丰富画幅缩放热键。

### P2 — 长期探索（视体积预算与用户诉求推进）

9. **轻量 Xtream Codes 直播接入 & 本地目录导入 M3U**
   - 仅解析 Xtream Live 频道列表与 EPG（不引入 VOD 电影海报墙），并增强机顶盒本地 U 盘/存储文件选择器导入 M3U。
10. **ABI 分包前提下评估可选 `libmpv` 兼容扩展**
    - 保持主包（< 20MB）仅含 ExoPlayer + FFmpeg 音频；仅当未来遇到 ExoPlayer + `ExperimentalFfmpegVideoRenderer` 仍无法兼容的高码率非标格式（如 AVS2 / 特殊 DVB 字幕封装）时，评估通过按 ABI 分包（`arm64-v8a` / `armeabi-v7a`）或可选插件化形态引入。
11. **按需扩展多画面（2→4 路）与直播录制（TS 直存）**
    - 在现有双画面画中画（PiP）基础上，在高性能设备可选开放 3/4 格多画面同看（自动限制高码率 4K 并发防解码器耗尽）及一键直存原生 MPEG-TS 录屏。
