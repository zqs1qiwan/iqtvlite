# TR100-G9 基线与验收方法

采样：2026-11-19 00:41–00:44（Asia/Hong_Kong）；角色 acceptance；任务 t3。此文是测量与验收规范，不是新 APK 通过报告。先只读采集；随后Captain明确允许前台化现装包，于00:45:10执行am start -W，将已有任务带到前台。未安装、未卸载、未清数据、未强停、未改源、未注入遥控器按键。截图及UI dump在/sdcard写临时取证文件。后续安装必须由 Captain 取得用户明确确认再安排。现有数据保留；首次配置用隔离测试数据/独立测试包或用户明确同意后执行，不能把旧验收文档“清除应用数据”当作授权。

## 已采集事实

| 项目 | 本次事实 | 证据 |
|---|---|---|
| 连接 | 192.168.2.245:5555 device，transport_id 2 | [adb](evidence/2026-11-19-baseline/adb-devices.txt) |
| 设备 | TR100-G9，Android9/API28，armeabi-v7a,armeabi，1920×1080，240dpi；逻辑约1280×720dp | [设备](evidence/2026-11-19-baseline/device.txt) |
| 系统内存 | MemTotal 2093056 KiB；瞬时值随负载变化 | [系统内存](evidence/2026-11-19-baseline/system-memory.txt) |
| Rust现装包 | tv.iqtvlite.app，versionCode1，versionName0.1.0-test，min26/target35；首次及最近安装2026-11-18 09:45:57（设备报告时间） | [包信息](evidence/2026-11-19-baseline/package-iqtvlite.txt) |
| 原Compose现装包 | com.cjlhll.iptv，versionCode1，versionName1.0，min24/target36；最近更新2026-11-18 10:30:29 | [包信息](evidence/2026-11-19-baseline/package-tivimini.txt) |
| APK体积与身份 | Rust base.apk 13140066 B（12.53MiB），SHA256 26da3876b5fdc6886e40f888c6e6ad2ff3cd8e85a506db5277c45fd4972b475e；未证明与本轮CI产物一致 | [身份](evidence/2026-11-19-baseline/apk-identity.txt) |
| 当前焦点 | 当贝 com.dangbei.tvlauncher/.IndexActivity；mInTouchMode=false，mHasPermanentDpad=true；只证明窗口焦点，不能证明应用内可见控件焦点 | [窗口](evidence/2026-11-19-baseline/windows.txt) |
| Rust后台状态 | PID10878；3个STOPPED MainActivity实例；PSS100463KiB（98.11MiB），NativeHeap PSS24076KiB；不是播放中内存 | [Activity](evidence/2026-11-19-baseline/activities.txt)、[内存](evidence/2026-11-19-baseline/meminfo-background.txt) |
| CPU | dumpsys历史窗口00:35:04–00:40:04，Rust0.3%；同窗口其他直播包46%、11%，media.codec17%；不能作为独占前台播放CPU | [CPU](evidence/2026-11-19-baseline/cpuinfo.txt) |
| 启动 | logcat有00:40:43.897 START Rust记录；无匹配可用Displayed/首帧对，既有进程存在；本次未测冷启动 | [启动历史](evidence/2026-11-19-baseline/startup-history.txt) |

设备ABI不等于APK内实际native库ABI；安装前另审CI APK的lib/armeabi-v7a及依赖，不能仅以构建支持v7a推断。

## 前台补充观察（00:45）

Captain允许后执行am start -W：输出“Activity not started, its current task has been brought to the front”，ThisTime/TotalTime270ms、WaitTime294ms；这是任务恢复，不是冷启。[前台启动](evidence/2026-11-19-baseline/foreground-start.txt)、[操作前栈](evidence/2026-11-19-baseline/activities-before-foreground.txt)、[操作后栈](evidence/2026-11-19-baseline/activities-after-foreground.txt)。同PID10878，3 Activities；PSS104768KiB（102.31MiB），仍无可比稳态播放样本。[前台内存](evidence/2026-11-19-baseline/meminfo-foreground.txt)。

UI层级显示“▶ CCTV-14 少儿”，channel_list focused=true、bounds[0,73][830,1080]；此为现装Rust包布局，不能按它推断上游原版尺寸。[UI标签](evidence/2026-11-19-baseline/foreground-labels.json)、[原始层级](evidence/2026-11-19-baseline/foreground-ui.xml)、[截图](evidence/2026-11-19-baseline/foreground.png)。本角色与Captain图像读取均被模型能力拒绝，未目视确认运动画面/声音。日志同PID有OMX.goke.video.decoder.avc surface连接，证明使用厂商AVC组件的路径证据；没有本次prepare→rendered首帧时间对，不证明真实首帧通过。[日志](evidence/2026-11-19-baseline/recent-logcat.txt)。

