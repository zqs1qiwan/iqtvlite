# Zap 全入口打点 / log-once 复查（t208 / run10126）

任务 t209（attempt 1），designer2，2026-11-21（Asia/Hong_Kong）。**结论：needs_revision。** t208 把打点加到 `playGroupAuto`（首 variant）和 `playGroupVariant`（目标 channel），并加 `loggedKey` + `summaryLineOnce()`，解决了“D-pad/数字无打点”和“重复首帧多行”的大部分问题；`summaryLineOnce`、重复首帧不输出、epg/IO、t205 buffer 边界均未破坏。但 **`playGroupAuto` 用 first variant 的 key、`playGroupVariant` 用实际 variant 的 key**：auto→variant 嵌套只在“selectedIndex=0”时幂等；当第一个源不可达/选中第二源（多源频道常见），`playGroupVariant` 会以不同 key 重新武装，`prepareDelay` 变回接近 0，且 probe 时间被排除；验收①“D-pad 连切 prepare>0”仍不保证。文档 “auto→variant 嵌套不重置” 是过度陈述。

> 只读固定 commit `bd54e254`；HEAD `448615b`（docs-only）。工作树 clean。未改代码、未跑 Gradle/CI/ADB、未操作 TV。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`；被审查 commit `bd54e2546d87b97b21d436586b49591b51e6d858`。
- `git show --stat`：3 files +70/−6：`ZapTrace.kt`、`ZapTraceTest.kt`、`PlayerActivity.kt`；docs `ZAP_COLDSTART_OPTIMIZATION4.md` 448615b。
- pair：主 `13,059,991B` SHA `2011476c…171e9c`、test `502,955B` `6fdd6350…f265bb`、run 44852052430（run10126）、10126；设备未验证。
- 关键行：`PlayerActivity` `playGroupVariant` L1085–1101（onZap L1092）、`playGroupAuto` L1122–1135（onZap L1129 附近）、`playChannel` L1174–1195（onZap L1179）、`onRenderedFirstFrame` L694–700；`ZapTrace` `loggedKey` L30、`summaryLineOnce` L90–100、`reset` L110；`ZapTraceTest` 12 项。
- 当前 source 包含 t205 的 buffer tiers（`LoadControlTuning` 300/400/500），但本任务只评价 t208 diff；t205 不在本报告范围。

## 2. 已通过/正确部分

### ZAP-ENTRY-01（覆盖）— 大部分 pass
- `playGroupAuto` L1128–1135 在 `Log.isLoggable(ZAP_TAG, DEBUG)` 内 `onZapRequested(zapKey(firstVariant.id, url), now)`；`playGroupVariant` L1090–1095 同 gate 内 `onZapRequested(zapKey(channel.id,url), now)`；`playChannel` L1176–1181 同 gate。
- 所有路径至少有一个 arm：
  - D-pad/数字 → `playGroupAuto`；
  - 上一台/quality variant → `playGroupVariant`；
  - 抽屉/return live → `playChannel`（再经 `playGroupAuto`/`Variant`）。
- 同 key 幂等 `onZapRequested` L37–41；`ZapTraceTest.aRealZapEntryLeavesAPositivePrepareGap` 断言手写 320ms gap；`theKeyIsTheChannel...` 覆盖 stable key。
- **但 auto→variant 的 key 不总是相同**，见 §3 ZAP-NESTED-01。

### ZAP-REPEAT-01 / log-once — pass
- `ZapTrace.loggedKey` @Volatile；`summaryLineOnce()`：`zapKey==null` 或 `key==loggedKey` 返回 null，否则 `summaryLine` 并记录 `loggedKey`；`reset()` 清 `loggedKey`。
- `PlayerActivity.onRenderedFirstFrame` L697–700 改用 `summaryLineOnce()`；同 key 重复首帧不再打印，换 key 打印；`ZapTraceTest.theLineIsPrintedOncePerChannelEvenIfTheFrameRepeats` 覆盖第一次 line、第二次 null、换频道再次 line。
- **注意**：`summaryLineOnce` 依赖 `zapKey`；若首次 autoplay 只 `onPrepare` fallback（无 key），不会输出（这是可接受的设计，非用户 zap）；文档可说明。

### PERF/DOC 部分 — pass
- `playGroupAuto`/`playGroupVariant`/`playChannel` 的 key 构造与 `elapsedRealtime` 均在 `Log.isLoggable` 内；release 不构造 key、不读时钟；`summaryLineOnce` 只在 gate 内调用/构建。
- `mayLoadEpgCache` 已删；t204 的 EPG `withContext(IO)` 仍保留；EPG collector/10s fallback 未回归。
- **但 `onPrepare`/`onFirstFrame` 仍无条件写 volatile**（见 PERF-01）；文档“zero writes”仍过度。

### 无回归/体积 — pass
- diff 仅 ZapTrace/ZapTraceTest/PlayerActivity；未改 F1/F3/F4 逻辑、LoadControl 数值（t205 已存在）、MP2、零拷贝、0dp、频道键、数字 OSD、台标、最近、EPG 数据层、SR、品牌；APK 13,059,991B≤30MB；三守卫报告 OK。

## 3. 阻断/需修复

### ZAP-NESTED-01（high/medium）auto→variant 不同 variant 的 key 不同，prepareDelay 仍可能 0

**证据**：
- `playGroupAuto` L1128 对 **`group.variants.firstOrNull()`** 的 channel 调 `onZapRequested`（`variant 0` 的 key）。
- 异步 `SourceLatencyProber.probe` 后，`playGroupVariant(groupIndex, selectedIndex, selectionTicket)` L1085；L1090 对 **实际 `selectedIndex` 的 channel** 调 `onZapRequested`。
- 当 `selectedIndex != 0`（第一个源不可达、多个备用源），两个 key 不同：
  - 第一次 arm（auto）被第二次 arm（variant）覆盖/`onZap` reset；
  - `playGroupVariant` 紧接着 `startPlayback`→`StreamPlayback.prepare`，`prepareDelay = prepareAt - variantArmAt` 仅几毫秒，**probe 时间（D-pad 的主要 gap）被排除**，甚至可能是 0；
  - 验收①“D-pad 10 次每次 prepare>0（真实 key→prepare）”在这一常见多源场景不成立。
- 同样 `playChannel(channel)` L1179 arm 目标 key → `playGroupAuto` arm first key（若 target 非 first 不同）→ `playGroupVariant` arm actual key；抽屉选中非首 variant 时多次 reset，最终 gap≈0。
- `ZapTraceTest` 的 `aRealZapEntryLeavesAPositivePrepareGap` 只测单 key 直接 arm，不覆盖 auto→variant 不同 key；文档 “auto→variant 嵌套不重置”只对 selectedIndex=0 成立。

**修复**：
- 不要用 variant URL 作为 zap key；用 **用户选择/频道组 key**（例如 `groupIndex`/`selectionTicket`）作为 `onZapRequested` 的 key，auto→variant 同组保持一致；variant 切换同组不重置；真正换组才重置。
- 或 `playGroupAuto` 只 arm 一次，`playGroupVariant` 增加 `armZap: Boolean=false` 参数（auto 调用传 false，用户直接 quality/上一台传 true）；`playChannel` 只 arm 一次。
- 或把 `onZapRequested` 全部移到**最外层用户入口**（`onChannelStep`、`onNumberJump`、`playLastChannel`、quality、drawer），`playGroupAuto`/Variant 内部不再 arm；用可注入的 `selectionTicket` 归并。
- 测试：selectedIndex=0/1/2 三条 auto→variant 路径都断言 `prepareDelay>0` 且不被嵌套 reset；D-pad/数字/上一台/quality 入口各自计数。

### TEST-01（medium）测试仍是纯函数，不覆盖真实嵌套/重复首帧/EPG 首帧前 emit/10s fallback

**证据**：
- `ZapTraceTest` 12 项全部手工 `onZapRequested`/`onPrepare`/`onFirstFrame`；`aRealZapEntry...` 单 key；`theLine...` 手工；没有：
  - `playGroupAuto → playGroupVariant` selectedIndex!=0 的嵌套；
  - Activity `onChannelStep`/`onNumberJump`/`playLastChannel`/quality 的实际调用；
  - `onRenderedFirstFrame` 重复/重进不第二行（仅纯 `summaryLineOnce`）；
  - `epgUpdated`/`manualEpgRequests` 首帧前 emit；
  - 10s fallback effect 的 IO/UI 行为。
- 178 全绿无法捕获 ZAP-NESTED-01 / 真实路径遗漏。

**修复**：Activity/Robolectric/Compose 测试四个入口；fake `ZapTrace`/时钟；源码扫描 `playGroupAuto`/`playGroupVariant`/`playChannel` 的 arm 位置与 key 一致性；EPG 首帧前 emit 测试。

### PERF-01（low）`onPrepare`/`onFirstFrame` 仍 debug 关闭写 volatile
- `ZapTrace.onPrepare` L? 无条件 `if (zapAt==0L) zapAt=now; prepareAt=now`；`onFirstFrame` 无条件写 `firstFrameAt/lastZapMs`；`onZapRequested` 的 gate 只包调用处，若外部误用仍写。
- 文档 “release zero writes” 不实；建议 `BuildConfig.DEBUG`/enabled gate 或文档改“无日志/无 key 构造，少量 volatile 写”。

### DOC-01（low/medium）文档 “auto→variant nested does not restart” 过度
- 只在 selectedIndex=0（同 key）成立；多源 selectedIndex>0 会 reset，probe gap 丢失。改为“同 group/variant key 时幂等；不同 variant 会重新计时（待修）”，或实现 group key 后更新。

### EPG-FALLBACK-01（low，非阻断）
- t204 的 `withTimeoutOrNull(10s)+snapshotFlow` 在 `LaunchedEffect(Unit)` 仍可被首帧后 IO load 等待；`EpgRepository.load` 已 IO；无 UI 阻塞。未发现新回归；仅保留观察项。

## 4. 真机/视觉未验证

- D-pad 10 次每次 prepare>0；数字/上一台/quality 各一次；同频道重进不第二行；三档 buffer（300/400/500）+ IQTV-ZAP bufferMin + rebuffer/冷启 5.8s；均归 acceptance2（t210）。本报告不宣称设备通过。

## 5. 放行判断

- **源码结论：needs_revision。** log-once/覆盖入口/EPG IO/无回归已到位；但 **ZAP-NESTED-01** 使多源 selectedIndex>0 的 D-pad/数字/抽屉 variant prepare>0 不成立，**TEST-01** 未覆盖嵌套；DOC 过度。
- 建议顺序：**ZAP-NESTED-01（group/selection key 或只在最外层 arm）→ TEST-01（selectedIndex 0/1/2 + 四入口）→ PERF/DOC**；重出 r27 pair 后 t210 正式 A/B。

## 6. 只读命令与终态

`git rev-parse HEAD`、`git status --short`、`git show --stat bd54e254`、`git show bd54e254`、`read ZapTrace.kt/ZapTraceTest.kt/StreamPlayback.kt/PlayerActivity.kt`、`Get-Content docs/ZAP_COLDSTART_OPTIMIZATION4.md`。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 本报告完成后停止读取目标源码。
