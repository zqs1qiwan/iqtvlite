# CI10077 可用性快验（频道键/起播/卡顿/sidebar）— 失败（阻断崩溃）

2026-11-20，acceptance2（t37 attempt 1）。对象：`tv.iqtvlite.app.validation` **10077 / 2.2.0-tivimini-bcf4a1e1-validation**（CI10077，run 44567827863 / source bcf4a1e1）。设备 `192.168.2.245:5555` 在线。

## 0. 结论（先给）：**失败/阻断** —— 进入播放器即崩溃，无法播放任何频道

CI10077 在进入 `PlayerActivity` 时**确定性崩溃**（复现 3 次）：`java.lang.IllegalArgumentException: minBufferMs cannot be less than bufferForPlaybackAfterRebufferMs`，进程被 Force finishing → 返回当贝桌面。**App 完全无法起播**，因此本次要验的用户四问（CHANNEL 键换台 / 起播换台更快 / 整体卡顿 / 开启 sidebar 视频是否跟随卡）**全部无法进入播放态测试（判失败/未验证）**。这是 t34 “LoadControl 提速” 引入的阻断级回归。**远劣于 CI10076（10076 可正常出画观看）。**

## 1. 产物身份与安装（PASS）

| 项 | 值 |
|---|---|
| 主 APK SHA256 | 1ed8b730d1c50c5e6874ed48c73749a1fadd1f277477c80d480885827ea37993（14,159,123B，match） |
| test SHA256 | 5b06fa0146d451f977e22bb7c1405b236e4ac81bdfd3e515ffd65440f85848af（502,955B，match） |
| 包/版本 | tv.iqtvlite.app.validation / 10077 / 2.2.0-tivimini-bcf4a1e1-validation |
| min/target/ABI | 24/35；arm64-v8a+armeabi-v7a |
| 签名SHA256 | bdab6664326a9697b770af70198278a13a90d6fd788012e67db05b513bdb3449（两包同） |

安装：backup 旧 10076 沙箱（validation-10076-backup.tar 1,725,440B / test 2,560B）后仅替换两个 validation 包（Success）；安装后 `pm path` 拉取 base.apk 复算 = `1ed8b730…`（match），versionCode 10077 / primaryCpuAbi armeabi-v7a。**原 tv.iqtvlite.app 与 com.cjlhll.iptv 及数据保留。**

## 2. 阻断缺陷（最小复现）

```
adb -s 192.168.2.245:5555 shell am force-stop tv.iqtvlite.app.validation
adb -s 192.168.2.245:5555 shell am start -n tv.iqtvlite.app.validation/com.cjlhll.iptv.SplashActivity
# 配源(或已有源)进入 PlayerActivity 即崩；进程死亡，回当贝桌面
```
实际 AndroidRuntime（[crash-logcat.txt](evidence/2026-11-20-ci-10077/crash-logcat.txt)）：
```
E AndroidRuntime: FATAL EXCEPTION: main
E AndroidRuntime: Process: tv.iqtvlite.app.validation, PID: 25189
E AndroidRuntime: java.lang.IllegalArgumentException: minBufferMs cannot be less than bufferForPlaybackAfterRebufferMs
E AndroidRuntime:   at androidx.media3.exoplayer.DefaultLoadControl$Builder.setBufferDurationsMs(DefaultLoadControl.java:292)
E AndroidRuntime:   at com.cjlhll.iptv.PlayerActivityKt.VideoPlayerScreen$initializePlayer(PlayerActivity.kt:413)
E AndroidRuntime:   at ...VideoPlayerScreen$lambda$105...(PlayerActivity.kt:462)
W ActivityManager:   Force finishing activity tv.iqtvlite.app.validation/com.cjlhll.iptv.PlayerActivity
I ActivityManager: Process tv.iqtvlite.app.validation (pid 25189) has died: fore TOP
```
- 复现：LAN 配源后自动进 PlayerActivity → 崩；再次 force-stop 冷启 → 仍崩（3 次一致，确定性）。
- 表现：MainActivity（配置页）可起（TotalTime 5979ms），但一旦进入播放即 Runtime 崩溃、进程死亡、回桌面；**没有任何首帧/画面**。

## 3. 根因

源码 `PlayerActivity.kt:413-421`（[源码](../../iqtvlite-integration/android/app/src/tivimini/java/com/cjlhll/iptv/PlayerActivity.kt#L413)）：
```
setBufferDurationsMs( 800, 6_000, 300, 1_000 )
// 依次: minBufferMs=800, maxBufferMs=6000, bufferForPlaybackMs=300, bufferForPlaybackAfterRebufferMs=1000
```
Media3 `DefaultLoadControl.setBufferDurationsMs` 的先决条件要求 **`minBufferMs >= bufferForPlaybackAfterRebufferMs`**（且 `minBufferMs >= bufferForPlaybackMs`、`maxBufferMs >= minBufferMs`）。此处 **800 < 1000** 违反先决条件 → `IllegalArgumentException: minBufferMs cannot be less than bufferForPlaybackAfterRebufferMs`。这是 t34 “LoadControl 1500/6000/500/1000 → 800/6000/300/1000” 改动引入（第 4 参数 1_000 > 第 1 参数 800 非法）。

## 4. 修复要求（交 developer）

- 满足 `minBufferMs >= bufferForPlaybackAfterRebufferMs`。最小改法任一：
  - 第4参数 `bufferForPlaybackAfterRebufferMs` 改为 **≤800**（如 500 或 800）；或
  - 第1参数 `minBufferMs` 提到 **≥1000**（但会牺牲“更快起播”目标）。
  推荐：`setBufferDurationsMs(800, 6_000, 300, 800)`（或 500），保留 0.8s 起播目标。
- 修复后重跑本地门禁 + 新 r27 CI pair，交 acceptance 复验（进入 PlayerActivity 不崩、能起播，再测频道键/zap/sidebar/CPU）。
- 建议加“能否进入播放器/起播”冒烟门禁（本缺陷本地 JVM/编译无法暴露，需 instrument 或真机 UI 起播冒烟）。

## 5. 逐项 通过/失败/未验证（本轮实际执行数）

| 项 | 结果 |
|---|---|
| 身份/安装/备份/保留原包 | 通过 |
| 冷启进入 PlayerActivity | **失败：Runtime 崩溃（3/3 必现）** |
| 灰屏是否回归 | 无法评估（进不了播放器，崩溃不如灰屏） |
| CHANNEL 键换台(91/92/PAGE) | **未验证（App 无法起播）** |
| 起播/换台 TTFF、冷启 TotalTime | **未验证（崩溃，无首帧）** |
| sidebar 打开/浏览视频是否跟随卡 | **未验证（无法播放）** |
| 稳态 CPU/PSS vs CI10076≈82% | **未验证（无法播放）** |
| TIV 冒烟(双列/OK/Back) | **未验证（无法进入播放器）** |

实际执行：身份/安装 1 次；冷启进 PlayerActivity 崩溃 3/3；其余因崩溃 0 项可测。

## 6. 判定

**FAIL（阻断）**。CI10077 因 `DefaultLoadControl.setBufferDurationsMs` 参数非法（minBufferMs 800 < bufferForPlaybackAfterRebufferMs 1000）在 `PlayerActivity.kt:413` 起播即崩，无法播放任何频道，用户四问均无法验证。需 developer 修 LoadControl 参数并重新 CI 交付后复验。不得使用 CI10077 作为交付候选。设备侧无干扰；未改代码/未改原包数据。
