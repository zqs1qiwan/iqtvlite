# 信息卡 v3（INFO_CARD_V3）—— 台标/台名双大位同行 + 右侧节目 + 右上徽标

> 版本 1.0 · 2026-11-21 · designer（任务 t23）· 代码基线：`HEAD=0ad7c41`
> 规格：[`INFO_CARD_V2.md`](INFO_CARD_V2.md)（v2 行为/三态/焦点契约继续有效）、接线基础：[`INFO_CARD_V2_WIRING.md`](INFO_CARD_V2_WIRING.md)
> 本文只写规格与改动清单，**不改代码、不跑设备**。真机验收由 t9 按 §12 执行。
> 目标机：TR100-G9 / API28 / 1080p / density 240 → **逻辑屏 1280×720dp**（1dp = 1.5px）。

---

## 0. 一句话

**台标与台名是两个“大位”，同一行垂直居中（台标 160×96 或 180×108；台名 headlineLarge 34–36sp）；台名/台标右侧的剩余空间放当前节目（单行 ellipsis + 起止/剩余 + 3dp 进度）；右上角一行 4 个横向徽标（分辨率档/帧率/码率/声道）；其下一条小字技术行（视频编码 · 音频编码 · SDR/HDR）；底部最近观看快跳与入口提示原样保留。全部单行、固定行高、不换行、无 border/shadow，高度 ≤300dp。**

用户原话对应：logo/台名两个大位同一行 ✔；右侧空白展示当前节目 ✔；右上角横向徽标（码率/声道/分辨率档，追加帧率）✔；不换行/少空白 ✔；logo 更大（160×96 起，是 v2 的 ~2.7× 面积）✔。

---

## 1. 与 v2 的差异 / 不变量

| 项 | v2（当前实现） | v3 |
|---|---|---|
| 台标 | 96×60dp（`ChannelInfoBanner.kt:191-199`） | **160×96dp（默认）** 或 **180×108dp（大屏档）** |
| 台名 | `headlineLarge` 32sp，台名下方是当前节目 + 技术行 | `headlineLarge` **34sp（A 档）/36sp（B 档）**，与台标同一行垂直居中；台名下方仅一行小字 `频道 N · 分组 [· 音频编码]` |
| 当前节目 | 台名下方（`ChannelInfoBanner.kt:224-241`），与技术行同列 | **同一行右侧**：节目名 `titleLarge`（单行 ellipsis）+ 起止/剩余 `labelMedium` + 3dp 进度 |
| 技术参数 | 中部一行 `音轨 · 分辨率 · 码率`（`243-253`） | **右上角 4 徽标**（分辨率档·帧率·码率·声道）+ **下方单独技术行**（视频编码·音频编码·SDR/HDR） |
| 回看进度行 | 顶部独立 `CatchupProgressRow`（`172-178`） | **并入右侧节目行**（同一行的时间 + 3dp 进度；不重复占一行，见 §7） |
| 最近观看/入口提示/三态/焦点 | 保留 | **原样保留**（≤6、40dp、12sp、OK 切台、A↔B、surfaceVariant 焦点、3s/聚焦不隐藏/遮罩优先） |
| 红线 | 0dp/无 border/shadow/blur | 不变；徽标用**纯文字 + `·` 分隔**（0dp，不用浅底胶囊，避免与 Flat 冲突；若实现选 6dp 浅底，仅限 `surfaceVariant α0.30` 且无描边） |

**不变量**：`InfoCardPolicy` 的 HIDDEN/IDLE/FOCUSED、`focusRecoveryAction` / `aggregateNodeFocus` / `shouldClearFocusOnTransition`、最近观看数据规则、`switchToRecent`、日志 tag、z-order（底部浮层）全部不动。

---

## 2. 精确布局栅格（DP）

### 2.1 结构（ASCII）

```
┌────────────────────────────────────────────────────────────────────────────────────┐ ← surface α0.92 / 0dp / 无 border
│ padding: start/end 40, top 20                                                        │
│                                              (24)  FHD · 60fps · 8.3Mbps · 2ch     │ ← 徽标行 h=24，右对齐，不换行
│ (6)                                                                                 │
│ ┌──────────────┐   (24)  ┌───────────────────────┐  (28)  ┌──────────────────────┐ │
│ │              │         │ CCTV-1 综合            │        │ 新闻联播              │ │ ← 台名 34/36sp（weight 0.44）
│ │   LOGO       │         │ headlineLarge 34/36sp  │        │ titleLarge 22sp       │ │    节目 titleLarge（weight 0.56）
│ │ 160×96 /     │         │ 频道 12 · 央视频道      │        │ 20:00–21:30 · 剩余42分钟│ │
│ │ 180×108      │         │ bodySmall 12sp         │        │ ▬▬▬▬▬▬▬░ 3dp         │ │
│ └──────────────┘         └───────────────────────┘        └──────────────────────┘ │
│ (4)                                                                                 │
│ H.264 · MPEG-L2 · SDR                                                               │ ← 技术行 h=16，12sp，α0.60，单行 ellipsis
│ (12)                                                                                │
│ 最近观看                                                    ← 频道列表　·　→ 设置    │ ← 标签/提示 h=16
│ (8)                                                                                 │
│ ┌────────┐ (12) ┌────────┐ (12) ┌────────┐ … ≤6                                     │ ← 104×64、40dp logo、12sp（原样保留）
│ └────────┘      └────────┘      └────────┘                                          │
│ padding bottom 20                                                                   │
└────────────────────────────────────────────────────────────────────────────────────┘
```

