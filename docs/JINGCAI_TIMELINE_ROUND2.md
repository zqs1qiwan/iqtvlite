# 睛彩第 5 轮（t54）：把诊断下沉到 App 内（TS Probe）+ F/G/H 实验

> 前置更正（captain 本地抓流复核）：**PCR/PTS 偏移不是异常**——睛彩 PCR 32152.9s / PTS 64307.0s（差 32154s），
> CCTV PCR 30608.4s / PTS 61217.5s（差 30609s），两者**同构**；A/V 偏移睛彩 0.264s、CCTV −0.009s。
> App 日志里的 `pcr_pid=-1` 是**我们自己的诊断解析器**的问题（PMT 定位写死 `PID_PMT=0x0000`），不是 ExoPlayer。
> 因此 **A/B、PTS 归零(E)、去 PCR(D) 全部降级为低优先、默认关保留**，本轮重点是**让 App 自己解析实际收到的字节**。

---

## 1. TS Probe（默认关，`iqtv_probe_ts=1` 打开）

`JingcaiTsDataSource` 旁路缓存前 **2MB**（只读，**不改变输出**），达到 **512KB** 时解析一次并打一行
`IQTV-JINGCAI-PROBE`（看完即 `adb shell settings delete global iqtv_probe_ts`）。

| 字段 | 含义 | 对照期望（PC 抓流） |
|---|---|---|
| `pmt_pid` | 从 **PAT** 解析出的 PMT PID（不再写死 0x0000） | 睛彩/CCTV 应可见 |
| `pcr_pid` | PMT 里声明的 PCR_PID | 若无 → 流侧真没有 PCR |
| `pcr=yes/no` | 是否真的出现过带 PCR 的包 | PC：有 |
| `streams=` | PMT 的 ES 列表 `stream_type@pid` | 视频 0x1B@…、音频 0x03/0x04@… |
| `vpts` / `apts` / `av` | 首视频 PTS / 首音频 PTS / **A−V 偏移**（秒） | 睛彩 av≈0.264s、CCTV ≈−0.009s |
| `pid_top=` | PID 直方图 top5 | 对照 PID 是否与 PC 一致 |
| `cc_err=` | 连续计数错误（丢包/乱序） | 弱机网络差时会升高 |
| **`null_pct=`** | **null 包占比** | **睛彩 ≈20%、CCTV ≈1.6%** |
| **`pes_len_zero/nonzero`** | **PES 声明长度分布** | **睛彩非 0、CCTV 0** |
| `pmt_interval=` | PMT 平均间隔（包数） | 周期是否正常 |

**判定**：若设备侧的 `null_pct` / `pes_len_*` / `streams` / `av` 与 PC 抓流**不同形** → 说明设备收到的流不一样
（源侧按 UA/地域/网关分片），继续在 App 里改字节无意义；若**同形** → 问题在 ExoPlayer 侧，再看 F/G/H。

---

## 2. 实验（全部默认关、逐字节直通、单变量）

| 模式 | 做什么 | 目的 | 实现 |
|---|---|---|---|
| **F** | 连接后**延迟 1.2s** 再读/播（`DELAYED_START_MS`），TTFF 由既有 ZAP 埋点照常报出 | 验证“一连接就读”是否触发首帧失败 | `StreamPlayback.prepare` 把 prebuffer 抬到 ≥1200ms |
| **G** | 丢掉**首个 PAT 之前**的前导字节（`firstPatOffset`），再从 PAT 开始吐数据 | 验证前导垃圾/半包是否让 extractor 走岔 | 管线对齐阶段定位首个 PAT 后起播 |
| **H** | 失败后**重建并新建 DataSource（不复用旧连接）**；统计 3 次成功率 | 验证“旧连接已坏”导致的间歇失败 | 现状每次 rebuild 都新建 player+DataSource；本轮明确记录并在验证时统计 3 次 |
| A | 丢 PCR-only 包 | （低优先） | 已有 |
| B | PMT PCR_PID→0x1FFF + 修 CRC32 | （低优先） | 已有 |
| **D** | 彻底去 PCR：丢 PCR-only **+ 剥离 adaptation field 的 PCR_flag** | （低优先） | 本轮新增 |
| **E** | **PES PTS/DTS 归零**（减首个 PES 基准；保留 marker bits/33 位/头长度） | （低优先） | 本轮新增 |

日志统一 `IQTV-JINGCAI mode=.. pcr=.. vpts=.. apts=.. av=..`（A/B/AUTO/D/E 各有计数，E 额外打 `base_pts=`）。

---

## 3. 纯逻辑与测试（`JingcaiTimeline`，无 Android import）

新增：`probeTs` / `probeLine` / `patPmtPid` / `pmtSectionAt` / `pmtPcrPidAt` / `pmtStreamsAt` /
`readPesPts` / `rewritePesTimestamps` / `stripPcrFlag` / `firstPatOffset`。

JVM 测试（`JingcaiTimelineTest`，t54 新增 4 例）：
1. `experimentERewritesPesPtsAndKeepsMarkerBits` —— PTS−base、marker bits 仍为 '0010'/1、负偏差钳 0；
2. `probeReportsNullPacketRatioAndPesLengthDistribution` —— null_pct=50、pes_len_zero/nonzero 各 1；
3. `probeParsesPmtStreamsAndPcrPidFromAnyPid` —— 任意 PID 上的 PMT：PCR_PID + 两条 ES 解析正确；
4. `experimentGAlignsToFirstPatPacket` —— 跳过 7 字节前导定位 PAT；无 PAT 返回 −1。

---

## 4. 回退

| 想关 | 操作 |
|---|---|
| Probe | `adb shell settings delete global iqtv_probe_ts`（默认关） |
| 实验 F/G/H/D/E | 设置里实验模式切回「关闭」（默认即关闭）；A/B/AUTO 同理 |
| 代码级 | `git revert` 本轮提交；纯逻辑无副作用，健康频道逐字节直通 |

---

## 5. 交付 receipt

| 项 | 值 |
|---|---|
| 提交 | `3ef11d1`（r5 主体）→ `375b123`（D/E 计数与诊断字段，修编译）→ `3241d72`（测试 Byte 字面量）→ `e0eabb6`（PMT 夹具 pointer_field + 模式集合期望） |
| CI run（绿） | **#195**，sha `e0eabb6`，push，conclusion `success` |
| 构建 | `BUILD SUCCESSFUL in 3m 10s`；`:app:testDebugUnitTest`（**337 用例**，含 t54 新增 5 例）+ `:app:lintDebug` + 双包 assemble |
| 门禁 | **`check_jingcai_timeline: OK (… A/B/AUTO/D/E + probe/F/G/H)`**；其余七门禁全 OK；pair gate `{0,0,0}` |
| 包 | `tv.iqtvlite.app.validation`，versionCode **10195** |
| app sha256 | `29eb7f5cf260ca6f4df272c155e0a05aa63a5a63a9e1ad3a75c7970abb5c94ac` |
| test sha256 | `7b531016771c354537456de58753a883ccd35a99d478d7a80a0eeb576288e819` |
| 产物 | `iqtvlite-test-apk`（artifact id 14727825291，10,475,492 B ≪30MB） |
