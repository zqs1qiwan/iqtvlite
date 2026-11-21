# CI10137 睛彩修复真机验证（重建播放器 + 软解兜底）

2026-11-21，acceptance（t5 attempt 1）。设备 `192.168.2.245:5555`（**TR100-G9 / Android 9 / API28 / armeabi-v7a / 1080p / 2GB**）。
验证包 **10137 / 2.2.0-tivimini-fcc76038-validation**（CI run `44884145289` / run_number **137** / head `fcc76038`）。
源码依据：`docs/JINGCAI_DECODER_REBUILD.md`（t234）、`ZapRebuildPolicy.kt`、`StreamPlayback.kt:137-200`、`JingcaiRenderersFactory.kt`。

## 0. 结论（先行）

> **⚠ 订正（captain 2026-11-21，t6 期间追加，append-only，不改原判定）**
> 本报告中"`bytes=0` ⇒ 数据源零字节 ⇒ 根因在数据源层"的表述**不成立**：`bytes` 取自 `onLoadCompleted.bytesLoaded`，**直播流永不会 completed → 该计数恒为 0**，不能当作"没收到数据"的证据。
> 后续由 PC 侧抓流解析给出更可能根因：睛彩 **PCR≈32153s / 首 PTS≈64307s，偏差 ≈32154s（约 9 小时）**，CCTV 对齐 → **时间轴错位**，与"解码器已创建但音视频不动"吻合（已派 t12 做时间轴实验）。
> 因此：本报告 §0-#3、§2 尾注、§10-1 中"数据源/零字节"的推断**降级为待验证假设**；"重建后仍无首帧/state=2"的事实判定不变。后续以开发新增的"DataSource 实读字节 / firstByte 耗时"指标为准。

| # | 验收项 | 结果 |
|---|---|---|
| 1 | 单次安装 CI10137 | ✅ 成功（签名冲突，按协议 §2.2 例外：备份 → 仅卸 `.validation` 一次 → 装；详见 §1） |
| 2 | 117/118/119 `~8 s` 各触发**一次** `rebuild=1` | ✅ 三台各 1 次；**重建后新 player 首次出现视频解码器 `OMX.goke.video.decoder.avc`**（10136 时为 0 次创建） |
| 3 | 重建后能否出首帧 / `state=3` | ❌ **不能**。三台 `firstFrame=false / state=2`；`bytes=0` **不可作证据**（见上方订正），根因待 t12 时间轴实验 |
| 4 | 仍失败时软解兜底是否触发一次 | ⚠️ **日志触发一次**（`softfallback=1`），但**未观察到软解真正接管**：SOFT 步仍创建 OMX 硬件解码器，全程无 FFmpeg 视频解码器初始化行（§4） |
| 5 | 有无崩溃（睛彩窗口内） | ✅ 无。117/118/119 + CCTV-1 每窗口 `Fatal signal = 0`、进程 pid 7881 全程稳定 |
| 6 | 健康频道无行为变化 | ✅ CCTV-1：`first_frame ttff=884ms`、`state=3`、`rebuffers=0`、**0 条** rebuild/softfallback、`IQTV-ZAP` 恰好 1 行 |
| 7 | `IQTV-JINGCAI` / `IQTV-ZAP` 日志 | ✅ 已抓（`docs/evidence/2026-11-21-ci-10137-jingcai/`） |
| 8 | 阶梯"每次换台重置" | ✅ 切回 CCTV-1 再回 117，`rebuild=1` 再次出现（额度按 zap 重置） |
| 9 | `giveup=1` 日志 | ❌ **未出现**（代码 GIVE_UP 分支只打印诊断行 + Toast，生产路径从不调用 `logLine(GIVE_UP)`，§6-①） |

**一句话**：重建播放器这条修复在真机上**行为成立**（确实重建了 player，并且第一次让该流创建出硬件视频解码器），但**睛彩三台仍不可播**——根因仍在数据源层（`bytes=0`，一个载荷字节都没收到）；软解兜底"触发但疑似不生效"，`giveup` 日志未实现。健康频道**零回归**。

