# iqtvlite

Android TV IPTV 播放器：Kotlin Compose for TV 负责界面、遥控焦点和生命周期，Media3 1.11.1 负责原生 Surface/硬件解码，Rust 经 JNI 负责播放列表、XMLTV 与稳定 ID。当前整合分支优先复刻 tivimini `8c93e1b` 的原始界面和遥控逻辑。

## 当前整合状态

基线是 `zqs1qiwan/iqtvlite` 的 `90ae4cd`，原始工作区与旧副本均保留。复刻源码位于 `android/app/src/tivimini`；旧 Views/Service 实现保留在仓库，但不参加当前应用编译。没有引入 Slint、mpv、FFmpeg 或录像功能。

原版633dp频道/节目并列抽屉、分组/日期展开、Back一次关抽屉、频道OK保留抽屉、独立右设置和居中源选择均沿用上游逻辑。数字输入、测试源预填和全局编号修复另行标记为移植增量。扫码/局域网推源继续保留，测试地址可以替换。

- [迁移基线与真实模块边界](docs/MIGRATION_BASELINE.md)
- [本次验证结果和待验项](docs/IMPLEMENTATION_VALIDATION.md)
- [本轮原版交互规格](docs/TIVIMINI_INTERACTION_SPEC.md)
- [最新 CI10073：TV-01 修复候选与验收边界](docs/CI_10073_DELIVERY.md)
- [TV-01 根因、共享运行时修复与回退方案](docs/TV01_RUNTIME_REPAIR.md)
- [历史 CI10071 收据：设备 runner 失败，已被替代](docs/CI_DELIVERY.md)

当前候选已通过新的 GitHub CI，r27/API24双ARM产物与配套测试APK身份见云端交付记录；同一 APK 的目标机验收仍待执行。旧版 Release/旧文档中的真机通过记录不能代替本候选结果。性能改进必须来自同机同源 B/C 测量，不能由 Rust 占比或编译成功推断。

## 构建

需要 Rust stable、JDK17、Android SDK36、cargo-ndk。CI 使用 NDKr27；本地主机 r27 下载损坏，已用现有r30完成本地交叉编译，版本差异在迁移文档中记录。Kotlin2.2.10、AGP8.10.1、Gradle8.11.1，minSdk24 / target35 / compile36。

```sh
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace --locked
cargo deny check licenses
cargo ndk -t arm64-v8a -t armeabi-v7a -P 24 -o android/app/src/main/jniLibs build --release --locked -p iqtvlite-capi
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest -PISOLATED_TEST_PACKAGE=true
```

Windows 使用 `gradlew.bat`。本机缺少 MSVC link.exe，可按命令选择既有 `cargo +stable-x86_64-pc-windows-gnu`，不修改全局默认工具链。

当前 debug 是可调试、启用R8代码收缩及资源收缩的测试变体。APK 同时包含 armeabi-v7a 与 arm64-v8a Rust 库；`scripts/verify_apk.py` 检查 ELF 类型和必需 JNI 符号。调试构建的 R8 不执行发布级优化/混淆，不把它冒充 release。

## 包与签名

正式身份保留 `tv.iqtvlite.app`。`-PISOLATED_TEST_PACKAGE=true` 生成独立的 `tv.iqtvlite.app.validation`，显示“iqtvlite 验证版”和独立版本后缀，使用单独 LAN 端口范围，可与旧包并存。JNI 类始终保持 `tv.iqtvlite.app.RustBridge`。

CI 版本由 run number/commit 派生，也支持 `APP_VERSION_CODE`、`APP_VERSION_NAME` Gradle属性。现装生产包与本轮CI证书不同；CI主包和测试包是同签名匹配对，须取自同一run。不得默认卸载或清数据；采用隔离包验收。

工作流提供可选的稳定签名环境配置。需要维护者安全配置 `ANDROID_SIGNING_KEYSTORE_BASE64`、`ANDROID_SIGNING_STORE_PASSWORD`、`ANDROID_SIGNING_KEY_ALIAS`、`ANDROID_SIGNING_KEY_PASSWORD` 四个 GitHub secrets。没有配置时仍是临时 debug 签名，不能承诺跨CI可升级。仓库不包含任何私钥或签名密码。

## 验收

提供独立 instrumentation APK，覆盖真实 JNI、M3U字段、两种gzip HTTP响应、时区、取消等待、LAN端口共存和实际 Media3 UA/Referer 请求。只有在明确安装测试候选后运行，编译成功不代表这些设备测试已执行。

debug日志默认关闭；设置 `adb shell setprop log.tag.IQTV-METRICS DEBUG` 可收集 prepare→真正渲染首帧的会话日志及decoder/format/drop/rebuffer事件；设WARN关闭。日志不包含完整流地址，READY不当作首帧。

Rust workspace 的 net/player/tvfocus 模块继续存在并有单测，但当前 JNI 不接管 Android 网络、播放器状态或遥控焦点。完整边界、体积对比、证书与剩余风险见迁移和验证文档。

## 许可

Rust workspace 声明 GPL-3.0-or-later。保留 tivimini 源码来源和固定提交；历史研究文档中的 Slint/mpv/FFmpeg 说明属于旧路线，不代表当前 APK 包含这些组件。第三方依赖许可仍通过 CI 的 cargo-deny 审计。
