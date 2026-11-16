# 真机验证报告 — Android TV 盒子实测

> 日期：2026-11-16
> 设备：**TR100-G9**（中国移动的 Android TV 盒子，Android 9，`cucc_guangdong`，1920×1080）
> 连接方式：`adb connect 192.168.2.245:5555`

---

## 1. 结论

**v0.2.0-epg APK 在真实 Android TV 盒子上完成了端到端验证：**

1. ✅ Rust core JNI 加载成功
2. ✅ 两个指定播放列表中的主线路解析成功：**91 个频道 / 8 个分组 / 1 个 EPG 源**
3. ✅ EPG 端点返回裸 gzip（无 Content-Encoding 头）→ App 检测并解压 → **59/69 频道匹配，61 个指南频道，550 个节目块**
4. ✅ 点击频道触发真实直播播放：**CCTV-1 综合** 使用 **VLC UA** 播放，AudioTrack 启动，OMXVDEC 硬件解码器工作

---

## 2. 关键日志证据

### 2.1 启动与 JNI

```
I iqtv-rust: Rust core loaded, version=0.1.0
```

### 2.2 播放列表解析（主线路 https://lbzs.laobaitv.net）

```
I iqtvlite: PLAYLIST_RESULT channels=91 groups=8 epgUrls=[https://epg.laobaitv.net]
```

UI 状态栏显示：`已加载 91 个频道 · 8 个分组 · EPG 源 1`
分组栏显示：`全部 / 体育 / 卫视频道 / 央视频道 / 影视 / 棋牌 / 港澳 / 电竞`
频道列表显示编号 `001, 002, ...`

### 2.3 EPG 加载（https://epg.laobaitv.net）

发现并修复了一个真机才暴露的问题：**EPG 端点直接返回 gzip 文件但没有 `Content-Encoding` 头**
（`curl` 实测首字节 `1f 8b 08 00`）。OkHttp 不会自动解压 → Rust 收到二进制垃圾。

修复：Kotlin 侧检测 gzip magic 并手动 `GZIPInputStream` 解压。

```
I iqtv-rust: detected gzip body, decompressing 403644 bytes
I iqtvlite: EPG_RESULT matched=59 requested=69 guide=61 rows=61 programmes=550
```

→ 59/69 频道匹配（EPG 只覆盖部分频道），6 小时窗口内 550 个节目块。

### 2.4 真实直播播放

点击第一个频道（CCTV-1 综合）：

```
I iqtvlite: playing CCTV-1 综合 url=http://audiovisual.vsd.gehua.net.cn/live/CCTV1HD_1200.m3u8?... ua=VLC/3.0.20 LibVLC/3.0.20
I iqtv-playback: stream UA -> VLC/3.0.20 LibVLC/3.0.20
D AudioTrack: AudioTrack(0xa9488000)::start
D OMXVDEC: set_config_index_config_operating_rate: set native rate 0x190000
```

→ **按频道 UA 生效**（这个流用 TiviMate UA 返回 403，只有 VLC UA 返回 200），
音频轨道启动，硬件视频解码器（OMXVDEC）工作。
UI 状态栏显示：`▶ CCTV-1 综合`。

> 附：Android 9 上 Media3 1.11 会打一条 `Choreographer$VsyncCallback` 不可用的 warning，
> 这是已知的 API 33+ 类在旧设备上的优雅降级（Media3 回退到非 VSync 路径），不影响播放。

---

## 3. 第二个播放列表 URL 的结论

`http://iptv.laobaitv.net` **不是播放列表**，是带 `?isp=&region=&city=&ip=&port=` 参数的 IPTV 配置查询页面：
- 返回 35 KB HTML，页面里**没有直接的 .m3u 链接**
- 原来的正则会误匹配 Google Fonts 的 URL（含 "api"）→ 已修复
- 找不到真实 M3U 时**自动回退到主线路** `https://lbzs.laobaitv.net`（同源）
- 空播放列表会报错而不是静默显示空白

---

## 4. 真机验证中修复的 3 个 Bug

| # | Bug | 症状 | 修复 |
|---|---|---|---|
| 1 | JNI 符号名用错 `$` 转义（`_00024`） | App 启动崩溃 `UnsatisfiedLinkError` | 改用非转义名 `Java_tv_iqtvlite_app_RustBridge_*` |
| 2 | 全局硬编码 TiviMate UA | CCTV1/2 流 403 | 解析播放列表里的 `http-user-agent="VLC"`，按频道映射完整 UA，OkHttp interceptor 每请求读取 |
| 3 | EPG 裸 gzip 未解压 | EPG 匹配 0 个频道 | Kotlin 检测 gzip magic 并手动解压 |

> 这 3 个问题在 145 个单元测试和 CI 里都不会暴露 —— 只有真机端到端验证才能发现。

---

## 5. 当前完成度

| TiviMate 功能 | 状态 |
|---|---|
| 频道列表 / 分组 / 搜索 | ✅ 真机验证 |
| EPG 时间轴网格（招牌功能） | ✅ 真机验证 550 节目块 |
| 直播播放（Media3 / 硬解） | ✅ 真机验证 OMXVDEC + AudioTrack |
| 按频道 UA / 备用线路 | ✅ 真机验证 VLC UA 生效 |
| 多 EPG 源 | ✅ 已实现（优先级合并 + UI 源管理），见 `docs/EPG-GRID-REPORT.md` |
| DVR 录制 / 多画面 / Catch-up UI | ✅ 已实现并真机验证（有效 TS 文件 / 四路同播 / 回看 URL 生成） |
| 断点续播 | ⬜ 未做 |
| Slint UI 迁移 | ❌ 不 pursue —— 用户决策 ADR-011 固定为 Kotlin UI |

---

## 6. 复现命令

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb connect 192.168.2.245:5555
& $adb install -r releases\iqtvlite-0.2.0-epg.apk
& $adb shell am start -n tv.iqtvlite.app/.MainActivity
& $adb logcat -d | Select-String "PLAYLIST_RESULT|EPG_RESULT|playing "
```
