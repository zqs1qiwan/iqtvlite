# INFO_CARD_V2 接线清单（wiring map）

> **v3 更新（t23）**：信息卡的**布局/台标尺寸/节目位置/徽标/技术行**以 [`INFO_CARD_V3.md`](INFO_CARD_V3.md) 为准；本文 §3（Activity 守卫）、§4（三态/计时）、§5.1（最近观看模型）、§6（门禁基础）、§7（易错点）仍然有效，§5.2/§5.3 的视觉布局部分已被 v3 覆盖。

> 版本 1.0 · 2026-11-21 · designer（captain 指派的可选只读任务）· 代码基线：`HEAD=dfb69f1`（t11 台标热修已合；`PlayerActivity.kt` / `ChannelInfoBanner.kt` / `RemoteHabits.kt` / `RecentChannelsDialog.kt` 自 `fcc7603` 起未改，行号有效）
> 用途：把 [`INFO_CARD_V2.md`](INFO_CARD_V2.md) 的规格翻译成 **t4 可直接照做的逐点接线表**（函数 / 行号 / 现状 / 改法 / 风险）。
> 只读审计：本次未改任何代码、未跑设备；行号仅对本基线有效，实现前请用 §9 的 anchor grep 复核（t11/t12/t13 合并后可能位移）。
> 边界：设置抽屉（SettingsDrawer/SettingsIA）属 **t13**；本文只覆盖信息卡（`ChannelInfoBanner` + `PlayerActivity` 卡片区 + `RemoteHabits` 默认值 + 新纯逻辑文件/测试/门禁）。

---

## 0. 结论摘要

信息卡 v2 **不是“改一下 banner 样式”**，它把信息条从「无状态只读浮层」升级为「三态、可聚焦、可切台」的交互层，因此接线点分布在三处：

| 层 | 文件 | 接线点数量 | 性质 |
|---|---|---:|---|
| Activity 按键层 | `PlayerActivity.kt:119-121, 208-209, 254-445, 472-473, 814-823` | 6 | 新增「卡片聚焦」守卫 + 第二段 OK 回调；**遮罩优先不得改动** |
| Compose 状态/数据层 | `PlayerActivity.kt:552, 844-850, 1325, 1387, 1401, 1485-1501, 1725-1765, 1792-1856` | 8 | `Boolean infoBannerOpen` → `State + focusIndex`；派生 `cardRecentItems`；统一 `switchToRecent` |
| 视图层 | `ChannelInfoBanner.kt:26-38, 41-142`（保留 `144-159/161-236/247-268`） | 3 | 大 logo/台名/技术行 + 最近观看条 + 焦点/提示/tag/日志 |
| 习惯与数据 | `RemoteHabits.kt:18-21, 49`；`RecentChannelsDialog.kt:49-54` | 2 | OK 默认改 `CONFIRM`；`RecentChannelUi` 增加切换所需索引 |
| 新文件 | `InfoCardPolicy.kt`、2 个测试类、`scripts/check_info_card.py` | 4 | 纯逻辑 + 门禁 |

**实施顺序建议**：`InfoCardPolicy`/模型 → `ChannelInfoBanner` 纯渲染拆分 → PlayerActivity 状态机 → Activity 守卫 → 测试/门禁 → 真机。每一步都可编译，便于二分回归。

---

## 1. 现状 vs 目标数据流

```
现状（t234 基线）
  recentResolved(1487-1497) ──► RecentChannelsDialog(1737-1765)   // 只有对话框能用最近观看
  nowProgramByUrl / videoFormat / audioTracks
        └──────────────► ChannelInfoBanner(1843-1854) visible=infoBannerOpen||seek
  Activity.dispatchKeyEvent(254-446) ── 只看 isInfoBannerOpen(368) 做 BACK 关闭
  LaunchedEffect(844-850) delay(5000) 自动隐藏

目标（INFO_CARD_V2）
  recentResolved ──► cardRecentItems = filter(!selected).take(6) ──┐
                                                                  ├─► Banner 最近观看条
  infoCardState(HIDDEN/IDLE/FOCUSED) + infoCardFocusIndex ─────────┘
  InfoCardPolicy（纯逻辑）：canFocusRecent / nextFocusIndex / autoHideMs / isVisible / showRecentRow / onExternalZap
  Activity：isInfoCardFocused 时 DPAD/OK/BACK 交给 Compose；第二段 OK 由 onInfoCardOk 回调决定聚焦
  ChannelInfoBanner：96×60 logo + headlineLarge 台名 + 技术行 + ≤6 小卡（40×40、焦点 surfaceVariant）
```

---

## 2. 必改 / 新增文件清单

