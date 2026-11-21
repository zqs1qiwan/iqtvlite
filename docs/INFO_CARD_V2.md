# 信息卡 v2（INFO_CARD_V2）— 大 logo / 大台名 + 底部「最近观看」快跳

> 版本 1.0 · 2026-11-21 · designer（任务 t2）· 代码基线 `b949a41`
> 适用：t4（信息卡实现）、t8（复审）、t9（真机验收）；与 [`SETTINGS_IA_V2.md`](SETTINGS_IA_V2.md) 同批交付。
> 目标机：TR100-G9 / API28 / 1080p / density 240 → **逻辑屏 1280×720dp**（1dp = 1.5px）。全文 dp/sp 以该逻辑屏为准。

---

## 0. 一句话

**播放中按 OK 出现底部信息卡：顶部 96dp 大台标 + 32sp 大台名，中部当前节目 / 进度 / 音轨 / 分辨率，底部一排「最近观看」小卡（40dp 台标 + 12sp 台名，≤6 个，可左右聚焦，OK 即切台）；整体 0dp 无边框、焦点只用 `surfaceVariant` 底色、不加蓝框，空闲 3s 自动隐藏、聚焦时不隐藏。**

### 0.1 本轮决策（评审先看）

| 编号 | 决策 | 说明 / 回退 |
|---|---|---|
| **D-C1** | **「OK键」默认值从「打开频道列表」改为「显示信息卡」**；保留枚举名 `CONFIRM`（已存偏好继续可解析），label `确认` → `显示信息卡`，`DEFAULT.ok = CONFIRM`。**captain 2026-11-21 已批准**（决定记录见 §12 cap·R1；captain 批复中把它记作 D-S3，编号对调说明见 §12） | **仅对从未写入 `remote_ok` 偏好的用户生效**（新装 / 清数据 / 从未改过该项）；已存 `OPEN_DRAWER` / `CONFIRM` 原样保留，不静默改老用户显式选择。`onOk` 对 `CONFIRM` 本就返回 `SHOW_INFO`（`RemoteHabits.kt:79-89`），所以只是默认值 + 文案。频道列表仍一键可达（**LEFT**），设置一键（**RIGHT**），并按 captain 要求在**信息卡与设置抽屉两处写明入口提示**（§2.1、`SETTINGS_IA_V2.md` §3.1/§3.3）。回退：`RemoteHabits.DEFAULT.ok = Ok.OPEN_DRAWER` 一行（枚举名不变，Prefs 无需迁移） |
| **D-C2** | **聚焦入口 = 卡片可见时再按一次 OK**（两段式） | 避免卡片一出现就把 LEFT/RIGHT 从“开列表/开设置”抢走（换台后 3s 内用户最常做的是继续换台或开列表）。第二下 OK 是明确意图：进入最近观看。聚焦后 LEFT/RIGHT 在卡片内移动、BACK/UP/DOWN 退出聚焦，退出后 LEFT/RIGHT 恢复原语义 |
| **D-C3** | 最近观看**排除当前频道**、最多 6 个、最新在前 | 当前频道已经是大标题；排除后 OK 才能在两个频道间快速来回（A↔B toggle），且不会出现“OK 切到正在播的台”这种无反馈操作 |
| **D-C4** | 回看（catchup）播放中**不显示最近观看条、不允许聚焦** | 最近观看小卡是“跳直播台”，回看中点击会丢掉回看进度；回看卡片保持现有「起止时间 + 进度」行 |
| **D-C5** | 自动隐藏 **3000ms**（旧 5000ms）；聚焦时**不隐藏** | 任务/t4 要求“3s 自动隐藏但聚焦时不隐藏”；换台时卡片刷新并按新内容重新计时 |
| **D-C6** | 不改零拷贝 SurfaceView 架构、不改信息卡之外的覆盖层 | 卡片仍由 Compose 覆盖层绘制（视频层在下方），不引入 `AndroidView`/`PlayerView`（防 t30 灰屏回归） |

### 0.2 不变量（红线）

1. 视频层零拷贝：卡片只在 Compose 覆盖层绘制，不碰 `PlayerView`/`SurfaceView`。
2. 无硬边框 / 无描边 / 无阴影 / 无圆角（0dp）/ 无蓝框 / 无模糊 / 无缩放焦点。
3. 卡片可点区域之外不拦截按键：未聚焦时按键路径与改造前完全一致（上下换台、左开抽屉、右开设置、OK 按习惯、BACK 关卡片）。
4. 现有能力不丢：回看进度行、快进/快退浮层、音轨标签、分辨率/码率、频道号、`▶`/“回看中”、自动隐藏、BACK 只关卡片。
5. 不新增持久化字段；最近观看仍来自 `RecentChannels`/`RecentChannelsCache`（≤10 存储、去重、清空语义不变）。

---

## 1. 现状与目标差异

现状（`android/app/src/tivimini/java/com/cjlhll/iptv/ChannelInfoBanner.kt:41-142`，调用点 `PlayerActivity.kt:1843-1854`）：

