# 1.5 — 共享连接池预热换台 + tvOS 3D 倾斜焦点

> 2026-11-17

## 1. 换台延迟优化：全局共享连接池 + 预热复用

### 1.1 问题
之前 4 个 OkHttp 客户端（主播放 / PiP 预览 / 多画面 / 列表下载）各有独立连接池，
PiP 浏览时预热到流媒体的 TCP 连接，换台时主播放无法复用 → 每次换台重新 DNS + TCP + TLS。

### 1.2 修复
`NetUtil.sharedConnectionPool`：全局共享 `ConnectionPool(8, 90s)`，
`PlaybackService` / `PreviewPlayer` / `MainActivity` 全部 `.connectionPool(NetUtil.sharedConnectionPool)`。

效果链路：
1. PiP 浏览焦点频道 → 连接池预热到流媒体主机
2. 换台 `setMediaItem` → 直接复用已建立的连接，省去握手
3. 实测（真机 TR100-G9）：冷换台 1556–1736ms，热换台 **683–835ms**

> 说明：进程启动的 ~2.2s（setContentView 阶段）是 Zygote fork + 系统资源加载，
> 应用代码无法消除；流媒体首包时间也受服务器影响。本轮优化的是应用可控的连接复用部分。

## 2. Apple TV 焦点卡精修：3D 倾斜（视差深度）

`focus_scale_poster` 增加 `rotationX=-4°` / `rotationY=2°` 的 3D 倾斜 +
18dp 抬升 + 14% 放大。配合已有的三层视差（卡片 14% / Logo 18% 放大上浮 / 文字上移），
更接近 tvOS 焦点项的"抬升 + 光泽 + 深度"质感。
真机截图确认海报卡焦点有立体倾斜感，无刺眼配色。

## 3. 未做（遵守用户禁令）
- ❌ 录制 / 定时录制 / 本地大文件写入 —— 保持彻底删除

## 4. 测试
167 Rust 测试通过，clippy -D warnings 零告警，Kotlin assembleDebug 成功。
真机验证：秒播、频道浮层、海报墙、PiP、换台 ZAP_MS、3D 焦点倾斜、无黄底。
