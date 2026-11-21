# 设备 validation 回退记录：CI10077 → CI10076（可用）

2026-11-20，acceptance2。原因：CI10077 进入 PlayerActivity 起播即崩（`IllegalArgumentException: minBufferMs cannot be less than bufferForPlaybackAfterRebufferMs`，PlayerActivity.kt:413 LoadControl 800<1000），不可用。按 Captain 指示回退到灰屏已修复、可正常出画观看的 **CI10076**，仅替换独立 validation 沙箱，保留原包数据。

## 回退操作与核验

| 项 | 值 |
|---|---|
| 回退前 validation | 10077 / 2.2.0-tivimini-bcf4a1e1-validation（崩溃版） |
| 回退后 validation | **10076 / 2.2.0-tivimini-983e153c-validation** |
| 回退所用主 APK | `local/ci-delivery/iqtvlite-ci-10076-validation.apk` 14,159,123B SHA256 5006e91d…4a66（match） |
| 回退所用 test APK | `local/ci-delivery/iqtvlite-ci-10076-androidTest.apk` 502,955B SHA256 74e4a965…f1be（match） |
| 安装后 base.apk 复算 | 5006e91d6bad682d5d8ef4dcdcd8630e59846389a9f16cd51575c927dc9f4a66（match） |
| 原生产包 | `tv.iqtvlite.app`、`com.cjlhll.iptv` 及数据**保留未动** |

操作：`adb uninstall` 10077 validation 两包 → `adb install -r` 10076 validation 两包（均 Success）→ `pm path` 拉取 base.apk 复算 SHA match → 启动 MainActivity → LAN `POST /api/push`（公开源 https://iptv.laobaitv.net / EPG）→ 进入 PlayerActivity。

## 回退后可用性验证（通过）

- 前台 `PlayerActivity`，进程存活（pid 26133），**无 FATAL/Force finishing**。
- `first_frame session=c7583... mime=video/avc 1920x1080 ttff_ms=3097`；截屏 PIL 像素 mean RGB 75/140/206、std 高、主色桶 0.029 → **真实画面可见（非灰屏）**。
- SurfaceFlinger 无 `SurfaceView - ...PlayerActivity` 层（TextureView 窗口内合成）。
- 证据：[rollback-play.png](evidence/2026-11-20-ci-10077/rollback-play.png)、[rollback-installed-app.apk](evidence/2026-11-20-ci-10077/)（base 复算）。

## 说明

- CI10077 崩溃证据保留于 [crash-logcat.txt](evidence/2026-11-20-ci-10077/crash-logcat.txt) 与 [CI10077_USABILITY_QUICKCHECK.md](CI10077_USABILITY_QUICKCHECK.md)，未删除。
- 设备现处可用 CI10076 状态，用户可直接观看；待 t38 修复 LoadControl 的新 pair 到位后再做 t40 全量验证。
- 未修改任何源代码，未改原包数据。
