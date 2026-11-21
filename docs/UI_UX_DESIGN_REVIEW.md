# Android TV 设计一致性独立源码审查（t6）

日期：2026-11-18。审查人：designer。结论：**需要修订，当前不宜按 A+B 完整实现交付**。

依据：[设计 v1](UI_UX_DESIGN.md)、[A01–A14 验收矩阵](UI_UX_ACCEPTANCE.md)、[t4 实施说明](UI_UX_IMPLEMENTATION_NOTES.md)。只读检查当前 Kotlin 调用链；本任务仅新增本报告，没有修改 Kotlin，没有运行 Gradle，也没有执行设备/遥控器测试。以下触发路径为源码可推导的复现步骤，不能当作已经设备复现。当前模型不支持图像输入，未进行截图目视验收。

严重度：P0=阻断观看/恢复闭环或数据完整性；P1=本轮必须实现的交互/设置行为缺陷；P2=次要设计偏差。high 对应 P0/P1 阻断项，medium 对应其余偏差。

## 1. 已确认的阻断与修复要求

### D01 / P0：关闭启动恢复后，首台只更新 UI，不启动播放；生命周期恢复还使用旧快照

证据：[SplashActivity:25](../app/src/main/java/com/cjlhll/iptv/SplashActivity.kt#L25)、[PlayerActivity:401](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L401)、[PlayerActivity:436](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L436)、[PlayerActivity:569](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L569)。

触发：成功配置后关闭“恢复上次频道”，杀进程并启动。Splash 不传 VIDEO_URL；播放器初始化时 channelGroups 为空，initialUrl 为空，没有 MediaItem。缓存解析虽选出首频道，但 shouldSwitchPlayback 明确排除 Initial 模式，不调用 setMediaItem/prepare。后台列表返回同一首台时 newChannel.url==previousUrl，也不会补启动。结果为可见频道索引与实际播放器不一致。

此外生命周期观察器仅以 lifecycleOwner 为 key 建立，initializePlayer 捕获建立时的 currentChannel/url/catchupRequest；切换频道再进入全屏源配置并返回时，可重新播放最初 URL，而 UI 仍表示新台。

要求：初始缓存成功解析后显式启动选中频道；播放器重建使用最新会话状态（而非初始组合值）；缓存缺失/空列表也须进入可恢复加载或配置状态。复验恢复开/关、无历史有缓存、配置页返回与后台重建，实际 MediaItem 必须匹配 UI。

### D02 / P1（A09 阻断）：刷新策略持久化了值，但没有完整接入调度

证据：[IptvApplication:8](../app/src/main/java/com/cjlhll/iptv/IptvApplication.kt#L8)、[BackgroundSourceUpdater:36](../app/src/main/java/com/cjlhll/iptv/BackgroundSourceUpdater.kt#L36)、[BackgroundSourceUpdater:72](../app/src/main/java/com/cjlhll/iptv/BackgroundSourceUpdater.kt#L72)、[PlayerActivity:669](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L669)、[EpgRepository:18](../app/src/main/java/com/cjlhll/iptv/EpgRepository.kt#L18)。

触发：设置列表/EPG“仅手动”，杀进程重启。Application 启动仍无条件请求直播列表和EPG；播放器 EpgRepository.load(forceRefresh=false) 在无缓存时也联网。getPlaylistRefreshPolicy 的生产调用只在设置 UI，根本没有读取它的列表定时调度，所以“启动+每6小时”和“仅启动”对运行行为相同。EPG DAILY 轮询跨天自动刷新，也不符合“每天首次启动”；策略改变没有统一重新调度。

要求：单一调度入口执行各策略，区分纯缓存读取与允许联网；列表有真正6小时调度、MANUAL不得发启动/周期请求；DAILY按当天首次启动实现；策略即时重算，按源记录最近成功时间和5分钟失败退避。通过可控时钟/请求记录证明行为，而非只检查 Prefs 值。

### D03 / P0：超时与恢复预算不能覆盖首次播放、同 URL 重试；刷新成功也不等于重新播放

证据：[PlayerActivity:424](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L424)、[PlayerActivity:480](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L480)、[PlayerActivity:611](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L611)、[PlayerActivity:736](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L736)、[PlayerActivity:1072](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L1072)。

触发A：首次/恢复 URL 一直 BUFFERING，无 PlayerError。initializePlayer 未调用 startLoading，loadingGeneration=0，12秒监控直接返回，不能进入超时/失败终态。

触发B：失败面板选择“重试”，目标仍为 currentChannel.url；startPlayback 将其判为 isSameLiveUrl，跳过 startLoading，未建立新超时；若连接仅挂起则没有后续恢复。自动刷新成功且 URL 没变时 applyPlaylistContent 也不重新 prepare，仅返回等待下一次超时。

触发C：playGroupAuto 先把 currentGroupIndex 设置为目标；单线路且旧 variantIndex 同为0时，下一次组合算出的 currentChannel 已是目标，随后 startPlayback 可能同样跳过计时，而画面仍是旧会话。

要求：所有实际 prepare 尝试（含初始化/同URL重试/线路恢复）统一创建新attempt与12秒监控；不能用 UI 索引或 URL 相等推断已播放。每个恢复attempt明确记录一次预算；列表刷新无 URL 变化也要重试当前流，不能把列表请求成功当播放成功。

### D04 / P0：恢复任务缺少会话隔离，可覆盖用户的新换台；失败层也会抢已有面板

证据：[PlayerActivity:623](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L623)、[PlayerActivity:635](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L635)、[PlayerActivity:748](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L748)、[PlayerActivity:1067](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L1067)。

触发：A频道错误→恢复协程正在退避/下载→用户切到B→A恢复下载返回。processPlaybackError 用普通 scope.launch，无会话generation或取消job；仍可用 failedUrl=A调用 applyPlaylistContent，重设索引并切回A，或对当前组执行旧错误的备用线路操作。超时/PlayerError还可能同时生成并行恢复任务。

failureAttempt 在任意 READY 被归零；错误/超时没有绑定产生事件的 MediaItem，旧事件可以消耗或清除新会话预算。耗尽直接 playbackFailed=true，不检查频道/设置是否已开，失败面板绘制在它们上方并请求焦点，违反顶层互斥。

要求：单一播放session+attempt标识，换台/退出回看/新源/主动重试时取消旧恢复；每次await后验证session；同一attempt只处理一次结果。失败应成为待呈现状态，已有用户面板关闭后再呈现，不抢焦点；任意时刻仅一个可交互顶层。

### D05 / P0：配置取消/新请求替换会被旧协程异常覆盖

证据：[MainActivity:163](../app/src/main/java/com/cjlhll/iptv/MainActivity.kt#L163)、[MainActivity:206](../app/src/main/java/com/cjlhll/iptv/MainActivity.kt#L206)、[MainActivity:244](../app/src/main/java/com/cjlhll/iptv/MainActivity.kt#L244)、[MainActivity:368](../app/src/main/java/com/cjlhll/iptv/MainActivity.kt#L368)。

触发：加载中点击取消/Back，或手机连续提交A、B。loadJob.cancel 会使 withContext 恢复时抛 CancellationException，但通用 catch(Exception) 将其改成 ERROR 并写 errorMessage；取消后应回编辑却再次出现错误。旧A的取消异常可覆盖新B的LOADING/ERROR状态。阻塞OkHttp call没有被取消，且不存在请求generation保护。

要求：CancellationException直接重抛，不写UI；记录配置请求ID，所有状态更新与成功提交前验证仍是当前请求；取消底层call；新无效输入也须终止旧请求。复验取消后稳定可编辑，连续推送只以最新结果为准，不保存旧结果。

### D06 / P1（A09 阻断）：Channel+/− 自定义映射不会按设置执行

证据：[AppSettings:165](../app/src/main/java/com/cjlhll/iptv/AppSettings.kt#L165)、[PlayerActivity:285](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L285)。

触发：将“节目单”或“上一观看频道”映射为 Channel+，设置允许保存且显示成功；回全屏按Channel+，dispatch在查询actionForKey之前硬编码换下一台并return，映射永远不到达。Channel−同样。

要求：Live全屏统一先解析有效映射，再处理未自定义时的Channel±默认别名；所有允许捕获的键都必须有一致运行路径，或明确禁止保存。面板导航与Menu安全入口不受映射影响。

### D07 / P1：分组方向右选择的是旧目标；节目/设置没有稳定焦点恢复

证据：[ChannelPanel:115](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L115)、[ChannelPanel:173](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L173)、[ChannelPanel:261](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L261)、[ChannelPanel:570](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L570)、[SettingsDrawer:119](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L119)。

触发A：进入Groups，上下把焦点移到非首分组，按右。focusGroupName 从未在焦点移动时更新，右键使用targetGroupIndex，选中初始分组而非当前焦点；OK路径才使用真实行名。

触发B：节目列表移动到一条历史节目→右进日期→Back。ProgramRow不报告聚焦ID，没有保存节目锚点，返回时重新用focusProgramIndex（当前节目/首项），不回进入日期前的节目。Guide直接进入节目也固定Back到Channels，未记录直接入口。

触发C：设置根选择EPG策略→返回。rootFocus永远绑源配置第一项，未存调用项；当前选项页焦点也固定第一项。播放信息页没有可聚焦项；捕获/确认关闭后无父焦点恢复请求。

要求：保存每层稳定ID和进入源；GroupRow上报焦点；节目/日期返回恢复进入前ID而非重新选当前节目；设置使用稳定条目ID+滚动锚点；Guide直接进入Back回视频。对捕获/确认取消和隐藏层退出补显式焦点恢复。

### D08 / P1：EPG失败永远显示“加载中”，没有失败/旧缓存状态及可聚焦重试

证据：[ChannelPanel:203](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L203)、[ChannelPanel:617](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L617)、[PlayerActivity:669](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L669)、[PlayerActivity:1038](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L1038)。

触发：有EPG地址但网络失败/无缓存→进入节目页。epgData保持null，页面固定“正在加载节目单…”，CenteredNote不是焦点项；requestFocus试图找不存在的emptyActionRequester目标。加载失败与真正加载中无法区分，页面无重试动作。有旧数据时刷新失败也没有“上次节目单”状态，立即刷新仅Toast。设置的成功时间参数被传null且未显示。

要求：独立EPG Loading/Ready/Empty/Failure/Cache状态，携带最后成功时间；失败页有可聚焦“重试/配置/返回”；旧缓存可继续浏览并显示更新失败；手动更新有进行中/成功/失败状态及重复提交控制。

### D09 / P1：回看非当前频道后，设置“返回直播”会回错误频道；结束不自动返直播

证据：[PlayerActivity:994](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L994)、[PlayerActivity:1027](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L1027)、[PlayerActivity:461](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L461)、[PlaybackFailurePanel:84](../app/src/main/java/com/cjlhll/iptv/PlaybackFailurePanel.kt#L84)。

触发：直播A→浏览B节目→回看B。onPlayProgram只设置catchupRequest、不改currentChannel；设置“返回直播”清空request后播放currentChannel=A，未使用req.liveUrl=B。双Back的另一条返回实现使用req.liveUrl，两个入口不一致；信息条名称也取playingGroup可能把B台名替换为A。

播放器STATE_ENDED没有返回直播分支，仅loading绑定隐藏提示；节目结束仍停在回看。回看失败复用通用失败面板，缺直接“返回直播”，关闭失败状态后Catchup分支也先于onOkOverlay，OK不能重新打开失败面板。

要求：所有返回直播入口统一使用回看对应liveUrl，正确匹配频道/台名；回看ENDED回直播并反馈；失败页提供“重试/返回直播”，Catchup模式OK也支持重开失败层。

### D10 / P0：后台刷新可用空/坏内容覆盖最后可用缓存，并可能覆写新源指针

证据：[BackgroundSourceUpdater:89](../app/src/main/java/com/cjlhll/iptv/BackgroundSourceUpdater.kt#L89)、[PlayerActivity:602](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L602)。

触发A：已配置可用源，服务器随后返回HTTP200非空但零有效频道。refreshPlaylist只检查非空，不解析验证，直接写缓存并修改Prefs文件名、广播成功；applyPlaylistContent发现零频道直接return，内存看似正常，重启后可用缓存已丢失。

触发B：旧源后台请求未结束时，在配置页成功保存新源。旧任务返回后仍Prefs.setPlaylistFileName(old)，可使新liveSource配旧缓存。playlistRefreshMutex只串行下载，不校验当前源代次。

要求：先下载临时内容并验证至少一个有效合并频道，再原子替换缓存；失败保留旧缓存和指针。写Prefs/发送事件前检查源ID与版本仍有效，旧源结果不可覆盖新源状态。

## 2. 其他明确设计偏差（仍需本轮修正或显式复验）

| ID / 严重度 | 证据与触发 | 要求 |
|---|---|---|
| D11 / P1 | [SettingsDrawer:134](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L134)：设置主容器没有左右键处理，只有捕获页注册preview；根层左不关闭、子项右不进入 | 按设计实现左/Back父层、右/OK进入，捕获时隔离方向键，恢复默认先确认 |
| D12 / P1 | [PlayerActivity:765](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L765)：准备播放前就写LastChannel；[PlayerActivity:753](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L753)按URL变化写上一台，多线路也算新台 | 最后/上一观看频道在实际成功播放后按全局频道ID提交，线路切换不进入上一台；否则失败台会变成重启恢复目标 |
| D13 / P1 | [ChannelPanel:664](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L664)：所有历史节目都标“可回看”，即使该频道无catchup；[ChannelPanel:517](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L517)频道行只有当前节目，没有下一节目 | 回看标记依据真实能力与URL可构造性；频道行接入下一节目并稳定缺数据占位 |
| D14 / P2 | [SettingsDrawer:214](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L214)：源选择没有自动选项；[PlayerActivity:772](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L772)refreshGroupLatencies无调用 | 补自动线路入口，打开源选择触发实际延迟探测，区分未测/探测中/不可用；不得把未测直接标不可用 |
| D15 / P1 | [MainActivity:218](../app/src/main/java/com/cjlhll/iptv/MainActivity.kt#L218)空EPG始终保留旧值，推送协议也丢失字段存在性；[MainActivity:274](../app/src/main/java/com/cjlhll/iptv/MainActivity.kt#L274)LAN失败无重试按钮；[MainActivity:248](../app/src/main/java/com/cjlhll/iptv/MainActivity.kt#L248)首次配置直接退出 | 区分字段缺失和显式清空，提供LAN重试；首次退出按设计双Back，从设置进入Back回原上下文；键盘先关闭并回字段焦点须设备复核 |
| D16 / P1 | [PlayerActivity:205](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L205)只消费Down，Back释放仍交super；双Back时间不在其他操作后重置；没有250ms换台节流 | 按成对按键事件统一归属，确认无单次Back退出；操作后重置双Back计时，换台重复节流。此项需设备注入事件实测，源码缺保护，不能断言已运行复现 |

## 3. 逐项设计对照（源码成立不代表设备通过）

| 核对项 | 源码结论 | 运行/视觉结论 |
|---|---|---|
| 单层宽度 | [computePanelWidth](../app/src/main/java/com/cjlhll/iptv/TvTokens.kt#L78)与BoxWithConstraints传参符合min(360dp,W/3)；ChannelPanel单一when替换层，设置同容器 | 720p/1080p与文本实际裁切未验证 |
| 全局编号 | ChannelGrouper提供NumberedChannel，过滤列表保留全局编号，数字跳台使用group序号，方向正确 | 112/分组/多线路交叉设备验证未执行 |
| 焦点/播放态 | ChannelRow分别使用focus背景/边框与播放文字；但playingUrl来自目标索引，不保证已出首帧 | 效果与对比未验证；实际播放一致性受D01/D03/D12影响 |
| 左侧层级Back | parentOf实现Channels/Groups/Programs/Dates一级返回 | 稳定节目/入口/确认恢复不完整，见D07；无焦点加载页见D08 |
| Now/Next信息条 | BannerHost真实查询nowProgram/nextProgram，ChannelInfoBanner渲染时间/标题/进度/缺数据占位 | 不支持加载/失败区分；频道列表缺Next，回看频道名风险见D09/D13 |
| 启动恢复设置 | AppSettings真实写Prefs，Splash读取开关 | 关闭恢复路径不能启动首台，D01阻断 |
| 节目点击设置 | DIRECT/CONFIRM持久化；handleProgramClick按设置分支；当前节目切直播、未来提示 | 确认取消焦点未恢复，仍需设备验证 |
| 按键设置 | Prefs映射、禁用键和冲突检测存在，Menu固定入口 | Channel±绕过映射，D06；捕获键Down可默认移动焦点，需实测 |
| 上一观看频道 | 有真实切换回调，非静态按钮 | 未按成功播放提交、线路也改历史，D12 |
| 列表/EPG刷新 | UI持久化枚举，手动回调确实请求网络 | 策略/最近成功/进行中/失败状态不完整，D02/D08/D10 |
| 回看返回直播 | 双Back路径使用req.liveUrl | 设置路径错频道、ENDED无自动返回、失败缺出口，D09 |
| 配置成功/失败保护 | 手动导入验证M3U非空后保存，错误/空页有重试/修改按钮 | 取消/旧请求隔离失败，后台刷新还可损坏缓存，D05/D10 |
| 错误预算 | failureAttempt与12秒监控已存在 | 不是覆盖所有attempt的闭环，存在并发/旧会话覆盖，D03/D04 |
| TV视觉token/Logo | 字号16–36sp；成功logo取消clip、Fit比例；颜色token集中 | 字段默认字体、窄设置行长值、对比、远距可读、图片透明边缘均未目视测量 |

## 4. 下一步门槛

先修复D01–D10及A09相关真实行为，补D11–D16的设计一致性与事件复验。推荐增加有意义的状态测试：关闭恢复首次缓存启动、所有刷新策略与可控时钟、过期会话结果拒收、取消不改ERROR、组右键使用焦点组、日期返回保持节目ID、失败后同URL重试重新计时。

修复后重新做独立源码复查和A01–A14验收。构建/单测通过只证明工具链和已覆盖逻辑，不证明遥控器焦点、扫码真实提交、解码首帧或视觉已通过；这些目前保持未验证。本文阻断已同步Captain与acceptance，不批准当前实现交付。
