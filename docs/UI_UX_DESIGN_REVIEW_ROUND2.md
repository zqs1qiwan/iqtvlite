# 第二轮修复独立设计与调用链审查（t12）

日期：2026-11-18。审查人 designer。结论：**needs_revision**。本轮修复实质关闭了多个原缺陷，但恢复后再次断流、事件来源、停止生命周期中的旧探测、弹层返焦等生产路径仍未闭合。

依据：[设计](UI_UX_DESIGN.md)、[验收矩阵](UI_UX_ACCEPTANCE.md)、[R01–R11复查](UI_UX_DESIGN_REREVIEW.md)、[t10修复记录](UI_UX_REPAIR_ROUND2.md)。已读当前实现、新增测试及缓存/EPG关键Git差异；Git基线早于本轮，diff包含历史工作区改动。本任务只新增此文档，未修改Kotlin、未运行Gradle。57项单测与lint成功是t10提供的证据，不代表本任务重新执行。

无图像或设备运行证据；所有“源码已修复”均不等于视觉、遥控器、扫码、解码首帧、60秒播放通过。

## 1. 逐 R 状态与证据

| R | 源码复查状态 | 真实调用路径/限制 |
|---|---|---|
| R01 | 部分修复 | [playGroupAuto:1017](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L1017)真实使用ProbeCoordinator，[startSession:519](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L519)提升共同意图，较新手动源/回看可使旧probe失效。ON_STOP未使gate失效，旧probe仍可后台创建播放器，见S03 |
| R02 | 未修复完整，阻断 | 媒体确有token，生命周期prepare和12秒监控已统一；但[error:599](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L599)仍读取回调当时的currentMediaItem，非事件来源。READY清attempt破坏随后错误接受条件，见S01/S02 |
| R03 | 原进程内写者交错主要已修复 | 当前所有生产源/缓存写者grep收敛至SourceStore：Main→commitSource、后台→commitBackground，同一包名复用repo同一Mutex；版本校验与写缓存/指针同锁，A→B→A可拒旧。AtomicFile替代删旧文件回退。持久事务声明与实际仍有差距，见§4 |
| R04 | 原误记成功与跨源内存主要已修复，回退实现仍有缺口 | EpgLoadResult与按URL缓存真实用于Repository和刷新上层；只网络Success记成功时间。forceRefresh=true时不读取任何旧缓存，CacheFallback分支不可达；测试只测分类器，见S05 |
| R05 | 部分修复 | [根页:374](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L374)实际按稳定id滚动再多帧request，修复屏外根目标；节目状态变化触发焦点，播放信息按钮已绑Requester。确认取消仍请求错误节目；Remote取消仍第一项，模态初始焦点无布局保证，见S04 |
| R06 | 源码主要已修复，待设备 | ChannelPanel请求携带requestedChannelUrl/EntryGuide，Guide覆盖旧focusedChannelUrl；浏览右进保持浏览台。需实际验证当前台、不同组、反复Guide和Back |
| R07 | 原Menu缺陷源码已修复，待设备 | Menu和映射开层先cancelNumberInput，默认未处理键回super；原数字Runnable设置中切台路径已阻断。Compose先消费后的全屏按键可达仍须注入验证；不能因onKeyDown存在判通过 |
| R08 | 源码已补出口，待设备 | MANUAL无缓存设置pendingFailure；失败重试在无target时真实手动刷新并apply Initial，否则呈失败。需断网/坏列表/取消/成功首台复验 |
| R09 | 按源与暂缓补做有修复，语义/生产测试仍未闭合 | 时间键按源hash区分，列表退避不再立即标尝试；EPG DAILY周期把launchAttempted设false补做，但跨天持续运行也刷新，不再是“每天首次启动”。启动/手动/周期EPG仍可重复请求，见S06 |
| R10 | 原索引历史主要已修复，受事件来源阻断 | 正常选台和备用线路CommitInfo传group.key；READY匹配token后写Prefs；Main已移除播放前写LastChannel。媒体来源及READY接受条件必须先修S01/S02，才能宣称“真实成功历史”可靠 |
| R11 | 源码主要已修复，待设备 | Now/Next独立计算；catchup标记调用真实构造器；SourceVariantUi加probed区分失败；文案改“选择可用线路”；手动列表更新有状态/去重。实际布局和探测四态待设备 |

## 2. 仍阻断或需要修订的发现

### S01 / P0：恢复成功后再次报错会被 attempt 不一致直接丢弃

证据：[READY:584](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L584)、[事件接收:822](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L822)。

源码可复现路径：初始媒体a0失败→接收分支将attempt=1→prepare新token iX-a1-sY→恢复READY→589行attempt=0，但activeMediaToken和MediaItem仍是a1→稍后再次断流error读出a1→828行parsed.attempt(1)!=attempt(0)，直接return。12秒监控也只针对准备期触发一次；此后不能进入恢复/失败终态。