## 1. 安装（签名冲突 → 协议 §2.2 例外流程）

CI 用**临时 debug keystore**（`apk.yml` 仅在 secrets 有 `ANDROID_SIGNING_KEYSTORE_BASE64` 时才用稳定签名），因此每个 CI 包签名都不同，`install -r` 必然冲突（10136 也是这么装的）。

| 步骤 | 命令 / 输出 |
|---|---|
| 包校验 | `app-debug.apk` **13,092,817 B**，sha256 `69008758689faf98312a86a574e5ca1850246bd7a67ebbafd83621f975f79d26`（与 t234 文档一致） |
| 前置体检 17:26:25 | loadavg `13.09/13.02/12.91`；`sys.boot_completed=1`；`/data` 可用 **2,638,584 KB**；storaged+installd=2；DeadObject/LMK/ext4/I-O=0；我方崩溃=0 |
| 旧签名 | `signatures=PackageSignatures{8f54c02 version:2, signatures:[7017587a]}`（版本 10136 / 2.2.0-tivimini-8c1f3ebb-validation） |
| 备份 data | `exec-out run-as tv.iqtvlite.app.validation tar -cf - .` → **1,695,744 B / 198 条目**（可 `tar -tvf` 列出；含 `shared_prefs/iptv_prefs.xml`、`iptv_stream.xml`、`files/playlist_*.m3u`、186 个台标 webp） |
| 卸载（仅此一条） | `pm uninstall tv.iqtvlite.app.validation` → **Success**（未碰 `tv.iqtvlite.app` / `.validation.test` / `com.cleanroom.iptv`） |
| 安装 17:27:33 | `adb install -r app-debug.apk` → **Success**；`versionCode=10137`、`versionName=2.2.0-tivimini-fcc76038-validation`、新签名 **`21a23813`**（旧 7017587a） |
| 安装后体检 17:27:42 | `/data` 可用 **2,627,768 KB**；loadavg `12.05/12.73/12.82`；守护进程 2；badlog=0；崩溃=0；4 包在位 |
| 4 包在位 | `tv.iqtvlite.app`、`tv.iqtvlite.app.validation`、`tv.iqtvlite.app.validation.test`、`com.cleanroom.iptv`（**全部保留**） |

> 方法披露（诚实记录）：卸载会清空 app data，因此装后用上述备份恢复 `shared_prefs`（直播源/上次频道）、`playlist_*.m3u`、EPG 缓存与 186 个台标缓存文件（`run-as … tee` 写入），目的是让验证起点与上一版一致、避免重新抓源。恢复行为**不影响被判定的代码路径**（重建阶梯、软解、健康频道回归均按用户换台触发）。
> 台标缓存必须恢复：不恢复时冷启动会触发一个**非睛彩**的原生崩溃（§7）。

## 2. 117 原始时序（17:34:22 zap）

```
17:34:21.803 IQTV-METRICS: prepare session=c4783027072129292115-l476020171-s2
17:34:23.009 IQTV-METRICS: format session=…-s2 mime=video/avc width=1920 height=1080 fps=-1.0
17:34:23.119 IQTV-METRICS: audio_track_initialized … audio/mpeg-L2 48k/2ch      ← 音频正常，视频无解码器
17:34:30.059 IQTV-JINGCAI: rebuild=1 channel=4783027072129292115                 ← 8.1 s：重建播放器
17:34:30.209 IQTV-METRICS: prepare session=…-s3                                   ← 新 player（旧 s2 释放）
17:34:31.212 IQTV-METRICS: decoder session=…-s3 name=OMX.goke.video.decoder.avc init_ms=71   ← ★首次创建视频解码器
17:34:38.307 IQTV-JINGCAI: softfallback=1 channel=4783027072129292115             ← 再 8 s：软解兜底档
17:34:38.567 IQTV-METRICS: prepare session=…-s4
17:34:39.869 IQTV-METRICS: decoder session=…-s4 name=OMX.goke.video.decoder.avc init_ms=64   ← ⚠ 仍是 OMX（非 FFmpeg）
17:34:46.660 IQTV-JINGCAI: mode=off bytes=0 format=video/avc/1920x1080@-1.0 firstFrame=false state=2   ← 第三次超时 = GIVE_UP（诊断行 + Toast）
```
- **无** `first_frame`、**无** `state=3`、**无** `audio_advancing`、`Dropping=0`。
- `bytes=0`（`onLoadCompleted` 累计）→ 载荷零字节；`format` 由解析层先给出，故"有 format 无解码器"的旧现象在本版被打破：**重建后的新 player 会创建解码器**，但仍**没有样本可解**。

