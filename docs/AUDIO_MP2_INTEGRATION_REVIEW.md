# MP2 音频集成 独立复查（t117 / run94）

任务 t118（attempt 1），designer2，2026-11-21（Asia/Hong_Kong）。**结论：源码层可接受（pass）。** AAR 已正确集成（仅 `arm64-v8a` + `armeabi-v7a`，无 x86；`implementation(files(...))` 路径正确；`EXTENSION_RENDERER_MODE_ON` 非 PREFER）；`PlayerActivity` 把它接到 `ExoPlayer.Builder.setRenderersFactory`；NOTICE 随包；未改观感/交互/EPG/LoadControl/SR/品牌/零拷贝基座；APK 12,928,915B≤30MB。真机音频未验证。剩余为 R8 反射 keep、全量 LGPL 文本、AAR 含视频 renderer 类等加固项（见 §7）。

> 本复查只读固定 commit `ae531f7f`（工作树 clean）。模型不能读图，音频/真机结论归 acceptance2(t119)。

## 1. 审查身份与证据冻结

- 目标 `C:/Users/Q/Documents/Projects/iqtvlite-integration`，分支 `integration/tivimini-baseline`；HEAD=`075c8bf`（docs-only），工作树 clean；`ae531f7f..HEAD` 无代码改动。
- 被审查 commit `ae531f7fe76c395c73d79814b7c02475f6e9f924`（t117），5 文件 +83（含 AAR 1,266,615B）。
- 关键文件 blob @ae531f7f：

| 文件 | blob / 证据 |
|---|---|
| [build.gradle.kts](../../iqtvlite-integration/android/app/build.gradle.kts) | `implementation(files("libs/lib-decoder-ffmpeg-release.aar"))`（依赖区 +2 行） |
| [PlayerActivity.kt](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt) | `DefaultRenderersFactory(context).setEnableDecoderFallback(true).setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)`；`setRenderersFactory(renderersFactory)` |
| `android/app/libs/lib-decoder-ffmpeg-release.aar` | 1,266,615B；SHA256 `E63AFB2FAEDF1DA7AA6C3C96B33F8EBEE9717DCC133D235D3FD8E88DF03ACFEC` |
| [NOTICE_FFMPEG_LGPL.txt](../../iqtvlite-integration/android/app/src/main/assets/NOTICE_FFMPEG_LGPL.txt) | 1,168B |
| `docs/AUDIO_MP2_INTEGRATION.md` | 集成/许可/体积/验证说明 |

- 候选：主 12,928,915B SHA256 `1e518f49…dad6`，test 502,955B `0ff2f7ee…0641`，签名 `773bf65a…c7a1`，run 44773892066(run94)，10096。未真机验证。

## 2. AAR 集成 / ABI / 依赖 — pass

- AAR 实体核查（本机 zip 内容）：仅 `jni/arm64-v8a/libffmpegJNI.so`（1,341,096B）+ `jni/armeabi-v7a/libffmpegJNI.so`（1,155,156B）；**无 x86/x86_64**；`classes.jar` 13,539B、`proguard.txt` 454B、`AndroidManifest.xml`（package `androidx.media3.decoder.ffmpeg`，minSdk 23）。
- `build.gradle.kts` 的 `ndk { abiFilters += listOf("arm64-v8a","armeabi-v7a") }` 与 AAR ABI 完全一致；`implementation(files("libs/lib-decoder-ffmpeg-release.aar"))` 路径为模块 `android/app/libs`，正确。
- `classes.jar` 含 `FfmpegAudioRenderer`（多个公开构造器）、`FfmpegAudioDecoder`、`FfmpegLibrary`、`ExperimentalFfmpegVideoRenderer`；音频扩展类齐全。
- `PlayerActivity` 创建 `DefaultRenderersFactory(context)` 并 `.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)`（**非 PREFER**），随后 `ExoPlayer.Builder(...).setRenderersFactory(renderersFactory)`（L454–466）。平台支持的编解码仍走 MediaCodec；仅平台不支持时扩展兜底。

## 3. LGPL 许可 / NOTICE — 基本满足，建议补全文本（low/medium）

- `android/app/src/main/assets/NOTICE_FFMPEG_LGPL.txt`（1,168B）随 APK；声明 FFmpeg LGPL-2.1-or-later、**无 `--enable-gpl`**、动态 `.so`、FFmpeg release/6.0 + androidx/media 源码链接、LGPL URL，Media3 包装 Apache-2.0。
- 构建脚本 `scripts/build_media3_ffmpeg_audio.sh` 的 `ENABLED_DECODERS=(mp3 aac ac3 eac3 flac opus vorbis alac pcm_mulaw pcm_alaw)`，**无 h264/hevc**；未见 `--enable-gpl/--enable-nonfree`（t116 构建记录一致）。
- **建议（AUDIO-03）**：NOTICE 只给 LGPL URL，未随包放**完整 LGPL-2.1 文本**；为合规齐全，建议把 `LGPL-2.1.txt`（或 FFmpeg `COPYING.LGPLv2.1`）作为 asset 随包，并在 NOTICE 指明路径。

## 4. R8 / keep 契约 — CI 通过，反射构造器需加固（low/medium）

- AAR `proguard.txt`：
  - `-keep,includedescriptorclasses class androidx.media3.decoder.ffmpeg.** { native <methods>; }`
  - `-keep ... FfmpegAudioDecoder { private java.nio.ByteBuffer growOutputBuffer(...); }`
  - 覆盖 JNI native 入口与 `growOutputBuffer`。
