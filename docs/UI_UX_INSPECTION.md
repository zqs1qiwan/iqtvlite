# Android TV IPTV 接手勘察与重构范围

日期：2026-11-18。执行者：designer。任务：t1。此文档是源码勘察与范围建议，不是实现验收结论。

## 1. 基线与证据边界

- 当前工作目录为 Windows 上的 iqtv 项目。Git 分支 main，已有大量未提交差异，涉及 MainActivity、PlayerActivity、Channel、M3uParser、EpgRepository、构建依赖等；另有 .idea 删除、docs、artifacts、reconstruction 等新增内容。后续不得覆盖这些已有改动。
- [现有 PRD](PRD.md)明确：直接使用 tivimini 为底座，吸收 TiviMate 层级交互，保留手机扫码推源，不回到旧版三栏首页。
- [交接记录](../HANDOFF.md)记载此前 Linux 构建与目标 TR100-G9 真机验证；这不是当前 Windows 工作区重新构建或当前包装机验证的证据。本任务没有修改 Kotlin、没有执行构建或真机操作。
- 已发现配置、信息条、数字跳台历史截图，位于 [证据目录说明](README.md)所列位置。当前模型图像工具明确不支持图像输入，尝试读取失败，因此本报告的视觉判断限于源码的尺寸、颜色、状态定义；不声称已目视验证截图。

## 2. 工程、入口与已有能力

