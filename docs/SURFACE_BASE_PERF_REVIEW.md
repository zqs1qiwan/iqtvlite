# 零拷贝 SurfaceView 基座改造 独立源码复查（t38 / run78）

任务 t39（attempt 1），designer2，2026-11-20（Asia/Hong_Kong）。**结论：源码层可接受（pass）；架构方向正确、USAB2-01 崩溃已修并有守护；但真机零验证，灰屏是否零回归、焦点/命中、性能/CPU 必须由 acceptance2 在 exact pair 上实测，不得宣称已流畅。**

本复查只读固定 commit 取证，未改 Kotlin/Rust/res，未跑 Gradle/CI/ADB，未操作 TV（设备归 acceptance2）。依据 [t38 修复说明](../../iqtvlite-integration/docs/SURFACE_BASE_PERF_REPAIR.md)、[灰屏诊断](GREYSCREEN_PERF_DIAGNOSIS.md)、[灰屏修复复查](GREYSCREEN_PERF_REVIEW.md)、[24项TIV规格](TIVIMINI_INTERACTION_SPEC.md)。**本报告完成后停止读取目标源码。**

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`，分支 `integration/tivimini-baseline`；HEAD=`d42aaf0`（docs-only），工作树 clean。
- 被审查修复 commit `c82593f5a5edcd9e30dc9001844bdd5f0923bad9`（t38），docs-only 后续 `d42aaf0`。`c82593f5..HEAD` 未再触及 `android/app/src`。
- 读取内容身份（git blob，随提交；工作树一致）：

| 文件 @c82593f5 | blob |
|---|---|
| [PlayerActivity.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt) | `b05d9990ea3344609c2af314d5c9bd4ae4a8e831` |
| [LoadControlConfigTest.kt](../../iqtvlite-integration/android/app/src/test/java/com/cjlhll/iptv/LoadControlConfigTest.kt) | `6f6db522ebbf2477816996bb5fce8b5b39bae05c` |
| [scripts/verify_apk.py](../../iqtvlite-integration/scripts/verify_apk.py) | `eef2b50c5e9d4cfc0b6b502801ba2c501690f3ca` |

- 修复 diff 范围（`git show c82593f5`）：**3 文件 +89/−47**（PlayerActivity 改渲染宿主/初始化/释放；新增 LoadControlConfigTest；verify_apk 预算）。`PlayerDrawer.kt`、`PlaybackIntent.kt` 本 commit 未动。
- 候选身份沿用 t38 记录（本任务未构建/安装）：主 `app-debug.apk` 14,126,355B SHA256 `ed165e6e…7285`，test `5564f5a21…ac71`，签名 `bc52f452…641c`，run 44651507384(run78)，version 10078 / `2.2.0-tivimini-c82593f5-validation`。**该 pair 未真机验证。**

## 2. 渲染基座 — 架构正确（源码接受；灰屏零回归仍须真机）

[PlayerActivity.onCreate:129–147](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L129) 用原生 `FrameLayout`（黑底）承载：index0 = `PlayerView`（**默认 SurfaceView = 零拷贝**，`useController=false`、`SHOW_BUFFERING_NEVER`、`keepContentOnPlayerReset(true)`、`setShutterBackgroundColor(TRANSPARENT)`、`isFocusable=false`、`FOCUS_BLOCK_DESCENDANTS`）；index1 = **透明 `ComposeView`**（`setBackgroundColor(TRANSPARENT)`）；`setContentView(rootLayout)`。视频**不再**在 Compose 树内（原 `AndroidView(PlayerView)` 已整体删除，[995–1004](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L995) 仅剩浮层）。

**不回归灰屏的源码论证**：
- 原 t30 病灶是「SurfaceView 处在 Compose 子组合(AndroidView)内，Compose 以**不透明** `MaterialTheme Surface 0x1E1E1E` 整窗绘制遮盖」。t38 已**移除该不透明 Surface**，Compose 根为 `Box(Modifier.fillMaxSize())`（[163–166](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L163)）无背景；`MaterialTheme` 本身不绘制背景 → 浮层全屏透明。
- 浮层内部：`PlayerDrawer` 根 `Box(fillMaxSize)` 背景为 `scrim=Color.Transparent`（[PlayerDrawer:131](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt#L131)、[604–608](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt#L604)），仅面板区 `Box(...).background(container…)` 为局部不透明；`ChannelSwitchOverlay` 仅小胶囊有半透明底。故仅在面板/胶囊处覆盖，视频区域透明。
- **该「SurfaceView 底层 + 上层透明 View 浮层」是标准可工作模式**：Media3 `PlayerView` 自身即是「SurfaceView + 其上 View 控制器」且视频可见，证明“透明上层 View 不遮挡下层 SurfaceView”。与 t30 的失败结构（SurfaceView 在 Compose 子组合内）本质不同。`rootLayout` 黑底 + 透明 shutter → 首帧前为**黑**非灰。
- **结论**：源码层架构正确、灰屏回归风险已被结构性消除。**但“首帧后视频在 SurfaceFlinger 中可见、非 z=-2/1×1 被遮”只能真机判定**（见 §7），不能据源码宣称零回归。

**焦点/命中/退出**：`SurfaceView` 非焦点且 `FOCUS_BLOCK_DESCENDANTS`，焦点应落到 `ComposeView`；`Activity.dispatchKeyEvent` 面板关闭时自处理 DPAD/频道键、面板开时回落 `super` 交由聚焦的 `ComposeView`（内部 DPAD 导航）。命中测试：`ComposeView` 全屏在上、`rootLayout`/`ComposeView` 透明，事件可达。**未在源码显式 `requestFocus()`/`setViewCompositionStrategy(...)`** —— 见 §6 加固建议；属真机必须验证项（DPAD 是否到达 ComposeView 并驱动抽屉/设置/列表导航），非源码可证缺陷。

## 3. 播放器 / Surface 复用 — 源码成立

- 播放器由 Activity 持有并绑定到原生 `videoView`：[initializePlayer:474](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L474) `playerView.player = newPlayer`（prepare 之前）；[releasePlayer:483](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L483) `playerView.player = null` 后 `StreamPlayback.release`；`request==null` 分支亦置空([472](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L472))。
- 仅在 `player==null` 时新建（[initializePlayer:435](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L435)），`releasePlayer` 仅 `ON_STOP` 触发；换台/回看走 `prepareRequest → StreamPlayback.prepare`（`setMediaSource + prepare`）**同一 ExoPlayer + 同一 SurfaceView，无每次重建**。源码核实成立。

## 4. t34 修复保留 + USAB2-01 崩溃已修（关键回归）

- **USAB2-01 已修复**：[PlayerActivity:439–448](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L439) `setBufferDurationsMs(1_000, 6_000, 300, 1_000)` —— 满足 Media3 1.11.1 不变量 `minBufferMs(1000) >= bufferForPlayback(300)` 且 `>= bufferForPlaybackAfterRebuffer(1000)`、`max(6000) >= min(1000)`，**不再抛 IllegalArgumentException**；仍快于原 1500/6000/500/1000。`media3-exoplayer:1.11.1`（[build.gradle.kts:73](../../iqtvlite-integration/android/app/build.gradle.kts#L73)）确认。
- **新增 JVM 守护** [LoadControlConfigTest.kt](../../iqtvlite-integration/android/app/src/test/java/com/cjlhll/iptv/LoadControlConfigTest.kt)：`productionBufferDurationsAreValid` 用生产值 build 不抛；`invalidBufferDurationsThrow` 用旧 800/6000/300/1000 断言抛 `IllegalArgumentException`。云 run78 JVM 23 tests 0 失败含此 2 项（引用 t38 receipt，本任务未重跑）。**注意**：该测试硬编码了生产字面值而非共享常量（见 §6 建议）。
- 频道键（`CHANNEL_UP/PAGE_UP→上一台`、`CHANNEL_DOWN/PAGE_DOWN→下一台`，仅 ACTION_DOWN、面板关闭时生效、面板开回落 super）与去预热、sidebar 无逐帧动画均保留（diff 未回退；键码文档已在 t38 §4 更正为 166/167/92/93，不再用 91）。

## 5. 性能预期 — 方向有据，效果须真机

- 零拷贝：改为 SurfaceView 基座后，视频在独立合成层、不经 Compose RenderThread/GPU 采样，**消除 t30 TextureView 的每帧 GPU 拷贝**（CI10076 实测稳态 CPU≈82% 单核之主因，见 [GREYSCREEN_PERF_VERIFICATION §4.3](CI_GREYSCREEN_PERF_VERIFICATION.md)）。方向正确、有据。
- sidebar 与视频解耦：视频独立层，Compose 浮层重组/动画不再经同一 RenderThread 拖累视频 → “打开/浏览侧栏视频跟随卡”应从机制上缓解。
- **但均为预期**：CPU/PSS/gfxinfo/TTFF 的实际改善比例、以及 SurfaceView 是否真的零拷贝生效，只能 acceptance2 真机测。**未删功能、未降画质**：未加 `TrackSelector` 分辨率/解码上限、HEVC 仍可解、ABI/JNI/keep 检查保留；属“不改能力换流畅”。

## 6. 变更范围 / 风险 / 预算

- 范围：仅渲染宿主重构 + LoadControl 合法化 + 预算脚本；`PlaybackIntent`（SR）、`PlayerDrawer`、JNI/R8/UA/音频诊断、硬解 OMX/`setEnableDecoderFallback(true)` 均未改。故 TIV-01–18/23 与 SR-01/02/03 的**源码逻辑**未回退（但渲染宿主变化可能影响焦点/显示，需真机回归）。
- 预算：[verify_apk.py:10](../../iqtvlite-integration/scripts/verify_apk.py#L10) `max_bytes 14,454,072 → 30,000,000`，in-file 记录用户 2026-11-20「最高30MB、性能最高优先」授权；**仍保留双 ABI/JNI/keep 检查与真实字节上报，未删 ABI/功能**。实际 APK 14,126,355B（<30MB，且略小于 t34）——合法，未借放大预算隐瞒体积。

**非阻断加固建议（low，可在后续增量处理，不影响本次源码放行）**：
1. 焦点健壮性：建议在 `onCreate` 对 `composeOverlay` 显式 `requestFocus()`，并 `setViewCompositionStrategy(DisposeOnViewTreeLifecycleDestroyed)`；降低“DPAD 未落到 ComposeView / 组合不随 Activity 销毁”的风险（当前依赖隐式焦点）。
2. `LoadControlConfigTest` 应引用与 `initializePlayer()` 共享的常量（如 `PlaybackTuning.BUFFER_MS`），避免测试字面值与生产漂移后守护失效。
3. 清理：`res/layout/player_texture_view.xml` 已成为无引用死资源、`AndroidView` 为未用 import，可移除（仅体积/lint 整洁，非缺陷）。

## 7. 放行结论与真机门禁

- **源码结论：可接受（pass）**。架构正确、USAB2-01 崩溃已修且有 JVM 守护、复用/键码/预算均合规、SR/JNI/R8/UA/硬解源码未回退、未降质删功能。
- **放行 acceptance2 在 run78 exact pair（主 `ed165e6e…` / test `5564f5a2…` / 10078）真机验证，顺序**：
  1. **首验“进 PlayerActivity 不崩、能起播”**（USAB2-01 类回归）。
  2. **SurfaceFlinger**：视频层可见、非 z=-2 被遮、非 1×1；冷启首帧前为黑（非灰），首帧后立即可见；**BACK/退出无闪**。
  3. **焦点/命中**：DPAD 打开抽屉、抽屉内上下/左右/跨列导航、设置、信息条、频道键 166/167（或 92/93）换台、数字键，均可达且正确。
  4. gfxinfo（稳态 + 打开/浏览侧栏）50th/95th/janky/missed vsync/dropped；单核 CPU/PSS（目标显著 <82%）；冷启 TotalTime/TTFF、30× zap TTFF p50/p95。
  5. 24-TIV + SR-01/02/03 回归；`AudioSink$UnexpectedDiscontinuityException` 仍为独立音频稳健项（本改造未处理）。
- 本结论不批准自身实现、不等于设备验收、不宣称流畅；历史结论保持原样。

## 8. 只读命令与终态

只读命令：`git rev-parse HEAD`、`git log --oneline`、`git show c82593f5`/`--stat`、`git rev-parse c82593f5:<file>`/`git hash-object`、`git ls-tree`、`git diff --stat 7f6e2d7 HEAD -- PlaybackIntent.kt`（SR 未变）、源码/文档读取。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 本报告完成后停止读取目标源码；后续实现变化须新 diff 证据快照，不自动纳入本次 pass。
