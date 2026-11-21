# tivimini 复刻独立源码复查

任务 t5，designer，2026-11-19。结论：**需修订，不能进入“源码复查通过”状态**。本任务只读目标实现，不构建、不安装、不操作TV，不批准自己此前的设计文档或任何实现代码。

复查对象：`C:/Users/Q/Documents/Projects/iqtvlite-integration`，分支`integration/tivimini-baseline`，基线`90ae4cd`，本地未提交候选差异101文件（12030+/130-）。绑定候选APK SHA256 `d28db1f7a9ed20869baf0455f665e892750956ffceb53071f786f7ec85be8581`；本任务未重新生成/执行此APK，身份来自t4交付。后续修复会使此源码结论与候选身份过期，需重新构建及独立复查。

依据：[TIV交互规格](TIVIMINI_INTERACTION_SPEC.md)、t1双仓基线、t3只读设备基线、t4实现与验证记录。没有将此前W/3单层设计作为原版要求。静态接受不代表目标机焦点、首帧、声音、性能或R8运行通过。

## 1. 阻断发现

### SR-01 高：长期存活收集器仍捕获启动频道，后台更新/错误恢复可退回初始台

涉及TIV-07/17/24。这是沿用原版时保留的内部正确性缺陷，不是导航设计变更要求。

- [PlayerActivity:372](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L372)的`currentChannel`是本次组合的普通不可变val；初始`channelGroups`为空时值为null。不同于可在回调时读取的委托状态变量，它不会让已启动协程自动看到重组后的频道。
- [PlayerActivity:703](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L703)的`LaunchedEffect(Unit)`仅启动一次，后台列表事件将捕获值用于`preserveCurrentUrl = currentChannel?.url ?: url`，因此通常总是启动URL A。
- [PlayerActivity:716](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L716)的错误收集器同样把`failedUrl`视为A；724行的所谓“当前频道仍为failedUrl”检查再次读取同一捕获值，无法证明实际播放还在A。刷新成功时728行按A恢复。
- 触发：启动A→换到B→后台刷新成功；或者B播放失败→紧急刷新成功。只要A仍在新列表，状态/信息/播放恢复目标可回A，违背当前观看保持。
- 额外来源风险：[PlayerActivity:507](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L507)仅发送`Unit`，丢失失败媒体身份。722行generation是在消费事件时取值，并非失败产生时取值；只修`currentChannel`捕获仍不足以证明排队旧错误不作用于新媒体。

**要求修复**：长期协程通过`rememberUpdatedState`或执行时读取`channelGroups/currentGroupIndex/currentVariantIndex`获取最新频道。错误事件携带失败mediaId/播放会话代次，并在下载、解析、应用结果前验证仍属于该媒体；不要把“消费时当前频道”当作“产生错误的频道”。后台列表应用也需要保护用户在异步解析等待期间发起的新换台，不能将启动或旧浏览状态重新提交到播放器。

**必要验证**：A→B后触发后台更新仍B；B错误刷新后仍B/其备用线路；A错误排队期间切B，A的恢复结果被丢弃；解析等待时再切C，不被旧恢复改回B。真实流变化由acceptance控制，最小逻辑测试可用注入事件/代次及延迟解析实现。

### SR-02 高：浏览B频道回看时，请求UA/Referer及媒体身份取自直播A

涉及TIV-09/15/24；这是新增请求头适配造成的跨频道接线问题。