| # | 文件 | 动作 | 归属 |
|---|---|---|---|
| 1 | `android/app/src/tivimini/java/com/cjlhll/iptv/InfoCardPolicy.kt` | **新增**（无 Compose/Android import） | t4 |
| 2 | `android/app/src/tivimini/java/com/cjlhll/iptv/ChannelInfoBanner.kt` | 重构（保留回看/进度/动效区） | t4 |
| 3 | `android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt` | 状态机 + 守卫 + 回调 + 派生数据（**只动卡片区**） | t4（与 t13 有文件级重叠，见 §8） |
| 4 | `android/app/src/tivimini/java/com/cjlhll/iptv/RecentChannelsDialog.kt` | `RecentChannelUi` 增加 `groupIndex/variantIndex`（默认值兼容） | t4 |
| 5 | `android/app/src/tivimini/java/com/cjlhll/iptv/RemoteHabits.kt` | `CONFIRM` label → 显示信息卡；`DEFAULT.ok = CONFIRM` | t4 |
| 6 | `android/app/src/test/java/com/cjlhll/iptv/RemoteHabitsTest.kt` | 4 处断言更新（§7） | t4 |
| 7 | `android/app/src/test/java/com/cjlhll/iptv/InfoCardPolicyTest.kt` | **新增** | t4 |
| 8 | `android/app/src/test/java/com/cjlhll/iptv/InfoCardRecentTest.kt` | **新增** | t4 |
| 9 | `scripts/check_info_card.py` | **新增**静态门禁（§6.4） | t4 |

> 不需要改：`StreamPlayback`/`PlaybackService`/零拷贝 `PlayerView`/看门狗/`SidebarController`/`SettingsDrawer`（设置入口提示属 t13）。

---

## 3. Activity 层接线（`PlayerActivity.kt`）

### 3.1 字段与 controller（P0，行 119-121 / 208-209 / 472-473 / 814-823）

现状：

```kotlin
119:    private var remote = RemoteHabits.DEFAULT
120:    private var setInfoBannerOpen: ((Boolean) -> Unit)? = null
121:    private var isInfoBannerOpen: Boolean = false
...
208:        setInfoBannerOpenController = { setInfoBannerOpen = it },
209:        onInfoBannerOpenChanged = { isInfoBannerOpen = it },
...
472:    setInfoBannerOpenController: (((Boolean) -> Unit)?) -> Unit,
473:    onInfoBannerOpenChanged: (Boolean) -> Unit,
...
817:        setInfoBannerOpenController { infoBannerOpen = it }
821:        setInfoBannerOpenController(null)
```

改法（新增 3 个字段 + 2 个参数 + 2 个镜像，**保留** `setInfoBannerOpen`/`isInfoBannerOpen` 语义）：

```kotlin
// Activity 字段
private var setInfoCardOk: (() -> Boolean)? = null      // 第二段 OK：IDLE→FOCUSED（Compose 判定）
private var setInfoCardFocused: ((Boolean) -> Unit)? = null // 供 Compose 推送“焦点在卡片内”
private var isInfoCardFocused: Boolean = false
// VideoPlayerScreen 参数
setInfoCardOkController: (((() -> Boolean)?) -> Unit),
onInfoCardFocusedChanged: (Boolean) -> Unit,
// 调用点
setInfoCardOkController = { setInfoCardOk = it },
onInfoCardFocusedChanged = { isInfoCardFocused = it },
// DisposableEffect(814-823) 同步 set/clear；onDispose 置 null
```

**为什么不复用一个 `Boolean`**：`isInfoBannerOpen`（可见）与 `isInfoCardFocused`（焦点在卡片内）是两个正交状态；BACK 语义不同（IDLE 一次关；FOCUSED 先退聚焦）。混用会导致 BACK 双触发。

### 3.2 按键守卫（P0，`dispatchKeyEvent` 254-446）

**插入位置**：数字键/数字 OSD 逻辑之后（291 行后），`when (event.keyCode)`（292 行）之前：

```kotlin
// INFO_CARD_V2 §4.2-2：卡片聚焦时，方向键/OK/BACK 交给 Compose 焦点节点；
// 数字键、166/167、上一台、MENU、音量等仍走既有分支（外部换台 → 卡片回 IDLE）。
val infoCardKey = when (event.keyCode) {
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK -> true
    else -> false
}
if (isInfoCardFocused && infoCardKey) return super.dispatchKeyEvent(event)
```

要点（每条都是回归点）：

