# GOLDEN_SPEC — IQTV Lite 功能金标准（单一权威，2026-11-23）

> 版本 1.0 · t48 · researcher（只读调研，不改代码、不跑设备）
> **本文件是功能行为的唯一权威来源**。功能契约此前散落在 `FULLFIX_SPEC.md` / `V4_SPEC.md` / `INFO_CARD_V4_CONFIRM.md` / 各 `*_VERIFY.md` / `*_REVIEW.md`，彼此存在矛盾（典型：一次 OK 可选 vs 7s 自动消失、RIGHT 进列表 vs 打开设置、焦点蓝 vs 灰白）。**冲突一律以本文件为准**；各历史文档只作来源追溯，不再作为判定依据。
> 基线：HEAD `b49a736`（vc10232）。设备 TR100-G9 / API28 / 1080p / 240dpi。
> 配套：`docs/REGRESSION_CHECKLIST.md`（把本文件每条落成可执行检查项）。修复根因见 `docs/INTERACTION_REGRESSION_SPEC.md`（t47）。

---

## 0. 如何读 / 冲突消解总表

每条给「**正确行为 / 错误行为 / 判定方法 / 来源**」。判定方法优先真机（dump/日志/截图），纯逻辑可由 JVM 单测判定。

| 冲突主题 | 历史矛盾 | **本文件裁定** | 章节 |
|---|---|---|---|
| 焦点底色 | V4 早期蓝底 `#2F6BFF22` vs t41 改回灰白 | **焦点底=灰白 `#FFFFFF14`；蓝色只表"选中台"文字/进度** | §1 |
| 一次 OK vs 7s | t32 一次 OK 进 FOCUSED "不隐藏" vs 用户要 7s 消失 | **一次 OK 直接 FOCUSED；FOCUSED 与 IDLE 都 7s 无操作自动收起（FOCUSED 仅在用户静止时）** | §4 |
| RIGHT 语义 | 旧=打开设置 vs V4§3=全部频道进列表 | **默认 RIGHT 在播放器 PLAYER_ONLY=打开设置；仅"分组列内/全部频道列表未进入"时进频道列表；用户可任意重绑，绑定优先** | §1.3 / §5 |
| 菜单高度 | V3 300dp vs V4 "只有 logo 行" | **目标 ≈96dp logo 行 + 少量 padding（≤140dp）；最近 4 logo 内联不单独占大行** | §4 |
| 帧率来源 | 固定 25fps vs 真实推断 | **优先 Format.frameRate → 帧窗口推断 → fallback 25；组播源无帧率时按"未知即隐藏"或 fallback 显示，属已知限制** | §6/§7 |

---

## 1. 遥控与焦点

### 1.1 焦点视觉（t41/t44 定案，冲突以本节为准）
- **正确行为**：所有可聚焦行/卡（选台、设置、音轨、质量、最近观看对话框、信息卡最近卡）聚焦底色 = 灰白 `BrandFocus.FocusBgColor = #FFFFFF14`（像素实测 `(56,59,56)`）；**当前播放/选中台的台名 = 品牌蓝 `#2F6BFF` 字**（像素 `(38,85,215)`），进度填充仅选中台为蓝；非焦点行白字、蓝像素 0；无硬边框、无蓝框、0dp。
- **错误行为**：焦点底变蓝（`#2F6BFF22`）、任何行都用蓝字、出现蓝色描边/大方框。
- **判定方法**：`uiautomator dump` + 截图取像素：焦点行 bg 中性灰白、选中台名蓝、其他行 0 蓝；`check_info_card.py`/`check_settings_ia.py` 无 border 断言通过。
- 来源：`63a1442`(t41/t44 合并)、`docs/STARTUP_FIX_VERIFY.md §3⑤`。

### 1.2 功能→按键全映射（TiviMate 式）
- **正确行为**：设置页「遥控器与操作」以功能列表展示，每个功能可绑任意键（短按/长按分别绑定）；新绑定 `remote_action_bindings_v1` 序列化持久化；改后立即生效（无需重启）；自定义键在通用 fallback 中可触发（t10）；老偏好可迁移；「恢复默认」清回默认表；默认语义表：

| 功能 | 默认键（短按） | 默认键（长按） |
|---|---|---|
| 上一个频道 | DPAD_UP(19)/CH_UP(166)/PAGE_UP(92) | — |
| 下一个频道 | DPAD_DOWN(20)/CH_DOWN(167)/PAGE_DOWN(93) | — |
| 打开频道列表 | DPAD_LEFT(21) | — |
| 打开设置 | DPAD_RIGHT(22)/MENU(82)，**但受 §1.3 特例约束** | — |
| 显示信息卡 | DPAD_CENTER(23)/ENTER(66)/NUM_ENTER(160) | — |
| 最近观看 | LAST_CHANNEL(229) | — |
| 返回直播 | — | BACK(4)（回看中） |
| 快退/快进 | LEFT/RIGHT（回看上下文） | — |

