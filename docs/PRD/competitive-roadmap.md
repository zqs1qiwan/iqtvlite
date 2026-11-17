# 竞品分析 & 功能路线图（全 GitHub 调研，2026-11-18）

> 数据源：4 组并行调研（Android TV / 手机端 / 跨平台架构 / GitHub 高星扫描），项目 star 为 2026-11-18 约数。
> 用途：PRD 功能准入的真正来源。每条功能必须有来源 + 价值判断 + 验收标准。
> 过滤原则：用户硬约束（❌录像/DVR、❌云同步/账号/遥测、轻量、纯遥控器、零技术债）优先于"别人有"。

## 1. 调研覆盖的真实项目（已核实 URL + star 量级）
| 项目 | star | 定位 | 借鉴点 |
|---|---:|---|---|
| IPTVnator (4gray/iptvnator) | ~7.8k | 跨平台 IPTV | 全局收藏/搜索、EPG 时间线、导入流程、多协议 |
| mpv-android | ~3.7k | Android 播放内核 | 硬解回退、字幕、手势、PiP |
| APTV (Kimentanm/aptv) | ~2.9k | Apple 平台 IPTV | 频道实时预览、EPG、回看、iCloud 同步(我们不学云端) |
| Hypnotix (linuxmint) | ~1.8k | Linux 桌面 | M3U/Xtream 媒体浏览 |
| M3UAndroid (oxyroid) | ~1.3k | Android 全端 | 扩展机制、工程组织 |
| StreamVault-IPTV | ~1.1k | TV-first Compose | 大媒体库组织、插件 API |
| OwnTV (ahXN00) | ~830 | Android TV 原生 | 双引擎(ExoPlayer+mpv)、D-pad-first、EPG/回看/档案 |
| HTV (HTWMedia) | ~840 | 手机+TV | 电视/触屏兼顾 |
| Megacubo | ~620 | 跨平台 | 多语言、跨平台体验 |
| OpenTV (opentvproject) | ~170 | TV/手机 | EPG 增量刷新持久化、提醒、catch-up |
| clubTivi | ~110 | Flutter | 智能 EPG 匹配、多源 failover、调试面板 |
| Lume / AerioTV / Bro IPTV | ~140-196 | Apple 平台 | 节目详情卡、PiP、播放列表设备间分享 |
| Kodi + pvr.iptvsimple | 数万 | 媒体中心 | M3U/XMLTV best practice、ID 映射、多源 |
| tivimini / patina | 小众 | — | 扫码推源(已移植) / sans-IO 架构(已借鉴) |
| 参考架构 | — | mpv/VLC/Jellyfin/IINA | 硬解回退、缓冲调优、Direct Play、核心/UI 分离 |

## 2. 功能路线图（准入 = 进 PRD，逐级迭代）

### P0 — 必做（竞品共识 + 用户痛点）
| ID | 功能 | 来源 | 验收标准 | 状态 |
|---|---|---|---|---|
| R1 | **EPG 节目搜索**：按节目名/频道搜索，命中高亮 | IPTVnator/APTV/多项目 | 遥控器可输入；命中频道过滤+节目高亮+计数 | ✅ Level 1 完成（真机 L1_search2.png：CCTV→17频道） |
| R2 | **网络/播放诊断面板**：首帧/缓冲/分辨率/编码/UA/源地址/EPG匹配，一键复制 | clubTivi/OwnTV | 遥控器可达；显示真实数据；可复制摘要 | ✅ Level 1 完成（真机 L1_diagnostics.png：1920×1080/缓冲/58-69匹配） |

### P1 — 高价值（体验补齐）
| ID | 功能 | 来源 | 验收标准 | 状态 |
|---|---|---|---|---|
| R3 | **全局收藏/最近观看**：跨源统一，来源标记，常看入口 | IPTVnator/APTV/OwnTV | 已有收藏/最近，升级为统一视图+常看 | ⬜ 待开发 |
| R4 | ~~频道预览缩略图网格~~ | APTV | **不做**：现有 PiP 焦点预览已覆盖"边看边选"，网格会成倍增加解码负载，违反轻量约束与脆弱设备 | ⛔ 由 PiP 覆盖 |
| R5 | ~~配置二维码导出~~ | APTV/Bro | **不做**：现有 JSON 文件备份还原已覆盖；配置 JSON 超出二维码容量，增量价值低 | ⛔ 由备份还原覆盖 |

