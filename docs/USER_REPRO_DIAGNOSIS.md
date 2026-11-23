# 用户实测 4 个 bug 真机复现诊断报告（vc10243 / CI#243）

- 时间：2026-11-23 19:16–19:39（Asia/Hong_Kong）
- 执行：acceptance（t68 attempt 1）—— **重置验证方法，以用户真实操作路径为准，逐条附原始证据**
- 设备：TR100-G9 / `192.168.2.245:5555` / Android 9 (API28)
- 包：vc10243 / `2.2.0-tivimini-dec2e7a5-validation`
- 不改代码。截图：`docs/evidence/user-review/t68-*.png`；污染 prefs 备份：`_rollback_backup/iptv_prefs_POLLUTED.xml` + `validation-data-polluted-backup.tar`

---

## 0. 环境清理（关键前提）

**发现验证污染根因**：设备 prefs 残留
```
<string name="remote_updown">CHANNEL_STEP</string>
<string name="remote_ok">OPEN_DRAWER</string>
```
→ OK 被映射成"打开抽屉"而非信息卡！这解释了此前 t67 报告"全过"但用户实测不过的矛盾——**我此前多轮 OK→信息卡测试实际打在抽屉/设置上，验证方法确实有问题，用户的质疑成立。**

清理步骤（串行、每步查健康）：
1. 备份完整 data（3,409,920B）+ 导出 `iptv_prefs_POLLUTED.xml` 留证
2. force-stop → 从 prefs XML 删除 `remote_ok`/`remote_updown` 两行 → 回灌（`remote_action_bindings_v1` 保留，OK=23→SHOW_INFO 默认）
3. 重启验证：prefs 无 remote_ok 残留；OK → `ok-open DIRECT state=FOCUSED`（真信息卡）✓
- 全程 boot=1、/data 2.5G、DeadObject/ext4/I-O=0、无崩溃

**本报告以下所有测试均在清理后的干净状态执行。**

---

## 1. bug① 确认菜单 BACK 无法取消 / 直接退出

**用户现象**：确认菜单（信息卡）无法返回取消、BACK 直接退出。

**复现操作与证据（干净状态）**：
```
OK → 11-23 19:31:03.767 ok-open DIRECT state=FOCUSED
dump: card=True texts=爱|143. 爱生活|其他频道|...|← 频道列表|→ 设置
BACK#1 → inPlayer=True, card=False(NO_TEXT), toastWin=0
BACK#2..#5 → 均 inPlayer=True, toastWin=0（连续 BACK 不退出）
裸播放器双按 input keyevent 4 4 → launcher, app_tasks=0, Toast#0
```
- **用户现象"BACK 直接退出"未复现**：干净状态下 FOCUSED→BACK 关卡（不退出），连续 BACK 不误退；只有裸播放器独立双按才退出。
- 但我必须如实指出一个**真实存在的可用性问题**：FOCUSED 态一次 BACK 直接把卡片关到 HIDDEN（`NO_TEXT`），没有中间的"退聚焦但卡还在"可视反馈（代码 `BackKeyPolicy:53 INFO_CARD_UNFOCUS` 之后 state 变化用户不易察觉）。如果用户的"无法取消"指"BACK 后卡片立即消失/没有取消感"，这个可以复现。
- 之前 19:19:34 曾抓到 `Toast#0`（退出 toast）出现在"OK 出卡后 BACK"流程中——但那是**污染 prefs + 前序双按残留计时器**导致，清理后不再出现。

**根因（源码）**：`BackKeyPolicy.kt:53-58`：FOCUSED→INFO_CARD_UNFOCUS、IDLE→INFO_CARD_CLOSE，均先于 exit 分支；逻辑正确。用户感知问题在 UI 反馈层（退聚焦无可见过渡）。

**判定**：核心"BACK 直接退出 app"**未复现**；"退卡无可见取消反馈"部分复现。

---

## 2. bug② 最近观看选第 2 个跳错频道（索引偏移疑点）

**用户现象**：选最近观看第 2 个却跳到错误频道。

**复现操作与原始证据（干净状态，单 shell 命令避免卡片 7s 自动隐藏导致误操作）**：
```
OK → ok-open DIRECT state=FOCUSED
dump 4 卡：
  最近观看第1个 16. CCTV-16 奥林匹克
  最近观看第2个 15. CCTV-15 音乐
  最近观看第3个 26. 湖北卫视
  最近观看第4个 25. 安徽卫视
（当前播放 143. 爱生活，已排除）
RIGHT → IQTV-INFOCARD: move index=1
OK   → IQTV-INFOCARD: switch idx=1 group=15 CCTV-15 音乐
last_channel_title: 爱生活 → CCTV-15 音乐
```
- **索引偏移 bug 未复现**：第 2 张卡 = CCTV-15，`switch idx=1` 正好跳到 CCTV-15，完全一致。`resolveRecentTarget` 的 focusIndex 与 `cardRecentItems`（已排除当前台）索引对齐正确。