| 序 | 要求 | 原因 |
|---|---|---|
| 1 | 必须放在**数字键分支之后** | 数字 OSD 的 BACK 清空（288）与 OK 提交（289）要优先，否则卡片聚焦时数字 OSD 无法取消 |
| 2 | `KEYCODE_MENU` **不在**跳过集合 | 聚焦中按 MENU 仍打开设置（339-344）；Compose 侧收到覆盖层变化后 `HIDDEN` 并清焦点 |
| 3 | `KEYCODE_LAST_CHANNEL/166/167` 不在跳过集合 | 外部换台 → `playLastChannel`/`setOnChannelStep` 里把状态置 `IDLE`（§4.5） |
| 4 | 不得改动既有 297/304/316/328/340/404/425 的覆盖层守卫 | 「遮罩优先」硬性要求：`isDrawerOpen/isSettingsDrawerOpen/isQualityDialogOpen/isRecentChannelsOpen/isAudioDialogOpen` 任一为真时 OK 绝不走 SHOW_INFO |
| 5 | OK 分支（303-313）只在**无覆盖层且 `isInfoBannerOpen`** 时先问 `setInfoCardOk` | 两段式 OK（D-C2）；只对 `remote.ok == CONFIRM` 生效，`OPEN_DRAWER` 保持旧行为 |

OK 分支改法（303-313）：

```kotlin
KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
    if (!isDrawerOpen && !isSettingsDrawerOpen && !isQualityDialogOpen &&
        !isRecentChannelsOpen && !isAudioDialogOpen) {
        // 卡片可见 + 新默认：第二段 OK 进入最近观看聚焦；返回 false 时按原习惯继续（重新出卡/开列表）
        if (isInfoBannerOpen && remote.ok == RemoteHabits.Ok.CONFIRM &&
            setInfoCardOk?.invoke() == true) return true
        when (RemoteHabits.onOk(remote, overlayOpen = false, catchupPlayback = isCatchupPlayback)) {
            RemoteHabits.OkAction.OPEN_DRAWER -> sidebarController.openDrawer()
            RemoteHabits.OkAction.SHOW_INFO -> setInfoBannerOpen?.invoke(true)
            RemoteHabits.OkAction.NONE -> Unit
        }
        return true
    }
}
```

BACK 分支（346-371）：**不改**。聚焦态 BACK 已在 §3.2 守卫处交给 Compose（卡片退聚焦并消费）；未聚焦时仍由 368-371 关卡片。

### 3.3 自动隐藏与 Activity 镜像（P1，844-850）

现状：

```kotlin
844:    LaunchedEffect(infoBannerOpen, currentGroupIndex, currentVariantIndex) {
845:        onInfoBannerOpenChanged(infoBannerOpen)
846:        if (infoBannerOpen) { delay(5000); infoBannerOpen = false }
847:    }
```

改为「镜像用 SideEffect、计时用策略」两段（`SideEffect` 模式参考 533 行音频）：

```kotlin
SideEffect {
    onInfoBannerOpenChanged(infoCardState != InfoCardPolicy.State.HIDDEN) // seek 不计入（保持旧 BACK 语义）
    onInfoCardFocusedChanged(infoCardState == InfoCardPolicy.State.FOCUSED)
}
LaunchedEffect(infoCardState, infoCardTimerTick, currentGroupIndex, currentVariantIndex) {
    val hideMs = InfoCardPolicy.autoHideMs(infoCardState) ?: return@LaunchedEffect // FOCUSED → null
    delay(hideMs)
    if (infoCardState == InfoCardPolicy.State.IDLE) infoCardState = InfoCardPolicy.State.HIDDEN
}
```

- `infoCardTimerTick`：无状态变化但需要“重新计时”时自增（第二段 OK 但最近观看为空时）。
- **不要**把 `isSeekOverlayVisible` 算进 `isInfoBannerOpen`：现状 856-862 的 1.5s 浮层靠自身计时，若镜像为 true，BACK 会先被 368 吃掉，改变回看退出体验。
- 旧的硬编码 `delay(5000)` 必须消失（门禁断言）。

### 3.4 覆盖层出现即隐藏（P0，新增 effect）

```kotlin
LaunchedEffect(drawerOpen, settingsDrawerOpen, qualityDialogOpen, recentChannelsOpen, audioDialogOpen) {
    if (drawerOpen || settingsDrawerOpen || qualityDialogOpen || recentChannelsOpen || audioDialogOpen) {
        infoCardState = InfoCardPolicy.State.HIDDEN
        infoCardFocusIndex = 0
    }
}
LaunchedEffect(isSeekOverlayVisible) {
    // 快进/快退浮层：卡片仍显示（回看进度行），但收起最近观看条并退出聚焦
    if (isSeekOverlayVisible && infoCardState == InfoCardPolicy.State.FOCUSED) {
        infoCardState = InfoCardPolicy.State.IDLE
        infoCardFocusIndex = 0
    }
}
```

- 覆盖层（抽屉/设置/线路/音轨/最近观看）打开 → `HIDDEN`（规格 §4.2-5），关闭后不自动重现。
- `isSeekOverlayVisible` **不**置 `HIDDEN`（否则回看快进时卡片会闪没）：只退聚焦；条是否渲染由 `InfoCardPolicy.showRecentRow(..., seekOverlayVisible=true)=false` 决定。

