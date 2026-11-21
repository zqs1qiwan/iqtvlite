# UI / 品牌 / sidebar-jank 综合修复 独立源码复查（t50 / run81）

任务 t51（attempt 1），designer2，2026-11-20（Asia/Hong_Kong）。**结论：源码层可接受（pass）；但这不是“sidebar jank 已解决”的证明。** t50 的改动安全、可逆、部分对症，且诚实标注 deferral；然而①根因未经真实逐帧 profile 证实（含“12dp 圆角 clip/saveLayer”这一理由技术上不准确），②仍有若干 18dp/10dp 圆角与“选中/播放”持久标记未按规范落地，③撤回延后会把刷新尖峰移回冷启窗口。真机 gfxinfo/Perfetto 与视觉验收必须由 acceptance2 完成；**模型不能读图**，本报告只基于源码/资源规格判断。

本复查只读固定 commit：未改 Kotlin/Rust/res、未跑 Gradle/CI/ADB、未操作 TV。依据 [t50 修复说明](../../iqtvlite-integration/docs/UI_BRAND_PERF_REPAIR.md)、[我的 UI 视觉规范](UI_VISUAL_SPEC_TIVIMATE.md)、[品牌规范](BRAND_GUIDELINES.md)、[CI10078 零拷贝基座验证](CI_SURFACE_BASE_PERF_VERIFICATION.md)、[t45 侧栏实测（依赖摘要）]。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`，分支 `integration/tivimini-baseline`；HEAD=`9783840`（docs-only），工作树 clean；`8e2e064e..HEAD` 未再触及代码。
- 被审查修复 commit `8e2e064eac5eec7bdbc12457ce40a18b7cd02b18`（t50），docs 后续 `9783840`。diff 47 文件（多数为品牌二进制）+代码约 +158/−36。
- 读取内容身份（git blob，随提交；工作树一致）：

| 文件 @8e2e064e | blob |
|---|---|
| [PlayerDrawer.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt) | `04134823a0246d4e115aea831e9f3635d98974e3` |
| [PlayerActivity.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt) | `d1ea670549155c8e2ea8f40cbd58809e96177e0b` |
| [BackgroundSourceUpdater.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/BackgroundSourceUpdater.kt) | `776c913f26fc98834a7201da9b0d3940aacbcc6c` |
| [AndroidManifest.xml](../../iqtvlite-integration/android/app/src/tivimini/AndroidManifest.xml) | `a172990ee59a0f95232c693104c1e35ecda03713` |
| [build.gradle.kts](../../iqtvlite-integration/android/app/build.gradle.kts) | `ae46f8659ba86097c01ed5a7f8accf9465739f3b` |
| [scripts/sidebar_jank_probe.sh](../../iqtvlite-integration/scripts/sidebar_jank_probe.sh) | `a2dd7c2f0fb3e8e2567fb006d51c3bcf48f31d8c` |

- 候选（本任务未构建/安装）：主 APK 10,404,860B SHA256 `5b0fdce6…e089f`，test 502,955B `5e8674da…4482e`，签名 `34a031e5…df90a`，run 44665902878(run81)，version 10081，label “IQTV Lite 验证版”。

## 2. sidebar jank — 部分对症，根因未证实（这是本次最重要的诚实结论）

### 2.1 已做且合理的改动

- **撤回有害延后**（t45 真机证据：CI10079 侧栏 96% janky，延后把刷新尖峰推进了浏览窗口）：[BackgroundSourceUpdater:21–23](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/BackgroundSourceUpdater.kt#L21) `PLAYLIST 0s / EPG 1.5s`，且 [scheduleStartupRefresh:37–62](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/BackgroundSourceUpdater.kt#L37) 已是**两个独立 `scope.launch`**（t46），EPG 不再被 playlist 下载串行阻塞。PlayerActivity 的首次网络 EPG `delay(8s)` 已删除（[PlayerActivity:730–733](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L730)）。→ **告警撤回成立**。
- **行去圆角 + 固定下限 + 焦点边框**：[PlayerDrawer DrawerGroupItem:932](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt#L932)、ChannelItem[:979–996](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt#L979)、DateItem[:1109](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt#L1109)、ProgramItem[:1159](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt#L1159)：`heightIn(min=44)+padding(vertical=1)`、`background(bg)`（方角）、聚焦行 `border(2dp,#4EA8FF)`。**低风险、可逆，方向合理**。
- **进度胶囊 999→2dp、抽屉容器 18→0dp、Loading 12→2、Quality 18→4/行12→2、Settings 行12→2、Main 12→2** 均与规范一致。
- **gfxinfo 探针** [scripts/sidebar_jank_probe.sh](../../iqtvlite-integration/scripts/sidebar_jank_probe.sh)：`force-stop→start→sleep 8→gfxinfo reset→LEFT+8×DOWN/4×UP+BACK→framestats`，对照 CI10078 80.07%/200/500。骨架正确，默认包/组件名正确（`tv.iqtvlite.app.validation/com.cjlhll.iptv.SplashActivity`）。

### 2.2 关键缺口：根因并未被“真实逐帧 profile”定位

- t50 文档 §A 明确写 **“code-analysis based; no device Perfetto run by developer”**。因此“每行 12dp 圆角是主因”只是**假设**，且其理由 **技术上不准确**：代码用的是 `.background(bg, RoundedCornerShape(12.dp))`（一次圆角矩形绘制），**并非 `.clip(...)`**，不会产生 per-row saveLayer；去掉它的收益很可能有限。`heightIn(min=44)` 也只是下限——**频道行含 标题+当前节目+进度条**，实际高度由内容决定（约 60–67dp），并未真正“固定行高”；它只在内容不足 44dp 时兜底。
- 结论：**“针对性修复”部分成立（撤回延后 + 去圆角 + 焦点边框 + 行下限），但“根因已被真实 profile 定位”不成立；预期 jank 改善属未证假设**。必须由 acceptance2 用 `sidebar_jank_probe.sh`/Perfetto 对照 CI10078 才能判断是否真降。
- **遗漏的 per-row 绘制成本**：[LogoLoader.kt:356](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/LogoLoader.kt#L356) 仍 `RoundedCornerShape(10.dp)`（台标容器圆角/裁剪），每个台标都带一次圆角 clip；这与“去圆角/减绘制”目标直接冲突，且 t50 未处理。**这是比行背景更可能的 per-row clip 成本**。

## 3. 视觉规范落地 — 核心项已做，仍有 18dp/10dp 与“选中/播放”缺口

### 3.1 已符合规范

- 圆角：抽屉行 0dp、抽屉容器 0dp、进度条 2dp、Loading 2dp、Quality 对话框 4dp/行 2dp、Settings 行 2dp、Main 2dp；**全项目无 `shadow(`/`elevation`**（grep 核实为空）。
- 焦点：抽屉四类行聚焦时 2dp `#4EA8FF` 边框（[PlayerDrawer:986](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt#L986) 等），焦点可见性改善。
- 品牌色 `darkColorScheme` 替换为 Obsidian/Surface/SignalBlue/Amber/Ink/Muted（[PlayerActivity:150–165](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L150)），与规范一致。
- TIV 语义：仅改绘制/尺寸/颜色，未改列结构、焦点顺序、Back 行为（diff 未触及导航逻辑）。

### 3.2 仍不符合规范（可执行修复）

1. **[SettingsDrawer:52](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/SettingsDrawer.kt#L52) `RoundedCornerShape(topStart=18,bottomStart=18)`** —— 设置抽屉仍 18dp；规范要求 0dp 或 ≤4dp。
2. **[ChannelInfoBanner:41](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/ChannelInfoBanner.kt#L41)** 与 **[PlayerActivity:1243](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L1243)** `topStart/topEnd=18dp` —— 信息条（含回看信息）仍 18dp 顶角；规范要求 0dp。
3. **[LogoLoader:356](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/LogoLoader.kt#L356) 10dp 圆角台标** —— 规范要求台标方形容器、不裁剪；且是每行 clip 成本。
4. **“选中/播放”持久标记缺失**：`DrawerChannelItem` 的 `selected: Boolean`（[PlayerDrawer:965](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt#L965)）**未被使用**——`bg` 只看 `focused`（[:972–975](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt#L972)）。焦点移开后，**正在播放的频道与普通频道无视觉区别**（无 3dp 蓝条、无 ▶、文字不加粗）。这是 UI 规范 §4/§7.3 的“必须”项，t50 明确 defer；非 t50 回退（pre-t50 亦如此），但**当前“选中清晰”要求未达成**。

### 3.3 行高/可读性

- `heightIn(min=44)`：分组/节目/日期行基本 44dp；频道行因 标题+节目+进度条 实际约 ≥60dp，**未真正固定**，但均为单行 ellipsis，不会因换行抖动。可读性未见明显受损；焦点 2dp 边框内描边不改变布局。若要求“64/56/44/40 精确行高”，需改为固定 `height(64.dp)`/`heightIn(min=64,max=64)` 并去掉频道行进度条的额外高度（deferred，见规范 §7）。

## 4. 品牌接入 — 正确、与 BRAND_GUIDELINES 一致（applicationId/JNI 未变）

- **显示名**：[build.gradle.kts](../../iqtvlite-integration/android/app/build.gradle.kts) `app_name` → `IQTV Lite` / `IQTV Lite 验证版`；applicationId `tv.iqtvlite.app(.validation)` 未变。
- **图标**：adaptive XML 背景 `@color/brand_obsidian` + `@mipmap/ic_launcher_foreground`；mipmap 各密度方/圆 PNG 已替换旧 `.webp`；`roundIcon` 指向 `ic_launcher_round`。
- **TV banner**：manifest `android:banner="@drawable/tv_banner"`（[AndroidManifest:19](../../iqtvlite-integration/android/app/src/tivimini/AndroidManifest.xml#L19)），`drawable-nodpi/tv_banner.png` 320×180（4355B，与 t49 资产一致）。
- **Splash**：`drawable-nodpi/splash_mark.png`（512，2535B）+ [splash_background.xml](../../iqtvlite-integration/android/app/src/tivimini/res/drawable/splash_background.xml)（Obsidian + 居中 mark）；`Theme.TiviMini` 的 `windowBackground` 在 [values/themes.xml](../../iqtvlite-integration/android/app/src/tivimini/res/values/themes.xml) 与 [values-night/themes.xml](../../iqtvlite-integration/android/app/src/tivimini/res/values-night/themes.xml) 均改为 `@drawable/splash_background`。
- **品牌色**：[brand_colors.xml](../../iqtvlite-integration/android/app/src/tivimini/res/values/brand_colors.xml) 与规范一致。
- 资产字节与 t49 生成件一致（tv_banner 4355、splash_mark 2535、leanback 3135），未见错误缩放/改名。
- **边界/低风险**：①`SplashActivity` 无 `setContentView` 且立即 `finish()`，品牌底只在冷启窗口短暂出现（是否可见由真机确认）；②`values-night` 的 `Theme.TiviMini` 父主题仍是 `Theme.MaterialComponents.DayNight.DarkActionBar`，而 `values` 是 `Theme.Material3.Dark.NoActionBar`（**pre-existing 不一致**，t50 只加了 windowBackground）；③Android 12+ 未配置 `windowSplashScreenAnimatedIcon`（target 35，API31+ 会走系统默认图标；API28 测试机不受影响）；④`drawable-nodpi/app_icon_leanback.png` 未被 manifest 引用，resource shrink 可能剔除（docs/brand 已有原资产）；⑤旧 `app_banner.xml`/`banner.xml`/`banner_bitmap.png`/`logo.png`/`ic_launcher_background.xml` 仍在源码树（已不被 manifest 引用，shrink 会剔除；建议清理以免混淆）。

## 5. 回归检查 — 未回退（源码层）

- **零拷贝基座/灰屏**：t50 未改 `onCreate` 的 FrameLayout/PlayerView/ComposeView 结构（PlayerActivity diff 仅 colorScheme + EPG delay），SurfaceView 直子层/透明浮层保持。
- **频道键/LoadControl/稳定 key/SR/JNI/R8/UA/硬解**：t50 diff 未触及 `dispatchKeyEvent`、`setBufferDurationsMs(1000/6000/300/1000)`、`DrawerKeys`（t46）、`PlaybackIntent`（SR）、RustBridge/JNI、`OMX.goke`/decoder fallback。
- **体积**：10,404,860B ≤ 30MB；相对 CI10080 降低是资源压缩移除旧未引用 banner/logo 位图，非删 ABI/功能（doc 说明；建议 acceptance 核对 APK 内功能资源齐全）。

## 6. 放行结论与门禁

- **源码结论：可接受（pass）**——改动安全、可逆、部分对症，品牌接入正确且 applicationId/JNI 不变，无回退。**但明确：这不等于 sidebar jank 已修复、也不等于视觉规范已完整落地。**
- **放行 acceptance2 在 run81 pair（主 `5b0fdce6…` / test `5e8674da…` / 10081）验证**：
  1. `scripts/sidebar_jank_probe.sh` 对照 CI10078（janky 80.07%/50th30/90th200/95th500）——若改善不明显，**必须**做 Perfetto/gfxinfo 逐帧 profile 再改，不能继续盲改。
  2. 冷启 ×3 TotalTime/TTFF + 初始化/EPG CPU 尖峰（撤回延后后需重测是否回到 86–270%）。
  3. 真实源（含名为“全部”的分组、重复 start+end EPG）开/关/浏览抽屉不崩（t46 key 加固）；视频 dropped=0。
  4. 视觉：图标（Leanback 首页/详情）、banner 320×180 不裁切、splash 冷启可见、显示名 “IQTV Lite / 验证版”；焦点 2dp 蓝框可见；18dp/10dp 残留是否可见突兀。
  5. 24-TIV + SR 回归；硬解 OMX.goke AVC；AudioSink 异常仍属独立项。
- **建议先落地的可执行修复（非阻断）见 §7**，可在 acceptance 跑测同时并行。

## 7. 缺陷与可执行修复（按严重度）

| id | 严重度 | 问题 | 修复 |
|---|---|---|---|
| UI-01 | medium | 根因未被真实 profile 证实；“12dp 圆角 clip/saveLayer”理由不准确（实为 `background(shape)`，非 `clip`）；`heightIn` 未真正固定频道行高 | acceptance 跑 gfxinfo/Perfetto；若未改善，按真实热点（LazyColumn measure、焦点 `withFrameNanos`、台标 decode/clip、EPG 行组合）再改，勿继续盲改 |
| UI-02 | medium | 台标仍 10dp 圆角 clip（LogoLoader:356），每行一次 clip，与“去圆角/降 jank”冲突且不符规范 | 台标方形容器（0dp，`ContentScale.Fit`），去掉 clip |
| UI-03 | medium | “选中/播放”持久标记缺失：`selected` 参数未使用，焦点移开后播放频道无标识 | 用 `selected` 画 3dp `#4EA8FF` 左条 + 700 字重 + ▶；或删除死参数并另加标记 |
| UI-04 | medium | SettingsDrawer:52 / ChannelInfoBanner:41 / PlayerActivity:1243 仍 18dp 圆角 | 改为 0dp（信息条/抽屉）或 ≤4dp |
| UI-05 | low | 撤回延后恢复冷启网络解析争用 | 实测冷启 CPU/TTFF；若尖峰回归，改为“错峰 + 限并发”而非整段延后 |
| UI-06 | low | values-night 父主题与 values 不一致（pre-existing） | 统一为 `Theme.Material3.Dark.NoActionBar`，验证 splash/无 ActionBar |
| UI-07 | low | gfxinfo 探针只打印、不判定；`dumpsys gfxinfo … framestats` 摘要行解析可能不稳 | 增加 `dumpsys gfxinfo`（无 framestats）摘要解析 + 阈值 exit code；文档化 |
| UI-08 | low | 旧 `app_banner/banner/banner_bitmap/logo/ic_launcher_background` 残留；leanback 512 未引用可能被 shrink | 删除/重定向遗留资源；leanback 512 仅作 store 资产，放 docs/brand 而非 res |
| UI-09 | low | Android 12+ splash 未配置 | 补 `windowSplashScreenAnimatedIcon/Background`（API31+），API28 不受影响 |
| UI-10 | low | BackgroundSourceUpdater 注释仍写“t42 defer…避免冷启争用”，与 t50 撤回矛盾 | 更新注释/日志，避免误导 |

## 8. 只读命令与终态

只读命令：`git rev-parse HEAD`、`git status --short`、`git log --oneline`、`git show --stat 8e2e064e`、`git show 8e2e064e -- <code paths>`、`git show 983f66d:<file>`（对照 pre-t50）、`git rev-parse 8e2e064e:<file>`/`git hash-object`、源码与资源 grep（RoundedCornerShape/shadow/selected/旧资源引用）。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 本报告完成后停止读取目标源码；后续变化须新 diff 证据快照，不自动纳入本次 pass。
