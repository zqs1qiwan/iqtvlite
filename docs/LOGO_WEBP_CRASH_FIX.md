# P0 修复：台标 WEBP 写盘导致冷启动 SIGSEGV（t11）

状态：已修复（写盘改 PNG；旧 `.webp` 仍可读；写盘失败只记日志）。真机复验由 acceptance 执行
（冷启动 + 清 data/清缓存场景）。证据文件：`docs/evidence/2026-11-21-ci-10137-jingcai/crash-coldstart-logo-loader.txt`。

## 1. 崩溃事实（真机 tombstone）

```
11-21 17:32:08.350 F libc: Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
                          tid 6517 (DefaultDispatch), pid 6374 (tv.iqtvlite.app.validation)
Cause: null pointer dereference
pc 00000000
#01 ImportYUVAFromRGBA.llvm.3275434143+480        /system/lib/libhwui.so
#02 WebPPictureImportRGBA+452                     /system/lib/libhwui.so
#03 SkWebpEncoder::Encode(...)+866                /system/lib/libhwui.so
#04 SkEncodeImage(... SkEncodedImageFormat ...)   /system/lib/libhwui.so
#05 Bitmap_compress(...)                          /system/lib/libandroid_runtime.so
#14 android.graphics.Bitmap.compress              boot-framework.vdex
#20 com.cjlhll.iptv.LogoLoader.writeBitmapToDisk$lambda$19+16
#38 com.cjlhll.iptv.LogoDiskStore.writeAtomically+134
#44 com.cjlhll.iptv.LogoLoader.writeBitmapToDisk+40
```

- 设备：TR100-G9 / Android 9（API 28）/ **arm**（armeabi-v7a）/ 用户调试固件 `Sumavision/cucc_guangdong`。
- 触发：**第一次安装或清 data 后冷启动**，台标首次下载并落盘时必现；进程直接死回桌面。
- 线程：`DefaultDispatch`（logo 解码/写盘的 `Dispatchers.Default` 协程），不是主线程崩溃，
  因此没有任何 UI 层异常或 Toast 可提示，用户只看到“打开就闪退”。

## 2. 根因

旧写入实现（`LogoLoader.writeBitmapToDisk`）：

```kotlin
val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
             else Bitmap.CompressFormat.WEBP
return LogoDiskStore.writeAtomically(file) { out -> bitmap.compress(format, 80, out) }
```

该设备的 `libhwui.so` 里 `SkWebpEncoder` 在 `ImportYUVAFromRGBA` 阶段解引用空指针
（`pc = 0x0`，`fault addr 0x0`），属于**固件 Skia/WebP 编码器缺陷**。

关键点：这是 **native SIGSEGV**，JVM 的 `try/catch`/`runCatching` **完全无法捕获**；
只要还用 WEBP 编码器写盘，包一层异常处理也照样闪退。所以修复必须换编码格式。

## 3. 修复（只动台标写盘，不碰播放/UI）

| 改动 | 位置 | 说明 |
|---|---|---|
| 写入格式改 **PNG** | `LogoLoader.writeBitmapToDisk` | `Bitmap.CompressFormat.PNG` + quality 100（无损；全 API 稳定，无硬件编码器依赖） |
| 写盘防御 | `LogoLoader.writeAtomicallyDefensively` | `runCatching` 包住 `LogoDiskStore.writeAtomically`；编码/IO 异常 → 返回 `false`，只记 `IQTV-LOGO write_failed=png`，**不冒泡** |
| 编码结果校验 | 同上 | `bitmap.compress(...)` 返回 false 时抛给防御层 → 写盘算失败（不留半个文件） |
| 已回收位图短路 | 同上 | `bitmap.isRecycled` → 直接 `write_failed=png reason=recycled`，不调 native |
| 新缓存后缀 `.png` | `LogoLoader.cacheFile` | 文件名 `<diskKey>.png`；`diskKey` 不变（url sha256 + 目标尺寸），内存 LRU/预取逻辑不变 |
| 旧 `.webp` 仍可读 | `LogoLoader.legacyCacheFile` / `readableCacheFile` | 读盘顺序 `.png` → `.webp`；升级不清旧缓存，二次启动仍秒显 |
| 统计/裁剪/清理覆盖新旧 | `LogoDiskStore.EXTENSION("png")` / `LEGACY_EXTENSIONS` / `DISK_EXTENSIONS` | `scan`/`prune`/`clear` 默认同时处理 `.png`+`.webp`，避免旧 webp 变孤儿文件 |
| 原子性保持 | `LogoDiskStore.writeAtomically`（未改） | 唯一临时文件 + 原子 rename；同 key 串行（LOCK-01）；失败不动已发布文件 |