- **主行**：`Row(Modifier.height(96.dp 或 108.dp), verticalAlignment = CenterVertically)`；`ChannelLogo(width/height=台标档)`；`Spacer(24)`；台名列 `Modifier.weight(0.44f)`；`Spacer(28)`；节目列 `Modifier.weight(0.56f)`。
- **徽标行**：`Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.End, verticalAlignment = CenterVertically)`；其左不放置内容（「右上角」的硬约束）；所有徽标在同一 Text/Row 内**单行**。
- **技术行**：`Text(maxLines = 1, overflow = Ellipsis)`，`Modifier.fillMaxWidth().height(16.dp)`。
- **底部最近观看**：沿用 v2 `RecentStrip`（104×64、40dp logo、12sp、间距 12dp、≤6、焦点只改 `surfaceVariant` 底色）。

### 2.2 两档台标（实现二选一，建议默认 A）

| 档 | 台标 | 台名 | 主行高 | 适用 |
|---|---|---|---|---|
| **A（默认）** | **160×96dp**（240×144px @1.5） | **34sp / 42sp 行高** | 96dp | 1080p/240dpi 最稳，高度账余量 14dp |
| **B（大屏档）** | **180×108dp**（270×162px） | **36sp / 44sp 行高** | 108dp | 1280dp 屏也放得下，但高度账余量较小：选 B 时把主行下方 gap 由 4→2、最近条前 gap 由 12→10，合计 294dp（余 6dp） |

台标实现：`ChannelLogo(logoUrl, fallbackTitle, modifier = Modifier.size(width=W.dp, height=H.dp).testTag("info_card_logo"), width=W.dp, height=H.dp)`；`ContentScale` 沿用现有（不拉伸变形），无素材时用现有占位（首字 + `surfaceVariant` 底，0dp）。

### 2.3 字体/间距/颜色 token

| 元素 | 规格 |
|---|---|
| 台名 | `headlineLarge.copy(fontSize = 34.sp/36.sp, lineHeight = 42.sp/44.sp)`，`onSurface`，`maxLines=1`，`TextOverflow.Ellipsis` |
| 台名下方小字 | `bodySmall` 12sp/16sp，`onSurfaceVariant` α0.72，`maxLines=1`，ellipsis；内容 `频道 {N} · {分组}`，`audioCodec` 有则追加 ` · {codec}`；无可显示项则保留 16dp 高空白（固定行高） |
| 节目名 | `titleLarge` 22sp/28sp，`primary`（#BDBDBD），`maxLines=1`，ellipsis |
| 起止/剩余 | `labelMedium` 12sp/16sp，`onSurface` α0.72，`maxLines=1`，ellipsis |
| 进度条 | 3dp 高，轨道 `onSurface` α0.12、填充 `primary` α0.80，0dp；宽度 = 节目列宽 |
| 徽标 | 10–12sp（建议 12sp `labelMedium`；小屏可 10sp），`onSurfaceVariant` α0.72；分隔 `·` α0.35；间距 8dp；行高 24dp；无背景（纯文字） |
| 技术行 | `labelMedium` 12sp/16sp，`onSurface` α0.60，单行 ellipsis |
| 最近观看/入口提示 | **与 v2 完全一致**（labelMedium 12sp α0.60 / α0.45；小卡 bodySmall 12sp） |
| 卡片容器 | `surface` α0.92、`RoundedCornerShape(0.dp)`、padding L/R 40、top 20、bottom 20；**无 border/shadow/elevation/blur/graphicsLayer** |

### 2.4 1080p/240dpi 宽度预算（证明“4 徽标 + 台名 + 节目不挤爆”）

内容宽 = 1280 − 40×2 = **1200dp**（960dp 逻辑屏时为 **880dp**）。

| 行 | 构成 | 1280dp 屏 | 960dp 屏 |
|---|---|---|---|
| 徽标行 | `FHD`(3) + `60fps`(5) + `8.3Mbps`(7) + `2ch`(3) = 18 字符；12sp ASCII≈7dp/字 → 126dp；3 个 ` · ` ≈30dp；3×8dp gap=24dp | **≈180dp**（预算 `BADGE_MAX_WIDTH_DP=260dp`，余 80dp） | 180dp（余 80dp） |
| 主行 A | logo 160 + 24 + 台名 weight0.44×(1200−160−24−28=988)≈435 + 28 + 节目 0.56×988≈553 | **1200dp（贴合）** | 160+24+294+28+374=**880dp** |
| 主行 B | logo 180 + 24 + 台名 0.44×(1200−180−24−28=968)≈426 + 28 + 节目 542 | **1200dp** | 180+24+285+28+363=**880dp** |
| 技术行 | `H.264 · MPEG-L2 · SDR` ≈20 ASCII×7 =140dp（含溢出徽标 <300dp） | 1200dp 内绰绰有余 | 880dp 内绰绰有余 |

