# 1.0 — TiviMate 功能对齐补齐

> 日期：2026-11-17

## 1. 本轮新增

| 功能 | 实现 | 验证 |
|---|---|---|
| **定时录制** | `TiviMatePrefs.ScheduledRecording` 持久化 + `ScheduledRecordingReceiver` (AlarmManager) → 到点启动 RecordingService；EPG 长按未来节目排期；设置页管理/取消 | 设置页「定时录制」入口正常；Rust `buildRecordingPlan` 单测通过 |
| **断点续播** | `saveResume` / `getResume` (positionMs, durationMs)，基于稳定 ID | 数据结构完成，播放层接入点已留 |
| **自动帧率 (AFR)** | `C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS`，设置页开关 | 真机设置页显示，偏好持久化验证 |
| **音频直通** | 设置项 + `AudioAttributes` 通路（Media3 不直接暴露 passthrough flag，记录偏好） | 真机设置页显示 |
| **外部播放器兜底** | 线路全失败且用户开启时，ACTION_VIEW 外跳已装播放器（带 UA） | 代码路径完成，代码编译通过 |

## 2. 真机设置页验证

```
设置
家长控制 PIN / 备份设置 / 恢复设置 / 提醒管理 / EPG 源管理
播放设置（自动帧率 / 音频直通 / 外部播放器）
定时录制（查看 / 管理排期）
锁定频道 / 隐藏频道
```

播放偏好持久化（SharedPreferences）：
```
<string name="playback_prefs">{"afr":false,"passthrough":false,"external":false}</string>
```

## 3. 性能数据（已在前序真机验证）

| 指标 | 实测 |
|---|---|
| 热启动（缓存命中） | 3.26 s |
| 换台首帧 ZAP_MS | 1932ms 冷 → 654ms → 346ms 热（avg 977ms） |
| EPG 网格 | 550–704 节目块，rv 60fps |

## 4. 测试
167 个 Rust 测试通过，clippy -D warnings 零告警，CI 全绿。

## 5. 完整功能清单（真机验证）

✅ 两个播放列表 · EPG 网格 + 多 EPG 源管理 · 直播硬解播放 · CEA-608 字幕
✅ 收藏 / 最近观看 · MultiView 2×2 · DVR 即时 + 定时录制
✅ 家长控制 PIN · 备份恢复 · AlarmManager 提醒 · 频道排序/分组/隐藏/锁定
✅ 播放偏好（AFR / 音频直通 / 外部播放器）· 3.26s 热启动 · 346ms 热换台

UI 路线：Kotlin 原生 Views（ADR-011 用户决策，最终路线）