### 3.5 换台/上一台/数字/音轨 → 统一 `IDLE`（P1）

| 位置 | 现状 | 改为 |
|---|---|---|
| 1325 `playLastChannel()` | `infoBannerOpen = true` | `showInfoCard()`（state=IDLE, index=0, timerTick++） |
| 1387 `setOnNumberJump` | `infoBannerOpen = true` | 同上 |
| 1401 `setOnChannelStep` | `infoBannerOpen = true` | 同上 |
| 1732 `AudioTrackDialog.onSelect` | `infoBannerOpen = true` | 同上 |
| 406/427 回看 UP/DOWN | `setInfoBannerOpen?.invoke(true)` | 同上（Compose 内部直接改 state） |
| 308 OK→SHOW_INFO | `setInfoBannerOpen?.invoke(true)` | 由 Compose controller 回调 `showInfoCard()`（Activity 只调用） |

外部换台（数字/166/167/229）必须把焦点清走：在 state 变 `IDLE` 的 `LaunchedEffect(infoCardState)` 里 `if (state != FOCUSED) focusManager.clearFocus()`。

### 3.6 第二段 OK 回调（P0，Compose 侧实现，Activity 侧只 invoke）

```kotlin
DisposableEffect(Unit) {
    val handler: () -> Boolean = {
        if (infoCardState == InfoCardPolicy.State.IDLE) {
            val canFocus = InfoCardPolicy.canFocusRecent(
                recentCount = cardRecentItems.size,
                catchup = catchupRequest != null,
                seekOverlayVisible = isSeekOverlayVisible,
            )
            if (canFocus) { infoCardFocusIndex = 0; infoCardState = InfoCardPolicy.State.FOCUSED }
            else infoCardTimerTick++            // 空条：只重新计时
            true
        } else false
    }
    setInfoCardOkController(handler)
    onDispose { setInfoCardOkController(null) }
}
```

---

## 4. Compose 状态与数据（`PlayerActivity.kt`）

### 4.1 状态声明（552 替换）

```kotlin
var infoCardState by remember { mutableStateOf(InfoCardPolicy.State.HIDDEN) }
var infoCardFocusIndex by remember { mutableIntStateOf(0) }
var infoCardTimerTick by remember { mutableIntStateOf(0) }
fun showInfoCard() { infoCardState = InfoCardPolicy.State.IDLE; infoCardFocusIndex = 0; infoCardTimerTick++ }
```

### 4.2 `cardRecentItems`（1485-1501 派生，新增 3 行）

```kotlin
val cardRecentItems = remember(recentResolved, currentGroupIndex) {
    recentResolved.map { it.first }
        .filterNot { it.selected }                       // 排除当前台（D-C3）
        .take(InfoCardPolicy.MAX_RECENT)                 // ≤6
}
```

`recentResolved` 需给 `RecentChannelUi` 补索引（见 §5.1），否则切台只能按 url 二次解析。

### 4.3 统一切台助手（对话框与卡片共用）

```kotlin
fun switchToRecent(item: RecentChannelUi): Boolean {
    if (item.groupIndex !in channelGroups.indices) return false
    val group = channelGroups[item.groupIndex]
    if (group.variants.isEmpty()) return false
    val variant = item.variantIndex.takeIf { it in group.variants.indices } ?: 0
    sidebarController.closeDrawer()
    playGroupVariant(item.groupIndex, variant, armZap = true) // 内部会 recordRecentChannel（1202）
    return true
}
```

- 对话框 `onSelect`（1740-1752）改为 `setRecentOpen(false); switchToRecent(recentItems[index])`（删除重复的 index 解析）。
- 卡片 `onSwitchRecent`：`switchToRecent(...)` 后**保持** `FOCUSED`、`infoCardFocusIndex = 0`（不重置计时；`playGroupVariant` 会更新 recent → 卡片内容刷新，见规格 §5.2）。
- 注意 `playGroupVariant` 不设置任何 banner 状态，所以不会把 `FOCUSED` 打回 `IDLE`；外部换台路径才会显式 `IDLE`。

### 4.4 卡片的 `onExitFocus` / `onHide`

```kotlin
onExitFocus = { infoCardFocusIndex = 0; infoCardState = IDLE; infoCardTimerTick++ }
onHide     = { infoCardState = HIDDEN; infoCardFocusIndex = 0 }
```

### 4.5 回看/快进门控

- 回看中（`catchupRequest != null`）：`InfoCardPolicy.showRecentRow(...)=false`（条不渲染）且 `canFocusRecent=false`。
- 快进/快退浮层（`isSeekOverlayVisible`）：同上（隐藏条、不可聚焦），但卡片本体仍显示回看进度行（现状保留）。

---

## 5. 视图层（`ChannelInfoBanner.kt`）