- **错误行为**：设置只有固定二选一开关；绑定的自定义键按下无反应；改键后要重启才生效。
- **判定方法**：`RemoteBindingsTest`（默认/迁移/冲突/序列化）+ 真机绑定 F1→切台、改键即时生效。
- 来源：`737d5ff`/`f762bf4`/`9105aa8`、`docs/REMOTE_HABITS_SETTINGS*.md`。

### 1.3 RIGHT 特例（冲突裁定，以本节为准）
- **正确行为**：`resolveRight` 仅在「焦点在分组列」或「尚未进入频道列表的『全部频道』上下文」时把默认 RIGHT 特例为 OPEN_DRAWER；**播放器常规 PLAYER_ONLY 且已进列表时 RIGHT=打开设置**；用户显式重绑 RIGHT 时永远尊重用户绑定；回看中 RIGHT=快进不受影响。
- **错误行为**：默认播放器按 RIGHT 永远只进列表、设置无入口；用户自定义绑定被特例吞掉。
- **判定方法**：`RemoteBindingsTest.resolveRight` 四象限用例；真机：播放器 RIGHT→设置、分组列 RIGHT→进频道列表、回看 RIGHT→快进。
- 来源：`318fbd9` V4§3、`RemoteBindings.kt:249-260`、`docs/V4_DEVICE_VERIFY.md ③`。

---

## 2. BACK 语义（分层优先级，任何层不得越级 finish）

**正确行为**（自上而下，命中即消费并返回 true）：
1. 音频框开 → 关音频框
2. 最近列表开 → 关最近列表
3. 质量/线路框开 → 关框
4. 频道抽屉开 → 关抽屉（任意列一次 Back 全关）
5. 设置二级页 → 退一级（只退一层）
6. 设置一级 → 关设置抽屉
7. 信息卡 FOCUSED → 退聚焦到 IDLE（卡片仍在）
8. 信息卡 IDLE（可见）→ 关卡片到 HIDDEN
9. 回看中 → 第一次 toast「再按一次返回直播」、2s 内第二次返回直播
10. 播放器前台 → **双按退出**：第一次 toast「再按一次退出app」、2s 内第二次才 `finish()`

- **错误行为**：确认菜单/信息卡可见时按 BACK 直接退出 app；单次 BACK 无 toast 直接 finish；任何下层跳过守卫越级 finish。
- **判定方法**：`BackKeyPolicyTest`（每层→对应 CONSUME 动作，永不 PASS_TO_SUPER）；真机逐层按 BACK 断言 `mResumedActivity` 仍 PlayerActivity 直到第 10 层第二次；toast 文本断言。
- **根治方向**：合并 `isInfoBannerOpen`/`isInfoCardFocused` 双镜像为单一状态机裁决，所有状态 CONSUME（见 `INTERACTION_REGRESSION_SPEC.md §1/§2`）。
- 来源：`PlayerActivity.kt:511-574`、`docs/FOCUSED_FIX_VERIFY.md 断言4`、`t47 §1-2`。

---

## 3. 换台 / 抽屉

- **正确行为**：
  - 换台时若抽屉已打开：**UI 原地保持**——不闪回首页、不重新打开、不重置滚动、不重算开窗；正在播放台变化只更新"选中台蓝字"，不动浏览焦点；
  - 打开抽屉直接定位当前/焦点台、无首帧钳制闪烁、打开即有焦点高亮（不需按键）；
  - **TiviMate 焦点居中（t72，以本节为准，消解旧"焦点贴上方 2 行"描述）**：呼出换台时当前台为焦点且位于**可视区正中间**（target = focus - DRAWER_SCREEN_ROWS/2，DRAWER_SCREEN_ROWS=12 → 居中约第 7 行）；上下滚动时**焦点始终居中、只是列表在动**（焦点移 1 行 → 列表反向滚 1 行使焦点回中间），丝滑无整页翻；长按连续滚焦点一直居中、一路顺滑到两端；边缘（开头/末尾）target 钳到 `[0, count-visibleRows]`，到顶/底无法居中属正常、不越界不崩；小 count 安全返回 0；
  - **节目单区域透明区分、无分割线（t72，以本节为准）**：节目单区用**更透明的静态背景**（graphicsLayer alpha 缓存，不逐帧重绘）与选台区视觉区分；**不得有任何 1dp 竖线/Spacer 分割线/边框**；节目文字保持不透明；
  - 滚动均匀平滑（`animateScrollBy`），不整页翻（长按走瞬时落位目标窗口，单点 ±8 对称逐帧）；
  - 按组记忆焦点（会话内），切组从该组第一台开始、聚焦即展示；
  - 分组列 UP/DOWN 焦点变化 → 右侧频道列**立即**显示该组频道（不需 OK）；→ 键从分组列进入频道列并**收起分组列**、频道列满宽；
  - 「全部频道」按 → 能进频道列表（V4 已修，见来源）；选台不需要翻页；
  - 复用缓存：同一内容版本复用已解析快照、已组合行、内存 logo 位图，切组不重解析全量频道/EPG。