结论：**徽标行独立一行（不与台名/节目抢宽度），4 徽标最坏 ≈180dp ≤260dp 预算；主行用 weight 分配，1280/960 都恰好等于内容宽（0 溢出，超长台名/节目由 ellipsis 截断）**。`BADGE_MAX_WIDTH_DP` 是策略层上限（给 4 徽标+分隔留足），不是布局宽度。

---

## 3. 右上角徽标条（最多 4 个，优先级从高到低）

### 3.1 顺序与映射（写死）

| 优先级 | 徽标 | 取值函数（`InfoCardPolicy`） | 映射 / 回退 |
|---:|---|---|---|
| 1 | 分辨率档 | `resolutionTier(width, height): ResolutionTier?` | `h = if (height>0) height else width`；`h<=0 → null（隐藏）`；`h<720 → SD`；`720–1079 → HD`；`1080–2159 → FHD`；`>=2160 → 4K` |
| 2 | 帧率 | `frameRateLabel(frameRate: Float): String?` | `<=0` 或 `NaN` → null；否则 `roundToInt()` → `"${n}fps"`（29.97→30fps、59.94→60fps、23.976→24fps） |
| 3 | 码率 | `bitrateLabel(bitrate: Int): String?` | `<=0 → null`；否则 `String.format(Locale.ROOT, "%.1fMbps", bitrate/1_000_000.0)`（`Format.bitrate` 单位是 **bit/s**，不是 kbps；8_300_000→`8.3Mbps`） |
| 4 | 声道 | `channelLabel(channelCount: Int): String?` | `<=0 → null`；`1→单声道`、`2→2ch（立体声）`、`6→5.1`、`8→7.1`、其它→`${n}ch`（语义名用于无障碍 contentDescription） |

**优先级**：分辨率 > 帧率 > 码率 > 声道。**未知即隐藏**（候选列表直接跳过，不占位、不留空分隔点）。

> **口径冲突说明**：t23 原始文案写“未知显示 —”，随后 captain 追加规则 C 要求“全部未知即隐藏，不留空占位”——**本规格以 C 为准**：徽标不显示 `—`、不留空位；`dynamicRange` 是唯一例外（`colorInfo` 缺失按 captain B 默认 `SDR`）。
> 码率单位：`Format.bitrate` 为 **bit/s**，直接 `/1_000_000.0`；若实现从其它来源拿到 kbps，先 `×1000` 再换算。
> 帧率：`Format.frameRate` 为 Float；`<=0/NaN` 隐藏；常见 25/30/50/60fps；23.976→`24fps`、29.97→`30fps`、59.94→`60fps`（`roundToInt`），其它正数按取整显示。

### 3.2 超宽降级（绝不换行）

纯函数：
```kotlin
data class BadgeCandidate(val key: String, val label: String)
data class BadgePlan(val visible: List<String>, val overflow: List<String>)
fun badgeCandidates(width: Int, height: Int, frameRate: Float, bitrate: Int, channelCount: Int): List<BadgeCandidate>
fun planBadges(
    candidates: List<BadgeCandidate>,
    maxWidthDp: Double = BADGE_MAX_WIDTH_DP,   // 260.0
    charWidthDp: Double = 7.0,                 // 12sp ASCII 估算
    gapDp: Double = 8.0, dotDp: Double = 10.0,
): BadgePlan
```
- 按优先级依次放入 `visible`，累计估算宽度（`label.length*charWidthDp + (n-1)*(dotDp+gapDp)`）超过 `maxWidthDp` 的项进入 `overflow`（保持原优先级顺序）。
- **绝不换行**：`visible` 以单个 `Text(maxLines=1, overflow=Ellipsis)`（或 Row + 固定 8dp gap）渲染；`visible` 为空则不组合该行（高度自然缩短）。
- `overflow` 追加到 §4 技术行末尾（` · ` 分隔），技术行仍单行 ellipsis。

### 3.3 渲染

```kotlin
val plan = InfoCardPolicy.planBadges(InfoCardPolicy.badgeCandidates(w, h, fps, br, ch))
Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.End,
    verticalAlignment = Alignment.CenterVertically) {
    if (plan.visible.isNotEmpty()) {
        Text(plan.visible.joinToString("  ·  "),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = onSurfaceVariant.copy(alpha = 0.72f), maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("info_card_badges"))
    }
}
```
（若选项用浅底胶囊：`background(surfaceVariant.copy(alpha=0.30f), RoundedCornerShape(6.dp))` + `padding(horizontal=4.dp)`，**无描边**，每个徽标 `minWidth = 64.dp` 等宽紧凑；默认纯文字，保持 0dp Flat。）

### 3.4 技术行宽度门槛（宁缺不换行）