PesReader Unexpected start code prefix在00:45:16.031–00:45:36.634共12条，期间未注入换台键，不能断言与换台关联或据此判断播放差。[告警汇总](evidence/2026-11-19-baseline/warning-summary.json)。尝试dumpsys media.codec返回Can't find service，未重复查询；候选应提供onRenderedFirstFrame、decoder名、buffer/drop计数日志来支持验收。

按TIV原版633dp抽屉，density1.5对应949.5px；目标可用窗口全屏1920px约1280dp，最终取应用bounds核对。不得按旧W/3验收。所有遥控路径仍未执行，前台化未改用户源。

## 源实时核验

本机网络请求与TV播放可达性分开记录。https://iptv.laobaitv.net 在默认PowerShell UA下200但返回HTML；ExoPlayerLib/1.5.1与VLC/3.0.20 LibVLC/3.0.20下均200、application/x-mpegURL、30218B，开头字节对应#EXTM3U，内含x-tvg-url=https://epg.laobaitv.net。不能把默认UA网页当M3U解析成功，也不能据此断言源失效。证据：[默认](evidence/2026-11-19-baseline/http-playlist.json)、[Exo UA](evidence/2026-11-19-baseline/http-playlist-exo.json)、[VLC UA](evidence/2026-11-19-baseline/http-playlist-vlc.json)。

https://epg.laobaitv.net 本次200，最终https://laobaiepg.laobaitv.net/guide.xml.gz，application/gzip，386021B，魔数31,139；尚未据此证明XMLTV解析、频道匹配或TV端下载通过。证据：[EPG](evidence/2026-11-19-baseline/http-epg.json)。每轮记录时间、UA、重定向、HTTP、实际流地址/频道稳定ID、编码/分辨率，不沿用历史112频道503为今天事实。

## 性能测量与门槛

先做同机同源旧包完整播放基线B，再在获准安装新CI包后做候选C。当前只读后台快照不能替代B；缺B的项标记“未验证”，不得报告提升百分比。记录APK SHA、CI run/commit、variant、签名、源内容hash、线路、解码器名、视频格式、网络、时间。两版本交错测试，选择至少3个实时验证可播的频道（覆盖设备可用的720p/1080p及H264/H265能力；没有对应源则明确缺覆盖）。固定频道/线路/播放时长，不允许C用低码率而B用高码率。测前收集其他活动进程，控制后台负载；无法控制则报告干扰，重测，不擅自强停用户其他应用。

| 指标 | 测量定义与采样 | 候选验收目标（待B后填数） |
|---|---|---|
| 冷启动UI | 保留数据；后续操作阶段force-stop后确认PID不存在，am start -W -n tv.iqtvlite.app/.MainActivity；记录TotalTime/WaitTime及Displayed。至少10次，p50/p95（n=10时p95取最大值），冷进程不等于清磁盘缓存 | C p50≤1.10×B，p95≤1.20×B；首次导入另列，不能混入缓存启动 |
| 首帧TTFF | 用elapsedRealtime日志记录prepare请求t0→Media3 onRenderedFirstFrame t1；每频道10次，首个可见运动画面与音频另用录屏/人工确认；READY不当首帧 | 同频道p50≤1.10×B、p95≤1.20×B；正常源单次超出产品超时预算必须有可操作错误，不得永久loading |
| 切台 | 远程键事件时间→该频道首帧；3频道按固定序列≥30次；记录旧请求是否取消，禁止把旧频道帧当新首帧 | p50≤1.10×B、p95≤1.20×B；0错台/旧错误覆盖；快速连按最终台正确 |
| 持续播放 | 每种确认支持格式至少30min，主样本60min；记录首帧后rebuffer次数/总时长、droppedFrames、decoder事件、音画观察、crash/ANR。准备、广告/源断开独立归因 | 0 crash/ANR；硬解路径证据存在；正常源buffer占比/掉帧率不高于B+0.5百分点；源故障须可恢复且不计播放器成功 |
| CPU | /proc/PID/stat前后差值 ÷ CLK_TCK ÷ wall time ×100，1核心=100%，多核可>100%；稳态第2–12分钟每秒采样，p50/p95；media.codec另列，不混入app归因；top输出需记录其归一化口径 | 同格式app及codec分列，C稳态p50≤1.10×B、p95≤1.20×B；未证明硬解不能用低app CPU声称硬解成功 |
| PSS | 稳态2min、5min、10min及30/60min dumpsys meminfo包；同样面板关闭/打开各测，Java/Native/Graphics/PSS/SwapPss分列；采样本身较重，不每秒dumpsys | 匹配场景C PSS≤1.10×B；10min→60min增量≤max(10MiB,10%×10min值)，异常单调增长需复验；当前后台98.11MiB不适用此门槛 |
| APK | CI下载本地文件字节数和SHA256；比较同variant/ABI/签名/是否universal，另记录安装base/split总和 | 对比当前base参考13,140,066B：同口径≤1.10×B；若改变ABI/签名/依赖必须列增量原因，不能虚称体积降低 |

