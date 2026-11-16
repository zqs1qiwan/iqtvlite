## iqtvlite 0.2.0 — EPG 时间轴网格 + 真机验证

第二版测试 APK，新增 TiviMate 招牌功能 **EPG 时间轴网格**，修复多个真机才暴露的问题，并**在真实 Android TV 盒子上完成端到端验证**。

### ✅ 真机验证（TR100-G9，Android 9，1920×1080）

```
I iqtv-rust: Rust core loaded, version=0.1.0
I iqtvlite: PLAYLIST_RESULT channels=91 groups=8 epgUrls=[https://epg.laobaitv.net]
I iqtv-rust: detected gzip body, decompressing 403644 bytes
I iqtvlite: EPG_RESULT matched=59 requested=69 guide=61 rows=61 programmes=550
I iqtvlite: playing CCTV-1 综合 url=.../CCTV1HD_1200.m3u8 ... ua=VLC/3.0.20 LibVLC/3.0.20
D AudioTrack: ::start
D OMXVDEC: set native rate ...
```

→ 91 频道解析成功；EPG 550 个节目块；CCTV-1 使用 VLC UA 播放，音频轨道启动，硬件解码器工作。
详见 `docs/DEVICE-VERIFICATION.md`。

### 新增：EPG 网格

- 顶部切换 **EPG** tab，加载播放列表头部的 `x-tvg-url`（`https://epg.laobaitv.net`）
- 每行 = 频道 + 节目时间轴，节目块宽度按真实时长（每 30 分钟一片），当前节目显示进度条
- 点击节目直接跳转到对应频道播放
- 数据由 **Rust core** 流式解析 3.7 MB XMLTV 生成（145 个测试验证）

### 修复的真机问题

| 问题 | 修复 |
|---|---|
| JNI 符号名 `$` 转义错误 → 启动崩溃 | 改用 `Java_tv_iqtvlite_app_RustBridge_*` |
| 全局 TiviMate UA → CCTV 流 403 | 解析 `http-user-agent="VLC"`，按频道映射完整 UA |
| EPG 裸 gzip（无 Content-Encoding 头）→ 0 频道匹配 | 检测 magic `1f8b` 并手动解压 |
| 第二个 URL 是配置页不是播放列表 | 加固提取逻辑，失败时回退主线路 |

### 验证

- 145 个 Rust 测试通过，clippy -D warnings 零告警
- 两个 GitHub Actions 工作流均 success
- APK 11.73 MB，arm64-v8a + armeabi-v7a 双 ABI

### 已知限制

- DVR / 多画面 / Catch-up UI 尚未实现
- UI 仍为 Kotlin 原生 Views（Slint 迁移待 Surface 合成方案 spike）

完整技术细节：`docs/EPG-GRID-REPORT.md`、`docs/DEVICE-VERIFICATION.md`
