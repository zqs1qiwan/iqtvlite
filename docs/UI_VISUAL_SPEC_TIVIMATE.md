# IQTV Lite UI 视觉规范 v4（Flat / Clean）— 基于 tivimini 原版架构，仅去圆角

> 版本 4.0 · 2026-11-20 · 设计负责人 designer2
> 依据：用户方向「基于回退版原版架构，做去圆角化的干净 UI，保留台标/名称/EPG 展示方式/双列 633dp/字号间距/颜色」；视觉基线 = **tivimini 原版 `c82593f`（PlayerDrawer）与 `8c93e1b`（信息条/设置/质量弹窗/加载/配置）**。
> **v4 取代 v2/v3。** v2/v3 的 40×40 台标、64/52 固定行高、品牌色、覆盖列、极简/方角改造**全部不作为基线**；v4 只在原版数值上改“圆角/胶囊/边角”，**其余一律不动**。
> 本模型不能看图；以下为可直接落地的 from→to 数值与“不可改动清单”，最终以 t99/acceptance 截图对照原版人工复核。

---

## 0. 一句话

**原版长什么样，v4 就长什么样；只把圆角/胶囊削平。** 不重排、不删内容、不改字号/颜色/台标/EPG/交互。

---

## 1. 基线声明（先读）

### 1.1 唯一视觉基线

| 页面 | 基线文件（原版） | 关键原版值 |
|---|---|---|
| 抽屉/分组/频道/节目/日期 | `docs/evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt`；回退版 `c82593f` | 双列 633dp、行 12dp 圆角、台标 64×40、行 padding、`titleMedium/bodySmall/bodyMedium`、`surfaceVariant` 焦点、容器 18dp、进度 999dp |
| 信息条 | `ChannelInfoBanner.kt`（8c93e1b / c82593f） | `surface` 0.92、顶角 18dp、台标 80×50、padding 32/24、`headlineMedium/titleMedium`、进度 999dp、catchup 2dp、live 12dp 圆点 |
| 设置 | `SettingsDrawer.kt` | 300dp、`surface` 0.95、18dp 左角、padding 24、行 12dp、padding 16/14 |
| 质量弹窗 | `QualitySelectorDialog.kt` | 面板 18dp、行 12dp |
| 加载 | `ChannelSwitchLoading.kt` | 12dp 圆角 |
| 配置 | `MainActivity.kt` | 二维码白底 12dp |
| 台标 | `LogoLoader.kt` | 10dp 圆角 |

### 1.2 原版主题（颜色不改）

`PlayerActivity` / `MainActivity` 原版 `darkColorScheme`：

| Token | 值 |
|---|---|
| primary | `#BDBDBD` |
| onPrimary | `#000000` |
| secondary | `#757575` |
| tertiary | `#616161` |
| background | `#121212` |
| onBackground | `#FFFFFF` |
| surface | `#1E1E1E` |
| onSurface | `#FFFFFF` |
| 焦点高亮 | `MaterialTheme.colorScheme.surfaceVariant`（原版；**不是** `#4EA8FF`/`#FFFFFF14`） |

**禁止**把 v2/v3 的品牌色（`#161B22`/`#4EA8FF`/`#F5A524`/`#A9B4C7`）带入 v4；颜色体系保持原版。

### 1.3 原版结构（不改）

- 抽屉：`Row` + `AnimatedVisibility(groups)` + channels `300dp` + `1dp divider` + programs `300dp + 32dp` + `AnimatedVisibility(dates 110dp + 10dp)`；基础 **633dp**，groups/dates 按需展开（原版宽度公式，不是 v3 固定覆盖层）。
- 行高由原版内容/padding 决定：行外 `padding(vertical=3dp)`；group 内 `14/12dp`、channel 内 `12/10dp`、date 内 `10/10dp`、program 内 `12/10dp`；**不要改成 v3 的 64/52/48/44 固定行高**。
- 台标：channel **64×40dp**、信息条 **80×50dp**；位置/间距原版（channel 后 `Spacer(12dp)`）。
- 节目时间：`time` 宽 **96dp**、`bodySmall`、`Spacer(6dp)`、标题 `bodyMedium`；时间格式/颜色（past 0.45、now `#40C4FF`、future 0.86）不改。
- 字号：`headlineMedium`（信息条台名）、`headlineSmall`（设置标题）、`titleMedium`（频道/分组/设置）、`bodyMedium`（节目名）、`bodySmall`（当前节目/时间/日期）、`labelMedium`（技术参数）；**不改为 v2/v3 的 16/15/12sp 表**。
- 焦点：原版 `surfaceVariant` 底色 + `onFocusChanged`；`basicMarquee`/`FocusRequester`/DPAD 路径不改。

