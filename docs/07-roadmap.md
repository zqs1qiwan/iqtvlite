# 07 — 里程碑与验收标准

> 原则：**每个里程碑都必须能在真机上跑起来**。不接受「只有单测通过」的里程碑。
> 每个里程碑结束必须产出**可对比的基准数据**（启动时间、内存、APK 体积）。

---

## M0 — Spike：技术可行性验证（**最高优先级**）

> **这一步不通过，整个架构要重做。所以先做这个。**

### M0.1 Slint + Android TV 基础链路
- [ ] Slint 1.18 编译到 `aarch64-linux-android`，产出 `.so` 并被 APK 加载
- [ ] Kotlin `NativeActivity` 壳 + Rust cdylib 桥接打通
- [ ] 遥控器 D-pad / OK / BACK 事件到达 Slint
- [ ] 验证 D-pad → `Key.*Arrow` 映射（参考 rusttv 结论）
- [ ] **在真实 Android TV 盒子/Chromecast with Google TV 上**测量冷启动、空闲 PSS、APK 体积

### M0.2 ★ 视频层合成（**最高风险项，对应 ADR-008**）
- [ ] ExoPlayer 解码到 `SurfaceView`
- [ ] Slint UI 与 SurfaceView 正确叠放，控制条在全屏时**不消失**
- [ ] 对比三种方案并选型：
  - (a) SurfaceView + 独立透明原生窗口
  - (b) TextureView + Slint 同窗
  - (c) Rust UI 作为 View 嵌入 Activity 布局（`rust-mobile/android-view`）
- [ ] 用 `dumpsys SurfaceFlinger --list` / Tracer 验证 layer 层级

### M0.3 双引擎原型
- [ ] ExoPlayer 播放 HLS + MPEG-TS，测首帧时间
- [ ] libmpv AAR 接入，Rust 侧 `libmpv2` 驱动，Render API 嵌 EGL
- [ ] mpv 兜底路径：人为触发 ExoPlayer 失败 → 自动切 mpv → 成功
- [ ] 确认 mpv 在目标 TV 盒子上的实际表现（**可能需要接受它在某些 GPU 上不可用**）

### M0.4 结论
产出 `docs/spikes/` 下的实测报告 + 拍板 ADR-008 / ADR-010。

**退出条件**：一段真实 IPTV 流能在真机上稳定播放 10 分钟，遥控器全程可用，冷启动 < 1.5 s。

---

## M1 — 内核：数据层与协议层

- [ ] `iqtvlite-core`：领域模型 + 稳定 ID（xxhash3）
- [ ] M3U/M3U+ 解析器（含全部 `#EXTINF` 属性）
- [ ] Xtream Codes 客户端（`player_api.php` 全端点）
- [ ] XMLTV **流式**解析（必须处理 100 MB+ 文件，内存不爆）
- [ ] `iqtvlite-store`：schema + FTS5 + 迁移机制
- [ ] 15 万频道导入/更新性能基准
- [ ] 单元测试 + fixture 快照测试

**验收**：
- 导入 15 万频道的 M3U：< 30 s，内存峰值 < 300 MB
- XMLTV 50 MB 流式解析：峰值内存 < 200 MB
- 重新同步后收藏/历史**零丢失**（稳定 ID 验证）

---

## M2 — 播放基础（单源直播）

- [ ] `iqtvlite-player`：Player trait + Rust 侧状态机
- [ ] C-ABI（Rust ↔ Kotlin）
- [ ] ExoPlayer 后端：播放/暂停/seek/音量/音轨/字幕
- [ ] 媒体会话 + 音频焦点 + 媒体键
- [ ] 错误分类与用户指引（UA 拦截 / 限流 / 格式不支持 / 鉴权失败）
- [ ] 用户可切换 UA（TiviMate / VLC / 自定义）
- [ ] 连接复用 + 换台节流

**验收**：
- 换台 P50 < 1.2 s
- 故意断网 → 有明确错误文案与「重试 / 换线路 / 换 UA」操作入口
- 连续换台 50 次无内存泄漏

---

## M3 — 电视 UI 骨架 + 频道浏览

