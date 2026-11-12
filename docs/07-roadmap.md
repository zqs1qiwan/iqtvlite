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

### M0.2 ★ 视频层合成（**最高风险项，对应 ADR-008 / `06-android-tv.md` §1**）

**这是整个项目的成败点。** Slint 的 Android 窗口硬编码不透明 → SurfaceView 视频层无法穿透。

必须回答的问题（按顺序试）：

- [ ] **Q1（决定 P1 可行性）**：Slint 能否渲染进**我们自建的** SurfaceView（而非 `NativeActivity` 创建的那个）？
      - 途径：`slint::platform::set_platform()` 自定义 `Platform`（只有一个 required method：`create_window_adapter`）
      - 途径：`android-activity` 0.6 的 `game-activity` feature（`i-slint-backend-android-activity` 有此 feature，但**未接入 `slint` 公开 feature，兼容性未验证**）
      - 途径：fork `slint` + `android-activity`（改 `transparent` 标志 + 窗口获取）
- [ ] **Q2**：视频 SurfaceView 与 Slint 层的 Z-order 怎么排？
      - API 36+：`setCompositionOrder(-2)`；API 30–35：`setZOrderMediaOverlay(true)`
      - 同值时相对顺序**未定义** → 必须保证数值不同
- [ ] **Q3（无论选哪条路都要做）**：ExoPlayer 解码到 `SurfaceView`，全屏时控制层不消失
      - 用 `dumpsys SurfaceFlinger --list` / Tracer 验证 layer 层级
- [ ] **Q4**：P2 路径的降级验证 —— 原生 Activity（SurfaceView + ExoPlayer）+ 200 行 OSD 是否可用

**三条路线的判定标准**：

| 路线 | 通过标准 | 不通过的降级 |
|---|---|---|
| P1 | Q1 = 是，且 Q2/Q3 通过 | 转 P2 |
| P2 | Q4 通过 | 转 P4（Compose TV） |
| P3 | — | 仅作为最后手段（丢 HDR/DRM） |

### M0.3 双引擎原型

- [ ] ExoPlayer **1.11.1** 播放 HLS + MPEG-TS，测首帧时间
- [ ] **MPEG-TS 无 AUD / 无 IDR 的两个 flag**（`FLAG_DETECT_ACCESS_UNITS` / `FLAG_ALLOW_NON_IDR_KEYFRAMES`）—— 不开就会卡在 buffering
- [ ] LoadControl 调优（1500/6000/500/1000）+ `setTargetOffsetMs(3000)` + OkHttp 保温连接池
- [ ] libmpv AAR 接入，Rust 侧 `rsmpv` 驱动，Render API 嵌 EGL
- [ ] 验证 `hwdec=mediacodec`（**不是** `mediacodec-copy`，copy 路径正是 Mali 绿屏根源）
- [ ] mpv 兜底路径：人为触发 ExoPlayer 失败 → 自动切 mpv → 成功
- [ ] 确认 mpv 在目标 TV 盒子上的实际表现（**可能需要接受它在某些 GPU 上不可用**）

### M0.4 中文字体与焦点

- [ ] 内置中文字体，通过 `slint::fontique_011::shared_collection()` 注册 → 中文频道名**无豆腐块**
- [ ] 媒体按键（`MediaPlayPause` / `ChannelUp` / `ChannelDown` / `Info`）在 Kotlin 层拦截 → 转发 Rust
- [ ] `Key.Back` **必须被消费**（U+F748），否则 Android 直接退出应用
- [ ] 自研焦点层：网格坐标 + 行列步进（Slint 无空间导航，源码已确认）

### M0.5 结论
产出 `docs/spikes/` 下的实测报告 + 拍板 ADR-008。

**退出条件**：一段真实 IPTV 流能在真机上稳定播放 10 分钟，遥控器全程可用，冷启动 < 1.5 s，中文无豆腐块。

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
| **Slint 窗口不透明 → 视频层无法穿透** | 架构重做 | M0.2 优先 spike，四条路径并行验证 | P2（原生播放屏）→ P4（Compose TV） |
| **中文豆腐块**（国产 ROM fonts.xml 缺陷） | 中文 UI 不可用 | 内置中文字体 + fontique 运行时注册 | 无（必须解决） |
| **媒体按键被 Slint 丢弃** | 遥控器 play/pause/频道键失效 | Kotlin 层拦截转发 | 无（必须解决） |
| **Mali 驱动的 GL context 泄漏**（slint#14397，1.85 MB/次） | 长跑内存增长 | 监测内存；必要时用 `renderer-skia-opengl` 而非 wgpu | 降低 UI 重绘频率 |
| **mpv 在目标 GPU 上不可用** | 兜底路径失效 | 明确告知用户「该内容需要兼容播放器」 | 只发 `lite` 变体，长尾交给外部播放器 |
| **mpv 捆绑增加 Play 拒审风险** | 上不了架 | 默认发 `lite` 变体（无 libmpv） | `full` 变体走 F-Droid / GitHub |
| **供应商限流导致起播失败** | 用户体感差 | UA 伪装 + 连接节流 + 故障切换组 + 自动退避 | 提供「备用线路」配置 |
| **Slint 焦点手感做不对** | TV 体验差 | 早期真机测试，网格化焦点模型 | 用现成 TV UI 模板规范 |
| **体积超标** | 定位失守 | CI 体积门禁 + `lite` 变体 | 放弃 Skia（换 egui，需重估焦点成本） |
| **GPL 分发义务处理不当** | 法律风险 | 固定版本 + 生成 notices + 附源码 tarball | 改发 `lite` 变体（不含 mpv） |
| **M3U/XMLTV 供应商格式千奇百怪** | 解析失败 | 宽松解析 + 未知属性保留 + fixture 库 | 让用户手工编辑 |
| **EPG 匹配串台**（同名频道跨国家） | 节目单错乱 | 有 id 的频道绝不用名字匹配 + 手动修正器 | 只显示 id 匹配的节目 |

---

## 待办：立刻可做的调研项

- [ ] 采集 20+ 份真实供应商的 M3U / XMLTV / `player_api.php` 响应做 fixture（**脱敏**）
- [ ] 确认目标 TV 盒子型号（Mali vs Amlogic vs Realtek）→ 决定 mpv 可用性预期
- [ ] 确认 `crispy-*` crate 质量，决定协议层自研 vs 复用
- [ ] 确认 Slint 1.18.1 的 `backend-android-activity-06` 具体 API（读 changelog / 源码）