| 项 | 现状 | v2 |
|---|---|---|
| 容器 | `surface` 0.92、0dp、padding 32/24 | **不变** |
| 台标 | `ChannelLogo` 80×50（`ChannelInfoBanner.kt:66-72`） | **96×60**（最大边 96dp） |
| 台名 | `headlineMedium` 28sp，标题自带 `"$number. $title"`（`82-88`） | **`headlineLarge` 32sp**；频道号改为独立 overline（14sp） |
| 当前节目 | `titleMedium` 16sp，`primary`（`90-98`） | **`titleLarge` 22sp**，`primary` 不变 |
| 进度 | 3dp 轨道 + `primary` 0.80 填充（`247-268`） | 不变（数值、颜色、0dp） |
| 技术参数 | 右侧竖排 4 行 `labelMedium`（`108-138`） | 合并为**中部一行**（音轨 · 分辨率 · 码率），节目标题获得整行宽度 |
| 最近观看 | 无（仅在设置弹窗里） | **底部横向小卡 ≤6，40dp 台标 + 12sp 台名，可聚焦，OK 切台** |
| 显示时机 | 换台 / OK（`RemoteHabits`）/ 回看 UP/DOWN | 同前（默认 OK 改为出卡，D-C1） |
| 自动隐藏 | 5000ms（`PlayerActivity.kt:844-850`） | **3000ms**；聚焦时不隐藏 |
| 焦点 | 无 | 卡片内只有最近观看小卡可聚焦（两段式 OK 进入） |
| BACK | 关卡片（`PlayerActivity.kt:368-371`） | 聚焦中先退聚焦，再按才关卡片 |

---

## 2. 布局与尺寸（live 播放，有 EPG + 有最近观看）

全宽底部浮层；坐标均相对卡片左上角；容器 padding 左 32 / 右 32 / 上 24 / 下 24。

