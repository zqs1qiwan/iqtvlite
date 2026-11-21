# iqtv 项目移植交接

更新时间：2026-11-18（Asia/Shanghai）

## 当前目标与用户明确要求

老白要求直接以 tivimini 的 UI/UX 为底座，之后再做二次改进。当前 IPTV 测试播放列表只用 `https://iptv.laobaitv.net`，EPG 测试地址为 `https://epg.laobaitv.net`。扫码配置必须保留；两个地址只是测试用预填，不限制其他源。

本目录 `/opt/data/projects/iqtv` 是本次开发的可见主工作副本；原始 `/opt/data/projects/tivimini` 留作参考副本。当前按用户最新指令继续开发。

## 工作区与设备

- 当前项目：`/opt/data/projects/iqtv`
- 原始 UI/UX 参考与来源仓库工作副本：`/opt/data/projects/tivimini`
- 技术/网络参考项目：`/opt/data/projects/iqtvlite`
- 目标机：TR100-G9，ADB `192.168.2.245:5555`，server port 5038。
- 可交付 debug APK：`artifacts/iqtv-debug-2026-11-18.apk`（22,910,016 bytes，SHA-256 `91d1e5086e024f0709691bf017af903339d754323b6dd8559a73548743ec88f1`）；构建输出：`app/build/outputs/apk/debug/app-debug.apk`。
- 包名：`com.cjlhll.iptv`
- 设备上安装的是此前的本地 debug 构建，不是本次归档目录生成的 APK；归档 APK 尚未装机回归。

## 已落地的未提交改动

当前所有改动仍在工作区，**没有 commit、push 或 Release**。重点文件：

- `app/src/main/java/com/cjlhll/iptv/MainActivity.kt`：空 Prefs 源地址时预填 `https://iptv.laobaitv.net`、`https://epg.laobaitv.net`；二维码/局域网推源仍保留。
- `app/src/main/java/com/cjlhll/iptv/PlayerActivity.kt`：增加数字键缓冲、1.2 秒等待后按编号跳转；新增频道级 UA 回调；播放数据源改为 OkHttpDataSource。
- `app/src/main/java/com/cjlhll/iptv/Channel.kt`、`M3uParser.kt`：Channel 增加 userAgent；从 EXTINF 解析 `http-user-agent`，`VLC` 映射为 `VLC/3.0.20 LibVLC/3.0.20`。
- `app/src/main/java/com/cjlhll/iptv/NetUtil.kt`：IPv4 优先 DNS 与共享 OkHttpClient。
- `app/src/main/java/com/cjlhll/iptv/EpgRepository.kt`：EPG 下载改用 IPv4 优先客户端。已有 `parseFile()` 会检查 gzip magic 并解压；裸 gzip 暂未在设备对实际 EPG 做端到端验证。
- `app/build.gradle.kts`、`gradle/libs.versions.toml`：增加 Media3 OkHttp DataSource 依赖。
- `app/src/test/java/com/cjlhll/iptv/M3uParserUserAgentTest.kt`：两条 UA parser 单测；移除解析期 Android `Log.d` 后，本地 JVM 单测现已通过。
- `gradlew` 在本地被 `chmod +x`，Git 状态显示文件变更；这看起来是文件权限位变化，不是脚本内容修改。仓库根目录有忽略的 `local.properties` 指向本机 Android SDK。

查看全量改动：`cd /opt/data/projects/tivimini && git status --short && git diff`。

## 实际验证记录

### 构建

构建环境：

```bash
export JAVA_HOME=/opt/data/rea-tvbox/tools/jdk-17.0.20.1+1
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/opt/data/cache/android
export ANDROID_SDK_ROOT=/opt/data/cache/android
export GRADLE_OPTS="-Dhttp.proxyHost=192.168.2.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=192.168.2.1 -Dhttps.proxyPort=7890"
```

`./gradlew :app:compileDebugKotlin --no-daemon`, `./gradlew :app:testDebugUnitTest --no-daemon` 和 `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon` 均通过。最后一次组合构建在 `/opt/data/projects/iqtv` 执行，**BUILD SUCCESSFUL in 1m 13s**；APK 已复制到 `artifacts/iqtv-debug-2026-11-18.apk`。新增的 UA parser 两条测试通过。此前测试因解析期 `Log.d()` 未 mock 失败；移除纯解析器中的 Android debug 日志后已解决。构建有一条既存 `LocalLifecycleOwner` deprecated 警告。

### 真机配置和数字跳台

