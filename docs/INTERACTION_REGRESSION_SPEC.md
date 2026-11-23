# INTERACTION_REGRESSION_SPEC — 8 项交互回归根因规格（t47）

> 2026-11-23 · researcher（只读深度定位，**不改代码、不跑设备**）
> 基线：`iqtvlite` HEAD `b49a736`（vc10232，`origin/integration/tivimini-baseline` 同步；工作树仅 `?? docs/FOCUSED_FIX_VERIFY.md`）。
> 输入：用户实测 8 个交互 bug、`docs/FOCUSED_FIX_VERIFY.md`（t37，CI#228）、`docs/INFO_CARD_V4_CONFIRM.md`、`docs/V4_SPEC.md`、git 历史（`318fbd9` V4 → `611dfde` 单真源 → `0a1c1d2` 信息卡 → `3ca71b3` FOCUSED → `63a1442` VerifyError）。
> **最终判定以即将产出的 `docs/GOLDEN_SPEC.md` 为准**：本文给源码根因与根治方案，GOLDEN_SPEC 给「正确行为/错误行为/判定方法」的单一权威口径。
> 每项：**根因（精确到文件/函数/行号）→ 根治方案（单变量，禁止 if 补丁）→ 可测用例 → 真机验收**。行号以 HEAD 为准。

---

## 0. 总览：8 项根因一句话

| # | 用户现象 | 一句话根因 | 主文件 |
|---|---|---|---|
| 1 | 确认菜单按 BACK 退 app | 两段镜像打架：`setInfoBannerOpen(false)` 把 state 钉成 HIDDEN、FOCUSED 的 `isInfoCardFocused` 镜像异步滞后；BACK 在 `:535` 的 `isInfoBannerOpen` 守卫可能未命中，落到 `:558 backIsExit`/双按逻辑甚至系统 finish | `PlayerActivity.kt` 511-574 |
| 2 | 双按退出缺中间 toast | `RemoteBindings.DEFAULT` 把 BACK 同时绑 `RETURN_LIVE` 和 `EXIT_APP` 两个 action 到同一长按集合；`resolve` 取列表第一个 → 短按 backIsExit=false 走 toast 分支但**双按判定变量 `lastBackPressTime` 与 toast 分支绑定**，exit 分支 `:562-566` 直接 finish 跳过 toast，两套退出语义并存 | `RemoteBindings.kt:88-89` / `PlayerActivity.kt:558-573` |
| 3 | 换台抽屉闪烁/闪回首页再闪回上一页 | 换台改 `selectedChannelUrl` → `initialScrollIndex` 的 `remember(channelIndexByUrl, selectedChannelUrl, openFocusUrl)` 重算（`:350`）→ 开窗 effect（`:365`）重置 `revealTarget`；同时 `hasInitializedDrawerFocus` 一次性块与 `desiredScrollIndex` 登记在不同 effect 竞争；抽屉在"已打开"状态下被换台重新走一遍"打开初始化"路径 | `PlayerDrawer.kt:347-376, 536-610` |
| 4 | 换台下拉一会滚一会整页翻 | 唯一校正 effect 用 `scrollToItem(target)`（瞬间跳，无动画），焦点跟随与揭示增长各自登记意图；`DRAWER_LOOKAHEAD_ROWS=16` + `drawer_reveal_grow` 使每次补 16 行、可见区一次跳一屏；局部 `scrollToItem` vs `animateScrollBy` 导入未用 | `PlayerDrawer.kt:407-470` / `PerfPolicy.kt` |
| 5 | logo 重心仍错 | 源 `iqtvlite-logo-assets/icon/*` 在 11-23 09:51 更新（README +10/+35 居中），但 `src/tivimini/res` 的 6 个 PNG 是 09:59 复制的**旧一版**（bytes 全不一致：192 源 19704 / 目标 19541；foreground 源 39136 / 目标 37141）；banner 一致 | 源 assets 与 `src/tivimini/res/mipmap-*` |
| 6 | 确认菜单压缩只有 logo 行、最近观看不单独占行 | t32 Bug2 用 `Box` 把徽标叠加主行右上（`:216-309`），但底部「最近观看」标签行 + `RecentStrip`（`:314-339`）+ `Spacer(24)` 仍在 Column 中，视觉上主行 96dp Independent，最近条被压成贴附小片；没有真正的"内联到 logo 行"布局 | `ChannelInfoBanner.kt:210-352` |
| 7 | 确认菜单不自动消失 | `InfoCardPolicy.autoHideMs(FOCUSED) = null`（`:59-62`）是**有意设计**；`LaunchedEffect(infoCardState…)`（`:1104`）对 FOCUSED `return@LaunchedEffect`，永不计时；t32 的 DIRECT 一次 OK 就进 FOCUSED → 永久停留 | `InfoCardPolicy.kt:59-62` / `PlayerActivity.kt:1103-1112` |
| 8 | 设置页面无法呼出 | V4 §3 A 方案 `RemoteBindings.resolveRight`（`:249-260`）在「全部」组（默认）把 RIGHT 从 OPEN_SETTINGS 特例为 OPEN_DRAWER；且 FOCUSED 态 `dispatchKeyEvent:411-423` 确定性消费所有方向键绝不穿透 → RIGHT 到不了 OPEN_SETTINGS；MENU 被本机 launcher 拦截（t37 证据） | `RemoteBindings.kt:249-260` / `PlayerActivity.kt:411-423, 477-509` |

