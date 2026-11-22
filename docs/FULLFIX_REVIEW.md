# FULLFIX 复审报告（t8）— 需修订（阻断）

> 复审日期：2026-11-22 · 复审人：researcher（只读，**未改代码、未跑设备**）
> 复审基线：HEAD `c8991ad`（`origin/integration/tivimini-baseline` 同步），CI run **#211** / `45015404890` / vc10211；
> 真机证据：`docs/FULLFIX_DEVICE_VERIFY.md`（t7，TR100-G9，2026-11-22 19:16–20:12）+ `docs/evidence/t7-fullfix/`（7 张截图）。
> 规格依据：`docs/FULLFIX_SPEC.md`（t1）、`docs/REVIEW_HANDOFF.md`、`docs/TIVIMATE_REFERENCE_2026_11_22.md`。
>
> **verdict = needs_revision（阻断）**。用户 5 项 UI + S1 的实现、门禁、CI、真机硬证据均通过、质量良好；
> 但存在 **1 个必须关闭的阻断（遥控自定义键不生效，t10 进行中）** 和 **1 个必须关闭的阻断（U5 两项明确要求未实现）**。
> S6/第二批 S2–S6 的未实现项按本文 §5 裁定为「已知限制 / 需补计划」，不构成本次交付的硬阻断。

---

## 0. 结论速览

| 复审维度 | 结论 | 依据 |
|---|---|---|
| S1 小分组崩溃 | ✅ pass | 代码 + S1 静态门禁 + 真机 §2-A（1/4/24 行、大→小→大、Back、0 Fatal） |
| 用户 ① 选台 UI 富布局 | ✅ pass | `1c7638b` 所有行副标题/无信息/3dp 进度、行高 64dp；真机行距 96px + 截图证据 |
| 用户 ② 不回开头 | ✅ pass | `openFocusUrl` 冻结 + `drawerInitialIndex` + 按组记忆；真机 `row_focus idx=4` 重开不回 0 |
| 用户 ③ 半透明背景 | ✅ pass | `graphicsLayer{alpha}` 在已缓存静态层、paint 不透明；真机面板灰色随底图变化、p95 未劣化 |
| 用户 ④ 信息卡排版 | ✅ pass | `smallMetaLine` 去编码、音频声道/回退徽标、16dp padding；真机 dump「频道 1 · 央视频道」无 MPEG-L2、徽标 FHD·2ch、高度 240px |
| U1 最近观看切换 | ✅ pass（硬证据） | 单一真源 focusIndex；真机 `OK→OK→右→OK` 切到 CCTV-6（第二张卡），last_channel_title 校验一致 |
| 用户 ⑤ 遥控功能→按键 | ❌ **阻断（t10 进行中）** | 模型/UI/迁移通过；真机 F1(131) 绑定后不生效，`dispatchKeyEvent:548` 无通用 fallback |
| U5 抽屉/节目单状态 | ❌ **阻断** | `DrawerChannelItem:1285` 无播放台 ▶/播放中标记；`PlayerActivity:1255/1257` 仍按 `selectedGroup` 过滤 nowProgramByUrl（HANDOFF U5 明确要求） |
| S2/S3/S5/U2/U4 等第二批 | ⚠️ 已知限制（记录） | `PlaybackRecoveryPolicy.kt` 未创建、S3/S4 代码在位但无并发真机场景；t7 如实标注未验证 |
| S6 配对 token | ⚠️ 已知限制（记录） | `SourceConfigLanServer.kt:89-96` 直接接 POST，无配对校验 |
| 门禁非空转 | ✅ pass | 5 个门禁本地重跑均 OK；断言含反向禁用模式，非空转 |
| CI 出包 | ✅ pass | GitHub API 核实 #211 success / head c8991adb / 双 artifact / 稳定签名 |
| 真机证据 | ✅ pass（主路径） | 报告含 sha256 校验、逐项证据、收尾态；Fatal 0、force-stop 0 |

---

## 1. 复审方法（只读、可复现）