- 清除 tivimini 数据后启动，首屏的二维码仍可见；实测直播源预填 `https://iptv.laobaitv.net`，EPG 预填 `https://epg.laobaitv.net`。
- 曾出现“发现新版本”对话框；左键选“取消”后可以配置。
- 电视遥控器上通过 `KEYCODE_TAB` 在两个源字段和“保存并播放”按钮间移动，再 `KEYCODE_ENTER`，成功进入 `PlayerActivity`，首频道为 CCTV-1。
- 真机连续输入遥控器数字键 `112`，屏幕出现“跳转频道 112…”反馈，等待超时后信息条变为 `112. 财富天下`。因此数字输入缓冲和频道选择的 UI 路径已端到端触发。
- 这个 112 频道的播放 URL 是 `http://192.168.2.1:4022/rtp/239.3.1.53:9136`。ExoPlayer 日志报 HTTP 503。主机用 VLC UA 和 TiviMate UA 分别 curl 同一 URL 都得到 HTTP 503/52 bytes；看起来是流端当前不可用，不足以判断播放器的频道 UA 接线是否正确。
- CCTV-1 的 URL `http://192.168.2.1:4022/rtp/239.3.1.129:8008` curl 返回 HTTP 200，8 秒收到约 8.7 MB 数据。可用它验证真实首帧。
- 真机截图已归档在 `docs/evidence/2026-11-18/`：`tivimini_config_final2.png`（QR + 默认源）、`tivimini_info.png`（CCTV-1 / 1920×1080 信息条）、`tivimini_num112_buffer.png`（数字缓冲）、`tivimini_num112_final.png`（112 财富天下）、`tivimini_after_import.png`（纯黑 SurfaceView 截图；不能据此断定无首帧）。

### 播放列表实时响应

2026-11-18 从开发主机 curl：

- `https://iptv.laobaitv.net` 返回 HTTP 200，`application/x-mpegURL`，当时 `num_redirects=0`。
- 下载内容 30,218 bytes，156 条 `#EXTINF`，包含一条 `http-user-agent="VLC"` 属性。
- 不要照搬旧记录里“固定 302 到 r2”的假设；每次按实时响应验证重定向。
- 156 是 M3U 条目数，ChannelGrouper 是否去重、频道号如何对应列表序号需接手者确认。真机当次确实把输入 112 选为 `财富天下`，但尚未验证前后频道序号映射及越界数字反馈。

## 当前主要风险/未完成项

1. **工作区未提交**：本目录来自独立 clone，保留原始 Git 历史与 GitHub origin；本次改动仍未 commit/push。
2. **数字跳台选择成功，但 112 号流服务端 503**：真机输入 `112` 后显示 `112. 财富天下`；该流在主机上用 VLC/TiviMate UA 均回 503。用 CCTV-1验证 ExoPlayer READY、首帧、稳定播放、错误恢复与换台行为。
3. **频道 UA 尚无请求级证据**：确认 VLC 条目的真实 Media3 请求携带 `VLC/3.0.20 LibVLC/3.0.20`，并确认默认 UA 不被污染。
4. **IPv4 逻辑只构建过**：确认目标机 DNS 结果为 IPv4，并验证列表、EPG、Media3 流均使用该网络路径。
5. **EPG gzip 未实测**：验证裸 gzip 下载、缓存和 XMLTV parse 的完整通路。
6. **交互/视觉尚未完成验收**：目前是 tivimini 原 UI 基线加少量兼容改动，不代表已满足抽屉尺寸、设置/节目单分离、透明台标、换台信息、刷新/按键设置和品牌统一验收。
7. **扫码推送只确认 QR 显示**：还需手机实际推送覆盖源并启动播放。
8. **CI/Release 尚未运行/发布**：当前有本地 debug APK；不能称为正式交付。

## 建议接手顺序

1. 查看 `git status`、`git diff`；保留当前未提交差异，确认后再分批提交。
2. 真机用 CCTV-1验证真实首帧和稳定播放，同时检查频道 UA 请求。
3. 明确“频道编号”按原始 M3U 序号还是分组去重后的显示序号；测试 `1`、`112`、最大有效号、无效号。
4. 端到端验证手机扫码、EPG gzip、IPv4、列表/EPG刷新与缓存。
5. 按 PRD 移植与改进交互，再逐项验证频道台标、节目单、设置抽屉和品牌资产。
6. 全部验收后再运行 CI/Release；当前 debug APK 不代表正式交付。

## 文档

- 可见主 PRD：`docs/PRD.md`
- 原项目 PRD 位置（隐藏目录，保留作兼容）：`.trae/documents/PRD_AndroidTV_IPTV_播放器抽屉需求.md`
- 本交接：`HANDOFF.md`
- tivimini 原始说明：`README.md`
- Android 项目构建约定：`AGENTS.md`