---

## 1. BACK 退 app（FOCUSED 取消菜单却退出）

### 1.1 根因（镜像失配 + 守卫顺序）

- `PlayerActivity.kt:411-423`：FOCUSED 态 BACK 经 `InfoCardPolicy.focusedKeyAction(4)=EXIT_FOCUS`（`InfoCardPolicy.kt:92`）由 `infoCardFocusedAction` 处理（`:1866-1871`）：设 `infoCardState=IDLE`、`infoCardTimerTick++`、退聚焦，**并 `return true` 消费**。这条路径本身正确（t37 真机已验证 FOCUSED→IDLE 不退出）。
- **真实风险路径**：镜像更新时序。`onInfoCardFocusedChanged`（`:256`）由 `ChannelInfoBanner` 的 `onFocusedChanged` 驱动（`:2330`），是**异步的 Compose 回调**；而 `isInfoBannerOpen` 由 `SideEffect { onInfoBannerOpenChanged(infoCardState != HIDDEN) }`（`:1099-1100`）驱动。用户快速按两次 BACK：
  1. BACK#1 若在 FOCUSED 镜像已变 false、state 已 IDLE 的窗口到达 → `:411` 守卫不命中 → `:535 if (isInfoBannerOpen)` 命中（IDLE 仍非 HIDDEN）→ `setInfoBannerOpen(false)` 把 state 钉成 HIDDEN（`:1065`）→ return true；
  2. BACK#2 到达时 `isInfoBannerOpen` SideEffect 镜像**可能还没重组推到 false**（SideEffect 在每次重组提交后才执行），`digits` 空、非回看、非 exit → 落到 `:568-573` 双按判定；若距上一次 `lastBackPressTime < 2000` 则**不 return**，事件落到 `super.dispatchKeyEvent` → Activity 默认 `finish()` 退出 app。
- 另：`:1063-1068` 的 `setInfoBannerOpen(false)` 强制 state=HIDDEN 并清 focus index，但**没有同步把 `isInfoCardFocused` 镜像清掉**（那是 Compose 回调的事）。若 banner 因 HIDDEN 重组销毁焦点，`onFocusedChanged(false)` 回调可能滞后或丢失（F1 历史问题），镜像长时间为 true → 方向键被吞、BACK 行为错乱。

### 1.2 根治方案（单一状态机 + 镜像同步，禁止 if 补丁）

