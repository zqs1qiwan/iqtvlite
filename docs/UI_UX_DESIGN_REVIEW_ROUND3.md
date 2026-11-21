# 第三轮修复独立设计与事件来源复查（t15）

日期：2026-11-18。审查人 designer。结论：**needs_revision**。S01/S02/S03/S05原始反例的生产调用链已得到实质修复；S04返焦主要路径有改进，但设置模态隔离未闭合；S06仍有“今日已有成功数据时启动，再常驻跨午夜”的明确遗漏分支。

依据：[设计](UI_UX_DESIGN.md)、[验收矩阵](UI_UX_ACCEPTANCE.md)、[S01–S06审查](UI_UX_DESIGN_REVIEW_ROUND2.md)、[t13修复说明](UI_UX_REPAIR_ROUND3.md)。本次只读当前源码与新增测试，且只写本报告；未修改Kotlin、未运行Gradle。71项测试/lint成功为t13交付证据，不是本任务重跑证据。无设备或图像运行验证，不声称视觉/遥控器/首帧/扫码通过。

## 1. 逐 S 源码结论

| S | 源码结论 | 生产证据与验证边界 |
|---|---|---|
| S01 | 原反例源码已修复 | [PlaybackAttemptState:82](../app/src/main/java/com/cjlhll/iptv/PlaybackControl.kt#L82)分离mediaAttempt/recoveryBudget；[PlayerActivity:539](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L539)READY调用onReady只清预算；[834](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L834)错误使用同一状态机。a1 READY后仍接受a1错误；原READY清媒体attempt问题未回归 |
| S02 | 事件来源结构修复成立，真实运行待验证 | [prepare:562](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L562)每次先释放旧播放器，再新建、绑定监听，再setMediaItem；[监听:524](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L524)闭包捕获该实例token，READY/ENDED/error均先eventRouter拒旧。不再读取可变currentMediaItem盖章。真实ExoPlayer排队/释放时序尚未注入测试 |
| S03 | 原STOP慢probe反例源码已修复 | [STOP:601](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L601)提升gate、取消probe/recovery、清router/token并释放；[提交:1027](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L1027)检查STARTED且按group.key重找频道。正常生命周期实际交错仍待设备；线路变体仍按原下标coerce而非URL重新映射，属后续边界测试项 |
| S04 | 历史节目取消返焦主要修复；设置模态隔离仍需修订 | [ChannelPanel:423](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L423)确认前记录节目key，[267](../app/src/main/java/com/cjlhll/iptv/ChannelPanel.kt#L267)取消后滚到锚点、多帧request；设置Remote记录原动作并持有requester，取消回原动作/reset行。设置捕获/重置弹层没有禁用底层或封闭方向焦点，见T02 |
| S05 | 原forceRefresh/null注入/缓存线程反例源码已修复 | [EpgRepository:45](../app/src/main/java/com/cjlhll/iptv/EpgRepository.kt#L45)IO内读取同源内存/磁盘候选，forceRefresh不清候选；[60](../app/src/main/java/com/cjlhll/iptv/EpgRepository.kt#L60)有fetcher即使用该函数，null不再Elvis回真实网络；生产确实调用loadStructuredCore分类并缓存成功数据。真实文件与网络、gzip大文件仍待验证 |
| S06 | 每源inFlight实质接入，DAILY首次启动语义仍不完整 | [PlayerActivity:856](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L856)启动/周期/手动统一perform并tryBegin/finally end；[BackgroundSourceUpdater:189](../app/src/main/java/com/cjlhll/iptv/BackgroundSourceUpdater.kt#L189)配置后刷新也同tryBegin。不能仅因共用inFlight就判DAILY通过：今日成功缓存启动不seed，跨午夜仍刷，见T01 |

## 2. 残余问题与可执行修复要求

### T01 / P1 / A09：DAILY 今日已有成功数据时启动，常驻跨午夜仍会刷新

证据：[EpgRefreshCoordinator:45](../app/src/main/java/com/cjlhll/iptv/EpgRefreshCoordinator.kt#L45)、[59](../app/src/main/java/com/cjlhll/iptv/EpgRefreshCoordinator.kt#L59)、[67](../app/src/main/java/com/cjlhll/iptv/EpgRefreshCoordinator.kt#L67)、[PlayerActivity:924](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L924)、[945](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L945)。

源码可推导的反例：

1. 选择“每天首次启动”，同源今天已有一次成功更新（上一个进程/手动更新均可）。
2. 冷启动新进程，dailySeeded为空，lastDay==today，shouldRunAuto返回false，因此performEpgRefresh不执行，也不markAutoAttempted。
3. 保持运行跨过午夜。周期再查时lastDay!=today，dailySeeded仍不含源，shouldRunAuto返回true；未重新启动却发生每日刷新。

这与已批准“每天首次启动”及修复说明的“常驻跨午夜不刷新”不一致。新增dailyDoesNotRefreshAgainWhenResidentAcrossMidnight测试先主动markAutoAttempted，只覆盖启动需要网络的分支，没有覆盖已有今日成功数据而跳过网络的启动。

修复要求：记录本进程对该源的启动基准日期/启动资格判断，与“实际请求已尝试”分开。启动时已有今日成功数据应完成该次启动的DAILY判断；启动因退避暂缓且尚需刷新，才保留待补做资格。补三条可控时钟反例：今日成功→启动跳过→跨午夜不刷；旧成功→启动暂缓→退避后补做；补做完成→跨午夜不刷。测试直接驱动生产coordinator并核对生产入口实际记录启动状态。

### T02 / P1 / A09/A12：设置捕获与恢复默认弹层未建立模态焦点边界

证据：[SettingsDrawer捕获/确认控制:138](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L138)、[Remote列表:434](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L434)、[CaptureOverlay键事件:543](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L543)。

捕获/确认显示时，底层RemotePage仍组合为可聚焦可点击项。设置文件没有focusProperties或等价禁用底层的逻辑；CaptureOverlay在KeyDown直接返回false，只在KeyUp捕获键。方向按下仍可参与空间焦点搜索，移出捕获层后相应KeyUp不一定经过捕获节点；恢复默认“取消”后的方向也没有模态边界。已有左右键代码只避免底层处理Left，不会封闭其他方向或背景焦点。

这不是声称已在设备复现焦点逃逸，而是源码没有实现承诺的唯一顶层事件/焦点约束；实际遥控体验保持未验证，不能将“模态隔离已完成”销项。

修复要求：捕获期间消费完整方向键按下/释放并把待分配键交给捕获状态机；确认期间把焦点限制在确认按钮组，禁用/隐藏背景的焦点参与。不要只在父容器设置canFocus=false却让子列表继续可聚焦；应逐项控制或使用可靠的模态容器。弹层出现后等布局再请求初始焦点，取消回保存的动作/reset目标。设备/Compose测试覆盖非首项、左右上下长按、Back取消与背景不被修改。

## 3. 测试覆盖核对

见 [Round3LogicTest](../app/src/test/java/com/cjlhll/iptv/Round3LogicTest.kt)。本次只读。

- S01三项确实驱动生产PlaybackAttemptState；a0失败→a1 READY→a1再次错误反例已覆盖，比仅token编解码有效。
- S02路由测试驱动AttemptEventRouter，但并未创建ExoPlayer或排队真实回调；本次通过源码确认每attempt独立实例与捕获token，运行时证据仍缺。测试本身不能发现Activity若继续读取currentMediaItem而router不变的错误，修复说明中“该测试会揭露旧读取方式”应限制表述。
- S03慢probe测试使用生产ProbeCoordinator及Deferred，测试中直接gate.newIntent模拟STOP；能证明gate失效后协调器拒旧，不能单独证明Lifecycle生产观察器接线。本次已读观察器确认接入，真实STOP时序未运行。
- S05六项调用生产loadStructuredCore；网络null/无缓存/坏解析回退有覆盖，成功两源测试注入parser规避Android XML限制，不能声称真实XMLTV解析/磁盘缓存已通过。`injectedFetcherNullDoesNotCallRealNetwork`仅断言Failure，没有网络调用计数；当前源码明确不回退网络，但测试断言本身不足以检测“请求真实网络后失败”。
- S06三项真实驱动coordinator时钟/inFlight；没有锁闩挂起网络请求，只是顺序tryBegin两次；可以验证set去重规则，不能证明所有生产入口响应的一致性。DAILY缺少T01分支；没有Compose焦点测试。

## 4. 原 R/D 保持与源声明准确性

- R03：当前Main/后台仍经SourceStore同一repo锁，缓存仍AtomicFile；源元组已改[Prefs.setSourceConfig](../app/src/main/java/com/cjlhll/iptv/Prefs.kt#L66)单Editor，version已改[持久Prefs读取](../app/src/main/java/com/cjlhll/iptv/SourceRepository.kt#L40)。不重复报告原来的进程内交错和三字段分别apply问题。
- version与源元组仍是两个Editor提交；文件与Prefs也不是跨资源事务。修复说明可准确称“持久版本+单Editor源元组”，不可称崩溃原子/跨进程并发已证明。当前无崩溃测试；Mutex只保证本进程同repo写者，不保证其他进程共同锁。
- 已读历史提交仍在成功媒体事件下用稳定channelKey；回看仍不写直播成功历史。单实例每attempt改造可能增加换台重建开销，首帧/资源释放/画面保留需设备性能观察，不能从结构修复推出稳定播放通过。
- Guide指定频道、数字Menu取消、MANUAL无缓存出口、Now/Next/透明logo的已有修复未发现本轮直接回退；本次未重复全量设备验收。字号/宽度源码不等于实际远距视觉。

## 5. 后续验收与终态

T01需代码与可控时钟测试修复；T02需明确焦点/事件隔离实现并补遥控器注入证据。之后继续按A01–A14补设备首帧、60秒、扫码、断网缓存、回看、焦点和视觉。已修S01/S02/S03/S05无需无理由重写，但需运行证据补足。

本报告提交后停止读取源码，不继续追加实现发现；等待Captain后续任务。只新增本报告，不改Kotlin、不跑Gradle，已与acceptance交叉同步。源码审查不批准当前A+B全量验收，也不冒称设备/视觉通过。
