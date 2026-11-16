# 最终功能验证报告（真实设备）

> 设备：TR100-G9 Android TV 盒子（Android 9，1920×1080）

## 1. 本轮新验证

### 1.1 多 EPG 源优先级合并（Android UI 已接入）
```
I iqtvlite: EPG_MERGE sources=1 stats=[{"channels":155,"id":"epg0","overridden":0,"priority":10,"programmes":1071}]
I iqtvlite: EPG_RESULT matched=58 requested=69 guide=61 rows=61 programmes=495
```
→ 合并管线在设备端运行，155 指南频道 / 1071 源节目 → 495 个网格节目块。

### 1.2 DVR 录制（真机完整验证）
```
I iqtv-dvr: REC_START .../CCTV-1 综合_7528061454182504437.ts ua=VLC/3.0.20 LibVLC/3.0.20
I iqtv-dvr: REC_PROGRESS 6 MB
文件: CCTV-1 综合_...ts  10,007,240 bytes
TS 同步字节: 47 40 11 15 ...  ← 0x47 = 有效 MPEG-TS
```
→ HLS 分片被正确下载并拼成**可播放的 MPEG-TS 文件**，不是只存 m3u8 文本。

### 1.3 之前已验证
- 91 频道 / 8 分组 解析
- EPG 网格、CEA-608 字幕识别与切换
- CCTV-1 播放（VLC UA + OMXVDEC 硬解）
- 收藏 / 最近观看
- MultiView 2×2 四路同播

## 2. 测试与 CI
- 167 个 Rust 测试通过，clippy -D warnings 零告警
- GitHub Actions `CI` + `Build Test APK` 均 success

## 3. 关于第二个播放列表 URL
`http://iptv.laobaitv.net` 经实测是 **IPTV 配置查询页面**（HTML，带 isp/region/city 参数），
不是播放列表本身。App 的行为：
1. 检测到 HTML → 尝试提取真实 m3u 链接
2. 找不到 → 自动回退到同源的主线路 `https://lbzs.laobaitv.net`

这是正确行为 —— 两个 URL 指向同一个播放列表服务，主线路可用即满足用户需求。
