# 睛彩 117/118/119 时间轴诊断与实验（t15）

> 替代 t12；依赖 t13（设置二级）与 t16（信息卡焦点修复）已完成。
> **本任务只改代码与 CI，不执行 adb/设备命令。** 设备侧复验由 acceptance(t9) 按 §7 执行（不抢 TV）。
> 门禁：JVM / lint / pair gate / keep / 三守卫（+ `check_perf_policy` + 新增 `check_jingcai_timeline`）。

---

## 0. 结论摘要

| 项 | 结论 |
|---|---|
| 真根因（captain 抓流 10s 样本） | 睛彩 117：PMT 的 `PCR_PID=104`，首个 PCR ≈ **32152.9s**；首个视频 PTS ≈ **64307.0s** → 偏差 **≈32154s（8.9 小时）**。CCTV-1 的 PCR 与视频同 PID、量级一致。 |
| 真机现象对得上 | 重建播放器后解码器能创建、音频轨初始化，但 `firstFrame=false / state=2 / audio_advancing=false` —— 样本时间戳相对 PCR 落在“未来 9 小时”，播放器永远等不到可渲染的帧。 |
| 本轮交付 | ① **修 bytes=0 误判**（DataSource 实读计数 + `firstByteMs` + `code`）；② 三个独立实验 **A/B/C 默认关**；③ **自动检测**（PCR 与首 PTS 偏差 >60s 才启用 A+B）+ 全局回退（设置里把「睛彩诊断实验」切回「关闭」即彻底恢复原路径）；④ JVM 测试 + 新静态门禁；⑤ P1 文档/日志订正。 |
| 不改动 | 播放器/零拷贝/台标/自动更新/音轨/信息卡/设置 IA 均未改；**默认 OFF 时字节流原样透传**（健康频道零影响，`JingcaiTimelineTest.experimentOffIsByteForBytePassThrough` 断言逐字节相同）。 |

---

## 1. 证据链（为什么是时间轴，而不是解码器）

1. 同一 URL 在设备外可拉、HTTP 200、约 8.3 Mbps、H.264 Main@4.0 1080p —— 流本身没问题（t231 已排除 UA/网络）。
2. 同机 CCTV 全部正常 → 排除播放器/渲染通用路径。
3. 抓流对比：睛彩 PCR 与首个视频 PTS 相差 **8.9 小时**；CCTV-1 同 PID、同量级。
4. 现象：`firstFrame=false`、`state=2(BUFFERING)`、`audio_advancing=false` —— 是“时间戳不可渲染”，不是“解码器建不起来”。

工程结论：**不要再去换解码器/软解；先修时间轴**（实验 A/B），并保留软解作为最后一级 ladder（已有，不动）。

---

## 2. 诊断修正：`bytes=0` 是测错了，不是真的没读

**旧实现的问题**：诊断值只累加 `AnalyticsListener.onLoadCompleted.loadEventInfo.bytesLoaded`。**直播流永远不会 `complete`**，所以 `bytes` 恒为 0，把“其实读了几 MB”误判成“一个字节都没读”。

**修复**（`StreamPlayback.prepare` → `JingcaiTsDataSource` 包一层 DataSource）：

- 每次 `read()` 真实读到的字节都进 `JingcaiDiag.onBytes(n)`（`AtomicLong`），并记录 **`firstByteMs`**（相对进程的首次读时刻）与 **`code`**（open/eof 等）。
- give-up 诊断行改为：
  `mode=.. bytesRead=<实读> firstByteMs=<..> format=.. firstFrame=false state=2 code=..`
  再追加一行时间轴：`pcr=.. pts=.. offset=.. mode=.. pcr_pid=.. drop_pcr_only=.. rewrite_pmt=..`
- `bytesRead = max(onLoadCompleted 累计, DataSource 实读)`，两路兜底不互相覆盖。

---

## 3. 纯逻辑：TS/PMT/PCR 解析（`JingcaiTimeline.kt`，无 Android import）

| 能力 | 实现 |
|---|---|
| TS 包头 | `packetPid` / `adaptationFieldControl` / `hasPayload` / `pcrFlag` / `payloadOffset` |
| PCR | `readPcr27`（33-bit base×300 + 6-bit ext，27MHz）→ `pcrSeconds` |
| PCR-only 包 | `isPcrOnlyPacket(p, off, pcrPid)` = 该 PID + 仅适配域 + 有 PCR + **无 payload** |
| PMT | `pmtSectionOffset` / `pmtPcrPid` / `pmtSectionTotalLength` / `pmtCrcValid` |
| PCR_PID 改写 | `rewritePmtPcrPid(p, off, 0x1FFF)` 原地改两字节并**重算 CRC32**（MPEG-2，poly 0x04C11DB7） |
| 首视频 PTS | `readVideoPts`（PES `00 00 01`、stream_id 0xE0–0xEF、PTS_DTS_flags≥2、33-bit PTS） |
| 判定 | `offsetSeconds` / `needsTimelineFix`（**|偏差| > 60s**） |

