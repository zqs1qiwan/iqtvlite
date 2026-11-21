# CI 10071 真机验收失败报告

2026-11-19 07:22–07:25（Asia/Hong_Kong），t7/acceptance。结论：**失败，阻断交付**。安装身份通过；instrumentation runner在任何测试执行前崩溃，7项均未执行。不得把CI编译或adb退出0写为设备测试通过。未宣称性能提升。

## 身份与授权

Captain已转达用户明确允许各种测试直至交付，授权安装这对CI包并在validation沙箱配置、强停、重启及受控故障。重新确认TV在线且validation尚不存在，再安装；原tv.iqtvlite.app、com.cjlhll.iptv未卸载/clear/改源。安装均成功：[应用安装](evidence/2026-11-19-ci-10071/install-app.txt)、[测试安装](evidence/2026-11-19-ci-10071/install-test.txt)。

[APK run44374123717](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44374123717)、[成对下载artifact14459474944](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44374123717/artifacts/14459474944)，source7f6e2d76bc8e2007d1b8684d5392777b6525a68e。主包validation10071/min24/target35/v7a，证书734217160fc11c980403f0df19dd368204a51959472afaa6d7bc71f67c80583e。设备安装后的base.apk SHA分别仍为ab151bb0a005fb840ef38ec8151ce502b87049105f53b087537bfd7104aac065及bd6e6bff2f891b87a2e870cdece069e7d6507bb2c73aa12116173e7cc4f9bfec，与独立本地检查/CI收据一致：[已安装hash](evidence/2026-11-19-ci-10071/installed-sha256.txt)、[包状态](evidence/2026-11-19-ci-10071/installed-package.txt)、[runner](evidence/2026-11-19-ci-10071/installed-instrumentation.txt)。完整预检与方案见[测试计划](CI_10071_DEVICE_TEST_PLAN.md)。

## 阻断发现 TV-01

复现：API28 armeabi-v7a TR100-G9安装上述同签pair，运行：

```text
adb -s 192.168.2.245:5555 shell am instrument -w -r -e class com.cjlhll.iptv.NativeBoundaryTest tv.iqtvlite.app.validation.test/androidx.test.runner.AndroidJUnitRunner
```

实际：AndroidJUnitRunner.onCreate → registerTestStorage → androidx.test.platform.io.TestDirCalculator 构造时抛 `java.lang.NoClassDefFoundError: Failed resolution of: Lkotlin/LazyKt;`；cause `ClassNotFoundException`在test与主APK DexPathList均找不到kotlin.LazyKt。输出“Process crashed before executing the test(s)”、STATUS_CODE -2、shortMsg Process crashed。末尾INSTRUMENTATION_CODE 0且adb退出0不改变失败结论。[完整输出](evidence/2026-11-19-ci-10071/instrumentation-all.txt)、[logcat](evidence/2026-11-19-ci-10071/runner-crash-logcat.txt)。

修复要求：开发修复R8/测试运行时依赖与类名保留的打包契约，确保实际runner及测试引用的Kotlin运行时在同一CI APK pair运行时可解析。需检查minified主包与未压缩instrumentation之间运行时名字及打包，不能只补编译依赖或跳过test storage掩盖后续缺类。修复后新CI pair重新绑定SHA并在此机运行全部7项；是否确由R8删除/重命名应由源码/DEX审查确认，本报告只证实实际缺类，不武断归因。

## 7项仪器结果

| 方法 | 结果 |
|---|---|
| productionM3uAdapterRetainsNativeFieldsAndIdentity | 未执行：runner崩溃 |
| encodedAndBareGzipAreDecodedExactlyOnce | 未执行：runner崩溃 |
| cancelledWaiterDoesNotPoisonSharedRefresh | 未执行：runner崩溃 |
| lanServersUseDifferentPortsAndStopCleanly | 未执行：runner崩溃 |
| media3RequestUsesChannelUaAndReferer | 未执行：runner崩溃 |
| media3CatchupOwnerStaysBForFirstRetryAndHome | 未执行：runner崩溃 |
| media3ErrorCarriesProducingSessionRatherThanLaterCurrentChannel | 未执行：runner崩溃 |

## TIV与SR、性能状态

阻断发现后保留原CI产物与已安装pair，未修改源码、未换成本地候选，等待Captain协调修复。以下未进行遥控或播放操作，不能从先前源码/旧APK/t3基线代入通过。

| ID | 本轮状态 |
|---|---|
| TIV-01启动 | 未验证 |
| TIV-02配置 | 未验证 |
| TIV-03扫码 | 未验证 |
| TIV-04直播按键 | 未验证 |
| TIV-05抽屉初焦 | 未验证 |
| TIV-06分组 | 未验证 |
| TIV-07频道选择 | 未验证 |
| TIV-08节目日期 | 未验证 |
| TIV-09回看点击 | 未验证 |
| TIV-10抽屉Back | 未验证 |
| TIV-11设置 | 未验证 |
| TIV-12源选择 | 未验证 |
| TIV-13信息条 | 未验证 |
| TIV-14Back优先级 | 未验证 |
| TIV-15回看seek | 未验证 |
| TIV-16加载 | 未验证 |
| TIV-17焦点/会话修复 | 未验证 |
| TIV-18编号修复 | 未验证 |
| TIV-19旧单层设计 | 可选后续，未验证，不阻断 |
| TIV-20旧选择行为 | 可选后续，未验证，不阻断 |
| TIV-21扩展设置 | 可选后续，未验证，不阻断 |
| TIV-22新增UI | 可选后续，未验证，不阻断 |
| TIV-23数字增量 | 未验证 |
| TIV-24性能 | 首帧/60秒/CPU/PSS/冷启/切台未验证；APK14125799B比旧+7.50%在预算内 |

SR01换台B→刷新/失败不跳A、prepare阶段503/连接拒绝错误归属、旧A事件不污染B/C：未验证。SR02 B历史首播/重试/Home的UA/Referer：未验证。SR03缺B Back/Home/刷新保持B、回表重定位/显式选C：未验证。现有fixture只编译证据在本次未转为运行通过；真实Activity路径仍须后续测。

当前模型不支持图像输入的限制仍在；本次未生成新的遥控截图或宣称目视/声音通过。同机同源B-C数据尚未形成，无性能提升结论。完成判定须修复TV-01、运行7项并继续TIV/SR/播放性能全量验收。