1. `git status/log/branch`：确认 HEAD 与 origin 一致、改动集中在 3 个提交（`1c7638b`/`723af6a`/`737d5ff`/`f762bf4` + 2 个编译修复）。
2. `git show <commit>` 逐文件核对：PlayerDrawer/ChannelInfoBanner/InfoCardPolicy/PerfPolicy/PlayerActivity/SidebarController/RemoteBindings/SettingsDrawer/各门禁脚本/各 JVM 测试。
3. 本地重跑静态门禁（bundled Python 3.12.14）：`check_perf_policy / check_info_card / check_settings_ia / check_settings_scroll / check_logo_webp` 全部 `OK`、exit 0。
4. GitHub API 核实 CI run `45015404890`：conclusion `success`、head_sha `c8991adb`、jobs/artifacts 与报告一致；工作流 `apk.yml` 确认执行 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` + 7 个 python 门禁 + DEX 单测 + pair gate。
5. 真机报告与源码逐条交叉核对（截图因本模型不支持图像输入未能直接查看，依据报告的文字证据 + dump 数值 + 源码）。
6. 复审 t10 进行中的工作树 diff（`PlayerActivity.kt` 已出现 isFixedRoutedKey + 通用 fallback，**尚未提交、未进 CI、未真机验证**）。

---

## 2. 逐项复审

### 2.1 S1 小分组（pass）
- `ef8f826` 的 `drawerFirstRows / drawerSafeOpenTarget / drawerSafeFirstVisibleIndex / drawerFocusIndexInRange` 4 个纯函数与 `check_perf_policy.py` 的反查断言（禁止旧 `coerceIn(FIRST, channels.size)` / `coerceIn(0, count-1)`）均在；本轮 UI 改动未回退（`1c7638b` 的 `initialScrollIndex` 仍走 `drawerInitialIndex`，打开 effect 仍走 `drawerSafeOpenTarget`）。
- t7 真机：1/4/24 行与大小组切换均可打开、落焦、Back 退出，`FATAL/IllegalArgumentException` 0。与 S1_VERIFY 静态结论一致。

### 2.2 用户① 选台 UI（pass）
- `1c7638b`：`DRAWER_ROW_HEIGHT_DP 56→64`（`PerfPolicy.kt:106`）、`rowSubtitleVisible()` 让**所有行**组合副标题、无节目显示「无信息」、`ThinProgressBar` 3dp 复用；`key("sub_$index")` + `clipToBounds` + `onTextLayout` 窄探测 + `TextSanitize` + 3 层 `graphicsLayer` + 分帧泵 + 滚动跟随全部保留。`check_perf_policy.py` 新增「旧 focused-only gate 回归检测」「rich-row token 必须存在」「行数仍 3/2/28」等断言，本地 OK。
- 真机：行距 96px = 64dp，副标题日志 `row_focus … subtitle=true`，无残影、无巨帧报告。对齐 FULLFIX §1 与「保留性能修复」红线。

### 2.3 用户② 定位不回开头（pass）
- `openFocusUrl = remember(visible) { if (visible) initialFocusUrl else null }`（`PlayerDrawer.kt:341` 附近）冻结开抽屉瞬间的恢复位置，避免浏览焦点回写导致重算窗口——这是比我规格更稳的实现（我原方案直接用 live initialFocusUrl 会触发窗口 effect 反复重置，developer 识别并规避，正确）。
- `SidebarController.lastFocusedByGroup` + `focusChannel` 回写 + Activity 传 `initialFocusUrl`（`PlayerActivity.kt:1623`）；切组 effect 不再 `scrollToItem(0)` 而是按记忆索引；S1 安全钳制保留。
- 真机：重开后 `row_focus idx=4 via=key down`，焦点在 idx=4 而非播放台 idx=0；按组记忆 JVM 用例 2 个 + `SidebarControllerTest` 全绿（CI #211）。

### 2.4 用户③ 半透明背景（pass）
- `PerfPolicy.DRAWER_OPAQUE_CONTAINER=false` + `DRAWER_CONTAINER_ALPHA=0.92f` + `drawerContainerAlpha()`；`PlayerDrawer.kt:950` 静态背景层 `.graphicsLayer { alpha = … }`，`container` paint 始终不透明，三层 layer 数不减。与我 §3 推荐方案一致，门禁断言 `surface.copy(alpha=0.92f)` 不得回归。
- 真机：面板像素随底图变化、无全色遮挡、无灰屏；gfxinfo 未报告性能劣化。**注意**：t7 未给出 §3.4 要求的开抽屉 A/B framestats 数值对比，属于证据轻微不足，但因静态结构正确且无巨帧报告，判 pass；建议补一份数值到交付文档。

### 2.5 用户④ 信息卡排版（pass）
- `smallMetaLine(number, group)` 去掉 audioCodec（`InfoCardPolicy.kt:265`）；`takePositive` 回退 + `AudioTrackUi.selectedChannelCount` 取**音频** Format 的声道数；banner 容器 padding 16dp、去掉双重 40dp、最近条 `height(64)+clipToBounds`；`check_info_card.py` 新增 12 项反向断言本地 OK。
- 真机：台标下「频道 1 · 央视频道」无 MPEG-L2；徽标 `FHD · 2ch`（码率/帧率因源 Format 无值按「未知即隐藏」策略隐藏，**符合规格**，非缺陷）；技术行完整；高度 240px < 300dp。
- 说明：用户原话要求码率/声道/分辨率/帧率「补全」，当前源确实无法提供码率/帧率，实现按规格隐藏并在日志可查，属可接受的数据限制，不构成回归或欺骗。

### 2.6 U1 最近观看（pass，硬证据最充分的一项）
- `InfoCardPolicy.focusTargetIndex/resolveRecentTarget` 单一真源；banner 根部 `onPreviewKeyEvent` 统一处理 LEFT/RIGHT/OK；去掉卡片级 `onPreviewKeyEvent` 与 `stripFocusIndex<0` 守卫；`onSwitchRecent` 用 `resolveRecentTarget(cardRecentItems, infoCardFocusIndex)`。`c8991ad` 修复了 OOB 不 clamp 的测试问题。门禁有 6 项反向断言防回归。
- 真机：精确序列 `OK→OK→右→OK` → `switch idx=1 group=6 CCTV-6 电影`，`last_channel_title` CCTV-8→CCTV-6；连续 RIGHT×8 clamp 到 index=5 不环绕；这与我之前漏测的「高亮=焦点=OK 目标」完全对齐。

### 2.7 用户⑤ 遥控全映射（❌ 阻断）
- **已通过部分**：`RemoteBindings.kt`（264 行）含 `RemoteAction/UiContext/KeyBinding`、`resolve/resolveWith/serialize/parse/migrate/bind/bindingSummary`；`RemoteBindingsTest` 10 例（默认等价、长短按/上下文、5 种 legacy 迁移、序列化往返、冲突解绑）；`Prefs` 新键 `remote_action_bindings_v1` 与迁移；`SettingsDrawer` 功能行 + 短/长按键捕获 UI、恢复默认；`check_settings_ia.py` 新增 router/捕获/tag 断言；真机设置 UI、Prefs 写入、捕获 F1 后 `s=20|93|131|167` 与 UI 摘要 `短[↓/93/键131/167]` 全部正确。
- **阻断缺陷（与 t7 结论一致，源码已核实）**：`PlayerActivity.dispatchKeyEvent` 只在固定 keycode 分支（DPAD/ENTER/BACK/MENU/166/167/92/93/LAST_CHANNEL）内调用 `resolvePlayerAction`，末尾 `:548 return super.dispatchKeyEvent(event)` 无通用 fallback。因此「设置页可绑定任意键」与「实际能触发」不一致：真机 F1(131) 绑定后按下不切台。这直接违背 TiviMate 参考 §1「选一个功能→按任意键完成绑定」与用户问题 5 的核心诉求。
- t10 正在修复：工作树 `PlayerActivity.kt` 已出现 `isFixedRoutedKey` + 通用 fallback diff（对固定集合外的键走 router，含 overlay/focus/digits 守卫），设计方向正确；但**尚未提交、未进 CI、未真机复验**，不计入本次 pass。
- **关闭条件（t10 完成后复审）**：① fallback 不造成固定键双触发（`isFixedRoutedKey` 覆盖检查）；② 覆盖层/信息卡聚焦/数字 OSD 守卫不被绕过；③ CI 新 run 全绿；④ 真机复验 F1 绑定 → 切台，且既有默认行为（换台/OK/BACK/数字/166-167/恢复默认）无回归。

### 2.8 U5 抽屉与节目单状态（❌ 阻断 — 需明确裁定）

**裁定：这两项属于 HANDOFF U5 的明确验收条件，属于本次范围，必须修，不能记为已知限制。**

HANDOFF U5 原文（`:26`）：「当前播放、浏览焦点、可回看状态难以区分。用独立标记区分状态。节目单空态区分未配置、加载失败和无节目。**信息卡直接查询实际播放台**。验收：A 台播放、焦点在 B 台时，两种状态都可辨。切换浏览分组不影响 A 台的信息卡节目。」

- **缺口 1 — 播放台标记未实现**：`PlayerDrawer.kt:1285` 仍是 `val label = remember(index, channel.title) { "$index. ${channel.title}" }`，`selected` 参数在 `DrawerChannelItem` 函数体内未使用（全仓 grep 无 `▶`/`播放中`）。t7 也确认「播放台标记未实现」。这直接导致「A 播放 + 焦点在 B 时两种状态不可辨」。规格 §6.2 用 `▶` 前缀 + 聚焦行 `· 播放中`，成本极低（纯文本 remember），无理由不修。
- **缺口 2 — 信息卡被浏览分组污染**：`PlayerActivity.kt:1255` 的 `LaunchedEffect(epgData, nowMillis, channelGroups, **selectedGroup**)`，`:1257-1261` 按 `selectedGroup` 过滤 groups，非「全部」时只构建该组 URL→节目映射；抽屉切组后正在播放的 A 台不在当前组 → `nowProgramByUrl[A.url]` 消失 → 信息卡节目/进度/起止变空（`:2092/2098/2112` 消费点）。这是 HANDOFF U5 验收「切换浏览分组不影响 A 台的信息卡节目」的反例，且用户 5 项 UI 中「信息卡排版」与「选台 UI」都依赖该信息正确性。规格 §6.2-5 明确要求改为全组遍历。必须修。
- 节目单空态三分类（`epgSourceConfigured/epgLoadFailed`）本包未接入（t7 已标注），HANDOFF U5 也明确要求「节目单空态区分未配置、加载失败和无节目」，同属必须修；成本是新增 2 个参数 + 3 分支文案。
- **关闭条件**：① `DrawerChannelItem` 用 `selected` 渲染 `▶` + 聚焦行 `· 播放中`；② `nowProgramByUrl` 改为遍历全部 `channelGroups`，key 去 `selectedGroup`；③ 节目单空态三分类参数接入；④ 补 JVM/静态断言；⑤ 真机按 §6.4 四步复验（A 播放/B 聚焦可辨、切组后信息卡节目不变、回看标记、三种空态）。

### 2.9 S6 扫码推源（裁定：本次记为已知限制，不阻断）
- HANDOFF S6 是第二批稳定性项，t1 规格已把它放在 §13.4（第二批），且 team goal 的交付重点是「5 项 UI + S1 + CI + 真机」。当前 `/api/push` 无 token（`SourceConfigLanServer.kt:89-96` 直接 `onPush(live, epg)`）、`MainActivity.kt:227` 早于实际提交就 Toast「已从手机推送更新」，真机有效推送通过但无效会话无法构造。
- **裁定**：属于 HANDOFF 范围但**不在本次用户 5 项 UI 的交付门槛**；记为已知限制并列入后续修复 backlog（按 FULLFIX §13.4 实施），**不阻断本轮交付**。若 captain 要求「HANDOFF 全部修复都要做好」的字面完成，则 S6 + 第二批 S2–S5/U2–U4 应另开任务排期，不能与本次 UI 交付捆绑验收。

### 2.10 S2/S3/S4/S5/U2/U3/U4（裁定：已知限制，不阻断本次 UI 交付）
- `PlaybackRecoveryPolicy.kt` 未创建（磁盘确认不存在），HANDOFF S2「统一重试预算/退避/代际校验」与 U4「共用播放状态」未实现；S3「手动代际 vs 已提交快照」拆分未做；S4 三态结果定义（`EpgOutcome`）在位但调度器仍按 `data != null` 记成功；S5 catchup identity/进度恢复未实现；U2 测速抢焦点、U3 回看提示未修。
- t7 真机对强时序/并发/回看场景如实标注「未验证」（源稳定、无回看、单线路），没有编造证据——这点值得肯定。
- **裁定**：与 S6 同理，属第二批，记为已知限制 + 后续任务，**不阻断本轮 5 项 UI 的 pass**。但交付文档必须明确「HANDOFF 全部条目尚未全部完成」，避免对外声称全部修复。

---

## 3. 门禁 / 测试非空转审计

| 检查 | 结果 | 非空转证据 |
|---|---|---|
| `check_perf_policy.py` | OK | 反向断言：旧 focused-only subtitle gate 回归会红；`DRAWER_ROW_HEIGHT_DP=64`、`graphicsLayer` 3 层、`surface.copy(alpha)` 禁用、S1 helper 必需、13 条性能不变量 |
| `check_info_card.py` | OK | 反向断言：`stripFocusIndex<0` 回归会红、`onSwitchRecent(index)` 回归会红、`vf?.channelCount` 禁用、`smallMetaLine` 带 audioCodec 禁用 |
| `check_settings_ia.py` | OK | router 调用/捕获守卫/新 tag 必须存在；旧 `RemoteHabits.onUpDown/onOk` 驱动 dispatch 会红 |
| `check_settings_scroll.py` / `check_logo_webp.py` | OK | 既有红线断言保留 |
| JVM 单测 | CI #211 绿 | 新增 `RemoteBindingsTest` 10 例、`SidebarControllerTest` 2 例、`InfoCardBadgeTest`/`PerfPolicyTest`/`InfoCardRecentTest` 扩充；报告称 365/365。注意：JVM 用例均为纯函数/状态层，**无法覆盖 dispatch 按键派发与 Compose 焦点真机语义**，这正是 U1/遥控缺陷此前漏检、必须靠 t7 真机补的原因 |
| DEX 契约 / pair gate | CI 通过 | `verify_apk.py` / `verify_instrumentation_pair.py` 在 apk.yml 中执行且 job success |

**结论**：门禁与测试非空转，但「纯逻辑覆盖 ≠ 交互覆盖」；所有 UI/焦点/按键类问题必须以真机证据为准（本次 U1 与遥控恰好一正一反印证）。

---

## 4. CI 与真机证据审计

- **CI**：GitHub API 核实 [run #211](https://github.com/zqs1qiwan/iqtvlite/actions/runs/45015404890) `success`、head `c8991ad`、双 artifact（`iqtvlite-test-apk` 10,497,670 B 摘要 `sha256:eaddd0…`；reports 3,477,481 B），与设备报告 sha256/versionCode 一致。推送状态：本地 HEAD == origin（同步）。✅
- **真机**：t7 报告完整覆盖安装态（旧包签名冲突按协议备份/卸载一次/重装、sha256 校验、force-stop 0）、S1、5 项 UI、U1 精确序列、回归（数字 OSD/上一台/最近观看/台标/自动更新/不崩不灰/无 Fatal）、收尾态（实验键 0、log tag 复位、磁盘健康）。证据目录 7 张截图与报告对应。**主路径证据齐全**；遗漏：§3.4 的 gfxinfo 数值对比、U5 三分类/播放标记、S2/S6 等（已在上文裁定）。
- **图像核实限制**：本模型不支持图像输入，未能直接查看 7 张 PNG；对 UI 观感的判断基于 t7 的文字描述、dump 数值与源码，特此声明。

---

## 5. 裁定汇总（给 captain 的明确清单）

| 项 | 是否本次范围 | 裁定 |
|---|---|---|
| 用户 ①②③④ + S1 + U1 | 是 | ✅ pass，无需再动 |
| 用户⑤ 自定义绑定键不生效（F1=131） | 是 | ❌ **必须修**；t10 进行中，按 §2.7 关闭条件复审；未关闭前不得发布 |
| U5 播放台 ▶/播放中标记 | 是（HANDOFF U5 明文） | ❌ **必须修**，不可记为已知限制 |
| U5 nowProgramByUrl 按 selectedGroup 过滤 | 是（HANDOFF U5 明文 + 用户信息卡/选台） | ❌ **必须修**，不可记为已知限制 |
| U5 节目单空态三分类 | 是（HANDOFF U5 明文） | ❌ **必须修**（可与上两项同一改动） |
| 半透明 gfxinfo A/B 数值 | 是（我规格 §3.4） | ⚠️ 建议补证据，不阻断 |
| S6 配对 token / 真反馈 | HANDOFF 第二批 | ⚠️ 记录为已知限制，另立任务；不阻断本次 UI 交付 |
| S2/S3/S4/S5/U2/U3/U4 | HANDOFF 第二批 | ⚠️ 记录为已知限制，另立任务；不阻断本次 UI 交付 |
| 对外表述 | — | 交付说明必须写明「全量 HANDOFF 尚未全部完成」，不得声称全部修复 |

---

## 6. 复审判定

**verdict = needs_revision（阻断，不通过）**

阻断清单（按关闭顺序）：
1. **B1 遥控自定义绑定键不生效**（t10 处理中）：需提交 + CI 新 run + 真机 F1 复验 + 默认行为回归，按 §2.7 四项关闭条件。
2. **B2 U5 三项未实现**：播放台 ▶/播放中标记、`nowProgramByUrl` 全组遍历、节目单空态三分类；按 §2.8 四项关闭条件，需真机复验。

非阻断但须随交付记录：半透明 A/B 数值证据待补；S2–S6/U2–U4/第三批为已知限制并另立任务。

> 关闭 B1/B2 后请再次发起复审（新任务或复用本任务的新 attempt）；本结论不覆盖 t10 未提交的代码，也不把「进行中」当成已修复。

---
