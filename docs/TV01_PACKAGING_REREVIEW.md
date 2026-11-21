# PKG-01继承成员门禁独立复验

任务t17，designer，2026-11-19（Asia/Hong_Kong）。**结论：pass，PKG-01源码与静态合同修复接受。** 此结论关闭[t16静态发现](TV01_PACKAGING_REVIEW.md)，不改写旧10071真机失败，不代表API28的7项仪器测试、TIV/SR或性能已经通过。

复验源码提交`82e56d2c96f409edd197d838605ae34178489d55`。读取时目标HEAD为文档后续提交`27a8eac48aa125ee6beacd0dbd8178b38ee1ed45`，工作树干净。本次未运行Gradle/Cargo/ADB，未watch CI或操作TV；仅只读源码/既有报告、Git/哈希以及真实Python函数内存回归。与acceptance设备任务并行，运行结论归其实际证据。

## 1. 原漏检的生产修复

[门禁:76–108](../../iqtvlite-integration/scripts/dex_contract.py#L76)新增main_ancestors/main_member_targets/consumes_main：

- 测试持有owner不再直接排除。main_ancestors沿test与main合并的super/interfaces遍历，识别测试子类实际消费的主包祖先；测试子类即使在`Lfixture/`等非SHARED前缀中也会检查。
- [unresolved:110](../../iqtvlite-integration/scripts/dex_contract.py#L110)用consumes_main选择需检查引用，find先查真实类本地方法/字段，未找到则递归父类与接口。构造器不继承。存在的test override可以正确满足引用，但不能顺便掩盖另一个未定义的继承字段。
- [生成器:27–35](../../iqtvlite-integration/scripts/generate_instrumentation_keep.py#L27)不再只处理外部owner roots；它实际调用main_member_targets，把test-owned子类上的符号调用投影到主包祖先成员合同，再输出具体方法/字段签名。测试自己实现的方法不会被误当主包声明。
- 新合同172个根、172唯一。它会对相关主类祖先传播具体成员签名以保留实际声明，部分祖先即使不声明该成员也可收到无效果的精确规则；没有借此保持整包或test包全部成员。Kotlin multifile家族规则保留原有有限签名匹配。
- platform_type明确划分Android/Java boot API，查找时SDK定义优先于APK兼容stub。此平台边界没有再将跨到主包的测试子类整体跳过；但compile SDK stub仍不是API28运行证明。

**原最小反例现已正确处理**：test Child继承main Parent，Child.required()/requiredField在Parent中缺失时报告缺方法/字段；恢复Parent实际声明后通过。generator为Parent产生精确keep而不保测试Child整类。PKG-01的排除逻辑缺陷关闭。

## 2. 本次独立执行的回归

实际命令：

```text
PYTHONDONTWRITEBYTECODE=1
python -m unittest discover -s scripts -p test_dex_contract.py -v
```

[7项脚本测试](../../iqtvlite-integration/scripts/test_dex_contract.py)全部通过，耗时0.001秒。覆盖缺失/恢复主成员、测试本地override与继承字段分离、主声明keep生成、非SHARED前缀主声明、platform stub边界、test接口桥到main。这是本任务重新执行的真实函数测试，非照抄CI结果。

另外本任务独立以内存SimpleNamespace构建两组继承关系（class parent与interface），分别核对：

1. 缺失方法和字段同时拒绝；
2. 恢复方法及字段后同时通过；
3. generate实际输出`-keepclassmembers class kotlin.ReviewParent`与`void required();`、`int requiredField;`；
4. 不输出`-keep class fixture.ReviewChild`。

两组均通过，补足仓库接口测试只直接断言missing method的覆盖范围。未执行目标APK代码、未用Gradle重构产物，也未写目标源码或字节码缓存。

## 3. 旧pair负控与新pair报告

本任务读取既有报告并独立计算缺口数组长度，未重新扫描APK或伪称亲自运行设备：

| 报告 | 实际绑定/缺口 |
|---|---|
| [旧10071修正版负控](../../iqtvlite-integration/local/tv01-old-contract-revised.json) | app `ab151bb0a005fb840ef38ec8151ce502b87049105f53b087537bfd7104aac065` / test `bd6e6bff2f891b87a2e870cdece069e7d6507bb2c73aa12116173e7cc4f9bfec`；23 types /59 methods /5 fields缺失，含实际runner失败的LazyKt。修正版未把旧失败放过。 |
| [CI10073云报告](../../iqtvlite-integration/local/tv01-ci73/reports/reports/instrumentation-contract.json) | 三缺口数组全空。main9987/test1100定义，main Kotlin335/test0，1908方法/642字段层级引用。 |
| [下载后复查报告](../../iqtvlite-integration/local/tv01-ci73-downloaded-contract.json) | 与云报告两包SHA及统计一致，缺口0/0/0。 |

新pair app SHA `956c9366541c1bbf9bba9a9834e8f7bc2d8f892072c0f8ba7c7dc95064da6509`，test SHA `b3e9a827c1eb627da6ebc0dd67fde2bc57e0a4bd048508b1d76e5f1da422857a`，均与[CI10073交付收据](../../iqtvlite-integration/docs/CI_10073_DELIVERY.md)一致。来源run44426383315/source82e56d2c；本任务没有重复watch或重新下载CI。

[workflow新增步骤](../../iqtvlite-integration/.github/workflows/apk.yml#L89)先执行7个Python回归，再对精确pair执行实际DEX合同门禁，失败不能上传交付为通过。报告仍明确排除反射/动态加载及API28平台兼容证明；类名与签名存在检查也不代替所有访问性/调用语义验证。

## 4. scope与业务不变性

限定commit diff确认：330cf13→82e56d2没有Gradle条件、业务tivimini目录或AndroidTest源码变更；只对脚本、keep合同、回归、workflow及说明修复。[Gradle](../../iqtvlite-integration/android/app/build.gradle.kts#L35)的keep仍仅isolated validation debug，普通debug/release规则不扩张；R8/resources/storage/全部7个设备方法仍保留。

重新读取的四项TIV/SR指纹与[t11](TIVIMINI_SR03_REVIEW.md)及[t16](TV01_PACKAGING_REVIEW.md)相同：PlayerActivity `C0CF372A…E70A6FD5`；PlaybackIntent `07C0C21F…04CBA915`；StreamPlayback `DC81F0A0…A080AE05`；UnlistedLiveOwnerTest `3931A606…C2CE44F4`。不重开已通过的业务源码门禁，不把新增keep描述为业务导航优化。

本次审查脚本完整SHA256：

- [dex_contract](../../iqtvlite-integration/scripts/dex_contract.py)：`F7614690357EE4526377D83CC7DD8BAEF8CD6C5AEEAC241A2B657D319CD7E36E`
- [generate_instrumentation_keep](../../iqtvlite-integration/scripts/generate_instrumentation_keep.py)：`9DA0171CD4AD9C30C8466E8ADB3EED3CC417D6A2E9B1C998E3B38F7E3B223F5A`
- [test_dex_contract](../../iqtvlite-integration/scripts/test_dex_contract.py)：`CD9C02AADE761BB45CAFF5DFE789BF2117DF836501E0CCFC60341B9CACB47A66`

## 5. 下游边界

PKG-01在源码/静态层面解除阻断，可继续以10073同run同签pair进入/继续acceptance。旧t16 needs_revision保留历史，不冒充它原本通过。

API28实际test storage初始化、7项完整runner结果、JNI/HTTP/gzip/LAN/Media3、早期503/连接错误、真实TIV/SR Activity/Home/Back/刷新路径以及首帧/音频/CPU/PSS仍需acceptance独立验证。本任务没有新增这些结论。新签名与已装10071不同，替换及回退只依已有授权和acceptance的validation沙箱备份方案，不能把静态pass当作生产包覆盖/删除授权或正式Release批准。

本次终态后停止读取目标源码，后续改动须绑定新提交及证据。