```
┌──────────────────────────────────────────────────────────────────────────────┐ ← surface 0.92, 0dp
│  (24)                                                                        │
│  ┌────────────┐   (24)  ┌─────────────────────────────────────────────────┐│
│  │            │         │ 频道 12                        titleSmall 14/20  ││
│  │   LOGO     │         │ CCTV-1 综合                    headlineLarge 32/40││
│  │  96 × 60   │         │ 新闻联播                        titleLarge 22/28 ││
│  │            │         │ ▬▬▬▬▬▬▬▬▬▬▬░░░░░░░░░        3dp, 6dp 上间距   ││
│  └────────────┘         │ 音轨 mpeg-L2 · 2ch · 1920x1080 · 3000 kbps       ││
│                         │                                       12/16     ││
│                         └─────────────────────────────────────────────────┘│
│  (16)                                                                        │
│  最近观看                                      labelMedium 12/16, α0.60     │
│  (8)                                                                         │
│  ┌────────┐ (12) ┌────────┐      ┌────────┐                                 │
│  │  LOGO  │      │  LOGO  │      │  LOGO  │   小卡 104×64                   │
│  │ 40×40  │      │ 40×40  │      │ 40×40  │   台名 bodySmall 12/16         │
│  │ CCTV-2 │      │ CCTV-3 │      │ 湖南.. │                                 │
│  └────────┘      └────────┘      └────────┘                                 │
│  (24)                                                                        │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 尺寸表

| 元素 | 尺寸 / 数值 | 说明 |
|---|---|---|
| 卡片容器 | `fillMaxWidth`，底部对齐，`background(surface α0.92)`，0dp，**无 border** | 高度随内容（无固定高） |
| 容器 padding | left 32 / right 32 / top 24 / bottom 24 | 沿用现状 |
| 顶部台标 | `ChannelLogo(width=96.dp, height=60.dp)` | 96dp = 最大边；`ContentScale` 沿用 `LogoLoader` 的显示方式，不拉伸变形 |
| 台标–文字间距 | 24dp | 现状 20dp，增大以突出台名 |
| 频道号 overline | `titleSmall` 14sp / 行高 20sp，`onSurfaceVariant` α0.80，单行 | 文案 `频道 {number}`；`number<=0` 时整行不渲染 |
| 台名 | `headlineLarge` 32sp / 行高 40sp，`onSurface`，单行 ellipsis | 全卡最大字号，对比度最高 |
| 当前节目 | `titleLarge` 22sp / 行高 28sp，`primary`（#BDBDBD），单行 ellipsis | 回看时文案 `{节目} (回看中)`（现状保留） |
| 进度条 | 高 3dp，`fillMaxWidth`（文字列宽），轨道 `onSurface α0.12`、填充 `primary α0.80`；上间距 6dp | 仅 live 且 `progress != null`；0dp 方头 |
| 技术参数行 | `labelMedium` 12sp / 行高 16sp，`onSurface` α0.70，单行 ellipsis；上间距 2dp | `音轨 … · 1920x1080 · 3000 kbps`，空项省略；全空则不渲染 |
| 小卡标签 | `labelMedium` 12sp / 16sp，`onSurface` α0.60；距 header 底 16dp | 文案 `最近观看` |
| 小卡行 | 高 64dp；小卡间距 12dp；距标签 8dp；左对齐（起点 = 容器左 padding 32dp） | 最多 6 个：6×104 + 5×12 = **684dp** ≤ 内容宽 1216dp |
| 入口提示 | `← 频道列表　·　→ 设置`，`labelMedium` 12sp/16，`onSurfaceVariant` α0.45，**不可聚焦** | 有最近观看条时与「最近观看」标签**同排右对齐**（不增高）；无条时单独一行右对齐（+16dp 高，见高度表）。captain 要求的入口补偿（§12 cap·R1） |
| 小卡 | 104dp × 64dp；内部：台标 40×40、间距 4dp、台名 `bodySmall` 12sp/16sp 单行 ellipsis、水平内边距 4dp | 台标居中偏上，台名在下 |
| 高度（live，EPG + 最近观看） | 24 + 121 + 16 + 16 + 8 + 64 + 24 = **273dp**（≈410px，屏高 38%） | 不遮挡视频中心区域；无 scrim |
| 高度（live，无 EPG / 无技术行） | **212dp** | header 由文字列决定（20+40） |
| 高度（无最近观看，仅入口提示） | 273 − 104 + 16 = **185dp**（有 EPG） / **124dp**（无 EPG） | 入口提示单占一行；仍接近旧版 98dp |
| 高度（回看 + 进度行） | 24 + (20+12+4) + 121 + 24 = **205dp**（无最近观看条，D-C4） | 回看进度行沿用现有 `CatchupProgressRow` |
| 快进/快退浮层 | 沿用现有 `isSeekOverlayVisible`：显示卡片、**隐藏最近观看条**、不可聚焦 | 1500ms 由现有逻辑控制，不改 |

> 「最大 96dp」的落地：台标盒子固定 96×60dp（96 为最大边），素材按原比例放入、不变形；无素材时用现有 `ChannelLogo` 占位（首字 + `surfaceVariant` 底），不额外画边框。占位字号沿用 `titleMedium`（如需更大可给 `ChannelLogo` 加可选参数，P2，不阻塞本轮）。

---

## 3. 视觉 token

| Token | 值 | 用途 |
|---|---|---|
| `card.bg` | `colorScheme.surface` **α0.92**（#1E1E1E） | 卡片底；视频仍透出 8% |
| `card.text` | `colorScheme.onSurface`（#FFFFFF） | 台名 |
| `card.text2` | `colorScheme.onSurfaceVariant`（M3 深色默认 #CAC4D0） | 频道号、标签、技术行 |
| `card.accent` | `colorScheme.primary`（#BDBDBD） | 当前节目、进度填充（**不是品牌蓝**） |
| `card.focus` | `colorScheme.surfaceVariant`（M3 深色默认 #49454F） | 小卡焦点底（**唯一**焦点表达） |
| `card.radius` | **0dp** | 容器、进度、小卡焦点底 |
| `card.border` | **无**（不允许 `BorderStroke`/`border`/`Divider`） | 分隔只靠留白与 α |
| `card.shadow` | **无**（不允许 `shadow`/`elevation`/`blur`/`graphicsLayer`） | 弱机性能 |
| `type.name` | `headlineLarge` 32/40 | 台名 |
| `type.number` | `titleSmall` 14/20 | 频道号 |
| `type.program` | `titleLarge` 22/28 | 当前节目 |
| `type.tech` / `type.label` | `labelMedium` 12/16 | 技术行 / 最近观看标签 |
| `type.cardTitle` | `bodySmall` 12/16 | 小卡台名 |
| `space` | 4 / 6 / 8 / 12 / 16 / 24 / 32 | 见 §2.1 |
| `motion.in` | `slideInVertically(tween(160), { it })` | 卡片入场（现状） |
| `motion.out` | `slideOutVertically(tween(120), { it })` | 卡片退场（现状） |
| `motion.focus` | **无**（焦点只换底色/文字色，不改尺寸） | 避免 remeasure |

### 3.1 焦点态（小卡）

| 状态 | 背景 | 台名颜色 | 尺寸 | 边框 |
|---|---|---|---|---|
| 未聚焦 | 透明 | `onSurfaceVariant`（α1.0） | 104×64 | 无 |
| 聚焦 | `surfaceVariant`，**0dp** | `onSurface` | 104×64（不变） | **无**（不加描边/外发光/缩放） |
| 按下（OK） | 保持聚焦底；切换后由新状态接管 | — | 不变 | 无 |

不允许：蓝色描边、`#4EA8FF`、`border(2.dp, …)`、`FocusRequester` 导致的 padding 跳变、焦点时放大 1.02。

---

## 4. 状态机与按键

### 4.1 状态

`InfoCardState = HIDDEN | IDLE | FOCUSED`（可聚焦条存在与否由策略判定）。

```
                   OK（习惯=显示信息卡）/ 换台 / 上一台 /
                   数字跳台 / 音轨切换 / 回看 UP/DOWN
      ┌─────────┐ ───────────────────────────────────────► ┌────────┐
      │ HIDDEN  │                                          │  IDLE  │◄─ BACK(聚焦中) / UP / DOWN
      └─────────┘ ◄──── 3000ms 计时到 / BACK / 打开任一覆盖层 ─┤        │
           ▲                                                └────────┘
           │ 打开抽屉/设置/线路/音轨/最近观看对话框                  │ OK（条非空）
           └──────────────────────────────────────────────────────┤
                                                                  ▼
                                                            ┌──────────┐
                                                            │ FOCUSED  │
                                                            │ index=0..5│
                                                            └──────────┘
                                               LEFT/RIGHT：移动（clamp，不环绕）
                                               OK：切台 → 仍 FOCUSED，index=0，计时关闭
                                               UP/DOWN/BACK：→ IDLE，重新 3000ms
                                               外部换台：→ IDLE（不保留焦点）
```

