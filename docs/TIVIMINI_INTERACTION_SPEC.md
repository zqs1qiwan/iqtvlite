# tivimini 原版交互复刻规格

日期：2026-11-19。任务 t2，designer。状态：源代码证据已核实；本文件不代表真机交互验收通过。

## 1. 决策与证据边界

本轮按 Captain 确认的用户优先级，先完整复刻 tivimini 的交互逻辑与基础设计。原版双列抽屉、原版返回语义优先于此前重构的单层侧栏。本文固定 TIV-ID 供 Android/Rust 实现与遥控验收交叉引用；ID 不随实现完成而重排。

- **必须一致**：已由原始 Git 源码确认的用户可见结构与按键语义。
- **内部修复**：可修竞态、阻塞、资源管理与编号映射正确性，保持导航及基础外观。涉及可见变化时单独记为修复，不冒充原版。
- **可选后续优化**：原版没有或与原版不同的交互；本轮不以旧设计自动替换。

证据优先级：原版 commit 源码 > 绑定 APK/版本及操作记录的原版运行证据 > 历史交接叙述 > 原始需求设计 > 此前重构设计。文档意图与源码冲突时以源码为本次基线。源码能证明实现条件，不能单独证明目标机最终焦点或解码画面。

| 证据 | 身份与限制 |
|---|---|
| U：原版 Git | 当前仓库 origin 为 https://github.com/cjlhll/tivimini.git，HEAD `8c93e1b1d6601f882a51022cfafd59e684f6ac26`。工作区大量未提交重构，不能以工作区 Kotlin 充当原版。已用 `git show HEAD:<path>` 保存下面八个源码快照，只用于取证，不编入 app。 |
| H：历史运行记录 | [HANDOFF](../HANDOFF.md#L52)记载 2026-11-18 的二维码、输入112、频道信息；是原底座加当时移植差异的 debug 版本，归档 APK 未装机回归，不能当纯上游运行版。 |
| S：截图 | 上游 [截图目录](../screenshot/)含九张20260211截图；[历史证据目录](evidence/2026-11-18/)含配置、信息条及112截图。本任务尝试读两张上游图，工具均报告当前模型不支持图像输入；未目视验证、未测像素。文件名不证明其画面内容。 |
| D：旧设计 | [UI_UX_DESIGN](UI_UX_DESIGN.md#L29)、[INSPECTION](UI_UX_INSPECTION.md#L51)是此前改进方案，不是原版行为证据；原始 [PAGE设计](reference/tivimini/PAGE_AndroidTV_IPTV_页面设计.md#L21)中的频道首页及55%–70%等也不能覆盖当前上游实现。 |
| R：TiviMate 静态报告 | [REA报告](../REA_analysis_report.md#L53)与[证据包](../REA_evidence_bundle.json)表明 `ar.tvplayer.tv` 5.3.3（SHA256 `beaebb25bf818450bf496d4cefb16885b190f156815dea280c31eaae0909ee6c`）核心字节码受 DexProtector 保护。只看到加载器、清单、资源及库；没有运行交互证据，不能声称完整反编译或已测量 TiviMate。本文未对 APK 再逆向，因此没有调用逆向工具，也没有借其推断 tivimini 按键。 |
| V：本轮设备 | acceptance 只读确认 `com.cjlhll.iptv` 1.0/code1 与 `tv.iqtvlite.app` 0.1.0-test/code1 均安装；前台当贝桌面。未启动、未注入按键。designer 全程不操作设备，运行补证由 acceptance 独占。 |

原版快照索引（所有下文 U 引用均指固定 commit，不指当前工作区）：

- [入口 SplashActivity](evidence/tivimini-upstream-8c93e1b/SplashActivity.kt)
- [配置 MainActivity](evidence/tivimini-upstream-8c93e1b/MainActivity.kt)
- [播放编排 PlayerActivity](evidence/tivimini-upstream-8c93e1b/PlayerActivity.kt)
- [频道抽屉 PlayerDrawer](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt)
- [设置 SettingsDrawer](evidence/tivimini-upstream-8c93e1b/SettingsDrawer.kt)
- [信息条 ChannelInfoBanner](evidence/tivimini-upstream-8c93e1b/ChannelInfoBanner.kt)
- [源选择 QualitySelectorDialog](evidence/tivimini-upstream-8c93e1b/QualitySelectorDialog.kt)
- [换台提示 ChannelSwitchLoading](evidence/tivimini-upstream-8c93e1b/ChannelSwitchLoading.kt)

## 2. 页面与操作复刻表

下面各项“源确认”表示已核实源码；全部仍待绑定最终 CI APK 在目标盒子运行验收。首次配置焦点及系统输入法返回行为未由原版显式规定，不编造固定值。

| ID / 状态 | 场景与必须达到的行为 | 源确认与可执行验收 |
|---|---|---|
| TIV-01 必须一致 | 启动：存在非空直播源且 lastUrl 非空即直接进入播放器并携带历史标题、缓存文件名；否则配置页。没有频道浏览首页。 | [Splash:14](evidence/tivimini-upstream-8c93e1b/SplashActivity.kt#L14)。清数据启动看配置；保存源/频道后杀进程再启应恢复。离线是否真正播出另验。 |
| TIV-02 必须一致 | 配置：左QR及LAN URL，右直播、EPG两字段及“保存并播放”；字段非编辑态只读，OK/Enter/NumPadEnter 的 KeyUp 开始编辑并显示IME，离焦退出编辑。加载中字段/主按钮禁用。 | [Main:274](evidence/tivimini-upstream-8c93e1b/MainActivity.kt#L274)、[Main:370](evidence/tivimini-upstream-8c93e1b/MainActivity.kt#L370)。遥控遍历字段和按钮；字段编辑中的左右仅编辑文本。首次焦点由系统分配，运行待补。 |
| TIV-03 必须一致 | 扫码推源：配置页启动LAN服务，离开销毁；接收非空live/epg更新对应字段，Toast“已从手机推送更新”，自动保存并加载。空推送EPG不清空已有EPG。加载成功进入播放并结束配置页。 | [Main:230](evidence/tivimini-upstream-8c93e1b/MainActivity.kt#L230)、[Main:153](evidence/tivimini-upstream-8c93e1b/MainActivity.kt#L153)。用手机实际提交，不能仅验QR存在。 |
| TIV-04 必须一致 | 无浮层直播：上/下全局合并频道前/后一项，首尾循环；左开频道抽屉；右/Menu开设置；OK/Enter显示信息条；已有侧栏/源选择时不得透传为换台。 | [Player:160](evidence/tivimini-upstream-8c93e1b/PlayerActivity.kt#L160)、[Player:974](evidence/tivimini-upstream-8c93e1b/PlayerActivity.kt#L974)。首频道上→末频道，末下→首；面板内上下不改变流。 |
| TIV-05 必须一致 | 左侧初开为频道列+节目列并列；第一次打开初始化当前播放频道，否则首项；后续优先保留仍有效的浏览焦点，不能强制每次回当前台。焦点频道变化350ms后更新右侧EPG，浏览不切台。 | [Drawer:193](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L193)、[Drawer:226](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L226)、[Drawer:281](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L281)。播放A浏览B，等待EPG更新，画面仍A；关开后记录焦点。 |
| TIV-06 必须一致 | 频道列左展开分组并聚焦选中组；分组右回频道。组OK只选择分组并刷新频道，不自动切台、不自动退出分组；频道重新获得焦点时分组隐藏、日期隐藏。 | [Drawer:650](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L650)、[Drawer:706](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L706)、[Drawer:749](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L749)、[Player:1035](evidence/tivimini-upstream-8c93e1b/PlayerActivity.kt#L1035)。左→上下→OK仍分组焦点，右进入筛后频道。 |
| TIV-07 必须一致 | 频道上下首尾循环；OK选择频道并保持抽屉打开和该行焦点。按源列表顺序选首个可达线路，全失败仍尝试第一个。节目列不是仅当前播放频道的EPG，而是浏览焦点频道EPG。 | [Drawer:632](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L632)、[Drawer:758](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L758)、[Player:876](evidence/tivimini-upstream-8c93e1b/PlayerActivity.kt#L876)、[Player:1026](evidence/tivimini-upstream-8c93e1b/PlayerActivity.kt#L1026)。OK后视频换、抽屉不关。 |
| TIV-08 必须一致 | 频道右→节目；节目左→频道；节目聚焦即显示日期列，节目右→日期；日期左→节目。日期OK更改日期并保持日期列焦点。默认今天（有数据时），否则首日期；今天节目目标为当前时段，否则首项。 | [Drawer:464](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L464)、[Drawer:657](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L657)、[Drawer:827](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L827)、[Drawer:891](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L891)。验证今天/历史日期，焦点可左右返回。 |
| TIV-09 必须一致 | 历史节目（end<=now）OK且有catchupMode并生成URL成功→启动回看并关抽屉。无支持/生成失败用Toast说明；当前和未来节目OK统一提示“只能回看历史节目”，不转直播、不预约。 | [Drawer:817](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L817)、[Player:1039](evidence/tivimini-upstream-8c93e1b/PlayerActivity.kt#L1039)。三个时态分别按OK；没有测试源回看能力时该项记录未验证，不伪造成功。 |
| TIV-10 必须一致 | 抽屉任意列Back都关闭整个抽屉；非频道列先请求频道焦点，Activity延80ms关闭。不是逐级回到频道/日期父层。重新打开保留焦点的具体结果需运行确认。 | [Player:201](evidence/tivimini-upstream-8c93e1b/PlayerActivity.kt#L201)、[Drawer:626](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L626)。分组、节目、日期分别Back，一次均应回视频。80ms是实现细节，复刻不必照搬延时。 |
| TIV-11 必须一致 | 右设置独立覆盖；每次打开初始“源配置”。上下移动；左/Back关闭。条目仅源配置、更新EPG、条件性源选择、检查更新；更新EPG立即关闭并后台请求。 | [Settings:50](evidence/tivimini-upstream-8c93e1b/SettingsDrawer.kt#L50)、[Player:1076](evidence/tivimini-upstream-8c93e1b/PlayerActivity.kt#L1076)。打开两次均源配置焦点；无多线路不显示源选择。 |
| TIV-12 必须一致 | 源选择：设置先关闭，居中模态；初始焦点当前线路；显示质量标签和测速中/ms/超时。OK切换该线路，不自动关闭对话框；Back关闭。 | [Quality:66](evidence/tivimini-upstream-8c93e1b/QualitySelectorDialog.kt#L66)、[Player:1109](evidence/tivimini-upstream-8c93e1b/PlayerActivity.kt#L1109)。多线路样本中换线，模态保持，直播导航不能穿透。 |
| TIV-13 必须一致 | 信息条底部全宽：台标、编号/频道名、存在时当前节目及直播进度、右侧可用视频参数；原版没有“接下来”一行。OK显示，5秒隐藏；上下换台亦显示并重置计时。无节目不新增伪节目。 | [Banner:25](evidence/tivimini-upstream-8c93e1b/ChannelInfoBanner.kt#L25)、[Player:528](evidence/tivimini-upstream-8c93e1b/PlayerActivity.kt#L528)、[Player:985](evidence/tivimini-upstream-8c93e1b/PlayerActivity.kt#L985)。有/无EPG、有/无视频格式分别截取；不把空参数写成0x0。 |
| TIV-14 必须一致 | Back优先级：源选择→频道抽屉→设置→信息条→回看双Back回直播/直播双Back退出。双击窗口2秒，第一次Toast。信息条可见时首次Back只关信息，不计为退出动作。 | [Player:201](evidence/tivimini-upstream-8c93e1b/PlayerActivity.kt#L201)。逐层构造后按Back，播放不被误退出。原版由第二次Back交系统退出，Android实现可等价finish。 |
| TIV-15 必须一致 | 回看且无浮层：左/右seek，首次10秒，重复1–14为30秒、15–39为60秒、40起300秒；限制在0..节目时长，非seekable不伪装seek成功。上/下/OK只显信息；Menu仍设置。 | [Player:151](evidence/tivimini-upstream-8c93e1b/PlayerActivity.kt#L151)、[Player:938](evidence/tivimini-upstream-8c93e1b/PlayerActivity.kt#L938)。可seek样本短按与长按，不能越界或直播误seek。 |
| TIV-16 必须一致 | 换台加载为小浮窗“加载中…”；READY/ENDED/error或12秒关闭，不阻止遥控继续导航。12秒仅隐藏提示，不等于原版定义了12秒失败终态。 | [Loading:61](evidence/tivimini-upstream-8c93e1b/ChannelSwitchLoading.kt#L61)。坏流时记录提示生命周期；不能以提示消失认定首帧成功。 |
| TIV-17 内部修复 | 焦点与播放状态分离。原版channel/group的selected参数未绘制，只有focused填背景；不能用“焦点行”作为实际正在播放标识。程序内部维护稳定频道/节目ID，异步旧结果不得覆盖新频道。 | [Drawer:930](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L930)、[Player:880](evidence/tivimini-upstream-8c93e1b/PlayerActivity.kt#L880)。快速浏览/换台后核对视频、信息、EPG对应关系。新增持久播放标记属于可见修复，应在变更表明确记录。 |
| TIV-18 内部修复 | 编号：原版抽屉对过滤后的频道index+1重新编号，信息条使用合并组序号；存在可证不一致。内部统一“合并后的全局编号”用于频道/信息条/数字目标，分组保留该号。不得改变合并频道顺序以掩盖问题。 | [Drawer:742](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L742)、[Player信息条调用](evidence/tivimini-upstream-8c93e1b/PlayerActivity.kt#L1151)。该编号变化是明确修复，非原版逐像素一致；用全部与非首组同频道交叉核对。 |

## 3. 状态转移与焦点约束

实现可用原生View/Compose；此状态模型约束可见结果，不要求复制原版分散的Boolean实现。

```text
Boot --有源+历史--> Video(Live)
     --否则------> Config --手动/手机成功--> Video(Live)
Video(Live) --Left--> Drawer(Channels + Programs)
Drawer(Channels) --Left--> Drawer(Groups + Channels + Programs)
Drawer(Groups) --Right--> Drawer(Channels + Programs)
Drawer(Channels) --Right--> Drawer(Channels + Programs + Dates), focus=Programs
Drawer(Programs) --Right--> 同布局, focus=Dates
Drawer(Dates) --Left--> 同布局, focus=Programs
Drawer(Programs) --Left--> Drawer(Channels + Programs), focus=Channels
Drawer(任意列) --Back--> Video（一次关闭）
Drawer(Channels) --OK--> 换台，保留Drawer
Drawer(Groups) --OK--> 筛选频道，保留Groups焦点
Drawer(Dates) --OK--> 换日期，保留Dates焦点
Drawer(历史可回看节目) --OK--> Video(Catchup)
Video(Live) --Right/Menu--> Settings --源选择--> QualityDialog
Settings --Left/Back--> Video；QualityDialog --Back--> Video
Video(Catchup) --Left/Right--> seek；--Menu--> Settings
```

边界补充：

1. 原版空EPG显示“加载中”或“暂无节目单”，没有可聚焦空态按钮。右进空节目时源码等待最多1.5秒，无节目即放弃请求焦点；实际保留焦点位置待运行补证。**内部修复**要求不丢焦点、不让按键穿透，保留频道焦点且Back仍可关闭，不擅加新层。
2. 所有非频道列表边界未见手动循环逻辑；采用框架默认上下边界，运行验收记录，不能宣称都首尾循环。
3. 原版未显式实现Guide、Channel+/−、MediaPrevious或数字跳台；不能由一般TV习惯推断它们有动作。
4. 原版信息条与普通侧栏并非显式互斥，已有信息条打开侧栏时可能维持至计时结束；复刻不强加此前“侧栏一开就隐藏信息条”的规则。
5. 基础优先级按TIV-14；IME、系统权限/更新对话框由其自身消费按键。源选择是全屏模态，焦点不得落到视频或隐藏设置。

## 4. 原版尺寸与样式基线（dp，不是截图px）

Wdp = 可用屏幕宽px / 实际density。目标机物理1920×1080只说明像素输出，未测density前不能写1920dp或固定比例。以下尺寸均来自源码，真实像素需由acceptance记录density、应用可用bounds、系统overscan及APK身份再比较；不根据无法读取的截图编造测量。

| 部位 | 源码基线 | 证据 |
|---|---|---|
| 默认左抽屉 | 300频道 + 1分隔 + 300节目 + 32节目内边距 = **633dp**；全高左锚定 | [Drawer:590](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L590) |
| 分组展开 | +150组列+1分隔，总**784dp** | [Drawer:592](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L592) |
| 日期展开 | +110日期+10间隔，总**753dp**；公式两者同时显可达904dp，但正常焦点回频道隐藏分组，因此904不是普通路径必须出现 | [Drawer:595](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L595)、[Drawer:749](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L749) |
| 左侧材质 | surface `#1E1E1E` alpha .92；右上/右下18dp圆角；外区透明scrim；列内16dp | [Drawer:136](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L136)、[Player:107](evidence/tivimini-upstream-8c93e1b/PlayerActivity.kt#L107) |
| 频道行 | logo64×40dp、间隔12dp、内边距横12纵10、行外纵3、圆角12；titleMedium名称，bodySmall当前节目/“无信息”；进度条3dp | [Drawer:971](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L971) |
| 分组行 | 内边距横14纵12、外纵3、圆角12、titleMedium单行ellipsis | [Drawer:930](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L930) |
| 设置 | 右侧300dp全高，内24dp，左上/下18dp圆角；surface alpha .95，透明scrim；行横16纵14，圆角12 | [Settings:50](evidence/tivimini-upstream-8c93e1b/SettingsDrawer.kt#L50)、[Settings:145](evidence/tivimini-upstream-8c93e1b/SettingsDrawer.kt#L145) |
| 源选择 | 居中400dp宽，最高480dp，内24dp，18dp圆角，surface alpha .98；全屏黑alpha .55 | [Quality:59](evidence/tivimini-upstream-8c93e1b/QualitySelectorDialog.kt#L59) |
| 底部信息 | 全宽、顶部18dp圆角、surface alpha .92；左右32、直播上下24；logo80×50，左右间隔20；名称headlineMedium、节目titleMedium | [Banner:38](evidence/tivimini-upstream-8c93e1b/ChannelInfoBanner.kt#L38) |
| 配置 | 外48dp；QR容器可用高的.6且1:1、内16；QR白底宽.85、内12、圆角12；左右间48；右列纵间24；按钮高56 | [Main:274](evidence/tivimini-upstream-8c93e1b/MainActivity.kt#L274) |
| 动效 | 抽屉位移进160/出140ms、宽度高刚度无回弹spring；设置160/140；源选择160/120 fade+scale(.96) | [Drawer:598](evidence/tivimini-upstream-8c93e1b/PlayerDrawer.kt#L598)、[Quality:73](evidence/tivimini-upstream-8c93e1b/QualitySelectorDialog.kt#L73) |

字体保持原版TV Material的命名style；没有读取对应依赖最终Typography前不把titleMedium武断换成20sp。后续可记录resolved字号/行高进行字体等价核对。本任务不增加全局绿色焦点/2dp描边等旧重构token。

若目标Wdp小于原抽屉宽，保持列次序、焦点路径和Back语义；可对整体尺度/可滚动可见范围作兼容修复，必须记录实际边界与处理，不偷偷换成单层侧栏。

## 5. 与此前重构方案分离

| ID / 状态 | 已有方案或能力 | 本轮处理 |
|---|---|---|
| TIV-19 可选后续优化 | [旧设计](UI_UX_DESIGN.md#L31)的`min(360dp,W/3)`单层频道→节目→日期、逐级Back | 禁止替代TIV-05/08/10及633dp基线。可留后续独立设计提案。 |
| TIV-20 可选后续优化 | [旧设计](UI_UX_DESIGN.md#L84)频道OK关面板、当前节目OK转直播、组OK自动进入频道、日期OK回节目 | 本轮按TIV-06/07/08/09原版语义。 |
| TIV-21 可选后续优化 | [旧设计](UI_UX_DESIGN.md#L99)遥控映射、上一台、刷新策略、启动开关、节目点击确认、Guide直达 | 上游设置没有这些项目；若保留已有能力需单列增量验收，不能作为“原版已复刻”的证据，也不扩展基础设置阻塞复刻。 |
| TIV-22 可选后续优化 | [旧设计](UI_UX_DESIGN.md#L72)下一节目、透明logo规范、持久播放标志、常驻失败操作面板、先验证再保存 | 是需求改进/修复候选；原版仅当前节目，配置网络验证前保存，错误Toast。可修资源/请求正确性，新增可见UI要列差异。 |
| TIV-23 内部修复（保留已有增量） | 数字112、测试源预填是[历史移植差异](../HANDOFF.md#L25)，原版Main从Prefs直接读，Player未实现数字键 | 用户既有要求继续保留：连续数字缓冲，最后一位1.2秒后提交；全局合并编号，与TIV-18统一；无效号不改变当前频道并反馈。OK立即提交/Back取消属于后续当前实现增量，需明确单独验收，不能标原版。 |
| TIV-24 内部修复 | 硬件解码、网络与长列表性能 | 保持本规格导航；Android Media3/原生Surface负责视频，Rust只负责纯数据解析、索引、匹配与查询，不能用Rust重造遥控焦点或播放器。具体接口由架构负责人定义。 |

## 6. 给实现与验收的交接

Android负责Activity/IME/遥控按键、焦点、侧栏与浮层、Media3播放及生命周期。Rust输出稳定频道ID、全局编号、组关系、线路、EPG节目/日期索引；UI只取可见窗口，不能每次焦点变化重建全EPG。浏览焦点、当前播放频道、当前线路、EPG查询代次各自有明确身份。查询/网络在后台完成，过期结果丢弃；不把播放缓冲、解码或Surface交给Rust。

验收记录每行至少包含：TIV-ID、APK SHA256/CI run/commit、包名版本、设备API/ABI、density及可用屏幕、操作序列、预期/实际、截图或日志、通过/失败/未验证。不以静态布局检查代替焦点运行，不以SurfaceView黑截图断言无首帧，不以READY或loading消失代替真实视频解码首帧。

建议优先运行序列：TIV-01→02→03（首次导入），04→05→06→07（频道/分组），08→09→10（EPG/日期/Back），11→12→14（设置/模态），13→15→16（信息/回看/加载），18→23（编号/数字）。无回看/多线路测试样本时TIV-09/12/15记录未验证，保留相应样本补验责任。

本任务验证仅包括：原始commit身份、八个快照与git内容等价、TIV-ID唯一性与本地链接存在性；未修改Kotlin/Rust，未运行Gradle、未连接/控制TV，未声称APK或CI已通过。
