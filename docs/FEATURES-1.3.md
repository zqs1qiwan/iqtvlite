# 1.3 — PiP 频道预览 + 频道号跳转 + 分组管理 + Apple TV 焦点卡

> 2026-11-17

## 1. 本轮新增（均不碰本地存储录制）

### 1.1 PiP 频道预览（TiviMate 标志性功能）
浏览频道列表时，右下角小窗**静音预览当前焦点频道**，换焦点自动换流。

- `PreviewPlayer.kt`：单个 ExoPlayer 复用（不随焦点反复创建），静音、不抢音频焦点、低缓冲(1s/3s)
- 焦点防抖：停留 350ms 才加载，快速划过不狂拉流
- 浮层关闭即 `hide()` 停播、`onDestroy` 即 `release()`，不占解码器
- 可在偏好里关（`pip_enabled`，默认开）
- 真机日志验证：
  ```
  PIP_FOCUS CCTV-1 综合 → preview CCTV-1 综合
  PIP_FOCUS CCTV-2 财经 → preview CCTV-2 财经
  ```
- 真机截图：右下角圆角小窗播放焦点频道实时画面 + 频道名标签

### 1.2 频道号跳转（TiviMate 功能）
满屏播放时按数字键（如 5、0 → 50），1.5s 内组合成频道号并 `zapTo` 直跳。
真机验证：输入 50 → `playing ... ZAP_MS 638`。

### 1.3 分组隐藏 / 重命名（TiviMate 功能）
频道列表分组 chip **长按** → 菜单「隐藏此分组 / 重命名分组」。
- 隐藏分组从 chip 行移除；重命名仅改显示，过滤仍用原始组名
- 持久化到 SharedPreferences，纳入备份/恢复
- 真机验证：长按「卫视频道」→ 隐藏 → `hidden_groups=["卫视频道"]`

### 1.4 Apple TV 焦点卡强化
- tvOS 焦点放大从 5% 提升到 **15%** + 16dp 抬升阴影（`focus_scale_poster`）
- stateListAnimator 挂在每个 item 上，由系统按焦点状态自动驱动

## 2. 未做（遵守用户禁令）
- ❌ 录制 / 定时录制 / 本地大文件写入 —— 已在上个版本彻底删除，本轮未触碰

## 3. 测试
167 个 Rust 测试通过，clippy -D warnings 零告警，Kotlin assembleDebug 成功。
真机 TR100-G9 逐项验证：PiP 换流、数字跳转、分组隐藏持久化、焦点卡放大。

## 4. 完整功能清单（真机验证）
✅ 满屏播放 + 按需浮层（OK/左右/返回） · 秒播（3.2s）
✅ PiP 频道预览 · 数字频道跳转 · 分组隐藏/重命名
✅ 两个播放列表 · EPG 网格 + 多源合并 · CEA-608 字幕 · 收藏/最近
✅ MultiView 2×2 · 家长控制 · 备份恢复 · AlarmManager 提醒
✅ AFR/音频直通/外部播放器设置 · Apple TV 风格 UI