- 组合前估算：`techWidthDp = techLine.length * 7.0`（12sp ASCII 估算；含分隔）。
- 若 `techWidthDp > 可用内容宽`（1280dp 屏 1200dp；960dp 屏 880dp）→ **整行不组合**（宁缺，不挤压/不换行）；否则按 §4 单行 ellipsis 渲染。
- 正常内容（`H.264 · MPEG-L2 · SDR` ≈20 字符 ≈140dp；含 1–2 个溢出徽标 <300dp）远小于可用宽，不会触发隐藏。`colorInfo` 缺失时 `SDR` 是唯一“默认可显示值”，其余全未知时技术行仅 `SDR`（仍单行）。

---

## 4. 下方技术行（单行、小字、`·` 分隔、超宽 ellipsis）

| 段 | 取值函数 | 映射 / 回退 |
|---|---|---|
| 视频编码 | `videoCodecLabel(mime: String?, codecs: String?)` | `avc/avc1/video/avc → H.264`；`hevc/hvc1/hev1/video/hevc → H.265`；`av01/video/av01 → AV1`；其它 → null（隐藏） |
| 音频编码 | `audioCodecLabel(audioSummary: String?)` | 取 `AudioTrackUi.summary` 的第一段（`mpeg-L2/aac/ac-3/eac3/opus/flac/mp3`）→ `MPEG-L2/AAC/AC-3/EAC3/OPUS/FLAC/MP3`；无法识别→null |
| 动态范围 | `dynamicRangeLabel(colorTransfer: Int, hasHdrStaticInfo: Boolean)` | `colorInfo == null` 或非 HDR transfer → **`SDR`**（captain 明确“无则 SDR”，不是隐藏）；`6`（`C.COLOR_TRANSFER_ST2084`）→ `HDR10`；`7`（`C.COLOR_TRANSFER_HLG`）→ `HLG` |
| 徽标溢出 | `plan.overflow` | 追加到末尾（` · `） |

```kotlin
fun techLine(videoCodec: String?, audioCodec: String?, dynamicRange: String?, overflow: List<String>): String =
    (listOfNotNull(videoCodec, audioCodec, dynamicRange) + overflow)
        .filter { it.isNotBlank() }.joinToString("  ·  ")
```
- 全部为空（仅当动态范围也为空，正常不会）→ 不组合该行；否则单行 ellipsis，**宁缺不换行**。
- 宽度：12sp、内容宽 1200dp 可容纳 ~170 ASCII 字符，远超实际（视频编码+音频编码+SDR≈20–40 字符），不会因为 4 徽标溢出而降级不可读。

---

## 5. 中部/右侧节目行

- 节目名：`programTitleOrPlaceholder(title)`：`title?.takeIf { it.isNotBlank() } ?: "暂无节目信息"`；`titleLarge` 22sp，单行 ellipsis；占位色 `onSurfaceVariant α0.85`（区别于真实节目 `primary`）。
- 时间：`programTimeLine(startMillis, endMillis, nowMillis, catchup): String`
  - `endMillis <= 0` → `""`（整行隐藏，不占位）
  - 直播：`"HH:mm–HH:mm · 剩余 {N} 分钟"`；`remaining = ((end-now).coerceAtLeast(0))/60_000`；`0 → "<1 分钟"`。
  - 回看：`"HH:mm–HH:mm"`（不显示剩余；进度由 3dp 条表达）。
- 进度：直播用 `NowProgramUi.progress`；回看用 `catchupConfiguration.positionMs / (end-start)`（clamp 0..1）；3dp、方头。
- **三态**：直播/回看/快进浮层都使用同一节目行；回看不再单独渲染 `CatchupProgressRow`（见 §7 高度账），信息不丢失（起止/进度/标题都在本行）。
- 节目为空：标题占位 + 时间行 `""` + 进度不渲染；**保留固定行高**（主行 96/108 由台标撑起，不跳动）。

---

## 6. 底部最近观看快跳（原样保留）

- `≤6`、排除当前台、最新在前、`104×64`、`40dp` logo、`12sp` 台名、间距 12dp、普通 Row（无 LazyRow）；
- LEFT/RIGHT clamp 移动、OK 切台、切台后保持 FOCUSED / index=0 / A↔B；
- 空/回看/快进浮层时整条不渲染（`InfoCardPolicy.showRecentRow` 不变）；
- 入口提示 `← 频道列表　·　→ 设置` 保留（`info_card_hint`，不可聚焦）。

---

## 7. 高度账（目标 ≤300dp）

| 元素 | A 档（160×96） | B 档（180×108） |
|---|---:|---:|
| 容器 top padding | 20 | 20 |
| 徽标行（4 徽标） | 24 | 24 |
| gap 徽标→主行 | 6 | 6 |
| **主行（台标/台名/节目同行）** | **96** | **108** |
| gap 主行→技术行 | 4 | 2（B 档收紧） |
| 技术行（单行） | 16 | 16 |
| gap →最近条 | 12 | 10（B 档收紧） |
| 最近观看标签/提示行 | 16 | 16 |
| gap 标签→小卡 | 8 | 8 |
| 小卡行（40dp logo+12sp 名） | 64 | 64 |
| 容器 bottom padding | 20 | 20 |
| **合计（live，有最近条）** | **286dp** | **294dp**（已按 §2.2 收紧两处 gap） |
| 无最近条（仅提示行 16 + bottom 20） | **214dp** | **222dp** |
| 回看 / 快进浮层（同一节目行） | **286dp** | **294dp** |

