# 1.4 — Apple TV 海报墙浏览器 + 冷启动并行优化 + 焦点配色修正

> 2026-11-17

## 1. tvOS 风格海报墙浏览器（对标 Apple TV）

新增可切换的**4 列海报卡网格**（控制行「海报」按钮 ↔「列表」）。

- `PosterAdapter.kt` + `item_poster.xml`：分层卡片
  - 背景层（渐变光晕，聚焦点亮）+ Logo 前景层 + 文字层
  - **视差近似**：聚焦时卡片整体放大 15%（stateListAnimator），Logo 前景层额外放大 18% + 上浮 10dp，文字层上移 —— 三层不同位移营造 Apple TV 纵深感
  - 圆角卡片（18dp）+ 半透明白描边 + 蓝色频道编号 + 当前节目
- 与列表模式共用数据（submit / nowPlaying / 选中态同步）；点海报卡即播放
- PiP 预览跟随海报焦点

真机验证：4×N 海报网格正常渲染，点击 CCTV-3 综艺卡 → `playing CCTV-3 综艺 / ZAP_MS 835`。

## 2. 冷启动性能分析与并行优化

### 2.1 分阶段埋点（COLD_PHASE）
定位到冷启动耗时分布（TR100-G9）：
```
setContentView        +2197ms   ← 进程启动/布局 inflate（应用代码无法消除）
views+findViewById      +23ms
playerInit             +711ms   ← ExoPlayer 构造
listeners+epgAdapter    +27ms
loadFromCache          +301ms   ← M3U JNI 解析
autoPlayLastChannel    +118ms
AUTOPLAY 总计 ~3372ms
```

### 2.2 并行优化
`startCachePrefetch(0)` 在 setContentView 后立即用 `lifecycleScope.async(Dispatchers.Default)`
后台解析缓存，与 playerInit 并行；`loadFromCache` 改为 `runBlocking { prefetched.await() }`。

效果：`loadFromCache` 阶段 **301ms → 166ms**（省 ~135ms）。
AUTOPLAY 稳定在 ~3.4s（进程启动 2.2s 是设备/系统开销，属硬下限）。

### 2.3 换台
本次热换台实测 ZAP_MS 835ms（Migu 流）；冷换台 1.7–2.4s。

## 3. 焦点配色修正（用户"不要丑"）
AppCompat 默认 `backgroundTint` 导致分组/按钮聚焦时出现**刺眼黄底**。
修复：对所有工具栏 Button + 分组 chip 设 `backgroundTintList = null`，
焦点改用克制白色描边（tvOS 风格）。真机截图确认黄底消失。

## 4. 未做（遵守用户禁令）
- ❌ 录制 / 定时录制 / 本地大文件写入 —— 保持彻底删除

## 5. 测试
167 Rust 测试通过，clippy -D warnings 零告警，Kotlin assembleDebug 成功。
真机逐项验证：海报墙渲染 / 点击播放 / PiP 跟随 / 焦点配色 / 冷启动分阶段 / 并行优化。