---

## 2. 全局“去圆角”规则（v4 唯一改动）

| Token | v4 值 | 原版值 | 适用 |
|---|---:|---:|---|
| `radius.row` | **0dp** | 12dp | 分组/频道/节目/日期/设置/线路/质量行 |
| `radius.panel` | **0dp** | 18dp | 抽屉容器、信息条容器、设置容器 |
| `radius.dialog` | **2dp** | 18dp | 质量弹窗面板（弹窗 0–2dp，取 2） |
| `radius.control` | **2dp** | 12dp（部分） | 按钮/输入框/配置控件（0–2dp，取 2） |
| `radius.logo` | **0dp** | 10dp | 台标磁贴/占位 |
| `radius.progress` | **0dp** | 999dp / 2dp | 信息条进度、节目进度、catchup 进度（线性方头） |
| `hairline` | **1px `#0FFFFFFF`**（允许） | 原版 1dp divider | 仅结构分隔；**绝不用于焦点** |

**允许**：0dp/2dp；极淡 hairline 1px。
**禁止**：10dp/12dp/18dp/999dp/胶囊；`border`/`BorderStroke` 表达焦点；`shadow`/`elevation`/blur/外发光/渐变；用圆角卡片把行重新“框起来”。

> “去圆角”只动 `RoundedCornerShape`/`CircleShape`（进度/容器）；**不动** `padding`/`size`/`width`/`height`/`fontSize`/`color`/`background` 的颜色。

---

## 3. 焦点（沿用原版，无蓝框）

| 项 | v4 |
|---|---|
| 焦点底 | **原版 `MaterialTheme.colorScheme.surfaceVariant`**（不改色） |
| 边框 | **无**；禁止 `border`/描边/外发光/阴影/缩放 |
| 行形状 | 0dp（原版 12dp 只改形状，不换颜色） |
| 文本 | 原版 `onSurface`；`basicMarquee` 行为不改 |
| 焦点布局 | 不改变行高/宽度/位置；不新增 padding |
| 蓝框 | **移除**（原版本身无 border；t50 添加的 `border(2dp,#4EA8FF)` 不得回归） |

---

## 4. 不可改动清单（红线）

> 以下任何一项都**不属于**“去圆角”，禁止改；否则会再次“越改越简”。

1. **双列 633dp**：channels 300、programs 300+32、divider 1、groups 150+1、dates 110+10；按需展开；不改宽度/不改覆盖层/不改动画路径。
2. **行高/间距**：行外 3dp；group 14/12、channel 12/10、date 10/10、program 12/10；不改固定行高。
3. **台标**：channel 64×40、信息条 80×50；位置、`Spacer(12/20)`、`ChannelLogo` 调用不改。
4. **频道名排版**：`"$index. ${channel.title}"`、`titleMedium`、单行 ellipsis；当前节目 `bodySmall` alpha 0.72；不改字号/行数/前缀。
5. **EPG 列结构**：date 列 110dp、program 时间 96dp、`bodySmall`、时间格式、past/now/future 颜色；不改时间展示方式。
6. **字号层级**：§1.3 的 MaterialTheme 角色不改（不引入 v2/v3 的 sp 表）。
7. **颜色体系**：§1.2 原版主题不改；焦点 `surfaceVariant` 不改；不引入品牌蓝/琥珀。
8. **信息条结构**：台标 + 台名 + 节目 + 技术参数 + 进度 + live 圆点；不改顺序/内容/字号/颜色/padding。
9. **设置结构**：300dp、24dp padding、标题 + 4 个 SettingsItem、8dp spacer；不改顺序/内容。
10. **质量弹窗结构**：面板 + 行 + 延迟文本；不改顺序/内容/字号。
11. **交互路径**：DPAD 左开抽屉、右开设置、Back 一次关、频道键 166/167、数字跳台、分组/日期/OK 语义、`basicMarquee`/FocusRequester 全部不改。
12. **内容**：不删台标、不删当前节目、不删进度、不删技术参数、不删日期/分组列。