- [ ] Slint 主题系统（深色、Material 3 变体）
- [ ] `iqtvlite-tvfocus`：规则网格焦点模型
- [ ] 频道列表（虚拟化，15 万行 60 fps）
- [ ] 分类栏 + 收藏夹 + 搜索
- [ ] 焦点环、焦点跟随滚动、遥控器操作反馈
- [ ] 中文字体渲染验证（CJK 排版/字重/回退）

**验收**：
- 15 万频道列表滚动无掉帧
- 焦点在所有列表间循环无死路
- 中文频道名无豆腐块

---

## M4 — EPG（核心差异化）

- [ ] EPG 同步调度（后台、增量、**绝不阻塞启动**）
- [ ] EPG 网格渲染（行=频道，列=时间片）
- [ ] 当前节目进度条、now/next
- [ ] 节目详情弹层
- [ ] 节目搜索（跨频道搜节目名）
- [ ] 提醒（Reminder）
- [ ] 节目表 → 播放 / → 录制

**验收**：
- 冷启动**不等待** EPG，UI 立即可浏览
- EPG 网格滚动 60 fps（100 频道 × 6 小时视图）
- 换源后 EPG 正确重绑定

---

## M5 — 高级功能

- [ ] Catch-up / 时移（供应商支持时）
- [ ] DVR 录制（即时 + 排期）
- [ ] 多画面 2×2
- [ ] 家长控制（PIN）
- [ ] 备份 / 还原
- [ ] 多播放列表 + 多 EPG 源 + 合并
- [ ] 断点续播
- [ ] 频道管理（隐藏/重命名/排序/自定义分组）

---

## M6 — 打磨与发布

- [ ] 性能调优（启动、体积、内存三线并进）
- [ ] 稳定性（长跑 8 小时播放、异常流 fuzz）
- [ ] 无障碍、国际化（至少 中/英）
- [ ] 崩溃报告（自建、无遥测外传）
- [ ] Play Console 上架
- [ ] `iqtvlite-lite`（无 mpv）变体拆分

---

## 里程碑依赖图

```
M0.1 ─┬─▶ M0.2 ─▶ M0.3 ─┐
      │                  │
M1 ───┴──────────────────┼──▶ M2 ──▶ M3 ──▶ M4 ──▶ M5 ──▶ M6
                         │
                    (M0.4 拍板)
```

---

## 风险与应对（汇总）

| 风险 | 影响 | 应对 | 兜底 |
|---|---|---|---|
| **Slint + SurfaceView 合成失败** | 架构重做 | M0.2 优先 spike，三方案并行验证 | 降级到 (b) TextureView，或 UI 层改用 Compose |
| **mpv 在目标 GPU 上不可用** | 兜底路径失效 | 明确告知用户「该内容需要 mpv 变体」 | 只发 `lite` 变体，长尾交给外部播放器 |
| **供应商限流导致起播失败** | 用户体感差 | UA 伪装 + 连接节流 + 故障切换组 | 提供「备用线路」配置 |
| **Slint 焦点手感做不对** | TV 体验差 | 早期真机测试，参考 rusttv | 用现成 TV UI 模板规范 |
| **体积超标** | 定位失守 | CI 体积门禁 + `lite` 变体 | 放弃 Skia（换 egui，需重估焦点成本） |
| **GPL 分发义务处理不当** | 法律风险 | 固定版本 + 生成 notices + 附源码 tarball | 改发 `lite` 变体（不含 mpv） |
| **M3U/XMLTV 供应商格式千奇百怪** | 解析失败 | 宽松解析 + 未知属性保留 + fixture 库 | 让用户手工编辑 |

---

## 待办：立刻可做的调研项

- [ ] 采集 20+ 份真实供应商的 M3U / XMLTV / `player_api.php` 响应做 fixture（**脱敏**）
- [ ] 确认目标 TV 盒子型号（Mali vs Amlogic vs Realtek）→ 决定 mpv 可用性预期
- [ ] 确认 `crispy-*` crate 质量，决定协议层自研 vs 复用
- [ ] 确认 Slint 1.18.1 的 `backend-android-activity-06` 具体 API（读 changelog / 源码）