1. **消除双镜像**：把 `isInfoBannerOpen` / `isInfoCardFocused` 两个 Activity 镜像**合并为一个纯函数派生的 `InfoCardUiState`**（可见性 + 聚焦都由 `infoCardState` 一个真源 + `stripFocusIndex` 派生），`dispatchKeyEvent` 只读这一个；`SideEffect` 一次推完整状态，不再两个回调各自异步写两个 var。
2. **集中 BACK 裁决函数**（纯逻辑，可 JVM 单测）：`BackKeyPolicy.decide(visibleOverlay, infoCardState, infoCardFocused, isExitBinding, doublePressWithin2s) -> Action {CONSUME_CLOSE_CARD, CONSUME_TOAST_EXIT, CONSUME_EXIT, PASS_TO_SUPER}`，`dispatchKeyEvent` 的 BACK 分支整体替换为一次调用，按返回 Action 执行。**所有**状态都返回 CONSUME（永不落到 super 默认 finish）；退出只允许通过显式 `finish()`（统一在 CONSUME_EXIT 分支）。
3. `ChannelInfoBanner` 在 `LaunchedEffect(state)` 里当 `state==HIDDEN` 时**同步**推 `onFocusedChanged(false)`（现已有 `:138` 兜底，需确认在 HIDDEN 的每一条路径都触发，包括 `setInfoBannerOpen(false)` 导致的 HIDDEN）——把镜像同步做成 state 的纯派生而非事件回调。

### 1.3 可测用例 / 真机

- `BackKeyPolicyTest`：FOCUSED+BACK → CONSUME_CLOSE_CARD（不退出）；IDLE+卡片可见+BACK → 关卡片；全关+第一次 BACK → CONSUME_TOAST_EXIT；全关+2s 内第二次 → CONSUME_EXIT；任何组合都不返回 PASS_TO_SUPER。
- 真机：确认菜单 FOCUSED 连按 BACK×3：第 1 次退 IDLE（卡片仍在）、第 2 次关卡片（仍在 PlayerActivity）、第 3 次出 toast「再按一次退出app」、第 4 次 2s 内才退出；`logcat` 无 `finish()` 来自系统默认路径。

---

## 2. 双按退出中间 toast

### 2.1 根因

- `RemoteBindings.kt:88-89`：`RemoteAction.RETURN_LIVE` 与 `RemoteAction.EXIT_APP` **都**把 `KEY_BACK` 放进 `longPressKeycodes`；`resolve/resolveWith`（`:101-130`）按 binding 列表顺序取第一个匹配 → BACK 长按永远解析成 `RETURN_LIVE`，`EXIT_APP` 绑定成为死配置。
- `PlayerActivity.kt:558-573`：短按 `backIsExit` 依赖 `resolvePlayerAction(event)`（短按 BACK 不在任何 short 集合 → null → false）→ 走 `:568` 双按 + toast（**这条其实工作**）；但一旦用户从设置页把 BACK 重绑到 `EXIT_APP` short 集合，`:562-566` 就**直接 finish 跳过 toast**——用户抱怨的正是「没有中间 toast 的退出」语义不一致。两套退出路径（exit 直退 / 非 exit 双按）并存，且默认配置下 exit 分支不可达但 toast 分支看起来"偶尔不触发"（因为 `lastBackPressTime` 是 Activity 字段，进程重启/配置变化会丢）。
- 用户要的是**统一语义**：无论绑定，退出播放器一律「第一次 toast、第二次（2s 内）才退」。

### 2.2 根治方案

1. **修数据模型矛盾**：`RemoteBindings.DEFAULT` 删除 `EXIT_APP` 的 `KEY_BACK` 长按绑定（`:89`），让 BACK 长按只表示 `RETURN_LIVE`（回看场景）；`EXIT_APP` 不再预置任何键，仅作为**用户可显式绑定的 action**（绑定后语义见下）。
2. **统一退出裁决**：`BackKeyPolicy`（§1.2）把「退出播放器」定义为**唯一的双按状态机**，与 BACK 的 action 绑定解耦：`EXIT_APP` 绑定表示「按此键触发退出流程」= 进入双按状态机的第一次按键（出 toast），而不是跳过 toast 直接 finish。`backIsExit` 分支（`:562-566`）删除，全部走 `:568-573` 的统一双按 + toast；`finish()` 只在第二次确认时调用。
3. `lastBackPressTime` 改为**纯策略状态**（放入 `BackKeyPolicy` 的状态对象，JVM 可测），不再依赖 Activity 生命周期字段的偶然持久性。

### 2.3 可测用例 / 真机

- `BackKeyPolicyTest`：默认 BACK 长按不再返回 EXIT_APP；显式绑定 EXIT_APP 的键第一次 → toast、第二次 2s 内 → exit；超时重置。
- 真机：默认 BACK 连按两次：第一次 toast「再按一次退出app」、第二次退出；从设置页把某键绑「退出应用」后按该键同样先 toast 再退；回看中长按 BACK 仍是「返回直播」不退出。

