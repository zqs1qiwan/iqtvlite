# CI 10071 独立真机测试计划与部署预检

2026-11-19，任务t7，acceptance。状态：预检完成，等待Captain转达用户明确部署确认；尚未安装或运行测试，不是验收通过报告。

## 同一云端产物身份

来源commit 7f6e2d76bc8e2007d1b8684d5392777b6525a68e，[APK run71](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44374123717)，[成对artifact14459474944](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44374123717/artifacts/14459474944)。本次独立Get-FileHash、aapt、apksigner检查结果：

| 项 | 应用 | androidTest |
|---|---|---|
| 包 | tv.iqtvlite.app.validation | tv.iqtvlite.app.validation.test |
| 版本 | 10071 / 2.2.0-tivimini-7f6e2d76-validation | manifest自身无版本字段 |
| min/target | 24/35 | 24/35 |
| SHA256 | ab151bb0a005fb840ef38ec8151ce502b87049105f53b087537bfd7104aac065 | bd6e6bff2f891b87a2e870cdece069e7d6507bb2c73aa12116173e7cc4f9bfec |
| 签名SHA256 | 734217160fc11c980403f0df19dd368204a51959472afaa6d7bc71f67c80583e | 同左 |

应用包含armeabi-v7a/libiqtvlite_capi.so 826040B及arm64-v8a库1199856B；AndroidJUnitRunner targetPackage=tv.iqtvlite.app.validation。apksigner v2验证通过。首次误设不存在的Android Studio JDK路径失败已保留，改用当前环境Microsoft JDK17后验证成功，不掩盖失败。

[应用hash](evidence/2026-11-19-ci-10071/app-hash.txt)、[测试hash](evidence/2026-11-19-ci-10071/test-hash.txt)、[应用manifest](evidence/2026-11-19-ci-10071/app-badging.txt)、[测试manifest](evidence/2026-11-19-ci-10071/test-manifest.txt)、[应用签名](evidence/2026-11-19-ci-10071/app-signature-verified.txt)、[测试签名](evidence/2026-11-19-ci-10071/test-signature-verified.txt)、[ABI库](evidence/2026-11-19-ci-10071/native-libraries.json)。

本次adb devices确认192.168.2.245:5555在线。pm list packages tv.iqtvlite.app仅返回原生产tv.iqtvlite.app，validation及test尚不存在。[现装包](evidence/2026-11-19-ci-10071/preinstall-packages.txt)。与t3记录区分，不把原包当新CI包。

## 待批准的具体部署与操作

仅安装上述hash对应两包至192.168.2.245:5555；不安装本地r30候选、不覆盖原生产包、不卸载、不clear任何包。安装后查dumpsys package、pm instrumentation与primaryCpuAbi，并拉取实际base.apk重新hash绑定CI文件。若安装冲突/签名异常，停止报告，不默认卸载解决。

安装应用：adb -s 192.168.2.245:5555 install ../iqtvlite-integration/local/ci-delivery/iqtvlite-ci-10071-validation.apk

安装测试包：adb -s 192.168.2.245:5555 install ../iqtvlite-integration/local/ci-delivery/iqtvlite-ci-10071-androidTest.apk

所有后续配置仅独立validation包：预填公开测试源https://iptv.laobaitv.net与EPG https://epg.laobaitv.net，必要时通过其独立LAN端口推送受控localhost/LAN fixture。旧应用设置不改。后台负载造成不可比时记录干扰，不擅自停用户其他包。

## 执行顺序与通过边界

1. 运行整个com.cjlhll.iptv.NativeBoundaryTest类，预期7项（原5项+2项SR真实Media3 fixture）；保存原始instrument -w输出、每项结果和logcat。命令：adb -s 192.168.2.245:5555 shell am instrument -w -r -e class com.cjlhll.iptv.NativeBoundaryTest tv.iqtvlite.app.validation.test/androidx.test.runner.AndroidJUnitRunner。失败保留堆栈，不以shell退出0替代JUnit成功。涵盖生产Rust/JNI字段、encoded/bare gzip、取消、LAN双服务、媒体UA/Referer隔离与SR owner/error。注意fixture有503路径，但需核实实际执行覆盖，不能只凭编译名称通过。
2. 启动com.cjlhll.iptv.SplashActivity，无源配置、焦点/IME、真实LAN手机表单提交；记录时间、UA与HTTP结果。M3U默认UA可能返回HTML，按实际应用请求确认；配置只在validation。
3. 按[TIV规格](TIVIMINI_INTERACTION_SPEC.md)与[基线验收法](TV_BASELINE_ACCEPTANCE.md)执行TIV-01–18、23、24。逐列记录双列初焦、组/日期OK保留当前列、频道OK保留抽屉、任意抽屉Back一次关闭、当前/未来节目只提示、信息条及数字112/取消/超界/长按；19–22可选不阻断。每条分别记录通过/失败/未验证及证据，不把instrumentation通过等同遥控全通过。
4. SR运行分开：A直播→B换台→后台刷新/早期prepare503/连接拒绝→重试仍B；旧A错误排队后选择B/C不污染；A直播→浏览B历史回看→首次/重试/Home→Back返回B；更新列表缺B仍保留B，B回表重新定位，显式C才结束B owner。检查真实媒体ID、请求UA/Referer与UI上下文一致。受控fixture与真实生产UI路径分开记，不能仅helper测试通过就宣布Activity路径通过。
5. 选实际可播样本记录renderedFirstFrame及decoder，并至少60秒连续播放；计划30/60min稳态测量。受当前模型图像输入限制，截图供人复核，运动画面/声音无法确认时明确未验证；READY或surface连接不当首帧证明。缺catchup/seekable样本则回看项未验证，不伪造源能力。
6. CPU/PSS/TTFF/冷进程启动/切台按基线方法采样。旧包B只读已有源可用时与C匹配比较；若需改旧包源则不擅改，报告不能形成同源B-C。前台270ms历史仅任务恢复。APK14125799B对原13140066B为+7.50%，预算内，不能说体积下降。缺B或样本不足时不声称提升。

## 报告与停止条件

最终报告绑定本CI pair，列7测试及24TIV-ID结果、SR场景、原始数值/统计口径、可播/回看样本限制、首帧/声音证据限制。crash/ANR、JNI加载失败、按键透传/失焦阻断观看、SR回到A或B头泄漏均为失败，提供复现与修复要求。部署授权缺失时只保持本方案待批准，不执行安装或遥控。