## 3. 118 / 119（同形态，逐台一次）

| 频道 | zap 时刻 | `rebuild=1` | 重建后解码器 | `softfallback=1` | SOFT 步解码器 | GIVE_UP 诊断行 | 首帧/state=3 | 崩溃 |
|---|---|---|---|---|---|---|---|---|
| 117 | 17:34:22 | 17:34:30.059（+8.1s） | `OMX.goke.video.decoder.avc` init 71 ms | 17:34:38.307 | 仍是 OMX（init 64 ms） | 17:34:46.660 | ❌ / ❌ | 0 |
| 118 | 17:36:14 | 17:36:23.070（+8.2s） | `OMX.goke…avc` init 75 ms | 17:36:31.415 | 仍是 OMX（init 78 ms） | 17:36:39.810 | ❌ / ❌ | 0 |
| 119 | 17:38:34 | 17:38:42.669（+8.2s） | `OMX.goke…avc` init 78 ms | 17:38:50.857 | 仍是 OMX（init 65 ms） | 17:38:59.201 | ❌ / ❌ | 0 |
| 117（二刷） | 17:40:08 | 17:40:16.332（+8.2s） | `OMX.goke…avc` init 70 ms | —（12 s 窗口） | — | — | ❌ | 0 |

- 三台 `IQTV-WATCHDOG` 行数 **0**：旧的 `firstFrameTimeout retry=1` 已被重建阶梯取代（符合设计）。
- 三台均**恰好 1 次** rebuild + 1 次 softfallback，**无重入、无循环**、无第二次 rebuild。

## 4. 软解兜底：触发但**未见生效证据**（重点待确认）

证据链：

1. `JingcaiRenderersFactory.buildVideoRenderers` 只有在 `softVideo && FfmpegLibrary.isAvailable()` 时才把 `ExperimentalFfmpegVideoRenderer` 放到最前；否则完全不改变默认（纯硬解）。
2. 真机 SOFT 步（s4/s7/s10）**都创建了 OMX 硬件解码器**（`OMX.goke.video.decoder.avc`）。若 FFmpeg 视频渲染器在最前并被选中，MEDIA3 不会再创建 MediaCodec 解码器。
3. 全程只有 `DefaultRenderersFactory: Loaded FfmpegVideoRenderer.` / `Loaded FfmpegAudioRenderer.`（扩展加载日志），**没有任何 FFmpeg 视频解码器初始化/首帧行**；音频侧 `ffmpegLavc60.3.100-mp3` 正常（音频渲染器一直是 ffmpeg 兜底，与视频无关）。
4. APK/AAR 内只有 **`libffmpegJNI.so`**（`libs/lib-decoder-ffmpeg-release.aar` → `jni/armeabi-v7a/libffmpegJNI.so` 1,155,156 B），无 `libavutil/libavcodec/libavformat` 等分库；`FfmpegLibrary.isAvailable()` 在该 AAR 形态下**很可能返回 false**。