---

## 3. 换台时抽屉闪烁 / 闪回首页再闪回上一页

### 3.1 根因（换台重入打开初始化路径）

- `PlayerDrawer.kt:350`：`initialScrollIndex = remember(channelIndexByUrl, selectedChannelUrl, openFocusUrl) { drawerInitialIndex(...) }`。换台改变 `selectedChannelUrl` → 即使 `openFocusUrl=remember(visible){…}`（`:347`）在抽屉保持可见时**冻结不变**，`remember` 的另一个 key `selectedChannelUrl` 变了 → `initialScrollIndex` 重算为新台索引（深行）。
- `:365-376` 开窗 effect 的 key 含 `initialScrollIndex`：重算后**再次执行**，`revealTarget = drawerSafeOpenTarget(initialScrollIndex,…)` 被重置（可能从已补满的 147 回落到较小窗口），`revealedRows` 虽由分帧泵推进但 `revealTarget` 回退会造成可见行收缩/重排 → 用户看到"刷新闪烁"。
- `:536-610` 的首次聚焦块（`hasInitializedDrawerFocus`）与 `LaunchedEffect(selectedChannelUrl, visible)`（`:591+`）、`onRowFocusedScroll`（`:450`）、`desiredScrollIndex` 登记（`:545, 602`）在换台时**多个 effect 同时写滚动意图**，抽屉在"已打开"状态下被当成"刚打开"重新定位，出现「闪回首页（initScrollIndex=0 的初值态）→ 再闪回上一页（校正 effect 把 desiredScrollIndex 落回焦点）」。
- 本质：`selectedChannelUrl` 同时承担「正在播放」与「抽屉初始恢复焦点」两个语义，换台改前者不应重置后者。

### 3.2 根治方案（语义分离 + 换台不重入）

1. **冻结恢复焦点为单一来源**：`initialScrollIndex` 只依赖 `openFocusUrl`（开抽屉瞬间冻结），**移除 `selectedChannelUrl` 作为 key**；`drawerInitialIndex` 的参数只用 `openFocusUrl ?: 播放台的初始快照`，但快照在 `remember(visible)` 时一并冻结成 `openPlayingUrl`。即：
   ```kotlin
   val openAnchor = remember(visible) { if (visible) (initialFocusUrl ?: selectedChannelUrl) else null }
   val initialScrollIndex = remember(openAnchor, channelIndexByUrl) { drawerInitialIndex(openAnchor, null, channelIndexByUrl) }
   ```
   换台时 `openAnchor` 不变 → 开窗 effect key 不变 → 不重入。
2. **开窗 effect 幂等**：`:365-376` 加「只抬高不回落」语义——`revealTarget = maxOf(revealTarget, openTarget)`（与分帧泵"唯一增长入口"一致），即使被其他路径重触发也不会收缩窗口。
3. **换台后焦点跟随与播放台解耦**：`:591+` 的 `LaunchedEffect(selectedChannelUrl, visible)` 只在 `!hasInitializedDrawerFocus`（首次打开）时登记恢复意图；换台导致的 selectedChannelUrl 变化**不写 desiredScrollIndex**（抽屉内浏览焦点由 `focusedChannelUrl` + `onRowFocusedScroll` 管理，与播放台无关）。这样"换台不打扰当前浏览 UI"。
4. 保留 t29 单真源（唯一 `desiredScrollIndex` + 唯一校正 effect），不新增第二条滚动路径。

### 3.3 可测用例 / 真机

- `DrawerStatePolicyTest`（纯函数）：`openAnchor` 冻结后 selectedChannelUrl 变化不改变 initialScrollIndex；`revealTarget=maxOf` 不回落。
- 真机（用户路径）：打开抽屉浏览到深行 → 保持抽屉打开用 OK/频道键换台 → 观察 dump 可见行**不闪回首页**、焦点/滚动保持原位置；logcat 无重复的 `drawer_compose target=` 重入（换台只应有 `row_focus` 事件，不应有新的 `drawer_open`）。

---

