# 最终两项修正源码复查（t18）

日期：2026-11-18。审查人 designer。结论：**限定范围源码复查通过（pass）**：本轮指定的T01/T02原反例已有真实生产接线，本次未发现阻止其源码销项的直接残余；S01/S02/S03/S05关键接线未见直接回退。

**完成后交叉补充：acceptance t17发现T01“启动退避跨午夜”组合仍有P1残余，Captain要求纳入正式报告，详见下文§5。原t18限定范围pass记录保持不变，但不能据此将T01/A09完整销项；该新增反例需最小修复。T02结构结论不变。**

这不是A01–A14全量验收通过，更不是视觉或遥控器运行通过。当前无图像/设备运行证据，相关项目仍为未验证。任务范围依据 [第三轮审查](UI_UX_DESIGN_REVIEW_ROUND3.md)、[第四轮修复依赖结果](UI_UX_REPAIR_ROUND4.md)、[设计](UI_UX_DESIGN.md)及[原A01–A14矩阵](UI_UX_ACCEPTANCE.md)。本任务仅新增本报告，不改Kotlin、不跑Gradle。

## 1. T01：DAILY启动判定与暂缓补做

**源码结论：原缺陷已修复；对应A09行为运行待验证。**

生产证据：[EpgRefreshCoordinator:60](../app/src/main/java/com/cjlhll/iptv/EpgRefreshCoordinator.kt#L60)。DAILY先检查dailySatisfied，再以dailyLaunchDay固定本进程首次判断日期；今日已有成功记录时加入dailySatisfied并返回false，不依赖实际发请求才记录启动判断。原“今日成功→启动跳过但未seed→常驻跨午夜刷新”的反例已被切断。

退避路径在记录launchDay后检查最近尝试，暂缓不会加入dailySatisfied；同一启动判定日内退避到期可再次返回true。实际请求通过markAutoAttempted加入dailySatisfied，之后跨午夜不会再按日期触发。生产播放器启动与周期仍调用同一coordinator，见 [PlayerActivity:924](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L924)、[945](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L945)，不是只在测试中接入。

新增三项 [Round3LogicTest:213](../app/src/test/java/com/cjlhll/iptv/Round3LogicTest.kt#L213)直接驱动生产coordinator和可控clock：今日成功启动跳过且跨午夜不刷；旧成功启动退避后补做；实际补做后跨午夜不刷。它们覆盖了上一轮遗漏分支。本任务未重跑；通过结果采用t16依赖证据，运行调度/真实网络时序仍须独立验收。

## 2. T02：设置模态边界、完整按键与返焦

**源码结论：指定结构缺陷已修复；对应A09/A12/A13运行待验证。**

| 检查项 | 实际生产接线 |
|---|---|
| 背景逐子项失焦/禁点 | [SettingsDrawer:245](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L245)以capturing==null且非confirmReset控制RemotePage.enabled；[475](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L475)与reset行[485](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L485)均逐项传入，最终SettingsRow同时控制focusable和clickable。不是仅禁用父容器 |
| 捕获完整Down/Up | [CaptureOverlay:563](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L563)把两类事件传给同一[CaptureKeyState:19](../app/src/main/java/com/cjlhll/iptv/CaptureKeyState.kt#L19)，所有结果均消费；方向Down不再返回false参与背景导航 |
| 一次提交/重复忽略/Back取消 | CaptureKeyState只在匹配Down→Up后Submit，提交后清pressed；重复Down消费，非匹配Up不提交；Back返回Cancel。设置既有禁用键/冲突规则继续处理Submit |
| reset布局后初焦 | [SettingsDrawer:144](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L144)等待帧后对confirmFocus重试，绑定弹层“取消”按钮；背景Remote项目禁用 |
| 关闭后重新触发恢复 | capturing或confirmReset关闭时提升remoteFocusNonce，见[140](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L140)；不是依赖未改变的focusIndex重新触发 |
| 滚动/attach/正确调用目标 | [RemotePage:457](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L457)effect包含nonce，先scrollToItem，再按普通动作/reset选正确Requester，等帧重试；动作行与reset行分别绑定正确目标 |

新增三个状态机测试见 [Round3LogicTest:261](../app/src/test/java/com/cjlhll/iptv/Round3LogicTest.kt#L261)：完整匹配事件只提交一次、方向消费与Back取消、不匹配Up不提交。它们调用生产CaptureKeyState，不是假设测试名代表UI通过；不覆盖Compose实际焦点搜索、窗口事件或厂商键。当前源码已经补齐背景逐项禁用与完整事件消费，因此不再把单纯缺设备证据重复作为T02未修复。

需设备补证：非首项映射→长按方向→冲突提示→Back取消→回原动作；滚到底部reset→取消→回reset；弹层中背景不出现焦点/点击；关闭弹层后的第一键不误触发第二层。实际观看距离与焦点框仍未测。

## 3. 已修关键项回归抽查

| 原项 | 本轮源码抽查 |
|---|---|
| S01媒体attempt/预算 | [PlayerActivity:539](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L539)READY仍只调用attemptState.onReady，媒体身份不归零 |
| S02真实来源 | [监听:524](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L524)闭包捕获token；[prepare:562](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L562)仍每attempt独立播放器；未退回currentMediaItem回调时盖章 |
| S03停止生命周期 | [STOP:601](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L601)仍提升gate，取消probe/recovery，清router/token并释放播放器 |
| S05缓存回退 | [EpgRepository:45](../app/src/main/java/com/cjlhll/iptv/EpgRepository.kt#L45)仍在IO取同源缓存候选；[60](../app/src/main/java/com/cjlhll/iptv/EpgRepository.kt#L60)有注入fetcher时null不回真实网络 |

以上只针对指定不回退范围抽查，不重新替代全部D/R或A01–A14设备验收。每attempt播放器重建性能、真实旧回调、STOP时序、文件与网络、崩溃一致性仍没有运行证明。

## 4. 证据与交付边界

- t16依赖报告为77项单测通过，lint 0 Error/69 Warning/6 Hint，APK SHA256 `BAA9D1E897F085CB87B3A9A3F53BCBF782AF6BBF0DD756508B1D4E681E486AE1`。本任务不重跑、不独立验证该构建标识；构建证据由acceptance独立核对。
- A09中T01/T02对应源码修复可销项；A09完整持久化与实际按键行为、A12唯一焦点/返回、A13可读/视觉，仍需设备证据。A01扫码配置到A14构建的原编号与标准保持不变。
- 首帧与60秒、手机实际扫码提交、断网/503/缓存、回看seek与ENDED、720p/1080p焦点、透明台标、API24实际运行均为未验证，不以当前源码pass推定通过。
- 勘误：本轮最初“没有新增必须修改Kotlin的阻断发现”仅对应原三项T01分支与T02结构检查。完成后t17已确认唯一P1待修为§5的跨午夜pending组合；应先做该调度分支最小修复，再独立构建/设备验收。不据原pass宣称T01/A09全量通过。

## 5. 完成后交叉补充：T01跨午夜pending补做（P1，A09）

来源：acceptance t17逐行核对及Captain确认；依据本任务已读的 [EpgRefreshCoordinator:63](../app/src/main/java/com/cjlhll/iptv/EpgRefreshCoordinator.kt#L63)，本补充没有重新读取源码或运行命令。

反例：23:58冷启动，同源旧成功数据，最近一次尝试仍处5分钟退避。shouldRunAuto记录dailyLaunchDay=D，返回false且未置dailySatisfied。00:03退避结束后，周期再次调用时today=D+1，代码先执行today!=launchDay→false，尚未执行的启动补做被吞掉。现有三项T01测试分别覆盖今日成功启动跳过、同日退避补做、补做后跨午夜，没有覆盖“启动暂缓本身跨午夜”的组合。

此项属于原“退避保留补做资格”要求，不改变“完成启动判断后常驻跨午夜不刷新”的产品语义。修复要求：显式区分pending启动请求与已满足/已尝试状态；允许启动时已欠的一次补做在退避结束后跨午夜执行一次，执行后消除pending。增加23:58→00:03可控时钟反例，确认补做一次且后续常驻不因日期重复刷新。

t18原终态pass不可变，适用范围仅原三分支与T02源码结构，不覆盖这个新增组合。该P1由t17正式记录并待Captain安排最小修复；设备、视觉与遥控器限制继续保持。T02无需因该发现重新判结构失败。

本报告及交叉补充提交后停止读取源码，等待Captain后续安排。仅编辑本文档，无Kotlin改动、无Gradle执行；补充已同步Captain与acceptance。
