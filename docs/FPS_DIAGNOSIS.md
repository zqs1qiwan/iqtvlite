# FPS 真相诊断：为什么 TiviMate 能显示帧率、我们看不到真实 fps

> t39（只读调研，**不改默认行为、不出包、不跑设备**）· 2026-11-23
> 基线：`iqtvlite` 分支 `integration/tivimini-baseline`（含 t38 `735d39b` 的 25fps 兜底）。
> 输入：源码链路 + 仓库内真机日志证据（docs/CI*.md、docs/INFOCARD_FIX_VERIFY.md）。
> Media3 版本：`androidx.media3:media3-exoplayer:1.11.1`（`android/app/build.gradle.kts:95`），
> `AnalyticsListener.onVideoFrameRendered` 在该版本可用（自 1.0.0-beta 起）。

---

## 0. 结论先行（TL;DR）

**传递链没有 bug。** 从 Media3 `Format.frameRate` → `PlayerActivity.videoFormat` → `ChannelInfoBanner` → `InfoCardPolicy.frameRateLabel` 是一条完整、无覆盖、无丢失的直连链。真机日志铁证：这组直播组播 TS 的 Media3 `Format.frameRate = -1.0`（即"未知"），**源流本身不携带帧率元数据**，所以链路末端拿到的是 `-1`，不是被谁覆盖成 0/丢失。

因此根因不在 App 的传递，而在**信源**：MPEG-2 TS（尤其组播 RTP 承载的 TS）的 PMT/视频流描述通常不写 frame_rate 字段；Media3 的 TsExtractor → `Format.frameRate` 在未从 SPS 解析出时序信息时为 `Format.NO_VALUE = -1`。 t38 的 `25fps` 兜底只是"让用户看到一排完整徽标"的权宜，不是真实测量值。

**TiviMate 为什么能显示**：TiviMate 是闭源商业播放器，它对无 frameRate 元数据的流，同样要么显示推断值、要么显示从解码器/帧间隔统计出来的值（行业常见做法是用 `MediaCodec` 输出帧时间戳或 `MediaFormat.KEY_FRAME_RATE` 的解码器回填，再不行按地区/流类型兜底）。我们看不到"真实 fps"不是别人有魔法数据，而是**我们目前没有任何帧率推断环节**——链路只做了"读 Format 字段 → 显示/兜底"，没有"测量"。

**推荐**：先落地**可观测**（方案 0，几乎零风险），再按优先级实施**方案 a（渲染帧间隔统计）**作为真实 fps 推断；方案 b（TS PTS 间隔）作为无解码器帧时间戳时的补充；方案 c（25 兜底）保留为最后防线。详见 §3。

---

## 1. 当前帧率取值链路（源码级，逐跳）

| 跳 | 文件:行 | 代码事实 | 对帧率做了什么 |
|---|---|---|---|
| ① 解码器输入格式 | `StreamPlayback.kt:465-466` | `onVideoInputFormatChanged { emit("format", "... fps=${format.frameRate}") }` | **只读原始值并打点**，不改写、不缓存到别处 |
| ② 首个 format 观测 | `StreamPlayback.kt:440-444` | `onDownstreamFormatChanged { downstream = "... @${f.frameRate}" }` | 仅诊断字符串 |
| ③ Activity 持有 Format | `PlayerActivity.kt:777` | `var videoFormat by remember { mutableStateOf<Format?>(null) }` | 容器 |
| ④ 写入时机 | `PlayerActivity.kt:987-988`（`onVideoSizeChanged`）、`1001-1002`（`onTracksChanged`） | `videoFormat = p.videoFormat`（`p` = ExoPlayer 实例） | **直接取播放器的 videoFormat 引用，未做 fps 变换** |
| ⑤ 传给信息卡 | `PlayerActivity.kt:2259` | `ChannelInfoBanner(... videoFormat = videoFormat ...)` | 原样传入；`frameRateFallback`/`videoBitrateFallback` 未显式传 → 用默认 `-1f` |
| ⑥ banner 取值 | `ChannelInfoBanner.kt:153-160` | `val frameRate = InfoCardPolicy.takePositive(vf?.frameRate ?: -1f, frameRateFallback)` → `compactBadges(frameRate = frameRate ?: -1f, ...)` | `takePositive(主值,-1)`：主值 ≤0 且 fallback=-1 → 返回 null → 传 -1f。无覆盖、无丢失 |
| ⑦ 显示 | `InfoCardPolicy.kt:172-175` `frameRateLabel` | `<=0/NaN → FALLBACK_FRAME_RATE(25f)` → `"25fps"` | t38 兜底在这里发生；真实值 `29.97→30`、`59.94→60` |