## 4. 换台下拉「一会滚一会整页翻」

### 4.1 根因（jump scroll + lookahead 抖动）

- 唯一校正 effect（`:407-430`）用 `channelListState.scrollToItem(target)`——**瞬时跳转无动画**。每次焦点行离开可视区，`onRowFocusedScroll`（`:450-457`）登记 `desiredScrollIndex=idx`，effect 满足 `drawerScrollDecision` 后一次 `scrollToItem` → 视觉上"整页翻"。
- `drawer_reveal_grow`（`:434-446`）：当最后一个可见项接近 `revealTarget` 时一次把 `revealTarget` 抬高 `DRAWER_LOOKAHEAD_ROWS=16` 行；16 行×64dp=1024dp > 一屏（~672dp），导致突然多出一大段可滚区，配合 `onRowFocusedScroll` 的"只在行不可见时才滚"，表现为「先停住、再猛翻一屏」的节奏不均。
- `PerfPolicy.DRAWER_ROWS_PER_FRAME=2` 补齐揭示行（每帧 2 行）比滚动动作慢，用户在揭示未完成时看到停顿。
- 导入了 `androidx.compose.foundation.gestures.animateScrollBy`（`:14`）但校正 effect 用的是 `scrollToItem`——平滑滚动能力已引入未使用。

### 4.2 根治方案（平滑滚动 + 统一节奏）

1. **改瞬时跳为平滑滚动**：唯一校正 effect 的 `SCROLL` 分支（`:422-426`）把 `scrollToItem(target)` 改为基于当前 `first visible index` 计算 delta 后 `channelListState.animateScrollBy(deltaPx)`（用 `:14` 已导入的 API + `.LocalDensity` 换算 64dp 行高）。`desiredScrollIndex` once 清标记保持；`animateScrollBy` 是 suspend，effect 内 `await` 完成后再允许下一次意图（防叠加）。
2. **收窄 lookahead 使其小于一屏**：`PerfPolicy.DRAWER_LOOKAHEAD_ROWS 16 → 8`（8×64=512dp < 672dp 一屏），让揭示增长与滚动节奏匹配；`drawer_reveal_grow` 保持 `maxOf` 增长不回落。
3. **决策函数加"接近边缘提前滚"**：`drawerScrollDecision` 从"目标不可见才 SCROLL"改为"目标距可视边缘 ≤2 行就平滑滚"，避免焦点已经贴边才突跳（单变量在纯函数里，可测）。
4. 性能红线：平滑滚动期间只动列表 layer（`:graphicsLayer()` 缓存），不重录背景/内容层；真机 gfxinfo 无 ≥300ms 巨帧。

### 4.3 可测用例 / 真机

- `PerfPolicyTest`：`drawerScrollDecision` 边缘 2 行内返回 SCROLL；`DRAWER_LOOKAHEAD_ROWS==8`。
- 真机：连续 DOWN×20 观察滚动为每行平滑跟随、无"整页翻"；换台后下拉节奏均匀；gfxinfo p50/巨帧对比基线不劣化。

---

## 5. logo 重心错误（源已更新、目标仍是旧版）

### 5.1 根因

`iqtvlite-logo-assets/README.md` 声明最终版 lockup「shifted +10 right / +35 down from centre」，源文件 2026-11-23 09:51 更新；但 `src/tivimini/res/mipmap-*` 中的 PNG 是 09:59 从**更早一版**复制的，字节全部不一致：

| 文件 | 源 bytes | 目标 bytes |
|---|---|---|
| `ic_launcher-48 → mipmap-mdpi/ic_launcher.png` | 3245 | 3183 |
| `-72 → hdpi` | 5411 | 5358 |
| `-96 → xhdpi` | 8076 | 8014 |
| `-144 → xxhdpi` | 13587 | 13526 |
| `-192 → xxxhdpi` | 19704 | 19541 |
| `ic_launcher_foreground-512 → xxxhdpi/ic_launcher_foreground.png` | 39136 | 37141 |
| `tv-banner → drawable-nodpi/tv_banner.png` | 24421 | 24421（一致✅） |

`bc19a84` 曾替换过一次，但源 assets 在 t41（11-23 09:51）后又改了居中参数，目标未同步；`mipmap-*/ic_launcher_round.png` 仍是更早的圆形图（源目录没有 round 资源）。

