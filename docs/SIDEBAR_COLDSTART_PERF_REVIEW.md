# Sidebar / 冷启 / EPG / PSS 性能第二阶段 独立源码复查（t42 / run79）

任务 t43（attempt 1），designer2，2026-11-20（Asia/Hong_Kong）。**结论：源码层可接受（pass）；命中 CI10078 实测的真实残余热点（sidebar 自身 jank / 冷启+EPG CPU 尖峰 / PSS），改动小、可逆、未删功能降质。真机零验证，必须由 acceptance2 在 exact pair 实测；另有若干非阻断加固项（重复 key 崩溃边界、启动 EPG 累计延迟与文档不符、marquee→省略号的可用性取舍）。**

本复查只读固定 commit，未改 Kotlin/Rust/res，未跑 Gradle/CI/ADB，未操作 TV。依据 [t42 修复说明](../../iqtvlite-integration/docs/SIDEBAR_COLDSTART_PERF_REPAIR.md)、[CI10078 零拷贝基座真机验证](CI_SURFACE_BASE_PERF_VERIFICATION.md)、[零拷贝基座复查](SURFACE_BASE_PERF_REVIEW.md)、[24项TIV规格](TIVIMINI_INTERACTION_SPEC.md)。**本报告完成后停止读取目标源码。**

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`，分支 `integration/tivimini-baseline`；HEAD=`e2c5a33`（docs-only），工作树 clean；`62eedde9..HEAD` 未再触及 `android/app/src`。
- 被审查修复 commit `62eedde92bf68b137c733740b46265a551e5d162`（t42），docs 后续 `e2c5a33`。diff 仅 4 文件 **+22/−10**。
- 读取内容身份（git blob，随提交；工作树一致）：

| 文件 @62eedde9 | blob |
|---|---|
| [BackgroundSourceUpdater.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/BackgroundSourceUpdater.kt) | `28c544d2aaf8ff83227f2f0fc7033393980faf80` |
| [LogoLoader.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/LogoLoader.kt) | `07e2e9b57456e077a1927750fa436370151ac490` |
| [PlayerActivity.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt) | `7184daaef2d841abbd2ffeb8c1491ff7ecb18743` |
| [PlayerDrawer.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt) | `15e5c0d96ff94022cfae8a0cce132c8c2fa0a212` |

- 候选身份沿用 t42 记录（本任务未构建/安装）：主 `app-debug.apk` 14,109,967B SHA256 `60efd72f…a89d`，test `2262b13a…8df4`，签名 `7bb63183…a60f`，run 44660869560(run79)，version 10079。**未真机验证。**

## 2. sidebar UI jank 热点 — 命中诊断，非删功能

CI10078 实测 sidebar UI 自身 80% janky / 90th 200ms / 95th 500ms（[CI_SURFACE_BASE §3.2](CI_SURFACE_BASE_PERF_VERIFICATION.md)）。t42 对症：

- **`FocusMarqueeText` 移除 `basicMarquee(Int.MAX_VALUE)`**（[PlayerDrawer:1070–1089](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt#L1070)）：原对焦行无限跑马灯 → 每帧重新组合/测量，是浏览期每帧动画根因。改为 `maxLines=1 + TextOverflow.Ellipsis` 静态。**这是命中根因的正确低风险修**。代价（见 §6-3）：长频道/节目名聚焦时不再滚动、被省略号截断，`focused` 参数变为未用（仅告警）；属视觉取舍、非功能删除。
- **LazyColumn 稳定 key**（[691/795/875](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt#L691)）：groups `"$index:$g"`→`g`（组名）、programs `"$index:…:title"`→`channelId:startMillis:endMillis`、dates `"$index:$d"`→`d`（日期）。去掉 index 后列表变更（切日期/EPG 刷新/筛选）不再整体重建。数据本就在主线程外（EPG `Dispatchers.Default`、logo `Default/IO`、`epgChannelUiCache` LRU32）——未新增主线程负载。**存在条件性重复-key 崩溃边界（见 §6-1）**。
- 350ms EPG 去抖、逐行 `epgChannelUiCache`、`nowProgramByUrl`（Default）保留。**未靠删功能/降可用性换流畅**（marquee 属视觉动画取舍，见 §6-3）。

## 3. 冷启 — 延后启动网络争用（命中冷启 CPU 尖峰）

- [BackgroundSourceUpdater:42–60](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/BackgroundSourceUpdater.kt#L42) 启动网络 playlist 刷新由「立即」→ `delay(8s)`；EPG 常量 `2.5s→12s`。均落在 `Dispatchers.IO` 协程，避开冷启/首帧窗口。**前台首屏仍立即读本地缓存**（PlayerActivity [649–658](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L649) 读 `PlaylistCache`；EPG 缓存 [713–720](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L713) 在 delay 之前加载）→ 首屏不被本改动拖慢。
- 手机推送/源变更流程走 `MainActivity:208 scheduleRefresh(context, delayMs=3000L)`（显式 3s，不受默认值改动影响）→ 推送刷新未回归。`scheduleRefresh` 默认参数虽改为 12s，但当前无调用方使用默认值（仅显式 3000L）。
- **待校正（§6-2，低）**：`scheduleStartupRefresh` 在**同一协程串行**执行 `delay(8s)+refreshPlaylist(...)` 再 `delay(12s)+refreshEpg(...)`，故 BackgroundSourceUpdater 的 EPG 启动刷新实际约 **≥8s + playlist 下载耗时 + 12s ≈ 20s+**，与文档「EPG 延 12s」不符；且 PlayerActivity 自身「首次 EPG 立即刷新」的前提不准确——其循环 [736](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L736) 有 5 分钟门控，`delay(8s)` 后 `lastAttemptTime=now`，首轮即被 5min 门控继续，故 PlayerActivity 的 EPG 网络刷新实际受 5min 门控主导。净效果：**有缓存时首屏不受影响；首次安装无缓存时 EPG 经 BackgroundSourceUpdater 约 20s 出现**（可接受但慢于文档）。非阻断。

## 4. EPG / 预取节流与正确性（不冒充陈旧/缺失）

- **EPG 单飞**：[EpgRepository:17–34](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/EpgRepository.kt#L17) `Mutex + inFlight[source] Deferred` 同源并发去重、失败不覆盖好数据。故 BackgroundSourceUpdater 与 PlayerActivity 两处 `forceRefresh` 同源并发会被合并，无无界并行下载/解码。t42 未引入并发风险。
- **Logo 预取限前 60**（[PlayerActivity:633–638](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L633) `.take(60)`）：其余 logo 由逐行 `ChannelLogo` 按需加载，非删除、非占位缺失。
- **数据正确性**：TIV-07（分组/频道）取自 `channelGroups`、TIV-08（日期/节目）、TIV-13（信息条 now/next）取自 `epgData`。t42 仅改刷新**时机**与 LazyColumn **key 身份**，未改内容/筛选/编号/映射逻辑；`itemsIndexed` 内容仍按 index 取真实数据。**未用陈旧/缺失数据冒充**：缓存先显示、网络成功再替换，失败保留旧好数据（EpgRepository 既有语义）。

## 5. PSS — 封顶缓存而非泄漏；未牺牲功能

- [LogoLoader:63–66](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/LogoLoader.kt#L63)：`LinkedHashMap<String, ImageBitmap>(96, 0.75f, /*accessOrder=*/true)` + `removeEldestEntry = size>96` → **真 LRU 有界**，MAX_ENTRIES 128→96 仅降低驻留上限、非掩盖泄漏；miss 由磁盘缓存兜底，图片功能不降。
- `epgChannelUiCache` 已 LRU32；`EpgData.programsByChannelId` 为功能所需保留（不封顶是合理的，受源节目数约束）。**PSS 收敛靠封顶缓存，非泄漏掩盖**；文档明确「不保证降到 73MiB、只降峰值」，未虚报。

## 6. 缺陷/加固（非阻断，给可执行修复）

1. **（medium）稳定 key 的条件性重复导致 LazyColumn 崩溃边界**：groups key=`g`（[691](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt#L691)）而 `sortedDrawerGroups` 返回 `listOf("全部") + …`（[ChannelGrouper:173](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/ChannelGrouper.kt#L173)）——若真实源存在分组名恰为「全部」，则列表同时含前置「全部」与该分组 → **重复 key → 双击打开抽屉时 Compose 抛 IllegalArgumentException**。programs key 去掉 title/index（[795](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt#L795)）——若同频道 EPG 存在相同 `startMillis+endMillis` 的重复项亦重复 key。`channels` 仍保留 `index:url`（规避重复 URL），说明作者已意识到该风险但 groups/programs 未同等防护。
   **修复**：groups 保证唯一（如 `key={_,g->g}` 前对源头去重，或 `key={_,g->"g:"+g}` 并确保「全部」只出现一次——实际上 `sortedDrawerGroups` 需判重避免前置「全部」与实际「全部」组重名）；programs 用稳定且唯一的身份（如 `channelId:startMillis:endMillis` 去重，或对同键项追加稳定序号作最后区分，避免崩溃优先于稳定性）。验收须用真实源（含「全部」组/重复节目的可能）打开抽屉确认不崩。
2. **（low）启动 EPG 刷新累计延迟与文档不符**：`scheduleStartupRefresh` 串行 `delay(8s)+playlist` 再 `delay(12s)+epg` → EPG 实际 ≥20s（非文档 12s）；PlayerActivity 的 `delay(8s)` 被既有 5min 门控掩盖、几乎无实际提前量。**修复**：将启动 EPG 刷新放入独立 `launch`（独立 12s 定时）或按已耗时补足至 12s；并据实修订文档「PlayerActivity 首次立即刷新」的表述。首次安装无缓存时 EPG 出现 ~20s，需接受或缩短。
3. **（low）marquee→省略号的可用性取舍**：聚焦行不再滚动，长频道/节目名被截断（`focused` 参数已成无用工，可移除或改单次有限滚动）。**修复建议**：如需保留完整名可见，聚焦时用一次性有限次 marquee 或于信息条显示全名；当前属可接受取舍，不影响功能。

## 7. 放行结论与真机门禁

- **源码结论：可接受（pass）**。4 文件 +22/−10、可逆、命中 CI10078 真实热点（marquee 每帧动画、启动网络争用、全量 logo 预取、PSS 缓存上限），未删功能/降画质，EPG 单飞并发安全，PSS 为封顶非泄漏；零拷贝基座/灰屏/频道键/LoadControl/SR/JNI/R8/UA/硬解未改（diff 未触及渲染基座、dispatchKeyEvent、LoadControl、PlaybackIntent、decoder）；体积 14,109,967B ≤30MB。
- **放行 acceptance2 在 run79 exact pair（主 `60efd72f…` / test `2262b13a…` / 10079）真机验证**：
  1. sidebar 打开+8×DPAD_DOWN/4×DPAD_UP+关闭：gfxinfo janky%/50/90/95、missed vsync、slow UI 期望显著低于 80%/90th200/95th500；**视频 dropped=0/rebuffer=0**（不得回退）；**用真实源打开抽屉确认无重复-key 崩溃**。
  2. 冷启 ×3：TotalTime / first-frame；初始化/EPG 刷新期 CPU 尖峰（目标显著低于 86–270%）。
  3. PSS dumpsys 分项稳态、确认无单调增长（封顶非泄漏）；Logo 前 60 之外滚动时逐行加载正常。
  4. 回归：无灰屏回归、频道键 166/167/92/93、zap、24-TIV（含 07/08/13 EPG 数据/时间正确、无陈旧冒充）、SR-01/02/03；`AudioSink$UnexpectedDiscontinuityException` 仍为独立音频项。
- 本结论不批准自身实现、不等于设备验收、不宣称已完美流畅；历史结论保持原样。

## 8. 只读命令与终态

只读命令：`git rev-parse HEAD`、`git log --oneline`、`git status --short`、`git show 62eedde9`/`--stat`、`git rev-parse 62eedde9:<file>`/`git hash-object`、`git diff --stat 62eedde9..HEAD -- android/app/src`、源码读取（BackgroundSourceUpdater/LogoLoader/PlayerActivity/PlayerDrawer/ChannelGrouper/EpgRepository）。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 本报告完成后停止读取目标源码；后续变化须新 diff 证据快照，不自动纳入本次 pass。
