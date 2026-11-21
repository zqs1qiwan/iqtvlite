# TV-01验证变体打包独立复查

任务t16，designer，2026-11-19（Asia/Hong_Kong）。对象：`iqtvlite-integration`提交`330cf135b13dc8d4d2e2453be81b1fa22f0a7a4b`，读取时HEAD等于该提交、工作树干净。**结论：保留范围与业务不变性接受；静态成员门禁存在一项可复现漏检，整体needs_revision。** 此结论不等于判定新APK真机失败；并行CI可继续收集构建证据，API28同pair的7项runner执行仍是独立门禁。

依据：[TV01修复记录](../../iqtvlite-integration/docs/TV01_RUNTIME_REPAIR.md)、[旧10071真实失败](CI_10071_DEVICE_VERIFICATION.md)。本任务未跑Gradle/Cargo、未操作TV、未重复watch CI、未修改目标文件。仅执行只读Git/哈希以及导入源码函数的纯内存合成契约检查；未对APK执行或重新逆向。

## 1. 已接受的修复范围

- [Gradle:35–50](../../iqtvlite-integration/android/app/build.gradle.kts#L35)：新增keep位于debug内，且只在`ISOLATED_TEST_PACKAGE == true`时应用。debug/release的isMinifyEnabled和isShrinkResources均保持true；普通非validation debug与release不增加该文件。没有广泛禁用R8。
- 提交范围仅9文件：workflow、gitignore、Gradle、keep、修复说明及4个Python脚本；未删除/更改7项Android测试、测试依赖、runner配置或test storage。没有用跳过TestDirCalculator注册绕开旧崩溃。
- [keep合同](../../iqtvlite-integration/android/app/instrumentation-shared-keep.pro)实际计数154个根且全部唯一。根类名具体，不存在整包`kotlin.**`/`androidx.**` blanket keep。应用模型/API有21个具体class的全成员keep；其他根按列出的signature保成员，Kotlin的15类`*Kt**`家族匹配限定在命名facade家族及具体成员签名，并非整包全成员保留。
- LazyKt根及`LazyKt**.lazy(Function0):Lazy`声明匹配用于修复旧缺类/继承声明删除。ResolvableFuture/AbstractResolvableFuture等祖先规则覆盖主包持有父类的成员声明。生成器需显式运行、合同提交review，CI不静默扩大规则。
- [workflow:89](../../iqtvlite-integration/.github/workflows/apk.yml#L89)在上传APK之前执行pair gate，报告保存到Android reports；非零会中断该shell步骤。新门禁读精确main/test路径并输出两包SHA，优于字符串搜索。

## 2. 旧真实失败与新静态能力边界

旧10071主/test SHA为`ab151bb0a005fb840ef38ec8151ce502b87049105f53b087537bfd7104aac065` / `bd6e6bff2f891b87a2e870cdece069e7d6507bb2c73aa12116173e7cc4f9bfec`。真实设备在AndroidJUnitRunner.onCreate→registerTestStorage→TestDirCalculator构造阶段找不到kotlin.LazyKt；7测试均未执行。adb退出0不能消除该失败。

修复报告将根因定位为main R8删除而非混淆：测试依赖被AGP判为主应用共享runtime，测试APK无相应stdlib定义；因此再次添加编译依赖不足。源码和本次keep方向与该证据一致。本任务没有重跑旧APK DEX负控；报告中的旧pair“23类型/59方法/5字段缺失”和本地新pair零缺失属于实现者既有静态证据，不冒充独立运行结果。

[DEX读取器](../../iqtvlite-integration/scripts/dex_contract.py)解析type/method/field ID表及class_data的实际定义，区分引用与定义；[classfile读取器](../../iqtvlite-integration/scripts/classfile_contract.py)解析SDK stub常量池与父类/接口及实际成员签名；[pair gate](../../iqtvlite-integration/scripts/verify_instrumentation_pair.py)合并两包定义并沿父类/接口递归，构造器不从父类继承，array.clone例外明确。这些生产机制确实存在。

但查到签名仅证明当前模型的名字/签名可解析，不证明访问性、调用种类、反射、动态加载或API28平台兼容。默认platform jar为android-36，这一点报告已声明，不可把它等同API28支持。真实prepare阶段503/连接错误、runner初始化、Media3/JNI及全部7项仍需新同run同签pair在API28运行。

## 3. 阻断静态契约发现 PKG-01（medium）

**问题**：[dex_contract:88–89](../../iqtvlite-integration/scripts/dex_contract.py#L88)在处理方法/字段时，凡引用owner在`test.classes`中就直接跳过，不调用已有find继承解析。测试持有子类不代表该方法/字段也由测试定义：合法DEX可将继承成员引用的owner写成测试子类，而成员实际声明在主包共享父类。父成员若被R8删除，这种可执行引用会被当成测试实现API排除，pair gate错误pass。

[生成器:25–32](../../iqtvlite-integration/scripts/generate_instrumentation_keep.py#L25)同样只从非test-owned roots收集成员；它没有从测试子类引用向主包祖先声明推导keep。门禁与生成器两边同时遗漏会令未来API变更无法被门禁发现。此问题不是metadata annotation或反射边界。

**本次实际执行的纯内存反例**（使用真实unresolved函数，无模拟被测算法）：

```text
main definitions: Lkotlin/SharedParent;，未定义 required()V / requiredField:I
test definitions: Landroidx/test/Child; extends SharedParent
test method ref: Child.required()V
test field ref:  Child.requiredField:I
unresolved => missing_types=[] missing_methods=[] missing_fields=[]
```

对照：同样的主类和缺失成员，把ref owner改为SharedParent，真实函数立即报告missing_methods及missing_fields。证明漏检来自test-owned排除条件，而不是解析器不支持签名。此反例没有证明当前新APK一定包含该缺失调用，因此不据此宣称新runner已失败；它证明所承诺的继承成员门禁尚不完整。

**最小修复**：不能按owner是否由test定义整体排除。应先在该owner的真实定义中查签名；本地未定义的成员沿test/main父类和接口链继续解析，并对抵达shared主包/缺失shared祖先的成员施加门禁；保留非共享平台/API边界的明确处理。生成器应将这类调用追溯至实际共享声明类，输出精确keep，而非保整个test包。至少补四个独立反例：test子类自有方法通过、继承存在的main方法/字段通过、删除该main成员后拒绝、接口继承成员存在/缺失分别处理。metadata-only排除不得覆盖上述可执行调用。

此缺陷已同步Captain和acceptance。修复后的静态合同需独立复验；新CI及真实设备证据可继续并行采集，不以本报告推断其结果。

## 4. TIV/SR源码指纹未变

实际重新读取哈希，与[t11记录](TIVIMINI_SR03_REVIEW.md)逐项相同：

| 文件 | SHA256 |
|---|---|
| [PlayerActivity](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt) | `C0CF372A80625DC8F421CC8C888AB038FCCD6305B241D02B9B7C44D1E70A6FD5` |
| [PlaybackIntent](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlaybackIntent.kt) | `07C0C21FA08E17F30D2A5A170C79A7C912AAA4EA393620D2E69D4E1D04CBA915` |
| [StreamPlayback](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/StreamPlayback.kt) | `DC81F0A05FEB4E7956B2110DDA1F5F42F1D91451FA94C62D300F2E17A080AE05` |
| [UnlistedLiveOwnerTest](../../iqtvlite-integration/android/app/src/test/java/com/cjlhll/iptv/UnlistedLiveOwnerTest.kt) | `3931A606EA6218FA3CB3C631888659C3F0B85CAFAE9ECA7FA8E3A058C2CE44F4` |

因此本次不重开24 TIV导航/SR业务评审。keep扩大验证变体可见运行API，不改变业务源码；它可能改变优化后的产物与资源使用，所以仍需新的API28运行和性能数据，不沿用旧候选测量。

## 5. 本任务验证与后续

实际只读验证：git rev-parse/status/show、限定提交diff、Get-FileHash；Python设置PYTHONDONTWRITEBYTECODE后导入unresolved执行内存方法/字段正反对照，精确keep roots计数154/154唯一。未执行pair APK扫描、Gradle/Cargo或ADB，未watch新CI。

当前结论为保留scope接受、PKG-01静态门禁需修订。旧TV-01真实失败仍保留；只有新pair实际runner执行全部7项、之后完成TIV/SR/播放/性能门禁，才能解除运行侧阻断。此报告不是交付或发布批准。终态后停止读取目标源码；任何后续修复另行绑定提交与证据。