计时规则：
- `autoHideMs(IDLE) = 3000`；`autoHideMs(FOCUSED) = null`（不隐藏）；`HIDDEN` 不运行。
- 计时在以下时刻**重新开始**：进入 IDLE、换台刷新内容、从 FOCUSED 退回 IDLE。
- 快进/快退浮层沿用现有 1500ms 独立计时，不受本规则影响（`PlayerActivity.kt:856-862`）。

### 4.2 按键矩阵（无其他覆盖层时）

| 状态 | OK | LEFT / RIGHT | UP / DOWN | BACK | 数字键 / 166-167 / 上一台 |
|---|---|---|---|---|---|
| HIDDEN | 按习惯：显示信息卡（默认）或打开频道列表 | 左开频道列表 / 右开设置（现状） | 换台（现状） | 播放器既有逻辑 | 现状 |
| IDLE | **若最近观看条非空 → FOCUSED(index 0)**；否则重新计时（无操作） | 左开频道列表 / 右开设置（**不被卡片拦截**） | 换台 + 卡片刷新（现状） | 关卡片 → HIDDEN | 各自现状；卡片按新频道刷新 |
| FOCUSED | **切到当前小卡频道** → 仍 FOCUSED（index→0，内容刷新） | 在 0..n-1 内移动（首/尾 clamp，不环绕） | **退聚焦 → IDLE**（不换台） | **退聚焦 → IDLE**（不关卡片） | 数字/166-167/上一台仍由 Activity 处理 → 换台后卡片 → IDLE |

实现约束（给 t4，`PlayerActivity.kt`）：
0. **遮罩优先（captain 硬性要求，不得回归）**：任何覆盖层打开时 OK 一律交覆盖层处理——`RemoteHabits.onOk(overlayOpen = true)` 必须返回 `NONE`（`RemoteHabits.kt:79-80`），Activity 现有 `isDrawerOpen / isSettingsDrawerOpen / isQualityDialogOpen / isRecentChannelsOpen / isAudioDialogOpen` 守卫先行；实现时**不得**因为新默认把 `SHOW_INFO` 提到守卫之前。卡片 FOCUSED 时 OK=切台（由 Compose 节点消费），同样不经过习惯判定。
1. 新状态替代 `infoBannerOpen: Boolean`（`PlayerActivity.kt:552`）：`infoCardState` + `infoFocusIndex`；对 Activity 暴露 `onInfoBannerOpenChanged(state != HIDDEN)`，保持现有 `isInfoBannerOpen` 语义（`PlayerActivity.kt:120-121`、`208-209`）。
2. 新增 `isInfoCardFocused` 镜像（`setInfoCardFocusController` + `onInfoCardFocusedChanged`）。`dispatchKeyEvent` 在**最前面**判断：`isInfoCardFocused && keyCode in {DPAD_UP/DOWN/LEFT/RIGHT/CENTER, ENTER, NUMPAD_ENTER, BACK}` → `return super.dispatchKeyEvent(event)`（让 Compose 焦点节点消费）；其余键（数字、166/167、上一台、音量、MENU）保持既有分支。
3. 卡片容器加 `onPreviewKeyEvent`（仅 FOCUSED 时处理，其他状态 `false`）：LEFT/RIGHT → 移动；CENTER/ENTER → 切台；UP/DOWN/BACK → 退聚焦，返回 `true`。
4. 状态变化同步 Activity：BACK 在 FOCUSED 被 Compose 消费后，Activity 不得再走“关卡片/退出”分支（顺序：`dispatchKeyEvent` 最前判断即可）。
5. 打开任一覆盖层（`drawerOpen` / `settingsDrawerOpen` / `qualityDialogOpen` / `recentChannelsOpen` / `audioDialogOpen`）→ 状态置 `HIDDEN`（`LaunchedEffect` 监听），避免卡片压在抽屉/对话框下面。**MENU 键不在跳过集合内**：聚焦中按 MENU 仍由 Activity 打开设置，卡片随即 `HIDDEN`，可聚焦节点随组合移除、焦点自然释放。
6. 换台路径（`setOnChannelStep` / `setOnNumberJump` / `playLastChannel` / 音轨切换）把 `infoBannerOpen = true` 改为 `state = IDLE`（等价，但会重新计时）。

### 4.3 遥控习惯兼容矩阵

| 「OK键」设置 | 卡片 HIDDEN 时 OK | IDLE 时 OK | 最近观看快跳入口 |
|---|---|---|---|
| **显示信息卡（新默认，D-C1）** | 出卡（IDLE） | 聚焦最近观看（有条时） | **卡片二段式 OK** |
| 打开频道列表（旧默认/用户选择） | 打开频道列表；卡片保持/隐藏 | 打开频道列表（现状） | 设置 → 数据与更新 → 最近观看（旧路径） |
| 回看中（与设置无关） | 出卡 | 同左（不聚焦，D-C4） | —（回看不提供快跳） |

LEFT / RIGHT / UP / DOWN 的播放器语义在任何设置下都不变（除 FOCUSED 期间由卡片接管）。

---

## 5. 最近观看小卡规则

### 5.1 数据与条数