- CI 记录 `keep 210 identical`、contract missing types/methods/fields = 0、dex contract 7 OK；与本仓库 `generate_instrumentation_keep.py` 基线一致。
- **注意（AUDIO-01，low/medium）**：`FfmpegAudioRenderer` 由 Media3 `DefaultRenderersFactory` **反射构造**（`Class.forName(...).getConstructor(Context.class)`）；上述 consumer rule 只显式 keep `native <methods>`/`growOutputBuffer`，未显式 keep `FfmpegAudioRenderer` 的 `<init>`。CI 的 keep/contract 不能证明反射构造器未被 shrink/obfuscate。建议：
  - 真机/日志用 `FfmpegLibrary.getVersion()` 确认扩展加载；
  - 若失败，补 `-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { <init>(android.content.Context); }`（或官方完整 consumer rules），并同步更新 keep/contract 证据。

## 5. 无回归 / 体积 / 崩溃风险

- `git show ae531f7f` 仅：`build.gradle.kts` +2 行、AAR、NOTICE、`PlayerActivity.kt` +4 行、docs；**未改**观感/交互/频道键 166/167/LoadControl/EPG/SR/品牌/零拷贝基座/灰屏。
- APK 12,928,915B（≈12.3MiB）≤30MB；增量来自两 ABI `libffmpegJNI.so` ≈2.5MB；本地 debug 15,996,266B 与 CI pair 差异已在 docs 标注，CI receipt 为准。
- ABI 匹配（app `abiFilters` 与 AAR 两 ABI 一致，无 x86 缺口）；`libffmpegJNI.so` 由 Media3 `FfmpegLibrary`/`LibraryLoader` 加载（`isAvailable()` 委托 `LibraryLoader`，通常捕获 `UnsatisfiedLinkError`）；`DefaultRenderersFactory` 对扩展类/构造器异常有容错（找不到则不启用扩展，不崩溃）。真机需确认 `FfmpegLibrary.getVersion()`/AudioSink。

## 6. 回退策略 — pass

- `EXTENSION_RENDERER_MODE_ON` 保持 `MediaCodecAudioRenderer` 第一优先；FFmpeg 仅在平台解码器不支持时启用；**不是 PREFER**，AAC/AVC 继续硬解/平台，避免 A/V 同步与功耗回归。
- `setEnableDecoderFallback(true)` 允许平台 decoder fallback；与扩展 fallback 不冲突。

## 7. 风险 / 加固项（不阻断源码放行）

| id | 严重度 | 问题 | 修复/验证 |
|---|---|---|---|
| AUDIO-01 | low/medium | R8 只 keep native/growOutputBuffer；`FfmpegAudioRenderer` 反射构造器未显式 keep | 真机 `FfmpegLibrary.getVersion()`；若未加载，补 `<init>(Context)` keep 并更新契约 |
| AUDIO-02 | low/medium | AAR `classes.jar` 含 `ExperimentalFfmpegVideoRenderer`（AAR 虽 audio-only 解码器集，但 renderer 类存在）；extension ON 可能添加视频软件 renderer（`ffmpegHasDecoder` 对 h264/hevc 为 false，应不选中） | 设备确认 AVC 仍硬解、无额外 CPU/掉帧；如担心可裁该 class 或文档记录 |
| AUDIO-03 | low/medium | NOTICE 仅链接 LGPL URL，未随包完整 LGPL-2.1 文本 | 加 `assets/LGPL-2.1.txt`/FFmpeg COPYING.LGPLv2.1 并在 NOTICE 注明 |
| AUDIO-04 | low | `LibraryLoader`/`isAvailable` 的 `UnsatisfiedLinkError` 实际捕获需设备确认 | 打印 `FfmpegLibrary.getVersion()`/logcat；arm ABI 已匹配 |
| AUDIO-05 | low | 本地 debug APK 15.99MB vs CI 12.93MB | CI pair 为交付真值；仅记录 |
| AUDIO-06 | low | `ExperimentalFfmpegVideoRenderer` 与“audio-only”表述不完全一致 | 文档说明或裁类（见 AUDIO-02） |
| AUDIO-07 | low | `FfmpegAudioRenderer` 构造器多、反射选择需匹配 | 设备 `getVersion`/MP2 播放验证 |
| AUDIO-08 | info | `NOTICE` 未列 AAR `proguard.txt` 细节 | 保持 AAR consumer rule；无需额外 |
| AUDIO-09 | info | AAR 无 x86，app abiFilters 也无 x86 | 保持 |
| AUDIO-10 | info | `setEnableDecoderFallback(true)` + ON | 保持非 PREFER |

## 8. 放行结论与门禁

- **源码结论：pass**。AAR/依赖/ABI/EXTENSION_RENDERER_MODE_ON/许可 NOTICE/无 UI 回归/体积均满足；真机音频（MP2 有声、AAC/AVC 不回退、音画同步、无新崩溃）未验证。
- acceptance2(t119) 在 exact pair（主 `1e518f49…`/test `0ff2f7ee…`/10096）验证：MP2 频道 AudioSink 有声；AAC/AVC 仍平台/硬解且同步；切台/抽屉无新崩溃；冷启/PSS/体积不回归；打印 `FfmpegLibrary.getVersion()` 确认扩展；若 AUDIO-01 反射未加载，按 §4 补 keep 后重出 pair。

## 9. 只读命令与终态

只读命令：`git rev-parse HEAD`、`git status --short`、`git show --stat ae531f7f`、`git show ae531f7f`、`Get-FileHash`/Zip AAR 条目（jni/classes.jar/proguard/AndroidManifest）、`javap`（FfmpegAudioRenderer/FfmpegLibrary/ExperimentalFfmpegVideoRenderer）、源码 grep（build.gradle/PlayerActivity/Drawer/EPG/LoadControl/SR/brand）。**未执行 Gradle/Cargo/CI/ADB，未改任何文件，未操作 TV。** 本报告完成后停止读取目标源码；后续提交须新 diff 证据快照。
