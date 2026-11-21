# 日期覆盖层 / 筛选焦点 / selector 焦点微优化 独立复查（t73 / run85）

任务 t74（attempt 1），designer2，2026-11-20（Asia/Hong_Kong）。**结论：源码层可接受（pass）**。t71 的 blocker（日期覆盖层落位）已修复；633dp 面板+覆盖列、筛选后位置失效、RIGHT from Groups、selector 驱动焦点（无 rebind）均落地；保留项无回退。真机未验证。剩余为交互/视觉加固（RIGHT from Groups 未隐藏覆盖层导致焦点被盖、焦点字重未提升、now 文本不周期刷新等），建议真机前同批修。

> 本复查只读固定 commit `69c614be`（工作树 clean）。模型不能读图，视觉结论仅基于源码/dp/选择器。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`，分支 `integration/tivimini-baseline`；HEAD=`cb994c2`（docs-only），工作树 clean；`69c614be..HEAD` 无代码改动。
- 被审查 commit `69c614beff79368db704c5107ed4b242fd5e9543`（t73），2 文件 +70/−44。
- 读取内容身份（git blob @69c614be）：

| 文件 | blob |
|---|---|
| [NativeSidebarView.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt) | `243c0ca65c0bfad158d447f45c975fc8c318428c` |
| [PlayerActivity.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt) | `ac1e151d7ba2afff86a826c665be2d00aa619710` |

- 候选：主 10,208,256B SHA256 `a7e6daa9…c120`，test 502,955B `5628fd54…19d2`，签名 `ea0367a5…9ad`，run 44679621598(run85)，10085。未真机验证。

## 2. GAP2-01 日期覆盖层 — 已修复（pass）

- [NativeSidebarView init](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L183)：新增 **633dp 宽 `panel` FrameLayout**（Gravity.START，根透明），base 633dp 与覆盖列都放进 panel。
- base = channels 300dp + programs 300dp（+33dp slack），`#E6161B22`；groups 151dp `Gravity.START`；**dates 120dp `Gravity.START + leftMargin 513dp`（=633−120）→ 右缘对齐基座右缘**，不再落屏幕最右。
- groups/dates 均 `setBackgroundColor(0xE6161B22)` 不透明；根透明 → 覆盖列绝不悬在视频上/透明压画面。**符合 GAP2-01。**
- 注：dates 覆盖 x=513..633，覆盖 programs 右缘（programs 300..600）与 33dp slack；programs 主文本 marginEnd=96 → 结束于 504，不被 dates 覆盖；但 programs **secondary（时间，504..594）会被 dates 覆盖 513..594**（视觉小重叠，见 SIDEBAR3-05）。

## 3. GAP2-02 筛选后焦点 — 已按要求“重置/解析”（pass）

- [render:232–236](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L232)：结构签名变化时 `lastPositions.remove(Channels)`/`remove(Programs)`。
- Channels 回退 `defaultPosition` 按 `focusedChannelUrl ?: selectedChannelUrl` 解析（[focusColumn/defaultPosition](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L355)）；Programs 从首项（0）。任务允许“重置/按当前时间或选中节目”，**不再按旧索引错台/错节目**。
- 说明：`showGroups/showDates` 切换也在签名内，因此打开分组/日期也会清位置；channels 仍按 focusedChannelUrl 回到浏览台（OK），programs 回顶部（属“重置”，可接受但丢失浏览位置，见 SIDEBAR3-07）。

## 4. GAP2-03 显式左右 — 已加，但 RIGHT from Groups 未隐藏覆盖层（medium）

- [PlayerActivity:265](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L265)：`DrawerColumn.Groups -> focusChannels()` 已加；RIGHT Channels→Programs、RIGHT Programs→Dates、LEFT 链均在。
- **缺陷（SIDEBAR3-01）**：RIGHT from Groups 只 `focusChannels()`，**未 `setShowGroups(false)`**。分组覆盖层（x=0..151）继续覆盖频道列左缘，而频道行主文本 `marginStart=12`、accent 2dp 在 x=0 → **焦点行被分组覆盖层遮住**，用户看不到焦点。原 Compose 是“加宽列”（频道右移）所以无此问题；改成**叠加**后必须隐藏覆盖层。修复：`RIGHT Groups -> { sidebarController.setShowGroups(false); nativeSidebar?.focusChannels(); return true }`（与 LEFT Groups 对称）。
- 其余进出（LEFT Channels→groups、LEFT Groups→hide+channels、RIGHT Programs→dates、LEFT Dates→hide+programs）正确。

## 5. Jank 微优化 — 已落地（pass）

