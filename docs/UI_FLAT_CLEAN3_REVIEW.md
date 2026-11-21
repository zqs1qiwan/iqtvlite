# 几何回退 / jank 排查 / 配置页收口 独立复查（t111 / run93）

任务 t112（attempt 1），designer2，2026-11-20（Asia/Hong_Kong）。**结论：源码层可接受（pass）**。`git diff 165d94f1..fa7f87e` 证明 PlayerDrawer/ChannelInfoBanner/SettingsDrawer **只有圆角/背景 shape 变化，没有 padding/rowHeight/spacer/logoSize/字号/文本起点/行距/头偏移改动**——扁平版布局度量按构造与 CI10090 相同；t108 的配置页/更新弹窗 2dp 与方头保留；jank 机械诱因（每行 0dp shape 背景、LRU128）已收敛。**但真机目标里的“选中▶+‘播放中’”在原版 PlayerDrawer/ChannelInfoBanner 中 0 命中**（只存在于已删 NativeSidebarView），目标需与 CI10090 基线对齐；真机数值未验证。

> 本复查只读固定 commit `fa7f87e`（工作树 clean）。模型不能读图，视觉/几何结论仅基于源码与 165d94f1 diff；真机由 acceptance2(t113) A/B 脚本取证。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`，分支 `integration/tivimini-baseline`；HEAD=`62be465`（docs-only），工作树 clean；`fa7f87e..HEAD` 无代码改动。
- 被审查 t111 commit `fa7f87eaddd81919d488b2051b5401eb9ea2c200`（+ t108 `4d1b6d4`，均含在 165d94f1..fa7f87e 区间）。
- 基线 `165d94f1`（CI10090 原版观感）。
- 关键 blob @fa7f87e：

| 文件 | blob |
|---|---|
| [PlayerDrawer.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt) | `2e85375acb22eb2b65ba21832709b1b12ab5e3c4` |
| [ChannelInfoBanner.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/ChannelInfoBanner.kt) | `b9b698156b3440bb701fa5272746dbc088406c5b` |
| [SettingsDrawer.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/SettingsDrawer.kt) | `96da0c58a505a93ce576ed1584ba865235dad02d` |
| [MainActivity.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/MainActivity.kt) | `110706f6f22f74e12fe6ccc152719c230a69ca42` |
| [UpdateDialog.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/UpdateDialog.kt) | `c9b09daa94f517cd52d84011b0638e5d6a9e3879` |
| [LogoLoader.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/LogoLoader.kt) | `dc3ba39114fbfd236f01449e99d9ea67b509d97e` |

- 候选：主 10,404,860B SHA256 `56c855a6…361c`，test 502,955B `abfe2aa9…b423`，签名 `579a0db2…fa9f`，run 44763302399(run93)，10093。未真机验证。

## 2. 几何对齐 — 源码按构造等价 CI10090（pass）

`git diff 165d94f1 fa7f87e -- PlayerDrawer/ChannelInfoBanner/SettingsDrawer`：

| 文件 | 实际 diff |
|---|---|
| `PlayerDrawer.kt` | 仅 6 处：容器 `.background(container, shape)`→`.background(container)`；channel/program/group/date 四行 `.background(bg, 12dp)`→`.background(bg)`；`ThinProgressBar` track/fill `999dp`→`plain`。**无 padding/rowHeight/spacer/logoSize/字号/width/offset 改动** |
| `ChannelInfoBanner.kt` | 仅 5 处 shape：容器 18→0；catchup 2→0；`BannerProgressBar` 2/999→0。**无 padding/logo 80×50/headlineMedium 改动** |
| `SettingsDrawer.kt` | 仅 2 处 shape：容器 18→0、行 2→0。**无 300dp/24dp/padding/字号改动** |

- 复核原版度量仍在：633 公式（groups 150+1 / channels 300 / divider 1 / programs 300+32 / dates 110+10，L586–589）、group `14/12`、channel `12/10`、date `10/10`、program `12/10`、行外 `3dp`、台标 `64×40`（L997）、节目时间 `96dp`（L1174）、`basicMarquee`（L1084）均未变。
- 因此 **Compose 布局度量与 CI10090 相同**；t107 报告的 text x=156 vs 96 / row pitch 114 vs 96 / header [24,24] vs [0,0] 只能是测量上下文（不同 APK 代/滚动/焦点状态）或 native 版残留，不是扁平代码回归。A/B 脚本 `scripts/sidebar_geometry_ab.sh` 已提供，acceptance2 用同状态左右对照。

## 3. “选中 ▶ + 播放中” — 目标与源码冲突（medium，需对齐）

- `git show 165d94f1:...PlayerDrawer.kt` 与 `git show fa7f87e:...PlayerDrawer.kt` 对 `▶` / `播放` **均 0 匹配**；`ChannelInfoBanner` 亦然。这些标记只存在于已删除的 `NativeSidebarView`（CI10086–88）。
- 结论：**不是 t103/t111 回归**；只做“去圆角”的 Compose 原版本来就没有该标记。若 acceptance 目标仍写“选中▶+播放中”，截图必然对不上——需把目标改为“与 CI10090 一致（无该标记）”，或明确把它当独立功能另立项（会改变原版架构，不能混在“仅扁平”里）。
- 这是**目标口径问题**，不是本轮代码缺陷；但需在 t113 前确认，否则真机验收会误判。

## 4. 配置页/更新弹窗 2dp — 已保留（pass）

- `MainActivity`：两个 `TextField` 均 `shape = RoundedCornerShape(2.dp)`（L364/L392）；“保存并播放” `Button` 使用 `androidx.tv.material3.ButtonDefaults.shape` 五个状态均 2dp（L421）；二维码白底 0dp（L314）。
- `UpdateDialog`：`AlertDialog(shape = RoundedCornerShape(2.dp))`（L57）；确认/取消 `Button` 五状态 2dp（L104/L112）；`LinearProgressIndicator(strokeCap = StrokeCap.Butt)`（L70）方头。
- 其余仍 0dp：PlayerDrawer/InfoBanner/Settings/Quality/Loading/Logo/QR/信息条进度；全工程 `RoundedCornerShape` 仅 **0dp 或 2dp**，无 999/10/12/18。

## 5. 架构保真 — pass（无内容/结构/字号变化）

- 台标 64×40（PlayerDrawer L997）/80×50（ChannelInfoBanner L67）；频道名 `"$index. ${channel.title}"` + `titleMedium`；当前节目 `bodySmall` alpha .72；EPG 时间 96dp + `bodySmall` + past/now/future 颜色；双列 633dp；行高/padding；字号层级；原版颜色（`#BDBDBD/#757575/#121212/#1E1E1E/White`）；`basicMarquee`；DPAD/分组/日期/回看/数字跳台/频道键——均未改。
- `git diff` 未见删除内容/改结构/改字号。

