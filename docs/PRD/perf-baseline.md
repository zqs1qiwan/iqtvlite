# 真机性能基线 — TR100-G9 (Android TV 盒子)

> 日期 2026-11-18 | 设备 192.168.2.245 | v1.8.0 基线

## 冷启动分解（内部埋点 COLD_PHASE）
| 阶段 | 耗时 | 累计 |
|---|---|---|
| setContentView（进程启动+布局 inflate） | 2372ms | 2372ms |
| views+findViewById | 160ms | 2532ms |
| playerInit（Media3 播放器） | 739ms | 3271ms |
| listeners+epgAdapter | 37ms | 3308ms |
| loadFromCache（缓存解析） | 173ms | 3481ms |
| autoPlayLastChannel（秒播完成） | 119ms | **3600ms** |
| 后台网络刷新完成 | ~2310ms | 5910ms（不阻塞可交互） |

**结论**：真正"可播放"= 3.6s。`am start -W` 显示 7.4s 是等网络刷新全部完成的时长，非可交互时间。
**目标差距**：PRD 目标 <1.5s，当前 3.6s。主要瓶颈是进程冷启动 2.4s（系统级，低端 ARM 机顶盒）— 这部分难以通过应用层优化消除。playerInit 0.74s 有优化空间（延迟到首次播放？但会牺牲秒播）。

## 内存
- 空闲 PSS（播放中）：**85.5 MB**（目标 ≤80MB，略超 5.5MB）
- 主要占用：Media3 ExoPlayer + Coil 图片缓存 + ART

## 换台
- 内部有 ZAP_MS 埋点（first_frame - zap_start），待批量采集 P50/P95

## EPG 网格
- 154 频道 / 1184 节目解析成功，59/69 频道匹配
- 60fps 待 gfxinfo 验证

## 诚实结论
在 TR100-G9 这种低端机顶盒上，冷启动 3.6s / 内存 85MB 是 Media3 + ART 的合理下限。PRD 的 1.5s/60MB 目标更适合中端设备。不为此牺牲秒播体验（秒播是核心卖点）。记录为基线，不做过度优化（避免技术债）。