- [PlayerActivity:1086](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L1086)回看回调保存包含B.liveUrl的请求，调用`startPlayback(req.catchupUrl)`，没有同步直播频道索引，符合原版浏览与播放分离语义。
- [PlayerActivity:867](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L867)却以回看URL在直播URL列表中查找；通常查不到，于是退到`currentChannel` A。
- [StreamPlayback:34](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/StreamPlayback.kt#L34)使用传入Channel的id、url、UA、Referer与持久化信息，但实际streamUrl为B回看地址。B若要求独立请求头，将鉴权失败；指标与恢复记录也错误归A。
- [PlayerActivity:433](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L433)生命周期恢复选择当前直播索引A，却播放req.catchupUrl；[PlayerActivity:501](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L501)回看重试同样读取当前直播索引。因此仅修首次startPlayback不够。

**要求修复**：统一按`catchupRequest.liveUrl`（或请求中不可变的所属频道对象/稳定ID）解析回看所属频道B，将B的UA/Referer/id与回看URL作为同一播放请求传给prepare；首次、重试、Home恢复均使用相同所属频道身份。保持原版导航和Back回直播语义，不通过强行把浏览频道设为当前直播来掩盖问题。重试须校验该回看会话仍有效。

**必要验证**：A、B设不同UA/Referer，直播A→浏览B历史节目→回看请求必须B请求头；回看失败重试及Home/恢复仍B；退出回看按原逻辑返回B直播。可用localhost受控HTTP/媒体样本检查实际请求，无源站回看能力时不得声称此路径已通过。

## 2. 逐交互调用链覆盖

下表“源码可接受”仅说明未发现与本轮基线冲突的静态导航变化，非独立运行通过。

| TIV | 源码结果及调用链 |
|---|---|
| 01 | 源码可接受：Splash路由→配置或Player；设置/启动Launcher均指向新生产入口。与快照用git diff忽略行尾空白无差异。 |
| 02–03 | 源码可接受：Main字段编辑KeyUp、禁用加载态、LAN推送→同一loadAndPlay→Rust解析→事务提交→Player。测试预填与先验证再保存是已声明增量/内部修复；未把它们标原版。 |
| 04 | 源码可接受：Activity dispatch先检查抽屉/设置/源选择标志，再向全屏播放分发DPAD，面板方向留给Compose。 |
| 05–06 | 源码可接受：PlayerDrawer保留350ms浏览EPG防抖、初始当前台焦点、后续浏览焦点、左进组/右回频道、组OK仅筛选；非单层替换。 |
| 07 | 频道OK→playChannel→playGroupAuto→playGroupVariant，不置drawerOpen=false，保持原版面板。后台维持当前台受SR-01阻断。 |
| 08 | 源码可接受：节目聚焦显示日期，右进日期/左返节目，日期OK仅selectedEpgDate赋值；来源offset保留到UI日期分组。 |
| 09 | 原版历史/现在/未来分支保留；历史请求回调实际进入播放且关抽屉，但回看所属频道参数受SR-02阻断。 |
| 10 | 源码可接受：Activity Back非频道列先请求频道焦点延80ms关闭；Drawer自身Back亦直接关闭。没有逐级Back替换。 |
| 11–12 | 源码可接受：Settings打开初焦源配置；源选择先关设置再开模态，选择线路不关模态；两组件与固定快照逐行一致（行尾规范化）。 |
| 13 | 源码可接受：底部信息条仍仅当前节目、logo和视频参数；5s隐藏效果保留。没有偷偷加入下一节目。跨频道回看名称仍可能用直播组displayTitle，属于原版已有显示限制，未声称修好。 |
| 14 | 源码可接受：Back优先源选择→抽屉→设置→信息条→双Back返回直播/退出；数字缓冲取消属于声明增量。 |
| 15 | seek步长与边界保留；恢复/重试所用请求头受SR-02阻断。Home恢复播放位置仍从头准备，属于上游原有行为，不能称已恢复回看进度。 |
| 16 | 换台加载READY/ENDED/error/12s隐藏与原版一致；没有新增常驻失败按钮。错误恢复正确性受SR-01阻断。 |
| 17–18 | 稳定编号通过channelGroups→各variant URL映射送Drawer，避免筛组重编号；但旧异步频道覆盖未完整修复（SR-01）。 |
| 19–22 | 旧单层/W/3/逐级Back/扩展设置/下一节目等没有冒充本轮原版生产UI。 |
| 23 | 数字显示→1.2s自动提交、OK立即提交、Back取消、方向取消后执行；onNumberJump依当前合并组序号选择。与原版明确分开；无效号Toast反馈，不改当前台。 |
| 24 | 一个实际ExoPlayer，Media3负责解码及Surface；Rust M3U/稳定ID/XMLTV生产接入真实存在。性能只接受结构与体积证据，运行指标待测；请求所属频道仍需SR-02修复。 |

## 3. 数据/构建接线及差异边界

- [build.gradle:57](../../iqtvlite-integration/android/app/build.gradle.kts#L57)与67行将Java及Kotlin main source sets均指到tivimini与bridge目录；旧Views/Service源码不编译。[新Manifest](../../iqtvlite-integration/android/app/src/tivimini/AndroidManifest.xml)只声明新Application、Splash、Main、Player与FileProvider，没有旧播放器Service/旧多视图入口。`tv.iqtvlite.app.MainActivity`为导出alias，实际目标Splash；JNI桥包保持`tv.iqtvlite.app`，与validation applicationId分离正确。
- [M3uParser](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/M3uParser.kt)确实调用RustBridge.parseM3u/stableId；[Rust输出](../../iqtvlite-integration/crates/iqtvlite-capi/src/lib.rs#L86)与适配字段匹配：tvgName/responseTime/catchupMode/Source/altUrls/UA/Referer均有去向。VLC映射及URL suffix拆头保留。默认append与playseek模板是上游行为，非这次编造的回看支持证明。
- [XmlTvParser](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/XmlTvParser.kt)走文件JNI；[guide](../../iqtvlite-integration/crates/iqtvlite-capi/src/guide.rs#L64)输出毫秒、title、provider offset，按起始时间排序。UI保留offset用于日期/HH:mm。无zone默认UTC相对源本地fallback是t4已声明数据差异，**不因此判失败或声称完全等价**。应补UTC设备/UTC+8设备、带+0800及无zone同一输入的日期/回看URL核对；现有+0800测试不能覆盖无zone差异。
- [SourceRepository](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/SourceRepository.kt#L43)将beginChange/commitSource/commitBackground置同一Mutex版本检查下；[Main](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/MainActivity.kt#L162)取消旧Call/Job、验证generation后导航。旧请求与后台结果覆盖已有基础防护。用户已取消而同步磁盘写临界区正在执行的极窄窗口、进程被杀的跨Prefs事务完整性未经运行证明，不超出证据断言绝对原子。
- [EpgRepository](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/EpgRepository.kt)source-key单飞、独立候选下载、解析成功再AtomicFile保存、失败同源回退均在生产链。最多32MiB/10万节目限制是显式实现边界，大源不得被描述为无限可用。
- [Proguard](../../iqtvlite-integration/android/app/proguard-rules.pro)保留RustBridge完整类名成员、EpgData/EpgProgram序列化类及属性；[JNI入口](../../iqtvlite-integration/crates/iqtvlite-capi/src/jni_bridge.rs)与桥名相符。这是源码接线检查，R8后的实际加载、字段/缓存读回仍需最终CI APK仪器测试。
- 主直播及线路切换的prepare每次创建独立Factory/headers，默认UA不沿用前一频道，符合隔离方向；错误是调用方回看传错Channel（SR-02），不能由已有A→默认B的单一请求测试推出跨频道回看也正确。

## 4. 保留的原版限制与验收责任

这些不是本次导航复刻的新增阻断项：焦点高亮与selected播放标记原版未分离；无EPG没有专门错误操作按钮；EPG设置“更新”标志可能受原循环sleep延迟；回看节目名/频道名原版混当前直播组；回看重试计数按播放器实例累计而非每个节目清零；更新失败仍可能提示已最新。后续修复应保留本轮导航基线并记录可见差异，不能声称原版已有完善终态。

真机需补：配置IME与遥控焦点、手机实际提交、四列可见区域与density、模态穿透、长按/双Back、首帧与音频、Home/恢复、坏流/回看/多线路、数字及全局号对应、CPU/PSS/切台分布。体积14,107,543B相对旧universal +7.36%仅在1.10容差，不证明运行优化。未看图不推断视觉通过，也不因无图输入判画面失败。

本任务只读验证命令：目标git status/diff --stat；上游快照与生产PlayerActivity/MainActivity/PlayerDrawer的`git diff --no-index --ignore-space-at-eol`；Settings/Quality/Loading逐行比较为相同；Splash的原始文本比较因行尾空白显示不等，但规范化git diff无差异。`--no-index`差异返回1为有差异，不是构建失败。未执行Gradle/Cargo或ADB。

建议由Captain创建修复任务处理SR-01/SR-02，再独立复查修复diff、重新生成CI候选并交acceptance运行上述具体路径。当前t5结论为failed/needs_revision，不能因原版组件近似相同或t4本地构建通过而改称完成验收。