**链路判定：无 bug。** 每一跳都是"读到什么传什么"，没有任何一处把有效 fps 抹成 0、-1 或 25。唯一"覆盖"发生在第 ⑦ 跳，且是在拿到 `-1` 之后的**有意兜底**（t38），不是丢失。

> 若未来发现 format 明明有 fps 却没显示，最可能的单点 bug 会是第 ⑥ 跳的 `takePositive(vf?.frameRate ?: -1f, frameRateFallback)`：如果 Activity 曾显式传了正数 `frameRateFallback` 而 format 为 `-1`，会显示 fallback 值（这是设计如此）；当前 Activity **未传**，故此路径不成立。

---

## 2. 可复现证据：源流 frameRate = -1.0（根因在信源）

仓库内多份真机日志一致记录同一组直播组播流的 format：

| 证据 | 出处 | 原文摘录 |
|---|---|---|
| CCTV-1 组播 TS | `docs/CI10093_VS_CI10101_CPU_AB.md:18-20` | URL `http://192.168.2.1:4022/rtp/239.3.1.129:8008`（组播 RTP→渐进式 TS）；视频 `mime=video/avc 1920x1080 **fps=-1.0**`（两版相同） |
| 睛彩诊断（格式已出） | `docs/CI10136_JINGCAI_DECODER.md:22` | `IQTV-METRICS: format ... mime=video/avc width=1920 height=1080 **fps=-1.0**` |
| 睛彩复核 | `docs/CI10137_JINGCAI_VERIFY.md:50` | `format ... 1920 height=1080 **fps=-1.0**` |
| 信息卡徽标 dump | `docs/INFOCARD_FIX_VERIFY.md:42-43` | `FHD · 2.0 · SDR · 264`，明确记 "**fps 未显示**：源为该直播组播流，Media3 video Format 的 frameRate ≤0，`frameRateLabel` 对 ≤0 返回 null（未知即隐藏，非 bug）" |

`Format.NO_VALUE = -1`（Media3 常量），表示"Extractors 无法从容器/码流推断出帧率"。MPEG-2 TS 的视频流（H.264）里，帧率可藏在 SPS 的 `timing_info`（`num_units_in_tick` / `time_scale`），但**很多组播编码器不写 timing_info**；PMT 也不强制带 frame_rate 描述符。Media3 TsExtractor 未解析到时即留 `-1`。这与 TiviMate 的差异是"是否做了额外推断"，不是"数据谁丢了"。

---

## 3. 可行方案与推荐

### 方案 0：可观测（先做，零风险）
在现有 `emit("format", ...)` 已含 `fps=` 的基础上，**补一行信息卡侧 DEBUG**：在 `ChannelInfoBanner` 计算徽标处，若 `log.tag.IQTV-INFOCARD` 为 DEBUG，打印 `IQTV-INFOCARD format fps=<vf?.frameRate> width=<...> height=<...> badge=<compactBadgeText>`。这样真机 logcat 能一眼确认"这组流到底有没有 fps"，也直接回答用户"为什么显示 25"。**不改默认行为**。