- 数据源：`RecentChannels.resolvedDistinct(recentEntries, channelGroups)`（`RecentChannels.kt:176-179`，按 group 去重、按存储 10 条上限、解析不到的行跳过）。
- **过滤**：排除当前播放 group（`group.key == currentGroupKey`），再 `take(6)`（`InfoCardPolicy.MAX_RECENT = 6`）。
- 顺序：最新在前（存储顺序即最近顺序）。
- 空态：过滤后为空 → **整条不渲染**（不显示“暂无最近观看”，不占高度）。
- 存储层不变：仍最多 10 条（`RecentChannels.MAX`）；卡片只是视图层截断到 6。设置里的「最近观看」对话框仍显示完整 ≤10 条。
- 回看中（catchup）与快进/快退浮层中：不渲染该条（D-C4）。
- 命中缓存：台标复用 `LogoLoader` 内存/磁盘缓存；`40×40dp` 在 240dpi 下为 60×60px（约 14KB/张，≤6 张 ≈ 86KB，且与抽屉 64×40 缓存键不同，属可接受的小图增量）。

### 5.2 切台行为

- 小卡 OK → 复用现有解析结果（group key → `groupIndex`，url → `variantIndex`，同 `PlayerActivity.kt:1740-1752` 的逻辑），调用 `playGroupVariant(groupIndex, variantIndex, armZap = true)`；**抽成一个 `switchToRecent(group, channel)` 供对话框与卡片共用**，避免两套解析。
- 切台后：`state` 保持 `FOCUSED`，`infoFocusIndex = 0`；`recentEntries` 由既有 `recentCache.record` 更新 → 卡片刷新（新台进入大标题、旧台回到小卡首位）。
- 由于排除当前台，连续 OK 即在「刚离开的台 ↔ 刚切到的台」之间反复切换（A↔B），与 TiviMate 的快速回跳体验一致；这是**预期行为**，不是 bug。
- 切台失败（目标已不在列表/URL 失效）：`resolvedDistinct` 已过滤 → 该小卡不会出现；运行中失败走既有播放失败面板，卡片不额外处理。

### 5.3 台名与频道号

- 小卡台名：`group.displayTitle`（与抽屉一致），`bodySmall` 12sp 单行 ellipsis（约 8 个汉字）。
- 无障碍 / 自动化：小卡 `contentDescription = "最近观看第${i+1}个 ${number}. ${title}"`；卡片整体 `contentDescription = "频道信息 ${number} ${title}"`。

---

## 6. 动效与性能预算

| 项 | 要求 |
|---|---|
| 组合时机 | 卡片内容只在 `visible` 时组合（沿用 `AnimatedVisibility`），HIDDEN 时零节点、零计时、零 IO |
| 最近观看列表 | 普通 `Row` + 6 个 `Column`（≤6，不用 `LazyRow`，无回收抖动）；不 `animateContentSize` |
| 焦点移动 | 只改背景/文字色（`background(color)` 纯色，不传 shape）；不改 padding/size → 无 remeasure |
| 重绘 | 无 `shadow`/`elevation`/`blur`/`clip`/`graphicsLayer`；无渐变、无边框；单层矩形填充 |
| 进度刷新 | 沿用 `nowProgramByUrl` 30s tick；进度条宽度变化不触发其他节点重组（`progress` 作为独立参数） |
| 台标 | 96×60（≈144×90px，ARGB 约 51KB/张）与 40×40（60×60px，约 14KB/张）；只在卡片可见时请求；无同步解码 |
| 打开成本 | 一次卡片打开 = 1 次布局 + ≤7 次图片缓存查找（无网络、无磁盘主线程 IO）；不得引入 `StrictMode` 违规 |
| jank 目标 | 换台后卡片出现的 `gfxinfo` 掉帧不得高于改造前（t6 干净窗口基线）；卡片打开不得产生 >16ms 的主线程 IO |
| PSS 目标 | 相对旧版增量 ≤ 2MB（小卡位图 + 文本节点）；不缓存整卡位图 |
| 动画 | 入场 160ms / 退场 120ms（现状），不新增透明度/缩放动画；系统关闭动画时直接显示 |

---

## 7. 可测试契约（t4 必须交付）

### 7.1 纯逻辑策略（新文件，Android-free，JVM 可测）

`android/app/src/tivimini/java/com/cjlhll/iptv/InfoCardPolicy.kt`：

```kotlin
object InfoCardPolicy {
    const val MAX_RECENT = 6
    const val AUTO_HIDE_MS = 3000L

    enum class State { HIDDEN, IDLE, FOCUSED }
    enum class Action {
        NONE, SHOW, FOCUS_IN, MOVE_LEFT, MOVE_RIGHT, SWITCH, UNFOCUS, HIDE, TIMER_EXPIRED,
    }

    /** 卡片可见性：HIDDEN 但快进/快退浮层出现时仍显示。 */
    fun isVisible(state: State, seekOverlayVisible: Boolean): Boolean

    /** null = 不自动隐藏。 */
    fun autoHideMs(state: State): Long?

    /** OK 在 IDLE 是否进入聚焦（条为空 / 回看 / 快进中 → false，只重置计时）。 */
    fun canFocusRecent(recentCount: Int, catchup: Boolean, seekOverlayVisible: Boolean): Boolean

    fun nextFocusIndex(current: Int, count: Int, delta: Int): Int  // clamp 0..count-1

    /** 是否渲染最近观看条（条空 / 回看 / 快进中 → false）。 */
    fun showRecentRow(recentCount: Int, catchup: Boolean, seekOverlayVisible: Boolean): Boolean

    /** 外部换台（数字/166-167/上一台/抽屉选台）→ 丢弃焦点。 */
    fun onExternalZap(state: State): State
}
```

### 7.2 JVM 测试清单