---

## 5. 逐页面去圆角（from → to）

### 5.1 频道抽屉（PlayerDrawer）

| 元素 | 原版 | v4 |
|---|---:|---:|
| 抽屉容器 `shape` | 18dp（topEnd/bottomEnd） | **0dp** |
| 分组/频道/节目/日期行 `background` | 12dp | **0dp** |
| 行内“当前节目”进度 `ThinProgressBar` | 999dp | **0dp** |
| 焦点 | `surfaceVariant` | **不变**（形状随行 0dp） |

**不动**：633dp 宽度公式、行 padding、64×40 台标、`titleMedium/bodySmall/bodyMedium`、时间 96dp、行外 3dp、divider 1dp、marquee、DPAD/focus。

### 5.2 分组 / 节目 / 日期行（同文件）

| 元素 | 原版 | v4 |
|---|---:|---:|
| 行背景 | 12dp | **0dp** |
| 其余（padding/字号/颜色/台标/时间宽度） | — | **不变** |

- 分组：`14/12dp`、`titleMedium`、`bodySmall` 状态不变。
- 节目：时间 96dp、`bodySmall`、`bodyMedium`、past/now/future 颜色不变。
- 日期：`10/10dp`、`bodySmall`、单行 Clip 不变。

### 5.3 信息条（ChannelInfoBanner）

| 元素 | 原版 | v4 |
|---|---:|---:|
| 容器 `shape` | 18dp（topStart/topEnd） | **0dp** |
| `BannerProgressBar` 轨道/填充 | 999dp | **0dp** |
| `CatchupProgressRow` 进度 | 2dp | **0dp** |
| live 圆点 | `CircleShape` 12dp | **保留**（状态点不是进度条；如需极致扁平可改 0dp 小方块，默认保留） |

**不动**：`surface` 0.92、padding 32/24、台标 80×50、`headlineMedium` 台名、`titleMedium` 节目（primary）、`labelMedium` 技术参数、进度高度 3dp、live 圆点颜色/尺寸、catchup 行结构。

### 5.4 设置抽屉（SettingsDrawer）

| 元素 | 原版 | v4 |
|---|---:|---:|
| 容器 `shape` | 18dp（topStart/bottomStart） | **0dp** |
| `SettingsItem` 行背景 | 12dp | **0dp** |

**不动**：300dp 宽、`surface` 0.95、24dp padding、`headlineSmall` 标题、行 `16/14dp`、`titleMedium`、4 项顺序、8dp spacer、右对齐/滑入动画。

### 5.5 质量弹窗（QualitySelectorDialog）

| 元素 | 原版 | v4 |
|---|---:|---:|
| 面板 `shape` | 18dp | **2dp**（弹窗 0–2dp） |
| 线路行背景 | 12dp | **0dp** |

**不动**：弹窗尺寸/位置、行高、文字/延迟右对齐、分组/线路数据、scrim、关闭路径。

### 5.6 加载（ChannelSwitchLoading）

| 元素 | 原版 | v4 |
|---|---:|---:|
| 加载胶囊/背景 | 12dp | **0dp**（线性方头） |
| 其他（进度/文字/居中） | — | **不变** |

- 禁止新增 `#4EA8FF` 大边框/大遮罩/胶囊。

### 5.7 配置页（MainActivity）

| 元素 | 原版 | v4 |
|---|---:|---:|
| 二维码白底 | 12dp | **0dp** |
| 按钮/输入框（若显式圆角） | 12dp / Material 默认 | **2dp**（配置控件 0–2dp） |

**不动**：布局、按钮/输入框尺寸、字号、颜色、二维码内容、源配置流程。

### 5.8 台标（LogoLoader / ChannelLogo）

