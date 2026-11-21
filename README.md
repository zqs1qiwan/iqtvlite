# iqtv — Android TV IPTV Player

这是本次接手开发的可运行项目目录，基于 tivimini 源码与 Jetpack Compose for TV。包名暂为 `com.cjlhll.iptv`，保留手机扫码推源与局域网配置。

## 项目状态

- 项目路径：`/opt/data/projects/iqtv`
- 源码基线：tivimini，原始仓库 `https://github.com/cjlhll/tivimini`
- 本目录包含当前未提交工作副本、测试资源、设计资料和真机验证证据。
- 尚未发布 Release；构建状态、待办与实际真机结果见 [`HANDOFF.md`](HANDOFF.md)。

## 文档入口

- [PRD 与验收标准](docs/PRD.md)
- [开发交接与当前状态](HANDOFF.md)
- [文档、设计资料和证据索引](docs/README.md)
- [Android 开发规范](AGENTS.md)

## 测试资源

- `testdata/iptv-test.m3u`：2026-11-18 从测试端点下载的 M3U 快照（156 条）。
- `app/src/main/res/`：应用图标、banner、频道 logo 占位及主题资源。
- `screenshot/`：原 tivimini UI 截图。
- `docs/evidence/2026-11-18/`：本次真机配置、频道数字跳转和播放器截图。
- `e.xml`：原仓库 XMLTV 样例。

测试源地址只用于开发验证；二维码/手机推送配置必须保留，默认地址不可硬编码成单源限制。

## 构建与测试

```bash
export JAVA_HOME=/opt/data/rea-tvbox/tools/jdk-17.0.20.1+1
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/opt/data/cache/android
export ANDROID_SDK_ROOT=/opt/data/cache/android
export GRADLE_OPTS="-Dhttp.proxyHost=192.168.2.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=192.168.2.1 -Dhttps.proxyPort=7890"

./gradlew :app:assembleDebug --no-daemon
./gradlew :app:testDebugUnitTest --no-daemon
```

项目副本的单测和 debug 构建均通过；`artifacts/iqtv-debug-2026-11-18.apk` 与当前归档源码对应。该 APK 尚未安装到目标机做最终回归；不要把当前 debug 包称为 Release。

## 目录说明

- `app/`：Android 应用源码、资源、测试及签名配置。
- `docs/`：PRD、架构/页面/解析设计、归档资料和验证截图。
- `testdata/`：播放列表测试快照。
- `gradle/`：Gradle wrapper 与版本目录。
- `HANDOFF.md`：工作区差异、实测证据、失败项和建议接手顺序。