| 测试类 | 断言 |
|---|---|
| `InfoCardPolicyTest` | `canFocusRecent`：0 条 false；回看 false；快进中 false；正常 true。`nextFocusIndex`：0 左 → 0；5 右（count=6）→ 5；中间 ±1 正确；count=0 → 0。`autoHideMs`：IDLE=3000、FOCUSED=null、HIDDEN=null。`isVisible`：IDLE/FOCUSED true；HIDDEN+seek true；HIDDEN 否则 false。`onExternalZap(FOCUSED)=IDLE`。`showRecentRow` 与 `canFocusRecent` 的回看/快进分支一致 |
| `InfoCardRecentTest` | 给定 10 条 recent（含当前台、含解析不到的行、含 legacy `url:` 行）→ 卡片条目 = 排除当前、去重、≤6、顺序 = 最近优先；当前台只出现 0 次；空列表 → 空 |
| `InfoCardSwitchTest`（可纯逻辑化） | 切台后 index 归 0；两次连续 OK 在 A/B 间交替（用 mock 的 recent 顺序推演） |
| 既有不回归 | `RecentChannelsTest` / `RecentChannelsResolveTest` / `RecentChannelsCacheTest` / `RemoteHabitsTest`（默认值变更随 D-C1 更新）/ `TracksFlatteningTest` 全绿 |

### 7.3 testTag 契约（uiautomator 用，容器开 `testTagsAsResourceId`）

| tag | 节点 |
|---|---|
| `info_card` | 卡片根（`contentDescription` = 频道信息） |
| `info_card_logo` | 96dp 台标 |
| `info_card_number` | 频道号 overline |
| `info_card_title` | 大台名 |
| `info_card_program` | 当前节目 |
| `info_card_progress` | 进度条 |
| `info_card_tech` | 技术参数行 |
| `info_card_recent_label` | 「最近观看」标签 |
| `info_card_recent_row` | 小卡行容器 |
| `info_card_recent_{0..5}` | 每个小卡（`contentDescription` 见 §5.3） |
| `info_card_hint` | 入口提示文本（`← 频道列表　·　→ 设置`，不可聚焦，见 §2.1/§12） |

另加日志（便于真机断言，沿用 `IQTV-RECENT` 风格）：
- `I/IQTV-INFOCARD: show state=IDLE recent=3`（每次进入 IDLE）
- `I/IQTV-INFOCARD: focus index=0` / `move index=2` / `switch idx=1 groupKey=…` / `hide reason=back|timer|overlay`
- 只在 `setprop log.tag.IQTV-INFOCARD DEBUG` 时输出（与 `IQTV-ZAP` 一致，避免默认刷日志）。

### 7.4 静态门禁 `scripts/check_info_card.py`（t4 交付）

1. `ChannelInfoBanner.kt`：存在 `96.dp` 台标与 `60.dp` 高度；存在 `headlineLarge`；存在 `40.dp` 小卡台标；存在 `titleLarge`。
2. `ChannelInfoBanner.kt`：无 `border(` / `BorderStroke` / `shadow` / `elevation` / `RoundedCornerShape(` 参数 > 2.dp / `blur`。
3. `InfoCardPolicy.kt`：`MAX_RECENT = 6`、`AUTO_HIDE_MS = 3000`。
4. `ChannelInfoBanner.kt` 或调用点出现 `RecentChannels`/`InfoCardPolicy` 引用；`info_card` 系列 tag 全在。
5. `PlayerActivity.kt`：`isInfoCardFocused` 守卫存在（`setInfoCardFocusController`），且不再有硬编码 `delay(5000)` 的旧信息条计时。
6. 入口提示：`ChannelInfoBanner.kt` 含 `← 频道列表` 文案、节点不可聚焦（无 `focusable`/`clickable`），`info_card_hint` tag 存在；设置抽屉一级页脚提示存在（见 `SETTINGS_IA_V2.md` §12 cap·R1）。

---

## 8. 验收清单

### 8.1 源码/静态（t4 自证）

- [ ] 台标 96×60、台名 `headlineLarge`、节目 `titleLarge`、技术行单行 `labelMedium`；频道号 overline。
- [ ] 最近观看条：≤6、排除当前台、40dp 台标 + 12sp 台名、左右聚焦、OK 切台、空态整条不渲染。
- [ ] 卡片：0dp、无 border/shadow/elevation/blur、焦点仅 `surfaceVariant`；未聚焦时不影响 LEFT/RIGHT/UP/DOWN/OK 原语义。
- [ ] 状态机三态 + 3000ms / 聚焦不隐藏 / BACK 先退聚焦；覆盖层打开即隐藏。
- [ ] 回看：进度行保留、最近观看条隐藏、不可聚焦。
- [ ] 零拷贝播放路径未改；`isInfoBannerOpen` 对 Activity 语义保持。
- [ ] `check_info_card.py` 通过；JVM 测试（§7.2）全绿；体积 ≤30MB。
- [ ] 入口提示 `← 频道列表　·　→ 设置` 可见、不可聚焦（`info_card_hint`）；OK 默认=显示信息卡时，任何覆盖层打开时 OK 仍走覆盖层（遮罩优先回归项）。

### 8.2 真机（t9 用）