### 5.1 依赖模型：`RecentChannelUi` 增加切换索引（`RecentChannelsDialog.kt:49-54`）

```kotlin
data class RecentChannelUi(
    val title: String,
    val number: Int,
    val logoUrl: String?,
    val selected: Boolean,
    val groupIndex: Int = -1,     // 新增：切台用（resolved.groupIndex）
    val variantIndex: Int = 0,    // 新增：切台用（resolved.variantIndex）
)
```

`recentResolved`（PlayerActivity 1487-1497）填充 `groupIndex = resolved.groupIndex`、`variantIndex = resolved.variantIndex`；对话框现有行为不变（默认值 + 新字段不参与渲染）。

### 5.2 函数签名（26-38 替换；唯一调用点在 1843-1854）

```kotlin
@Composable
fun ChannelInfoBanner(
    state: InfoCardPolicy.State,
    seekOverlayVisible: Boolean,
    channel: Channel?,
    programTitle: String?,
    channelNumber: Int = -1,
    programProgress: Float? = null,
    videoFormat: Format?,
    audioLabel: String? = null,
    catchupConfiguration: CatchupConfiguration? = null,
    recentCards: List<RecentChannelUi> = emptyList(),
    focusIndex: Int = 0,
    onFocusNext: (Int) -> Unit = {},
    onSwitchRecent: (Int) -> Unit = {},
    onExitFocus: () -> Unit = {},
    onHide: () -> Unit = {},
    onTimerReset: () -> Unit = {},     // 可选：第二段 OK 空条时重新计时
    animate: Boolean = true,
    modifier: Modifier = Modifier,
)
```

内部：`val visible = InfoCardPolicy.isVisible(state, seekOverlayVisible)`；`AnimatedVisibility(visible, ...)` 保留 144-159 的 160/120ms 动效。

### 5.3 结构改法（from → to）

| 区域 | 现状行 | 改法 |
|---|---|---|
| 容器 | 46-50 | 不变：`fillMaxWidth().background(containerColor, shape)`，`surface` α0.92，**0dp** |
| 根节点 | 新增 | 加 `testTag("info_card")` + `semantics { contentDescription = "频道信息 …"; testTagsAsResourceId = true }`（需 `@OptIn(ExperimentalComposeUiApi::class)`） |
| 回看进度行 | 52-58 | 保留不动（`CatchupConfiguration` 解构） |
| 顶部行 | 60-139 | 重写：logo `96×60`（tag `info_card_logo`）、间距 24、频道号 overline `titleSmall`（tag `info_card_number`）、台名 `headlineLarge`（tag `info_card_title`）、节目 `titleLarge`（tag `info_card_program`）、进度条（tag `info_card_progress`）、技术行 `labelMedium`（tag `info_card_tech`） |
| 右侧技术列 | 106-138 | **删除该列**，逻辑抽到 `InfoCardPolicy.techLine(audioLabel, width, height, bitrate)`（纯函数、可单测）：`音轨 mpeg-L2 · 2ch · 1920x1080 · 3000 kbps`，空项省略 |
| 最近观看条 | 新增 | 见 §5.4 |
| 入口提示 | 新增 | `← 频道列表　·　→ 设置`，`labelMedium` α0.45，`testTag("info_card_hint")`，不可聚焦（`focusable` 不调用） |
| 动画 | 144-158 | 保留（`slideInVertically(160)` / `slideOutVertically(120)`） |
| `TechSpecText` | 238-245 | 改为新版技术行样式（或删除，避免两个未用函数） |
| `BannerProgressBar` | 247-268 | 保留（3dp、`primary` 0.80、0dp） |
| `CatchupProgressRow` | 161-236 | 保留不动 |

### 5.4 最近观看条（新增 composable，约 80 行）

```kotlin
@Composable
private fun RecentStrip(
    items: List<RecentChannelUi>,
    focusIndex: Int,
    onFocusNext: (Int) -> Unit,
    onSwitchRecent: (Int) -> Unit,
    onExitFocus: () -> Unit,
) {
    val requesters = remember(items.size) { List(items.size) { FocusRequester() } }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(items.size) {
        // 只有处于 FOCUSED 时才请求焦点；由调用方保证进入时机
    }
    Row(Modifier.testTag("info_card_recent_row")) {
        items.forEachIndexed { i, item ->
            Column(
                Modifier
                    .size(width = 104.dp, height = 64.dp)
                    .testTag("info_card_recent_$i")
                    .semantics { contentDescription = "最近观看第${i + 1}个 ${item.number}. ${item.title}" }
                    .focusRequester(requesters[i])
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionLeft -> { onFocusNext(-1); true }
                            Key.DirectionRight -> { onFocusNext(1); true }
                            Key.DirectionCenter, Key.Enter -> { onSwitchRecent(i); true }
                            Key.DirectionUp, Key.DirectionDown, Key.Back -> { onExitFocus(); true }
                            else -> false
                        }
                    }
                    .focusable()
                    .background(if (i == focusIndex) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                    .padding(4.dp),
            ) {
                ChannelLogo(item.logoUrl, item.title, width = 40.dp, height = 40.dp)
                Spacer(Modifier.height(4.dp))
                Text(item.title, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                     overflow = TextOverflow.Ellipsis,
                     color = if (i == focusIndex) MaterialTheme.colorScheme.onSurface
                             else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    // 调用方在 state==FOCUSED 时：LaunchedEffect(focusIndex, items.size) { requesters[focusIndex].requestFocus() }
    // state!=FOCUSED 时：LaunchedEffect(state) { if (state != FOCUSED) focusManager.clearFocus() }
}
```