### 方案 a：渲染帧间隔统计（推荐，真实 fps）
ExoPlayer 有 `AnalyticsListener.onVideoFrameRendered` / `VideoFrameReleaseHelper` 的帧渲染节奏。做法：首帧后取 N（如 60）个渲染帧时间戳，算 `(N-1)/(tN-t1)` 得真实 fps，缓存为 `measuredFps` 注入 `frameRateFallback`。
- 优点：测的是**实际渲染节奏**，最接近用户感知，TiviMate 类做法。
- 复杂度：中（需 listener + 环形缓冲 + 温和滤波）。
- 性能：极低（每帧一个 long 写入，无额外录制层）。
- 风险：丢帧/倍速/低帧率源会抖动 → 需中位数 + 只在稳定窗口提交；回看倍速时需排除。
- 结论：**推荐作为"真实 fps"主方案**。

### 方案 b：TS PTS 间隔推断（补充）
仓库已有 `JingcaiTimeline.readVideoPts`（`JingcaiTimeline.kt:161-172`，90kHz 时间戳）与 `JingcaiTsPipeline` 的 PTS 采集。对相邻视频 PES 的 PTS 差取倒数即帧率（如 3600 tick = 25fps）。
- 优点：不依赖解码器；已有解析基础设施，复用成本低。
- 复杂度：中（PTS 间隔在 B 帧/场景切换下不稳，需统计）。
- 性能：低（字节级解析，已有 pipeline 在做）。
- 风险：组播流 PTS 可能有错位（项目已有 PCR/PTS 错位诊断），推断可能失真；仅作 a 的交叉校验或 a 不可用时的补充。

### 方案 c：兜底 25fps（现状，保留为最后防线）
t38 已实现：**不是真实测量**，只是保证徽标一排完整。保留，但在有 a/b 的真实值时必须让位（`takePositive(measured, 25f)` 或형 `measured ?: 25f`）。

### 推荐落地顺序
**0（可观测）→ a（渲染帧统计）→ b（PTS 交叉校验）→ c（兜底常在）**。先 0 让用户/验收拿到"源到底有没有 fps"的铁证；a 给出真实值；b 作为组播无 timing_info 时的第二意见；c 永远兜底不删。

---

## 4. 可观测补充（建议，不在本任务落地）

本任务只读不落地；建议后续单独提交实现：
1. `ChannelInfoBanner` 徽标计算处加 `IQTV-INFOCARD format fps=<vf?.frameRate> badge=<text>` DEBUG 行（BuildConfig.DEBUG + `Log.isLoggable` 守卫，与现有 `IQTV-METRICS` 一致）。
2. 若实施方案 a，再打 `IQTV-METRICS measured_fps=<值> samples=<N> source=render`。

现有 `emit("format", ... fps=...)`（`StreamPlayback.kt:466`）已经会在 DEBUG 下输出原始 fps，真机只需 `setprop log.tag.IQTV-METRICS DEBUG` 即可看到 `-1.0`——**这是当前就能复现的最省事路径**。

---

## 5. 一句话修复建议（若将来发现链路 bug）

当前链路无 bug，无需修复。仅当出现"format 有正 fps 但徽标不显示"时，检查单点：`ChannelInfoBanner.kt:154` 的 `takePositive(vf?.frameRate ?: -1f, frameRateFallback)` 与 `:160` 的 `frameRate ?: -1f`——确保 `frameRateFallback` 未被 Activity 误传负值覆盖、且 `-1f` 能抵达 t38 兜底 `frameRateLabel(-1f)→25fps`。

---

## 6. 本任务产出与边界
- 产出：本文（诊断结论 + 证据 + 方案评估 + 可观测建议）。
- **未改任何代码 / 未改默认行为 / 未出包 / 未跑设备 / 未碰 main/tag**。
- 后续真机断言依赖：DEBUG 日志 `IQTV-METRICS format ... fps=-1.0`（确认源无 fps）+ 若实施 a 后的 `measured_fps`。
