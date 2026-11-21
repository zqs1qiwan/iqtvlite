# Logo 真实加载 / 方形解码 / 面板圆角 / 并发 独立复查（t84 / run88）

任务 t85（attempt 1），designer2，2026-11-20（Asia/Hong_Kong）。**结论：needs_revision。** t82 的 blocker（Logo 用直播流地址）已修复：`SidebarRow.logoUrl` + `channel.logoUrl` + `bindLogo` 用 `row.logoUrl`；方形解码 + `CENTER_INSIDE`、真实 4dp 面板圆角、`Semaphore(3)`、分组 48dp、`dispose()` 均已落地。但**节目行时间仍是单行 `HH:mm-HH:mm` 塞进 42dp，未按 v3 做两行，文字会被裁切**；且**解码尺寸按 dp 再乘 density（频道 160×160px）是视图的 2×，LRU 内存约 4×**，对 v7a PSS/jank 不利。修复后复审。

> 本复查只读固定 commit `c02c8fb1`（工作树 clean）。模型不能读图，视觉结论仅基于源码/尺寸。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`，分支 `integration/tivimini-baseline`；HEAD=`a09bfb6`（docs-only），工作树 clean；`c02c8fb1..HEAD` 无代码改动。
- 被审查 commit `c02c8fb11682c0f4ef13138ceee85af87f4a4db5`（t84），1 文件 +36/−11。
- 读取内容身份（git blob @c02c8fb1）：

| 文件 | blob |
|---|---|
| [NativeSidebarView.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt) | `845e5423dcb69ddcecb0ce3be440e3d4af297e38` |

- 候选：主 10,208,256B SHA256 `f13408a1…ac97`，test 502,955B `88660a60…cd30`，签名 `0975cc73…b67f6`，run 44686317357(run88)，10088。未真机验证。

## 2. LOGO-01（blocker）— 已修复（pass）

- `SidebarRow` 新增 `logoUrl`（[:65](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L65)）。
- `buildChannelRows` 设 `logoUrl = channel.logoUrl`（[:430](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L430)）；`programsFor` 设 `logoUrl = channel.logoUrl`（[:574](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L574)）。
- `bindLogo` 用 `val url = row.logoUrl ?: ""`（[:222](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L222)）；`row.url` 仅保留频道身份/catchup。**不再对流地址请求 Logo。**
- 空/无 logo → 占位；缓存命中直接设图；未命中异步 `LogoLoader.load`。符合。

## 3. LOGO-02 方形 + CENTER_INSIDE — 已修复（pass，但解码尺寸偏大见 LOGO-11）

- `scaleType = CENTER_INSIDE`（[:134](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L134)），宽幅台标不再被 `CENTER_CROP` 裁切。
- 解码盒 `w = dp(80/40)`、`h = dp(80/40)`（[:230–232](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L230)）→ 方形，符合“方形”；但 **dp 再乘 density**：density 2 时频道 160×160px、节目 80×80px，而 ImageView 只有 40dp/20dp（80/40px），即**解码是视图的 2× 超采样**。视觉更清晰，但内存/解码成本约 4×（LOGO-11）。

## 4. LOGO-03 面板圆角 — 已真实实现（pass）

- `panelBg()`：`GradientDrawable`，`cornerRadius = 4 * density`，`#E6161B22`（[:604](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L604)）。
- `base`（[:350](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L350)）、`groupsColumn`/`datesColumn`（[:355–356](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L355)）均使用；4dp 在 2–4dp 范围内。真实落地，非文档声称。

## 5. LOGO-04 节目行时间列 — **未完全修复（medium）**

- 数值已改：`timeWidth = dp(42)`（[:148](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L148)），`startX += 42 + 8` → 标题起点 86dp（[:151](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L151)）。数值符合 v3。
- **但时间内容仍是单行 `"HH:mm-HH:mm"`**（`programsFor` 的 `secondary`），11sp、`maxLines=1`、**未设 ellipsize**；42dp 只能容纳约 7 个字符，`20:00-21:00` 会被**直接裁切**（不是省略号）。v3 §4.2 要求 42dp 内**两行**：`HH:mm`（12sp）+ 第二行 `HH:mm`（11sp muted）。
- 修复：时间列改两行（start 12sp / end 11sp，等宽/tabular），或保留单行但加宽到 ~78dp 并设 `ellipsize=END`；两者择一，保证 `HH:mm-HH:mm` 可读。

## 6. LOGO-09 并发限制 — 已实现（pass）

- `logoGate = Semaphore(3)`（[:86](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L86)）；未命中缓存时 `logoGate.withPermit { LogoLoader.load(...) }`（[:243](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L243)）；缓存命中在闸外直接设图。`withPermit` 在取消/异常时释放，`onViewRecycled` 取消 job；`dispose()` 在 `onDetachedFromWindow` 取消 scope（[:327](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L327)）。符合 2–4 并发。
- 注：`Semaphore` 是**每 adapter** 一个（channels 3 + programs 3 = 6 并发），仍可接受；若要更保守可改全局 3。

## 7. 低危 — 基本落地