注意：
1. **不要**用 `LazyRow`（≤6 个、无回收抖动）。
2. 焦点底色只改 `background(color)`，不传 shape、不改 size/padding → 无 remeasure。
3. 小卡必须保持 `104×64` 不变（聚焦不放大）。
4. `onFocusNext` 由 PlayerActivity 用 `InfoCardPolicy.nextFocusIndex` clamp（首尾不环绕）。
5. 若 `recentCards` 为空或 `showRecentRow=false`：不组合该条（也不组合 label）。

### 5.5 标签行与提示

```
Row(fillMaxWidth) {
  Text("最近观看", labelMedium, onSurface α0.60)   // testTag info_card_recent_label
  Spacer(weight 1f)
  Text("← 频道列表　·　→ 设置", labelMedium, onSurfaceVariant α0.45) // info_card_hint
}
```
无最近观看条时：提示单独一行右对齐（+16dp 高，见规格高度表）。提示**不可聚焦**。

### 5.6 日志（可选但建议）

`IQTV-INFOCARD`（仅 `Log.isLoggable(..., DEBUG)`）：
`show state=IDLE recent=3` / `focus index=0` / `move index=2` / `switch idx=1 key=…` / `hide reason=back|timer|overlay`。
位置：`showInfoCard()`、`onFocusNext`、`onSwitchRecent`、`onHide/onExitFocus` 与计时器到点处。

---

## 6. 习惯、默认值与门禁

### 6.1 `RemoteHabits.kt`（18-21 / 49）

```kotlin
enum class Ok(val label: String) {
    OPEN_DRAWER("打开频道列表"),
    CONFIRM("显示信息卡"),        // 仅改 label，枚举名不变（Prefs 兼容）
}
val DEFAULT = Settings(ok = Ok.CONFIRM)   // 原 Settings() 默认 OPEN_DRAWER
```

`onOk`（79-89）**不改**：`CONFIRM` 本来就返回 `SHOW_INFO`；回看仍强制 `SHOW_INFO`；`overlayOpen=true` 仍 `NONE`（遮罩优先）。

### 6.2 `RemoteHabitsTest.kt` 必改断言

| 行 | 现状断言 | 改为 |
|---|---|---|
| 16 | `defaults.ok == OPEN_DRAWER` | `defaults.ok == CONFIRM` |
| 26 | `parseOk(null) == OPEN_DRAWER` | `parseOk(null) == CONFIRM` |
| 65 | `onOk(defaults) == OPEN_DRAWER` | `onOk(defaults) == SHOW_INFO`（并把 67 行的 `confirm` 用例扩展为 `OPEN_DRAWER` → 证明旧选择仍生效） |
| 98 | `onOk(defaults, catchup=false) == OPEN_DRAWER` | `== SHOW_INFO`（或显式用 `defaults.copy(ok = OPEN_DRAWER)` 验证旧行为） |
| 标签测试 189-194 | 只断言非空 | 可补 `Ok.CONFIRM.label == "显示信息卡"` |

### 6.3 规格门禁：`scripts/check_info_card.py`（新增）

断言列表（全部基于源码文本，参考 `check_settings_scroll.py` 风格）：

1. `InfoCardPolicy.kt` 存在，含 `MAX_RECENT = 6`、`AUTO_HIDE_MS = 3000`、`canFocusRecent`、`nextFocusIndex`、`autoHideMs`、`showRecentRow`、`onExternalZap`。
2. `ChannelInfoBanner.kt`：`96.dp` 与 `60.dp`（logo）、`headlineLarge`、`titleLarge`、`40.dp`（小卡 logo）、`info_card` 系列 tag、`← 频道列表` 提示。
3. `ChannelInfoBanner.kt`：无 `BorderStroke`/`border(`/`shadow`/`elevation`/`blur`/`graphicsLayer`；`RoundedCornerShape(` 参数仅 `0.dp`/`2.dp`。
4. `PlayerActivity.kt`：`isInfoCardFocused` 守卫存在，且不再含旧 `delay(5000)`；`setInfoCardOkController`/`onInfoCardFocusedChanged` 均接线。
5. `RemoteHabits.kt`：`CONFIRM("显示信息卡")` 且 `DEFAULT` 含 `ok = Ok.CONFIRM`。
6. 设置侧入口提示（t13 负责）不在本门禁断言范围，避免跨任务耦合。