## 6. 焦点 / hairline / 阴影 — pass

- 焦点仍原版 `MaterialTheme.colorScheme.surfaceVariant` 浅底；无 `border`/`BorderStroke`/`shadow`/`elevation`/`blur`/`clipToOutline`/`graphicsLayer`（Compose）；未加 hairline（可选，未加）；无蓝框/硬框/外发光。
- `CircleShape` 仅 live 12dp 圆点（状态点，保留）。

## 7. jank 收敛 — 源码方向正确（真机归 acceptance）

- 每行/容器/进度去掉了 `RoundedCornerShape(0.dp)` 背景：PlayerDrawer 行 `.background(bg)`、容器 `.background(container)`、进度 `.background(track/fill)`；不再每行构建 0dp outline/path。
- `LogoLoader` LRU 128→96，降 PSS/GC。
- 未新增 `clipToOutline`/`graphicsLayer`/shadow；原版 `background` 不裁剪，去圆角不增加 overdraw。
- **low（JANK-01）**：`ChannelInfoBanner`/`SettingsDrawer`/`QualitySelectorDialog` 的 `background(..., RoundedCornerShape(0.dp))` 仍在（非抽屉每行热路径，只影响设置/弹窗打开时；可后续统一 `.background(bg)` 省 allocation）。
- **low（JANK-02）**：`PlayerDrawer.kt` 的 `val shape = RoundedCornerShape(0.dp)`（L132）在容器改 `.background(container)` 后已无使用；可删/注释。
- 真机 jank 数值（目标回近 CI10090 40.32%/105ms）由 t113 `sidebar_geometry_ab.sh`/gfxinfo 回测；源码无法给出数值结论。

