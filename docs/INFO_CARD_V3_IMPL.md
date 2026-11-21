# 信息卡 v3 实现记录（t24）

> 规格：`docs/INFO_CARD_V3.md`（t23）。基线：t20 包 **vc10156**（`679f961`）。
> 只改代码与 CI；设备侧由 acceptance(t25) 验证。未动播放/设置/台标/更新；未动 t20 perf 默认值。

---

## 1. 一句话

信息卡从 v2（台标 96×60、台名在右列、技术行中文案）改为 v3：**台标 160×96 与 34sp 台名同一行**、
**右侧独立节目列**（单行 ellipsis + 起止/剩余 + 3dp 进度）、**右上角横向徽标（≤4：分辨率档·帧率·码率·声道）**、
**下方单行技术行（视频编码·音频编码·SDR/HDR）**；底部最近观看快跳、入口提示、三态、焦点 F1/F2 全部不变。

---

## 2. 纯逻辑（`InfoCardPolicy.kt`，Android-free）

| 函数 | 说明 |
|---|---|
| `resolutionTier(w,h)` | `h = height>0?height:width`；`<=0`→null；`<720` SD / `720–1079` HD / `1080–2159` FHD / `>=2160` 4K |
| `frameRateLabel(f)` | `<=0/NaN`→null；`roundToInt()+"fps"`（29.97→30、59.94→60、23.976→24） |
| `bitrateLabel(b)` | `Format.bitrate` 为 **bit/s**；`<=0`→null；`"%.1fMbps"`（8_300_000→`8.3Mbps`） |
| `channelLabel(n)` | 1→单声道、2→2ch、6→5.1、8→7.1、其它→`Nch`；`<=0`→null |
| `badgeCandidates(w,h,fps,br,ch)` | 优先级 分辨率 > 帧率 > 码率 > 声道；未知**跳过、不占位** |
| `planBadges(cands,max=260,...)` | 估算宽度超限后**从该点起全部进 overflow**（visible 恒为前缀）→ 绝不换行 |
| `videoCodecLabel(mime,codecs)` | avc→H.264、hevc→H.265、av01→AV1、未知 null |
| `audioCodecLabel(summary)` | 取第一段：mpeg-L2→MPEG-L2、aac→AAC、ac-3→AC-3、eac3/opus/flac/mp3 同理；未知 null |
| `dynamicRangeLabel(transfer,hdr)` | 6→HDR10、7→HLG、其它/缺失→**SDR**（唯一“缺失也显示”的项） |
| `techLine(video,audio,dr,overflow)` | `  ·  ` 连接，空项省略；全空→`""`（不组合该行） |
| `programTitleOrPlaceholder(t)` | 空→`暂无节目信息` |
| `programTimeLine(s,e,now,catchup)` | `e<=0`→`""`；直播 `HH:mm–HH:mm · 剩余 N 分钟`（0→`<1 分钟`）；回看只给起止 |
| `smallMetaLine(n,group,audio)` | `频道 N · 分组 · 音频编码`（存在项才拼） |

旧 `techLine(audioLabel,width,height,bitrate,channelCount,sampleMimeType)` 已删除，测试同步改为 v3 断言。

---

## 3. 布局（`ChannelInfoBanner.kt`）

- 卡片：`surface α0.92`、0dp、无 border/shadow/blur；padding `L/R 40, top 20, bottom 20`。
- **徽标行**：`Row(fillMaxWidth().height(24.dp), Arrangement.End)`，`Text(maxLines=1, Ellipsis, tag=info_card_badges)`，`joinToString("  ·  ")`。
- **主行**：`Row(fillMaxWidth().height(96.dp), CenterVertically)`：`ChannelLogo(160×96, tag=info_card_logo)` + `Spacer(24)` + 台名列 `weight(0.44f)`（`headlineLarge.copy(fontSize=34.sp, lineHeight=42.sp)` tag=`info_card_title`；下行 `Row(height(16.dp), tag=info_card_group)` 内 `Text(smallMetaLine, tag=info_card_number)`）+ `Spacer(28)` + 节目列 `weight(0.56f)`（`titleLarge` 单行 ellipsis tag=`info_card_program`；`programTimeLine` 单行 tag=`info_card_program_time`；`BannerProgressBar` 3dp tag=`info_card_progress`）。
- **技术行**：`Text(maxLines=1, Ellipsis, fillMaxWidth().height(16.dp), tag=info_card_tech)`，α0.60。
- **最近观看 / 入口提示 / RecentStrip / 焦点 F1/F2**：与 v2 完全一致（只把左右 padding 32→40）。
- 回看：删除独立 `CatchupProgressRow`，进度并入节目列（`positionMs` 换算进度），三态同高。

