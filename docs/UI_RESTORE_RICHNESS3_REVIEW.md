# 时间列两行 / 台标解码像素 / 低危视觉 独立复查（t88 / run89）

任务 t89（attempt 1），designer2，2026-11-20（Asia/Hong_Kong）。**结论：源码层可接受（pass）**。t85 的两个 medium（时间单行被裁、解码 2× 超采样）与低危 LOGO-12/13/14/06/07 均已落地；LOGO-01/02/03/09 保留；真机/视觉未验证。剩余仅为密度自适应、4dp 细节等 low 加固，不阻断 acceptance2。

> 本复查只读固定 commit `87023bb`（工作树 clean）。模型不能读图，视觉结论仅基于源码/尺寸。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`，分支 `integration/tivimini-baseline`；HEAD=`dbdc8ee`（docs-only），工作树 clean；`87023bb..HEAD` 无代码改动。
- 被审查 commit `87023bb79ea29ec3b6ea2e5c4f23241474634b34`（t88），1 文件 +65/−24。
- 读取内容身份（git blob @87023bb）：

| 文件 | blob |
|---|---|
| [NativeSidebarView.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt) | `5507bb858b8d453999a73888fbf79ec341581a97` |

- 候选：主 10,208,256B SHA256 `6e457b6f…84eb`，test 502,955B `3b38bb3a…4a5a`，签名 `445d918f…812c`，run 44693489041(run89)，10089。未真机验证。

## 2. LOGO-04 两行时间列 — 已修复（pass）

- `timeBox` = 垂直 `LinearLayout`，宽 **42dp**，`Gravity.CENTER_VERTICAL`；`timeStart` **12sp `#C7D0DD`**、`timeEnd` **11sp `#A9B4C7`**，均 `maxLines=1` + `ellipsize=END`（[:159–168](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L159)）。
- 文本按 `secondary.split("-", limit=2)` 拆成 start/end（[:232–235](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L232)），不再单行 `HH:mm-HH:mm` 被裁。
- 起点：program `startX = 12 + 20 + 12 = 44`，时间 44..86，**标题 86dp**（[:153](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L153)）。符合 v3 §4.2。
- 小偏差：v3 时间列 x40，实际 x44（4dp，见 LOGO-16）；不影响可读性。

## 3. LOGO-11 解码像素 — 已修复（pass）

- `w = if (CHANNEL) 80 else 40`、`h` 同（**raw px**，[:264–265](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L264)），不再是 `dp(80)`×density 的 2× 超采样；`CENTER_INSIDE` 保留。
- density 2（1080p TV）时正好等于视图 40dp/20dp（80/40px）；LRU96 位图缓存从约 12MB 降到约 3MB，PSS/GC/解码成本明显下降。
- **低风险（LOGO-15）**：raw 80/40px 不随 density 变化；density 3（4K）时视图 120px 会被 80px 上采样、略糊。1080p 测试机 density2 不受影响；建议改为 `(logoSz * density).toInt()` 做密度自适应（density2 结果不变）。

## 4. 低危视觉 — 已落地（pass）

- **LOGO-12**：logo `marginStart=12dp`；channel `startX = 12+40+12 = 64dp`（[:152–153](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L152)）；program `12+20+12=44`、时间 42 → 标题 86。符合 v3。
- **LOGO-13**：`base`/`groupsColumn`/`datesColumn` 均 `clipToOutline=true`（[:389–397](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L389)），4dp 圆角背景裁掉 accent 边角。
- **LOGO-14**：`timeStart`/`timeEnd` 均 `ellipsize=END`（[:161–165](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L161)）。
- **LOGO-06**：`logoBox` 内 `logoLetter`（channel 16sp / program 10sp `#C7D0DD`）叠在 `#1E242E` 占位之上；缓存命中/位图到达 `GONE`，无 logo/加载中 `VISIBLE`（[:152–168, 258–281](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L258)）。
- **LOGO-07**：选中频道主文本 `▶` 前缀 + 副标题 `" · 播放中"`（仅 secondary 非空时，[:225–230](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L225)）。
- 小风险：`logoLetter` 首字母用 `substringAfter(". ", row.text)`；`"1.CCTV"`（无空格）会取到 `"1"`（LOGO-18，low）；`timeStart/timeEnd` 的 `isDuplicateParentStateEnabled` 父级是 `timeBox`（非焦点），无实际作用但无害（LOGO-17，low）。

