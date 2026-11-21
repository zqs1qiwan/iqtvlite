# SR-03 独立复验与CI放行

任务t11，designer，2026-11-19（Asia/Hong_Kong）。**结论：源码复验通过，SR-03关闭，放行后续CI。** 该结论仅解除源码门禁，不代表CI已经通过、允许发布或设备验收通过。

依据：[t9独立复查](TIVIMINI_SOURCE_REREVIEW.md)、[原交互规格](TIVIMINI_INTERACTION_SPEC.md)及[t10修复记录](../../iqtvlite-integration/docs/SR03_REPAIR.md)。本任务只读审查目标工程，未改Kotlin、未跑Gradle/Cargo、未操作TV；未重开此前接受的SR-01/SR-02主路径与原版导航。

## 1. 审查身份

目标独立工作区`iqtvlite-integration`，分支`integration/tivimini-baseline`，HEAD仍`90ae4cdd0ba7f0acbeb25955868dc5d4b1d0bac9`，修复尚未提交。本次实际读取并记录SHA256：

| 文件 | SHA256 |
|---|---|
| [PlayerActivity](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt) | `C0CF372A80625DC8F421CC8C888AB038FCCD6305B241D02B9B7C44D1E70A6FD5` |
| [PlaybackIntent](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlaybackIntent.kt) | `07C0C21FA08E17F30D2A5A170C79A7C912AAA4EA393620D2E69D4E1D04CBA915` |
| [StreamPlayback](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/StreamPlayback.kt) | `DC81F0A05FEB4E7956B2110DDA1F5F42F1D91451FA94C62D300F2E17A080AE05`，与t9一致 |
| [UnlistedLiveOwnerTest](../../iqtvlite-integration/android/app/src/test/java/com/cjlhll/iptv/UnlistedLiveOwnerTest.kt) | `3931A606EA6218FA3CB3C631888659C3F0B85CAFAE9ECA7FA8E3A058C2CE44F4` |
| [PlaybackIntentRegressionTest](../../iqtvlite-integration/android/app/src/test/java/com/cjlhll/iptv/PlaybackIntentRegressionTest.kt) | `329E5C8A188F20520FC1D10CF2AEB533DA7F7D3E7271DD809C52F17385ED8D4E`，与t9一致 |

本地候选身份引用t10：APK `e6f8b67d1cdeda464a9e9c44c145818f30962b63268bf71f41ca9e4ed47d5b19`（14,123,927B）；测试APK `37a33e31cda95237060c88eb62f33ecbfd063fcd8df7783a482463adf2eaf1dc`。本任务未安装或独立运行它们，后续CI必须绑定新产物身份，不能沿用此哈希冒充CI。

## 2. SR-03完整反例：生产调用链接受