---

## 4. 数据接线

- `EpgModels.NowProgramUi` 增 `startMillis/endMillis`（默认 0，兼容其它调用点）。
- `PlayerActivity`：`nowProgramByUrl` 构造时填 `current?.startMillis/endMillis`；`ChannelInfoBanner` 调用新增 `programStartMs/programEndMs/nowMillis/groupLabel`（直播取 `nowProgramByUrl[url]`，回看取 `catchupConfig`，分组名取 `ChannelGrouper.displayGroupName`）。
- `Format`（width/height/frameRate/bitrate/channelCount/sampleMimeType/codecs/colorInfo）直接来自既有 `videoFormat`，无新增回调。

---

## 5. 测试与门禁

- 新增 `InfoCardBadgeTest`（11 例）：档位边界 719/720/1079/1080/2159/2160/0-0、帧率取整、码率 Mbps、声道文案、徽标优先级与四档宽度下的 visible 前缀/overflow 后缀、节目行（end≤0、剩余、`<1 分钟`、回看不显示剩余）、小字拼接。
- `InfoCardPolicyTest` 的旧 `techLine*` 两例替换为 `techLineJoinsCodecsAndDynamicRangeAndOmitsEmptyParts` 与 `codecAndDynamicRangeLabelsMapKnownValuesAndHideUnknown`。
- `scripts/check_info_card.py` 新增 12 组 v3 断言：台标 ≥160×96、`headlineLarge.copy(34.sp)`、五个新 tag、13 个纯函数存在、档位/编码/`fps`/`Mbps`/`单声道` 字符串、`maxLines = 1` ≥8 次、禁 `basicMarquee/LazyRow/animateContentSize/wrapContentHeight`、固定行高 24/16/96dp、未知徽标 `<=0→null`、`else -> "SDR"`；**v2 的 F1/F2 与 0dp/无 border 断言全部保留**。

---

## 6. 回退 / 风险

- 单文件回退：`git revert` 本提交回到 v2 布局（`NowProgramUi` 新字段有默认值，无 Prefs/数据迁移）。
- 高度账：A 档 96dp 主行 + 24dp 徽标 + 16dp 技术行 + 最近条 ≈286dp ≤300dp（无最近条更矮）；超长台名/节目/技术行均 ellipsis 单行，不换行不增高。
- 1060/960dp 宽度下主行用 weight 分配，不溢出；徽标最坏 ≈180dp ≤260dp 预算。

---

## 7. 交付 receipt

| 项 | 值 |
|---|---|
| 提交链 | `3180954`（v3 实现）→ `68c6e51`（channelLabel when 条件修正）→ `84e3693`（bitrate 一位小数 HALF_DOWN，950k→0.9Mbps） |
| CI run（绿） | **#160**，sha `84e3693`，push，conclusion `success` |
| 构建 | `BUILD SUCCESSFUL in 3m 28s`；`:app:testDebugUnitTest`（312 用例，含新增 `InfoCardBadgeTest` 11 例）+ `:app:lintDebug` + `assembleDebug` + `assembleDebugAndroidTest` |
| 包 | `tv.iqtvlite.app.validation`，versionCode **10160**，versionName `2.2.0-tivimini-84e36930-validation` |
| app sha256 | `623081d2cd1ec7e0477728e03f19afc6bdddeea1f4edd36205097b69e6ac319d` |
| test sha256 | `8928141b6fd74d9631c60cca0c988e759d666d936ad72c505bbddb0d7ebcf0e0` |
| 门禁 | logo / **info_card(v3 新增断言)** / settings_ia / settings_scroll / perf_policy 全 OK；pair gate `{0,0,0}` |
| 产物 | `iqtvlite-test-apk`（artifact id 14708775885，10,448,106 B ≪30MB） |
| 未改 | 播放/零拷贝/设置/台标/自动更新/音轨；t20 perf 默认值（M1/E4/E1）保持现状；无 Prefs 迁移 |

> 修复过程：`#158` 编译错（`channelLabel` 的 `when { 1 -> … }` 缺布尔条件）→ `#159` 单测 1 红（`%.1f` 把 950k 进位成 1.0Mbps，规格要求 0.9）→ `#160` 全绿。