→ **判定：软解兜底日志按阶梯触发了一次，但极可能未真正接管视频（硬件解码器仍被创建）。** 需开发侧确认（建议加一行 `IQTV-JINGCAI softVideo=… ffmpegAvailable=…` 日志，或在 SOFT 步断言"不得出现 MediaCodecVideoRenderer 的 decoder 事件"）。这也解释了为什么 SOFT 步没有带来任何画面变化。

## 5. 健康频道回归（CCTV-1，17:39:20 zap）

```
17:39:21.735 IQTV-METRICS: first_frame session=c7583001598850122915-l590536782-s11 ttff_ms=884 rendered_at=12148468
17:39:21.739 IQTV-ZAP: zap=1149ms prepare=278ms firstFrame=871ms fmt=748ms dec=0ms render=123ms   ← 恰好 1 行
17:39:21.941 IQTV-METRICS: state session=…-s11 state=3 rebuffers=0 rebuffer_ms=0 dropped=0
17:39:22.000 IQTV-METRICS: audio_advancing session=…-s11 since_prepare_ms=1150
```
- `rebuild/softfallback/giveup` = **0 / 0 / 0**，`IQTV-WATCHDOG` = 0 → **默认路径零变化**（与 t234 "对健康频道零影响" 一致）。
- 冷启动（17:33:41，恢复 data 后）：`first_frame ttff=1871ms` → `state=3` → 首帧后 `rebuffer 1889ms`（历史已知"启动 rebuffer"，非本次回归项）。

## 6. 与 `docs/JINGCAI_DECODER_REBUILD.md` 的差异（文档需订正）

| # | 文档说法 | 真机实际 |
|---|---|---|
| ① | 阶梯最后一档打印 `IQTV-JINGCAI giveup=1 channel=…` | **从未打印**。`StreamPlayback.kt:186-196` 的 GIVE_UP 分支只输出 `diagnosticLine(mode=off bytes=0 …)` + Toast；`ZapRebuildPolicy.logLine(GIVE_UP)` 仅被 JVM 测试引用，生产路径没有调用 → 要么补日志，要么改文档 |
| ② | "重建不会产生第二条 `IQTV-ZAP` 行" | 重建/软解**各产生 1 行** `IQTV-ZAP: bufferMin=500`（来自 `initializePlayer`）。真正的换台测量行 `IQTV-ZAP: zap=…` 确实**没有**第二条（睛彩无首帧时连第一条都没有）→ 文档表述需精确化 |
| ③ | 阶梯"按频道 key 记账，不同频道互不影响" | 真机上 117/118/119 的 key **完全相同**（`channel=4783027072129292115`，mediaId 前缀亦相同，仅 `l<urlHash>` 不同）。因为每次换台都会 `onUserZap()` 重置，**行为仍正确**（观测到 117→118→119 各重建一次；CCTV-1 后回 117 也重建），但"频道独立"在实现上并未体现，建议排查 `channel.id` 的生成（三台同 id 会影响最近观看/阶梯记账语义） |

## 7. 旁证发现：冷启动原生崩溃（非睛彩，移交性能/稳定性任务）

- 时刻：`17:32:08.350`，`Fatal signal 11 (SIGSEGV) SEV_MAPERR fault addr 0x0`，pid 6374、tid 6517 `DefaultDispatch`。
- 栈（我方帧）：`LogoLoader.loadInternal → LogoLoader.writeBitmapToDisk → LogoDiskStore.writeAtomically → Bitmap.compress(WEBP) → libhwui SkWebpEncoder::Encode → WebPPictureImportRGBA → ImportYUVAFromRGBA (pc 0x0)`。
- 触发条件：**台标缓存冷**（data 被清空后首次抓台标并写盘）。恢复 186 个已缓存 webp 后重启，**不再复现**（进程 7881 全程稳定，多窗口 0 崩溃）。
- 影响：用户首次安装/清数据后冷启动会**直接闪退**（回桌面），属高优先级稳定性问题；与睛彩/重建阶梯无关。原始栈：`docs/evidence/2026-11-21-ci-10137-jingcai/crash-coldstart-logo-loader.txt`。