| 路径/要求 | 真实生产接线与复验结论 |
|---|---|
| A-live→B-history仍保持原版浏览/直播分离 | 进入历史仍沿owner B的immutable catchup请求准备；未通过强改直播索引掩盖身份。本次不修改Drawer/Back导航层级。 |
| 回看期间新表删B | 历史播放时未建立unlistedLive，沿既有列表更新；不会中断B回看，直播索引仍可为A。 |
| Back返回缺表B | [Activity:906](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L906)实际调用[returnToLive:54](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlaybackIntent.kt#L54)。按B request/owner稳定身份找行；缺B时保存unlistedLive=B-live；Activity取消旧探测、清回看、prepare该B-live请求。不伪改A浏览索引。 |
| 下一Home仍B | [selectedLiveNow:377](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L377)改为调用[liveOwner:43](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlaybackIntent.kt#L43)，该函数优先显式保留B，不再先读旧A。suspendPlayer仅作废active，不清unlistedLive；[初始化:437](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L437)取得B并传同一prepareRequest工厂。 |
| 后台再更新仍缺B | [updatePlaylist:123](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlaybackIntent.kt#L123)检查保留B未出现在parsed表，直接产生keepUnlistedOwner应用，绕过title/index fallback。[commit策略:66](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlaybackIntent.kt#L66)返回null索引和无普通后台prepare；[Activity:587](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L587)实际按策略更新列表、条件写索引、条件prepare。新A/C表可被接收，但不能替代B媒体。 |
| 错误恢复不能借旧A备用线路 | 恢复同样走guarded updatePlaylist；保留B且新表缺B时可重试保留B。无可用列表后的[Activity:658](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L658)明确跳过旧索引线路fallback，不把A冒充B。 |
| B回表（同ID/新URL/新位置） | positionOf优先稳定ID；找到B后正常目标匹配。commit清保留并定位B，player存在时准备B新请求；停止时只更新索引及作废旧revision，不对released player操作，下一初始化按B定位。 |
| 用户显式选C | beginSelection清unlistedLive，原playGroupAuto/显式线路路径继续沿既有新意图和探测流程。C成为新选择，不永久锁定B。 |
| 元数据必须跟B | [Activity:374](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L374) currentChannel/playingGroup来自同一LiveOwnerView，未入表group=-1、number=0；[信息条:1128](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L1128)使用B channel/title/logo，不借A组名/编号；保留未入表时不取可能复用URL的A节目/进度。[抽屉:1003](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L1003)不把A传为selected播放项。 |

所有pipeline仍在load/parse/apply前检查意图，最终guard到同步commit无suspend。SR-01的旧异步拒收和SR-02的B首次/retry/Home请求头工厂没有因本次修复回退。未入表号码0为已支持的未知编号语义，不新增虚假全局频道号。

## 3. 测试证据：使用真实生产解析/提交入口，非Activity运行证明

[新增4项JVM源码](../../iqtvlite-integration/android/app/src/test/java/com/cjlhll/iptv/UnlistedLiveOwnerTest.kt)的Screen fixture刻意保持旧索引A。Back调用生产returnToLive；view/Home调用生产liveOwner；refresh调用生产updatePlaylist及commitPlaylist，并按Activity相同policy应用索引与prepare sink。没有再用手工B请求替代缺表Back转移。

覆盖四组反例：

1. A→B回看→删B→Back→Home→再缺B更新→Home，断言实际request/媒体id/UA/Referer/metadata B、unknown number0，更新不得prepare A。
2. B同ID新URL异位回表并重新prepare；另一次缺表状态显式选C后保持C。
3. 停止状态缺B更新和B回表，验证不prepare已释放播放器，恢复时B。
4. A复用B URL，仍保留B且不误定位A。

实际读取t10留下的三个JUnit XML：[4项SR03](../../iqtvlite-integration/android/app/build/test-results/testDebugUnitTest/TEST-com.cjlhll.iptv.UnlistedLiveOwnerTest.xml)、[10项SR01/02](../../iqtvlite-integration/android/app/build/test-results/testDebugUnitTest/TEST-com.cjlhll.iptv.PlaybackIntentRegressionTest.xml)、[5项来源完整性](../../iqtvlite-integration/android/app/build/test-results/testDebugUnitTest/TEST-com.cjlhll.iptv.SourceIntegrityTest.xml)，合计19项，全部failures/errors=0。本任务只核对已有结果与生产接线，未独立重跑测试。

[Android fixture:134](../../iqtvlite-integration/android/app/src/androidTest/java/com/cjlhll/iptv/NativeBoundaryTest.kt#L134)也已改为调用生产缺表Back/liveOwner/updatePlaylist/commitPlaylist，并检查实际Media3 B-live请求、Home后B id与保存请求头。它和其他6项仅编译未执行。JVM fixture不启动Activity，不能证明真实焦点、IME、Home系统事件、解码或视频画面；本次接受基于逐调用点核对与已有生产函数回归，设备仍要执行。

## 4. TIV范围与下游门禁

本轮直接覆盖TIV-01（Home入口）、07/17（实际频道与后台）、13（信息条元数据）、14/15（Back回直播及Home）、24（请求归属）。TIV-04/12/23的显式换台/线路/数字仍进入原新选择路径，保留状态可退出。TIV-02/03/05/06/08/09/10/11/16/18未新增导航变化，继承此前源码结论；19–22仍为可选后续，不借本修复扩大范围。原版双列633dp、焦点/返回/模态没有被替换为旧单层方案。

**放行后续CI**：允许将该源码候选交付新r27 CI执行编译、JVM/lint及双APK/native/包体检查。此处放行不等于main合并、tag、Release或设备验收通过。旧t5/t9失败结论维持历史原样，当前SR-03在t11关闭。

最终CI自己的APK及匹配test APK需核对SHA/签名/版本/validation包身份/双ARM ABI，执行7项仪器测试及早期503/连接失败/旧错误EventTime归属；再由acceptance在同APK上完成SR-03真实Home/后台序列、原TIV遥控/扫码、JNI/gzip、首帧/声音/性能。当前本地r30 native结果不替代r27 CI；14,123,927B仅为+7.49%体积容差，不代表运行资源改善。无zone XMLTV UTC差异及签名限制仍按已有记录保留。

本次只读命令为git rev-parse与Get-FileHash，其余为源码及已有XML读取。**本报告完成后停止读取目标源码**；后续实现变化需新diff证据，不自动纳入此次pass。