- 均 **≤300dp**（A 档余量 14dp；B 档收紧后 294dp，余 6dp）。
- 若实现选择 B 档又不做 gap 收紧，298dp 仍 ≤300（余 2dp，字体度量波动风险高，**不建议**）。
- 高度与内容无关（节目名/台名长度不影响行高；ellipsis）；无最近条时高度自然缩短，不保留空占位。

---

## 8. 三个易错点（实现必看）

1. **不换行 / ellipsis**：台名、台名小字、节目名、时间、徽标、技术行全部 `maxLines=1` + `TextOverflow.Ellipsis`；主行/徽标行/技术行用**固定高度**（`.height(96/108.dp)`、`.height(24.dp)`、`.height(16.dp)`）；**禁止** `wrapContentHeight`、`basicMarquee`、`LazyRow`、`animateContentSize`；长名称只能截断，不能挤动同行或换行。
2. **徽标数据缺失回退**：`frameRate<=0/NaN`、`bitrate<=0`、`channelCount<=0`、`width/height<=0` 一律 null→隐藏（不显示 `—`、不占分隔点）；`resolutionTier` 未知也隐藏；`dynamicRange` 例外（null→SDR）。`badgeCandidates` 为空时徽标行不组合；单个缺失只少一个徽标，其余仍按优先级排列、间距不塌陷。
3. **节目为空**：`programTitle` 为 null/空白 → `"暂无节目信息"` 占位（单行），时间行 `""`、进度不渲染，主行高度由台标固定（不跳动）；回看且无 EPG 时显示 `"回看中"` + catchup 起止（如可用）。

---

## 9. 数据来源与接线点（精确到当前行号）

> 行号基线 `HEAD=0ad7c41`；实现前用 §9.7 的 grep 复核（t20 之后仅 perf 改动，banner 行号已核对）。

### 9.1 `Format` 数据（分辨率/帧率/码率/声道/编码/HDR）

`PlayerActivity.kt` 已有：
- `var videoFormat by remember { mutableStateOf<Format?>(null) }`（`619`）
- `Player.Listener.onVideoSizeChanged` → `videoFormat = p.videoFormat`（`825-827`）
- `Player.Listener.onTracksChanged` → `videoFormat = p.videoFormat`（`839-840`）

`Format` 字段直接用（Media3）：
`width`, `height`, `frameRate` (Float, fps), `bitrate` (Int, **bit/s**), `channelCount` (Int), `sampleMimeType`/`codecs`, `colorInfo?.colorTransfer` / `colorInfo?.hdrStaticInfo`。
→ 不需要新增回调；只需在 `ChannelInfoBanner(...)` 调用点把 `videoFormat` 继续传入（`1992` 已传）；所有取值/回退在 `InfoCardPolicy` 纯函数完成（避免 `Format` 进入纯逻辑：banner 传 `width/height/frameRate/bitrate/channelCount/colorTransfer/hasHdr` 原始值）。

### 9.2 节目起止/剩余（新增数据）

- `EpgModels.kt:156` `NowProgramUi(title, progress, nextTitle)` → **新增 `startMillis: Long = 0, endMillis: Long = 0`**（默认值保证其他使用点零改动）。
- `PlayerActivity.kt:1199-1219` 的 `buildMap`：`val current = data.nowProgram(displayChannel, nowMillis)`（`1205`）已有 `startMillis/endMillis`；构造 `NowProgramUi(title=t, progress=progress, startMillis=current?.startMillis ?: 0, endMillis=current?.endMillis ?: 0)`（`1211`）。
- `banner` 调用点（`1985-2025`）新增参数：
  ```kotlin
  val programStartMs = if (req != null) req.startMillis
      else displayChannel?.let { nowProgramByUrl[it.url]?.startMillis } ?: 0L
  val programEndMs = if (req != null) req.endMillis
      else displayChannel?.let { nowProgramByUrl[it.url]?.endMillis } ?: 0L
  val groupLabel = channelGroups.getOrNull(playingGroupIndex)?.let { ChannelGrouper.displayGroupName(it) }
  ```
  传入 `programStartMs/programEndMs/nowMillis = nowMillis/groupLabel`（`nowMillis` 已声明于 `638`，30s 刷新）。
- 回看不依赖 `NowProgramUi`：`CatchupPlayRequest`（`CatchupHelper.kt`）自带 `startMillis/endMillis`；`catchupConfig` 在 `PlayerActivity.kt:1943-1949` 构造，直接用于时间行与进度（`catchupPositionMs` 已有）。
- 现有 `programProgress`（`1969-1973`）直播保留；回看可传 `null` 让 banner 用 `catchupConfiguration` 算进度（与 v2 一致）。