| # | 步骤 | 期望 |
|---|---|---|
| 1 | 播放中按 OK（默认习惯=显示信息卡） | 卡片滑入；96dp 台标 + 32sp 台名；中部节目/进度/音轨/分辨率正确；无蓝框/边框 |
| 2 | 不操作 3s | 卡片自动滑出（≈3000ms，误差 ≤400ms） |
| 3 | 卡片出现后按 OK | 最近观看条第一张小卡获得焦点（`surfaceVariant` 底，台名变白） |
| 4 | LEFT / RIGHT | 焦点在 ≤6 张小卡间移动，首尾不越界；不触发开列表/开设置 |
| 5 | 再按 OK | 切到该频道；卡片不消失、内容刷新为大标题新台；最近观看条第一张 = 刚离开的台 |
| 6 | 连按 OK ×2 | 在两个频道间来回切换（A↔B），每次均有换台反馈 |
| 7 | 聚焦中按 UP / DOWN / BACK | 只退聚焦，卡片仍在；再按 BACK 才关卡片 |
| 8 | 聚焦中等待 10s | 卡片**不**自动隐藏 |
| 9 | 卡片 IDLE 时按 LEFT / RIGHT | 正常开频道列表 / 设置（不被卡片拦截） |
| 10 | 换台（UP/DOWN/数字/166-167） | 卡片刷新并按新内容重新计时；不残留旧台信息 |
| 11 | 回看节目 | 显示起止时间+进度行；无最近观看条；OK 不出焦点 |
| 12 | 快进/快退（回看中 LEFT/RIGHT） | 浮层 1.5s 自动收；不出现最近观看条 |
| 13 | 卡片显示中打开设置/抽屉/音轨/线路 | 卡片立即隐藏，不与覆盖层叠层 |
| 14 | 台标缺失的频道 | 占位显示正常，不崩、不空白 |
| 15 | 小区块 jank | 换台后卡片出现不掉帧（对比 t6 基线）；PSS 增量 ≤2MB |
| 16 | 卡片入口提示 | 可见 `← 频道列表　·　→ 设置`；UP/DOWN/LEFT/RIGHT 不会聚焦到提示文本；默认 OK=显示信息卡时 LEFT 仍开频道列表、RIGHT 仍开设置 |
| 17 | 遮罩优先 | 打开抽屉 / 设置 / 线路 / 音轨 / 最近观看后再按 OK | 只由该覆盖层处理，**绝不**额外弹出信息卡；关闭覆盖层后按 OK 才出卡 |

### 8.3 自动化路径（uiautomator / adb）

```text
出卡：      adb shell input keyevent 23            # OK（默认=显示信息卡）
查卡：      adb shell uiautomator dump → 含 info_card / info_card_title
聚焦：      keyevent 23 再按一次 → dump 含 info_card_recent_0 且 focused=true
移动：      keyevent 22 / 21（RIGHT/LEFT）
切台：      keyevent 23 → logcat 见 IQTV-INFOCARD switch idx=…；播放器 session 变化
退聚焦：    keyevent 19/20/4（UP/DOWN/BACK）
隐藏：      等待 >3s → dump 不再含 info_card
```

---

## 9. 与旧版逐项对照（不回归清单）

| 旧行为（`b949a41`） | 是否保留 | 说明 |
|---|---|---|
| 换台/上一台/数字跳台/音轨切换后自动出卡 | ✅ | 计时 5s → 3s |
| 回看 UP/DOWN 出卡、LEFT/RIGHT 快进快退、浮层 1.5s | ✅ | 快进时隐藏最近观看条 |
| 回看进度行（起止时间 + 进度） | ✅ | `CatchupProgressRow` 不动 |
| 频道号 `"$number. $title"` | ⚠️ 改为 overline | 台名不再带前缀；文本断言改用 `info_card_number` / `info_card_title` |
| 右侧竖排技术参数（分辨率/码率/codec/ch） | ✅ 合并为一行 | 信息不删，只是换布局；`audioLabel` 存在时 codec 省略（同现状条件） |
| 音轨 `音轨 …` 行 | ✅ | 技术行首段 |
| 关闭卡片：BACK | ✅ | 未聚焦时一次 BACK 关；聚焦时先退聚焦 |
| 卡片不抢焦点、不拦截按键 | ✅ | 仅 FOCUSED 时接管 D-pad/OK/BACK |
| 无圆角/无边框/无蓝框 | ✅ | 0dp + 纯色焦点底 |
| 零拷贝 SurfaceView、播放/重建/看门狗逻辑 | ✅ 未触碰 | 只改 Compose 覆盖层与 Activity 按键守卫 |
| OK 默认打开频道列表 | ❌ 默认值变更（D-C1） | 可一行回退；LEFT 仍一键开列表 |

---

## 10. 风险与回退

| 风险 | 缓解 |
|---|---|
| 二段式 OK 不被理解（按 OK 只出卡，再按才聚焦） | 卡片出现即显示「最近观看」标签与卡片，视觉上可发现；第一阶段 OK 不会误切台；如需更直接，可改为 IDLE + RIGHT 聚焦（但会抢设置键）——本轮不采用 |
| 聚焦后 LEFT/RIGHT 不再开列表/设置，用户误以为卡死 | BACK/UP/DOWN 立即退聚焦；标签/焦点底色可见；真机验收 §8.2 #4/#7 |
| 默认 OK 变更与旧验收断言冲突 | `RemoteHabitsTest` 同批更新；t9 按 §8.3 新路径断言；回退一行 |
| 96dp 台标解码开销/PSS | 单张 144×90px；只在卡片可见时加载；PSS 目标 +2MB；无主线程 IO |
| 换台后卡片内容一帧旧值 | 卡片数据全部来自 `currentGroupIndex/currentVariantIndex` 派生值，随换台同帧重组；`info_card_title` 断言可复查 |
| 回看中误触跳直播 | D-C4：回看不渲染/不可聚焦最近观看条 |
| 信息卡与设置/抽屉叠层 | 覆盖层打开即 `HIDDEN`（§4.2-5） |