修复要求：当前媒体尝试身份不可在READY后单独重置。区分“当前媒体attempt”与“连续恢复预算”，成功后重置预算但保留有效媒体身份，或以新的已成功会话身份完成一致切换。加真实生产状态驱动测试：a0失败→a1成功→再次错误→应开始新恢复，而不是只测token往返/整数accepts。

### S02 / P0：currentMediaItem 仍不是排队错误/状态回调的来源身份

证据：[Player.Listener:580](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L580)、[onPlayerError:599](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L599)。

READY/ENDED/error都在回调时从p.currentMediaItem读token。它能阻断已记录旧token的事件，但若旧加载回调到达时播放器已切为新媒体，读到的是新token，旧事件仍被盖上新身份；与上一轮“当前session盖章”的缺陷同类。未见AnalyticsListener.EventTime、错误timeline/mediaPeriod对应项或独立attempt播放器/监听器绑定。新增MediaToken编码不提供事件时间来源。

修复要求：采用真正可回溯事件MediaItem/媒体期身份的监听方案（例如正确解析AnalyticsListener.EventTime），或每attempt隔离播放器/监听生命周期，使旧实例事件不能归新媒体；READY/ENDED/错误都按同一来源规则。加可控旧回调排队到新选择之后的测试。t10文档承认这一风险，不应在“修复闭合”同时把它归为仅极端设备观察。

### S03 / P0：ON_STOP没有失效探测意图，旧probe可在后台重新启动播放