### 9.3 分组小字

`ChannelGrouper.displayGroupName(group)`（`ChannelGrouper.kt:169`）→ `playingGroup`（`PlayerActivity.kt:677`）→ `groupLabel`；`channelNumber` 已有（`displayNumber`，`1975-1983`）。

### 9.4 音频编码（技术行）

`AudioTrackUi.summary(audioTracks, audioOverrideGroup, audioOverrideTrack)`（`PlayerActivity.kt:1993`）返回形如 `"mpeg-L2 · 2ch"`；`InfoCardPolicy.audioCodecLabel(summary)` 取第一段映射 `MPEG-L2/AAC/AC-3`；不新增回调。

### 9.5 新 `InfoCardPolicy` 纯函数（Android/Compose-free）

```kotlin
enum class ResolutionTier(val label: String) { SD("SD"), HD("HD"), FHD("FHD"), UHD("4K") }
data class BadgeCandidate(val key: String, val label: String)
data class BadgePlan(val visible: List<String>, val overflow: List<String>)

const val BADGE_MAX_WIDTH_DP = 260.0

fun resolutionTier(width: Int, height: Int): ResolutionTier?     // 见 §3.1
fun frameRateLabel(frameRate: Float): String?                    // 25fps/30fps/…；<=0/NaN → null
fun bitrateLabel(bitrate: Int): String?                          // 8.3Mbps；<=0 → null
fun channelLabel(channelCount: Int): String?                     // 单声道/2ch/5.1/7.1/Nch
fun badgeCandidates(width: Int, height: Int, frameRate: Float, bitrate: Int, channelCount: Int): List<BadgeCandidate>
fun planBadges(candidates: List<BadgeCandidate>, maxWidthDp: Double = BADGE_MAX_WIDTH_DP,
               charWidthDp: Double = 7.0, gapDp: Double = 8.0, dotDp: Double = 10.0): BadgePlan
fun videoCodecLabel(mime: String?, codecs: String?): String?     // H.264/H.265/AV1；未知 null
fun audioCodecLabel(audioSummary: String?): String?              // MPEG-L2/AAC/AC-3；未知 null
fun dynamicRangeLabel(colorTransfer: Int, hasHdrStaticInfo: Boolean): String  // SDR/HDR10/HLG
fun techLine(videoCodec: String?, audioCodec: String?, dynamicRange: String?, overflow: List<String>): String
fun programTitleOrPlaceholder(title: String?): String            // 暂无节目信息
fun programTimeLine(startMillis: Long, endMillis: Long, nowMillis: Long, catchup: Boolean): String
fun smallMetaLine(number: Int, group: String?, audioCodec: String?): String
```
保留 v2 既有函数（`focusRecoveryAction/aggregateNodeFocus/shouldClearFocusOnTransition/isVisible/autoHideMs/showRecentRow/canFocusRecent/nextFocusIndex/onExternalZap/cardRecentItems/ENTRY_HINT`）不动；旧 `techLine(audioLabel,width,height,bitrate,channelCount,sampleMimeType)` **替换**为上表新签名（`InfoCardPolicyTest.techLine*` 3 例改为新的编码/技术行测试）。

### 9.6 `ChannelInfoBanner` 改动点（当前行号）

| 区域 | 当前 | 改为 |
|---|---|---|
| 参数（`58-78`） | `channel/programTitle/channelNumber/programProgress/videoFormat/audioLabel/catchupConfiguration/recentCards/focusIndex/...` | 新增 `programStartMs: Long = 0`, `programEndMs: Long = 0`, `nowMillis: Long = System.currentTimeMillis()`, `groupLabel: String? = null`；其余保留 |
| 回看独立行（`171-178`） | `if (catchupConfiguration != null) CatchupProgressRow(...)` | **删除该行**（并入节目行）；`CatchupProgressRow`（`412+`）可删除或保留未用（建议删除） |
| 主行（`180-255`） | logo 96×60 + 台名/节目/技术同列 | 重写为 §2.1 主行：logo 160×96/180×108、台名列（34/36sp + 小字）、节目列（22sp + 时间 + 3dp 进度）；技术行移出 |
| 技术行（`243-253`） | `techLine` 单行 | 改为 §4 技术行（编码/音频/HDR/溢出徽标），保留 tag `info_card_tech` |
| 最近条（`257-298`） | 标签/提示/RecentStrip | 保留；只调整 padding/gaps 以匹配 §7 高度（A：主行→技术 4、技术→最近 12；B：2/10） |
| 徽标行 | 无 | 新增 `info_card_badges` 行（§3.3） |
| 小字 | `频道 N` overline（`205-214`） | 移到台名下 `info_card_number` + `info_card_group` 一行（`smallMetaLine`），固定 16dp |
| 节目 tag | `info_card_program`（`232`）、`info_card_progress`（`238`） | 保留；新增 `info_card_program_time`（时间行） |
| 入口提示/最近条/焦点 | 保留 | 不动（`EntryHint` 322-329、`RecentStrip` 337+、focus F1/F2 逻辑） |

