# 视觉丰富度恢复与 Logo / 无边框焦点 独立复查（t81 / run87）

任务 t82（attempt 1），designer2，2026-11-20（Asia/Hong_Kong）。**结论：needs_revision。** 行高/双行/时间/NOW/无蓝框焦点已按 UI v3 恢复，焦点 selector 不 rebind，Logo 异步/LRU/占位/回收取消的**框架正确**；但**台标实际加载的是频道流地址而不是 `channel.logoUrl`（节目行 url 为 null）**，导致频道 Logo 基本加载失败、节目行 Logo 永远占位——t81 的主目标“恢复台标”未真正生效；另有面板 2–4dp 未落地、2:1 解码盒 + `CENTER_CROP` 裁切非方形 Logo、Logo 并发无上限等。修复后复审。

> 本复查只读固定 commit `84f2bc8b`（工作树 clean）。模型不能读图，视觉结论仅基于源码/尺寸。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`，分支 `integration/tivimini-baseline`；HEAD=`33d5811`（docs-only），工作树 clean；`84f2bc8b..HEAD` 无代码改动。
- 被审查 commit `84f2bc8b4f5ed0082c78652f4aa958dabe068824`（t81），1 文件 +133/−33。
- 读取内容身份（git blob @84f2bc8b）：

| 文件 | blob |
|---|---|
| [NativeSidebarView.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt) | `496ee5fff678bc7c173a9457987d3fbb7d43284f` |

- 候选：主 10,208,256B SHA256 `add07266…7339`，test 502,955B `80d22766…e657`，签名 `29473c96…c339`，run 44684317240(run87)，10087。未真机验证。
- 参考规格：[UI_VISUAL_SPEC_TIVIMATE.md v3](../../iqtv/docs/UI_VISUAL_SPEC_TIVIMATE.md)。

## 2. 频道行 / 节目行 Logo — **blocker（主目标未生效）**

- 频道行：`RowAdapter(RowStyle.CHANNEL, dp(64))`；行内 accent 2dp + `ImageView` 40×40dp + 双行（`FocusWeightTextView` 16sp、副行 12sp `#C7D0DD`）；行方角；固定 64dp。结构符合 v3。
- 节目行：`RowAdapter(RowStyle.PROGRAM, dp(52))`；accent + `ImageView` 20×20dp + 时间列 + 节目名 15sp；NOW 用 `state_activated` 琥珀、播放用 `state_selected` 蓝。结构符合 v3。
- **但 Logo URL 取错（LOGO-01 blocker）**：`bindLogo` 用 `val url = row.url ?: ""`（[NativeSidebarView:211](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L211)）。`SidebarRow.url` 在 `buildChannelRows` 被赋值为 **`channel.url`（直播流地址）**，不是 `channel.logoUrl`；`SidebarRow` 也没有 `logoUrl` 字段。因此：
  - 频道行会把 **m3u8/ts 流地址** 传给 `LogoLoader.load` → 解码失败/占位，且对 40 个频道发起无意义的网络请求（IO/失败风暴）；
  - 节目行 `programsFor` 未设置 `url`（null）→ `bindLogo` 直接占位，**节目行 Logo 永远不显示**。
  - 这与 t81 docs「40×40 台标 / 20×20 台标」和 UI v3 §7 不符，属于**主目标未生效**。