## 8. 健康与协议（逐步复核，全部通过）

| 时点 | loadavg | /data 可用 | storaged+installd | DeadObject/LMK/ext4/I-O | 我方崩溃 | 4 包 |
|---|---|---|---|---|---|---|
| 装前 17:26:25 | 13.09/13.02/12.91 | 2,638,584 KB | 2 | 0 | 0 | 在位 |
| 装后 17:27:42 | 12.05/12.73/12.82 | 2,627,768 KB | 2 | 0 | 0 | 在位 |
| 117 后 17:35 | 12.94/12.47/12.59 | — | 2 | 0 | 0 | 在位 |
| 118 后 17:36:47 | 12.85/12.50/12.58 | — | 2 | 0 | 0 | 在位 |
| 119 后 17:39:06 | 12.83/12.71/12.65 | — | 2 | 0 | 0 | 在位 |
| CCTV-1 后 17:39:40 | 12.72/12.69/12.65 | 2,624,444 KB | 2 | 0 | 0 | 在位 |
| 全部结束 17:40:47 | 13.60/12.97/12.75 | 2,624,376 KB | 2 | 0 | 0 | 在位 |

协议遵守：仅 `adb install -r`（1 次安装）；**只**卸载 `tv.iqtvlite.app.validation` 一次（协议 §2.2 签名冲突例外，已获 captain 批准）；未卸载 `tv.iqtvlite.app` / `.validation.test` / `com.cleanroom.iptv` / 第三方；`force-stop` 仅本包 2 次（恢复 data、重启取日志）；未整机重启、未改系统分区、`log.tag.*` 调试开关沿用既有值（未复位，因为仍是调试会话）。
设备常态 loadavg ≈ 12–13.6（4 核），**所有测量均记录同一时刻 loadavg**，不做性能结论用。

## 9. 交付物

- 本报告：`docs/CI10137_JINGCAI_VERIFY.md`
- 原始日志（`docs/evidence/2026-11-21-ci-10137-jingcai/`）
  - `logcat-117-ci10137.txt`（rebuild → soft → giveup 完整窗口）
  - `logcat-118-ci10137.txt`、`logcat-119-ci10137.txt`
  - `logcat-117b-repeat-ci10137.txt`（换台后阶梯重置）
  - `logcat-cctv1-ci10137.txt`（健康频道回归）
  - `crash-coldstart-logo-loader.txt`（§7 原生崩溃栈，131 行）
- data 备份：`.acceptance/backups/validation-10136-backup.tar`（1,695,744 B，仓库外，未入库）

## 10. 判定与建议

**判定：部分通过（机制成立 / 目标未达成 / 健康频道无回归）。**

1. **P0（修复验证）**：重建播放器阶梯在真机确实生效且**首次让睛彩流创建出硬件视频解码器**，但 `bytes=0` 说明播放器**一个载荷字节都没收到** → 下一步应回到**数据源/组播层**（`rtp/udp 239.3.1.125/126/127:8001` 经 `192.168.2.1:4022`），而不是继续在解码/渲染上迭代。建议在 `onLoadError`/`onLoadStarted` 处记录数据源类型、URL、错误码与首包耗时（当前 `bytes=0` 但**无任何 load error 行**，这本身是缺口）。
2. **P0（软解兜底）**：SOFT 步疑似未真正软解（见 §4）——或确认 `FfmpegLibrary.isAvailable()` 并补日志，或明确文档"本机软解不可用"。
3. **P1（日志契约）**：`giveup=1` 未实现（§6-①）；`IQTV-ZAP bufferMin` 行与"不产生第二条 ZAP 行"表述冲突（§6-②）。
4. **P1（稳定性，移交 t6/开发）**：台标缓存冷启动 WebP 编码原生崩溃（§7），会影响**首次安装用户冷启动**。
5. **P2（数据一致性）**：睛彩三台 `channel.id` 相同（§6-③），建议核对 `m3u_provider_key` 输入。