### 5.2 根治方案（同步 + 自适应居中）

1. 用当前源 `icon/ic_launcher-{48,72,96,144,192}.png` **逐密度覆盖** `src/tivimini/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`；`ic_launcher_foreground-512.png` 覆盖 `mipmap-xxxhdpi/ic_launcher_foreground.png`；`tv-banner` 已一致无需动。
2. `ic_launcher_round.png`：源无 round 版 → 用 5 密度 `ic_launcher.png` 同名复制为 `ic_launcher_round.png`（Android TV launcher 非圆裁剪时视觉一致），或新建 `mipmap-anydpi-v26/ic_launcher_round.xml` 指向同一 adaptive foreground/background，避免"圆形图标是另一套重心"。
3. 自适应前景：`mipmap-xxxhdpi/ic_launcher_foreground.png` 是 512px 图，自适应图标按 108dp viewport 缩放，源 README 的 +10/+35 偏移是相对 512px 画布的 lockup 位置；覆盖后需在真机 launcher 验证"lockup 视觉居中"。若仍偏，根治是在源 asset 上重导（本任务不改源），目标侧只保证逐字节一致。
4. 加 CI 门禁：`check_brand_assets.py` 比较 assets 与 res 的 sha256，不一致直接 FAIL（防再次漂移）。

### 5.3 可测用例 / 真机

- 门禁：7 个源/目标 hash 全一致；round 与方形同源。
- 真机：冷启 launcher 图标 + 设置页 TV banner + 最近任务卡图标，lockup 视觉居中（+10/+35 生效）、无错位/裁切；截图留证。

---

## 6. 确认菜单压缩到只有 logo 行大小

### 6.1 根因

`ChannelInfoBanner.kt:210-352`：t32 Bug2 已把徽标用 `Box` 叠加到主行右上（`:216-309`，"不额外占行"），主行 `height(96.dp)`（`:221`）+ logo 160×96（`:228`）独立。但底部仍有：
- `:311 Spacer(8dp)` + `:314-338` 最近观看「标签行 + `RecentStrip` + `Spacer(24dp)`」；
- `RecentStrip`（`:409+`）卡片 `height(64.dp)`、logo 40dp + 12sp 文字两行；
- 用户看到的"确认菜单只有 logo 行大小、最近观看不单独占一行"= 卡片被压缩到主行 96dp 附近时，最近观看区只剩贴附在 logo 行下方的一小条（标签行与卡片挤在一起），视觉上不成独立区。

### 6.2 根治方案（结构性重排，不是调 spacer）

1. **把最近观看小卡内联进主行 logo 下方的横向空间**：重排 `Column` 为两层——主行 `Row`（logo + 台名/分组 + 节目列，高 96dp）保持不变；最近观看 `RecentStrip` 移到**主行内部的第三列**（节目列下方或 logo 右侧空白），用 `Column(Modifier.weight(...))` 容纳 4 个小 logo（每个 40dp），与主行共享 96dp 高度，不再单独占一整行。
2. **卡片高度账**：目标 ≈ logo 行 96dp + top/bottom padding（当前 10/16dp）≈ 122dp；最近 4 logo 每个 40dp 高 + 4dp gap ≤ 56dp，可完全放进 96dp 主行的下半区。删除独立的「最近观看」标签行（或缩为角标文字叠加），删除 `Spacer(24dp)`。
3. **保留**：`info_card_*` 全部 tag、U1 单一焦点真源、FOCUSED 按键闭环、`showRecent` 三态守卫、`MAX_RECENT=4`、徽标右上叠加、节目/进度三态。
4. 静态门禁 `check_info_card.py` 加断言：卡片容器高度目标 ≤140dp；最近条不再有独立 `Spacer(24.dp)`；tag 全在。

### 6.3 可测用例 / 真机

- 布局纯函数（可选）`infoCardHeightV4(showRecent) -> 96+padding`，断言 ≤140dp。
- 真机：确认菜单整体高度 ≈ logo 行 + 少量（目标 ~96–130dp）；4 个最近 logo 与主行同处一层、各自可见可聚焦；节目/进度/徽标不挤压；dump 量 `info_card` bounds 高度符合预期。