- **修复**：`SidebarRow` 增加 `logoUrl: String?`；`buildChannelRows` 设 `logoUrl = channel.logoUrl`；`programsFor` 设 `logoUrl = channel.logoUrl`；`bindLogo` 改用 `row.logoUrl`（`row.url` 继续只做频道身份/catchup，不传给 LogoLoader）。`Channel.logoUrl` 已存在（[Channel.kt:7](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/Channel.kt#L7)）。

## 3. Logo 解码/裁剪 — 2:1 盒 + CENTER_CROP 会裁非方形台标（medium）

- 解码盒：`w = dp(80/40)`、`h = dp(40/20)`（[bindLogo](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L218)）→ 频道 2:1（80×40dp），节目 2:1（40×20dp）。
- `ImageView.scaleType = CENTER_CROP`（[:124](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L124)）。`LogoLoader.scaleToBoxCenter` 会把源等比放进 2:1 盒（方形源 → 80×80 居中，左右透明），`CENTER_CROP` 再按 40×40dp 视图裁切；**宽幅/非方形台标会被左右裁掉**（v3 要求 `centerInside` 等比完整显示）。
- 修复：解码盒改**方形**（频道 80×80px、节目 40×40px，按 density 换算），`scaleType = CENTER_INSIDE`/`FIT_CENTER`；避免 2:1 盒 + `CENTER_CROP`。同时可把 LRU 内存从 2:1 盒降回方形（更省）。

## 4. 信息条 — pass

- `ChannelInfoBanner` 仍用 `ChannelLogo(logoUrl = channel?.logoUrl)` + 频道名/节目/时间（[ChannelInfoBanner.kt:64](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/ChannelInfoBanner.kt#L64)）；t81 未改，**保留台标与频道信息**。符合。

## 5. 焦点 / 行方角 / 面板圆角

- **焦点无蓝框（pass）**：行背景 `StateListDrawable(state_focused→#14FFFFFF)`；主文字 `ColorStateList(focused→#F2F5FA)` + `FocusWeightTextView` medium→bold；副行 `#C7D0DD`（频道）/`#A9B4C7`（时间）；无 border/描边/外发光；焦点只改 drawable/typeface，不 rebind。
- **行方角（pass）**：行 0dp；Logo 磁贴 2dp；日期 chip 2dp（沿用）。
- **面板 2–4dp（fail）**：`panel` FrameLayout 透明、`base` 直接 `setBackgroundColor(0xE6161B22)`（[:330–334](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L330)），**没有任何 `cornerRadius`**；t81 docs 却声称「panel container 2–4dp」。任务点 3 明确要求面板 2–4dp。修复：给 base/panel 设 `GradientDrawable` 2–4dp 圆角（或 `ViewOutlineProvider`），行仍 0dp。
- **无大阴影/模糊/硬框架（pass）**：无 shadow/elevation/blur/border。

## 6. 保留项 / 回归 / 体积 — pass

- 633dp 基座 + groups 151 / dates 120 覆盖层；DiffUtil；EPG 移出 `SidebarState` equality（`epgVersion`）；根透明；回看 owner B；日期分层；零拷贝基座/灰屏、LoadControl `1000/6000/300/1000`、频道键 166/167/92/93、稳定 key、SR、JNI/R8/UA、硬解 OMX.goke 未改；APK 10,208,256B ≤ 30MB。

## 7. Logo 性能 — 框架正确，但有并发/生命周期加固点

- **无主线程阻塞（pass）**：`getInMemory` 仅同步 LRU 查表（廉价）；未命中走 `LogoLoader.load(appCtx, url, w, h)` 的 `scope.launch`（Main 启动，内部 IO/Default 解码），回调 `holder.boundUrl == url` 才设图；`onViewRecycled` 取消 job。
- **不整列重绑（pass）**：Logo 只在 `onBindViewHolder` 绑定；到达只 `setImageBitmap`（固定 dp 盒子，不重排），无 `notifyItemChanged`/`notifyDataSetChanged`；焦点移动不 rebind。
- **风险（LOGO-09 medium/low）**：**无并发上限**。抽屉打开时可见频道/节目行约 20–30 行，若缓存未命中会同时发起 20–30 个 `LogoLoader.load`（网络+解码），可能造成 IO/CPU 突发与打开瞬间卡顿，影响 run87 的 jank 对照。建议加 **2–4 并发信号量**（或滚动停止后加载/预取队列），并保留 LRU/磁盘缓存。
- **风险（LOGO-08 low）**：每个 `RowAdapter` 创建 `CoroutineScope(Dispatchers.Main + SupervisorJob())`，未在 view detach/adapter 销毁时 `cancel()`；短任务影响小，但建议在 `NativeSidebarView.onDetachedFromWindow` 统一取消。
- 其他低风险：占位是 `0x22FFFFFF` 2dp 圆角块，无首字母/`#1E242E`（LOGO-06）；频道行“播放中”只把 `▶` 前缀进主文字，无右侧「播放中」10sp（LOGO-07）；节目行时间列 78dp、标题起点 118dp（v3 为 42dp/86dp，LOGO-04）；分组行 40dp（v3 48dp，LOGO-05）。

## 8. 放行结论与门禁

- **源码结论：needs_revision**。行布局/双行/时间/NOW/无蓝框焦点/selector 不 rebind 已达成；但**台标 URL 取错（blocker）**导致频道 Logo 基本不显示、节目行 Logo 永远占位；面板 2–4dp 未落地；2:1 盒 + CENTER_CROP 会裁非方形 Logo；Logo 并发无上限。
- 修复后 acceptance2 在 exact pair（主 `add07266…`/test `80d22766…`/10087）验证：频道/节目行台标真实显示、宽幅 Logo 不裁、面板 2–4dp、焦点无蓝框/字重提升、Logo 异步不卡/回收不闪、`sidebar_jank_probe` 对照 CI10078/CI10086 确认 jank 未明显回退、视频 dropped0、频道键、SR、冷启/CPU/PSS、品牌。

## 9. 缺陷与可执行修复

| id | 严重度 | 问题 | 修复 |
|---|---|---|---|
| LOGO-01 | **blocker** | `bindLogo` 用 `row.url`（频道流地址）当 Logo URL；节目行 `url` 为 null → 频道 Logo 加载失败/网络风暴，节目行 Logo 永远占位；`SidebarRow` 无 `logoUrl` | `SidebarRow` 加 `logoUrl`；`buildChannelRows`/`programsFor` 设 `logoUrl = channel.logoUrl`；`bindLogo` 用 `row.logoUrl`；`row.url` 只做频道身份/catchup |
| LOGO-02 | medium | 解码盒 2:1（80×40/40×20dp）+ `CENTER_CROP`，宽幅台标被左右裁切 | 方形解码盒（80×80/40×40px 按 density），`CENTER_INSIDE`/`FIT_CENTER` |
| LOGO-03 | medium | 面板容器 2–4dp 未实现（base/panel 方形纯色），docs 声称已做 | base/panel 用 `GradientDrawable` 2–4dp 圆角；行仍 0dp |
| LOGO-04 | medium/low | 节目行时间列 78dp、标题起点 118dp；v3 为 42dp/86dp，标题空间被压缩 | 时间列 42dp（或两行 HH:mm），标题起点约 86dp |
| LOGO-05 | low | 分组行 40dp，v3 为 48dp | 分组行 48dp（或明确接受 40dp 并记录） |
| LOGO-06 | low | 占位为 13% 白 2dp 块，无首字母/`#1E242E` | 占位改 `#1E242E` 2dp 磁贴 + 首字母/品牌 mark |
| LOGO-07 | low | 频道行“播放中”仅 `▶` 前缀，无右侧「播放中」10sp | 右侧加 `▶` 12sp + `播放中` 10sp `#A9B4C7`（或明确接受前缀） |
| LOGO-08 | low | 每 adapter 的 CoroutineScope 未取消 | `onDetachedFromWindow`/adapter 销毁时 `scope.cancel()` |
| LOGO-09 | medium/low | Logo 并发无上限，抽屉打开可能 20–30 个 load 同时发起 | 2–4 并发信号量/预取队列；保留 LRU/磁盘；设备对照 jank |
| LOGO-10 | low | 节目行无 2dp 进度条（v3 可选） | 可选加 2dp 进度（轨道 `#FFFFFF1F`/填充 `#4EA8FF`） |

## 10. 只读命令与终态

只读命令：`git rev-parse HEAD`、`git status --short`、`git show --stat 84f2bc8b`、`git show 84f2bc8b`（全 diff）、`git rev-parse 84f2bc8b:<file>`、源码读取（NativeSidebarView SidebarRow/RowAdapter/bindLogo/onCreateViewHolder/buildChannelRows/programsFor、Channel.logoUrl、ChannelInfoBanner）。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 本报告完成后停止读取目标源码；后续提交须新 diff 证据快照。
