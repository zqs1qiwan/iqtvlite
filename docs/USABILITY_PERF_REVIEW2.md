# 频道键 / 起播 / 卡顿 / sidebar 性能修复 独立源码复查（t34 / run77）

任务 t35（attempt 2），designer2，2026-11-20（Asia/Hong_Kong）。**结论：needs_revision（BLOCKER），不放行、不可直接上机验证。** 命中一个会在播放器初始化时同步抛异常的 LoadControl 参数违约：run77(t34) 候选在 `initializePlayer()` 即崩溃，频道键/换台/sidebar 全部无从验证。其余改动源码方向正确。本复查只读固定 commit，未改 Kotlin/Rust，未跑 Gradle/CI，未操作 TV（设备归 acceptance2）。依据 [t34 修复说明](../../iqtvlite-integration/docs/USABILITY_PERF_REPAIR2.md)、[灰屏诊断](GREYSCREEN_PERF_DIAGNOSIS.md)、[CI10076 真机复验](CI_GREYSCREEN_PERF_VERIFICATION.md)、[24项TIV规格](TIVIMINI_INTERACTION_SPEC.md)。**本报告完成后停止读取目标源码。**

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`，分支 `integration/tivimini-baseline`。
- 被审查修复 commit `bcf4a1e134c985e2e9e1b2a7724db93dbb62c970`（t34 源码），docs-only 后续 `6da7c6e`（`docs/USABILITY_PERF_REPAIR2.md`）。HEAD=`6da7c6e`；`bcf4a1e1..HEAD` 未再触及 PlayerActivity/PlayerDrawer。
- 读取内容身份（git blob，随提交）：

| 文件 @bcf4a1e1 | blob |
|---|---|
| [PlayerActivity.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt) | `49a6940a911f7a05bff1d92f69519da3de6c31b6` |
| [PlayerDrawer.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt) | `64894c617565f67a6f39a58c34903d4415e431df` |

- 修复 diff 范围（`git show bcf4a1e1`）：**仅 2 文件 +19/−29**（PlayerActivity：频道键分支+LoadControl+移除预热；PlayerDrawer：去逐帧动画）。`PlaybackIntent.kt` 自 `7f6e2d7` 起 0 变更（SR 逻辑未动）。
- **工作树当前有未提交改动**（`git status`：`M PlayerActivity.kt`，diff 为另一条进行中的 t38「SurfaceView 基座 + 透明 ComposeView」改造）。**该未提交改动不属于 t34/run77，不在本次审查范围**，本次证据一律钉在 `bcf4a1e1`（即 run77 构建源）。经核对该未提交改动**未触及** §2 的 LoadControl 缺陷，故缺陷同样存在于工作树的后续改动中，须一并修。
- 候选身份沿用 t34 记录（本任务未构建/安装）：主 `app-debug.apk` 14,159,123B SHA256 `1ed8b730…7993`，test `5b06fa01…48af`，签名 `bdab6664…3449`，run 44567827863(run77)，version 10077 / `2.2.0-tivimini-bcf4a1e1-validation`。**该 pair 受 §2 缺陷阻断，不建议上机。**

## 2. BLOCKER — LoadControl 参数违约，播放器初始化即抛异常（阻断全部验证）

[t34 修改](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L412) 在 `initializePlayer()`（`ON_START` 触发，[454–467](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L454)）中：

```kotlin
val loadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(800, 6_000, 300, 1_000)   // (minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs)
    .setBackBuffer(0, false)
    .setPrioritizeTimeOverSizeThresholds(true)
    .build()