证据：[ON_STOP:559](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L559)、[自动探测:1023](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L1023)、[ProbeCoordinator](../app/src/main/java/com/cjlhll/iptv/PlaybackControl.kt#L26)。

路径：启动慢probe→用户Home/进入全屏配置→ON_STOP取消recoveryJob、清token、releasePlayer，但未newIntent也未取消probe Job→probe结束，gate仍current→playGroupVariant→startSession→prepareMediaNow→ensurePlayer，Activity已停止仍新建并播放。rememberCoroutineScope随组合销毁才取消，ON_STOP不等于组合销毁。

此外自动探测捕获groupIndex，等待期间后台列表重排但未改变意图时，返回后该index可能对应另一频道；应使用稳定group.key重新解析目标。

修复要求：将probe Job与恢复/timeout统一挂在可停播会话，ON_STOP提升gate并取消；提交前校验生命周期STARTED和稳定频道ID。现有probe测试仅覆盖显式gate.newIntent，不验证生命周期生产入口是否真的调用它。

### S04 / P1：回看确认取消仍不回到刚操作的历史节目，捕获/重置取消也没有完整恢复

证据：[ChannelPanel:199](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L199)、[取消请求:266](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L266)、[onPendingConfirm:416](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L416)、[SettingsDrawer:135](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L135)。

路径：不先进入日期，直接从当前节目上下浏览到远处历史节目→OK确认→取消。programAnchorKey仅在进入日期时保存，确认前未赋focusedProgramKey；programRequester仍绑effectiveProgramFocusIndex（当前节目/旧日期锚点），取消effect只request，不先滚到刚才节目。当目标离屏时Requester未attach，异常吞掉；可见时则跳回错误节目。

设置Remote捕取消仍request subFirstFocus（固定第一个动作），不回调用动作。恢复默认确认仅true时request一次，没有取消后恢复原行；其初始request也未等待布局。根页滚动修复不等于这些模态恢复已修复。

修复要求：进入模态前记录调用稳定ID；关闭后滚动到该ID、等待attach、request；Remote也记录动作ID；reset取消恢复“恢复默认映射”行。测试需在目标超出一屏时操作，避免首项验证掩盖缺陷。隐层/模态焦点隔离和布局可靠性仍待设备。

### S05 / P1：强制刷新不读取旧缓存，结构化回退的生产入口与测试脱节

证据：[EpgRepository:45](../app/src/main/java/com/cjlhll/iptv/EpgRepository.kt#L45)、[49](../app/src/main/java/com/cjlhll/iptv/EpgRepository.kt#L49)、[62](../app/src/main/java/com/cjlhll/iptv/EpgRepository.kt#L62)、[BackgroundSourceUpdater:201](../app/src/main/java/com/cjlhll/iptv/BackgroundSourceUpdater.kt#L201)。

所有实际刷新传forceRefresh=true，导致memory读取和disk读取全部跳过，cached固定null；断网时EpgOutcome只能Failure，不能返回已有CacheFallback。当前Player若已有epgData会保留STALE，改善了误成功，但Repository承诺的回退并未实现，首次启动/后台调用不能可靠拿到已有缓存回退。

另注入fetcher返回null时Elvis表达式会继续downloadBytes，无法用null稳定模拟网络失败；三个新增EPG测试未调用loadStructured，未揭露上述问题。缓存/文件加载发生在withContext(IO)之前，缓存较大时还会在调用线程执行反序列化。

修复要求：forceRefresh只表示“必须尝试网络”，仍读取同源旧缓存作为失败候选；明确fetcher存在时返回null就是失败，不回退真实网络；结构化结果携带来源，并在网络/解析失败测试中检查实际Repository结果及LastSuccess不变。缓存IO整体放IO dispatcher。

### S06 / P1：DAILY产品语义与并发刷新入口仍不一致

证据：[EPG启动:917](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L917)、[周期:936](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L936)、[手动:851](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L851)、[配置后刷新](../app/src/main/java/com/cjlhll/iptv/BackgroundSourceUpdater.kt#L129)。

EPG周期对DAILY始终传launchAttempted=false，跨午夜而不重启也会刷新，选项却仍“每天首次启动”。初始被退避挡住且无缓存时LOADING不立即转可恢复状态。手动epgRefreshInProgress仅保护refreshEpgNow，启动/周期直接loadStructured、配置后台refreshAll不共享它，可能并发；启动/周期LastAttempt在网络结束才写，不能阻挡进行中请求。

修复要求：区分启动待补做与跨天持续运行；统一每源inFlight、尝试时间/成功时间和状态提交，手动与自动共用同一协调入口。补可控时钟+锁闩Scheduler生产入口测试，覆盖启动暂缓后补做、跨午夜不重启、手动与自动并发，不只是RefreshSchedule纯函数。

## 3. 新增10项测试到底证明了什么

见 [Round2LogicTest](../app/src/test/java/com/cjlhll/iptv/Round2LogicTest.kt)。本任务只读，不重新执行。

| 测试组 | 实际调用 | 能揭露/不能揭露 |
|---|---|---|
| SourceRepository四项 | 真repo + FakePrefs/FakeCache；顺序捕获版本后提交手动/后台 | 能揭露缺版本拒绝、A→B→A、返回false写失败的指针保护；没有缓存写过程锁闩、多线程同时入锁、真实Android AtomicFile/Prefs、进程中断 |
| ProbeCoordinator两项 | 真协调器+真实gate；其中一项CompletableDeferred挂起并中途newIntent | 能揭露协调器await后不校验；不能证明所有生产入口（特别ON_STOP）实际提升意图，也不测试播放器副作用 |
| MediaToken一项 | 仅encode/parse往返与非法字符串 | 不调用Player/Listener/READY/恢复收集器；不能揭露S01、S02；注释所称“无事件归属会失败”不适用于此项 |
| EPG三项 | EpgOutcome.classify与EpgSourceCache | 能揭露分类顺序和map源隔离；不调用loadStructured/fetcher/磁盘/上层状态与时间提交，不能揭露S05 |

没有新增测试覆盖Compose焦点attach/滚动、Guide、Menu数字交错、调度生产入口、成功历史状态流。本轮测试有价值，但“57项通过”不能推出R01–R11全部闭合。

## 4. 源事务与历史的审查边界

- 已核对当前源/缓存写者均经SourceStore同一repoMutex；AtomicFile本身不提供线程锁，实际互斥来自SourceRepository。原后台check→手动保存→后台写旧指针的进程内交错已得到实质修复，不重复列为未修。
- [SourceRepository:37](../app/src/main/java/com/cjlhll/iptv/SourceRepository.kt#L37)version只是内存变量，无持久化实现；[Android适配器:70](../app/src/main/java/com/cjlhll/iptv/SourceRepository.kt#L70)仍以三次Prefs.apply写源/EPG/指针。因此不能称“持久version/崩溃原子事务”，进程中断一致性未验证。可改一Editor提交配置元组并说明版本生命周期；本项不抵消已修复的进程内互斥。
- 写入使用AtomicFile.finishWrite/failWrite，不再主动删旧文件。读路径仍普通File.readText；真实平台原子替换/中断恢复需Android IO测试，不以FakeCache证明。
- 历史按group.key而非位置，Main不再提前写LastChannel：原R10方向正确。但成功判定依赖S02来源，恢复后再次错误依赖S01，应修后再跑“不同频道/同频道线路/重排/失败台/回看”状态流测试。

## 5. 交付门槛

先关闭S01–S03观看与事件阻断，再修S04–S06并补对应生产路径测试。已修项保持逐条记录，不以继续发现缺陷否定已有工作。最终仍需目标设备/可用TV模拟器完成A01–A14中的焦点、真实首帧与60秒、手机扫码、断网缓存、回看seek/ENDED、透明台标与远距视觉证据。

本任务结论已交叉同步Captain与acceptance；不批准当前全量A+B交付，不声称遥控器或视觉已通过。