### 9.7 t4 改动清单（照此逐项改）

- [ ] `InfoCardPolicy.kt`：新增 §9.5 全部纯函数（`BADGE_MAX_WIDTH_DP`、`resolutionTier/frameRateLabel/bitrateLabel/channelLabel/badgeCandidates/planBadges/videoCodecLabel/audioCodecLabel/dynamicRangeLabel/techLine/programTitleOrPlaceholder/programTimeLine/smallMetaLine`）；旧 `techLine(audioLabel,width,height,bitrate,channelCount,sampleMimeType)` 替换/删除（同步测试）。
- [ ] `ChannelInfoBanner.kt`：签名 +4 参数（`programStartMs/programEndMs/nowMillis/groupLabel`）；删回看独立行；主行按 §2 重写（logo 160×96|180×108、台名 34/36sp、节目列）；新增徽标行 `info_card_badges`、技术行 `info_card_tech`、小字 `info_card_number`+`info_card_group`、时间行 `info_card_program_time`；保留 `info_card_program`/`info_card_progress`/最近条/入口提示/焦点逻辑。
- [ ] `PlayerActivity.kt`：`banner` 调用（`1985-2025`）传 `programStartMs/programEndMs/nowMillis/groupLabel`；`NowProgramUi` 的起止从 `data.nowProgram` 填入（`1199-1219`）；`videoFormat` 无需新回调（`825-827/839-840` 已更新）。
- [ ] `EpgModels.kt:156`：`NowProgramUi` 增加 `startMillis: Long = 0, endMillis: Long = 0`（默认值兼容其他调用点）。
- [ ] `scripts/check_info_card.py`：按 §10 增补（台标 ≥160dp、34/36sp、徽标/技术/时间 tag、纯函数与档位字符串、单行/固定高、禁 marquee/wrap；保留既有 F1/F2 与 0dp 断言）。
- [ ] 测试：新增 `InfoCardBadgeTest`（§10.1）；`InfoCardPolicyTest` 的 `techLine*` 3 例改为 §10.1 的编码/技术行断言；`InfoCardFocusTest` 不动。
- [ ] 文档：在 `INFO_CARD_V2_WIRING.md` 顶部标注“布局/台标/节目/徽标以 INFO_CARD_V3 为准，§3/§4/§5.1/§6 仍有效”。



### 9.8 实现前 anchor 复核

```powershell
Select-String -Path android/app/src/tivimini/java/com/cjlhll/iptv/ChannelInfoBanner.kt `
  -Pattern 'fun ChannelInfoBanner','ChannelLogo\(','headlineLarge','titleLarge','CatchupProgressRow','info_card_tech','if \(showRecent\)','private fun RecentStrip'
Select-String -Path android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt `
  -Pattern 'var videoFormat','videoFormat = p.videoFormat','val current = data.nowProgram','NowProgramUi\(title','ChannelInfoBanner\(','AudioTrackUi.summary','var nowMillis','val playingGroup ='
Select-String -Path android/app/src/tivimini/java/com/cjlhll/iptv/EpgModels.kt -Pattern 'data class NowProgramUi'
```

---

## 10. `check_info_card.py` 新增断言（与旧断言合并）

| # | 断言 |
|---|---|
| 1 | `ChannelInfoBanner.kt` 台标为 **160×96 或 180×108**（`"160.dp"`+`"96.dp"` 或 `"180.dp"`+`"108.dp"`），且 `info_card_logo` tag 仍在 |
| 2 | 台名 `headlineLarge` + `34.sp` 或 `36.sp`（`headlineLarge.copy(fontSize = …)`） |
| 3 | 徽标行 tag `info_card_badges`；技术行 tag `info_card_tech`；节目时间 tag `info_card_program_time`；小字 tag `info_card_number`/`info_card_group` |
| 4 | 徽标三项映射在 `InfoCardPolicy` 存在：`resolutionTier`、`frameRateLabel`、`bitrateLabel`、`channelLabel`、`badgeCandidates`、`planBadges`；且字符串包含 `SD HD FHD 4K`、`fps`、`Mbps`、`单声道` |
| 5 | 技术行函数：`videoCodecLabel`、`audioCodecLabel`、`dynamicRangeLabel`、`techLine`；映射字符串含 `H.264`、`H.265`、`AV1`、`SDR`、`HDR10`、`HLG` |
| 6 | **单行 ellipsis**：banner 里 `maxLines = 1` 出现 ≥8 次、`TextOverflow.Ellipsis` 存在；**禁止** `basicMarquee`、`LazyRow`、`animateContentSize`、`wrapContentHeight` |
| 7 | 固定行高：banner 含 `.height(24.dp)`（徽标）、`.height(16.dp)`（技术行/小字）及主行 `.height(96.dp)` 或 `.height(108.dp)` |
| 8 | 未知隐藏：`planBadges`/`badgeCandidates` 中存在 `<= 0` 判空（字符串断言）+ `dynamicRangeLabel` 有 `SDR` 默认 |
| 9 | 既有 F1/F2 断言（focusRecoveryAction/aggregateNodeFocus/onFocusedChanged(false)/shouldClearFocusOnTransition/Activity 先 super 再 BACK 兜底/镜像不由 state 推导）**全部保留** |
| 10 | 既有 0dp/无 border/shadow/blur 断言保留 |

