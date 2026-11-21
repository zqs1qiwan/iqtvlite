# 睛彩解码器重建 + 软解兜底（t234）

2026-11-21. Task t234 (developer2). 默认行为对健康频道**零影响**：只有在「8 秒没有首帧」这条已经存在的看门狗路径上才升级动作。

## 根因（设备侧硬证据）

| 证据 | 结论 |
|---|---|
| OMX/ACodec/OMXVDEC 计数全 0、无 init/configure/start failed、无丢帧 | **视频解码器从未被创建** |
| `format=avc/1920x1080` 已出、音频正常起播 | extractor 与音频链路正常，卡在视频解码器创建/选择 |
| CCTV-1 同机正常（`OMX.goke.video.decoder.avc` 被创建） | 不是设备能力问题 |
| 睛彩：**完全没有 AUD(NAL type 9)**、独立稀疏 PCR(pid 104)、PES flags 0x80/0xC0 混合、PTS 有 B 帧重排 | 码流形态与该 SoC 的硬件解码器路径不兼容 |

**关键推论**：旧的重试是 `seekToDefaultPosition()+prepare()`（**同一个 player**），根本不会重新创建 MediaCodec → 对这类流**必然无效**。所以升级为「重建 player」，再不行才用软解。

## 实现

### 阶梯（`ZapRebuildPolicy`，纯逻辑、可测）

| 阶段 | 8 s 仍无首帧时 | 动作 |
|---|---|---|
| `NORMAL` | 第 1 次 | **REBUILD**：释放旧 player → 新建 → 重放同一 URL（硬件视频，与原来完全一致） |
| `REBUILT` | 第 2 次 | **SOFT**：再新建一次，但**追加 FFmpeg 视频渲染器**（软解） |
| `SOFT_TRIED` | 第 3 次 | **GIVE_UP**：Toast「该频道暂不可播（网络/源响应慢）」并停止 |

- 每次用户换台（`playGroupAuto` 首行）→ `StreamPlayback.onUserZap()` **清空阶梯**，所以「重建一次、软解一次」是**每次换台**的额度，不是全局循环。
- `Ladder` 按频道 key 记账，不同频道互不影响，阶段只会单调前进且**在最后阶段饱和**（永不再重建）。

### 重建（`PlayerActivity.rebuildPlayer`）

```
releasePlayer()                                   // 释放旧实例（StreamPlayback.release 里会 cancel 看门狗 job）
initializePlayer(softVideoOverride, prepareNow=false)
StreamPlayback.prepareForRebuild(fresh, context, request, intent, onFailure)
```

- `prepareNow=false`：`initializePlayer` 只建 player，不再走它自己的 `prepareRequest`，避免**两次 prepare**。
- `prepareForRebuild` → `prepare(..., zapTracking = false)`：**不 arm、不写 `ZapTrace`**，因此**不会产生第二条 `IQTV-ZAP` 行**（重放不是用户换台）。
- 防抖：`rebuildBusy[0]` 布尔门，重建过程中再次超时直接返回（不会重入）。
- 播放意图/遮罩状态不碰：`playbackIntent` 原样传给 `prepareForRebuild`，抽屉/设置等 Compose 状态完全不动。

### 软解兜底（`JingcaiRenderersFactory`）

- 默认构造（`softVideo=false`）**与原来逐字节一致**：`DefaultRenderersFactory` + `setEnableDecoderFallback(true)` + `EXTENSION_RENDERER_MODE_ON`（FFmpeg 仅用于音频兜底，AVC/AAC 仍走 MediaCodec）。
- 只有 `softVideo=true`（阶梯最后一档）时才 `buildVideoRenderers` **先**加入 `ExperimentalFfmpegVideoRenderer`，MediaCodec 作为其后备。armv7 上 1080p 软解可能掉帧——这是最后手段，每次换台只用一次。
- FFmpeg 视频渲染器来自项目已依赖的 `lib-decoder-ffmpeg-release.aar`（`FfmpegLibrary.isAvailable()` 守卫，不可用则自动退回纯硬解路径）。

### 日志

```
IQTV-JINGCAI rebuild=1 channel=117
IQTV-JINGCAI softfallback=1 channel=117
IQTV-JINGCAI giveup=1 channel=117
```

