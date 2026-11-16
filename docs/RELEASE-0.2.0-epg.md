## iqtvlite 0.2.0 — EPG 时间轴网格

第二版测试 APK，新增 TiviMate 招牌功能 **EPG 时间轴网格**，并修复一个影响真实播放的关键问题。

### 新增：EPG 网格

- 顶部切换到 **EPG** tab，加载播放列表头部的 `x-tvg-url`（`https://epg.laobaitv.net`）
- 每行 = 频道 + 节目时间轴，节目块宽度按真实时长（每 30 分钟一片），正在播出的节目显示进度条
- 点击节目直接跳转到对应频道播放
- EPG 数据由 **Rust core** 流式解析 3.7 MB XMLTV 并生成网格（145 个测试验证）

### 修复：按频道 User-Agent

实测发现老白直播的 CCTV1/CCTV2 等频道：
- 用 TiviMate UA → **403 Forbidden**
- 用 VLC UA → **200 OK** ✅

播放列表用 `http-user-agent="VLC"` / `|User-Agent=VLC` 标记了这些频道。
现在 App 会读取该标记，映射成完整 VLC UA，并**按频道**应用到流请求（OkHttp interceptor 每请求读取当前 UA）。

### 验证

- 145 个 Rust 测试通过，clippy -D warnings 零告警
- 两个 GitHub Actions 工作流均 success
- 真实 EPG 端到端验证：`CCTV1/CCTV5/CCTV9` 匹配成功，6 小时窗口内 26 个节目，进度条正确

### 已知限制

- 真机/模拟器播放验证仍在进行（Android 模拟器系统镜像下载中）
- DVR / 多画面 / Catch-up UI 尚未实现
- UI 仍为 Kotlin 原生 Views（Slint 迁移待 Surface 合成方案 spike）

完整技术细节：`docs/EPG-GRID-REPORT.md`