这些是防回退容差，不是性能提升结论。降低资源目标只有C实测低于B、差值大于样本波动且重复成立才能宣称；否则写“未观察到可确认提升”。记录原始每次值及样本数，失败样本保留，不能只报成功最快值。

## 遥控逐路径验收框架（TIV-01–24）

以下以[TIV规格](TIVIMINI_INTERACTION_SPEC.md)为唯一交互目标，旧[验收矩阵](UI_UX_ACCEPTANCE.md)与[改进设计](UI_UX_DESIGN.md)仅作背景。原版双列/Back关闭整个抽屉优先；旧单层/逐级Back不作为本轮门槛。TIV-01–16必须一致，17–18内部修复，23保留数字增量，24性能边界；19–22可选后续，未做不能误判本轮阻断。

每条记录：交互ID、APK SHA、前置频道/面板/焦点、注入的keycode及down/up/长按、期望状态/播放/焦点、实际结果、截图前后与logcat、通过/失败/未验证。当前下面所有路径均未执行，不能由源码审查标成真机通过。

| ID | 可执行路径与通过判定（均待最终APK真机执行） |
|---|---|
| TIV-01 | 现有源+历史启动进入播放器；Home再回/退出重启恢复历史。无源配置在隔离数据条件验证，禁止默认清数据 |
| TIV-02 | 配置左右结构、QR/LAN/两字段/按钮；字段OK KeyUp编辑，离焦结束，加载时禁用；首次焦点记录事实，不预设为按钮 |
| TIV-03 | 手机真实非空live/epg推送更新并自动加载；空epg不清原值；离开配置LAN关闭。涉及改现存源须先准备可恢复配置与安排测试窗口 |
| TIV-04 | 无浮层Up/Down首尾循环；Left双列抽屉；Right/Menu设置；OK信息；面板方向键不透传切台 |
| TIV-05 | 初开当前频道，后续保留有效浏览焦点；播放A浏览B等350ms后EPG为B，视频仍A。关闭再开记录焦点 |
| TIV-06 | 频道Left组，上下选组OK仅筛选且留组焦点；Right回频道并隐藏组；不因筛选换台 |
| TIV-07 | 频道上下循环；OK切台后抽屉保持且行焦点保持；多线按源顺序首个可达；失败分支另记 |
| TIV-08 | Left开抽屉→Right节目并显示日期→Right日期；日期OK改日期且留日期焦点；Left返回节目再Left频道 |
| TIV-09 | 历史有catchup才启动并关抽屉；无能力说明失败；当前/未来OK均提示只能回看历史，不转直播。源无回看能力标未验证 |
| TIV-10 | 组/节目/日期每列分别Back，一次关闭整个抽屉回视频；重开后焦点记录，不逐层Back |
| TIV-11 | Right/Menu设置每次初焦源配置；上下遍历源配置/更新EPG/条件源选择/检查更新；更新EPG立即关闭后台请求；Left/Back关闭 |
| TIV-12 | 多线路进入居中源选择，当前线路初焦；OK切线后模态保留；Back关闭，不透传底层换台 |
| TIV-13 | OK信息条5秒隐藏；换台重置；台标/全局号/频道/存在时节目进度/视频参数，原版无接下来行；缺数据不伪造 |
| TIV-14 | 源选择→抽屉→设置→信息条→回看双Back回直播/直播双Back退出；2秒窗口；关信息不算退出第一次；IME先消费Back |
| TIV-15 | seekable回看左右首10秒，重复1–14=30秒，15–39=60秒，40起300秒，0..时长夹限；Up/Down/OK仅信息；Menu设置 |
| TIV-16 | 换台小加载窗在READY/ENDED/error/12秒关闭，可同时导航；坏流12秒提示消失不能判首帧成功，也不要求旧设计失败面板 |
| TIV-17 | 方向快速20次/开关面板20轮，视频、焦点、EPG区分；旧结果不覆盖；空EPG保留有效焦点且Back可关，新增播放标记单列可见修复 |
| TIV-18 | 全部与非首组同一频道核对抽屉/信息条全局号与数字目标；不改变合并顺序 |
| TIV-19–22 | 可选后续，不将单层/逐级Back、新节目策略、扩展设置、新UI控件混入原版一致性通过门槛 |
| TIV-23 | 输入1,1,2完整112；OK/超时提交、Back取消、0/越界/前导0/长按，目标全局号一致；此为保留增量，非原版已有能力 |
| TIV-24 | 按上文同机同源性能表测首帧/切台/持续/CPU/PSS/冷启/APK；缺B/C不声称提升 |

## 交付门禁

CI构建成功、旧APK在机可启动、HTML/M3U下载、Media3 READY、源码存在硬解配置，均不能替代新APK安装后的真机首帧与遥控结果。验收报告必须标识新CI APK SHA与设备安装后身份一致；安装前核验包名/签名/ABI与数据升级兼容。无明确安装授权时保持待部署，不暗中覆盖现装包。
