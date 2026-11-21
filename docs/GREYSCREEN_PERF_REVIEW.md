# 灰屏 / 老机卡顿修复 独立源码复查

任务 t31，designer2，2026-11-19（Asia/Hong_Kong）。结论：**源码层可接受（pass）；灰屏渲染修复按源码证据成立；但“老机流畅/卡顿已解”未获证明，属真机未验证范畴，不得对外宣称已流畅。**

本复查只读固定 commit 取证，未改 Kotlin/Rust/res、未跑 Gradle/CI/Cargo、未操作 TV（设备归 acceptance2）。依据：[灰屏真机诊断](GREYSCREEN_PERF_DIAGNOSIS.md)（acceptance2/t29）、[修复说明](../../iqtvlite-integration/docs/GREYSCREEN_PERF_REPAIR.md)（t30）、[24项TIV规格](TIVIMINI_INTERACTION_SPEC.md)、[SR03复验](TIVIMINI_SR03_REVIEW.md)。**本报告完成后停止读取目标源码**；后续任何实现变化不自动纳入本次 pass。

## 1. 审查身份与证据冻结

- 目标：`C:/Users/Q/Documents/Projects/iqtvlite-integration`，分支 `integration/tivimini-baseline`。
- 修复 commit `983e153c790375c4862b6c3f1bda4e06901f4945`（"fix(player): render video via TextureView …"），docs-only 后续 `ff5cdb1`（`docs/GREYSCREEN_PERF_REPAIR.md`）。当前 HEAD=`ff5cdb1`，`983e153..HEAD` 仅 1 个 docs commit，未再触及代码。
- 本次实际读取的代码内容身份（git blob，工作树与提交一致）：

| 文件（@983e153） | blob | 说明 |
|---|---|---|
| [PlayerActivity.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt) | `c2fc8a594cd74a4712b3170cc873c2ee88e8949c` | 与 7f6e2d7 差异仅第 964–971 行工厂 6 行 |
| [res/layout/player_texture_view.xml](../../iqtvlite-integration/android/app/src/tivimini/res/layout/player_texture_view.xml) | `5d2f16eaf17226d903c84043c4d2cecbc2220a73` | 新增 15 行布局 |

- 修复 diff 范围（`git show 983e153`）：**仅 2 文件 +20/−1**；`PlaybackIntent.kt`、`PlayerDrawer.kt` 自 `7f6e2d7` 起 0 变更；`PlayerActivity.kt` 自 `7f6e2d7` 起仅上述工厂 6 行。**SR-01/02/03 生产逻辑、请求头/UA、JNI、R8 keep、TIV 导航均不在本修复 diff 内。**

修复候选身份沿用 t30 记录（本任务未独立构建/安装）：主 `app-debug.apk` 14,159,123B SHA256 `5006e91d6bad682d5d8ef4dcdcd8630e59846389a9f16cd51575c927dc9f4a66`，test `502,955B` SHA256 `74e4a965cf7e66ac7c3b82dbe434ec2789198f0431be7f4bf5224cfe8adbf1be`，签名 `578bdb34…102`，run 44553646612(run76)。**该身份为本地/CI 记录，不是本次运行证明。**

## 2. 视频渲染（灰屏）——源码层接受

**根因与修复匹配**：灰屏主因按诊断是默认 `SurfaceView` 处于独立图层 `z=-2`、被不透明 Compose 窗口层遮挡。复核源码，遮挡层确实存在且位于视频之下：

- [PlayerActivity:132](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L132) `surface = 0xFF1E1E1E`（即诊断所见 #1E1E1E 灰）；[PlayerActivity:136–140](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L136) `MaterialTheme{ Surface(Modifier.fillMaxSize(), RectangleShape) { … VideoPlayerScreen … } }`——该不透明 `Surface` 是视频的**父容器背景**，透明度=不透明。
- [PlayerActivity:961–986](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L961) `Box(fillMaxSize){ if(player!=null){ Box(fillMaxSize){ AndroidView(PlayerView) ; ChannelSwitchOverlay } } … }`。修复把 `AndroidView` 工厂从 `PlayerView(it)` 改为 inflate [player_texture_view.xml](../../iqtvlite-integration/android/app/src/tivimini/res/layout/player_texture_view.xml)（`app:surface_type="texture_view"`）。