## 10.1 新增 JVM 测试（`InfoCardBadgeTest`）

- 档位边界：`719→SD`、`720→HD`、`1079→HD`、`1080→FHD`、`2159→FHD`、`2160→4K`、`0/0→null`（`1920×1080→FHD`）。
- 帧率：`0→null`、`25→"25fps"`、`29.97→"30fps"`、`59.94→"60fps"`、`23.976→"24fps"`。
- 码率：`0→null`、`8_300_000→"8.3Mbps"`、`950_000→"0.9Mbps"`、`-1→null`。
- 声道：`0→null`、`1→"单声道"`、`2→"2ch"`、`6→"5.1"`、`8→"7.1"`、`3→"3ch"`。
- 徽标优先级/溢出：候选按分辨率→帧率→码率→声道；缩小 `maxWidthDp` 时先丢声道、再码率、再帧率，`visible` 始终是候选前缀、`overflow` 是后缀且顺序不变。
- 技术行：编码映射 `video/avc→H.264`、`video/hevc→H.265`、`video/av01→AV1`、未知→null；音频 summary `"mpeg-L2 · 2ch"→"MPEG-L2"`；动态 `null/-1→SDR`、`6→HDR10`、`7→HLG`；join 空项省略、全空→""。
- 节目行：`end<=0→""`；直播剩余 `(end-now)/60000`、`<1 分钟`；catchup 无剩余；`programTitleOrPlaceholder(null)→"暂无节目信息"`。
- `smallMetaLine(12,"央视频道","MPEG-L2")→"频道 12 · 央视频道 · MPEG-L2"`；number=0/group=null 时按存在项拼接。

---

## 11. 实施顺序 / 回退 / 风险

1. 先加 `InfoCardPolicy` 纯函数 + `InfoCardBadgeTest`（可 JVM 独立验证，不动 UI）。
2. 再改 `ChannelInfoBanner` 布局（保持所有 tag/焦点逻辑）；用 §2.4 宽度预算在预览/真机核对不换行。
3. 补 `NowProgramUi` 起止字段与 banner 参数接线（直播/回看两路）。
4. 同步 `check_info_card.py` 与既有测试；跑四/五门禁 + 单测 + `assemble`。
5. 回退：布局集中在 `ChannelInfoBanner.kt`；数据字段默认值 0 兼容；`git revert` 单提交即可回 v2；无 Prefs/数据结构迁移。
6. 风险：①字体度量在 240dpi 下比 7dp/字宽 → 徽标预算 260dp 有 80dp 余量，主行用 weight 不会溢出；②B 档高度余量小（2dp），按 §2.2 收紧；③`Format.frameRate`/`colorInfo` 可能为 0/null → 按 §3/§4 隐藏/SDR；④`colorInfo` 的 HDR 判断依赖 `colorTransfer`，常量用 `C.COLOR_TRANSFER_ST2084=6`/`HLG=7`（banner 侧换算为 Int 传给纯函数）。

---

## 12. t9 真机验收（在 v2 17 步基础上增补）

1. 直播 CCTV-1：大 logo（160×96）+ 台名 34sp 同行；右侧节目名/起止/剩余/3dp 进度；右上 4 徽标（如 `FHD · 50fps · 8.3Mbps · 2ch`）；技术行 `H.264 · MPEG-L2 · SDR`；无换行/无挤压。
2. 4K/高清台各一：档位分别为 `4K`/`FHD`/`HD`；未知字段（如码率 0）不出现且不留空位。
3. 回看：节目行显示 catchup 起止 + 进度（不再有独立回看行）；快进浮层仍显示同一卡片；最近条隐藏、不可聚焦。
4. 最近观看快跳 + 入口提示 + 3s/聚焦不隐藏/遮罩优先/F1/F2 焦点用例全部回归。
5. 高度实测：`dumpsys gfxinfo`/截图量卡片高度 ≤300dp（A 档≈286、B 档≈294）；4 徽标 + 台名 + 节目不重叠、不换行。
6. 记录 `frameRate/bitrate/channelCount/colorInfo` 日志（banner 侧 `IQTV-INFOCARD` DEBUG）验证取值与档位。

---

## 13. 版本关系 / 非目标

- 本规格**覆盖** `INFO_CARD_V2_WIRING.md` §5.2/§5.3 的布局部分（台标尺寸/节目位置/技术参数），**不覆盖**其 §3（Activity 守卫）、§4（状态机）、§5.1（最近观看模型）、§6（门禁基础）——这些继续有效。
- `INFO_CARD_V2.md` 的三态/焦点/最近观看/遮罩优先/入口提示契约继续有效；v3 只重排视觉与徽标/技术信息。
- 非目标：不做台标圆角/背景；不做触摸交互；不做自定义徽标顺序；不引入 LazyRow/动画；不改变 `Format` 回调与播放管线。