### 6.4 JVM 测试（新增）

| 文件 | 断言 |
|---|---|
| `InfoCardPolicyTest.kt` | §7.2 规格全部：`canFocusRecent` 四分支、`nextFocusIndex` clamp、`autoHideMs` 三态、`isVisible`（含 seek）、`onExternalZap`、`showRecentRow` |
| `InfoCardRecentTest.kt` | 10 条 recent（含当前台/legacy `url:` 行/解析不到行）→ 卡片条目排除当前、去重、≤6、顺序；空列表为空 |
| `RemoteHabitsTest.kt` | 上述 4 处默认值/标签更新，其余用例保持绿 |

> `ChannelInfoBanner` 是 Compose 函数，不进 JVM 测试；所有可判定逻辑必须留在 `InfoCardPolicy`（纯 Kotlin）。

---

## 7. 修改范围与冲突控制

| 文件 | t4 可动 | 其他人 |
|---|---|---|
| `PlayerActivity.kt` | 仅 119-121、208-209、254-292（插入守卫）、303-313、472-473、552、814-850、1325、1387、1401、1485-1501、1725-1735、1737-1765（复用助手）、1792-1856 | t13 只动 `SettingsDrawer(...)` 调用参数区（1511-1723）与设置状态，**不改**上述行；t11/t12 可能动播放器区（<1200），不改卡片区 |
| `ChannelInfoBanner.kt` | 全文（保留回看/进度/动效段） | 无 |
| `RemoteHabits.kt` | 仅 label/DEFAULT | t13 只读 label 显示 |
| `RecentChannelsDialog.kt` | 仅 `RecentChannelUi` 加字段（49-54） | 无 |
| `SettingsDrawer.kt` / `SettingsIA.kt` | **不改** | t13 |

合并顺序建议：t13 先合（设置抽屉，文件独立）→ t4（卡片）。若 t4 先合，t13 rebase 时注意 `PlayerActivity` 的 `SettingsDrawer` 调用区不重叠。

---

## 8. 与 `INFO_CARD_V2.md` 的对应关系

| 规格条目 | 本文件接线点 |
|---|---|
| §2 尺寸/高度账 | §5.3 结构、§5.4 小卡尺寸、§5.5 提示行 |
| §3 视觉 token/焦点态 | §5.4 焦点底色、§5.3 容器 |
| §4 状态机/按键 | §3.2 守卫、§3.3/3.4 计时与覆盖层、§3.6 二段 OK、§4.1/4.4 状态 |
| §5 最近观看规则 | §4.2 派生、§4.3 切台、§5.1 模型、§5.4 渲染 |
| §7 测试/门禁 | §6.3/6.4 |
| §12 cap·R1（入口提示/遮罩优先） | §5.5 提示、§3.2 第 4 条、§3.3 镜像说明 |

---

## 9. 行号 anchor（t11/t12/t13 合并后复核用）

```powershell
# 改前逐条确认仍存在（不应有输出之外的错位）
Select-String -Path android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt `
  -Pattern 'private var setInfoBannerOpen','setInfoBannerOpenController =','onInfoBannerOpenChanged =','override fun dispatchKeyEvent','KeyEvent.KEYCODE_DPAD_CENTER','LaunchedEffect\(infoBannerOpen','var infoBannerOpen','fun playLastChannel','setOnNumberJump','setOnChannelStep','ChannelInfoBanner\('
Select-String -Path android/app/src/tivimini/java/com/cjlhll/iptv/ChannelInfoBanner.kt `
  -Pattern 'fun ChannelInfoBanner','ChannelLogo\(','headlineMedium','BannerProgressBar','CatchupProgressRow','TechSpecText'
Select-String -Path android/app/src/tivimini/java/com/cjlhll/iptv/RemoteHabits.kt -Pattern 'enum class Ok','val DEFAULT'
Select-String -Path android/app/src/tivimini/java/com/cjlhll/iptv/RecentChannelsDialog.kt -Pattern 'data class RecentChannelUi'
```

当前基线命中行：