**关键方法教训**：前几次"RIGHT/OK 无日志"是因为我在 dump 与按键之间用了 PowerShell 处理（>7s），卡片 IDLE 自动隐藏后 RIGHT 打到了播放器设置页——**不是产品 bug，是我验证脚本太慢**。改用单 shell `input keyevent 23; sleep 0.4; 22; sleep 0.4; 23` 后一次成功。

**根因（源码核对）**：`InfoCardPolicy.cardRecentItems` 先 filter 当前台再取前 N；`resolveRecentTarget(items, focusIndex)` 用同一列表索引取值（PlayerActivity.kt:2099 附近），无错位。

**判定**：用户现象**未复现**（有完整日志证据）；给出方法学说明供 captain 回复用户。

---

## 3. bug③ 最近观看要更大 + 多一个频道 + 底部提示上收紧

**用户诉求**：卡 56dp/4 个 → 更大 + 5 个；底部 ←频道列表/→设置 上收紧、减少空白。

**现状代码定位（精确改动点）**：
- `InfoCardPolicy.kt:13` `const val MAX_RECENT = 4` → 改 5
- `ChannelInfoBanner.kt:391` 最近条行高 `.height(56.dp)`（t59 从 40 提到 56）→ 继续加大（如 64dp）
- `ChannelInfoBanner.kt:401` 单卡 `.size(56.dp)` → 加大（如 64dp）
- `ChannelInfoBanner.kt:417-418` logo `48.dp` → 加大（如 56dp）
- `ChannelInfoBanner.kt:213` 主内容行 `.padding(start=40,end=40,top=6,bottom=6)`；底部提示行在 `:353-369`（ENTRY_HINT_LEFT/RIGHT），需压缩其与小卡区之间的 spacer/空白使提示上移
- 约束：卡片总高仍须 ≤140dp（当前 4 卡约 110dp，增 1 卡+加大后可能超 140dp，需同步压缩顶部 logo 区或 padding）
- `InfoCardPolicy.kt:401-404` ENTRY_HINT_LEFT/RIGHT 文案已分两侧（t59 已做）

**判定**：这是**需求/UI 调整项（非 bug）**，改动点已精确定位，交开发实现，不在本任务改代码。

---

## 4. bug④ 下滚与上滚不一致 + 长按连续滚卡住（**复现，严重**）

**用户现象**：上下滚不一致；长按 DOWN/UP 不能一路顺滑滚到两端。

**复现操作与原始证据（干净状态）**：
```
抽屉打开，可见行 4-13
长按式 DOWN×40（30ms 间隔）：
  focus: row_focus idx=53；可见行仍 4-13（列表未跟随！）
  3s 后仍 4-13
慢速 DOWN×10（200ms 间隔）：
  focus idx=63；可见行仍 4-13（仍不跟随）
scroll_correct 日志（连续 10 条）：
  19:36:08 target=52 focus=54 revealed=62 first=2 last=12 smooth=8
  19:36:09 target=53 focus=55 revealed=63 first=2 last=12 smooth=8
  ...
  19:36:24 target=61 focus=63 revealed=71 first=2 last=12 smooth=8
  → first 始终 2、last 始终 12，focus 从 54 跑到 63，每次都发 smooth=8 但列表一步没动
gfxinfo: 50th=61ms/90th=250ms/95th=500ms/99th=700ms
```
- **用户现象复现**：长按/连续 DOWN 时焦点一路跑到 idx63，可见行钉在 4-13 不动 → 焦点飞出屏幕、列表不跟随。上滚同理（同机制）。

**根因（源码，非 if 补丁层面）**：
- `PlayerDrawer.kt:418` `LaunchedEffect(visible, desiredScrollIndex, revealedRows, channels.size)` —— effect 的 key 含 `desiredScrollIndex` 与 `revealedRows`。
- `PlayerDrawer.kt:446` `channelListState.animateScrollBy(...)` 是 **suspend 动画**，但 :435 紧接着 `desiredScrollIndex=-1`；每次新焦点事件 `onRowFocusedScroll`(:473-478) 又写 `desiredScrollIndex=idx`，分帧揭示又改 `revealedRows` → **effect 被反复取消重启，in-flight 的 animateScrollBy 每次被 cancel**，动画还没走完就被打断 → 净位移≈0，`first` 钉住。
- 同时使用 `animateScrollBy` 把"滚动状态"与"焦点意图"耦合在一个会被高频重入的 effect 里；连续按键（repeat）时每次取消前一动画，越按越落后，永远到不了底/顶。