- **行背景**：`StateListDrawable(state_focused→#14FFFFFF, else transparent)`，在 `onCreateViewHolder` 设置一次；焦点变化不再 `setBackground`/分配/rebind（[NativeSidebarView:77–80](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L77)）。
- **文字色**：`ColorStateList(state_focused→#F2F5FA, else #A9B4C7)`，primary/secondary `isDuplicateParentStateEnabled=true`；焦点只重绘文字颜色（[:96–112](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/NativeSidebarView.kt#L96)）。
- **accent**：`StateListDrawable(state_activated→Amber, state_selected→Blue, else transparent)`，bind 时 `isSelected/isActivated`；2dp 方角。
- **▶ 合入 primary 文本**（预格式化），行 View 数 3（accent/primary/secondary）；`itemAnimator=null`、`setHasFixedSize(true)`、`setItemViewCacheSize(8)`、固定行高；`onFocusChangeListener` 只记录位置/更新 controller，不做 view mutation。
- `onRowFocused` 350ms 防抖 `removeCallbacksAndMessages` + 只 `renderProgramsOnly()`（节目/日期列），符合“焦点变化不 rebind 当前行/不重排”。
- **视觉 v2**：无 border/描边；焦点=背景 `#14FFFFFF`+文字 INK；选中 2dp 蓝+▶；NOW 2dp 琥珀；方角；无 shadow/elevation/blur。
- 低风险偏差：①焦点**字重未提升**（primary 固定 `sans-serif-medium`，普通/焦点同重；v2 期望 400→500/600，selector 无法改 typeface）——见 SIDEBAR3-02；②secondary 焦点色用 INK（与 primary 相同），v2 期望 `#C7D0DD`——见 SIDEBAR3-03。

## 6. 保留项 / 回归 / 体积 — pass

- 633dp 基座、DiffUtil、EPG 移出 `SidebarState` equality、根透明、回看 owner B、日期分层、视觉 v2 均保留。
- 零拷贝基座/灰屏、LoadControl `1000/6000/300/1000`、频道键 166/167/92/93、稳定 key、SR、JNI/R8/UA、硬解 OMX.goke 未改；APK 10,208,256B ≤ 30MB。

## 7. 放行结论与门禁

- **源码结论：pass（可接受）**。t71 blocker 已修；GAP2-02/03 按要求落地；selector 焦点微优化正确；无回退。
- **真机前建议同批修** SIDEBAR3-01（RIGHT Groups 隐藏覆盖层，否则 TIV-05 右回频道焦点不可见）；SIDEBAR3-02/03/04/05 为视觉/时效加固。
- acceptance2 在 exact pair（主 `a7e6daa9…`/test `5628fd54…`/10085）验证：633dp 基座+日期覆盖落位、RIGHT/LEFT 返回保位置（含筛选后）、RIGHT Groups 焦点可见（若未修则记录）、TIV-05/06/08/09/15、`sidebar_jank_probe.sh` 对照 CI10078/CI10085（目标 janky<80%/90th<200）、视频 dropped0、冷启/CPU/PSS、品牌。

## 8. 缺陷与可执行修复

| id | 严重度 | 问题 | 修复 |
|---|---|---|---|
| SIDEBAR3-01 | **medium** | RIGHT from Groups 只 focusChannels，未隐藏 groups 覆盖层；频道焦点行被 x0..151 覆盖层遮住，TIV-05 右回频道焦点不可见 | `RIGHT Groups -> setShowGroups(false); focusChannels()`（与 LEFT Groups 对称）；真机验证 |
| SIDEBAR3-02 | low | 焦点字重未提升（primary 固定 sans-serif-medium，普通/焦点同重；v2 期望 400→500/600） | 如需字重区分：自定义 TextView 在 `drawableStateChanged` 切 typeface（不 rebind），或接受“色+底”区分 |
| SIDEBAR3-03 | low | secondary 焦点色=INK（与 primary 同），v2 期望 `#C7D0DD` | secondary 单独 ColorStateList（focused→#C7D0DD） |
| SIDEBAR3-04 | low | 频道当前节目/NOW 标记在 build 时计算，无周期刷新；节目结束后可能短暂显示旧 NOW | 30s tick 触发 `renderProgramsOnly`（或 bind 时计算）；注意不要每帧 |
| SIDEBAR3-05 | low | dates 覆盖 x513..633 会盖住 programs secondary 时间（504..594） | 可接受；或 programs secondary 右移到 504 内/日期显示时隐藏时间 |
| SIDEBAR3-06 | low | `requestInitialFocus` fallback `channelsRv.requestFocus()` 对 isFocusable=false 的 RV 无效 | fallback 前设 `channelsRv.isFocusable=true` 或确保 holder |
| SIDEBAR3-07 | low | `render` 在 showGroups/showDates 切换时也清 lastPositions（programs 回顶）；属“重置”可接受但丢浏览位置 | 仅当 channelGroups/selectedGroup/selectedEpgDate/epgVersion 变化时清；overlay 切换不清 |
| SIDEBAR3-08 | low | `onRowClicked` 末尾 `render(controller.value)` 对 Channels 会早退（签名排除 selectedChannelUrl），行为正确；仅记录 | — |

## 9. 只读命令与终态

只读命令：`git rev-parse HEAD`、`git status --short`、`git show --stat 69c614be`、`git show 69c614be`（全 diff）、`git rev-parse 69c614be:<file>`、源码读取（NativeSidebarView init/column/RowAdapter/render/focusColumn/onRowFocused/programsFor、PlayerActivity dispatchKeyEvent）。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 本报告完成后停止读取目标源码；后续提交须新 diff 证据快照。