不变项：cache key（`sha256(url)_<w>x<h>`）、内存 LRU（96 条）、预取/并发门控、磁盘上限（32MB）、
`stats()` 字段、`clearDisk`/`invalidate` 语义、启动流程与 UI 全部未改；体积门禁不受影响
（PNG 仅影响缓存目录，不进 APK）。

## 4. 回归与门禁

- JVM 单测（新增 `LogoWriteFormatTest`）：
  1. `writeFormatIsPngAndNeverWebp` —— 写盘格式常量必须 `PNG`，且不等于 `WEBP` / `WEBP_LOSSY`；
  2. `newCacheFilesUsePngExtension` —— 新缓存文件名 `.png`，永不为 `.webp`；
  3. `legacyWebpIsStillReadable` —— 只有旧 `.webp` 时能读；`.png` 出现后优先读新文件且不删旧文件；
  4. `writeFailureReturnsFalseInsteadOfThrowing` —— 编码器抛异常 → 只返回 false，无临时文件残留；
  5. `statsAndCleanupCoverBothNewAndLegacyExtensions` —— 统计/裁剪/清理同时覆盖 `.png` + `.webp`。
- 既有 `LogoDiskStoreTest` 同步更新（`clear(extensions = listOf("webp"))`），其余 8 个原子性/并发用例不变。
- 静态门禁 `scripts/check_logo_webp.py`（已在 `apk.yml` 构建校验步骤内执行）：
  源码中不得出现 `CompressFormat.WEBP*`；必须使用 `Bitmap.CompressFormat.PNG` +
  `writeAtomicallyDefensively`；`LogoDiskStore.EXTENSION == "png"` 且 `LEGACY_EXTENSIONS` 含 `"webp"`；
  读盘必须具备 `legacyCacheFile` / `readableCacheFile`；回归测试与本文档必须存在。
- CI：`.github/workflows/apk.yml` 的 `Verify APK contains Rust .so` 步骤新增
  `python3 scripts/check_logo_webp.py`；`:app:testDebugUnitTest` 覆盖上述 JVM 用例。

## 5. 设备侧复验步骤（acceptance 执行，本次不跑）

1. **冷启动不闪退**（修复的主验收点）：`adb uninstall` 后全新安装（或清 data），播放器冷启动 →
   台标陆续出现，进程存活（`pidof tv.iqtvlite.app.validation` 不变）。
2. **首次写盘成功**：`adb shell run-as <pkg> ls -l cache/logo_cache_v1 | head` 应出现 `*.png`；
   `adb shell logcat -d | grep IQTV-LOGO` 不应出现 `write_failed=png`。
3. **二次秒显**：force-stop 再启动，同频道台标走磁盘命中（`IQTV-LOGO` perf 行 cache hits > 0）。
4. **旧 webp 兼容**：保留旧版本写下的 `.webp` 文件（升级安装而非清 data），启动后仍能显示台标。
5. **写盘失败不崩**：缓存目录只读/磁盘满（可用 `adb shell chmod` 或填满分区模拟）时，
   只出现 `write_failed=png` 日志，播放器继续运行、台标显示占位。
6. 顺带回归：设置 → 台标与缓存 → 「刷新当前组台标」「清理台标缓存」计数正确（0dp/无边框等视觉不变）。

## 6. 回退