- **错误行为**：换台导致抽屉闪回首页/重开；下拉一会滚一会整页翻；每次焦点变化重解析；分组要按 OK 才切换；→ 只翻页进不去。
- **判定方法**：`drawer_scroll_correct` 日志一次且不重入；dump 可见行连续平滑；切组无 `XmlTvParser`/`M3uParser` 日志；真机用户路径（冷启首开/换台/点分组/全部→/下拉）。
- 来源：`611dfde`(t29 单真源)、`37a2f61`(t26)、`docs/V4_DEVICE_VERIFY.md ①②③`、`docs/SCROLL_REFACTOR_VERIFY.md`、`t47 §3-4`。

---

## 4. 确认菜单（信息卡）

- **正确行为**：
  - **OK 模式默认 DIRECT**：一次 OK 出卡即进 FOCUSED、左右键立即选最近观看、OK 跳台；设置可切 TWO_STEP（第一次 OK 出 IDLE，第二次才进 FOCUSED）；空/1张/回看/快进不聚焦；
  - **7s 无操作自动收起**：IDLE 与 FOCUSED 都是 7s（FOCUSED 仅在用户静止 ≥7s 时，正在 move/switch 不收）；BACK 可提前取消；
  - **徽标**：右上 TopEnd 紧凑一排、单行不占行，含 分辨率(FHD/HD/SD/4K)·帧数(fps)·声道数字(2.0/5.1)·SDR·编码(264/265)，未知按"未知即隐藏"；
  - **台标下**：只有 `N. 台名` + 本台分组小字，无 MPEG-L2/编码；
  - **右侧**：节目名单行省略、起止/剩余、3dp 进度；播放信息白色非蓝；
  - **最近观看**：4 个 logo 内联、无 logo 用文字/首字回退；LEFT/RIGHT 聚焦、OK 跳台；高亮=真实焦点=OK 目标三者一致；
  - **高度** ≈ logo 行 96dp + 少量 padding（实测 ~223dp，目标 ≤240dp，根治目标 ≈96+少量）。
- **错误行为**：一次 OK 要按两次才能选；FOCUSED 永不消失；徽标占一整行/换行；台标下出现编码；节目名蓝色；最近观看大段独立行或不可切；高度 >300dp。
- **判定方法**：`InfoCardPolicyTest`/`InfoCardBadgeTest`/`InfoCardFocusTest` + dump（徽标 TopEnd 单行、`info_card` bounds 高、4 卡、台标下文本）+ 日志（`ok-open DIRECT focus`、`move index`、`switch idx`、`hide reason=timer` 7±0.5s）。
- 来源：`0a1c1d2`(t32)、`3ca71b3`(t35 FOCUSED 闭环)、`docs/INFOCARD_FIX_VERIFY.md`、`docs/FOCUSED_FIX_VERIFY.md`、`t47 §6-7`。

---

## 5. 台标资源

- **正确行为（用户 2026-11-23 明确的唯一判据）**：app 方块图标必须与
  `iqtvlite-logo-assets/icon/icon-512.png` **像素级一致（按比例缩放后）**——这才是正确重心。
  参考属性：512×512 RGBA、alpha bbox (0,0,512,512)、四角透明 (0,0,0,0)、中心色 (21,25,35,255)=#151923。
  `src/tivimini/res` 各密度 `ic_launcher.png`（48/72/96/144/192）必须由 icon-512 缩放生成；adaptive 的
  foreground 不得再叠加任何 +10/+35 偏移或旧版 lockup，合成后视觉等价于 icon-512 缩放版；round 与方形同源；
  tv_banner 与 assets 一致；graphite `#151923` + accent `#2F6BFF` 扁平无渐变；不改 applicationId。
  **开屏（t55）**：`drawable-nodpi/splash_mark.png` 必须来自正式字标
  `iqtvlite-logo-assets/logo/logo-white-transparent.png`（白色宽幅字标，深色 Obsidian 底居中不拉伸），
  **不得是旧方块占位图**（旧图 512×512、bbox≈286×286 正方形）；SplashActivity 用 postDelayed
  给出 ~800ms 非阻塞可见时长（不 sleep），冷启仍能进 PlayerActivity 无 VerifyError。