```

项目依赖 `androidx.media3:media3-exoplayer:1.11.1`（[build.gradle.kts:73](../../iqtvlite-integration/android/app/build.gradle.kts#L73)）。Media3 1.11.1 `DefaultLoadControl.Builder.setBufferDurationsMs` 在赋值前即断言（官方源码：

`assertGreaterOrEqual(minBufferMs, bufferForPlaybackAfterRebufferMs, "minBufferMs", "bufferForPlaybackAfterRebufferMs");`，见 [DefaultLoadControl.java @1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java)；本地 `%USERPROFILE%\.gradle\caches\...\media3-exoplayer-1.11.1.aar` 的 `DefaultLoadControl$Builder.class` 亦含 `assertGreaterOrEqual` 与 `"%s cannot be less than %s"`）：

- `minBufferMs(800) >= bufferForPlaybackMs(300)` ✔
- **`minBufferMs(800) >= bufferForPlaybackAfterRebufferMs(1000)` ✘ → `IllegalArgumentException: minBufferMs cannot be less than bufferForPlaybackAfterRebufferMs`**

该断言在 `setBufferDurationsMs(...)` 调用当场同步抛出，位于 `initializePlayer()` 主线程、无 try/catch（[409–449](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L409)）。`ON_START` 必调 `initializePlayer()`，故 **run77 APK 一旦进入 PlayerActivity 即崩溃**，频道键/换台/卡顿/sidebar 全部无法验证。CI(JVM 21/lint) 不会构造该 Builder，无法暴露此运行期缺陷——这正是需源码复查拦截项。

**最小修复（可执行）**：使 `minBufferMs >= bufferForPlaybackMs` 且 `minBufferMs >= bufferForPlaybackAfterRebufferMs`。任选其一：
- `.setBufferDurationsMs(1000, 6_000, 300, 1000)`（起播阈值仍从 500→300ms 加快，min 缓冲 1000 仍 < 旧 1500，满足 1000≥300、1000≥1000、6000≥1000）；或
- `.setBufferDurationsMs(800, 6_000, 300, 800)`（after-rebuffer 亦降到 800）。
- 并新增一个 JVM 守护测试：直接 `DefaultLoadControl.Builder().setBufferDurationsMs(...).build()` 构造一次，确保参数组合合法（可离线运行，防止再次回归）。修复后重跑 CI 生成新 pair（run77 作废），再交 acceptance。

## 3. 遥控器频道键 — 源码方向正确（设备未验证）

[PlayerActivity:285–303](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L285) 将 `KEYCODE_CHANNEL_UP/PAGE_UP` 并入 `DPAD_UP` 分支、`CHANNEL_DOWN/PAGE_DOWN` 并入 `DPAD_DOWN` 分支：

- 仅 `event.action==ACTION_DOWN`（[184](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L184)）内处理；面板均关闭时 `onChannelStep(-1/+1)` 换台；回看态改为显示信息条（同 DPAD 语义）。
- 面板打开（drawer/settings/quality）时 `if` 条件为假、函数体不 `return`，落到 [306](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L306) `return super.dispatchKeyEvent(event)` → 不换台、交由面板 DPAD 导航。**与面板导航无冲突；面板关闭才换台——源码成立。**
- **缺陷（文档/验收指令，非源码，medium）**：`USABILITY_PERF_REPAIR2.md` §1/§6 写 `adb shell input keyevent 91/92` 作 CHANNEL_UP/DOWN 是**错误键码**：`91=KEYCODE_MUTE`、`92=KEYCODE_PAGE_UP`（`93=PAGE_DOWN`），CHANNEL_UP/DOWN 为 `166/167`。acceptance 用 91 会得到“无反应”，可能误判频道键失效。**修复**：验收用 `input keyevent 166`(上)/`167`(下) 或 `92`(PAGE_UP)/`93`(PAGE_DOWN)。

## 4. 起播/换台：复用同一 player/Surface — 源码成立（但被 §2 阻断）

- [initializePlayer:409–449](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L409) 仅在 `player==null` 时建 `ExoPlayer`；`releasePlayer()` 仅 `ON_STOP`（[451+](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L451)；lifecycle [454–467](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L454)）。换台走 `prepareRequest → StreamPlayback.prepare`（`setMediaSource + prepare` 同一 player），**不重建 player/Surface**——与 t34 说明一致，源码核实成立。
- 移除 `experimentalSetEnableMediaCodecVideoRendererPrewarming(true)`（[426–430](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L426)）：移除一次性预热、可能降低冷启峰；属合理。`setEnableDecoderFallback(true)` 保留（硬解不变）。
- LoadControl 意图（更快起播）合理，但当前取值的非法性使上述增益**完全无法生效**（先崩溃）。TTFF 提速“有据”仅在修好 §2 且真机实测后才成立。

## 5. 卡顿/CPU — 动画收敛正确，但主 CPU 瓶颈未解（不得宣称流畅）

- 逐帧面板动画已移除：[PlayerDrawer:595–596](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt#L595) `drawerWidth=targetDrawerWidth`、`drawerOffsetX=if(visible)0.dp else -drawerWidth`（instant），仍经 [668–669](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt#L668) `.width(drawerWidth).offset(x=drawerOffsetX)` 生效；`AnimatedVisibility` 由 slide/expand 改为纯 `fadeIn/fadeOut`。**逻辑自洽**：打开/浏览侧栏由多帧布局动画降为单次布局，能减轻“侧栏拖动导致视频跟随卡”的 UI 线程饱和。UX 取舍（无滑入）符合任务“避免逐帧动画”。
- **未使用** `setOpaque(false)`/`LAYER_TYPE_HARDWARE` 或 SurfaceView 基座（t34 明确未采用/未盲改，正确）：对全屏视频 `setOpaque(false)` 反增混合成本，论证成立。sidebar `epgChannelUiCache`（[PlayerDrawer:174](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerDrawer.kt#L174)）与 `nowProgramByUrl` 在 `Dispatchers.Default`（PlayerActivity 754-768）保留、离主线程。
- **关键预期校正（不得误判为已达标）**：CI10076 真机测得稳态 CPU≈82% 单核，主因是 **TextureView 每帧 GPU 拷贝/合成**（[CI_GREYSCREEN_PERF_VERIFICATION §4.3](CI_GREYSCREEN_PERF_VERIFICATION.md)）。t34 本次改动（去预热=仅冷启一次；去侧栏动画=仅侧栏开/浏览期）**均不触及稳态播放期该开销**，故 **acceptance 的“CPU<82%”目标不应期待由本 pair 达成**；真正的 CPU 杠杆仍是 SurfaceView 零拷贝基座（t34 已上报、未实施；工作树已有进行中的 t38 尝试）。这是**预期边界，不是本修复缺陷**，但验收不得以“CPU 未降”误判为 t34 无效——应据“侧栏打开期 dropped frames/gfxinfo 是否改善”评估低风险项收益。

## 6. 既有能力未回退（源码级）

- 修复 diff 仅 2 文件：`PlaybackIntent.kt` 自 `7f6e2d7` 零变更（SR-01/02/03 未动）；JNI/R8/UA/音频诊断无涉及；`setEnableDecoderFallback(true)` 保留、未加 `TrackSelector` 分辨率/解码限制、未改硬解 OMX 路径（仅移除预热，属初始化策略非解码能力）。→ **TIV01–18/23、SR、JNI/R8/UA、硬解源码层面未见回退。**
- 但 §2 的崩溃会令**整个 App 在进入播放器时不可用**，等同对所有 TIV/SR 的运行时回归，必须在放行前修复。

## 7. 放行结论与验收门禁

- **源码结论：needs_revision / 不放行。** §2 LoadControl 参数违约是 blocker：run77 pair 预期在 `initializePlayer` 崩溃，任何设备验证无意义。须按 §2 最小修复 + 守护测试 + 重建新 CI pair（run77 作废），再复审放行。
- 次级：§3 文档 adb 键码错误须更正，否则 acceptance 会误测。
- 修复并新 CI 后，acceptance2 在新 exact pair 上验证：①`initializePlayer` 不再抛异常、能稳定起播；②频道键用 **166/167 或 92/93**（非 91）上/下换台，面板内 DPAD 仍导航；③冷启 TotalTime/TTFF、约30次 zap TTFF p50/p95 前后对比；④侧栏打开/浏览 gfxinfo+dropped frames 且视频不跟随卡；⑤单核 CPU（注意 §5 预期，稳态 82% 系 TextureView 拷贝，非本项能解）；⑥24-TIV + SR 回归；⑦`AudioSink$UnexpectedDiscontinuityException` 仍为独立音频稳健项（本修复未处理）。
- 本结论不批准自身实现、不等于设备验收；历史结论保持原样。

## 8. 只读命令与终态

只读命令：`git rev-parse HEAD`、`git log --oneline`、`git show bcf4a1e1`/`--stat`、`git diff --stat 7f6e2d7 HEAD -- <SR文件>`、`git rev-parse bcf4a1e1:<file>`/`git hash-object`、`git status`/`git diff HEAD -- PlayerActivity.kt`（确认工作树另有未提交 t38 改动，非本次范围）、解包本地 `media3-exoplayer-1.11.1.aar` 检查 `DefaultLoadControl` 断言字符串、并核对官方 1.11.1 `DefaultLoadControl.java` 的 `setBufferDurationsMs` 断言。**未跑 Gradle/Cargo/CI/ADB、未改任何文件、未操作 TV。** 本报告完成后停止读取目标源码；后续实现变化须新 diff 证据快照，不自动纳入本结论。