单点回退：把 `LogoLoader.WRITE_FORMAT_NAME` / `writeBitmapToDisk` 改回旧实现即可（不建议，会复现 P0）。
读路径保留 `.webp` 兼容，因此**不需要**迁移或清空任何用户缓存；两个方向都可回退。

## 7. 变更清单

| 文件 | 变更 |
|---|---|
| `android/app/src/tivimini/java/com/cjlhll/iptv/LogoLoader.kt` | PNG 写盘、防御层、`.png` 新后缀、旧 `.webp` 读兼容、stats/日志 |
| `android/app/src/tivimini/java/com/cjlhll/iptv/LogoDiskStore.kt` | `EXTENSION`/`LEGACY_EXTENSIONS`/`DISK_EXTENSIONS`，`scan`/`prune`/`clear` 支持多后缀 |
| `android/app/src/test/java/com/cjlhll/iptv/LogoWriteFormatTest.kt` | 新增回归（6 个用例覆盖格式/兼容/失败不抛） |
| `android/app/src/test/java/com/cjlhll/iptv/LogoDiskStoreTest.kt` | 适配多后缀 API |
| `scripts/check_logo_webp.py` | 新增静态门禁（禁止 WEBP 编码器回归） |
| `.github/workflows/apk.yml` | 校验步骤加 `check_logo_webp.py`；`push` 触发加入 `integration/tivimini-baseline` |
| `docs/LOGO_WEBP_CRASH_FIX.md` | 本文（崩溃栈与修复对照、验收步骤） |

## 8. 交付 receipt（CI 绿）

| 项 | 值 |
|---|---|
| 修复提交 | `dfb69f1` fix(logo): write logo cache as PNG (P0 WEBP SIGSEGV)…；`dde661e` fix(ci): default push builds to isolated validation package |
| CI run（绿） | **#141**，sha `dde661e`，event `push`（推送即自动构建），conclusion `success` |
| 产物 | `iqtvlite-test-apk`（artifact id 14702980775，10,372,465 B）；`iqtvlite-android-reports`（id 14703070432） |
| 包 | `tv.iqtvlite.app.validation`，versionCode `10141`，versionName `2.2.0-tivimini-dde661e5-validation` |
| `app-debug.apk` sha256 | `f7b9931fd86bd34f314c9c7f6e6878a8e914e64c9b861bb3d927b0e070e15722` |
| `app-debug-androidTest.apk` sha256 | `97b5aeca6a35fcd7ed857f4f83745f29a4bc14769a38cc479e8f01eda99d2bc1` |
| 签名证书 SHA-256（主包=测试包） | `2dbd4422210439ed5c6bf05ed88f0d6bdb85778215202f93350b892eeaff7fb9`（4 个稳定签名 secrets 尚未配置，故仍是临时 debug keystore，跨 run 会变——见 `docs/CI_STABLE_SIGNING.md`） |
| 门禁结果 | `BUILD SUCCESSFUL`（`testDebugUnitTest` + `lintDebug` + `assembleDebug` + `assembleDebugAndroidTest` 全部通过）；`check_logo_webp: OK (PNG writes, defensive wrapper, legacy .webp readable, no WEBP encoder)`；instrumentation pair `{"missing_types": 0, "missing_methods": 0, "missing_fields": 0}`；dex contract `Ran 7 tests … OK` |

附：run **#140**（sha `dfb69f1`）在 pair gate 失败，原因不是本次台标改动，而是 push 触发**没有 inputs**，
构建了非隔离包（`applicationId=tv.iqtvlite.app`），不走 `instrumentation-shared-keep.pro`，R8 收缩后
test APK 引用缺失（`missing_types=24 / missing_methods=58 / missing_fields=5`，全是 keep 规则覆盖的
Kotlin/AndroidX/App 符号）。已由 `dde661e` 修：push 事件默认回退 `isolated_test_package=true`
（`workflow_dispatch` 显式 false 仍生效），run #141 全绿。

设备侧复验（冷启动不闪退 / 首次写 .png / 二次秒显 / 旧 webp 可读 / 写盘失败不崩）按 §5 由 acceptance 执行；
本任务未执行任何 adb/设备命令。

