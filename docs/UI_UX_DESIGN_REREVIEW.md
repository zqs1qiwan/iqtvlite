# TV UI/UX 修复独立复查（t9）

日期：2026-11-18。审查人 designer。结论：**needs_revision，尚不能将 D01–D16 全部销项或宣称 A+B 完整通过。**

依据：[设计](UI_UX_DESIGN.md)、[验收矩阵](UI_UX_ACCEPTANCE.md)、[首轮审查](UI_UX_DESIGN_REVIEW.md)、[t7修复说明](UI_UX_REPAIR_NOTES.md)。审查当前源码与Git差异（注意当前Git基线早于t4，diff包含已有修改，并非独立t7提交）。本任务只新增此报告，不修改Kotlin、不运行Gradle。构建与47项单测通过为t7依赖结果，不作为本任务重新执行证据。无图像输入能力、无可用设备证据；所有视觉、遥控器和真实播放结论仍待设备验证。

“源码已修复”仅指原始缺陷调用链已被替换，不代表设备验收通过。“部分修复/未闭合”说明仍有生产路径反例。

## 1. D01–D16 逐条结论

| 原ID | 本轮源码结论与证据 | 残余/设备门槛 |
|---|---|---|
| D01 | 部分修复。[PlayerActivity:687](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L687)初始缓存解析显式startSession，正常关闭恢复开关可启动首台；[生命周期:538](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L538)重建读取可变preparedUrl，旧初始URL快照已替换 | 重建走ensurePlayer直接prepare但未建立新loadToken，恢复中断/重建超时仍缺，见R02；MANUAL且无缓存无失败出口，见R08 |
| D02 | 部分修复。[BackgroundSourceUpdater:77](../app/src/main/java/com/cjlhll/iptv/BackgroundSourceUpdater.kt#L77)与周期循环真实读取RefreshSchedule；EPG纯缓存allowNetwork=false，MANUAL不再必然启动联网 | 每源时间戳未隔离；DAILY启动被退避挡住后永久错过该次启动，见R09。策略即时变更/跨天需设备与时钟证据 |
| D03 | 部分修复。[prepareMediaNow:498](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L498)不再按URL相同跳过，所有该入口调用都有loadToken；[恢复:787](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L787)无备用线路也重新prepare | ensurePlayer另有直接prepare路径；探测阶段没有会话预算，R01/R02 |
| D04 | 部分修复。[startSession:510](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L510)取消recoveryJob，恢复await后有session检查；pendingFailure等待用户侧栏关闭 | 自动选源另一个Job不受session取消；PlayerError按当前值盖章，R01/R02。后台applyPlaylistContent也无session前后校验 |
| D05 | 原取消转ERROR问题源码已修复。[MainActivity:171](../app/src/main/java/com/cjlhll/iptv/MainActivity.kt#L171)同时取消Call/Job并提升requestId；[232](../app/src/main/java/com/cjlhll/iptv/MainActivity.kt#L232)重抛CancellationException；网络与解析后校验代次 | 真正取消/快速连续推送仍需设备/可控服务验证；手动成功提交与后台写源的原子性见R03 |
| D06 | 原硬编码抢先问题源码已修复。[PlayerActivity:329](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L329)先actionForKey再处理Channel±默认别名 | onKeyDown与Compose先消费的实际事件归属待设备；数字Menu绕过清空仍有R07 |
| D07 | 部分修复。[ChannelPanel:361](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L361)真实上报组焦点；进入日期记录programAnchorKey并恢复，Guide记录直接入口 | Guide仍复用旧浏览频道；节目加载后/确认取消无恢复触发；设置屏外目标Requester不存在，见R05/R06 |
| D08 | 部分修复。[ChannelPanel:664](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L664)区分UNCONFIGURED/LOADING/FAILURE/STALE并给失败重试；手动EPG回调有inProgress | IOException回退旧缓存被记为成功，R04；失败/就绪替换后焦点请求未重跑，R05 |
| D09 | 原错频道与缺出口已修复主要路径。[回直播:1016](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L1016)统一liveUrl；[回看开始:1179](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L1179)切换对应频道索引；ENDED触发回直播；失败层提供返回直播，Catchup OK可重开 | 旧选源探测仍可能覆盖回看，R01；seek/ENDED/多入口返回实际行为待设备 |
| D10 | 坏内容校验已接入，但数据事务未修复完整。[BackgroundSourceUpdater:159](../app/src/main/java/com/cjlhll/iptv/BackgroundSourceUpdater.kt#L159)先PlaylistValidation再写缓存 | 源相等检查与提交非原子；写Atomic有删除旧文件回退且共用tmp，R03 |
| D11 | 左/右导航与恢复默认确认已加入：[SettingsDrawer:147](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L147)、[541](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L541) | 根/子焦点与确认初始焦点仍缺可靠滚动/请求，R05；捕获KeyDown未消费，须设备验证方向不逃逸 |
| D12 | 部分修复。[commitSuccessfulPlayback:523](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L523)READY后写LastChannel并按groupIndex区分线路 | Main配置仍播放前写LastChannel；groupIndex非稳定频道ID，刷新重排误归历史，R10 |
| D13 | 频道行Next已接入，过去节目非catchup显示“已结束” | [ChannelPanel:380](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L380)只按mode/source非空判能力而未校验URL可构造；当前节目缺失时[PlayerActivity:933](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L933)continue跳过Next，见R11 |
| D14 | 自动入口、打开设置探测已接：[SettingsDrawer:443](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L443)、[PlayerActivity:1132](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L1132) | null同时表示未测和失败，测完不可用仍标“未测”；自动选第一个可达而非最低延迟，见R11 |
| D15 | 推送null/空分离、LAN重试token、首次双Back均已落到实际逻辑：[MainActivity:251](../app/src/main/java/com/cjlhll/iptv/MainActivity.kt#L251)、[261](../app/src/main/java/com/cjlhll/iptv/MainActivity.kt#L261)、[281](../app/src/main/java/com/cjlhll/iptv/MainActivity.kt#L281) | 手机真实提交、键盘Back/字段返焦、LAN重试须设备验证；源事务见R03 |
| D16 | 已改OnBackPressedCallback、250ms换台节流、非面板操作清双Back计时 | 替换为onKeyDown后未调用super处理未消费键，旧Android Back跟踪路径须验证；Menu+数字定时器未取消，R07 |

## 2. 本轮残余发现与修复要求

### R01 / P0 / D04：旧自动选源探测可覆盖新的手动源、上一台或回看

证据：[playGroupAuto:961](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L961)、[startSession:510](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L510)、[手动源:1220](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L1220)。

路径：自动选择A开始probe并挂起→用户手动选线路B（或启动历史节目回看/上一观看频道）→新操作调用startSession，只取消recoveryJob，不更新sourceSelectGeneration→A探测返回仍generation相同→playGroupVariant重新启动A，清除catchupRequest并覆盖用户刚作的选择。只有另一轮playGroupAuto会提升探测generation。

要求：探测任务与播放session共用一个用户意图代次；所有播放意图先失效旧探测/恢复，任务每次await后检查；取消/离开配置也不能留下写播放器的旧任务。用可控probe延迟验证“较新手动选择最终生效”。

### R02 / P0 / D03–D04：事件ID未绑定事件来源，生命周期prepare仍绕过监控

证据：[onPlayerError:573](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L573)、[ensurePlayer:482](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L482)、[超时:586](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L586)、[恢复接收:792](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L792)。

onPlayerError在回调发生时读取当前sessionIdState/attemptState盖章，不含媒体项目/发起尝试ID；若旧加载的回调排队到新会话后，生成事件的身份已被换成新会话，accepts纯函数不能将其判旧。READY/ENDED也不核验MediaItem身份。存在变量不等于事件有来源绑定。

此外ON_STOP释放播放器；ON_START的ensurePlayer直接setMediaItem/prepare/play，不提升loadToken或重置超时。若上一监控已完成，重建后BUFFERING挂起没有新12秒监控；若在后台期间player=null，旧timer还可能触发恢复，并重新创建播放器播放。

要求：MediaItem携带不可复用session/attempt媒体ID，并通过能取得事件MediaItem身份的监听路径校验READY/error/ENDED；统一生命周期重建与每次prepare入口及超时，停播时取消监控/恢复。旧回调归属的实际注入测试仍需补齐，不能只测accepts整数比较。

### R03 / P0 / D10：源校验与数据提交不构成原子事务，缓存回退可丢旧文件

证据：[BackgroundSourceUpdater:165](../app/src/main/java/com/cjlhll/iptv/BackgroundSourceUpdater.kt#L165)、[MainActivity:140](../app/src/main/java/com/cjlhll/iptv/MainActivity.kt#L140)、[PlaylistCache:29](../app/src/main/java/com/cjlhll/iptv/PlaylistCache.kt#L29)。

可复现交错：IO线程校验旧源A仍为当前→主线程startPlayer保存B及B缓存指针→IO线程继续writeAtomic(A)/setPlaylistFileName(A)。最终liveSource=B、playlistFileName=A。playlistRefreshMutex只包后台请求，手动配置不共享；URL相同的A→B→A也不能辨别旧请求。AppSettings成功时间还会被旧源推进。

writeAtomic所有写者共用`$fileName.tmp`，无共同锁，两个写者可互相重命名/覆盖；rename失败时先delete旧目标，再copy fallback，不符合“失败保留最后可用缓存”。

要求：源持久版本+单一仓库事务（手动/后台都遵守），提交前校验和缓存指针/时间写入在同一串行或锁区；临时文件唯一，失败不能删最后可用文件，使用Android AtomicFile/可靠原子替换语义。以锁闩控制交错验证，不能只测内容校验函数。

### R04 / P1 / D08：EPG网络失败返回缓存仍被标为成功，且内存缓存未按源隔离

证据：[EpgRepository:26](../app/src/main/java/com/cjlhll/iptv/EpgRepository.kt#L26)、[84](../app/src/main/java/com/cjlhll/iptv/EpgRepository.kt#L84)、[BackgroundSourceUpdater:197](../app/src/main/java/com/cjlhll/iptv/BackgroundSourceUpdater.kt#L197)、[PlayerActivity:825](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L825)。

路径：有旧缓存，断网手动刷新。Repository catch IOException后返回旧parsedCache；上层只看非null，记录LastSuccess=现在、状态READY、“EPG更新成功”，无法呈现应有STALE，也影响刷新调度。切换EPG A→B时非force load直接返回全局memoryCache，不核验URL，可能显示A数据；旧源结果写memoryCache也无代次防护。

要求：返回结构包含网络成功/缓存回退/失败及源ID；只真实网络解析成功更新LastSuccess，回退应STALE；内存缓存以源ID分区、源代次校验结果。测试成功缓存后断网与A/B两套不同节目数据。

### R05 / P1 / D07–D08–D11：Requester未保证目标存在/可见，确认取消与加载完成不返焦

证据：[SettingsDrawer:124](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L124)、[根列表:361](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L361)、[确认:279](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L279)、[ChannelPanel:224](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L224)、[432](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L432)。

路径A：滚到设置靠后“节目选择/播放信息”进入再返回。RootSettingsPage离开when即移除，回来LazyColumn使用新默认state从首行开始；rootFocus仅绑旧index，若该行在屏外就没有attach。requestFocus异常被吞掉，无scrollToItem/重试。rootFocusIndex还是位置索引，动态“返回直播/源选择”出现消失会改归属。

路径B：Program层LOADING没有焦点项；完成变Ready/Failure时LaunchedEffect只key visible/layer，不因数据/状态变化再request，可能一直无焦点。回看确认取消仅pendingCatchup=null，不恢复focusedProgramKey；恢复默认确认未在confirmReset变true时请求其按钮焦点。Remote捕获取消固定回第一动作，而非调用项；PlaybackInfo返回按钮没有绑subFirstFocus，进入时请求不存在的目标。

要求：稳定条目ID+保存LazyListState；布局后先滚到锚点，再请求可见目标；将数据/状态替换、弹层取消纳入恢复触发；确认层明确初始焦点、禁用底层可交互区域。设备注入Back/OK/方向后应始终有唯一可见焦点。

### R06 / P1 / D07：Guide仍可能展示上次浏览频道而非正在播放频道

证据：[ChannelPanel:137](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L137)、[147](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L147)、[PlayerActivity:1101](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L1101)。

路径：播放3→浏览8不选择并关闭→Guide。外部只传NavLayer.Programs，内部focusedChannelUrl只在null时设playingUrl，旧8仍优先，节目页打开8。即使3与8同组也会发生，不依赖组切换。

要求：导航请求应携带频道稳定ID/进入原因；Guide直接指定当前liveUrl，频道右进保留浏览焦点，二者不能只靠requestedLayer区分。

### R07 / P1 / D16：数字缓冲后Menu未取消，定时切台可在设置打开时发生

证据：[PlayerActivity:295](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L295)、[319](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L319)、[1079](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L1079)。

路径：输入112→立刻Menu。Menu在数字清空分支之前return，1.2秒Runnable仍提交；onNumberJump没有面板/会话校验，设置打开期间仍换台。符合设计的“Menu先取消数字再打开设置”未实现。

要求：统一在所有打开层动作前清空数字定时器；定时提交校验输入会话与当前可提交状态。另当前onKeyDown未处理键均直接false，不调用super，旧Android的Back按键跟踪和Compose消费顺序需实际注入验证，不能用lint通过替代；本报告不冒称Back已设备复现失败。

### R08 / P0 / D01：仅手动刷新且缓存缺失时，播放器可停在无内容且无错误出口

证据：[PlayerActivity:715](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L715)、[724](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L724)。

路径：有Prefs源/缓存文件名，设置MANUAL，缓存文件缺失或被清理，关闭启动恢复后冷启动。loaded=false，但MANUAL直接跳过整个网络/失败分支；player为空媒体，channelGroups为空，pendingFailure未设置，黑屏无状态。

要求：MANUAL禁止自动网络不等于禁止恢复提示；无缓存立即显示“没有可用缓存”与手动刷新/源配置，绝不进入无内容无状态播放器。

### R09 / P1 / D02：刷新时间全局共享，启动退避和DAILY状态可能让应执行请求被跳过

证据：[AppSettings:105](../app/src/main/java/com/cjlhll/iptv/AppSettings.kt#L105)、[BackgroundSourceUpdater:77](../app/src/main/java/com/cjlhll/iptv/BackgroundSourceUpdater.kt#L77)、[RefreshSchedule:50](../app/src/main/java/com/cjlhll/iptv/RefreshSchedule.kt#L50)、[PlayerActivity:861](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L861)。

时间戳只有全局key，不按源；A刚成功后切B，B继承A时间/退避。DAILY冷启动若上次失败在5分钟内，初次epgDue=false；后续循环固定launchAttempted=true，DAILY永远false，5分钟过去也不重试当天首次启动。STARTUP_ONLY也在被退避挡住时设置playlistLaunchAttempted=true，周期不再补做。EPG startup due=false且无缓存时状态还可能维持LOADING。

要求：每源调度状态，区分“被退避暂缓”与“已尝试启动”；延后补执行，不伪记已尝试；统一scheduler反馈到EPG状态。保存偏好后首次调度的语义应有生产调用测试。

### R10 / P1 / D12：历史频道仍用不稳定组序号，配置成功还在播放前写LastChannel

证据：[PlayerActivity:523](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L523)、[MainActivity:158](../app/src/main/java/com/cjlhll/iptv/MainActivity.kt#L158)、[刷新:682](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L682)。

路径：A在组序号0成功，列表重排A到1；再切A备用线路，pendingCommit.groupIndex=1与lastCommittedGroupIndex=0不同，会把A算上一台。反过来，另一频道重排到原序号0，切台不会更新上一台。缓存刷新不重播时pendingCommit/lastCommitted组索引也未迁移。Main配置仍在首次首帧前写LastChannel，失败新频道会替换最近成功观看记录。

要求：稳定频道组ID（与位置/线路分离），历史提交绑定成功媒体会话；配置只提交源和待播放目标，不提前改成功观看记录；重排测试验证上一台语义。

### R11 / P2：Next/回看能力/探测状态还存在误导性边界

- [PlayerActivity:933](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L933)无当前节目直接continue，即使有未来节目，频道行Next也丢失；信息条查询路径则可显示，二者不一致。
- [ChannelPanel:380](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L380)非空mode/source即“可回看”，未调用CatchupHelper验证；不支持的mode仍会误标。
- [SettingsDrawer:447](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L447)探测完成null仍“未测”；SourceLatencyProber实际区分fresh失败但UI未传递。自动策略第一个可达即停止，[PlayerActivity:984](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L984)，文案“探测最优线路”不准确。

要求：独立计算Now/Next；能力由同一URL构造判断；显式NotMeasured/Probing/Available/Unavailable状态；选择逻辑或文案保持一致。

## 3. 完整 A+B 状态与验证边界

- 单层面板：computePanelWidth仍从BoxWithConstraints实际W计算；频道/分组/节目/日期用when替换，宽度源码方向一致。实际720p/1080p、焦点框、长文本、透明logo、远距可读没有视觉证据。
- 设置：Prefs写入与UI选项存在且接入了多条行为；A09仍被R01/R05/R09/R10阻断，不能按“枚举保存成功”通过。手动列表刷新仍只有最终Toast，无进行中状态/重复提交保护；成功时间参数未在根页实际呈现。
- 播放/回看：首台正常缓存路径和回看返回主要入口有修复；首帧/60秒/seek/声音/ENDED须设备验证；R01/R02/R08仍是观看闭环阻断。
- 配置/扫码：取消与请求代次主要逻辑改善；真正扫码提交、空字段、键盘焦点与LAN重启未实测；R03仍影响源/缓存一致性。
- 事件：映射查询顺序修复，但onKeyDown不能证明Compose优先消费后的全屏映射/数字可达；Back、长按、确认层焦点隔离须遥控器事件注入验证。

推荐下一轮以R01/R02/R03/R08为首要阻断，随后R04/R05/R06/R07/R09/R10完成核心设计一致性；R11与设备验证一起关闭。新增纯函数测试不能代替生产入口、异步交错和UI焦点测试。复查已交叉同步acceptance；本报告不批准当前全量交付。
