# SR-01 / SR-02 独立复查及 CI 门禁结论

任务t9，designer，2026-11-19（Asia/Hong_Kong）。**结论：needs_revision / failed，暂不放行后续CI；SR-01、SR-02原发现主路径源码修复接受，另有一个明确窄边界SR-03需修复复验。**

本次依据[原复查](TIVIMINI_SOURCE_REVIEW.md)、[24项TIV规格](TIVIMINI_INTERACTION_SPEC.md)和[t8修复记录](../../iqtvlite-integration/docs/SOURCE_REVIEW_REPAIR.md)。只读审查独立目标工程`integration/tivimini-baseline`，HEAD仍`90ae4cdd0ba7f0acbeb25955868dc5d4b1d0bac9`，实现未提交。未改Kotlin、未运行Gradle/Cargo、未操作设备。

## 1. 审查对象与证据冻结

本地修复APK身份由t8记录：SHA256 `61af7ac08a07dfc4ae56485f059fb45e13cb360e8163f5a0ef043f01961f8ac3`，测试APK `da99499fd0581e165f4fb04c25464886a80667d88bfd80134a17fd3540e06ca7`。均为独立validation包，不代表新r27 CI，也不证明设备已运行。

本次实际读取的关键源码SHA256：

| 文件 | SHA256 |
|---|---|
| [PlayerActivity](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt) | `006021CEBFAE65BFDD42F2133444BC5F187F49A4EB0B2276364A3DC33CE12F77` |
| [PlaybackIntent](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlaybackIntent.kt) | `8B8018D4D080AAFF288F3FE0166C195B24D222A7CD1A18875C2D188A54768491` |
| [StreamPlayback](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/StreamPlayback.kt) | `DC81F0A05FEB4E7956B2110DDA1F5F42F1D91451FA94C62D300F2E17A080AE05` |
| [PlaybackIntentRegressionTest](../../iqtvlite-integration/android/app/src/test/java/com/cjlhll/iptv/PlaybackIntentRegressionTest.kt) | `329E5C8A188F20520FC1D10CF2AEB533DA7F7D3E7271DD809C52F17385ED8D4E` |

developer确认t8已冻结且本轮没有并发更改。本报告完成后停止读取目标源码；后续修改不归入此次证据。

## 2. SR-01 主路径：源码接受

