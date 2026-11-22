# IQTV Lite 审查与修复交接

审查基线为 `integration/tivimini-baseline` 的 `e5111af`。Android 实际构建入口使用 `src/tivimini/java` 和 `src/bridge/kotlin`。本文覆盖代码设计、性能和 Android TV 交互。发现来自源码审查，设备数据来自 CI10204 既有记录。每个修复分开验证，优先确保现有双列抽屉和遥控语义可用。

[Android 构建配置](../android/app/build.gradle.kts#L82) · [现有交互规格](TIVIMINI_INTERACTION_SPEC.md#L35) · [CI10204 验收](CI_FINAL_DRAWER_V2.md#L65)

## 第一批 · 稳定性和数据正确性

| 任务 | 问题与修复方向 | 验收条件 |
|---|---|---|
| S1 · 小分组抽屉 | `coerceIn(3, channels.size)` 遇到 0–2 行会抛异常。按实际行数计算首帧和目标窗口。 [PlayerDrawer.kt](../android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt#L342) | 0、1、2 行均可打开，打开后从大组切到小组仍可操作，非空组可落焦，Back 可退出。 |
| S2 · 播放恢复 | 持续报错的直播可能重复拉 M3U 和重播。旧看门狗或首帧事件也可能干扰新选台。按每次选台统一重试预算、退避、已试线路和取消，按代际与 `mediaId` 校验事件。 [错误处理](../android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L1028) · [首帧事件](../android/app/src/tivimini/java/com/cjlhll/iptv/StreamPlayback.kt#L448) | 模拟连续失败后能到达稳定终态。选 A 后立即选 B，A 的迟到事件不能取消、重建或覆盖 B。 |
| S3 · 源保存竞态 | 用户验证新源期间，旧源刷新可提交并使手动操作版本失效。拆开手动请求代际和已提交源快照版本。 [SourceRepository.kt](../android/app/src/tivimini/java/com/cjlhll/iptv/SourceRepository.kt#L42) | 用可控并发覆盖手动换 B、后台更新 A 的交错，B 验证成功后可提交。失败、取消、A→B→A 时旧结果不能覆盖新配置。 |
| S4 · EPG 假成功 | 网络失败后回退旧缓存被调度器记为成功。保留网络成功、缓存回退和失败三种结果。 [结果类型](../android/app/src/tivimini/java/com/cjlhll/iptv/EpgOutcome.kt#L3) · [调度器](../android/app/src/tivimini/java/com/cjlhll/iptv/AutoUpdateScheduler.kt#L131) | 网络失败且有缓存时仍能看旧节目。成功时间不推进，失败退避生效，手动反馈说明在使用旧数据。 |
| S5 · 回看身份与进度 | 异步节目单可把旧节目时段和新频道地址混用，重建播放器时回看进度丢失。节目快照与频道绑定，只有同一回看请求恢复保存位置。 [节目快照](../android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt#L638) · [播放生命周期](../android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L751) | 快速浏览 A→B 后，OK 不能用 A 的节目时间打开 B。可 seek 的回看切 Home 再返回，继续原请求的播放位置。 |
| S6 · 扫码推源 | 配置页接收局域网提交后可以自动保存并播放，响应早于实际提交。增加短时配对、输入校验和真实结果反馈。 [SourceConfigLanServer.kt](../android/app/src/tivimini/java/com/cjlhll/iptv/SourceConfigLanServer.kt#L73) | 有效会话能扫码配置。无效会话不能变更配置，下载失败或保存失败有清楚反馈。 |

## 第二批 · 遥控和播放反馈

| 任务 | 问题与修复方向 | 验收条件 |
|---|---|---|
| U1 · 最近观看焦点 | 左右键更新索引，但真实焦点只在条内无焦点时请求。让高亮、真实焦点和 OK 目标一致。 [ChannelInfoBanner.kt](../android/app/src/tivimini/java/com/cjlhll/iptv/ChannelInfoBanner.kt#L105) | 有至少两张最近观看卡片时，`OK → OK → 右 → OK` 切到第二张卡对应的台。 |
| U2 · 线路测速抢焦点 | 线路测速更新会重新请求当前线路焦点。把焦点初始化与后台测速状态解耦。 [QualitySelectorDialog.kt](../android/app/src/tivimini/java/com/cjlhll/iptv/QualitySelectorDialog.kt#L66) | 光标停在备用线路时测速结果返回，焦点留在原行，OK 切到该线路。区分未测速、测速中和失败。 |
| U3 · 回看提示 | 回看仍显示直播的左右键提示，长按 Back 的重复事件也可触发双按分支。按状态显示实际可用按键。 [提示](../android/app/src/tivimini/java/com/cjlhll/iptv/ChannelInfoBanner.kt#L339) · [按键分派](../android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L353) | 回看展示可用 seek 操作和明确的返回直播入口。长按一次 Back 不等价于独立双按。 |
| U4 · 换台终态 | READY、错误和超时都可能清掉同一加载提示。与 S2 共用播放状态，显示目标台名、首帧与失败入口。 [ChannelSwitchLoading.kt](../android/app/src/tivimini/java/com/cjlhll/iptv/ChannelSwitchLoading.kt#L62) | 真实首帧结束加载。坏流重试耗尽后可重试或选其他可用线路，继续浏览时按键可用。 |
| U5 · 抽屉与节目单 | 当前播放、浏览焦点、可回看状态难以区分。用独立标记区分状态。节目单空态区分未配置、加载失败和无节目。信息卡直接查询实际播放台。 [频道行](../android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt#L1240) · [节目单](../android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt#L1048) · [信息卡](../android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L1969) | A 台播放、焦点在 B 台时，两种状态都可辨。切换浏览分组不影响 A 台的信息卡节目。 |

## 第三批 · 性能与信息层级

- **换台。** 用选定或最近成功的线路直接试播，将串行探测移出常规路径。备用探测使用总预算，取消传到实际网络调用。测按键到真实首帧的 p50、p95 和失败率。 [线路选择](../android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L1339) · [网络探测](../android/app/src/tivimini/java/com/cjlhll/iptv/SourceLatencyProber.kt#L44)
- **列表与冷启。** 同一内容版本复用解析后的快照，内容未变时跳过写盘和发布，M3U 下载设置体积上限。按阶段记录下载、解析、缓存、真实首帧和第一次再缓冲。背景刷新与首帧的重叠影响用同机同源 A/B 验证。 [列表处理](../android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L975) · [自动刷新](../android/app/src/tivimini/java/com/cjlhll/iptv/AutoUpdateScheduler.kt#L30)
- **抽屉。** 随频道列表更新构建分组索引与稳定展示行，缩小 EPG 状态更新范围。抽屉打开和连续滚动分开测 p95 与焦点跟随。CI10204 两轮均无 ≥300 ms 巨帧，`gfxinfo` p95 均为 150 ms。 [派生数据](../android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L1165) · [帧时记录](CI_FINAL_DRAWER_V2.md#L65)
- **信息卡、设置和首次配置。** 自动换台显示简洁信息条，主动打开查看完整信息。高级诊断收纳实验，三档以上配置提供可见选项，扫码提供同网指引与可重试的错误状态。 [信息卡](../android/app/src/tivimini/java/com/cjlhll/iptv/ChannelInfoBanner.kt#L188) · [设置](../android/app/src/tivimini/java/com/cjlhll/iptv/SettingsIA.kt#L45) · [首次配置](../android/app/src/tivimini/java/com/cjlhll/iptv/MainActivity.kt#L278)
- **后续边界。** 区分真实的「全部」分组与内置入口。修复起播档位提示与实际生效点。组播预热保持默认关闭，先让 socket 超时与取消能真正结束任务，再测收益。 [分组](../android/app/src/tivimini/java/com/cjlhll/iptv/ChannelGrouper.kt#L173) · [起播设置](../android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L1797) · [组播预热](../android/app/src/tivimini/java/com/cjlhll/iptv/MulticastPrewarm.kt#L55)

## 交给 agent 的方式

优先从 S1 开始。每个实现 agent 只领取明确任务，复核当前代码位置，补能触发问题的行为用例，并报告修改文件、运行命令、结果和环境限制。并行任务涉及同一文件时使用独立工作树，或改为顺序交接。播放状态和 UI 终态建议由处理 S2 的实现先确定契约，再对接 U4。

首个任务可直接复制给 agent：

> 在 `iqtvlite` 主仓阅读 `docs/REVIEW_HANDOFF.md`，只修复 S1 小分组抽屉崩溃。先核对当前分支和真实 Compose 入口。覆盖 0、1、2 行，以及从大组切到小组后仍可落焦与返回。执行可用的相关检查，报告修改、命令和结果。保留其他频道抽屉交互。未经指示不推送、不发包、不操作真机。

审查时运行的 8 项静态门禁、7 项 DEX 脚本单测和 CI10204 主 APK 的双 ABI/JNI 检查通过。Android/Rust 构建测试与设备验证未重跑，主测 APK 配对检查因本机缺少 Android 36 平台 JAR 未完成。修复后根据环境运行相应的 Gradle 单测、静态门禁和设备验收。源码定位行号对应审查基线。