`JingcaiTsPipeline.kt`（纯逻辑）把字节流按 188B 切包并做变换：

- **对齐**：确认 `p[i]==0x47 && p[i+188]==0x47` 才动手；8KB 内找不到同步 → **永久透传**（`ALIGN_OFF`）。
- **A**：丢“仅 PCR、无 payload”的包（`drop_pcr_only` 计数）。
- **B**：PMT 的 PCR_PID → `0x1FFF` + 重算 CRC（`rewrite_pmt` 计数）。
- **AUTO**：先透传，读到 PCR 与首视频 PTS 后若偏差 >60s → 自动开启 A+B（`mode=auto-on`）。
- **OFF / E1 / E2 / E3 / C**：`mutates=false`，字节原样透传（C 是“时基归零”的设计占位，见 §6）。

`JingcaiTsDataSource.kt`（Android）只做一件事：包一层上游 DataSource，计数 + 把字节喂给 pipeline；**上游线程不变**（仍是 Media3 loader 线程），不新增线程。

---

## 4. 实验开关（默认关，单变量）

复用既有设置行「播放与画面 → 睛彩诊断实验」，`OK` 循环（`Prefs.jingcai_experiment`）：

```
关闭 → E1 → E2 → E3 → A(丢PCR-only包) → B(PMT PCR→0x1FFF) → 自动时间轴修复(>60s) → C(时基归零设计) → 关闭
```

- **默认 `off`**：`JingcaiExperimentTest.theDefaultIsOffAndUnknownValuesFallBackToOff`。
- 每个模式只动一个旋钮：`everyExperimentTouchesExactlyOneKnob`（A/B/AUTO 归为“包级改写”这一个旋钮；C 单独验证为惰性占位）。
- **全局回退**：任意时刻把该行切回「关闭」，下一次换台即回到原路径，无残留状态（pipeline 每次 prepare 新建）。

---

## 5. 自动检测（有效后才启用）

- 门槛：`JingcaiTimeline.MAX_OFFSET_SECONDS = 60.0`（`|pts − pcr| > 60s` 才启用）。
- 场景：CCTV 这类 PCR 与 PTS 同量级的流，AUTO **不激活**（`JingcaiTimelineTest.autoModeActivatesOnlyWhenOffsetExceedsSixtySeconds` 两个方向都断言）。
- 激活后同一连接内持续生效；换台重新判定（每 `prepare` 都是新的 pipeline）。
- 若 A/B 在真机被证明无效，就保持默认 OFF，把结论写进本文 §8，不改默认。

---

## 6. 实验 C（时基归零）——设计占位，不实现

若 A/B 有效但仍有残余：把首帧前的 PTS/PCR 统一平移到从 0 开始（PES PTS 与 PCR 同步减偏移）。
风险：需要跨包改写 PES 头 + 重算 PCR，且遇到 B 帧/多参考流容易出错；本轮**只保留模式标签与设计说明**（`JingcaiExperiment.timebaseRebase(C)=true`，`touchesPacketBytes(C)=false`），等 A/B 真机结论后再决定是否实现。

---

## 7. P1 顺带订正（文档/日志）

1. **`giveup=1`**：ladder 放弃时日志固定带 `giveup=1 stage=<stage>`，方便 acceptance grep。
2. **rebuild / soft 各一行 `bufferMin` 属正常**：重建（rebuild）与软解（soft）是 ladder 的两级，各打一行日志、各带一次 `bufferMin`/`range`，**不是重复启动**；看到两行不要误判为 crash/双播放器。
3. **三台睛彩同 `channel.id`**：117/118/119 三个睛彩频道在源里共用同一 `channel.id`（同 `tvg-id`），因此 ① 最近观看/收藏按 id 去重时只会出现一个；② 台标缓存 key 相同（复用同一张台标）；③ `ZapRebuildPolicy` 的 ladder 以 `"$channelId"` 为键，**在 117↔118 之间来回切会共用 ladder 状态**（一次 rebuild/一次 soft 的额度被共享）——这是已知且可接受的行为，验收时不要当成“第二次不重建”的 bug。

---

## 8. 设备侧复验（acceptance / t9，不抢 TV）

> 设备现已停在 CI10145/后续验证包；按 t9 一次性覆盖（性能/设置/信息卡/焦点/睛彩）执行时顺带做本节。

1. `adb shell setprop log.tag.IQTV-JINGCAI DEBUG`；进睛彩 117。
2. 设置「睛彩诊断实验」= **A**，换台回 117：抓 `logcat -s IQTV-JINGCAI`，确认出现
   `pcr=.. pts=.. offset=..`（offset ≈ 32154）与 `drop_pcr_only=N`；看是否出首帧。