- 分组行 48dp（[:294](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L294)）；占位 `#1E242E` 2dp（首字母未画，doc 已注明）；`▶` 预置选中频道文本；`dispose()` 已接。低危项按 doc 接受。
- 小偏差：频道行 Logo marginStart 8dp、文本起点 56dp（v3 为 12/64，LOGO-12）；4dp 面板圆角下 accent 2dp 从 x0 画到矩形边，可能露出圆角缺口（LOGO-13）；时间 TextView 未设 ellipsize（LOGO-14）。

## 8. 保留项 / 回归 / 体积 — pass

- 焦点无蓝框：`StateListDrawable(state_focused→#14FFFFFF)` + `ColorStateList(focused→#F2F5FA)` + `FocusWeightTextView` medium→bold；无 border/描边/发光；焦点不 rebind。
- 行 0dp / Logo 磁贴 2dp；信息条 `ChannelInfoBanner` 仍含 `ChannelLogo(logoUrl)` + 频道信息（未改）。
- 633dp 基座 + groups 151 / dates 120 覆盖层；DiffUtil；EPG 移出 `SidebarState` equality（`epgVersion`）；根透明；回看 owner B；日期分层；零拷贝基座/灰屏、LoadControl `1000/6000/300/1000`、频道键 166/167/92/93、稳定 key、SR、JNI/R8/UA、硬解 OMX.goke 未改；APK 10,208,256B ≤ 30MB。

## 9. 性能风险 — 解码 2× 超采样（medium）

- `w/h = dp(80)`（频道）在 density 2 = **160×160px**，而视图 40dp=80px；`LogoLoader` LRU 96 项 → 96×160×160×4 ≈ **9.8MB**（若按视图 80×80 仅 ≈2.5MB），加节目 80×80（96×80×80×4≈2.5MB）总计约 **12MB** 位图缓存。t42 曾为 PSS 把 LRU 降到 96；本次 2× 超采样把内存放大 4×，对 API28 v7a 的 PSS/GC/jank 不利。
- 修复（LOGO-11）：解码目标用**视图像素**（频道 80×80px、节目 40×40px，即 `dp(40)`/`dp(20)`，或 `logoSz * density`），`CENTER_INSIDE` 不变；视觉仍清晰，内存/解码成本降 4×。若担心 1× 模糊，可用 1.25×（100/50px）折中。

## 10. 放行结论与门禁

- **源码结论：needs_revision**。LOGO-01/02/03/09 与低危项已修；剩 **LOGO-04 时间单行塞 42dp 被裁**、**LOGO-11 解码 2× 超采样**两个 medium，建议同批修 LOGO-12/13/14。
- 修复后 acceptance2 在 exact pair（主 `f13408a1…`/test `88660a60…`/10088）验证：频道/节目台标真实显示、宽幅不裁、时间 `HH:mm-HH:mm` 可读、面板 4dp 圆角、焦点无蓝框/字重、打开抽屉不突发卡顿、`sidebar_jank_probe` 对照 CI10078/CI10087 确认 jank/PSS 未明显回退、视频 dropped0、频道键、SR、冷启/CPU/PSS、品牌。

## 11. 缺陷与可执行修复

| id | 严重度 | 问题 | 修复 |
|---|---|---|---|
| LOGO-04 | **medium** | 节目时间仍单行 `HH:mm-HH:mm` 塞进 42dp（11sp、maxLines=1、无 ellipsize），会被裁切；v3 要求两行 start/end | 时间列两行（start 12sp / end 11sp muted，等宽）；或单行加宽 ~78dp + `ellipsize=END` |
| LOGO-11 | medium | 解码 `dp(80)/dp(40)` 再乘 density → 频道 160×160px、节目 80×80px，是视图 2×；LRU 内存约 12MB | 解码用视图像素（80×80/40×40），或 1.25× 折中；`CENTER_INSIDE` 保持 |
| LOGO-12 | low | 频道行 Logo x8/文本 x56（v3 x12/x64） | marginStart 12、startX 64（或记录接受 8/56） |
| LOGO-13 | low | 4dp 圆角面板下 accent 2dp 从 x0 画到矩形边，圆角处可能露出 | base/overlay 设 `clipToOutline`/padding，或首末行 accent 内缩 4dp |
| LOGO-14 | low | 时间 TextView 未设 `ellipsize` | 设 `ellipsize=END`，防裁剪 |
| LOGO-06 | low | 占位无首字母（doc 已注明） | 可加首字母/品牌 mark；或接受 |
| LOGO-07 | low | 播放中仅 `▶` 前缀，无右侧「播放中」10sp | 可加右侧标记；或接受 |

## 12. 只读命令与终态

只读命令：`git rev-parse HEAD`、`git status --short`、`git show --stat c02c8fb1`、`git show c02c8fb1`（全 diff）、`git rev-parse c02c8fb1:<file>`、源码读取（NativeSidebarView SidebarRow/bindLogo/onCreateViewHolder/programsFor/buildChannelRows/panelBg/dispose/onDetachedFromWindow）。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 本报告完成后停止读取目标源码；后续提交须新 diff 证据快照。