---

## 7. 确认菜单不自动消失（FOCUSED 永不隐藏）

### 7.1 根因

- `InfoCardPolicy.autoHideMs`（`:59-62`）：`State.FOCUSED -> null`（"聚焦不隐藏"是 v2 的有意设计）。
- `PlayerActivity.kt:1104-1112`：`LaunchedEffect(infoCardState, …)` 对 `null` 直接 `return@LaunchedEffect`，FOCUSED 永不计时。
- t32 的 `InfoOkMode.DIRECT`（`:69-75`，默认）让一次 OK 出卡**直接进 FOCUSED**（`:1831`），于是默认用户一按 OK 卡片就永久停留，除非手动 BACK/OK 切台。

### 7.2 根治方案（ inactivity 超时，区分操作/静止）

1. **改策略**：`InfoCardPolicy.autoHideMs` 增加 inactivity 口径——`FOCUSED` 返回 `FOCUSED_IDLE_HIDE_MS = 7000L`（与 IDLE 同），但**只有"用户静止"才计时**：新增纯函数 `shouldArmAutoHide(state, lastInteractionAt, now, interactionInFlight)`，FOCUSED 且距最后一次交互 ≥7s 且无进行中的焦点操作时才 arm。
2. **交互时间戳单一真源**：在 Activity 维护 `infoCardLastInteractionMs`（由 `onMoveFocus/onSwitchRecent/OK/BACK` 等所有卡片交互入口统一刷新），`infoCardTimerTick` 在每次交互时 ++；`LaunchedEffect(infoCardState, infoCardTimerTick, lastInteractionMs)` 在 FOCUSED 也 `delay(autoHideMs)`，到期且仍 FOCUSED 且无新交互 → `infoCardState = IDLE`（`:1107` 现只处理 IDLE，扩展为先退 IDLE 再下一 tick 退 HIDDEN，或直接退 HIDDEN 并清焦点 Index）。
3. **保留语义**："正在操作"= 最近 7s 内有 move/switch/按键 → 不隐藏；静止 7s → 退 IDLE→HIDDEN；期间 BACK 仍可主动取消（`:535` 守卫与 §1 的 BackKeyPolicy 不变）。
4. 门禁：`check_info_card.py` 断言 FOCUSED 不再返回 null；`InfoCardPolicyTest` 新增 FOCUSED 超时用例。

### 7.3 可测用例 / 真机

- `InfoCardPolicyTest`：`autoHideMs(FOCUSED)==7000`（非 null）；`shouldArmAutoHide(FOCUSED, now-8s, inFlight=false)==true`；`now-2s==false`。
- 真机：一次 OK 进 FOCUSED → 静止 7s 卡片自动消失（HIDDEN）；期间每 3s 按一次 RIGHT 保持不消失；消失后 BACK 不退出播放器；OK 再出卡重新计时。

---

## 8. 设置页面无法呼出

### 8.1 根因（双锁：RIGHT 特例 + FOCUSED 吞键 + MENU 被拦截）

1. `RemoteBindings.resolveRight`（`:249-260`）：默认绑定 + `selectedGroup=="全部"`（**默认组**）→ RIGHT 从 OPEN_SETTINGS 改判 OPEN_DRAWER（V4 §3 用户确认的特例）。默认状态下 RIGHT **永远进频道列表，不进设置**。
2. `PlayerActivity.kt:411-423`：FOCUSED 态所有方向键/OK/BACK 被 `focusedKeyAction` 确定性消费并 `return true`（t35 防穿透修复），即使 RIGHT 在 PLAYER_ONLY 解析为 OPEN_SETTINGS 也到不了 `:477` 分支。
3. `KEYCODE_MENU`（`:501-509`）本是备用设置入口，但 t37 真机证据：「MENU(82) 被本机 launcher 拦截（弹出应用选择器）」——设备上不可用。
4. 结果：用户没有任何可靠路径呼出设置（默认 RIGHT 进列表、FOCUSED 吞 RIGHT、MENU 被系统拦截）。

### 8.2 根治方案（明确多入口 + 消除 RIGHT 死锁）

