# 睛彩 APP 侧诊断（t231）

2026-11-21. Task t231 (developer2). **三个实验全部默认关闭**，默认播放行为与 CI10135 完全一致。

## 已排除（captain 设备侧证据）

| 检查 | 结果 |
|---|---|
| 源可达 | 设备 curl HTTP 200、~8.3 Mbps、6 s 6.25 MB |
| 编码 | SPS = H.264 Main@4.0、8-bit 4:2:0、progressive 1920×1080 |
| UA | 无关（换 UA 同样表现） |
| 其他频道 | 同机 CCTV 正常 |

→ 结论：问题在**本 App 播放链路**对这类 TS 流的处理，而不是源/网络/编码。

## 1) 无首帧诊断（DEBUG 门控）

`IQTV-JINGCAI` 标签，**只在看门狗最终放弃时打印一次**（即真的没有首帧），格式：

```
mode=e1 bytes=0 format=none firstFrame=false state=2
```

| 字段 | 来源 | 用途 |
|---|---|---|
| `mode` | 当前实验 | 确认单变量 |
| `bytes` | `AnalyticsListener.onLoadCompleted` 累计 `bytesLoaded` | Loader 是否真的读到数据 |
| `format` | `onDownstreamFormatChanged.trackFormat` → `mime/宽x高@帧率` | **extractor 是否解析出 format**（`none` = 卡在 extractor/未出流） |
| `firstFrame` | `Metrics.firstFrame` | 是否渲染过首帧 |
| `state` | `player.playbackState` | 2=BUFFERING、3=READY |

判读：`bytes>0 && format=none` → 数据到了但 extractor 未产出（E1/E3 方向）；`bytes=0` → 数据源/连接层（E2 方向）；`format!=none && firstFrame=false` → 解码/渲染层。

**诚实说明**：PMT/PID/首个 PTS/TS 同步字节需要自建 TS 解析器，本版**未做**——ExoPlayer 不暴露这些值，自己再解一遍会与播放器重复且影响时序。诊断只覆盖"到哪一层"（数据源/extractor/解码渲染），足以选实验；若需要 PID/PTS 级证据，需另派任务做**旁路只读 TS 解析**（不接播放链路）。

## 2) 三个单变量实验（设置 → 播放与显示 → 睛彩诊断实验，循环切换）

| 实验 | 唯一改动 | 针对的假设 |
|---|---|---|
| **E1** | `setTsExtractorTimestampSearchBytes(1 MB → 4 MB)` | 该流 null 包多，首个 PTS 可能在 1 MB 之后 → extractor 迟迟不产出 sample |
| **E2** | 数据源从 `OkHttpDataSource` 换成 `DefaultHttpDataSource`（仍带原 UA/头） | OkHttp 客户端/拦截器在该流有异常（分块、超时、缓冲区） |
| **E3** | 去掉 CEA608 字幕注入 + `experimentalParseSubtitlesDuringExtraction(false)` | 字幕解析在 TS 上拖住了数据通路 |

约束（有 JVM 测试守护）：**每次只改一个开关**（`everyExperimentTouchesExactlyOneKnob`），默认 `off` 时三个开关全部等于原值。

## 3) 验证流程（acceptance，单次安装）

`setprop log.tag.IQTV-JINGCAI DEBUG`（以及 `IQTV-WATCHDOG`、`IQTV-ZAP`），对 **117/118/119 同一 URL**：

1. 默认「关闭」→ 记录诊断行（预期 `bytes>0 format=?`）。
2. 切 E1 → 重新选台 117 → 看是否出 `first_frame` / `state=3`，记录诊断行。
3. 切 E2 → 同上。
4. 切 E3 → 同上。
5. 最后切回「关闭」。

每一档记录：能否出首帧、`bytes`、`format`、`state`、以及 `IQTV-ZAP` 行。

## 4) 结论与回退

- 若某实验能出首帧 → 该开关即根因方向，下一步把它做成**默认修复**（仅对受影响流或全局安全值），并保留回退常量（`TS_SEARCH_BYTES_DEFAULT`）。
- 本任务**不下结论**：实验开关已就绪，等待 acceptance 的单次安装数据。
- **回退**：设置改回「关闭」即恢复 CI10135 行为；删除 `JingcaiExperiment` 引用亦可完全移除（默认关，无其他调用点）。

## Preserved

F1/F3/F4、默认缓冲 **稳/500**、LoadControl、MP2、零拷贝、去圆角、频道键、数字 OSD（t223）、台标、最近观看、EPG、SR、品牌。体积 ≤30 MB。

## Verification

- Local `--no-daemon`: **BUILD SUCCESSFUL**; JVM **214 → 221 tests 0 failures** (`JingcaiExperimentTest` 7); lint **0 errors**/90 warnings; pair gate missing 0; keep 210 identical; dex 7 OK; 三守卫 OK; APK 16,091,977 B ≤ 30 MB.
- CI pair receipt (run 44877851760, source `8c1f3ebb`, **success**, NDK r27, dual ABI/API24, pair gate passed): cloud JVM **221 tests 0 failures** (`JingcaiExperimentTest` 7); lint 0 errors / 88 warnings.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |13,092,813| `07b805dc071b0b6f4a7bea62e4bf12c3537704c7ae05040e19b28829e2213206` |
| app-debug-androidTest.apk |502,955| `cb84b985e337299e33e77d3bd8fb39e7bab7f0eb41d43f874eafb6c3dd67ab06` |

- Artifacts: pair `14697261176` (`sha256:b1c8f1be11b3b51de2858bd3117568845802073356d82dc6b6a91c70354037f1`, zip 10,365,606 B); reports `14696781707` (`sha256:ae40cc1ac857eb64a4caea36a4a752e09032dda9a82907de4f403284926098a4`). Signer `2913b0334f5ab0e565c7157b17dc07ff5666086982dd6ca5d522dad66392f3ef`.
- Version: `tv.iqtvlite.app.validation / 10136 / 2.2.0-tivimini-8c1f3ebb-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 identical. Size 13,092,813 B ≤ 30 MB.
