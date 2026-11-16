# 0.8 — 家长控制 / 备份恢复 / 提醒 / 排序分组 / 性能实测

> 日期：2026-11-17

## 1. 新增 TiviMate 功能

| 功能 | 实现 | 真机验证 |
|---|---|---|
| **家长控制 PIN** | 设置页设置 4 位 PIN；锁定频道播放前要求输入 | PIN 对话框正常显示 |
| **锁定/隐藏频道** | 频道长按菜单切换；隐藏频道从列表过滤 | 设置页显示计数 |
| **提醒** | 长按菜单「设置提醒」；设置页可查看/删除 | 设置页提醒管理可用 |
| **自定义分组** | 长按「移动到分组…」；按 tvgId 覆盖分组 | 持久化到 SharedPreferences |
| **自定义排序** | 保存 id 顺序列表，加载时应用 | 持久化 |
| **备份/恢复** | 全部配置导出/导入 JSON | ✅ 真机导出成功（见下） |

### 备份文件真机输出
```json
{
  "version": 1,
  "parental_pin": null,
  "locked_channels": [],
  "hidden_channels": [],
  "channel_order": [],
  "custom_groups": {},
  "reminders": []
}
```

## 2. 性能实测（TR100-G9 Android TV 盒子）

### 2.1 冷启动（关键改进）

| 场景 | 之前 | 之后 | 改善 |
|---|---|---|---|
| **首次启动**（无缓存） | 35.8s | 35.8s（网络下载 playlist） | — |
| **二次启动**（缓存命中） | 35.8s | **3.26s** | **11x** |

实现：`PlaylistCache` 启动时同步读缓存 → 频道列表立即可交互；后台拉网络 → 成功后刷新 + 更新缓存；网络失败继续用缓存（离线可用）。

真机日志：
```
首次: COLD_START_CACHE hit=false ms=2978
      PLAYLIST_APPLIED channels=91 fromCache=false COLD_START_MS=35801
二次: PLAYLIST_APPLIED channels=91 fromCache=true  COLD_START_MS=3262
      COLD_START_CACHE hit=true ms=3262
```

### 2.2 换台
已埋点 `ZAP_MS`（首帧 - 发起播放），Logcat 输出 avg/max/count，待稳定后持续调优。

## 3. 测试
167 个 Rust 测试通过，clippy -D warnings 零告警，CI 全绿。
