# 测试 APK — 交付报告（持续更新）

> 最新更新：2026-11-16
> 目标：按推荐路线（轻量 / 稳定 / 高性能，最终对齐 TiviMate 全功能）交付**可安装、可播放指定播放列表**的测试 APK。

---

## 1. 交付物

| 项 | 值 |
|---|---|
| **GitHub 仓库** | https://github.com/zqs1qiwan/iqtvlite |
| **最新 Release** | https://github.com/zqs1qiwan/iqtvlite/releases/tag/v0.3.0-features |
| **APK 文件名** | `iqtvlite-0.3.0-features.apk` |
| **体积** | 12.07 MB |
| **包名** | `tv.iqtvlite.app` |
| **ABI** | arm64-v8a + armeabi-v7a（均含 Rust core `.so`） |
| **minSdk / targetSdk** | 26 / 35 |
| **启动器** | Leanback（Android TV）+ Launcher |
| **CI 状态** | ✅ `CI` success · ✅ `Build Test APK` success（最后一次提交） |
| **测试** | 148 passed，clippy -D warnings 零告警 |

---

## 2. 指定的两个播放列表

| # | URL | 实测结果 |
|---|---|---|
| 1 | `https://lbzs.laobaitv.net` | ✅ 真实 M3U（实际 302 → `r2.laobaitv.net/public.m3u`），**真机解析出 91 个频道 / 8 个分组 / 1 个 EPG 源** |
| 2 | `http://iptv.laobaitv.net` | ⚠️ 实际是 **IPTV 配置查询页**（HTML，带 isp/region/city 参数），没有直接 M3U 链接 → App 加固检测后**自动回退到主线路** |

---

## 3. 已完成功能（真机验证）

| 功能 | 状态 | 证据 |
|---|---|---|
| 频道列表 + logo + 分组 + 编号 | ✅ | UI 显示 `001, 002, ...` 与 8 个分组 |
| 搜索 / 分组过滤 / 切源 / 下拉刷新 | ✅ | UI 交互 |
| **EPG 时间轴网格**（TiviMate 招牌功能） | ✅ | `EPG_RESULT matched=59 requested=69 guide=61 rows=61 programmes=550` |
| 直播播放（Media3 + 硬解） | ✅ | `AudioTrack::start` + `OMXVDEC set native rate` |
| 按频道 UA（VLC/TiviMate 映射） | ✅ | `playing CCTV-1 ... ua=VLC/3.0.20` + 播放成功 |
| **★收藏 / 最近观看** | ✅ | `WATCHED ... recents=1`，UI 分组栏可见 |
| **Catch-up 回看**（长按 EPG 节目） | ✅ | Rust 桥接 3 个单测；两个指定源无 catchup 元数据故提示不支持 |
| 低延迟缓冲 + 后台播放 + 屏幕常亮 | ✅ | 代码实现 |
| Rust core（解析/EPG/回退决策） | ✅ | 148 个测试 |
| **ADR-008 Slint Surface spike** | ✅ | 源码级确认 Slint Android 窗口不透明，维持 P2 路线；见 `docs/spikes/ADR-008-slint-surface.md` |

详细真机证据见 `docs/DEVICE-VERIFICATION.md`。

---

## 4. 真机验证中发现并修复的 3 个 Bug

1. **JNI 符号名 `$` 转义错误** → App 启动即崩溃 `UnsatisfiedLinkError`。修复：改用 `Java_tv_iqtvlite_app_RustBridge_*`。
2. **全局硬编码 TiviMate UA** → CCTV1/2 流返回 403。修复：解析播放列表的 `http-user-agent="VLC"`，按频道映射完整 UA，OkHttp interceptor 每请求读取。
3. **EPG 端点是裸 gzip（无 Content-Encoding 头）** → EPG 匹配 0 频道。修复：Kotlin 检测 magic `1f8b` 并手动解压。

---

## 5. 当前状态 / 后续路线

> 2026-11-17 更新：以下功能已实现并在真机验证，不再是“限制”。

| 项 | 现状 |
|---|---|
| DVR 录制 | ✅ 已实现，真机验证生成有效 MPEG-TS 文件（10 MB，0x47 同步字节） |
| 多画面 2×2 | ✅ 已实现并真机验证四路同播、独立音频焦点 |
| 多 EPG 源叠加 | ✅ Rust 优先级合并 + JNI + Android UI 源管理（添加/优先级/删除） |
| 家长控制 / 备份恢复 / 提醒 / 自定义排序分组 | ✅ 已实现（提醒用 AlarmManager 系统级调度） |
| UI 技术栈 | ✅ **最终确定为 Kotlin 原生 Views**（用户决策 ADR-011），不再考虑 Slint fork |

### 实测性能（TR100-G9 Android TV 盒子）

| 指标 | 实测 |
|---|---|
| 热启动（缓存命中） | **3.26 s**（首次无缓存 35.8 s） |
| 换台首帧 ZAP_MS | 冷 1932 ms → 654 ms → 346 ms（avg 977 ms，样本 3） |

### 仍可继续优化

- 换台首帧进一步降低（当前 warm ~350–650 ms）
- 自动帧率 / 音频直通 / 外部播放器选项
- 定时录制（当前仅即时录制）
| 无 mpv 兜底（lite 变体） | 长尾编码按需加入 |

---

## 6. 复现命令

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb connect <TV_IP>:5555
& $adb install -r releases\iqtvlite-0.2.0-epg.apk
& $adb shell am start -n tv.iqtvlite.app/.MainActivity
& $adb logcat -d | Select-String "PLAYLIST_RESULT|EPG_RESULT|playing "
```