主工程仅包含 `:app`，见 [settings.gradle.kts](../settings.gradle.kts#L22)。Gradle wrapper 8.13、AGP 8.13.2、Kotlin 2.0.21；Compose for TV + Media3 1.9.2 + OkHttp。SDK 24–36；包名 com.cjlhll.iptv，Java/Kotlin 字节码目标 11，配置 ABI 为 armeabi-v7a / x86。实际构建 JDK 与 Android SDK 需在当前环境确认。

构建入口：Windows `./gradlew.bat :app:assembleDebug`；单测 `./gradlew.bat :app:testDebugUnitTest`；静态检查 `./gradlew.bat :app:lintDebug`。现有 lint 配置 abortOnError=false，因此任务退出成功不能单独证明无 lint 问题。现有测试覆盖 ChannelGrouper、M3U UA；instrumentation 为示例测试，未见遥控器 UI 回归覆盖。

独立 [reconstruction 工程](../reconstruction/settings.gradle#L15)使用 com.cleanroom.iptv、Java/Leanback/Media3 1.5.1，与根工程未集成。建议本轮不迁移、不双线重做播放器；其内容只作为已有参考资料，由 Captain 决定是否另行使用。

| 层级 | 当前实现与职责 |
|---|---|
| 应用入口 | [Manifest](../app/src/main/AndroidManifest.xml#L21) → SplashActivity → 配置或播放器；横屏、TV launcher |
| 启动恢复 | [SplashActivity](../app/src/main/java/com/cjlhll/iptv/SplashActivity.kt#L14)：有源及历史 URL 直接恢复播放，否则打开配置 |
| 源配置 | [MainActivity](../app/src/main/java/com/cjlhll/iptv/MainActivity.kt#L108)：直播/EPG 字段、二维码、LAN 服务、保存并播放、启动检查更新；允许任意源 |
| 播放编排 | [PlayerActivity](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L300)：Media3、缓存、后台刷新、换台、分组合并、多线路、延迟探测、错误恢复、直播/回看状态 |
| 频道/EPG | [PlayerDrawer](../app/src/main/java/com/cjlhll/iptv/PlayerDrawer.kt#L118)：频道、动态分组、节目列表、日期、历史节目回看 |
| 设置 | [SettingsDrawer](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L40)：源配置、更新 EPG、有多线路时源选择、检查更新 |
| 播放信息 | [ChannelInfoBanner](../app/src/main/java/com/cjlhll/iptv/ChannelInfoBanner.kt#L25)：台标、编号、频道名、当前节目、节目进度、视频参数、回看进度 |
| 数据和兼容 | M3uParser、ChannelGrouper、XMLTV/EpgRepository/EpgCache、Prefs、PlaylistCache、CatchupHelper、NetUtil、LogoLoader；保留频道 UA、IPv4 优先及 gzip 处理 |

## 3. 当前完整操作路径

### 首次使用

Splash → 源配置。页面外边距 48dp，左侧二维码/局域网地址，右侧直播地址、EPG 地址、56dp 高“保存并播放”按钮。首次 Prefs 为空时预填测试源；手机推送后自动保存并请求播放列表。列表加载成功后解析、合并线路、匹配历史频道并进入播放器；网络/空列表反馈主要使用 Toast，加载时禁用输入。

### 日常直播

全屏：上/下循环换台；左键打开频道/节目单抽屉；右键或 Menu 打开设置；OK/Enter 显示底部信息条，5 秒后消失；数字键连续输入，1.2 秒后按合并频道全局序号跳台。源码见 [按键分发](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L166)。

抽屉初始频道列 + 节目列；频道列左键临时展开分组；右键节目列表，节目聚焦时展开日期；日期左键回节目、节目左键回频道。频道列表首尾循环。焦点移动只浏览，OK 选择频道；节目 OK 仅历史节目可回看。设置初始焦点固定“源配置”，左键/返回关闭。

### 回看与返回

历史节目 OK → 校验 catchup 属性 → 生成 URL → 关闭抽屉并播放。回看左右快退/快进，按住加速（10/30/60/300 秒）；上下或 OK 呼出信息。返回优先关闭源选择、频道抽屉、设置、信息条；回看空白状态双返回回直播；直播空白状态双返回退出。当前抽屉从任何列按返回都会关闭，非频道列仅先恢复频道焦点并延迟 80ms 后关闭，不是逐级后退。

### 异常恢复

直播错误发起紧急列表刷新，失败时尝试其他线路；回看错误最多自动重试 3 次。换台 loading 在 READY/ENDED/error 或 12 秒后隐藏。源码未见重试耗尽后的常驻错误与“重试/换台/源配置”操作。配置失败以 Toast 为主；EPG 失败多数只记录日志，空节目与获取失败难以区分。

## 4. 明确 UX 问题与风险

| 优先级 | 发现及源码证据 | 对重构的要求 |
|---|---|---|
| P0 | [抽屉宽度](../app/src/main/java/com/cjlhll/iptv/PlayerDrawer.kt#L590)：常态 300+1+300+32=633dp，日期或分组出现继续扩宽。PRD 要求侧栏约不超过屏宽 1/3 | 按层级替换内容，频道、节目与日期避免同时堆叠为宽面板；视频始终可见。宽度按实际屏幕逻辑宽度计算，不能将 1920px 直接当 1920dp |
| P0 | [过滤后的频道](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L813)被 [列表本地 index+1](../app/src/main/java/com/cjlhll/iptv/PlayerDrawer.kt#L742)重新编号；[数字跳台](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L1027)却用全局合并组序号 | 固定一个全局展示编号，分组、信息条、数字跳台共享；线路合并不另占频道号 |
| P0 | [分组 selected](../app/src/main/java/com/cjlhll/iptv/PlayerDrawer.kt#L931)与[频道 selected](../app/src/main/java/com/cjlhll/iptv/PlayerDrawer.kt#L972)参数未参与视觉绘制，仅 focused 改背景 | 播放/选中与焦点必须同时可辨；当前播放用持久标记，焦点用边框/高对比背景 |
| P0 | [错误恢复](../app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt#L710)和 [loading](../app/src/main/java/com/cjlhll/iptv/ChannelSwitchLoading.kt#L62)没有恢复耗尽的可操作终态 | 加载、重试、失败、离线缓存各有状态；失败后遥控器可以重试、换台或重新配置，不能只消失 loading |
| P1 | [信息条参数](../app/src/main/java/com/cjlhll/iptv/ChannelInfoBanner.kt#L25)只有当前节目，未接入下一节目；缺 EPG 时不提供明确说明 | 信息条呈现“正在播放/接下来”；无 EPG 用稳定占位说明，不能伪造节目 |
| P1 | [Logo](../app/src/main/java/com/cjlhll/iptv/LogoLoader.kt#L356)对成功加载图片施加 10dp 圆角裁剪 | 成功 logo 保留原始透明边缘与完整比例；错误占位单独定义 |
| P1 | [设置菜单](../app/src/main/java/com/cjlhll/iptv/SettingsDrawer.kt#L102)没有按键映射、上一台、刷新策略、节目点击策略或恢复开关 | 当前能力与 PRD 需求有差距；须逐项纳入实现或明确阶段，不能用静态占位设置充数 |
| P1 | 配置保存发生在网络验证前；网络/解析/空列表错误依赖 Toast；自动更新对话框可能在首次配置时插入 | 保存结果、加载结果与可恢复动作要明确，失败保留输入；首次配置主路径焦点稳定 |
| P1 | 按键处理分布在 Activity dispatch、Compose preview、组件 clickable，且焦点 request 有多帧/延时重试 | 重构需集中定义浮层互斥、返回优先级与焦点恢复，防止一次按键触发多层或焦点落到隐藏组件 |
| P2 | 默认 typography、bodySmall、低透明文字分散定义；缺全局字号/间距/颜色 token | 统一 TV 字号和状态对比；需在 720p/1080p 逻辑密度及目标观看距离实测 |

## 5. 建议阶段与范围

### A：保住观看闭环并固化验收基线

沿用主 Compose 工程和播放/解析能力。确定全局频道号；记录首次配置、缓存启动、扫码推送、可用频道首帧、无效频道号、失败流和 EPG 缺失的路径。先确认构建环境与目标设备可达。保护已有 UA、IPv4、gzip、缓存和多线路改动。数字输入、返回和浮层建立单一状态表。

### B：核心电视交互重构（本轮推荐最小完整范围）

1. 建立 TV 设计 token、统一焦点/选中态，修正透明台标裁剪。
2. 重构左侧层级：频道 → 节目 → 日期，分组作为前置层；单层宽度不超过屏宽约 1/3；独立右侧设置。
3. 换台信息补全当前/下一节目、编号、logo、缺 EPG 状态；数字缓冲改为稳定屏幕反馈。
4. 建立加载/空/错误/缓存状态及遥控器可操作恢复；返回逐级关闭并恢复进入前焦点。
5. 实现 PRD 所需基础设置：按键映射（冲突检查/恢复默认）、上一台、启动恢复、列表/EPG 刷新策略、节目点击行为，并持久化与生效。

A+B 应形成“首次配置 → 播放 → 浏览/换台 → 设置 → 异常恢复”可独立交付的完整切片。若时间不足，Captain 应缩小设置项的本轮数量并在验收范围明确延期，不能默认宣称 PRD 全部完成。

### C：品牌与扩展体验

启动图标、TV banner、品牌资产统一；长列表性能、动效节奏与目标机观看距离细化。进一步扩展收藏/搜索/更多自定义仅在明确新增范围后实施。无需增加首页、账户、推荐内容、付费功能，也不迁移至 reconstruction。

## 6. 后续设计与验收的交接条件

- 设计需交付具体页面结构、每层进入/退出/返回路径、焦点恢复点、字号与间距 token、颜色对比、所有组件状态；依据 tivimini 已有操作再做层级收敛。
- 验收应分开记录源码/自动检查、当前构建、模拟器遥控器、目标真机和历史证据；每条结果绑定当前 APK/commit 或差异基线。
- 屏宽约 1/3 用实际布局宽度测量；透明 logo 用完整边缘测试图验证；分组编号用“全部/非首组/数字直跳/信息条”交叉验证；焦点与当前播放用不同频道同时显示验证。
- 异常至少包含：坏源地址、空列表、EPG 不可用、流 503、断网缓存启动、重试耗尽；当前已知 112 流 503 不可当首帧基准，可用频道也需重新确认实时可用性。
- 历史扫码只确认二维码显示，历史 SurfaceView 黑截图不证明没有首帧；真实扫码提交、实际解码首帧与稳定播放仍要端到端验证。