**为何 TextureView 能修好而 SurfaceView 不能（源码推理成立）**：`TextureView` 作为普通子 View 进入窗口视图层级，由 `AndroidComposeView` 作为子 View 在其父 `onDraw`（含不透明 Surface 背景）**之后**绘制，因此叠于 `0x1E1E1E` 背景之上可见；而 `SurfaceView` 是独立 SurfaceFlinger 图层，位于窗口层之下，即使有帧也被窗口的不透明 `Surface` 遮住，仅在窗口销毁（退出）瞬间露出。这与诊断 `layers-z-opaque.txt`（SurfaceView z=-2 / isOpaque=1 / activeBuffer 1×1）与像素证据一致。**故“视频始终不可见/退出闪一次”的根因被正确命中，且修复方向正确。**

**交互与层级未回退**：
- 工厂 `apply{}` 中 `useController=false`、`setShowBuffering(SHOW_BUFFERING_NEVER)`、`setKeepContentOnPlayerReset(true)`、`setShutterBackgroundColor(TRANSPARENT)`、`keepScreenOn=true`、`isFocusable=false`、`isFocusableInTouchMode=false`、`descendantFocusability=FOCUS_BLOCK_DESCENDANTS` 全部保留（[PlayerActivity:972–980](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L972)）。PlayerView 不抢焦点、焦点直落 Compose → DPAD/焦点行为与原版一致（TIV-04/05/07/10 不退化）。
- 视频之上仅 `ChannelSwitchOverlay`（`AnimatedVisibility`，容器透明，[ChannelSwitchLoading.kt:101–129](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/ChannelSwitchLoading.kt#L101)）；`PlayerDrawer` 为同级浮层，未覆盖时无实心背景。视频区域无其它不透明 `Box/Scrim` 压盖（`PlayerDrawer`/`SettingsDrawer` 的 scrim 为 `Color.Transparent`）。故叠加层不再遮挡视频，符合“透明可交互浮层”要求。

**lifecycle / attach-detach 正确**：
- [PlayerActivity:338](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L338) `player` 为 `remember` 单实例；[409–444](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L409) `initializePlayer()`（ON_START）建播放器并 prepare/play；[446–452](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L446) `releasePlayer()`（ON_STOP）`StreamPlayback.release`；[454–467](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L454) `DisposableEffect(lifecycleOwner)` 监听 START/STOP 并在 onDispose 释放。
- `AndroidView` 仅在 `player != null` 时组合，工厂与 `update` 均 `it.player = player`。播放器先 prepare、Surface 后 attach 属 Media3 正常时序（与修复前一致，无新增时序风险）。TextureView 的 surface 生命周期由 `PlayerView` 内部管理，`onSurfaceTextureDestroyed` 随视图 detach 释放；无新增泄漏路径。

## 3. 性能 / 卡顿——审计基本属实，但“流畅”未被证明

**主线程审计复核（逐点核对，基本与修复说明一致）**：
- M3U/清单解析：[PlayerActivity:583–585](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L583) `parsePlaylist = withContext(Dispatchers.Default)`。
- 播放列表读取/后台更新：[PlayerActivity:624–632](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L624) `Dispatchers.IO`。
- EPG：[PlayerActivity:677–731](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L677) 刷新循环 `delay(600_000)`/`10_000` 且 `EpgRepository` 在 IO；`nowProgramByUrl` 计算 [754–768](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L754) `withContext(Dispatchers.Default)`。
- Logo：`LogoLoader` 解码 `Dispatchers.Default`、磁盘/网络 `Dispatchers.IO`；延迟探测 `SourceLatencyProber` `Dispatchers.IO`。
- 无每帧 app 状态写入：`nowMillis` [733–738](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L733) 每 30s；回看位置 [569–581](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L569) 每 500ms 且仅回看态、`catchupRequest==null` 即归零；`videoFormat` 仅在 `onVideoSizeChanged/onTracksChanged`（[488–494](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L488)）更新，非逐帧。

**结论**：t30 所称“数据重活在子线程、无逐帧 app 状态写入”经源码核对成立。但据此**不能**推出“老机已流畅”，原因：

1. 诊断 gfxinfo 100% janky / Davey 单帧 2–3.4s 发生在**主线程合成/初始化**侧（Slow UI thread、Slow issue draw commands），其真实占比需设备复测；源码无法量化 Compose 组合/measure/draw 与首次 MediaCodec/GPU 初始化的主线程耗时。
2. 仍存在**可见即逐帧动画**的重型 Compose 组件：`ChannelSwitchOverlay` 内的 `CircularProgressIndicator`（[ChannelSwitchLoading.kt:117–121](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/ChannelSwitchLoading.kt#L117)）为不定进度无限旋转，`AnimatedVisibility` 有进入/退出过渡。切台加载期在 v7a 上会持续触发重绘/动画帧。这不属于“app 状态每帧写入”，但属“每帧动画负载”，t30 审计未覆盖。
3. 坏掉的 1×1 SurfaceView 可能迫使解码反复丢帧/重建（诊断推测），TextureView 修复或可减轻，但这是**待实测假设**，非源码可证。
4. 修复**未降低画质、未删功能**（无新增 `TrackSelector` 分辨率/解码上限、无禁用轨道/HEVC）——符合“不以降质/删功能冒充流畅”，但也意味着若瓶颈在 4K/HEVC 解码/GPU，本次不会自动解决。

**大 UI 架构调整的判断（合理，未擅改）**：若 TextureView pair 真机上 jank 仍高，则需较大改造（原生 `FrameLayout` 基座 + `PlayerView(SurfaceView)` + 透明 `ComposeView` 覆盖、精简 Compose、必要时限 ≤1080p 避 HEVC），t30 已按指示先把方案与代价上报 Captain、未擅自实施。本复查认可该处置；该改造属另一独立任务，需回归 24-TIV/性能。

## 4. 既有能力未回退（源码层）

- **SR-01/02/03**：`PlaybackIntent.kt`、`PlayerDrawer.kt` 自 `7f6e2d7` 起无变更；`PlayerActivity.kt` 仅渲染工厂 6 行受影响，`selectedLiveNow/liveOwner/returnToLive/updatePlaylist/commitPlaylist/prepareRequest` 等 SR 生产链未动。→ 与 t11 已放行状态一致，无回退。
- **JNI/R8**：新增布局资源在代码中经 `tv.iqtvlite.app.R.layout.player_texture_view` 引用（[PlayerActivity:971](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L971)），不会被资源收缩误删；`res.setSrcDirs(listOf("src/tivimini/res"))` 已将该 `res/layout` 纳入打包（`android/app/build.gradle.kts` sourceSets）。构建未改 minSdk/abi/签名规则。
- **UA/Referer、音频诊断**：修复 diff 不含 `StreamPlayback.kt`；其 `+63` 行来自 77fe7f6 的音频诊断，`enabled = BuildConfig.DEBUG && Log.isLoggable(TAG, DEBUG)`（[StreamPlayback.kt:72](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/StreamPlayback.kt#L72)），默认关闭、每回调先 `observe()` 短路，无逐帧主线程开销；错误/首帧 `matches()` 仅在一次性事件调用。本修复未改变其行为。

## 5. TIV 逐项范围（源码可接受 ≠ 真机验证）

| TIV | 本轮源码结论/范围 |
|---|---|
| 01 | 启动→Splash→Player 路由未改；渲染修复不影响入口，Home/恢复逻辑 SR 未动。 |
| 02–03 | 配置/扫码链未改；手机真实提交/test 预填仍待设备。 |
| 04/05/06 | DPAD 分发、初焦/浏览 EPG、分组行为未改；PlayerView 非焦点、焦点直落 Compose。 |
| 07 | 频道 OK→play… 不置 drawerOpen=false；后台保 B 未动；出画待真机。 |
| 08 | 节目/日期列导航未改。 |
| 09/15 | 回看 owner/id/UA/Referer 请求绑定未动（SR-02 保持）；seek 未改；真机回看待验。 |
| 10/14 | Back 优先级/双 Back 未改；缺表 B 回直播逻辑（SR-03）未动。 |
| 11/12 | 设置导航、线路模态未改。 |
| 13 | 信息条结构未改；元数据不涉及本修复。 |
| 16 | loading/READY/error 结构与 12s 隐藏未改；注意加载转圈动画（见 §3.2）。 |
| 17/18 | 稳定编号/重复 URL 身份匹配未改。 |
| 19–22 | 仍为可选后续，本修复未扩 scope。 |
| 23 | 数字跳转未改。 |
| 24 | 单 ExoPlayer+Media3 未变；渲染宿主 SurfaceView→TextureView（可见性修复）。运行首帧/声音/性能未验。 |

## 6. 变更范围与风险

- 体积：主 APK +560B（14,158,563→14,159,123B），预算内（cap 14,454,072B）；纯渲染改动。
- 风险 1（中）：TextureView 相对 SurfaceView 多一次 GPU 合成拷贝，极弱 v7a GPU 上可能反而影响视频顺滑；必须设备实测（gfxinfo 50th/95th/janky/missed vsync、dropped frames 前后对比）。
- 风险 2（低）：`LayoutInflater.inflate(..., null)` 以 null 作 root（[PlayerActivity:970–971](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L970)）依赖 `AndroidView` 重新测量，属常见写法，无阻断。
- 观感（低，可选）：首帧前 `shutter=TRANSPARENT` → 透出父 `Surface` `0x1E1E1E`，冷启到首帧前仍会短暂显示“深灰”而非纯黑。**可选改进**：把视频容器/`shutter` 设为纯黑（如 `Box(Modifier.background(Color.Black))` 包住 `AndroidView`，或 `setShutterBackgroundColor(Color.BLACK)`），使首帧前为黑而非灰，避免用户仍误认为“灰屏”。非阻断。

## 7. 放行结论与设备门禁

- **源码层：接受（pass）。** 灰屏根因命中，TextureView 修复方向与实现正确、范围最小，未回退 TIV/SR/JNI/R8/UA/诊断，未以降质/删功能充数。
- **不得宣称“老机已流畅”**：卡顿仅完成主线程/动画审计与假设性归因，真实 jank 需设备复测；若仍高，则须走已上报的大 UI 架构改造任务。
- **真机必须由 acceptance2 在该 exact pair（run76 / 主 `5006e91d…` / test `74e4a965…`）验证**：①force-stop 后冷启首帧前不得为永久灰、首帧产出即可见；②BACK/退出无“闪一帧/灰滞留”；③`dumpsys SurfaceFlinger` 确认视频不再是 z=-2 被遮 SurfaceView、`--latency` 有真实帧；④gfxinfo 50th/95th/janky/missed vsync 与 dropped frames 与修复前及原装 Views 包对比；⑤24-TIV 交互不退化；⑥logcat 关注 `AudioSink$UnexpectedDiscontinuityException`（本修复未处理，属稳健性单独项）。
- 本结论**仅解除源码门禁**，不等于 CI 已过、设备已验收、可发布。旧历史结论（t5 failed / t9 SR-03 needs_revision）保持原样。

## 8. 只读命令与终态

实际只读命令：`git rev-parse HEAD`、`git log --oneline`、`git show 983e153`、`git show --stat 983e153/ff5cdb1`、`git diff --stat 7f6e2d7 HEAD -- <SR/render文件>`、`git rev-parse 983e153:<file>`（blob）、`git hash-object`、`Get-FileHash`；其余为源码/文档读取。**未执行 Gradle/Cargo/CI/ADB，未改任何文件。** 本报告完成后停止读取目标源码；后续修复须形成新 diff 证据快照，不自动纳入本次 pass。