3. 切 **B**：确认 `rewrite_pmt=N`，看是否出首帧。
4. 切 **自动时间轴修复**：确认 `mode=auto-on`（只有偏差 >60s 才出现）；再换 CCTV-1 确认**不激活**（健康流零影响）。
5. 每个实验都要记录：`firstFrame` / `state` / `rebuffer` / `IQTV-DIAG bytesRead/firstByteMs/code`（验证不再是 0）。
6. 收尾把该行切回「关闭」，确认最坏情况回归原状（睛彩仍不出帧属已知问题，不阻塞其他验收）。

**判定**：任一个实验能在睛彩 117/118/119 出首帧 → 记录哪个模式有效，反馈给 captain 决定是否把 AUTO 设为默认。

---

## 9. 测试与门禁

- 新增 `JingcaiTimelineTest`（14 例）：包头/PCR/PCR-only 判定、PMT PCR_PID 与 CRC（含**改写后 CRC 仍有效**、`data||crc` 的 CRC 为 0）、首视频 PTS、60s 判定（正/负/未知）、A 丢包计数、B 只改字节、AUTO 双方向、错位流永久透传。
- 更新 `JingcaiExperimentTest`：模式循环（8 个）、单旋钮不变量（A/B/AUTO 归一）、C 惰性、诊断行新格式。
- 新增静态门禁 `scripts/check_jingcai_timeline.py`（已接 `apk.yml`）：纯逻辑无 Android import、60s 门、A/B/AUTO 是唯一改写者、DataSource 实读计数、`giveup=1` + 时间轴诊断行、对齐失败回退。

---

## 10. 本次落地（t49）与改动清单

t15 的设计在 t49 落地为可编译、可门禁的代码（此前 WIP 只在 stash）：

| 文件 | 作用 |
|---|---|
| `JingcaiTimeline.kt` | **纯逻辑**：TS 包头/PID/PCR 解析、PMT PCR_PID 解析、PMT CRC32 重算、`isPcrOnlyPacket`、偏差判定（>60s）、`diagnosticLine()`（`pcr/pts/offset/mode`） |
| `JingcaiTsPipeline.kt` | 包级变换流水（A：丢 PCR-only 包；B：PMT PCR_PID→0x1FFF + 修 CRC32），非实验模式 `mutates=false` 直接透传 |
| `JingcaiTsDataSource.kt` | DataSource 包装：**实读字节计数**（`JingcaiDiag`）+ 实验时交给 pipeline；其余模式纯计数、零改写 |
| `JingcaiDiag.kt` | 计数 + `bindPipeline()` + `timelineLine()`；`IQTV-DIAG bytesRead/firstByteMs/code` |
| `StreamPlayback.kt` | 按实验模式创建 pipeline 并 `bindPipeline`；无首帧/giveup 时打 `timeline` 一行（`format/currentPosition/bufferedPosition/duration/windowCount/state` + `pcr/pts/offset`） |
| `JingcaiExperiment.kt` | 模式 `OFF/E1/E2/E3/A/B/AUTO/C`，**全部默认 OFF**；`touchesPacketBytes()` 只对 A/B/AUTO 为真 |
| `JingcaiTimelineTest.kt` | JVM：PCR 包识别、PMT PCR_PID 解析、CRC32 重算、60s 偏差判定、A/B 变换逐字节比对 |
| `scripts/check_jingcai_timeline.py` | 静态门禁：纯逻辑无 Android import、默认关、60s 闸门、真实字节计数、只有 A/B/AUTO 改字节 |

> 已删除旧的 `CountingDataSource.kt`（被 `JingcaiTsDataSource`（pipeline 可空）取代），
> `check_perf_policy.py` 的断言同步改为 `JingcaiTsDataSource(`。
> `apk.yml` 门禁链新增 `python3 scripts/check_jingcai_timeline.py`。

### 10.1 P1 订正（已在代码/文档落地）

1. **giveup 日志**：放弃时打 `… giveup=1 stage=<stage>`，并附 `IQTV-DIAG bytesRead/firstByteMs/code`
   与 `buffer <bufferDiagnosticLine>`、`timeline <timelineDiagnosticLine>` —— 一眼看出是“没读到字节 / 有字节但无首帧 / 有时基问题”。
2. **bufferMin 两行说明**：起播缓冲档位（300/400/500，默认 500）与 `LoadControl` 的
   `minBuffer/maxBuffer/playbackBufferMin/afterRebuffer` 是**两套东西**：前者决定“起播前攒多少”，
   后者决定“运行中水位”。文档不再混用两个词。
3. **同 `channel.id` 说明**：睛彩 117/118/119 共用同一 `channel.id` 时，`ZapTrace`/`JingcaiDiag`
   的键按 `channelId + url` 组合，避免“换台但 id 相同”导致诊断串台。

## 11. 交付 receipt

| 项 | 值 |
|---|---|
| 提交 / CI | 见 t15 任务输出（本文件随实现一起提交；文档定稿后再补 run 号） |
| 回退 | 设置行切回「关闭」即全局回退；代码级 `git revert` 本提交回到 t16 状态；无 Prefs 迁移 |