## 5. 保留项 / 回归 / 体积 — pass

- 真实 `logoUrl`（LOGO-01）、方形 `CENTER_INSIDE`（LOGO-02）、面板 4dp 圆角（LOGO-03）、`Semaphore(3)`（LOGO-09）均保留；`row.url` 仅身份/catchup。
- 焦点无蓝框：`StateListDrawable(state_focused→#14FFFFFF)` + `ColorStateList(INK/MUTED)` + `FocusWeightTextView` medium→bold；无 border/描边/发光；焦点不 rebind。
- 行 0dp / Logo 磁贴 2dp；信息条 `ChannelInfoBanner` 仍含 `ChannelLogo(logoUrl)` + 频道信息。
- 633dp 基座 + groups 151 / dates 120 覆盖层；DiffUtil；EPG 移出 `SidebarState` equality（`epgVersion`）；根透明；回看 owner B；日期分层；零拷贝基座/灰屏、LoadControl `1000/6000/300/1000`、频道键 166/167/92/93、稳定 key、SR、JNI/R8/UA、硬解 OMX.goke 未改；APK 10,208,256B ≤ 30MB。

## 6. 放行结论与门禁

- **源码结论：pass**。LOGO-04/11 与 LOGO-12/13/14/06/07 均已按 v3 落地；LOGO-01/02/03/09 保留；无回退。剩余 LOGO-15/16/17/18 为 low 加固。
- acceptance2 在 exact pair（主 `6e457b6f…`/test `3b38bb3a…`/10089）验证：两行时间 `HH:mm`/`HH:mm` 不裁切、标题起点 86、台标清晰不裁且 PSS/GC 改善、首字母占位/位图切换、播放中 `▶ + · 播放中`、panel 4dp 无 accent 露角、焦点无蓝框/字重；`sidebar_jank_probe` 对照 CI10078/CI10088 确认 jank 未明显回退；视频 dropped0、频道键、SR、冷启/CPU/PSS、品牌。

## 7. 低风险加固（不阻断）

| id | 严重度 | 问题 | 建议 |
|---|---|---|---|
| LOGO-15 | low | 解码 raw 80/40px 不随 density；density3（4K）视图 120px 会轻微上采样 | 改 `(logoSz * density).toInt()`（density2 不变），或 1.25× 折中 |
| LOGO-16 | low | 时间列 x44（v3 x40），标题 86 正确 | 可接受；若严格对齐 v3 可把 logo→时间间距 8dp |
| LOGO-17 | low | `timeStart/timeEnd` 的 `isDuplicateParentStateEnabled` 父级非焦点，无实际作用 | 可移除或忽略 |
| LOGO-18 | low | 首字母 `substringAfter(". ", ...)` 对 `"1.CCTV"`（无空格）取到 `"1"` | 用正则 `^\s*\d+[.、]\s*` 去前缀，或取第一个字母 |
| LOGO-19 | low | `clipToOutline` 会裁掉首/末行 4dp 内容（设计内） | 真机确认无文字/焦点底被切 |
| LOGO-20 | low | 信息条 Logo 与侧栏 Logo 解码尺寸/缓存 key 可能不同，重复缓存 | 统一 target 尺寸可进一步省内存 |

## 8. 只读命令与终态

只读命令：`git rev-parse HEAD`、`git status --short`、`git show --stat 87023bb`、`git show 87023bb`（全 diff）、`git rev-parse 87023bb:<file>`、源码读取（NativeSidebarView H/onCreateViewHolder/onBindViewHolder/bindLogo/clipToOutline）。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 本报告完成后停止读取目标源码；后续提交须新 diff 证据快照。