| 元素 | 原版 | v4 |
|---|---:|---:|
| 台标磁贴 `shape` | 10dp | **0dp** |
| 台标尺寸/位置 | channel 64×40、信息条 80×50 | **不变** |

- 不改解码尺寸、`ContentScale`、占位逻辑、缓存；只把磁贴圆角削平。

---

## 6. 性能与实现约束

1. **扁平化有利于绘制**：去掉 `RoundedCornerShape(10/12/18/999)` 可减少圆角/裁剪/离屏层；但**不得删内容/改结构/改字号**。
2. 复用原版 Compose 结构：`PlayerDrawer` 的 `Row/AnimatedVisibility/LazyColumn`、`ChannelInfoBanner` 的 `Column/Row`、`SettingsDrawer` 的 `Column`、`QualitySelectorDialog`、`ChannelSwitchLoading`；只替换 shape 参数。
3. 不新增 `shadow`/`elevation`/`blur`/`graphicsLayer`/`clip`；不使用 `border` 表达焦点。
4. 不改 `basicMarquee`/`FocusRequester`/DPAD/频道键/数字跳台；jank 以原版 Compose 基线为准，不宣称优化。
5. 不引入 v2/v3 的品牌色/40×40 台标/固定 64/52 行高/覆盖列；颜色/尺寸/结构完全跟原版。
6. 允许 hairline `#0FFFFFFF` 1px 仅做结构分隔；不得用于焦点，不得新增行边框。

---

## 7. 验收清单（源码 + 真机）

**源码可验证**
- [ ] `RoundedCornerShape` 仅允许 `0.dp` / `2.dp`；全项目无 `10.dp`/`12.dp`/`18.dp`/`999.dp` 用于上述元素。
- [ ] 无 `border`/`BorderStroke` 用于焦点；无 `shadow`/`elevation`/blur。
- [ ] 抽屉容器 0dp、行 0dp、信息条容器 0dp、设置容器 0dp、质量弹窗 2dp、配置控件 2dp、台标 0dp。
- [ ] 信息条/节目/catchup 进度均 0dp 方头；无 999dp 胶囊。
- [ ] 633dp 宽度公式/行 padding/64×40 与 80×50 台标/96dp 时间/字号/颜色/焦点 `surfaceVariant` 未被改动。
- [ ] `basicMarquee`/DPAD/频道键/数字跳台/回看/日期/分组路径未改。

**真机人工对照（t99/acceptance）**
- [ ] 截图与 c82593f 原版一致，仅圆角/胶囊变方；无蓝框。
- [ ] 双列 633dp、台标位置/大小、频道名/当前节目、EPG 时间、信息条、设置、质量弹窗、加载、配置均无排版变化。
- [ ] 反复开关不崩、不灰屏；视频/性能不回退到不可接受；jank 以原版 Compose 基线为准。

---

## 8. 落地顺序（给开发 t103）

1. 先只改 shape（按 §5 文件清单）：PlayerDrawer 18→0、12→0、999→0；ChannelInfoBanner 18→0、999→0、2→0；SettingsDrawer 18→0、12→0；Quality 18→2、12→0；Main 12→0/2；Loading 12→0；Logo 10→0。
2. **不要**改 padding/size/width/height/fontSize/color/background 颜色/结构/内容/交互。
3. 编译后做 `grep -R "RoundedCornerShape"` 审计：只允许 0/2；再对照原版截图逐页核对。
4. 真机回归：回看/日期/分组/数字跳台/频道键/焦点/灰屏/开关抽屉。

---

## 9. 版本关系

- **v4 是唯一 Flat/Clean 规范**；v2（无硬边框）与 v3（丰富布局）仅作历史参考，**不得再用其 40×40 台标、64/52 行高、品牌色、覆盖列**。
- v4 的原版基线：`PlayerDrawer.kt`（c82593f）、`ChannelInfoBanner.kt`/`SettingsDrawer.kt`/`QualitySelectorDialog.kt`/`ChannelSwitchLoading.kt`/`MainActivity.kt`/`LogoLoader.kt`（8c93e1b/c82593f）。
- 唯一改动：圆角/胶囊/边角；焦点仍原版 `surfaceVariant` 无蓝框。