1. **给"打开设置"一个与 RIGHT 无关的确定入口**：在信息卡 `EntryHint`（`ChannelInfoBanner.kt:397+`，现文案 `← 频道列表 · → 设置`）与设置一级页脚已有提示基础上，新增**专用遥控功能绑定** `OPEN_SETTINGS` 的默认键：`RemoteBindings.DEFAULT` 把 `KEY_MENU` 保留（`:85`）**同时新增一个设备可用键**——彩键 `KEYCODE_BUTTON_1`（或用户设置页可绑任意键）；FOCUSED 态下该键走通用 fallback（t10 `isFixedRoutedKey` 不含彩键）→ `resolvePlayerAction` 命中 OPEN_SETTINGS → 打开设置。这样"FOCUSED 也能呼出设置"。
2. **消 RIGHT 特例死锁**：`resolveRight`（`:249-260`）增加"设置需求优先"判据——若用户在设置里把某键**显式绑定**了 OPEN_SETTINGS（`rightCustomBound=true`，已是现行逻辑），RIGHT 特例不生效；同时把默认特例从"selectedGroup==全部"收窄为"仅当焦点在分组列 `inGroupColumn`"——播放器 PLAYER_ONLY 的 RIGHT 恢复 OPEN_SETTINGS 默认语义（回到 V4 之前），`全部频道按 → 进列表` 由分组列内 → 承担。这样默认 RIGHT 在播放器里能开设置。
   - 若产品坚持"默认 RIGHT 进列表"：则必须走 ① 的专用绑定键兜底，并在 GOLDEN_SPEC 里明确「RIGHT=频道列表、彩键/MENU=设置」。
3. **FOCUSED 态放行 OPEN_SETTINGS**：`dispatchKeyEvent:411-423` 的 FOCUSED 闭环对"已绑定到 OPEN_SETTINGS 的非方向键"（彩键/MENU）**不拦截**，交给 `:501`/通用 fallback 处理；方向键仍闭环防穿透（保留 t35 核心价值）。
4. **单变量原则**：以上是"入口重分配"而非加 if 补丁——集中在 `RemoteBindings.DEFAULT`/`resolveRight` 和 FOCUSED 守卫的纯策略函数 `FocusedKeyPolicy.passthrough(action)` 中实现，可 JVM 单测。

### 8.3 可测用例 / 真机

- `RemoteBindingsTest`：默认 RIGHT 在 PLAYER_ONLY（非分组列）→ OPEN_SETTINGS；在分组列 → OPEN_DRAWER；彩键 → OPEN_SETTINGS；`FocusedKeyPolicy.passthrough(OPEN_SETTINGS)==true`、`passthrough(MOVE_RIGHT)==false`。
- 真机：默认播放器按 RIGHT → 设置抽屉打开；FOCUSED 态按彩键 → 设置打开且不关卡片焦点乱；分组列内按 RIGHT → 进频道列表（V4 功能保留）；MENU 被拦截的设备能用彩键兜底。

---

## 9. 交叉影响与排期

| 修复 | 相互影响 | 建议顺序 |
|---|---|---|
| §1+§2 BACK 统一 BackKeyPolicy | 依赖 §7 的 FOCUSED 超时状态机、§8 的入口语义 | 先 §7/§8 定状态与入口，再 §1/§2 |
| §3+§4 抽屉换台/滚动 | 共用 `desiredScrollIndex` 单真源，同一实现者顺序做 | §3 语义分离 → §4 平滑滚动 |
| §5 logo | 独立资源，CI 门禁同步 | 并行 |
| §6 菜单布局 | 与 §7 超时状态机同文件 `ChannelInfoBanner`/`InfoCardPolicy`，顺序交接 | §7 策略先 → §6 布局 |
| 全部 | **最终判定以 GOLDEN_SPEC + REGRESSION_CHECKLIST 为准**（t48 产出），每条修复都要在 checklist 有对应可勾选项 | — |

> 用户明确「不要表面修复、根源解决」：本文所有方案都是**状态机/数据模型/布局结构层**的根治，未使用"在某处加一个 if 特判"的补丁式修法（§8 的入口重分配通过 DEFAULT 绑定与纯策略函数实现，也不是散落 if）。实施时若发现必须加散落 if，请回退到本文重新定位根因。