- **错误行为**：res 是旧版资源（重心偏）、foreground 仍带 +10/+35 偏移、round 是另一套图、banner 不一致、字标变形、"看起来像但非像素级一致"；开屏仍显示旧方块占位图、或开屏时长为 0（字标看不到）、或用阻塞 sleep 拖慢冷启。
- **判定方法**：①各密度 ic_launcher.png 与 icon-512 缩放版做像素比对（尺寸/四角透明/中心色/重心一致，非目视"差不多"）；②真机桌面图标截图与 icon-512 缩放版比对；③`check_brand_assets.py` 校验来源 hash。
- 来源：用户 2026-11-23 明确要求（icon-512.png 像素级一致）、`iqtvlite-logo-assets/README.md`、`t47 §5`（当前 res 为旧版、7 文件 hash 不一致，待修）。

---

## 6. 性能与稳定性

- **正确行为/已知上限**：
  - TTFF/zap：`p50≈923ms`、`p95≈1453ms`（CI101xx 基线），失败率 0；
  - 抽屉 jank：**0 个 ≥300ms 巨帧**、50th 32–38ms、Draw ≈2.5ms（CI10204）；
  - 冷启 rebuffer：已知上限 ~1 次；后台刷新与首帧重叠用同机同源 A/B；
  - **API28 冷启动不 VerifyError**：每次出包必冒烟（第 0 项），`VideoPlayerScreen` 参数已由 35→9 拆分根治，静态绿**不算**，必须真机冷启进 PlayerActivity；
  - 零拷贝 SurfaceView 底层、MP2 FFmpeg 扩展、LoadControl 2500/6000/500/2000、三守卫（overlay/digit/focus）、滚动单真源（`drawer_scroll_correct`）保持。
- **错误行为**：VerifyError/灰屏/ANR/大量巨帧/滚动多真源互相打架。
- **判定方法**：`gfxinfo framestats`、`logcat` Fatal/VerifyError=0、CI 门禁全绿 + 真机冷启冒烟。
- 来源：`63a1442`、`docs/STARTUP_FIX_VERIFY.md`、`docs/FINAL_PACKAGE.md`、`docs/CI_FINAL_DRAWER_V2.md`。

---

## 7. 已知限制（如实，不判失败）

- 组播源 `Format.frameRate=-1`，帧窗口推断回调不规律 → fps 徽标来源常为 `fallback 25`（合法分支，非逻辑 bug）；`docs/STARTUP_FIX_VERIFY.md ①`。
- 166/167 频道键被设备系统服务（InputMonitor/AdbSwitchService）拦截，adb 注入无法验证；D-pad 换台正常。
- MENU(82) 被本机 launcher 拦截（弹应用选择器），设置入口需靠 RIGHT/自定义键兜底。
- MP2 听感无音频采集手段，只能验证解码/声道 dump。
- HANDOFF 第二批（S2–S6/U2–U4）强时序/并发/回看场景在单一稳定源上无法可靠构造，`docs/FULLFIX_REVIEW.md` 记为已知限制。
- `remote_ok` 等历史 Prefs 残留可能影响测试，判定前从空 Prefs 起更干净。

---

## 8. 追溯索引（来源 → 决策）

| 来源 | 固化到本文件 |
|---|---|
| `INFO_CARD_V4_CONFIRM.md` | §4 菜单布局、§5 logo、§3 分组 |
| `V4_SPEC.md` / `V4_DEVICE_VERIFY.md` | §1.3 RIGHT 特例、§3 抽屉、§4 菜单 |
| `INFOCARD_FIX_VERIFY.md` / `FOCUSED_FIX_VERIFY.md` | §4 OK DIRECT/7s/FOCUSED 闭环 |
| `STARTUP_FIX_VERIFY.md` (t46) | §6 VerifyError 冒烟、§1.1 焦点灰白/蓝字 |
| `FULLFIX_SPEC.md` / `FULLFIX_REVIEW.md` | §1.2 遥控全映射、§7 已知限制 |
| `INTERACTION_REGRESSION_SPEC.md` (t47) | §2 BACK、§3-4 抽屉、§5-8 回归根因 |

> 当历史文档与本文件冲突时，**以本文件为准**；本文件更新需经 captain 确认并同步 `REGRESSION_CHECKLIST.md`。