## 8. 保留项 / 体积 — pass

- 零拷贝 SurfaceView 基座/灰屏、频道键 166/167、LoadControl `1000/6000/300/1000`、SR-01–03、回看 owner B、EPG、音频诊断（`AudioDiagnosticFields`/`StreamPlayback`）、稳定 key（`DrawerKeys`）、品牌资源/app name（`resValue` `IQTV Lite`/验证版）均未改。
- `SidebarController.setDrawerOpen` 已删除（无引用）；`focusToChannels` 仅 PlayerActivity 局部（L545）但已无消费者（low 清理）。
- 体积 10,404,860B ≤ 30MB。
- **low（PERF-INFO）**：`LogoLoader` 128→96 已回落；未新增 clip/overdraw。

## 9. 放行结论与门禁

- **源码结论：pass**。几何按 diff 等价 CI10090（无布局代码改动）、配置/更新弹窗 2dp 保留、架构/焦点/内容保真、jank 机械诱因已收敛。
- t113 必须：用 `scripts/sidebar_geometry_ab.sh` 同状态对 CI10090 vs run93 取图（text x / row pitch / header / 标记），并 gfxinfo 回测 jank；**验收目标请去掉“▶+播放中”或与 CI10090 对齐**（原版无该标记，源码 0 命中）。
- 真机若仍显示 x/行距/头偏移差异，按脚本排除测量上下文；若仍偏高 jank，剩余差异只有圆角数值（无每帧工作），指向运行间波动。

## 10. 缺陷/风险与可执行修复

| id | 严重度 | 问题 | 修复/动作 |
|---|---|---|---|
| GEOM-01 | **medium** | 真机目标含“选中▶+‘播放中’”，但 165d94f1/fa7f87e 的 PlayerDrawer/InfoBanner 0 匹配；原版无此标记（仅 NativeSidebarView 有） | 目标改为“与 CI10090 一致（无标记）”；或另立项加标记（会改架构，不能混“仅扁平”） |
| GEOM-02 | low | text x=96/row96px/header[0,0] 无法由源码验证；diff 证无布局改动 | t113 用 A/B 脚本同状态截图对照；预期一致 |
| JANK-01 | low | InfoBanner/Settings/Quality 仍 `background(..., RoundedCornerShape(0.dp))`（非主抽屉热路径） | 可统一 `.background(bg)` 省 allocation |
| JANK-02 | low | `PlayerDrawer` `shape`（L132）已无使用 | 删除/注释 |
| JANK-03 | low | `SourceConfigWebPage` HTML box-shadow 非 TV UI | 忽略/记录 |
| JANK-04 | low | `ChannelSwitchLoading` `CircularProgressIndicator` 为状态 glyph | 保留（非容器） |
| CLEAN-01 | low | `focusToChannels` 局部无消费者 | 清理/注释 |

## 11. 只读命令与终态

只读命令：`git rev-parse HEAD`、`git status --short`、`git show --stat fa7f87e`、`git show fa7f87e`、`git diff 165d94f1 fa7f87e -- PlayerDrawer/ChannelInfoBanner/SettingsDrawer/MainActivity/UpdateDialog/SidebarController`、`git show <commit>:PlayerDrawer.kt | Select-String '▶|播放'`、源码 grep（RoundedCornerShape/999/border/shadow/clipToOutline/graphicsLayer/633/64×40/96dp/basicMarquee）。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 本报告完成后停止读取目标源码；后续提交须新 diff 证据快照。