全部 `Log.isLoggable("IQTV-JINGCAI", DEBUG)` 门控。

## 测试（`ZapRebuildPolicyTest` 8 项）

| 测试 | 断言 |
|---|---|
| `theLadderIsRebuildThenSoftThenGiveUp` | 阶段 → 动作映射 |
| `onlyTheSoftStepUsesTheSoftwareVideoRenderer` | 只有最后一档才软解，**第一次重建仍是硬解** |
| `stagesAdvanceAndSaturate` | 阶段单调前进、末尾饱和（无循环） |
| `theLadderRunsOncePerChannelAndStops` | 第三次超时是 GIVE_UP，不会再次重建 |
| `aUserZapGivesTheChannelAFreshLadder` | 换台后额度重置 |
| `channelsAreIndependentAndClearResetsEverything` | 频道互不影响 / clear 全清 |
| `theLogLinesAreStable` | 三条日志文本 |
| `aHealthyChannelNeverLeavesTheNormalStage` | 健康频道停留 NORMAL，永不触发（默认路径不变） |

JVM **221 → 229**。

## 回退

- **单点回退**：`StreamPlayback.setRebuildHandler(null)` → 看门狗退回「同 player 重试」的旧行为；`JingcaiRenderersFactory(context, softVideo=false)` 永远不软解。
- 整个阶梯由 `ZapRebuildPolicy.actionFor()` 决定，改成只返回 `GIVE_UP` 即恢复 CI10136 的「一次重试 + 提示」语义。
- 硬件路径（健康频道）在任何情况下都不会走软解：`softVideo` 只由阶梯最后一档置 true。

## Preserved

F1/F3/F4、默认缓冲 **稳/500**、LoadControl、MP2、零拷贝 SurfaceView、去圆角、频道键、数字 OSD（t223）、台标、最近观看、EPG、SR、品牌。体积 ≤30 MB。

## Verification

- Local `--no-daemon`: **BUILD SUCCESSFUL**; JVM **229 tests 0 failures** (`ZapRebuildPolicyTest` 8); lint **0 errors**/90 warnings; pair gate missing 0; keep 210 identical; dex 7 OK; 三守卫 OK; APK 16,096,197 B ≤ 30 MB.
- CI pair receipt (run 44884145289, source `fcc76038`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **229 tests 0 failures** (`ZapRebuildPolicyTest` 8); lint 0 errors / 88 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,092,817| `69008758689faf98312a86a574e5ca1850246bd7a67ebbafd83621f975f79d26` |
| app-debug-androidTest.apk |502,887| `48750c8cddcf1f96c11ad0078537e56b6251e799741298012fe1edce5f5f4027` |

- Artifacts: pair `14699426485` (`sha256:df2cb14cbb6503d21359ac526ae77520b2a3334422c20ebebe44f0fe85948719`, zip 10,369,851 B); reports `14699615632` (`sha256:ea3e871051eefc392c9b8b0ef18d7461ce3749c1fdf1f9b8979379626f4b0995`). Signer `c25469717f2eddb9132a07cb9cb8d472cea01799bcd55a8218c741953c89baff`.
- Version: `tv.iqtvlite.app.validation / 10137 / 2.2.0-tivimini-fcc76038-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,092,817 B ≤ 30 MB.

## 设备协议（acceptance，单次安装）

1. `setprop log.tag.IQTV-JINGCAI DEBUG` + `IQTV-WATCHDOG DEBUG`。
2. 选 **117**：8 s 后应看到 **`rebuild=1 channel=117`**（新 player 被创建 → `OMX.goke.video.decoder.avc` 应出现创建日志）。
3. 若重建后 8 s 仍无首帧 → **`softfallback=1 channel=117`**，此时应看到 FFmpeg 软解在跑（可能掉帧但出画）。
4. 再 8 s 仍无 → Toast 一次 + `giveup=1`，不再循环。
5. **CCTV-1 回归**：正常出画、无任何 `rebuild/softfallback` 日志、仍走硬解。
6. 全程 `IQTV-ZAP` 每个频道**仍只有一行**（重建不产生第二行）。
