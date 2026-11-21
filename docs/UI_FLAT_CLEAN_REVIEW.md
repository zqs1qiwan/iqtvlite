# 去圆角干净 UI 与架构保真 独立复查（t103 / run91）

任务 t105（attempt 1），designer2，2026-11-20（Asia/Hong_Kong）。**结论：needs_revision。** 侧栏/信息条/设置/质量弹窗/加载/台标/二维码白底等显式 `RoundedCornerShape` 已全部扁平到 0dp，进度条方头，无 999dp 胶囊；原版架构（633dp 双列、台标 64×40/80×50、EPG 96dp 时间、字号/间距/颜色、basicMarquee、交互）**未改**，焦点仍原版 `surfaceVariant` 无蓝框。但**配置页的 TextField×2 与“保存并播放”Button 未显式设 shape**，仍沿用 Material3 默认圆角/胶囊；**UpdateDialog 的 AlertDialog/Button 也未设 shape**（默认 28dp 圆角）——这两处不在“0–2dp”内。补齐后即可完全满足 v4。

> 本复查只读固定 commit `ad2c6d57`（工作树 clean）。模型不能读图，视觉结论仅基于源码/尺寸与 c82593f 的 diff。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`，分支 `integration/tivimini-baseline`；HEAD=`109a455`（docs-only），工作树 clean；`ad2c6d57..HEAD` 无代码改动。
- 被审查 commit `ad2c6d57e7a227b5d5b73cac2a9ddc64568527a2`（t103），8 文件 +23/−32（仅 shape/死字段/LRU）。
- 基线 `c82593f`（原版 Compose 观感）+ `docs/UI_VISUAL_SPEC_TIVIMATE.md` v4。
- 关键 blob @ad2c6d57：

| 文件 | blob |
|---|---|
| [PlayerDrawer.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt) | `4967904985699fd51c87587c7ed709fa030c6bff` |
| [ChannelInfoBanner.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/ChannelInfoBanner.kt) | `b9b698156b3440bb701fa5272746dbc088406c5b` |
| [SettingsDrawer.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/SettingsDrawer.kt) | `96da0c58a505a93ce576ed1584ba865235dad02d` |
| [QualitySelectorDialog.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/QualitySelectorDialog.kt) | `f9ca52ca558aa6a146e7ed1002ac540a6f794a86` |
| [MainActivity.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/MainActivity.kt) | `453722b799a2bcc8ae5635b05bdf8b7750490b82` |
| [LogoLoader.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/LogoLoader.kt) | `85d5b290c674d123e9e53814688f5eea75287772` |
| [PlayerActivity.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt) | `637c67909c12c140fa28399a3fab9fe149bc609e` |

- 候选：主 10,404,864B SHA256 `06da0b50…2da9`，test 502,955B `63939d39…c2af`，签名 `7ce57292…3cc8`，run 44710264438(run91)，10091。未真机验证。

## 2. 显式圆角扁平化 — 已全部落地（pass）

- 全工程 `RoundedCornerShape` 现仅 **0.dp**：
  - PlayerDrawer 容器 18→0、分组/频道/节目/日期行 12→0、`ThinProgressBar` 999→0（L132/936/981/1052/1058/1107/1155）。
  - ChannelInfoBanner 容器 18→0、`CatchupProgressRow` 2→0、`BannerProgressBar` 2/999→0（L41/202/211/256/262）。
  - SettingsDrawer 容器 18→0、行 12→0（L52/155）。
  - QualitySelectorDialog 面板 18→0、行 12→0（L60/162）。
  - MainActivity 二维码白底 12→0（L314）。
  - ChannelSwitchLoading 12→0（L113）。
  - LogoLoader 台标磁贴 10→0（L356）。
  - PlayerActivity `CatchupProgressBar` 容器 18→0、进度 2→0（L1240/1274/1283）。
- **无 999dp / 10 / 12 / 18** 的 `RoundedCornerShape` 残留（全工程 grep 均为 0.dp）；`CircleShape` 仅 live 12dp 圆点（非容器/胶囊，保留）。
- **无 `border`/`BorderStroke`/`shadow`/`elevation`/`blur`** 用于 Compose 焦点；焦点仍原版 `surfaceVariant` 浅底，无蓝框/硬框。符合。

## 3. 仍未扁平：配置页控件 + UpdateDialog 默认 shape（needs_revision 主因）

> 这些不是显式 `RoundedCornerShape`，因此“全工程 RoundedCornerShape→0”没有覆盖到；但 v4 要求“按钮/输入框 0–2dp、弹窗 0–2dp”。

- **FLAT-01 medium（配置输入框）**：`MainActivity` 两个 `TextField`（[L360](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/MainActivity.kt#L360)、[L387](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/MainActivity.kt#L387)）**未设 `shape`**，沿用 Material3 `TextFieldDefaults.shape`（圆角，非 0–2dp）。
- **FLAT-02 medium（配置按钮）**：`MainActivity` “保存并播放” `Button`（[L414](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/MainActivity.kt#L414)）**未设 `shape`**，沿用 TV Material3 `ButtonDefaults.shape`（胶囊/圆角，非 0–2dp）。
- **FLAT-03 medium/low（更新弹窗）**：`UpdateDialog` 使用 `AlertDialog`（[L53](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/UpdateDialog.kt#L53)）**未设 `shape`**，默认 `AlertDialogDefaults.shape`（28dp 圆角）；其 `Button`（L79）与 `LinearProgressIndicator`（L66）也用默认 shape。v4 要求弹窗 0–2dp、进度线性方头。
- 修复：
  - `TextField(... shape = RoundedCornerShape(2.dp))` ×2；
  - `Button(... shape = RoundedCornerShape(2.dp))`（或 `ButtonDefaults.shape = RoundedCornerShape(2.dp)`）；
  - `AlertDialog(shape = RoundedCornerShape(2.dp))`；`UpdateDialog` 内 `Button` 同 2dp；`LinearProgressIndicator` 如需方头加 `strokeCap = StrokeCap.Butt` / `trackCornerRadius = 0.dp`（若 Material3 版本支持）。
- 其余页面已 0dp，无需再动。

## 4. 架构未被破坏 — pass

- `git show ad2c6d57` 仅改 shape + Activity 死字段 + `LogoLoader` LRU；**未改** padding/size/width/height/fontSize/color/结构。
- 633dp 宽度公式保留：groups 150+1、channels 300、divider 1、programs 300+32、dates 110+10（PlayerDrawer L586–589）。
- 台标 64×40（L997）、信息条 80×50（ChannelInfoBanner L67）；位置/`Spacer` 未动。
- 频道名 `"$index. ${channel.title}"` + `titleMedium`、当前节目 `bodySmall` alpha .72、EPG 时间 96dp + `bodySmall`、past/now/future 颜色、`basicMarquee`（L1084）、行外 3dp / group 14-12 / channel 12-10 / date 10-10 / program 12-10 全部未改。
- 字号层级：`headlineMedium/titleMedium/bodySmall/bodyMedium` 未改；颜色体系原版（`#BDBDBD/#757575/#121212/#1E1E1E/White`）未改。
- 交互：`PlayerDrawer` 原版 DPAD/focus/分组/日期/回看/数字跳台/频道键/`onClose/onActiveColumnChanged` 未改；`SidebarController` 仍承载 drawerOpen/activeColumn/selectedGroup，无双状态机。
- 死字段清理（`setDrawerOpen`/`focusToDrawerChannels`）与 `LogoLoader` 128 属低危非视觉变更，不影响架构。

