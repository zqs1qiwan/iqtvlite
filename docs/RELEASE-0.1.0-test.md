## iqtvlite 第一版测试 APK

**功能**：Android TV / Google TV IPTV 播放器，Rust core (JNI) + Media3 ExoPlayer。

**默认播放列表**（启动时加载，可在下拉框切换）：
- https://lbzs.laobaitv.net  — 主线路（91 个 EXTINF，90 个有效流，实测）
- http://iptv.laobaitv.net   — 备用线路（HTML 落地页，自动提取真实播放列表）

**已验证**：
- 134 个 Rust 测试通过，clippy -D warnings 零告警
- APK 11.67 MB，含 arm64-v8a + armeabi-v7a 双 ABI 的 Rust core
- minSdk 26，Leanback 启动器（Android TV 可用）

**操作**：遥控器方向键选台/OK 播放，CH+/CH- 快速换台，下拉框切源，搜索框过滤。

**当前限制（第一版）**：应用内 EPG 网格尚未实现（已解析 x-tvg-url 但 UI 未渲染）；UI 用 Kotlin 原生 Views（Slint 迁移待 Surface 合成方案 spike）。DVR/多画面/catch-up 待后续版本。