回退：改动集中在 `ChannelInfoBanner.kt` + 新增 `InfoCardPolicy.kt` + `PlayerActivity.kt` 卡片状态/守卫；`RemoteHabits.kt` 只改默认值与一个 label。revert 三个文件即可回到旧信息条。

---

## 11. 版本关系与跨任务写作用域

- 视觉基线：[`UI_VISUAL_SPEC_TIVIMATE.md`](UI_VISUAL_SPEC_TIVIMATE.md)（0dp / 无边框 / `surfaceVariant` 焦点 / 原版字号层级）。本卡在其上把台名提升到 `headlineLarge`、台标提升到 96×60、新增最近观看条；**不引入品牌蓝、不加边框、不加阴影**。
- TiviMate 参照：[`TIVIMATE-UI-REFERENCE.md`](TIVIMATE-UI-REFERENCE.md)（底部浮层、信息优先、按需浮出）。
- 最近观看语义：[`RECENT_CHANNELS_LAST3.md`](RECENT_CHANNELS_LAST3.md)、`RecentChannels.kt`（group key 去重、≤10、最新在前、清空持久化）。
- 跨任务写作用域：`RemoteHabits.kt`（默认值 + label）归 **t4**；`SettingsDrawer.kt`/`SettingsIA.kt` 归 **t3**；`PlayerActivity.kt` 两侧都会触碰（t3 只动设置回调与设置状态，t4 只动信息卡状态与按键守卫）——实现时按行区分，避免互相覆盖。
- 非目标：不做“下一页节目”“节目时间区间”（需要 `NowProgramUi` 扩展 start/end，属 P2，本轮不要求）；不做卡片内音量/字幕入口；不做鼠标/触摸拖拽；不做卡片自定义位置/尺寸设置。

---

## 12. 决定记录（captain 批准 · 2026-11-21）

> **编号说明（先读）**：提交评审时我用的是「D-S3 = 一级默认焦点」「D-C1 = OK 键默认」；captain 批复把两者编号对调（D-S3 = OK 键、D-C1 = 一级焦点），**批准内容一致**。以下按内容定名 **cap·R1 / cap·R2**，与 `SETTINGS_IA_V2.md` §12 同源，t3/t4 以本节为准则。

### cap·R1（OK 键默认 = 显示信息卡 · 本文件原 D-C1，captain 记作 D-S3）

- **结论：批准。**
- **生效范围**：仅当**从未写入** `remote_ok` 偏好时使用新默认（新装 / 清数据 / 从未改过该项）；已存 `CONFIRM` / `OPEN_DRAWER` 原样保留，**不静默改老用户显式选择**。
- 实现：保留枚举名 `CONFIRM`（偏好兼容）；label `确认` → `显示信息卡`；`RemoteHabits.DEFAULT.ok = Ok.CONFIRM`；`onOk` 映射不变（`CONFIRM` → `SHOW_INFO`，回看 → `SHOW_INFO`）。
- **入口提示两处都要写（captain 明确要求）**：
  1. 信息卡：`← 频道列表　·　→ 设置`（§2.1；tag `info_card_hint`）；有条时与「最近观看」标签同排右对齐，无条时单独一行右对齐（+16dp，见高度表）。
  2. 设置抽屉：一级页脚 `关闭后按 ← 打开频道列表`；「遥控器与操作」页帮助 `频道列表：← 　设置：→`（`SETTINGS_IA_V2.md` §3.1 / §3.3 / §6.2）。
- **遮罩优先（硬性、不得回归）**：任一覆盖层打开时 OK 一律交覆盖层处理（`RemoteHabits.onOk(overlayOpen = true) = NONE`）；实现**不得**把 `SHOW_INFO` 提到 Activity 的 `isDrawerOpen / isSettingsDrawerOpen / isQualityDialogOpen / isRecentChannelsOpen / isAudioDialogOpen` 守卫之前；卡片 FOCUSED 时 OK = 切台，同样不经习惯判定。
- 同步更新 `RemoteHabitsTest` 的默认值 + label 断言；`docs/REMOTE_HABITS_SETTINGS*.md` 属历史记录，不改写，以本节为准。
- **一行回退**：`RemoteHabits.DEFAULT.ok = Ok.OPEN_DRAWER`（枚举名不变，Prefs 无需迁移；卡片仍会在换台后自动出现）。

### cap·R2（一级默认焦点 = 播放与画面 · 本文件原 D-S3，captain 记作 D-C1）

- **结论：批准。**
- 一级首次打开焦点 = 第 1 行「播放与画面」；会话内记忆上次停留行（不持久化）。
- 「观看记录（最近观看）」迁至「数据与更新 → 观看记录」，但**本卡底部最近观看快跳必须保留**（§5：≤6、排除当前台、40dp 台标、左右聚焦、OK 切台、连按 A↔B）。
- **一行回退**：一级初始焦点改回按 tag 定位的旧默认路径（不建议，会与“最近观看已迁走”冲突）。