| anchor | 文件:行 |
|---|---|
| `setInfoBannerOpen` 字段 / `isInfoBannerOpen` | `PlayerActivity.kt:120-121` |
| controller 接线 | `PlayerActivity.kt:208-209` |
| `VideoPlayerScreen` 参数 | `PlayerActivity.kt:472-473` |
| `dispatchKeyEvent` | `PlayerActivity.kt:254` |
| OK / LEFT / RIGHT / MENU / BACK / UP / DOWN | `PlayerActivity.kt:303 / 315 / 327 / 339 / 346 / 403 / 424` |
| `infoBannerOpen` 声明 | `PlayerActivity.kt:552` |
| 计时 effect | `PlayerActivity.kt:844-850` |
| seek 浮层 effect | `PlayerActivity.kt:856-862` |
| `playGroupVariant` | `PlayerActivity.kt:1177-1203` |
| `playLastChannel` | `PlayerActivity.kt:1312-1327` |
| 数字跳台 / 频道步进 | `PlayerActivity.kt:1383-1390 / 1391-1403` |
| `recentResolved` / `recentItems` | `PlayerActivity.kt:1487-1501` |
| 音轨选择回卡 | `PlayerActivity.kt:1725-1735` |
| 最近观看对话框 | `PlayerActivity.kt:1737-1765` |
| 信息卡调用 | `PlayerActivity.kt:1843-1854` |
| banner 签名 / 容器 | `ChannelInfoBanner.kt:26-38 / 41-50` |
| 旧 logo / 台名 / 技术列 | `ChannelInfoBanner.kt:66-72 / 82-88 / 106-138` |
| 进度条 | `ChannelInfoBanner.kt:247-268` |
| `Ok` 枚举 / DEFAULT | `RemoteHabits.kt:18-21 / 49` |
| `onOk` | `RemoteHabits.kt:79-89` |
| `RecentChannelUi` | `RecentChannelsDialog.kt:49-54` |
| `ChannelLogo` 定义 | `LogoLoader.kt:614` |

---

## 10. 验证清单（t4 交付时自查）

- [ ] `InfoCardPolicy.kt` 无 Compose/Android import；单测全绿。
- [ ] `ChannelInfoBanner` 新签名唯一调用点（1843-1854）已更新；无遗留 `visible=`/旧参数。
- [ ] Activity：`isInfoCardFocused` 守卫在数字键逻辑之后、`when` 之前；覆盖层守卫顺序未变；`delay(5000)` 已删。
- [ ] 第二段 OK：`CONFIRM` 且卡片可见时聚焦；`OPEN_DRAWER` 用户仍直接开列表；无最近观看时只重计时。
- [ ] BACK：IDLE 一次关；FOCUSED 先退聚焦且不退出播放器。
- [ ] 外部换台（数字/166/167/229/抽屉选台）→ `IDLE` 且焦点清空。
- [ ] 覆盖层（抽屉/设置/线路/音轨/最近观看）打开 → 卡片 `HIDDEN`；关闭后不自动重现。
- [ ] 回看/快进：无最近观看条、不可聚焦、回看进度行保留。
- [ ] 小卡：≤6、排除当前台、40×40 logo、12sp 台名、LEFT/RIGHT clamp、OK 切台、切后台保持聚焦。
- [ ] 无 border/shadow/blur/非 0dp；无 `LazyRow`。
- [ ] `RemoteHabitsTest` 4 处断言更新；`Init` 默认值只影响无偏好用户（`parseOk(null)=CONFIRM`）。
- [ ] `python scripts/check_info_card.py` 通过；`check_settings_scroll.py` 不受影响（t13 后跑）。
- [ ] 真机按 `INFO_CARD_V2.md` §8.2 的 17 步验收。

建议本地验证命令（Windows，`--no-daemon` 防 daemon 污染 CI 资源）：

```powershell
cd iqtvlite
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python scripts/check_info_card.py
python scripts/check_settings_scroll.py
Select-String -Path android/app/src/tivimini/java/com/cjlhll/iptv/ChannelInfoBanner.kt -Pattern 'border|BorderStroke|shadow|elevation|blur|LazyRow'
```

---

## 11. 待 t4 拍板的小选择（不影响规格）

1. **状态归属**：建议 `infoCardState/focusIndex` 留在 `VideoPlayerScreen`（由 Activity 通过 controller 只读镜像），不放进 `ChannelInfoBanner`（Activity 需要按键守卫）。
2. **`visible` 参数**：建议删除，改为 `state + seekOverlayVisible`，让 `InfoCardPolicy.isVisible` 唯一裁决（避免两套可见性判断）。
3. **模型**：建议扩展 `RecentChannelUi`（单模型、对话框零改动）；不建议新建平行模型。
4. **计时重开**：用 `infoCardTimerTick` 自增，避免 `LaunchedEffect` 同值不重启。
5. **提示行位置**：按规格“有条与标签同排、无条单独一行”；若实现成本高，可退化为始终单独一行（+16dp，规格已给高度）。
6. **焦点丢失兜底**：`LaunchedEffect(infoCardState)` 中当 state != FOCUSED 时 `focusManager.clearFocus()`；若 Compose 焦点被系统抢走（罕见），下一次 OK 仍可从 IDLE 重新进入。
