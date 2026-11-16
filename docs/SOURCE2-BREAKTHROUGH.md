# 第二个播放列表 URL 的完整解决

> 日期：2026-11-17
> 用户提示：`iptv.laobaitv.net` 作为标准播放地址会自动载入播放列表。

## 1. 诊断过程

之前我们把 `http://iptv.laobaitv.net` 当成"配置页面无法使用"，实际是漏掉了它的关键参数。

### 1.1 UA 阻断
实测不同 User-Agent 访问该域名：

| UA | 结果 |
|---|---|
| `TiviMate/2.1.5` | ❌ 失败 |
| `VLC/3.0.20` | ❌ 失败 |
| `iqtvlite` | ❌ 失败 |
| `Mozilla/5.0` | ✅ 200 text/html |

→ **该站拒绝非浏览器 UA**。我们原来播放列表下载用的是 TiviMate UA → 第二个源必然失败。

### 1.2 真正的播放列表入口
分析 HTML 里的链接发现：
```html
<a href="?m3u=true" download="playlist.m3u">保存为 m3u 文件</a>
```

实测 `http://iptv.laobaitv.net?m3u=true`：
```
200 text/plain 30218 bytes
#EXTM3U x-tvg-url="https://epg.laobaitv.net"
#EXTINF:-1 tvg-id="CCTV1" ...,CCTV-1 综合
http://192.168.2.1:4022/rtp/239.3.1.129:8008    ← 默认配置是内网组播地址
```

## 2. 修复

1. **播放列表/EPG 下载统一用浏览器 UA**（流播放仍按频道用 VLC UA）
2. 检测到 HTML 配置页时，自动追加 `?m3u=true` 请求真实 M3U

## 3. 真机验证（TR100-G9）

切换到第二个源后：
```
I iqtvlite: M3U_URL http://iptv.laobaitv.net?m3u=true
I iqtvlite: PLAYLIST_RESULT channels=156 groups=13 epgUrls=[https://epg.laobaitv.net]
I iqtvlite: EPG_MERGE sources=1 stats=[{"channels":155,"programmes":1148}]
I iqtvlite: EPG_RESULT matched=72 requested=141 guide=76 rows=76 programmes=704
```

→ **两个指定播放列表都已真正可用**。注意第二个源的默认流是内网组播地址（`192.168.2.1:4022/rtp/...`），
在当前测试网络下可能无法直接播放，但播放列表本身 100% 正确载入（156 频道 / 13 分组）。

## 4. TiviMate 参考
安装了用户提供的 TiviMate v2.1.5（9.65 MB）到真机研究其交互：
- 采用 Android TV Leanback `GuidedAction`（左侧标题 + 右侧动作列表）
- 设置向导："节目列表地址 / 粘贴自剪贴板 / 本地文件 / Xtream Codes"
- EPG 时间轴在首页背景可见
- 我们使用 Kotlin 原生 Views 但借鉴其 TV 设计原则（10-foot UI / D-pad 明显焦点反馈）

## 5. 附带能力
- LoadControl 低延迟调优（1.5s/6s/500ms/1000ms）
- 换台耗时埋点 `ZAP_MS`（首帧 - 发起播放），用于后续性能优化