1. [PlayerActivity:381](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L381)的selectedLiveNow和liveSelection在执行时读取委托的channelGroups/currentGroupIndex/currentVariantIndex。长期[后台收集器:636](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L636)与[恢复收集器:646](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L646)通过这些函数取得新状态，不再直接捕获首次普通val currentChannel=null。
2. [StreamPlayback:87](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/StreamPlayback.kt#L87)在真实AnalyticsListener.onPlayerError产生PlaybackFailure，验证EventTime timeline窗口mediaId与本次PreparedPlayback及当前player mediaId一致；发送的是不可变prepared binding，旧Unit事件已移除。[Activity:650](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L650)消费时再次验证active revision和实际媒体。
3. [PlaybackIntent.updatePlaylist:49](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlaybackIntent.kt#L49)确由[Activity.applyPlaylistContent:621](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L621)调用；恢复也调用同一个生产管线。加载前、加载后、解析后、apply前检查revision/selecting/stillValid；解析前后重读selection。最终guard到同步commit无suspend点，主线程用户新选择不能插入这个临界区。
4. [playGroupAuto:851](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L851)在探测前beginSelection，未prepare的新选择C同样让旧B结果失效；显式线路、回看和Home释放也作废旧意图。不是消费错误后给旧A补贴B身份。
5. 新表匹配优先相同stableID+URL，再stableID，防止已知A复用B URL误认成B。SR-01原先A→B后刷新/错误回启动A的普通路径得到修复。

**设备边界**：EventTime为空或无法匹配时当前实现拒收，不会猜测错误属于当前频道。源码不能证明所有prepare阶段HTTP失败都具有非空timeline；现有真实Media3 parser-error fixture只编译未运行。下游必须运行实际错误绑定测试，并增加/明确执行早期503、连接失败和立即切台后旧错误情形，检查当前失败可到达恢复、旧失败不误归属。不能将本次源码接受写成“所有网络错误恢复已实测通过”。

## 3. SR-02 主路径：源码接受

- [Drawer:849](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt#L849)把实际EPG频道B作为owner随CatchupPlayRequest传出。[PlaybackIntent.catchup:36](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlaybackIntent.kt#L36)检查liveUrl并建立B Channel+archive URL请求，不再以catchupURL查直播表后回退A。
- [历史节目回调:1025](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L1025)实际调用该绑定函数→startPlayback→prepareRequest→StreamPlayback；取消旧探测，不修改原直播索引以伪装回看归属。
- [初始化:448](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L448)从保留请求/owner重新绑定B；[回看重试:651](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L651)延800ms后验证会话，直接prepare失败会话的同一request。B即使不在更新表中仍可在**回看模式内**保留归属。
- [MediaPlaybackRequest.httpHeaders](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlaybackIntent.kt#L4)由[实际Media3工厂:46](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/StreamPlayback.kt#L46)消费；首次/retry/Home均保存B ID/UA/Referer及地址，没有全局可变头污染。
- B仍在表时，原Back返回直播通过B稳定身份定位组并沿原探测播放路径，未退化成强制改浏览/直播索引；B不在表时存在下节SR-03。

## 4. SR-03 高：缺席列表的B返回直播后，实际owner与索引分叉，恢复/刷新再次选择A

**触发链**（TIV-14/15/17/24）：

1. 直播A，浏览B历史节目进入回看；直播索引仍A是原版允许的分离状态。
2. 回看期间后台更新的新列表删除B（保留A）。commitPlaylist更新列表/索引，因catchupRequest非空不改B回看流。
3. Back按原双击路径返回直播，[Activity:917–929](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L917)找不到B列表行，清catchupRequest，prepare保留的B-live请求；B的请求头立即正确，但currentGroupIndex/currentVariantIndex仍A。
4. 下一次Home→ON_START→initializePlayer→selectedLiveNow，[381–384](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L381)优先返回列表索引A，尚未读取保留B-live请求即返回，因此播放跳A。后台updatePlaylist同样以A作为selection，可能同步/重新prepare A；直播元数据也按A显示。

实现者通过团队消息确认遗漏：没有隐藏的生产同步逻辑；本轮未改动源码。

**最小修复要求**：明确保留“当前实际直播owner B未入列表”的绑定，直到用户显式选择其他台，或新列表能按B稳定身份重新定位。selectedLiveNow/初始化/后台selection/元数据必须使用同一实际owner；不能只把retained request改优先级，却仍让背景匹配不到B后fallback A。可安全保留旧请求、拒绝替换播放或以明确的未入表状态保存B，具体内部表示由实现决定，不新增导航层，也不能为了修复而在进入回看时强改原直播索引。

**单独反例验收**：生产入口中A-live→B-history→受控更新表仅A/C→Back返回B-live→Home恢复→再次背景更新缺B，实际媒体仍B、UA/Referer/id及信息不标成A；新表恢复同stableID的B可重新定位；用户显式选C应结束保留B状态。测试必须覆盖Activity实际使用的owner/selection解析入口与commit策略，不能只在helper里手动构造B-live请求。

## 5. 测试真实覆盖与缺口

实际读取本地已有JUnit XML：

- [10项修复回归结果](../../iqtvlite-integration/android/app/build/test-results/testDebugUnitTest/TEST-com.cjlhll.iptv.PlaybackIntentRegressionTest.xml)：tests=10，failures/errors=0。
- [5项来源完整性结果](../../iqtvlite-integration/android/app/build/test-results/testDebugUnitTest/TEST-com.cjlhll.iptv.SourceIntegrityTest.xml)：tests=5，failures/errors=0。

这是t8执行留下的结果，本次未重跑，也不将其当独立设备证据。

[回归源码](../../iqtvlite-integration/android/app/src/test/java/com/cjlhll/iptv/PlaybackIntentRegressionTest.kt)真实调用Activity所用的updatePlaylist/recover/catchup/httpHeaders生产函数。CompletableDeferred控制parse/load挂起后切C，断言旧任务不parse/不commit；队列测试验证A不可变失败不下载新表；reorder/重复URL测试检查B身份。这些不是仅断言计数器或helper存在。

但JVM测试不启动Activity；SR-03正位于Activity的实际owner与索引整合处。现有Back测试手动`MediaPlaybackRequest(intent.request.channel)`，并未调用后续selectedLiveNow/后台apply，因此15项通过不能排除此问题。

[7项Android仪器测试源码](../../iqtvlite-integration/android/app/src/androidTest/java/com/cjlhll/iptv/NativeBoundaryTest.kt)包含真实Media3 B-first/retry/Home/Back头及真实parser-error来源测试；t8仅编译未运行。localhost JVM实际HTTP验证共享头合同，不能代替Media3/Activity/焦点运行。

## 6. 原24 TIV ID本轮范围

| ID | 本轮结论/范围 |
|---|---|
| 01 | 启动路由未重开；Home恢复入口审查，缺表B受SR-03阻断。 |
| 02 | 配置编辑导航未变，沿用t5源码结论，设备未验。 |
| 03 | 扫码链未变，手机真实提交待设备。 |
| 04 | 全屏DPAD映射未变；新意图在显式切台时作废旧恢复。 |
| 05 | 初焦/浏览EPG导航未变。 |
| 06 | 分组行为未变。 |
| 07 | 原版频道OK保留抽屉未变；普通背景保B修复接受。 |
| 08 | 节目/日期列导航未变。 |
| 09 | B历史回看owner确实从Drawer进入生产prepare，主路径接受。 |
| 10 | Back一次关抽屉未变。 |
| 11 | 设置导航未变。 |
| 12 | 线路模态未变；显式线路切换作废旧意图。 |
| 13 | 原信息条结构未重开；缺表B回直播元数据须随SR-03一致。 |
| 14 | 原Back优先/双Back不变；缺表B回直播后的持久owner有SR-03。 |
| 15 | 回看seek未改；B回看首次/retry/Home请求绑定接受，回直播后Home有SR-03。 |
| 16 | loading结构不变；实际prepare阶段错误到达性留设备门禁。 |
| 17 | SR-01普通旧恢复拒收接受；缺表直播owner同步剩SR-03。 |
| 18 | 全局编号逻辑不重开；重复URL按稳定身份匹配有回归。 |
| 19 | 单层侧栏仍后续可选，本轮未引入。 |
| 20 | 旧选择/返回优化仍后续可选，未替代原版。 |
| 21 | 扩展设置仍后续可选，未扩scope。 |
| 22 | 新增可见UI仍后续可选，未扩scope。 |
| 23 | 数字增量映射未重开，发起选择经过同一新意图路径。 |
| 24 | SR-01/02主绑定改善接受；SR-03未解决，真实Media3/JNI/性能未验证。 |

## 7. 下游决定

先对SR-03执行最小正式修复与单独反例复验，再决定CI放行；不因该边界重开已接受的SR-01/02主路径或全量导航设计。t5原失败记录保持不改。

后续CI必须重新构建r27双ARM库与对应两APK，并绑定自己的SHA/签名/版本/ABI；当前本地r30候选与体积+7.36%不代表CI或运行优化。新CI候选的7项仪器测试、prepare阶段网络错误归属、TV遥控/Home/首帧/声音/性能仍需acceptance验证。

本次只读命令为git rev-parse、限定文件git diff --numstat、Get-FileHash；其余为源码与已有XML结果读取。无Gradle/Cargo/ADB执行。本报告终态后停止读源码，后续修复须形成新证据快照。