## 5. 保留项 / 体积 — pass

- 零拷贝 SurfaceView 基座/灰屏、频道键 166/167、LoadControl `1000/6000/300/1000`、SR-01–03、回看 owner B、EPG、音频诊断（`AudioDiagnosticFields`/`StreamPlayback`）、稳定 key（`DrawerKeys`）、品牌资源/app name（`resValue` `IQTV Lite` / 验证版）均未改。
- 体积 10,404,864B ≤ 30MB。
- **性能**：去圆角减少裁剪/离屏层；未删内容/改结构；jank 以原版 Compose 基线为准；`LogoLoader` 128 恢复原版（PSS 略高于 96，但属 REVERT-03 低危，不属视觉回归）。

## 6. 放行结论与门禁

- **源码结论：needs_revision**。侧栏/信息条/设置/质量弹窗/加载/台标/二维码已全部方角，进度方头，架构/台标/EPG/字号/颜色/焦点均保真；只剩**配置页 TextField×2 + Button** 与 **UpdateDialog AlertDialog/Button** 的 Material 默认圆角未覆盖。
- 修复 FLAT-01/02/03 并新 CI 后复审；acceptance2 在 exact pair（主 `06da0b50…`/test `63939d39…`/10091）验证：配置页输入框/按钮方角 2dp、更新弹窗 2dp、列表/面板/信息条/设置/质量弹窗/加载/台标均方角、进度条方头、无蓝框、结构/台标/EPG/字号/间距与 CI10090 一致；TIV/回看不回归；反复开关不崩/不灰屏。

## 7. 缺陷与可执行修复

| id | 严重度 | 问题 | 修复 |
|---|---|---|---|
| FLAT-01 | **medium** | MainActivity 两个 `TextField` 未设 shape，沿用 Material3 默认圆角 | `shape = RoundedCornerShape(2.dp)`（两处） |
| FLAT-02 | **medium** | MainActivity “保存并播放” `Button` 未设 shape，沿用 TV Material3 默认胶囊/圆角 | `shape = RoundedCornerShape(2.dp)`（或 `ButtonDefaults.shape`） |
| FLAT-03 | medium/low | UpdateDialog `AlertDialog` 未设 shape（默认 28dp），内部 Button/LinearProgressIndicator 亦默认 | `AlertDialog(shape=RoundedCornerShape(2.dp))`；Button 2dp；进度如需方头 `strokeCap=Butt`/`trackCornerRadius=0` |
| FLAT-04 | low | `PlayerActivity` 可能残留未用 `focusToChannels`/`SidebarController.setDrawerOpen` | 清理或注释 |
| FLAT-05 | low | `LogoLoader` LRU 128 比 96 增 PSS（恢复原版，REVERT-03） | 接受并记录；如 PSS 紧张再降 |
| FLAT-06 | low | `SourceConfigWebPage` HTML `box-shadow` 非 TV Compose | 不属于本次 TV UI，忽略/记录 |
| FLAT-07 | low | `ChannelSwitchLoading` 仍 `CircularProgressIndicator`（圆环形） | 状态 glyph，不是容器；保留（若要极致扁平可改线性 2dp） |

## 8. 只读命令与终态

只读命令：`git rev-parse HEAD`、`git status --short`、`git show --stat ad2c6d57`、`git show ad2c6d57`（全 diff）、`git rev-parse ad2c6d57:<file>`、源码 grep（RoundedCornerShape、CircleShape、border/shadow、TextField/Button/AlertDialog、633/64×40/96dp/basicMarquee）。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 本报告完成后停止读取目标源码；后续提交须新 diff 证据快照。