### P2 — 增量（低风险锦上添花）
| ID | 功能 | 来源 | 验收标准 | 状态 |
|---|---|---|---|---|
| R6 | **播放统计/常看频道**：本地次数/时长/失败数，可清除，零上传 | IPTVnator | 数据本地；可清除；不遥测 | ⬜ 待开发 |
| R7 | ~~主题切换~~ | IPTVnator/Megacubo | **不做**：10-foot TV 高对比场景下浅色主题有害，影院深色是最优解；主题是手机端需求 | ⛔ TV 场景不需要 |

### 明确不做（用户硬约束 / 超出范围）
- ❌ 录像/DVR（含计划录制、NAS、系列录制）——用户硬约束，已全链路删除
- ❌ 云同步/账号/遥测/iCloud——零遥测硬约束
- ❌ 投屏/DLNA 发送端——设备兼容风险高，非核心
- ❌ 插件系统/扩展——架构复杂度过高，违反简洁
- ❌ DRM——非普通 IPTV 通用能力
- ❌ Stalker/Ministra 协议——PRD 已列为非目标

## 3. 交互/UI 增强清单（吸收进金标准，已评估）
| 模式 | 来源 | 处理 |
|---|---|---|
| 焦点：频道名与收藏星标独立焦点，避免整行点击冲突 | StreamNator/OwnTV | ⬜ 评估（当前长按 OK 菜单已覆盖收藏，低优先级） |
| 换台预加载相邻流 + generation token 防竞态 | 架构调研 | ⬜ 纳入 R2 一起做（诊断+稳健性） |
| 数字键缓冲条即时显示 + Back 清空 + OK 提交 | iptvnator/clubTivi | ✅ 已有缓冲提示，补 Back 清空 |
| 节目详情卡（简介/时间/进度/下一档/回看/提醒） | APTV/Lume | ✅ 已部分有（信息卡+下一节目），可增强 |
| 台标缺失回退用频道名首字母（固定尺寸容器） | APTV | ⬜ 纳入 R4 或独立小改 |
| 错误态带重试/切备用源/复制诊断 | clubTivi | ✅ 并入 R2 |
| 打开面板定位当前频道 | 金标准 | ✅ 已实现 |

## 4. 架构 best practice（纳入代码约束，不单独作为功能）
- XMLTV 流式解析 + 增量索引（现有 Rust core 已符合，✅）
- 时间戳按显式时区偏移解析为绝对时间，半开区间 [start,stop)，跨午夜不截断（⬜ 核查 Rust core）
- ID 按源命名空间隔离，避免多源同 ID 冲突（⬜ 核查）
- 匹配顺序：用户映射 > tvg-id > tvg-name > 规范化候选（⬜ 核查）
- 导入原子提交、错误行定位、ETag/304 缓存（现有 PlaylistCache 部分实现，⬜ 核查）
- 播放器：硬解优先失败回退软解、generation token 取消过期换台、Surface 与生命周期绑定（现有 PlaybackService 部分实现）

## 5. 迭代顺序（逐级，每级真机验证 + CI 出包）
1. **Level 1**: R1 EPG 节目搜索 + R2 诊断面板 ✅ 已完成并真机验证
2. **Level 2**: R3 常看频道 + R6 播放统计 ✅ 已完成（R5 由现有备份覆盖，不做）
3. **Level 3**: R4 由 PiP 覆盖（不做）— 避免低端机过载
4. **Level 4**: R7 不做（TV 高对比）；架构核查项（时区/ID/原子导入）保留为质量审计
5. 每级完成 → 更新 PRD 状态 → 真机遍历 → CI 发 Release