**根治方案（架构层，不是加 if）**：
1. 把"滚动执行"与"意图更新"解耦：滚动用一个**独立、不会被焦点事件取消的常驻 effect**（key 只含 visible），意图用 `MutableStateFlow<Int>` 收集，每次新意图取消旧动画但**从当前实际 `firstVisibleItemIndex` 重新计算 delta**（不是从旧 target），保证单调推进。
2. 或连续滚动直接对目标行用一次 `scrollToItem(target)`（瞬时定位到目标窗口），只对"单点焦点微调"用 `animateScrollBy`；长按 repeat 场景走瞬时分支，天然顺滑到底/到顶。
3. `animateScrollBy` 放在 `launch {}` 内并持有 job，repeat 时 `job?.cancel()` 后基于最新 `first`/`target` 重算，避免累积取消导致零位移。

---

## 5. bug⑤ 按左定位当前台失效（**复现，严重**）

**用户现象**：播放中按 LEFT 打开抽屉，列表应定位当前台，实际失效。

**复现操作与原始证据（干净状态）**：
```
播放 CCTV-16 奥林匹克（约 idx16）
LEFT → drawer_open ms=281 total=147 ... group=全部
      drawer_scroll_correct target=61 focus=63 INSTANT open-position
2s 后 dump：first=54 last=63（当前台 idx16 不在可见区）
截图像素：rows 54-63 无蓝色播放台名
```
- **用户现象复现**：INSTANT 确实触发，但定位目标是 `focus=63`（**上次浏览焦点残留**），不是当前播放台 idx16 → 打开后可见 54-63，播放台完全不在视野。

**根因（源码）**：
- `PlayerDrawer.kt:561-574` 首次定位块：`openAnchor`（来自 SidebarController 的 openFocusUrl / 上次浏览焦点）优先于播放台：
  ```
  val targetUrl = openAnchor?.takeIf { channelIndexByUrl.containsKey(it) } ?: channels.firstOrNull()?.url
  → focusedChannelUrl = targetUrl；desiredScrollIndex=targetIndex
  ```
  `openAnchor` 是会话内按组记忆的 browse focus（idx63），**换台不更新它**（t65 设计：换台不重开抽屉不置位；但关→开时它仍是旧 browse focus），于是每次开都定位到旧焦点而非当前播放台。
- `SidebarController.focusChannel` 由抽屉内浏览回写；Activity 开抽屉时传入 `initialFocusUrl = lastFocusedUrlFor(selectedGroup)`，播放台变化未覆盖该记忆。

**根治方案（架构层）**：
1. 打开抽屉的定位锚点**明确以"当前播放台"为第一优先**（`selectedChannelUrl`/`currentLive`），browse 记忆只用于"切组后该组内的恢复"或"抽屉已打开时的焦点跟随"，不用于冷开/每次打开定位。
2. 或换台成功时同步更新 `SidebarController.lastFocusedByGroup[group] = 新播放台 url`（让记忆与播放台一致），使 openAnchor 天然指向当前台——但要避免与"浏览焦点记忆"语义冲突。
3. 区别两种意图：`openPositioning`（定位播放台，INSTANT）与 `browseRestore`（恢复浏览位置，smooth），分别持有独立锚点，不再复用一个 openAnchor。

---

## 6. 总结与给 captain 的回复要点

| 用户条目 | 是否复现 | 性质 |
|---|---|---|
| ①确认菜单 BACK 直接退出 | 未复现（干净状态）；"退卡无可见取消反馈"部分存在 | 需向用户说明先清理污染 prefs；UI 反馈可优化 |
| ②最近观看跳错台 | **未复现**（有完整日志：第2卡=CCTV-15→switch idx=1→CCTV-15） | 验证方法学问题（脚本太慢致卡自动隐藏）；产品逻辑正确 |
| ③最近观看更大+5个+提示收紧 | 是需求不是 bug | 改动点已定位（MAX_RECENT=4→5、56→64/48→56dp、提示行 padding） |
| ④长按连续滚卡/上下不一致 | **复现（严重）** | animateScrollBy 在高频重入 effect 中被反复 cancel，列表零位移；需架构层解耦 |
| ⑤按左定位当前台失效 | **复现（严重）** | openAnchor 用了 browse 焦点(idx63)而非当前播放台(idx16)；需锚点语义拆分 |

**最重要的元结论**：此前验收结论不可靠，根因是设备 prefs `remote_ok=OPEN_DRAWER` 残留 + 验证脚本在卡片 7s 自动隐藏窗口外操作。本次已清理并在干净状态重测，③④⑤中 ④⑤真实复现、②未复现、①部分。污染 prefs 已备份留证，未改代码。

**设备收尾**：前台 PlayerActivity；实验键 0；buffer 稳 500 默认；Fatal/VerifyError=0；debug tag 复位；/data 2.56G；t59/t62 回滚包保留。
